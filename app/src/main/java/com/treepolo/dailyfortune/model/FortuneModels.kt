package com.treepolo.dailyfortune.model

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

data class FortuneDefinition(
    val number: Int,
    val sourceGrade: String,
    val grade: FortuneGrade,
    val poem: List<String>,
    val generalExplanation: String,
    val domains: Map<FortuneDomain, DomainFortune>,
)

/** 私人「逆天改命」的一整包古籤結果。 */
data class DestinySnapshot(
    val overallFortuneNumber: Int,
    val domainFortuneNumbers: Map<FortuneDomain, Int>,
)

enum class DestinySource {
    PUBLIC_ASTROLOGY,
    PERSONAL_FORTUNE,
}

data class ResolvedDestiny(
    val source: DestinySource,
    val overallGrade: FortuneGrade,
    val overallExplanation: String,
    val domains: Map<FortuneDomain, DomainFortune>,
    val snapshot: DestinySnapshot? = null,
    val fortune: FortuneDefinition? = null,
    val astrologyAudit: AstrologyAudit? = null,
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
    val todayPersonalDestiny: DestinySnapshot? = null,
    val todayRerollCount: Int = 0,
    val todaySeen: Boolean = false,
    val stats: FortuneStats = FortuneStats(),
)
