package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.AstroAspect
import com.treepolo.dailyfortune.model.AstroBody
import com.treepolo.dailyfortune.model.AstroSample
import com.treepolo.dailyfortune.model.AstronomyDayData
import com.treepolo.dailyfortune.model.BodyDaySummary
import com.treepolo.dailyfortune.model.SignIngress
import com.treepolo.dailyfortune.model.ZodiacSign
import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.equatorialToEcliptic
import io.github.cosinekitty.astronomy.geoVector
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.floor

/** Actual astronomical input for Astrology Engine v1. No pseudo-random inputs. */
object AstronomyEphemeris {
    val zone: ZoneId = ZoneId.of("Asia/Taipei")
    private const val sampleMinutes = 15L
    private const val intervalCount = 96
    private const val motionStepIntervals = 24 // six hours
    private const val motionEpsilon = 0.002

    fun analyze(date: LocalDate): AstronomyDayData {
        val start = date.atStartOfDay(zone).toInstant()
        val samples = (0..intervalCount).map { index ->
            sample(start.plus(Duration.ofMinutes(sampleMinutes * index)))
        }
        val bodies = AstroBody.entries.associateWith { summarizeBody(it, samples) }
        val aspects = detectAspects(samples)
        return AstronomyDayData(date, samples, bodies, aspects)
    }

    fun longitude(body: AstroBody, instant: Instant): Double {
        val time = Time.fromMillisecondsSince1970(instant.toEpochMilli())
        val vector = geoVector(body.toEngineBody(), time, Aberration.Corrected)
        return normalize(equatorialToEcliptic(vector).elon)
    }

    private fun sample(instant: Instant): AstroSample = AstroSample(
        instant = instant,
        longitudes = AstroBody.entries.associateWith { longitude(it, instant) },
    )

    private fun summarizeBody(body: AstroBody, samples: List<AstroSample>): BodyDaySummary {
        val inDay = samples.dropLast(1)
        val counts = inDay.groupingBy { zodiac(it.longitudes.getValue(body)) }.eachCount()
        val fractions = ZodiacSign.entries.associateWith { sign ->
            (counts[sign] ?: 0).toDouble() / intervalCount
        }.filterValues { it > 0.0 }

        val motionIndices = listOf(0, 24, 48, 72, 96)
        val deltas = motionIndices.zipWithNext().map { (a, b) ->
            signedAngularDelta(
                samples[a].longitudes.getValue(body),
                samples[b].longitudes.getValue(body),
            )
        }
        val retrogradeIntervals = if (body == AstroBody.SUN || body == AstroBody.MOON) {
            0
        } else {
            deltas.count { it < -motionEpsilon }
        }
        val directIntervals = deltas.count { it > motionEpsilon }
        val directionChanged = body != AstroBody.SUN && body != AstroBody.MOON &&
            retrogradeIntervals > 0 && directIntervals > 0

        val ingresses = samples.zipWithNext().mapNotNull { (a, b) ->
            val from = zodiac(a.longitudes.getValue(body))
            val to = zodiac(b.longitudes.getValue(body))
            if (from == to) null else SignIngress(body, from, to, b.instant)
        }

        return BodyDaySummary(
            body = body,
            signFractions = fractions,
            averageSpeedDegPerDay = deltas.sum(),
            retrogradeFraction = retrogradeIntervals.toDouble() / deltas.size,
            directionChanged = directionChanged,
            ingresses = ingresses,
        )
    }

    private fun detectAspects(samples: List<AstroSample>) = buildList {
        AstroBody.entries.forEachIndexed { i, first ->
            AstroBody.entries.drop(i + 1).forEach { second ->
                AstroAspect.entries.forEach { aspect ->
                    val closest = samples.minBy { sample ->
                        aspectOrb(
                            sample.longitudes.getValue(first),
                            sample.longitudes.getValue(second),
                            aspect.angle,
                        )
                    }
                    val orb = aspectOrb(
                        closest.longitudes.getValue(first),
                        closest.longitudes.getValue(second),
                        aspect.angle,
                    )
                    if (orb <= aspect.maxOrb) {
                        add(com.treepolo.dailyfortune.model.AspectHit(first, second, aspect, closest.instant, orb))
                    }
                }
            }
        }
    }

    fun zodiac(longitude: Double): ZodiacSign =
        ZodiacSign.entries[floor(normalize(longitude) / 30.0).toInt().coerceIn(0, 11)]

    internal fun signedAngularDelta(from: Double, to: Double): Double {
        var delta = normalize(to) - normalize(from)
        if (delta > 180.0) delta -= 360.0
        if (delta <= -180.0) delta += 360.0
        return delta
    }

    internal fun aspectOrb(first: Double, second: Double, target: Double): Double {
        val separation = abs(signedAngularDelta(first, second))
        return abs(separation - target)
    }

    private fun normalize(value: Double): Double {
        var result = value % 360.0
        if (result < 0.0) result += 360.0
        return result
    }

    private fun AstroBody.toEngineBody(): Body = when (this) {
        AstroBody.SUN -> Body.Sun
        AstroBody.MOON -> Body.Moon
        AstroBody.MERCURY -> Body.Mercury
        AstroBody.VENUS -> Body.Venus
        AstroBody.MARS -> Body.Mars
        AstroBody.JUPITER -> Body.Jupiter
        AstroBody.SATURN -> Body.Saturn
        AstroBody.URANUS -> Body.Uranus
        AstroBody.NEPTUNE -> Body.Neptune
        AstroBody.PLUTO -> Body.Pluto
    }
}
