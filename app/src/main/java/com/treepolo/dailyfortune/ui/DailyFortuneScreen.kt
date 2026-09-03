package com.treepolo.dailyfortune.ui

import android.os.SystemClock
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.FortuneDraw
import com.treepolo.dailyfortune.model.FortuneGrade
import kotlinx.coroutines.delay

private val TempleInk = Color(0xFF160B09)
private val TempleDeepRed = Color(0xFF3D0908)
private val TempleRed = Color(0xFF7A1110)
private val TempleCinnabar = Color(0xFFA3221B)
private val TempleGold = Color(0xFFD6A84A)
private val TempleBrightGold = Color(0xFFFFD874)
private val TemplePaper = Color(0xFFF3E4C3)
private val TempleMutedPaper = Color(0xFFD5C29D)
private val Bamboo = Color(0xFFD9B66F)
private val BambooDark = Color(0xFF765028)

private const val MIN_CEREMONY_MILLIS = 2_650L
private const val RESULT_IMPACT_MILLIS = 1_250L

@Composable
fun DailyFortuneRoot(viewModel: FortuneViewModel) {
    val state by viewModel.uiState.collectAsState()
    val colorScheme = darkColorScheme(
        primary = TempleGold,
        onPrimary = TempleInk,
        background = TempleInk,
        onBackground = TemplePaper,
        surface = TempleDeepRed,
        onSurface = TemplePaper,
    )

    MaterialTheme(colorScheme = colorScheme) {
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
    var displayedDraw by remember { mutableStateOf<FortuneDraw?>(null) }
    var ceremonyActive by remember { mutableStateOf(false) }
    var ceremonyKey by remember { mutableIntStateOf(0) }
    var ceremonyStartedAt by remember { mutableLongStateOf(0L) }
    var impactDraw by remember { mutableStateOf<FortuneDraw?>(null) }

    fun beginCeremony(action: () -> Unit) {
        if (ceremonyActive) return
        ceremonyKey += 1
        ceremonyStartedAt = SystemClock.elapsedRealtime()
        ceremonyActive = true
        impactDraw = null
        action()
    }

    LaunchedEffect(state.currentDraw?.id, ceremonyActive) {
        val incoming = state.currentDraw
        if (incoming == null) {
            if (!ceremonyActive) displayedDraw = null
            return@LaunchedEffect
        }

        if (!ceremonyActive) {
            displayedDraw = incoming
            return@LaunchedEffect
        }

        if (incoming.id == displayedDraw?.id) return@LaunchedEffect

        val elapsed = SystemClock.elapsedRealtime() - ceremonyStartedAt
        delay((MIN_CEREMONY_MILLIS - elapsed).coerceAtLeast(250L))
        displayedDraw = incoming
        impactDraw = incoming
        delay(RESULT_IMPACT_MILLIS)
        impactDraw = null
        ceremonyActive = false
    }

    LaunchedEffect(state.errorMessage) {
        if (state.errorMessage != null && ceremonyActive) {
            delay(350L)
            impactDraw = null
            ceremonyActive = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TempleBackdrop(displayedDraw?.overallGrade)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TempleHeader(hasDraw = displayedDraw != null)
            Spacer(modifier = Modifier.height(24.dp))

            when {
                state.isLoading && displayedDraw == null && !ceremonyActive -> CircularProgressIndicator(
                    color = TempleGold,
                )
                displayedDraw == null -> EmptyFortune(
                    onInitialDraw = { beginCeremony(onInitialDraw) },
                    isLoading = state.isLoading || ceremonyActive,
                )
                else -> FortuneResult(
                    draw = displayedDraw!!,
                    onReroll = { beginCeremony(onReroll) },
                    isLoading = state.isLoading || ceremonyActive,
                )
            }

            state.errorMessage?.let { message ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    color = TempleMutedPaper,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        if (ceremonyActive) {
            DrawCeremonyOverlay(ceremonyKey = ceremonyKey, revealedDraw = impactDraw)
        }
    }
}

@Composable
private fun TempleHeader(hasDraw: Boolean) {
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
                .padding(top = 9.dp)
                .fillMaxWidth(0.52f)
                .height(1.dp)
                .background(TempleGold.copy(alpha = 0.65f)),
        )
        Text(
            text = if (hasDraw) "今日籤意已現" else "一日一問，親手求籤",
            modifier = Modifier.padding(top = 10.dp),
            color = TempleMutedPaper,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyFortune(onInitialDraw: () -> Unit, isLoading: Boolean) {
    TemplePanel {
        FortuneTubeIllustration(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(width = 176.dp, height = 226.dp),
            shakeDegrees = 0f,
            selectedStickLift = 0f,
        )
        Text(
            text = "今天還沒抽籤",
            modifier = Modifier.padding(top = 4.dp),
            color = TemplePaper,
            fontFamily = FontFamily.Serif,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "財運、戀愛、工作／學業、人際、健康，一次定下今日五運。",
            modifier = Modifier.padding(top = 10.dp, bottom = 22.dp),
            color = TempleMutedPaper,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        TempleActionButton(
            text = if (isLoading) "求籤中……" else "求今日一籤",
            enabled = !isLoading,
            onClick = onInitialDraw,
        )
    }
}

@Composable
private fun FortuneResult(draw: FortuneDraw, onReroll: () -> Unit, isLoading: Boolean) {
    val visual = visualFor(draw.overallGrade)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = visual.panel,
        border = BorderStroke(1.dp, visual.accent.copy(alpha = 0.8f)),
        shadowElevation = if (draw.overallGrade.score >= 5) 10.dp else 2.dp,
    ) {
        Column(
            modifier = Modifier
                .background(Brush.verticalGradient(visual.panelGradient))
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "總體運勢",
                color = visual.secondaryText,
                fontFamily = FontFamily.Serif,
                fontSize = 14.sp,
                letterSpacing = 3.sp,
            )
            Text(
                text = draw.overallGrade.label,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                color = visual.primaryText,
                fontFamily = FontFamily.Serif,
                fontSize = 52.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 5.sp,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(visual.accent.copy(alpha = 0.42f)),
            )
            Spacer(modifier = Modifier.height(10.dp))
            FortuneDomain.entries.forEach { domain ->
                val score = draw.domainScores.getValue(domain)
                DomainRow(label = domain.label, grade = FortuneGrade.fromScore(score))
            }
        }
    }

    Spacer(modifier = Modifier.height(20.dp))
    TempleActionButton(
        text = if (isLoading) "改命中……" else "逆天改命!!",
        enabled = !isLoading,
        onClick = onReroll,
    )
}

@Composable
private fun DomainRow(label: String, grade: FortuneGrade) {
    val gradeVisual = visualFor(grade)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = TempleMutedPaper, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = grade.label,
            color = gradeVisual.accent,
            fontFamily = FontFamily.Serif,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TemplePanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = TempleDeepRed.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, TempleGold.copy(alpha = 0.58f)),
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

@Composable
private fun TempleActionButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, TempleBrightGold.copy(alpha = if (enabled) 0.9f else 0.35f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = TempleCinnabar,
            contentColor = TemplePaper,
            disabledContainerColor = TempleDeepRed,
            disabledContentColor = TempleMutedPaper.copy(alpha = 0.6f),
        ),
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Serif,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
private fun DrawCeremonyOverlay(ceremonyKey: Int, revealedDraw: FortuneDraw?) {
    var phase by remember(ceremonyKey) { mutableIntStateOf(0) }
    val infinite = rememberInfiniteTransition(label = "fortune-ceremony")
    val shake by infinite.animateFloat(
        initialValue = -2.4f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(125, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tube-shake",
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.42f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(760, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ceremony-pulse",
    )
    val stickLift by animateFloatAsState(
        targetValue = if (phase >= 1) 1f else 0f,
        animationSpec = tween(780, easing = FastOutSlowInEasing),
        label = "selected-stick-lift",
    )

    LaunchedEffect(ceremonyKey) {
        phase = 0
        delay(720L)
        phase = 1
        delay(840L)
        phase = 2
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        TempleRed.copy(alpha = 0.96f),
                        TempleDeepRed.copy(alpha = 0.985f),
                        Color.Black.copy(alpha = 0.99f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        CeremonyRays(alpha = pulse * 0.36f)
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (revealedDraw == null) {
                FortuneTubeIllustration(
                    modifier = Modifier
                        .size(width = 202.dp, height = 270.dp)
                        .graphicsLayer { rotationZ = if (phase < 2) shake else shake * 0.28f },
                    shakeDegrees = shake,
                    selectedStickLift = stickLift,
                )
                Text(
                    text = when (phase) {
                        0 -> "靜心求籤"
                        1 -> "籤已落定"
                        else -> "天機將現"
                    },
                    modifier = Modifier.padding(top = 12.dp),
                    color = TempleBrightGold.copy(alpha = 0.78f + pulse * 0.22f),
                    fontFamily = FontFamily.Serif,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 5.sp,
                )
                Text(
                    text = if (phase >= 2) "……" else "",
                    modifier = Modifier.padding(top = 8.dp),
                    color = TempleMutedPaper,
                    fontSize = 22.sp,
                    letterSpacing = 8.sp,
                )
            } else {
                ResultImpact(revealedDraw)
            }
        }
    }
}

@Composable
private fun ResultImpact(draw: FortuneDraw) {
    val visual = visualFor(draw.overallGrade)
    var started by remember(draw.id) { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (started) 1f else 0.36f,
        animationSpec = tween(520, easing = FastOutSlowInEasing),
        label = "result-impact-scale",
    )
    val halo by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(420),
        label = "result-impact-halo",
    )

    LaunchedEffect(draw.id) { started = true }

    Box(modifier = Modifier.size(300.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * (0.22f + visual.glowStrength * 0.36f * halo)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        visual.aura.copy(alpha = 0.72f * halo),
                        visual.aura.copy(alpha = 0.18f * halo),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = radius.coerceAtLeast(1f),
                ),
                radius = radius,
                center = center,
            )
            if (draw.overallGrade.score >= 5) {
                val rayAlpha = (0.12f + 0.24f * visual.glowStrength) * halo
                repeat(18) { index ->
                    val radians = Math.toRadians(index * (360.0 / 18.0))
                    val inner = size.minDimension * 0.27f
                    val outer = size.minDimension * (0.38f + 0.08f * visual.glowStrength)
                    drawLine(
                        color = visual.accent.copy(alpha = rayAlpha),
                        start = Offset(
                            center.x + kotlin.math.cos(radians).toFloat() * inner,
                            center.y + kotlin.math.sin(radians).toFloat() * inner,
                        ),
                        end = Offset(
                            center.x + kotlin.math.cos(radians).toFloat() * outer,
                            center.y + kotlin.math.sin(radians).toFloat() * outer,
                        ),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }

        if (draw.overallGrade == FortuneGrade.DAI_JI) {
            Box(
                modifier = Modifier
                    .size(244.dp)
                    .background(
                        Brush.sweepGradient(
                            listOf(
                                Color(0x66FF6B6B),
                                Color(0x66FFD93D),
                                Color(0x6657E389),
                                Color(0x665DBBFF),
                                Color(0x66B06CFF),
                                Color(0x66FF6B6B),
                            ),
                        ),
                        RoundedCornerShape(122.dp),
                    ),
            )
        }

        Text(
            text = draw.overallGrade.label,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
            color = visual.primaryText,
            fontFamily = FontFamily.Serif,
            fontSize = 66.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 6.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FortuneTubeIllustration(
    modifier: Modifier,
    shakeDegrees: Float,
    selectedStickLift: Float,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val stickWidth = w * 0.055f
            val baseY = h * 0.61f
            val normalTop = h * 0.11f

            repeat(7) { index ->
                val x = w * (0.29f + index * 0.07f)
                val isChosen = index == 3
                val lift = if (isChosen) h * 0.18f * selectedStickLift else 0f
                val top = normalTop - lift + kotlin.math.abs(shakeDegrees) * (index % 2) * 0.8f
                drawRoundRect(
                    color = if (isChosen) TempleBrightGold else Bamboo,
                    topLeft = Offset(x, top),
                    size = Size(stickWidth, baseY - top + h * 0.06f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(stickWidth / 2f),
                )
                drawLine(
                    color = BambooDark.copy(alpha = 0.72f),
                    start = Offset(x + stickWidth * 0.5f, top + h * 0.025f),
                    end = Offset(x + stickWidth * 0.5f, top + h * 0.095f),
                    strokeWidth = 2f,
                )
            }

            val tubeTop = h * 0.47f
            val tubeLeft = w * 0.16f
            val tubeWidth = w * 0.68f
            val tubeHeight = h * 0.45f
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFF8B1D16), Color(0xFF4E0A08), Color(0xFF2A0605)),
                    startY = tubeTop,
                    endY = tubeTop + tubeHeight,
                ),
                topLeft = Offset(tubeLeft, tubeTop),
                size = Size(tubeWidth, tubeHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
            )
            drawRoundRect(
                color = TempleGold.copy(alpha = 0.88f),
                topLeft = Offset(tubeLeft, tubeTop),
                size = Size(tubeWidth, tubeHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
                style = Stroke(width = 3f),
            )
            drawLine(
                color = TempleBrightGold.copy(alpha = 0.72f),
                start = Offset(tubeLeft + w * 0.04f, tubeTop + h * 0.11f),
                end = Offset(tubeLeft + tubeWidth - w * 0.04f, tubeTop + h * 0.11f),
                strokeWidth = 3f,
            )
        }

        Text(
            text = "籤",
            modifier = Modifier.padding(top = 105.dp),
            color = TempleBrightGold,
            fontFamily = FontFamily.Serif,
            fontSize = 40.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun CeremonyRays(alpha: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height * 0.46f)
        repeat(24) { index ->
            val radians = Math.toRadians(index * 15.0)
            val inner = size.minDimension * 0.27f
            val outer = size.maxDimension * 0.58f
            drawLine(
                color = TempleGold.copy(alpha = alpha),
                start = Offset(
                    center.x + kotlin.math.cos(radians).toFloat() * inner,
                    center.y + kotlin.math.sin(radians).toFloat() * inner,
                ),
                end = Offset(
                    center.x + kotlin.math.cos(radians).toFloat() * outer,
                    center.y + kotlin.math.sin(radians).toFloat() * outer,
                ),
                strokeWidth = 2f,
            )
        }
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
                    Brush.verticalGradient(listOf(TempleInk, TempleDeepRed, Color(0xFF090504)))
                } else {
                    Brush.verticalGradient(visual.backdropGradient)
                },
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gold = TempleGold.copy(alpha = 0.17f)
            val y = size.height * 0.075f
            drawLine(gold, Offset(0f, y), Offset(size.width * 0.5f, 0f), 4f)
            drawLine(gold, Offset(size.width, y), Offset(size.width * 0.5f, 0f), 4f)
            drawLine(gold, Offset(0f, y + 13f), Offset(size.width * 0.5f, 13f), 2f)
            drawLine(gold, Offset(size.width, y + 13f), Offset(size.width * 0.5f, 13f), 2f)
            drawLine(gold, Offset(size.width * 0.055f, 0f), Offset(size.width * 0.055f, size.height), 2f)
            drawLine(gold, Offset(size.width * 0.945f, 0f), Offset(size.width * 0.945f, size.height), 2f)
        }
    }
}

private data class FortuneVisual(
    val backdropGradient: List<Color>,
    val panelGradient: List<Color>,
    val panel: Color,
    val accent: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val aura: Color,
    val glowStrength: Float,
)

private fun visualFor(grade: FortuneGrade): FortuneVisual = when (grade) {
    FortuneGrade.DAI_XIONG -> FortuneVisual(
        listOf(Color(0xFF030303), Color(0xFF120C0C), Color(0xFF050505)),
        listOf(Color(0xFF171717), Color(0xFF0A0909)), Color(0xFF121111), Color(0xFF77706B),
        Color(0xFFB9B1A7), Color(0xFF7E7973), Color(0xFF3B3735), 0.08f,
    )
    FortuneGrade.XIONG -> FortuneVisual(
        listOf(Color(0xFF120708), Color(0xFF240B0C), Color(0xFF080505)),
        listOf(Color(0xFF281112), Color(0xFF140B0B)), Color(0xFF211010), Color(0xFF9B6B61),
        Color(0xFFD2B3A8), Color(0xFFA98F88), Color(0xFF6A322F), 0.16f,
    )
    FortuneGrade.XIAO_XIONG -> FortuneVisual(
        listOf(Color(0xFF25100E), Color(0xFF3A1511), Color(0xFF130908)),
        listOf(Color(0xFF401B16), Color(0xFF24110E)), Color(0xFF351712), Color(0xFFB98062),
        Color(0xFFE0C1A8), Color(0xFFB89F90), Color(0xFF8C4A35), 0.25f,
    )
    FortuneGrade.PING -> FortuneVisual(
        listOf(TempleInk, TempleDeepRed, Color(0xFF120908)),
        listOf(Color(0xFF5A1A14), Color(0xFF35100E)), Color(0xFF49150F), Color(0xFFC6A56A),
        TemplePaper, TempleMutedPaper, TempleGold, 0.34f,
    )
    FortuneGrade.XIAO_JI -> FortuneVisual(
        listOf(Color(0xFF3D0908), Color(0xFF741710), Color(0xFF1A0806)),
        listOf(Color(0xFF7A2117), Color(0xFF4A130D)), Color(0xFF671B13), Color(0xFFE4B95C),
        Color(0xFFFFE7B0), Color(0xFFE4C995), Color(0xFFE6B64D), 0.52f,
    )
    FortuneGrade.JI -> FortuneVisual(
        listOf(Color(0xFF5B0D08), Color(0xFFA12C15), Color(0xFF271006)),
        listOf(Color(0xFFA8371D), Color(0xFF64150E)), Color(0xFF842419), Color(0xFFFFD46D),
        Color(0xFFFFF0BE), Color(0xFFF2D79D), Color(0xFFFFC846), 0.76f,
    )
    FortuneGrade.DAI_JI -> FortuneVisual(
        listOf(Color(0xFF7B1208), Color(0xFFB93818), Color(0xFF4A140B), Color(0xFF17104A)),
        listOf(Color(0xFFB83B1E), Color(0xFF712014), Color(0xFF3B173D)), Color(0xFF912817), Color(0xFFFFE38A),
        Color.White, Color(0xFFFFE0A8), Color(0xFFFFE16A), 1f,
    )
}
