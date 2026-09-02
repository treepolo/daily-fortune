package com.treepolo.dailyfortune.data.local

import com.treepolo.dailyfortune.data.AstronomyEphemeris
import com.treepolo.dailyfortune.model.AstroBody
import com.treepolo.dailyfortune.model.AstroSample
import com.treepolo.dailyfortune.model.AstrologyAudit
import com.treepolo.dailyfortune.model.AstrologyFactor
import com.treepolo.dailyfortune.model.DestinySource
import com.treepolo.dailyfortune.model.DomainFortune
import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.FortuneGrade
import com.treepolo.dailyfortune.model.ParallelSkyInfo
import com.treepolo.dailyfortune.model.ResolvedDestiny
import com.treepolo.dailyfortune.model.ZodiacSign
import java.time.Instant
import java.time.LocalDate

data class LocalDestinyBundle(
    val destiny: LocalDestinyEntity,
    val factors: List<LocalAstrologyFactorEntity>,
    val samples: List<LocalAstronomySampleEntity>,
)

object LocalDestinyMapper {
    fun toBundle(
        id: String,
        fortuneDate: LocalDate,
        destiny: ResolvedDestiny,
        authority: LocalAuthority,
        createdAtEpochMillis: Long,
    ): LocalDestinyBundle {
        val audit = destiny.astrologyAudit
        val domainScores = audit.domainScores
        val row = LocalDestinyEntity(
            id = id,
            fortuneDate = fortuneDate.toString(),
            zodiacSign = audit.zodiac.name,
            sourceType = destiny.source.name,
            sourceDate = audit.astronomy.date.toString(),
            engineVersion = audit.engineVersion,
            ephemerisVersion = AstronomyEphemeris.version,
            authority = authority.name,
            overallGrade = destiny.overallGrade.name,
            overallScore = audit.overallScore,
            overallExplanation = destiny.overallExplanation,
            wealthGrade = destiny.domains.getValue(FortuneDomain.WEALTH).grade.name,
            wealthScore = domainScores.getValue(FortuneDomain.WEALTH),
            wealthExplanation = destiny.domains.getValue(FortuneDomain.WEALTH).explanation,
            loveGrade = destiny.domains.getValue(FortuneDomain.LOVE).grade.name,
            loveScore = domainScores.getValue(FortuneDomain.LOVE),
            loveExplanation = destiny.domains.getValue(FortuneDomain.LOVE).explanation,
            workStudyGrade = destiny.domains.getValue(FortuneDomain.WORK_STUDY).grade.name,
            workStudyScore = domainScores.getValue(FortuneDomain.WORK_STUDY),
            workStudyExplanation = destiny.domains.getValue(FortuneDomain.WORK_STUDY).explanation,
            relationshipsGrade = destiny.domains.getValue(FortuneDomain.RELATIONSHIPS).grade.name,
            relationshipsScore = domainScores.getValue(FortuneDomain.RELATIONSHIPS),
            relationshipsExplanation = destiny.domains.getValue(FortuneDomain.RELATIONSHIPS).explanation,
            healthGrade = destiny.domains.getValue(FortuneDomain.HEALTH).grade.name,
            healthScore = domainScores.getValue(FortuneDomain.HEALTH),
            healthExplanation = destiny.domains.getValue(FortuneDomain.HEALTH).explanation,
            originalSunLongitude = destiny.parallelSky?.originalSunLongitude,
            alteredSunLongitude = destiny.parallelSky?.alteredSunLongitude,
            sunLongitudeDifference = destiny.parallelSky?.sunLongitudeDifference,
            createdAtEpochMillis = createdAtEpochMillis,
        )
        val factors = audit.factors.map { factor ->
            LocalAstrologyFactorEntity(
                destinyId = id,
                factorId = factor.id,
                title = factor.title,
                evidence = factor.evidence,
                wealth = factor.contributions[FortuneDomain.WEALTH] ?: 0.0,
                love = factor.contributions[FortuneDomain.LOVE] ?: 0.0,
                workStudy = factor.contributions[FortuneDomain.WORK_STUDY] ?: 0.0,
                relationships = factor.contributions[FortuneDomain.RELATIONSHIPS] ?: 0.0,
                health = factor.contributions[FortuneDomain.HEALTH] ?: 0.0,
            )
        }
        val samples = audit.astronomy.samples.mapIndexed { index, sample ->
            LocalAstronomySampleEntity(
                sourceDate = audit.astronomy.date.toString(),
                ephemerisVersion = AstronomyEphemeris.version,
                sampleIndex = index,
                instantEpochMillis = sample.instant.toEpochMilli(),
                sun = sample.longitudes.getValue(AstroBody.SUN),
                moon = sample.longitudes.getValue(AstroBody.MOON),
                mercury = sample.longitudes.getValue(AstroBody.MERCURY),
                venus = sample.longitudes.getValue(AstroBody.VENUS),
                mars = sample.longitudes.getValue(AstroBody.MARS),
                jupiter = sample.longitudes.getValue(AstroBody.JUPITER),
                saturn = sample.longitudes.getValue(AstroBody.SATURN),
                uranus = sample.longitudes.getValue(AstroBody.URANUS),
                neptune = sample.longitudes.getValue(AstroBody.NEPTUNE),
                pluto = sample.longitudes.getValue(AstroBody.PLUTO),
            )
        }
        return LocalDestinyBundle(row, factors, samples)
    }

