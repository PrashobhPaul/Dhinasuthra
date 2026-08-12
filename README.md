# DhinaSuthra — The thread of your day

A privacy-first Android app that quietly learns your daily routine — leave home,
office, lunch, return, sleep — **entirely on your phone**, and turns it into a
living thread: animated timeline, routine insights, adaptive gentle reminders.

**No internet permission. No account. No cloud. No ads.** The APK physically
cannot transmit your data.

---

## Milestone 1 scope (this build)

| Layer | What's inside |
|---|---|
| Sensing | Geofencing, Activity Recognition transitions, 15-min context samples (screen/charging/last-known location), boot + timezone recovery |
| Context fusion | Debounced state machine → HOME / OFFICE / TRAVEL / KNOWN_PLACE / UNKNOWN_STAY segments in Room |
| Place intelligence (§49P) | On-device stay clustering, home/office hypothesis, discovery suggestions, save-current-location, custom names + categories (incl. religious places), confirm/correct/delete → geofences regenerate |
| Routine learning | Per-event robust stats (median, p10–p90, IQR-driven confidence), weekday/weekend split, cross-midnight sleep normalization |
| Routine templates (§49C–F) | "Tell DhinaSuthra your routine" — user times act as decaying Bayesian priors (5 pseudo-observations), usable immediately for Next Up + reminders, never presented as observed |
| Manual correction (§49A) | Add moments to any past day, delete manual entries, provenance dots, automatic recalculation of summaries + patterns + reminders |
| Reminders | AlarmManager local notifications, gentle→significant escalation, cooldowns, context-driven cancellation, snooze / "It's intentional" actions, discreet lock-screen mode |
| Analytics | Daily rollups, routine-match scoring, weekly consistency bars, time-split donut, time-leakage deltas, honest unknown-time reporting (§49N) |
| Experience (§3–§14) | 6-tab swipe pager with finger-tracking indicator, MotionTokens + reduced-motion support, greeting engine (name + live context + routine), animated count-ups/charts, press-tilt cards |
| Personalization (§34–§36) | Local preferred name, context-aware greetings, dynamic Next Up |
| Privacy controls | Pause 1h/today, master tracking switch, privacy dashboard, JSON/CSV export, full delete |
| Dev | Debug-only 21-day simulator that drives the **real** pipeline (§58), unit tests, permission/network audit scripts, GitHub Actions CI |

## Build & install

Requirements: JDK 17+ (21 recommended), Android SDK 35.

```bash
# from repo root
gradle assembleDebug            # or ./gradlew if wrapper present
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

On the phone (no adb): copy the APK over, open it, allow "install unknown apps"
for your file manager. It's a debug-signed build — Play Protect may ask you to
confirm.

### First run
1. Complete onboarding — for the full experience choose **"Allow all the time"**
   for location (background arrival/departure detection needs it).
2. Real learning takes days. To see every dashboard immediately:
   **More → Developer · Simulator → Load 21 days** (debug builds only) — this
   pushes three synthetic weeks through the actual sensing→context→learning→
   reminder pipeline, not canned UI data.

### Battery-restricted OEMs
Xiaomi/Oppo/Vivo/OnePlus aggressively kill background apps. If moments stop
appearing, exempt DhinaSuthra from battery optimization. The app never uses a
permanent foreground service or other keep-alive hacks (§19).

## Repository layout

```
app/src/main/java/com/dhinasuthra/app/
  core/        models, Room database, time provider, utils
  context/     context fusion state machine, sleep estimator
  places/      place learner (clustering, hypotheses, suggestions)
  routine/     stats, learning engine (+ priors), adherence, greetings, timeline editor
  reminders/   scheduler, receivers, notification copy
  sensing/     geofencing, activity recognition, policy engine, boot receiver
  work/        WorkManager jobs (samples, rollups, reminder planning)
  analytics/   daily rollup + summary writer
  export/      JSON/CSV export, delete-all
  simulate/    debug-only synthetic-day driver
  ui/          Compose: theme, motion tokens, components, 6 screens, onboarding
```

## Push to GitHub

```bash
cd dhinasuthra
git init -b main
git add .
git commit -m "DhinaSuthra M1: on-device routine intelligence"
git remote add origin git@github.com:<you>/dhinasuthra.git
git push -u origin main
```
CI builds the APK, runs unit tests, and enforces the permission/network audits
on every push.

## Honest gaps → Milestone 2

- WFH / travel-day routine clusters (§49C day-pattern variants beyond weekday/weekend)
- Routine change detection with "Routine Change Suspected" transition state (§49I)
- Gap-fill TRAVEL inference between known places after process death (§20/§49P.22) — currently gaps stay honestly UNKNOWN
- Place merge / split tooling (§49P.9–10)
- Bulk historical entry UX (§49A.10) — single-day backdated entry works today
- Adaptive tolerance learned from dismissal behaviour
- Full string-resource extraction for Malayalam/Hindi/Telugu/Tamil/Kannada (§39) — copy currently lives in Kotlin; extraction is mechanical
- Day-replay animation, deeper accessibility pass, OEM battery help screens
- Import/restore of exported data

## Privacy model

See `docs/privacy.md`. Short version: sensors → Room on device → engines on
device → notifications on device. Raw sensor rows pruned at 60 days, context at
180. Export and delete are always one tap away. There is no code path to the
network, and CI fails if `INTERNET` ever appears in the merged manifest.
