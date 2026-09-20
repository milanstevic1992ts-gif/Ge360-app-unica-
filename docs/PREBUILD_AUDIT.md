# GE360 — Audit build v0.21.7

Data aggiornamento: 2026-09-02

Questo documento conserva i controlli storici e descrive lo stato corrente della build. L'installabilità finale richiede ancora firma canonica e prova su telefono.

## Toolchain fissata

- Android Gradle Plugin `8.7.3`
- Kotlin Android `2.0.21`
- Gradle `8.9`
- Java/Kotlin target `17`
- compileSdk / targetSdk `35`
- minSdk `26`
- applicationId `it.edilmilan.ge360`
- app `0.21.7` / versionCode `2108`

Sono presenti `gradlew`, `gradlew.bat` e `gradle/wrapper/gradle-wrapper.properties`; il bootstrap Gradle 8.9 usa checksum SHA-256.

## Moduli registrati

### Core

- `core:common`
- `core:backup`
- `core:bridge`

### Feature

- `feature:home`
- `feature:clienti`
- `feature:cantieri`
- `feature:centrocantiere`
- `feature:giornata`
- `feature:messaggi`
- `feature:spese`
- `feature:scanner`
- `feature:preventivi`
- `feature:attrezzi`
- `feature:galleria`
- `feature:fisco`
- `feature:backup`
- `feature:marketing`
- `feature:jarvis`

La Home espone 13 moduli operativi; Centro Cantiere è un hub interno raggiunto da Cantieri.

## Controlli statici eseguiti

- [x] `settings.gradle.kts` registra Giornata e tutti gli altri moduli;
- [x] nessun ciclo Gradle evidente;
- [x] `feature:giornata → feature:cantieri`;
- [x] `feature:preventivi → feature:giornata + feature:spese + feature:cantieri` senza dipendenza inversa;
- [x] `feature:backup` legge Giornata senza creare ciclo;
- [x] Giornata passa DB `v1 → v2` con `ALTER TABLE work_sessions ADD COLUMN hourly_rate REAL NOT NULL DEFAULT 0`;
- [x] il vecchio costo globale v0.14 viene migrato come tariffa Milan e snapshot delle sessioni storiche quando disponibile;
- [x] ogni nuova WorkSession fotografa la tariffa corrente del collaboratore;
- [x] variazioni future della tariffa non riscrivono il costo delle sessioni precedenti;
- [x] `modules/giornata.json` usa `formatVersion 5` con `laborRates` e `hourlyRate` per sessione;
- [x] import Giornata mantiene compatibilità con `formatVersion 2` / `hourlyCost` globale;
- [x] Preventivi usa il costo delle singole sessioni, non una tariffa globale corrente;
- [x] Preventivi mostra dettaglio manodopera per collaboratore e ore senza tariffa;
- [x] avviso esplicito contro doppio conteggio dei collaboratori già presenti in Spese;
- [x] Attrezzi registra `ToolsHubActivity`, `VanModeActivity`, `ToolsActivity` con `exported=false`;
- [x] PREPARA FURGONE riusa `JobResource` esistenti e non richiede nuova migrazione DB;
- [x] Scanner e Galleria mantengono FileProvider distinti;
- [x] Backup usa SHA-256 e versione runtime;
- [x] Marketing/Jarvis usano `core:bridge` e token Keystore;
- [x] workflow GitHub Actions attivo: build release, verifica struttura e pubblicazione artefatto non firmato.

## Punto sensibile — migrazioni Giornata

La migrazione SQLite v1 → v2 è semplice e additiva, ma resta **da provare realmente** su un database creato dalla v0.14.

Casi da testare:

1. database v1 con costo globale > 0;
2. database v1 con costo globale = 0;
3. sessioni già chiuse;
4. sessione ancora aperta durante aggiornamento app;
5. backup Giornata formatVersion 2 importato nella versione corrente;
6. backup formatVersion 3–5 con più collaboratori e tariffe differenti;
7. modifica tariffa corrente dopo sessioni storiche, verificando che il costo storico non cambi.

## Primo tentativo di compilazione locale — 2026-08-29

È stato autorizzato un tentativo locale di `assembleDebug`, ma il processo si è fermato prima dell'avvio di Gradle perché l'ambiente disponibile non contiene Android SDK/`sdkmanager`/`adb` né cache dipendenze utilizzabile.

Java disponibile: OpenJDK 21. Android SDK 35: assente. `ANDROID_HOME` / `ANDROID_SDK_ROOT`: non configurati.

Quindi non esiste ancora un risultato `BUILD SUCCESSFUL` o `BUILD FAILED` dell'app: il compilatore Android non è stato eseguito.

