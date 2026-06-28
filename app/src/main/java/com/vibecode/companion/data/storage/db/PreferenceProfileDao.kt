package com.vibecode.companion.data.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

/**
 * Data access for [PreferenceProfileEntity] — the user's saved launch defaults.
 *
 * Plain class, no DI annotations; wiring lives in `di/`. Reads are one-shot suspend calls (the
 * launch screen reads defaults once on open).
 */
@Dao
interface PreferenceProfileDao {

    /**
     * Seeds a profile only if its primary key is free ([OnConflictStrategy.IGNORE]) — used to
     * lay down the factory default without clobbering a profile the user has since edited.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(profile: PreferenceProfileEntity)

    /** Inserts or updates [profile] by primary key — used when the user's defaults change. */
    @Upsert
    suspend fun upsert(profile: PreferenceProfileEntity)

    /** The profile with [id], or `null` if it hasn't been seeded yet. */
    @Query("SELECT * FROM preference_profiles WHERE id = :id")
    suspend fun profile(id: Long): PreferenceProfileEntity?

    /** All profiles ordered by [PreferenceProfileEntity.id] — for future multi-profile UI/tests. */
    @Query("SELECT * FROM preference_profiles ORDER BY id")
    suspend fun allProfiles(): List<PreferenceProfileEntity>

    /** Wipes every profile — used by the sign-out account-data reset. */
    @Query("DELETE FROM preference_profiles")
    suspend fun clear()
}
