package com.deskbot.os.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "deskbot_consent_prefs")

class ConsentManager(private val context: Context) {
    companion object {
        val KEY_NOTIFICATION_CONSENT = booleanPreferencesKey("notif_disclosure_acknowledged")
    }

    val isConsentGranted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_NOTIFICATION_CONSENT] ?: false
    }

    suspend fun recordConsentGranted() {
        context.dataStore.edit { prefs ->
            prefs[KEY_NOTIFICATION_CONSENT] = true
        }
    }
}
