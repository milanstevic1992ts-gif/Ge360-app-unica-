# GE360 App Unica

Applicazione Android unica per l'ecosistema **GE360 / EDIL MILAN STEVIC**, progettata come app modulare e offline-first.

## Stato attuale — v0.21.25

La Home espone **13 moduli operativi**:

1. `feature:clienti` — CRM, temperatura, polso, follow-up, rubrica e backup;
2. `feature:cantieri` — UUID GE360, `CANT-YYYY-NNN`, stati e nodo centrale `jobsiteId`;
3. `feature:giornata` — timer ore persistente, tariffe per collaboratore, rapportino e storico;
4. `feature:preventivi` — prezzario, voci, Cliente/Cantiere, Spese, ore e margine operativo stimato;
5. `feature:spese` — entrate/uscite, Privato/Azienda, categorie e costi per cantiere;
6. `feature:scanner` — ML Kit, PDF locale, OCR, FIR e creazione Uscita Spese;
7. `feature:attrezzi` — gestione completa, **PREPARA FURGONE** e archivio **Scontrini garanzia**;
8. `feature:galleria` — PRIMA/DURANTE/DOPO/PROBLEMI/DOCUMENTI, SAF locale e copia Drive;
9. `feature:messaggi` — template, variabili Cliente/Cantiere, WhatsApp e Share Target;
10. `feature:fisco` — confronto Forfettario/Ordinario 2026 alimentato da Spese;
11. `feature:backup` + `core:backup` — ZIP GE360, checksum, ultimi 10, telefono persistente, Drive e restore;
12. `feature:marketing` — KPI, lead, SEO, tracking, azioni proposte e dashboard server;
13. `feature:jarvis` — health/capabilities, analisi `dryRun`, proposta e azione solo dopo conferma.

`core:bridge` è il client condiviso per Marketing/Jarvis: URL separati, protocollo GE360 v2, request id e token opzionale cifrato con Android Keystore.

> **13 moduli visibili nella Home.** Centro Cantiere è un hub interno aperto da Cantieri. La build release viene verificata da GitHub Actions; i test reali sul telefono restano obbligatori.

## Stato build

Toolchain fissata:

- Android Gradle Plugin `8.7.3`;
- Kotlin Android `2.0.21`;
- Gradle `8.9`;
- JDK target `17`;
- compile/target SDK `35`;
- min SDK `26`.

Sono presenti `gradlew`, `gradlew.bat` e `gradle/wrapper/gradle-wrapper.properties`. L'audit statico è in `docs/PREBUILD_AUDIT.md`.

## Flusso operativo centrale

`Cliente → Cantiere → Giornata → Preventivo → Scanner → Spese → Attrezzi → Galleria → Messaggi`

Tutti i dati operativi ruotano attorno al `jobsiteId` UUID stabile del Cantiere.

## Giornata e costo manodopera

Giornata registra:

- Cantiere;
- collaboratore;
- `INIZIA LAVORO / FINE LAVORO`;
- sessione persistente;
- ore reali;
- **tariffa interna distinta per collaboratore**;
- tariffa fotografata dentro la singola sessione;
- fatto oggi / prossimi passi / problemi / materiale-attrezzi / note;
- rapportino condivisibile.

Ogni collaboratore parte da `0 €/h`: GE360 non inventa costi. Quando parte un timer, la tariffa corrente di quella persona viene copiata nella `WorkSession`. Se in futuro la tariffa cambia, le ore già registrate mantengono il costo storico originale.

Esempio:

- Milan: 25 €/h;
- Dragan: 22 €/h;
- Bojan: 28 €/h.

Le sessioni vengono valorizzate individualmente e poi sommate per Cantiere.

## Preventivi — economia cantiere

Preventivi mostra separatamente:

- valore preventivo;
- costi registrati in Spese;
- ore Giornata;
- dettaglio ore/costo per collaboratore;
- manodopera valorizzata;
- ore senza tariffa;
- costi conosciuti;
- **margine operativo stimato**.

Formula:

`margine operativo stimato = valore preventivo - Spese - manodopera valorizzata da timer`

Se esistono ore a tariffa `0`, la schermata le segnala esplicitamente: il margine è quindi ancora incompleto e presumibilmente più alto del reale.

Non è utile netto. Imposte, costi indiretti, ammortamenti e altri costi restano separati. Se un collaboratore è già contabilizzato in Spese e vengono valorizzate anche le sue ore, va evitato il doppio conteggio.

## Attrezzi — PREPARA FURGONE

L'ingresso Attrezzi apre un hub con:

- **PREPARA FURGONE**;
- Gestione completa;
- **Scontrini garanzia**.

La modalità Furgone usa gli stessi `JobResource` della gestione completa e permette aggiornamenti con un tap:

- `CARICATO` → In furgone;
- `MANCA` → Mancante;
- `COMPRA` → Da comprare;
- `CANTIERE` → In cantiere;
- `RIENTRO` → Rientrato.

Mancanti e materiali da comprare vengono portati in cima alla lista. Funziona offline.

