package com.treepolo.dailyfortune.ui

import android.graphics.Typeface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.FortuneDraw
import com.treepolo.dailyfortune.model.FortuneGrade
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val TempleInk = Color(0xFF120A08)
private val TempleDeepRed = Color(0xFF3A0907)
private val TempleRed = Color(0xFF77140F)
private val TempleCinnabar = Color(0xFFA92A1D)
private val TempleGold = Color(0xFFD7A53C)
private val TempleBrightGold = Color(0xFFFFDF78)
private val Paper = Color(0xFFF4E7C7)
private val PaperShade = Color(0xFFE3D0A8)
private val PaperInk = Color(0xFF2B2119)
private val MutedInk = Color(0xFF735F47)
private val Bamboo = Color(0xFFDDBD77)
private val BambooDark = Color(0xFF6A431F)

// Prefer the actual DFKai-SB system family when a device provides it. Android falls
// back to its platform CJK typeface if the family is unavailable.
private val KaiFont = FontFamily(Typeface.create("DFKai-SB", Typeface.NORMAL))

private const val SUSPENSE_MILLIS = 2_350L
private const val STICK_COMMIT_THRESHOLD = 0.72f
private const val PAPER_COMMIT_THRESHOLD = 0.975f

private enum class RitualStage {
    INITIALIZING,
    DRAW,
    SUSPENSE,
    PAPER,
}

