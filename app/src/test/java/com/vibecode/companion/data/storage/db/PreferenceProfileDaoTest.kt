package com.vibecode.companion.data.storage.db

import androidx.room.Room
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
 * Unit tests for [PreferenceProfileDao] against an in-memory Room database — verifies the
 * seed-if-absent vs upsert semantics the launch-default restore relies on.
 */
@RunWith(RobolectricTestRunner::class)
class PreferenceProfileDaoTest {

    private lateinit var db: CompanionDatabase
    private lateinit var dao: PreferenceProfileDao

    /** Builds a throwaway in-memory database before each test. */
    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CompanionDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.preferenceProfileDao()
    }

    /** Closes the in-memory database after each test. */
    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertIfAbsent_doesNotOverwriteExisting() = runBlocking {
        dao.insertIfAbsent(profile(id = 1, repo = "https://github.com/acme/web"))
        dao.insertIfAbsent(profile(id = 1, repo = "https://github.com/acme/other"))

        assertEquals("https://github.com/acme/web", dao.profile(1)?.defaultRepoUrl)
        assertEquals(1, dao.allProfiles().size)
    }

    @Test
    fun upsert_insertsThenUpdatesByPrimaryKey() = runBlocking {
        dao.upsert(profile(id = 1, repo = "https://github.com/acme/web"))
        dao.upsert(profile(id = 1, repo = "https://github.com/acme/web", autoCreatePr = false, mode = "plan"))

        val stored = dao.profile(1)
        assertEquals(false, stored?.autoCreatePr)
        assertEquals("plan", stored?.defaultMode)
        assertEquals(1, dao.allProfiles().size)
    }

    @Test
    fun profile_returnsNullWhenMissing() = runBlocking {
        assertNull(dao.profile(42))
    }

    @Test
    fun allProfiles_isOrderedById() = runBlocking {
        dao.upsert(profile(id = 2, repo = "b"))
        dao.upsert(profile(id = 1, repo = "a"))
        dao.upsert(profile(id = 3, repo = "c"))

        assertEquals(listOf(1L, 2L, 3L), dao.allProfiles().map { it.id })
    }

    @Test
    fun clear_removesEveryProfile() = runBlocking {
        dao.upsert(profile(id = 1, repo = "a"))

        dao.clear()

        assertEquals(0, dao.allProfiles().size)
    }

    private fun profile(
        id: Long,
        repo: String?,
        model: String? = null,
        autoCreatePr: Boolean = true,
        mode: String = "agent",
    ) = PreferenceProfileEntity(
        id = id,
        name = "Default",
        defaultRepoUrl = repo,
        defaultModelId = model,
        autoCreatePr = autoCreatePr,
        defaultMode = mode,
    )
}
