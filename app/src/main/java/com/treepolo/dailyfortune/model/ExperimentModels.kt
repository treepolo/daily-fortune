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

data class RerollDistributionBand(
    val minRerollIndexInclusive: Int,
    val maxRerollIndexInclusive: Int?,
    val distribution: GradeDistribution,
)

enum class PityScope {
    OVERALL_AT_LEAST,
    ANY_DOMAIN_AT_LEAST,
}

data class PityConfig(
    val enabled: Boolean = false,
    val afterConsecutiveMisses: Int = 0,
    val successScore: Int = 7,
    val scope: PityScope = PityScope.OVERALL_AT_LEAST,
)

data class DynamicProbabilityConfig(
    val policyId: String = "static-v1",
    val rerollSchedule: List<RerollDistributionBand> = emptyList(),
    val pity: PityConfig? = null,
)

data class VisualExperimentConfig(
    val staticVariantId: String = "baseline",
    val revealVariantId: String = "interactive-paper-v1",
)

enum class AdFailurePolicy {
    FAIL_OPEN,
    FAIL_CLOSED,
}

data class AdsConfig(
    val enabled: Boolean = false,
    val provider: String = "ADMOB",
    val rewardedUnitId: String = "",
    val bypassOverallScores: Set<Int> = setOf(7),
    val failurePolicy: AdFailurePolicy = AdFailurePolicy.FAIL_OPEN,
    val preload: Boolean = true,
    val loadTimeoutMillis: Long = 8_000L,
) {
    fun isValid(): Boolean =
        provider.isNotBlank() &&
            (!enabled || rewardedUnitId.isNotBlank()) &&
            bypassOverallScores.all { it in 1..7 } &&
            loadTimeoutMillis in 1_000L..60_000L
}

data class ResolvedExperimentConfig(
    val configId: String,
    val assignments: List<ExperimentAssignment>,
    val initialDistribution: GradeDistribution,
    val rerollDistribution: GradeDistribution,
    val sampling: SamplingConfig,
    val overallRule: OverallRule,
    val dynamicProbability: DynamicProbabilityConfig = DynamicProbabilityConfig(),
    val visual: VisualExperimentConfig = VisualExperimentConfig(),
    val ads: AdsConfig = AdsConfig(),
) {
    fun isValid(): Boolean =
        configId.isNotBlank() &&
            initialDistribution.isValid() &&
            rerollDistribution.isValid() &&
            sampling.profileId.isNotBlank() &&
            overallRule.id.isNotBlank() &&
            dynamicProbability.policyId.isNotBlank() &&
            ads.isValid()

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
                dynamicProbability = DynamicProbabilityConfig(
                    policyId = "static-v1",
                    rerollSchedule = emptyList(),
                    pity = PityConfig(enabled = false),
                ),
                ads = AdsConfig(enabled = false),
            )
        }
    }
}
