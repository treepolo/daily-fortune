package com.treepolo.dailyfortune.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FortuneStatsTest {
    @Test
    fun averageDailyRerolls_usesDrawDaysAsDenominator() {
        val stats = FortuneStats(totalRerolls = 9, totalDrawDays = 3)
        assertEquals(3.0, stats.averageDailyRerolls, 0.0001)
    }

    @Test
    fun rates_useEveryActualDrawIncludingRerolls() {
        val stats = FortuneStats(
            totalDraws = 10,
            daiJiDraws = 2,
            nonXiongDraws = 7,
            daiXiongDraws = 1,
        )
        assertEquals(0.2, stats.daiJiRate, 0.0001)
        assertEquals(0.7, stats.nonXiongRate, 0.0001)
        assertEquals(0.1, stats.daiXiongRate, 0.0001)
    }
}
