# GE360 App Unica — Note, materiali e attrezzi da ricordare

## Obiettivo
Un appunto nato durante il lavoro non deve restare dimenticato: può diventare
una voce esplicita nella **Checklist furgone** di un cantiere.

Questa modifica **riutilizza i dati esistenti**:
- Note e Agenda -> `AgendaStore` (note originali conservate)
- Attrezzi & Materiali -> `ToolsStore.job_resources` (stesse voci della checklist)
- Preparazione del furgone -> `VanModeActivity`
- Pianificazione Dashboard -> `VanPreparationStore` (data e nota del cantiere)

Non introduce un nuovo database, migrazioni SQLite o sync forzate.

## Aggiunta veloce
Da **Attrezzi & materiali**, **Note e Agenda**, **Cantiere** oppure
**Checklist furgone** toccare "Ricorda da portare / comprare".

Si scelgono:
1. Cantiere attivo (obbligatorio per alimentare la checklist esistente)
2. Nome oggetto
3. **Attrezzo da portare**, **Materiale da portare** oppure **Materiale da comprare**
4. Quantità e unità di misura, anche con virgola decimale
5. Nota breve (es. ubicazione, specifica di acquisto)

La lista non viene cambiata prima della conferma. Una voce già presente
dello stesso tipo e nome nel medesimo cantiere viene riconosciuta:
il salvataggio chiede se **aggiornarla** invece di duplicarla.

## Dalle note
Le note di lavoro (scope WORK) presentano l'azione "Ricorda per il cantiere".
Il titolo viene proposto come nome dell'oggetto, ma il **corpo della nota
non viene copiato automaticamente** nella checklist, perché potrebbe
contenere informazioni non destinate a condivisioni.
Le note PRIVATE non mostrano l'azione di conversione.
La nota originale non viene alterata o eliminata.

Una nota senza cantiere può comunque essere collegata scegliendo un
cantiere attivo nella finestra. Se non ci sono cantieri attivi, l'utente
può conservare il promemoria generico in Note e Agenda.

## Checklist
- "Attrezzo da portare" e "Materiale da portare" => **PRESO**
  quando è caricato; l'elemento viene rimosso dalla lista come prima.
- "Materiale da comprare" => prima pulsante **COMPRATO**.
  Il materiale resta in lista con stato "Comprato · da caricare".
  Solo dopo il pulsante **PRESO** lo rimuove dalla checklist.
- "TOGLI" chiede conferma, e non equivale a "Preso".
- Note e quantità sono visibili sulle schede da caricare.
- La Checklist conserva il comportamento storico "usa e spunta":
  il flag Preso non è uno storico permanente dei movimenti di magazzino.

La data della Preparazione furgone può essere salvata per la Dashboard.
**Non è stata aggiunta una notifica push automatica per la sola lista**;
per un avviso a data e ora si usa un'attività con promemoria in Agenda.

## Integrazione nel menu
- Attrezzi & materiali: card di aggiunta rapida
- Note e Agenda: pulsante aggiunta e conversione di note di lavoro
- Cantiere: "Ricorda da portare" e link "Checklist furgone"
- Oggi: shortcut "Checklist furgone"

## Verifiche e limiti
Test: `feature/attrezzi/src/test/.../ResourceReminderRulesTest.kt` (6 scenari).
Test Android ancora da eseguire: duplicati, note private, acquisto
seguito da carico, list sharing, switch di cantiere, Dashboard e backup.
L'attrezzo viene salvato nel DB attrezzi già incluso nella copertura backup
del progetto, ma va verificato con un ripristino reale.

**Non creare APK, non avviare GitHub Actions, non unire in main** finché
non sono passati compilazione e collaudo.
