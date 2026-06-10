package com.vibecode.companion.data.storage

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/** Single process-wide preferences DataStore shared by storage classes. */
val Context.companionDataStore by preferencesDataStore(name = "companion_prefs")
