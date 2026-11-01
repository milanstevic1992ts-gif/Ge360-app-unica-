# GE360 Smart Notes & Agenda — Incremento 1 (branch isolato)

Stato: CODICE_SU_BRANCH, non compilato, non testato, non distribuito. Nessun deploy Debian o APK.

## Flusso iniziale
- Dalla Home: + > Nota veloce / Attivita' / Appuntamento. Da Altro: Note e Agenda.
- Titolo o testo libero; crea senza scegliere cliente o cantiere. Associa in seguito tramite UUID GE360.
- Schede Oggi (scaduti e oggi), Attivita', Note e Agenda; ricerca semplice.
- Attivita' completabili/riapribili; da nota a task con ID nuovo e campo sourceNoteId, originale invariato.
- Priorita' alta e scadenza opzionale; appuntamento con data/ora obbligatoria.
- Ambito WORK o PRIVATE. Il Data Hub non riceve ancora questi dati: gli elementi privati devono rimanere esclusi anche nella futura sincronizzazione.
- Persistenza offline con AtomicFile. Export/import nel backup completo e ripristino merge per UUID e updatedAt.

## Dati e contratti
- schema: ge360.agenda.v1
- file locale: filesDir/ge360-agenda-v1.json
- backup: modules/agenda.json (opzionale per i vecchi backup)
- sync Data Hub: NON attivata in questo incremento. Export WORK-only predisposto, ma serve verifica server e politica di revoca quando un elemento passa da WORK a PRIVATE.
- campi: id UUID, kind NOTE|TASK|EVENT, title, body, dueAt epoch ms (0 = assente),
  priority NORMAL|HIGH, scope WORK|PRIVATE, clientGe360Id?, jobsiteGe360Id?,
  sourceNoteId?, completed, createdAt, updatedAt.

## Deliberatamente NON inclusi nel primo incremento
Notifiche reali, calendari esterni, dettatura, foto/allegati, assegnazione collaboratori,
ricerca globale e suggerimenti JARVIS. Il salvataggio non fa partire notifiche:
la scheda Oggi va aperta manualmente. Il Data Hub deve essere verificato prima di
considerare la sincronizzazione backend collaudata.

## Accettazione da verificare in seguito
1. Creare nota senza rete e senza cantiere; chiudere app e ritrovarla.
2. Convertire in task: due record, nota originale inalterata, sourceNoteId corretto.
3. Selezionare cliente e cantiere da anagrafiche esistenti.
4. Backup e restore (merge/replace), anche da ZIP precedente senza agenda.json.
5. Indurre archivio JSON corrotto: nessuna cancellazione silenziosa.
6. Definire e verificare il contratto Data Hub e la revoca dei record passati a PRIVATE, prima di attivare il push.
7. Verify che push su branch non attivi CI; niente build, test, APK o merge finche' non autorizzati.
