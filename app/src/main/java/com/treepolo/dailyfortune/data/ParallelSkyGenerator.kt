package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.AstroBody
import com.treepolo.dailyfortune.model.DestinySource
import com.treepolo.dailyfortune.model.ParallelSkyInfo
import com.treepolo.dailyfortune.model.ResolvedDestiny
import com.treepolo.dailyfortune.model.ZodiacSign
import java.security.SecureRandom
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.min

/**
 * Creates a private "parallel sky" from a real astronomical day.
 *
 * We never randomize planets independently. A reroll first uses [SecureRandom] to choose another
 * year, then finds the real calendar day in that year whose noon Sun longitude is closest to the
 * original day's noon Sun longitude. The entire 24-hour sky for that real day is then passed
 * unchanged through the same Astrology Engine as the public destiny.
 */
object ParallelSkyGenerator {
    private const val minYear = 1900
    private const val maxYear = 2100
    private const val seasonalSearchDays = 6L
    private val secureRandom = SecureRandom()

    fun reroll(originalDate: LocalDate, zodiac: ZodiacSign): ResolvedDestiny {
        val sourceDate = randomSourceDate(originalDate)
        return resolve(originalDate, zodiac, sourceDate)
    }

    fun resolve(
        originalDate: LocalDate,
        zodiac: ZodiacSign,
        sourceDate: LocalDate,
    ): ResolvedDestiny {
        val originalSun = noonSunLongitude(originalDate)
        val alteredSun = noonSunLongitude(sourceDate)
        val astronomy = AstronomyEphemeris.analyze(sourceDate)
        val destiny = AstrologyEngine.calculate(zodiac, astronomy)
        val info = ParallelSkyInfo(
            originalDate = originalDate,
            sourceDate = sourceDate,
            originalSunLongitude = originalSun,
            alteredSunLongitude = alteredSun,
            sunLongitudeDifference = angularDistance(originalSun, alteredSun),
            engineVersion = AstrologyEngine.version,
        )
        return ResolvedDestiny(
            source = DestinySource.PERSONAL_ASTROLOGY,
            overallGrade = destiny.overallGrade,
            overallExplanation = destiny.overallExplanation,
            domains = destiny.domains,
            astrologyAudit = destiny.audit,
            parallelSky = info,
        )
    }

    internal fun closestSeasonalDate(originalDate: LocalDate, targetYear: Int): LocalDate {
        val targetLongitude = noonSunLongitude(originalDate)
        val month = originalDate.month
        val safeDay = min(originalDate.dayOfMonth, YearMonth.of(targetYear, month).lengthOfMonth())
        val approximate = LocalDate.of(targetYear, month, safeDay)

        return (-seasonalSearchDays..seasonalSearchDays)
            .map(approximate::plusDays)
            .minBy { candidate -> angularDistance(targetLongitude, noonSunLongitude(candidate)) }
    }

    internal fun noonSunLongitude(date: LocalDate): Double =
        AstronomyEphemeris.longitude(
            AstroBody.SUN,
            date.atTime(12, 0).atZone(AstronomyEphemeris.zone).toInstant(),
        )

    internal fun angularDistance(first: Double, second: Double): Double =
        abs(AstronomyEphemeris.signedAngularDelta(first, second))

    private fun randomSourceDate(originalDate: LocalDate): LocalDate {
        val yearCount = maxYear - minYear + 1
        var targetYear: Int
        do {
            targetYear = minYear + secureRandom.nextInt(yearCount)
        } while (targetYear == originalDate.year)
        return closestSeasonalDate(originalDate, targetYear)
    }
}
