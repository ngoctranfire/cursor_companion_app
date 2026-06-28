package com.vibecode.companion.data.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.vibecode.companion.data.storage.db.CompanionDatabase
import com.vibecode.companion.di.AppContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     * one's data. That means both backing stores — the shared DataStore (encrypted API key,
     * repo cache, prompt history, poll-status baselines) is cleared wholesale, and the Room
     * database (run-mode history, preference profiles) has all its tables emptied. The Room
     * schema and the Keystore-held AES key survive; only their *contents* go. The default
     * preference profile is re-seeded lazily on the next launch (see [PreferenceProfileStore]).
     */
    suspend fun clearAccountData() {
        context.companionDataStore.edit { it.clear() }
        // clearAllTables() runs its own transaction and must not be called on the main thread.
        withContext(Dispatchers.IO) { database.clearAllTables() }
    }
}
