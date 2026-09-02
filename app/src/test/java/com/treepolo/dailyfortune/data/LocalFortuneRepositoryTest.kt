package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.data.local.LocalAstrologyFactorEntity
import com.treepolo.dailyfortune.data.local.LocalAstronomySampleEntity
import com.treepolo.dailyfortune.data.local.LocalAuthority
import com.treepolo.dailyfortune.data.local.LocalBindingEntity
import com.treepolo.dailyfortune.data.local.LocalDailyFortuneEntity
import com.treepolo.dailyfortune.data.local.LocalDestinyEntity
import com.treepolo.dailyfortune.data.local.LocalFateSampleEventEntity
import com.treepolo.dailyfortune.data.local.LocalFortuneDao
import com.treepolo.dailyfortune.data.local.LocalRerollDayCount
import com.treepolo.dailyfortune.data.local.LocalRerollEventEntity
import com.treepolo.dailyfortune.data.local.LocalStatsAggregate
import com.treepolo.dailyfortune.data.local.LocalSyncState
import com.treepolo.dailyfortune.model.ResolvedDestiny
import com.treepolo.dailyfortune.model.ZodiacSign
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class LocalFortuneRepositoryTest {
    private lateinit var dao: FakeFortuneDao
    private lateinit var settings: FakeSettings
    private val date = LocalDate.of(2026, 9, 2)

    @Before
    fun setUp() {
        dao = FakeFortuneDao()
        settings = FakeSettings()
    }

    @Test
    fun publicCacheSurvivesAuthorityOutage() {
        runBlocking {
            val online = LocalFortuneRepository(
                dao,
                settings,
                FakeAuthority(listOf(LocalDate.of(2012, 9, 2))),
                nowMillis = { 1_000L },
            )
            online.selectZodiac(date, ZodiacSign.SCORPIO)
            val first = online.snapshot(date)
            assertEquals(12, first.publicDestinies.size)

            val offline = LocalFortuneRepository(
                dao,
                settings,
                UnavailableDestinyAuthority(),
                nowMillis = { 2_000L },
            )
            val cached = offline.snapshot(date)
            assertEquals(first.publicDestinies, cached.publicDestinies)
        }
    }

    @Test(expected = DestinyUnavailableException::class)
    fun missingCacheDoesNotInventProductionFallback() {
        runBlocking {
            settings.selectFirstZodiac(ZodiacSign.SCORPIO)
            LocalFortuneRepository(
                dao,
                settings,
                UnavailableDestinyAuthority(),
            ).snapshot(date)
        }
    }

    @Test
    fun repeatedRerollsCompareCurrentWorldlineAndKeepHistory() {
        runBlocking {
            val authority = FakeAuthority(
                listOf(
                    LocalDate.of(2012, 9, 2),
                    LocalDate.of(2044, 9, 2),
                ),
            )
            val repository = LocalFortuneRepository(
                dao,
                settings,
                authority,
                nowMillis = object : () -> Long {
                    var value = 10_000L
                    override fun invoke(): Long = value++
                },
            )
            repository.selectZodiac(date, ZodiacSign.SCORPIO)

            repository.reroll(date)
            val afterFirst = repository.snapshot(date)
            val firstGrade = requireNotNull(afterFirst.currentDestiny).overallGrade

            repository.reroll(date)
            val history = repository.rerollHistory(date)
            val afterSecond = repository.snapshot(date)

            assertEquals(2, history.size)
            assertEquals(history[0].afterDestinyId, history[1].beforeDestinyId)
            assertEquals(firstGrade, requireNotNull(afterSecond.destinyChange).beforeOverallGrade)
            assertEquals(2, afterSecond.todayRerollCount)
            assertEquals(2, afterSecond.stats.totalRerolls)
            assertEquals(3, afterSecond.stats.totalDraws)
            assertEquals(1, afterSecond.stats.totalDrawDays)
            assertEquals(2, afterSecond.stats.maxDailyRerolls)
        }
    }

    @Test
    fun newDayDropsCurrentOverrideButPreservesHistoricalStats() {
        runBlocking {
            val repository = LocalFortuneRepository(
                dao,
                settings,
                FakeAuthority(listOf(LocalDate.of(2012, 9, 2))),
            )
            repository.selectZodiac(date, ZodiacSign.SCORPIO)
            repository.reroll(date)

            val tomorrow = date.plusDays(1)
            repository.markTodaySeen(tomorrow)
            val snapshot = repository.snapshot(tomorrow)

            assertNull(snapshot.currentDestiny?.parallelSky)
            assertEquals(0, snapshot.todayRerollCount)
            assertEquals(1, snapshot.stats.totalRerolls)
            assertEquals(2, snapshot.stats.totalDrawDays)
            assertEquals(3, snapshot.stats.totalDraws)
        }
    }

    private class FakeSettings : ZodiacSettings {
        private val state = MutableStateFlow<ZodiacSign?>(null)
        override val selectedZodiac: Flow<ZodiacSign?> = state
        override suspend fun currentZodiac(): ZodiacSign? = state.value
        override suspend fun selectFirstZodiac(zodiac: ZodiacSign) {
            if (state.value == null) state.value = zodiac
        }
        override suspend fun cleanupLegacyState() = Unit
    }

    private class FakeAuthority(sourceDates: List<LocalDate>) : DestinyAuthority {
        private val dates = ArrayDeque(sourceDates)
        override val publicAuthority = LocalAuthority.DEVELOPMENT_EMBEDDED
        override val personalAuthority = LocalAuthority.PERSONAL_LOCAL
        override val localSyncState = LocalSyncState.LOCAL_DEVELOPMENT

        override suspend fun publicDestinies(date: LocalDate): Map<ZodiacSign, ResolvedDestiny> =
            DailyDestinyProvider.publicDestinies(date)

        override suspend fun reroll(date: LocalDate, zodiac: ZodiacSign): ResolvedDestiny {
            val source = dates.removeFirstOrNull() ?: date.minusYears(11)
            return ParallelSkyGenerator.resolve(date, zodiac, source)
        }
    }

    /**
     * Repository contract fake. Room's KSP compiler validates the real DAO SQL during every Android
     * build; Android-local JVM tests intentionally avoid loading Android native SQLite libraries.
     */
    private class FakeFortuneDao : LocalFortuneDao {
        private val astronomy = linkedMapOf<Triple<String, String, Int>, LocalAstronomySampleEntity>()
        private val destinies = linkedMapOf<String, LocalDestinyEntity>()
        private val factors = linkedMapOf<Pair<String, String>, LocalAstrologyFactorEntity>()
        private val days = linkedMapOf<String, LocalDailyFortuneEntity>()
        private val rerolls = linkedMapOf<String, LocalRerollEventEntity>()
        private val samples = linkedMapOf<String, LocalFateSampleEventEntity>()
        private val bindings = linkedMapOf<String, LocalBindingEntity>()

        override suspend fun upsertAstronomySamples(samples: List<LocalAstronomySampleEntity>) {
            samples.forEach { sample ->
                astronomy[Triple(sample.sourceDate, sample.ephemerisVersion, sample.sampleIndex)] = sample
            }
        }

        override suspend fun upsertDestiny(destiny: LocalDestinyEntity) {
            destinies[destiny.id] = destiny
        }

        override suspend fun upsertFactors(factors: List<LocalAstrologyFactorEntity>) {
            factors.forEach { factor -> this.factors[factor.destinyId to factor.factorId] = factor }
        }

        override suspend fun deleteFactors(destinyId: String) {
            factors.keys.removeAll { it.first == destinyId }
        }

        override suspend fun getPublicDestinies(date: String): List<LocalDestinyEntity> =
            destinies.values.filter { it.fortuneDate == date && it.sourceType == "PUBLIC_ASTROLOGY" }

        override suspend fun getDestiny(id: String): LocalDestinyEntity? = destinies[id]

        override suspend fun getFactors(destinyId: String): List<LocalAstrologyFactorEntity> =
            factors.values.filter { it.destinyId == destinyId }.sortedBy { it.factorId }

        override suspend fun getAstronomySamples(
            sourceDate: String,
            ephemerisVersion: String,
        ): List<LocalAstronomySampleEntity> = astronomy.values
            .filter { it.sourceDate == sourceDate && it.ephemerisVersion == ephemerisVersion }
            .sortedBy { it.sampleIndex }

        override suspend fun upsertDailyFortune(day: LocalDailyFortuneEntity) {
            days[day.fortuneDate] = day
        }

        override suspend fun getDailyFortune(date: String): LocalDailyFortuneEntity? = days[date]

        override suspend fun insertRerollEvent(event: LocalRerollEventEntity) {
            check(event.localId !in rerolls)
            check(rerolls.values.none { it.fortuneDate == event.fortuneDate && it.drawIndex == event.drawIndex })
            rerolls[event.localId] = event
        }

        override suspend fun getLatestReroll(date: String): LocalRerollEventEntity? =
            rerolls.values.filter { it.fortuneDate == date }.maxByOrNull { it.drawIndex }

        override suspend fun getRerollCount(date: String): Int =
            rerolls.values.count { it.fortuneDate == date }

        override suspend fun getRerollHistory(date: String): List<LocalRerollEventEntity> =
            rerolls.values.filter { it.fortuneDate == date }.sortedBy { it.drawIndex }

        override suspend fun insertFateSample(sample: LocalFateSampleEventEntity): Long {
            if (sample.sampleId in samples) return -1L
            samples[sample.sampleId] = sample
            return 1L
        }

        override suspend fun getDrawDayCount(): Long =
            samples.values.count { it.sampleKind == "PUBLIC_VIEW" }.toLong()

        override suspend fun getStatsAggregate(): LocalStatsAggregate {
            val values = samples.values
            return LocalStatsAggregate(
                totalDraws = values.size.toLong(),
                daiJiDraws = values.count { it.overallGrade == "DAI_JI" }.toLong(),
                nonXiongDraws = values.count {
                    it.overallGrade in setOf("DAI_JI", "JI", "XIAO_JI", "PING")
                }.toLong(),
                daiXiongDraws = values.count { it.overallGrade == "DAI_XIONG" }.toLong(),
            )
        }

        override suspend fun getRerollDayCounts(): List<LocalRerollDayCount> = rerolls.values
            .groupingBy { it.fortuneDate }
            .eachCount()
            .map { (fortuneDate, count) -> LocalRerollDayCount(fortuneDate, count.toLong()) }

        override suspend fun upsertBinding(binding: LocalBindingEntity) {
            bindings[binding.localId] = binding
        }

        override suspend fun getBindings(): List<LocalBindingEntity> =
            bindings.values.sortedByDescending { it.createdAtEpochMillis }
    }
}
