package com.treepolo.dailyfortune.ui

import androidx.compose.foundation.basicMarquee
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
import androidx.compose.material3.Card
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.FortuneStats
import com.treepolo.dailyfortune.model.ResolvedDestiny
import com.treepolo.dailyfortune.model.ZodiacSign
import java.util.Locale
import kotlin.random.Random

@Composable
fun DailyFortuneRoot(viewModel: FortuneViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = colorScheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            DailyFortuneScreen(
                state = state,
                onSelectZodiac = viewModel::selectZodiac,
                onReroll = viewModel::defyFate,
            )
        }
    }
}

@Composable
private fun DailyFortuneScreen(
    state: FortuneUiState,
    onSelectZodiac: (ZodiacSign) -> Unit,
    onReroll: () -> Unit,
) {
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
                        text = "今日天象已定；不服可以逆天改命。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                StatsPanel(state.stats)
            }

            Spacer(modifier = Modifier.height(28.dp))

            val zodiac = state.selectedZodiac
            when {
                zodiac == null -> ZodiacPicker(onSelectZodiac)

                state.publicDestinies.size != ZodiacSign.entries.size -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = if (state.isLoading) {
                                    "正在讀取今日天命……"
                                } else {
                                    state.errorMessage ?: "暫時無法取得今日天命。"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                            )
                            if (!state.isLoading) {
                                Text(
                                    text = "如果本機沒有今天的中央快取，正式版不會自行補算另一套天命。",
                                    modifier = Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }

                else -> {
                    DestinyTicker(state, zodiac)
                    Spacer(modifier = Modifier.height(22.dp))

                    Text(
                        text = if (state.hasDefiedFate) {
                            "${zodiac.label} · 你的命運已進入私人平行天象"
                        } else {
                            "${zodiac.label} · 目前仍在今日公共天命上"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onReroll,
                        enabled = !state.isLoading,
                    ) {
                        Text("逆天改命!!")
                    }
                    Text(
                        text = "今天已逆天改命 ${state.todayRerollCount} 次",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )

                    state.destinyChange?.let { change ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = change.narrative,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = change.domainChanges.values.joinToString("　｜　") { domain ->
                                        "${domain.domain.label} ${domain.beforeGrade.label}→${domain.afterGrade.label}"
                                    },
                                    modifier = Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                state.currentDestiny?.parallelSky?.let { sky ->
                                    Text(
                                        text = "平行天象取自 ${sky.sourceDate} 的真實天空；正午太陽黃經與今日相差 ${formatDegrees(sky.sunLongitudeDifference)}°。",
                                        modifier = Modifier.padding(top = 8.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }

                    if (state.hasDefiedFate) {
                        Text(
                            text = "跑馬燈中的 ${zodiac.label} 已替換成你的私人平行天象結果；其他十一星座仍是今日公共天象。",
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "公共天命：今日真實星曆 + Astrology Engine v1。逆天改命：安全亂數抽取另一個同季節、物理上真實存在的天空，再用完全相同的占星規則重算。Room 會保存今日快取、完整計算依據與改命歷史。",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ZodiacPicker(onSelect: (ZodiacSign) -> Unit) {
    Text(
        text = "先選擇你的星座",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = "第一版選定後先鎖定；正式設定頁會遵守『變更隔日生效』。",
        modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
    ZodiacSign.entries.chunked(3).forEach { rowSigns ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            rowSigns.forEach { sign ->
                TextButton(onClick = { onSelect(sign) }) {
                    Text(sign.label)
                }
            }
        }
    }
}

@Composable
private fun DestinyTicker(state: FortuneUiState, userZodiac: ZodiacSign) {
    var waitSigns by rememberSaveable(userZodiac.name) {
        mutableIntStateOf(Random.nextInt(from = 2, until = 12))
    }

    val effectiveDestinies = remember(
        state.publicDestinies,
        state.currentDestiny,
        state.hasDefiedFate,
        userZodiac,
    ) {
        state.publicDestinies.toMutableMap().apply {
            if (state.hasDefiedFate) {
                state.currentDestiny?.let { put(userZodiac, it) }
            }
        }
    }

    val tickerText = remember(effectiveDestinies, userZodiac, waitSigns) {
        val signs = ZodiacSign.entries
        val start = Math.floorMod(userZodiac.ordinal - waitSigns, signs.size)
        List(signs.size) { offset -> signs[(start + offset) % signs.size] }
            .joinToString(separator = "　◆　") { sign ->
                val destiny = effectiveDestinies.getValue(sign)
                destinySegment(sign, destiny, sign == userZodiac)
            }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(
                text = "今日十二星座命運播報",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = tickerText,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(iterations = Int.MAX_VALUE)
                    .padding(horizontal = 12.dp),
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private fun destinySegment(
    zodiac: ZodiacSign,
    destiny: ResolvedDestiny,
    isUser: Boolean,
): String = buildString {
    append("【")
    append(zodiac.label)
    if (isUser) append("・你")
    append("】總運勢 ")
    append(destiny.overallGrade.label)
    append("：")
    append(destiny.overallExplanation)

    FortuneDomain.entries.forEach { domain ->
        val result = destiny.domains.getValue(domain)
        append("　｜　")
        append(domain.label)
        append(' ')
        append(result.grade.label)
        append("：")
        append(result.explanation)
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

private fun formatDegrees(value: Double): String =
    String.format(Locale.TAIWAN, "%.3f", value)
