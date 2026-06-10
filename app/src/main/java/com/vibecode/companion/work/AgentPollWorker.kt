package com.vibecode.companion.work

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vibecode.companion.CompanionApp
import com.vibecode.companion.data.api.CursorApiException
import com.vibecode.companion.data.api.Run
import com.vibecode.companion.data.api.RunStatus
import com.vibecode.companion.data.storage.companionDataStore
import com.vibecode.companion.notifications.AgentNotifications
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Periodic poll for terminal runs (local-notification MVP — replaced by
 * webhook→FCM push in milestone 2). Compares each agent's latest run status
 * against the last known status persisted in DataStore and notifies on
 * transitions into FINISHED / ERROR. Budget: at most ~6 API calls per poll
 * (1 list + up to 5 run fetches).
 */
class AgentPollWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private companion object {
        val PREF_RUN_STATUSES = stringPreferencesKey("last_known_run_statuses")
        val STATUS_MAP_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
        const val MAX_RUN_FETCHES = 5
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
            val current = mutableMapOf<String, String>()

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
                val justReachedTerminal = prior != null &&
                    !RunStatus.isTerminal(prior) &&
                    (run.status == RunStatus.FINISHED || run.status == RunStatus.ERROR)
                if (justReachedTerminal) {
                    AgentNotifications.notifyRunTerminal(
                        context = applicationContext,
                        agentId = agent.id,
                        agentName = agent.name,
                        status = run.status,
                        prUrl = run.git?.branches?.firstNotNullOfOrNull { it.prUrl },
                    )
                }
            }

            // Prune to the runs seen this poll so the map never grows unbounded.
            writeStatuses(current)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
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

    private suspend fun writeStatuses(statuses: Map<String, String>) {
        val encoded = json.encodeToString(STATUS_MAP_SERIALIZER, statuses)
        applicationContext.companionDataStore.edit { it[PREF_RUN_STATUSES] = encoded }
    }
}
