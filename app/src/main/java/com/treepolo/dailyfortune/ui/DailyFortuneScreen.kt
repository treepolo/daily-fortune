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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.treepolo.dailyfortune.model.FortuneDefinition
import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.FortuneStats
import java.util.Locale

@Composable
fun DailyFortuneRoot(viewModel: FortuneViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = colorScheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            DailyFortuneScreen(
                state = state,
                onDraw = viewModel::drawToday,
                onReroll = viewModel::defyFate,
            )
        }
    }
}

@Composable
private fun DailyFortuneScreen(
    state: FortuneUiState,
    onDraw: () -> Unit,
    onReroll: () -> Unit,
) {
    var dialog by remember { mutableStateOf<InfoDialog?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "今日運勢",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "一天一籤；不服可以逆天改命。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                StatsPanel(state.stats)
            }

            Spacer(modifier = Modifier.height(28.dp))

            val fortune = state.currentFortune
            if (fortune == null) {
                Spacer(modifier = Modifier.height(48.dp))
                Text(
                    text = "今天的籤還沒有抽。",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = onDraw) {
                    Text("抽取今日運勢")
                }
            } else {
                FortuneCard(
                    fortune = fortune,
                    onGeneralInfo = {
                        dialog = InfoDialog(
                            title = "第 ${fortune.number} 籤 · ${fortune.grade.label}",
                            text = fortune.generalExplanation,
                        )
                    },
                    onDomainInfo = { domain ->
                        val result = fortune.domains.getValue(domain)
                        dialog = InfoDialog(
                            title = "${domain.label} · ${result.grade.label}",
                            text = result.explanation,
                        )
                    },
                )
                Spacer(modifier = Modifier.height(18.dp))
                OutlinedButton(onClick = onReroll) {
                    Text("逆天改命!!")
                }
                Text(
                    text = "今天已逆天改命 ${state.todayRerollCount} 次",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "目前為機制驗證版，籤池只包含少量已核對公版古籤。",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }
    }

    dialog?.let { info ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(info.title) },
            text = { Text(info.text) },
            confirmButton = {
                TextButton(onClick = { dialog = null }) {
                    Text("知道了")
                }
            },
        )
    }
}

@Composable
private fun FortuneCard(
    fortune: FortuneDefinition,
    onGeneralInfo: () -> Unit,
    onDomainInfo: (FortuneDomain) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("第 ${fortune.number} 籤", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = fortune.grade.label,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onGeneralInfo) {
                    Text("ⓘ")
                }
            }
            Text(
                text = "原籤等級：${fortune.sourceGrade}",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.height(18.dp))
            fortune.poem.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            Spacer(modifier = Modifier.height(22.dp))
            FortuneDomain.entries.forEach { domain ->
                val result = fortune.domains.getValue(domain)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = domain.label, modifier = Modifier.weight(1f))
                    Text(text = result.grade.label, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { onDomainInfo(domain) }) {
                        Text("ⓘ")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsPanel(stats: FortuneStats) {
    Card {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text("改命 ${stats.totalRerolls} 次", style = MaterialTheme.typography.labelSmall)
            Text(
                "平均 ${formatNumber(stats.averageDailyRerolls)} 次／日",
                style = MaterialTheme.typography.labelSmall,
            )
            Text("單日最高 ${stats.maxDailyRerolls} 次", style = MaterialTheme.typography.labelSmall)
            Text(
                "大吉 ${stats.daiJiDraws} · ${formatPercent(stats.daiJiRate)}",
                style = MaterialTheme.typography.labelSmall,
            )
            Text("非凶 ${formatPercent(stats.nonXiongRate)}", style = MaterialTheme.typography.labelSmall)
            Text(
                "大凶 ${stats.daiXiongDraws} · ${formatPercent(stats.daiXiongRate)}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun formatNumber(value: Double): String =
    String.format(Locale.TAIWAN, "%.2f", value)

private fun formatPercent(value: Double): String =
    String.format(Locale.TAIWAN, "%.1f%%", value * 100.0)

private data class InfoDialog(
    val title: String,
    val text: String,
)
