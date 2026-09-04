package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.AdFailurePolicy
import com.treepolo.dailyfortune.model.AdsConfig
import com.treepolo.dailyfortune.model.DynamicProbabilityConfig
import com.treepolo.dailyfortune.model.ExperimentAssignment
import com.treepolo.dailyfortune.model.GradeDistribution
import com.treepolo.dailyfortune.model.OverallRule
import com.treepolo.dailyfortune.model.OverallRuleSegment
import com.treepolo.dailyfortune.model.OverallRuleType
import com.treepolo.dailyfortune.model.PityConfig
import com.treepolo.dailyfortune.model.PityScope
import com.treepolo.dailyfortune.model.ResolvedExperimentConfig
import com.treepolo.dailyfortune.model.RerollDistributionBand
import com.treepolo.dailyfortune.model.RoundingMethod
import com.treepolo.dailyfortune.model.SamplingConfig
import com.treepolo.dailyfortune.model.SamplingMode
import com.treepolo.dailyfortune.model.VisualExperimentConfig
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

object ExperimentConfigCodec {
    fun decode(json: String): ResolvedExperimentConfig {
        val root = JSONObject(json)
        val fortune = root.getJSONObject("fortune")
        val samplingObject = fortune.getJSONObject("sampling")
        val overallObject = fortune.getJSONObject("overall_rule")
        val dynamicObject = fortune.optJSONObject("dynamic_probability")
        val visualObject = root.optJSONObject("visual")
        val adsObject = root.optJSONObject("ads")

        val config = ResolvedExperimentConfig(
            configId = root.getString("config_id"),
            assignments = parseAssignments(root.optJSONArray("assignments")),
            initialDistribution = parseDistribution(fortune.getJSONObject("initial_distribution")),
            rerollDistribution = parseDistribution(fortune.getJSONObject("reroll_distribution")),
            sampling = SamplingConfig(
                mode = SamplingMode.valueOf(samplingObject.getString("mode")),
                profileId = samplingObject.getString("profile_id"),
                correlationMatrix = samplingObject.optJSONArray("correlation_matrix")?.let(::parseMatrix),
            ),
            overallRule = OverallRule(
                id = overallObject.getString("id"),
                type = OverallRuleType.valueOf(overallObject.getString("type")),
                segments = overallObject.optJSONArray("segments")?.let(::parseSegments).orEmpty(),
            ),
            dynamicProbability = parseDynamicProbability(dynamicObject),
            visual = VisualExperimentConfig(
                staticVariantId = visualObject?.optString("static_variant_id", "baseline") ?: "baseline",
                revealVariantId = visualObject?.optString("reveal_variant_id", "interactive-paper-v1")
                    ?: "interactive-paper-v1",
            ),
            ads = parseAds(adsObject),
        )
        require(config.isValid()) { "Remote experiment config failed validation" }
        FortuneConfigValidator.validate(config)
        return config
    }

    fun assignmentsToJson(assignments: List<ExperimentAssignment>): String {
        val array = JSONArray()
        assignments.forEach { assignment ->
            array.put(
                JSONObject()
                    .put("experiment_id", assignment.experimentId)
                    .put("variant_id", assignment.variantId),
            )
        }
        return array.toString()
    }

    fun assignmentsFromJson(json: String): List<ExperimentAssignment> =
        parseAssignments(JSONArray(json))

    private fun parseDistribution(value: JSONObject): GradeDistribution = GradeDistribution(
        id = value.getString("id"),
        probabilities = value.getJSONArray("probabilities").toDoubleList(),
    )

    private fun parseDynamicProbability(value: JSONObject?): DynamicProbabilityConfig {
        if (value == null) return DynamicProbabilityConfig()
        val schedule = value.optJSONArray("reroll_schedule")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        RerollDistributionBand(
                            minRerollIndexInclusive = item.getInt("min_reroll_index_inclusive"),
                            maxRerollIndexInclusive = if (item.isNull("max_reroll_index_inclusive")) {
                                null
                            } else {
                                item.getInt("max_reroll_index_inclusive")
                            },
                            distribution = parseDistribution(item.getJSONObject("distribution")),
                        ),
                    )
                }
            }
        }.orEmpty()
        val pityObject = value.optJSONObject("pity")
        val pity = pityObject?.let {
            PityConfig(
                enabled = it.optBoolean("enabled", false),
                afterConsecutiveMisses = it.optInt("after_consecutive_misses", 0),
                successScore = it.optInt("success_score", 7),
                scope = PityScope.valueOf(it.optString("scope", PityScope.OVERALL_AT_LEAST.name)),
            )
        }
        return DynamicProbabilityConfig(
            policyId = value.optString("policy_id", "static-v1"),
            rerollSchedule = schedule,
            pity = pity,
        )
    }

    private fun parseAds(value: JSONObject?): AdsConfig {
        if (value == null) return AdsConfig(enabled = false)
        val bypass = value.optJSONArray("bypass_overall_scores")?.let { array ->
            buildSet {
                for (index in 0 until array.length()) add(array.getInt(index))
            }
        } ?: setOf(7)
        return AdsConfig(
            enabled = value.optBoolean("enabled", false),
            provider = value.optString("provider", "ADMOB"),
            rewardedUnitId = value.optString("rewarded_unit_id", ""),
            bypassOverallScores = bypass,
            failurePolicy = AdFailurePolicy.valueOf(
                value.optString("failure_policy", AdFailurePolicy.FAIL_OPEN.name),
            ),
            preload = value.optBoolean("preload", true),
            loadTimeoutMillis = value.optLong("load_timeout_millis", 8_000L),
        )
    }

    private fun parseAssignments(array: JSONArray?): List<ExperimentAssignment> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(
                ExperimentAssignment(
                    experimentId = item.getString("experiment_id"),
                    variantId = item.getString("variant_id"),
                ),
            )
        }
    }

    private fun parseMatrix(array: JSONArray): List<List<Double>> = buildList {
        for (row in 0 until array.length()) add(array.getJSONArray(row).toDoubleList())
    }

    private fun parseSegments(array: JSONArray): List<OverallRuleSegment> = buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(
                OverallRuleSegment(
                    minInclusive = item.getDouble("min_inclusive"),
                    maxExclusive = if (item.isNull("max_exclusive")) null else item.getDouble("max_exclusive"),
                    method = RoundingMethod.valueOf(item.getString("method")),
                ),
            )
        }
    }

    private fun JSONArray.toDoubleList(): List<Double> =
        List(length()) { index -> getDouble(index) }
}

