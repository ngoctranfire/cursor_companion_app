package com.vibecode.companion.ui.launch

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import com.vibecode.companion.data.api.CloudAgent
import com.vibecode.companion.data.api.CreateAgentResponse
import com.vibecode.companion.data.api.ListModelsResponse
import com.vibecode.companion.data.api.ListRepositoriesResponse
import com.vibecode.companion.data.api.ModelListItem
import com.vibecode.companion.data.api.Repository
import com.vibecode.companion.data.api.Run
import com.vibecode.companion.data.storage.LaunchDefaults
import com.vibecode.companion.data.storage.PreferenceProfileStore
import com.vibecode.companion.data.storage.PromptStore
import com.vibecode.companion.data.storage.RepoCache
import com.vibecode.companion.data.storage.RunModeStore
import com.vibecode.companion.data.storage.companionDataStore
import com.vibecode.companion.data.storage.db.CompanionDatabase
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

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = RuntimeEnvironment.getApplication()
        runBlocking { context.companionDataStore.edit { it.clear() } } // isolate DataStore between tests
        db = Room.inMemoryDatabaseBuilder(context, CompanionDatabase::class.java)
            .allowMainThreadQueries().build()
        repoCache = RepoCache(context)
        promptStore = PromptStore(context)
        runModeStore = RunModeStore(db.runModeDao())
        preferenceProfileStore = PreferenceProfileStore(db.preferenceProfileDao())
        // Stand-in for the @AppCoroutineScope singleton — survives the (simulated) VM clear.
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        appScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(api: FakeCursorApiClient) = LaunchViewModel(
        api, repoCache, promptStore, runModeStore, preferenceProfileStore, appScope,
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
        assertEquals("repoA", preferenceProfileStore.launchDefaults().repoUrl)
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

    private suspend fun LaunchViewModel.awaitState(predicate: (LaunchUiState) -> Boolean): LaunchUiState =
        withTimeout(10_000) { uiState.first(predicate) }
}
