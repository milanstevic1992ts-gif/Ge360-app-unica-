#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PAY="$ROOT/feature/giornata/src/main/java/it/edilmilan/ge360/giornata/SlobodanWeeklyPay.kt"
UI="$ROOT/feature/giornata/src/main/java/it/edilmilan/ge360/giornata/CollaboratorsActivity.kt"
MANIFEST="$ROOT/feature/giornata/src/main/AndroidManifest.xml"
GRADLE="$ROOT/feature/giornata/build.gradle.kts"
MAIN="$ROOT/app/src/main/java/it/edilmilan/ge360/MainActivity.kt"
MORE="$ROOT/app/src/main/java/it/edilmilan/ge360/MainSectionFragment.kt"

for f in "$PAY" "$UI" "$MANIFEST" "$GRADLE" "$MAIN" "$MORE"; do
  [ -f "$f" ] || { echo "Missing $f" >&2; exit 1; }
done

grep -q 'PERSON_NAME = "Slobodan"' "$PAY"
grep -q 'WEEKLY_AMOUNT = 550.0' "$PAY"
grep -q 'CATEGORY = "Collaboratori"' "$PAY"
grep -q 'FinanceType.EXPENSE' "$PAY"
grep -q 'FinanceScope.BUSINESS' "$PAY"
grep -q 'getByGe360Id' "$PAY"
grep -q 'DayOfWeek.SATURDAY' "$PAY"
grep -q 'withHour(19)' "$PAY"
grep -q 'setAndAllowWhileIdle' "$PAY"
grep -q 'Slobodan non risulta pagato' "$PAY"

grep -q 'PAGATO' "$UI"
grep -q 'savePayment' "$UI"
grep -q 'VEDI TUTTE LE SPESE' "$UI"
grep -q 'ATTIVA NOTIFICHE' "$UI"

grep -q 'android.permission.POST_NOTIFICATIONS' "$MANIFEST"
grep -q 'android.permission.RECEIVE_BOOT_COMPLETED' "$MANIFEST"
grep -q 'WeeklyPaymentReminderReceiver' "$MANIFEST"
grep -q 'BOOT_COMPLETED' "$MANIFEST"

grep -q 'implementation(project(":feature:spese"))' "$GRADLE"
grep -q 'CollaboratorPaymentReminderScheduler.schedule' "$MAIN"
grep -q 'Tool("Collaboratori"' "$MORE"

echo "Collaborator weekly pay coverage: OK"
