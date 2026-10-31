# GE360 Materiali — Previsione e consumo V1

Data: 1 novembre 2026
Branch: `feature/ge360-material-variance-v1-20261031`
Stato: **codice sviluppato su branch, NON compilato, NON collaudato, NON unito a main e NON distribuito.**

## Implementazione nel branch

- `MaterialVarianceRules.kt`: calcolo dei movimenti effettivi per UUID materiale, unità, cantiere e revisione prevista, con stati `COMPARABLE / NO_PLAN / UNIT_MISMATCH / PARTIAL_HISTORY`. «Carico» non equivale ad acquisto e «Reso» è rientro in stock secondo il contratto esistente.
- `MaterialInventoryStore.kt`: aggiunto `allMovementHistory()` senza limite di 1.000 record per backup e confronto. La lista UI rimane paginata; il backup esporta lo storico intero. Il nuovo campo `completeMovementHistory` distingue gli archivi nuovi/completi dai backup vecchi potenzialmente troncati; per i vecchi dati lo scostamento viene **sospeso** anziché calcolato con falsa precisione.
- `MaterialPlanStore.kt`: archivio separato **solo per revisioni delle quantità previste**, non magazzino duplicato. I piani sono legati a UUID materiale e UUID cantiere opzionale, unità canonica, timestamp di conferma e fonte `MANUAL`. Ogni correzione crea una nuova revisione, non cambia i piani precedenti.
- `ToolsStore.kt`: all'export/import Attrezzi si aggiunge `materialPlans` con schema `ge360.material-plans.v1`. Un backup precedente che non contiene il campo lascia il nuovo registro dei piani invariato; i riferimenti UUID sono conservati.
- `MaterialsActivity.kt`: pulsante **PREVISTO / CONSUMATO** su ogni scheda, selezione di un cantiere o ambito generale, quantità prevista confermata manualmente, riepilogo Caricato/Consumato/Rientrato, scostamento solo se confrontabile.
- `MaterialVarianceRulesV1Test.kt`: regressioni preparate su multi-cantiere, unità incompatibili, storici incompleti, UUID distinti e piano versionato, non eseguite.

## Invarianti e limiti

1. Nessun cambiamento al catalogo/stock canonico: `MaterialInventoryStore` resta fonte delle giacenze e dei movimenti; `MaterialPlanStore` contiene solo quantità previste.
2. Le stime manuali non vengono scambiate per computi Rilievi/Preventivi già approvati; il collegamento a `MaterialCalculationStore` non è ancora attivo. La fonte `CALCULATION` è riservata al futuro adapter esplicito verificato.
3. Non vengono inventati acquisti, fatture o prezzi a partire dai Carichi e non viene alterato un preventivo storico.
4. Un backup preesistente che esponeva al massimo 1.000 movimenti non può certificare automaticamente lo storico completo. Il confronto continua a mostrare i dati registrati e segnala l'assenza di affidabilità per la differenza.
5. Lo storico completo può ingrandire i payload Attrezzi del Data Hub: **verificare limiti e prestazioni server prima della distribuzione**.
6. I nuovi piani sono locali e compresi nell'export Attrezzi; il backend Glass attuale non mostra ancora le previsioni per cantiere nella vista Materiali.
7. Non sono presenti riconciliazione SKU/fattura d'acquisto, ordini, resi a fornitore, statistiche sulle rese o aggiornamento automatico dei preventivi.

## Prossimi incrementi

- Validare che il `MaterialCalculationStore.resultsJson` e il catalogo `EstimateStore` siano compatibili con i riferimenti `MaterialPlanRevisionV1`, senza match per nome.
- Mostrare piano/previsione e scostamento nel Control Center Glass da snapshot Attrezzi, senza nuove API.
- Collegare le fatture/scontrini e distinguere da movimento Carico con prova documentale UUID.
- Dopo la riconciliazione, consentire suggerimenti di resa solo su lavori completati e campioni sufficienti.

**Nessun test/build/Actions/APK/merge/deploy richiesto o eseguito in questa fase.**
