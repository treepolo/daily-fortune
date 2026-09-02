package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.AstroBody
import com.treepolo.dailyfortune.model.DestinySource
import com.treepolo.dailyfortune.model.ParallelSkyInfo
import com.treepolo.dailyfortune.model.ResolvedDestiny
import com.treepolo.dailyfortune.model.ZodiacSign
import java.security.SecureRandom
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Creates a private "parallel sky" from one complete real astronomical day.
 *
 * Planets are never randomized independently. A reroll chooses one calendar day uniformly from
 * 1900-01-01 through 2100-12-31 (excluding the original day itself), then passes that real 24-hour
 * sky unchanged through the same Astrology Engine as the public destiny. There is intentionally no
 * same-season or same-Sun-longitude restriction: every other real day in the supported range can be
 * selected.
 */
object ParallelSkyGenerator {
    internal val minSourceDate: LocalDate = LocalDate.of(1900, 1, 1)
    internal val maxSourceDate: LocalDate = LocalDate.of(2100, 12, 31)
    internal val sourceDateCount: Int =
        (ChronoUnit.DAYS.between(minSourceDate, maxSourceDate) + 1L).toInt()

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
        require(!sourceDate.isBefore(minSourceDate) && !sourceDate.isAfter(maxSourceDate)) {
            "Parallel source date must be within $minSourceDate..$maxSourceDate"
        }
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

    internal fun sourceDateForIndex(index: Int): LocalDate {
        require(index in 0 until sourceDateCount) { "Parallel source-date index out of range: $index" }
        return minSourceDate.plusDays(index.toLong())
    }

    internal fun randomSourceDate(
        originalDate: LocalDate,
        nextIndex: (Int) -> Int = { bound -> secureRandom.nextInt(bound) },
    ): LocalDate {
        var sourceDate: LocalDate
        do {
            sourceDate = sourceDateForIndex(nextIndex(sourceDateCount))
        } while (sourceDate == originalDate)
        return sourceDate
    }

    internal fun noonSunLongitude(date: LocalDate): Double =
        AstronomyEphemeris.longitude(
            AstroBody.SUN,
            date.atTime(12, 0).atZone(AstronomyEphemeris.zone).toInstant(),
        )

    internal fun angularDistance(first: Double, second: Double): Double =
        abs(AstronomyEphemeris.signedAngularDelta(first, second))
}
