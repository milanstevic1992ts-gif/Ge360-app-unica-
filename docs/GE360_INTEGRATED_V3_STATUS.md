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


## Hardening del 1 novembre 2026 — source review, senza compilazione

### Backup distinto da sincronizzazione
- `MaterialInventoryStore.exportJson()` rimane **integrale** per il backup del dispositivo.
- `MaterialInventoryStore.exportSyncJson(limit=500)` espone solo gli ultimi 500 movimenti nello snapshot, con `totalMovementCount`, `syncedMovementCount` e `completeMovementHistory=false` quando non si può attestare la completezza.
- `ToolsStore.exportJson(forSync=true)` è usato da `DataHubSyncCoordinator`, mentre Backup continua a usare `ToolsStore.exportJson()` senza flag.
- La nuova installazione apre il database prima di leggere la provenienza dello storico; il flag di primo avvio è scritto in modo sincrono. Gli archivi precedenti senza provenance attendibile restano cautelativamente **non attestati**.

### Protezione dei dati in ripristino Materiali
- `MaterialInventoryStore.importJson(…, replaceAll=false)`: in presenza dello stesso UUID, le giacenze/attributi di materiale e i movimenti devono coincidere, ignorando solo gli ID locali e il timestamp catalogo rigenerato da `save()`. Divergenze generano errore e rollback della transazione di importazione Materiali.
- Movimenti con UUID vuoto, quantità non finita, tipo sconosciuto o riferimento a materiale assente sono respinti.
- Lo stato `complete_history_v1` non viene impostato a vero se si combinano archivi incompleti senza prova sufficiente.
- **Limite documentato:** `ToolsStore`, Materiali e piani sono database differenti; il rollback totale del backup continua a dipendere dalla procedura di safety backup di `BackupCoordinator`.

### UI Web Glass
- Il dettaglio Materiali ora mostra il numero di eventi presenti nello snapshot rispetto al totale dichiarato e differenzia uno storico dichiarato completo da uno parziale/non attestato.
- La differenza previsto/consumato resta bloccata su storico incompleto.

### Gate ancora obbligatori — non eseguiti
1. Validare l'interazione dei database SQLite con backup vecchi e nuovi, merge conflittuale, `replaceAll` e ripristino dopo errore.
2. Accertare che i documenti Scanner PDF e riferimenti Compensi sopravvivano a un backup/restore con file realmente presenti.
3. Verificare l'upload Data Hub del payload Materiali limitato e il comportamento a 499, 500 e 501 movimenti; confrontare il limite effettivo lato server.
4. Confrontare i conti sui materiali con 0 movimenti, 2 cantieri e storici non certificabili.
5. Verificare coerenza Compensi/Spese in caso di errore fra creazione dell'uscita e collegamento del riferimento, mai pagamenti reali.
6. Compilare i moduli Kotlin e verificare la UI su emulator/dispositivo **solo su autorizzazione**.

**Stato:** CODICE_SU_BRANCH. Non è una versione collaudata. Nessuna Actions, build, APK o deploy avviata da questa fase.
