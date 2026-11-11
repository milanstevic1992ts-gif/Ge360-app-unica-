# GE360 App Unica — Motore interno, accettazione e doppio backup
11 novembre 2026 · ramo di lavoro, non ancora una release collaudata.

## 1. Percorso operativo
- **Sopralluogo**: rilievo tradizionale, rilievo OpenPlan3D o Sopralluogo Smart
- **Preventivo**: bozza → controllo → inviato → accordo con cliente
- **LAVORO ACCETTATO → CANTIERE**: conferma esplicita, cliente/oggetto/importo controllati;
  idempotente (UUID cantiere deterministico), collegamento allo stesso cantiere quando
  già presente, fasi ricavate dalle voci incluse e non opzionali
- **Contratto**: snapshot accettato congelato, storico e revisioni/Extra/Varianti
- **Cantiere**: stato Programmato, non ancora "In corso"; l'avvio effettivo va registrato
  dall'operatore. Non azzerare avanzamenti, ore e costi preesistenti.
- **Note, materiali, attrezzi**: usano l'UUID cantiere per essere reperibili dalla checklist,
  dall'Agenda e dalla cartella cantiere.

Il blocco è applicato sia dall'interfaccia del preventivo sia dalla routine
centrale `EstimateStore.save`: cambiare direttamente lo stato non sostituisce
l'accettazione; prezzi e lavori dell'accordo congelato non sono riscrivibili.
Le informazioni del cantiere (ID, codice, titolo) sono aggiornabili per collegarlo
anche a fronte di un arresto durante l'accettazione.

### Limiti della transazione
I dati sono conservati in database SQLite **distinti**. Non esiste
una transazione ACID che abbracci tutti i moduli. Il flusso è ripetibile
e progettato per riprendersi dopo un crash, ma va collaudato con
simulazione di interruzioni a ogni passaggio e ripristino.

## 2. Backup locale e copia Drive
La funzionalità preesistente comprende:
- ZIP/archivio cifrato V8 con manifest, hash SHA-256 per elemento
- foto Galleria, Scanner, accordi economici, planimetrie native, note,
  cantieri, clienti, stime, storico, finanze, giornate, attrezzi e altro
- cartella GE360 BACKUP sotto due cartelle scelte tramite Storage Access Framework
- verifica SHA-256 *dopo* la scrittura su ciascun provider
- ripristino manuale "Unisci" o "Sostituisci", preflight e snapshot di sicurezza
- job automatico giornaliero (la sua esecuzione dipende dal JobScheduler Android;
  NON è garantita una precisa ora di avvio)

Aggiunte:
- Sopralluoghi Smart: **JSON delle sessioni più immagini private**, presenti
  nello stesso archivio e ripristinati con preflight di coerenza
- una foto dichiarata ma assente o vuota fa fallire il backup, non viene
  semplicemente ignorata
- la scrittura delle foto ripristinate sostituisce atomicamente il file
- il job automatico e quello manuale **segnalano separatamente**:
  copia di lavoro interna, copia nella cartella telefono, copia Drive
- la dicitura "DOPPIA COPIA VERIFICATA" compare solo se entrambe le
  copie esterne sono scritte e rilette correttamente (e backup completo)
- si impedisce di selezionare **la stessa URI** per i due archivi
- per salvare su Drive bisogna attivare **cifratura AES-GCM tramite password**.
  La password va custodita fuori dal telefono e non è recuperabile da GE360.
  Le vecchie copie ZIP non vengono protette retroattivamente.

### Attenzione a "Drive"
La scelta avviene tramite il selettore documenti Android, se il provider
Google Drive supporta l'accesso alle cartelle. La verifica SHA indica che
il provider selezionato ha accettato e restituito gli stessi byte.
**Non certifica** che il file sia già replicato sui server cloud di Google:
quel completamento dipende dal provider Drive e dalla connessione.
È necessario verificare un backup anche da Drive su un secondo dispositivo.

### Cartelle cantieri
`JobsitePersistenceCoordinator` materializza già le cartelle per cantiere
in `Documents/GE360/Cantieri`. `JobsiteReplicaCoordinator` mantiene
le due copie nella cartella `GE360/Cantieri` scelta dall'utente
(senza cancellazione automatica). Il backup ZIP è il meccanismo di
recupero **completo dei dati supportati**; le cartelle leggibili per
cantiere non sono da sole un ripristino dei database.