### Scontrini garanzia attrezzi

Dal relativo riquadro nel modulo Attrezzi è possibile:

- scansionare uno scontrino multipagina con ML Kit;
- salvare nome e tipo dell'attrezzo;
- conservare e ricercare la data d'acquisto;
- registrare negozio e importo, se disponibili;
- aprire o eliminare il PDF dall'archivio dedicato.

I PDF sono salvati in `GE360/Scontrini garanzia/<ANNO>/` con nomi leggibili e ID stabile.
La stessa cartella contiene anche gli indici `indice-scontrini-garanzia.json` e
`indice-scontrini-garanzia.txt`. Metadati e PDF entrano nel backup GE360 e vengono
ricostruiti nella cartella corretta dopo il ripristino.

## Marketing

La feature nativa usa i contratti del server `Ge360-Marketing` e legge `/health`, `/dashboard/bootstrap`, `/actions` e `/ai/recommend`.

Mostra solo dati reali o cache reale. Le azioni richiedono conferma.

## Jarvis / IA

Flusso:

`richiesta → dryRun=true → intent/azione proposta → conferma → dryRun=false`

Il contesto locale opzionale contiene solo aggregati e non invia automaticamente dati identificativi, OCR, PDF o fotografie.

## Backup

Il backup `.ge360.zip` include tutti gli archivi operativi: dati dei moduli, ambienti e cronologia Preventivi, personalizzazioni, pacchetti Attrezzi/Materiali, preparazioni Furgone, scontrini garanzia con nome/tipo/data e PDF, ore, rapportini, tariffe collaboratori, PDF Scanner/FIR e fotografie leggibili nel backup automatico completo.

`modules/giornata.json` è ora `formatVersion 5`. I backup Giornata precedenti restano importabili: il vecchio `hourlyCost` globale viene migrato su Milan e applicato alle vecchie sessioni secondo la logica usata dalla v0.14.

Destinazioni:

- copia di lavoro interna;
- cartella persistente telefono via SAF;
- Google Drive via SAF.

Le destinazioni persistenti mantengono gli ultimi 10 backup. Ogni voce ZIP ha SHA-256.

I backup precedenti all'introduzione di Giornata restano ripristinabili: se `modules/giornata.json` manca, il restore prosegue con un avviso.

## Sicurezza servizi remoti

Credenziali Meta, Google, WordPress, n8n, PostgreSQL, Redis e provider IA restano server-side. I token Bridge non entrano nei backup.

HTTP LAN richiede opt-in esplicito; HTTPS resta preferibile.

## Documentazione principale

- `docs/ARCHITECTURE.md`
- `docs/CANTIERI_DATA_MODEL.md`
- `docs/GIORNATA_DATA_MODEL.md`
- `docs/SPESE_DATA_MODEL.md`
- `docs/SCANNER_DATA_MODEL.md`
- `docs/PREVENTIVI_DATA_MODEL.md`
- `docs/ATTREZZI_DATA_MODEL.md`
- `docs/GALLERIA_DATA_MODEL.md`
- `docs/FISCO_DATA_MODEL.md`
- `docs/BACKUP_DATA_MODEL.md`
- `docs/REMOTE_SERVICES.md`
- `docs/PREBUILD_AUDIT.md`
- `docs/MIGRATION_PLAN.md`

## Verifiche prima della distribuzione

Già preparato:

- Gradle 8.9 bootstrap + checksum;
- struttura modulare completa, incluso Centro Cantiere e FIR;
- UUID / `jobsiteId`;
- backup unico;
- modalità Furgone;
- ore reali e tariffa per collaboratore;
- snapshot tariffa in ogni sessione;
- manodopera per persona nel controllo economico;
- audit statico principale.

Da verificare realmente:

- compilazione completa Android a ogni modifica;
- migrazione DB Giornata v1 → v2;
- errori Kotlin/Android rilevati dal compilatore;
- Cliente → Cantiere → Giornata → Preventivo → Spese;
- timer dopo kill processo/cambio data;
- tariffe multiple e margine su casi reali;
- restore Giornata dalle versioni precedenti al `formatVersion 5`;
- modalità Furgone su cantiere reale;
- Scanner ML Kit/OCR/PDF;
- Galleria fotocamera/SAF/Drive;
- backup automatico, rotazione 10 e restore su secondo dispositivo;
- Fisco contro casi manuali;
- Marketing/Jarvis contro server reali;
- firma release canonica GE360;
- verifica APK finale: package, versione, firma, contenuto e dimensione.

## Identità Android

- Application ID: `it.edilmilan.ge360`
- versione: `0.21.10` / versionCode `2111`
- minSdk: 26
- targetSdk: 35
- Java/Kotlin target: 17

## GitHub Actions

Il workflow `Build GE360 APK` compila la release solo quando cambia il marcatore di build dedicato o su avvio manuale. Pull request e push ordinari eseguono invece compilazione debug e test, senza generare APK. L'artefatto release resta non firmato: la firma finale deve usare esclusivamente la chiave canonica GE360 conservata fuori da Git.
