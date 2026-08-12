package com.dhinasuthra.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dhinasuthra.app.DhinaSuthraApp
import com.dhinasuthra.app.core.Permissions
import com.dhinasuthra.app.core.model.DayType
import com.dhinasuthra.app.ui.motion.MotionTokens
import com.dhinasuthra.app.ui.routine.RoutineTemplateSheet
import com.dhinasuthra.app.ui.theme.Ds
import com.dhinasuthra.app.work.Workers
import kotlinx.coroutines.launch

/**
 * Onboarding (product.md §61–66 + experience spec §34/§49O):
 * value → name → how it works → privacy promise → optional routine template
 * (hybrid bootstrapping) → notifications → location → background location with
 * prominent disclosure → activity recognition → done. Every permission is
 * explained before it is requested; declining any of them still lands the user
 * in a working app.
 */
@Composable
fun OnboardingFlow(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val app = DhinaSuthraApp.get(ctx)
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var showTemplate by remember { mutableStateOf(false) }

    fun finish() {
        app.container.settings.userName = name.trim()
        app.container.settings.onboarded = true
        app.container.settings.trackingEnabled = true
        Workers.scheduleAll(ctx)
        scope.launch {
            app.container.sensorPolicyEngine.applyCurrentPolicy()
            app.container.reminderScheduler.ensureDefaultRules()
            app.container.reminderScheduler.planToday()
        }
        onDone()
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { step++ }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { step++ }
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { step++ }
    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { finish() }

    // Spec §47: onboarding respects the same insets as every other screen, so the
    // first thing a new user sees is not clipped by a cutout or a gesture bar.
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 26.dp)
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (slideInHorizontally(tween(MotionTokens.PAGE, easing = MotionTokens.EnterEasing)) { it / 3 } +
                    fadeIn(tween(MotionTokens.PAGE))) togetherWith
                    (slideOutHorizontally(tween(MotionTokens.STANDARD, easing = MotionTokens.ExitEasing)) { -it / 3 } +
                        fadeOut(tween(MotionTokens.FAST)))
            },
            label = "onboarding"
        ) { s ->
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (s) {
                    0 -> StepCard(
                        title = "DhinaSuthra",
                        body = "The thread of your day.\n\nYour phone quietly learns your daily rhythm — when you leave, work, eat, return and rest — entirely on this device. No account. No cloud. No ads.",
                        cta = "Begin"
                    ) { step++ }

                    1 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("What should DhinaSuthra call you?", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text("Stored only on this phone. You can change or clear it anytime.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(18.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(24) },
                            label = { Text("Your name (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(18.dp))
                        PrimaryButton("Continue") { step++ }
                    }

                    2 -> StepCard(
                        title = "How it works",
                        body = "DhinaSuthra watches gentle signals — places you stay, movement between them, screen rest at night — and weaves them into your day's thread. It learns your usual times and only speaks up when it genuinely helps.",
                        cta = "Continue"
                    ) { step++ }

                    3 -> StepCard(
                        title = "Our privacy promise",
                        body = "Everything stays on your phone. DhinaSuthra has no internet permission — it cannot send your data anywhere even if it wanted to. You can export or erase everything at any time.",
                        cta = "Continue"
                    ) { step++ }

                    4 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Know your routine already?", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Optionally tell DhinaSuthra your usual workday times. It starts helpful on day one, then refines everything from what actually happens.",
                            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(18.dp))
                        PrimaryButton("Set my usual times") { showTemplate = true }
                        TextButton(onClick = { step++ }) { Text("Skip — learn as we go", color = Ds.Muted) }
                    }

                    5 -> StepCard(
                        title = "Gentle reminders",
                        body = "DhinaSuthra can nudge you when you're drifting well past your usual rhythm — lunch, leaving office, sleep. Quiet days mean zero notifications.",
                        cta = if (Build.VERSION.SDK_INT >= 33) "Allow notifications" else "Continue"
                    ) {
                        if (Build.VERSION.SDK_INT >= 33 && !Permissions.hasNotifications(ctx)) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else step++
                    }

                    6 -> StepCard(
                        title = "Places need location",
                        body = "Location lets DhinaSuthra recognise Home, Office and other places you choose — using GPS on the phone itself, even with internet off.",
                        cta = "Allow location"
                    ) {
                        if (!Permissions.hasAnyLocation(ctx)) {
                            locationLauncher.launch(arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            ))
                        } else step++
                    }

                    7 -> StepCard(
                        title = "The thread continues in the background",
                        body = "DhinaSuthra collects location data to enable place and routine detection even when the app is closed or not in use.\n\nThis is what makes automatic arrival and departure moments possible. Choose \"Allow all the time\" on the next screen for the full experience. Data never leaves your phone.",
                        cta = "Continue"
                    ) {
                        if (Build.VERSION.SDK_INT >= 29 && !Permissions.hasBackgroundLocation(ctx)) {
                            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        } else step++
                    }

                    else -> StepCard(
                        title = "One last signal",
                        body = "Movement recognition tells DhinaSuthra when you're walking, driving or still — so travel between places is understood without guessing.",
                        cta = "Allow movement & finish"
                    ) {
                        if (Build.VERSION.SDK_INT >= 29 && !Permissions.hasActivityRecognition(ctx)) {
                            activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                        } else finish()
                    }
                }
            }
        }
        Text(
            "${step + 1} / 9",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)
        )
    }

    if (showTemplate) {
        RoutineTemplateSheet(dayType = DayType.WEEKDAY, onDismiss = {
            showTemplate = false
            step++
        })
    }
}

@Composable
private fun StepCard(title: String, body: String, cta: String, onCta: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        PrimaryButton(cta, onCta)
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Ds.Amber, contentColor = Ds.Navy),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) { Text(label, style = MaterialTheme.typography.titleMedium) }
}
