#!/usr/bin/env bash
set -euo pipefail

activity="feature/centrocantiere/src/main/java/it/edilmilan/ge360/centrocantiere/JobsiteCostAnalyticsActivity.kt"
chart="feature/centrocantiere/src/main/java/it/edilmilan/ge360/centrocantiere/CostDonutView.kt"
hub="feature/centrocantiere/src/main/java/it/edilmilan/ge360/centrocantiere/JobsiteHubActivity.kt"
manifest="feature/centrocantiere/src/main/AndroidManifest.xml"
materials="feature/attrezzi/src/main/java/it/edilmilan/ge360/attrezzi/MaterialsActivity.kt"
main="app/src/main/java/it/edilmilan/ge360/MainActivity.kt"
safehome="app/src/main/java/it/edilmilan/ge360/SafeHomeFragment.kt"
gradle="app/build.gradle.kts"

for f in "$activity" "$chart" "$hub" "$manifest" "$materials"; do
  test -s "$f" || { echo "File statistiche cantiere mancante: $f" >&2; exit 1; }
done

grep -Fq 'class CostDonutView' "$chart"
grep -Fq 'data class DonutSlice' "$chart"
grep -Fq 'drawArc' "$chart"

grep -Fq 'title = "Costi / statistiche"' "$activity"
grep -Fq 'RIPARTIZIONE COSTI' "$activity"
grep -Fq 'MANODOPERA PER OPERATORE' "$activity"
grep -Fq 'MATERIALI CONSUMATI' "$activity"
grep -Fq 'MARGINE' "$activity"
grep -Fq 'FinanceStore(this)' "$activity"
grep -Fq 'DailyStore(this)' "$activity"
grep -Fq 'MaterialInventoryStore(this)' "$activity"
grep -Fq 'max(materialFinance, stockMaterialCost)' "$activity"
grep -Fq 'personnelPaymentsExcluded' "$activity"
grep -Fq 'EXTERNAL_ROLE_PREFIX' "$activity"

grep -Fq 'unitCost = stock.avgUnitCost' "$materials"
grep -Fq 'defaultType == MaterialStockMovement.TYPE_CONSUME' "$materials"

grep -Fq 'Costi / statistiche' "$hub"
grep -Fq 'JobsiteCostAnalyticsActivity::class.java' "$hub"
grep -Fq 'android:name=".JobsiteCostAnalyticsActivity"' "$manifest"
grep -Fq 'versionCode = 2125' "$gradle"
grep -Fq 'versionName = "0.21.24"' "$gradle"

if grep -Fq 'SQLiteOpenHelper' "$activity" || grep -Fq 'JobsiteCostAnalyticsActivity' "$main" || grep -Fq 'JobsiteCostAnalyticsActivity' "$safehome"; then
  echo "Le statistiche cantiere non devono introdurre DB o bootstrap iniziale" >&2
  exit 1
fi

echo "Statistiche costi cantiere: contratto OK"
