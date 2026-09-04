package com.treepolo.dailyfortune.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.treepolo.dailyfortune.ads.AdMobRewardedController
import com.treepolo.dailyfortune.ads.RewardedResult
import com.treepolo.dailyfortune.data.LocalFortuneRepository
import com.treepolo.dailyfortune.model.AdFailurePolicy
import com.treepolo.dailyfortune.model.AdsConfig
import com.treepolo.dailyfortune.model.FortuneDraw
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class FortuneViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LocalFortuneRepository.create(application)
    private val _uiState = MutableStateFlow(FortuneUiState(isLoading = true))
    val uiState: StateFlow<FortuneUiState> = _uiState.asStateFlow()
    private val _adsConfig = MutableStateFlow(AdsConfig(enabled = false))
    val adsConfig: StateFlow<AdsConfig> = _adsConfig.asStateFlow()

    init {
        viewModelScope.launch {
            var lastDate = today()
            while (isActive) {
                delay(60_000L)
                val currentDate = today()
                if (currentDate != lastDate) {
                    lastDate = currentDate
                    refresh(currentDate)
                }
            }
        }
    }

    fun onForeground() {
        // Visual startup depends only on the local Room snapshot. Remote treatment resolution,
        // ad-policy resolution, consent/ad preload, and analytics upload must never hold the
        // full-screen startup loader.
        _uiState.value = _uiState.value.copy(errorMessage = null)
        viewModelScope.launch {
            refresh(today())
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.startSession() }
            _adsConfig.value = repository.currentAdsConfig()
            runCatching { repository.flushResearchEvents() }
        }
    }

    fun onBackground() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.endSession() }
            runCatching { repository.flushResearchEvents() }
        }
    }

    fun drawToday() {
        performDraw { date -> repository.initialDraw(date) }
    }

    fun defyFate() {
        viewModelScope.launch {
            val currentDraw = _uiState.value.currentDraw
                ?: return@launch
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            val configResult = runCatching {
                withContext(Dispatchers.IO) { repository.ensureAdsConfigReady() }
            }
            val config = configResult.getOrElse {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "暫時無法確認逆天改命條件，請再試一次。",
                )
                return@launch
            }
            _adsConfig.value = config

            val gate = evaluateRerollGate(currentDraw, config)
            if (!gate.allowed) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "廣告未完成，這次沒有消耗逆天改命。",
                )
                return@launch
            }

            if (config.enabled) {
                reportAdEvent(
                    "reroll_unlocked",
                    mapOf(
                        "placement" to "reroll_rewarded",
                        "reason" to gate.reason,
                        "overall_score" to currentDraw.overallGrade.score,
                    ),
                )
            }
            performDrawNow { date -> repository.reroll(date) }
        }
    }

    private suspend fun evaluateRerollGate(
        currentDraw: FortuneDraw,
        config: AdsConfig,
    ): RerollGateResult {
        if (!config.enabled) return RerollGateResult(true, "ads_disabled")
        if (currentDraw.overallGrade.score in config.bypassOverallScores) {
            reportAdEvent(
                "ad_gate_bypassed",
                mapOf(
                    "placement" to "reroll_rewarded",
                    "reason" to "overall_score",
                    "overall_score" to currentDraw.overallGrade.score,
                ),
            )
            return RerollGateResult(true, "bypass_overall_score")
        }

        val result = AdMobRewardedController.showForReroll(config, ::reportAdEvent)
        if (result == RewardedResult.REWARDED) return RerollGateResult(true, "rewarded_complete")

        val failOpen = config.failurePolicy == AdFailurePolicy.FAIL_OPEN
        if (failOpen) {
            reportAdEvent(
                "ad_failed",
                mapOf(
                    "placement" to "reroll_rewarded",
                    "stage" to "gate",
                    "result" to result.name,
                    "failure_policy" to config.failurePolicy.name,
                ),
            )
        }
        return RerollGateResult(failOpen, if (failOpen) "ad_failure_fail_open" else "ad_required")
    }

    private fun reportAdEvent(eventName: String, payload: Map<String, Any?>) {
        viewModelScope.launch(Dispatchers.IO) {
            val json = JSONObject()
            payload.forEach { (key, value) -> json.put(key, value) }
            runCatching { repository.recordResearchEvent(eventName, json) }
            runCatching { repository.flushResearchEvents() }
        }
    }

    private fun performDraw(action: suspend (LocalDate) -> FortuneDraw) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            performDrawNow(action)
        }
    }

    private suspend fun performDrawNow(action: suspend (LocalDate) -> FortuneDraw) {
        val date = today()
        val result = runCatching { withContext(Dispatchers.IO) { action(date) } }
        result.onSuccess { draw ->
            _uiState.value = FortuneUiState(currentDraw = draw, isLoading = false)
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { repository.flushResearchEvents() }
            }
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = error.message ?: "抽籤失敗，請再試一次。",
            )
        }
    }

    private suspend fun refresh(date: LocalDate) {
        val result = runCatching {
            withContext(Dispatchers.IO) { repository.snapshot(date) }
        }
        result.onSuccess { snapshot ->
            _uiState.value = FortuneUiState(
                currentDraw = snapshot.currentDraw,
                isLoading = false,
            )
        }.onFailure { error ->
            _uiState.value = FortuneUiState(
                currentDraw = null,
                isLoading = false,
                errorMessage = error.message ?: "暫時無法讀取今日運勢。",
            )
        }
    }

    private fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())
}

data class FortuneUiState(
    val currentDraw: FortuneDraw? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

private data class RerollGateResult(
    val allowed: Boolean,
    val reason: String,
)
