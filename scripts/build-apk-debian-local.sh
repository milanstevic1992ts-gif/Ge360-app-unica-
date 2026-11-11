#!/usr/bin/env bash
# GE360 TEST APK - compilazione solo sul Debian, nessun GitHub Actions
set -Eeuo pipefail
say() { printf '\n[GE360] %s\n' "$*"; }
die() { echo "[ERRORE] $*" >&2; exit 1; }
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
cd "$ROOT"
BRANCH=feat/integrazione-rilievi-openplan3d-20261110
[[ "$(git branch --show-current)" == "$BRANCH" ]] || die "Usa il branch $BRANCH"
[[ "$(uname -m)" == x86_64 ]] || die 'Richiesto Debian x86_64'
[[ -z "$(git status --porcelain)" ]] || die 'Salva prima le modifiche locali'
git pull --ff-only origin "$BRANCH"

NEED=()
for tool in curl unzip python3 node; do command -v "$tool" >/dev/null || NEED+=("$tool"); done
command -v java >/dev/null || NEED+=(default-jdk)
if (( ${#NEED[@]} > 0 )); then
  say "Installo: ${NEED[*]}"
  sudo apt-get update && sudo apt-get install -y ca-certificates "${NEED[@]}"
fi
JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2)
JAVA_MAJOR=${JAVA_VERSION%%.*}
if [[ "$JAVA_MAJOR" == 1 ]]; then JAVA_MAJOR=$(printf '%s' "$JAVA_VERSION" | cut -d. -f2); fi
[[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] && (( JAVA_MAJOR >= 17 )) || die 'Serve Java 17+'

SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}
export ANDROID_SDK_ROOT="$SDK" ANDROID_HOME="$SDK"
mkdir -p "$SDK"
if [[ ! -f "$SDK/platforms/android-35/android.jar" || ! -x "$SDK/build-tools/35.0.0/aapt2" ]]; then
  MANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
  if [[ ! -x "$MANAGER" ]]; then
    [[ ! -e "$SDK/cmdline-tools/latest" ]] || die 'cmdline-tools/latest incompleto: controllare SDK'
    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' EXIT
    say 'Scarico Android Command Line Tools ufficiali (SHA256 verificato)'
    curl -fL --retry 3 --connect-timeout 25 \
      https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip \
      -o "$TMP/sdk.zip"
    echo "4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  $TMP/sdk.zip" | sha256sum -c -
    unzip -q "$TMP/sdk.zip" -d "$TMP/extracted"
    mkdir -p "$SDK/cmdline-tools"
    mv "$TMP/extracted/cmdline-tools" "$SDK/cmdline-tools/latest"
  fi
  say 'Conferma le licenze Google quando richiesto (premi y)'
  "$MANAGER" --sdk_root="$SDK" --licenses
  "$MANAGER" --sdk_root="$SDK" 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
fi

say 'Controllo JS, HTML e scambio Rilievi → Preventivi'
python3 scripts/verify-rilievi-integration.py
node --test scripts/test-rilievi-free-mode.mjs scripts/test-rilievi-estimate-handoff.mjs

say 'Compilo APK Android e test Kotlin in locale (senza GitHub Actions)'
DEST="$HOME/GE360-APK"
mkdir -p "$DEST"
bash ./gradlew --no-daemon --console=plain \
   :feature:planimetrie:testDebugUnitTest :app:assembleDebug \
   2>&1 | tee "$DEST/compilazione-ge360.log"
APK=app/build/outputs/apk/debug/app-debug.apk
[[ -s "$APK" ]] || die 'APK non creato: consulta compilazione-ge360.log'
cp "$APK" "$DEST/GE360-Rilievi-Libero-TEST.apk"
sha256sum "$DEST/GE360-Rilievi-Libero-TEST.apk" | tee "$DEST/GE360-Rilievi-Libero-TEST.apk.sha256"
say "APK PRONTO: $DEST/GE360-Rilievi-Libero-TEST.apk"
say 'Il pacchetto it.edilmilan.ge360.dev NON sostituisce la tua app GE360 esistente.'
if command -v tailscale >/dev/null && tailscale ip -4 >/dev/null 2>&1; then
  IP=$(tailscale ip -4 | head -1)
  say "Sul Debian esegui: cd '$DEST' && python3 -m http.server 8765 --bind '$IP'"
  say "Dal telefono con Tailscale apri http://$IP:8765/GE360-Rilievi-Libero-TEST.apk"
fi
