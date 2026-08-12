package com.dhinasuthra.app.ui.components

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.dhinasuthra.app.ui.motion.MotionTokens
import com.dhinasuthra.app.ui.motion.rememberReducedMotion
import com.dhinasuthra.app.ui.theme.Ds
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Component library for the premium animated identity (product.md §37):
 * calm, warm, data-rich; the thread is the signature element.
 */

// ---------------------------------------------------------------------------
// 3D press-tilt — cards tip toward the touch point (subtle, spring-released)
// ---------------------------------------------------------------------------

@Composable
fun TiltCard(
    modifier: Modifier = Modifier,
    corner: Dp = 22.dp,
    background: Brush = Ds.CardBrush,
    content: @Composable () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    var tiltX by remember { mutableFloatStateOf(0f) }
    var tiltY by remember { mutableFloatStateOf(0f) }
    val animTiltX by animateFloatAsState(if (pressed) tiltX else 0f, spring(stiffness = 300f), label = "tx")
    val animTiltY by animateFloatAsState(if (pressed) tiltY else 0f, spring(stiffness = 300f), label = "ty")
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, spring(stiffness = 400f), label = "sc")

    Box(
        modifier = modifier
            .graphicsLayer {
                rotationX = animTiltX
                rotationY = animTiltY
                scaleX = scale
                scaleY = scale
                cameraDistance = 16f * density
            }
            .clip(RoundedCornerShape(corner))
            .background(background)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    tiltY = ((down.position.x / w) - 0.5f) * 2f * 5f
                    tiltX = -((down.position.y / h) - 0.5f) * 2f * 5f
                    pressed = true
                    waitForUpOrCancellation()
                    pressed = false
                }
            }
    ) { content() }
}

// ---------------------------------------------------------------------------
// Animated counter
// ---------------------------------------------------------------------------

@Composable
fun AnimatedNumber(target: Int, suffix: String = "", style: androidx.compose.ui.text.TextStyle) {
    var started by remember(target) { mutableStateOf(false) }
    LaunchedEffect(target) { started = true }
    val v by animateFloatAsState(
        if (started) target.toFloat() else 0f,
        tween(MotionTokens.COUNT_UP, easing = EaseOutCubic), label = "num"
    )
    Text("${v.roundToInt()}$suffix", style = style)
}

// ---------------------------------------------------------------------------
// Routine Match ring — gradient sweep with glow + animated fill
// ---------------------------------------------------------------------------

