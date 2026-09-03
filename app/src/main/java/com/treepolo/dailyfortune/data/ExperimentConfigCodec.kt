package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.ExperimentAssignment
import com.treepolo.dailyfortune.model.GradeDistribution
import com.treepolo.dailyfortune.model.OverallRule
import com.treepolo.dailyfortune.model.OverallRuleSegment
import com.treepolo.dailyfortune.model.OverallRuleType
import com.treepolo.dailyfortune.model.ResolvedExperimentConfig
import com.treepolo.dailyfortune.model.RoundingMethod
import com.treepolo.dailyfortune.model.SamplingConfig
import com.treepolo.dailyfortune.model.SamplingMode
import com.treepolo.dailyfortune.model.VisualExperimentConfig
import org.json.JSONArray
import org.json.JSONObject

object ExperimentConfigCodec {
    fun decode(json: String): ResolvedExperimentConfig {
        val root = JSONObject(json)
        val fortune = root.getJSONObject("fortune")
        val samplingObject = fortune.getJSONObject("sampling")
        val overallObject = fortune.getJSONObject("overall_rule")
        val visualObject = root.optJSONObject("visual")

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
            visual = VisualExperimentConfig(
                staticVariantId = visualObject?.optString("static_variant_id", "baseline") ?: "baseline",
                revealVariantId = visualObject?.optString("reveal_variant_id", "none") ?: "none",
            ),
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

    private fun parseDistribution(value: JSONObject): GradeDistribution = GradeDistribution(
        id = value.getString("id"),
        probabilities = value.getJSONArray("probabilities").toDoubleList(),
    )

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
    fun validate(config: ResolvedExperimentConfig) {
        require(config.initialDistribution.isValid()) { "Invalid initial distribution" }
        require(config.rerollDistribution.isValid()) { "Invalid reroll distribution" }

        when (config.sampling.mode) {
            SamplingMode.INDEPENDENT -> Unit
            SamplingMode.GAUSSIAN_COPULA -> {
                val matrix = requireNotNull(config.sampling.correlationMatrix) {
                    "Correlated sampling requires a matrix"
                }
                require(matrix.size == 5 && matrix.all { it.size == 5 }) { "Correlation matrix must be 5x5" }
            }
        }

        if (config.overallRule.type == OverallRuleType.PIECEWISE) {
            val segments = config.overallRule.segments.sortedBy { it.minInclusive }
            require(segments.isNotEmpty()) { "PIECEWISE requires segments" }
            require(kotlin.math.abs(segments.first().minInclusive - 1.0) <= 1e-9) {
                "PIECEWISE must start at 1"
            }
            var expectedStart = 1.0
            segments.forEachIndexed { index, segment ->
                require(kotlin.math.abs(segment.minInclusive - expectedStart) <= 1e-9) {
                    "PIECEWISE segments must be contiguous"
                }
                if (index == segments.lastIndex) {
                    require(segment.maxExclusive == null || kotlin.math.abs(segment.maxExclusive - 7.0) <= 1e-9) {
                        "Final PIECEWISE segment must cover 7"
                    }
                } else {
                    val end = requireNotNull(segment.maxExclusive) { "Non-final segment needs max_exclusive" }
                    require(end > segment.minInclusive) { "PIECEWISE segment must have positive width" }
                    expectedStart = end
                }
            }
        }
    }
}
