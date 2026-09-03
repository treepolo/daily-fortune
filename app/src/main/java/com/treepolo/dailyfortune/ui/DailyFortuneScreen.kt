package com.treepolo.dailyfortune.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.treepolo.dailyfortune.R
import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.FortuneDraw
import com.treepolo.dailyfortune.model.FortuneGrade
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val BackgroundInk = Color(0xFFB78059)
private val BackgroundRaised = Color(0xFF9E6A50)
private val LacquerRed = Color(0xFF8E2018)
private val LacquerRedDark = Color(0xFF5A120E)
private val LacquerRedSide = Color(0xFF6D1712)
private val GoldLine = Color(0xFFD9AA48)
private val GoldBright = Color(0xFFFFE09A)
private val Amber = Color(0xFFD98A10)
private val AmberDark = Color(0xFF784100)
private val AmberHighlight = Color(0xFFFFEDAE)
private val PaperBase = Color(0xFFF4E8CC)
private val PaperEdge = Color(0xFFCBB891)
private val PaperFiber = Color(0xFFB8A67F)
private val PaperInk = Color(0xFF2C241C)
private val PaperMuted = Color(0xFF735F48)
private val RollPaper = Color(0xFFEDE1C4)
private val RollEdge = Color(0xFFC8B68D)
private val RollInner = Color(0xFF8E7A58)

private val KaiFont = FontFamily(Font(R.font.tw_kai_98_1))

private const val SUSPENSE_MILLIS = 2_550L
private const val STICK_COMMIT_THRESHOLD = 0.74f
private const val PAPER_COMMIT_THRESHOLD = 0.975f

private enum class RitualStage {
    INITIALIZING,
    DRAW,
    SUSPENSE,
    PAPER,
}

private data class SlipPose(
    val x: Int,
    val y: Int,
    val rotation: Float,
)

@Composable
fun DailyFortuneRoot(viewModel: FortuneViewModel) {
    val state by viewModel.uiState.collectAsState()
    val colors = darkColorScheme(
        primary = GoldLine,
        onPrimary = PaperInk,
        background = BackgroundInk,
        onBackground = PaperBase,
        surface = LacquerRedDark,
        onSurface = PaperBase,
    )
    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = BackgroundInk) {
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
                fadeIn(tween(230, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(180, easing = FastOutSlowInEasing))
            },
            label = "ritual-stage",
        ) { currentStage ->
            when (currentStage) {
                RitualStage.INITIALIZING -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = GoldBright)
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
                color = PaperBase,
                fontFamily = KaiFont,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 1f), 3f),
                ),
            )
        }
    }
}

@Composable
private fun DistantTempleBackdrop() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundInk),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val horizon = size.height * 0.47f
            drawRect(
                color = BackgroundRaised.copy(alpha = 0.68f),
                topLeft = Offset(0f, horizon),
                size = Size(size.width, size.height - horizon),
            )
            drawLine(
                color = PaperBase.copy(alpha = 0.08f),
                start = Offset(0f, horizon),
                end = Offset(size.width, horizon),
                strokeWidth = 1.5f,
            )
        }
    }
}

@Composable
private fun TempleHeader(
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "今日運勢",
            color = GoldBright,
            fontFamily = KaiFont,
            fontSize = 34.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 1.5.sp,
            style = TextStyle(
                shadow = Shadow(LacquerRedDark.copy(alpha = 0.75f), Offset(0f, 2f), 4f),
            ),
        )
        Text(
            text = subtitle,
            modifier = Modifier.padding(top = 7.dp),
            color = PaperBase,
            fontFamily = KaiFont,
            fontSize = 17.sp,
            letterSpacing = 0.4.sp,
            textAlign = TextAlign.Center,
            style = TextStyle(
                shadow = Shadow(Color.Black.copy(alpha = 0.42f), Offset(0f, 1f), 3f),
            ),
        )
    }
}

