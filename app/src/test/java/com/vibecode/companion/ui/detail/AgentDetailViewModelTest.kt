package com.vibecode.companion.ui.detail

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import com.vibecode.companion.data.api.AgentMode
import com.vibecode.companion.data.api.CloudAgent
import com.vibecode.companion.data.api.CreateRunResponse
import com.vibecode.companion.data.api.CursorApiException
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
import kotlinx.coroutines.CompletableDeferred
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

    @Test
    fun followUp_whenPromptSaveFails_stillRecordsInheritedMode_soBuildNotHidden() = runBlocking {
        val agentId = "agentP"
        val priorRun = run(id = "runOld", agentId = agentId)
        val followUpRun = run(id = "runNew", agentId = agentId)
        val api = FakeCursorApiClient(
            onGetAgent = { agent(agentId) },
            onListRuns = { ListRunsResponse(items = listOf(priorRun)) },
            onCreateRun = { _, _ -> CreateRunResponse(run = followUpRun) },
            onGetRun = { _, _ -> followUpRun },
        )
        // Prior run's mode IS known, so the follow-up should inherit and persist it for the new run.
        runModeStore.recordMode("runOld", agentId, "plan")
        // The prompt save fails — this must NOT skip the mode write (the two are isolated, mode first).
        val failingPromptStore = ThrowingSavePromptStore(context)
        val vm = AgentDetailViewModel(api, FakeRunStreamClient(), failingPromptStore, runModeStore, agentId)

        vm.awaitState { !it.isLoading && it.newestRun?.id == "runOld" }
        vm.onFollowUpTextChange("keep going")
        vm.sendFollowUp()
        vm.awaitState { it.newestRun?.id == "runNew" }

        // The inherited mode was still recorded despite the prompt-save failure: Room has the row and
        // latestMode reflects it — CUR-8's mode-gated Build is NOT hidden for this follow-up.
        vm.awaitState { it.latestMode == "plan" }
        assertEquals("plan", vm.uiState.value.latestMode)
        assertEquals("plan", db.runModeDao().modeForRun("runNew"))
        // The send still succeeded and surfaced the non-fatal note rather than a duplicate-retry error.
        assertEquals(
            "Run created, but local state could not be saved.",
            vm.uiState.value.transientMessage,
        )
    }

    // ---- CUR-8: Build (plan → agent follow-up) ----

    @Test
    fun buildPlan_postsAgentModeAndCannedPrompt_persistsNewRunModeAsAgent() = runBlocking {
        val agentId = "agentBuild"
        val planRun = run(id = "runOld", agentId = agentId)
        val buildRun = run(id = "runNew", agentId = agentId)
        var capturedMode: String? = "unset"
        var capturedPrompt: String? = null
        val api = FakeCursorApiClient(
            onGetAgent = { agent(agentId) },
            onListRuns = { ListRunsResponse(items = listOf(planRun)) },
            onCreateRun = { _, request ->
                capturedMode = request.mode
                capturedPrompt = request.prompt.text
                CreateRunResponse(run = buildRun)
            },
            onGetRun = { _, _ -> buildRun },
        )
        // The newest run is a finished PLAN run → Build is offered.
        runModeStore.recordMode("runOld", agentId, AgentMode.PLAN)
        val vm = AgentDetailViewModel(api, FakeRunStreamClient(), promptStore, runModeStore, agentId)
        vm.awaitState { !it.isLoading && it.latestMode == AgentMode.PLAN && it.canBuild }

        vm.buildPlan()
        vm.awaitState { it.newestRun?.id == "runNew" }

        // The build run was created in AGENT mode with the canned, non-empty prompt.
        assertEquals(AgentMode.AGENT, capturedMode)
        assertEquals("Implement the approved plan above.", capturedPrompt)
        // The new run's mode is persisted as AGENT (a known value, not a guess), so latestMode flips
        // to "agent" and Build hides afterward.
        vm.awaitState { it.latestMode == AgentMode.AGENT }
        assertEquals(AgentMode.AGENT, db.runModeDao().modeForRun("runNew"))
        assertFalse("Build must hide once the agent run starts", vm.uiState.value.canBuild)
        assertFalse(vm.uiState.value.isBuilding)
    }

    @Test
    fun buildPlan_whenAgentBusy_surfacesMessage_preservesPlanRun_andKeepsBuildAvailable() = runBlocking {
        val agentId = "agentBusy"
        val planRun = run(id = "runOld", agentId = agentId)
        val api = FakeCursorApiClient(
            onGetAgent = { agent(agentId) },
            onListRuns = { ListRunsResponse(items = listOf(planRun)) },
            onCreateRun = { _, _ -> throw CursorApiException(409, "agent_busy", "busy") },
            onGetRun = { _, _ -> planRun },
        )
        runModeStore.recordMode("runOld", agentId, AgentMode.PLAN)
        val vm = AgentDetailViewModel(api, FakeRunStreamClient(), promptStore, runModeStore, agentId)
        vm.awaitState { !it.isLoading && it.canBuild }

        vm.buildPlan()
        vm.awaitState { it.transientMessage != null }

        val state = vm.uiState.value
        assertFalse(state.isBuilding)
        assertEquals("Agent is busy — wait for the current run to finish.", state.transientMessage)
        // The plan run is untouched and Build stays available to retry.
        assertEquals("runOld", state.newestRun?.id)
        assertEquals(AgentMode.PLAN, state.latestMode)
        assertTrue(state.canBuild)
    }

    @Test
    fun buildPlan_whenNetworkFails_surfacesConnectionMessage_andPreservesPlanRun() = runBlocking {
        val agentId = "agentNet"
        val planRun = run(id = "runOld", agentId = agentId)
        val api = FakeCursorApiClient(
            onGetAgent = { agent(agentId) },
            onListRuns = { ListRunsResponse(items = listOf(planRun)) },
            onCreateRun = { _, _ -> throw IOException("offline") },
            onGetRun = { _, _ -> planRun },
        )
        runModeStore.recordMode("runOld", agentId, AgentMode.PLAN)
        val vm = AgentDetailViewModel(api, FakeRunStreamClient(), promptStore, runModeStore, agentId)
        vm.awaitState { !it.isLoading && it.canBuild }

        vm.buildPlan()
        vm.awaitState { it.transientMessage != null }

        val state = vm.uiState.value
        assertFalse(state.isBuilding)
        assertEquals("Check your connection", state.transientMessage)
        assertEquals("runOld", state.newestRun?.id)
        assertTrue("no run created → Build stays available", state.canBuild)
        // A failed create must not fabricate a mode row for a run that never existed.
        assertTrue(db.runModeDao().runModesForAgent(agentId).none { it.runId != "runOld" })
    }

    @Test
    fun buildPlan_whenRateLimited_surfacesMessage_preservesPlanRun_andKeepsBuildAvailable() = runBlocking {
        val agentId = "agentLimited"
        val planRun = run(id = "runOld", agentId = agentId)
        val api = FakeCursorApiClient(
            onGetAgent = { agent(agentId) },
            onListRuns = { ListRunsResponse(items = listOf(planRun)) },
            onCreateRun = { _, _ -> throw CursorApiException(429, "rate_limit_exceeded", "slow down") },
            onGetRun = { _, _ -> planRun },
        )
        runModeStore.recordMode("runOld", agentId, AgentMode.PLAN)
        val vm = AgentDetailViewModel(api, FakeRunStreamClient(), promptStore, runModeStore, agentId)
        vm.awaitState { !it.isLoading && it.canBuild }

        vm.buildPlan()
        vm.awaitState { it.transientMessage != null }

        val state = vm.uiState.value
        assertFalse(state.isBuilding)
        // Production (buildPlan's CursorApiException handler) maps rate_limit_exceeded to this
        // transient/retry message — asserted verbatim from the source, not invented here.
        assertEquals("Rate limited — try again in a moment.", state.transientMessage)
        // The plan run is untouched and Build stays available to retry once the limit clears.
        assertEquals("runOld", state.newestRun?.id)
        assertEquals(AgentMode.PLAN, state.latestMode)
        assertTrue(state.canBuild)
        // A failed create must not fabricate a mode row for a run that never existed.
        assertTrue(db.runModeDao().runModesForAgent(agentId).none { it.runId != "runOld" })
    }

    @Test
    fun buildPlan_whenLocalWriteFails_preservesCreatedRun_andSurfacesNonFatalMessage() = runBlocking {
        val agentId = "agentBuildW"
        val planRun = run(id = "runOld", agentId = agentId)
        val buildRun = run(id = "runNew", agentId = agentId)
        val api = FakeCursorApiClient(
            onGetAgent = { agent(agentId) },
            onListRuns = { ListRunsResponse(items = listOf(planRun)) },
            onCreateRun = { _, _ -> CreateRunResponse(run = buildRun) },
            onGetRun = { _, _ -> buildRun },
        )
        // A RunModeStore whose Room ops all fail — recordMode(AGENT) for the build run throws. The
        // remote run already exists, so this must NOT route into the build-failure path (which would
        // show a connection/API error → the user retries → a DUPLICATE run). buildPlan has no canBuild
        // guard (the UI gates the button); invoking it directly exercises the post-create isolation.
        val failingModeStore = RunModeStore(ThrowingRunModeDao())
        val vm = AgentDetailViewModel(api, FakeRunStreamClient(), promptStore, failingModeStore, agentId)

        vm.awaitState { !it.isLoading && it.newestRun?.id == "runOld" }
        vm.buildPlan()
        vm.awaitState { it.newestRun?.id == "runNew" }

        val state = vm.uiState.value
        // The created run is preserved (build succeeded) and the user sees the non-fatal note — NOT
        // the createRun-failure path. The 'never mask a successful createRun' contract holds.
        assertFalse("build must not stay in-flight after a local-write failure", state.isBuilding)
        assertEquals("Run created, but local state could not be saved.", state.transientMessage)
        // latestMode keeps the KNOWN new-run mode (AGENT, set eagerly) — never corrupted to PLAN, so
        // Build correctly stays hidden rather than being wrongly re-offered on the new agent run.
        assertEquals(AgentMode.AGENT, state.latestMode)
        assertFalse("Build hides once the agent run starts", state.canBuild)
    }

    @Test
    fun buildPlan_whenPromptSaveFails_stillRecordsAgentMode_soBuildStaysHidden() = runBlocking {
        val agentId = "agentBuildP"
        val planRun = run(id = "runOld", agentId = agentId)
        val buildRun = run(id = "runNew", agentId = agentId)
        val api = FakeCursorApiClient(
            onGetAgent = { agent(agentId) },
            onListRuns = { ListRunsResponse(items = listOf(planRun)) },
            onCreateRun = { _, _ -> CreateRunResponse(run = buildRun) },
            onGetRun = { _, _ -> buildRun },
        )
        // Newest run is a finished PLAN run → Build is offered.
        runModeStore.recordMode("runOld", agentId, AgentMode.PLAN)
        // The prompt save fails — this must NOT skip the AGENT mode write (the two are isolated, mode
        // first). With no mode row, observeMode(runNew) would re-emit null and drop the known mode.
        val failingPromptStore = ThrowingSavePromptStore(context)
        val vm = AgentDetailViewModel(api, FakeRunStreamClient(), failingPromptStore, runModeStore, agentId)

        vm.awaitState { !it.isLoading && it.latestMode == AgentMode.PLAN && it.canBuild }
        vm.buildPlan()
        vm.awaitState { it.newestRun?.id == "runNew" }

        // The build run's KNOWN mode (AGENT) was still recorded despite the prompt-save failure: Room
        // has the row and latestMode reflects it, so Build correctly STAYS hidden afterward.
        vm.awaitState { it.latestMode == AgentMode.AGENT }
        assertEquals(AgentMode.AGENT, vm.uiState.value.latestMode)
        assertEquals(AgentMode.AGENT, db.runModeDao().modeForRun("runNew"))
        assertFalse("Build must hide once the agent run starts", vm.uiState.value.canBuild)
        // The build still succeeded and surfaced the non-fatal note rather than a duplicate-retry error.
        assertFalse(vm.uiState.value.isBuilding)
        assertEquals(
            "Run created, but local state could not be saved.",
            vm.uiState.value.transientMessage,
        )
    }

    @Test
    fun whileBuildInFlight_sendFollowUpIsNoOp_onlyOneCreateRun() = runBlocking {
        val agentId = "agentMx1"
        val planRun = run(id = "runOld", agentId = agentId)
        val buildRun = run(id = "runNew", agentId = agentId)
        // Hold the first createRun in flight so the second path's guard is exercised deterministically.
        val gate = CompletableDeferred<Unit>()
        var createRunCalls = 0
        val api = FakeCursorApiClient(
            onGetAgent = { agent(agentId) },
            onListRuns = { ListRunsResponse(items = listOf(planRun)) },
            onCreateRun = { _, _ ->
                createRunCalls++
                gate.await()
                CreateRunResponse(run = buildRun)
            },
            onGetRun = { _, _ -> buildRun },
        )
        runModeStore.recordMode("runOld", agentId, AgentMode.PLAN)
        val vm = AgentDetailViewModel(api, FakeRunStreamClient(), promptStore, runModeStore, agentId)
        vm.awaitState { !it.isLoading && it.canBuild }

        // Start a Build; its createRun suspends on the gate, so isBuilding stays true.
        vm.buildPlan()
        vm.awaitState { it.isBuilding }

        // A typed follow-up while building must be a NO-OP — the reciprocal guard blocks a 2nd createRun.
        vm.onFollowUpTextChange("squeeze in a follow-up")
        vm.sendFollowUp()
        assertEquals("sendFollowUp must not create a run while building", 1, createRunCalls)
        assertFalse("sendFollowUp must stay inert while building", vm.uiState.value.isSending)

        // Release the build; it completes as the sole created run.
        gate.complete(Unit)
        vm.awaitState { it.newestRun?.id == "runNew" }
        assertEquals("exactly one createRun overall", 1, createRunCalls)
    }

    @Test
    fun whileFollowUpInFlight_buildPlanIsNoOp_onlyOneCreateRun() = runBlocking {
        val agentId = "agentMx2"
        val planRun = run(id = "runOld", agentId = agentId)
        val followUpRun = run(id = "runNew", agentId = agentId)
        val gate = CompletableDeferred<Unit>()
        var createRunCalls = 0
        val api = FakeCursorApiClient(
            onGetAgent = { agent(agentId) },
            onListRuns = { ListRunsResponse(items = listOf(planRun)) },
            onCreateRun = { _, _ ->
                createRunCalls++
                gate.await()
                CreateRunResponse(run = followUpRun)
            },
            onGetRun = { _, _ -> followUpRun },
        )
        // Newest run is a terminal PLAN run (so buildPlan would otherwise be actionable).
        runModeStore.recordMode("runOld", agentId, AgentMode.PLAN)
        val vm = AgentDetailViewModel(api, FakeRunStreamClient(), promptStore, runModeStore, agentId)
        vm.awaitState { !it.isLoading && it.canBuild }

        // Start a follow-up; its createRun suspends on the gate, so isSending stays true.
        vm.onFollowUpTextChange("keep going")
        vm.sendFollowUp()
        vm.awaitState { it.isSending }

        // A Build tap while the follow-up is in flight must be a NO-OP — no 2nd createRun.
        vm.buildPlan()
        assertEquals("buildPlan must not create a run while a follow-up is in flight", 1, createRunCalls)
        assertFalse("buildPlan must stay inert while sending", vm.uiState.value.isBuilding)

        gate.complete(Unit)
        vm.awaitState { it.newestRun?.id == "runNew" }
        assertEquals("exactly one createRun overall", 1, createRunCalls)
    }

    @Test
    fun canBuild_truthTable() {
        val terminalRun = Run(
            id = "r", agentId = "a", status = RunStatus.FINISHED, createdAt = "t", updatedAt = "t",
        )
        val activeRun = terminalRun.copy(status = RunStatus.RUNNING)

        // plan + terminal → Build offered.
        assertTrue(
            AgentDetailUiState(
                newestRun = terminalRun,
                liveRunStatus = RunStatus.FINISHED,
                latestMode = AgentMode.PLAN,
            ).canBuild,
        )
        // plan + active → hidden (run still running).
        assertFalse(
            AgentDetailUiState(
                newestRun = activeRun,
                liveRunStatus = RunStatus.RUNNING,
                latestMode = AgentMode.PLAN,
            ).canBuild,
        )
        // agent + terminal → hidden (never after a normal agent run).
        assertFalse(
            AgentDetailUiState(
                newestRun = terminalRun,
                liveRunStatus = RunStatus.FINISHED,
                latestMode = AgentMode.AGENT,
            ).canBuild,
        )
        // unknown mode + terminal → hidden (never on a guessed/unknown mode).
        assertFalse(
            AgentDetailUiState(
                newestRun = terminalRun,
                liveRunStatus = RunStatus.FINISHED,
                latestMode = null,
            ).canBuild,
        )
        // plan + terminal but still streaming → hidden until the live stream settles.
        assertFalse(
            AgentDetailUiState(
                newestRun = terminalRun,
                liveRunStatus = RunStatus.FINISHED,
                latestMode = AgentMode.PLAN,
                isStreaming = true,
            ).canBuild,
        )
        // plan + terminal but still replaying history → hidden until the replay settles.
        assertFalse(
            AgentDetailUiState(
                newestRun = terminalRun,
                liveRunStatus = RunStatus.FINISHED,
                latestMode = AgentMode.PLAN,
                isReplaying = true,
            ).canBuild,
        )
    }

    /** A [PromptStore] whose `save` always fails, to exercise the prompt-save vs mode-write isolation. */
    private class ThrowingSavePromptStore(context: Context) : PromptStore(context) {
        override suspend fun save(runId: String, prompt: String): Unit = throw IOException("prompt store down")
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
