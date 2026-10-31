# GE360 Android — branch integrato Compensi e Materiali

Data: 1 novembre 2026
Branch: `feature/ge360-integrated-v3-20261101`
Base: `feature/ge360-compensi-v1-core-20261031` al commit `1c592cb8331d30baed91b45b8c72cd91564de70e`.

Il branch integrato contiene integralmente il nuovo nucleo Compensi e i file Materiali 2.0/Previsioni provenienti da `feature/ge360-material-variance-v1-20261031`, senza sovrapposizioni fra i percorsi modificati rispetto a main. `main` non è stata alterata.

**Stato: SOLO CODICE, NON COLLAUDATO, NON COMPILATO, NESSUNA APK, NESSUN DEPLOY, NESSUNA PR.**

## Funzioni preparate
- Compensi: registro settimanale/a cottimo, pagamenti parziali, riconciliazione UUID con Spese, esplicita creazione idempotente delle uscite, storni non riconciliati, fattura e prova PDF in Scanner, backup e Data Hub.
- Materiali: storico movimenti completo in backup, provenienza di completezza, previsioni confermate/versionate per materiale e cantiere, confronto quantità nel modulo Android e backup/Sync nested `attrezzi.materialPlans`.
- Le rispettive regressioni Kotlin sono scritte ma non eseguite.

## Attenzione
- Verificare JUnit, compilazione moduli, import JSON, backup/restore, UI 360px, riconciliazione Spese e PDF prima di una PR o release.
- Due database separati Compensi/Spese non hanno transazione ACID comune. Retry con UUID stabile, e possibili movimenti senza riferimento se collegamento non completa.
- Materiali non deduce fatture dagli eventi `Carico`; vecchi backup che potrebbero essere troncati non producono scostamenti certificati.
- Control Center Glass V3 è su branch distinto nel repo `GE360-Backend`, non su Debian.
- Market/Ads in pausa, Scanner FIR e Rilievi non toccati.
