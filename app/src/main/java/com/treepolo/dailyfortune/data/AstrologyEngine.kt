package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.AstroAspect
import com.treepolo.dailyfortune.model.AstroBody
import com.treepolo.dailyfortune.model.AstrologyAudit
import com.treepolo.dailyfortune.model.AstrologyDestiny
import com.treepolo.dailyfortune.model.AstrologyFactor
import com.treepolo.dailyfortune.model.AstronomyDayData
import com.treepolo.dailyfortune.model.DomainFortune
import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.FortuneGrade
import com.treepolo.dailyfortune.model.ZodiacSign
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

/**
 * Sun-sign daily astrology engine v1.
 *
 * Public results contain no random component. Every contribution originates from an astronomical
 * position/motion/aspect recorded in [AstronomyDayData] and an explicit rule below.
 */
object AstrologyEngine {
    const val version = "astrology-v1.2.0"
    private val taipei = ZoneId.of("Asia/Taipei")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    fun calculateDay(date: java.time.LocalDate): Map<ZodiacSign, AstrologyDestiny> {
        val astronomy = AstronomyEphemeris.analyze(date)
        return ZodiacSign.entries.associateWith { calculate(it, astronomy) }
    }

    fun calculate(zodiac: ZodiacSign, astronomy: AstronomyDayData): AstrologyDestiny {
        val factors = buildFactors(zodiac, astronomy)
        val scores = FortuneDomain.entries.associateWith { domain ->
            factors.sumOf { it.contributions[domain] ?: 0.0 }
        }
        val overall = scores.values.average()
        val domains = FortuneDomain.entries.associateWith { domain ->
            val score = scores.getValue(domain)
            DomainFortune(
                grade = grade(score, domain),
                explanation = domainExplanation(domain, score, factors),
            )
        }
        val audit = AstrologyAudit(
            engineVersion = version,
            date = astronomy.date,
            zodiac = zodiac,
            astronomy = astronomy,
            factors = factors,
            domainScores = scores,
            overallScore = overall,
        )
        return AstrologyDestiny(
            overallGrade = grade(overall),
            overallExplanation = overallExplanation(overall, scores, factors),
            domains = domains,
            audit = audit,
        )
    }

    private fun buildFactors(zodiac: ZodiacSign, astronomy: AstronomyDayData): List<AstrologyFactor> = buildList {
        astronomy.bodies.values.forEach { summary ->
            summary.signFractions.forEach { (sign, fraction) ->
                val house = houseFor(zodiac, sign)
                val dignity = dignityMultiplier(summary.body, sign)
                add(
                    AstrologyFactor(
                        id = "house:${summary.body.name}:${sign.name}",
                        title = "${summary.body.label}位於第${house}宮",
                        evidence = "${summary.body.label}於今日約 ${(fraction * 100).toInt()}% 時間位於${sign.label}（太陽星座整宮制第${house}宮）",
                        contributions = FortuneDomain.entries.associateWith { domain ->
                            bodyValence(summary.body) * AstrologyWeightsV12.houseFactorAffinity(summary.body, house, domain) *
                                dignity * fraction * 8.0
                        },
                    ),
                )

                val relation = signRelation(zodiac, sign)
                if (relation != null) {
                    add(
                        AstrologyFactor(
                            id = "sun-sign:${summary.body.name}:${sign.name}:${relation.label}",
                            title = "${summary.body.label}與${zodiac.label}形成${relation.label}",
                            evidence = "${summary.body.label}位於${sign.label}，以${zodiac.label}為太陽星座時形成星座級${relation.label}；不假設出生太陽的精確度數",
                            contributions = FortuneDomain.entries.associateWith { domain ->
                                relation.polarity(summary.body) * AstrologyWeightsV12.bodyFactorAffinity(summary.body, domain) *
                                    dignity * fraction * 5.0
                            },
                        ),
                    )
                }
            }

            if (summary.retrogradeFraction >= 0.5 && summary.body != AstroBody.SUN && summary.body != AstroBody.MOON) {
                val dominantSign = summary.signFractions.maxBy { it.value }.key
                val house = houseFor(zodiac, dominantSign)
                val stationBoost = if (summary.directionChanged) 1.35 else 1.0
                add(
                    AstrologyFactor(
                        id = "retrograde:${summary.body.name}",
                        title = "${summary.body.label}逆行",
                        evidence = buildString {
                            append("${summary.body.label}在四個六小時運動區段中約 ${(summary.retrogradeFraction * 100).toInt()}% 呈逆行")
                            if (summary.directionChanged) append("，且當日偵測到運動方向切換")
                        },
                        contributions = FortuneDomain.entries.associateWith { domain ->
                            -3.0 * retrogradeSensitivity(summary.body) * AstrologyWeightsV12.bodyFactorAffinity(summary.body, domain) *
                                (0.5 + 0.5 * AstrologyWeightsV12.houseRelevance(house, domain).coerceIn(0.0, 1.0)) * stationBoost
                        },
                    ),
                )
            }
        }

        val samples = astronomy.samples.associateBy { it.instant }
        astronomy.aspects.forEach { hit ->
            val sample = requireNotNull(samples[hit.closestTime])
            val signA = AstronomyEphemeris.zodiac(sample.longitudes.getValue(hit.first))
            val signB = AstronomyEphemeris.zodiac(sample.longitudes.getValue(hit.second))
            val houseA = houseFor(zodiac, signA)
            val houseB = houseFor(zodiac, signB)
            val strength = (1.0 - hit.orbDegrees / hit.aspect.maxOrb).coerceIn(0.0, 1.0)
            val dignity = (dignityMultiplier(hit.first, signA) + dignityMultiplier(hit.second, signB)) / 2.0
            val polarity = hit.aspect.basePolarity ?: (bodyValence(hit.first) + bodyValence(hit.second)).coerceIn(-1.0, 1.0)
            add(
                AstrologyFactor(
                    id = "aspect:${hit.first.name}:${hit.second.name}:${hit.aspect.name}",
                    title = "${hit.first.label}與${hit.second.label}${hit.aspect.label}",
                    evidence = "最近於 ${hit.closestTime.atZone(taipei).format(timeFormat)}，與${hit.aspect.angle.toInt()}°相差 ${formatOrb(hit.orbDegrees)}°；分別落第${houseA}、${houseB}宮",
                    contributions = FortuneDomain.entries.associateWith { domain ->
                        val directAffinity = AstrologyWeightsV12.aspectFactorAffinity(hit.first, hit.second, hit.aspect, domain)
                        val relevance = 0.6 + 0.4 * max(
                            AstrologyWeightsV12.houseRelevance(houseA, domain),
                            AstrologyWeightsV12.houseRelevance(houseB, domain),
                        ).coerceIn(0.0, 1.0)
                        polarity * hit.aspect.importance * strength * directAffinity * relevance * dignity * 10.0
                    },
                ),
            )
        }
    }

