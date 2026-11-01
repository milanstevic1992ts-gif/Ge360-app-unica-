#!/usr/bin/env bash
set -euo pipefail

failed=0
while IFS= read -r file; do
  version=$(sed -nE 's/.*DB_VERSION[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$file" | tail -n1)
  [[ -z "$version" || "$version" -le 1 ]] && continue

  if ! grep -q 'override fun onUpgrade' "$file"; then
    echo "Migrazione mancante per DB_VERSION=$version: $file" >&2
    failed=1
    continue
  fi

  if grep -Eq 'override fun onUpgrade\([^)]*\)[[:space:]]*=[[:space:]]*Unit' "$file"; then
    echo "Migrazione vuota per DB_VERSION=$version: $file" >&2
    failed=1
  fi
done < <(grep -RlE --include='*.kt' 'DB_VERSION[[:space:]]*=[[:space:]]*[0-9]+' app feature core)

if [[ "$failed" -ne 0 ]]; then
  exit 1
fi

echo "Contratti di migrazione SQLite verificati."
