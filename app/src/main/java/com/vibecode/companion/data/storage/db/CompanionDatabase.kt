package com.vibecode.companion.data.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's Room (SQLite) database — the queryable/relational local store introduced in CUR-9
 * (see `docs/adr/ADR-002-adopt-room.md`). It holds state that DataStore Preferences can't model
 * well: the per-run [RunModeEntity] history and [PreferenceProfileEntity] launch defaults.
 *
 * Construction is kept here (via [build]) so the data layer stays DI-framework-agnostic — the
 * Metro graph just calls [build] and `@Provides` the result (see `di/AppBindings.kt`), exactly
 * as it does for the DataStore-backed stores. The encrypted API token and the repo/prompt
 * caches deliberately stay on DataStore; only relational state lives here.
 *
 * `exportSchema = false` because there are no migrations yet (version 1). When the first
 * migration lands, flip it on, set `room.schemaLocation`, and add migration tests.
 */
@Database(
    entities = [RunModeEntity::class, PreferenceProfileEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class CompanionDatabase : RoomDatabase() {

    /** DAO for the per-run launch-mode log. */
    abstract fun runModeDao(): RunModeDao

    /** DAO for the saved launch-default profiles. */
    abstract fun preferenceProfileDao(): PreferenceProfileDao

    companion object {
        /** On-disk database file name. */
        const val DATABASE_NAME = "companion.db"

        /** Builds the on-disk database. Called once from the Metro graph and shared process-wide. */
        fun build(context: Context): CompanionDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                CompanionDatabase::class.java,
                DATABASE_NAME,
            ).build()
    }
}
