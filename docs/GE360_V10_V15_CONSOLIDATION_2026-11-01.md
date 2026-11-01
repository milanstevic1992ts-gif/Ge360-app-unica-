# GE360 — Consolidamento tecnico V10–V15
## Stato registrato: 1 novembre 2026

**Stato: sorgenti sul branch, NON compilati, NON testati, NON collaudati, NON distribuiti.**

Branch Android: `feature/ge360-integrated-v3-20261101`.
Non toccare `main`, Debian, backend Glass o il marketing. Non attivare Actions, non creare APK, non eseguire Gradle, emulatori o test senza autorizzazione separata.

## Fonte di verità e perimetri

| Dominio | Fonte autorevole | Relazione / vincolo |
| --- | --- | --- |
| Clienti, cantieri e UUID | `ClientStore`, `JobsiteStore` | La loro presenza non obbliga planimetrie, documenti o materiali ad appartenere a un cantiere |
| Preflight referenziale V10 | `RestoreLinksV10` | Valida i riferimenti supportati prima del ripristino ordinario |
| Diagnostica V11 | `DiagnosticsInspectorV11` | Lettura su richiesta, non una certificazione globale né una riparazione |
| Proiezione operativa V12 | `JobsiteControlV12` | KPI dei soli dati presenti e degli UUID del cantiere; ore, compensi e Spese restano distinti |
| Piano materiali | `MaterialPlanStore` | Revisioni confermate e UUID espliciti; quantità, non costi verificati |
| Materiali movimentati | `MaterialInventoryStore` | Carico ≠ acquisto fatturato, consumo ≠ giacenza fisica certificata; completo solo se provenance valida |
| Documenti V13 | `MaterialDocumentEvidenceStoreV13` + `DocumentStore` | Fattura o DDT ↔ materiale/cantiere/quantità per conferma manuale |
| Abbinamento V14 | `MaterialReconciliationRulesV14` | Intersezione quantità fattura–DDT, non una nuova quantità o un importo aggiuntivo |
| Importi e budget V15 | `MaterialCostStoreV15` | Imponibile netto manuale della riga fattura, prezzi previsti legati a revisione del piano; mai pagamento |
| Backup | `BackupCoordinator`, `BackupArchive` | V8 cifratura, V9 recovery, V10 strict preflight, V13/V14/V15 moduli ZIP; più DB non sono transazione ACID globale |

## Consolidamento implementato ora

1. `feature/attrezzi/.../MaterialIntegrityV10V15.kt`: proiezione **puramente osservativa** di riferimenti e capacità V13–V15. Controlla perimetro UUID, PDF locali accessibili, corrispondenza tipo documento, materiale/unità, cantiere opzionale, abbinamenti V14 coerenti e non sovra-allocati, importi V15 referenziati, fatture senza importi, budget e versioni piano superate. Nessuna modifica ai registri; nessun nome cliente/documento o percorso nella sintesi.
2. `feature/backup/.../DiagnosticsInspectorV11.kt`: lettura aggregata di documenti, stock, piani, V13/V14/V15 e cantieri tramite il motore sopra. Una singola scheda diagnostica esplicita errore, attenzione, dato non presente o assenza di anomalie **nei dati verificati**, non promette correttezza fiscale o completezza.
3. `feature/attrezzi/.../MaterialCostV15.kt`: conferma di un imponibile su riga V13 FATTURA rifiuta ora un collegamento con UUID cantiere eliminato/non presente. Materiali senza cantiere restano consentiti. Guard aggiuntivo al momento di una nuova dichiarazione; nessuna riscrittura di importi preesistenti.
4. `feature/attrezzi/src/test/.../MaterialIntegrityV10V15Test.kt`: casi sintetici di sorgente assente, PDF non accessibile, overbooking, costi senza fonte, budget di revisione superata, budget con unità diversa, registro vuoto. **Solo sorgenti di test, nessuna esecuzione**.

## Matrice statica dei rischi residui

| Rischio | Stato dopo consolidamento | Gate futuro (non eseguito) |
| --- | --- | --- |
| Compatibilità Kotlin/Android e risorse | Non verificabile senza compilazione | Compilare moduli Android e correggere gli eventuali errori |
| Legami UUID V10 e fonti V13–V15 | Diagnosi nuova e preflight già presenti nel codice; non collaudati | Dati sintetici con UUID orfani e riferimenti sovrapposti |
| SQL migration database V13 1→2 | Codice migrazione additiva già presente in V14; non collaudato | Installazione precedente → aggiornamento, record preservati |
| Backup ZIP e checksum | `BackupArchive.inspect` usa SHA-256; V13–V15 usano manifest, metadati e gates. Non testato | Backup completo, cifratura V8, media, checksum, backup con PDF assenti |
| Ripristino `Unisci` / `Sostituisci` | Gate V10/V13–V15 + safety snapshot; **non è transazione globale** | Fault injection, rollback, versioni legacy, dati incoerenti |
| Numeri monetari | Centesimi interi e `BigDecimal` per prezzo piano; nessun costo dedotto da OCR | 0, overflow, input decimale, unità, IVA/sconti/trasporto non attribuiti |
| Doppio conteggio FATTURA/DDT | V14 contabilizza solo overlap e capacità; non somma le serie | Abbinamenti parziali, molti-a-uno, una fattura per più cantieri |
| Affidabilità dashboard V12 | I campi sono osservazioni momentanee da archivi diversi | Dati incompleti, grandi registri, conferme in parallelo |
| Lettura V11 su dispositivo | On demand in thread dedicato; aprire helper SQLite può creare/migrare DB | Smartphone con DB legacy, permessi SAF/URI, privacy export |
| UI Materiali | Layout mobile-first esistente, ma carica molte righe da più store | Interazione su smartphone e grandi volumi, senza tempi fittizi |
| Dati senza cantiere | Permessi come record autonomi; non vengono forzati nel cantiere | Creazione e movimentazione autonoma, poi eventuale assegnazione esplicita |

## Regole di certificazione

- `CODICE GITHUB` non equivale a `COMPILE_OK`, `TEST_OK`, `COLLAUDATO`, `APK` o `DEPLOY`.
- Non sono state eseguite GitHub Actions, compilazioni, JUnit, installazioni o ripristini reali.
- Un documento locale esistente non dimostra autenticità fiscale. Un importo manuale non dimostra che sia stato pagato, né che tutte le fatture siano state registrate.
- Il rapporto diagnostico segnala anomalie rilevabili e dati mancanti; non elimina righe, non muta Spese/Compensi/Stock e non sostituisce controlli contabili o test autorizzati.
- Prima di ulteriori feature importanti, il gate prioritario rimane un collaudo esplicitamente autorizzato dell'intera catena V10–V15.
