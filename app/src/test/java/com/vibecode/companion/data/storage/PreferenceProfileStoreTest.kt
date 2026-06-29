package com.vibecode.companion.data.storage

import androidx.room.Room
import com.vibecode.companion.data.storage.db.CompanionDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Tests [PreferenceProfileStore]'s seed-and-restore behavior over an in-memory Room database —
 * the contract the launch screen relies on to restore a returning user's defaults.
 */
@RunWith(RobolectricTestRunner::class)
class PreferenceProfileStoreTest {

    private lateinit var db: CompanionDatabase
    private lateinit var store: PreferenceProfileStore

    /** Builds a throwaway in-memory database + store before each test. */
    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CompanionDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = PreferenceProfileStore(db.preferenceProfileDao())
    }

    /** Closes the in-memory database after each test. */
    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun launchDefaults_seedsFactoryDefaultsOnFirstRead() = runBlocking {
        assertEquals(PreferenceProfileStore.FACTORY_DEFAULTS, store.launchDefaults())
        // Reading seeds exactly one row (the default profile), not a fresh one each call.
        assertEquals(1, db.preferenceProfileDao().allProfiles().size)
    }

    @Test
    fun saveLaunchDefaults_roundTrips() = runBlocking {
        val saved = LaunchDefaults(
            repoUrl = "https://github.com/acme/web",
            modelId = "claude-opus",
            autoCreatePr = false,
            mode = "plan",
        )
        store.saveLaunchDefaults(saved)

        assertEquals(saved, store.launchDefaults())
        // Still a single profile — saving updates the default row in place.
        assertEquals(1, db.preferenceProfileDao().allProfiles().size)
    }

    @Test
    fun ensureSeeded_doesNotOverwriteSavedDefaults() = runBlocking {
        val saved = LaunchDefaults(repoUrl = "https://github.com/acme/web", modelId = null, autoCreatePr = true, mode = "agent")
        store.saveLaunchDefaults(saved)

        store.ensureSeeded()

        assertEquals(saved, store.launchDefaults())
    }
}