@Composable
private fun DrawStage(
    isReroll: Boolean,
    enabled: Boolean,
    onStickCommitted: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        TempleHeader(
            subtitle = if (isReroll) "再抽一籤，逆天改命" else "親手抽出今天的籤",
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "任選一支卷籤，往上抽出",
                color = GoldBright,
                fontFamily = KaiFont,
                fontSize = 19.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.6.sp,
                style = TextStyle(
                    shadow = Shadow(LacquerRedDark.copy(alpha = 0.55f), Offset(0f, 1f), 3f),
                ),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "抽離籤筒後，命數才會落定",
                color = PaperBase,
                fontFamily = KaiFont,
                fontSize = 14.sp,
                style = TextStyle(
                    shadow = Shadow(Color.Black.copy(alpha = 0.38f), Offset(0f, 1f), 2f),
                ),
            )
            Spacer(modifier = Modifier.height(8.dp))
            InteractiveFortuneTube(
                enabled = enabled,
                onStickCommitted = onStickCommitted,
            )
        }
    }
}

@Composable
private fun RolledPaperSlip(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 18.dp, height = 226.dp)) {
        drawRoundRect(
            color = RollPaper,
            topLeft = Offset.Zero,
            size = size,
            cornerRadius = CornerRadius(size.width * 0.48f, size.width * 0.48f),
        )
        drawLine(
            color = RollEdge.copy(alpha = 0.82f),
            start = Offset(size.width * 0.24f, size.height * 0.05f),
            end = Offset(size.width * 0.24f, size.height * 0.95f),
            strokeWidth = 1.2f,
        )
        drawOval(
            color = RollInner.copy(alpha = 0.72f),
            topLeft = Offset(size.width * 0.24f, size.height * 0.015f),
            size = Size(size.width * 0.52f, size.width * 0.20f),
        )
    }
}