    fun fromRows(
        row: LocalDestinyEntity,
        factorRows: List<LocalAstrologyFactorEntity>,
        sampleRows: List<LocalAstronomySampleEntity>,
    ): ResolvedDestiny {
        require(sampleRows.isNotEmpty()) { "Missing astronomy snapshot for ${row.id}" }
        val samples = sampleRows.sortedBy { it.sampleIndex }.map { sample ->
            AstroSample(
                instant = Instant.ofEpochMilli(sample.instantEpochMillis),
                longitudes = mapOf(
                    AstroBody.SUN to sample.sun,
                    AstroBody.MOON to sample.moon,
                    AstroBody.MERCURY to sample.mercury,
                    AstroBody.VENUS to sample.venus,
                    AstroBody.MARS to sample.mars,
                    AstroBody.JUPITER to sample.jupiter,
                    AstroBody.SATURN to sample.saturn,
                    AstroBody.URANUS to sample.uranus,
                    AstroBody.NEPTUNE to sample.neptune,
                    AstroBody.PLUTO to sample.pluto,
                ),
            )
        }
        val astronomy = AstronomyEphemeris.rebuild(LocalDate.parse(row.sourceDate), samples)
        val factors = factorRows.map { factor ->
            AstrologyFactor(
                id = factor.factorId,
                title = factor.title,
                evidence = factor.evidence,
                contributions = mapOf(
                    FortuneDomain.WEALTH to factor.wealth,
                    FortuneDomain.LOVE to factor.love,
                    FortuneDomain.WORK_STUDY to factor.workStudy,
                    FortuneDomain.RELATIONSHIPS to factor.relationships,
                    FortuneDomain.HEALTH to factor.health,
                ),
            )
        }
        val scores = mapOf(
            FortuneDomain.WEALTH to row.wealthScore,
            FortuneDomain.LOVE to row.loveScore,
            FortuneDomain.WORK_STUDY to row.workStudyScore,
            FortuneDomain.RELATIONSHIPS to row.relationshipsScore,
            FortuneDomain.HEALTH to row.healthScore,
        )
        val domains = mapOf(
            FortuneDomain.WEALTH to DomainFortune(FortuneGrade.valueOf(row.wealthGrade), row.wealthExplanation),
            FortuneDomain.LOVE to DomainFortune(FortuneGrade.valueOf(row.loveGrade), row.loveExplanation),
            FortuneDomain.WORK_STUDY to DomainFortune(FortuneGrade.valueOf(row.workStudyGrade), row.workStudyExplanation),
            FortuneDomain.RELATIONSHIPS to DomainFortune(FortuneGrade.valueOf(row.relationshipsGrade), row.relationshipsExplanation),
            FortuneDomain.HEALTH to DomainFortune(FortuneGrade.valueOf(row.healthGrade), row.healthExplanation),
        )
        val source = DestinySource.valueOf(row.sourceType)
        val parallel = if (source == DestinySource.PERSONAL_ASTROLOGY) {
            ParallelSkyInfo(
                originalDate = LocalDate.parse(row.fortuneDate),
                sourceDate = LocalDate.parse(row.sourceDate),
                originalSunLongitude = requireNotNull(row.originalSunLongitude),
                alteredSunLongitude = requireNotNull(row.alteredSunLongitude),
                sunLongitudeDifference = requireNotNull(row.sunLongitudeDifference),
                engineVersion = row.engineVersion,
            )
        } else {
            null
        }
        return ResolvedDestiny(
            source = source,
            overallGrade = FortuneGrade.valueOf(row.overallGrade),
            overallExplanation = row.overallExplanation,
            domains = domains,
            astrologyAudit = AstrologyAudit(
                engineVersion = row.engineVersion,
                date = astronomy.date,
                zodiac = ZodiacSign.valueOf(row.zodiacSign),
                astronomy = astronomy,
                factors = factors,
                domainScores = scores,
                overallScore = row.overallScore,
            ),
            parallelSky = parallel,
        )
    }
}
