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
- Esiste ora un collegamento **esplicito** alle Spese già registrate: controllo UUID, tipo uscita aziendale, importo in centesimi, data e cantiere quando entrambi dichiarati. Il movimento resta nel `FinanceStore`, non viene duplicato.
- In alternativa l'utente può confermare la creazione di **una** uscita aziendale: l'identificativo `ge360-compensi-payment:<UUID>` è stabile per riutilizzare la stessa registrazione nei ritentativi. Il codice non effettua bonifici, né accerta i movimenti bancari.
- Per ciascun pagamento è possibile allegare un comprovante PDF nello Scanner; non significa che la banca ne abbia attestato l'esecuzione. È possibile registrare uno storno solo per eventi **non collegati** a Spese; uno storno non cancella la prova o il vecchio evento.
- Mancano ancora: verifica automatica dell'effettivo accredito bancario, anteprima PDF direttamente dalla scheda, migrazione/riconciliazione dei pagamenti legacy, allocazione multi-cantiere con UI, rettifiche di eventi già riconciliati, analitiche consolidate e notifiche del nuovo motore.
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

## Tranche riconciliazione 1 novembre 2026

- File `CompensationFinanceReconciliation.kt`: validatore puro e ricerca delle Spese ammissibili senza collegamenti per nome o importo soltanto.
- `CompensationStore.kt`: `linkFinanceRecord()` aggiornamento solo del riferimento UUID, `attachPaymentProof()` per documento Scanner (unicità tra eventi), senza generare transazioni economiche.
- `CompensationActivity.kt`: selezione manuale uscita esistente, registrazione espressamente confermata con UUID stabile, indicatore di collegamento eventualmente non verificabile se l'uscita è stata cambiata o cancellata, storno di registrazioni non collegate, caricamento PDF prova pagamento.
- `CompensationFinanceReconciliationTest.kt`: casi JUnit aggiunti per importo, valuta numerica, data, cantiere, doppioni e uscita assente; **non eseguiti**.
- Risolta mancanza del formattatore EUR nella schermata Kotlin.

**Attenzione:** Compensi usa due database SQLite separati (archivio compensi e Spese). Non c'è una transazione ACID cross-database. Il riferimento stabile consente il ritentativo dopo errore, ma il legame dovrà essere verificato ancora sul dispositivo e dopo i ripristini. Le Spese di collaboratori interni ed esterni hanno categorie contabili differenti; la classificazione è scelta espressamente dall'operatore. Non sommare maturato, pagamento e Spesa come tre costi.
