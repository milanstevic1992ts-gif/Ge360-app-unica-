# GE360 Materiali V1 — confronto consumi su registro esistente

Data: 2026-10-31
Stato: CODICE SU BRANCH, **non eseguito, non compilato e non collegato alla UI Android**.
Branch: `feature/ge360-material-variance-v1-20261031`.
Base originaria: Android main `91bbf01808fce6acd375ef6f5525ff0d278fdc05`.

## Codice introdotto
- `feature/attrezzi/src/main/java/it/edilmilan/ge360/attrezzi/MaterialVarianceRules.kt` — confronto puro su `MaterialStockMovement` del *già esistente* `MaterialInventoryStore`.
- `feature/attrezzi/src/test/java/it/edilmilan/ge360/attrezzi/MaterialVarianceRulesV1Test.kt` — test JUnit preparati e non eseguiti.
- `feature/attrezzi/build.gradle.kts` — JUnit per futuro controllo locale.

## Controlli implementati nel calcolatore
Confronto tra un piano opzionale e movimenti associati a `materialGe360Id`, unità e `jobsiteId` verificabili; separazione tra carichi, consumi, resi al magazzino e rettifiche; percentuale di scostamento solo con una quantità prevista positiva. Se lo storico export è parziale o le unità sono diverse, il risultato è `PARTIAL_HISTORY` o `UNIT_MISMATCH` e lo scostamento resta non calcolato. Piano assente = `NO_PLAN`, non zero previsto.

## Compatibilità fondamentale
`MaterialInventoryStore` e `ToolsStore` già esportano `materials2` dentro lo snapshot `attrezzi`. I tipi attuali `Carico`, `Consumo`, `Reso` e `Rettifica` sono mantenuti: **Carico non equivale ad acquisto fatturato** e `Reso` esistente è rientro positivo a stock, non storno fornitore. Non aggiungere altro magazzino o alterare retroattivamente le quantità.

## Non implementato
Manca il collegamento a un piano materiali confermato, catalogo tecnico/resa verificato, import fatture, riconciliazione per documento SKU, archivio storico completo, integrazione UI e apprendimento delle rese. Il calcolatore non registra acquisti né migliora automaticamente un preventivo approvato. Nessuna conferma di efficacia su dispositivo.

## Prossime fasi
1. Contratto `MaterialRequirement` con riferimento a preventivo/rilievo/versione/superficie, facoltativamente cantiere.
2. Fonte acquisti verificata mediante documenti, senza dedurre l'acquisto da `TYPE_LOAD`.
3. UI riga semplice previsto / caricato / consumato / scostamento, unità e copertura dati visibili.
4. Storico completo e riconciliazione movimenti Spese prima di calcolare un costo.
5. Suggerimenti di resa solo con cantieri conclusi e sufficienti campioni, mai modifica automatica.
6. Test Kotlin/Android, backup/restore, build e distribuzione solo su autorizzazione.

**GitHub Actions**: nessuna PR, nessun push a main, nessun test eseguito da questi commit. Rivedere il budget e i trigger prima di eventuale integrazione.
