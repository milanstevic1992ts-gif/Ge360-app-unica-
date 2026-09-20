# GE360 Preventivi — Best-of architecture 2026.09

## Principio

GE360 non deve copiare un singolo software. Il modulo Preventivi combina:

- profondità tecnica e analisi economica tipiche dei computi professionali;
- flusso preventivo → contratto → variante/extra → cantiere;
- semplicità d'uso da app per artigiani;
- struttura GE360 per ambienti, checklist libere e lavorazioni manuali;
- apprendimento progressivo dai prezzi realmente utilizzati.

Lo standard propone una base ma non blocca mai l'operatore durante la preparazione del preventivo.

## Flusso principale

1. Preventivo in bozza.
2. Ambienti indipendenti: Bagno, Cucina, Stanza, Balcone/Terrazzo, Altro.
3. Misure geometriche o manuali per forme irregolari; porte e finestre restano comprese nei m².
4. Checklist standard completamente modificabile + lavorazioni manuali.
5. Prezzo a corpo oppure a misura.
6. Costi interni e ore previste separati dal prezzo cliente.
7. Documento cliente dettagliato, sintetico o a corpo.
8. Stato Inviato.
9. Accettazione: snapshot completo e immutabile dell'accordo.
10. Modifiche successive come Extra o Variante collegati al contratto base.
11. Conversione preventivo → cantiere senza reinserire le lavorazioni.
12. Confronto futuro tra preventivato e dati reali di cantiere.

## Storico e contratto

`EstimateLifecycleStore` mantiene un archivio separato e non distruttivo con:

- eventi del preventivo;
- snapshot delle versioni registrate;
- hash SHA-256 dello snapshot;
- snapshot accettato;
- nominativo/nota di accettazione;
- relazioni fra contratto base, revisioni, extra e varianti.

Il riepilogo contrattuale usa il totale dello snapshot accettato quando disponibile, non il valore corrente del record modificabile.

## Extra e varianti

Extra e Variante sono preventivi autonomi collegati al contratto base. Possono avere propri ambienti, lavorazioni, quantità, prezzi e stato.

Nel riepilogo:

- contratto base = valore dello snapshot accettato;
- extra/varianti accettati = somma degli adeguamenti approvati;
- valore contrattuale aggiornato = base + adeguamenti approvati.

Questo evita di riscrivere retroattivamente l'accordo originario.

## Storico prezzi GE360

`EstimateLearningEngine` analizza i preventivi accettati, in corso e completati e aggrega le lavorazioni per codice/nome e unità.

Per ogni lavorazione può produrre:

- numero di utilizzi;
- ultimo prezzo;
- prezzo medio;
- minimo;
- massimo;
- ultimo utilizzo.

Lo storico parte dai dati realmente inseriti dall'impresa, non da valori teorici imposti dal software.

## Ricarico e margine

GE360 distingue esplicitamente:

- ricarico: `prezzo = costo × (1 + ricarico%)`;
- margine: `prezzo = costo / (1 - margine%)`.

Le due percentuali non sono equivalenti e non devono essere mostrate come sinonimi.

## Personalizzazioni e feedback loop

Le lavorazioni personali e le checklist predefinite possono essere esportate nel formato versionato `ge360.preventivi.customizations.v1`.

Il file repository:

`feature/preventivi/src/main/assets/preventivi_customizations.default.json`

può essere aggiornato in futuro con una versione revisionata del backup reale. In questo modo l'uso quotidiano dell'app può diventare il nuovo standard delle versioni successive senza sovrascrivere i dati personali già presenti.

## Funzioni già coerenti con il modello

- ambienti indipendenti;
- checklist libere;
- lavorazioni manuali senza limiti;
- riferimenti normativi/tecnici modificabili;
- misure manuali per stanze irregolari;
- duplicazione ambiente;
- checklist personali;
- libreria lavorazioni personali;
- backup JSON delle personalizzazioni;
- prezzo a corpo/a misura;
- costi interni e margine;
- modalità documento dettagliata/sintetica/a corpo;
- revisione indipendente;
- preventivo → cantiere;
- storico/snapshot accettato;
- extra e varianti collegati;
- storico prezzi e calcolo margine/ricarico.

## Non introdotto volutamente in questa fase

Per non trasformare il modulo in un ERP pesante, restano successive:

- takeoff automatico da PDF/CAD;
- generazione automatica delle misure da planimetrie;
- AI che costruisce il preventivo da voce/testo;
- firma grafica/elettronica completa con gestione documentale dedicata;
- portale cliente;
- sincronizzazione automatica fra storico prezzi e aggiornamento dei prezzi senza conferma umana.

L'AI, quando arriverà, dovrà compilare la stessa struttura manuale e verificabile già utilizzata dall'utente: non dovrà introdurre una seconda logica parallela.
