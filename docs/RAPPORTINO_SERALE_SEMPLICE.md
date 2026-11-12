# GE360 App Unica — Rapportino serale semplice

12 novembre 2026 · feature branch, non ancora collaudato su Android.

## Obiettivo

Compilare in 1–2 minuti, alla sera, un **rapportino per cantiere e data**, senza dover avviare il timer, compilare tariffe o aprire il modulo Giornata completo.

## Percorso

- **Home → RAPPORTINO SERALE**: sceglie cantiere e data.
- **Centro Cantiere → RAPPORTINO SERALE**: cantiere preselezionato tramite UUID canonico.
- Se è in corso un timer singolo, il suo cantiere può venire suggerito.
- Se esiste un unico cantiere in stato *In corso*, questo viene suggerito; con più cantieri l'utente sceglie quello corretto.

## Flusso schermata

1. Data iniziale **oggi**, modificabile anche per recuperare un giorno passato.
2. Cantiere (il suo indirizzo viene mostrato come contesto **dove** si è lavorato).
3. **Cosa abbiamo fatto oggi?** campo principale libero.
4. **Cosa facciamo domani?** secondo campo, facoltativo.
5. Pulsante opzionale per **imprevisti, materiale/attrezzi e altre note**.
6. **SALVA RAPPORTINO**: richiede una descrizione dei lavori svolti e imposta `closed=true` sul **DailyReport**, non ferma i timer né chiude il cantiere.
7. **Condividi**: dopo il salvataggio passa un testo leggibile allo share sheet Android, senza invii automatici.
8. Elenco degli ultimi rapportini; tocco per riaprirne uno.

## Sicurezza dei dati

- **Stesso `DailyStore` e tabella `daily_reports`** già presenti: chiave unica `(jobsiteId, workDate)`; nessuna duplicazione o nuova migrazione.
- Riutilizzati i campi `doneToday`, `nextSteps`, `issues`, `materialsNeeded`, `notes`, `closed`.
- Ogni modifica in corso è salvata come **bozza** con debounce 900 ms; il cambio di cantiere/data e l'uscita dall'Activity cercano di salvarla prima del cambio.
- Non crea rapportini vuoti involontariamente.
- Se si modifica un rapporto già chiuso, le modifiche lo riportano in bozza; **SALVA RAPPORTINO** lo rende nuovamente completo.
- Le ore del timer sono mostrate come contesto ma non possono essere modificate da questa schermata.
- I rapportini continuano a essere esportati nel backup centrale come `modules/giornata.json` (formato esistente): serve collaudare il ciclo backup/ripristino finale dell'App Unica.

## Limiti intenzionali della prima versione

- Nessun allegato fotografico diretto: le foto rimangono nel modulo Galleria collegato al cantiere.
- L'elenco materiali qui è descrittivo e **non crea automaticamente acquisti o checklist furgone**. Per questi utilizzare il modulo Note → Attrezzi/Materiali già predisposto.
- Nessun PDF automatico; condivisione manuale del testo tramite Android.
- Nessun promemoria push alla sera, nessuna IA remota e nessun invio a terzi.
- Non è ancora stato collaudato su Android: verificare navigazione con più cantieri, rapportini per date passate, ripresa bozze, condivisione, backup e restore.
- **Nessun APK e nessuna GitHub Actions** avviati da questa modifica.

## Verifiche da effettuare prima del merge

```sh
./gradlew :feature:giornata:testDebugUnitTest :feature:home:compileDebugKotlin :feature:centrocantiere:compileDebugKotlin :app:assembleDebug
```

Poi provare sul telefono:
1. Avvio dalla Home senza cantiere, scelta manuale.
2. Avvio da un Centro Cantiere con preselezione corretta.
3. Modifica rapida, chiusura app, riapertura e recupero della bozza.
4. Salvataggio di oggi per due cantieri diversi.
5. Creazione e modifica di un rapportino per ieri.
6. Verifica di una sola riga per coppia cantiere/data e del campo `closed`.
7. Condivisione manuale e test ripristino del backup ZIP.
