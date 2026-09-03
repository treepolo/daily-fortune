package com.treepolo.dailyfortune.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.treepolo.dailyfortune.data.AstronomyEphemeris
import com.treepolo.dailyfortune.data.FortuneRepositorySnapshot
import com.treepolo.dailyfortune.data.LocalFortuneRepository
import com.treepolo.dailyfortune.model.DestinyChange
import com.treepolo.dailyfortune.model.FortuneStats
import com.treepolo.dailyfortune.model.ResolvedDestiny
import com.treepolo.dailyfortune.model.ZodiacSign
import java.time.LocalDate
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
            repository.prepare()
            refresh(todayTaipei())
            var lastDate = todayTaipei()
            while (isActive) {
                delay(60_000L)
                val today = todayTaipei()
                if (today != lastDate) {
                    lastDate = today
                    refresh(today)
                }
            }
        }
    }

    fun selectZodiac(zodiac: ZodiacSign) {
        viewModelScope.launch {
            val today = todayTaipei()
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = runCatching {
                withContext(Dispatchers.IO) { repository.selectZodiac(today, zodiac) }
            }
            if (result.isSuccess) {
                refresh(today)
            } else {
                restoreLocalState(today, requireNotNull(result.exceptionOrNull()))
            }
        }
    }

    fun defyFate() {
        viewModelScope.launch {
            val today = todayTaipei()
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = runCatching {
                withContext(Dispatchers.IO) { repository.reroll(today) }
            }
            if (result.isSuccess) {
                refresh(today)
            } else {
                restoreLocalState(today, requireNotNull(result.exceptionOrNull()))
            }
        }
    }

    private suspend fun refresh(date: LocalDate) {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        val result = runCatching {
            withContext(Dispatchers.IO) {
                repository.markTodaySeen(date)
                repository.snapshot(date)
            }
        }
        val snapshot = result.getOrNull()
        if (snapshot != null) {
            render(snapshot)
        } else {
            restoreLocalState(date, requireNotNull(result.exceptionOrNull()))
        }
    }

    private suspend fun restoreLocalState(date: LocalDate, error: Throwable) {
        val local = runCatching {
            withContext(Dispatchers.IO) { repository.localSnapshot(date) }
        }.getOrNull()
        if (local != null) {
            render(local, error.message ?: "暫時無法取得今日天命。")
        } else {
            showError(error)
        }
    }

    private fun render(snapshot: FortuneRepositorySnapshot, errorMessage: String? = null) {
        _uiState.value = FortuneUiState(
            selectedZodiac = snapshot.selectedZodiac,
            publicDestinies = snapshot.publicDestinies,
            currentDestiny = snapshot.currentDestiny,
            destinyChange = snapshot.destinyChange,
            hasDefiedFate = snapshot.currentDestiny?.parallelSky != null,
            todayRerollCount = snapshot.todayRerollCount,
            stats = snapshot.stats,
            isLoading = false,
            errorMessage = errorMessage,
        )
    }

    private fun showError(error: Throwable) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            errorMessage = error.message ?: "暫時無法取得今日天命。",
        )
    }

    private fun todayTaipei(): LocalDate = LocalDate.now(AstronomyEphemeris.zone)
}

data class FortuneUiState(
    val selectedZodiac: ZodiacSign? = null,
    val publicDestinies: Map<ZodiacSign, ResolvedDestiny> = emptyMap(),
    val currentDestiny: ResolvedDestiny? = null,
    val destinyChange: DestinyChange? = null,
    val hasDefiedFate: Boolean = false,
    val todayRerollCount: Int = 0,
    val stats: FortuneStats = FortuneStats(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)
