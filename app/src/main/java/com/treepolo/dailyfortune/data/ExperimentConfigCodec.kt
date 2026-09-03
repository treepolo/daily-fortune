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

    fun assignmentsFromJson(json: String): List<ExperimentAssignment> =
        parseAssignments(JSONArray(json))

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
    private const val MATRIX_SIZE = 5
    private const val EPSILON = 1e-8

    fun validate(config: ResolvedExperimentConfig) {
        require(config.initialDistribution.isValid()) { "Invalid initial distribution" }
        require(config.rerollDistribution.isValid()) { "Invalid reroll distribution" }

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

        // Cholesky factorization proves the matrix is positive definite enough for sampling.
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
