package com.treepolo.dailyfortune.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.treepolo.dailyfortune.data.local.DailyFortuneDatabase
import com.treepolo.dailyfortune.data.local.LocalAuthority
import com.treepolo.dailyfortune.data.local.LocalSyncState
import com.treepolo.dailyfortune.model.ResolvedDestiny
import com.treepolo.dailyfortune.model.ZodiacSign
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalFortuneRepositoryTest {
    private lateinit var database: DailyFortuneDatabase
    private lateinit var settings: FakeSettings
    private val date = LocalDate.of(2026, 9, 2)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<DailyFortuneDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        settings = FakeSettings()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun publicCacheSurvivesAuthorityOutage() = runBlocking {
        val online = LocalFortuneRepository(
            database.fortuneDao(),
            settings,
            FakeAuthority(listOf(LocalDate.of(2012, 9, 2))),
            nowMillis = { 1_000L },
        )
        online.selectZodiac(date, ZodiacSign.SCORPIO)
        val first = online.snapshot(date)
        assertEquals(12, first.publicDestinies.size)

        val offline = LocalFortuneRepository(
            database.fortuneDao(),
            settings,
            UnavailableDestinyAuthority(),
            nowMillis = { 2_000L },
        )
        val cached = offline.snapshot(date)
        assertEquals(first.publicDestinies, cached.publicDestinies)
    }

    @Test(expected = DestinyUnavailableException::class)
    fun missingCacheDoesNotInventProductionFallback() = runBlocking {
        settings.selectFirstZodiac(ZodiacSign.SCORPIO)
        LocalFortuneRepository(
            database.fortuneDao(),
            settings,
            UnavailableDestinyAuthority(),
        ).snapshot(date)
    }

    @Test
    fun repeatedRerollsCompareCurrentWorldlineAndKeepHistory() = runBlocking {
        val authority = FakeAuthority(
            listOf(
                LocalDate.of(2012, 9, 2),
                LocalDate.of(2044, 9, 2),
            ),
        )
        val repository = LocalFortuneRepository(
            database.fortuneDao(),
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

    @Test
    fun newDayDropsCurrentOverrideButPreservesHistoricalStats() = runBlocking {
        val repository = LocalFortuneRepository(
            database.fortuneDao(),
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
}
