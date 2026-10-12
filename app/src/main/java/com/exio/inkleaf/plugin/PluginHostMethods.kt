package com.exio.inkleaf.plugin

import kotlinx.serialization.json.JsonElement

/** How the generated JS wrapper forwards its arguments to one host method. */
internal sealed interface PluginHostJsArgs {
    /** Source text for the wrapper's parameter list. */
    val params: String

    /** Source text for the request object the wrapper sends. The abort signal is added after it. */
    val requestArgs: String

    /** `function (request, signal) { return hostCall(name, request, signal); }` */
    data object Request : PluginHostJsArgs {
        override val params: String = "request, signal"
        override val requestArgs: String = "request"
    }

    /** `function (signal) { return hostCall(name, {}, signal); }` */
    data object Signal : PluginHostJsArgs {
        override val params: String = "signal"
        override val requestArgs: String = "{}"
    }

    /** `function (id, signal) { return hostCall(name, { id: id }, signal); }` */
    data class Field(val name: String) : PluginHostJsArgs {
        override val params: String = "$name, signal"
        override val requestArgs: String = "{ $name: $name }"
    }

    /** `function (level, message, fields, signal) { ... }` */
    data object Log : PluginHostJsArgs {
        override val params: String = "level, message, fields, signal"
        override val requestArgs: String = "{ level: level, message: message, fields: fields || {} }"
    }
}

/**
 * One host capability.
 *
 * [name] is the single name both sides of the seam agree on: the Kotlin dispatch looks methods up
 * by it, and the JS `host` object is generated from it. [jsArgs] says how the JS wrapper shapes its
 * call. [handle] decodes the typed request and produces the JSON response, and is supplied where the
 * session's private state is in scope.
 */
internal class PluginHostMethod(
    val name: String,
    val jsArgs: PluginHostJsArgs,
    private val handle: suspend (PluginHostSession, JsonElement) -> JsonElement,
) {
    suspend fun invoke(session: PluginHostSession, params: JsonElement): JsonElement =
        handle(session, params)
}

/**
 * Renders the `host` object a plugin is handed.
 *
 * The catalogue is the only source of truth: a capability added there appears in the generated JS and
 * in the Kotlin dispatch together, so the two cannot drift into an "Unknown host method" that only
 * fires at runtime. Pure, so a JVM test can pin the generated surface without a JS engine.
 *
 * [indent] prefixes every line so the object sits flush inside the bootstrap template.
 */
internal fun renderPluginHostObject(methods: List<PluginHostMethod>, indent: String = ""): String {
    val grouped = LinkedHashMap<String, MutableList<PluginHostMethod>>()
    val loose = mutableListOf<PluginHostMethod>()
    for (method in methods) {
        val group = method.name.substringBeforeLast('.', missingDelimiterValue = "")
        if (group.isEmpty()) {
            loose += method
        } else {
            grouped.getOrPut(group) { mutableListOf() } += method
        }
    }
    val entries = buildList {
        grouped.forEach { (group, members) ->
            val body =
                members.joinToString(",\n") { method ->
                    renderWrapper(indent + "    ", method.name.substringAfterLast('.'), method)
                }
            add("$indent  $group: Object.freeze({\n$body\n$indent  })")
        }
        loose.forEach { method -> add(renderWrapper("$indent  ", method.name, method)) }
    }
    return buildString {
        append(indent).appendLine("const host = Object.freeze({")
        append(indent).appendLine("  call: hostCall,")
        entries.forEachIndexed { index, entry ->
            append(entry)
            if (index == entries.lastIndex) appendLine() else appendLine(",")
        }
        append(indent).append("});")
    }
}

private fun renderWrapper(indent: String, member: String, method: PluginHostMethod): String =
    "$indent$member: function(${method.jsArgs.params}) { " +
        "return hostCall(\"${method.name}\", ${method.jsArgs.requestArgs}, signal); }"
