package com.exio.inkleaf.plugin

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Runs plugin-declared source actions for every surface that offers them. After an action the
 * runner records its effect on the [SourceSessionHint] and drops the source's derived data, so no
 * caller can skip either step.
 */
internal class SourceActionRunner(
    private val invoke: suspend (pluginId: String, PluginActionRequest) -> JsonElement,
    private val fetchOptions: suspend (pluginId: String, PluginActionRequest) -> PluginActionOptions,
    private val sessionHint: SourceSessionHint,
    private val invalidateDerivedData: suspend (pluginId: String) -> Unit,
) {
    /**
     * Runs [action], against the comic [sourceId] when it is comic-scoped, with the chosen
     * [optionId] when it asks for one. Returns the message to show the user.
     */
    suspend fun run(
        pluginId: String,
        action: PluginActionDescriptor,
        sourceId: String? = null,
        optionId: String? = null,
    ): String {
        val result = invoke(pluginId, request(action, sourceId, optionId))
        sessionHint.applyOutcome(pluginId, action, result)
        invalidateDerivedData(pluginId)
        // Only a top-level message has a defined UI representation.
        return ((result as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: "${action.title}：操作完成"
    }

    /** The runtime choices [action] offers for [sourceId], such as an account's folders. */
    suspend fun options(
        pluginId: String,
        action: PluginActionDescriptor,
        sourceId: String? = null,
    ): PluginActionOptions = fetchOptions(pluginId, request(action, sourceId, optionId = null))

    private fun request(action: PluginActionDescriptor, sourceId: String?, optionId: String?) =
        PluginActionRequest(
            actionId = action.id,
            input =
                buildJsonObject {
                    sourceId?.let { put("sourceId", it) }
                    optionId?.let { put(PluginActionDescriptor.OPTION_INPUT_ID, it) }
                },
        )
}
