package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.ZodiacSign
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParallelSkyGeneratorTest {
    @Test
    fun sourceDateIndexCoversEveryCalendarDayInSupportedRange() {
        assertEquals(LocalDate.of(1900, 1, 1), ParallelSkyGenerator.sourceDateForIndex(0))
        assertEquals(LocalDate.of(1900, 2, 1), ParallelSkyGenerator.sourceDateForIndex(31))
        assertEquals(
            LocalDate.of(2100, 12, 31),
            ParallelSkyGenerator.sourceDateForIndex(ParallelSkyGenerator.sourceDateCount - 1),
        )
        assertEquals(
            ChronoUnit.DAYS.between(LocalDate.of(1900, 1, 1), LocalDate.of(2100, 12, 31)) + 1L,
            ParallelSkyGenerator.sourceDateCount.toLong(),
        )
    }

    @Test
    fun randomSourceDateIsNotSeasonLockedAndExcludesOriginalDay() {
        val original = LocalDate.of(2026, 9, 2)
        val january1950 = LocalDate.of(1950, 1, 17)
        val index = ChronoUnit.DAYS.between(ParallelSkyGenerator.minSourceDate, january1950).toInt()
        val selected = ParallelSkyGenerator.randomSourceDate(original) { index }

        assertEquals(january1950, selected)
        assertNotEquals(original.month, selected.month)
        assertNotEquals(original, selected)
    }

    @Test
    fun parallelDestinyIsReproducibleFromItsRealSourceDate() {
        val original = LocalDate.of(2026, 9, 2)
        val source = LocalDate.of(2047, 3, 14)
        val first = ParallelSkyGenerator.resolve(original, ZodiacSign.SCORPIO, source)
        val second = ParallelSkyGenerator.resolve(original, ZodiacSign.SCORPIO, source)

        assertEquals(first, second)
        assertEquals(source, first.astrologyAudit.astronomy.date)
        assertNotEquals(original, source)
        assertEquals(97, first.astrologyAudit.astronomy.samples.size)
        assertTrue(first.parallelSky!!.sunLongitudeDifference > 1.0)
    }

    @Test
    fun comparisonDeltasMatchTheUnderlyingAuditScores() {
        val originalDate = LocalDate.of(2026, 9, 2)
        val zodiac = ZodiacSign.SCORPIO
        val publicDestiny = DailyDestinyProvider.publicDestiny(originalDate, zodiac)
        val sourceDate = LocalDate.of(2047, 3, 14)
        val altered = ParallelSkyGenerator.resolve(originalDate, zodiac, sourceDate)
        val change = DailyDestinyProvider.compare(publicDestiny, altered)

        val expectedOverall = altered.astrologyAudit.overallScore - publicDestiny.astrologyAudit.overallScore
        assertEquals(expectedOverall, change.overallScoreDelta, 1e-9)
        FortuneDomain.entries.forEach { domain ->
            val expected = altered.astrologyAudit.domainScores.getValue(domain) -
                publicDestiny.astrologyAudit.domainScores.getValue(domain)
            assertEquals(expected, change.domainChanges.getValue(domain).scoreDelta, 1e-9)
        }
    }
}
