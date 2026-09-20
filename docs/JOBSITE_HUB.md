# GE360 · Hub e Cartella Cantiere

Ogni cantiere usa `jobsiteId` come chiave unica e si apre nel nuovo `JobsiteHubActivity`.

## Hub Cantiere

Il nuovo hub raccoglie gli accessi a:

- lavori / avanzamento (Centro Cantiere esistente)
- ore / giornata
- preventivi
- spese
- documenti / FIR
- foto
- attrezzi / materiali
- planimetrie e documenti cliente
- appunti cliente
- appunti interni

L'azione `it.edilmilan.ge360.action.OPEN_JOBSITE` ora apre `JobsiteHubActivity`; il precedente `JobsiteCenterActivity` resta disponibile come schermata lavori/avanzamento.

## Cartella cantiere

`JobsiteArchiveActivity` gestisce due famiglie di PDF:

- `PLANIMETRIA`: caricamento PDF o scansione tramite ML Kit Document Scanner;
- `DOCUMENTO_CLIENTE`: documenti consegnati dal cliente, caricati o scansionati.

I PDF sono copiati nell'area privata dell'app sotto `files/scanner/jobsite_archive/<jobsiteId>/` e possono essere aperti tramite il FileProvider già usato dal modulo Scanner.

## Appunti

`JobsiteArchiveStore` mantiene due insiemi separati:

- `CLIENTE`: richieste, scelte, misure e decisioni concordate;
- `INTERNO`: note riservate all'impresa.

Gli appunti interni sono separati a livello dati, non solo graficamente. Non devono essere inclusi automaticamente in preventivi, PDF, WhatsApp o altri contenuti destinati al cliente.

Ogni appunto supporta priorità (`Normale`, `Importante`, `Urgente`), pin, autore opzionale e timestamp.

## Database

La nuova funzione usa `ge360_jobsite_archive.db` v1, separato dai database esistenti per evitare migrazioni distruttive sui dati già presenti.
