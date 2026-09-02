package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.DestinySnapshot
import com.treepolo.dailyfortune.model.DestinySource
import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.ResolvedDestiny
import com.treepolo.dailyfortune.model.ZodiacSign
import java.security.SecureRandom
import java.time.LocalDate

/**
 * Public fate is calculated from actual astronomy + Astrology Engine v1 with no random input.
 * Personal "逆天改命" is a separate true-random ancient-fortune draw.
 */
object DailyDestinyProvider {
    private val secureRandom = SecureRandom()
    @Volatile private var cachedDate: LocalDate? = null
    @Volatile private var cachedPublic: Map<ZodiacSign, ResolvedDestiny>? = null

    fun publicDestinies(date: LocalDate): Map<ZodiacSign, ResolvedDestiny> {
        if (cachedDate == date) cachedPublic?.let { return it }
        return synchronized(this) {
            if (cachedDate == date) {
                cachedPublic ?: calculatePublic(date)
            } else {
                calculatePublic(date)
            }.also {
                cachedDate = date
                cachedPublic = it
            }
        }
    }

    fun publicDestiny(date: LocalDate, zodiac: ZodiacSign): ResolvedDestiny =
        publicDestinies(date).getValue(zodiac)

    private fun calculatePublic(date: LocalDate): Map<ZodiacSign, ResolvedDestiny> =
        AstrologyEngine.calculateDay(date).mapValues { (_, destiny) ->
            ResolvedDestiny(
                source = DestinySource.PUBLIC_ASTROLOGY,
                overallGrade = destiny.overallGrade,
                overallExplanation = destiny.overallExplanation,
                domains = destiny.domains,
                astrologyAudit = destiny.audit,
            )
        }

    fun personalReroll(): ResolvedDestiny = resolve(generatePersonalSnapshot())

    fun resolve(snapshot: DestinySnapshot): ResolvedDestiny {
        val overall = requireNotNull(FortuneCatalog.byNumber(snapshot.overallFortuneNumber)) {
            "Unknown overall fortune ${snapshot.overallFortuneNumber}"
        }
        val domains = FortuneDomain.entries.associateWith { domain ->
            val sourceNumber = requireNotNull(snapshot.domainFortuneNumbers[domain]) {
                "Missing source fortune for ${domain.name}"
            }
            val source = requireNotNull(FortuneCatalog.byNumber(sourceNumber)) {
                "Unknown domain fortune $sourceNumber"
            }
            source.domains.getValue(domain)
        }
        return ResolvedDestiny(
            source = DestinySource.PERSONAL_FORTUNE,
            overallGrade = overall.grade,
            overallExplanation = overall.generalExplanation,
            domains = domains,
            snapshot = snapshot,
            fortune = overall,
        )
    }

    private fun generatePersonalSnapshot(): DestinySnapshot {
        val overall = secureFortuneNumber()
        val domainNumbers = FortuneDomain.entries.associateWith {
            if (secureRandom.nextInt(100) < 70) overall else secureFortuneNumber()
        }
        return DestinySnapshot(overall, domainNumbers)
    }

    private fun secureFortuneNumber(): Int =
        FortuneCatalog.fortunes[secureRandom.nextInt(FortuneCatalog.fortunes.size)].number
}
