package com.treepolo.dailyfortune.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.FortuneDraw
import com.treepolo.dailyfortune.model.FortuneGrade
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

private val TempleInk = Color(0xFF100706)
private val TempleDeepRed = Color(0xFF330605)
private val TempleRed = Color(0xFF74100C)
private val TempleCinnabar = Color(0xFFAA2418)
private val TempleGold = Color(0xFFD5A13C)
private val TempleBrightGold = Color(0xFFFFDE78)
private val TemplePaper = Color(0xFFF2E2BE)
private val TemplePaperDark = Color(0xFFD8C396)
private val TempleMutedPaper = Color(0xFFBFAE8B)
private val Bamboo = Color(0xFFDDBD77)
private val BambooDark = Color(0xFF6C431F)

private const val SUSPENSE_MILLIS = 2_250L
private const val STICK_COMMIT_THRESHOLD = 0.72f
private const val PAPER_COMMIT_THRESHOLD = 0.985f

private enum class RitualStage {
    INITIALIZING,
    WAITING_FOR_STICK,
    SUSPENSE,
    PAPER_REVEAL,
    RESULT,
}

@Composable
fun DailyFortuneRoot(viewModel: FortuneViewModel) {
    val state by viewModel.uiState.collectAsState()
    val colors = darkColorScheme(
        primary = TempleGold,
        onPrimary = TempleInk,
        background = TempleInk,
        onBackground = TemplePaper,
        surface = TempleDeepRed,
        onSurface = TemplePaper,
    )
    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = TempleInk) {
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
    val haptics = LocalHapticFeedback.current
    var stage by remember { mutableStateOf(RitualStage.INITIALIZING) }
    var displayedDraw by remember { mutableStateOf<FortuneDraw?>(null) }
    var pendingDraw by remember { mutableStateOf<FortuneDraw?>(null) }
    var requestBaseId by remember { mutableStateOf<String?>(null) }
    var rerollFlow by remember { mutableStateOf(false) }
    var suspenseBeat by remember { mutableIntStateOf(0) }
    var paperProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(state.isLoading, state.currentDraw?.id) {
        if (stage == RitualStage.INITIALIZING && !state.isLoading) {
            displayedDraw = state.currentDraw
            stage = if (state.currentDraw == null) RitualStage.WAITING_FOR_STICK else RitualStage.RESULT
        }
    }

    LaunchedEffect(stage) {
        if (stage == RitualStage.SUSPENSE) {
            suspenseBeat = 0
            delay(650L)
            suspenseBeat = 1
            delay(700L)
            suspenseBeat = 2
        }
    }

    LaunchedEffect(state.currentDraw?.id, stage, state.errorMessage) {
        if (stage != RitualStage.SUSPENSE) return@LaunchedEffect
        if (state.errorMessage != null) {
            stage = if (displayedDraw == null) RitualStage.WAITING_FOR_STICK else RitualStage.RESULT
            return@LaunchedEffect
        }
        val incoming = state.currentDraw ?: return@LaunchedEffect
        if (incoming.id == requestBaseId) return@LaunchedEffect
        pendingDraw = incoming
        delay(SUSPENSE_MILLIS)
        paperProgress = 0f
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        stage = RitualStage.PAPER_REVEAL
    }

    fun armRitual(isReroll: Boolean) {
        rerollFlow = isReroll
        pendingDraw = null
        paperProgress = 0f
        stage = RitualStage.WAITING_FOR_STICK
    }

    fun commitStickPull() {
        if (stage != RitualStage.WAITING_FOR_STICK) return
        requestBaseId = state.currentDraw?.id
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        stage = RitualStage.SUSPENSE
        if (rerollFlow) onReroll() else onInitialDraw()
    }

    val atmosphereGrade = when (stage) {
        RitualStage.RESULT -> displayedDraw?.overallGrade
        else -> null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TempleBackdrop(atmosphereGrade)
        when (stage) {
            RitualStage.INITIALIZING -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = TempleGold)
            }
            RitualStage.WAITING_FOR_STICK -> DrawStage(
                isReroll = rerollFlow,
                onStickCommitted = ::commitStickPull,
            )
            RitualStage.SUSPENSE -> SuspenseStage(beat = suspenseBeat)
            RitualStage.PAPER_REVEAL -> pendingDraw?.let { draw ->
                PaperRevealStage(
                    draw = draw,
                    progress = paperProgress,
                    onProgress = { paperProgress = it },
                    onCompleted = {
                        paperProgress = 1f
                        displayedDraw = draw
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        stage = RitualStage.RESULT
                    },
                )
            }
            RitualStage.RESULT -> displayedDraw?.let { draw ->
                ResultStage(
                    draw = draw,
                    onReroll = { armRitual(true) },
                )
            }
        }

        state.errorMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
                color = TempleMutedPaper,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DrawStage(isReroll: Boolean, onStickCommitted: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TempleHeader(
            subtitle = if (isReroll) "再抽一籤，逆天改命" else "親手抽出今天的命數",
        )
        Spacer(modifier = Modifier.height(22.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = TempleDeepRed.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, TempleGold.copy(alpha = 0.58f)),
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                InteractiveFortuneTube(onStickCommitted = onStickCommitted)
                Text(
                    text = "按住中央籤，往上抽出",
                    modifier = Modifier.padding(top = 16.dp),
                    color = TempleBrightGold,
                    fontFamily = FontFamily.Serif,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Text(
                    text = "抽離籤筒後，今日結果才會真正落定",
                    modifier = Modifier.padding(top = 8.dp),
                    color = TempleMutedPaper,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun InteractiveFortuneTube(onStickCommitted: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var committed by remember { mutableStateOf(false) }
    val smoothProgress by animateFloatAsState(
        targetValue = dragProgress,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "stick-pull",
    )

    Box(
        modifier = Modifier.size(width = 230.dp, height = 330.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            repeat(6) { index ->
                val x = w * (0.27f + index * 0.09f)
                val top = h * (0.105f + (index % 2) * 0.025f)
                drawRoundRect(
                    color = Bamboo,
                    topLeft = Offset(x, top),
                    size = Size(w * 0.055f, h * 0.56f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.025f),
                )
            }
            val tubeLeft = w * 0.12f
            val tubeTop = h * 0.43f
            val tubeWidth = w * 0.76f
            val tubeHeight = h * 0.49f
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFF9C2719), Color(0xFF5A0D09), Color(0xFF260504)),
                    startY = tubeTop,
                    endY = tubeTop + tubeHeight,
                ),
                topLeft = Offset(tubeLeft, tubeTop),
                size = Size(tubeWidth, tubeHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
            )
            drawRoundRect(
                color = TempleGold,
                topLeft = Offset(tubeLeft, tubeTop),
                size = Size(tubeWidth, tubeHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
                style = Stroke(3.5f),
            )
            drawLine(
                color = TempleBrightGold.copy(alpha = 0.72f),
                start = Offset(tubeLeft + w * 0.04f, tubeTop + h * 0.12f),
                end = Offset(tubeLeft + tubeWidth - w * 0.04f, tubeTop + h * 0.12f),
                strokeWidth = 4f,
            )
        }

        Box(
            modifier = Modifier
                .offset(y = (-118f * smoothProgress).dp)
                .padding(top = 22.dp)
                .size(width = 28.dp, height = 206.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(BambooDark, TempleBrightGold, Bamboo, BambooDark),
                    ),
                )
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { committed = false },
                        onDragEnd = {
                            if (dragProgress >= STICK_COMMIT_THRESHOLD && !committed) {
                                committed = true
                                dragProgress = 1f
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStickCommitted()
                            } else if (!committed) {
                                dragProgress = 0f
                            }
                        },
                        onDragCancel = { if (!committed) dragProgress = 0f },
                    ) { change, amount ->
                        change.consume()
                        if (!committed) {
                            dragProgress = (dragProgress - amount.y / 180f).coerceIn(0f, 1f)
                        }
                    }
                },
        ) {
            Text(
                text = "籤",
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                color = TempleDeepRed,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
            )
        }

        Text(
            text = "籤",
            modifier = Modifier.align(Alignment.Center).padding(top = 116.dp),
            color = TempleBrightGold,
            fontFamily = FontFamily.Serif,
            fontSize = 46.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun SuspenseStage(beat: Int) {
    val infinite = rememberInfiniteTransition(label = "suspense")
    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2_800, easing = LinearEasing)),
        label = "spin",
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(420, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(TempleRed, TempleDeepRed, Color.Black))),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = spin },
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            repeat(36) { index ->
                val angle = Math.toRadians(index * 10.0)
                val inner = size.minDimension * 0.17f
                val outer = size.maxDimension * (0.45f + pulse * 0.08f)
                drawLine(
                    color = TempleGold.copy(alpha = 0.08f + pulse * 0.18f),
                    start = Offset(
                        center.x + cos(angle).toFloat() * inner,
                        center.y + sin(angle).toFloat() * inner,
                    ),
                    end = Offset(
                        center.x + cos(angle).toFloat() * outer,
                        center.y + sin(angle).toFloat() * outer,
                    ),
                    strokeWidth = if (index % 3 == 0) 4f else 1.5f,
                    cap = StrokeCap.Round,
                )
            }
            repeat(3) { ring ->
                drawCircle(
                    color = TempleBrightGold.copy(alpha = (0.22f - ring * 0.05f) * pulse),
                    radius = size.minDimension * (0.16f + ring * 0.07f + pulse * 0.025f),
                    center = center,
                    style = Stroke(3f),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(148.dp),
                shape = RoundedCornerShape(74.dp),
                color = TempleCinnabar.copy(alpha = 0.92f),
                border = BorderStroke(3.dp, TempleBrightGold.copy(alpha = pulse)),
                shadowElevation = 18.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "命",
                        color = TempleBrightGold,
                        fontFamily = FontFamily.Serif,
                        fontSize = 66.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
            Text(
                text = when (beat) {
                    0 -> "籤已抽離"
                    1 -> "五運正在落定"
                    else -> "命數已定"
                },
                modifier = Modifier.padding(top = 28.dp),
                color = TempleBrightGold,
                fontFamily = FontFamily.Serif,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 5.sp,
            )
            Text(
                text = when (beat) {
                    0 -> "不要急……"
                    1 -> "還差一瞬……"
                    else -> "準備親手揭籤"
                },
                modifier = Modifier.padding(top = 12.dp),
                color = TemplePaper.copy(alpha = 0.82f),
                fontSize = 16.sp,
                letterSpacing = 2.sp,
            )
        }
    }
}

