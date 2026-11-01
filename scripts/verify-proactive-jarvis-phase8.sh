#!/usr/bin/env bash
set -euo pipefail

proactive="feature/jarvis/src/main/java/it/edilmilan/ge360/jarvis/JarvisProactive.kt"
policy="feature/jarvis/src/main/java/it/edilmilan/ge360/jarvis/JarvisProactivePolicy.kt"
activity="feature/jarvis/src/main/java/it/edilmilan/ge360/jarvis/ProactiveAlertsActivity.kt"
manifest="feature/jarvis/src/main/AndroidManifest.xml"
application="app/src/main/java/it/edilmilan/ge360/Ge360Application.kt"
home="app/src/main/java/it/edilmilan/ge360/SafeHomeFragment.kt"
gradle="app/build.gradle.kts"
jarvis_gradle="feature/jarvis/build.gradle.kts"

for f in "$proactive" "$policy" "$activity" "$manifest" "$application" "$home" "$gradle" "$jarvis_gradle"; do
  test -s "$f" || { echo "Fase 8: file mancante $f" >&2; exit 1; }
done

grep -Fq 'PeriodicWorkRequestBuilder<JarvisProactiveWorker>(15, TimeUnit.MINUTES)' "$proactive"
grep -Fq 'NetworkType.CONNECTED' "$proactive"
grep -Fq '/api/v1/jarvis/proactive?limit=50&minimum_severity=' "$proactive"
grep -Fq 'root.optBoolean("actionsExecuted", true)' "$proactive"
grep -Fq 'JarvisProactivePolicy.shouldNotify' "$proactive"
grep -Fq 'markSeen(posted)' "$proactive"
grep -Fq 'POST_NOTIFICATIONS' "$manifest"
grep -Fq 'ProactiveAlertsActivity' "$manifest"
grep -Fq 'JarvisProactiveScheduler.schedule(applicationContext)' "$application"
grep -Fq 'postDelayed' "$application"
grep -Fq 'JARVIS PROATTIVO' "$home"
grep -Fq 'ProactiveAlertsActivity::class.java' "$home"
grep -Fq 'androidx.work:work-runtime-ktx:2.10.0' "$jarvis_gradle"
grep -Fq 'versionCode = 2126' "$gradle"
grep -Fq 'versionName = "0.21.25"' "$gradle"

if grep -Fq 'dryRun = false' "$proactive" || grep -Fq 'method = "POST"' "$proactive" || grep -Fq '/proposal' "$proactive"; then
  echo "Fase 8: il worker proattivo deve restare read-only anche dopo la Fase 9" >&2
  exit 1
fi

grep -Fq 'Preparare non significa eseguire' "$activity"

echo "Jarvis Proattivo Fase 8: contratto read-only ancora OK"
