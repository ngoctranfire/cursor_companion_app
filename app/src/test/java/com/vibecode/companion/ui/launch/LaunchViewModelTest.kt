package com.vibecode.companion.ui.launch

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import com.vibecode.companion.data.api.CloudAgent
import com.vibecode.companion.data.api.CreateAgentRequest
import com.vibecode.companion.data.api.CreateAgentResponse
import com.vibecode.companion.data.api.ListModelsResponse
import com.vibecode.companion.data.api.ListRepositoriesResponse
import com.vibecode.companion.data.api.ModelListItem
import com.vibecode.companion.data.api.ModelRef
import com.vibecode.companion.data.api.Repository
import com.vibecode.companion.data.api.Run
import com.vibecode.companion.data.storage.AccountWriteCoordinator
import com.vibecode.companion.data.storage.LaunchDefaults
import com.vibecode.companion.data.storage.PreferenceProfileStore
import com.vibecode.companion.data.storage.PromptStore
import com.vibecode.companion.data.storage.RepoCache
import com.vibecode.companion.data.storage.RunModeStore
import com.vibecode.companion.data.storage.companionDataStore
import com.vibecode.companion.data.storage.db.CompanionDatabase
import com.vibecode.companion.data.storage.db.PreferenceProfileDao
import com.vibecode.companion.data.storage.db.PreferenceProfileEntity
import com.vibecode.companion.testutil.FakeCursorApiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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
 * Guards CUR-9's launch-restore contract:
 *  - a returning user's saved model/repo are validated against the freshly loaded lists, so a
 *    stale id can't survive a race with the restore (Cluster C); and
 *  - post-launch bookkeeping runs on the app scope so it survives the launch screen being popped
 *    (Cluster D).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LaunchViewModelTest {

    private lateinit var context: Context
    private lateinit var db: CompanionDatabase
    private lateinit var repoCache: RepoCache
    private lateinit var promptStore: PromptStore
    private lateinit var runModeStore: RunModeStore
    private lateinit var preferenceProfileStore: PreferenceProfileStore
    private lateinit var appScope: CoroutineScope
    private lateinit var writeCoordinator: AccountWriteCoordinator

    @Before
    fun setUp() {
        // Main = Unconfined under runBlocking, deliberately NOT a StandardTestDispatcher under
        // runTest: these tests drive real Room + DataStore, which run on their own background
        // executors. Virtual-time `runTest` would let `withTimeout` state-awaits fire before those
        // real callbacks land, making the suite flaky. Unconfined keeps the VM's launched
        // coroutines eager so the in-flight-fetch windows are observable, while `withTimeout` stays
        // real wall-clock.
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = RuntimeEnvironment.getApplication()
        runBlocking { context.companionDataStore.edit { it.clear() } } // isolate DataStore between tests
        db = Room.inMemoryDatabaseBuilder(context, CompanionDatabase::class.java)
            .allowMainThreadQueries().build()
        repoCache = RepoCache(context)
        promptStore = PromptStore(context)
        runModeStore = RunModeStore(db.runModeDao())
        preferenceProfileStore = PreferenceProfileStore(db.preferenceProfileDao())
        // Stand-in for the @AppCoroutineScope singleton — survives the (simulated) VM clear. Uses a
        // distinct real dispatcher (production uses Dispatchers.Default) rather than sharing Main's
        // Unconfined, so the app-scope↔main boundary the post-nav-persistence test relies on is a
        // genuine async hop, not collapsed onto the main dispatcher.
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        writeCoordinator = AccountWriteCoordinator(appScope)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(api: FakeCursorApiClient) = LaunchViewModel(
        api, repoCache, promptStore, runModeStore, preferenceProfileStore, writeCoordinator,
    )

    @Test
    fun init_dropsRestoredModelTheServerNoLongerOffers() = runBlocking {
        // Returning user: saved model "old-model"; saved repo "repoA" still present in the cache.
        preferenceProfileStore.saveLaunchDefaults(
            LaunchDefaults(repoUrl = "repoA", modelId = "old-model", autoCreatePr = true, mode = "agent"),
        )
        repoCache.save(listOf("repoA"), nowEpochMs = 1)
        val api = FakeCursorApiClient(
            onListModels = { ListModelsResponse(items = listOf(ModelListItem(id = "m1", displayName = "M1"))) },
        )

        val vm = viewModel(api)
        val state = vm.awaitState { it.models.isNotEmpty() } // validation has run by now

        assertNull("stale restored model id must be dropped", state.selectedModelId)
        assertEquals("a still-valid repo must survive", "repoA", state.selectedRepo)
    }

    @Test
    fun init_dropsRestoredRepoNoLongerInTheList() = runBlocking {
        // Returning user: saved repo "gone-repo" is no longer in the cached list.
        preferenceProfileStore.saveLaunchDefaults(
            LaunchDefaults(repoUrl = "gone-repo", modelId = null, autoCreatePr = true, mode = "agent"),
        )
        repoCache.save(listOf("repoA", "repoB"), nowEpochMs = 1)
        val api = FakeCursorApiClient()

        val vm = viewModel(api)
        val state = vm.awaitState { it.repoUrls.isNotEmpty() }

        assertNull("inaccessible restored repo must be dropped", state.selectedRepo)
        assertFalse("canLaunch must not stay true for a dropped repo", state.canLaunch)
    }

    @Test
    fun launch_persistsBookkeepingOnAppScope_evenAfterNavigationSignal() = runBlocking {
        // Factory defaults (repoUrl = null) so the persisted repo is a detectable change.
        repoCache.save(listOf("repoA"), nowEpochMs = 1)
        val createdAgent = CloudAgent(id = "agent1", status = "ACTIVE", createdAt = "t", updatedAt = "t")
        val createdRun = Run(id = "run1", agentId = "agent1", status = "RUNNING", createdAt = "t", updatedAt = "t")
        val api = FakeCursorApiClient(
            onCreateAgent = { CreateAgentResponse(agent = createdAgent, run = createdRun) },
        )

        val vm = viewModel(api)
        vm.awaitState { it.repoUrls.contains("repoA") }
        vm.selectRepo("repoA")
        vm.setPrompt("build the thing")

        vm.launch()
        // The navigation signal is published BEFORE persistence finishes — the screen can pop now.
        vm.awaitState { it.launchedAgentId == "agent1" }

        // The app-scoped writes complete regardless (they don't ride viewModelScope). Await the
        // last of the three (launch defaults) by its value change from the factory null.
        withTimeout(10_000) {
            while (preferenceProfileStore.launchDefaults().repoUrl != "repoA") yield()
        }
        assertEquals("build the thing", promptStore.get("run1"))
        assertEquals("agent", db.runModeDao().modeForRun("run1"))
        // persistLaunchResult writes the full LaunchDefaults, not just the repo — assert every
        // field so a regression that drops mode / modelId / autoCreatePr can't slip through.
        val defaults = preferenceProfileStore.launchDefaults()
        assertEquals("repoA", defaults.repoUrl)
        assertEquals("agent", defaults.mode) // planMode defaults to false → AGENT
        assertNull("no model selected → Default (null id) persisted", defaults.modelId)
        assertTrue("autoCreatePr defaults to true", defaults.autoCreatePr)
    }

    @Test
    fun launch_withNoCachedRepos_isGatedUntilFetchValidatesAndDropsStaleRepo() = runBlocking {
        // No cached list → init takes the network (refreshRepos) path. The restored repo is stale.
        preferenceProfileStore.saveLaunchDefaults(
            LaunchDefaults(repoUrl = "gone-repo", modelId = null, autoCreatePr = true, mode = "agent"),
        )
        val fetchGate = CompletableDeferred<Unit>()
        val api = FakeCursorApiClient(
            onListRepositories = { fetchGate.await(); ListRepositoriesResponse(items = listOf(Repository("repoA"))) },
        )

        val vm = viewModel(api)
        vm.setPrompt("do the thing")

        // Window: the fetch is still in flight, so the stale restored repo must NOT be launchable.
        val midFetch = vm.awaitState { it.reposLoading }
        assertEquals("gone-repo", midFetch.selectedRepo)
        assertFalse("must not launch a stale repo before fresh validation", midFetch.canLaunch)

        // Fetch resolves: the stale repo is dropped and launch stays disabled.
        fetchGate.complete(Unit)
        val settled = vm.awaitState { !it.reposLoading }
        assertNull("stale repo dropped after validation", settled.selectedRepo)
        assertFalse(settled.canLaunch)
    }

    @Test
    fun launch_withNoCachedRepos_validRestoredRepoBecomesLaunchableAfterFetch() = runBlocking {
        // No cache; the restored repo IS valid — it must become launchable once the fetch resolves.
        preferenceProfileStore.saveLaunchDefaults(
            LaunchDefaults(repoUrl = "repoA", modelId = null, autoCreatePr = true, mode = "agent"),
        )
        val fetchGate = CompletableDeferred<Unit>()
        val api = FakeCursorApiClient(
            onListRepositories = { fetchGate.await(); ListRepositoriesResponse(items = listOf(Repository("repoA"))) },
        )

        val vm = viewModel(api)
        vm.setPrompt("do the thing")

        // Even a valid restored repo is gated while validation is in flight.
        assertFalse("valid repo still gated until fetch resolves", vm.awaitState { it.reposLoading }.canLaunch)

        fetchGate.complete(Unit)
        val settled = vm.awaitState { !it.reposLoading }
        assertEquals("repoA", settled.selectedRepo)
        assertTrue("valid repo launchable once validated", settled.canLaunch)
    }

    // ---- R4: a stale restored model id must never reach the create request ----

    @Test
    fun launch_whenModelListFailed_dropsStaleModelId_andSendsServerDefault() = runBlocking {
        // Returning user with a saved model; the model list fails to load → models stays empty.
        preferenceProfileStore.saveLaunchDefaults(
            LaunchDefaults(repoUrl = "repoA", modelId = "old-model", autoCreatePr = true, mode = "agent"),
        )
        repoCache.save(listOf("repoA"), nowEpochMs = 1)
        var captured: CreateAgentRequest? = null
        val api = FakeCursorApiClient(
            onListModels = { throw IOException("models down") },
            onCreateAgent = { req -> captured = req; createAgentResponse() },
        )

        val vm = viewModel(api)
        // The restored stale id survives in state (loadModels never validated it), but...
        vm.awaitState { it.selectedModelId == "old-model" && it.repoUrls.contains("repoA") }
        vm.setPrompt("do the thing")

        vm.launch()
        vm.awaitState { it.launchedAgentId != null }
        // ...the launch-time membership gate drops it: a failed model list can't send a removed id.
        assertNull("a failed model list must degrade to the server default", captured?.model)
    }

    @Test
    fun launch_whileModelListStillLoading_dropsStaleModelId_andSendsServerDefault() = runBlocking {
        preferenceProfileStore.saveLaunchDefaults(
            LaunchDefaults(repoUrl = "repoA", modelId = "old-model", autoCreatePr = true, mode = "agent"),
        )
        repoCache.save(listOf("repoA"), nowEpochMs = 1)
        val modelGate = CompletableDeferred<Unit>() // never completed → list stays in flight
        var captured: CreateAgentRequest? = null
        val api = FakeCursorApiClient(
            onListModels = { modelGate.await(); ListModelsResponse(items = listOf(ModelListItem("old-model", "Old"))) },
            onCreateAgent = { req -> captured = req; createAgentResponse() },
        )

        val vm = viewModel(api)
        vm.awaitState { it.selectedModelId == "old-model" && it.repoUrls.contains("repoA") }
        vm.setPrompt("do the thing")

        // Launch while the model list is still loading (models still empty).
        vm.launch()
        vm.awaitState { it.launchedAgentId != null }
        assertNull("a pending model list must not let a stale id reach the request", captured?.model)
    }

    @Test
    fun launch_whenRestoredModelNotInLoadedList_dropsIt_andSendsServerDefault() = runBlocking {
        preferenceProfileStore.saveLaunchDefaults(
            LaunchDefaults(repoUrl = "repoA", modelId = "old-model", autoCreatePr = true, mode = "agent"),
        )
        repoCache.save(listOf("repoA"), nowEpochMs = 1)
        var captured: CreateAgentRequest? = null
        val api = FakeCursorApiClient(
            // Loaded list does NOT contain "old-model".
            onListModels = { ListModelsResponse(items = listOf(ModelListItem("m1", "M1"))) },
            onCreateAgent = { req -> captured = req; createAgentResponse() },
        )

        val vm = viewModel(api)
        // loadModels validated and dropped the unsupported id.
        vm.awaitState { it.models.isNotEmpty() }
        assertNull("loadModels drops the unsupported restored id", vm.uiState.value.selectedModelId)
        vm.setPrompt("do the thing")

        vm.launch()
        vm.awaitState { it.launchedAgentId != null }
        assertNull("a restored id absent from the loaded list must not be sent", captured?.model)
    }

    @Test
    fun launch_whenRestoredModelStillOffered_honorsIt() = runBlocking {
        // Happy path must not regress: a valid restored model is honored once the list contains it.
        preferenceProfileStore.saveLaunchDefaults(
            LaunchDefaults(repoUrl = "repoA", modelId = "m1", autoCreatePr = true, mode = "agent"),
        )
        repoCache.save(listOf("repoA"), nowEpochMs = 1)
        var captured: CreateAgentRequest? = null
        val api = FakeCursorApiClient(
            onListModels = { ListModelsResponse(items = listOf(ModelListItem("m1", "M1"))) },
            onCreateAgent = { req -> captured = req; createAgentResponse() },
        )

        val vm = viewModel(api)
        vm.awaitState { it.selectedModelId == "m1" && it.models.isNotEmpty() }
        vm.setPrompt("do the thing")

        vm.launch()
        vm.awaitState { it.launchedAgentId != null }
        assertEquals("a still-offered restored model must be honored", ModelRef("m1"), captured?.model)
    }

    // ---- R5: a failing launchDefaults() restore must fall back, not crash ----

    @Test
    fun init_whenLaunchDefaultsThrows_fallsBackToFactoryDefaults_andDoesNotCrash() = runBlocking {
        repoCache.save(listOf("repoA"), nowEpochMs = 1)
        val failingProfileStore = PreferenceProfileStore(ThrowingPreferenceProfileDao())
        val api = FakeCursorApiClient(
            onListModels = { ListModelsResponse(items = listOf(ModelListItem("m1", "M1"))) },
        )
        val vm = LaunchViewModel(
            api, repoCache, promptStore, runModeStore, failingProfileStore, writeCoordinator,
        )

        // No crash; the screen lands on factory defaults rather than failing to compose.
        val state = vm.awaitState { it.repoUrls.isNotEmpty() }
        assertFalse("factory default: plan mode off", state.planMode)
        assertTrue("factory default: auto-create PR on", state.autoCreatePr)
        assertNull("factory default: no model selected", state.selectedModelId)
    }

    private fun createAgentResponse() = CreateAgentResponse(
        agent = CloudAgent(id = "agent1", status = "ACTIVE", createdAt = "t", updatedAt = "t"),
        run = Run(id = "run1", agentId = "agent1", status = "RUNNING", createdAt = "t", updatedAt = "t"),
    )

    /** A [PreferenceProfileDao] whose reads fail, to exercise the launchDefaults() crash guard. */
    private class ThrowingPreferenceProfileDao : PreferenceProfileDao {
        override suspend fun insertIfAbsent(profile: PreferenceProfileEntity): Unit = throw IOException("db down")
        override suspend fun upsert(profile: PreferenceProfileEntity): Unit = throw IOException("db down")
        override suspend fun profile(id: Long): PreferenceProfileEntity? = throw IOException("db down")
        override suspend fun allProfiles(): List<PreferenceProfileEntity> = emptyList()
        override suspend fun clear() = Unit
    }

    private suspend fun LaunchViewModel.awaitState(predicate: (LaunchUiState) -> Boolean): LaunchUiState =
        withTimeout(10_000) { uiState.first(predicate) }
}
