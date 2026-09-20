#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
STRINGS="$ROOT/app/src/main/res/values/strings.xml"
SAFEHOME="$ROOT/app/src/main/java/it/edilmilan/ge360/SafeHomeFragment.kt"

[ -f "$MANIFEST" ] || { echo "Missing AndroidManifest.xml" >&2; exit 1; }
[ -f "$STRINGS" ] || { echo "Missing strings.xml" >&2; exit 1; }
[ -f "$SAFEHOME" ] || { echo "Missing SafeHomeFragment.kt" >&2; exit 1; }

grep -q 'EDIL MILAN STEVIC' "$STRINGS"
grep -q 'android:label="@string/app_name"' "$MANIFEST"
grep -q 'android:icon="@mipmap/ic_launcher"' "$MANIFEST"
grep -q 'android:roundIcon="@mipmap/ic_launcher_round"' "$MANIFEST"
grep -q 'android:logo="@drawable/edil_milan_stevic_logo"' "$MANIFEST"

test -s "$ROOT/app/src/main/res/drawable-nodpi/edil_milan_stevic_logo.webp"
test -s "$ROOT/app/src/main/res/drawable-nodpi/ic_launcher_foreground.webp"
test -s "$ROOT/app/src/main/res/mipmap-anydpi/ic_launcher.xml"
test -s "$ROOT/app/src/main/res/mipmap-anydpi/ic_launcher_round.xml"
test -s "$ROOT/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"
test -s "$ROOT/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml"

grep -q 'R.drawable.edil_milan_stevic_logo' "$SAFEHOME"
grep -q 'EDIL MILAN STEVIC' "$SAFEHOME"

echo "Branding EDIL MILAN STEVIC: OK"
