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


## Fase 4 — La mia giornata (sviluppo non collaudato)
- Nuova Activity OperationalDayActivity, accessibile da Home protetta, Dashboard completa e sezione Altro.
- Nessun archivio aggiuntivo: aggrega AgendaStore, AgendaSourceProjection e i promemoria del TodayDashboardView.
- Le attività Agenda possono essere concluse dalla vista; gli elementi di Clienti, SAL, Checklist e promemoria Home restano in sola lettura e rimandano al proprietario.
- Scadenze suddivise in DA RECUPERARE / OGGI / PROSSIMI SETTE GIORNI / PIÙ AVANTI / DA PIANIFICARE.
- Distingue date senza ora da appuntamenti con orario: un SAL di oggi non è scaduto soltanto perché ora è dopo mezzanotte.
- "Oggi", "7 giorni" e "Tutto" sono filtri visivi, non duplicano record né modificano date.
- Appunti NOTE sono separati dai task, con accesso ai tre più recenti; le anteprime dei PRIVATI non rivelano il titolo.
- Pulsanti rapidi per Note / Attività / Appuntamenti, e accesso a Ore/Squadra già presente.
- Ogni AgendaEntry viene aperta direttamente tramite EXTRA_EDIT_ENTRY_ID; gli elementi di origine aprono la propria scheda.
- I promemoria generali Home hanno identificativi WorkflowTask canonici e scadenze ISO salvate in templateId con prefisso "dashboard-reminder:". Non sono copiati in AgendaStore.
- Nessuna modifica automatica ai clienti, ai SAL, alle checklist, ai timer, a JARVIS o al Data Hub.

### Criticità da verificare prima di una release
- Compilazione e runtime Android su dispositivo e legami tra moduli.
- Controllare timing delle ricorrenze e limiti di visibilità delle note private.
- Verificare che i promemoria Home DATE_ONLY non vengano classificati scaduti prima del giorno successivo.
- Per un promemoria generale, il link apre la Home di origine; la modifica resta nel pannello promemoria.
- Il nuovo schermo non sostituisce il registro ore e rapportini Giornata, che resta la fonte.
- GitHub Actions, build, test, APK, merge e Debian non vengono eseguiti in questo incremento.


## Fase 5 — Pianificatore settimanale cantieri (codice su ramo, non compilato)
- Nuova schermata Settimana cantieri (lun–dom), navigazione settimane, accesso Home protetta, Altro, La mia giornata e Centro Cantiere.
- Fonte unica della squadra: DailyStore.PersonnelAssignment. Inserimento e modifica dal calendario senza nuova tabella.
- Controlli prima della scrittura: assegnazione duplicata per stessa persona, giorno e cantiere; avviso se oltre 8 ore o su più cantieri. Conferma esplicita in caso di sovrapposizione.
- Appuntamenti e sopralluoghi Agenda compaiono nella settimana; nuova visita proposta alle 09:00 locali del giorno selezionato, modificabile prima di salvarla.
- I dati materiali e lavorazioni sono letti da ToolsStore.summary e JobsiteWorkflowStore.tasks; mancanti e richieste non implicano una data di consegna.
- La preparazione lavoro riceve ora l'UUID cantiere per preselezionarlo. Nessuna duplicazione o migrazione dei materiali.
- I dati non vengono sincronizzati con il Data Hub dal nuovo modulo; i calendari esterni restano esclusi.
- Necessari prima dell'uso: compilazione, verifica Android, casi limite date/DST, revisione del flusso persone e assegnazioni, controlli autorizzazioni.
- Nessuna GitHub Action, test, APK, deploy, merge o modifica al backend eseguita in questa fase.


## Fase 6 — Programmato vs effettivo (codice su ramo, NON collaudato)
- WeeklyExecutionComparison legge le assegnazioni di DailyStore e i timer Giornata originali per ciascun giorno della settimana: nessuna copia di sessioni, nessuna nuova tabella.
- "Previste" indica la somma dei minuti di PersonnelAssignment; "registrate" è la somma delle durate dei WorkSession presenti nel registro.
- La vista espone settimana, giorni e cantiere; i giorni futuri e quello corrente non sono trattati come scostamenti definitivi.
- Il delta di giorni passati è registrate meno previste: NON misura la produttività e non prova che un lavoro senza timer non sia stato eseguito.
- I timer ancora aperti in giorni precedenti sono evidenziati e non generano falsi conteggi di ore fino a oggi; il confronto di quei giorni non è definitivo.
- Le ore registrate senza assegnazione vengono evidenziate, ma non inserite automaticamente in pianificazione. Le vecchie sessioni senza UUID personale sono attribuite solo se la corrispondenza nome/cantiere/giorno è univoca.
- Checklist: mostra numero di lavorazioni "Fatto", aperte e bloccate nello STATO ATTUALE, non come produttività o lavorazioni concluse nella settimana (manca uno storico affidabile di snapshot settimanali).
- Avanzamento percentuale: letto da JobsiteStore.progressSummary se esiste, come stato attuale. Non viene inventato un obiettivo percentuale a data.
- Materiali: riepilogo di mancanti, richieste e preparazione deriva da ToolsStore.summary; il pulsante "Confronta materiali" apre la riconciliazione ufficiale MaterialEvidenceActivityV13 per il cantiere via UUID.
- La riconciliazione di quantità previste/consumate resta nel modulo Materiali V13, che distingue fattura, DDT, consumo e completezza dello storico. Il pianificatore non somma quantità eterogenee né inventa consumi.
- I dati passano da fonti originali e sono consultati senza creare sessioni, prezzi, movimenti o fatture.
- Se la lettura dei timer fallisce, il programma settimanale rimane consultabile e il confronto è indicato come NON DISPONIBILE (mai come zero effettivo).
- Non sono stati compilati APK né avviati test, GitHub Actions o deploy; verificare i confronti con timer aperti, sessioni legacy, lavori con personale senza UUID e cantieri multipli prima dell'uso reale.

### Prossimi controlli di accettazione (da autorizzare separatamente)
1. Stessa persona, due cantieri, una giornata: tutte le ore registrate sono attribuite una sola volta.
2. Timbrature senza assegnazioni: ore presenti nel consuntivo, segnalate come non pianificate.
3. Settimana futura: nessun falso scostamento negativo.
4. Giornata in corso con timer aperto: valori parziali e avviso.
5. Timer lasciato aperto da ieri: valori non proiettati impropriamente al presente.
6. Una lavorazione ancora aperta: non conteggiata come completata per mancanza di storico.
7. Magazzino con storia parziale: accesso alla riconciliazione ufficiale, nessun delta materiale inventato.
