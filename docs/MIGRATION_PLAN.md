# Piano di migrazione verso GE360 App Unica

## Regola

Una vecchia app/repository resta intatta finché la corrispondente feature GE360 non è stata **compilata, testata con dati reali, ripristinata e installata come release firmata**. La presenza del modulo nel codice non autorizza ancora a dismettere l'app precedente.

## Stato generale — v0.21.7

- [x] repository App Unica dedicata
- [x] shell Android Kotlin multi-modulo
- [x] Home GE360
- [x] 13 moduli operativi in Home + Centro Cantiere interno
- [x] contratto UUID / `jobsiteId`
- [x] `core:backup`
- [x] `core:bridge`
- [x] Giornata con ore, tariffe per collaboratore e rapportino
- [x] snapshot tariffa dentro ogni sessione di lavoro
- [x] Attrezzi con modalità PREPARA FURGONE
- [x] Preventivi con Spese + ore + costo manodopera per persona nel margine operativo stimato
- [ ] compilazione completa verificata
- [ ] test end-to-end su telefono
- [ ] release firmata installabile verificata

## Fase 1 — Clienti

Sorgenti: `Edil-clienti`, `edil-milan-pwa`.

- [x] CRM nativo, temperatura, polso, storico, follow-up, rubrica
- [x] chiamata/WhatsApp
- [x] import/export compatibile
- [x] incluso nel backup unico
- [ ] test import/restore reale e compilazione

## Fase 2 — Cantieri

- [x] UUID GE360 + `CANT-YYYY-NNN`
- [x] più cantieri per cliente
- [x] stati, tipo lavoro, date, indirizzo, note
- [x] nodo relazionale `jobsiteId`
- [x] restore con remap Cliente tramite `clientGe360Id`
- [ ] collegamento diretto completo da ogni scheda Cliente
- [ ] test reale/compilazione

## Fase 3 — Messaggi

Sorgente: `Messaggi-`.

- [x] template SQLite, ricerca, categorie, preferiti
- [x] variabili Cliente/Cantiere
- [x] copia, WhatsApp, Share Target
- [x] import legacy e backup unico
- [ ] test reale/compilazione

## Fase 4 — Spese

Sorgente: `gestione-spese`.

- [x] Entrata/Uscita, Privato/Azienda
- [x] categorie e `jobsiteId`
- [x] riepiloghi per mese/cantiere
- [x] JSON/CSV e import `spese360.v1`
- [x] backup centralizzato
- [ ] test import reale/compilazione

## Fase 5 — Scanner

Sorgente: `GE360-Scanner-AI`.

- [x] ML Kit Document Scanner + galleria
- [x] PDF locale + OCR locale
- [x] parsing Fattura/DDT/Scontrino/Ricevuta
- [x] conferma manuale prima della Spesa
- [x] Cantiere + Uscita Spese
- [x] PDF inclusi nel backup e ricostruiti al restore
- [ ] test documenti reali/ML Kit/compilazione

## Fase 6 — Preventivi

Sorgenti: `preventivi-apk-beta1`, `Generatore-preventivi-beta-1`.

- [x] preventivi nativi + voci + prezzario
- [x] UUID + `PREV-YYYY-NNN`
- [x] Cliente/Cantiere
- [x] misure/corpo/forfait, sconto, maggiorazione, arrotondamento
- [x] import backup legacy
- [x] confronto con Spese
- [x] ore reali Giornata per Cantiere
- [x] dettaglio manodopera per collaboratore
- [x] costo storico basato sulla tariffa snapshot della singola sessione
- [x] ore senza tariffa evidenziate separatamente
- [x] margine operativo stimato = preventivo - Spese - manodopera valorizzata
- [x] avviso possibile doppio conteggio collaboratori
- [x] backup unico + remap Cliente UUID
- [ ] configuratore avanzato legacy completo
- [ ] test economico con cantieri reali
- [ ] compilazione

## Fase 7 — Attrezzi & Materiali

Sorgente: `Attrezzi-apk-`.

- [x] catalogo locale
- [x] preparazione per Cantiere
- [x] richieste e task squadra
- [x] stati operativi/acquisto
- [x] hub Attrezzi con Gestione completa / PREPARA FURGONE
- [x] modalità Furgone con CARICATO / MANCA / COMPRA / CANTIERE / RIENTRO
- [x] ordinamento operativo mancanti/priorità
- [x] backup unico senza nuove tabelle dedicate al Furgone
- [ ] import legacy verificato
- [ ] lista aggregata multi-cantiere magazzino/furgone
- [ ] QR/barcode opzionale
- [ ] test reale/compilazione

## Fase 8 — Galleria

Sorgente: `EDIL-GALERIA`.

- [x] riuso Cantieri GE360
- [x] PRIMA / DURANTE / DOPO / PROBLEMI / DOCUMENTI
- [x] fotocamera + import multiplo
- [x] archivio principale SAF telefono
- [x] note, tag, preferiti, SHA-256
- [x] Drive come copia secondaria
- [x] backup completo opzionale con fotografie
- [x] restore file in nuova cartella SAF
- [ ] test archivio/Drive su molte foto
- [ ] migrazione legacy reale
- [ ] compilazione

## Fase 9 — Fisco

Sorgente: `edil-fisco-hub`.

- [x] motore Kotlin separato dalla UI
- [x] dati Azienda da Spese
- [x] parametri fiscali 2026 centralizzati
- [x] Forfettario/Ordinario, INPS, IRPEF, addizionali, soglie
- [x] stima deducibilità/IVA prudente
- [x] break-even e report
- [ ] rettifica IVA art. 19-bis2
- [ ] scadenzario/acconti completo
- [ ] dati fiscali più ricchi in Spese/Scanner
- [ ] test contro FiscoHub e casi manuali
- [ ] compilazione

