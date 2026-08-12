package com.dhinasuthra.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.TimeUtils
import com.dhinasuthra.app.ui.insights.InsightsScreen
import com.dhinasuthra.app.ui.more.MoreScreen
import com.dhinasuthra.app.ui.onboarding.OnboardingFlow
import com.dhinasuthra.app.ui.places.PlacesScreen
import com.dhinasuthra.app.ui.routine.RoutineScreen
import com.dhinasuthra.app.ui.theme.DhinaSuthraTheme
import com.dhinasuthra.app.ui.theme.Ds
import com.dhinasuthra.app.ui.timeline.TimelineScreen
import com.dhinasuthra.app.ui.today.TodayScreen
import kotlinx.coroutines.launch

/**
 * Primary navigation (experience spec §4–§10): five destinations, tap +
 * horizontal swipe via HorizontalPager (finger-tracking, velocity-aware,
 * direction-locked against vertical scroll — §6–§8 are what Compose's pager
 * implements), with a sliding tab indicator driven by page offset (§9).
 * Swipe is never the only mechanism (§44): every tab is an accessible button.
 * onResume reconciles state (§42).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DhinaSuthraTheme {
                val app = DhinaSuthraApp.get(this)
                var onboarded by remember { mutableStateOf(app.container.settings.onboarded) }
                Surface(Modifier.fillMaxSize(), color = Ds.Navy) {
                    if (!onboarded) {
                        OnboardingFlow(onDone = { onboarded = true })
                    } else {
                        MainScaffold()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // §42 App resume: read local date, reconcile day rollup + reminder plan.
        val app = DhinaSuthraApp.get(this)
        if (app.container.settings.onboarded) {
            lifecycleScope.launch {
                runCatching {
                    app.container.analyticsEngine.dailyRollup(TimeUtils.epochDay())
                    app.container.reminderScheduler.planToday()
                }
            }
        }
    }
}

private data class Dest(val label: String, val icon: ImageVector)

@Composable
private fun MainScaffold() {
    val dests = listOf(
        Dest("Today", Icons.Filled.Home),
        Dest("Timeline", Icons.Filled.Timeline),
        Dest("Insights", Icons.Filled.Insights),
        Dest("Routine", Icons.Filled.Schedule),
        Dest("Places", Icons.Filled.Place),
        Dest("More", Icons.Filled.MoreHoriz)
    )
    val pagerState = rememberPagerState(pageCount = { dests.size })
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            beyondViewportPageCount = 1,   // §43: neighbours stay alive, gestures aren't fought
            key = { it }
        ) { page ->
            when (page) {
                0 -> TodayScreen()
                1 -> TimelineScreen()
                2 -> InsightsScreen()
                3 -> RoutineScreen()
                4 -> PlacesScreen()
                else -> MoreScreen()
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
 * Custom bottom bar with a thread-gold indicator that translates continuously
 * with the pager offset (§9): the indicator physically follows the swipe.
 */
@Composable
private fun ThreadNavBar(
    dests: List<Dest>,
    currentPage: Int,
    pageOffset: Float,
    onSelect: (Int) -> Unit
) {
    Surface(color = Ds.NavyRaised, tonalElevation = 4.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding()) {
            val itemWidth = maxWidth / dests.size
            val indicatorWidth = 34.dp
            val x = itemWidth * (currentPage + pageOffset) + (itemWidth - indicatorWidth) / 2

            Column {
                Box(
                    Modifier
                        .offset(x = x)
                        .width(indicatorWidth)
                        .height(3.dp)
                        .background(Ds.Amber, RoundedCornerShape(2.dp))
                )
                Row(Modifier.fillMaxWidth()) {
                    dests.forEachIndexed { i, d ->
                        val selected = i == currentPage
                        Column(
                            Modifier
                                .weight(1f)
                                .height(62.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onSelect(i) }
                                .semantics { contentDescription = d.label },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(8.dp))
                            Icon(
                                d.icon, contentDescription = null,
                                tint = if (selected) Ds.Amber else Ds.Muted,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                d.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) Ds.Cream else Ds.Muted
                            )
                        }
                    }
                }
            }
        }
    }
}
