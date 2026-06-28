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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

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
}
