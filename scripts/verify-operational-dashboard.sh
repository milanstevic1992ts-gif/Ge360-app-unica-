#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TODAY="$ROOT/feature/home/src/main/java/it/edilmilan/ge360/home/TodayDashboardView.kt"
DIAG="$ROOT/app/src/main/java/it/edilmilan/ge360/DiagnosticsActivity.kt"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
MORE="$ROOT/app/src/main/java/it/edilmilan/ge360/MainSectionFragment.kt"
LAYOUT="$ROOT/feature/home/src/main/res/layout/fragment_home.xml"

for file in "$TODAY" "$DIAG" "$MANIFEST" "$MORE" "$LAYOUT"; do
  test -f "$file" || { echo "Missing $file" >&2; exit 1; }
done

grep -q 'TodayDashboardView' "$LAYOUT"
grep -q 'runningPersonnelSessions' "$TODAY"
grep -q 'assignments(today, today)' "$TODAY"
grep -q 'class DiagnosticsActivity' "$DIAG"
grep -q 'DataHubSyncCoordinator.lastSuccessAt' "$DIAG"
grep -q 'Ge360Application.lastCrash' "$DIAG"
grep -q 'android:name=".DiagnosticsActivity"' "$MANIFEST"
grep -q 'Tool("Stato GE360"' "$MORE"

echo "Operational dashboard coverage: OK"
