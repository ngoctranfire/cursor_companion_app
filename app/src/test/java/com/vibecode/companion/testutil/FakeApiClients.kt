package com.vibecode.companion.testutil

import com.vibecode.companion.data.api.CloudAgent
import com.vibecode.companion.data.api.CreateAgentRequest
import com.vibecode.companion.data.api.CreateAgentResponse
import com.vibecode.companion.data.api.CreateRunRequest
import com.vibecode.companion.data.api.CreateRunResponse
import com.vibecode.companion.data.api.CursorApiClient
import com.vibecode.companion.data.api.ListModelsResponse
import com.vibecode.companion.data.api.ListRepositoriesResponse
import com.vibecode.companion.data.api.ListRunsResponse
import com.vibecode.companion.data.api.Run
import com.vibecode.companion.data.api.RunStreamClient
import com.vibecode.companion.data.api.RunStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.OkHttpClient

/**
 * Hand-written fake for [CursorApiClient] — each request delegates to a swappable lambda, so a
 * test only has to stub the calls its path exercises. The project keeps no mocking framework
 * (see [CursorApiClient]'s KDoc), so the request methods are `open` for exactly this.
 */
class FakeCursorApiClient(
    var onListModels: suspend () -> ListModelsResponse = { ListModelsResponse(emptyList()) },
    var onListRepositories: suspend () -> ListRepositoriesResponse = { ListRepositoriesResponse(emptyList()) },
    var onCreateAgent: suspend (CreateAgentRequest) -> CreateAgentResponse = { error("createAgent not stubbed") },
    var onGetAgent: suspend (String) -> CloudAgent = { error("getAgent not stubbed") },
    var onListRuns: suspend (String) -> ListRunsResponse = { ListRunsResponse(emptyList()) },
    var onGetRun: suspend (String, String) -> Run = { _, _ -> error("getRun not stubbed") },
    var onCreateRun: suspend (String, CreateRunRequest) -> CreateRunResponse = { _, _ -> error("createRun not stubbed") },
) : CursorApiClient(apiKeyProvider = { "test-key" }) {
    override suspend fun listModels(): ListModelsResponse = onListModels()
    override suspend fun listRepositories(): ListRepositoriesResponse = onListRepositories()
    override suspend fun createAgent(request: CreateAgentRequest): CreateAgentResponse = onCreateAgent(request)
    override suspend fun getAgent(agentId: String): CloudAgent = onGetAgent(agentId)
    override suspend fun listRuns(agentId: String, limit: Int, cursor: String?): ListRunsResponse = onListRuns(agentId)
    override suspend fun getRun(agentId: String, runId: String): Run = onGetRun(agentId, runId)
    override suspend fun createRun(agentId: String, request: CreateRunRequest): CreateRunResponse =
        onCreateRun(agentId, request)
}

/**
 * Hand-written fake for [RunStreamClient]. Returns an empty event flow by default, so streaming
 * completes immediately and the ViewModel's reconnect loop never spins in a test.
 */
class FakeRunStreamClient(
    var onStreamRun: (String, String, String?) -> Flow<RunStreamEvent> = { _, _, _ -> emptyFlow() },
) : RunStreamClient(sseClient = OkHttpClient(), apiKeyProvider = { "test-key" }) {
    override fun streamRun(agentId: String, runId: String, lastEventId: String?): Flow<RunStreamEvent> =
        onStreamRun(agentId, runId, lastEventId)
}
