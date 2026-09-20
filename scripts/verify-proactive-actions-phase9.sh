#!/usr/bin/env bash
set -euo pipefail

worker="feature/jarvis/src/main/java/it/edilmilan/ge360/jarvis/JarvisProactive.kt"
client="feature/jarvis/src/main/java/it/edilmilan/ge360/jarvis/JarvisProactiveActionClient.kt"
activity="feature/jarvis/src/main/java/it/edilmilan/ge360/jarvis/ProactiveAlertsActivity.kt"
gradle="app/build.gradle.kts"

for f in "$worker" "$client" "$activity" "$gradle"; do
  test -s "$f" || { echo "Fase 9: file mancante $f" >&2; exit 1; }
done

# Il worker della Fase 8 deve rimanere esclusivamente read-only.
if grep -Fq 'method = "POST"' "$worker" || grep -Fq '/proposal' "$worker" || grep -Fq '/approve' "$worker"; then
  echo "Fase 9: il worker proattivo non deve preparare o approvare azioni" >&2
  exit 1
fi

grep -Fq '/api/v1/jarvis/proactive/${segment(alertId)}/proposal' "$client"
grep -Fq '/api/v1/jarvis/proactive/proposals?status=proposed&limit=50' "$client"
grep -Fq '/approve' "$client"
grep -Fq '/reject' "$client"
grep -Fq 'X-GE360-Device-Id' "$client"
grep -Fq 'requiresApproval && !autoExecute' "$client"

grep -Fq 'Preparo la proposta. Non eseguo nulla.' "$activity"
grep -Fq 'APPROVA' "$activity"
grep -Fq 'RIFIUTA' "$activity"
grep -Fq 'Solo premendo APPROVA' "$activity"
grep -Fq 'actionClient.prepare' "$activity"
grep -Fq 'actionClient.approve' "$activity"
grep -Fq 'actionClient.reject' "$activity"

grep -Fq 'versionCode = 2125' "$gradle"
grep -Fq 'versionName = "0.21.24"' "$gradle"

echo "Jarvis Proactive Actions Fase 9: contratto OK"