object FortuneConfigValidator {
    private const val MATRIX_SIZE = 5
    private const val EPSILON = 1e-8

    fun validate(config: ResolvedExperimentConfig) {
        require(config.initialDistribution.isValid()) { "Invalid initial distribution" }
        require(config.rerollDistribution.isValid()) { "Invalid reroll distribution" }
        require(config.ads.isValid()) { "Invalid ads config" }

        when (config.sampling.mode) {
            SamplingMode.INDEPENDENT -> Unit
            SamplingMode.GAUSSIAN_COPULA -> validateCorrelationMatrix(
                requireNotNull(config.sampling.correlationMatrix) {
                    "Correlated sampling requires a matrix"
                },
            )
        }

        if (config.overallRule.type == OverallRuleType.PIECEWISE) {
            validatePiecewise(config.overallRule.segments)
        }
        validateDynamicProbability(config.dynamicProbability)
    }

    private fun validateDynamicProbability(config: DynamicProbabilityConfig) {
        require(config.policyId.isNotBlank()) { "Dynamic probability policy id must not be blank" }
        val schedule = config.rerollSchedule.sortedBy { it.minRerollIndexInclusive }
        var previousEnd = 0
        schedule.forEachIndexed { index, band ->
            require(band.minRerollIndexInclusive >= 1) { "Reroll schedule starts at index 1 or later" }
            require(band.distribution.isValid()) { "Invalid reroll schedule distribution" }
            val end = band.maxRerollIndexInclusive
            if (end != null) {
                require(end >= band.minRerollIndexInclusive) { "Reroll schedule band has negative width" }
            }
            if (index > 0) {
                require(band.minRerollIndexInclusive > previousEnd) { "Reroll schedule bands must not overlap" }
            }
            previousEnd = end ?: Int.MAX_VALUE
            if (previousEnd == Int.MAX_VALUE) {
                require(index == schedule.lastIndex) { "Open-ended reroll schedule band must be final" }
            }
        }

        config.pity?.let { pity ->
            require(pity.afterConsecutiveMisses >= 0) { "Pity miss threshold must be non-negative" }
            require(pity.successScore in 1..7) { "Pity success score must be in [1, 7]" }
        }
    }

    private fun validateCorrelationMatrix(matrix: List<List<Double>>) {
        require(matrix.size == MATRIX_SIZE && matrix.all { it.size == MATRIX_SIZE }) {
            "Correlation matrix must be 5x5"
        }
        for (row in 0 until MATRIX_SIZE) {
            for (column in 0 until MATRIX_SIZE) {
                val value = matrix[row][column]
                require(value.isFinite() && value in -1.0..1.0) {
                    "Correlation values must be finite and in [-1, 1]"
                }
                require(abs(value - matrix[column][row]) <= EPSILON) {
                    "Correlation matrix must be symmetric"
                }
            }
            require(abs(matrix[row][row] - 1.0) <= EPSILON) {
                "Correlation matrix diagonal must equal 1"
            }
        }

        val lower = Array(MATRIX_SIZE) { DoubleArray(MATRIX_SIZE) }
        for (row in 0 until MATRIX_SIZE) {
            for (column in 0..row) {
                var residual = matrix[row][column]
                for (index in 0 until column) {
                    residual -= lower[row][index] * lower[column][index]
                }
                if (row == column) {
                    require(residual > EPSILON) { "Correlation matrix must be positive definite" }
                    lower[row][column] = sqrt(residual)
                } else {
                    lower[row][column] = residual / lower[column][column]
                }
            }
        }
    }

    private fun validatePiecewise(rawSegments: List<OverallRuleSegment>) {
        val segments = rawSegments.sortedBy { it.minInclusive }
        require(segments.isNotEmpty()) { "PIECEWISE requires segments" }
        require(abs(segments.first().minInclusive - 1.0) <= EPSILON) {
            "PIECEWISE must start at 1"
        }

        var expectedStart = 1.0
        segments.forEachIndexed { index, segment ->
            require(segment.minInclusive.isFinite() && segment.minInclusive in 1.0..7.0) {
                "PIECEWISE segment start must be in [1, 7]"
            }
            require(abs(segment.minInclusive - expectedStart) <= EPSILON) {
                "PIECEWISE segments must be contiguous and non-overlapping"
            }

            val end = segment.maxExclusive
            if (index == segments.lastIndex) {
                require(end == null || abs(end - 7.0) <= EPSILON) {
                    "Final PIECEWISE segment must cover 7"
                }
                require(segment.minInclusive < 7.0 || segments.size == 1) {
                    "Final PIECEWISE segment must have positive width"
                }
            } else {
                val requiredEnd = requireNotNull(end) { "Non-final segment needs max_exclusive" }
                require(requiredEnd.isFinite() && requiredEnd > segment.minInclusive && requiredEnd <= 7.0) {
                    "PIECEWISE segment must have positive width within [1, 7]"
                }
                expectedStart = requiredEnd
            }
        }
    }
}