@Composable
fun DailyFortuneRoot(viewModel: FortuneViewModel) {
    val state by viewModel.uiState.collectAsState()
    val colors = darkColorScheme(
        primary = TempleGold,
        onPrimary = TempleInk,
        background = TempleInk,
        onBackground = Paper,
        surface = TempleDeepRed,
        onSurface = Paper,
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
    var paperProgress by remember { mutableFloatStateOf(0f) }
    var paperCompleted by remember { mutableStateOf(false) }
    var ritualNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.isLoading, state.currentDraw?.id) {
        if (stage == RitualStage.INITIALIZING && !state.isLoading) {
            displayedDraw = state.currentDraw
            if (state.currentDraw == null) {
                stage = RitualStage.DRAW
            } else {
                paperProgress = 1f
                paperCompleted = true
                stage = RitualStage.PAPER
            }
        }
    }

    LaunchedEffect(state.currentDraw?.id, stage, state.errorMessage, ritualNonce) {
        if (stage != RitualStage.SUSPENSE) return@LaunchedEffect
        if (state.errorMessage != null) {
            stage = RitualStage.DRAW
            return@LaunchedEffect
        }
        val incoming = state.currentDraw ?: return@LaunchedEffect
        if (incoming.id == requestBaseId) return@LaunchedEffect
        pendingDraw = incoming
        delay(SUSPENSE_MILLIS)
        paperProgress = 0f
        paperCompleted = false
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        stage = RitualStage.PAPER
    }

    fun armReroll() {
        rerollFlow = true
        pendingDraw = null
        paperProgress = 0f
        paperCompleted = false
        stage = RitualStage.DRAW
    }

    fun commitStickPull() {
        if (stage != RitualStage.DRAW) return
        ritualNonce += 1
        requestBaseId = state.currentDraw?.id
        stage = RitualStage.SUSPENSE
        if (rerollFlow) onReroll() else onInitialDraw()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        DistantTempleBackdrop()
        AnimatedContent(
            targetState = stage,
            transitionSpec = {
                fadeIn(tween(330, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(260, easing = FastOutSlowInEasing))
            },
            label = "ritual-stage",
        ) { currentStage ->
            when (currentStage) {
                RitualStage.INITIALIZING -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = TempleGold)
                }
                RitualStage.DRAW -> DrawStage(
                    isReroll = rerollFlow,
                    enabled = !state.isLoading,
                    onStickCommitted = ::commitStickPull,
                )
                RitualStage.SUSPENSE -> SuspenseStage(ritualNonce = ritualNonce)
                RitualStage.PAPER -> {
                    val draw = pendingDraw ?: displayedDraw ?: state.currentDraw
                    if (draw != null) {
                        PaperStage(
                            draw = draw,
                            progress = paperProgress,
                            completed = paperCompleted,
                            onProgress = { paperProgress = it },
                            onCompleted = {
                                paperProgress = 1f
                                paperCompleted = true
                                displayedDraw = draw
                                pendingDraw = null
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onReroll = ::armReroll,
                        )
                    }
                }
            }
        }

        state.errorMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
                color = Paper,
                fontFamily = KaiFont,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DistantTempleBackdrop() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0B0909),
                        Color(0xFF17100D),
                        Color(0xFF24130F),
                        Color(0xFF100908),
                    ),
                ),
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val horizon = h * 0.40f

            // A small, low-contrast temple silhouette sits well behind the interface.
            // It deliberately avoids the old full-width top rules that read like a funeral frame.
            val haze = Color(0xFFB56D3B).copy(alpha = 0.07f)
            drawCircle(haze, radius = w * 0.42f, center = Offset(w * 0.50f, h * 0.27f))

            val silhouette = Color(0xFF5F2A1D).copy(alpha = 0.20f)
            val darkSilhouette = Color(0xFF2B1511).copy(alpha = 0.48f)

            val roof = Path().apply {
                moveTo(w * 0.25f, horizon)
                quadraticTo(w * 0.34f, h * 0.33f, w * 0.43f, h * 0.35f)
                lineTo(w * 0.50f, h * 0.30f)
                lineTo(w * 0.57f, h * 0.35f)
                quadraticTo(w * 0.66f, h * 0.33f, w * 0.75f, horizon)
                lineTo(w * 0.67f, h * 0.39f)
                lineTo(w * 0.57f, h * 0.40f)
                lineTo(w * 0.50f, h * 0.36f)
                lineTo(w * 0.43f, h * 0.40f)
                lineTo(w * 0.33f, h * 0.39f)
                close()
            }
            drawPath(roof, silhouette)
            drawRect(
                darkSilhouette,
                topLeft = Offset(w * 0.34f, horizon),
                size = Size(w * 0.32f, h * 0.13f),
            )
            repeat(4) { index ->
                val x = w * (0.39f + index * 0.073f)
                drawRect(
                    Color(0xFF8D3A24).copy(alpha = 0.13f),
                    topLeft = Offset(x, horizon + h * 0.015f),
                    size = Size(w * 0.025f, h * 0.105f),
                )
            }
            drawOval(
                Color.Black.copy(alpha = 0.24f),
                topLeft = Offset(-w * 0.15f, h * 0.47f),
                size = Size(w * 1.30f, h * 0.26f),
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
            fontFamily = KaiFont,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp,
            style = TextStyle(
                shadow = Shadow(Color.Black.copy(alpha = 0.75f), Offset(0f, 3f), 6f),
            ),
        )
        Text(
            text = subtitle,
            modifier = Modifier.padding(top = 9.dp),
            color = Paper.copy(alpha = 0.90f),
            fontFamily = KaiFont,
            fontSize = 16.sp,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DrawStage(
    isReroll: Boolean,
    enabled: Boolean,
    onStickCommitted: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TempleHeader(
            subtitle = if (isReroll) "再抽一籤，逆天改命" else "親手抽出今天的命數",
        )
        Spacer(modifier = Modifier.height(24.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            InteractiveHexFortuneTube(
                enabled = enabled,
                onStickCommitted = onStickCommitted,
            )
        }
        Text(
            text = "按住中央籤，往上抽出",
            modifier = Modifier.padding(top = 10.dp),
            color = TempleBrightGold,
            fontFamily = KaiFont,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            style = TextStyle(
                shadow = Shadow(Color.Black.copy(alpha = 0.70f), Offset(0f, 2f), 5f),
            ),
        )
        Text(
            text = "抽離籤筒後，命數才會落定",
            modifier = Modifier.padding(top = 8.dp),
            color = Paper.copy(alpha = 0.80f),
            fontFamily = KaiFont,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun InteractiveHexFortuneTube(
    enabled: Boolean,
    onStickCommitted: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var committed by remember { mutableStateOf(false) }
    val smoothProgress by animateFloatAsState(
        targetValue = dragProgress,
        animationSpec = tween(90, easing = FastOutSlowInEasing),
        label = "stick-pull",
    )

    Box(
        modifier = Modifier.size(width = 246.dp, height = 344.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Rear sticks first: they belong inside the tube and therefore stay behind its rim/body.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            repeat(7) { index ->
                val x = w * (0.29f + index * 0.07f)
                val top = h * (0.11f + (index % 3) * 0.018f)
                drawRoundRect(
                    color = if (index % 2 == 0) Bamboo else Color(0xFFC79B58),
                    topLeft = Offset(x, top),
                    size = Size(w * 0.045f, h * 0.52f),
                    cornerRadius = CornerRadius(w * 0.02f),
                )
            }
        }

        // The selected stick is drawn after rear sticks but BEFORE the tube face.
        // The tube therefore occludes its lower half correctly while the exposed tip can be dragged out.
        Box(
            modifier = Modifier
                .offset(y = (-132f * smoothProgress).dp)
                .padding(top = 17.dp)
                .size(width = 34.dp, height = 222.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(BambooDark, TempleBrightGold, Bamboo, BambooDark),
                    ),
                )
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { committed = false },
                        onDragEnd = {
                            if (dragProgress >= STICK_COMMIT_THRESHOLD && !committed) {
                                committed = true
                                dragProgress = 1f
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch {
                                    // Let the physical pull finish before cross-fading into suspense.
                                    delay(240L)
                                    onStickCommitted()
                                }
                            } else if (!committed) {
                                dragProgress = 0f
                            }
                        },
                        onDragCancel = { if (!committed) dragProgress = 0f },
                    ) { change, amount ->
                        change.consume()
                        if (!committed) {
                            dragProgress = (dragProgress - amount.y / 190f).coerceIn(0f, 1f)
                        }
                    }
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = "籤",
                modifier = Modifier.padding(top = 10.dp),
                color = TempleDeepRed,
                fontFamily = KaiFont,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
            )
        }

        // Hexagonal-prism tube foreground. This is intentionally drawn last so its
        // opening and front faces sit in front of every stick that remains inside.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val topY = h * 0.42f
            val bottomY = h * 0.94f

            val body = Path().apply {
                moveTo(w * 0.22f, topY)
                lineTo(w * 0.78f, topY)
                lineTo(w * 0.86f, h * 0.84f)
                lineTo(w * 0.70f, bottomY)
                lineTo(w * 0.30f, bottomY)
                lineTo(w * 0.14f, h * 0.84f)
                close()
            }
            drawPath(
                body,
                brush = Brush.verticalGradient(
                    listOf(Color(0xFFB63825), Color(0xFF72170F), Color(0xFF3B0907)),
                    startY = topY,
                    endY = bottomY,
                ),
            )

            val leftFace = Path().apply {
                moveTo(w * 0.22f, topY)
                lineTo(w * 0.34f, h * 0.47f)
                lineTo(w * 0.36f, h * 0.90f)
                lineTo(w * 0.30f, bottomY)
                lineTo(w * 0.14f, h * 0.84f)
                close()
            }
            drawPath(leftFace, Color(0xFF53100C).copy(alpha = 0.70f))

            val rightFace = Path().apply {
                moveTo(w * 0.78f, topY)
                lineTo(w * 0.66f, h * 0.47f)
                lineTo(w * 0.64f, h * 0.90f)
                lineTo(w * 0.70f, bottomY)
                lineTo(w * 0.86f, h * 0.84f)
                close()
            }
            drawPath(rightFace, Color(0xFF8D2116).copy(alpha = 0.52f))

            drawPath(body, TempleGold.copy(alpha = 0.92f), style = Stroke(3.5f))

            val opening = Path().apply {
                moveTo(w * 0.22f, topY)
                lineTo(w * 0.34f, h * 0.375f)
                lineTo(w * 0.66f, h * 0.375f)
                lineTo(w * 0.78f, topY)
                lineTo(w * 0.66f, h * 0.47f)
                lineTo(w * 0.34f, h * 0.47f)
                close()
            }
            drawPath(opening, Color(0xFF170503).copy(alpha = 0.97f))
            drawPath(opening, TempleBrightGold.copy(alpha = 0.92f), style = Stroke(3.2f))

            drawLine(
                color = TempleBrightGold.copy(alpha = 0.75f),
                start = Offset(w * 0.28f, h * 0.56f),
                end = Offset(w * 0.72f, h * 0.56f),
                strokeWidth = 3.5f,
                cap = StrokeCap.Round,
            )
        }

        Text(
            text = "籤",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 46.dp),
            color = TempleBrightGold,
            fontFamily = KaiFont,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle(
                shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 3f), 5f),
            ),
        )
    }
}

