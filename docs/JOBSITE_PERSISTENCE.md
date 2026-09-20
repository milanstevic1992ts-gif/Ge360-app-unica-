# GE360 — Persistenza completa della cartella cantiere

## Regola non negoziabile

Ogni informazione associata a un cantiere deve esistere anche nella cartella fisica di quel cantiere sul dispositivo.

Il database SQLite resta la fonte operativa dell'applicazione, ma **non deve essere l'unica copia** di cronologia, note, dati strutturati o riferimenti ai documenti.

La politica GE360 usa livelli distinti:

1. **copia operativa Android** nella memoria dell'app;
2. **replica locale persistente** nella cartella del telefono scelta dall'utente;
3. **replica Google Drive** nella cartella Drive scelta dall'utente;
4. **replica PC Linux futura** tramite GE360 Data Hub / File Vault, usando la stessa struttura e gli stessi ID.

Backup ZIP e cartella viva del cantiere sono due sistemi diversi e complementari. Il backup serve al ripristino; le repliche mantengono una cartella cantiere consultabile e aggiornata.

## Componenti

Materializzazione della cartella cantiere:

`app/src/main/java/it/edilmilan/ge360/storage/JobsitePersistenceCoordinator.kt`

Replica doppia telefono + Drive:

`feature/backup/src/main/java/it/edilmilan/ge360/backup/JobsiteReplicaCoordinator.kt`

Sincronizzazione dati verso backend GE360/Linux:

`app/src/main/java/it/edilmilan/ge360/sync/DataHubSyncCoordinator.kt`

I coordinatori vengono installati da `MainActivity` e sincronizzano:

- all'avvio dell'app;
- al ritorno sulla schermata principale;
- quando un'Activity operativa viene sospesa;
- immediatamente quando un'Activity viene fermata;
- nuovamente se arriva una richiesta mentre una sincronizzazione e' gia' in corso.

La replica verso cartella persistente e Drive viene richiesta soltanto dopo che la materializzazione locale del cantiere è terminata, così non può copiare uno snapshot precedente.

Le operazioni pesanti vengono eseguite fuori dal thread UI.

## Copia operativa Android

Base principale:

`<externalFilesDir/Documents>/GE360/Cantieri/`

Fallback se la directory Documents non e' disponibile:

`<filesDir>/GE360/Cantieri/`

Ogni cartella usa codice cantiere + prefisso dell'ID stabile GE360. Se il codice cambia, il coordinatore cerca la cartella tramite il suffisso dell'ID e prova a rinominarla senza creare duplicati.

Questa copia e' la sorgente della replica ma non sostituisce la cartella persistente scelta dall'utente: la memoria privata dell'app puo' essere rimossa con la disinstallazione.

## Replica locale persistente e Google Drive

Le due destinazioni usano le cartelle gia' configurate nella schermata **Backup & sincronizzazione**:

- `Scegli cartella backup sul telefono` -> radice della replica locale persistente;
- `Scegli cartella Google Drive` -> radice della replica Drive.

Dentro entrambe viene mantenuta la stessa struttura:

`<radice scelta>/GE360/Cantieri/CANTIERE-<CODICE>_<ID8>/...`

Le due repliche sono indipendenti. Un problema su Drive non impedisce la copia locale e viceversa.

La replica e' incrementale: i file invariati vengono saltati; quelli nuovi o modificati vengono copiati. La replica e' **non distruttiva**: non cancella automaticamente file storici presenti nelle destinazioni.

Anche la materializzazione è incrementale: `SYNC-STATE.json` conserva l'impronta SHA-256 dello stato strutturato e degli allegati del singolo cantiere. Se l'impronta non cambia, GE360 evita di riscrivere la cartella e non avvia una nuova replica. Una cartella priva dello stato viene invece materializzata completamente.

## Struttura della cartella

```text
CANTIERE-<CODICE>_<ID8>/
├── cantiere.json
├── LEGGIMI.txt
├── INDICE-FILE.json
├── SYNC-STATE.json
├── SYNC-MANIFEST.json
├── CRONOLOGIA/
│   ├── cronologia.json
│   └── cronologia.txt
├── DOCUMENTI/
│   ├── FIR/
│   │   ├── registro-fir.json
│   │   └── *.pdf
│   ├── SCANNER/
│   │   └── <TIPO>/
│   │       └── *.pdf
│   ├── PLANIMETRIE/
│   │   └── *
│   └── CLIENTE/
│       └── *
├── FOTO/
│   └── <FASE>/
│       └── *
├── DATI/
│   ├── cantieri.json
│   ├── centro-cantiere.json
│   ├── cartella-cantiere.json
│   ├── giornata.json
│   ├── spese.json
│   ├── scanner.json
│   ├── fir.json
│   ├── preventivi.json
│   ├── preventivi-ambienti.json
│   ├── preventivi-cronologia.json
│   ├── preventivi-personalizzazioni.json
│   ├── planimetrie.json
│   ├── attrezzi.json
│   ├── furgone.json
│   ├── galleria.json
│   └── cliente.json
└── LOG/
    └── sync-errors.log
```

## SYNC-MANIFEST e Linux

Ogni cartella cantiere contiene `SYNC-MANIFEST.json` con:

