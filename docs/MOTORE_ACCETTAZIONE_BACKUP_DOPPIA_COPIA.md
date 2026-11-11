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

## 3. COPERTURA ANCORA INCOMPLETA — NON DICHIARARE RIPRISTINO TOTALE
**OpenPlan3D avanzato** salva geometria in `localStorage` WebView e foto in
`IndexedDB`, indipendentemente da `FloorPlanStore` Android.
Questo ramo NON include ancora questi due archivi WebView nel backup ZIP.
Per i rilievi avanzati è attualmente necessario esportare manualmente i
file JSON/foto dal disegnatore. Non disinstallare l'app senza averli salvati.
Serve un ponte nativo WebView ↔ BackupCoordinator con importazione verificata
prima di promettere un ripristino integrale.

Le credenziali del backend Debian, l'archivio **del server Debian GE360-Backend**
e le preferenze di autorizzazione dei provider non sono ripristinate dal
backup dello smartphone. Il server richiede un backup autonomo.
I record e le foto di **Sopralluogo Smart** sono invece inclusi in questo ramo.

## 4. Come usare dopo il collaudo
1. Apri **Backup e sincronizzazione**.
2. Configura una cartella persistente sul telefono e una cartella
   **diversa** disponibile sul provider Google Drive.
3. Attiva la password backup e conservala in luogo sicuro.
4. Premi **BACKUP COMPLETO · telefono + Drive**.
5. Controlla che siano mostrati *telefono OK* e *Drive OK*.
6. Esegui un ripristino di prova da uno ZIP proveniente dalla seconda
   destinazione, idealmente su un altro dispositivo.
7. Attiva backup giornaliero; controlla periodicamente lo stato, perché
   eventuali interruzioni di rete o le ottimizzazioni Android possono
   ritardare o far fallire una copia.
8. Prima di ripristinare con "Sostituisci" l'app tenta un backup di
   sicurezza; **non disinstallare/azzerare l'app durante il ripristino**.

## 5. Test e rilascio
- Nuovi test per `EstimateAcceptanceEngine` (UUID stabile e verifiche stato)
- Richiedono collaudo su telefono: stato jobsite, fasi parziali, snapshot,
  ritentativi dopo crash, importazione Smart con foto, controlli SHA e
  restore Unisci/Sostituisci, backup automatici su entrambe le cartelle
- **Nessun APK creato né GitHub Actions avviata** per questo ramo.
- PR resta draft finché test Gradle e collaudo Android non passano.
