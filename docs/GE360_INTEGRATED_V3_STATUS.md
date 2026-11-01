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


## Incremento CRM e Rilievi — 1 novembre 2026

Stato: **codice salvato sul branch integrato; nessuna build, test, APK, merge, deploy o modifica Debian.**

### Clienti — cronologia fattuale
- `ClientStore.kt`: migrazione additiva database Clienti v1→v2; nuova tabella `factual_client_timeline` con UUID stabile dell'evento e del cliente, cantiere opzionale, fonte, data, tipo, descrizione, prossima azione.
- `ClientFactualTimeline.kt`: tipi di evento osservabili e convalida data ISO; niente diagnosi, valutazione di personalità, scoring automatico o nuovo punteggio «polso».
- `ClientsActivity.kt`: cronologia leggibile e inserimento manuale con tipo evento, fonte, testo, data prossima azione.
- `ClientStore.exportJson/importJson`: nuovo array `factualTimeline` opzionale dentro il backup Clienti e lo snapshot `clienti` Data Hub; rifiuto di conflitti con UUID già presente e dati diversi; backup precedente senza timeline lascia gli eventi attuali inalterati, senza inventarne.
- `ClientFactualTimelineTest.kt`: regressioni predisposte ma non eseguite.
- **Limite:** selezione del cantiere per evento non ancora nella UI Clienti, perché `feature:cantieri` dipende da `feature:clienti`: introdurre la dipendenza inversa creerebbe un ciclo Gradle. Il modello include comunque `jobsiteGe360Id?` per la futura associazione nel livello app.

### Rilievi → Preventivi — selezione senza pricing automatico
- `PlanWorkBinding.kt`: proiezione immutabile con UUID rilievo/ambiente/preventivo, codice lavorazione esistente, superficie pavimento/soffitto/pareti e quantità in m² come fotografia alla selezione, revisione del dato stanza.
- `FloorPlanStore.kt`: migrazione additiva db Planimetrie v2→v3, salvataggio di selezioni multistanza in transazione e `workBindings[]` nell'export/import `ge360.planimetrie.v1`. I backup precedenti restano accettati senza cancellare automaticamente il registro di selezioni.
- `FloorPlansActivity.kt`: pulsante di selezione per rilievo, scelta preventivo già esistente (UUID), voci catalogo m², pavimento/soffitto/pareti, checklist multipla ambienti. Nessuna modifica a `EstimateStore` o listino, nessuna riga/prezzo creati automaticamente.
- Se un ambiente viene modificato dopo la selezione il collegamento appare **da rivedere** in scheda, senza alterare quantità/pricing precedenti.
- `PlanWorkBindingTest.kt`: casi regressione preparati, NON eseguiti.

**Limiti tecnici dichiarati:** `FloorPlanRoom.wallArea()` può fornire una superficie lorda; non certifica automaticamente detrazioni aperture, singole facce o geometrie irregolari complesse. La sola `room.updatedAt` non intercetta necessariamente ogni modifica a muri, aperture o geometria esterna: serve in futuro un fingerprint di geometria canonica/versionata prima di abilitare produzione di computi o preventivi. Il salvataggio associa dati ma non certifica metri né prezzi. L'import storico Planimetrie non è attualmente una transazione ACID globale su tutti i record. Nessun merge verso `main`.

Prossimo gate: revisione statica mirata del codice/compilazione quando autorizzata; test restore versioni vecchie/nuove e il passaggio planimetria→quantità sotto vincoli geometrici e di prezzo.

## Rilievi → Preventivi, geometria verificabile V2 — 1 novembre 2026

Codice su `feature/ge360-integrated-v3-20261101` soltanto. **NESSUNA BUILD, TEST, APK, ACTIONS, MERGE O DEPLOY ESEGUITI.**

### Motore e salvataggio
- `PlanGeometryQuantitiesV2.kt`: SHA-256 deterministico di geometria stanza (modalità, dimensioni e dati manuali) + pareti per ordine/UUID, lunghezza/direzione e nota contenente `[GE360_OPENINGS]`. Esclude ID SQLite locali e timestamp di lettura per sopravvivere al backup/restore.
- Per profili `Pareti quotate`: richiede perimetro chiuso e senza autointersezioni. L'area del pavimento deriva dal poligono; un valore manuale difforme viene bloccato.
- Superfici: `PAVIMENTO`, `SOFFITTO`, `PARETI` lorde, `PARETI_NETTE`, `PARETE_SINGOLA_LORDA`, `PARETE_SINGOLA_NETTA`. Una parete singola rappresenta **una faccia**, non automaticamente due.
- Le superfici nette sottraggono solo porte/finestre esplicitamente salvate nel metadato della parete. Dimensioni finite, aperture non sovrapposte, altezza compatibile con la stanza, UUID, offset e larghezza dentro al muro sono obbligatori. Metadati corrotti causano errore, non uno sconto inventato.
- `PlanWorkBindingV1`: aggiunti `geometryFingerprint`, `wallGe360Id`, `grossM2`, `openingDeductionsM2`, nel backup `workBindings[]`. La quantità netta è un'istantanea separata dalle righe economiche.
- `FloorPlanStore` database v4: migrazione additiva da v3 con nuove colonne; i vecchi binding senza hash sono **legacy da riconfermare**, non vengono promossi a verificati. La chiave di deduplicazione usa geometria e UUID parete, permettendo più muri nello stesso ambiente.
- `addWorkBindings` registra più superfici in una transazione SQLite e ricontrolla geometria/quantità subito prima del salvataggio; se un ambiente o un'apertura cambia durante la scelta, l'operazione è rifiutata.

### Interfaccia
- `FloorPlansActivity`: scelta del preventivo esistente, lavorazione catalogo `m²`, tipo superficie, checklist multi-stanza oppure checklist multi-parete. Selezione/deselezione totale e preview metri/decurtazioni prima di salvare.
- Stato dei binding storici: `PlanGeometryQuantitiesV2.matches` controlla impronta SHA-256, quantità lorda, detrazioni e netta contro le misure attuali. Un hash assente (V1) è sempre non verificato.
- I preventivi esistenti e i loro prezzi **non sono aggiornati** da questo flusso.

### Gate di qualità aperti
1. Eseguire compilazione Kotlin/Gradle, test JUnit, migrazioni reali DB v1/v2/v3→v4, backup/restore e UI mobile **solo dopo autorizzazione**.
2. Netti complessi: aperture non dichiarate, più facce, nicchie, demolizioni, pareti inclinate, contropareti e finiture diverse per superficie richiedono modellazione manuale e verifica in cantiere.
3. Prezzi, preventivi approvati e computi ufficiali restano indipendenti. Prima di creare voci economiche implementare revisione e conferma delle quantità, gestione duplicati tra stanze e singole facce e detrazioni secondo criteri contrattuali.
4. Hash geometria è controllo d'integrità **della selezione locale**, non misura certificata, firma digitale né prova fotografica. Non viene promossa automaticamente alcuna quantità.
5. Backup Planimetrie esistente resta solo parzialmente transazionale tra record e database separati: è necessaria prova di restore/rollback prima dell'uso in produzione.
6. Test preparati in `PlanGeometryQuantitiesV2Test.kt`, **non eseguiti**. Per test delle aperture basati su `org.json`, usare runtime JSON effettivo/strumentazione.