@Composable
private fun PaperRevealStage(
    draw: FortuneDraw,
    progress: Float,
    onProgress: (Float) -> Unit,
    onCompleted: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TempleHeader(subtitle = "按住籤紙下緣，慢慢往下揭開")
        Spacer(modifier = Modifier.height(18.dp))
        FortunePaper(
            draw = draw,
            revealFraction = progress,
            interactive = true,
            onRevealDelta = { delta -> onProgress((progress + delta).coerceIn(0f, 1f)) },
            onRevealEnd = {
                if (progress >= PAPER_COMMIT_THRESHOLD) onCompleted()
            },
        )
    }
}

@Composable
private fun ResultStage(draw: FortuneDraw, onReroll: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TempleHeader(subtitle = "今日籤意已現")
        Spacer(modifier = Modifier.height(18.dp))
        FortunePaper(
            draw = draw,
            revealFraction = 1f,
            interactive = false,
            onRevealDelta = {},
            onRevealEnd = {},
        )
        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = onReroll,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(9.dp),
            border = BorderStroke(1.dp, TempleBrightGold),
            colors = ButtonDefaults.buttonColors(
                containerColor = TempleCinnabar,
                contentColor = TemplePaper,
            ),
        ) {
            Text(
                text = "逆天改命!!",
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Black,
                fontSize = 19.sp,
                letterSpacing = 2.sp,
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
    }
}

