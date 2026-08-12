#!/usr/bin/env bash
# Network audit (product spec §41/§75): the app must never hold INTERNET.
set -euo pipefail
MERGED="app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"
[ -f "$MERGED" ] || MERGED="app/build/intermediates/merged_manifest/debug/AndroidManifest.xml"
if [ ! -f "$MERGED" ]; then echo "Merged manifest not found — run assembleDebug first"; exit 2; fi
if grep -q 'android.permission.INTERNET' "$MERGED"; then
  echo "❌ INTERNET permission leaked into the merged manifest"; exit 1
fi
echo "✅ No INTERNET permission — data cannot leave the device"
