# GE360 — Marketing e Jarvis / servizi remoti

## Principio

L'APK GE360 resta operativa offline per Clienti, Cantieri, Preventivi, Spese, Scanner, Attrezzi, Galleria, Messaggi, Fisco e Backup.

Marketing e Jarvis sono **client di servizi esterni**. Nell'APK non vengono incorporati Node.js, PostgreSQL, Redis, Ollama, n8n, container Docker o credenziali Meta/Google/WordPress.

## core:bridge

`core:bridge` fornisce:

- URL separato per Marketing e Jarvis;
- header protocollo GE360 v2;
- request id univoco;
- GET/POST JSON con timeout;
- token Bearer opzionale;
- cifratura del token tramite Android Keystore AES/GCM;
- blocco applicativo di HTTP non cifrato finché l'utente non lo abilita esplicitamente.

I token non vengono esportati nei backup GE360 e non vengono committati.

## Data Hub Android → Linux

L'App Unica usa la stessa configurazione backend impostata nella sezione Jarvis per pubblicare gli snapshot sul `GE360-Backend`.

Il coordinatore è `app/src/main/java/it/edilmilan/ge360/sync/DataHubSyncCoordinator.kt` e viene richiamato dalla `MainActivity` quando la Home torna in primo piano. Il lavoro di rete avviene su un thread separato e non blocca l'interfaccia.

Endpoint usati:

- `GET /api/v1/sync/state`
- `POST /api/v1/sync/push`

Moduli pubblicati:

- `clienti`;
- `cantieri`;
- `centrocantiere`;
- `jobsite_archive`;
- `giornata`;
- `messaggi`;
- `spese`;
- `scanner`;
- `fir`;
- `preventivi`;
- `attrezzi`;
- `galleria`.

Gli snapshot partono dagli stessi `exportJson()` usati dal backup. Il campo top-level `exportedAt` viene rimosso prima dell'invio perché cambia a ogni serializzazione e non deve generare una nuova revisione quando i dati sono invariati.

Per `attrezzi`, il coordinatore aggiunge anche `vanPreparations`, proveniente da `VanPreparationStore`, con i campi reali usati dalla Dashboard Android e dal Today Hub Linux: `jobsiteId`, `jobsiteCode`, `jobsiteTitle`, `date`, `note`, `toolCount`, `materialCount`, `missingCount`, `completed` e `updatedAt`.

### Revisioni e conflitti

Alla prima sincronizzazione del dispositivo viene adottata come `baseRevision` la revisione corrente del server e il telefono pubblica il proprio snapshot.

Dopo il primo sync, l'app conserva per ogni modulo l'ultima `serverRevision` confermata. Se il server risulta avanzato rispetto alla revisione conosciuta, quel modulo non viene sovrascritto automaticamente: viene segnalato come conflitto.

Questa prima implementazione è volutamente **push-first**: rende i dati Android disponibili a Linux, Today Hub e Jarvis senza introdurre merge distruttivi. Il pull bidirezionale con merge/import sicuro rimane una fase successiva.

Lo stato dell'ultimo sync e l'ultimo timestamp riuscito vengono conservati localmente in `ge360_datahub_sync`; nessun token viene duplicato in queste preferenze.

## Marketing

Sorgente server: `Ge360-Marketing`.

L'app usa i contratti esistenti del backend:

- `/health`
- `/dashboard/bootstrap`
- `/actions`
- `/actions/:id/approve`
- `/ai/recommend`
- dashboard web `/dashboard/`

La vista nativa mostra dati reali o l'ultima cache reale: nessun valore demo viene presentato come dato operativo.

Dal bootstrap vengono mostrati KPI, lead, ricavi attribuiti, CPL/ROAS quando presenti, stato SEO, tracking, connettori e azioni in attesa.

Le approvazioni sono sempre manuali. Un'azione proposta deve essere selezionata e confermata prima della chiamata `approve`.

Le credenziali Meta, Google Ads, Google Business Profile, WordPress, Search Console ecc. restano server-side.

## Jarvis

Sorgente server: `GE360-Jarvis` / `GE360-Backend`.

Endpoint usati:

- `GET /health`
- `GET /api/v1/capabilities`
- `POST /api/jarvis/text`

Flusso obbligatorio nell'app:

`testo → dryRun=true → risposta/intento/azione proposta → conferma utente → dryRun=false`

L'app non invia `dryRun=false` in automatico e non accoda comandi esecutivi per inviarli più tardi.

### Contesto locale opzionale

L'utente può scegliere di includere un contesto GE360 sintetico. Il contesto contiene solo aggregati:

- numero clienti;
- numero cantieri e cantieri in corso;
- numero preventivi;
- entrate/uscite/saldo Azienda dell'anno.

Non vengono aggiunti automaticamente nomi, telefoni, email, indirizzi, OCR, documenti o fotografie.

## HTTP locale

Il manifest Android consente tecnicamente cleartext per supportare server LAN legacy, ma `core:bridge` rifiuta ogni URL `http://` finché l'utente non abilita esplicitamente **Consenti HTTP locale non cifrato** per quel servizio.

Per uso stabile è preferibile HTTPS, reverse proxy HTTPS o un percorso privato protetto (per esempio rete VPN/Tailscale con configurazione adeguata).

## Backup

Il backup GE360 non contiene token Bridge né credenziali provider. Le connessioni possono essere riconfigurate sul nuovo dispositivo dopo un restore.

## Test ancora necessari

Prima della prima release installabile:

- compilazione Android completa;
- test HTTPS;
- test HTTP LAN con consenso esplicito;
- test timeout/server offline;
- test Data Hub su backend vuoto;
- test secondo sync invariato: nessun incremento revisione;
- test modifica Attrezzi/Giornata e verifica su `GET /api/v1/today`;
- test conflitto simulando una revisione server avanzata;
- test Marketing `/health` e `/dashboard/bootstrap` reali;
- test approvazione Marketing su azione non distruttiva;
- test Jarvis `dryRun=true`;
- test conferma `dryRun=false` su azione sicura;
- verifica che nessun token compaia in log, backup o export.
