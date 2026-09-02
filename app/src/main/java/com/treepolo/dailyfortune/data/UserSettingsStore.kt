package com.treepolo.dailyfortune.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.treepolo.dailyfortune.model.ZodiacSign
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "fortune_state")

interface ZodiacSettings {
    val selectedZodiac: Flow<ZodiacSign?>
    suspend fun currentZodiac(): ZodiacSign?
    suspend fun selectFirstZodiac(zodiac: ZodiacSign)
    suspend fun cleanupLegacyState()
}

/** DataStore now owns only small user settings. Fate/history data lives in Room. */
class UserSettingsStore(private val context: Context) : ZodiacSettings {
    override val selectedZodiac: Flow<ZodiacSign?> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[Keys.selectedZodiac]
                ?.let { runCatching { ZodiacSign.valueOf(it) }.getOrNull() }
        }

    override suspend fun currentZodiac(): ZodiacSign? = selectedZodiac.first()

    override suspend fun selectFirstZodiac(zodiac: ZodiacSign) {
        context.settingsDataStore.edit { preferences ->
            if (preferences[Keys.selectedZodiac] == null) {
                preferences[Keys.selectedZodiac] = zodiac.name
            }
        }
    }

    override suspend fun cleanupLegacyState() {
        context.settingsDataStore.edit { preferences ->
            if (preferences[Keys.roomMigrationCompleted] == true) return@edit

            Keys.legacyStringKeys.forEach(preferences::remove)
            Keys.legacyIntKeys.forEach(preferences::remove)
            Keys.legacyBooleanKeys.forEach(preferences::remove)
            preferences[Keys.roomMigrationCompleted] = true
        }
    }

    private object Keys {
        val selectedZodiac = stringPreferencesKey("selected_zodiac")
        val roomMigrationCompleted = booleanPreferencesKey("room_state_initialized_v1")

        val legacyStringKeys = listOf(
            stringPreferencesKey("today_date"),
            stringPreferencesKey("today_personal_sky_date"),
        )
        val legacyBooleanKeys = listOf(
            booleanPreferencesKey("today_seen"),
        )
        val legacyIntKeys = listOf(
            intPreferencesKey("today_reroll_count"),
            intPreferencesKey("total_rerolls"),
            intPreferencesKey("total_draw_days"),
            intPreferencesKey("max_daily_rerolls"),
            intPreferencesKey("total_draws"),
            intPreferencesKey("dai_ji_draws"),
            intPreferencesKey("non_xiong_draws"),
            intPreferencesKey("dai_xiong_draws"),
            intPreferencesKey("today_personal_overall"),
            intPreferencesKey("today_personal_wealth"),
            intPreferencesKey("today_personal_love"),
            intPreferencesKey("today_personal_work_study"),
            intPreferencesKey("today_personal_relationships"),
            intPreferencesKey("today_personal_health"),
        )
    }
}
