# GE360 — Modello dati Galleria Cantieri

## Principio

La Galleria è **offline-first**.

- Telefono/cartella scelta con Android SAF = archivio originale autorevole.
- Google Drive scelto tramite SAF = copia secondaria.
- Backend, NAS, n8n e IA non sono necessari per scattare, importare o consultare le foto.

## Collegamento centrale

Ogni foto conserva:

- `ge360Id`: UUID della foto;
- `jobsiteId`: UUID GE360 del cantiere;
- `jobsiteCode`: codice leggibile `CANT-YYYY-NNN`;
- `jobsiteTitle`: descrizione visuale del cantiere.

`jobsiteId` è la relazione stabile. Codice e titolo sono copie descrittive utili per cartelle, ricerca e recupero manuale.

## Fasi

- `PRIMA`
- `DURANTE`
- `DOPO`
- `PROBLEMI`
- `DOCUMENTI`

La struttura creata nella cartella scelta è:

```text
GALLERIA CANTIERI/
└── CANT-2026-001_Nome-Cantiere/
    ├── PRIMA/
    ├── DURANTE/
    ├── DOPO/
    ├── PROBLEMI/
    └── DOCUMENTI/
```

## Metadati foto

SQLite `ge360_galleria.db` conserva:

- URI locale SAF;
- nome file e MIME type;
- dimensione;
- SHA-256 per rilevare duplicati;
- fase;
- nota e tag;
- preferita sì/no;
- URI copia Drive;
- stato sincronizzazione Drive;
- date creazione/aggiornamento.

Le immagini non vengono salvate come BLOB nel database.

## Flusso acquisizione

```text
Fotocamera / Galleria Android
        ↓
Cantiere GE360 + fase
        ↓
Copia nella cartella SAF locale
        ↓
SHA-256 + metadati SQLite
        ↓
Copia Drive opzionale
```

Lo scatto fotocamera usa soltanto un file temporaneo in cache come destinazione della camera. Dopo la copia nell'archivio SAF il temporaneo viene eliminato.

## Drive

La copia Drive è esplicita e può essere eseguita:

- su una singola foto;
- su tutte le foto ancora in attesa del cantiere selezionato;
- globalmente quando nessun cantiere è selezionato.

Un errore Drive non modifica né elimina l'originale locale.

## Backup

`ge360.galleria.v1` esporta **l'indice/metadati**, non i file fotografici. Nel JSON è dichiarato `filesIncluded=false`.

Il backup completo futuro `core:backup` dovrà gestire insieme:

1. database/metadati;
2. riferimenti SAF;
3. copie fotografiche quando si richiede un backup portabile.

Un import dell'indice da un altro telefono può contenere URI non validi sul nuovo dispositivo; per questo l'import metadati non viene considerato un backup completo delle immagini.

## Migrazione EDIL-GALERIA

La vecchia repo resta sorgente di riferimento. Non vengono importati automaticamente i suoi metadati finché non viene provato un archivio reale e verificata la corrispondenza tra vecchi cantieri e `jobsiteId` GE360.