@Composable
private fun SuspenseStage(ritualNonce: Int) {
    val progress = remember(ritualNonce) { Animatable(0f) }
    val infinite = rememberInfiniteTransition(label = "suspense-pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(430, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "suspense-pulse-value",
    )
    LaunchedEffect(ritualNonce) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(SUSPENSE_MILLIS.toInt(), easing = FastOutSlowInEasing))
    }

    val p = progress.value
    val stickAlpha = (1f - (p - 0.36f).coerceAtLeast(0f) / 0.36f).coerceIn(0f, 1f)
    val sealAlpha = ((p - 0.16f) / 0.48f).coerceIn(0f, 1f)
    val lateGlow = ((p - 0.56f) / 0.44f).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(
                        Color(0xFF6A1710).copy(alpha = 0.90f),
                        Color(0xFF2E0A08).copy(alpha = 0.96f),
                        Color(0xFF090706),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            repeat(28) { index ->
                val angle = 2.0 * PI * index / 28.0
                val inner = size.minDimension * (0.13f + lateGlow * 0.03f)
                val outer = size.minDimension * (0.25f + lateGlow * 0.15f * pulse)
                drawLine(
                    color = TempleGold.copy(alpha = (0.03f + lateGlow * 0.23f) * pulse),
                    start = Offset(
                        center.x + cos(angle).toFloat() * inner,
                        center.y + sin(angle).toFloat() * inner,
                    ),
                    end = Offset(
                        center.x + cos(angle).toFloat() * outer,
                        center.y + sin(angle).toFloat() * outer,
                    ),
                    strokeWidth = if (index % 4 == 0) 3f else 1.2f,
                    cap = StrokeCap.Round,
                )
            }
        }

        // Continue the same visual object that the user just pulled: the stick rises,
        // slows, and dissolves into the reveal seal instead of cutting to an unrelated screen.
        Box(
            modifier = Modifier
                .offset(y = (80f - p * 150f).dp)
                .graphicsLayer {
                    alpha = stickAlpha
                    scaleX = 0.96f + p * 0.08f
                    scaleY = 0.96f + p * 0.08f
                }
                .size(width = 34.dp, height = 224.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(BambooDark, TempleBrightGold, Bamboo, BambooDark),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .graphicsLayer {
                    alpha = sealAlpha
                    scaleX = 0.72f + sealAlpha * 0.28f
                    scaleY = 0.72f + sealAlpha * 0.28f
                }
                .size(154.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = TempleGold.copy(alpha = 0.24f + lateGlow * 0.30f),
                    radius = size.minDimension * (0.44f + lateGlow * 0.03f * pulse),
                    style = Stroke(5f),
                )
                drawCircle(
                    color = TempleBrightGold.copy(alpha = 0.15f + lateGlow * 0.22f),
                    radius = size.minDimension * 0.34f,
                    style = Stroke(2f),
                )
            }
            Text(
                text = "命",
                color = TempleBrightGold,
                fontFamily = KaiFont,
                fontSize = 68.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(
                    shadow = Shadow(Color.Black.copy(alpha = 0.82f), Offset(0f, 4f), 8f),
                ),
            )
        }

        AnimatedContent(
            targetState = when {
                p < 0.34f -> 0
                p < 0.70f -> 1
                else -> 2
            },
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 146.dp),
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(220)) },
            label = "suspense-copy",
        ) { beat ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when (beat) {
                        0 -> "籤已離筒"
                        1 -> "五運正在落定"
                        else -> "命數已定"
                    },
                    color = TempleBrightGold,
                    fontFamily = KaiFont,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                )
                Text(
                    text = when (beat) {
                        0 -> "先別急……"
                        1 -> "再等一瞬……"
                        else -> "親手揭開"
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    color = Paper.copy(alpha = 0.84f),
                    fontFamily = KaiFont,
                    fontSize = 15.sp,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

@Composable
private fun PaperStage(
    draw: FortuneDraw,
    progress: Float,
    completed: Boolean,
    onProgress: (Float) -> Unit,
    onCompleted: () -> Unit,
    onReroll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TempleHeader(
            subtitle = if (completed) "今日籤意已現" else "按住籤紙下緣，往下慢慢揭開",
        )
        Spacer(modifier = Modifier.height(20.dp))
        FortunePaper(
            draw = draw,
            revealFraction = progress,
            interactive = !completed,
            onRevealDelta = { delta -> onProgress((progress + delta).coerceIn(0f, 1f)) },
            onRevealEnd = {
                if (progress >= PAPER_COMMIT_THRESHOLD) onCompleted()
            },
        )
        if (completed) {
            Spacer(modifier = Modifier.height(18.dp))
            Button(
                onClick = onReroll,
                modifier = Modifier
                    .widthIn(max = 236.dp)
                    .fillMaxWidth(0.64f)
                    .height(54.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, TempleBrightGold.copy(alpha = 0.90f)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TempleCinnabar,
                    contentColor = Paper,
                ),
            ) {
                Text(
                    text = "逆天改命!!",
                    fontFamily = KaiFont,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
            }
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
    val density = LocalDensity.current
    val minHeightDp = 76.dp
    val fullHeightDp = 594.dp
    val travelPx = with(density) { (fullHeightDp - minHeightDp).toPx() }
    val visibleHeightDp = minHeightDp + (fullHeightDp - minHeightDp) * revealFraction
    val latestDelta by rememberUpdatedState(onRevealDelta)
    val latestEnd by rememberUpdatedState(onRevealEnd)

    Box(
        modifier = Modifier
            .widthIn(max = 228.dp)
            .fillMaxWidth(0.62f)
            .height(visibleHeightDp)
            .clip(RoundedCornerShape(6.dp))
            .clipToBounds()
            .background(
                Brush.horizontalGradient(
                    listOf(PaperShade, Paper, Color(0xFFF8EDCF), Paper, PaperShade),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(fullHeightDp)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "今 日 籤",
                color = TempleDeepRed,
                fontFamily = KaiFont,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
            )
            Box(
                modifier = Modifier
                    .padding(top = 7.dp, bottom = 7.dp)
                    .fillMaxWidth(0.72f)
                    .height(1.dp)
                    .background(TempleRed.copy(alpha = 0.32f)),
            )
            FortuneDomain.entries.forEach { domain ->
                val grade = FortuneGrade.fromScore(draw.domainScores.getValue(domain))
                DomainFortuneRow(label = domain.label, grade = grade)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "總體",
                color = MutedInk,
                fontFamily = KaiFont,
                fontSize = 14.sp,
                letterSpacing = 2.sp,
            )
            GradeMark(
                grade = draw.overallGrade,
                large = true,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (interactive) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, PaperShade.copy(alpha = 0.94f)),
                        ),
                    )
                    .pointerInput(interactive, travelPx) {
                        detectDragGestures(
                            onDragEnd = { latestEnd() },
                            onDragCancel = { latestEnd() },
                        ) { change, amount ->
                            change.consume()
                            // 1 px of finger movement changes the paper edge by 1 px.
                            // No artificial multiplier: the lower edge remains physically attached to the finger.
                            latestDelta(amount.y / travelPx)
                        }
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .size(width = 74.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(TempleRed.copy(alpha = 0.46f)),
                )
            }
        }
    }
}

@Composable
private fun DomainFortuneRow(label: String, grade: FortuneGrade) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = PaperInk,
            fontFamily = KaiFont,
            fontSize = if (label.length > 4) 14.sp else 16.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
        )
        GradeMark(grade = grade, large = false)
    }
}

