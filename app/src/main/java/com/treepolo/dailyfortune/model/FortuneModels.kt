package com.treepolo.dailyfortune.model

import java.time.LocalDate

enum class FortuneGrade(
    val score: Int,
    val label: String,
) {
    DAI_XIONG(1, "大凶"),
    XIONG(2, "凶"),
    XIAO_XIONG(3, "小凶"),
    PING(4, "平"),
    XIAO_JI(5, "小吉"),
    JI(6, "吉"),
    DAI_JI(7, "大吉");

    companion object {
        fun fromScore(score: Int): FortuneGrade =
            entries.firstOrNull { it.score == score }
                ?: throw IllegalArgumentException("Fortune score must be between 1 and 7: $score")
    }
}

enum class FortuneDomain(val label: String) {
    WEALTH("財運"),
    LOVE("戀愛"),
    WORK_STUDY("工作／學業"),
    RELATIONSHIPS("人際"),
    HEALTH("健康"),
}

enum class DrawType {
    INITIAL,
    REROLL,
}

data class ExperimentAssignment(
    val experimentId: String,
    val variantId: String,
)

data class FortuneDraw(
    val id: String,
    val fortuneDate: LocalDate,
    val drawIndex: Int,
    val drawType: DrawType,
    val domainScores: Map<FortuneDomain, Int>,
    val rawAverage: Double,
    val overallGrade: FortuneGrade,
    val configId: String,
    val assignments: List<ExperimentAssignment>,
    val distributionId: String,
    val samplingProfileId: String,
    val overallRuleId: String,
    val createdAtEpochMillis: Long,
)
