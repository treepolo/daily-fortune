package com.treepolo.dailyfortune.data.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
interface LocalFortuneDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDraw(draw: LocalFortuneDrawEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyState(state: LocalDailyFortuneStateEntity)

    @Query("SELECT * FROM daily_fortune_state_v2 WHERE fortuneDate = :date LIMIT 1")
    suspend fun getDailyState(date: String): LocalDailyFortuneStateEntity?

    @Query("SELECT * FROM fortune_draws_v2 WHERE id = :id LIMIT 1")
    suspend fun getDraw(id: String): LocalFortuneDrawEntity?

    @Query("SELECT * FROM fortune_draws_v2 WHERE fortuneDate = :date ORDER BY drawIndex")
    suspend fun getDrawHistory(date: String): List<LocalFortuneDrawEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAnalyticsEvent(event: LocalAnalyticsEventEntity)

    @Query(
        """
        SELECT * FROM analytics_events_v2
        WHERE uploadState IN ('PENDING', 'FAILED')
        ORDER BY eventEpochMillis
        LIMIT :limit
        """,
    )
    suspend fun getPendingAnalyticsEvents(limit: Int = 50): List<LocalAnalyticsEventEntity>

    @Query("DELETE FROM analytics_events_v2 WHERE eventId IN (:eventIds)")
    suspend fun deleteAnalyticsEvents(eventIds: List<String>)

    @Query(
        """
        UPDATE analytics_events_v2
        SET uploadState = 'FAILED', attemptCount = attemptCount + 1
        WHERE eventId IN (:eventIds)
        """,
    )
    suspend fun markAnalyticsEventsFailed(eventIds: List<String>)

    @Transaction
    suspend fun persistDraw(
        draw: LocalFortuneDrawEntity,
        state: LocalDailyFortuneStateEntity,
        analyticsEvent: LocalAnalyticsEventEntity,
    ) {
        insertDraw(draw)
        upsertDailyState(state)
        insertAnalyticsEvent(analyticsEvent)
    }
}
