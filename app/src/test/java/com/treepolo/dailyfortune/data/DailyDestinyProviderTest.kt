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
        assertNull(first.fortune)
        assertNotNull(first.astrologyAudit)
    }

    @Test
    fun publicDayContainsAllTwelveZodiacSigns() {
        val destinies = DailyDestinyProvider.publicDestinies(LocalDate.of(2026, 9, 2))

        assertEquals(12, destinies.size)
        assertTrue(ZodiacSign.entries.all(destinies::containsKey))
    }

    @Test
    fun privateRerollUsesAncientFortuneAndHasNoAstrologyAudit() {
        val destiny = DailyDestinyProvider.personalReroll()

        assertEquals(DestinySource.PERSONAL_FORTUNE, destiny.source)
        assertNotNull(destiny.snapshot)
        assertNotNull(destiny.fortune)
        assertNull(destiny.astrologyAudit)
    }
}
