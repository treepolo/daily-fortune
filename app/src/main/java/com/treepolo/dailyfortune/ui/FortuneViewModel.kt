package com.treepolo.dailyfortune.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.treepolo.dailyfortune.data.LocalFortuneRepository
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

class FortuneViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LocalFortuneRepository.create(application)
    private val _uiState = MutableStateFlow(FortuneUiState(isLoading = true))
    val uiState: StateFlow<FortuneUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            var lastDate = today()
            refresh(lastDate)
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
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.startSession() } }
            refresh(today())
        }
    }

    fun onBackground() {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.endSession() } }
        }
    }

    fun drawToday() {
        performDraw { date -> repository.initialDraw(date) }
    }

    fun defyFate() {
        performDraw { date -> repository.reroll(date) }
    }

    private fun performDraw(action: suspend (LocalDate) -> FortuneDraw) {
        viewModelScope.launch {
            val date = today()
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
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
