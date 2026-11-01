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


## GE360 Rilievi → Revisione quantità → Preventivi — incremento V3J, 1 novembre 2026

**Stato: CODICE SU FEATURE BRANCH, NON COMPILATO / NON COLLAUDATO.** È stata scritta una via di ingresso conservativa per trasferire una quantità da un rilievo già collegato a un preventivo in **Bozza** o **Da controllare**, senza produrre fatture, prezzi, computi ufficiali o modifiche ai preventivi già inviati/accettati.

### Nuovo percorso operativo
1. Dalla lista Rilievi, selezionare `RIVEDI QUANTITÀ → BOZZA PREVENTIVO`. La schermata mostra le selezioni `workBindings[]` già create e lo stato **PRONTA PER REVISIONE / MISURE DA RICONFERMARE**, ricalcolato con il motore di fingerprinting geometrico. Le vecchie selezioni senza fingerprint non sono importabili.
2. Scegliere una selezione: vengono visualizzati preventivo, ambiente, catalogo, superficie, **lordo, aperture già sottratte e netto rilevato**.
3. Inserire una **ulteriore detrazione manuale** in m² (opzionale; se maggiore di zero la motivazione è obbligatoria). Non sottrarre una seconda volta le aperture già dedotte. La detrazione deve essere arrotondata al centesimo e lasciare superficie positiva.
4. Verificare le altre voci simili: se il preventivo contiene una voce manuale dello stesso codice e ambiente, richiedere esplicita conferma e motivazione. Le sovrapposizioni fra intere pareti e singole pareti già importate con lo stesso codice sono bloccate anche se confermate.
5. Solo dopo spunta `CONFERMO`, una voce catalogo `m²` viene inserita con `included=false`, `optional=false`, `unitPrice=0`, `total=0` e quantitativo registrato. Si può aprire subito l'editor Preventivi.

### Implementazione
- `MeasuredEstimateDraft.kt` (modulo `feature:preventivi`): DTO di collegamento tra UUID binding/rilievo/ambiente/parete, hash geometrico, quantità misurata, detrazione aggiuntiva, motivo e verifica sovrapposizioni. UUID **deterministico** della voce per prevenire doppie importazioni con la stessa fonte. Annotazione di provenienza `GE360_METRICO_V1:` in `EstimateItem.internalNote` (già esportata dal backup Preventivi).
- `EstimateStore.insertMeasuredReviewLine()`: **un'unica transazione SQLite** sul DB Preventivi, verifica preventivo e versione `updatedAt`, status `Bozza/Da controllare`, codice catalogo `m²`, assenza di stesso binding e di lavoro sovrapposto sul medesimo ambiente, assenza di voce manuale ambigua non riconosciuta. Inserisce soltanto la nuova riga esclusa e aggiorna `updated_at`; **non cancella né reinserisce le altre righe e non tocca subtotal/finalTotal**.
- `FloorPlansActivity` ripete il controllo della geometria all'ultimo momento prima del trasferimento, poi chiede conferma e offre il collegamento diretto alla scheda del preventivo.
- `EstimatesActivity.editItem()` conserva identità/quantità/unità/ambiente e nota di origine delle nuove voci metriche: per cambiare metratura si torna a Rilievi. Quando l'operatore decide di includere la voce nel totale deve confermare esplicitamente il controllo delle misure e del prezzo.
- `MeasuredEstimateDraftTest.kt`: scenari per detrazioni, idempotenza UUID, superfici sovrapposte, origine e conferme. **I test sono stati solo scritti, non eseguiti.**

### Limiti e gate
- Prezzi **non calcolati automaticamente** e nessuna autorizzazione bancaria/contabile implicita.
- Il salvataggio del preventivo tramite l'editor legacy usa ancora `EstimateStore.save()` e riscrive l'elenco delle righe; è stato aggiunto un **controllo della revisione** quando il preventivo corrente contiene almeno un import metrico. Un editor stantio viene rifiutato per evitare la perdita delle nuove righe. Rimane comunque da implementare optimistic locking uniforme per tutte le righe e tutti i moduli.
- I database Rilievi e Preventivi sono separati: il fingerprint viene riletto prima della transazione, ma non c'è transazione ACID cross-database. Le dimensioni non sono computi certificati o quantità contrattuali.
- L'annotazione `internalNote` resta tecnicamente editabile attraverso altri client/API/import. Per maggiore robustezza in produzione, progettare vincolo persistente di origine fuori dalle note e gestione delle revisioni a preventivo già prezzato.
- Verificare migrazioni, export/import, scenari con duplicati manuali e reali, cantiere collegato e stessa lavorazione su due facce diverse. Compilazione, UI Android, JUnit, GitHub Actions, APK, PR, merge e deploy **non autorizzati e non eseguiti**.

