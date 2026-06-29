package com.vibecode.companion.ui.detail

import android.content.Context
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
import com.vibecode.companion.data.storage.db.RunModeDao
import com.vibecode.companion.data.storage.db.RunModeEntity
import com.vibecode.companion.testutil.FakeCursorApiClient
import com.vibecode.companion.testutil.FakeRunStreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException

/**
 * Guards CUR-9's mode-derivation contract: the detail screen reads the **newest run's** mode and
 * never fabricates one. A follow-up on an agent whose newest run has no recorded mode (legacy run
 * or one created outside this app) must persist nothing and leave `latestMode` null — otherwise a
 * guessed "agent" row would poison CUR-8's "Build" gating.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AgentDetailViewModelTest {

    private lateinit var context: Context
    private lateinit var db: CompanionDatabase
    private lateinit var runModeStore: RunModeStore
    private lateinit var promptStore: PromptStore

    @Before
    fun setUp() {
        // Unconfined Main so viewModelScope work runs eagerly without a blocked main looper.
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = RuntimeEnvironment.getApplication()
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
        // This suite writes through PromptStore into the process-global companionDataStore; clear it
        // on the way out (as well as in) so state can't leak into other test classes.
        runBlocking { context.companionDataStore.edit { it.clear() } }
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

    @Test
    fun latestMode_updatesReactively_whenModePersistedAfterLoad() = runBlocking {
        val agentId = "agentY"
        // The newest run is the one the launch screen just created; its mode is persisted in the
        // background AFTER navigation, so load() here runs before any mode row exists.
        val newest = run(id = "runNew", agentId = agentId)
        val api = FakeCursorApiClient(
            onGetAgent = { agent(agentId) },
            onListRuns = { ListRunsResponse(items = listOf(newest)) },
            onGetRun = { _, _ -> newest },
        )
        val vm = AgentDetailViewModel(api, FakeRunStreamClient(), promptStore, runModeStore, agentId)

        // After load() there is no recorded mode yet → latestMode is null (Build stays hidden).
        vm.awaitState { !it.isLoading && it.newestRun?.id == "runNew" }
        assertNull("no mode recorded yet → latestMode null", vm.uiState.value.latestMode)

        // Mode persisted AFTER load() — the reactive observer must flip latestMode without a manual
        // refresh. A one-shot read at load time would leave this null forever (the bug this guards).
        runModeStore.recordMode("runNew", agentId, "plan")

        vm.awaitState { it.latestMode == "plan" } // times out if the mode isn't observed reactively
        assertEquals("plan", vm.uiState.value.latestMode)
    }

    @Test
    fun followUp_inheritsModeFromRelay_whenPriorRunRoomWriteHasNotLanded() = runBlocking {
        val agentId = "agentR"
        val priorRun = run(id = "runOld", agentId = agentId)
        val followUpRun = run(id = "runNew", agentId = agentId)
        val api = FakeCursorApiClient(
            onGetAgent = { agent(agentId) },
            onListRuns = { ListRunsResponse(items = listOf(priorRun)) },
            onCreateRun = { _, _ -> CreateRunResponse(run = followUpRun) },
            onGetRun = { _, _ -> followUpRun },
        )
        // The launch → detail race: the launch screen remembered the initial run's mode
        // synchronously, but its background Room write has NOT landed (no row in Room yet).
        runModeStore.remember("runOld", "plan")
        assertNull("guard: prior run has no persisted Room row yet", db.runModeDao().modeForRun("runOld"))

        val vm = AgentDetailViewModel(api, FakeRunStreamClient(), promptStore, runModeStore, agentId)
        vm.awaitState { !it.isLoading && it.newestRun?.id == "runOld" }

        vm.onFollowUpTextChange("keep going")
        vm.sendFollowUp()
        vm.awaitState { it.newestRun?.id == "runNew" }

        // The follow-up resolved the prior run's mode from the relay (not Room) and carried it
        // forward: the new run gets a real mode row and latestMode reflects it — the mode-gated
        // Build action is NOT lost to the race. A one-shot Room read would have read null here.
        vm.awaitState { it.latestMode == "plan" }
        assertEquals("plan", vm.uiState.value.latestMode)
        assertEquals("plan", db.runModeDao().modeForRun("runNew"))
    }

    @Test
    fun followUp_whenLocalWriteFails_preservesCreatedRun_andSurfacesNonFatalMessage() = runBlocking {
        val agentId = "agentZ"
        val priorRun = run(id = "runOld", agentId = agentId)
        val followUpRun = run(id = "runNew", agentId = agentId)
        val api = FakeCursorApiClient(
            onGetAgent = { agent(agentId) },
            onListRuns = { ListRunsResponse(items = listOf(priorRun)) },
            onCreateRun = { _, _ -> CreateRunResponse(run = followUpRun) },
            onGetRun = { _, _ -> followUpRun },
        )
        // A RunModeStore whose Room ops all fail — stands in for any post-create local-write failure
        // (prompt save, mode read, mode record). The remote run already exists, so this must NOT
        // route into the send-failure path that prompts a duplicate retry.
        val failingModeStore = RunModeStore(ThrowingRunModeDao())
        val vm = AgentDetailViewModel(api, FakeRunStreamClient(), promptStore, failingModeStore, agentId)

        vm.awaitState { !it.isLoading && it.newestRun?.id == "runOld" }
        vm.onFollowUpTextChange("keep going")
        vm.sendFollowUp()

        // The created run becomes the newest run (send succeeded) and the user sees the non-fatal
        // note — NOT the connection-error path the unisolated code would have hit.
        vm.awaitState { it.newestRun?.id == "runNew" }
        val state = vm.uiState.value
        assertFalse("send must not stay in-flight after a local-write failure", state.isSending)
        assertEquals("Run created, but local state could not be saved.", state.transientMessage)
    }

    /** A [RunModeDao] whose persistence ops fail, to exercise the post-create local-write isolation. */
    private class ThrowingRunModeDao : RunModeDao {
        override suspend fun upsert(entity: RunModeEntity): Unit = throw IOException("db down")
        override suspend fun modeForRun(runId: String): String? = throw IOException("db down")
        override fun modeForRunFlow(runId: String): Flow<String?> = emptyFlow()
        override suspend fun latestModeForAgent(agentId: String): String? = null
        override suspend fun runModesForAgent(agentId: String): List<RunModeEntity> = emptyList()
        override suspend fun clear() = Unit
    }

    private suspend fun AgentDetailViewModel.awaitState(predicate: (AgentDetailUiState) -> Boolean) {
        withTimeout(10_000) { uiState.first(predicate) }
    }

    private fun agent(id: String) =
        CloudAgent(id = id, status = "ACTIVE", createdAt = "t", updatedAt = "t")

    private fun run(id: String, agentId: String) =
        Run(id = id, agentId = agentId, status = RunStatus.FINISHED, createdAt = "t", updatedAt = "t")
}
