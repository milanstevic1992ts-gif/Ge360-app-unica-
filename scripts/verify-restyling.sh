#!/usr/bin/env bash
set -euo pipefail

# Contratti di restyling GE360.
#
# Questo script protegge due cose: l'identita' visiva del marchio e la garanzia
# di avvio. Verifica l'INTENTO, non la riga esatta in cui e' scritto: dopo
# l'introduzione del design system i colori non stanno piu' come costanti
# Kotlin ma come risorse con variante notturna, e i valori sono gli stessi.
#
# Ogni asserzione stampa cosa stava verificando quando fallisce: prima un
# fallimento in CI non diceva niente e andava diagnosticato scaricando i log.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BRAND="$ROOT/core/common/src/main/java/it/edilmilan/ge360/core/common/ui/Ge360Brand.kt"
PALETTE="$ROOT/core/common/src/main/res/values/ge360_palette.xml"
PALETTE_NIGHT="$ROOT/core/common/src/main/res/values-night/ge360_palette.xml"
THEME="$ROOT/app/src/main/res/values/themes.xml"
MAIN="$ROOT/app/src/main/java/it/edilmilan/ge360/MainActivity.kt"
SAFE="$ROOT/app/src/main/java/it/edilmilan/ge360/SafeHomeFragment.kt"
TODAY="$ROOT/app/src/main/java/it/edilmilan/ge360/TodayFragment.kt"
DASH="$ROOT/feature/home/src/main/res/layout/fragment_home.xml"
HUB="$ROOT/feature/centrocantiere/src/main/java/it/edilmilan/ge360/centrocantiere/JobsiteHubActivity.kt"

failures=0

must() {
  local description="$1" pattern="$2" file="$3"
  if ! grep -q "$pattern" "$file"; then
    echo "FALLITO: $description"
    echo "         atteso in $(basename "$file"): $pattern"
    failures=$((failures + 1))
  fi
}

must_not() {
  local description="$1" pattern="$2" file="$3"
  if grep -q "$pattern" "$file"; then
    echo "FALLITO: $description"
    echo "         non deve comparire in $(basename "$file"): $pattern"
    failures=$((failures + 1))
  fi
}

for file in "$BRAND" "$PALETTE" "$PALETTE_NIGHT" "$THEME" "$MAIN" "$SAFE" "$TODAY" "$DASH" "$HUB"; do
  test -f "$file" || { echo "File di restyling mancante: $file"; exit 1; }
done

# --- Identita' del marchio -------------------------------------------------
# I valori vivono nelle risorse. Sono gli stessi di prima, in esadecimale:
# #E49A12 = rgb(228,154,18), #384234 = rgb(56,66,52), #F6F5F2 = rgb(246,245,242).
must "arancione cantiere nella palette chiara" '<color name="ge360_primary">#E49A12</color>' "$PALETTE"
must "verde del marchio nella palette chiara" '<color name="ge360_forest">#384234</color>' "$PALETTE"
must "fondo caldo nella palette chiara" '<color name="ge360_background">#F6F5F2</color>' "$PALETTE"

# La variante notturna deve esistere e definire gli stessi token, altrimenti in
# tema scuro l'app torna a mostrare colori chiari su fondo scuro.
must "token primary anche al buio" '<color name="ge360_primary">' "$PALETTE_NIGHT"
must "token forest anche al buio" '<color name="ge360_forest">' "$PALETTE_NIGHT"
must "token background anche al buio" '<color name="ge360_background">' "$PALETTE_NIGHT"

# Se Ge360Brand.sync() non viene mai chiamata l'app deve restare identica a
# come era prima del tema scuro: la palette chiara scritta a mano e' la rete.
must "fallback chiaro: arancione" 'primary = Color.rgb(228, 154, 18)' "$BRAND"
must "fallback chiaro: verde" 'forest = Color.rgb(56, 66, 52)' "$BRAND"
must "fallback chiaro: fondo" 'background = Color.rgb(246, 245, 242)' "$BRAND"

# --- Tema ------------------------------------------------------------------
must "tema che segue chiaro/scuro di sistema" 'Theme.MaterialComponents.DayNight.NoActionBar' "$THEME"
must "colore primario preso dalla palette" '<item name="colorPrimary">@color/ge360_primary_dark</item>' "$THEME"
must "colore secondario preso dalla palette" '<item name="colorSecondary">@color/ge360_forest</item>' "$THEME"
must "stile bottoni del marchio" 'materialButtonStyle' "$THEME"
# I colori non devono tornare a essere esadecimali nel tema: cosi' non seguono
# piu' il tema scuro.
must_not "nessun esadecimale nel tema" '<item name="colorPrimary">#' "$THEME"

