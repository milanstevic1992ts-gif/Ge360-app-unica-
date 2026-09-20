# GE360 — Rilievi / Planimetrie

## Obiettivo

Il modulo `feature:planimetrie` è l'archivio indipendente di sopralluoghi e rilievi di GE360.

Un rilievo **non richiede** l'esistenza preventiva di un cantiere o di un preventivo. Questo consente di misurare un immobile durante il sopralluogo anche quando il lavoro non è ancora acquisito.

Flusso principale:

`Sopralluogo → Rilievo / Planimetria → Preventivo → Cantiere`

Sono validi anche:

- `Rilievo → Cantiere`
- `Rilievo → Preventivo`
- `Rilievo non collegato / archiviato`

La planimetria rimane la sorgente del rilievo. I collegamenti usano gli identificativi GE360 e non richiedono copie indipendenti della planimetria.

## Identità e collegamenti

Ogni `FloorPlanRecord` possiede un `ge360Id` stabile.

Campi di collegamento:

- `jobsiteId`: `ge360Id` del cantiere collegato, se presente;
- `estimateId`: `ge360Id` del preventivo generato/collegato, se presente.

Il rilievo può esistere con entrambi i campi vuoti.

## Ambienti

Ogni rilievo contiene zero o più `FloorPlanRoom`.

Dati principali:

- nome ambiente;
- tipo ambiente;
- modalità geometrica;
- lunghezza;
- larghezza;
- altezza;
- quantità manuali/derivate;
- posizione e ingombro nel canvas 2D;
- ordinamento.

Tipi UI:

- Bagno
- Cucina
- Stanza
- Camera
- Corridoio
- Balcone/Terrazzo
- Altro

## Modalità geometriche

### Rettangolare

Usa `lunghezza × larghezza × altezza`.

Calcoli:

- pavimento = `L × W`
- soffitto = pavimento
- perimetro = `2 × (L + W)`
- pareti = `perimetro × H`

### Forma a L

Usa un rettangolo esterno con un rientro rettangolare:

- `length`, `width`: ingombro esterno;
- `cutWidth`, `cutHeight`: rientro.

Pavimento:

`L × W - cutWidth × cutHeight`

La forma viene rappresentata realmente a L nel canvas.

### Irregolare / misure manuali

Consente di inserire direttamente:

- pavimento m²;
- soffitto m²;
- pareti lorde m²;
- perimetro ml.

`L × W` può essere usato solamente come ingombro grafico.

La forma manuale viene mostrata con un rettangolo tratteggiato e la dicitura `schema manuale`. Le quantità rimangono quelle reali inserite dall'utente.

### Pareti quotate

È la modalità per il rilievo reale stanza per stanza.

Ogni ambiente può contenere una sequenza ordinata di `FloorPlanWall`.

Ogni parete salva:

- `ge360Id`;
- `floorPlanId`;
- `roomId`;
- lunghezza in metri;
- direzione;
- nota facoltativa;
- ordine;
- `updatedAt`.

Direzioni ammesse:

- `0°` — Destra
- `90°` — Giù
- `180°` — Sinistra
- `270°` — Su

Le direzioni sono volutamente ortogonali: lo snap a 90° è parte del modello dati e non solo dell'interfaccia. Non vengono quindi generate diagonali accidentali.

Flusso operativo:

1. aprire un ambiente;
2. scegliere `Pareti quotate`;
3. premere `+ Parete`;
4. inserire lunghezza;
5. scegliere Destra / Giù / Sinistra / Su;
6. ripetere;
7. premere `Chiudi stanza`.

Ogni nuova parete parte dalla fine della precedente.

`Chiudi stanza` calcola la distanza dal punto finale all'origine e genera automaticamente una o due pareti ortogonali di chiusura.

Se il profilo è chiuso:

- area pavimento = formula di Gauss / shoelace sul poligono;
- soffitto = area pavimento;
- perimetro = somma delle pareti;
- pareti lorde = `perimetro × altezza`.

Se il profilo è aperto:

- l'area pavimento resta `0`;
- il perimetro rappresenta solamente la somma delle pareti già rilevate;
- GE360 non consente di generare il preventivo finché l'ambiente a pareti quotate non è chiuso.

L'ingombro grafico viene ricavato dal bounding box reale del profilo.

