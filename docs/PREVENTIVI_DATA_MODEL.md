# GE360 Preventivi — ambienti, checklist e misure

## Filosofia

Il modulo Preventivi non usa più configuratori rigidi con scelte obbligatorie. La logica operativa è una **checklist libera per ambiente**:

- alcune lavorazioni comuni sono proposte già attive;
- qualsiasi voce può essere attivata o disattivata;
- nessuna combinazione blocca il salvataggio;
- dopo la checklist è sempre possibile modificare una singola voce o aggiungerne una dal Capitolato, dal Prezzario oppure come voce Libera.

Le voci iniziali sono quindi suggerimenti di lavoro, non regole.

## Struttura per ambienti

Il preventivo può contenere più ambienti indipendenti, per esempio:

- Bagno;
- Cucina;
- Stanza 1;
- Stanza 2;
- Camera matrimoniale;
- Soggiorno;
- Balcone/Terrazzo;
- altre zone personalizzate.

Ogni ambiente conserva:

- UUID stabile;
- nome;
- tipo ambiente;
- lunghezza;
- larghezza;
- altezza;
- modalità prezzo;
- eventuale prezzo a corpo;
- checklist delle lavorazioni attive.

I dati ambiente sono salvati localmente in `ge360_preventivi_rooms.db`. Inoltre i metadati essenziali vengono fotografati nelle `internalNote` delle voci generate: questo permette di ricostruire gli ambienti anche dopo duplicazione o importazione del preventivo.

## Misure automatiche

Inserendo lunghezza, larghezza e altezza, GE360 calcola:

- pavimento = lunghezza × larghezza;
- soffitto = lunghezza × larghezza;
- perimetro = 2 × (lunghezza + larghezza);
- pareti = perimetro × altezza;
- pareti + soffitto;
- pareti + pavimento.

Ogni voce della checklist indica quale quantità usa normalmente:

- a corpo;
- pavimento m²;
- soffitto m²;
- pareti m²;
- pareti + soffitto m²;
- pareti + pavimento m²;
- perimetro ml;
- quantità manuale.

Il calcolo è una proposta iniziale. La quantità della singola `EstimateItem` resta modificabile manualmente dall'editor.

## Prezzo a corpo / prezzo a misura

Ogni ambiente può essere impostato in modo indipendente.

### A corpo

Esempio tipico: Bagno.

- la checklist descrive tutte le lavorazioni comprese;
- le lavorazioni tecniche generate restano a prezzo zero;
- viene generata una sola voce economica `Nome ambiente — prezzo a corpo`;
- il prezzo complessivo viene inserito in quella voce.

Esempio: `Bagno — prezzo a corpo = 4.800 €` con sotto tutte le lavorazioni comprese nel capitolato.

### A misura

Esempio tipico: pittura, pavimento, rasatura, battiscopa.

- le quantità vengono proposte automaticamente dalle misure ambiente;
- il prezzo unitario viene valorizzato per m², ml o altra unità;
- il totale deriva da quantità × prezzo.

Le lavorazioni a corpo presenti anche dentro un ambiente a misura, come allestimento o smaltimento, possono comunque essere valorizzate separatamente.

## Ristrutturazione completa appartamento

Per un preventivo completo la logica prevista è:

1. creare il preventivo `Ristrutturazione intero appartamento`;
2. aprire `+ Configura`;
3. aggiungere gli ambienti reali uno per uno;
4. inserire le misure;
5. scegliere `A corpo` oppure `A misura` per ciascun ambiente;
6. usare la checklist specifica dell'ambiente;
7. aggiungere eventuali lavorazioni particolari con `+ Libera`, `+ Capitolato` o `+ Prezzario`.

Esempio:

- Bagno → checklist bagno → prezzo a corpo;
- Cucina → checklist cucina → a corpo oppure a misura;
- Stanza 1 → pittura, rasatura, pavimento, battiscopa → quantità automatiche;
- Stanza 2 → checklist indipendente;
- Balcone → demolizione, pendenze, impermeabilizzazione, piastrelle → quantità del balcone.

