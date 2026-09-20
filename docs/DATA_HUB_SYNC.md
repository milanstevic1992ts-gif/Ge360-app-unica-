# GE360 — Data Hub Sync Android → Linux

Data stato: 2026-09-01

## Stato

Il precedente gap `App Unica → GE360-Backend` è ora coperto lato codice da `DataHubSyncCoordinator`.

Il flusso implementato è:

`Store locali Android → exportJson() → normalizzazione snapshot → /api/v1/sync/state → /api/v1/sync/push → GE360-Backend → Today Hub / Jarvis / Control Center Linux`

Questa fase è **push-first**. Non applica automaticamente snapshot server ai database locali Android.

## Moduli sincronizzati

- clienti
- cantieri
- centrocantiere
- jobsite_archive
- giornata
- messaggi
- spese
- scanner
- fir
- preventivi
- preventivi_rooms
- preventivi_lifecycle
- preventivi_customizations
- planimetrie
- attrezzi
- attrezzi_custom_packs
- galleria
- fisco_settings

La preparazione Furgone è inclusa nello snapshot `attrezzi` per compatibilità con la proiezione Today del backend.

## Attrezzi e preparazione furgone

`ToolsStore.exportJson()` resta la sorgente principale per catalogo, `jobResources`, richieste e task.

Il coordinatore aggiunge allo snapshot `attrezzi` la collezione `vanPreparations` letta da `VanPreparationStore`.

Campi inviati:

- id
- jobsiteId
- jobsiteCode
- jobsiteTitle
- date
- note
- toolCount
- materialCount
- missingCount
- completed
- updatedAt

Questo permette al backend di proiettare una preparazione furgone dentro `GET /api/v1/today` senza deduzioni lato browser.

## Revisione e conflitti

Il backend mantiene `serverRevision` per modulo.

Prima sincronizzazione del dispositivo:

- l'app legge `/api/v1/sync/state`;
- pubblica lo snapshot locale soltanto se il modulo server è ancora alla revisione zero;
- se il server contiene già dati, blocca l'invio e richiede una riconciliazione esplicita.

Sincronizzazioni successive:

- la revisione confermata viene salvata localmente;
- se il server ha una revisione diversa da quella conosciuta, il modulo viene bloccato;
- nessun `force=true` viene usato automaticamente;
- viene mostrato un avviso di conflitto.

## Snapshot stabili

Gli `exportJson()` del backup contengono spesso `exportedAt` con il timestamp della serializzazione.

Quel campo viene rimosso dal `payload` inviato al Data Hub: in caso contrario ogni apertura dell'app produrrebbe un checksum diverso e una revisione server nuova anche con dati identici.

`clientUpdatedAt` conserva comunque il timestamp dell'esportazione come metadato fuori dal payload.

## Trigger Android

La sincronizzazione parte solo dal comando `Sincronizza Data Hub ora` nella sezione Sistema. Conflitti ed errori vengono mostrati all'utente, mentre lo stato completo resta nelle preferenze locali del Data Hub.

Il flusso operativo è:

1. dalla Home si apre Cantieri / Giornata / Attrezzi;
2. si modificano i dati localmente;
3. si torna alla Home;
4. si avvia consapevolmente la sincronizzazione, eseguita in un thread separato.

Esiste un throttle breve per evitare richieste duplicate ravvicinate.

## Configurazione rete

Per questa prima fase Data Hub riusa la configurazione `BridgeService.JARVIS`, perché Jarvis e Sync sono endpoint dello stesso `GE360-Backend`.

Sono quindi riusati:

- base URL;
- Bearer token cifrato Android Keystore;
- consenso esplicito per HTTP locale;
- header protocollo GE360 v2.

## Verifiche ancora obbligatorie

Da `0.21.6` il Data Hub non parte più automaticamente all'apertura dell'app. L'invio è manuale e viene bloccato quando un dispositivo nuovo o reinstallato incontra revisioni server già esistenti, evitando sovrascritture alla cieca. Restano obbligatorie le prove end-to-end con il backend Debian aggiornato.

Prima di considerare il sync verificato end-to-end servono:

1. build Android/CI riuscita;
2. backend Debian aggiornato;
3. configurazione URL/token nell'app;
4. sync iniziale su backend di test;
5. controllo `/api/v1/sync/state`;
6. modifica reale `Aspirapolvere → Da portare` con preparazione datata;
7. ritorno alla Home;
8. verifica `GET /api/v1/today` sul Linux;
9. secondo sync senza modifiche e verifica `unchanged`;
10. simulazione conflitto aumentando la revisione server da un altro client.

## Fase successiva

Il passo successivo del Sync Engine è il **pull sicuro Linux → Android**. Prima di abilitarlo occorre definire merge/deletion semantics per ogni modulo; non va implementato come semplice `replaceAll` automatico perché potrebbe sovrascrivere modifiche locali offline.
