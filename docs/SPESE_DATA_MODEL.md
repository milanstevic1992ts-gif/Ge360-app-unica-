# GE360 — Modello dati Spese

## Obiettivo

`feature:spese` gestisce movimenti economici locali e offline-first. Ogni movimento possiede un UUID GE360 indipendente dall'ID SQLite locale.

## FinanceRecord

Campi principali:

- `id`: ID SQLite locale;
- `ge360Id`: UUID stabile GE360 del movimento;
- `type`: `INCOME` oppure `EXPENSE`;
- `amount`: importo;
- `category`: categoria;
- `note`: nota libera;
- `date`: data ISO `YYYY-MM-DD`;
- `scope`: `BUSINESS` oppure `PRIVATE`;
- `jobsiteId`: UUID GE360 del cantiere, opzionale;
- `jobsiteCode`: copia leggibile del codice `CANT-YYYY-NNN`;
- `jobsiteTitle`: copia leggibile del nome/indirizzo del cantiere;
- `fireflyId`: ID del gruppo transazione creato in Firefly III;
- `fireflyStatus`: `PENDING`, `SYNCED` oppure `ERROR`;
- `fireflyLastError`: ultimo errore di sincronizzazione, se presente;
- `fireflySyncedAt`: timestamp dell'ultima sincronizzazione riuscita;
- `createdAt`, `updatedAt`: timestamp locali.

## Regola jobsiteId

Un movimento `PRIVATE` non mantiene collegamenti a cantieri.

Un movimento `BUSINESS` può essere:

1. generale aziendale, senza `jobsiteId`;
2. collegato a uno specifico cantiere tramite `jobsiteId`.

`jobsiteCode` e `jobsiteTitle` sono snapshot leggibili per la UI e per gli export, ma la chiave vera resta sempre `jobsiteId`.

## Riepilogo cantiere

Per ogni `jobsiteId` il modulo calcola:

- somma entrate collegate;
- somma uscite collegate;
- saldo movimenti = entrate - uscite;
- numero di registrazioni.

Questo saldo non va confuso con il margine economico definitivo del cantiere finché Preventivi, ore lavoro, eventuali costi indiretti e altri dati non saranno integrati.

## Compatibilità backup

### Nuovo formato

Schema: `ge360.spese.v3`.

Oltre a UUID GE360 e riferimenti cantiere, conserva lo stato Firefly. I backup `ge360.spese.v2` restano importabili: i movimenti senza metadati Firefly vengono marcati `PENDING`.

Mantiene UUID GE360 e riferimenti cantiere.

### Vecchio formato

Schema: `spese360.v1` dalla repo `gestione-spese`.

L'importer accetta i vecchi campi:

- `type`;
- `amount`;
- `category`;
- `note`;
- `date`;
- `scope`.

I movimenti legacy ricevono un nuovo UUID GE360. In modalità merge, se un movimento equivalente esiste già, vengono preservati UUID e collegamento al cantiere già presenti nell'App Unica.

## Futuri collegamenti

- Scanner potrà creare una spesa con `jobsiteId` dopo OCR e conferma;
- Fisco leggerà i movimenti aziendali confermati;
- Preventivi e Cantieri potranno confrontare valore previsto e movimenti reali;
- Jarvis potrà analizzare costi e anomalie senza diventare il database primario.


## Sincronizzazione Firefly III

Il salvataggio resta **offline-first**:

1. il movimento viene scritto subito nel database locale Android;
2. viene marcato `PENDING`;
3. l'app usa la configurazione sicura già esistente del GE360 Backend;
4. invia il movimento al backend con `POST /api/v1/integrations/firefly/transactions`;
5. il backend conserva il token Firefly e inoltra la richiesta a Firefly III;
6. alla prima risposta positiva viene salvato `fireflyId` e lo stato diventa `SYNCED`;
7. una modifica successiva usa `PUT` sullo stesso `fireflyId`, evitando di creare un secondo movimento.

Se la rete o Firefly non sono disponibili il movimento resta salvato localmente e passa a `ERROR`. Dalla schermata **Dati Spese** è disponibile **Riprova sincronizzazione Firefly**.

La chiave API Firefly non viene mai salvata nell'APK.

### Mapping

- `INCOME` → Firefly `deposit`;
- `EXPENSE` → Firefly `withdrawal`;
- importo → centesimi interi nell'API GE360, convertiti dal backend nel formato Firefly;
- categoria → `category_name`;
- cantiere → tag `CANTIERE: <codice · titolo>`;
- ambito → tag `AMBITO: Azienda` / `AMBITO: Privato`;
- `ge360Id` → riferimento esterno stabile in Firefly.

La cancellazione remota non viene ancora eseguita automaticamente: una cancellazione locale non deve fingere di essere sincronizzata finché non esiste una coda tombstone/offline affidabile.
