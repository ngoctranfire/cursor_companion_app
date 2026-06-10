package com.vibecode.companion.data.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Client for the Cursor Cloud Agents API v1 (docs/cloud-agents-openapi.yaml).
 *
 * The API is in beta — all calls go through this adapter so a future API change
 * stays contained here. Errors surface as [CursorApiException] with the
 * machine-readable `code` from the error envelope.
 */
class CursorApiClient(
    private val apiKeyProvider: suspend () -> String?,
    baseUrl: String = DEFAULT_BASE_URL,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.cursor.com"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    private val baseUrl: HttpUrl = baseUrl.toHttpUrl()

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Separate client for SSE: long read timeout so a silent (dead) socket surfaces as a
     * failure. The spec guarantees heartbeat events, so 90s without data means the
     * connection is gone — onFailure fires and the reconnect loop recovers.
     */
    val sseClient: OkHttpClient = httpClient.newBuilder()
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    // ---- Auth / account ----

    /** Validates an explicit key (onboarding flow — key not yet persisted). */
    suspend fun validateKey(apiKey: String): ApiKeyInfo =
        get("v1/me", overrideKey = apiKey)

    suspend fun me(): ApiKeyInfo = get("v1/me")

    // ---- Catalog ----

    suspend fun listModels(): ListModelsResponse = get("v1/models")

    /** Heavily rate limited (1/min/user) — callers must cache. See RepoCache. */
    suspend fun listRepositories(): ListRepositoriesResponse = get("v1/repositories")

    // ---- Agents ----

    suspend fun listAgents(
        limit: Int = 20,
        cursor: String? = null,
        includeArchived: Boolean = false,
    ): ListAgentsResponse = get("v1/agents") {
        addQueryParameter("limit", limit.toString())
        cursor?.let { addQueryParameter("cursor", it) }
        addQueryParameter("includeArchived", includeArchived.toString())
    }

    suspend fun getAgent(agentId: String): CloudAgent = get("v1/agents/$agentId")

    suspend fun createAgent(request: CreateAgentRequest): CreateAgentResponse =
        post("v1/agents", json.encodeToString(CreateAgentRequest.serializer(), request))

    suspend fun archiveAgent(agentId: String): IdResponse =
        post("v1/agents/$agentId/archive")

    suspend fun unarchiveAgent(agentId: String): IdResponse =
        post("v1/agents/$agentId/unarchive")

    suspend fun deleteAgent(agentId: String): IdResponse = delete("v1/agents/$agentId")

    // ---- Runs ----

    suspend fun listRuns(agentId: String, limit: Int = 20, cursor: String? = null): ListRunsResponse =
        get("v1/agents/$agentId/runs") {
            addQueryParameter("limit", limit.toString())
            cursor?.let { addQueryParameter("cursor", it) }
        }

    suspend fun getRun(agentId: String, runId: String): Run =
        get("v1/agents/$agentId/runs/$runId")

    /** 409 with code `agent_busy` means a run is already active — surface as retry/queue UX. */
    suspend fun createRun(agentId: String, request: CreateRunRequest): CreateRunResponse =
        post("v1/agents/$agentId/runs", json.encodeToString(CreateRunRequest.serializer(), request))

    suspend fun cancelRun(agentId: String, runId: String): IdResponse =
        post("v1/agents/$agentId/runs/$runId/cancel")

    // ---- Internals ----

    private suspend inline fun <reified T> get(
        path: String,
        overrideKey: String? = null,
        crossinline query: HttpUrl.Builder.() -> Unit = {},
    ): T {
        val url = baseUrl.newBuilder().addPathSegments(path).apply(query).build()
        val request = authedRequest(url, overrideKey).get().build()
        return execute(request)
    }

    private suspend inline fun <reified T> post(path: String, body: String = "{}"): T {
        val url = baseUrl.newBuilder().addPathSegments(path).build()
        val request = authedRequest(url).post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
        return execute(request)
    }

    private suspend inline fun <reified T> delete(path: String): T {
        val url = baseUrl.newBuilder().addPathSegments(path).build()
        val request = authedRequest(url).delete().build()
        return execute(request)
    }

    private suspend fun authedRequest(url: HttpUrl, overrideKey: String? = null): Request.Builder {
        val key = overrideKey ?: apiKeyProvider()
            ?: throw CursorApiException(401, "no_api_key", "No API key configured")
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
    }

    private suspend inline fun <reified T> execute(request: Request): T {
        val bodyText = awaitResponse(request)
        return try {
            json.decodeFromString(bodyText)
        } catch (e: SerializationException) {
            throw CursorApiException(200, "invalid_response", "Unexpected response from the Cursor API: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw CursorApiException(200, "invalid_response", "Unexpected response from the Cursor API: ${e.message}")
        }
    }

    private suspend fun awaitResponse(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = it.body?.string().orEmpty()
                        if (it.isSuccessful) {
                            continuation.resume(text)
                        } else {
                            continuation.resumeWithException(parseError(it.code, text))
                        }
                    }
                }
            })
        }

    private fun parseError(httpStatus: Int, body: String): CursorApiException = try {
        val parsed = json.decodeFromString(ApiErrorBody.serializer(), body)
        CursorApiException(httpStatus, parsed.error.code, parsed.error.message, parsed.error.helpUrl)
    } catch (_: Exception) {
        CursorApiException(httpStatus, "http_$httpStatus", body.ifBlank { "HTTP $httpStatus" })
    }
}