# --- Fondo delle schermate -------------------------------------------------
must "shell con fondo del marchio" 'Ge360Brand.Background' "$MAIN"
must "home di bootstrap con fondo del marchio" 'Ge360Brand.Background' "$SAFE"
must "hub cantiere con fondo del marchio" 'Ge360Brand.Background' "$HUB"
must "dashboard con intestazione del marchio" 'EDIL MILAN STEVIC' "$DASH"
must "dashboard dichiarata come operativa" 'Dashboard operativa' "$DASH"

# --- Garanzia di avvio -----------------------------------------------------
# Il contratto originale fissava "else -> SafeHomeFragment()". L'intento era:
# la schermata iniziale non deve diventare la dashboard completa, che apre
# database e servizi. Resta valido con "Oggi", che rispetta lo stesso vincolo;
# quindi qui si verifica l'intento, non il nome della classe.
must "schermata iniziale: Oggi" 'else -> TodayFragment()' "$MAIN"
must_not "la dashboard completa non e' la schermata iniziale" 'else -> HomeFragment()' "$MAIN"
must "home di bootstrap ancora collegata come ripiego" 'SafeHomeFragment()' "$MAIN"
must "servizi non critici ancora rimandati dopo la prima UI" 'root.postDelayed({ startNonCriticalServices() }, 1200L)' "$MAIN"

# "Oggi" non deve leggere dati prima del primo frame: le letture stanno dentro
# loadDay(), che gira in background. Se un AgendaStore comparisse sopra quella
# funzione vorrebbe dire che e' tornato sul percorso di avvio.
must "Oggi legge il giorno in background" 'geLoad' "$TODAY"
first_store="$(grep -n 'AgendaStore(\|AgendaSourceProjection.read(' "$TODAY" | head -n1 | cut -d: -f1 || true)"
load_day="$(grep -n 'private fun loadDay' "$TODAY" | head -n1 | cut -d: -f1 || true)"
if [ -n "$first_store" ] && [ -n "$load_day" ] && [ "$first_store" -lt "$load_day" ]; then
  echo "FALLITO: Oggi apre un database prima di loadDay() (riga $first_store)"
  echo "         la schermata iniziale non deve leggere dati sul percorso di avvio"
  failures=$((failures + 1))
fi

# --- Migrazione dei colori verso i token ----------------------------------
# I colori scritti a mano non seguono il tema scuro: una schermata che ne usa
# resta chiara quando il telefono passa al buio. Questi file sono gli unici
# ancora da migrare: l'elenco puo' solo accorciarsi, mai allungarsi.
#
# Granularita' per FILE e non per riga, di proposito: dentro una card il
# riempimento e i testi devono migrare insieme, altrimenti si ottiene un fondo
# che si inverte con sopra un testo che non si inverte.
ALLOWED_HARDCODED_COLORS="
core/common/src/main/java/it/edilmilan/ge360/core/common/ui/Ge360Brand.kt
feature/home/src/main/java/it/edilmilan/ge360/home/HomeFragment.kt
feature/cantieri/src/main/java/it/edilmilan/ge360/cantieri/JobsiteProgressActivity.kt
feature/centrocantiere/src/main/java/it/edilmilan/ge360/centrocantiere/JobsiteTaskPlanActivity.kt
feature/centrocantiere/src/main/java/it/edilmilan/ge360/centrocantiere/JobsiteCostAnalyticsActivity.kt
feature/centrocantiere/src/main/java/it/edilmilan/ge360/centrocantiere/CostDonutView.kt
feature/agenda/src/main/java/it/edilmilan/ge360/agenda/OperationalDayActivity.kt
"

offenders=""
while IFS= read -r file; do
  case "$ALLOWED_HARDCODED_COLORS" in
    *"$file"*) continue ;;
  esac
  offenders="$offenders $file"
done <<EOF
$(cd "$ROOT" && grep -rl '"#[0-9A-Fa-f]\{6\}"\|Color\.parseColor\|Color\.rgb(\|Color\.WHITE\|Color\.BLACK' \
    --include=*.kt app core feature 2>/dev/null | grep -v '/test/' | sort)
EOF

if [ -n "$offenders" ]; then
  echo "FALLITO: colori scritti a mano fuori dai file ancora da migrare"
  for file in $offenders; do
    echo "         $file"
  done
  echo "         usa un token di Ge360Brand, oppure aggiungi il colore alla palette"
  failures=$((failures + 1))
fi

if [ "$failures" -gt 0 ]; then
  echo
  echo "Contratti di restyling GE360: $failures asserzioni fallite"
  exit 1
fi

echo "GE360 restyling contracts: OK"
