package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.ZodiacSign
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParallelSkyGeneratorTest {
    @Test
    fun seasonalMatchKeepsNoonSunVeryClose() {
        val original = LocalDate.of(2026, 9, 2)
        val source = ParallelSkyGenerator.closestSeasonalDate(original, 2047)
        val difference = ParallelSkyGenerator.angularDistance(
            ParallelSkyGenerator.noonSunLongitude(original),
            ParallelSkyGenerator.noonSunLongitude(source),
        )

        assertEquals(2047, source.year)
        assertTrue("Sun difference was $difference", difference < 0.75)
    }

    @Test
    fun parallelDestinyIsReproducibleFromItsRealSourceDate() {
        val original = LocalDate.of(2026, 9, 2)
        val source = ParallelSkyGenerator.closestSeasonalDate(original, 2047)
        val first = ParallelSkyGenerator.resolve(original, ZodiacSign.SCORPIO, source)
        val second = ParallelSkyGenerator.resolve(original, ZodiacSign.SCORPIO, source)

        assertEquals(first, second)
        assertEquals(source, first.astrologyAudit.astronomy.date)
        assertNotEquals(original, source)
        assertEquals(97, first.astrologyAudit.astronomy.samples.size)
    }

    @Test
    fun comparisonDeltasMatchTheUnderlyingAuditScores() {
        val originalDate = LocalDate.of(2026, 9, 2)
        val zodiac = ZodiacSign.SCORPIO
        val publicDestiny = DailyDestinyProvider.publicDestiny(originalDate, zodiac)
        val sourceDate = ParallelSkyGenerator.closestSeasonalDate(originalDate, 2047)
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
