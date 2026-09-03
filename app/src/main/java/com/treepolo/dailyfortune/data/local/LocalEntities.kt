package com.treepolo.dailyfortune.data.local

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "fortune_draws_v2",
    indices = [Index(value = ["fortuneDate", "drawIndex"], unique = true)],
)
data class LocalFortuneDrawEntity(
    @PrimaryKey val id: String,
    val fortuneDate: String,
    val drawIndex: Int,
    val drawType: String,
    val wealthScore: Int,
    val loveScore: Int,
    val workStudyScore: Int,
    val relationshipsScore: Int,
    val healthScore: Int,
    val rawAverage: Double,
    val overallScore: Int,
    val configId: String,
    val assignmentsJson: String,
    val distributionId: String,
    val samplingProfileId: String,
    val overallRuleId: String,
    val createdAtEpochMillis: Long,
)

@Entity(tableName = "daily_fortune_state_v2")
data class LocalDailyFortuneStateEntity(
    @PrimaryKey val fortuneDate: String,
    val currentDrawId: String,
    val drawCount: Int,
    val firstDrawAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "analytics_events_v2",
    indices = [Index(value = ["uploadState", "eventEpochMillis"])],
)
data class LocalAnalyticsEventEntity(
    @PrimaryKey val eventId: String,
    val installationId: String,
    val sessionId: String,
    val eventName: String,
    val eventEpochMillis: Long,
    val localDateTime: String,
    val timezoneId: String,
    val appVersion: String,
    val configId: String,
    val assignmentsJson: String,
    val payloadJson: String,
    val uploadState: String,
    val attemptCount: Int,
)

enum class AnalyticsUploadState {
    PENDING,
    FAILED,
}
