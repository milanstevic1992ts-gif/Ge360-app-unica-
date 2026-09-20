# GE360 Scanner — modello dati

## Obiettivo

Scanner non e piu una app separata: e un modulo interno a GE360 che collega documento, cantiere e movimento finanziario.

## Entita `ScannerDocument`

Campi principali:

- `ge360Id`: UUID stabile del documento;
- `type`: FATTURA / DDT / SCONTRINO / RICEVUTA / SCONTRINO_GARANZIA / ALTRO;
- `documentDate`, `documentNumber`;
- `supplier`, `category`;
- `taxableAmount`, `vatAmount`, `totalAmount`;
- `confidence`;
- `ocrText`;
- `pdfPath`: percorso del PDF nell'archivio privato GE360;
- `jobsiteId`, `jobsiteCode`, `jobsiteTitle`;
- `expenseGe360Id`: UUID del movimento Spese generato, se presente;
- `status`, `createdAt`, `updatedAt`.
- `toolName`, `toolType`: campi specifici dello scontrino garanzia Attrezzi.

## Flusso

```text
ML Kit Document Scanner
        |
        v
PDF + immagini pagina
        |
        +--> copia PDF in filesDir/scanner/YYYY/MM
        |
        v
ML Kit Text Recognition
        |
        v
DocumentParser
        |
        v
Revisione manuale
        |
        +--> associa jobsiteId
        |
        +--> salva documento
        |
        `--> opzionale: crea FinanceRecord EXPENSE
```

## Regola finanziaria

Lo Scanner non deve creare automaticamente una spesa senza conferma dell'utente. Un DDT, un documento con OCR incerto o un totale interpretato male non deve alterare la contabilita operativa.

Se l'utente seleziona "Crea anche una voce Uscita in Spese", il modulo crea un `FinanceRecord` BUSINESS e copia:

- totale;
- categoria;
- data;
- fornitore/tipo/numero documento nella nota;
- `jobsiteId`, codice e titolo del cantiere.

Il documento salva poi `expenseGe360Id` per mantenere il collegamento.

## Archiviazione

I PDF ordinari vengono copiati nell'area privata Android dell'app. Gli scontrini garanzia
degli attrezzi vengono conservati nella cartella dedicata
`GE360/Scontrini garanzia/<ANNO>/`, con indice JSON e TXT.

L'export JSON del modulo contiene i metadati. Il backup GE360 completo incorpora anche i
byte dei PDF sotto `files/scanner/`, verifica i checksum SHA-256 e può essere replicato
nella cartella persistente del telefono e su Google Drive.

## Sicurezza

- nessun OCR remoto necessario;
- nessun invio automatico a n8n/Jarvis/provider esterni;
- apertura PDF tramite FileProvider non esportato;
- niente credenziali nel modulo Scanner.
