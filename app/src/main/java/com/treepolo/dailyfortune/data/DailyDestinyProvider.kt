package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.DestinySnapshot
import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.ResolvedDestiny
import com.treepolo.dailyfortune.model.ZodiacSign
import java.time.LocalDate
import kotlin.random.Random

/**
 * 開發期的公共天命提供者。
 *
 * 在 Supabase 接入前，用「日期 + 星座」固定種子產生公共結果，因此同一天、同版本的客戶端
 * 會得到相同的十二星座天命。正式版會改成由中央伺服器每天產生並鎖定十二筆資料；介面與
 * DestinySnapshot 模型維持不變。
 */
object DailyDestinyProvider {
    fun publicDestinies(date: LocalDate): Map<ZodiacSign, ResolvedDestiny> =
        ZodiacSign.entries.associateWith { zodiac -> publicDestiny(date, zodiac) }

    fun publicDestiny(date: LocalDate, zodiac: ZodiacSign): ResolvedDestiny {
        val seed = (date.toEpochDay() * 1009L + zodiac.ordinal * 7919L).hashCode()
        return resolve(generateSnapshot(Random(seed)))
    }

    fun personalReroll(random: Random = Random.Default): ResolvedDestiny =
        resolve(generateSnapshot(random))

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
        return ResolvedDestiny(snapshot, overall, domains)
    }

    private fun generateSnapshot(random: Random): DestinySnapshot {
        val overall = FortuneCatalog.random(random)
        val domainNumbers = FortuneDomain.entries.associateWith {
            // 多數細項承接整體籤的氣勢，但保留足夠機率從其他籤取得該領域結果。
            // 因此整體大吉仍可能出現凶細項，整體大凶也可能存在吉細項。
            if (random.nextInt(100) < 70) {
                overall.number
            } else {
                FortuneCatalog.random(random).number
            }
        }
        return DestinySnapshot(
            overallFortuneNumber = overall.number,
            domainFortuneNumbers = domainNumbers,
        )
    }
}
