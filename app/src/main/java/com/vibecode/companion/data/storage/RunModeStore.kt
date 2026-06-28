package com.vibecode.companion.data.storage

import com.vibecode.companion.data.storage.db.RunModeDao
import com.vibecode.companion.data.storage.db.RunModeEntity

/**
 * Records and recalls the launch mode (`plan` / `agent`) of each Cursor run.
 *
 * The Cloud Agents API never returns a run's mode, so this is the only durable source of truth.
 * It backs two flows: the launch screen records the mode it created an agent in, follow-up runs
 * record the mode they inherit, and the detail screen reads the agent's *latest* mode (the
 * signal CUR-8's "Build" action will gate on). A thin wrapper over [RunModeDao] — it keeps Room
 * types out of the ViewModels and mirrors the existing DataStore-backed `*Store` convention.
 */
class RunModeStore(private val dao: RunModeDao) {

    /**
     * Records that [runId] (belonging to [agentId]) was created in [mode]. [recordedAtEpochMs]
     * orders the agent's history so [latestModeForAgent] can find the newest; it defaults to now.
     */
    suspend fun recordMode(
        runId: String,
        agentId: String,
        mode: String,
        recordedAtEpochMs: Long = System.currentTimeMillis(),
    ) {
        dao.upsert(RunModeEntity(runId = runId, agentId = agentId, mode = mode, recordedAtEpochMs = recordedAtEpochMs))
    }

    /** The mode [runId] was created in, or `null` if this app never recorded one for it. */
    suspend fun modeForRun(runId: String): String? = dao.modeForRun(runId)

    /** The most recently recorded mode for [agentId], or `null` if none is known. */
    suspend fun latestModeForAgent(agentId: String): String? = dao.latestModeForAgent(agentId)
}
