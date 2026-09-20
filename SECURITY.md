# Sicurezza GE360

## Non committare mai

- keystore `.jks` / `.keystore`;
- password della firma;
- API key;
- token n8n;
- token Jarvis/Bridge;
- credenziali Google/Meta/WordPress;
- backup con dati reali di clienti;
- documenti, fatture, fotografie o database reali.

## Firma

La chiave privata canonica GE360 deve restare fuori dal repository. Quando verrà configurata la release firmata, il materiale di firma dovrà essere fornito esclusivamente tramite meccanismi sicuri e non dovrà essere scritto nel codice o nei commit.

## Dati locali

GE360 è offline-first. I dati sensibili devono essere conservati nello storage privato dell'app o in cartelle esplicitamente autorizzate dall'utente tramite Android Storage Access Framework.

## Bridge Marketing / Jarvis

`core:bridge` salva gli URL dei servizi separatamente. L'eventuale token Bearer viene cifrato con una chiave AES/GCM generata in Android Keystore.

- il token non viene inserito nei backup GE360;
- il token non viene inviato a provider terzi diversi dal servizio configurato;
- nessuna credenziale Meta, Google, WordPress o n8n viene memorizzata nella feature Marketing;
- Jarvis usa `dryRun=true` come percorso iniziale;
- `dryRun=false` richiede conferma esplicita dell'utente;
- le approvazioni Marketing richiedono selezione + conferma esplicita.

## HTTP locale

Per compatibilità con servizi LAN esistenti il manifest Android consente tecnicamente traffico cleartext. Il client GE360 lo blocca comunque a livello applicativo: un URL `http://` viene rifiutato finché l'utente non abilita esplicitamente **Consenti HTTP locale non cifrato** per quel servizio.

HTTPS resta preferibile. Non usare HTTP su reti non fidate.

## Integrazioni

Jarvis, n8n, Marketing e provider IA non devono ricevere dati automaticamente senza una funzione esplicitamente configurata. Il contesto Jarvis locale opzionale contiene solo aggregati e non include automaticamente nomi, telefoni, email, indirizzi, documenti, OCR o fotografie.
