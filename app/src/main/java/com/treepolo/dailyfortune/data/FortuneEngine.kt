package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.DrawType
import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.FortuneGrade
import com.treepolo.dailyfortune.model.GradeDistribution
import com.treepolo.dailyfortune.model.OverallRule
import com.treepolo.dailyfortune.model.OverallRuleType
import com.treepolo.dailyfortune.model.PityConfig
import com.treepolo.dailyfortune.model.PityScope
import com.treepolo.dailyfortune.model.ResolvedExperimentConfig
import com.treepolo.dailyfortune.model.RoundingMethod
import com.treepolo.dailyfortune.model.SamplingConfig
import com.treepolo.dailyfortune.model.SamplingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

data class GeneratedFortune(
    val domainScores: Map<FortuneDomain, Int>,
    val rawAverage: Double,
    val overallGrade: FortuneGrade,
    val distributionId: String,
    val samplingProfileId: String,
    val overallRuleId: String,
    val probabilityPolicyId: String,
    val pityCounter: Int,
    val guaranteeTriggered: Boolean,
)

class FortuneEngine(
    private val random: Random = Random.Default,
) {
    fun draw(
        type: DrawType,
        config: ResolvedExperimentConfig,
        rerollIndex: Int = 0,
        consecutivePityMisses: Int = 0,
    ): GeneratedFortune {
        require(config.isValid()) { "Invalid experiment config" }
        val distribution = effectiveDistribution(type, config, rerollIndex)
        val uniforms = sampleUniforms(config.sampling)
        val sampledScores = FortuneDomain.entries.mapIndexed { index, domain ->
            domain to mapUniformToScore(uniforms[index], distribution)
        }.toMap()

        val pity = config.dynamicProbability.pity
        val shouldGuarantee = type == DrawType.REROLL &&
            pity?.enabled == true &&
            consecutivePityMisses >= pity.afterConsecutiveMisses
        val finalScores = if (shouldGuarantee) {
            enforcePity(sampledScores, pity!!, config.overallRule)
        } else {
            sampledScores
        }
        val average = finalScores.values.average()
        val overall = FortuneGrade.fromScore(resolveOverallScore(average, config.overallRule))
        val guaranteeTriggered = shouldGuarantee && finalScores != sampledScores

        return GeneratedFortune(
            domainScores = finalScores,
            rawAverage = average,
            overallGrade = overall,
            distributionId = distribution.id,
            samplingProfileId = config.sampling.profileId,
            overallRuleId = config.overallRule.id,
            probabilityPolicyId = config.dynamicProbability.policyId,
            pityCounter = consecutivePityMisses,
            guaranteeTriggered = guaranteeTriggered,
        )
    }

    private fun effectiveDistribution(
        type: DrawType,
        config: ResolvedExperimentConfig,
        rerollIndex: Int,
    ): GradeDistribution {
        if (type == DrawType.INITIAL) return config.initialDistribution
        val scheduled = config.dynamicProbability.rerollSchedule.firstOrNull { band ->
            rerollIndex >= band.minRerollIndexInclusive &&
                (band.maxRerollIndexInclusive == null || rerollIndex <= band.maxRerollIndexInclusive)
        }
        return scheduled?.distribution ?: config.rerollDistribution
    }

    private fun enforcePity(
        rawScores: Map<FortuneDomain, Int>,
        pity: PityConfig,
        overallRule: OverallRule,
    ): Map<FortuneDomain, Int> {
        if (meetsPityCondition(rawScores, pity, overallRule)) return rawScores
        val mutable = rawScores.toMutableMap()
        when (pity.scope) {
            PityScope.ANY_DOMAIN_AT_LEAST -> {
                val candidates = FortuneDomain.entries.filter { mutable.getValue(it) < pity.successScore }
                if (candidates.isNotEmpty()) {
                    val chosen = candidates[random.nextInt(candidates.size)]
                    mutable[chosen] = pity.successScore
                }
            }
            PityScope.OVERALL_AT_LEAST -> {
                var guard = 0
                while (!meetsPityCondition(mutable, pity, overallRule) && guard < 64) {
                    val candidates = FortuneDomain.entries.filter { mutable.getValue(it) < 7 }
                    if (candidates.isEmpty()) break
                    val minimum = candidates.minOf { mutable.getValue(it) }
                    val lowest = candidates.filter { mutable.getValue(it) == minimum }
                    val chosen = lowest[random.nextInt(lowest.size)]
                    mutable[chosen] = mutable.getValue(chosen) + 1
                    guard += 1
                }
            }
        }
        return mutable
    }

    fun meetsPityCondition(
        scores: Map<FortuneDomain, Int>,
        pity: PityConfig,
        overallRule: OverallRule,
    ): Boolean = when (pity.scope) {
        PityScope.ANY_DOMAIN_AT_LEAST -> scores.values.any { it >= pity.successScore }
        PityScope.OVERALL_AT_LEAST -> resolveOverallScore(scores.values.average(), overallRule) >= pity.successScore
    }

    private fun sampleUniforms(config: SamplingConfig): List<Double> = when (config.mode) {
        SamplingMode.INDEPENDENT -> List(FortuneDomain.entries.size) { random.nextDouble() }
        SamplingMode.GAUSSIAN_COPULA -> correlatedUniforms(config)
    }

    private fun correlatedUniforms(config: SamplingConfig): List<Double> {
        val matrix = requireNotNull(config.correlationMatrix) {
            "GAUSSIAN_COPULA requires a correlation matrix"
        }
        val cholesky = cholesky(matrix)
        val normals = standardNormals(FortuneDomain.entries.size)
        return List(FortuneDomain.entries.size) { row ->
            var value = 0.0
            for (column in 0..row) value += cholesky[row][column] * normals[column]
            normalCdf(value).coerceIn(0.0, 1.0 - 1e-15)
        }
    }

    private fun mapUniformToScore(value: Double, distribution: GradeDistribution): Int {
        var cumulative = 0.0
        distribution.probabilities.forEachIndexed { index, probability ->
            cumulative += probability
            if (value < cumulative || index == distribution.probabilities.lastIndex) return index + 1
        }
        return 7
    }

    internal fun resolveOverallScore(average: Double, rule: OverallRule): Int {
        require(average in 1.0..7.0) { "Average must be in [1, 7]: $average" }
        val resolved = when (rule.type) {
            OverallRuleType.FLOOR -> applyRounding(average, RoundingMethod.FLOOR)
            OverallRuleType.CEIL -> applyRounding(average, RoundingMethod.CEIL)
            OverallRuleType.ROUND -> applyRounding(average, RoundingMethod.ROUND)
            OverallRuleType.PIECEWISE -> {
                require(rule.segments.isNotEmpty()) { "PIECEWISE rule requires segments" }
                val segment = rule.segments.firstOrNull { candidate ->
                    average >= candidate.minInclusive &&
                        (candidate.maxExclusive == null || average < candidate.maxExclusive ||
                            (average == 7.0 && candidate.maxExclusive == 7.0))
                } ?: throw IllegalArgumentException("No PIECEWISE segment covers average=$average")
                applyRounding(average, segment.method)
            }
        }
        return resolved.coerceIn(1, 7)
    }

    private fun applyRounding(value: Double, method: RoundingMethod): Int = when (method) {
        RoundingMethod.FLOOR -> floor(value).toInt()
        RoundingMethod.CEIL -> ceil(value).toInt()
        RoundingMethod.ROUND -> floor(value + 0.5).toInt()
    }

    private fun standardNormals(count: Int): List<Double> {
        val values = ArrayList<Double>(count)
        while (values.size < count) {
            val u1 = max(random.nextDouble(), 1e-15)
            val u2 = random.nextDouble()
            val radius = sqrt(-2.0 * ln(u1))
            val angle = 2.0 * PI * u2
            values += radius * kotlin.math.cos(angle)
            if (values.size < count) values += radius * kotlin.math.sin(angle)
        }
        return values
    }

    private fun cholesky(matrix: List<List<Double>>): Array<DoubleArray> {
        val n = FortuneDomain.entries.size
        require(matrix.size == n && matrix.all { it.size == n }) { "Correlation matrix must be 5x5" }
        for (i in 0 until n) {
            require(abs(matrix[i][i] - 1.0) <= 1e-6) { "Correlation diagonal must equal 1" }
            for (j in 0 until n) {
                require(matrix[i][j] in -1.0..1.0) { "Correlation must be in [-1, 1]" }
                require(abs(matrix[i][j] - matrix[j][i]) <= 1e-6) { "Correlation matrix must be symmetric" }
            }
        }

        val result = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0..i) {
                var sum = matrix[i][j]
                for (k in 0 until j) sum -= result[i][k] * result[j][k]
                if (i == j) {
                    require(sum > 1e-10) { "Correlation matrix must be positive definite" }
                    result[i][j] = sqrt(sum)
                } else {
                    result[i][j] = sum / result[j][j]
                }
            }
        }
        return result
    }

    private fun normalCdf(value: Double): Double = 0.5 * (1.0 + erf(value / sqrt(2.0)))

    private fun erf(value: Double): Double {
        val sign = if (value < 0.0) -1.0 else 1.0
        val x = abs(value)
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val polynomial = (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t -
            0.284496736) * t + 0.254829592) * t
        val y = 1.0 - polynomial * exp(-x * x)
        return sign * min(1.0, max(0.0, y))
    }
}
