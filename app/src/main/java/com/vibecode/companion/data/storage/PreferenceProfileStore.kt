package com.vibecode.companion.data.storage

import com.vibecode.companion.data.storage.db.PreferenceProfileDao
import com.vibecode.companion.data.storage.db.PreferenceProfileEntity

/**
 * The launch-default options the launch screen restores for a returning user.
 *
 * Mirrors the in-memory options in `LaunchUiState`: [repoUrl]/[modelId] are nullable
 * (no repo selected / the server's default model) and [mode] is the raw API mode string
 * (see `data.api.AgentMode`). Decouples ViewModels from the Room entity type.
 */
data class LaunchDefaults(
    val repoUrl: String?,
    val modelId: String?,
    val autoCreatePr: Boolean,
    val mode: String,
)

/**
 * Reads and writes the user's saved launch defaults.
 *
 * Today it manages a single seeded "Default" profile ([DEFAULT_PROFILE_ID]); the underlying
 * [PreferenceProfileEntity] schema already carries an id/name so multiple named profiles can be
 * added later without a migration. A thin wrapper over [PreferenceProfileDao], mirroring the
 * existing DataStore-backed `*Store` convention and keeping Room types out of the ViewModels.
 */
class PreferenceProfileStore(private val dao: PreferenceProfileDao) {

    companion object {
        /** Primary key of the single default profile used until multi-profile support lands. */
        const val DEFAULT_PROFILE_ID = 1L

        /** Display name of the seeded default profile. */
        const val DEFAULT_PROFILE_NAME = "Default"

        /**
         * Factory defaults for a fresh install — they mirror `LaunchUiState`'s in-memory defaults
         * (auto-create-PR on, plan mode off → `"agent"`, matching `data.api.AgentMode.AGENT`).
         */
        val FACTORY_DEFAULTS = LaunchDefaults(
            repoUrl = null,
            modelId = null,
            autoCreatePr = true,
            mode = "agent",
        )
    }

    /** Lays down the factory [DEFAULT_PROFILE_ID] profile if it doesn't exist yet (idempotent). */
    suspend fun ensureSeeded() {
        dao.insertIfAbsent(FACTORY_DEFAULTS.toEntity())
    }

    /**
     * The default profile's launch defaults, seeding the factory profile first if absent so a
     * first-open and a wiped (signed-out) database both return sensible values.
     */
    suspend fun launchDefaults(): LaunchDefaults {
        ensureSeeded()
        return dao.profile(DEFAULT_PROFILE_ID)?.toLaunchDefaults() ?: FACTORY_DEFAULTS
    }

    /** Persists [defaults] as the default profile so the next launch restores them. */
    suspend fun saveLaunchDefaults(defaults: LaunchDefaults) {
        dao.upsert(defaults.toEntity())
    }

    private fun LaunchDefaults.toEntity(): PreferenceProfileEntity = PreferenceProfileEntity(
        id = DEFAULT_PROFILE_ID,
        name = DEFAULT_PROFILE_NAME,
        defaultRepoUrl = repoUrl,
        defaultModelId = modelId,
        autoCreatePr = autoCreatePr,
        defaultMode = mode,
    )

    private fun PreferenceProfileEntity.toLaunchDefaults(): LaunchDefaults = LaunchDefaults(
        repoUrl = defaultRepoUrl,
        modelId = defaultModelId,
        autoCreatePr = autoCreatePr,
        mode = defaultMode,
    )
}