## 3. OpenPlan3D incluso tramite copia nativa (NUOVO — collaudo necessario)
OpenPlan3D è embedded in WebView e salva geometrie e dati in `localStorage`,
le immagini in `IndexedDB`. Il nuovo bridge `GE360Native` è attivo solo
nell'origine HTTPS locale `appassets.androidplatform.net`. A ogni aggiornamento
della libreria (o della coda offline e del catalogo lavoro), il frontend
trasferisce il contenuto a blocchi verso `OpenPlan3DMirrorStore`, con
checksum SHA-256 e scritture atomiche. Le foto vengono copiate in blocchi
da 128 KiB con checksum originale, prima di essere considerate recuperabili.

Il backup centrale salva:
- `modules/openplan3d.json`: progetti, muri, aperture, ambienti, note,
  lavori, geometrie, configurazioni di disegno e metadati foto
- `files/openplan3d/<id>.bin`: originali delle fotografie collegate
- Coda offline del rilievo, preferenze di catalogo e lavori usati, ma
  **NON** password, token, pairing o API key del backend Debian.

**Regola fondamentale:** un backup NON può dichiararsi completo se
OpenPlan3D non è mai stato aperto con la nuova versione dell'app, se un
salvataggio è interrotto o se manca una fotografia. Aprire quindi Rilievi
avanzati almeno una volta prima di usare BACKUP COMPLETO.

### Recupero con conferma e senza perdita di rilievi
- L'archivio ZIP verificato viene importato nel deposito nativo con
  tutti i file fotografici e si marca il ripristino come **pendente**
- Alla prossima apertura di Rilievi avanzati, il bridge copia prima le
  foto nella IndexedDB WebView e **solo dopo** pubblica la libreria
- **Unisci** preserva i rilievi già presenti sul telefono e inserisce
  quelli con ID non ancora presenti. Le voci locali omonime prevalgono
- **Sostituisci** sostituisce la libreria WebView solo per scelta
  esplicita durante il ripristino generale. I dati precedenti sono protetti
  dal backup di sicurezza creato prima del restore
- In caso di interruzione la marcatura rimane pendente: si può riaprire
  OpenPlan3D e ripetere il trasferimento senza perdere i file
- Dopo il recupero bisogna aprire OpenPlan3D e controllare piante e foto
  per **finalizzare** il ripristino. Fino ad allora il backup successivo
  rimane bloccato e il disegnatore potrebbe mostrare ancora dati vecchi

### Collaudo logico e protezione dalle modifiche concorrenti
In corso di revisione è emerso un caso di concorrenza: il browser poteva
iniziare a trasmettere uno snapshot precedente dopo che l'utente aveva già
effettuato un'altra modifica. Ora ogni richiesta di inizio snapshot porta
la **revisione della libreria catturata prima della serializzazione e del
calcolo SHA-256**. Il deposito Android conserva l'ultima revisione segnalata
e cancella il marcatore di backup incompleto *solo* se la revisione consegnata
corrisponde a quella più recente. Anche le modifiche durante l'inizializzazione
del WebView marcano la copia nativa come non sincronizzata.

**Verifiche eseguite sul sorgente corrente (ambiente di test isolato):**
- 12/12 test JavaScript di logica effettivamente eseguiti, inclusa una
  simulazione asincrona di modifica durante il trasferimento (revisioni 0,
  1 e 2 senza dichiarare pulita la copia 1);
- 10/10 controlli statici dei collegamenti fra JavaScript, Android, ZIP,
  fotografia e cifratura superati; parser JavaScript app.js senza errori.

**Non equivalgono a test Gradle, ad esecuzione sul telefono o a una prova
completa di ripristino dopo disinstallazione.** Il runner GitHub Actions
non è stato avviato e non è stato prodotto alcun APK.

### Limiti che restano
- Non è stato eseguito un collaudo reale dell'intero ciclo su smartphone:
  gli unit test JS/Android sono predisposti ma non ancora eseguiti
- Archivi complessi (grandi quantità di rilievi o foto >25 MB) possono
  richiedere una gestione dedicata; file oltre soglia bloccano il
  backup invece di essere omessi
- Il WebView richiede `WEB_MESSAGE_LISTENER` supportato dall'Android System
  WebView in uso. Se assente, il backup segnalerà impossibilità di sincronizzare
- L'archivio **del server Debian GE360-Backend** non è compreso nel backup
  dello smartphone: richiede backup e test di ripristino dedicati
- Alcune autorizzazioni di sistema, credenziali e permessi SAF non si possono
  trasferire su un altro telefono: vanno riconfigurati manualmente

