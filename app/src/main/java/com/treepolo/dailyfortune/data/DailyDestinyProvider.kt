package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.DestinyChange
import com.treepolo.dailyfortune.model.DestinySource
import com.treepolo.dailyfortune.model.ResolvedDestiny
import com.treepolo.dailyfortune.model.ZodiacSign
import java.time.LocalDate

/**
 * Public fate uses the real sky for [date]. Private fate uses a randomly selected, physically valid
 * parallel sky and then runs the exact same Astrology Engine rules.
 */
object DailyDestinyProvider {
    @Volatile private var cachedDate: LocalDate? = null
    @Volatile private var cachedPublic: Map<ZodiacSign, ResolvedDestiny>? = null
    @Volatile private var cachedPersonalKey: Triple<LocalDate, ZodiacSign, LocalDate>? = null
    @Volatile private var cachedPersonal: ResolvedDestiny? = null

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

    fun personalReroll(date: LocalDate, zodiac: ZodiacSign): ResolvedDestiny =
        ParallelSkyGenerator.reroll(date, zodiac)

    fun personalDestiny(
        date: LocalDate,
        zodiac: ZodiacSign,
        sourceDate: LocalDate,
    ): ResolvedDestiny {
        val key = Triple(date, zodiac, sourceDate)
        if (cachedPersonalKey == key) cachedPersonal?.let { return it }
        return synchronized(this) {
            if (cachedPersonalKey == key) {
                cachedPersonal ?: ParallelSkyGenerator.resolve(date, zodiac, sourceDate)
            } else {
                ParallelSkyGenerator.resolve(date, zodiac, sourceDate)
            }.also {
                cachedPersonalKey = key
                cachedPersonal = it
            }
        }
    }

    fun compare(original: ResolvedDestiny, altered: ResolvedDestiny): DestinyChange =
        AstrologyComparison.compare(original, altered)

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
}
