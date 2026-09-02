package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.DestinySource
import com.treepolo.dailyfortune.model.ZodiacSign
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyDestinyProviderTest {
    @Test
    fun sameDateAndZodiacProducesSameAstronomyBasedPublicDestiny() {
        val date = LocalDate.of(2026, 9, 2)
        val first = DailyDestinyProvider.publicDestiny(date, ZodiacSign.SCORPIO)
        val second = DailyDestinyProvider.publicDestiny(date, ZodiacSign.SCORPIO)

        assertEquals(first, second)
        assertEquals(DestinySource.PUBLIC_ASTROLOGY, first.source)
        assertNull(first.parallelSky)
        assertNotNull(first.astrologyAudit)
    }

    @Test
    fun publicDayContainsAllTwelveZodiacSigns() {
        val destinies = DailyDestinyProvider.publicDestinies(LocalDate.of(2026, 9, 2))

        assertEquals(12, destinies.size)
        assertTrue(ZodiacSign.entries.all(destinies::containsKey))
    }

    @Test
    fun privateRerollUsesRealParallelSkyAndSameAstrologyEngine() {
        val date = LocalDate.of(2026, 9, 2)
        val destiny = DailyDestinyProvider.personalReroll(date, ZodiacSign.SCORPIO)
        val sky = requireNotNull(destiny.parallelSky)

        assertEquals(DestinySource.PERSONAL_ASTROLOGY, destiny.source)
        assertEquals(AstrologyEngine.version, destiny.astrologyAudit.engineVersion)
        assertEquals(AstrologyEngine.version, sky.engineVersion)
        assertEquals(date, sky.originalDate)
        assertTrue(sky.sourceDate.year in 1900..2100)
        assertTrue("Sun difference was ${sky.sunLongitudeDifference}", sky.sunLongitudeDifference < 0.75)
        assertEquals(sky.sourceDate, destiny.astrologyAudit.astronomy.date)
    }
}
