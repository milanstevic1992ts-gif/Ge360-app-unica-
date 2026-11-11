# Come creare GE360-Rilievi-Libero-TEST.apk sul Debian JARVIS

## Obiettivo e sicurezza dei dati
Build Android **debug di test**, firmata automaticamente dalla chiave debug
locale. Il pacchetto ha applicationId **it.edilmilan.ge360.dev** e nome
**GE360 TEST Rilievi**: si installa **accanto** all'app GE360 normale,
senza sovrascriverne SQLite e file. Non richiede la disinstallazione
della versione precedente. I due archivi sono separati.

Questa build contiene GE360 App Unica, OpenPlan3D incorporato, rilievo
libero/guidato, export Android, e il flusso manuale Rilievi → Preventivi.
È una **versione da collaudare**, non una release di produzione.

## Un'unica procedura sul Debian
Aprire il terminale (l'account GitHub deve essere già autenticato):


    mkdir -p ~/repos
    cd ~/repos
    gh repo clone milanstevic1992ts-gif/Ge360-app-unica- GE360-Rilievi-APK -- --single-branch --branch feat/integrazione-rilievi-openplan3d-20261110
    cd GE360-Rilievi-APK
    bash scripts/build-apk-debian-local.sh

Se il clone di test esiste già, aprire la sua cartella ed eseguire
semplicemente:


    bash scripts/build-apk-debian-local.sh

Lo script, in ordine:
1. controlla di essere nel branch giusto e di non avere modifiche locali;
2. scarica gli aggiornamenti del SOLO branch con fast-forward;
3. installa, se mancanti, curl/unzip/Node/Python/Java;
4. prepara Android SDK 35 e Build Tools 35.0.0 (se mancanti)
   con Google Command Line Tools **15859902** e SHA256 verificato;
5. chiede esplicitamente di accettare le licenze Android;
6. esegue verifica statica e i test JavaScript;
7. esegue i test Kotlin del modulo Rilievi e la build con Gradle 8.9;
8. copia l'APK e il checksum in una cartella dedicata.

**Non** crea un workflow, non chiama GitHub Actions e non addebita
crediti di runner CI. Le prime installazioni di Gradle/SDK richiedono
Internet e diversi GB di spazio disco.

## Dove trovare l'APK
Al termine della build, cercare:

    ~/GE360-APK/GE360-Rilievi-Libero-TEST.apk

Il log completo è in:

    ~/GE360-APK/compilazione-ge360.log

Se il comando si ferma per errore, non presumere che l'APK sia valido:
controllare la parte finale del log e correggere il problema prima
dell'installazione.

## Download privato sul telefono tramite Tailscale
Con telefono e Debian collegati alla stessa tailnet, sul Debian:


    cd ~/GE360-APK
    python3 -m http.server 8765 --bind "$(tailscale ip -4)"

Dal telefono aprire nel browser:

    http://IP-TAILSCALE-DEBIAN:8765/GE360-Rilievi-Libero-TEST.apk

Sostituire IP-TAILSCALE-DEBIAN con l'indirizzo mostrato da
\`tailscale ip -4\`. Android potrebbe richiedere il permesso di
installare app da quel browser, autorizzarlo solo se il file e'
stato generato sul proprio server.

## Prima di considerarla pronta
Verificare apertura, salvataggio e riapertura di un rilievo **libero
offline**, senza misure obbligatorie; alternare libero/guidato; provare
foto e appunti. Testare poi CORS/HTTPS Tailscale del backend,
elaborazione delle superfici e trasferimento controllato alle
bozze Preventivi. Non cancellare né sovrascrivere archivi di produzione.

La CI GitHub non viene utilizzata; lo stato della PR rimane **draft**
fino alla compilazione e ai test Android.
