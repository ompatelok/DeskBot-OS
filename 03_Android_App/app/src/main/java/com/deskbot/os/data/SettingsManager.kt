package com.deskbot.os.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "deskbot_user_settings")

data class ReminderSettings(
    val eyeIntervalMin: Int = 20,
    val waterIntervalMin: Int = 40,
    val buzzerEnabled: Boolean = true
)

class SettingsManager(private val context: Context) {
    companion object {
        val KEY_EYE_INTERVAL = intPreferencesKey("eye_interval_min")
        val KEY_WATER_INTERVAL = intPreferencesKey("water_interval_min")
        val KEY_BUZZER_ENABLED = booleanPreferencesKey("buzzer_enabled")
    }

    val reminderSettings: Flow<ReminderSettings> = context.settingsDataStore.data.map { prefs ->
        ReminderSettings(
            eyeIntervalMin = prefs[KEY_EYE_INTERVAL] ?: 20,
            waterIntervalMin = prefs[KEY_WATER_INTERVAL] ?: 40,
            buzzerEnabled = prefs[KEY_BUZZER_ENABLED] ?: true
        )
    }

    suspend fun updateEyeInterval(minutes: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_EYE_INTERVAL] = minutes.coerceIn(10, 60)
        }
    }

    suspend fun updateWaterInterval(minutes: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_WATER_INTERVAL] = minutes.coerceIn(15, 120)
        }
    }

    suspend fun setBuzzerEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_BUZZER_ENABLED] = enabled
        }
    }
}
