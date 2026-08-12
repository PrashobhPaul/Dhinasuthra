package com.dhinasuthra.app.ui.viz

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.intelligence.ActivityType
import com.dhinasuthra.app.intelligence.DayReconstruction
import com.dhinasuthra.app.intelligence.TimeEpisode
import com.dhinasuthra.app.ui.foundation.rememberEntryProgress
import com.dhinasuthra.app.ui.theme.DsTokens
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The day, drawn.
 *
 * Three views of exactly the same 1440 minutes: a linear ribbon you can scrub, a
 * radial clock you can spin, and a fingerprint strip small enough to sit in a
 * calendar cell. They share one colour vocabulary ([DsTokens.colorFor]) so a
 * violet block means sleep wherever you meet it.
 */

// ---------------------------------------------------------------------------
// The 24-hour ribbon
// ---------------------------------------------------------------------------

@Composable
fun DayRibbon(
    day: DayReconstruction,
    modifier: Modifier = Modifier,
    height: Dp = 76.dp,
    nowMin: Int? = null,
    selectedIndex: Int? = null,
    onSelect: ((Int?) -> Unit)? = null,
    showHourAxis: Boolean = true
) {
    val progress = rememberEntryProgress(day.epochDay, durationMs = 1100)
    val haptics = LocalHapticFeedback.current
    var scrubMin by remember(day.epochDay) { mutableIntStateOf(-1) }
    var lastHapticIndex by remember(day.epochDay) { mutableIntStateOf(-1) }

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(day.epochDay, day.episodes.size) {
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    fun minuteAt(x: Float) = ((x / width) * 1440f).roundToInt().coerceIn(0, 1439)
                    detectTapGestures(
                        onTap = { pos ->
                            val minute = minuteAt(pos.x)
                            val idx = day.episodes.indexOfFirst { minute >= it.startMin && minute < it.endMin }
                            onSelect?.invoke(if (idx >= 0 && idx != selectedIndex) idx else null)
                        }
                    )
                }
                .pointerInput(day.epochDay, day.episodes.size) {
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    fun minuteAt(x: Float) = ((x / width) * 1440f).roundToInt().coerceIn(0, 1439)
                    detectDragGestures(
                        onDragStart = { pos -> scrubMin = minuteAt(pos.x) },
                        onDragEnd = { scrubMin = -1; lastHapticIndex = -1 },
                        onDragCancel = { scrubMin = -1; lastHapticIndex = -1 }
                    ) { change, _ ->
                        val minute = minuteAt(change.position.x)
                        scrubMin = minute
                        val idx = day.episodes.indexOfFirst { minute >= it.startMin && minute < it.endMin }
                        if (idx >= 0 && idx != lastHapticIndex) {
                            lastHapticIndex = idx
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect?.invoke(idx)
                        }
                        change.consume()
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val radius = h / 2.2f

                // Bed
                drawRoundRectCompat(
                    color = Color.White.copy(alpha = 0.04f),
                    topLeft = Offset(0f, 0f),
                    size = Size(w, h),
                    radius = radius
                )

                for ((i, e) in day.episodes.withIndex()) {
                    val x0 = w * (e.startMin / 1440f)
                    val x1 = w * (e.endMin / 1440f)
                    val grown = x0 + (x1 - x0) * progress
                    if (grown - x0 < 0.5f) continue
                    val color = DsTokens.colorFor(e.activity)
                    val selected = selectedIndex == i
                    val inset = if (selected) 0f else h * 0.10f

                    drawRoundRectCompat(
                        brush = Brush.verticalGradient(
                            listOf(
                                color.copy(alpha = if (e.activity == ActivityType.UNKNOWN) 0.30f else 0.98f),
                                color.copy(alpha = if (e.activity == ActivityType.UNKNOWN) 0.16f else 0.55f)
                            )
                        ),
                        topLeft = Offset(x0, inset),
                        size = Size((grown - x0).coerceAtLeast(1f), h - inset * 2),
                        radius = radius * 0.55f
                    )
                    if (selected) {
                        drawRoundRectCompat(
                            color = Color.White.copy(alpha = 0.85f),
                            topLeft = Offset(x0, 0f),
                            size = Size((grown - x0).coerceAtLeast(1f), h),
                            radius = radius * 0.55f,
                            stroke = 1.5.dp.toPx()
                        )
                    }
                }

                // The present moment, drawn as a bright filament.
                if (nowMin != null && nowMin in 0..1440) {
                    val x = w * (nowMin / 1440f)
                    drawLine(
                        Brush.verticalGradient(listOf(DsTokens.GoldSoft, DsTokens.Gold)),
                        Offset(x, -2f), Offset(x, h + 2f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
                    )
                    drawCircle(DsTokens.Gold, 3.5.dp.toPx(), Offset(x, 0f))
                }

                if (scrubMin >= 0) {
                    val x = w * (scrubMin / 1440f)
                    drawLine(Color.White, Offset(x, 0f), Offset(x, h), strokeWidth = 1.5.dp.toPx())
                }
            }
        }

        if (showHourAxis) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(0, 4, 8, 12, 16, 20, 24).forEach { h ->
                    Text(
                        if (h == 24) "24" else "%02d".format(h),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        if (scrubMin >= 0) {
            val e = day.at(scrubMin)
            Spacer(Modifier.height(6.dp))
            Text(
                "${TimeUtils.formatMinuteOfDay(scrubMin)} · ${e?.activity?.label ?: "Unclassified"}" +
                    (e?.placeName?.let { " @ $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium.copy(color = DsTokens.Ink)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The radial day — spin it with a finger
// ---------------------------------------------------------------------------

/**
 * A 24-hour dial: midnight at the top, noon at the bottom, the outer ring showing
 * what happened and the inner ring showing what you planned. Dragging rotates the
 * dial under a fixed pointer, so the readout in the middle changes as you spin —
 * the gesture *is* the query.
 */
@Composable
fun RadialDayClock(
    day: DayReconstruction,
    modifier: Modifier = Modifier,
    plannedRing: List<Pair<IntRange, ActivityType>> = emptyList(),
    nowMin: Int? = null,
    ringWidth: Dp = 22.dp
) {
    val progress = rememberEntryProgress(day.epochDay, durationMs = 1300)
    val haptics = LocalHapticFeedback.current
    var rotation by remember(day.epochDay) { mutableFloatStateOf(0f) }
    var lastReadIndex by remember(day.epochDay) { mutableIntStateOf(-1) }
    val settled by animateFloatAsState(rotation, spring(stiffness = 260f), label = "dialSpin")

    // The pointer sits at the top; rotating the dial by r degrees means the minute
    // under the pointer is the one that would otherwise be drawn at -r.
    val pointerMinute = (((-settled / 360f) * 1440f).roundToInt() % 1440 + 1440) % 1440
    val focus = day.at(pointerMinute)

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(day.epochDay) {
                    detectDragGestures { change, drag ->
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        val from = change.position - drag - centre
                        val to = change.position - centre
                        val delta = (atan2(to.y, to.x) - atan2(from.y, from.x)) * 180f / PI.toFloat()
                        rotation += delta
                        val underPointer = (((-rotation / 360f) * 1440f).roundToInt() % 1440 + 1440) % 1440
                        val idx = day.episodes.indexOfFirst {
                            underPointer >= it.startMin && underPointer < it.endMin
                        }
                        if (idx != lastReadIndex) {
                            lastReadIndex = idx
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        change.consume()
                    }
                }
        ) {
            val stroke = ringWidth.toPx()
            val outerInset = stroke / 2 + 10.dp.toPx()
            val innerInset = outerInset + stroke * 1.25f
            val arcSize = Size(size.width - outerInset * 2, size.height - outerInset * 2)
            val innerSize = Size(size.width - innerInset * 2, size.height - innerInset * 2)

            // Track
            drawArc(
                color = Color.White.copy(alpha = 0.05f), startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(outerInset, outerInset), size = arcSize, style = Stroke(stroke)
            )

            fun sweepFor(range: IntRange) = ((range.last - range.first) / 1440f) * 360f
            fun startFor(minute: Int) = -90f + (minute / 1440f) * 360f + settled

            for (e in day.episodes) {
                val sweep = sweepFor(e.startMin..e.endMin) * progress
                if (sweep <= 0.2f) continue
                val color = DsTokens.colorFor(e.activity)
                drawArc(
                    color = color.copy(alpha = if (e.activity == ActivityType.UNKNOWN) 0.22f else 0.95f),
                    startAngle = startFor(e.startMin), sweepAngle = sweep, useCenter = false,
                    topLeft = Offset(outerInset, outerInset), size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Butt)
                )
            }

            // Planned ring — what you said you'd do, drawn thinner and inside.
            for ((range, activity) in plannedRing) {
                val sweep = sweepFor(range) * progress
                if (sweep <= 0.2f) continue
                drawArc(
                    color = DsTokens.colorFor(activity).copy(alpha = 0.55f),
                    startAngle = startFor(range.first), sweepAngle = sweep, useCenter = false,
                    topLeft = Offset(innerInset, innerInset), size = innerSize,
                    style = Stroke(stroke * 0.42f, cap = StrokeCap.Round)
                )
            }

            // Hour ticks
            val centre = Offset(size.width / 2f, size.height / 2f)
            val tickOuter = size.minDimension / 2f - outerInset + stroke / 2 + 4.dp.toPx()
            for (hour in 0 until 24) {
                val major = hour % 6 == 0
                val angle = Math.toRadians((-90f + (hour / 24f) * 360f + settled).toDouble())
                val len = if (major) 8.dp.toPx() else 4.dp.toPx()
                val p1 = Offset(
                    centre.x + cos(angle).toFloat() * tickOuter,
                    centre.y + sin(angle).toFloat() * tickOuter
                )
                val p2 = Offset(
                    centre.x + cos(angle).toFloat() * (tickOuter + len),
                    centre.y + sin(angle).toFloat() * (tickOuter + len)
                )
                drawLine(
                    Color.White.copy(alpha = if (major) 0.5f else 0.18f), p1, p2,
                    strokeWidth = if (major) 2f else 1f
                )
            }

            // Now hand
            if (nowMin != null) {
                val angle = Math.toRadians((-90f + (nowMin / 1440f) * 360f + settled).toDouble())
                val r = size.minDimension / 2f - outerInset - stroke / 2
                drawLine(
                    DsTokens.Gold, centre,
                    Offset(centre.x + cos(angle).toFloat() * r, centre.y + sin(angle).toFloat() * r),
                    strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round
                )
            }

            // The fixed pointer at the top — the thing you spin the day past.
            val pointer = Path().apply {
                moveTo(centre.x, outerInset - 10.dp.toPx())
                lineTo(centre.x - 6.dp.toPx(), outerInset - 20.dp.toPx())
                lineTo(centre.x + 6.dp.toPx(), outerInset - 20.dp.toPx())
                close()
            }
            drawPath(pointer, DsTokens.GoldSoft)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(TimeUtils.formatMinuteOfDay(pointerMinute), style = MaterialTheme.typography.displaySmall)
            Text(
                focus?.activity?.label ?: "Unclassified",
                style = MaterialTheme.typography.titleSmall.copy(
                    color = DsTokens.colorFor(focus?.activity ?: ActivityType.UNKNOWN)
                )
            )
            if (focus != null) {
                Text(
                    "${focus.rangeLabel()} · ${focus.durationLabel()}",
                    style = MaterialTheme.typography.labelSmall
                )
                focus.placeName?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            Spacer(Modifier.height(4.dp))
            Text("Drag to explore", style = MaterialTheme.typography.labelSmall.copy(color = DsTokens.InkFaint))
        }
    }
}

// ---------------------------------------------------------------------------
// Day DNA — a whole day in a strip you can put in a calendar cell
// ---------------------------------------------------------------------------

@Composable
fun DayDna(
    fingerprint: List<ActivityType>,
    modifier: Modifier = Modifier,
    height: Dp = 26.dp,
    corner: Dp = 6.dp
) {
    Canvas(modifier.height(height)) {
        if (fingerprint.isEmpty()) return@Canvas
        val slotWidth = size.width / fingerprint.size
        for ((i, activity) in fingerprint.withIndex()) {
            drawRect(
                color = DsTokens.colorFor(activity).copy(
                    alpha = if (activity == ActivityType.UNKNOWN) 0.18f else 0.92f
                ),
                topLeft = Offset(i * slotWidth, 0f),
                size = Size(slotWidth + 0.6f, size.height)
            )
        }
    }
}

/** A vertical variant used as the spine of the timeline list. */
@Composable
fun EpisodeSpine(
    episode: TimeEpisode,
    isFirst: Boolean,
    isLast: Boolean,
    isNow: Boolean,
    modifier: Modifier = Modifier
) {
    val color = DsTokens.colorFor(episode.activity)
    Canvas(modifier) {
        val cx = size.width / 2f
        val nodeY = 22.dp.toPx()
        val strokeW = 2.dp.toPx()
        if (!isFirst) {
            drawLine(color.copy(alpha = 0.35f), Offset(cx, 0f), Offset(cx, nodeY - 9.dp.toPx()), strokeW)
        }
        if (!isLast) {
            drawLine(color.copy(alpha = 0.35f), Offset(cx, nodeY + 9.dp.toPx()), Offset(cx, size.height), strokeW)
        }
        if (isNow) drawCircle(color.copy(alpha = 0.22f), 12.dp.toPx(), Offset(cx, nodeY))
        drawCircle(color, 6.dp.toPx(), Offset(cx, nodeY))
        drawCircle(DsTokens.Base, 2.6.dp.toPx(), Offset(cx, nodeY))
    }
}

@Composable
fun ActivityLegend(
    entries: List<Pair<ActivityType, String>>,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { (activity, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(DsTokens.colorFor(activity), CircleShape))
                Spacer(Modifier.width(9.dp))
                Text(
                    activity.label,
                    style = MaterialTheme.typography.bodySmall.copy(color = DsTokens.InkSoft),
                    modifier = Modifier.weight(1f)
                )
                Text(label, style = MaterialTheme.typography.labelMedium.copy(color = DsTokens.Ink))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Small drawing helpers
// ---------------------------------------------------------------------------

internal fun DrawScope.drawRoundRectCompat(
    color: Color,
    topLeft: Offset,
    size: Size,
    radius: Float,
    stroke: Float = 0f
) {
    if (stroke > 0f) {
        drawRoundRect(
            color = color, topLeft = topLeft, size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
            style = Stroke(stroke)
        )
    } else {
        drawRoundRect(
            color = color, topLeft = topLeft, size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
        )
    }
}

internal fun DrawScope.drawRoundRectCompat(
    brush: Brush,
    topLeft: Offset,
    size: Size,
    radius: Float
) {
    drawRoundRect(
        brush = brush, topLeft = topLeft, size = size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
    )
}
