package com.treepolo.dailyfortune.data

import android.content.Context
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.treepolo.dailyfortune.model.FortuneDefinition
import com.treepolo.dailyfortune.model.FortuneGrade
import com.treepolo.dailyfortune.model.FortuneStats
import com.treepolo.dailyfortune.model.PersistedFortuneState
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.fortuneDataStore by preferencesDataStore(name = "fortune_state")

class FortuneStore(private val context: Context) {
    val state: Flow<PersistedFortuneState> = context.fortuneDataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            PersistedFortuneState(
                todayDate = preferences[Keys.todayDate],
                todayFortuneNumber = preferences[Keys.todayFortuneNumber],
                todayRerollCount = preferences[Keys.todayRerollCount] ?: 0,
                stats = FortuneStats(
                    totalRerolls = preferences[Keys.totalRerolls] ?: 0,
                    totalDrawDays = preferences[Keys.totalDrawDays] ?: 0,
                    maxDailyRerolls = preferences[Keys.maxDailyRerolls] ?: 0,
                    totalDraws = preferences[Keys.totalDraws] ?: 0,
                    daiJiDraws = preferences[Keys.daiJiDraws] ?: 0,
                    nonXiongDraws = preferences[Keys.nonXiongDraws] ?: 0,
                    daiXiongDraws = preferences[Keys.daiXiongDraws] ?: 0,
                ),
            )
        }

    suspend fun rolloverIfNeeded(today: LocalDate) {
        context.fortuneDataStore.edit { preferences ->
            normalizeDate(preferences, today)
        }
    }

    suspend fun drawInitial(today: LocalDate, fortune: FortuneDefinition) {
        context.fortuneDataStore.edit { preferences ->
            normalizeDate(preferences, today)
            if (preferences[Keys.todayFortuneNumber] != null) return@edit

            preferences[Keys.todayFortuneNumber] = fortune.number
            preferences[Keys.totalDrawDays] = (preferences[Keys.totalDrawDays] ?: 0) + 1
            recordDraw(preferences, fortune.grade)
        }
    }

    suspend fun reroll(today: LocalDate, fortune: FortuneDefinition) {
        context.fortuneDataStore.edit { preferences ->
            normalizeDate(preferences, today)
            if (preferences[Keys.todayFortuneNumber] == null) return@edit

            val todayRerolls = (preferences[Keys.todayRerollCount] ?: 0) + 1
            preferences[Keys.todayRerollCount] = todayRerolls
            preferences[Keys.totalRerolls] = (preferences[Keys.totalRerolls] ?: 0) + 1
            preferences[Keys.maxDailyRerolls] = maxOf(
                preferences[Keys.maxDailyRerolls] ?: 0,
                todayRerolls,
            )
            preferences[Keys.todayFortuneNumber] = fortune.number
            recordDraw(preferences, fortune.grade)
        }
    }

    private fun normalizeDate(preferences: MutablePreferences, today: LocalDate) {
        val todayText = today.toString()
        if (preferences[Keys.todayDate] == todayText) return

        preferences[Keys.todayDate] = todayText
        preferences.remove(Keys.todayFortuneNumber)
        preferences[Keys.todayRerollCount] = 0
    }

    private fun recordDraw(preferences: MutablePreferences, grade: FortuneGrade) {
        preferences[Keys.totalDraws] = (preferences[Keys.totalDraws] ?: 0) + 1
        if (grade == FortuneGrade.DAI_JI) {
            preferences[Keys.daiJiDraws] = (preferences[Keys.daiJiDraws] ?: 0) + 1
        }
        if (grade.isNonXiong) {
            preferences[Keys.nonXiongDraws] = (preferences[Keys.nonXiongDraws] ?: 0) + 1
        }
        if (grade == FortuneGrade.DAI_XIONG) {
            preferences[Keys.daiXiongDraws] = (preferences[Keys.daiXiongDraws] ?: 0) + 1
        }
    }

    private object Keys {
        val todayDate = stringPreferencesKey("today_date")
        val todayFortuneNumber = intPreferencesKey("today_fortune_number")
        val todayRerollCount = intPreferencesKey("today_reroll_count")
        val totalRerolls = intPreferencesKey("total_rerolls")
        val totalDrawDays = intPreferencesKey("total_draw_days")
        val maxDailyRerolls = intPreferencesKey("max_daily_rerolls")
        val totalDraws = intPreferencesKey("total_draws")
        val daiJiDraws = intPreferencesKey("dai_ji_draws")
        val nonXiongDraws = intPreferencesKey("non_xiong_draws")
        val daiXiongDraws = intPreferencesKey("dai_xiong_draws")
    }
}
