#!/usr/bin/env bash
set -euo pipefail

main="app/src/main/java/it/edilmilan/ge360/MainSectionFragment.kt"
activity="feature/giornata/src/main/java/it/edilmilan/ge360/giornata/ExternalCollaboratorsActivity.kt"
manifest="feature/giornata/src/main/AndroidManifest.xml"
daily="feature/giornata/src/main/java/it/edilmilan/ge360/giornata/DailyStore.kt"
finance="feature/spese/src/main/java/it/edilmilan/ge360/spese/FinanceStore.kt"

grep -q 'Tool("Collaboratori esterni"' "$main"
grep -q 'ExternalCollaboratorsActivity::class.java' "$main"
grep -q 'android:name=".ExternalCollaboratorsActivity"' "$manifest"
grep -q 'const val CATEGORY = "Collaboratori esterni"' "$activity"
grep -q 'private const val ROLE_PREFIX = "Esterno · "' "$activity"
grep -q 'jobsiteId = job?.ge360Id.orEmpty()' "$activity"
grep -q 'FinanceType.EXPENSE' "$activity"
grep -q 'FinanceScope.BUSINESS' "$activity"
grep -q 'dailyStore.personnel(false)' "$activity"
grep -q 'put("personnel"' "$daily"
grep -q 'root.put("transactions"' "$finance"

if grep -q 'SQLiteOpenHelper' "$activity"; then
  echo "ExternalCollaboratorsActivity non deve introdurre un nuovo database" >&2
  exit 1
fi

echo "Collaboratori esterni: contratto OK"
