package com.exio.inkleaf.data.enhancement

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.asDeferred
import java.util.concurrent.atomic.AtomicReference

internal enum class EnhancementPersistenceRequirement {
    TRANSIENT,
    PINNED,
}

/** Exposes requirements that attached callers may promote while a page job is running. */
internal interface EnhancementPageJobContext {
    val priority: EnhancementRequestPriority
    val persistenceRequirement: EnhancementPersistenceRequirement
}

/**
 * Serializes enhancement page production while sharing one result for each page identity.
 *
 * The producer belongs to the coordinator scope, so cancellation of any requesting coroutine only
 * stops that caller from awaiting the result. The first producer registered for a key is the only
 * producer executed; later requests attach to its result and may promote queued priority or the
 * persistence requirement. Once the supplied scope completes, pending and future requests fail
 * with cancellation; retrying requires a new coordinator with an active scope.
 */
internal class EnhancementPageJobCoordinator<T>(
    scope: CoroutineScope,
) {
    private data class ProducedPage<T>(
        val job: PageJob<T>,
        val value: T,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private class PageJob<T>(
        val key: EnhancementPageKey,
        initialPriority: EnhancementRequestPriority,
        initialPersistence: EnhancementPersistenceRequirement,
        val sequence: Long,
        val producer: suspend EnhancementPageJobContext.() -> T,
        val finalizer: suspend (T, EnhancementPersistenceRequirement) -> T,
        val discardProducer: () -> Unit,
        initialRetainWithoutWaiters: Boolean,
    ) : EnhancementPageJobContext {
        private val promotedPriority = AtomicReference(initialPriority)
        private val persistence = AtomicReference(initialPersistence)

        override val priority: EnhancementRequestPriority
            get() = promotedPriority.get()

        private val completion = CompletableDeferred<T>()

        // Callers can await a page but cannot complete coordinator-owned work themselves.
        val result: Deferred<T> = completion.asDeferred()
        var producerStarted = false
        var waiterCount = 0
        var retainWithoutWaiters = initialRetainWithoutWaiters

        override val persistenceRequirement: EnhancementPersistenceRequirement
            get() = persistence.get()

        fun promote(requested: EnhancementRequestPriority) {
            while (true) {
                val current = promotedPriority.get()
                if (requested.rank >= current.rank) return
                if (promotedPriority.compareAndSet(current, requested)) return
            }
        }

        fun require(required: EnhancementPersistenceRequirement) {
            if (required == EnhancementPersistenceRequirement.PINNED) {
                persistence.set(EnhancementPersistenceRequirement.PINNED)
            }
        }

        fun complete(value: T) {
            completion.complete(value)
        }

        fun completeExceptionally(error: Throwable) {
            completion.completeExceptionally(error)
        }
    }

    private val stateLock = Any()
    private val coordinatorJob = SupervisorJob()
    private val coordinatorScope = CoroutineScope(scope.coroutineContext + coordinatorJob)
    private val jobs = mutableMapOf<EnhancementPageKey, PageJob<T>>()
    private val queuedJobs = mutableListOf<PageJob<T>>()
    private val producedPages = Channel<ProducedPage<T>>(Channel.RENDEZVOUS)
    private var nextSequence = 0L
    private var dispatcherRunning = false
    private var closedCause: CancellationException? = null

    init {
        coordinatorScope.launch { finalizeProducedPages() }
        scope.coroutineContext[Job]?.invokeOnCompletion { cause ->
            val cancellation = cause.asCoordinatorCancellation()
            closeFromScope(cancellation)
            coordinatorJob.cancel(cancellation)
        }
        coordinatorJob.invokeOnCompletion { cause -> closeFromScope(cause) }
    }

    suspend fun request(
        key: EnhancementPageKey,
        priority: EnhancementRequestPriority,
        persistenceRequirement: EnhancementPersistenceRequirement,
        retainWithoutWaiters: Boolean = false,
        discardProducer: () -> Unit = {},
        finalizer: suspend (T, EnhancementPersistenceRequirement) -> T = { value, _ -> value },
        producer: suspend EnhancementPageJobContext.() -> T,
    ): T {
        var startDispatcher = false
        var discardAttachedProducer = false
        val job = try {
            synchronized(stateLock) {
                closedCause?.let { throw it }
                jobs[key]?.also { existing ->
                    existing.promote(priority)
                    existing.require(persistenceRequirement)
                    if (retainWithoutWaiters) existing.retainWithoutWaiters = true
                    existing.waiterCount += 1
                    discardAttachedProducer = true
                } ?: PageJob(
                    key = key,
                    initialPriority = priority,
                    initialPersistence = persistenceRequirement,
                    sequence = nextSequence++,
                    producer = producer,
                    finalizer = finalizer,
                    discardProducer = discardProducer,
                    initialRetainWithoutWaiters = retainWithoutWaiters,
                ).also { created ->
                    created.waiterCount = 1
                    jobs[key] = created
                    queuedJobs += created
                    if (!dispatcherRunning) {
                        dispatcherRunning = true
                        startDispatcher = true
                    }
                }
            }
        } catch (error: Throwable) {
            runCatching(discardProducer)
            throw error
        }
        if (discardAttachedProducer) runCatching(discardProducer)

        if (startDispatcher) {
            coordinatorScope.launch { dispatchQueuedJobs() }
        }
        return try {
            job.result.await()
        } finally {
            detachWaiter(job)
        }
    }

    private fun detachWaiter(job: PageJob<T>) {
        var discard: (() -> Unit)? = null
        synchronized(stateLock) {
            if (jobs[job.key] !== job) return
            job.waiterCount = (job.waiterCount - 1).coerceAtLeast(0)
            if (
                job.waiterCount == 0 &&
                !job.producerStarted &&
                !job.retainWithoutWaiters
            ) {
                jobs.remove(job.key)
                queuedJobs.remove(job)
                discard = job.discardProducer
            }
        }
        discard?.let { runCatching(it) }
    }

    private suspend fun dispatchQueuedJobs() {
        while (true) {
            val next = synchronized(stateLock) {
                if (closedCause != null) return
                val selected = queuedJobs.minWithOrNull(
                    compareBy<PageJob<T>>({ it.priority.rank }, { it.sequence })
                )
                if (selected == null) {
                    dispatcherRunning = false
                } else {
                    queuedJobs.remove(selected)
                    selected.producerStarted = true
                }
                selected
            } ?: return

            val produced = try {
                currentCoroutineContext().ensureActive()
                Result.success(next.producer(next))
            } catch (error: Throwable) {
                Result.failure(error)
            }

            val producedValue = produced.getOrElse { error ->
                synchronized(stateLock) {
                    if (closedCause == null && jobs[next.key] === next) {
                        next.completeExceptionally(error)
                        jobs.remove(next.key)
                    }
                }
                continue
            }

            producedPages.send(ProducedPage(next, producedValue))
        }
    }

    private suspend fun finalizeProducedPages() {
        for ((next, producedValue) in producedPages) {
            while (true) {
                val finalizedFor = next.persistenceRequirement
                val finalized = try {
                    Result.success(next.finalizer(producedValue, finalizedFor))
                } catch (error: Throwable) {
                    Result.failure(error)
                }
                val published = synchronized(stateLock) {
                    if (closedCause != null || jobs[next.key] !== next) {
                        true
                    } else if (finalized.isFailure) {
                        next.completeExceptionally(finalized.exceptionOrNull()!!)
                        jobs.remove(next.key)
                        true
                    } else if (next.persistenceRequirement == finalizedFor) {
                        next.complete(finalized.getOrThrow())
                        jobs.remove(next.key)
                        true
                    } else {
                        false
                    }
                }
                if (published) break
            }
        }
    }

    private fun closeFromScope(cause: Throwable?) {
        val cancellation = cause.asCoordinatorCancellation()
        val discarded = mutableListOf<() -> Unit>()
        synchronized(stateLock) {
            if (closedCause != null) return
            closedCause = cancellation
            dispatcherRunning = false
            queuedJobs.filterNot { it.producerStarted }.forEach { discarded += it.discardProducer }
            jobs.values.forEach { it.completeExceptionally(cancellation) }
            jobs.clear()
            queuedJobs.clear()
        }
        discarded.forEach { discard -> runCatching(discard) }
    }
}

private fun Throwable?.asCoordinatorCancellation(): CancellationException =
    this as? CancellationException
        ?: CancellationException("Enhancement page job coordinator scope is closed").also {
            if (this != null) it.initCause(this)
        }

private val EnhancementRequestPriority.rank: Int
    get() = when (this) {
        EnhancementRequestPriority.CURRENT_PAGE -> 0
        EnhancementRequestPriority.PREFETCH -> 1
        EnhancementRequestPriority.BULK_CACHE -> 2
    }
