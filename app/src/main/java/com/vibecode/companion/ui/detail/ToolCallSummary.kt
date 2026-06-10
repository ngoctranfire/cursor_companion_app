package com.vibecode.companion.ui.detail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Presentation helpers for tool_call stream events: a one-line summary of what
 * the tool is actually doing (the path being read, the pattern being grepped,
 * the command being run) plus bounded pretty-printed JSON for the expandable
 * raw view. Key names are best-effort — tool arg shapes aren't in the OpenAPI
 * spec, so unknown tools fall back to the first string-ish argument.
 */

private const val MAX_DETAIL_CHARS = 140
private const val MAX_PRETTY_CHARS = 2_000

private val prettyPrinter = Json { prettyPrint = true }

/** Argument keys to surface per tool name; first present non-blank key wins. */
private val TOOL_DETAIL_KEYS = mapOf(
    "read_file" to listOf("path", "target_file", "file_path"),
    "write" to listOf("path", "file_path", "target_file"),
    "edit_file" to listOf("path", "target_file", "file_path"),
    "search_replace" to listOf("path", "file_path"),
    "create_file" to listOf("path", "target_file"),
    "delete_file" to listOf("path", "target_file"),
    "run_terminal_cmd" to listOf("command"),
    "shell" to listOf("command"),
    "grep" to listOf("pattern", "query"),
    "grep_search" to listOf("pattern", "query"),
    "codebase_search" to listOf("query"),
    "file_search" to listOf("query", "pattern"),
    "glob_file_search" to listOf("glob_pattern", "pattern"),
    "list_dir" to listOf("path", "relative_workspace_path", "target_directory"),
    "web_search" to listOf("search_term", "query"),
)

/** One-line human summary of a tool invocation, or null when args carry nothing usable. */
fun toolCallDetail(name: String?, args: JsonElement?): String? {
    val obj = args as? JsonObject ?: return null
    val preferred = TOOL_DETAIL_KEYS[name?.lowercase()]
    val value = preferred?.firstNotNullOfOrNull { obj.stringValue(it) }
        ?: obj.firstStringValue()
        ?: return null
    return value.replace('\n', ' ').take(MAX_DETAIL_CHARS)
}

/** Bounded pretty-printed JSON for the expandable raw args/result view. */
fun prettyToolJson(element: JsonElement?): String? {
    if (element == null) return null
    val pretty = try {
        prettyPrinter.encodeToString(JsonElement.serializer(), element)
    } catch (_: Exception) {
        element.toString()
    }
    return if (pretty.length > MAX_PRETTY_CHARS) pretty.take(MAX_PRETTY_CHARS) + "\n…" else pretty
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.firstStringValue(): String? =
    values.firstNotNullOfOrNull { value ->
        (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