## Porte e finestre

Regola operativa GE360:

**porte e finestre non vengono sottratte dai m² delle pareti.**

Le superfici pareti restano quindi lorde, coerentemente con il metodo di lavoro scelto.

Porte e finestre sono ora oggetti strutturati collegati ad una specifica parete quotata tramite `FloorPlanOpening`.

Dati disponibili:

- tipo: `Porta` / `Finestra`;
- distanza dall'inizio parete (`offset`);
- larghezza;
- altezza;
- quota davanzale per le finestre;
- cerniera/anta sinistra o destra per le porte;
- nota facoltativa;
- ID stabile dell'apertura.

Vincoli:

- l'apertura deve stare interamente dentro la lunghezza della parete;
- due aperture sulla stessa parete non possono sovrapporsi;
- se una parete viene accorciata e una sua apertura uscirebbe dal segmento, GE360 blocca la modifica;
- eliminando la parete vengono eliminate logicamente anche le sue aperture;
- sostituendo tutto il profilo con `Da rettangolo` le aperture delle vecchie pareti non vengono mantenute.

### Persistenza aperture

Per non introdurre una migrazione SQLite solo per questo dato, le aperture vengono serializzate nei metadati della parete sotto il marker:

`[GE360_OPENINGS]`

La nota libera dell'utente resta separata dal metadato tramite `FloorPlanWallOpenings.userNote(...)`.

Questo permette alle aperture di seguire automaticamente:

- la parete;
- il backup del rilievo;
- la cartella fisica del cantiere quando il rilievo è collegato;
- restore/merge già esistenti.

Lo schema backup resta quindi `ge360.planimetrie.v1`.

### Rendering aperture

`FloorPlanOpeningRenderer` viene usato sia dalla preview della stanza sia dal canvas generale.

Rappresentazione:

- il segmento del muro viene interrotto graficamente nel punto dell'apertura;
- finestra = doppia linea nel vano;
- porta = vano + indicazione schematica dell'anta/cerniera;
- quando la scala lo permette viene mostrata anche `P` o `F`.

Il rendering è esclusivamente grafico e non modifica i calcoli di area/perimetro/pareti.

## Canvas 2D

`FloorPlanCanvasView`:

- mostra griglia metrica;
- disegna rettangoli e forme a L;
- disegna il profilo reale degli ambienti a pareti quotate;
- mostra porte e finestre sulle pareti quotate;
- mostra le lunghezze delle pareti quando la scala lo permette;
- mostra nome ambiente e m² pavimento;
- distingue le forme manuali con tratteggio;
- permette di trascinare gli ambienti;
- applica lo snap tra ambienti vicini;
- salva le coordinate del rilievo.

`WallProfileView` è la preview dedicata dell'editor pareti e mostra:

- profilo aperto/chiuso;
- punti del percorso;
- quote di ogni segmento;
- porte/finestre;
- riempimento quando la stanza è chiusa.

Il canvas resta volutamente un editor da cantiere semplice, non un CAD generale.

## Snap tra ambienti / muri adiacenti

Quando un ambiente viene trascinato e rilasciato, GE360 cerca un aggancio entro circa `0,20 m`.

Ordine logico:

1. per ambienti a pareti quotate vengono confrontati i segmenti muro reali;
2. orizzontali vengono confrontati solo con orizzontali;
3. verticali vengono confrontati solo con verticali;
4. è richiesta una sovrapposizione utile di almeno circa `0,15 m`;
5. viene scelta la correzione più piccola;
6. per ambienti senza profilo quotato viene usato come fallback l'ingombro rettangolare.

Lo snap modifica solamente `drawX/drawY`: non cambia lunghezze, aree o quantità rilevate.

Questo è già sufficiente per accostare correttamente stanze e corridoi. Non è ancora un sistema semantico di “muro condiviso unico”: due ambienti mantengono i propri segmenti indipendenti anche quando risultano allineati.

## Editor pareti quotate

Schermata: `FloorPlanWallEditorActivity`.

Azioni disponibili:

- `+ Parete`;
- modifica di lunghezza/direzione/nota;
- `Porte / finestre` per ogni parete;
- eliminazione di una parete;
- `Chiudi stanza`;
- `Da rettangolo`, che genera quattro pareti dalle misure L×W esistenti;
- `Svuota`.

