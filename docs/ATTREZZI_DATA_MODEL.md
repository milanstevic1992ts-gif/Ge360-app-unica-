# GE360 — Modello Attrezzi & Materiali

## Obiettivo

`feature:attrezzi` organizza attrezzi, materiali, richieste e task squadra usando il `jobsiteId` del Cantiere GE360.

## Hub operativo

L'ingresso del modulo è `ToolsHubActivity` con due modalità:

1. **PREPARA FURGONE** — interfaccia rapida da usare prima o durante il cantiere;
2. **Gestione completa** — catalogo, quantità, richieste, task e modifica dettagli.

Entrambe usano lo stesso `ToolsStore`: non esistono copie parallele dei dati.

## Modalità PREPARA FURGONE

Per un Cantiere selezionato mostra le dotazioni ordinate per urgenza operativa.

Azioni rapide grandi:

- `CARICATO` → `In furgone`;
- `MANCA` → `Mancante`;
- `COMPRA` → `Da comprare`;
- `CANTIERE` → `In cantiere`;
- `RIENTRO` → `Rientrato`.

Le voci `Mancante` e `Da comprare` vengono mostrate prima, seguite da `Da portare`, `Comprato`, `In furgone`, `In cantiere`, `Consegnato`, `Rientrato`.

L'obiettivo è permettere l'aggiornamento con un solo tap senza aprire editor o menu profondi.

## Relazione principale

`Cliente → Cantiere(jobsiteId) → Attrezzi/Materiali/Richieste/Task`

Codice/nome cantiere sono snapshot; la relazione stabile usa `jobsiteId`.

## Entità

### CatalogResource

- UUID GE360;
- `TOOL` / `MATERIAL`;
- nome, categoria, unità, nota;
- attivo/nascosto.

### JobResource

- UUID GE360;
- `jobsiteId`;
- riferimento opzionale al catalogo;
- tipo, nome, quantità, unità;
- stato operativo;
- nota.

Stati:

`Da portare → In furgone → In cantiere → Rientrato`

Materiali:

`Mancante → Da comprare → Comprato → Consegnato`

### ResourceRequest

Richiesta materiale/attrezzo con cantiere, quantità, urgenza, richiedente, nota e stato.

### TeamTask

Task squadra con tipo, titolo, descrizione, assegnatario, data, priorità e stato.

## Offline-first

Catalogo e preparazione sono SQLite locali. La modalità Furgone funziona completamente senza rete.

## Backup

Formato: `ge360.attrezzi.v1`.

Il backup contiene catalogo, dotazioni cantieri, richieste e task; la modalità Furgone non introduce nuove tabelle perché aggiorna gli stessi `JobResource`.

Il formato PWA legacy `EDIL_ATTREZZI_BACKUP` resta da verificare con un backup reale prima di una conversione automatica.

## Scontrini garanzia

L'hub Attrezzi espone una sezione dedicata agli scontrini degli attrezzi. Il flusso:

1. acquisisce lo scontrino con ML Kit Document Scanner;
2. mantiene il PDF multipagina;
3. esegue OCR locale per proporre data, negozio e totale;
4. richiede sempre la conferma di **nome attrezzo**, **tipo attrezzo** e **data d'acquisto**;
5. salva il record canonico nello stesso `DocumentStore` dello Scanner.

Tipo documento canonico:

`SCONTRINO_GARANZIA`

Campi specifici:

- `toolName`;
- `toolType`;
- `documentDate` come data d'acquisto;
- `supplier` e `totalAmount`, opzionali;
- `pdfPath`, OCR e UUID stabile del documento.

Archivio fisico:

`<externalFilesDir/Documents>/GE360/Scontrini garanzia/<ANNO>/`

Fallback:

`<filesDir>/GE360/Scontrini garanzia/<ANNO>/`

Il nome PDF contiene data, nome attrezzo e prefisso dell'UUID. Nella radice vengono
rigenerati anche un indice JSON e uno TXT, così tipo e data non restano esclusivamente
nel database. Il PDF non viene duplicato: Attrezzi e Scanner leggono lo stesso record.
Il backup automatico include metadati e PDF e il restore ricostruisce la cartella dedicata.

## Evoluzione prevista

- lista aggregata multi-cantiere per magazzino/furgone;
- QR/barcode opzionale sugli attrezzi;
- collegamento opzionale dello scontrino garanzia a una voce Spese;
- link collaboratore limitato al singolo cantiere;
- sync richieste squadra via Bridge.
