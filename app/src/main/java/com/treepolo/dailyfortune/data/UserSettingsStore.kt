package com.treepolo.dailyfortune.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.treepolo.dailyfortune.model.FortuneStats
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
    suspend fun legacyStats(): FortuneStats? = null
}

/** DataStore owns small user settings and preserves pre-Room aggregate statistics for upgrade safety. */
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
            // Keep all legacy values. They cost almost nothing and let newer builds recover aggregate
            // statistics when the Room event history is empty or unavailable after an upgrade.
            preferences[Keys.roomMigrationCompleted] = true
        }
    }

    override suspend fun legacyStats(): FortuneStats? {
        val preferences = context.settingsDataStore.data
            .catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }
            .first()

        val hasLegacyStats = listOf(
            Keys.totalRerolls,
            Keys.totalDrawDays,
            Keys.maxDailyRerolls,
            Keys.totalDraws,
            Keys.daiJiDraws,
            Keys.nonXiongDraws,
            Keys.daiXiongDraws,
        ).any { preferences[it] != null }
        if (!hasLegacyStats) return null

        return FortuneStats(
            totalRerolls = preferences[Keys.totalRerolls] ?: 0,
            totalDrawDays = preferences[Keys.totalDrawDays] ?: 0,
            maxDailyRerolls = preferences[Keys.maxDailyRerolls] ?: 0,
            totalDraws = preferences[Keys.totalDraws] ?: 0,
            daiJiDraws = preferences[Keys.daiJiDraws] ?: 0,
            nonXiongDraws = preferences[Keys.nonXiongDraws] ?: 0,
            daiXiongDraws = preferences[Keys.daiXiongDraws] ?: 0,
        )
    }

    private object Keys {
        val selectedZodiac = stringPreferencesKey("selected_zodiac")
        val roomMigrationCompleted = booleanPreferencesKey("room_state_initialized_v1")

        val totalRerolls = intPreferencesKey("total_rerolls")
        val totalDrawDays = intPreferencesKey("total_draw_days")
        val maxDailyRerolls = intPreferencesKey("max_daily_rerolls")
        val totalDraws = intPreferencesKey("total_draws")
        val daiJiDraws = intPreferencesKey("dai_ji_draws")
        val nonXiongDraws = intPreferencesKey("non_xiong_draws")
        val daiXiongDraws = intPreferencesKey("dai_xiong_draws")
    }
}
