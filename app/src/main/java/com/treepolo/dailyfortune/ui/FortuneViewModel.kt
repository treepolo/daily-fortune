package com.treepolo.dailyfortune.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.treepolo.dailyfortune.data.AstronomyEphemeris
import com.treepolo.dailyfortune.data.DailyDestinyProvider
import com.treepolo.dailyfortune.data.FortuneStore
import com.treepolo.dailyfortune.model.DestinyChange
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class FortuneViewModel(application: Application) : AndroidViewModel(application) {
    private val store = FortuneStore(application)
    private val rerollMutex = Mutex()

    val uiState = store.state
        .map { persisted ->
            val date = persisted.todayDate
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: todayTaipei()
            val publicDestinies = withContext(Dispatchers.Default) {
                DailyDestinyProvider.publicDestinies(date)
            }
            val zodiac = persisted.selectedZodiac
            val personalDestiny = if (zodiac != null && persisted.todayPersonalSkyDate != null) {
                withContext(Dispatchers.Default) {
                    DailyDestinyProvider.personalDestiny(
                        date = date,
                        zodiac = zodiac,
                        sourceDate = persisted.todayPersonalSkyDate,
                    )
                }
            } else {
                null
            }
            val currentDestiny = personalDestiny ?: zodiac?.let(publicDestinies::get)
            val change = if (zodiac != null && personalDestiny != null) {
                DailyDestinyProvider.compare(publicDestinies.getValue(zodiac), personalDestiny)
            } else {
                null
            }

            FortuneUiState(
                selectedZodiac = zodiac,
                publicDestinies = publicDestinies,
                currentDestiny = currentDestiny,
                destinyChange = change,
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
                val today = todayTaipei()
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
            val today = todayTaipei()
            val publicDestiny = withContext(Dispatchers.Default) {
                DailyDestinyProvider.publicDestiny(today, zodiac)
            }
            store.markTodaySeen(today, publicDestiny.overallGrade)
        }
    }

    fun defyFate() {
        viewModelScope.launch {
            rerollMutex.withLock {
                val persisted = store.state.first()
                val zodiac = persisted.selectedZodiac ?: return@withLock
                val today = todayTaipei()
                val publicDestiny = withContext(Dispatchers.Default) {
                    DailyDestinyProvider.publicDestiny(today, zodiac)
                }
                store.markTodaySeen(today, publicDestiny.overallGrade)

                val newDestiny = withContext(Dispatchers.Default) {
                    DailyDestinyProvider.personalReroll(today, zodiac)
                }
                val sourceDate = requireNotNull(newDestiny.parallelSky).sourceDate
                store.reroll(today, sourceDate, newDestiny.overallGrade)
            }
        }
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
)
