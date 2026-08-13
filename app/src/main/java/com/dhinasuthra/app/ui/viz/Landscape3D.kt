package com.dhinasuthra.app.ui.viz

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.intelligence.ActivityType
import com.dhinasuthra.app.ui.foundation.rememberEntryProgress
import com.dhinasuthra.app.ui.theme.DsTokens
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Time Landscape (spec §27).
 *
 * X is the calendar day, Y is the hour, Z is how much of that hour was spent on
 * something. Weeks of routine become a terrain: the ridge at 09:00 is your commute,
 * the plateau across the afternoon is work, and the canyon at the weekend is
 * visible from across the room.
 *
 * Rendering is a hand-rolled orthographic projection on a Compose Canvas — a few
 * hundred quads sorted back to front. No OpenGL, no external engine, and a 2D
 * fallback one flag away for devices or people who would rather not.
 */

data class LandscapeCell(
    val dayIndex: Int,
    val hour: Int,
    /** 0..1 intensity — usually minutes of the hour that were classified. */
    val height: Float,
    val activity: ActivityType
)

@Composable
fun TimeLandscape3D(
    cells: List<LandscapeCell>,
    dayCount: Int,
    modifier: Modifier = Modifier,
    height: Dp = 300.dp,
    onReset: (() -> Unit)? = null
) {
    var yaw by remember { mutableFloatStateOf(-0.55f) }
    var pitch by remember { mutableFloatStateOf(0.62f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val progress = rememberEntryProgress(cells.size, durationMs = 1400)

    val animYaw by animateFloatAsState(yaw, spring(stiffness = 220f), label = "yaw")
    val animPitch by animateFloatAsState(pitch, spring(stiffness = 220f), label = "pitch")
    val animZoom by animateFloatAsState(zoom, spring(stiffness = 220f), label = "zoom")

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        yaw += pan.x * 0.006f
                        pitch = (pitch - pan.y * 0.004f).coerceIn(0.15f, 1.35f)
                        zoom = (zoom * gestureZoom).coerceIn(0.6f, 2.6f)
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (cells.isEmpty() || dayCount <= 0) return@Canvas

                val cx = size.width / 2f
                val cy = size.height * 0.60f
                val spanX = (size.width * 0.78f * animZoom) / dayCount.coerceAtLeast(1)
                val spanY = (size.height * 0.62f * animZoom) / 24f
                val zScale = size.height * 0.34f * animZoom

                val cosY = cos(animYaw)
                val sinY = sin(animYaw)
                val cosP = cos(animPitch)
                val sinP = sin(animPitch)

                fun project(day: Float, hour: Float, z: Float): Offset {
                    val x = (day - dayCount / 2f) * spanX
                    val y = (hour - 12f) * spanY
                    val xr = x * cosY - y * sinY
                    val yr = x * sinY + y * cosY
                    return Offset(cx + xr, cy + yr * cosP - z * zScale * sinP)
                }

                /** Painter's algorithm: draw the far cells first. */
                fun depth(day: Float, hour: Float): Float {
                    val x = (day - dayCount / 2f) * spanX
                    val y = (hour - 12f) * spanY
                    return x * sinY + y * cosY
                }

                // Ground grid
                for (h in 0..24 step 6) {
                    val a = project(0f, h.toFloat(), 0f)
                    val b = project(dayCount.toFloat(), h.toFloat(), 0f)
                    drawLine(Color.White.copy(alpha = 0.10f), a, b, 1f)
                }
                val dayStep = (dayCount / 6).coerceAtLeast(1)
                for (d in 0..dayCount step dayStep) {
                    val a = project(d.toFloat(), 0f, 0f)
                    val b = project(d.toFloat(), 24f, 0f)
                    drawLine(Color.White.copy(alpha = 0.07f), a, b, 1f)
                }

                val sorted = cells.sortedBy { depth(it.dayIndex.toFloat(), it.hour.toFloat()) }
                for (cell in sorted) {
                    val z = (cell.height * progress).coerceIn(0f, 1f)
                    if (z <= 0.02f) continue
                    val d0 = cell.dayIndex.toFloat()
                    val d1 = d0 + 0.92f
                    val h0 = cell.hour.toFloat()
                    val h1 = h0 + 0.92f

                    val topA = project(d0, h0, z)
                    val topB = project(d1, h0, z)
                    val topC = project(d1, h1, z)
                    val topD = project(d0, h1, z)
                    val baseB = project(d1, h0, 0f)
                    val baseC = project(d1, h1, 0f)
                    val baseD = project(d0, h1, 0f)

                    val colour = DsTokens.colorFor(cell.activity)

                    // Two visible side faces, shaded down, then the lit top face.
                    drawPath(quad(topB, topC, baseC, baseB), colour.copy(alpha = 0.35f + 0.25f * z))
                    drawPath(quad(topC, topD, baseD, baseC), colour.copy(alpha = 0.22f + 0.2f * z))
                    val top = quad(topA, topB, topC, topD)
                    drawPath(top, colour.copy(alpha = 0.6f + 0.4f * z))
                    drawPath(top, Color.White.copy(alpha = 0.10f), style = Stroke(0.8f))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Drag to orbit · pinch to zoom",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                "Reset view",
                style = MaterialTheme.typography.labelSmall.copy(color = DsTokens.Gold),
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures {
                        yaw = -0.55f; pitch = 0.62f; zoom = 1f
                        onReset?.invoke()
                    }
                }
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text("X · days", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text("Y · hours", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text("Z · time classified", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun quad(a: Offset, b: Offset, c: Offset, d: Offset): Path = Path().apply {
    moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
}

/**
 * The mandated 2D fallback (spec §27): the same matrix as a flat terrain map,
 * for low-end devices, accessibility, or simply preferring to read it flat.
 */
@Composable
fun TimeLandscape2D(
    cells: List<LandscapeCell>,
    dayCount: Int,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp
) {
    val progress = rememberEntryProgress(cells.size, durationMs = 900)
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (cells.isEmpty() || dayCount <= 0) return@Canvas
        val cw = size.width / dayCount
        val ch = size.height / 24f
        for (cell in cells) {
            val intensity = (cell.height * progress).coerceIn(0f, 1f)
            drawRoundRectCompat(
                color = DsTokens.colorFor(cell.activity).copy(alpha = 0.08f + 0.88f * intensity),
                topLeft = Offset(cell.dayIndex * cw, cell.hour * ch),
                size = androidx.compose.ui.geometry.Size(cw - 0.6f, ch - 0.6f),
                radius = 1.5f
            )
        }
    }
}
