package com.vibecode.companion.data.storage.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One persisted launch mode (`plan` / `agent`) for a single Cursor run.
 *
 * The Cloud Agents API never echoes a run's mode back in any response, and the launch-time
 * choice is otherwise in-memory only — so this table is the one durable record of which mode
 * a run was created in. Rows are keyed by [runId] (one mode per run) and tagged with [agentId]
 * + [recordedAtEpochMs] so the *latest* mode for an agent can be looked up (the signal CUR-8's
 * "Build" action gates on). [mode] holds the raw API string (see `data.api.AgentMode`); it is
 * stored verbatim rather than as an enum to stay tolerant of values the beta API may add.
 */
@Entity(
    tableName = "run_modes",
    indices = [Index(value = ["agentId", "recordedAtEpochMs"])],
)
data class RunModeEntity(
    @PrimaryKey val runId: String,
    val agentId: String,
    val mode: String,
    val recordedAtEpochMs: Long,
)