    fun grade(score: Double): FortuneGrade =
        gradeWithThresholds(score, AstrologyWeightsV12.overallGradeThresholds)

    private fun grade(score: Double, domain: FortuneDomain): FortuneGrade =
        gradeWithThresholds(score, AstrologyWeightsV12.domainGradeThresholds.getValue(domain))

    private fun gradeWithThresholds(score: Double, thresholds: DoubleArray): FortuneGrade = when {
        score >= thresholds[5] -> FortuneGrade.DAI_JI
        score >= thresholds[4] -> FortuneGrade.JI
        score >= thresholds[3] -> FortuneGrade.XIAO_JI
        score >= thresholds[2] -> FortuneGrade.PING
        score >= thresholds[1] -> FortuneGrade.XIAO_XIONG
        score >= thresholds[0] -> FortuneGrade.XIONG
        else -> FortuneGrade.DAI_XIONG
    }

    private fun domainExplanation(
        domain: FortuneDomain,
        score: Double,
        factors: List<AstrologyFactor>,
    ): String {
        val ranked = factors.map { it to (it.contributions[domain] ?: 0.0) }
        val positive = ranked.filter { it.second > 0.05 }.maxByOrNull { it.second }
        val negative = ranked.filter { it.second < -0.05 }.minByOrNull { it.second }
        return buildString {
            append("${grade(score, domain).label}（${formatScore(score)}分）。")
            when {
                positive != null && negative != null -> append("${positive.first.title}是主要加分；${negative.first.title}形成主要壓力。")
                positive != null -> append("主要正向來源是${positive.first.title}。")
                negative != null -> append("主要壓力來源是${negative.first.title}。")
                else -> append("今日沒有單一高權重天象主導此領域，整體接近平衡。")
            }
        }
    }

    private fun overallExplanation(
        overall: Double,
        scores: Map<FortuneDomain, Double>,
        factors: List<AstrologyFactor>,
    ): String {
        val bestDomain = scores.maxBy { it.value }
        val worstDomain = scores.minBy { it.value }
        val totalByFactor = factors.map { factor -> factor to factor.contributions.values.sum() }
        val positive = totalByFactor.filter { it.second > 0.1 }.maxByOrNull { it.second }
        val negative = totalByFactor.filter { it.second < -0.1 }.minByOrNull { it.second }
        return buildString {
            append("${grade(overall).label}（五項平均 ${formatScore(overall)} 分）。")
            append("相對較強的是${bestDomain.key.label}，較需留意${worstDomain.key.label}。")
            if (positive != null) append("主要加分來自${positive.first.title}。")
            if (negative != null) append("主要壓力來自${negative.first.title}。")
        }
    }

    private data class SignRelation(val label: String, val fixedPolarity: Double?) {
        fun polarity(body: AstroBody): Double = fixedPolarity ?: bodyValence(body)
    }

    private fun signRelation(user: ZodiacSign, transit: ZodiacSign): SignRelation? {
        val distance = Math.floorMod(transit.ordinal - user.ordinal, 12)
        return when (distance) {
            0 -> SignRelation("合相", null)
            2, 10 -> SignRelation("六合", 0.35)
            3, 9 -> SignRelation("刑相", -0.45)
            4, 8 -> SignRelation("拱相", 0.50)
            6 -> SignRelation("對分", -0.55)
            else -> null
        }
    }