### Hardening aggiuntivo dello stesso incremento
- Il picker iniziale accetta solo preventivi `Bozza` e `Da controllare`, senza creare nuovi link a preventivi finalizzati.
- La verifica di sovrapposizione sfrutta il codice lavorazione memorizzato nel riferimento di origine (non soltanto l'eventuale campo modificabile `priceListCode` della riga).
- La schermata Preventivi blocca l'attivazione economica di una voce importata se manca la spunta di riconferma oppure il prezzo è nullo/non positivo.
- `EstimateStore.save()` confronta `updatedAt` dell'editor con il valore attuale del preventivo quando quest'ultimo contiene voci metriche. Le copie stantie non possono cancellarle silenziosamente.
- Se il passaggio va a buon fine, viene offerta l'apertura diretta del preventivo.
- I test JUnit sono stati solamente aggiunti al repository. Nessuna esecuzione di test, Gradle o APK.

### Attenzione QA
- Il riconoscimento di voci manuali non correlate resta conservativo ma non può dimostrare automaticamente che due lavori siano o meno duplicati: serve valutazione esplicita dell'operatore.
- I nuovi controlli interattivi non sostituiscono un workflow di approvazione economica centralizzato. La provenienza nelle note non è crittograficamente vincolante.


## 1 novembre 2026 — Preventivi: registro autorevole origine metrica V2

**Stato: implementato solo su `feature/ge360-integrated-v3-20261101`. NON compilato, testato né distribuito.** Nessuna modifica a `main`, Debian, GitHub Actions, APK o marketing.

### Motivazione
`GE360_METRICO_V1:` nella nota interna non garantiva l'integrità della provenienza durante operazioni di editor, copia, importazione o variazione del prezzo. `EstimateStore.save()` riscrive inoltre le righe del preventivo: la nuova fonte deve essere indipendente dall'ID SQLite delle righe, mantenendo UUID stabili.

### Implementazione
- **DB Preventivi v1→v2**: tabelle `estimate_metric_sources` (snapshot originale indipendente da `internalNote`, legato a UUID voce, FK id preventivo, UUID binding unico per preventivo) e `estimate_metric_events` (storico conferme `IMPORT` e `PRICE_REVIEW`). Le origini non sono aggiornate dai normali editor, anche quando `estimate_items` viene ricreata; eventi economici inseriti solo in append dal codice applicativo.
- `MetricProvenanceV2.kt`: fotografia delle quantità lorde, aperture sottratte, detrazioni aggiuntive, quantità finale, UUID rilievo/stanza/muro/binding, codice catalogo, impronta SHA-256 e fiducia `REVIEWED_FROM_GEOMETRY` / `LEGACY_NOTE_ONLY`.
- **Migrazione conservativa**: riferimenti nelle note esistenti vengono importati nel registro con fiducia `LEGACY_NOTE_ONLY` solo se analizzabili e coerenti. Le altre note non vengono promosse; l'editor blocca modifiche sospette di contenuti metrici senza provenienza valida. Il dato `LEGACY_NOTE_ONLY` non può essere autorizzato economicamente senza nuova revisione.
- `EstimateStore.insertMeasuredReviewLine()` scrive, in una transazione, la voce esclusa da totale, la fonte V2 e l'evento `IMPORT`. Confronta duplicati e sovrapposizioni su tabella metrica, **non** su testo delle note.
- `EstimateStore.save(record, metricConfirmations)`: impedisce perdita/rimozione di righe metriche senza procedura dedicata; preserva quantità, codice lavorazione, unità e stanza riferiti alla fonte; rifiuta editor stantii; eventuale variazione di prezzo o inclusione richiede origine verificata, stato `Bozza/Da controllare` e motivo di almeno 8 caratteri. L'evento `PRICE_REVIEW` registra prezzo/inclusione prima e dopo. Ogni salvato è transazionale nel medesimo DB Preventivi.
- `EstimatesActivity`: nota interna non più autorevole; la schermata consulta il registro V2 per bloccare quantità e riferimenti, chiede spunta e motivazione alla variazione economica, mostra lo storico delle verifiche. La rimozione rapida di voce con origine è bloccata.
- `EstimateStore.duplicate()`: una metratura presente in copia di un preventivo diventa **informativa, fuori totale e a prezzo 0**, senza ereditare falsa provenienza; occorre rieseguire il collegamento al rilievo.
- `EstimateStore.exportJson()/importJson()`: nuovi array `metricProvenance[]` e `metricReviewEvents[]` nello schema JSON compatibile `ge360.preventivi.v1`. Restore transazionale sull'intero DB Preventivi con validazione UUID, corrispondenza item/fonte e conflitti; backup precedenti privi di array accettati, con origine note-only non verificata.
- `MetricProvenanceV2Test.kt`: test di contratto scritti ma **non eseguiti**.

### Gate di sviluppo aperti
- Validazione compilazione/strumentazione/migrazione v1→v2, import vecchi backup, restore con eventi/duplicati, editor con concorrenza, roundtrip ZIP GE360.
- **Varianti**: ancora da progettare come sostituzione/supersessione controllata con nuova revisione di metratura e preservazione delle scelte economiche precedenti; per ora le fonti correnti non si eliminano/modificano tramite editor standard.
- Gestione multi-database Rilievi/Preventivi: controllo revisione geometria prima del trasferimento, ma nessuna transazione distribuita; richiede collaudo.
- Il registro riduce le modifiche accidentali ma NON è una firma digitale crittografica, un ledger antifrode o un computo metrico ufficiale. I backup JSON non sono autenticati con firma.
- La logica generale degli editor legacy resta preesistente fuori dai percorsi metrici: ulteriori gate di optimistic locking/immutabilità sono previsti.


## 1 novembre 2026 — Varianti metriche V3 (proposte non distruttive)

**Stato: codice su feature branch, NON compilato/testato, senza APK/Actions/merge/deploy.**

### Decisione GE360
Una variante di metratura su un preventivo già inviato/accettato NON riscrive la vecchia riga, NON aggiorna automaticamente il totale, NON crea SAL/fatture/pagamenti. Il delta in m² e l'impatto in € calcolato con il prezzo storico sono **solo informativi**, anche quando negativi. Le verifiche tecniche restano separate da qualsiasi successiva approvazione contrattuale/economica.

### Funzionalità introdotte
- `MetricVariantV3.kt`: snapshot UUID della proposta con fonte metrica precedente e nuovo binding, plan/room/wall/work/surface, SHA-256 delle due revisioni, quantità precedente e nuova, apertura e detrazioni della nuova revisione, prezzo unitario precedente, stato `PROPOSTA` o `VERIFICATA_TECNICAMENTE`, motivazioni e tempi. Delta quantitativo con segno, delta € indicativo calcolato con BigDecimal senza toccare somme.
- **Preventivi DB V2→V3**: nuove tabelle `estimate_metric_variants` e `estimate_metric_variant_events`, FK verso il preventivo, UUID stabili, unicità per vecchia voce e nuovo binding, audit append-only degli eventi proposta e verifica tecnica.
- `EstimateStore.proposeMetricVariant()`: accetta solo una nuova selezione con hash diverso, stessa planimetria, stanza, superficie, eventuale singola parete e codice catalogo della precedente, provenienza V2 verificata, delta quantitativo non nullo e motivazione. Verifica che la revisione del preventivo e il prezzo storico non siano cambiati. Una proposta per voce originale alla volta, anche se il preventivo è già accettato; rifiuta preventivi Rifiutati. **La nuova selezione è controllata contro il Rilievo LIVE dalla UI prima della creazione, ma non esiste una transazione distribuita fra i due database.**
- `EstimateStore.reviewMetricVariant()`: conferma soltanto la verifica tecnica con motivo, registrando un evento senza modificare le voci/prezzi/totali originali. Richiede corrispondenza del prezzo e dello stato di inclusione con lo snapshot proposto; se il preventivo cambia, rifiuta la conferma.
- `FloorPlansActivity`: pulsante `NUOVA VARIANTE · CONFRONTA METRATURE`; cerca nuove selezioni geometriche con la stessa identità UUID della fonte importata; mostra quantità vecchia e nuova, aperture, prezzo precedente, detrazione manuale opzionale motivata e impatto indicativo prima del salvataggio.
- `EstimatesActivity`: voce `Varianti di metratura` nella scheda Preventivo, elenco varianti, stato, delta con segno e storico; dialog per verifica tecnica motivata. Nessuna azione economica è collegata alla verifica tecnica.
- Backup schema compatibile `ge360.preventivi.v1`: array facoltativi `metricVariants[]` e `metricVariantEvents[]`; restore transazionale con validazione delle origini V2 verificate, degli UUID e del prezzo/quantità di partenza. Esistenti backup senza nuovi array rimangono ammissibili.
- Contratti JUnit aggiunti a `MetricVariantV3Test.kt` **senza eseguirli**.

### Limiti deliberati / gate per prossima fase
1. Nessun credito, extra economico, nuova voce prezzo, supplemento contrattuale, firma, SAL o fattura viene applicato. Il delta negativo è mostrato soltanto, perché il calcolo legacy `calculateItemTotal()` non ammette automaticamente importi negativi.
2. Una variante per singola voce storica è ammessa, non una catena di infinite varianti sovrapposte. Servirà decidere approvazione/rifiuto/supersessione formale, autorizzazioni e successivi stati economici, senza doppio conteggio.
3. UI di verifica tecnica in Preventivi mostra uno snapshot: chi conferma deve controllare effettivamente le nuove misure; non certifica che il Rilievo non sia cambiato successivamente.
4. Eseguire (solo con autorizzazione) compilazione Android, test Kotlin, integrazione UI, migrazione SQLite V2→V3, backup restore/merge, rollback, edge cases cantiere facoltativo, status Preventivi e delta positivi/negativi. Nessuna delle verifiche è stata eseguita.