## Fase 10 — Backup & Sincronizzazione

- [x] `core:backup` ZIP + SHA-256
- [x] `feature:backup` orchestratore
- [x] backup automatico giornaliero
- [x] copia interna + cartella persistente telefono SAF + Drive SAF
- [x] ultimi 10 su destinazioni persistenti
- [x] PDF Scanner
- [x] backup completo fotografie
- [x] sessioni ore + rapportini Giornata
- [x] tariffe collaboratori Giornata (`laborRates`)
- [x] tariffa snapshot dentro ogni sessione (`hourlyRate`)
- [x] compatibilità import Giornata formatVersion 2 → 3
- [x] restore Unisci/Sostituisci
- [x] remap ID locali tramite UUID GE360
- [x] compatibilità restore con backup precedenti a Giornata
- [x] versione backup derivata a runtime dalla versione installata
- [ ] test restore su secondo telefono/profilo
- [ ] spazio insufficiente / pacchetti molto grandi
- [ ] eventuale cifratura portabile ZIP
- [ ] compilazione

## Fase 11 — Marketing

Sorgente server: `Ge360-Marketing`.

- [x] modulo nativo `feature:marketing`
- [x] `core:bridge` condiviso
- [x] configurazione URL + token opzionale Keystore
- [x] health server
- [x] bootstrap ultimi 30 giorni
- [x] KPI / lead / ricavo attribuito / CPL / ROAS
- [x] SEO / tracking / stato connettori
- [x] cache dell'ultima risposta reale; nessun dato demo operativo
- [x] apertura dashboard web
- [x] azioni proposte con conferma prima di `approve`
- [x] richiesta consiglio IA
- [ ] calendario editoriale nativo completo nell'APK
- [ ] test contro server Marketing reale
- [ ] compilazione

## Fase 12 — Jarvis / IA

Sorgente server: `GE360-Jarvis`.

- [x] modulo nativo `feature:jarvis`
- [x] health + capabilities protocollo v2
- [x] sessione Jarvis locale
- [x] `POST /api/jarvis/text`
- [x] `dryRun=true` obbligatorio nel primo passaggio
- [x] visualizzazione intent/confidenza/azione proposta
- [x] `dryRun=false` solo dopo conferma esplicita
- [x] contesto GE360 aggregato opzionale senza dati identificativi automatici
- [x] nessun accodamento automatico di comandi esecutivi
- [ ] chat cronologica persistente
- [ ] integrazione eventi/outbox protocollo v2
- [ ] test contro backend Jarvis reale
- [ ] compilazione

## Fase 13 — Giornata di Cantiere

- [x] modulo nativo `feature:giornata`
- [x] selezione Cantiere tramite UUID `jobsiteId`
- [x] timer persistente con `startedAt` / `endedAt`
- [x] una sola sessione attiva
- [x] collaboratore memorizzato localmente
- [x] totale ore giornaliero e totale storico per Cantiere
- [x] tariffa corrente distinta per collaboratore, predefinita 0
- [x] snapshot `hourlyRate` nella singola sessione
- [x] costo storico non modificato da future variazioni tariffa
- [x] riepilogo ore/costi per collaboratore
- [x] ore senza tariffa rilevate separatamente
- [x] migrazione SQLite Giornata v1 → v2
- [x] JSON export/import Giornata `formatVersion 5`
- [x] compatibilità backup precedente con `hourlyCost` globale
- [x] rapportino unico per Cantiere/data
- [x] fatto oggi / prossimi passi / problemi / materiale-attrezzi / note
- [x] stato rapportino chiuso
- [x] storico rapido
- [x] condivisione Android Share Sheet
- [ ] test timer dopo kill processo e riavvio
- [ ] test sessione aperta durante cambio data
- [ ] test variazione tariffa senza alterare sessioni storiche
- [ ] firma cliente / consegna lavoro
- [ ] compilazione

## Miglioramenti architetturali successivi alla build stabile

I repository Android maturi analizzati separano maggiormente feature, dati e navigazione. GE360 deve arrivarci in modo incrementale, senza una riscrittura completa.

Ordine consigliato:

1. mantenere build e test di regressione verdi;
2. test end-to-end dei 13 moduli;
3. estrazione progressiva in `core:data` / `core:model`;
4. navigazione condivisa per ridurre dipendenze dirette fra feature;
5. migrazione graduale delle tabelle più critiche a Room;
6. importi finanziari verso centesimi interi/decimal model invece di `Double` nei flussi nuovi.

## Sicurezza servizi remoti

- [x] token Bridge cifrato con Android Keystore
- [x] token escluso dai backup
- [x] credenziali provider restano server-side
- [x] HTTP locale richiede opt-in esplicito
- [x] nessuna approvazione Marketing automatica
- [x] nessuna esecuzione Jarvis automatica

## Repository da non fondere dentro l'APK

- `GE360-Jarvis` / `Jarvis`: backend e servizi;
- `Ge360-Marketing` / `Ge360-Marketing-`: backend/dashboard;
- `socialintel-llama-social-os`: servizio AI marketing;
- `edil-milan-stevic-wp`: sito web;
- `Alex-Garage`: progetto indipendente.

## GitHub Actions

Non aggiungere workflow automatici alla repository App Unica durante la migrazione iniziale. La prima Action di build verrà aggiunta solo su richiesta esplicita.
