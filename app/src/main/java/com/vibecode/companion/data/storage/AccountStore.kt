package com.vibecode.companion.data.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.vibecode.companion.data.storage.db.CompanionDatabase
import com.vibecode.companion.di.AppContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Account-level operations over the shared local stores. Today that's just sign-out;
 * when per-account scoping lands this is the seam that moves into `AccountScope`.
 *
 * Replaces the old `AppContainer.clearAccountData()` so the sign-out path can be
 * constructor-injected into [com.vibecode.companion.ui.agents.AgentListViewModel].
 */
@Inject
@SingleIn(AppScope::class)
class AccountStore(
    @AppContext private val context: Context,
    private val database: CompanionDatabase,
) {

    /**
     * Sign-out: wipe everything account-scoped so the next account never sees the previous
     * one's data. That means both backing stores — the Room database (run-mode history,
     * preference profiles) has all its tables emptied, and the shared DataStore (encrypted API
     * key, repo cache, prompt history, poll-status baselines) is cleared wholesale. The Room
     * schema and the Keystore-held AES key survive; only their *contents* go. The default
     * preference profile is re-seeded lazily on the next launch (see [PreferenceProfileStore]).
     *
     * Ordering matters: Room is cleared **first** so a failure leaves the previous account fully
     * intact rather than half-wiped — if the DataStore (API key + state) were cleared first and
     * then the Room call threw, the `run_modes` / `preference_profiles` rows would linger on disk
     * for the next account to inherit, defeating the isolation this wipe exists to provide. Any
     * Room failure is mapped to [IOException] so the sign-out caller's existing error handling
     * surfaces it (snackbar, stay signed in) instead of crashing on an unhandled exception.
     *
     * @throws IOException if either backing store fails to clear.
     */
    suspend fun clearAccountData() {
        // clearAllTables() runs its own transaction and must not be called on the main thread.
        try {
            withContext(Dispatchers.IO) { database.clearAllTables() }
        } catch (e: CancellationException) {
            throw e // never swallow cancellation — structured concurrency depends on it
        } catch (e: Exception) {
            throw IOException("Failed to clear the local database on sign-out", e)
        }
        context.companionDataStore.edit { it.clear() }
    }
}
