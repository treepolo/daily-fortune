package com.treepolo.dailyfortune.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.treepolo.dailyfortune.data.FortuneCatalog
import com.treepolo.dailyfortune.data.FortuneStore
import com.treepolo.dailyfortune.model.FortuneDefinition
import com.treepolo.dailyfortune.model.FortuneStats
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FortuneViewModel(application: Application) : AndroidViewModel(application) {
    private val store = FortuneStore(application)

    val uiState = store.state
        .map { persisted ->
            FortuneUiState(
                currentFortune = FortuneCatalog.byNumber(persisted.todayFortuneNumber),
                todayRerollCount = persisted.todayRerollCount,
                stats = persisted.stats,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FortuneUiState(),
        )

    init {
        viewModelScope.launch {
            while (isActive) {
                store.rolloverIfNeeded(LocalDate.now())
                delay(60_000L)
            }
        }
    }

    fun drawToday() {
        viewModelScope.launch {
            store.drawInitial(LocalDate.now(), FortuneCatalog.random())
        }
    }

    fun defyFate() {
        viewModelScope.launch {
            store.reroll(LocalDate.now(), FortuneCatalog.random())
        }
    }
}

data class FortuneUiState(
    val currentFortune: FortuneDefinition? = null,
    val todayRerollCount: Int = 0,
    val stats: FortuneStats = FortuneStats(),
)
