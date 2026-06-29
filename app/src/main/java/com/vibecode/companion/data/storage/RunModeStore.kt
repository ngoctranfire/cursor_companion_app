package com.vibecode.companion.data.storage

import com.vibecode.companion.data.storage.db.RunModeDao
import com.vibecode.companion.data.storage.db.RunModeEntity
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/**
 * Records and recalls the launch mode (`plan` / `agent`) of each Cursor run.
 *
 * The Cloud Agents API never returns a run's mode, so this is the only durable source of truth.
 * It backs two flows: the launch screen records the mode it created an agent in, follow-up runs
 * record the mode they inherit, and the detail screen reads the *newest run's* mode via
 * [modeForRun] (the signal CUR-8's "Build" action gates on) — deliberately keyed to a specific
 * run rather than the agent's last-recorded mode, which can lag the server's newest run. A thin
 * wrapper over [RunModeDao] — it keeps Room types out of the ViewModels and mirrors the existing
 * DataStore-backed `*Store` convention.
 */
class RunModeStore(private val dao: RunModeDao) {

    /**
     * In-memory bridge for the launch → detail handoff. The launch screen records the initial run's
     * mode on a background app-scope write **after** navigation (see `LaunchViewModel`), so a fast
     * follow-up on the detail screen can call [modeForRun] before that Room write lands. A one-shot
     * read would then return null, the follow-up would inherit no mode, and the new run's mode row
     * would never be written — leaving CUR-8's mode-gated "Build" hidden forever even though the
     * server kept running in the same mode. [remember] is called *synchronously* at launch so that
     * window resolves deterministically. Keyed by the server's globally-unique run id, so an entry
     * can never be served to a different run or account, and self-pruning: [recordMode] drops the
     * entry once the durable Room row exists. Sign-out clears it wholesale via [clearPending].
     */
    private val pendingModes = ConcurrentHashMap<String, String>()

    /**
     * Synchronously remembers the [mode] [runId] was launched in, so [modeForRun] can resolve it in
     * the window before [recordMode]'s Room write lands. In-memory only; [recordMode] persists it.
     */
    fun remember(runId: String, mode: String) {
        pendingModes[runId] = mode
    }

    /**
     * Records that [runId] (belonging to [agentId]) was created in [mode]. [recordedAtEpochMs]
     * timestamps the row for `RunModeDao.latestModeForAgent` history ordering; it defaults to now.
     */
    suspend fun recordMode(
        runId: String,
        agentId: String,
        mode: String,
        recordedAtEpochMs: Long = System.currentTimeMillis(),
    ) {
        dao.upsert(RunModeEntity(runId = runId, agentId = agentId, mode = mode, recordedAtEpochMs = recordedAtEpochMs))
        // The Room row is now the durable source of truth — drop the in-memory bridge entry.
        pendingModes.remove(runId)
    }

    /**
     * The mode [runId] was created in, or `null` if this app never recorded one for it. Falls back
     * to the in-memory [pendingModes] bridge so a fast follow-up still inherits a launch's mode
     * before its background Room write lands (see [pendingModes]).
     */
    suspend fun modeForRun(runId: String): String? = dao.modeForRun(runId) ?: pendingModes[runId]

    /** Drops the in-memory [pendingModes] bridge; called by the sign-out wipe alongside the Room clear. */
    fun clearPending() {
        pendingModes.clear()
    }

    /**
     * Observes [runId]'s mode, emitting `null` until one is recorded and then the recorded value.
     * The detail screen collects this so a mode persisted *after* it loaded (the launch screen
     * records the initial run's mode in the background, after navigation) updates the UI instead
     * of leaving the "Build" signal stuck on the load-time snapshot.
     */
    fun modeForRunFlow(runId: String): Flow<String?> = dao.modeForRunFlow(runId)
}