La direzione proposta per una nuova parete ruota automaticamente di 90° rispetto alla precedente, ma l'utente può sempre cambiarla.

La chiusura automatica non sostituisce il controllo visivo: profili degeneri con area nulla vengono segnalati.

## Editor porte / finestre

Schermata: `FloorPlanOpeningEditorActivity`.

Per ogni parete permette:

- `+ Porta`;
- `+ Finestra`;
- modifica/eliminazione delle aperture;
- preview immediata del segmento;
- controllo della posizione e della sovrapposizione.

La distanza dell'apertura è sempre misurata dall'inizio della parete secondo l'ordine/direzione del rilievo.

## Rilievo → Cantiere

Azioni disponibili:

1. collegare il rilievo a un cantiere esistente;
2. creare un nuovo cantiere dal rilievo.

Il nuovo cantiere nasce nello stato `Sopralluogo` e riceve:

- cliente, se inserito;
- titolo rilievo;
- tipo del rilievo;
- note;
- riferimento al `ge360Id` del rilievo.

Il rilievo conserva il `jobsiteId` del cantiere.

Quando collegato, il rilievo entra anche nella cartella fisica del cantiere tramite:

`DATI/planimetrie.json`

## Rilievo → Preventivo

Il modulo può generare un nuovo preventivo e trasferire gli ambienti.

Per ambienti rettangolari vengono trasferiti:

- nome;
- tipo;
- lunghezza;
- larghezza;
- altezza.

Per forme a L, irregolari e pareti quotate vengono inoltre trasferite in Preventivi le quantità reali:

- pavimento;
- soffitto;
- pareti lorde;
- perimetro.

Le pareti quotate e le aperture rimangono nel rilievo sorgente; Preventivi riceve le quantità derivate necessarie al calcolo delle lavorazioni. Le aperture non vengono sottratte dai m².

Il preventivo nasce in stato `Bozza`; le checklist vengono poi scelte nel normale planner Preventivi.

## Persistenza

Database:

`ge360_rilievi.db`

Versione attuale:

`DB_VERSION = 2`

Tabelle:

- `floor_plans`
- `floor_plan_rooms`
- `floor_plan_walls`

La migrazione `v1 → v2` crea solamente la tabella pareti e i relativi indici. Non elimina o ricrea rilievi/ambienti esistenti.

Le aperture non richiedono una tabella aggiuntiva perché sono metadati strutturati della parete.

## Backup

Schema portabile:

`ge360.planimetrie.v1`

Il numero schema resta `v1` perché `walls` e i metadati delle aperture sono retrocompatibili all'interno dell'ambiente/parete.

Il backup unico GE360 include:

`modules/planimetrie.json`

Sono salvati:

- rilievi;
- ambienti;
- misure;
- modalità geometrica;
- rientri;
- superfici manuali/derivate;
- pareti quotate complete;
- porte e finestre tramite metadati parete;
- coordinate e ingombri del canvas;
- collegamenti a cantiere e preventivo.

Il restore supporta:

- merge per `ge360Id`;
- sostituzione completa;
- backup precedenti alle pareti quotate;
- pareti senza metadati aperture;
- backup GE360 precedenti all'intero modulo Planimetrie.

## Test geometria

`FloorPlanWallGeometryTest` copre almeno:

- rettangolo;
- forma a L;
- area;
- perimetro;
- bounding box;
- chiusura automatica con due segmenti ortogonali.

I test sono presenti nella repository ma la loro esecuzione dipende da una build/test esplicita.

## Limiti attuali / prossimi passi

Non sono ancora implementati:

- pareti a 45° o angoli arbitrari;
- controllo automatico delle auto-intersezioni del poligono;
- muro condiviso come singola entità semantica tra due stanze (oggi c'è snap grafico dei segmenti);
- modifica delle quote trascinando i vertici;
- foto agganciate direttamente al punto della planimetria;
- importazione/scansione di una pianta;
- AR/LiDAR;
- collegamento Bluetooth con metro laser;
- esportazione PDF/DXF/SVG.

Queste funzioni devono evolvere sopra il modello dati esistente senza cambiare il principio fondamentale: **rilievo semplice in cantiere, dati reali modificabili, nessun vincolo rigido e collegamento successivo a Preventivo/Cantiere.**
