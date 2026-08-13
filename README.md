# DhinaSuthra — The thread of your day

A privacy-first Android app that quietly learns your daily routine — leave home,
office, lunch, return, sleep — **entirely on your phone**, and turns it into a
living thread: animated timeline, routine insights, adaptive gentle reminders.

**No internet permission. No account. No cloud. No ads.** The APK physically
cannot transmit your data.

---

## V2 — time intelligence, routines and the Rule Book

V2 rebuilds the product around one idea: **a day is 1440 minutes, and every one of
them should be accounted for honestly.**

| Layer | What's inside |
|---|---|
| Time model | Canonical `TimeEpisode` set per day: activity + location + confidence + status (observed / inferred / confirmed / corrected). Every day reconciles to exactly 24 hours, with unexplained time labelled rather than invented |
| Three lenses (§6) | Activity, Location, and Activity @ Location — never mixed. "Sleep 8h" and "Home 15h" can no longer appear in the same chart |
| Rule Book | 131 deterministic rules across 12 domains, each with an id, an IF/THEN statement, an evidence weight and the class that enforces it — browsable in the app, and asserted by unit tests |
| Reconciliation (§9–§10) | Geofence crossings raise *candidates*; only dwell, continuity and context confirm them. Park → walk → home is one arrival, not four events |
| Sleep (§11) | Alarm dismissal is wake *intent*. Sleep runs to a wake confirmed by movement, repeated interaction or departure — with the gap between the first stir and getting up recorded |
| Meals (§12) | Lunch is learned from your own timing, duration, place and repetition. With no evidence the window stays unclassified — 13:00 is never assumed |
| Work (§13) | Office presence decomposes into work / meeting / lunch / break / unclassified. Presence and activity are always reported as two different numbers |
| Patterns (§16, §38) | Median, IQR, SD, percentiles, consistency, outlier exclusion, and a lifecycle: Learning → Emerging → Established, plus Unstable and Stale |
| Routines (§18, §20) | Established patterns can be saved as timetables with per-entry tolerance; planned vs actual, transparent adherence, and drift proposals instead of nagging |
| Narration (§2, §51) | Insights are ranked, hedged in proportion to confidence, and carry the measurement and rules behind them. Silence is a valid output |
| Time Lab (§25–§27) | Range × lens × view: ribbons, a spinnable radial clock, 7×24 and calendar heatmaps, day fingerprints, histograms, box plots, scatter, similarity matrices, trends, and a gesture-driven 3D time landscape with a 2D fallback |
| Android UI (§47) | Edge-to-edge with real `WindowInsets.safeDrawing` on every screen and `shortEdges` cutout mode — no hard-coded top padding anywhere |

Everything above runs on-device from your own signals. There is still no network
permission, no account, no model file and no cloud inference.

## Milestone 1 foundations (still here)

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
  intelligence/ V2 core: rule book, episode model, reconciler, interpreters,
                pattern/statistics engines, lenses, narration, timetables
  ui/          Compose: design tokens, safe-area foundations, visualisation
               library (ribbon, radial, heatmaps, charts, 3D landscape),
               Today / Timeline / Insights / Routine / Time Lab / More, onboarding
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

## Honest gaps

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