private data class GradeVisual(
    val text: Color,
    val aura: Color,
    val secondary: Color,
    val strength: Float,
    val positive: Boolean,
    val extreme: Boolean,
)

private fun gradeVisual(grade: FortuneGrade): GradeVisual = when (grade) {
    FortuneGrade.DAI_XIONG -> GradeVisual(
        text = Color(0xFF170707),
        aura = Color(0xFF250000),
        secondary = Color(0xFF7B0D0D),
        strength = 1f,
        positive = false,
        extreme = true,
    )
    FortuneGrade.XIONG -> GradeVisual(
        text = Color(0xFF4C1110),
        aura = Color(0xFF3C0908),
        secondary = Color(0xFF8E2A22),
        strength = 0.72f,
        positive = false,
        extreme = false,
    )
    FortuneGrade.XIAO_XIONG -> GradeVisual(
        text = Color(0xFF713026),
        aura = Color(0xFF5A241D),
        secondary = Color(0xFFA45C4C),
        strength = 0.42f,
        positive = false,
        extreme = false,
    )
    FortuneGrade.PING -> GradeVisual(
        text = Color(0xFF594735),
        aura = Color(0xFF8C6F45),
        secondary = Color(0xFF9C7A4F),
        strength = 0.16f,
        positive = true,
        extreme = false,
    )
    FortuneGrade.XIAO_JI -> GradeVisual(
        text = Color(0xFF9A5C08),
        aura = Color(0xFFC89020),
        secondary = Color(0xFFE4B84E),
        strength = 0.42f,
        positive = true,
        extreme = false,
    )
    FortuneGrade.JI -> GradeVisual(
        text = Color(0xFFB26800),
        aura = Color(0xFFE7AC28),
        secondary = Color(0xFFFFD86B),
        strength = 0.72f,
        positive = true,
        extreme = false,
    )
    FortuneGrade.DAI_JI -> GradeVisual(
        text = Color(0xFFC97700),
        aura = Color(0xFFFFC21F),
        secondary = Color(0xFFFFED96),
        strength = 1f,
        positive = true,
        extreme = true,
    )
}

