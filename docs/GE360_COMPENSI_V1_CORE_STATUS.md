# GE360 Compensi V1 — stato implementazione Android

Aggiornamento: 1 novembre 2026
Branch: `feature/ge360-compensi-v1-core-20261031`
Stato: **CODICE_SU_BRANCH — non compilato, non testato, non unito a main e non installato**.
Base originaria Android `main` verificata: `91bbf01808fce6acd375ef6f5525ff0d278fdc05`.

## Implementato sul branch

1. `CompensationLedger.kt`: regole pure con importi `Long` centesimi, accordi settimanali/a cottimo, maturati confermati, pagamenti parziali e storni; ripartizione facoltativa tra cantieri, prevenzione doppio link FinanceRecord.
2. `CompensationStore.kt`: database SQLite isolato `ge360_compensi_v1.db`, salvataggio atomico accordo+maturato, eventi di pagamento append-only, link a PDF Scanner per UUID, export/import `ge360.compensi.v1`, transazioni e rifiuto di conflitti di dati. NON crea movimenti finanziari automaticamente.
3. `CompensationActivity.kt`: schermata leggera per inserimento collaboratore (anagrafica esistente di `DailyStore`), settimana o cottimo, importo, data, cantiere facoltativo, pagamento parziale e data effettiva, caricamento fattura PDF tramite selettore documenti.
4. `CollaboratorsActivity.kt` e manifest: nuovo punto d'accesso, mantenendo Slobodan e collaboratori esterni legacy.
5. `feature/backup/.../BackupCoordinator.kt`: esporta `modules/compensi.json` nel backup GE360; ripristina se presente, ne verifica lo schema; su backup più vecchi senza modulo conserva l'archivio Compensi anziché cancellarlo. I PDF selezionati sono inseriti nel DocumentStore Scanner già compreso nel backup esistente.
6. `app/.../sync/DataHubSyncCoordinator.kt`: snapshot `compensi` push-first, senza replaceAll; include nomi collaboratori di sola visualizzazione provenienti da Giornata, senza modificare l'anagrafica.
7. `feature/giornata/.../CompensationLedgerRulesV1Test.kt`: casi JUnit già preparati, NON eseguiti.

## Vincoli importanti

- Uno screenshot, una fattura o un riferimento documento **non** costituiscono pagamento. Il saldo rappresenta **pagamenti dichiarati manualmente** nella nuova schermata; non una verifica bancaria.
- Le registrazioni di pagamenti preesistenti in `FinanceStore`, `SlobodanWeeklyPay` e `ExternalCollaboratorsActivity` non vengono importate o sovrascritte automaticamente. I dati dei due registri non vanno sommati senza riconciliazione.
- I nuovi pagamenti non sono ancora transazioni Spese: non dichiarare contabilità unificata o doppio conteggio risolto a livello di bilancio.
- L'allegato fattura viene registrato nel DocumentStore/Scanner e legato al maturato per UUID; il PDF resta contenuto nel backup Scanner. Nessuna fattura viene inviata a terzi.
- Non sono implementati ancora: prova di bonifico per singolo pagamento, controlli/anteprima PDF direttamente dalla scheda, migrazione dei pagamenti legacy, allocazione multi-cantiere con UI, gestione rettifiche/storni attraverso la UI, analitiche consolidate e notifiche del nuovo motore.
- L'app Web Glass sul backend ha un adapter per il nuovo schema sul proprio branch V3; non esiste ancora alcun deploy della coppia app/backend.
- Esportazione dati, permessi e accessibilità devono essere collaudati su un dispositivo reale prima dell'uso produttivo.

## Criteri QA futuri — NON eseguiti

- Maturato 800,00 € e pagamento 250,00 € → residuo registrato 550,00 €.
- Una fattura PDF allegata non riduce il saldo.
- Le vecchie registrazioni Slobodan/Esterni rimangono visibili e immutate.
- Record nuovo persistente dopo kill processo e riapertura.
- Backup completo → restore merge → restore replaceAll → nessun UUID duplicato, stesso importo e PDF disponibile.
- Backup senza `modules/compensi.json` → il nuovo registro resta invariato e compare un avviso.
- Due FinanceRecord con stesso UUID collegato → conflitto esplicito.
- Login Data Hub, revisioni, conflitti 409 e modalità offline senza perdite.
- Schermata a 360 e 390 pixel, tastiera, navigazione e date.
- Build Gradle `feature:giornata` e app, senza generare APK in questa fase.

## Prossimo task

`GE360-COMPENSI-004`: predisporre collegamento confermato del pagamento a `FinanceStore` e prova di versamento preservando gli eventi esistenti; aggiungere UI di storno e aggregazioni solo dopo riconciliazione. L'operatore deve poter scrivere un nome e registrare un compenso senza scegliere un cantiere.

**Non creare PR verso main, non attivare Actions, non eseguire test/build/APK/deploy automaticamente senza nuova verifica delle condizioni.**
