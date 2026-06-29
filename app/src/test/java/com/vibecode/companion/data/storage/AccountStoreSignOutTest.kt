package com.vibecode.companion.data.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import com.vibecode.companion.data.storage.db.CompanionDatabase
import com.vibecode.companion.data.storage.db.PreferenceProfileEntity
import com.vibecode.companion.data.storage.db.RunModeEntity
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
 * previous one's local state.
 */
@RunWith(RobolectricTestRunner::class)
class AccountStoreSignOutTest {

    private lateinit var context: Context
    private lateinit var db: CompanionDatabase
    private lateinit var accountStore: AccountStore

    /** Builds a real context + in-memory Room database before the test. */
    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, CompanionDatabase::class.java)
            .allowMainThreadQueries().build()
        accountStore = AccountStore(context, db)
    }

    /** Closes the in-memory database after the test. */
    @After
    fun tearDown() {
        db.close()
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
}