Non sono state create GitHub Actions per aggirare il blocco.

## Bootstrap Gradle Wrapper rotto — trovato e corretto il 2026-08-31

`gradlew` e `gradlew.bat` erano stati scritti a mano per scaricare `gradle-8.9-wrapper.jar` da `https://services.gradle.org/distributions/gradle-8.9-wrapper.jar`. Questo URL **non esiste** (404): Gradle non pubblica il wrapper jar come file scaricabile a sé stante, solo come parte della distribuzione completa (`gradle-8.9-bin.zip`). Il jar `gradle/wrapper/gradle-wrapper.jar` corretto va generato con il task ufficiale `gradle wrapper` ed essere versionato nel repository, non scaricato a runtime.

Effetto pratico: qualunque `./gradlew`, anche con Android SDK 35 installato, falliva subito con `curl: 404` prima di avviare Gradle. Bloccava totalmente la prima build.

Correzione applicata:

- rigenerati `gradlew`, `gradlew.bat` e `gradle/wrapper/gradle-wrapper.jar` con il meccanismo wrapper ufficiale (`gradle wrapper --gradle-version 8.9 --distribution-type bin`), puntando comunque a `gradle-8.9-bin.zip` (URL valido) con lo stesso `distributionSha256Sum` già presente nel repo;
- il `gradle-wrapper.jar` generato è ora committato nel repository, come previsto dallo standard Gradle.

## Prima compilazione completa riuscita — 2026-08-31

Con Android SDK 35 disponibile in locale e il wrapper corretto:

```
./gradlew --no-daemon :app:assembleDebug --stacktrace
```

`BUILD SUCCESSFUL in 13m 52s` — 358 task eseguiti, nessun errore. Uniche segnalazioni: warning di deprecazione Kotlin su `startActivityForResult`/`onActivityResult` in `clienti`, `cantieri`, `galleria`, `attrezzi`, `messaggi`, `spese`, `preventivi`, `scanner`, `backup` (già noti e accettati temporaneamente, vedi sezione "Compatibilità accettata temporaneamente"), e un avviso di `stripDebugDebugSymbols` che non riesce a strippare `libmlkit_google_ocr_pipeline.so` (impacchettata così com'è, non blocca la build).

APK debug generata correttamente in `app/build/outputs/apk/debug/`. Restano da fare i test funzionali reali su dispositivo elencati più sotto in "Da verificare su telefono".

## Compatibilità accettata temporaneamente

Sono presenti API deprecate ma ancora valide (`startActivityForResult`, `onActivityResult` ecc.). Verranno modernizzate dopo una baseline APK stabile.

Il persistence layer è ancora prevalentemente `SQLiteOpenHelper`; la migrazione a Room deve essere incrementale dopo la prima build stabile.

Gli importi finanziari usano ancora `Double` in moduli legacy/migrati: nei futuri flussi contabili è consigliato passare a centesimi interi/decimal model.

## Da verificare solo con compilazione Android

- risoluzione Maven;
- compatibilità finale Kotlin/AGP;
- compilazione della nuova migrazione Giornata v2;
- merge manifest;
- risorse `R`;
- packaging ML Kit/FileProvider;
- DEX;
- dimensione APK;
- eventuali errori non rilevabili staticamente.

## Da verificare su telefono

- timer Giornata dopo kill processo e cambio data;
- tariffe diverse per Milan/Dragan/Bojan o altri collaboratori;
- snapshot tariffa invariato dopo modifica della tariffa corrente;
- ore senza tariffa evidenziate correttamente;
- margine Preventivi con più collaboratori;
- PREPARA FURGONE con liste reali;
- Scanner ML Kit/OCR;
- rubrica;
- SAF locale/Drive;
- fotocamera Galleria;
- apertura PDF;
- backup JobScheduler e restore su secondo dispositivo;
- Marketing/Jarvis reali;
- installazione/aggiornamento release firmata.

## Prima build — ordine

1. ambiente con Android SDK 35;
2. `./gradlew --no-daemon :app:assembleDebug --stacktrace`;
3. correzione errori fino a build pulita;
4. ispezione APK: package, versione, manifest, DEX, risorse e dimensione;
5. installazione debug;
6. test migrazione Giornata v1 → v2;
7. test end-to-end;
8. firma release canonica solo dopo baseline stabile;
9. GitHub Actions soltanto su autorizzazione esplicita.

## Regola di stato

**Moduli presenti nel codice != APK firmata e verificata su telefono.**

Lo stato diventa “build verificata” solo dopo compilazione reale riuscita e ispezione dell'APK.
