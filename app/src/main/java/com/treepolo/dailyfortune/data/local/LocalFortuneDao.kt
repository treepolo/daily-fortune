package com.treepolo.dailyfortune.data.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
interface LocalFortuneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAstronomySamples(samples: List<LocalAstronomySampleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDestiny(destiny: LocalDestinyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFactors(factors: List<LocalAstrologyFactorEntity>)

    @Query("DELETE FROM local_astrology_factors WHERE destinyId = :destinyId")
    suspend fun deleteFactors(destinyId: String)

    @Transaction
    suspend fun replaceDestiny(destiny: LocalDestinyEntity, factors: List<LocalAstrologyFactorEntity>) {
        upsertDestiny(destiny)
        deleteFactors(destiny.id)
        if (factors.isNotEmpty()) upsertFactors(factors)
    }

    @Query("SELECT * FROM local_destinies WHERE fortuneDate = :date AND sourceType = 'PUBLIC_ASTROLOGY'")
    suspend fun getPublicDestinies(date: String): List<LocalDestinyEntity>

    @Query("SELECT * FROM local_destinies WHERE id = :id LIMIT 1")
    suspend fun getDestiny(id: String): LocalDestinyEntity?

    @Query("SELECT * FROM local_astrology_factors WHERE destinyId = :destinyId ORDER BY factorId")
    suspend fun getFactors(destinyId: String): List<LocalAstrologyFactorEntity>

    @Query("SELECT * FROM local_astronomy_samples WHERE sourceDate = :sourceDate AND ephemerisVersion = :ephemerisVersion ORDER BY sampleIndex")
    suspend fun getAstronomySamples(
        sourceDate: String,
        ephemerisVersion: String,
    ): List<LocalAstronomySampleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyFortune(day: LocalDailyFortuneEntity)

    @Query("SELECT * FROM local_daily_fortunes WHERE fortuneDate = :date LIMIT 1")
    suspend fun getDailyFortune(date: String): LocalDailyFortuneEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRerollEvent(event: LocalRerollEventEntity)

    @Query("SELECT * FROM local_reroll_events WHERE fortuneDate = :date ORDER BY drawIndex DESC LIMIT 1")
    suspend fun getLatestReroll(date: String): LocalRerollEventEntity?

    @Query("SELECT COUNT(*) FROM local_reroll_events WHERE fortuneDate = :date")
    suspend fun getRerollCount(date: String): Int

    @Query("SELECT * FROM local_reroll_events WHERE fortuneDate = :date ORDER BY drawIndex")
    suspend fun getRerollHistory(date: String): List<LocalRerollEventEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFateSample(sample: LocalFateSampleEventEntity): Long

    @Query("SELECT COUNT(*) FROM local_fate_sample_events WHERE sampleKind = 'PUBLIC_VIEW'")
    suspend fun getDrawDayCount(): Long

    @Query(
        """
        SELECT
            COUNT(*) AS totalDraws,
            COALESCE(SUM(CASE WHEN overallGrade = 'DAI_JI' THEN 1 ELSE 0 END), 0) AS daiJiDraws,
            COALESCE(SUM(CASE WHEN overallGrade IN ('DAI_JI','JI','XIAO_JI','PING') THEN 1 ELSE 0 END), 0) AS nonXiongDraws,
            COALESCE(SUM(CASE WHEN overallGrade = 'DAI_XIONG' THEN 1 ELSE 0 END), 0) AS daiXiongDraws
        FROM local_fate_sample_events
        """,
    )
    suspend fun getStatsAggregate(): LocalStatsAggregate

    @Query("SELECT fortuneDate, COUNT(*) AS rerollCount FROM local_reroll_events GROUP BY fortuneDate")
    suspend fun getRerollDayCounts(): List<LocalRerollDayCount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBinding(binding: LocalBindingEntity)

    @Query("SELECT * FROM local_bindings ORDER BY createdAtEpochMillis DESC")
    suspend fun getBindings(): List<LocalBindingEntity>
}
