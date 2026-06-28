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
 * Unit tests for [RunModeDao] against a real (in-memory) Room database under Robolectric, so the
 * generated SQL and the `latestModeForAgent` ordering are exercised for real rather than mocked.
 */
@RunWith(RobolectricTestRunner::class)
class RunModeDaoTest {

    private lateinit var db: CompanionDatabase
    private lateinit var dao: RunModeDao

    /** Builds a throwaway in-memory database before each test. */
    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            CompanionDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.runModeDao()
    }

    /** Closes the in-memory database after each test. */
    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun modeForRun_returnsRecordedMode_orNull() = runBlocking {
        dao.upsert(RunModeEntity(runId = "run1", agentId = "agentA", mode = "plan", recordedAtEpochMs = 100))

        assertEquals("plan", dao.modeForRun("run1"))
        assertNull(dao.modeForRun("does-not-exist"))
    }

    @Test
    fun upsert_replacesTheSameRun() = runBlocking {
        dao.upsert(RunModeEntity(runId = "run1", agentId = "agentA", mode = "plan", recordedAtEpochMs = 100))
        dao.upsert(RunModeEntity(runId = "run1", agentId = "agentA", mode = "agent", recordedAtEpochMs = 200))

        assertEquals("agent", dao.modeForRun("run1"))
        assertEquals(1, dao.runModesForAgent("agentA").size)
    }

    @Test
    fun latestModeForAgent_returnsNewestByTimestamp() = runBlocking {
        dao.upsert(RunModeEntity(runId = "run1", agentId = "agentA", mode = "plan", recordedAtEpochMs = 100))
        dao.upsert(RunModeEntity(runId = "run2", agentId = "agentA", mode = "agent", recordedAtEpochMs = 300))
        dao.upsert(RunModeEntity(runId = "run3", agentId = "agentA", mode = "plan", recordedAtEpochMs = 200))

        assertEquals("agent", dao.latestModeForAgent("agentA"))
    }

    @Test
    fun latestModeForAgent_breaksTimestampTiesByRunIdDesc() = runBlocking {
        // All three share recordedAtEpochMs, so the `runId DESC` tie-breaker decides the winner
        // deterministically — "run-c" is the greatest runId and carries "agent".
        dao.upsert(RunModeEntity(runId = "run-a", agentId = "agentA", mode = "plan", recordedAtEpochMs = 500))
        dao.upsert(RunModeEntity(runId = "run-c", agentId = "agentA", mode = "agent", recordedAtEpochMs = 500))
        dao.upsert(RunModeEntity(runId = "run-b", agentId = "agentA", mode = "plan", recordedAtEpochMs = 500))

        assertEquals("agent", dao.latestModeForAgent("agentA"))
    }

    @Test
    fun latestModeForAgent_isScopedPerAgent() = runBlocking {
        dao.upsert(RunModeEntity(runId = "run1", agentId = "agentA", mode = "plan", recordedAtEpochMs = 100))
        dao.upsert(RunModeEntity(runId = "run2", agentId = "agentB", mode = "agent", recordedAtEpochMs = 200))

        assertEquals("plan", dao.latestModeForAgent("agentA"))
        assertEquals("agent", dao.latestModeForAgent("agentB"))
        assertNull(dao.latestModeForAgent("unknown-agent"))
    }

    @Test
    fun clear_removesEveryRow() = runBlocking {
        dao.upsert(RunModeEntity(runId = "run1", agentId = "agentA", mode = "plan", recordedAtEpochMs = 100))

        dao.clear()

        assertNull(dao.latestModeForAgent("agentA"))
        assertEquals(0, dao.runModesForAgent("agentA").size)
    }
}
