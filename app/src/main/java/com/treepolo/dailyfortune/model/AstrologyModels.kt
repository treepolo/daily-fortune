package com.treepolo.dailyfortune.model

import java.time.Instant
import java.time.LocalDate

enum class AstroBody(val label: String) {
    SUN("太陽"),
    MOON("月亮"),
    MERCURY("水星"),
    VENUS("金星"),
    MARS("火星"),
    JUPITER("木星"),
    SATURN("土星"),
    URANUS("天王星"),
    NEPTUNE("海王星"),
    PLUTO("冥王星"),
}

enum class AstroAspect(
    val label: String,
    val angle: Double,
    val maxOrb: Double,
    val basePolarity: Double?,
    val importance: Double,
) {
    CONJUNCTION("合相", 0.0, 1.5, null, 1.00),
    SEXTILE("六合", 60.0, 1.0, 0.45, 0.60),
    SQUARE("刑相", 90.0, 1.5, -0.75, 0.90),
    TRINE("拱相", 120.0, 1.2, 0.65, 0.75),
    OPPOSITION("對分", 180.0, 1.5, -0.70, 0.95),
}

data class AstroSample(
    val instant: Instant,
    val longitudes: Map<AstroBody, Double>,
)

data class SignIngress(
    val body: AstroBody,
    val from: ZodiacSign,
    val to: ZodiacSign,
    val nearTime: Instant,
)

data class BodyDaySummary(
    val body: AstroBody,
    val signFractions: Map<ZodiacSign, Double>,
    val averageSpeedDegPerDay: Double,
    val retrogradeFraction: Double,
    val directionChanged: Boolean,
    val ingresses: List<SignIngress>,
)

data class AspectHit(
    val first: AstroBody,
    val second: AstroBody,
    val aspect: AstroAspect,
    val closestTime: Instant,
    val orbDegrees: Double,
)

data class AstronomyDayData(
    val date: LocalDate,
    val samples: List<AstroSample>,
    val bodies: Map<AstroBody, BodyDaySummary>,
    val aspects: List<AspectHit>,
)

data class AstrologyFactor(
    val id: String,
    val title: String,
    val evidence: String,
    val contributions: Map<FortuneDomain, Double>,
)

data class AstrologyAudit(
    val engineVersion: String,
    val date: LocalDate,
    val zodiac: ZodiacSign,
    val astronomy: AstronomyDayData,
    val factors: List<AstrologyFactor>,
    val domainScores: Map<FortuneDomain, Double>,
    val overallScore: Double,
)

data class AstrologyDestiny(
    val overallGrade: FortuneGrade,
    val overallExplanation: String,
    val domains: Map<FortuneDomain, DomainFortune>,
    val audit: AstrologyAudit,
)
