# GE360 — Giornata di cantiere

## Obiettivo

`feature:giornata` è la schermata operativa da usare durante il lavoro sul campo. Registra tempo reale, costo interno della manodopera, rapportino, problemi e materiale necessario usando sempre il `jobsiteId` UUID GE360 del Cantiere.

## WorkSession

Ogni sessione contiene:

- `ge360Id` UUID stabile;
- `jobsiteId` UUID del Cantiere;
- `jobsiteCode` / `jobsiteTitle` snapshot;
- `workDate` ISO `YYYY-MM-DD`;
- `collaborator`;
- `hourlyRate` **snapshot della tariffa al momento di INIZIA LAVORO**;
- `startedAt` / `endedAt` epoch millis;
- nota e timestamp.

La durata è calcolata da inizio/fine. Una sessione con `endedAt=0` resta attiva anche chiudendo la schermata e viene recuperata al riavvio.

È ammessa una sola sessione attiva alla volta per ridurre errori di attribuzione.

## Tariffe per collaboratore

Le tariffe correnti vengono salvate separatamente per nome collaboratore.

Esempio:

- Milan → 25 €/h;
- Dragan → 22 €/h;
- Bojan → 28 €/h.

Regole:

1. ogni collaboratore parte da `0 €/h`;
2. `0` significa tariffa non configurata;
3. all'avvio di una sessione, GE360 copia la tariffa corrente del collaboratore dentro `WorkSession.hourlyRate`;
4. il costo della sessione è `durata × hourlyRate snapshot`;
5. modificare successivamente la tariffa corrente non cambia le sessioni storiche;
6. le ore con snapshot `0` vengono mostrate come **ore senza tariffa** e non vengono valorizzate nel costo;
7. Preventivi segnala esplicitamente queste ore per evitare un margine apparentemente completo ma sottostimato nei costi.

Questa scelta evita che una variazione futura di tariffa riscriva economicamente il passato.

## Migrazione database v1 → v2

`ge360_giornata.db` passa da versione 1 a versione 2 aggiungendo:

`work_sessions.hourly_rate REAL NOT NULL DEFAULT 0`

Se esiste il vecchio costo globale della v0.14:

- viene migrato come tariffa corrente di Milan;
- le sessioni già presenti ricevono quel valore come snapshot, coerentemente con il vecchio modello globale.

Se il vecchio costo era 0, le sessioni storiche restano con tariffa 0 e vengono segnalate come non valorizzate.

## LaborBreakdown

Per ogni Cantiere GE360 raggruppa le sessioni per collaboratore e calcola:

- minuti totali;
- minuti con tariffa;
- minuti senza tariffa;
- costo totale da sessioni;
- tariffa corrente del collaboratore;
- tariffe storiche effettivamente presenti nelle sessioni.

Una stessa persona può quindi avere sessioni vecchie a 22 €/h e nuove a 25 €/h senza perdita di informazione.

## DailyReport

Un rapportino è unico per coppia `jobsiteId + workDate` e contiene:

- UUID stabile;
- data/cantiere;
- `doneToday`;
- `nextSteps`;
- `issues`;
- `materialsNeeded`;
- `notes`;
- `closed`;
- timestamp.

## Flusso UI

1. selezione Cantiere;
2. inserimento/selezione collaboratore;
3. richiamo eventuale da `Tariffe collaboratori`;
4. impostazione tariffa interna opzionale;
5. `INIZIA LAVORO`;
6. snapshot tariffa dentro la sessione;
7. timer persistente;
8. `FINE LAVORO`;
9. rapportino rapido;
10. salvataggio / condivisione Android.

Mentre il timer è attivo, Cantiere, collaboratore e tariffa vengono bloccati per evitare incoerenze nella sessione in corso.

## Integrazione economica

Preventivi legge:

- valore preventivo;
- Spese del Cantiere;
- sessioni Giornata;
- ore per collaboratore;
- costo storico per collaboratore;
- ore senza tariffa;
- manodopera totale valorizzata.

Formula mostrata:

`margine operativo stimato = preventivo - Spese - manodopera valorizzata da timer`

Se esistono ore senza tariffa, il margine viene accompagnato da un avviso perché il costo conosciuto è incompleto.

Non viene chiamato utile netto: imposte, costi indiretti, ammortamenti e altri costi restano fuori.

Se un collaboratore è già contabilizzato in Spese, va evitato il doppio conteggio dello stesso costo tramite le ore.

## Backup

Formato corrente: `GE360 / giornata / formatVersion 5`.

Il file `modules/giornata.json` contiene:

- `sessions`, ciascuna con `hourlyRate` snapshot;
- `reports`;
- `laborRates` con le tariffe correnti per collaboratore;
- `hourlyCost` di Milan mantenuto come campo di compatibilità con la v0.14.

### Compatibilità

- backup v3: ripristina tariffe per collaboratore e snapshot delle sessioni;
- backup v2: il vecchio `hourlyCost` viene migrato su Milan e usato come tariffa delle vecchie sessioni che non avevano ancora `hourlyRate`;
- backup precedenti all'introduzione di Giornata: il modulo viene saltato con avviso dal restore unico.

## Vincoli

La presenza nel codice non equivale a validazione su dispositivo. Prima della release vanno verificati:

- compilazione;
- migrazione SQLite v1 → v2;
- timer dopo kill processo;
- cambio data con sessione aperta;
- più collaboratori con tariffe differenti;
- modifica tariffa senza alterazione dei costi storici;
- backup/restore v2 → v3;
- ore senza tariffa e relativo avviso nel margine.
