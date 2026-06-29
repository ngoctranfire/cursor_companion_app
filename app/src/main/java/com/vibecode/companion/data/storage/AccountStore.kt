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
     * for the next account to inherit, defeating the isolation this wipe exists to provide. The
     * auth token (which lives in the DataStore) is therefore cleared **only after** Room succeeds:
     * a new account can sign in only once the token is gone, and by then the rows it could inherit
     * are already gone too.
     *
     * The two clears are attempted best-effort and the *outcome* is gated on the auth-token clear
     * specifically. If the DataStore clear throws **after** Room was already wiped, the token
     * survives — so the user stays signed in on the **same** account (no cross-account exposure)
     * and can retry deterministically (the Room clear is idempotent once empty) rather than being
     * left in an inconsistent "data destroyed, session still live" limbo with no recovery. Any
     * failure is surfaced as [IOException] so the sign-out caller's existing handling kicks in
     * (snackbar, stay signed in) instead of crashing on an unhandled exception.
     *
     * @throws IOException if the wipe did not complete (the auth token was not cleared).
     */
    suspend fun clearAccountData() = writeCoordinator.wipe {
        var failure: Throwable? = null

        // Room first (Cluster A): the auth token below is cleared only if this succeeds, so a Room
        // failure leaves the previous account fully intact — no new account can inherit the rows.
        // clearAllTables() runs its own transaction and must not be called on the main thread.
        val roomCleared = try {
            withContext(Dispatchers.IO) { database.clearAllTables() }
            true
        } catch (e: CancellationException) {
            throw e // never swallow cancellation — structured concurrency depends on it
        } catch (e: Exception) {
            failure = e
            false
        }

        var sessionCleared = false
        if (roomCleared) {
            // The Room tables are wiped; drop the in-memory launch → detail mode bridge too so no
            // remembered-but-not-yet-persisted mode survives the wipe (the durable rows are gone).
            runModeStore.clearPending()
            // Clear the auth token (and the rest of the shared DataStore) last, in isolation. If
            // this throws, Room is already gone but the token survives → the user stays signed in
            // on the SAME account and retries; a *new* account can never read the half-wiped store.
            sessionCleared = try {
                clearSessionStore()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                false
            }
        }

        // The sign-out only completes — and the caller only navigates away — when the auth token is
        // gone. Otherwise surface IOException so AgentListViewModel keeps the user signed in.
        if (!sessionCleared) {
            throw IOException("Failed to clear account data on sign-out", failure)
        }
    }

    /**
     * Clears the shared DataStore that holds the auth token (and the rest of the account's
     * preference state). A seam (not a hard-coded call) so a test can deterministically force the
     * "DataStore clear fails after Room already cleared" partial-failure path; production always
     * uses the real wipe below.
     */
    internal var clearSessionStore: suspend () -> Unit = {
        context.companionDataStore.edit { it.clear() }
    }
}
