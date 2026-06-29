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
    private val writeCoordinator: AccountWriteCoordinator,
    private val runModeStore: RunModeStore,
) {

    /**
     * Sign-out: wipe everything account-scoped so the next account never sees the previous
     * one's data. That means both backing stores — the Room database (run-mode history,
     * preference profiles) has all its tables emptied, and the shared DataStore (encrypted API
     * key, repo cache, prompt history, poll-status baselines) is cleared wholesale. The Room
     * schema and the Keystore-held AES key survive; only their *contents* go. The default
     * preference profile is re-seeded lazily on the next launch (see [PreferenceProfileStore]).
     *
     * The wipe runs through [AccountWriteCoordinator.wipe], which cancels and joins any in-flight
     * fire-and-forget account write (e.g. `LaunchViewModel`'s post-launch bookkeeping) **before**
     * clearing the stores. Without that, a write still in flight at sign-out could persist the
     * *previous* account's data back into the freshly cleared stores, re-opening the cross-account
     * leak through a new race.
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
    suspend fun clearAccountData() = writeCoordinator.wipe {
        // clearAllTables() runs its own transaction and must not be called on the main thread.
        try {
            withContext(Dispatchers.IO) { database.clearAllTables() }
        } catch (e: CancellationException) {
            throw e // never swallow cancellation — structured concurrency depends on it
        } catch (e: Exception) {
            throw IOException("Failed to clear the local database on sign-out", e)
        }
        // The Room tables are wiped; drop the in-memory launch → detail mode bridge too so no
        // remembered-but-not-yet-persisted mode survives the wipe (the durable rows are gone).
        runModeStore.clearPending()
        context.companionDataStore.edit { it.clear() }
    }
}
