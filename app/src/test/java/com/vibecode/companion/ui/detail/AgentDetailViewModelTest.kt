package com.vibecode.companion.ui.detail

import androidx.datastore.preferences.core.edit
import androidx.room.Room
import com.vibecode.companion.data.api.CloudAgent
import com.vibecode.companion.data.api.CreateRunResponse
import com.vibecode.companion.data.api.ListRunsResponse
import com.vibecode.companion.data.api.Run
import com.vibecode.companion.data.api.RunStatus
import com.vibecode.companion.data.storage.PromptStore
import com.vibecode.companion.data.storage.RunModeStore
import com.vibecode.companion.data.storage.companionDataStore
import com.vibecode.companion.data.storage.db.CompanionDatabase
import com.vibecode.companion.testutil.FakeCursorApiClient
import com.vibecode.companion.testutil.FakeRunStreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Guards CUR-9's mode-derivation contract: the detail screen reads the **newest run's** mode and
 * never fabricates one. A follow-up on an agent whose newest run has no recorded mode (legacy run
 * or one created outside this app) must persist nothing and leave `latestMode` null — otherwise a
 * guessed "agent" row would poison CUR-8's "Build" gating.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AgentDetailViewModelTest {

    private lateinit var db: CompanionDatabase
    private lateinit var runModeStore: RunModeStore
    private lateinit var promptStore: PromptStore

    @Before
    fun setUp() {
        // Unconfined Main so viewModelScope work runs eagerly without a blocked main looper.
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, CompanionDatabase::class.java)
            .allowMainThreadQueries().build()
        runModeStore = RunModeStore(db.runModeDao())
        promptStore = PromptStore(context)
        // Isolate the shared DataStore between tests.
        runBlocking { context.companionDataStore.edit { it.clear() } }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun followUp_whenNewestRunModeUnknown_doesNotFabricateRow_andLeavesLatestModeNull() = runBlocking {
        val agentId = "agentX"
        // The newest (and only) run was never recorded by this app — its mode is genuinely unknown.
        val priorRun = run(id = "runOld", agentId = agentId)
        val followUpRun = run(id = "runNew", agentId = agentId)
        val api = FakeCursorApiClient(
            onGetAgent = { agent(agentId) },
            onListRuns = { ListRunsResponse(items = listOf(priorRun)) },
            onCreateRun = { _, _ -> CreateRunResponse(run = followUpRun) },
            onGetRun = { _, _ -> followUpRun },
        )
        val vm = AgentDetailViewModel(api, FakeRunStreamClient(), promptStore, runModeStore, agentId)

        // After the initial load, latestMode reflects the newest run's (unknown) mode → null.
        vm.awaitState { !it.isLoading && it.newestRun?.id == "runOld" }
        assertNull("unknown newest-run mode must not default to agent", vm.uiState.value.latestMode)

        vm.onFollowUpTextChange("keep going")
        vm.sendFollowUp()
        vm.awaitState { it.newestRun?.id == "runNew" }

        // No mode was inherited (prior run's mode unknown) → nothing persisted, latestMode still null.
        assertNull(vm.uiState.value.latestMode)
        assertTrue(
            "follow-up must not fabricate a mode row",
            db.runModeDao().runModesForAgent(agentId).isEmpty(),
        )
        assertNull(db.runModeDao().modeForRun("runNew"))
    }

    private suspend fun AgentDetailViewModel.awaitState(predicate: (AgentDetailUiState) -> Boolean) {
        withTimeout(10_000) { uiState.first(predicate) }
    }

    private fun agent(id: String) =
        CloudAgent(id = id, status = "ACTIVE", createdAt = "t", updatedAt = "t")

    private fun run(id: String, agentId: String) =
        Run(id = id, agentId = agentId, status = RunStatus.FINISHED, createdAt = "t", updatedAt = "t")
}
