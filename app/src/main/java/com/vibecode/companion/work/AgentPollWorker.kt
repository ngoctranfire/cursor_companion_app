package com.vibecode.companion.work

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vibecode.companion.CompanionApp
import com.vibecode.companion.data.api.CursorApiException
import com.vibecode.companion.data.api.Run
import com.vibecode.companion.data.api.RunStatus
import com.vibecode.companion.data.storage.companionDataStore
import com.vibecode.companion.notifications.AgentNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant

/**
 * Periodic poll for terminal runs (local-notification MVP — replaced by
 * webhook→FCM push in milestone 2). Compares each agent's latest run status
 * against the last known status persisted in DataStore and notifies on
 * transitions into FINISHED / ERROR; runs first seen already terminal notify
 * when they were updated after the last completed poll. Budget: at most
 * ~6 API calls per poll (1 list + up to 5 run fetches).
 */
class AgentPollWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private companion object {
        val PREF_RUN_STATUSES = stringPreferencesKey("last_known_run_statuses")
        val PREF_LAST_POLL_COMPLETED_AT = longPreferencesKey("last_poll_completed_at")
        val STATUS_MAP_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
        const val MAX_RUN_FETCHES = 5
        const val MAX_ATTEMPTS = 3
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        return try {
            val container = (applicationContext as CompanionApp).container
            if (container.apiKeyStore.get() == null) return Result.success()

            val agents = container.apiClient
                .listAgents(limit = 10, includeArchived = false)
                .items

            val previous = readStatuses()
            val lastPollCompletedAt = readLastPollCompletedAt()
            // Seed from the previous map, kept to runs still attached to listed
            // agents (bounds growth). This poll's successful fetches overlay
            // below, so a run whose fetch fails keeps its previous status.
            val attachedRunIds = agents.mapNotNull { it.latestRunId }.toSet()
            val current = previous.filterKeys { it in attachedRunIds }.toMutableMap()

            val candidates = agents.filter { it.latestRunId != null }.take(MAX_RUN_FETCHES)
            for (agent in candidates) {
                val runId = agent.latestRunId ?: continue
                val run: Run = try {
                    container.apiClient.getRun(agent.id, runId)
                } catch (ex: CursorApiException) {
                    if (ex.isRateLimited) break // back off; the next slot retries
                    continue
                } catch (_: IOException) {
                    continue
                }
                current[run.id] = run.status

                val prior = previous[run.id]
                val isTerminalNow =
                    run.status == RunStatus.FINISHED || run.status == RunStatus.ERROR
                val justReachedTerminal = prior != null &&
                    !RunStatus.isTerminal(prior) &&
                    isTerminalNow
                // A run that started AND finished within one poll interval has
                // no prior status — notify if it was updated after the last
                // completed poll. No baseline yet (first poll after install)
                // suppresses, so historical runs don't spam.
                val finishedSinceLastPoll = prior == null && isTerminalNow &&
                    lastPollCompletedAt != null &&
                    updatedAfter(run.updatedAt, lastPollCompletedAt)
                if (justReachedTerminal || finishedSinceLastPoll) {
                    AgentNotifications.notifyRunTerminal(
                        context = applicationContext,
                        agentId = agent.id,
                        agentName = agent.name,
                        status = run.status,
                        prUrl = run.git?.branches?.firstNotNullOfOrNull { it.prUrl },
                    )
                }
            }

            writePollState(current)
            Result.success()
        } catch (ex: CancellationException) {
            // WorkManager cancelled us (or the process is going down) — never
            // swallow this, structured concurrency depends on it propagating.
            throw ex
        } catch (_: Exception) {
            // Periodic work: a few backoff retries, then wait for the next
            // 15-minute slot instead of hammering a persistent failure.
            if (runAttemptCount >= MAX_ATTEMPTS - 1) Result.failure() else Result.retry()
        }
    }

    private suspend fun readStatuses(): Map<String, String> {
        val raw = applicationContext.companionDataStore.data.first()[PREF_RUN_STATUSES]
            ?: return emptyMap()
        return try {
            json.decodeFromString(STATUS_MAP_SERIALIZER, raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun readLastPollCompletedAt(): Long? =
        applicationContext.companionDataStore.data.first()[PREF_LAST_POLL_COMPLETED_AT]

    private fun updatedAfter(updatedAt: String, epochMillis: Long): Boolean = try {
        Instant.parse(updatedAt).toEpochMilli() > epochMillis
    } catch (_: Exception) {
        false
    }

    private suspend fun writePollState(statuses: Map<String, String>) {
        val encoded = json.encodeToString(STATUS_MAP_SERIALIZER, statuses)
        applicationContext.companionDataStore.edit {
            it[PREF_RUN_STATUSES] = encoded
            it[PREF_LAST_POLL_COMPLETED_AT] = System.currentTimeMillis()
        }
    }
}
