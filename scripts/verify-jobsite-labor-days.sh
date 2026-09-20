#!/usr/bin/env bash
set -euo pipefail

helper="feature/giornata/src/main/java/it/edilmilan/ge360/giornata/LaborDaySessions.kt"
activity="feature/giornata/src/main/java/it/edilmilan/ge360/giornata/JobsiteLaborActivity.kt"
daily="feature/giornata/src/main/java/it/edilmilan/ge360/giornata/DailyStore.kt"
manifest="feature/giornata/src/main/AndroidManifest.xml"
hub="feature/centrocantiere/src/main/java/it/edilmilan/ge360/centrocantiere/JobsiteHubActivity.kt"
main="app/src/main/java/it/edilmilan/ge360/MainActivity.kt"
safehome="app/src/main/java/it/edilmilan/ge360/SafeHomeFragment.kt"

for file in "$helper" "$activity" "$daily" "$manifest" "$hub"; do
  test -s "$file" || { echo "File manodopera cantiere mancante: $file" >&2; exit 1; }
done

grep -Fq 'SLOBODAN_DEFAULT_DAILY_RATE = 92.0' "$helper"
grep -Fq 'fun DailyStore.saveLaborDayEntry' "$helper"
grep -Fq 'fun DailyStore.hasTimedSessionForDay' "$helper"
grep -Fq 'workDate.isBefore(LocalDate.now())' "$helper"
grep -Fq 'Per oggi usa il timer' "$helper"
grep -Fq 'Giornata retroattiva' "$helper"
grep -Fq 'writableDatabase.insertOrThrow("work_sessions"' "$helper"
grep -Fq 'dailyRate / STANDARD_DAY_HOURS' "$helper"
grep -Fq 'fun WorkSession.dailyRateSnapshot' "$helper"

grep -Fq 'CalendarView' "$activity"
grep -Fq 'REGISTRA / AGGIORNA GIORNO' "$activity"
grep -Fq 'TIMER ORE REALI / RAPPORTINO' "$activity"
grep -Fq 'Slobodan parte da 92 €/giorno' "$activity"
grep -Fq 'dailyStore.laborCostForJobsite' "$activity"

grep -Fq 'android:name=".JobsiteLaborActivity"' "$manifest"
grep -Fq 'Ore / manodopera' "$hub"
grep -Fq 'JobsiteLaborActivity::class.java' "$hub"
grep -Fq 'MANODOPERA' "$hub"
grep -Fq 'laborCostForJobsite' "$hub"

# Le giornate retroattive usano work_sessions, già esportato/importato da Giornata.
grep -Fq 'root.put("sessions"' "$daily"
grep -Fq 'sessionRows = root.optJSONArray("sessions")' "$daily"

# Niente database parallelo e niente inizializzazione nel bootstrap sicuro.
if grep -Fq 'SQLiteOpenHelper' "$helper" || grep -Fq 'ge360_labor_days.db' "$helper"; then
  echo "La manodopera retroattiva deve riusare il database Giornata" >&2
  exit 1
fi
if grep -Fq 'JobsiteLaborActivity' "$main" || grep -Fq 'JobsiteLaborActivity' "$safehome"; then
  echo "Manodopera cantiere non deve entrare nel bootstrap principale" >&2
  exit 1
fi

echo "Manodopera cantiere: contratto OK"
