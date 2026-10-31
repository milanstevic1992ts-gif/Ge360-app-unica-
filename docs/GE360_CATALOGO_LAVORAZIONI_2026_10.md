# GE360 — Catalogo lavorazioni e assegnazione alle superfici (31/10/2026)

## Scopo

Preventivi e Rilievi restano due moduli autonomi. Un preventivo nasce anche senza disegno, un rilievo può esistere senza preventivo e un lavoro può essere associato a una parete, a una stanza o a più ambienti solo quando serve.

## Stato del catalogo

- **87 voci tecniche** in `StandardWorkCatalog.kt`, codici `CAP-01`–`CAP-87`.
- `CAP-01`–`CAP-42` non sono stati rinumerati: i preventivi esistenti non cambiano.
- `CAP-43`–`CAP-87` rendono più granulari carta da parati, profili, rasatura armata, Q3/Q4, crepe superficiali, pitture, SPC/LVT, battiscopa, massetti, cartongesso, zona doccia, piastrelle, terrazzi e ripristini.
- Descrizione tecnica distinta dal prezzo. Le nuove voci **non impostano prezzi automaticamente** e non alterano il prezzario personale.
- Il catalogo è ricercabile per testo/gruppo dal modulo Preventivi. Alcune voci frequenti sono state aggiunte come opzioni non preselezionate nelle checklist Bagno, Cucina, Stanza, Balcone e Altro.
- Lavorazioni aggiuntive fuori catalogo rimangono possibili come voci libere.

## Selezione futura da planimetria

Il nuovo `WorkScopeCatalog.kt` definisce i target compatibili e la proposta di quantità, senza dipendenza dal repository Rilievi:

| Selezione | Esempio | Quantità prevista |
|---|---|---|
| Parete singola | Rasatura armata | Area della parete selezionata in m² |
| Pareti della stanza | Pittura | Totale superfici pareti in m² |
| Soffitto | Controsoffitto | Superficie soffitto in m² |
| Pavimento | Posa SPC | Superficie del pavimento in m² |
| Perimetro | Battiscopa | Perimetro della stanza in ml |
| Tratto lineare | Sigillatura doccia | Lunghezza reale del tratto in ml |
| Elemento | Botola, profilo soglia | Numero di elementi |
| Intero ambiente | Protezioni / prezzo a corpo | 1 corpo |
| Manuale | Qualsiasi voce prevista | Quantità verificata dall'operatore |

Il collegamento al disegno è **facoltativo**: `WorkTargetReference` conserva tipo, ID esterno dell'oggetto ed eventuale ID rilievo, senza obbligare un preventivo a nascere da una planimetria.

Una quantità geometrica non disponibile, nulla, negativa o non finita genera `null`, non 1 m² inventato. Gli importi vengono definiti esclusivamente nel preventivo; i rilievi non calcolano prezzi né creano lavori automaticamente.

## Regole progettuali da mantenere nella fase successiva

1. L'utente seleziona **muro**, **tutta la stanza**, **pavimento**, **soffitto** o **intero immobile**, oppure lavora senza disegno.
2. GE360 mostra solo le lavorazioni compatibili, con ricerca e checklist rapida per Bagno e Cucina.
3. Ogni assegnazione salva l'ID di origine, la misura proposta e l'eventuale correzione manuale; la modifica del disegno non sovrascrive silenziosamente un preventivo già confermato.
4. Se una stessa area è selezionata più volte, il programma deve segnalare possibili doppioni senza vietare due cicli distinti sullo stesso muro.
5. Una lavorazione può essere usata in più stanze, ma deve risultare sempre chiaro **dove** viene eseguita e **quale misura** è stata scelta.
6. Prezzo a corpo e prezzo a misura restano configurabili indipendentemente per ambiente; inclusioni, esclusioni e optional sono espliciti.
7. Collegare, scollegare o cancellare una planimetria non deve eliminare automaticamente il preventivo o le voci collegate.

## Cosa è già implementato in questo branch

- 45 nuove voci consultabili nel catalogo Preventivi;
- checklist ampliate senza attivazioni forzate;
- regole pure `WorkScopeCatalog` per target e quantità senza invenzione delle misure;
- test unitari dedicati.

## Cosa NON è ancora implementato

- tap diretto su muro/pavimento nel frontend Rilievi;
- finestra di selezione target nell'editor Preventivi e salvataggio di assegnazioni target a singola voce;
- sincronizzazione delle geometrie dal backend Debian con consenso sull'aggiornamento delle quantità;
- collaudo Android e integrazione su due dispositivi.

## Verifica locale prevista

`./gradlew :feature:preventivi:testDebugUnitTest`

Il lavoro resta sul branch `feat/ge360-catalogo-lavorazioni-2026`. Nessun merge su `main`, APK o deploy sul Debian di produzione in questa fase.
