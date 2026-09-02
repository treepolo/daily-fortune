package com.treepolo.dailyfortune.model

import java.time.LocalDate

enum class FortuneGrade(
    val label: String,
    val isNonXiong: Boolean,
) {
    DAI_JI("大吉", true),
    JI("吉", true),
    XIAO_JI("小吉", true),
    PING("平", true),
    XIAO_XIONG("小凶", false),
    XIONG("凶", false),
    DAI_XIONG("大凶", false),
}

enum class FortuneDomain(val label: String) {
    WEALTH("財運"),
    LOVE("戀愛"),
    WORK_STUDY("工作／學業"),
    RELATIONSHIPS("人際"),
    HEALTH("健康"),
}

enum class ZodiacSign(val label: String) {
    ARIES("牡羊座"),
    TAURUS("金牛座"),
    GEMINI("雙子座"),
    CANCER("巨蟹座"),
    LEO("獅子座"),
    VIRGO("處女座"),
    LIBRA("天秤座"),
    SCORPIO("天蠍座"),
    SAGITTARIUS("射手座"),
    CAPRICORN("摩羯座"),
    AQUARIUS("水瓶座"),
    PISCES("雙魚座"),
}

data class DomainFortune(
    val grade: FortuneGrade,
    val explanation: String,
)

enum class DestinySource {
    PUBLIC_ASTROLOGY,
    PERSONAL_ASTROLOGY,
}

data class ParallelSkyInfo(
    val originalDate: LocalDate,
    val sourceDate: LocalDate,
    val originalSunLongitude: Double,
    val alteredSunLongitude: Double,
    val sunLongitudeDifference: Double,
    val engineVersion: String,
)

data class ResolvedDestiny(
    val source: DestinySource,
    val overallGrade: FortuneGrade,
    val overallExplanation: String,
    val domains: Map<FortuneDomain, DomainFortune>,
    val astrologyAudit: AstrologyAudit,
    val parallelSky: ParallelSkyInfo? = null,
)

enum class ChangeOutcome {
    IMPROVED,
    SIMILAR,
    WORSENED,
}

data class DomainChange(
    val domain: FortuneDomain,
    val beforeGrade: FortuneGrade,
    val afterGrade: FortuneGrade,
    val scoreDelta: Double,
)

data class DestinyChange(
    val outcome: ChangeOutcome,
    val beforeOverallGrade: FortuneGrade,
    val afterOverallGrade: FortuneGrade,
    val overallScoreDelta: Double,
    val domainChanges: Map<FortuneDomain, DomainChange>,
    val removedFactors: List<String>,
    val addedFactors: List<String>,
    val narrative: String,
)

data class FortuneStats(
    val totalRerolls: Int = 0,
    val totalDrawDays: Int = 0,
    val maxDailyRerolls: Int = 0,
    val totalDraws: Int = 0,
    val daiJiDraws: Int = 0,
    val nonXiongDraws: Int = 0,
    val daiXiongDraws: Int = 0,
) {
    val averageDailyRerolls: Double
        get() = if (totalDrawDays == 0) 0.0 else totalRerolls.toDouble() / totalDrawDays

    val daiJiRate: Double
        get() = rate(daiJiDraws)

    val nonXiongRate: Double
        get() = rate(nonXiongDraws)

    val daiXiongRate: Double
        get() = rate(daiXiongDraws)

    private fun rate(count: Int): Double =
        if (totalDraws == 0) 0.0 else count.toDouble() / totalDraws
}

data class PersistedFortuneState(
    val todayDate: String? = null,
    val selectedZodiac: ZodiacSign? = null,
    val todayPersonalSkyDate: LocalDate? = null,
    val todayRerollCount: Int = 0,
    val todaySeen: Boolean = false,
    val stats: FortuneStats = FortuneStats(),
)
