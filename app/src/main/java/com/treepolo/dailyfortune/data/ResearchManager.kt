package com.treepolo.dailyfortune.data

import android.content.Context
import com.treepolo.dailyfortune.BuildConfig
import com.treepolo.dailyfortune.data.local.AnalyticsUploadState
import com.treepolo.dailyfortune.data.local.LocalAnalyticsEventEntity
import com.treepolo.dailyfortune.data.local.LocalFortuneDao
import com.treepolo.dailyfortune.model.ResolvedExperimentConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ResearchManager(
    context: Context,
    private val dao: LocalFortuneDao,
) {
    private val preferences = context.getSharedPreferences("research_state_v1", Context.MODE_PRIVATE)
    private val installationId: String = preferences.getString(KEY_INSTALLATION_ID, null)
        ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(KEY_INSTALLATION_ID, it).apply()
        }
    private val sessionMutex = Mutex()
    private val uploadMutex = Mutex()

    @Volatile private var sessionId: String? = null
    @Volatile private var activeConfig: ResolvedExperimentConfig = loadCachedConfig()

    fun currentConfig(): ResolvedExperimentConfig = activeConfig

    suspend fun startSession() {
        val newSession = sessionMutex.withLock {
            if (sessionId != null) return@withLock null
            UUID.randomUUID().toString().also { sessionId = it }
        }
        if (newSession != null) {
            dao.insertAnalyticsEvent(eventEntity("app_open", JSONObject(), activeConfig, newSession))
            dao.insertAnalyticsEvent(eventEntity("session_start", JSONObject(), activeConfig, newSession))
        }
        refreshRemoteConfig()
        flushPendingEvents()
    }

    suspend fun endSession() {
        val endingSession = sessionMutex.withLock {
            val current = sessionId ?: return@withLock null
            sessionId = null
            current
        } ?: return
        dao.insertAnalyticsEvent(eventEntity("session_end", JSONObject(), activeConfig, endingSession))
        flushPendingEvents()
    }

    suspend fun refreshRemoteConfig() {
        if (BuildConfig.REMOTE_CONFIG_URL.isBlank()) return
        val response = runCatching {
            withContext(Dispatchers.IO) {
                val zone = ZoneId.systemDefault().id
                val separator = if ('?' in BuildConfig.REMOTE_CONFIG_URL) '&' else '?'
                val url = BuildConfig.REMOTE_CONFIG_URL + separator + listOf(
                    "installation_id" to installationId,
                    "app_version" to BuildConfig.VERSION_NAME,
                    "timezone" to zone,
                ).joinToString("&") { (key, value) ->
                    "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
                }
                httpGet(url)
            }
        }.getOrNull() ?: return

        val parsed = runCatching { ExperimentConfigCodec.decode(response) }.getOrNull() ?: return
        activeConfig = parsed
        preferences.edit()
            .putString(KEY_CACHED_CONFIG, response)
            .putLong(KEY_CACHED_CONFIG_AT, System.currentTimeMillis())
            .apply()
    }

    fun eventEntity(
        eventName: String,
        payload: JSONObject,
        config: ResolvedExperimentConfig = activeConfig,
        explicitSessionId: String? = null,
    ): LocalAnalyticsEventEntity {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val localDateTime = Instant.ofEpochMilli(now).atZone(zone).toLocalDateTime().toString()
        return LocalAnalyticsEventEntity(
            eventId = UUID.randomUUID().toString(),
            installationId = installationId,
            sessionId = explicitSessionId ?: sessionId ?: "no-session",
            eventName = eventName,
            eventEpochMillis = now,
            localDateTime = localDateTime,
            timezoneId = zone.id,
            appVersion = BuildConfig.VERSION_NAME,
            configId = config.configId,
            assignmentsJson = ExperimentConfigCodec.assignmentsToJson(config.assignments),
            payloadJson = payload.toString(),
            uploadState = AnalyticsUploadState.PENDING.name,
            attemptCount = 0,
        )
    }

    suspend fun enqueueEvent(
        eventName: String,
        payload: JSONObject = JSONObject(),
        config: ResolvedExperimentConfig = activeConfig,
    ) {
        dao.insertAnalyticsEvent(eventEntity(eventName, payload, config))
    }

    suspend fun flushPendingEvents() = uploadMutex.withLock {
        if (BuildConfig.ANALYTICS_INGEST_URL.isBlank()) return@withLock
        while (true) {
            val events = dao.getPendingAnalyticsEvents(50)
            if (events.isEmpty()) return@withLock
            val ids = events.map { it.eventId }
            val accepted = runCatching {
                withContext(Dispatchers.IO) {
                    httpPost(BuildConfig.ANALYTICS_INGEST_URL, encodeEventBatch(events))
                }
            }.getOrDefault(false)
            if (accepted) {
                dao.deleteAnalyticsEvents(ids)
            } else {
                dao.markAnalyticsEventsFailed(ids)
                return@withLock
            }
        }
    }

    private fun loadCachedConfig(): ResolvedExperimentConfig {
        val raw = preferences.getString(KEY_CACHED_CONFIG, null)
        return raw?.let { runCatching { ExperimentConfigCodec.decode(it) }.getOrNull() }
            ?: ResolvedExperimentConfig.embeddedDefault()
    }

    private fun encodeEventBatch(events: List<LocalAnalyticsEventEntity>): String {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject()
                    .put("event_id", event.eventId)
                    .put("installation_id", event.installationId)
                    .put("session_id", event.sessionId)
                    .put("event_name", event.eventName)
                    .put("event_epoch_millis", event.eventEpochMillis)
                    .put("local_datetime", event.localDateTime)
                    .put("timezone_id", event.timezoneId)
                    .put("app_version", event.appVersion)
                    .put("config_id", event.configId)
                    .put("assignments", JSONArray(event.assignmentsJson))
                    .put("payload", JSONObject(event.payloadJson)),
            )
        }
        return JSONObject().put("events", array).toString()
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4_000
            readTimeout = 4_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            require(connection.responseCode in 200..299) { "Remote config HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpPost(url: String, body: String): Boolean {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4_000
            readTimeout = 4_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.bufferedWriter().use { it.write(body) }
            return connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val KEY_CACHED_CONFIG = "cached_remote_config_json"
        private const val KEY_CACHED_CONFIG_AT = "cached_remote_config_saved_at"
    }
}
