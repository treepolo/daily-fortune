package com.treepolo.dailyfortune.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.treepolo.dailyfortune.model.DestinySnapshot
import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.FortuneGrade
import com.treepolo.dailyfortune.model.FortuneStats
import com.treepolo.dailyfortune.model.PersistedFortuneState
import com.treepolo.dailyfortune.model.ZodiacSign
import java.io.IOException
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
                selectedZodiac = preferences[Keys.selectedZodiac]
                    ?.let { runCatching { ZodiacSign.valueOf(it) }.getOrNull() },
                todayPersonalDestiny = readSnapshot(preferences),
                todayRerollCount = preferences[Keys.todayRerollCount] ?: 0,
                todaySeen = preferences[Keys.todaySeen] ?: false,
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

    suspend fun selectZodiac(zodiac: ZodiacSign) {
        context.fortuneDataStore.edit { preferences ->
            if (preferences[Keys.selectedZodiac] == null) {
                preferences[Keys.selectedZodiac] = zodiac.name
            }
        }
    }

    suspend fun markTodaySeen(today: LocalDate, publicOverallGrade: FortuneGrade) {
        context.fortuneDataStore.edit { preferences ->
            normalizeDate(preferences, today)
            if (preferences[Keys.todaySeen] == true) return@edit

            preferences[Keys.todaySeen] = true
            preferences[Keys.totalDrawDays] = (preferences[Keys.totalDrawDays] ?: 0) + 1
            recordDraw(preferences, publicOverallGrade)
        }
    }

    suspend fun reroll(today: LocalDate, destiny: DestinySnapshot, overallGrade: FortuneGrade) {
        context.fortuneDataStore.edit { preferences ->
            normalizeDate(preferences, today)
            if (preferences[Keys.todaySeen] != true) return@edit

            val todayRerolls = (preferences[Keys.todayRerollCount] ?: 0) + 1
            preferences[Keys.todayRerollCount] = todayRerolls
            preferences[Keys.totalRerolls] = (preferences[Keys.totalRerolls] ?: 0) + 1
            preferences[Keys.maxDailyRerolls] = maxOf(
                preferences[Keys.maxDailyRerolls] ?: 0,
                todayRerolls,
            )
            writeSnapshot(preferences, destiny)
            recordDraw(preferences, overallGrade)
        }
    }

    private fun normalizeDate(preferences: MutablePreferences, today: LocalDate) {
        val todayText = today.toString()
        if (preferences[Keys.todayDate] == todayText) return

        preferences[Keys.todayDate] = todayText
        preferences[Keys.todaySeen] = false
        preferences[Keys.todayRerollCount] = 0
        clearSnapshot(preferences)
    }

    private fun readSnapshot(preferences: androidx.datastore.preferences.core.Preferences): DestinySnapshot? {
        val overall = preferences[Keys.personalOverall] ?: return null
        val domains = buildMap {
            FortuneDomain.entries.forEach { domain ->
                val value = preferences[Keys.domainKey(domain)] ?: return null
                put(domain, value)
            }
        }
        return DestinySnapshot(overall, domains)
    }

    private fun writeSnapshot(preferences: MutablePreferences, destiny: DestinySnapshot) {
        preferences[Keys.personalOverall] = destiny.overallFortuneNumber
        FortuneDomain.entries.forEach { domain ->
            preferences[Keys.domainKey(domain)] = destiny.domainFortuneNumbers.getValue(domain)
        }
    }

    private fun clearSnapshot(preferences: MutablePreferences) {
        preferences.remove(Keys.personalOverall)
        FortuneDomain.entries.forEach { domain -> preferences.remove(Keys.domainKey(domain)) }
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
        val selectedZodiac = stringPreferencesKey("selected_zodiac")
        val todaySeen = booleanPreferencesKey("today_seen")
        val personalOverall = intPreferencesKey("today_personal_overall")
        val todayRerollCount = intPreferencesKey("today_reroll_count")
        val totalRerolls = intPreferencesKey("total_rerolls")
        val totalDrawDays = intPreferencesKey("total_draw_days")
        val maxDailyRerolls = intPreferencesKey("max_daily_rerolls")
        val totalDraws = intPreferencesKey("total_draws")
        val daiJiDraws = intPreferencesKey("dai_ji_draws")
        val nonXiongDraws = intPreferencesKey("non_xiong_draws")
        val daiXiongDraws = intPreferencesKey("dai_xiong_draws")

        private val wealth = intPreferencesKey("today_personal_wealth")
        private val love = intPreferencesKey("today_personal_love")
        private val workStudy = intPreferencesKey("today_personal_work_study")
        private val relationships = intPreferencesKey("today_personal_relationships")
        private val health = intPreferencesKey("today_personal_health")

        fun domainKey(domain: FortuneDomain) = when (domain) {
            FortuneDomain.WEALTH -> wealth
            FortuneDomain.LOVE -> love
            FortuneDomain.WORK_STUDY -> workStudy
            FortuneDomain.RELATIONSHIPS -> relationships
            FortuneDomain.HEALTH -> health
        }
    }
}
