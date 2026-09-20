#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BRAND="$ROOT/core/common/src/main/java/it/edilmilan/ge360/core/common/ui/Ge360Brand.kt"
THEME="$ROOT/app/src/main/res/values/themes.xml"
MAIN="$ROOT/app/src/main/java/it/edilmilan/ge360/MainActivity.kt"
SAFE="$ROOT/app/src/main/java/it/edilmilan/ge360/SafeHomeFragment.kt"
DASH="$ROOT/feature/home/src/main/res/layout/fragment_home.xml"
HUB="$ROOT/feature/centrocantiere/src/main/java/it/edilmilan/ge360/centrocantiere/JobsiteHubActivity.kt"

for file in "$BRAND" "$THEME" "$MAIN" "$SAFE" "$DASH" "$HUB"; do
  test -f "$file" || { echo "Missing restyling file: $file"; exit 1; }
done

grep -q 'Primary = Color.rgb(228, 154, 18)' "$BRAND"
grep -q 'Forest = Color.rgb(56, 66, 52)' "$BRAND"
grep -q 'Background = Color.rgb(246, 245, 242)' "$BRAND"
grep -q '<item name="colorPrimary">#B87400</item>' "$THEME"
grep -q '<item name="colorSecondary">#384234</item>' "$THEME"
grep -q 'materialButtonStyle' "$THEME"
grep -q 'Ge360Brand.Background' "$MAIN"
grep -q 'Ge360Brand.Background' "$SAFE"
grep -q 'EDIL MILAN STEVIC' "$DASH"
grep -q 'Dashboard operativa' "$DASH"
grep -q 'Ge360Brand.Background' "$HUB"

# Il restyling non deve sostituire il bootstrap sicuro con la dashboard completa.
grep -q 'else -> SafeHomeFragment()' "$MAIN"
grep -q 'root.postDelayed({ startNonCriticalServices() }, 1200L)' "$MAIN"

echo "GE360 restyling contracts: OK"
