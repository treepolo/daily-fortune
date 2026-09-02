package com.treepolo.dailyfortune.data

import android.content.Context
import com.treepolo.dailyfortune.BuildConfig
import com.treepolo.dailyfortune.data.local.DailyFortuneDatabase
import com.treepolo.dailyfortune.data.local.LocalAuthority
import com.treepolo.dailyfortune.data.local.LocalDailyFortuneEntity
import com.treepolo.dailyfortune.data.local.LocalDestinyEntity
import com.treepolo.dailyfortune.data.local.LocalDestinyMapper
import com.treepolo.dailyfortune.data.local.LocalFateSampleEventEntity
import com.treepolo.dailyfortune.data.local.LocalFortuneDao
import com.treepolo.dailyfortune.data.local.LocalRerollEventEntity
import com.treepolo.dailyfortune.data.local.LocalSampleKind
import com.treepolo.dailyfortune.data.local.LocalSyncState
import com.treepolo.dailyfortune.model.DestinyChange
import com.treepolo.dailyfortune.model.FortuneStats
import com.treepolo.dailyfortune.model.ResolvedDestiny
import com.treepolo.dailyfortune.model.ZodiacSign
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DestinyUnavailableException(message: String) : IllegalStateException(message)

interface DestinyAuthority {
    val publicAuthority: LocalAuthority
    val personalAuthority: LocalAuthority
    val localSyncState: LocalSyncState
    suspend fun publicDestinies(date: LocalDate): Map<ZodiacSign, ResolvedDestiny>
    suspend fun reroll(date: LocalDate, zodiac: ZodiacSign): ResolvedDestiny
}

/** Debug-only source. Release builds must use the future Supabase authority and never silently fall back. */
class EmbeddedDevelopmentDestinyAuthority : DestinyAuthority {
    override val publicAuthority = LocalAuthority.DEVELOPMENT_EMBEDDED
    override val personalAuthority = LocalAuthority.PERSONAL_LOCAL
    override val localSyncState = LocalSyncState.LOCAL_DEVELOPMENT

    override suspend fun publicDestinies(date: LocalDate) = withContext(Dispatchers.Default) {
        DailyDestinyProvider.publicDestinies(date)
    }

    override suspend fun reroll(date: LocalDate, zodiac: ZodiacSign) = withContext(Dispatchers.Default) {
        DailyDestinyProvider.personalReroll(date, zodiac)
    }
}

class UnavailableDestinyAuthority : DestinyAuthority {
    override val publicAuthority = LocalAuthority.CENTRAL
    override val personalAuthority = LocalAuthority.CENTRAL
    override val localSyncState = LocalSyncState.PENDING

    override suspend fun publicDestinies(date: LocalDate): Map<ZodiacSign, ResolvedDestiny> =
        throw DestinyUnavailableException("今日中央天命尚未快取，且目前無法連線取得。")

    override suspend fun reroll(date: LocalDate, zodiac: ZodiacSign): ResolvedDestiny =
        throw DestinyUnavailableException("逆天改命需要中央服務，目前無法取得。")
}

data class FortuneRepositorySnapshot(
    val selectedZodiac: ZodiacSign?,
    val publicDestinies: Map<ZodiacSign, ResolvedDestiny>,
    val currentDestiny: ResolvedDestiny?,
    val destinyChange: DestinyChange?,
    val todayRerollCount: Int,
    val stats: FortuneStats,
)

