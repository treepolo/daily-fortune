package com.treepolo.dailyfortune.data

import android.content.Context
import com.treepolo.dailyfortune.data.local.DailyFortuneDatabase
import com.treepolo.dailyfortune.data.local.LocalDailyFortuneStateEntity
import com.treepolo.dailyfortune.data.local.LocalFortuneDao
import com.treepolo.dailyfortune.data.local.LocalFortuneDrawEntity
import com.treepolo.dailyfortune.model.DrawType
import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.FortuneDraw
import com.treepolo.dailyfortune.model.FortuneGrade
import com.treepolo.dailyfortune.model.PityConfig
import com.treepolo.dailyfortune.model.PityScope
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class FortuneRepositorySnapshot(
    val currentDraw: FortuneDraw?,
)

class LocalFortuneRepository(
    private val dao: LocalFortuneDao,
    private val research: ResearchManager,
    private val engine: FortuneEngine = FortuneEngine(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val writeMutex = Mutex()

    suspend fun startSession() = research.startSession()

    suspend fun endSession() = research.endSession()

    suspend fun refreshExperimentConfig() = research.refreshRemoteConfig()

    suspend fun flushResearchEvents() = research.flushPendingEvents()

    suspend fun snapshot(date: LocalDate): FortuneRepositorySnapshot {
        val state = dao.getDailyState(date.toString()) ?: return FortuneRepositorySnapshot(null)
        val draw = dao.getDraw(state.currentDrawId)?.toModel()
        return FortuneRepositorySnapshot(draw)
    }

    suspend fun initialDraw(date: LocalDate): FortuneDraw = writeMutex.withLock {
        val existing = dao.getDailyState(date.toString())
        if (existing != null) {
            return@withLock requireNotNull(dao.getDraw(existing.currentDrawId)).toModel()
        }
        persistDraw(date, DrawType.INITIAL, null)
    }

    suspend fun reroll(date: LocalDate): FortuneDraw = writeMutex.withLock {
        val existing = dao.getDailyState(date.toString())
            ?: throw IllegalStateException("今天尚未抽籤")
        persistDraw(date, DrawType.REROLL, existing)
    }

    suspend fun drawHistory(date: LocalDate): List<FortuneDraw> =
        dao.getDrawHistory(date.toString()).map { it.toModel() }

    private suspend fun persistDraw(
        date: LocalDate,
        type: DrawType,
        existingState: LocalDailyFortuneStateEntity?,
    ): FortuneDraw {
        val config = research.currentConfig()
        val drawIndex = (existingState?.drawCount ?: 0) + 1
        val rerollIndex = if (type == DrawType.REROLL) existingState?.drawCount ?: 0 else 0
        val pityMisses = if (type == DrawType.REROLL) {
            config.dynamicProbability.pity
                ?.takeIf { it.enabled }
                ?.let { consecutivePityMisses(date, it) }
                ?: 0
        } else {
            0
        }
        val generated = engine.draw(
            type = type,
            config = config,
            rerollIndex = rerollIndex,
            consecutivePityMisses = pityMisses,
        )
        val now = nowMillis()
        val id = UUID.randomUUID().toString()
        val assignmentsJson = ExperimentConfigCodec.assignmentsToJson(config.assignments)
        val draw = LocalFortuneDrawEntity(
            id = id,
            fortuneDate = date.toString(),
            drawIndex = drawIndex,
            drawType = type.name,
            wealthScore = generated.domainScores.getValue(FortuneDomain.WEALTH),
            loveScore = generated.domainScores.getValue(FortuneDomain.LOVE),
            workStudyScore = generated.domainScores.getValue(FortuneDomain.WORK_STUDY),
            relationshipsScore = generated.domainScores.getValue(FortuneDomain.RELATIONSHIPS),
            healthScore = generated.domainScores.getValue(FortuneDomain.HEALTH),
            rawAverage = generated.rawAverage,
            overallScore = generated.overallGrade.score,
            configId = config.configId,
            assignmentsJson = assignmentsJson,
            distributionId = generated.distributionId,
            samplingProfileId = generated.samplingProfileId,
            overallRuleId = generated.overallRuleId,
            createdAtEpochMillis = now,
        )
        val state = LocalDailyFortuneStateEntity(
            fortuneDate = date.toString(),
            currentDrawId = id,
            drawCount = drawIndex,
            firstDrawAtEpochMillis = existingState?.firstDrawAtEpochMillis ?: now,
            updatedAtEpochMillis = now,
        )
        val payload = JSONObject()
            .put("fortune_date", date.toString())
            .put("draw_index", drawIndex)
            .put("reroll_index", rerollIndex)
            .put("draw_type", type.name)
            .put("wealth", draw.wealthScore)
            .put("love", draw.loveScore)
            .put("work_study", draw.workStudyScore)
            .put("relationships", draw.relationshipsScore)
            .put("health", draw.healthScore)
            .put("raw_average", draw.rawAverage)
            .put("overall", draw.overallScore)
            .put("distribution_id", draw.distributionId)
            .put("sampling_profile_id", draw.samplingProfileId)
            .put("overall_rule_id", draw.overallRuleId)
            .put("probability_policy_id", generated.probabilityPolicyId)
            .put("pity_counter", generated.pityCounter)
            .put("guarantee_triggered", generated.guaranteeTriggered)
        val eventName = if (type == DrawType.INITIAL) "initial_draw" else "reroll"
        val analyticsEvent = research.eventEntity(eventName, payload, config)
        dao.persistDraw(draw, state, analyticsEvent)
        return draw.toModel()
    }

    private suspend fun consecutivePityMisses(date: LocalDate, pity: PityConfig): Int {
        var misses = 0
        for (draw in dao.getDrawHistory(date.toString()).asReversed()) {
            if (draw.drawType != DrawType.REROLL.name) break
            val succeeded = when (pity.scope) {
                PityScope.OVERALL_AT_LEAST -> draw.overallScore >= pity.successScore
                PityScope.ANY_DOMAIN_AT_LEAST -> listOf(
                    draw.wealthScore,
                    draw.loveScore,
                    draw.workStudyScore,
                    draw.relationshipsScore,
                    draw.healthScore,
                ).any { it >= pity.successScore }
            }
            if (succeeded) break
            misses += 1
        }
        return misses
    }

    private fun LocalFortuneDrawEntity.toModel(): FortuneDraw = FortuneDraw(
        id = id,
        fortuneDate = LocalDate.parse(fortuneDate),
        drawIndex = drawIndex,
        drawType = DrawType.valueOf(drawType),
        domainScores = mapOf(
            FortuneDomain.WEALTH to wealthScore,
            FortuneDomain.LOVE to loveScore,
            FortuneDomain.WORK_STUDY to workStudyScore,
            FortuneDomain.RELATIONSHIPS to relationshipsScore,
            FortuneDomain.HEALTH to healthScore,
        ),
        rawAverage = rawAverage,
        overallGrade = FortuneGrade.fromScore(overallScore),
        configId = configId,
        assignments = runCatching { ExperimentConfigCodec.assignmentsFromJson(assignmentsJson) }.getOrDefault(emptyList()),
        distributionId = distributionId,
        samplingProfileId = samplingProfileId,
        overallRuleId = overallRuleId,
        createdAtEpochMillis = createdAtEpochMillis,
    )

    companion object {
        fun create(context: Context): LocalFortuneRepository {
            val dao = DailyFortuneDatabase.get(context).fortuneDao()
            return LocalFortuneRepository(
                dao = dao,
                research = ResearchManager(context, dao),
            )
        }
    }
}
