package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.AstroBody
import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.ZodiacSign
import java.time.LocalDate
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AstrologyEngineTest {
    @Test
    fun astronomySamplesCoverTheWholeTaipeiDay() {
        val data = AstronomyEphemeris.analyze(LocalDate.of(2026, 9, 2))

        assertEquals(97, data.samples.size)
        assertEquals(10, data.samples.first().longitudes.size)
        assertEquals(10, data.bodies.size)
        assertTrue(data.aspects.all { it.orbDegrees <= it.aspect.maxOrb })
    }

    @Test
    fun sunIsNearTropicalAriesZeroAroundMarchEquinox() {
        val data = AstronomyEphemeris.analyze(LocalDate.of(2026, 3, 20))
        val noonLongitude = data.samples[48].longitudes.getValue(AstroBody.SUN)
        val distanceToZero = min(noonLongitude, 360.0 - noonLongitude)

        assertTrue("Sun longitude was $noonLongitude", distanceToZero < 2.0)
    }

    @Test
    fun twelveSignsDoNotCollapseToOneIdenticalDestiny() {
        val destinies = AstrologyEngine.calculateDay(LocalDate.of(2026, 9, 2))
        val scoreVectors = destinies.values.map { destiny ->
            FortuneDomain.entries.map { destiny.audit.domainScores.getValue(it) }
        }.toSet()

        assertEquals(12, destinies.size)
        assertTrue("Expected zodiac-specific score differences", scoreVectors.size >= 8)
        assertTrue(destinies.values.all { it.audit.factors.isNotEmpty() })
        assertTrue(destinies.values.all { destiny -> destiny.domains.values.all { it.explanation.isNotBlank() } })
    }

    @Test
    fun referenceDatesExerciseMultipleFortuneGrades() {
        val dates = listOf(
            LocalDate.of(2026, 1, 15),
            LocalDate.of(2026, 3, 20),
            LocalDate.of(2026, 5, 15),
            LocalDate.of(2026, 7, 15),
            LocalDate.of(2026, 9, 2),
            LocalDate.of(2026, 11, 15),
        )
        val grades = dates.flatMap { date ->
            AstrologyEngine.calculateDay(date).values.flatMap { destiny ->
                buildList {
                    add(destiny.overallGrade)
                    addAll(destiny.domains.values.map { it.grade })
                }
            }
        }.toSet()

        assertTrue("Only generated grades: $grades", grades.size >= 4)
    }

    @Test
    fun auditContributionSumsExactlyToDomainScores() {
        val destiny = AstrologyEngine.calculateDay(LocalDate.of(2026, 9, 2)).getValue(ZodiacSign.SCORPIO)

        FortuneDomain.entries.forEach { domain ->
            val reconstructed = destiny.audit.factors.sumOf { it.contributions[domain] ?: 0.0 }
            val recorded = destiny.audit.domainScores.getValue(domain)
            assertEquals(recorded, reconstructed, 1e-9)
        }
        assertEquals(destiny.audit.domainScores.values.average(), destiny.audit.overallScore, 1e-9)
    }

    @Test
    fun wholeSignHouseMappingIsStable() {
        assertEquals(1, AstrologyEngine.houseFor(ZodiacSign.SCORPIO, ZodiacSign.SCORPIO))
        assertEquals(2, AstrologyEngine.houseFor(ZodiacSign.SCORPIO, ZodiacSign.SAGITTARIUS))
        assertEquals(12, AstrologyEngine.houseFor(ZodiacSign.SCORPIO, ZodiacSign.LIBRA))
    }
}
