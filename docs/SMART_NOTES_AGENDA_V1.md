# GE360 Smart Notes & Agenda — Incremento 1 (branch isolato)

Stato: CODICE_SU_BRANCH, non compilato, non testato, non distribuito. Nessun deploy Debian o APK.
Fase 2: implementati calendari settimanale/mensile, ricorrenze, allarmi Android e riepilogo dashboard completa.

## Flusso iniziale
- Dalla Home: + > Nota veloce / Attivita' / Appuntamento. Da Altro: Note e Agenda.
- Titolo o testo libero; crea senza scegliere cliente o cantiere. Associa in seguito tramite UUID GE360.
- Schede Oggi (scaduti e oggi), Attivita', Note e Agenda; ricerca semplice.
- Attivita' completabili/riapribili; da nota a task con ID nuovo e campo sourceNoteId, originale invariato.
- Priorita' alta e scadenza opzionale; appuntamento con data/ora obbligatoria.
- Ambito WORK o PRIVATE. Il Data Hub non riceve questi dati: gli elementi privati devono rimanere esclusi anche nella futura sincronizzazione.
- Home protetta: scorciatoie senza aprire archivi; dashboard completa: riepilogo impegni scaduti e di oggi.
- Persistenza offline con AtomicFile. Export/import nel backup completo e ripristino merge per UUID e updatedAt.

## Dati e contratti
- schema: ge360.agenda.v1
- file locale: filesDir/ge360-agenda-v1.json
- backup: modules/agenda.json (opzionale per i vecchi backup)
- sync Data Hub: NON attivata in questo incremento. Export WORK-only predisposto, ma serve verifica server e politica di revoca quando un elemento passa da WORK a PRIVATE.
- campi: id UUID, kind NOTE|TASK|EVENT, title, body, dueAt epoch ms (0 = assente),
  priority NORMAL|HIGH, scope WORK|PRIVATE, clientGe360Id?, jobsiteGe360Id?,
  sourceNoteId?, completed, createdAt, updatedAt.

## Fase 2: visualizzazioni e promemoria locali
- Settimana (lunedì-domenica) e Mese con navigazione, selezione giorno e segni sugli impegni.
- Ricorrenze DAILY/WEEKLY/MONTHLY senza duplicare le attività: il calendario proietta le date.
- Il completamento di una ricorrenza avanza alla prossima data futura; ultima chiusura registrata.
- Ricorrenze mensili ancorate al giorno iniziale (es. 31 gennaio -> 28 febbraio -> 31 marzo).
- Notifiche locali per TASK/EVENT, con anticipo 0/15/60/1440 minuti; Android 13+ richiede autorizzazione.
- AlarmManager inexact per evitare privilegi speciali: possibili ritardi di consegna.
- Ripristino allarmi dopo avvio/aggiornamento app/cambio orario e fuso.
- In lockscreen la notifica pubblica è neutra; promemoria PRIVATE sempre neutri.
- Nessuna notifica viene pianificata per NOTE; la ricezione dipende dalle impostazioni Android.

## Fuori ambito / da progettare
- Sincronizzazione esterna (Data Hub e Google Calendar), dettatura, foto/allegati,
  assegnazione collaboratori, ricerca globale e suggerimenti JARVIS.
- Notifiche su dispositivo reale e migrazione JSON da verificare prima della distribuzione.
- Il Data Hub non è stato toccato; non c'è un nuovo endpoint server.


## Accettazione da verificare in seguito
1. Creare nota senza rete e senza cantiere; chiudere app e ritrovarla.
2. Convertire in task: due record, nota originale inalterata, sourceNoteId corretto.
3. Selezionare cliente e cantiere da anagrafiche esistenti.
4. Backup e restore (merge/replace), anche da ZIP precedente senza agenda.json.
5. Indurre archivio JSON corrotto: nessuna cancellazione silenziosa.
6. Definire e verificare il contratto Data Hub e la revoca dei record passati a PRIVATE, prima di attivare il push.
7. Verify che push su branch non attivi CI; niente build, test, APK o merge finche' non autorizzati.


## Fase 3 — collegamenti clienti e cantieri (codice su questo ramo)
- I record propri di Agenda mantengono un UUID autonomo, con clientGe360Id e jobsiteGe360Id facoltativi.
- Dalla scheda Clienti: "Note e Agenda cliente" e "+ Nuova nota collegata" aprono Agenda con UUID già predisposto.
- Dal Centro Cantiere: "Note e Agenda" e "Nota veloce" aprono Agenda con contesto cantiere.
- Se il contesto è un cantiere, la vista mostra solo note collegate a quel cantiere, non quelle di altri cantieri dello stesso cliente.
- Clienti: gli eventi fattuali con nextActionDate ISO sono mostrati in sola lettura; ora vengono recuperati tutti quelli datati, non solo le ultime 200 interazioni.
- Cantieri: fine prevista e SAL/acconti aperti con dueDate ISO sono mostrati in sola lettura; chiusi/cancellati e cantieri archiviati non appaiono.
- Ogni elemento esterno usa il suo ID canonico e rimanda alla schermata che possiede il record; non viene copiato o conteggiato come nuova attività GE360.
- Le date senza ora rimangono date senza ora, non attivano notifiche Agenda e non sono trasformate automaticamente in appuntamenti.
- Il campo Cliente.followUp libero non viene interpretato come data, per evitare false scadenze.
- I dati Agenda non sono inviati al Data Hub: privacy e revoche ancora da progettare.
- Aggiunta la proiezione nel calendario Settimana/Mese: i giorni con date esterne sono evidenziati senza creare copie.
- Non modifica i punteggi del termometro cliente, i SAL, la contabilità o le fonti originali.

### Da verificare prima del collaudo
- Compilazione e risoluzione dei link Android Cliente ↔ Agenda e Cantiere ↔ Agenda.
- Clienti con due cantieri e scadenze simili: nessuna contaminazione dei contesti.
- Dati storici, date invalide, cambio fuso e date ISO.
- Backend/sync non coinvolti, FIR/Scanner non modificati.
- Nessuna compilazione, CI, test, APK, deploy o merge eseguiti in questa fase.

### Integrazione checklist e note preesistenti
- La checklist nel JobsiteWorkflowStore resta proprietaria dei task: Agenda mostra in lettura solo task aperti (da fare, in corso, bloccati); a livello generale include quelli manuali, nel contesto del singolo cantiere anche quelli da template.
- Le note storiche del Centro Cantiere restano nel loro archivio e sono visibili solo nel contesto del cantiere selezionato.
- I task e le note privi di scadenza vengono etichettati "Senza data" e non compaiono nel calendario a giorni o nelle notifiche.
- L'accesso apre la scheda del cantiere: azioni sulla checklist restano nel modulo proprietario.
