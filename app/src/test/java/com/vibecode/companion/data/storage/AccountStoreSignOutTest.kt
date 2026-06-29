package com.vibecode.companion.data.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import com.vibecode.companion.data.storage.db.CompanionDatabase
import com.vibecode.companion.data.storage.db.PreferenceProfileEntity
import com.vibecode.companion.data.storage.db.RunModeEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException

/**
 * Guards the sign-out data-loss/privacy path: [AccountStore.clearAccountData] must wipe **both**
 * backends — the shared DataStore *and* every Room table — so the next account never inherits the
 * previous one's local state, and it must drain in-flight fire-and-forget writes first so none can
 * resurrect the wiped account's data.
 */
@RunWith(RobolectricTestRunner::class)
class AccountStoreSignOutTest {

    private lateinit var context: Context
    private lateinit var db: CompanionDatabase
    private lateinit var appScope: CoroutineScope
    private lateinit var writeCoordinator: AccountWriteCoordinator
    private lateinit var accountStore: AccountStore

    /** Builds a real context + in-memory Room database before the test, and isolates the DataStore. */
    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, CompanionDatabase::class.java)
            .allowMainThreadQueries().build()
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        writeCoordinator = AccountWriteCoordinator(appScope)
        accountStore = AccountStore(context, db, writeCoordinator)
        // The shared DataStore is process-global, so a key left behind by one case (e.g. the
        // Room-clear-fails path, which deliberately never reaches the DataStore wipe) would leak
        // into the next. Clear it on the way in AND out so ordering can't make the suite flaky.
        runBlocking { context.companionDataStore.edit { it.clear() } }
    }

    /** Closes the in-memory database and re-isolates the shared DataStore after the test. */
    @After
    fun tearDown() {
        appScope.cancel()
        db.close()
        runBlocking { context.companionDataStore.edit { it.clear() } }
    }

    @Test
    fun clearAccountData_wipesDataStoreAndRoom() = runBlocking {
        // Seed Room (both tables) ...
        db.runModeDao().upsert(RunModeEntity(runId = "run1", agentId = "agentA", mode = "plan", recordedAtEpochMs = 1))
        db.preferenceProfileDao().upsert(
            PreferenceProfileEntity(
                id = 1,
                name = "Default",
                defaultRepoUrl = "https://github.com/acme/web",
                defaultModelId = null,
                autoCreatePr = true,
                defaultMode = "agent",
            ),
        )
        // ... and the shared DataStore.
        val probeKey = stringPreferencesKey("signout_probe")
        context.companionDataStore.edit { it[probeKey] = "present" }
        // Sanity-check the seed landed.
        assertEquals("plan", db.runModeDao().latestModeForAgent("agentA"))
        assertEquals("present", context.companionDataStore.data.first()[probeKey])

        accountStore.clearAccountData()

        // Room: both tables empty.
        assertNull(db.runModeDao().latestModeForAgent("agentA"))
        assertEquals(0, db.runModeDao().runModesForAgent("agentA").size)
        assertEquals(0, db.preferenceProfileDao().allProfiles().size)
        // DataStore: cleared.
        assertNull(context.companionDataStore.data.first()[probeKey])
    }

    /**
     * The wipe is all-or-nothing the *safe* way: Room is cleared first, so if it fails the
     * DataStore (the previous account's API key + state) is left fully intact rather than
     * half-wiped — and the failure surfaces as [IOException], the contract `signOut()` handles.
     */
    @Test
    fun clearAccountData_whenRoomClearFails_leavesDataStoreIntact_andSurfacesIoException() = runBlocking {
        // Seed the shared DataStore — this stands in for the previous account's key/state.
        val probeKey = stringPreferencesKey("signout_probe")
        context.companionDataStore.edit { it[probeKey] = "present" }
        // Force the Room wipe to throw deterministically: drop a table that clearAllTables()'s
        // generated `DELETE FROM run_modes` targets, so the wipe fails with "no such table".
        // (Merely closing the db doesn't work — Room transparently reopens it.)
        db.openHelper.writableDatabase.execSQL("DROP TABLE run_modes")

        val error = runCatching { accountStore.clearAccountData() }.exceptionOrNull()

        // Failure is mapped to IOException (so signOut()'s existing catch surfaces a snackbar)...
        assertTrue("expected IOException but was $error", error is IOException)
        // ...and because Room is wiped first, the DataStore was never touched.
        assertEquals("present", context.companionDataStore.data.first()[probeKey])
    }

    /**
     * Regression for the in-flight-write cross-account leak: a fire-and-forget account write that
     * is *in flight* when sign-out begins must be cancelled before the wipe, so it can never
     * resurrect the previous account's data into the freshly cleared stores. Whatever the
     * interleaving, the stores must end up empty.
     */
    @Test
    fun clearAccountData_drainsInFlightWrite_soItCannotResurrectData() = runBlocking {
        val probeKey = stringPreferencesKey("resurrected")
        val writeStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // A durable account write that parks mid-flight, then (if it survived) would write the
        // previous account's data back into BOTH stores.
        writeCoordinator.launchWrite {
            writeStarted.complete(Unit)
            release.await()
            db.runModeDao().upsert(
                RunModeEntity(runId = "ghost", agentId = "ghostAgent", mode = "plan", recordedAtEpochMs = 1),
            )
            context.companionDataStore.edit { it[probeKey] = "leaked" }
        }
        writeStarted.await() // the write is now parked, i.e. in flight when sign-out runs

        // Sign out: clearAccountData() must cancel+join the in-flight write, THEN wipe.
        accountStore.clearAccountData()
        // Unblock the (now-cancelled) write — it must NOT resurrect anything. In the buggy shape
        // (write on an un-cancelled app scope) this is exactly when the leak would land.
        release.complete(Unit)

        // Both stores end up empty: the write was drained before the wipe, so nothing it would
        // have written survives.
        assertNull("Room must not carry resurrected run-mode data", db.runModeDao().modeForRun("ghost"))
        assertNull("DataStore must not carry resurrected data", context.companionDataStore.data.first()[probeKey])
    }
}
