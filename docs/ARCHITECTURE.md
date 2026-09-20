# GE360 App Unica - Architettura

## Obiettivo

GE360 e una sola applicazione Android composta da feature indipendenti. L'APK e unico, ma il codice non deve diventare un monolite.

## Struttura iniziale

```text
App-unica-/
├── app/                 shell Android e navigazione globale
├── core/
│   └── common/          contratti e modelli condivisi
├── feature/
│   └── home/            dashboard GE360
└── docs/
```

I moduli vengono aggiunti soltanto quando inizia la loro migrazione reale.

## Struttura obiettivo

```text
app
core:common
core:data
core:database
core:backup
core:files
core:bridge
core:security
feature:home
feature:clienti
feature:cantieri
feature:preventivi
feature:spese
feature:scanner
feature:attrezzi
feature:galleria
feature:messaggi
feature:fisco
feature:marketing
feature:jarvis
feature:settings
```

## Regola dei dati

Il telefono resta la fonte autorevole per le operazioni che devono funzionare offline. La rete aggiunge sincronizzazione e servizi, non rende l'app inutilizzabile quando manca.

```text
GE360 Android
   |
   +--> database locale
   +--> file locali
   +--> backup locale
   +--> Google Drive via Android SAF
   |
   +--> GE360 Bridge / Jarvis (opzionale)
           |
           +--> Data Hub / PostgreSQL
           +--> n8n
           +--> servizi IA
           +--> Marketing
```

## Relazioni principali

### Cliente

Un cliente puo avere piu immobili, opportunita, preventivi e cantieri.

### Cantiere

Il cantiere e il nodo operativo principale. Collega:

- cliente;
- indirizzo/immobile;
- preventivo accettato;
- spese;
- foto;
- documenti;
- attrezzi e materiali;
- note;
- messaggi;
- stato avanzamento.

### Documento scannerizzato

Un documento puo generare o aggiornare una spesa, ma l'utente conferma i dati estratti prima della contabilizzazione.

## Servizi che restano esterni all'APK

### Jarvis

Resta server-side. L'app usa API private e continua a funzionare se Jarvis non e raggiungibile.

### GE360 Marketing

Credenziali Meta/Google/WordPress e logica server restano fuori dal telefono. L'app espone dashboard e comandi autorizzati.

### n8n

E un motore di automazione opzionale, non una dipendenza del flusso quotidiano.

## Sicurezza

- nessun keystore nel repository;
- nessuna API key nel codice;
- nessuna password nei backup standard;
- token sensibili salvati con storage Android protetto;
- FileProvider/URI grant per lo scambio file;
- API locali tra componenti protette quando necessario;
- operazioni distruttive o con effetti esterni richiedono conferma.

## Firma Android

L'app unica dovra usare una sola identita Android e una sola chiave di firma canonica per tutte le release. L'`applicationId` attuale e `it.edilmilan.ge360`. Va considerato definitivo solo al momento della prima release firmata distribuita.

## Compatibilita migrazione

Le vecchie app non vengono modificate durante la migrazione. Ogni feature deve avere un percorso di importazione dei dati prima che la vecchia app possa essere considerata sostituita.
