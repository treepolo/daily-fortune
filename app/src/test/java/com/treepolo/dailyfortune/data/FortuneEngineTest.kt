package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.DrawType
import com.treepolo.dailyfortune.model.FortuneGrade
import com.treepolo.dailyfortune.model.GradeDistribution
import com.treepolo.dailyfortune.model.OverallRule
import com.treepolo.dailyfortune.model.OverallRuleSegment
import com.treepolo.dailyfortune.model.OverallRuleType
import com.treepolo.dailyfortune.model.ResolvedExperimentConfig
import com.treepolo.dailyfortune.model.RoundingMethod
import com.treepolo.dailyfortune.model.SamplingConfig
import com.treepolo.dailyfortune.model.SamplingMode
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FortuneEngineTest {
    private val engine = FortuneEngine(Random(12345))

    @Test
    fun gradeScoresRunFromDaiXiongOneToDaiJiSeven() {
        assertEquals(FortuneGrade.DAI_XIONG, FortuneGrade.fromScore(1))
        assertEquals(FortuneGrade.PING, FortuneGrade.fromScore(4))
        assertEquals(FortuneGrade.DAI_JI, FortuneGrade.fromScore(7))
    }

    @Test
    fun defaultOverallRuleUsesFloor() {
        assertEquals(4, engine.resolveOverallScore(4.8, OverallRule("floor", OverallRuleType.FLOOR)))
        assertEquals(7, engine.resolveOverallScore(7.0, OverallRule("floor", OverallRuleType.FLOOR)))
    }

    @Test
    fun ceilAndRoundAreSelectable() {
        assertEquals(5, engine.resolveOverallScore(4.2, OverallRule("ceil", OverallRuleType.CEIL)))
        assertEquals(4, engine.resolveOverallScore(4.49, OverallRule("round", OverallRuleType.ROUND)))
        assertEquals(5, engine.resolveOverallScore(4.5, OverallRule("round", OverallRuleType.ROUND)))
    }

    @Test
    fun piecewiseRuleCanUseDifferentRoundingByRange() {
        val rule = OverallRule(
            id = "piecewise",
            type = OverallRuleType.PIECEWISE,
            segments = listOf(
                OverallRuleSegment(1.0, 2.0, RoundingMethod.CEIL),
                OverallRuleSegment(2.0, 6.0, RoundingMethod.ROUND),
                OverallRuleSegment(6.0, 7.0, RoundingMethod.FLOOR),
            ),
        )

        assertEquals(2, engine.resolveOverallScore(1.8, rule))
        assertEquals(3, engine.resolveOverallScore(3.4, rule))
        assertEquals(6, engine.resolveOverallScore(5.6, rule))
        assertEquals(6, engine.resolveOverallScore(6.8, rule))
        assertEquals(7, engine.resolveOverallScore(7.0, rule))
    }

    @Test
    fun embeddedDefaultProducesFiveLegalScores() {
        repeat(100) {
            val draw = engine.draw(DrawType.INITIAL, ResolvedExperimentConfig.embeddedDefault())
            assertEquals(5, draw.domainScores.size)
            assertTrue(draw.domainScores.values.all { it in 1..7 })
            assertTrue(draw.overallGrade.score in 1..7)
        }
    }

    @Test
    fun gaussianCopulaModeProducesLegalScores() {
        val uniform = List(7) { 1.0 / 7.0 }
        val matrix = listOf(
            listOf(1.0, 0.4, 0.2, 0.1, 0.0),
            listOf(0.4, 1.0, 0.2, 0.3, 0.0),
            listOf(0.2, 0.2, 1.0, 0.3, 0.1),
            listOf(0.1, 0.3, 0.3, 1.0, 0.1),
            listOf(0.0, 0.0, 0.1, 0.1, 1.0),
        )
        val config = ResolvedExperimentConfig.embeddedDefault().copy(
            initialDistribution = GradeDistribution("uniform", uniform),
            sampling = SamplingConfig(
                mode = SamplingMode.GAUSSIAN_COPULA,
                profileId = "correlated-test",
                correlationMatrix = matrix,
            ),
        )

        repeat(100) {
            val draw = engine.draw(DrawType.INITIAL, config)
            assertTrue(draw.domainScores.values.all { it in 1..7 })
        }
    }
}
