package com.dhinasuthra.app.ui.foundation

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.intelligence.ConfidenceBand
import com.dhinasuthra.app.ui.motion.MotionTokens
import com.dhinasuthra.app.ui.motion.rememberReducedMotion
import com.dhinasuthra.app.ui.theme.DsTokens
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Foundations: safe area, surfaces, motion primitives.
 *
 * The first of these is not decoration. Spec §21/§47 calls the header colliding
 * with the camera cutout "unacceptable for a production release", and the fix is
 * a root-cause one: every screen is laid out through [DsScreen], which consumes
 * the real WindowInsets — status bar, display cutout, navigation bar, gesture
 * area — rather than guessing with a fixed top margin.
 */

/** The insets a screen's content must never draw under. */
object DsSafeArea {
    val top: WindowInsets
        @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)

    val horizontal: WindowInsets
        @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)

    val topAndSides: WindowInsets
        @Composable get() = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        )

    val bottomAndSides: WindowInsets
        @Composable get() = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
        )
}

/**
 * The standard screen frame: an inset-safe header that never collides with the
 * status bar or a punch-hole, and a lazy body with room left for the tab bar.
 */
@Composable
fun DsScreen(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    header: @Composable (() -> Unit)? = null,
    content: LazyListScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(DsSafeArea.topAndSides)
    ) {
        DsHeader(title = title, subtitle = subtitle, trailing = trailing)
        header?.invoke()
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = DsTokens.ScreenPadding,
                end = DsTokens.ScreenPadding,
                top = DsTokens.GapS,
                bottom = DsTokens.BottomInset
            ),
            verticalArrangement = Arrangement.spacedBy(DsTokens.GapM),
            content = content
        )
    }
}

@Composable
fun DsHeader(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = DsTokens.ScreenPadding,
                end = DsTokens.ScreenPadding,
                top = DsTokens.GapM,
                bottom = DsTokens.GapS
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium)
            }
        }
        trailing?.invoke()
    }
}

// ---------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------

/**
 * The card everything sits on: a dark glass plate that tips toward your thumb.
 *
 * The tilt is a real perspective transform (rotationX/rotationY with a camera
 * distance), not a scale trick, so the card genuinely turns in space — 5° at the
 * corners, released by a spring. It is suppressed under system reduced-motion.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    tint: Color = DsTokens.Cyan,
    corner: Dp = DsTokens.CardCorner,
    interactive: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val reduced = rememberReducedMotion()
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    var tiltX by remember { mutableFloatStateOf(0f) }
    var tiltY by remember { mutableFloatStateOf(0f) }

    val animTiltX by animateFloatAsState(
        if (pressed && !reduced) tiltX else 0f, spring(dampingRatio = 0.55f, stiffness = 320f), label = "tiltX"
    )
    val animTiltY by animateFloatAsState(
        if (pressed && !reduced) tiltY else 0f, spring(dampingRatio = 0.55f, stiffness = 320f), label = "tiltY"
    )
    val scale by animateFloatAsState(
        if (pressed && !reduced) 0.982f else 1f, spring(stiffness = 420f), label = "scale"
    )

    Box(
        modifier
            .graphicsLayer {
                rotationX = animTiltX
                rotationY = animTiltY
                scaleX = scale
                scaleY = scale
                cameraDistance = 14f * density
            }
            .clip(RoundedCornerShape(corner))
            .background(DsTokens.CardBrush)
            .background(DsTokens.glass(tint))
            .border(1.dp, DsTokens.Hairline, RoundedCornerShape(corner))
            .then(
                if (!interactive) Modifier else Modifier.pointerInput(onClick, onLongClick) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        tiltY = ((down.position.x / w) - 0.5f) * 10f
                        tiltX = -((down.position.y / h) - 0.5f) * 10f
                        pressed = true
                        val up = waitForUpOrCancellation()
                        pressed = false
                        if (up != null) {
                            val travel = (up.position - down.position).getDistance()
                            if (travel < 24f) {
                                if (onLongClick != null && up.uptimeMillis - down.uptimeMillis > 450) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onLongClick()
                                } else {
                                    onClick?.invoke()
                                }
                            }
                        }
                    }
                }
            )
    ) { content() }
}

@Composable
fun CardBody(
    padding: Dp = DsTokens.CardPadding,
    spacing: Dp = DsTokens.GapS,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        Modifier.padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

@Composable
fun SectionTitle(text: String, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(color = DsTokens.InkMuted),
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelSmall.copy(color = DsTokens.Gold))
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(DsTokens.Hairline))
}

// ---------------------------------------------------------------------------
// Motion primitives
// ---------------------------------------------------------------------------

/**
 * Numbers count up when they arrive. Spec §28: animation must communicate —
 * here it communicates that this value was measured, and how big it is.
 */
