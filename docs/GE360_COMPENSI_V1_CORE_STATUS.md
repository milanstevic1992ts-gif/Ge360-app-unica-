# GE360 Compensi V1 — nucleo di dominio Android

Data: 2026-10-31
Stato: CODICE SU BRANCH, **non eseguito, non compilato e non collegato alla UI/persistenza**.
Branch: `feature/ge360-compensi-v1-core-20261031`.
Base originaria: Android main `91bbf01808fce6acd375ef6f5525ff0d278fdc05`.

## Codice introdotto
- `feature/giornata/src/main/java/it/edilmilan/ge360/giornata/CompensationLedger.kt` — regole pure, nessun accesso a dati, rete o file.
- `feature/giornata/src/test/java/it/edilmilan/ge360/giornata/CompensationLedgerRulesV1Test.kt` — scenari JUnit di contratto, non eseguiti.
- `feature/giornata/build.gradle.kts` — JUnit per futuro controllo locale.

## Contratto
Accordo WEEKLY/PIECEWORK, maturato in centesimi `Long` confermato, periodo settimanale ISO (Europe/Rome), pagamento parziale, saldo, storno totale con riferimento all'evento originale, eventuali ripartizioni su più cantieri oppure nessuno. La logica `balance()` rifiuta importi superiori al maturato, doppioni di evento e duplicati di FinanceRecord per il medesimo maturato; `fullyPaid` non interpreta un maturato nullo come un pagamento effettuato.

## Non implementato
Non esiste ancora in questo branch il database dei compensi, la migrazione legacy, l'integrazione `FinanceStore`, la gestione dell'allegato fattura, il flusso prova pagamento, la UI «Nuovo compenso», il backup/restore, il Data Hub `compensi` né l'aggiornamento delle statistiche cantiere. Non è corretto chiamarlo **motore compensi completo**.

## Riuso del codice esistente
Non modificare `SlobodanWeeklyPay.kt`, `CollaboratorsActivity.kt`, `ExternalCollaboratorsActivity.kt` o FinanceRecord storici prima di aver progettato la migrazione senza duplicati. Il pagamento dell'ora lavorata non è automaticamente il maturato da accordo, la fattura non prova il pagamento. Vedi `GE360-Backend` branch `docs/ge360-next-modules-contracts-20261031` e file `docs/GE360_COMPENSI_V1_CONTRACT.md`.

## Prossime fasi
1. Migrazione e archivio locale versionato (con campi UUID e centesimi), senza cancellazione dei dati preesistenti.
2. UI semplice collaboratore → settimana/cottimo → maturato → fattura → pagamento.
3. Riconciliazione evento/FinanceRecord per impedire doppio conteggio, pagamenti parziali e storni.
4. Export Data Hub push-first revisionato, backup/restore completo e vista web solo dei dati realmente sincronizzati.
5. Solo successivamente test Kotlin/Android, build e distribuzione autorizzata.

**GitHub Actions**: nessuna PR aperta, nessun push a main, nessuna build o collaudo eseguito da questi commit. Le azioni automatiche sono state limitate a main/PR verso main nei workflow Android letti; verificare di nuovo prima di ogni futura modifica.
