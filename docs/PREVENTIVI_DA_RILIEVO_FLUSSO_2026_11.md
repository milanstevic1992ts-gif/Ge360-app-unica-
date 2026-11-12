# GE360 — Nuovo flusso di sopralluogo e preventivazione

Data: 12 novembre 2026. **Branch di sviluppo**. Non modifica automaticamente preventivi già accettati.

## La regola principale

**RILIEVO PRIMA. PREVENTIVO DOPO.**

1. L'operatore entra in **Rilievi** e misura le stanze. Se vuole associare lavori a singole pareti, registra i muri quotati e le eventuali aperture.
2. Entra in **Preventivi → RIPRENDI UN RILIEVO → SCEGLI LAVORAZIONI**.
3. Sceglie un rilievo già salvato, poi crea una bozza collegata oppure collega esplicitamente una bozza modificabile.
4. Seleziona **la stanza**.
5. Seleziona **dove fare il lavoro**: tutta la stanza, tutte le pareti, Parete 1, Parete 2, soffitto o pavimento.
6. Cerca le lavorazioni del catalogo completo, le spunta e le aggiunge. Toccando una lavorazione inserisce/conferma quantità, prezzo e optional.
7. Torna alle altre superfici/stanze; il totale del preventivo si aggiorna senza duplicare le misure.

Lo stesso percorso è accessibile dal pulsante **PREVENTIVO · SCEGLI LAVORI PER MURO / SOFFITTO / PAVIMENTO** sulla scheda di un rilievo nativo. Un preventivo già collegato si può riaprire dalla lista Preventivi con l'azione «Lavorazioni da rilievo».

## Scelte di integrazione

- **Fonte delle misure:** le tabelle originali `FloorPlanStore`, non una copia del rilievo.
- **Pareti:** identificativi UUID stabili delle pareti originali `FloorPlanWall`; aperture già registrate in `FloorPlanWallOpenings`.
- **Quantità suggerite:** il validatore geometrico originale `PlanGeometryQuantitiesV2`. Se la geometria non è affidabile, la quantità resta zero e va confermata manualmente. Nessuna superficie non misurata è inventata.
- **Preventivo:** `EstimateStore` originale; righe distinguibili tramite `RILIEVO-QUOTE/{planId}/{roomId}/{scopeId}/{workCode}`. Metrature e prezzi confermati rimangono congelati anche se il rilievo cambia, con avviso di revisione geometrica.
- **Controllo doppioni:** stesso codice sulla stanza e su una sua superficie, oppure su tutte le pareti e su una parete specifica, viene bloccato. Lo stesso lavoro può comparire su pareti distinte. Rimuovere una lavorazione è un'azione esplicita, con conferma.
- **Salvataggio:** ogni aggiunta o aggiornamento salva solo la bozza scelta, conservando le altre voci del preventivo.
- **Blocco contrattuale:** nessuna modifica a preventivi accettati o non più in stato Bozza/Da controllare.
- **Scelte visuali:** selezione con schede stanza/superficie e spunte raggruppate per categoria, non un percorso di 8 schermate; note e agenda restano moduli separati.

## Limiti da conoscere

Questo collegamento diretto usa **Rilievi / Planimetrie nativi Android** (i record `FloorPlanStore`). Il disegnatore avanzato **OpenPlan3D** mantiene, per ora, un archivio WebView differente e il suo percorso di importazione metrature già esistente; non è corretto far credere che i rilievi avanzati compaiano automaticamente nel nuovo selettore. Servirà un adattatore specifico con geometria e UUID originali prima di abilitarli in questo stesso flusso.

La nuova interfaccia non equivale ancora a un editor grafico con tocco sul muro della piantina; i muri si scelgono in un elenco con nome e lunghezza. Le voci a corpo vanno verificate rispetto ad altre lavorazioni incluse per evitare sovrapposizioni concettuali.

## Verifiche richieste prima del rilascio

- Compilazione `:app:assembleDebug` e test `:feature:planimetrie:testDebugUnitTest`, `:feature:preventivi:testDebugUnitTest`.
- Prova su Android: due stanze, pareti nominate, apertura finestra, una lavorazione su due pareti con prezzi diversi, pavimento e soffitto, ricerca catalogo, eliminazione con conferma, riapertura della bozza.
- Cambio della metratura originale: avviso di modifica senza ricalcolo silenzioso delle quantità già quotate.
- Backup/ripristino e apertura del preventivo in editor classico.
- Tema scuro e navigazione con il tasto Indietro.

Le schermate Note/Agenda e La mia giornata non sono il punto di partenza del flusso: il nuovo ingresso si trova in Preventivi e nella scheda Rilievi.
