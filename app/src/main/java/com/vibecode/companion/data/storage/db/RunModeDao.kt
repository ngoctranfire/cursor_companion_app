package com.vibecode.companion.data.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access for [RunModeEntity] — the per-run launch-mode log.
 *
 * Most reads are suspend (one-shot); [modeForRunFlow] is the exception — the detail screen
 * *observes* the newest run's mode so a mode persisted **after** the screen loaded (the launch
 * screen records the initial run's mode in the background, after navigation) still lights up
 * CUR-8's "Build" action. Plain class, no DI annotations — wiring lives in `di/`.
 */
@Dao
interface RunModeDao {

    /** Records (or overwrites) the mode for a run; re-recording the same [RunModeEntity.runId] replaces it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RunModeEntity)

    /** The mode a specific run was created in, or `null` if this app never recorded one for it. */
    @Query("SELECT mode FROM run_modes WHERE runId = :runId")
    suspend fun modeForRun(runId: String): String?

    /**
     * Observes [runId]'s mode, emitting `null` until one is recorded and the new value whenever it
     * changes. Lets the detail screen pick up a mode persisted *after* it loaded (e.g. the launch
     * screen's background `recordMode` for the initial run) instead of staying stuck on the
     * one-shot snapshot it read at load time.
     */
    @Query("SELECT mode FROM run_modes WHERE runId = :runId")
    fun modeForRunFlow(runId: String): Flow<String?>

    /**
     * The most recently recorded mode for [agentId] (newest [RunModeEntity.recordedAtEpochMs]
     * wins; [RunModeEntity.runId] breaks ties for determinism), or `null` if none recorded.
     */
    @Query(
        "SELECT mode FROM run_modes WHERE agentId = :agentId " +
            "ORDER BY recordedAtEpochMs DESC, runId DESC LIMIT 1",
    )
    suspend fun latestModeForAgent(agentId: String): String?

    /** All recorded modes for [agentId], newest first — for diagnostics/tests. */
    @Query("SELECT * FROM run_modes WHERE agentId = :agentId ORDER BY recordedAtEpochMs DESC, runId DESC")
    suspend fun runModesForAgent(agentId: String): List<RunModeEntity>

    /**
     * Wipes every recorded run mode. Sign-out clears the whole database via
     * `CompanionDatabase.clearAllTables()`; this targeted delete is for tests/diagnostics.
     */
    @Query("DELETE FROM run_modes")
    suspend fun clear()
}
