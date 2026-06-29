package com.vibecode.companion.data.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Local memory of what the user asked each run to do. The Cloud Agents API
 * never returns prompt text (runs carry only results, and the SSE replay has
 * no user-message event), so the app remembers prompts it sent itself —
 * keyed by run id, bounded, and wiped with the rest of the account data on
 * sign-out. Runs launched outside this app simply have no stored prompt.
 */
open class PromptStore(private val context: Context) {

    companion object {
        private val PREF_RUN_PROMPTS = stringPreferencesKey("run_prompts")
        private const val MAX_ENTRIES = 200
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    // `open` so tests can supply a save-failing double (the store has no DAO seam to throw through);
    // production never overrides it.
    open suspend fun save(runId: String, prompt: String) {
        context.companionDataStore.edit { prefs ->
            val map = decode(prefs[PREF_RUN_PROMPTS]).toMutableMap()
            map[runId] = prompt
            // LinkedHashMap preserves insertion order — drop the oldest entries.
            while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
            prefs[PREF_RUN_PROMPTS] = json.encodeToString(serializer, map)
        }
    }

    suspend fun get(runId: String): String? =
        decode(context.companionDataStore.data.first()[PREF_RUN_PROMPTS])[runId]

    private fun decode(raw: String?): Map<String, String> = if (raw == null) {
        emptyMap()
    } else {
        try {
            json.decodeFromString(serializer, raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