@Composable
private fun SlipEmphasisLines(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(width = 64.dp, height = 48.dp)) {
        val alpha = if (active) 0.98f else 0.76f
        val stroke = if (active) 3.2f else 2.6f
        drawLine(
            color = GoldBright.copy(alpha = alpha),
            start = Offset(size.width * 0.28f, size.height * 0.88f),
            end = Offset(size.width * 0.10f, size.height * 0.14f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = GoldBright.copy(alpha = alpha),
            start = Offset(size.width * 0.50f, size.height * 0.80f),
            end = Offset(size.width * 0.50f, size.height * 0.02f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = GoldBright.copy(alpha = alpha),
            start = Offset(size.width * 0.72f, size.height * 0.88f),
            end = Offset(size.width * 0.90f, size.height * 0.14f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun InteractiveFortuneTube(
    enabled: Boolean,
    onStickCommitted: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var activeIndex by remember { mutableStateOf<Int?>(null) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var committed by remember { mutableStateOf(false) }

    // Resting positions are exactly the v0.6.3 arrangement the device review approved:
    // eight rear rolls first, then the slightly higher center roll drawn on top.
    val poses = remember {
        listOf(
            SlipPose(-70, 43, -8f),
            SlipPose(-50, 28, -4f),
            SlipPose(-29, 48, 3f),
            SlipPose(-12, 34, -2f),
            SlipPose(18, 45, 2f),
            SlipPose(37, 30, -3f),
            SlipPose(57, 47, 5f),
            SlipPose(72, 36, 8f),
            SlipPose(0, 20, 0f),
        )
    }
    val suggestedIndex = 8
    val focusIndex = activeIndex ?: suggestedIndex
    val focusPose = poses[focusIndex]
    val focusLift = if (activeIndex != null) 10f + 150f * dragProgress else 0f

    Box(
        modifier = Modifier.size(width = 228.dp, height = 360.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        SlipEmphasisLines(
            active = activeIndex != null,
            modifier = Modifier
                .offset(
                    x = focusPose.x.dp,
                    y = (focusPose.y - 52f - focusLift).dp,
                )
                .zIndex(0f),
        )

        poses.forEachIndexed { index, pose ->
            val isActive = activeIndex == index
            val lift = if (isActive) 10f + 150f * dragProgress else 0f
            val currentRotation = if (isActive) {
                pose.rotation * (1f - dragProgress * 0.78f)
            } else {
                pose.rotation
            }

            Box(
                modifier = Modifier
                    .offset(x = pose.x.dp, y = (pose.y - lift).dp)
                    .size(width = 42.dp, height = 236.dp)
                    .graphicsLayer { rotationZ = currentRotation }
                    .zIndex(if (isActive) 2f else 1f),
                contentAlignment = Alignment.TopCenter,
            ) {
                RolledPaperSlip()

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .size(width = 38.dp, height = 122.dp)
                        .pointerInput(enabled, index) {
                            if (!enabled) return@pointerInput
                            detectDragGestures(
                                onDragStart = {
                                    if (activeIndex == null || activeIndex == index) {
                                        activeIndex = index
                                        dragProgress = 0f
                                        committed = false
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                },
                                onDragEnd = {
                                    if (activeIndex == index && dragProgress >= STICK_COMMIT_THRESHOLD && !committed) {
                                        committed = true
                                        dragProgress = 1f
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        scope.launch {
                                            delay(190L)
                                            onStickCommitted()
                                        }
                                    } else if (!committed && activeIndex == index) {
                                        dragProgress = 0f
                                        activeIndex = null
                                    }
                                },
                                onDragCancel = {
                                    if (!committed && activeIndex == index) {
                                        dragProgress = 0f
                                        activeIndex = null
                                    }
                                },
                            ) { change, amount ->
                                change.consume()
                                if (!committed && (activeIndex == index || activeIndex == null)) {
                                    if (activeIndex == null) activeIndex = index
                                    dragProgress = (dragProgress - amount.y / 190f).coerceIn(0f, 1f)
                                }
                            }
                        },
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(3f),
        ) {
            val w = size.width
            val h = size.height
            val topY = h * 0.405f
            val bottomY = h * 0.97f
            val outerLeft = w * 0.13f
            val frontLeft = w * 0.24f
            val frontRight = w * 0.76f
            val outerRight = w * 0.87f

            val leftFace = Path().apply {
                moveTo(outerLeft, topY + h * 0.025f)
                lineTo(frontLeft, topY)
                lineTo(frontLeft, bottomY)
                lineTo(outerLeft, bottomY - h * 0.025f)
                close()
            }
            val frontFace = Path().apply {
                moveTo(frontLeft, topY)
                lineTo(frontRight, topY)
                lineTo(frontRight, bottomY)
                lineTo(frontLeft, bottomY)
                close()
            }
            val rightFace = Path().apply {
                moveTo(frontRight, topY)
                lineTo(outerRight, topY + h * 0.025f)
                lineTo(outerRight, bottomY - h * 0.025f)
                lineTo(frontRight, bottomY)
                close()
            }

            drawPath(leftFace, LacquerRedDark)
            drawPath(frontFace, LacquerRed)
            drawPath(rightFace, LacquerRedSide)

            drawLine(
                color = GoldLine.copy(alpha = 0.95f),
                start = Offset(outerLeft, topY + h * 0.025f),
                end = Offset(frontLeft, topY),
                strokeWidth = 3.0f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = GoldBright.copy(alpha = 0.95f),
                start = Offset(frontLeft, topY),
                end = Offset(frontRight, topY),
                strokeWidth = 3.2f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = GoldLine.copy(alpha = 0.95f),
                start = Offset(frontRight, topY),
                end = Offset(outerRight, topY + h * 0.025f),
                strokeWidth = 3.0f,
                cap = StrokeCap.Round,
            )
            drawPath(leftFace, GoldLine.copy(alpha = 0.62f), style = Stroke(1.8f))
            drawPath(frontFace, GoldLine.copy(alpha = 0.68f), style = Stroke(1.8f))
            drawPath(rightFace, GoldLine.copy(alpha = 0.62f), style = Stroke(1.8f))

            drawLine(
                color = GoldLine.copy(alpha = 0.64f),
                start = Offset(frontLeft + w * 0.06f, h * 0.55f),
                end = Offset(frontRight - w * 0.06f, h * 0.55f),
                strokeWidth = 2.0f,
                cap = StrokeCap.Round,
            )
        }

        Text(
            text = "籤",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 50.dp)
                .zIndex(4f),
            color = GoldBright,
            fontFamily = KaiFont,
            fontSize = 42.sp,
            fontWeight = FontWeight.Normal,
            style = TextStyle(
                shadow = Shadow(LacquerRedDark.copy(alpha = 0.72f), Offset(0f, 2f), 3f),
            ),
        )
    }
}

@Composable
private fun SuspenseStage(ritualNonce: Int) {
    val progress = remember(ritualNonce) { Animatable(0f) }
    val pulseTransition = rememberInfiniteTransition(label = "suspense-pulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "suspense-pulse-value",
    )

    LaunchedEffect(ritualNonce) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(SUSPENSE_MILLIS.toInt(), easing = FastOutSlowInEasing))
    }

    val p = progress.value
    val rollAlpha = 1f - ((p - 0.40f) / 0.22f).coerceIn(0f, 1f)
    val sealAlpha = ((p - 0.28f) / 0.36f).coerceIn(0f, 1f)
    val rayAlpha = ((p - 0.52f) / 0.48f).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundRaised),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height * 0.43f)
            repeat(24) { index ->
                val angle = 2.0 * PI * index / 24.0
                val inner = size.minDimension * 0.12f
                val outer = size.minDimension * (0.18f + 0.08f * pulse)
                drawLine(
                    color = GoldBright.copy(alpha = rayAlpha * if (index % 3 == 0) 0.40f else 0.18f),
                    start = Offset(
                        center.x + cos(angle).toFloat() * inner,
                        center.y + sin(angle).toFloat() * inner,
                    ),
                    end = Offset(
                        center.x + cos(angle).toFloat() * outer,
                        center.y + sin(angle).toFloat() * outer,
                    ),
                    strokeWidth = if (index % 3 == 0) 2.2f else 1.1f,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(
                color = GoldLine.copy(alpha = sealAlpha * 0.46f),
                radius = size.minDimension * 0.105f,
                center = center,
                style = Stroke(2.8f),
            )
            drawCircle(
                color = GoldBright.copy(alpha = sealAlpha * 0.30f),
                radius = size.minDimension * 0.075f,
                center = center,
                style = Stroke(1.5f),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (175f - p * 250f).dp)
                .graphicsLayer { alpha = rollAlpha },
        ) {
            RolledPaperSlip()
        }

        Text(
            text = "命",
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-54).dp)
                .graphicsLayer {
                    alpha = sealAlpha
                    scaleX = 0.82f + sealAlpha * 0.18f
                    scaleY = 0.82f + sealAlpha * 0.18f
                },
            color = GoldBright,
            fontFamily = KaiFont,
            fontSize = 64.sp,
            fontWeight = FontWeight.Normal,
            style = TextStyle(
                shadow = Shadow(LacquerRedDark.copy(alpha = 0.75f), Offset(0f, 3f), 5f),
            ),
        )

        AnimatedContent(
            targetState = when {
                p < 0.34f -> 0
                p < 0.70f -> 1
                else -> 2
            },
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 112.dp),
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
            label = "suspense-copy",
        ) { beat ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when (beat) {
                        0 -> "籤已離筒"
                        1 -> "命數正在落定"
                        else -> "命數已定"
                    },
                    color = GoldBright,
                    fontFamily = KaiFont,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Normal,
                )
                Text(
                    text = when (beat) {
                        0 -> "先別急……"
                        1 -> "再等一瞬……"
                        else -> "親手揭開"
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    color = PaperBase,
                    fontFamily = KaiFont,
                    fontSize = 15.sp,
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TempleHeader(
            subtitle = if (completed) "今日籤意已現" else "按住籤紙下緣，慢慢往下揭開",
        )
        Spacer(modifier = Modifier.height(14.dp))
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
                    .fillMaxWidth(0.78f)
                    .widthIn(max = 280.dp)
                    .height(54.dp),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, GoldLine),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LacquerRed,
                    contentColor = PaperBase,
                ),
            ) {
                Text(
                    text = "逆天改命!!",
                    fontFamily = KaiFont,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
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
    val minHeightDp = 70.dp
    val fullHeightDp = 660.dp
    val travelPx = with(density) { (fullHeightDp - minHeightDp).toPx() }
    val visibleHeightDp = minHeightDp + (fullHeightDp - minHeightDp) * revealFraction
    val latestDelta by rememberUpdatedState(onRevealDelta)
    val latestEnd by rememberUpdatedState(onRevealEnd)

    Box(
        modifier = Modifier
            .fillMaxWidth(0.82f)
            .widthIn(max = 300.dp)
            .height(visibleHeightDp)
            .clip(RoundedCornerShape(3.dp))
            .clipToBounds()
            .background(PaperBase)
            .border(1.dp, PaperEdge, RoundedCornerShape(3.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            listOf(0.12f, 0.37f, 0.63f, 0.86f).forEach { fraction ->
                drawLine(
                    color = PaperFiber.copy(alpha = 0.09f),
                    start = Offset(size.width * fraction, 0f),
                    end = Offset(size.width * fraction + 4f, size.height),
                    strokeWidth = 1f,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(fullHeightDp)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "今日籤",
                color = LacquerRedDark,
                fontFamily = KaiFont,
                fontSize = 23.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 1.5.sp,
            )
            Box(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 6.dp)
                    .fillMaxWidth(0.84f)
                    .height(1.dp)
                    .background(LacquerRedDark.copy(alpha = 0.44f)),
            )

            FortuneDomain.entries.forEach { domain ->
                val grade = FortuneGrade.fromScore(draw.domainScores.getValue(domain))
                DomainFortuneRow(label = domain.label, grade = grade)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.76f)
                    .height(1.dp)
                    .background(PaperEdge.copy(alpha = 0.70f)),
            )
            Text(
                text = "總體",
                modifier = Modifier.padding(top = 12.dp),
                color = PaperMuted,
                fontFamily = KaiFont,
                fontSize = 16.sp,
                letterSpacing = 1.sp,
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
                    .height(52.dp)
                    .pointerInput(interactive, travelPx) {
                        detectDragGestures(
                            onDragEnd = { latestEnd() },
                            onDragCancel = { latestEnd() },
                        ) { change, amount ->
                            change.consume()
                            latestDelta(amount.y / travelPx)
                        }
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 9.dp)
                        .width(78.dp)
                        .height(3.dp)
                        .background(LacquerRedDark.copy(alpha = 0.52f)),
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
            .height(70.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(106.dp),
            color = PaperInk,
            fontFamily = KaiFont,
            fontSize = if (label.length >= 5) 16.sp else 18.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            GradeMark(grade = grade, large = false)
        }
    }
}

private data class GradeVisual(
    val text: Color,
    val effect: Color,
    val secondary: Color,
    val strength: Float,
    val positive: Boolean,
    val extreme: Boolean,
)

private fun gradeVisual(grade: FortuneGrade): GradeVisual = when (grade) {
    FortuneGrade.DAI_XIONG -> GradeVisual(
        text = Color(0xFF160A09),
        effect = Color(0xFF0A0505),
        secondary = Color(0xFF6A1612),
        strength = 1f,
        positive = false,
        extreme = true,
    )
    FortuneGrade.XIONG -> GradeVisual(
        text = Color(0xFF4A1714),
        effect = Color(0xFF2A0F0D),
        secondary = Color(0xFF7C2C24),
        strength = 0.70f,
        positive = false,
        extreme = false,
    )
    FortuneGrade.XIAO_XIONG -> GradeVisual(
        text = Color(0xFF6D3028),
        effect = Color(0xFF5A2A23),
        secondary = Color(0xFF9A5A4E),
        strength = 0.42f,
        positive = false,
        extreme = false,
    )
    FortuneGrade.PING -> GradeVisual(
        text = Color(0xFF574A3A),
        effect = Color(0xFF8A785E),
        secondary = Color(0xFF8A785E),
        strength = 0.18f,
        positive = true,
        extreme = false,
    )
    FortuneGrade.XIAO_JI -> GradeVisual(
        text = Color(0xFF8B5A13),
        effect = Color(0xFFB98021),
        secondary = Color(0xFFD5A23D),
        strength = 0.42f,
        positive = true,
        extreme = false,
    )
    FortuneGrade.JI -> GradeVisual(
        text = Color(0xFFA86505),
        effect = Color(0xFFD7A02C),
        secondary = Color(0xFFEBC968),
        strength = 0.72f,
        positive = true,
        extreme = false,
    )
    FortuneGrade.DAI_JI -> GradeVisual(
        text = Amber,
        effect = Color(0xFFFFC83D),
        secondary = AmberHighlight,
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
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (grade) {
                    FortuneGrade.DAI_JI -> 760
                    FortuneGrade.DAI_XIONG -> 1_080
                    else -> 1_500
                },
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "grade-phase",
    )
    val breathe by infinite.animateFloat(
        initialValue = 0.88f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (visual.extreme) 520 else 1_050, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "grade-breathe",
    )

    val width = if (large) 200.dp else 132.dp
    val height = if (large) 126.dp else 68.dp

    Box(
        modifier = modifier.size(width = width, height = height),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val minDim = size.minDimension

            when {
                grade == FortuneGrade.PING -> {
                    drawCircle(
                        color = visual.effect.copy(alpha = 0.20f),
                        radius = minDim * 0.31f,
                        center = center,
                        style = Stroke(1.2f),
                    )
                }
                visual.positive -> {
                    val rayCount = when (grade) {
                        FortuneGrade.XIAO_JI -> 6
                        FortuneGrade.JI -> 12
                        FortuneGrade.DAI_JI -> 30
                        else -> 0
                    }
                    if (grade == FortuneGrade.DAI_JI) {
                        drawCircle(
                            color = Amber.copy(alpha = 0.15f + 0.06f * breathe),
                            radius = minDim * 0.34f,
                            center = center,
                        )
                        drawCircle(
                            color = AmberHighlight.copy(alpha = 0.54f * breathe),
                            radius = minDim * 0.35f,
                            center = center,
                            style = Stroke(3.8f),
                        )
                        drawCircle(
                            color = GoldBright.copy(alpha = 0.34f * breathe),
                            radius = minDim * 0.42f,
                            center = center,
                            style = Stroke(1.4f),
                        )
                    } else {
                        drawCircle(
                            color = visual.effect.copy(alpha = (0.12f + visual.strength * 0.18f) * breathe),
                            radius = minDim * (0.29f + 0.04f * breathe),
                            center = center,
                            style = Stroke(2.0f),
                        )
                    }
                    repeat(rayCount) { index ->
                        val angle = 2.0 * PI * index / rayCount + phase * 0.18
                        val inner = minDim * if (grade == FortuneGrade.DAI_JI) 0.32f else 0.29f
                        val outer = minDim * if (grade == FortuneGrade.DAI_JI) {
                            0.55f + 0.05f * breathe
                        } else {
                            0.36f + visual.strength * 0.11f * breathe
                        }
                        drawLine(
                            color = if (grade == FortuneGrade.DAI_JI) {
                                AmberHighlight.copy(alpha = if (index % 3 == 0) 0.68f else 0.34f)
                            } else {
                                visual.effect.copy(alpha = 0.16f + visual.strength * 0.30f)
                            },
                            start = Offset(
                                center.x + cos(angle).toFloat() * inner,
                                center.y + sin(angle).toFloat() * inner,
                            ),
                            end = Offset(
                                center.x + cos(angle).toFloat() * outer,
                                center.y + sin(angle).toFloat() * outer,
                            ),
                            strokeWidth = if (grade == FortuneGrade.DAI_JI && index % 3 == 0) 3.0f else 1.4f,
                            cap = StrokeCap.Round,
                        )
                    }

                    val sparkCount = when (grade) {
                        FortuneGrade.XIAO_JI -> 2
                        FortuneGrade.JI -> 5
                        FortuneGrade.DAI_JI -> 16
                        else -> 0
                    }
                    repeat(sparkCount) { index ->
                        val angle = 2.0 * PI * index / sparkCount + 0.35
                        val radius = minDim * (0.34f + ((phase + index * 0.11f) % 1f) * 0.22f)
                        val x = center.x + cos(angle).toFloat() * radius
                        val y = center.y + sin(angle).toFloat() * radius
                        val sparkColor = if (grade == FortuneGrade.DAI_JI && index % 3 == 0) {
                            Color.White
                        } else {
                            visual.secondary
                        }
                        val span = if (grade == FortuneGrade.DAI_JI) 5.5f else 4f
                        drawLine(
                            color = sparkColor.copy(alpha = 0.58f + 0.30f * breathe),
                            start = Offset(x - span, y),
                            end = Offset(x + span, y),
                            strokeWidth = if (grade == FortuneGrade.DAI_JI) 2.0f else 1.7f,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = sparkColor.copy(alpha = 0.58f + 0.30f * breathe),
                            start = Offset(x, y - span),
                            end = Offset(x, y + span),
                            strokeWidth = if (grade == FortuneGrade.DAI_JI) 2.0f else 1.7f,
                            cap = StrokeCap.Round,
                        )
                    }
                }
                else -> {
                    if (grade == FortuneGrade.DAI_XIONG) {
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.22f + 0.10f * breathe),
                            radius = minDim * 0.39f,
                            center = center,
                        )
                        drawCircle(
                            color = visual.secondary.copy(alpha = 0.13f),
                            radius = minDim * 0.42f,
                            center = center,
                            style = Stroke(2f),
                        )
                    }

                    val ashCount = when (grade) {
                        FortuneGrade.XIAO_XIONG -> 3
                        FortuneGrade.XIONG -> 6
                        FortuneGrade.DAI_XIONG -> 12
                        else -> 0
                    }
                    repeat(ashCount) { index ->
                        val x = size.width * (0.18f + (index % 5) * 0.16f)
                        val local = (phase + index * 0.13f) % 1f
                        val y = size.height * (0.18f + local * 0.70f)
                        drawCircle(
                            color = visual.effect.copy(alpha = 0.14f + visual.strength * 0.28f * (1f - local)),
                            radius = if (visual.extreme) 3.2f else 2.1f,
                            center = Offset(x, y),
                        )
                    }

                    val crackCount = when (grade) {
                        FortuneGrade.XIAO_XIONG -> 0
                        FortuneGrade.XIONG -> 2
                        FortuneGrade.DAI_XIONG -> 5
                        else -> 0
                    }
                    repeat(crackCount) { index ->
                        val angle = 2.0 * PI * index / crackCount + 0.25
                        val startRadius = minDim * 0.28f
                        val midRadius = minDim * 0.38f
                        val endRadius = minDim * 0.48f
                        val start = Offset(
                            center.x + cos(angle).toFloat() * startRadius,
                            center.y + sin(angle).toFloat() * startRadius,
                        )
                        val mid = Offset(
                            center.x + cos(angle + 0.14).toFloat() * midRadius,
                            center.y + sin(angle + 0.14).toFloat() * midRadius,
                        )
                        val end = Offset(
                            center.x + cos(angle - 0.08).toFloat() * endRadius,
                            center.y + sin(angle - 0.08).toFloat() * endRadius,
                        )
                        drawLine(
                            color = visual.secondary.copy(alpha = 0.30f + 0.20f * visual.strength),
                            start = start,
                            end = mid,
                            strokeWidth = 1.3f,
                        )
                        drawLine(
                            color = visual.secondary.copy(alpha = 0.24f + 0.18f * visual.strength),
                            start = mid,
                            end = end,
                            strokeWidth = 1.0f,
                        )
                    }
                }
            }
        }

        val jitter = if (grade == FortuneGrade.DAI_XIONG) {
            sin(phase * PI * 12).toFloat() * 1.6f
        } else {
            0f
        }
        val fontSize = when {
            grade == FortuneGrade.DAI_JI && large -> 56.sp
            grade == FortuneGrade.DAI_JI -> 30.sp
            large -> 48.sp
            else -> 25.sp
        }

        if (grade == FortuneGrade.DAI_JI) {
            Text(
                text = grade.label,
                modifier = Modifier
                    .offset(y = 2.dp)
                    .graphicsLayer {
                        scaleX = 0.98f + 0.05f * breathe
                        scaleY = 0.98f + 0.05f * breathe
                    },
                color = AmberDark,
                fontFamily = KaiFont,
                fontSize = fontSize,
                fontWeight = FontWeight.Normal,
                letterSpacing = if (large) 1.5.sp else 0.5.sp,
            )
            Text(
                text = grade.label,
                modifier = Modifier.graphicsLayer {
                    scaleX = 0.98f + 0.05f * breathe
                    scaleY = 0.98f + 0.05f * breathe
                },
                color = Amber,
                fontFamily = KaiFont,
                fontSize = fontSize,
                fontWeight = FontWeight.Normal,
                letterSpacing = if (large) 1.5.sp else 0.5.sp,
                style = TextStyle(
                    shadow = Shadow(
                        color = GoldBright.copy(alpha = 0.72f),
                        offset = Offset.Zero,
                        blurRadius = if (large) 11f else 7f,
                    ),
                ),
            )
            Text(
                text = grade.label,
                modifier = Modifier
                    .offset(x = (-1).dp, y = (-1).dp)
                    .graphicsLayer {
                        alpha = 0.46f + 0.18f * breathe
                        scaleX = 0.98f + 0.05f * breathe
                        scaleY = 0.98f + 0.05f * breathe
                    },
                color = AmberHighlight,
                fontFamily = KaiFont,
                fontSize = fontSize,
                fontWeight = FontWeight.Normal,
                letterSpacing = if (large) 1.5.sp else 0.5.sp,
            )
        } else {
            Text(
                text = grade.label,
                modifier = Modifier.graphicsLayer {
                    translationX = jitter
                },
                color = visual.text,
                fontFamily = KaiFont,
                fontSize = fontSize,
                fontWeight = FontWeight.Normal,
                letterSpacing = if (large) 1.5.sp else 0.5.sp,
                style = TextStyle(
                    shadow = Shadow(
                        color = if (visual.positive) {
                            Color(0xFF7B4A08).copy(alpha = 0.28f)
                        } else {
                            Color.Black.copy(alpha = 0.34f)
                        },
                        offset = Offset(0f, if (large) 2f else 1f),
                        blurRadius = if (large) 2.5f else 1.2f,
                    ),
                ),
            )
        }
    }
}