class LocalFortuneRepository(
    private val dao: LocalFortuneDao,
    private val settings: ZodiacSettings,
    private val authority: DestinyAuthority,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val writeMutex = Mutex()

    suspend fun prepare() = settings.cleanupLegacyState()

    suspend fun selectZodiac(date: LocalDate, zodiac: ZodiacSign) = writeMutex.withLock {
        settings.selectFirstZodiac(zodiac)
        ensurePublicCached(date)
        markTodaySeenLocked(date, settings.currentZodiac() ?: zodiac)
    }

    suspend fun markTodaySeen(date: LocalDate) = writeMutex.withLock {
        val zodiac = settings.currentZodiac() ?: return@withLock
        ensurePublicCached(date)
        markTodaySeenLocked(date, zodiac)
    }

    suspend fun reroll(date: LocalDate): DestinyChange = writeMutex.withLock {
        val zodiac = settings.currentZodiac()
            ?: throw IllegalStateException("Zodiac must be selected before reroll")
        ensurePublicCached(date)
        markTodaySeenLocked(date, zodiac)

        val day = requireNotNull(dao.getDailyFortune(date.toString()))
        val beforeId = day.currentPersonalDestinyId ?: publicId(date, zodiac)
        val before = loadDestinyById(beforeId)
            ?: throw IllegalStateException("Current destiny $beforeId is missing from Room")
        val after = authority.reroll(date, zodiac)
        val afterId = UUID.randomUUID().toString()
        persistDestiny(afterId, date, after, authority.personalAuthority)

        val drawIndex = dao.getRerollCount(date.toString()) + 1
        val now = nowMillis()
        val sourceDate = requireNotNull(after.parallelSky).sourceDate
        val eventId = UUID.randomUUID().toString()
        dao.insertRerollEvent(
            LocalRerollEventEntity(
                localId = eventId,
                serverId = null,
                fortuneDate = date.toString(),
                zodiacSign = zodiac.name,
                drawIndex = drawIndex,
                beforeDestinyId = beforeId,
                afterDestinyId = afterId,
                sourceDate = sourceDate.toString(),
                syncState = authority.localSyncState.name,
                createdAtEpochMillis = now,
            ),
        )
        dao.upsertDailyFortune(
            day.copy(
                currentPersonalDestinyId = afterId,
                rerollCount = drawIndex,
                updatedAtEpochMillis = now,
            ),
        )
        dao.insertFateSample(
            LocalFateSampleEventEntity(
                sampleId = "reroll:$eventId",
                fortuneDate = date.toString(),
                sampleKind = LocalSampleKind.PERSONAL_REROLL.name,
                destinyId = afterId,
                overallGrade = after.overallGrade.name,
                createdAtEpochMillis = now,
            ),
        )
        AstrologyComparison.compare(before, after)
    }

    suspend fun snapshot(date: LocalDate): FortuneRepositorySnapshot {
        val zodiac = settings.currentZodiac()
        if (zodiac == null) {
            return FortuneRepositorySnapshot(null, emptyMap(), null, null, 0, loadStats())
        }
        ensurePublicCached(date)
        val publicRows = dao.getPublicDestinies(date.toString())
        val public = publicRows.associate { row ->
            ZodiacSign.valueOf(row.zodiacSign) to requireNotNull(loadDestiny(row))
        }
        val day = dao.getDailyFortune(date.toString())
        val current = day?.currentPersonalDestinyId?.let { loadDestinyById(it) }
            ?: public[zodiac]
        val latest = dao.getLatestReroll(date.toString())
        val change = if (latest != null && latest.afterDestinyId == day?.currentPersonalDestinyId) {
            val before = loadDestinyById(latest.beforeDestinyId)
            val after = loadDestinyById(latest.afterDestinyId)
            if (before != null && after != null) AstrologyComparison.compare(before, after) else null
        } else {
            null
        }
        return FortuneRepositorySnapshot(
            selectedZodiac = zodiac,
            publicDestinies = public,
            currentDestiny = current,
            destinyChange = change,
            todayRerollCount = day?.rerollCount ?: 0,
            stats = loadStats(),
        )
    }

    suspend fun rerollHistory(date: LocalDate): List<LocalRerollEventEntity> =
        dao.getRerollHistory(date.toString())

    private suspend fun markTodaySeenLocked(date: LocalDate, zodiac: ZodiacSign) {
        val publicDestinyId = publicId(date, zodiac)
        val publicDestiny = loadDestinyById(publicDestinyId)
            ?: throw IllegalStateException("Public destiny $publicDestinyId is missing")
        val now = nowMillis()
        val existing = dao.getDailyFortune(date.toString())
        if (existing == null) {
            dao.upsertDailyFortune(
                LocalDailyFortuneEntity(
                    fortuneDate = date.toString(),
                    zodiacSign = zodiac.name,
                    publicDestinyId = publicDestinyId,
                    currentPersonalDestinyId = null,
                    rerollCount = 0,
                    firstSeenAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                ),
            )
        }
        dao.insertFateSample(
            LocalFateSampleEventEntity(
                sampleId = "public:${date}",
                fortuneDate = date.toString(),
                sampleKind = LocalSampleKind.PUBLIC_VIEW.name,
                destinyId = publicDestinyId,
                overallGrade = publicDestiny.overallGrade.name,
                createdAtEpochMillis = now,
            ),
        )
    }

    private suspend fun ensurePublicCached(date: LocalDate) {
        val cached = dao.getPublicDestinies(date.toString())
        if (cached.size == 12 && cached.map { it.zodiacSign }.toSet().size == 12) return

        val generated = authority.publicDestinies(date)
        require(generated.keys.toSet() == ZodiacSign.entries.toSet()) {
            "Authority must return exactly all 12 zodiac signs"
        }
        var samplesSaved = false
        generated.forEach { (zodiac, destiny) ->
            val bundle = LocalDestinyMapper.toBundle(
                id = publicId(date, zodiac),
                fortuneDate = date,
                destiny = destiny,
                authority = authority.publicAuthority,
                createdAtEpochMillis = nowMillis(),
            )
            if (!samplesSaved) {
                dao.upsertAstronomySamples(bundle.samples)
                samplesSaved = true
            }
            dao.replaceDestiny(bundle.destiny, bundle.factors)
        }
    }

    private suspend fun persistDestiny(
        id: String,
        fortuneDate: LocalDate,
        destiny: ResolvedDestiny,
        localAuthority: LocalAuthority,
    ) {
        val bundle = LocalDestinyMapper.toBundle(id, fortuneDate, destiny, localAuthority, nowMillis())
        dao.upsertAstronomySamples(bundle.samples)
        dao.replaceDestiny(bundle.destiny, bundle.factors)
    }

    private suspend fun loadDestinyById(id: String): ResolvedDestiny? =
        dao.getDestiny(id)?.let { loadDestiny(it) }

    private suspend fun loadDestiny(row: LocalDestinyEntity): ResolvedDestiny? {
        val factors = dao.getFactors(row.id)
        val samples = dao.getAstronomySamples(row.sourceDate, row.ephemerisVersion)
        if (samples.isEmpty()) return null
        return LocalDestinyMapper.fromRows(row, factors, samples)
    }

    private suspend fun loadStats(): FortuneStats {
        val aggregate = dao.getStatsAggregate()
        val rerollDays = dao.getRerollDayCounts()
        val totalRerolls = rerollDays.sumOf { it.rerollCount }.toInt()
        return FortuneStats(
            totalRerolls = totalRerolls,
            totalDrawDays = dao.getDrawDayCount().toInt(),
            maxDailyRerolls = rerollDays.maxOfOrNull { it.rerollCount }?.toInt() ?: 0,
            totalDraws = aggregate.totalDraws.toInt(),
            daiJiDraws = aggregate.daiJiDraws.toInt(),
            nonXiongDraws = aggregate.nonXiongDraws.toInt(),
            daiXiongDraws = aggregate.daiXiongDraws.toInt(),
        )
    }

    companion object {
        fun create(context: Context): LocalFortuneRepository {
            val authority: DestinyAuthority = if (BuildConfig.DEBUG) {
                EmbeddedDevelopmentDestinyAuthority()
            } else {
                UnavailableDestinyAuthority()
            }
            return LocalFortuneRepository(
                dao = DailyFortuneDatabase.get(context).fortuneDao(),
                settings = UserSettingsStore(context),
                authority = authority,
            )
        }

        fun publicId(date: LocalDate, zodiac: ZodiacSign): String = "public:$date:${zodiac.name}"
    }
}
