package com.treepolo.dailyfortune.model

import kotlin.math.abs

data class GradeDistribution(
    val id: String,
    val probabilities: List<Double>,
) {
    fun isValid(): Boolean {
        if (id.isBlank() || probabilities.size != 7) return false
        if (probabilities.any { !it.isFinite() || it < 0.0 }) return false
        return abs(probabilities.sum() - 1.0) <= 1e-6
    }
}

enum class SamplingMode {
    INDEPENDENT,
    GAUSSIAN_COPULA,
}

data class SamplingConfig(
    val mode: SamplingMode,
    val profileId: String,
    val correlationMatrix: List<List<Double>>? = null,
)

enum class RoundingMethod {
    FLOOR,
    CEIL,
    ROUND,
}

enum class OverallRuleType {
    FLOOR,
    CEIL,
    ROUND,
    PIECEWISE,
}

data class OverallRuleSegment(
    val minInclusive: Double,
    val maxExclusive: Double?,
    val method: RoundingMethod,
)

data class OverallRule(
    val id: String,
    val type: OverallRuleType,
    val segments: List<OverallRuleSegment> = emptyList(),
)

data class VisualExperimentConfig(
    val staticVariantId: String = "baseline",
    val revealVariantId: String = "none",
)

data class ResolvedExperimentConfig(
    val configId: String,
    val assignments: List<ExperimentAssignment>,
    val initialDistribution: GradeDistribution,
    val rerollDistribution: GradeDistribution,
    val sampling: SamplingConfig,
    val overallRule: OverallRule,
    val visual: VisualExperimentConfig = VisualExperimentConfig(),
) {
    fun isValid(): Boolean =
        configId.isNotBlank() &&
            initialDistribution.isValid() &&
            rerollDistribution.isValid() &&
            sampling.profileId.isNotBlank() &&
            overallRule.id.isNotBlank()

    companion object {
        fun embeddedDefault(): ResolvedExperimentConfig {
            val uniform = List(7) { 1.0 / 7.0 }
            return ResolvedExperimentConfig(
                configId = "embedded-default-v1",
                assignments = emptyList(),
                initialDistribution = GradeDistribution("uniform-v1", uniform),
                rerollDistribution = GradeDistribution("uniform-v1", uniform),
                sampling = SamplingConfig(
                    mode = SamplingMode.INDEPENDENT,
                    profileId = "independent-v1",
                ),
                overallRule = OverallRule(
                    id = "floor-v1",
                    type = OverallRuleType.FLOOR,
                ),
            )
        }
    }
}
