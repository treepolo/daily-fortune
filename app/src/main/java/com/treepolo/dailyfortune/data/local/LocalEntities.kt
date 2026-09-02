package com.treepolo.dailyfortune.data.local

import androidx.room3.Entity
import androidx.room3.Index

@Entity(
    tableName = "local_astronomy_samples",
    primaryKeys = ["sourceDate", "ephemerisVersion", "sampleIndex"],
)
data class LocalAstronomySampleEntity(
    val sourceDate: String,
    val ephemerisVersion: String,
    val sampleIndex: Int,
    val instantEpochMillis: Long,
    val sun: Double,
    val moon: Double,
    val mercury: Double,
    val venus: Double,
    val mars: Double,
    val jupiter: Double,
    val saturn: Double,
    val uranus: Double,
    val neptune: Double,
    val pluto: Double,
)

@Entity(
    tableName = "local_destinies",
    indices = [
        Index(value = ["fortuneDate", "sourceType"]),
        Index(value = ["sourceDate", "ephemerisVersion"]),
    ],
)
data class LocalDestinyEntity(
    @androidx.room3.PrimaryKey val id: String,
    val fortuneDate: String,
    val zodiacSign: String,
    val sourceType: String,
    val sourceDate: String,
    val engineVersion: String,
    val ephemerisVersion: String,
    val authority: String,
    val overallGrade: String,
    val overallScore: Double,
    val overallExplanation: String,
    val wealthGrade: String,
    val wealthScore: Double,
    val wealthExplanation: String,
    val loveGrade: String,
    val loveScore: Double,
    val loveExplanation: String,
    val workStudyGrade: String,
    val workStudyScore: Double,
    val workStudyExplanation: String,
    val relationshipsGrade: String,
    val relationshipsScore: Double,
    val relationshipsExplanation: String,
    val healthGrade: String,
    val healthScore: Double,
    val healthExplanation: String,
    val originalSunLongitude: Double?,
    val alteredSunLongitude: Double?,
    val sunLongitudeDifference: Double?,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "local_astrology_factors",
    primaryKeys = ["destinyId", "factorId"],
    indices = [Index(value = ["destinyId"])],
)
data class LocalAstrologyFactorEntity(
    val destinyId: String,
    val factorId: String,
    val title: String,
    val evidence: String,
    val wealth: Double,
    val love: Double,
    val workStudy: Double,
    val relationships: Double,
    val health: Double,
)

@Entity(tableName = "local_daily_fortunes")
data class LocalDailyFortuneEntity(
    @androidx.room3.PrimaryKey val fortuneDate: String,
    val zodiacSign: String,
    val publicDestinyId: String,
    val currentPersonalDestinyId: String?,
    val rerollCount: Int,
    val firstSeenAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "local_reroll_events",
    indices = [Index(value = ["fortuneDate", "drawIndex"], unique = true)],
)
data class LocalRerollEventEntity(
    @androidx.room3.PrimaryKey val localId: String,
    val serverId: String?,
    val fortuneDate: String,
    val zodiacSign: String,
    val drawIndex: Int,
    val beforeDestinyId: String,
    val afterDestinyId: String,
    val sourceDate: String,
    val syncState: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "local_fate_sample_events",
    indices = [Index(value = ["fortuneDate", "sampleKind"])],
)
data class LocalFateSampleEventEntity(
    @androidx.room3.PrimaryKey val sampleId: String,
    val fortuneDate: String,
    val sampleKind: String,
    val destinyId: String,
    val overallGrade: String,
    val createdAtEpochMillis: Long,
)

@Entity(tableName = "local_bindings")
data class LocalBindingEntity(
    @androidx.room3.PrimaryKey val localId: String,
    val serverId: String?,
    val targetType: String,
    val targetId: String,
    val message: String?,
    val syncState: String,
    val createdAtEpochMillis: Long,
)

data class LocalStatsAggregate(
    val totalDraws: Long,
    val daiJiDraws: Long,
    val nonXiongDraws: Long,
    val daiXiongDraws: Long,
)

data class LocalRerollDayCount(
    val fortuneDate: String,
    val rerollCount: Long,
)

enum class LocalAuthority { CENTRAL, DEVELOPMENT_EMBEDDED, PERSONAL_LOCAL }
enum class LocalSyncState { LOCAL_DEVELOPMENT, PENDING, SYNCED, FAILED }
enum class LocalSampleKind { PUBLIC_VIEW, PERSONAL_REROLL }