@Composable
fun AnimatedNumber(
    target: Int,
    style: TextStyle,
    suffix: String = "",
    prefix: String = "",
    durationMs: Int = MotionTokens.COUNT_UP,
    modifier: Modifier = Modifier
) {
    val reduced = rememberReducedMotion()
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(target) { started = true }
    val value by animateFloatAsState(
        targetValue = if (started) target.toFloat() else 0f,
        animationSpec = if (reduced) tween(0) else tween(durationMs, easing = EaseOutCubic),
        label = "counter"
    )
    Text("$prefix${value.roundToInt()}$suffix", style = style, modifier = modifier)
}

/** Staggered entrance: each child rises and fades in a beat after the one above. */
@Composable
fun Reveal(
    index: Int = 0,
    delayPerItemMs: Int = 55,
    content: @Composable () -> Unit
) {
    val reduced = rememberReducedMotion()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = if (reduced) tween(0) else tween(
            durationMillis = MotionTokens.EMPHASIS,
            delayMillis = (index * delayPerItemMs).coerceAtMost(420),
            easing = MotionTokens.EnterEasing
        ),
        label = "reveal"
    )
    Box(
        Modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 26f
        }
    ) { content() }
}

/** A slow travelling sheen — used only on genuinely pending data. */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    val reduced = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX"
    )
    val phase = if (reduced) 0.5f else x
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .drawBehind {
                drawRect(DsTokens.Elevated)
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.07f), Color.Transparent),
                        startX = size.width * (phase - 0.4f),
                        endX = size.width * (phase + 0.4f)
                    )
                )
            }
    )
}

// ---------------------------------------------------------------------------
// Small semantic atoms
// ---------------------------------------------------------------------------

/** Confidence never shown by colour alone (spec §46) — dot plus word. */
@Composable
fun ConfidenceTag(band: ConfidenceBand, modifier: Modifier = Modifier, showLabel: Boolean = true) {
    Row(
        modifier.semantics { contentDescription = "Confidence: ${band.label}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).background(DsTokens.colorFor(band), CircleShape))
        if (showLabel) {
            Spacer(Modifier.width(6.dp))
            Text(band.label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun DsChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = DsTokens.Gold
) {
    val bg by animateFloatAsState(if (selected) 1f else 0f, tween(MotionTokens.FAST), label = "chipBg")
    Box(
        modifier
            .clip(RoundedCornerShape(DsTokens.ChipCorner))
            .background(
                if (selected) accent.copy(alpha = 0.16f + 0.06f * bg) else Color.White.copy(alpha = 0.045f)
            )
            .border(
                1.dp,
                if (selected) accent.copy(alpha = 0.55f) else DsTokens.Hairline,
                RoundedCornerShape(DsTokens.ChipCorner)
            )
            .pointerInput(onClick) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val up = waitForUpOrCancellation()
                    if (up != null) onClick()
                }
            }
            .padding(horizontal = 13.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = if (selected) accent else DsTokens.InkSoft
            )
        )
    }
}

/** A statistic with its unit and, when it matters, the sample it came from. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = DsTokens.Cyan,
    caption: String? = null
) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.035f))
            .border(1.dp, DsTokens.Hairline, RoundedCornerShape(18.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(accent, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(5.dp))
        Text(value, style = MaterialTheme.typography.titleLarge)
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    GlassCard(modifier.fillMaxWidth(), interactive = false) {
        CardBody {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Start)
        }
    }
}

/** Shared helper: a smooth 0→1 progress that restarts whenever [key] changes. */
@Composable
fun rememberEntryProgress(key: Any?, durationMs: Int = 900): Float {
    val reduced = rememberReducedMotion()
    var started by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) { started = true }
    val p by animateFloatAsState(
        if (started) 1f else 0f,
        if (reduced) tween(0) else tween(durationMs, easing = EaseOutCubic),
        label = "entry"
    )
    return p
}

internal fun Offset.distanceTo(other: Offset): Float =
    abs((this - other).getDistance())
