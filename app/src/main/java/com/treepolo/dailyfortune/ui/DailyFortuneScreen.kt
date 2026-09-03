package com.treepolo.dailyfortune.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.FortuneDraw
import com.treepolo.dailyfortune.model.FortuneGrade

@Composable
fun DailyFortuneRoot(viewModel: FortuneViewModel) {
    val state by viewModel.uiState.collectAsState()
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = colorScheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            DailyFortuneScreen(
                state = state,
                onInitialDraw = viewModel::drawToday,
                onReroll = viewModel::defyFate,
            )
        }
    }
}

@Composable
private fun DailyFortuneScreen(
    state: FortuneUiState,
    onInitialDraw: () -> Unit,
    onReroll: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "今日運勢",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (state.currentDraw == null) "今天的運勢，自己來抽。" else "今日命運已定。",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(32.dp))

            when {
                state.isLoading && state.currentDraw == null -> CircularProgressIndicator()
                state.currentDraw == null -> EmptyFortune(onInitialDraw, state.isLoading)
                else -> FortuneResult(state.currentDraw, onReroll, state.isLoading)
            }

            state.errorMessage?.let { message ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun EmptyFortune(
    onInitialDraw: () -> Unit,
    isLoading: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "今天還沒抽籤",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "按下按鈕後，財運、戀愛、工作／學業、人際、健康會各自抽出今天的結果。",
                modifier = Modifier.padding(top = 10.dp, bottom = 20.dp),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onInitialDraw, enabled = !isLoading) {
                Text("抽籤")
            }
        }
    }
}

@Composable
private fun FortuneResult(
    draw: FortuneDraw,
    onReroll: () -> Unit,
    isLoading: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "總體運勢",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = draw.overallGrade.label,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )

            FortuneDomain.entries.forEach { domain ->
                val score = draw.domainScores.getValue(domain)
                DomainRow(domain.label, FortuneGrade.fromScore(score).label)
            }
        }
    }

    Spacer(modifier = Modifier.height(22.dp))

    OutlinedButton(onClick = onReroll, enabled = !isLoading) {
        Text(if (isLoading) "改命中……" else "逆天改命!!")
    }
}

@Composable
private fun DomainRow(label: String, grade: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            grade,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