@Composable
fun RoutineMatchRing(
    percent: Int?,
    modifier: Modifier = Modifier,
    ringWidth: Dp = 13.dp
) {
    val reduced = rememberReducedMotion()
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(percent) { started = true }
    val progress by animateFloatAsState(
        if (started && percent != null) percent / 100f else 0f,
        if (reduced) tween(0) else tween(MotionTokens.COUNT_UP + 400, easing = FastOutSlowInEasing),
        label = "ring"
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = ringWidth.toPx()
            val inset = stroke / 2 + 4.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            // Track
            drawArc(
                color = Color.White.copy(alpha = 0.07f),
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
            if (progress > 0f) {
                val sweep = 360f * progress
                val brush = Brush.sweepGradient(colors = Ds.ThreadColors + Ds.ThreadColors.first())
                rotate(degrees = -90f) {
                    // Soft glow layer (static — §14: no continuous decorative motion)
                    drawArc(
                        brush = brush, startAngle = 0f, sweepAngle = sweep, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(stroke * 1.9f, cap = StrokeCap.Round),
                        alpha = 0.14f
                    )
                    // Main arc
                    drawArc(
                        brush = brush, startAngle = 0f, sweepAngle = sweep, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (percent != null) {
                AnimatedNumber(percent, "%", MaterialTheme.typography.displayLarge)
                Text("Routine Match", style = MaterialTheme.typography.labelMedium)
            } else {
                Text("Learning", style = MaterialTheme.typography.headlineSmall)
                Text("your routine", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Donut chart — animated segment sweep (Insights: where did my time go)
// ---------------------------------------------------------------------------

data class Slice(val label: String, val minutes: Int, val color: Color)

@Composable
fun DonutChart(slices: List<Slice>, modifier: Modifier = Modifier, ringWidth: Dp = 26.dp) {
    val total = slices.sumOf { it.minutes }.coerceAtLeast(1)
    var started by remember(slices) { mutableStateOf(false) }
    LaunchedEffect(slices) { started = true }
    val progress by animateFloatAsState(if (started) 1f else 0f, tween(1300, easing = EaseOutCubic), label = "donut")

    Canvas(modifier) {
        val stroke = ringWidth.toPx()
        val inset = stroke / 2 + 2.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        var start = -90f
        for (s in slices) {
            val sweepFull = 360f * s.minutes / total
            val sweep = sweepFull * progress
            drawArc(
                brush = SolidColor(s.color), startAngle = start, sweepAngle = (sweep - 2f).coerceAtLeast(0f),
                useCenter = false, topLeft = topLeft, size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Butt)
            )
            start += sweepFull
        }
    }
}

// ---------------------------------------------------------------------------
// Week bars — animated rounded bars with gradient fill
// ---------------------------------------------------------------------------

data class Bar(val label: String, val value: Int, val max: Int, val highlight: Boolean = false)

@Composable
fun WeekBars(bars: List<Bar>, modifier: Modifier = Modifier, height: Dp = 120.dp) {
    var started by remember(bars) { mutableStateOf(false) }
    LaunchedEffect(bars) { started = true }
    val progress by animateFloatAsState(if (started) 1f else 0f, tween(1000, easing = EaseOutCubic), label = "bars")

    Row(
        modifier
            .fillMaxWidth()
            .height(height + 22.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        for (b in bars) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val frac = if (b.max <= 0) 0f else (b.value.toFloat() / b.max).coerceIn(0.04f, 1f)
                Box(
                    Modifier
                        .width(22.dp)
                        .height(height * frac * progress + 4.dp)
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                        .background(
                            if (b.highlight) Ds.SunsetBrush
                            else Brush.verticalGradient(listOf(Ds.Active, Ds.Purple))
                        )
                )
                Spacer(Modifier.height(6.dp))
                Text(b.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Thread timeline — the Sūtra itself (product.md §21): gradient line + nodes
// ---------------------------------------------------------------------------

data class ThreadItem(
    val time: String,
    val title: String,
    val subtitle: String,
    val duration: String?,
    val color: Color,
    val isCurrent: Boolean = false,
    val manual: Boolean = false
)

@Composable
fun ThreadTimeline(
    items: List<ThreadItem>,
    modifier: Modifier = Modifier,
    onItemLongPress: ((Int) -> Unit)? = null
) {
    Column(modifier) {
        items.forEachIndexed { i, item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (onItemLongPress != null) Modifier.pointerInput(i) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val up = withTimeoutOrNull(480) { waitForUpOrCancellation() }
                                if (up == null) onItemLongPress(i)
                            }
                        } else Modifier
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    item.time,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(48.dp)
                )
                Box(Modifier.width(26.dp).height(58.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val cx = size.width / 2
                        val brush = Brush.verticalGradient(listOf(Ds.ThreadStart, Ds.Amber))
                        if (i > 0) drawLine(brush, Offset(cx, 0f), Offset(cx, size.height / 2 - 9.dp.toPx()), 2.5.dp.toPx())
                        if (i < items.lastIndex) drawLine(brush, Offset(cx, size.height / 2 + 9.dp.toPx()), Offset(cx, size.height), 2.5.dp.toPx())
                        val r = 5.5.dp.toPx()
                        // Current node: static glow halo — no continuous pulsing (§11).
                        if (item.isCurrent) drawCircle(item.color.copy(alpha = 0.22f), r * 2.1f, Offset(cx, size.height / 2))
                        drawCircle(item.color, r, Offset(cx, size.height / 2))
                        drawCircle(Ds.Navy, r * 0.42f, Offset(cx, size.height / 2))
                    }
                }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        if (item.manual) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .background(Ds.Active, CircleShape)
                            )
                        }
                    }
                    Text(item.subtitle, style = MaterialTheme.typography.bodySmall)
                }
                if (item.duration != null) {
                    Text(item.duration, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Small stat chip
// ---------------------------------------------------------------------------

@Composable
fun StatChip(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(accent)
        )
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