@Composable
private fun GradeMark(
    grade: FortuneGrade,
    large: Boolean,
    modifier: Modifier = Modifier,
) {
    val visual = gradeVisual(grade)
    val infinite = rememberInfiniteTransition(label = "grade-${grade.name}")
    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (grade) {
                    FortuneGrade.DAI_JI -> 780
                    FortuneGrade.DAI_XIONG -> 1_180
                    else -> 1_500
                },
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "grade-effect-progress",
    )
    val breathe by infinite.animateFloat(
        initialValue = 0.88f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (visual.extreme) 560 else 1_050, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "grade-breathe",
    )

    val width = if (large) 154.dp else 92.dp
    val height = if (large) 96.dp else 54.dp

    Box(
        modifier = modifier.size(width = width, height = height),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val minDim = size.minDimension

            if (grade == FortuneGrade.PING) {
                drawCircle(
                    color = visual.aura.copy(alpha = 0.14f),
                    radius = minDim * 0.32f,
                    center = center,
                    style = Stroke(1.5f),
                )
            } else if (visual.positive) {
                val rayCount = when (grade) {
                    FortuneGrade.XIAO_JI -> 6
                    FortuneGrade.JI -> 12
                    FortuneGrade.DAI_JI -> 20
                    else -> 0
                }
                val haloAlpha = (0.08f + visual.strength * 0.18f) * breathe
                drawCircle(
                    color = visual.aura.copy(alpha = haloAlpha),
                    radius = minDim * (0.30f + 0.05f * breathe),
                    center = center,
                    style = Stroke(if (visual.extreme) 5f else 2.5f),
                )
                if (visual.extreme) {
                    drawCircle(
                        color = visual.secondary.copy(alpha = 0.18f * breathe),
                        radius = minDim * (0.40f + 0.04f * breathe),
                        center = center,
                        style = Stroke(2f),
                    )
                }
                repeat(rayCount) { index ->
                    val angle = 2.0 * PI * index / rayCount + pulse * 0.22
                    val inner = minDim * 0.26f
                    val outer = minDim * (0.35f + visual.strength * 0.12f * breathe)
                    drawLine(
                        color = visual.aura.copy(alpha = 0.13f + visual.strength * 0.26f),
                        start = Offset(
                            center.x + cos(angle).toFloat() * inner,
                            center.y + sin(angle).toFloat() * inner,
                        ),
                        end = Offset(
                            center.x + cos(angle).toFloat() * outer,
                            center.y + sin(angle).toFloat() * outer,
                        ),
                        strokeWidth = if (visual.extreme && index % 2 == 0) 3f else 1.5f,
                        cap = StrokeCap.Round,
                    )
                }
                val sparks = when (grade) {
                    FortuneGrade.XIAO_JI -> 3
                    FortuneGrade.JI -> 6
                    FortuneGrade.DAI_JI -> 12
                    else -> 0
                }
                repeat(sparks) { index ->
                    val angle = 2.0 * PI * index / sparks + 0.45
                    val radius = minDim * (0.30f + ((pulse + index * 0.13f) % 1f) * 0.18f)
                    val x = center.x + cos(angle).toFloat() * radius
                    val y = center.y + sin(angle).toFloat() * radius
                    val sparkColor = if (grade == FortuneGrade.DAI_JI) {
                        listOf(
                            Color(0xFFFFD84F),
                            Color(0xFFFF8ED7),
                            Color(0xFF8EEBFF),
                            Color(0xFFD5A6FF),
                        )[index % 4]
                    } else {
                        visual.secondary
                    }
                    drawLine(
                        sparkColor.copy(alpha = 0.42f + 0.38f * breathe),
                        Offset(x - 4f, y),
                        Offset(x + 4f, y),
                        strokeWidth = 1.8f,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        sparkColor.copy(alpha = 0.42f + 0.38f * breathe),
                        Offset(x, y - 4f),
                        Offset(x, y + 4f),
                        strokeWidth = 1.8f,
                        cap = StrokeCap.Round,
                    )
                }
            } else {
                val ashCount = when (grade) {
                    FortuneGrade.XIAO_XIONG -> 3
                    FortuneGrade.XIONG -> 6
                    FortuneGrade.DAI_XIONG -> 11
                    else -> 0
                }
                repeat(ashCount) { index ->
                    val x = size.width * (0.18f + (index % 5) * 0.16f)
                    val phase = (pulse + index * 0.17f) % 1f
                    val y = size.height * (0.25f + phase * 0.62f)
                    val radius = if (grade == FortuneGrade.DAI_XIONG) 3.2f else 2.2f
                    drawCircle(
                        color = visual.aura.copy(alpha = 0.10f + visual.strength * 0.24f * (1f - phase)),
                        radius = radius,
                        center = Offset(x, y),
                    )
                }
                if (grade == FortuneGrade.XIONG || grade == FortuneGrade.DAI_XIONG) {
                    repeat(if (grade == FortuneGrade.DAI_XIONG) 5 else 2) { index ->
                        val x = size.width * (0.30f + index * 0.10f)
                        val y = size.height * (0.35f + index * 0.08f)
                        drawLine(
                            color = visual.secondary.copy(alpha = 0.20f + visual.strength * 0.18f),
                            start = Offset(x, y),
                            end = Offset(x - 8f, y + 10f),
                            strokeWidth = 1.3f,
                        )
                        drawLine(
                            color = visual.secondary.copy(alpha = 0.18f + visual.strength * 0.16f),
                            start = Offset(x - 8f, y + 10f),
                            end = Offset(x - 2f, y + 18f),
                            strokeWidth = 1.1f,
                        )
                    }
                }
                if (grade == FortuneGrade.DAI_XIONG) {
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.24f * breathe),
                        radius = minDim * 0.38f,
                        center = center,
                    )
                }
            }
        }

        val extremeJitter = if (grade == FortuneGrade.DAI_XIONG) sin(pulse * PI * 10).toFloat() * 1.8f else 0f
        Text(
            text = grade.label,
            modifier = Modifier.graphicsLayer {
                translationX = extremeJitter
                scaleX = if (grade == FortuneGrade.DAI_JI) 0.97f + 0.05f * breathe else 1f
                scaleY = if (grade == FortuneGrade.DAI_JI) 0.97f + 0.05f * breathe else 1f
            },
            color = visual.text,
            fontFamily = KaiFont,
            fontSize = if (large) 44.sp else 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = if (large) 3.sp else 1.sp,
            style = TextStyle(
                shadow = Shadow(
                    color = if (visual.positive) Color(0xFF4C2A00).copy(alpha = 0.42f) else Color.Black.copy(alpha = 0.38f),
                    offset = Offset(0f, if (large) 2f else 1f),
                    blurRadius = if (large) 3f else 1.5f,
                ),
            ),
        )
    }
}