## Checklist Bagno

Il Bagno propone normalmente già attivi allestimento, rimozione rivestimenti, sanitari, smaltimento, ripristini, chiusura tracce, rasatura, impermeabilizzazione, posa piastrelle, stuccatura/pittura, sigillature e pulizia.

Restano liberamente attivabili/disattivabili anche:

- rimozione vasca;
- rimozione doccia;
- demolizione pavimento;
- posa su posa;
- ciclo di pulizia/sgrassaggio e promotore di adesione/aggrappante chimico;
- doccia a filo pavimento;
- piatto doccia in muratura;
- nicchie;
- muretto WC;
- controsoffitto;
- grandi formati;
- mosaico;
- tagli a 45°;
- muffa;
- isolamento;
- accesso difficoltoso;
- smaltimenti eccezionali.

Non esiste più una regola che obbliga a scegliere vasca oppure doccia o demolizione oppure sovrapposizione: l'utente decide cosa fa realmente parte del lavoro.

## Checklist Cucina

Comprende lavorazioni tipiche quali rimozione rivestimenti, smaltimento, ripristini, tracce, rasatura, rivestimenti, stuccatura e pittura. Sono attivabili separatamente demolizione pavimento, posa su posa, nuovo pavimento in piastrelle, SPC/LVT/laminato, battiscopa, grandi formati, tagli speciali, cartongesso e porte.

## Checklist Stanza

Base semplice con protezioni, stuccatura, pittura e pulizia. Possono essere attivati rasatura completa, rasatura armata, ripristini, demolizione pavimento, posa su posa, SPC/LVT/laminato, piastrelle, battiscopa, controsoffitto, contropareti, porta scorrevole e trattamenti muffa.

## Checklist Balcone/Terrazzo

Sono disponibili demolizione pavimentazione, smaltimento, posa su posa, formazione pendenze, impermeabilizzazione, posa pavimentazione esterna, ripristini murari, pittura, sigillature, accesso difficoltoso e smaltimenti eccezionali.

## Compatibilità con i preventivi precedenti

`GuidedWorkConfigurator` rimane come punto di ingresso usato dall'Activity, ma instrada il lavoro verso `FlexibleRoomPlanner`.

`GuidedWorkCatalog` continua a riconoscere anche i vecchi codici `CUC-*`, `PAV-*`, `PIT-*`, `CAR-*`, `TER-*`, ecc., così i preventivi creati con la versione precedente restano leggibili.

Il vecchio `BathroomConfigurator` viene mantenuto per compatibilità con i codici `BAGNO-*` già salvati, ma la nuova configurazione passa dagli ambienti flessibili.

## Capitolato e riferimenti tecnici

`StandardWorkCatalog.kt` resta la sorgente centrale delle descrizioni tecniche e dei riferimenti applicabili. Le nuove voci ambiente usano snapshot delle descrizioni nel preventivo, come già avveniva per le voci `CAP-*`.

I riferimenti normativi restano richiami tecnici **ove applicabili**, non dichiarazioni automatiche di conformità.

## Backup e ricostruzione

Il backup principale dei preventivi continua a salvare le `estimate_items`. Le voci generate dagli ambienti contengono anche un metadato `GE360_ROOM_META` nella nota interna, non visibile al cliente, con nome ambiente, tipo, misure, modalità prezzo e checklist.

In caso di duplicazione o importazione, se il database locale delle stanze non contiene ancora il record, `EstimateRoomStore.hydrateFromEstimate()` ricostruisce gli ambienti dai metadati delle voci.

## File principali

- `StandardWorkCatalog.kt` — capitolato tecnico;
- `RoomPlanner.kt` — modello ambienti, catalogo checklist e persistenza;
- `FlexibleRoomPlanner.kt` — UI flessibile definitiva;
- `GuidedWorkConfigurator.kt` — compatibilità e routing;
- `EstimateStore.kt` — preventivi, voci, prezzario e backup principale.
