# Play Store readiness notes (M1 → release)

- Background location: the onboarding step before the system dialog is the
  prominent disclosure; Play Console declaration must mirror its wording
  ("collects location data to enable place and routine detection even when the
  app is closed or not in use").
- Data safety form: no data collected/shared off-device; data stored locally;
  deletion path: More → Delete all my data.
- Release builds: add signing config + `minifyEnabled true` with the existing
  proguard rules; re-run audit scripts on the release merged manifest.
- Foreground services: none used; no special declaration needed.
- Target SDK 35 satisfies current Play target requirements.

## Requested permissions (merged manifest, audited)

| Permission | Origin | Justification |
|---|---|---|
| ACCESS_COARSE/FINE_LOCATION | app | Recognise Home/Office/places (§11) |
| ACCESS_BACKGROUND_LOCATION | app | Arrival/departure while app closed — prominent disclosure precedes the request (§13/§39) |
| ACTIVITY_RECOGNITION | app | Walking/driving/still transitions (§16.3) |
| POST_NOTIFICATIONS | app | Local routine reminders (§10) |
| RECEIVE_BOOT_COMPLETED | app | Reboot recovery of schedules (§31) |
| WAKE_LOCK | WorkManager | Partial wake lock during scheduled jobs |
| ACCESS_NETWORK_STATE | WorkManager/play-services | Constraint signal only; app holds no INTERNET, so nothing can transmit |
| FOREGROUND_SERVICE | WorkManager | Capability for expedited jobs; DhinaSuthra starts no foreground service and uses no expedited work |
