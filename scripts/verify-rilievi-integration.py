#!/usr/bin/env python3
"""Verifica statica, offline e senza crediti dell'integrazione OpenPlan3D."""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "feature/planimetrie/src/main/assets/rilievi"
FILES = [
    "index.html",
    "css/app.css",
    "geometry-engine/index.js",
    "geometry-engine/constants.js",
    "geometry-engine/constraints.js",
    "geometry-engine/geometry.js",
    "geometry-engine/openings.js",
    "geometry-engine/solver.js",
    "geometry-engine/topology.js",
    "geometry-engine/validation.js",
    "js/app.js",
    "js/backend-bridge.js",
    "js/backend-client.js",
    "js/backend-results.js",
    "js/construction-quantities.js",
    "js/construction-visuals.js",
    "js/geometry-diagnostics.js",
    "js/laser-client.js",
    "js/offline-queue.js",
    "js/opening-presets.js",
    "js/pdf-reader.js",
    "js/photo-store.js",
    "js/plan-payload.js",
    "js/room-surfaces.js",
    "js/room-template.js",
    "js/sketch-snap.js",
    "js/work-catalog.js",
]
IMPORTS = re.compile(r"""(?:\bfrom\s*|\bimport\s*\()\s*["'](\.[^"']+)["']""")
errors: list[str] = []

for name in FILES:
    path = ASSETS / name
    if not path.is_file() or path.stat().st_size == 0:
        errors.append(f"File mancante/vuoto: {name}")

index_path = ASSETS / "index.html"
if index_path.exists():
    index = index_path.read_text(encoding="utf-8")
    for fragment in ('href="css/app.css"', 'type="module" src="js/app.js"'):
        if fragment not in index:
            errors.append(f"Riferimento principale HTML mancante: {fragment}")

for name in FILES:
    if not name.endswith(".js"):
        continue
    path = ASSETS / name
    if not path.is_file():
        continue
    source = path.read_text(encoding="utf-8")
    for dependency in IMPORTS.findall(source):
        dep = (path.parent / dependency).resolve()
        # PDF.js non e' versionato dalla sorgente. Il reader ha un fallback,
        # verificato visivamente durante il collaudo Android.
        if dependency == "../vendor/pdfjs/pdf.mjs":
            continue
        if not dep.is_relative_to(ASSETS.resolve()) or not dep.is_file():
            errors.append(f"Import non risolto: {name} → {dependency}")
    if shutil.which("node"):
        check = subprocess.run(
            ["node", "--check", "--input-type=module"],
            input=source, text=True, capture_output=True
        )
        if check.returncode != 0:
            errors.append(f"Errore sintassi JS {name}: {check.stderr.strip()[-450:]}")

kotlin = ROOT / "feature/planimetrie/src/main/java/it/edilmilan/ge360/planimetrie/AdvancedSurveyActivity.kt"
activity = kotlin.read_text(encoding="utf-8") if kotlin.exists() else ""
for required in (
    "WebViewAssetLoader", "MIXED_CONTENT_NEVER_ALLOW",
    "WebViewCompat.addWebMessageListener",
    "ACTION_CREATE_DOCUMENT", "https://appassets.androidplatform.net/assets/rilievi/index.html",
):
    if required not in activity:
        errors.append(f"WebView/SAF: simbolo mancante {required}")

frontend = (ASSETS / "js/app.js").read_text(encoding="utf-8") if (ASSETS / "js/app.js").exists() else ""
for required in ("GE360Native.postMessage", "saveBlob(blob, filename)", "async function exportJson()"):
    if required not in frontend:
        errors.append(f"Frontend export: simbolo mancante {required}")

build = (ROOT / "feature/planimetrie/build.gradle.kts").read_text(encoding="utf-8")
manifest = (ROOT / "feature/planimetrie/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
navigation = (
    ROOT / "feature/planimetrie/src/main/java/it/edilmilan/ge360/planimetrie/FloorPlansActivity.kt"
).read_text(encoding="utf-8")
if 'androidx.webkit:webkit:' not in build:
    errors.append("Manca dipendenza AndroidX WebKit")
if 'AdvancedSurveyActivity' not in manifest:
    errors.append("AdvancedSurveyActivity non registrata nel manifest")
if 'AdvancedSurveyActivity::class.java' not in navigation:
    errors.append("L'accesso da Rilievi nativi non e' collegato")

if errors:
    print("INTEGRAZIONE RILIEVI: ERRORI")
    for error in errors:
        print(" -", error)
    sys.exit(1)
print(f"INTEGRAZIONE RILIEVI: OK, {len(FILES)} asset verificati.")
print("La verifica NON sostituisce build Gradle o collaudo reale del backend.")