@Composable
private fun FortunePaper(
    draw: FortuneDraw,
    revealFraction: Float,
    interactive: Boolean,
    onRevealDelta: (Float) -> Unit,
    onRevealEnd: () -> Unit,
) {
    val visual = visualFor(draw.overallGrade)
    val minHeight = 94f
    val fullHeight = 548f
    val visibleHeight = minHeight + (fullHeight - minHeight) * revealFraction
    val paperBrush = Brush.verticalGradient(
        listOf(
            TemplePaper,
            Color(0xFFE8D4A8),
            TemplePaperDark,
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(visibleHeight.dp)
            .clip(RoundedCornerShape(12.dp))
            .clipToBounds()
            .background(paperBrush),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(fullHeight.dp)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "今 日 籤",
                color = TempleDeepRed,
                fontFamily = FontFamily.Serif,
                fontSize = 21.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 5.sp,
            )
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 8.dp)
                    .fillMaxWidth(0.82f)
                    .height(1.dp)
                    .background(TempleRed.copy(alpha = 0.48f)),
            )
            FortuneDomain.entries.forEach { domain ->
                val grade = FortuneGrade.fromScore(draw.domainScores.getValue(domain))
                DomainFortuneRow(label = domain.label, grade = grade)
            }
            Spacer(modifier = Modifier.height(10.dp))
            OverallFortuneSeal(grade = draw.overallGrade)
        }

        if (interactive) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(42.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, TemplePaperDark.copy(alpha = 0.96f)),
                        ),
                    )
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = onRevealEnd,
                            onDragCancel = onRevealEnd,
                        ) { change, amount ->
                            change.consume()
                            onRevealDelta(amount.y / 390f)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 82.dp, height = 5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(TempleRed.copy(alpha = 0.55f)),
                )
            }
        }

        if (draw.overallGrade == FortuneGrade.DAI_XIONG && revealFraction > 0.65f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(Color.Black.copy(alpha = 0.08f))
                repeat(8) { index ->
                    val x = size.width * ((index + 1) / 9f)
                    drawLine(
                        color = Color(0xFF3B0808).copy(alpha = 0.22f),
                        start = Offset(x, size.height * 0.72f),
                        end = Offset(x - 22f, size.height),
                        strokeWidth = 2f,
                    )
                }
            }
        }

        if (draw.overallGrade == FortuneGrade.DAI_JI && revealFraction > 0.65f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                repeat(12) { index ->
                    val x = size.width * ((index % 4 + 1) / 5f)
                    val y = size.height * (0.58f + (index / 4) * 0.12f)
                    drawLine(
                        color = visual.accent.copy(alpha = 0.52f),
                        start = Offset(x - 8f, y),
                        end = Offset(x + 8f, y),
                        strokeWidth = 2.5f,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = visual.accent.copy(alpha = 0.52f),
                        start = Offset(x, y - 8f),
                        end = Offset(x, y + 8f),
                        strokeWidth = 2.5f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
private fun DomainFortuneRow(label: String, grade: FortuneGrade) {
    val visual = visualFor(grade)
    val infinite = rememberInfiniteTransition(label = "domain-${grade.name}")
    val shimmer by infinite.animateFloat(
        initialValue = if (grade == FortuneGrade.DAI_XIONG) 0.55f else 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (grade == FortuneGrade.DAI_JI) 520 else 1_100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "domain-shimmer",
    )
    val background = when (grade) {
        FortuneGrade.DAI_XIONG -> Brush.horizontalGradient(
            listOf(Color(0xFF171515), Color(0xFF050505), Color(0xFF171515)),
        )
        FortuneGrade.DAI_JI -> Brush.horizontalGradient(
            listOf(Color(0xFFFFE49A), Color(0xFFFFF8D6), Color(0xFFFFC84D)),
        )
        else -> Brush.horizontalGradient(
            listOf(
                visual.aura.copy(alpha = 0.08f + visual.glowStrength * 0.11f),
                Color.Transparent,
                visual.aura.copy(alpha = 0.05f + visual.glowStrength * 0.08f),
            ),
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(
            if (grade == FortuneGrade.DAI_JI || grade == FortuneGrade.DAI_XIONG) 1.4.dp else 0.6.dp,
            visual.accent.copy(alpha = if (grade == FortuneGrade.DAI_JI) shimmer else 0.55f),
        ),
    ) {
        Row(
            modifier = Modifier.background(background).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = if (grade == FortuneGrade.DAI_XIONG) Color(0xFF8B8480) else Color(0xFF48241B),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Text(
                text = grade.label,
                color = when (grade) {
                    FortuneGrade.DAI_XIONG -> Color(0xFFB1AAA5).copy(alpha = shimmer)
                    FortuneGrade.DAI_JI -> Color(0xFF9D4B00)
                    else -> visual.accent
                },
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Black,
                fontSize = if (grade == FortuneGrade.DAI_JI || grade == FortuneGrade.DAI_XIONG) 22.sp else 19.sp,
                letterSpacing = 2.sp,
            )
        }
    }
}

@Composable
private fun OverallFortuneSeal(grade: FortuneGrade) {
    val visual = visualFor(grade)
    val infinite = rememberInfiniteTransition(label = "overall-${grade.name}")
    val pulse by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (grade == FortuneGrade.DAI_JI) 430 else 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "overall-pulse",
    )
    val extreme = grade == FortuneGrade.DAI_JI || grade == FortuneGrade.DAI_XIONG
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (extreme) 122.dp else 104.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when (grade) {
                    FortuneGrade.DAI_XIONG -> Brush.radialGradient(
                        listOf(Color(0xFF2D0708), Color(0xFF090909), Color.Black),
                    )
                    FortuneGrade.DAI_JI -> Brush.horizontalGradient(
                        listOf(Color(0xFFFFC933), Color(0xFFFFF4B3), Color(0xFFFFB51E)),
                    )
                    else -> Brush.horizontalGradient(visual.panelGradient)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (grade == FortuneGrade.DAI_JI) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                repeat(24) { index ->
                    val angle = Math.toRadians(index * 15.0)
                    val inner = 42f
                    val outer = 94f + pulse * 24f
                    drawLine(
                        color = Color.White.copy(alpha = 0.16f + pulse * 0.26f),
                        start = Offset(
                            center.x + cos(angle).toFloat() * inner,
                            center.y + sin(angle).toFloat() * inner,
                        ),
                        end = Offset(
                            center.x + cos(angle).toFloat() * outer,
                            center.y + sin(angle).toFloat() * outer,
                        ),
                        strokeWidth = 3f,
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "總體運勢",
                color = when (grade) {
                    FortuneGrade.DAI_XIONG -> Color(0xFF7A6F6A)
                    FortuneGrade.DAI_JI -> Color(0xFF8D4500)
                    else -> visual.secondaryText
                },
                fontFamily = FontFamily.Serif,
                fontSize = 13.sp,
                letterSpacing = 3.sp,
            )
            Text(
                text = grade.label,
                modifier = Modifier.padding(top = 2.dp).graphicsLayer {
                    if (grade == FortuneGrade.DAI_JI) {
                        scaleX = 0.98f + pulse * 0.035f
                        scaleY = 0.98f + pulse * 0.035f
                    }
                },
                color = when (grade) {
                    FortuneGrade.DAI_XIONG -> Color(0xFFB2AAA4).copy(alpha = 0.62f + pulse * 0.22f)
                    FortuneGrade.DAI_JI -> Color(0xFF8E3D00)
                    else -> visual.primaryText
                },
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Black,
                fontSize = if (extreme) 50.sp else 42.sp,
                letterSpacing = 6.sp,
            )
        }
    }
}

@Composable
private fun TempleHeader(subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "今日運勢",
            color = TempleBrightGold,
            fontFamily = FontFamily.Serif,
            fontSize = 34.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 4.sp,
        )
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(0.52f)
                .height(1.dp)
                .background(TempleGold.copy(alpha = 0.62f)),
        )
        Text(
            text = subtitle,
            modifier = Modifier.padding(top = 10.dp),
            color = TempleMutedPaper,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TempleBackdrop(grade: FortuneGrade?) {
    val visual = grade?.let(::visualFor)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (visual == null) {
                    Brush.verticalGradient(listOf(TempleInk, TempleDeepRed, Color(0xFF050302)))
                } else {
                    Brush.verticalGradient(visual.backdropGradient)
                },
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gold = TempleGold.copy(alpha = 0.16f)
            val y = size.height * 0.075f
            drawLine(gold, Offset(0f, y), Offset(size.width * 0.5f, 0f), 4f)
            drawLine(gold, Offset(size.width, y), Offset(size.width * 0.5f, 0f), 4f)
            drawLine(gold, Offset(size.width * 0.055f, 0f), Offset(size.width * 0.055f, size.height), 2f)
            drawLine(gold, Offset(size.width * 0.945f, 0f), Offset(size.width * 0.945f, size.height), 2f)
        }
    }
}

private data class FortuneVisual(
    val backdropGradient: List<Color>,
    val panelGradient: List<Color>,
    val accent: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val aura: Color,
    val glowStrength: Float,
)

private fun visualFor(grade: FortuneGrade): FortuneVisual = when (grade) {
    FortuneGrade.DAI_XIONG -> FortuneVisual(
        backdropGradient = listOf(Color.Black, Color(0xFF100708), Color(0xFF020202)),
        panelGradient = listOf(Color(0xFF141313), Color(0xFF050505)),
        accent = Color(0xFF5F5753),
        primaryText = Color(0xFFB2AAA4),
        secondaryText = Color(0xFF746C68),
        aura = Color(0xFF180405),
        glowStrength = 0.02f,
    )
    FortuneGrade.XIONG -> FortuneVisual(
        listOf(Color(0xFF130607), Color(0xFF23090A), Color(0xFF070404)),
        listOf(Color(0xFF281112), Color(0xFF130A0A)), Color(0xFF98675E),
        Color(0xFFCBB0A7), Color(0xFF9C8882), Color(0xFF612C2A), 0.13f,
    )
    FortuneGrade.XIAO_XIONG -> FortuneVisual(
        listOf(Color(0xFF25100E), Color(0xFF3A1511), Color(0xFF120807)),
        listOf(Color(0xFF401B16), Color(0xFF24110E)), Color(0xFFB77C5E),
        Color(0xFFDEC0A7), Color(0xFFB59D8E), Color(0xFF8B4935), 0.25f,
    )
    FortuneGrade.PING -> FortuneVisual(
        listOf(TempleInk, TempleDeepRed, Color(0xFF110807)),
        listOf(Color(0xFF5A1A14), Color(0xFF35100E)), Color(0xFFC3A069),
        TemplePaper, TempleMutedPaper, TempleGold, 0.36f,
    )
    FortuneGrade.XIAO_JI -> FortuneVisual(
        listOf(Color(0xFF3D0908), Color(0xFF741710), Color(0xFF190806)),
        listOf(Color(0xFF7A2117), Color(0xFF4A130D)), Color(0xFFE3B75B),
        Color(0xFFFFE7AE), Color(0xFFE1C793), Color(0xFFE6B64D), 0.55f,
    )
    FortuneGrade.JI -> FortuneVisual(
        listOf(Color(0xFF5B0D08), Color(0xFFA12C15), Color(0xFF271006)),
        listOf(Color(0xFFA8371D), Color(0xFF64150E)), Color(0xFFFFD36B),
        Color(0xFFFFF1BE), Color(0xFFF2D79D), Color(0xFFFFC846), 0.8f,
    )
    FortuneGrade.DAI_JI -> FortuneVisual(
        backdropGradient = listOf(Color(0xFF7D1307), Color(0xFFC63B15), Color(0xFF6A2108), Color(0xFF301452)),
        panelGradient = listOf(Color(0xFFFFC73A), Color(0xFFFFE990), Color(0xFFE67B18)),
        accent = Color(0xFFFFC400),
        primaryText = Color.White,
        secondaryText = Color(0xFFFFE9AC),
        aura = Color(0xFFFFE55E),
        glowStrength = 1f,
    )
}
