package com.vibecode.companion.data.api

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * SSE events from GET /v1/agents/{id}/runs/{runId}/stream.
 * Event types per spec: status, assistant, thinking, tool_call,
 * interaction_update, heartbeat, result, error, done.
 *
 * Payload shapes are parsed defensively (the API is beta) — unrecognized
 * events surface as [RunStreamEvent.Unknown] instead of crashing the stream.
 */
sealed class RunStreamEvent {
    abstract val eventId: String?

    data class Status(override val eventId: String?, val runId: String?, val status: String?) : RunStreamEvent()
    data class Assistant(override val eventId: String?, val text: String) : RunStreamEvent()
    data class Thinking(override val eventId: String?, val text: String) : RunStreamEvent()
    data class ToolCall(
        override val eventId: String?,
        val callId: String?,
        val name: String?,
        val status: String?,
        /** Tool-specific JSON arguments (e.g. the path for read_file), when streamed. */
        val args: JsonElement? = null,
        /** Tool-specific JSON result, when streamed (may be truncated server-side). */
        val result: JsonElement? = null,
    ) : RunStreamEvent()

    data class Result(
        override val eventId: String?,
        val status: String?,
        val text: String?,
        val durationMs: Long?,
        val git: RunGit?,
    ) : RunStreamEvent()

    data class StreamError(override val eventId: String?, val message: String) : RunStreamEvent()
    data class Heartbeat(override val eventId: String?) : RunStreamEvent()
    data class Done(override val eventId: String?) : RunStreamEvent()
    data class Unknown(override val eventId: String?, val type: String?, val raw: String) : RunStreamEvent()

    /**
     * Emitted locally (not by the server) when the connection drops, so UIs can offer reconnect.
     * [httpCode] and [errorCode] (the error envelope's `error.code`, e.g. `invalid_last_event_id`,
     * `stream_expired`) are populated when the failure carried an HTTP response, so callers can
     * distinguish protocol errors from network blips.
     */
    data class ConnectionClosed(
        override val eventId: String?,
        val reason: String?,
        val httpCode: Int? = null,
        val errorCode: String? = null,
    ) : RunStreamEvent()
}

class RunStreamClient(
    private val sseClient: OkHttpClient,
    private val apiKeyProvider: suspend () -> String?,
    private val baseUrl: String = CursorApiClient.DEFAULT_BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Streams events for one run. The flow completes after `done` or a connection
     * failure (emitted as [RunStreamEvent.ConnectionClosed] — reconnect by collecting
     * again with the last seen event id as [lastEventId]).
     */
    fun streamRun(agentId: String, runId: String, lastEventId: String? = null): Flow<RunStreamEvent> =
        callbackFlow {
            val key = apiKeyProvider()
            if (key == null) {
                trySend(RunStreamEvent.ConnectionClosed(null, "No API key configured", null, null))
                close()
                return@callbackFlow
            }

            val request = Request.Builder()
                .url("$baseUrl/v1/agents/$agentId/runs/$runId/stream")
                .header("Authorization", "Bearer $key")
                .header("Accept", "text/event-stream")
                .apply { lastEventId?.let { header("Last-Event-ID", it) } }
                .build()

            val source = EventSources.createFactory(sseClient).newEventSource(
                request,
                object : EventSourceListener() {
                    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                        trySend(parseEvent(id, type, data))
                        if (type == "done") close()
                    }

                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                        val reason = t?.message ?: response?.let { "HTTP ${it.code}" } ?: "connection lost"
                        // Parse the error envelope's code defensively — the body may be
                        // absent, already consumed, or not JSON at all.
                        val errorCode = try {
                            response?.body?.string()?.let { body ->
                                json.parseToJsonElement(body).jsonObject["error"]
                                    ?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
                            }
                        } catch (_: Exception) {
                            null
                        }
                        trySend(RunStreamEvent.ConnectionClosed(null, reason, response?.code, errorCode))
                        close()
                    }

                    override fun onClosed(eventSource: EventSource) {
                        close()
                    }
                },
            )

            awaitClose { source.cancel() }
        }
            // History replay arrives as a burst on OkHttp's thread; the default
            // 64-slot channel would silently drop events past it (trySend is
            // unchecked). buffer() fuses into the callbackFlow's own channel.
            .buffer(Channel.UNLIMITED)

    private fun parseEvent(id: String?, type: String?, data: String): RunStreamEvent {
        val obj: JsonObject? = try {
            json.parseToJsonElement(data).jsonObject
        } catch (_: Exception) {
            null
        }

        fun str(field: String): String? = obj?.get(field)?.jsonPrimitive?.contentOrNull
        fun long(field: String): Long? = str(field)?.toLongOrNull()

        return when (type) {
            "status" -> RunStreamEvent.Status(id, str("runId"), str("status"))
            "assistant" -> RunStreamEvent.Assistant(id, str("text") ?: data)
            "thinking" -> RunStreamEvent.Thinking(id, str("text") ?: data)
            "tool_call" -> RunStreamEvent.ToolCall(
                eventId = id,
                callId = str("callId"),
                name = str("name"),
                status = str("status"),
                args = obj?.get("args"),
                result = obj?.get("result"),
            )
            "result" -> RunStreamEvent.Result(
                eventId = id,
                status = str("status"),
                text = str("text"),
                durationMs = long("durationMs"),
                git = obj?.get("git")?.let {
                    try {
                        json.decodeFromJsonElement(RunGit.serializer(), it)
                    } catch (_: Exception) {
                        null
                    }
                },
            )
            "error" -> RunStreamEvent.StreamError(id, str("message") ?: data)
            "heartbeat" -> RunStreamEvent.Heartbeat(id)
            "done" -> RunStreamEvent.Done(id)
            // interaction_update duplicates simplified events (same event id) — skip per spec.
            else -> RunStreamEvent.Unknown(id, type, data)
        }
    }
}
