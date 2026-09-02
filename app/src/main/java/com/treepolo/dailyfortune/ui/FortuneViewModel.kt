package com.treepolo.dailyfortune.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.treepolo.dailyfortune.data.DailyDestinyProvider
import com.treepolo.dailyfortune.data.FortuneStore
import com.treepolo.dailyfortune.model.FortuneStats
import com.treepolo.dailyfortune.model.ResolvedDestiny
import com.treepolo.dailyfortune.model.ZodiacSign
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FortuneViewModel(application: Application) : AndroidViewModel(application) {
    private val store = FortuneStore(application)

    val uiState = store.state
        .map { persisted ->
            val date = persisted.todayDate
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: LocalDate.now()
            val publicDestinies = withContext(Dispatchers.Default) {
                DailyDestinyProvider.publicDestinies(date)
            }
            val personalDestiny = persisted.todayPersonalDestiny?.let(DailyDestinyProvider::resolve)
            val currentDestiny = personalDestiny
                ?: persisted.selectedZodiac?.let(publicDestinies::get)

            FortuneUiState(
                selectedZodiac = persisted.selectedZodiac,
                publicDestinies = publicDestinies,
                currentDestiny = currentDestiny,
                hasDefiedFate = personalDestiny != null,
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
                val today = LocalDate.now()
                store.rolloverIfNeeded(today)
                val persisted = store.state.first()
                persisted.selectedZodiac?.let { zodiac ->
                    val publicDestiny = withContext(Dispatchers.Default) {
                        DailyDestinyProvider.publicDestiny(today, zodiac)
                    }
                    store.markTodaySeen(today, publicDestiny.overallGrade)
                }
                delay(60_000L)
            }
        }
    }

    fun selectZodiac(zodiac: ZodiacSign) {
        viewModelScope.launch {
            store.selectZodiac(zodiac)
            val today = LocalDate.now()
            val publicDestiny = withContext(Dispatchers.Default) {
                DailyDestinyProvider.publicDestiny(today, zodiac)
            }
            store.markTodaySeen(today, publicDestiny.overallGrade)
        }
    }

    fun defyFate() {
        viewModelScope.launch {
            val persisted = store.state.first()
            val zodiac = persisted.selectedZodiac ?: return@launch
            val today = LocalDate.now()
            val publicDestiny = withContext(Dispatchers.Default) {
                DailyDestinyProvider.publicDestiny(today, zodiac)
            }
            store.markTodaySeen(today, publicDestiny.overallGrade)

            val newDestiny = DailyDestinyProvider.personalReroll()
            store.reroll(
                today,
                requireNotNull(newDestiny.snapshot),
                newDestiny.overallGrade,
            )
        }
    }
}

data class FortuneUiState(
    val selectedZodiac: ZodiacSign? = null,
    val publicDestinies: Map<ZodiacSign, ResolvedDestiny> = emptyMap(),
    val currentDestiny: ResolvedDestiny? = null,
    val hasDefiedFate: Boolean = false,
    val todayRerollCount: Int = 0,
    val stats: FortuneStats = FortuneStats(),
)
