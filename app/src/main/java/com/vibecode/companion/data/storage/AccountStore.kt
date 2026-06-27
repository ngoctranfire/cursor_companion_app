package com.vibecode.companion.data.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.vibecode.companion.di.AppContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Account-level operations over the shared DataStore. Today that's just sign-out;
 * when per-account scoping lands this is the seam that moves into `AccountScope`.
 *
 * Replaces the old `AppContainer.clearAccountData()` so the sign-out path can be
 * constructor-injected into [com.vibecode.companion.ui.agents.AgentListViewModel].
 */
@Inject
@SingleIn(AppScope::class)
class AccountStore(@AppContext private val context: Context) {

    /**
     * Sign-out: everything in the shared DataStore is account-scoped (encrypted API key,
     * repo cache, poll-status baselines), so wipe it wholesale rather than leaking the
     * previous account's data to the next one.
     */
    suspend fun clearAccountData() {
        context.companionDataStore.edit { it.clear() }
    }
}
