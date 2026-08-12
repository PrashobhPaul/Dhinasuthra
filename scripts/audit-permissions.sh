#!/usr/bin/env bash
# Permission audit (product spec §75): fail the build if the merged manifest
# REQUESTS a permission that is not on the reviewed allowlist. Only
# <uses-permission> elements count; android:permission binding attributes on
# platform services (e.g. WorkManager's BIND_JOB_SERVICE/DUMP) are not requests.
set -euo pipefail
MERGED="app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"
[ -f "$MERGED" ] || MERGED="app/build/intermediates/merged_manifest/debug/AndroidManifest.xml"
if [ ! -f "$MERGED" ]; then echo "Merged manifest not found — run assembleDebug first"; exit 2; fi
ACTUAL=$(grep '<uses-permission' "$MERGED" | grep -o 'android\.permission\.[A-Z_]*' | sort -u)
ALLOW=$(grep -v '^#' scripts/permission-allowlist.txt | sort -u)
EXTRA=$(comm -23 <(echo "$ACTUAL") <(echo "$ALLOW") || true)
if [ -n "$EXTRA" ]; then
  echo "❌ Unreviewed requested permissions:"; echo "$EXTRA"; exit 1
fi
echo "✅ Requested permissions match the allowlist:"; echo "$ACTUAL"
