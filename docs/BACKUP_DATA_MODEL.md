# GE360 Backup & Sincronizzazione — modello v1

## Obiettivo

Un solo pacchetto di backup per l'intera App Unica, verificabile prima del ripristino e indipendente da Jarvis/NAS.

## Destinazioni

GE360 mantiene tre livelli distinti:

1. **copia di lavoro interna** in `externalFilesDir/GE360/Backups` — utile per uso corrente, ma può essere eliminata disinstallando l'app;
2. **cartella persistente sul telefono** scelta dall'utente con Android Storage Access Framework — sopravvive alla reinstallazione e mantiene gli ultimi 10 ZIP;
3. **Google Drive** scelto con Android SAF — copia secondaria, senza OAuth web, client secret o backend, con rotazione degli ultimi 10 ZIP.

La cartella persistente e Drive ricevono i file dentro una sottocartella `GE360 BACKUP`.

## Formato

Estensione:

`*.ge360.zip`

Manifest:

`manifest.json`

Schema iniziale:

`ge360.backup.v1`

Il manifest contiene versione app, data, politica media, elenco moduli, mapping dei PDF Scanner, mapping opzionale delle foto e SHA-256 di ogni voce del pacchetto.

Dopo la creazione il file viene riaperto e verificato. Un restore ricalcola i checksum prima di modificare i dati.

## Contenuto backup automatico

Il backup giornaliero contiene:

- `modules/clienti.json`
- `modules/cantieri.json`
- `modules/centro-cantiere.json`
- `modules/jobsite-archive.json`
- `modules/giornata.json`
- `modules/messaggi.json`
- `modules/spese.json`
- `modules/scanner.json`
- `modules/fir.json`
- `modules/preventivi.json`
- `modules/preventivi-rooms.json`
- `modules/preventivi-lifecycle.json`
- `modules/preventivi-customizations.json`
- `modules/attrezzi.json`
- `modules/attrezzi-custom-packs.json`
- `modules/furgone.json`
- `modules/galleria.json`
- `modules/planimetrie.json`
- `modules/fisco-settings.json`
- PDF Scanner disponibili in `files/scanner/`
- PDF degli scontrini garanzia Attrezzi in `files/scanner/`, con nome/tipo/data in `modules/scanner.json`
- foto/documenti leggibili della Galleria in `files/gallery/`

La copia giornaliera privilegia la recuperabilità completa: include anche i file leggibili della Galleria e viene ruotata mantenendo gli ultimi dieci ZIP per destinazione configurata.

## Backup completo manuale

`Backup completo con fotografie` avvia manualmente lo stesso salvataggio completo, includendo le foto/documenti leggibili della Galleria sotto `files/gallery/`.

È destinato soprattutto a:

- cambio telefono;
- archivio completo periodico;
- migrazione dell'intero GE360.

Può diventare molto grande.

## Ripristino

Prima del restore:

1. verifica schema del manifest;
2. verifica SHA-256 delle voci;
3. chiede `Unisci` oppure `Sostituisci`;
4. crea un backup completo di sicurezza prima di modificare i dati.

Se il ripristino fallisce, GE360 tenta automaticamente il rollback usando quel backup di sicurezza e segnala chiaramente anche l'eventuale percorso da conservare.

Ordine logico:

`Clienti → Cantieri → dati operativi → ambienti/cronologia/personalizzazioni Preventivi → pacchetti Attrezzi/Furgone → Scanner/Planimetrie → Galleria → impostazioni Fisco`

### Identità

Gli ID SQLite locali non sono chiavi di integrazione.

Durante il restore Cantieri e Preventivi ricostruiscono `clientId` locale usando `clientGe360Id`. I collegamenti a Cantiere continuano a usare il `jobsiteId` UUID stabile.

### Scanner

I PDF inclusi vengono estratti nel nuovo archivio privato Scanner e `pdfPath` viene ricostruito. Il collegamento a Spese resta tramite UUID `expenseGe360Id`.

I documenti di tipo `SCONTRINO_GARANZIA` vengono invece ricollocati automaticamente in
`GE360/Scontrini garanzia/<ANNO>/`; al termine del restore sono rigenerati gli indici
JSON e TXT con nome attrezzo, tipo e data d'acquisto.

### Galleria

Se il backup contiene fotografie e la cartella Galleria locale è configurata, i file vengono ricreati nelle cartelle del relativo Cantiere/fase e i metadati ricevono il nuovo URI SAF.

Se la cartella Galleria non è configurata, vengono ripristinati i metadati e viene mostrato un avviso: il file media deve essere recuperato/configurato successivamente.

## Backup automatico

`JobScheduler` esegue un backup completo giornaliero. È attivo di default e può essere disattivato dalla schermata Backup; l'ultimo esito è visibile nella stessa schermata.

Se configurate, dopo la creazione e verifica:

- copia nella cartella persistente del telefono;
- copia su Drive.

Nessuna rete è necessaria per creare il backup locale.

## Sicurezza v1

- nessun token, password, API key o keystore entra nel backup;
- nessun segreto viene scritto nel repository;
- SHA-256 rileva pacchetti corrotti o incompleti;
- il formato v1 **non cifra** ancora l'intero ZIP con una password portabile.

La cifratura portabile è un'estensione futura separata, per non rendere irrecuperabili i backup se cambia telefono o Android Keystore.

## Da verificare su dispositivo

- persistenza permessi SAF dopo riavvio;
- creazione automatica giornaliera;
- rotazione 10 locale/Drive;
- restore `Unisci` e `Sostituisci` con dati reali;
- remap Clienti/Cantieri/Preventivi su nuovo dispositivo;
- PDF Scanner dopo restore;
- backup completo e ripristino Galleria;
- comportamento con backup di grandi dimensioni e spazio insufficiente;
- compilazione Android completa.


## Storico avanzamento Cantieri (formatVersion 3 nell'export ZIP)
L'archivio già presente modules/cantieri.json include il nuovo array progressSnapshots
con tutte le rilevazioni datate di avanzamento, oltre a jobsites, progressItems e milestones.
La rilevazione è immutabile per UUID; merge idempotente soltanto se coincide il contenuto,
altrimenti il restore fallisce con transazione rollback. Non si ricostruiscono date
retroattive dal valore di avanzamento attuale.
Il payload di sincronizzazione GE360 Data Hub resta volutamente al formato Cantieri 2,
non include progressSnapshots e richiede un contratto server separato per l'eventuale futura sync.


## Piani lavorazioni Centro Cantiere (schema v2 nel backup ZIP)
La voce già presente modules/centro-cantiere.json contiene taskPlans: durata residua/prevista
in giornate lun–ven, lista UUID dei predecessori, nota e identificazione canonica
della lavorazione. Nessuna checklist duplicata.
Lo schema ge360.centro-cantiere.v1 dei backup vecchi continua a essere accettato;
lo schema v2 include taskPlans. Nel merge, piani con medesimo task UUID e contenuto
divergente vengono bloccati anziché sovrascritti.
Data Hub continua esplicitamente a ricevere Centro Cantiere v1, senza taskPlans;
la nuova struttura non ha ancora un contratto di sincronizzazione server.
