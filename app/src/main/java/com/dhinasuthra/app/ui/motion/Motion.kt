package com.dhinasuthra.app.ui.motion

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Motion design system (experience spec §3): centralized tokens, natural easing,
 * reduced-motion respect (§44). No hard-coded durations scattered through screens,
 * no continuous decorative animation when the user asked for less motion.
 */
object MotionTokens {
    const val FAST = 140
    const val STANDARD = 240
    const val PAGE = 320
    const val EMPHASIS = 420
    const val COUNT_UP = 1000

    val EnterEasing: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)      // ease-out
    val ExitEasing: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)       // ease-in
    val TransformEasing: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f) // ease-in-out
}

/** True when the device animator scale is 0 (system reduced-motion). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember { isReducedMotion(context) }
}

fun isReducedMotion(context: Context): Boolean = try {
    Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) == 0f
} catch (_: Throwable) {
    false
}
