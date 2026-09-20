#!/usr/bin/env bash
set -euo pipefail

store="feature/attrezzi/src/main/java/it/edilmilan/ge360/attrezzi/MaterialInventoryStore.kt"
ui="feature/attrezzi/src/main/java/it/edilmilan/ge360/attrezzi/MaterialsActivity.kt"
tools="feature/attrezzi/src/main/java/it/edilmilan/ge360/attrezzi/ToolsStore.kt"
hub="feature/attrezzi/src/main/java/it/edilmilan/ge360/attrezzi/ToolsHubActivity.kt"
manifest="feature/attrezzi/src/main/AndroidManifest.xml"
main="app/src/main/java/it/edilmilan/ge360/MainActivity.kt"
safehome="app/src/main/java/it/edilmilan/ge360/SafeHomeFragment.kt"

for file in "$store" "$ui" "$tools" "$hub" "$manifest"; do
  test -s "$file" || { echo "File Materiali 2.0 mancante: $file" >&2; exit 1; }
done

grep -Fq 'ge360.materials.v2' "$store"
grep -Fq 'CREATE TABLE materials' "$store"
grep -Fq 'CREATE TABLE material_movements' "$store"
grep -Fq 'fun recordMovement' "$store"
grep -Fq 'fun ensureCatalogMirror' "$store"
grep -Fq 'supplier' "$store"
grep -Fq 'min_qty' "$store"
grep -Fq 'target_qty' "$store"
grep -Fq 'avg_unit_cost' "$store"
grep -Fq 'location' "$store"
grep -Fq 'barcode' "$store"

grep -Fq 'title = "Materiali 2.0"' "$ui"
grep -Fq 'MODE_STOCK' "$ui"
grep -Fq 'MODE_BUY' "$ui"
grep -Fq 'MODE_MOVEMENTS' "$ui"
grep -Fq 'fulfillFromStock' "$ui"
grep -Fq 'MaterialStockMovement.TYPE_CONSUME' "$ui"
grep -Fq 'requestedBy' "$ui"

grep -Fq 'materials2' "$tools"
grep -Fq 'MaterialInventoryStore(appContext)' "$tools"
grep -Fq 'MATERIALI 2.0' "$hub"
grep -Fq 'MaterialsActivity::class.java' "$hub"
grep -Fq 'android:name=".MaterialsActivity"' "$manifest"

if grep -Fq 'MaterialInventoryStore' "$main" || grep -Fq 'MaterialInventoryStore' "$safehome"; then
  echo "Materiali 2.0 non deve essere inizializzato nel bootstrap principale" >&2
  exit 1
fi

echo "Materiali 2.0: contratto OK"
