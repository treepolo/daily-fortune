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
import kotlin.math.abs
import kotlin.math.max

/**
 * Sun-sign daily astrology engine v1.
 *
 * Public results contain no random component. Every contribution originates from an astronomical
 * position/motion/aspect recorded in [AstronomyDayData] and an explicit rule below.
 */
object AstrologyEngine {
    const val version = "astrology-v1.0.0"
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
                grade = grade(score),
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
                val houseContributions = FortuneDomain.entries.associateWith { domain ->
                    bodyValence(summary.body) * affinity(summary.body, domain) *
                        houseWeight(house, domain) * dignity * fraction * 8.0
                }
                addIfMeaningful(
                    AstrologyFactor(
                        id = "house:${summary.body.name}:${sign.name}",
                        title = "${summary.body.label}位於第${house}宮",
                        evidence = "${summary.body.label}於今日約 ${(fraction * 100).toInt()}% 時間位於${sign.label}（太陽星座整宮制第${house}宮）",
                        contributions = houseContributions,
                    ),
                )

                val relation = signRelation(zodiac, sign)
                if (relation != null) {
                    val relationContributions = FortuneDomain.entries.associateWith { domain ->
                        relation.polarity(summary.body) * affinity(summary.body, domain) *
                            dignity * fraction * 5.0
                    }
                    addIfMeaningful(
                        AstrologyFactor(
                            id = "sun-sign:${summary.body.name}:${sign.name}:${relation.label}",
                            title = "${summary.body.label}與${zodiac.label}形成${relation.label}",
                            evidence = "${summary.body.label}位於${sign.label}，以${zodiac.label}為太陽星座時形成星座級${relation.label}；不假設出生太陽的精確度數",
                            contributions = relationContributions,
                        ),
                    )
                }
            }

            if (summary.retrogradeFraction >= 0.5 && summary.body != AstroBody.SUN && summary.body != AstroBody.MOON) {
                val dominantSign = summary.signFractions.maxBy { it.value }.key
                val house = houseFor(zodiac, dominantSign)
                val stationBoost = if (summary.directionChanged) 1.35 else 1.0
                val contributions = FortuneDomain.entries.associateWith { domain ->
                    -3.0 * retrogradeSensitivity(summary.body) * affinity(summary.body, domain) *
                        (0.5 + 0.5 * houseWeight(house, domain).coerceIn(0.0, 1.0)) * stationBoost
                }
                addIfMeaningful(
                    AstrologyFactor(
                        id = "retrograde:${summary.body.name}",
                        title = "${summary.body.label}逆行",
                        evidence = buildString {
                            append("${summary.body.label}在四個六小時運動區段中約 ${(summary.retrogradeFraction * 100).toInt()}% 呈逆行")
                            if (summary.directionChanged) append("，且當日偵測到運動方向切換")
                        },
                        contributions = contributions,
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
            val contributions = FortuneDomain.entries.associateWith { domain ->
                val affinity = (affinity(hit.first, domain) + affinity(hit.second, domain)) / 2.0
                val houseRelevance = 0.6 + 0.4 * max(
                    houseWeight(houseA, domain),
                    houseWeight(houseB, domain),
                ).coerceIn(0.0, 1.0)
                polarity * hit.aspect.importance * strength * affinity * houseRelevance * dignity * 10.0
            }
            addIfMeaningful(
                AstrologyFactor(
                    id = "aspect:${hit.first.name}:${hit.second.name}:${hit.aspect.name}",
                    title = "${hit.first.label}與${hit.second.label}${hit.aspect.label}",
                    evidence = "最近於 ${hit.closestTime.atZone(taipei).format(timeFormat)}，與${hit.aspect.angle.toInt()}°相差 ${formatOrb(hit.orbDegrees)}°；分別落第${houseA}、${houseB}宮",
                    contributions = contributions,
                ),
            )
        }
    }

    private fun MutableList<AstrologyFactor>.addIfMeaningful(factor: AstrologyFactor) {
        if (factor.contributions.values.any { abs(it) >= 0.01 }) add(factor)
    }

    fun grade(score: Double): FortuneGrade = when {
        score >= 10.0 -> FortuneGrade.DAI_JI
        score >= 5.5 -> FortuneGrade.JI
        score >= 1.8 -> FortuneGrade.XIAO_JI
        score > -1.8 -> FortuneGrade.PING
        score > -5.5 -> FortuneGrade.XIAO_XIONG
        score > -10.0 -> FortuneGrade.XIONG
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
            append("${grade(score).label}（${formatScore(score)}分）。")
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

    private fun affinity(body: AstroBody, domain: FortuneDomain): Double = bodyAffinities.getValue(body).getValue(domain)

    private val bodyAffinities = mapOf(
        AstroBody.SUN to affinities(.25, .20, .75, .25, .85),
        AstroBody.MOON to affinities(.20, .70, .20, .80, .75),
        AstroBody.MERCURY to affinities(.55, .35, 1.00, .70, .20),
        AstroBody.VENUS to affinities(.55, 1.00, .25, .95, .30),
        AstroBody.MARS to affinities(.35, .65, .85, .55, .85),
        AstroBody.JUPITER to affinities(.95, .45, .80, .60, .50),
        AstroBody.SATURN to affinities(.75, .35, .90, .45, .70),
        AstroBody.URANUS to affinities(.45, .45, .55, .55, .35),
        AstroBody.NEPTUNE to affinities(.35, .65, .45, .55, .50),
        AstroBody.PLUTO to affinities(.50, .55, .55, .55, .45),
    )

    private fun affinities(w: Double, l: Double, work: Double, r: Double, h: Double) = mapOf(
        FortuneDomain.WEALTH to w,
        FortuneDomain.LOVE to l,
        FortuneDomain.WORK_STUDY to work,
        FortuneDomain.RELATIONSHIPS to r,
        FortuneDomain.HEALTH to h,
    )

    internal fun houseWeight(house: Int, domain: FortuneDomain): Double = when (house) {
        1 -> weights(domain, work = .25, relationships = .20, health = 1.00)
        2 -> weights(domain, wealth = 1.00)
        3 -> weights(domain, work = .90, relationships = .50)
        4 -> weights(domain, love = .45, relationships = .55, health = .35)
        5 -> weights(domain, love = 1.00, work = .35, relationships = .25)
        6 -> weights(domain, work = .85, health = 1.00)
        7 -> weights(domain, wealth = .25, love = 1.00, relationships = 1.00)
        8 -> weights(domain, wealth = .75, love = .45, health = .35)
        9 -> weights(domain, work = .85, relationships = .25)
        10 -> weights(domain, wealth = .40, work = 1.00, relationships = .20)
        11 -> weights(domain, wealth = .35, love = .25, work = .35, relationships = 1.00)
        12 -> weights(domain, love = .25, work = .20, relationships = .25, health = .60)
        else -> 0.0
    }

    private fun weights(
        domain: FortuneDomain,
        wealth: Double = 0.0,
        love: Double = 0.0,
        work: Double = 0.0,
        relationships: Double = 0.0,
        health: Double = 0.0,
    ): Double = when (domain) {
        FortuneDomain.WEALTH -> wealth
        FortuneDomain.LOVE -> love
        FortuneDomain.WORK_STUDY -> work
        FortuneDomain.RELATIONSHIPS -> relationships
        FortuneDomain.HEALTH -> health
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