- nome cartella stabile;
- percorso relativo stabile `GE360/Cantieri/<cartella-cantiere>`;
- stato di configurazione della replica locale;
- stato di configurazione della replica Drive;
- policy non distruttiva;
- destinazione prevista per il futuro GE360 File Vault Linux.

Questo e' il contratto per il backend Debian: il futuro servizio file non deve inventare una seconda nomenclatura. Deve ricevere e mantenere la stessa gerarchia, identificando il cantiere tramite il suo ID GE360 stabile.

Il `DataHubSyncCoordinator` gia' sincronizza gli snapshot strutturati dei moduli verso il backend GE360 quando configurato. La fase Linux successiva aggiungera' al Data Hub il trasporto dei file del vault usando il `SYNC-MANIFEST`, checksum e percorsi relativi.

## Cronologia

La cronologia non deve vivere soltanto nella tabella SQLite `workflow_events`.

Per ogni cantiere viene generato:

- `CRONOLOGIA/cronologia.json`: snapshot strutturato completo del Centro Cantiere filtrato per `jobsiteId`;
- `CRONOLOGIA/cronologia.txt`: versione leggibile degli eventi con data, tipo, titolo e dettaglio.

La prima sincronizzazione recupera anche gli eventi storici gia' presenti nei database, quindi la funzione non vale soltanto per i nuovi eventi.

## FIR

Per ogni cantiere:

- i record FIR strutturati vengono salvati in `DOCUMENTI/FIR/registro-fir.json`;
- i PDF dello Scanner con tipo `FIR` vengono copiati fisicamente in `DOCUMENTI/FIR/`;
- il record Scanner originale continua a esistere nel database e nel normale sistema di backup;
- cartella e PDF vengono poi replicati sia sulla destinazione locale persistente sia su Drive quando configurate.

Quindi un FIR non e' piu' soltanto un collegamento tramite `jobsiteId`: il cantiere possiede copie fisiche indipendenti.

## Altri documenti

I documenti Scanner non FIR vengono copiati in `DOCUMENTI/SCANNER/<TIPO>/`.

Planimetrie e documenti cliente gestiti da `JobsiteArchiveStore` vengono copiati rispettivamente in:

- `DOCUMENTI/PLANIMETRIE/`;
- `DOCUMENTI/CLIENTE/`.

Gli scontrini garanzia degli attrezzi sono invece documenti aziendali globali, non
appartengono automaticamente a un singolo cantiere. Vengono conservati in
`GE360/Scontrini garanzia/<ANNO>/`, indicizzati con nome/tipo/data e inclusi nel
backup Scanner con il tipo canonico `SCONTRINO_GARANZIA`.

## Foto

Le foto legate al cantiere vengono replicate in `FOTO/<FASE>/` mantenendo il collegamento al `ge360Id` nel nome file e nell'indice.

La copia fisica nella cartella cantiere e' aggiuntiva rispetto alla Galleria GE360. Quando le destinazioni sono configurate, anche questa struttura viene replicata su cartella persistente e Drive.

## Dati strutturati

La directory `DATI/` contiene snapshot per-cantiere dei moduli che possono contenere dati legati al cantiere:

- Cantieri e avanzamento;
- Centro Cantiere / checklist / note / eventi;
- Cartella Cantiere / appunti / documenti;
- Giornata;
- Spese;
- Scanner;
- FIR;
- Preventivi;
- ambienti, cronologia e personalizzazioni Preventivi;
- Planimetrie;
- Attrezzi;
- preparazioni Furgone;
- Galleria;
- Cliente associato.

Gli export globali dei moduli vengono filtrati sullo stabile `jobsiteId`/`ge360Id`, evitando di mescolare dati di cantieri diversi nella singola cartella.

## Strategia di sicurezza

La sincronizzazione e la replica sono **non distruttive** per gli allegati fisici: se un record sparisce dall'interfaccia, i coordinatori non cancellano automaticamente la vecchia copia dal vault cantiere, dalla replica locale o da Drive.

Motivo: documenti FIR, foto, scansioni e allegati di cantiere hanno valore documentale. Una cancellazione accidentale nell'app non deve eliminare automaticamente anche le altre copie.

`INDICE-FILE.json` rappresenta invece lo stato corrente dei record conosciuti dall'app.

## Backup GE360

Il sistema backup esistente continua a salvare le fonti canoniche:

- JSON Centro Cantiere, Giornata, Spese, Scanner, FIR, Preventivi, Attrezzi, Galleria e Cartella Cantiere;
- PDF Scanner/FIR;
- file della Cartella Cantiere;
- fotografie leggibili anche nel backup automatico giornaliero completo.

Dopo un ripristino, la sincronizzazione della cartella cantiere ricostruisce nuovamente gli snapshot e le copie fisiche disponibili a partire dai dati ripristinati; successivamente il replica coordinator riallinea telefono persistente e Drive.

## Regola per modifiche future

Quando viene aggiunto un nuovo modulo che salva dati o file con `jobsiteId`, deve essere valutato anche in `JobsitePersistenceCoordinator`.

La sua rappresentazione nella cartella cantiere deve poter essere replicata senza logica speciale da `JobsiteReplicaCoordinator` e in futuro dal File Vault Linux.

Una feature legata al cantiere non e' considerata completa se il suo contenuto resta esclusivamente in memoria, in SQLite o in una cartella globale senza replica nella cartella fisica del cantiere.
