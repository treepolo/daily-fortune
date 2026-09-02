package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.ChangeOutcome
import com.treepolo.dailyfortune.model.DestinyChange
import com.treepolo.dailyfortune.model.DomainChange
import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.ResolvedDestiny
import kotlin.math.abs

object AstrologyComparison {
    fun compare(original: ResolvedDestiny, altered: ResolvedDestiny): DestinyChange {
        val before = original.astrologyAudit
        val after = altered.astrologyAudit
        val domainChanges = FortuneDomain.entries.associateWith { domain ->
            val beforeScore = before.domainScores.getValue(domain)
            val afterScore = after.domainScores.getValue(domain)
            DomainChange(
                domain = domain,
                beforeGrade = original.domains.getValue(domain).grade,
                afterGrade = altered.domains.getValue(domain).grade,
                scoreDelta = afterScore - beforeScore,
            )
        }
        val overallDelta = after.overallScore - before.overallScore
        val outcome = when {
            overallDelta > 0.25 -> ChangeOutcome.IMPROVED
            overallDelta < -0.25 -> ChangeOutcome.WORSENED
            else -> ChangeOutcome.SIMILAR
        }

        val beforeById = before.factors.associateBy { it.id }
        val afterById = after.factors.associateBy { it.id }
        val removed = before.factors
            .filter { it.id !in afterById }
            .sortedByDescending(::factorMagnitude)
            .take(3)
            .map { it.title }
        val added = after.factors
            .filter { it.id !in beforeById }
            .sortedByDescending(::factorMagnitude)
            .take(3)
            .map { it.title }

        return DestinyChange(
            outcome = outcome,
            beforeOverallGrade = original.overallGrade,
            afterOverallGrade = altered.overallGrade,
            overallScoreDelta = overallDelta,
            domainChanges = domainChanges,
            removedFactors = removed,
            addedFactors = added,
            narrative = narrative(outcome, original, altered, removed, added),
        )
    }

    private fun factorMagnitude(factor: com.treepolo.dailyfortune.model.AstrologyFactor): Double =
        factor.contributions.values.sumOf(::abs)

    private fun narrative(
        outcome: ChangeOutcome,
        original: ResolvedDestiny,
        altered: ResolvedDestiny,
        removed: List<String>,
        added: List<String>,
    ): String = buildString {
        append(
            when (outcome) {
                ChangeOutcome.IMPROVED -> "你改動天象後，綜合運勢變好了。"
                ChangeOutcome.WORSENED -> "很遺憾，你改動天象後，綜合運勢反而變差了。"
                ChangeOutcome.SIMILAR -> "你確實改動了天象，但綜合運勢目前差異不大。"
            },
        )
        append("原本是${original.overallGrade.label}，改命後是${altered.overallGrade.label}。")
        removed.firstOrNull()?.let { append("原本的重要因素「$it」已離開主要影響。") }
        added.firstOrNull()?.let { append("新的重要因素是「$it」。") }
    }
}
