#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HUB="$ROOT/feature/centrocantiere/src/main/java/it/edilmilan/ge360/centrocantiere/JobsiteHubActivity.kt"

[ -f "$HUB" ] || { echo "Missing JobsiteHubActivity" >&2; exit 1; }

grep -q 'FinanceStore' "$HUB"
grep -q 'financeStore.jobsiteSummary(jobsite.ge360Id)' "$HUB"
grep -q '"ENTRATE"' "$HUB"
grep -q '"USCITE"' "$HUB"
grep -q '"SALDO"' "$HUB"
grep -q 'Cronologia / lavori' "$HUB"

echo "Jobsite Hub finance coverage: OK"
