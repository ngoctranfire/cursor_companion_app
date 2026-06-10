package com.vibecode.companion.data.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Cache for GET /v1/repositories. That endpoint is limited to 1 request per user
 * per minute (30/hour) and can take tens of seconds, so the UI must read from
 * here and refresh sparingly.
 */
class RepoCache(private val context: Context) {

    companion object {
        private val PREF_REPO_URLS = stringPreferencesKey("cached_repo_urls")
        private val PREF_FETCHED_AT = longPreferencesKey("cached_repo_fetched_at")
        private const val SEPARATOR = "\n"

        /** Default freshness window before the UI offers a refresh. */
        const val STALE_AFTER_MS: Long = 6 * 60 * 60 * 1000L
    }

    data class CachedRepos(val urls: List<String>, val fetchedAtEpochMs: Long) {
        fun isStale(nowEpochMs: Long): Boolean = nowEpochMs - fetchedAtEpochMs > STALE_AFTER_MS
    }

    val repos: Flow<CachedRepos?> = context.companionDataStore.data.map { prefs ->
        val raw = prefs[PREF_REPO_URLS] ?: return@map null
        val fetchedAt = prefs[PREF_FETCHED_AT] ?: 0L
        CachedRepos(raw.split(SEPARATOR).filter { it.isNotBlank() }, fetchedAt)
    }

    suspend fun get(): CachedRepos? = repos.first()

    suspend fun save(urls: List<String>, nowEpochMs: Long) {
        context.companionDataStore.edit {
            it[PREF_REPO_URLS] = urls.joinToString(SEPARATOR)
            it[PREF_FETCHED_AT] = nowEpochMs
        }
    }
}
