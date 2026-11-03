# GE360 — verifica e integrazione Android (3 novembre 2026)

## Provenienza
- ZIP di origine: GE360-design-system-4101909.zip e GE360-tutte-le-modifiche-4101909.zip (identiche).
- Commit sorgente design system: `410190903ca91586a731582603bab180876c1a8f`.
- Commit correzioni accessibilità: `b9f50eb1f83c29ee65a4d86cf96eb13657051102`.
- Commit effettivamente compilato e testato: `6733e6d6cede7c5a78efd9ca0618dfefca676e4a`.
- GitHub Actions: https://github.com/milanstevic1992ts-gif/Ge360-app-unica-/actions/runs/41613731362

## Esito
- Pipeline: **success**.
- `gradle :app:assembleDebug --stacktrace`: **success**.
- Tutti i 14 contratti `scripts/verify-*.sh`: **success**.
- Test unitari `core:common`, `core:backup`, agenda, giornata, attrezzi, planimetrie, preventivi, fisco e spese: **success**.
- Integrità ZIP APK, `classes.dex`, manifest, aapt e `apksigner verify`: **success**.
- Artefatto: `GE360-debug-installabile` (APK firmato con chiave debug; ZIP artifact disponibile per 7 giorni).

## Ambito e limiti
- Gli ultimi interventi successivi alla build modificano **soltanto** trigger Actions, README, SECURITY e questa documentazione; i sorgenti e le risorse Android compilati non cambiano.
- No collaudo Android strumentale o su dispositivo reale, no deploy Debian/Jarvis, no APK release firmato con chiave produzione.
- Per il merge su `main`: avanzamento fast-forward, senza riscrivere storia e conservando tutti i moduli del ramo integrato.
- Le quattro pipeline sono riportate a `workflow_dispatch`: nuovi build/test solo su richiesta esplicita per evitare minuti non necessari.
