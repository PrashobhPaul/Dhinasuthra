package com.dhinasuthra.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.ui.foundation.DsSafeArea
import com.dhinasuthra.app.ui.insights.InsightsScreen
import com.dhinasuthra.app.ui.lab.TimeLabScreen
import com.dhinasuthra.app.ui.settings.SettingsScreen
import com.dhinasuthra.app.ui.onboarding.OnboardingFlow
import com.dhinasuthra.app.ui.routine.RoutineScreen
import com.dhinasuthra.app.ui.theme.DhinaSuthraTheme
import com.dhinasuthra.app.ui.theme.DsTokens
import com.dhinasuthra.app.ui.timeline.TimelineScreen
import com.dhinasuthra.app.ui.today.TodayScreen
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/**
 * Shell and navigation (spec §48).
 *
 * Five destinations, swipeable and tappable. Settings lives behind the gear on
 * Today rather than costing a tab. Edge-to-edge is enabled here and the
 * insets are consumed by the screens themselves ([DsSafeArea]) and by the tab bar
 * below — the window is never padded blindly, so a punch-hole phone, a notched
 * phone and a gesture-navigation phone each get exactly the space they need.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DhinaSuthraTheme {
                val app = DhinaSuthraApp.get(this)
                var onboarded by remember { mutableStateOf(app.container.settings.onboarded) }
                Surface(Modifier.fillMaxSize(), color = DsTokens.Base) {
                    Box(Modifier.fillMaxSize().background(BackdropBrush)) {
                        if (!onboarded) {
                            OnboardingFlow(onDone = { onboarded = true })
                        } else {
                            MainScaffold()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = DhinaSuthraApp.get(this)
        if (app.container.settings.onboarded) {
            lifecycleScope.launch {
                runCatching {
                    app.container.analyticsEngine.dailyRollup(TimeUtils.epochDay())
                    app.container.reminderScheduler.planToday()
                    app.container.timeIntelligence.invalidate()
                }
            }
        }
    }
}

/** A very soft radial wash so the dark background has depth rather than being flat black. */
private val BackdropBrush = Brush.verticalGradient(
    listOf(Color(0xFF0E1830), DsTokens.Base, Color(0xFF080D1A))
)

private data class Dest(val label: String, val icon: ImageVector)

@Composable
private fun MainScaffold() {
    val dests = listOf(
        Dest("Today", Icons.Filled.Home),
        Dest("Timeline", Icons.Filled.Timeline),
        Dest("Insights", Icons.Filled.AutoAwesome),
        Dest("Routine", Icons.Filled.Schedule),
        Dest("Lab", Icons.Filled.Science)
    )
    val pagerState = rememberPagerState(pageCount = { dests.size })
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }

    // Settings is a destination you visit, not a place you live — so it takes the
    // whole screen and hands it back, instead of costing a permanent tab.
    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(onBack = { showSettings = false })
        return
    }

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            beyondViewportPageCount = 1,
            key = { it }
        ) { page ->
            // Pages drift and fade slightly as they pass, so the swipe has physicality.
            val offset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
            Box(
                Modifier.graphicsLayer {
                    val distance = offset.absoluteValue.coerceIn(0f, 1f)
                    alpha = 1f - distance * 0.35f
                    scaleX = 1f - distance * 0.04f
                    scaleY = 1f - distance * 0.04f
                }
            ) {
                when (page) {
                    0 -> TodayScreen(onOpenSettings = { showSettings = true })
                    1 -> TimelineScreen()
                    2 -> InsightsScreen()
                    3 -> RoutineScreen()
                    else -> TimeLabScreen()
                }
            }
        }
        ThreadNavBar(
            dests = dests,
            currentPage = pagerState.currentPage,
            pageOffset = pagerState.currentPageOffsetFraction,
            onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } }
        )
    }
}

/**
 * The tab bar: a floating glass rail whose indicator tracks the swipe continuously
 * rather than snapping when the page settles. Bottom and side insets are consumed
 * here so it clears gesture navigation on every device.
 */
@Composable
private fun ThreadNavBar(
    dests: List<Dest>,
    currentPage: Int,
    pageOffset: Float,
    onSelect: (Int) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(DsSafeArea.bottomAndSides)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xF01A2236))
                .border(1.dp, DsTokens.Hairline, RoundedCornerShape(26.dp))
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val itemWidth = maxWidth / dests.size
                val indicatorWidth = itemWidth * 0.62f
                val x = itemWidth * (currentPage + pageOffset) + (itemWidth - indicatorWidth) / 2

                Column {
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .offset(x = x)
                            .width(indicatorWidth)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(DsTokens.GoldBrush)
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        dests.forEachIndexed { index, dest ->
                            val selected = index == currentPage
                            val scale by animateFloatAsState(
                                if (selected) 1f else 0.92f,
                                spring(stiffness = 400f),
                                label = "tabScale"
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .height(58.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelect(index)
                                    }
                                    .semantics { contentDescription = dest.label },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Spacer(Modifier.height(9.dp))
                                Icon(
                                    dest.icon,
                                    contentDescription = null,
                                    tint = if (selected) DsTokens.Gold else DsTokens.InkMuted,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .graphicsLayer { scaleX = scale; scaleY = scale }
                                )
                                Spacer(Modifier.height(3.dp))
                                // One line, centred, never allowed to bleed into its
                                // neighbour however narrow the screen gets.
                                Text(
                                    dest.label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (selected) DsTokens.Ink else DsTokens.InkMuted
                                    ),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