    internal fun houseFor(user: ZodiacSign, transit: ZodiacSign): Int =
        Math.floorMod(transit.ordinal - user.ordinal, 12) + 1

    private fun bodyValence(body: AstroBody): Double = when (body) {
        AstroBody.SUN -> 0.25
        AstroBody.MOON -> 0.10
        AstroBody.MERCURY -> 0.00
        AstroBody.VENUS -> 0.65
        AstroBody.MARS -> -0.45
        AstroBody.JUPITER -> 0.85
        AstroBody.SATURN -> -0.55
        AstroBody.URANUS, AstroBody.NEPTUNE, AstroBody.PLUTO -> 0.00
    }

    private fun retrogradeSensitivity(body: AstroBody): Double = when (body) {
        AstroBody.MERCURY -> 1.00
        AstroBody.VENUS, AstroBody.MARS -> .80
        AstroBody.JUPITER, AstroBody.SATURN -> .45
        AstroBody.URANUS, AstroBody.NEPTUNE, AstroBody.PLUTO -> .12
        AstroBody.SUN, AstroBody.MOON -> 0.0
    }

    private enum class Dignity { DOMICILE, EXALTATION, DETRIMENT, FALL, NEUTRAL }

    internal fun dignityMultiplier(body: AstroBody, sign: ZodiacSign): Double = when (dignity(body, sign)) {
        Dignity.DOMICILE -> 1.20
        Dignity.EXALTATION -> 1.15
        Dignity.DETRIMENT -> .85
        Dignity.FALL -> .80
        Dignity.NEUTRAL -> 1.00
    }

    private fun dignity(body: AstroBody, sign: ZodiacSign): Dignity {
        if (body !in traditionalBodies) return Dignity.NEUTRAL
        if (sign in domicile.getValue(body)) return Dignity.DOMICILE
        if (sign == exaltation[body]) return Dignity.EXALTATION
        if (sign in detriment.getValue(body)) return Dignity.DETRIMENT
        if (sign == fall[body]) return Dignity.FALL
        return Dignity.NEUTRAL
    }

    private val traditionalBodies = setOf(
        AstroBody.SUN, AstroBody.MOON, AstroBody.MERCURY, AstroBody.VENUS,
        AstroBody.MARS, AstroBody.JUPITER, AstroBody.SATURN,
    )
    private val domicile = mapOf(
        AstroBody.SUN to setOf(ZodiacSign.LEO),
        AstroBody.MOON to setOf(ZodiacSign.CANCER),
        AstroBody.MERCURY to setOf(ZodiacSign.GEMINI, ZodiacSign.VIRGO),
        AstroBody.VENUS to setOf(ZodiacSign.TAURUS, ZodiacSign.LIBRA),
        AstroBody.MARS to setOf(ZodiacSign.ARIES, ZodiacSign.SCORPIO),
        AstroBody.JUPITER to setOf(ZodiacSign.SAGITTARIUS, ZodiacSign.PISCES),
        AstroBody.SATURN to setOf(ZodiacSign.CAPRICORN, ZodiacSign.AQUARIUS),
    )
    private val detriment = mapOf(
        AstroBody.SUN to setOf(ZodiacSign.AQUARIUS),
        AstroBody.MOON to setOf(ZodiacSign.CAPRICORN),
        AstroBody.MERCURY to setOf(ZodiacSign.SAGITTARIUS, ZodiacSign.PISCES),
        AstroBody.VENUS to setOf(ZodiacSign.ARIES, ZodiacSign.SCORPIO),
        AstroBody.MARS to setOf(ZodiacSign.TAURUS, ZodiacSign.LIBRA),
        AstroBody.JUPITER to setOf(ZodiacSign.GEMINI, ZodiacSign.VIRGO),
        AstroBody.SATURN to setOf(ZodiacSign.CANCER, ZodiacSign.LEO),
    )
    private val exaltation = mapOf(
        AstroBody.SUN to ZodiacSign.ARIES,
        AstroBody.MOON to ZodiacSign.TAURUS,
        AstroBody.MERCURY to ZodiacSign.VIRGO,
        AstroBody.VENUS to ZodiacSign.PISCES,
        AstroBody.MARS to ZodiacSign.CAPRICORN,
        AstroBody.JUPITER to ZodiacSign.CANCER,
        AstroBody.SATURN to ZodiacSign.LIBRA,
    )
    private val fall = mapOf(
        AstroBody.SUN to ZodiacSign.LIBRA,
        AstroBody.MOON to ZodiacSign.SCORPIO,
        AstroBody.MERCURY to ZodiacSign.PISCES,
        AstroBody.VENUS to ZodiacSign.VIRGO,
        AstroBody.MARS to ZodiacSign.CANCER,
        AstroBody.JUPITER to ZodiacSign.CAPRICORN,
        AstroBody.SATURN to ZodiacSign.ARIES,
    )

    private fun formatScore(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
    private fun formatOrb(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
}