## 4. Come usare dopo il collaudo
1. Apri **Backup e sincronizzazione**.
2. Configura una cartella persistente sul telefono e una cartella
   **diversa** disponibile sul provider Google Drive.
3. Attiva la password backup e conservala in luogo sicuro.
4. Apri **Rilievi avanzati OpenPlan3D** una prima volta e verifica che i progetti/foto si sincronizzino.\n5. Premi **BACKUP GE360 + OPENPLAN3D · telefono + Drive**.
6. Controlla che siano mostrati *telefono OK* e *Drive OK*.
7. Esegui un ripristino di prova da uno ZIP proveniente dalla seconda
   destinazione, idealmente su un altro dispositivo.
8. Dopo il restore, apri **OpenPlan3D** e completa la riconciliazione dei progetti/foto.\n9. Attiva backup giornaliero; controlla periodicamente lo stato, perché
   eventuali interruzioni di rete o le ottimizzazioni Android possono
   ritardare o far fallire una copia.
10. Prima di ripristinare con "Sostituisci" l'app tenta un backup di
   sicurezza; **non disinstallare/azzerare l'app durante il ripristino**.

## 5. Revisione del motore prima del rilascio (audit)

Sono emersi e sono stati corretti alcuni casi di incoerenza importanti:

- **Foto durante il restore**: una foto locale che non veniva copiata sul
  mirror poteva produrre un avviso ma consentire alla UI di confermare il
  ripristino. Ora la sincronizzazione richiesta dal restore è `strict`:
  un errore lascia il restore in stato pendente. Anche Android rifiuta
  `mirror-restore-complete` se esistono modifiche pendenti o foto mancanti.
- **Sopralluoghi Smart durante il backup**: la corrispondenza fra JSON delle
  sessioni e file foto viene ora verificata anche alla creazione dello ZIP,
  non solo prima del ripristino.
- **Rilievi aggiornati durante il backup**: dopo aver costruito lo ZIP si
  confronta la libreria OpenPlan3D corrente con la revisione inserita nel
  pacchetto. Una modifica concorrente invalida quella copia; non viene
  dichiarata completa.
- **Ripristino su nuovo telefono**: prima della selezione del backup viene
  verificato che il mirror locale sia inizializzato, perché il motore deve
  poter creare una copia di sicurezza dei dati correnti. La UI guida ad
  aprire OpenPlan3D una volta per inizializzarlo; in questa versione il
  bootstrap non è ancora interamente automatico.
- **Esito ripristino**: il dialogo distingue la prima fase nativa dalla
  riconciliazione OpenPlan3D, che deve avvenire riaprendo il disegnatore.

**Verifica disponibile**: 12/12 test logici JavaScript della libreria mirror
superati in ambiente isolato con oggetti simulati, inclusi ripristino con foto
fallita e modifica durante il trasferimento. Controllo statico dei cinque
principali file Kotlin modificati senza delimitatori incoerenti. **Non sono
test Gradle, compilazione Android o prove reali del sistema di archiviazione.**

**Rischi aperti da validare prima di considerarlo affidabile**:
- SQLite di Preventivi, Cantieri e altri moduli usa database separati:
  gli UUID e il recupero a tentativi riducono i duplicati, ma non offrono
  una transazione ACID tra tutti i moduli
- Backup e modifiche concorrenti agli altri archivi (non OpenPlan3D)
  richiedono collaudi di consistenza su database reali
- Una foto illeggibile nella Galleria fa fallire, correttamente, la modalità
  backup completa e può impedire anche il backup di sicurezza pre-restore;
  occorre una procedura operativa per riparare gli allegati difettosi
- Il provider Google Drive via SAF può confermare la scrittura prima della
  sincronizzazione effettiva: collaudare il download da un secondo telefono
- `GE360-Backend` sul Debian resta esterno al backup dello smartphone
- Non è ancora stata provata la procedura di ripristino dopo disinstallazione
  o su dispositivo Android appena inizializzato.

## 5. Test e rilascio
- Test nuovi per `EstimateAcceptanceEngine` (UUID stabile e verifiche stato)\n- Test JavaScript `scripts/test-openplan3d-native-mirror.mjs`: merge, replace, foto, path traversal e chunking
- Richiedono collaudo su telefono: upload e replay WebView/IndexedDB, stato jobsite, fasi parziali, snapshot,
  ritentativi dopo crash, importazione Smart con foto, controlli SHA e
  restore Unisci/Sostituisci, backup automatici su entrambe le cartelle
- **Nessun APK creato né GitHub Actions avviata** per questo ramo.
- PR resta draft finché test Gradle e collaudo Android non passano.
