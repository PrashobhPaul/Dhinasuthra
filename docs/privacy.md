# DhinaSuthra privacy model

**Principle: acquire locally, understand locally, store locally.**

- No `INTERNET` permission — enforced by `scripts/audit-network.sh` in CI.
- Sensors used: location (GNSS works offline), activity transitions, screen
  interactive state, charging state. Never: app usage, contacts, messages,
  microphone, camera.
- All data lives in a local Room database (`dhinasuthra.db`).
- Retention: raw sensor rows 60 days, context segments 180 days, derived
  summaries/patterns kept (they contain no coordinates).
- Notifications are generated on-device; a "discreet" mode hides content on the
  lock screen.
- The user can pause tracking (1h / today), switch it off, export everything
  (JSON/CSV), or delete everything. Deleting a place forgets its geofence.
- Coordinates are internal; the UI speaks in place names (§49P.14).
