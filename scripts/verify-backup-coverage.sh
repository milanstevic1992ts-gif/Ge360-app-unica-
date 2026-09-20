#!/usr/bin/env bash
set -euo pipefail

coordinator="feature/backup/src/main/java/it/edilmilan/ge360/backup/BackupCoordinator.kt"
datahub="app/src/main/java/it/edilmilan/ge360/sync/DataHubSyncCoordinator.kt"

required_backup_modules=(
  clienti cantieri centro-cantiere jobsite-archive giornata messaggi spese scanner fir
  preventivi preventivi-rooms preventivi-lifecycle preventivi-customizations planimetrie
  attrezzi attrezzi-custom-packs furgone galleria fisco-settings
)

for module in "${required_backup_modules[@]}"; do
  grep -Fq "modules/${module}.json" "$coordinator" || {
    echo "Modulo mancante dal backup: ${module}" >&2
    exit 1
  }
done

required_datahub_modules=(
  clienti cantieri centrocantiere jobsite_archive giornata messaggi spese scanner fir
  preventivi preventivi_rooms preventivi_lifecycle preventivi_customizations planimetrie
  attrezzi attrezzi_custom_packs galleria fisco_settings
)

for module in "${required_datahub_modules[@]}"; do
  grep -Fq "snapshot(\"${module}\"" "$datahub" || {
    echo "Modulo mancante dal Data Hub: ${module}" >&2
    exit 1
  }
done

scanner_store="feature/scanner/src/main/java/it/edilmilan/ge360/scanner/DocumentStore.kt"
warranty_archive="feature/scanner/src/main/java/it/edilmilan/ge360/scanner/ToolWarrantyArchive.kt"
warranty_ui="feature/scanner/src/main/java/it/edilmilan/ge360/scanner/ToolWarrantyActivity.kt"
tools_store="feature/attrezzi/src/main/java/it/edilmilan/ge360/attrezzi/ToolsStore.kt"
material_calculations="feature/attrezzi/src/main/java/it/edilmilan/ge360/attrezzi/MaterialCalculationStore.kt"
materials_v2="feature/attrezzi/src/main/java/it/edilmilan/ge360/attrezzi/MaterialInventoryStore.kt"

for field in tool_name tool_type; do
  grep -Fq "$field" "$scanner_store" || {
    echo "Campo scontrino garanzia mancante: $field" >&2
    exit 1
  }
done

grep -Fq 'SCONTRINO_GARANZIA' "$warranty_archive" || {
  echo "Tipo canonico scontrino garanzia mancante" >&2
  exit 1
}
grep -Fq 'Scontrini garanzia' "$warranty_ui" || {
  echo "Interfaccia scontrini garanzia mancante" >&2
  exit 1
}
grep -Fq 'ToolWarrantyArchive.targetFile' "$coordinator" || {
  echo "Ripristino PDF scontrini garanzia non coperto" >&2
  exit 1
}

grep -Fq 'materialCalculations' "$tools_store" || {
  echo "Storico Calcolo materiali non incluso nel backup Attrezzi" >&2
  exit 1
}
grep -Fq 'MaterialCalculationStore(appContext)' "$tools_store" || {
  echo "Bridge backup Calcolo materiali mancante" >&2
  exit 1
}
grep -Fq 'ge360.material-calculations.v1' "$material_calculations" || {
  echo "Schema backup Calcolo materiali mancante" >&2
  exit 1
}

grep -Fq 'materials2' "$tools_store" || {
  echo "Materiali 2.0 non incluso nel backup Attrezzi" >&2
  exit 1
}
grep -Fq 'MaterialInventoryStore(appContext)' "$tools_store" || {
  echo "Bridge backup Materiali 2.0 mancante" >&2
  exit 1
}
grep -Fq 'ge360.materials.v2' "$materials_v2" || {
  echo "Schema backup Materiali 2.0 mancante" >&2
  exit 1
}

echo "Copertura backup e Data Hub verificata."
