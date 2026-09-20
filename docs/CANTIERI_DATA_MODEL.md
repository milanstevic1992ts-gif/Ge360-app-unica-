# GE360 Cantieri — modello centrale

## Scopo

`Cantieri` è il nodo che collega i dati operativi dell'app unica. Un cliente può avere zero, uno o più cantieri.

## Identità

Ogni cantiere possiede due identificatori:

- `ge360Id`: UUID stabile e autorevole per integrazioni, backup, sync e collegamenti tra moduli;
- `code`: codice leggibile generato localmente nel formato `CANT-YYYY-NNN`.

L'ID SQLite locale serve solo al database del modulo e non deve essere usato come chiave di integrazione esterna.

## Campi v1

- cliente locale e UUID cliente;
- nome cliente snapshot;
- titolo cantiere;
- indirizzo;
- tipo lavoro;
- stato;
- data inizio;
- fine prevista;
- note;
- data creazione/aggiornamento.

## Stati

1. Sopralluogo
2. Preventivo
3. Programmato
4. In corso
5. Sospeso
6. Completato
7. Archiviato

## Regola per i futuri moduli

Ogni record collegabile a un lavoro deve poter conservare `jobsiteId` (`ge360Id` del cantiere) e, quando utile per la UI, `jobsiteCode`.

Esempi:

- Preventivo → `jobsiteId`
- Spesa → `jobsiteId`
- Documento scannerizzato → `jobsiteId`
- Foto → `jobsiteId`
- richiesta attrezzo/materiale → `jobsiteId`
- evento Jarvis/n8n → `jobsiteId`

Il nome del cantiere o l'indirizzo non devono essere usati come chiave perché possono cambiare.

## Backup

Il modulo esporta JSON `formatVersion: 1` con UUID e codici. L'import può unire per UUID o sostituire l'archivio Cantieri.

## Evoluzione

La v1 mantiene il database del modulo separato per consentire migrazione graduale. Quando `core:database` sarà introdotto, lo schema Cantieri verrà spostato nel database condiviso mantenendo gli stessi UUID e il formato backup.
