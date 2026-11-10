# GE360 App Unica — integrazione Rilievi avanzati (OpenPlan3D)

## Sorgenti e stato

- Frontend: https://github.com/milanstevic1992ts-gif/ge360-open-plan3d
- Snapshot esatto frontend: commit 93d6cec9019e49b8252d232d7cb42b0a0c9043cd
- Backend: https://github.com/milanstevic1992ts-gif/ge360-rilievi-backend-
- App di destinazione: feature/planimetrie di GE360 App Unica

I 27 file HTML/CSS/JS essenziali del frontend sono copiati negli asset Android
in feature/planimetrie/src/main/assets/rilievi/. L'originale resta immutato.
Solo js/app.js ha due adattamenti locali: esportazione dei blob tramite
GE360Native.postMessage e riutilizzo di saveBlob per Esporta JSON.

Non si usa una pagina GitHub esterna: l'interfaccia è dentro l'APK.

## Uso

1. Aprire GE360 App Unica → Rilievi.
2. Premere **RILIEVO AVANZATO · Disegno touch + calcolo Debian**.
3. Disegnare il rilievo con il dito, quotare muri/aperture, assegnare stanze e lavori.
4. Premere Salva: il disegno è nel database web locale del dispositivo.
5. Dal pannello impostazioni (ingranaggio), configurare URL API Debian e chiave.
6. Usare CALCOLO PROFESSIONALE soltanto con server raggiungibile; leggere
   eventuali decisioni del solver prima di utilizzare i risultati.
7. Per portare i documenti fuori dall'app, usare Esporta JSON oppure PDF/DXF/PNG,
   e scegliere un file nel selettore di documenti Android.

La modalità di disegno funziona senza Debian: l'elaborazione professionale,
l'archivio server e l'agente Ollama richiedono il backend online.

## Connessione raccomandata: Tailscale Serve HTTPS

Sul Debian, secondo la guida del backend, predisporre Serve su porta locale
127.0.0.1:9888, privato nella tailnet. In Rilievi → Impostazioni:

- URL: https://NOME-NODO.NOME-TAILNET.ts.net/api/v1
- Chiave: la GE360_API_KEY effettiva del server (NON committare mai in GitHub)

Poiché la pagina dell'app viene servita dall'origine sicura
https://appassets.androidplatform.net, FastAPI deve ammettere esplicitamente
quest'origine CORS. Nell'ENV REALE del backend, aggiungerla all'elenco ESISTENTE:

GE360_CORS_ORIGINS=http://localhost,http://127.0.0.1,https://localhost,capacitor://localhost,https://appassets.androidplatform.net

Quindi riavviare il servizio, per esempio:

sudo systemctl restart ge360-rilievi-backend.service

**Non** usare wildcard *; non aprire l'API al pubblico né usare Tailscale Funnel.
I domini Tailscale funzionano in HTTPS e non richiedono eccezioni cleartext.
L'URL WireGuard in HTTP (10.88.0.1:9888) resta non supportato nel WebView
sicuro: è bloccato come mixed content. La configurazione Diretta WireGuard
del frontend Capacitor non è presente nel contenitore WebView di App Unica.
Le impostazioni URL/key sono locali al profilo WebView; la key non viene
salvata nel repository, ma l'archivio WebView NON usa Android Keystore.

La repository backend NON viene modificata in questa integrazione:
i suoi workflow si attivano automaticamente su push/PR e consumerebbero
runner GitHub, in contrasto con l'obiettivo di evitare crediti.

## Privacy / backup / contratto tra moduli

- Il nuovo disegnatore NON sostituisce il modulo nativo FloorPlanStore.
- Non esiste ancora import automatico né sincronizzazione bidirezionale tra
  gli archivi WebView/SQLite o tra Rilievi avanzati e Preventivi.
- WebView localStorage e IndexedDB **non sono inclusi** nel backup ZIP GE360.
  Il JSON esportato contiene il rilievo trasmissibile al backend, NON una copia
  completa della libreria e delle foto locali: conservare le immagini a parte.
- Non cancellare dati app, cache persistenti WebView o disinstallare l'app
  prima di un'esportazione e di una procedura di migrazione/backup verificata.
- Gli asset JavaScript locali possono richiedere migrazioni delle impostazioni
  di browser storage quando si aggiorna il bundle OpenPlan3D.
- I rilievi nativi restano visibili e modificabili come prima; nessun
  preventivo accettato viene modificato dall'apertura del nuovo strumento.

## Sicurezza WebView

- Origine locale HTTPS tramite WebViewAssetLoader (non file://).
- Navigazioni esterne aperte nel browser di sistema, non nella WebView.
- File access e universal file access non concessi, HTTP mixed content vietato.
- I salvataggi ricevono messaggi soltanto dal frame principale
  https://appassets.androidplatform.net tramite WebViewCompat origin-scoped;
  niente addJavascriptInterface esposto a frame o domini arbitrari.
- Dimensione dei file esportabili limitata a 10 MB, selezione destinazione
  manuale via Android Storage Access Framework, senza nuovi permessi storage.
- Richieste backend e caricamento delle foto sono avviati dall'utente.
- Esportazione PDF/JSON/PNG/DXF manuale, senza upload a servizi cloud esterni.

## Limiti noti da collaudare

- Plugin Capacitor BLE laser e QR/Direct Bridge non attivi in una WebView
  standard: le misure possono essere inserite a mano e URL/key a mano.
- La libreria vendor/pdfjs non è distribuita nella repo frontend: il lettore
  PDF interno prova un fallback. Esportare il PDF e aprirlo con un lettore
  Android quando serve.
- Il PDF/DXF calcolato dal server è un elaborato tecnico di lavoro e va
  verificato prima di usarlo per preventivi definitivi o documenti ufficiali.
- L'elaborazione in WebView non crea automaticamente righe nel modulo
  Preventivi: il collegamento metrico/cantiere è una fase distinta.

## Collaudo solo locale — SENZA GitHub Actions

Dopo il checkout su Debian con Android SDK e JDK 17:

python3 scripts/verify-rilievi-integration.py
./gradlew :feature:planimetrie:testDebugUnitTest :app:assembleDebug

Test sul dispositivo:
1. navigazione Rilievi nativi → Rilievo avanzato → ritorno, nessuna perdita dati;
2. salvataggio offline, riavvio e apertura disegno dall'archivio locale;
3. quota parete, porta e finestra; schizzo approssimativo e correzione misure;
4. Esporta JSON via selettore file Android e verifica JSON leggibile;
5. collegamento HTTPS Tailscale con API key e CORS corretto;
6. CALCOLO, stato/progresso, superfici, versioni e PDF/DXF/PNG;
7. condivisione/esportazione, rotazione schermo e ritorno, comportamento
   con backend spento o fuori rete;
8. backup ZIP nativo e nessuna modifica ai vecchi rilievi/accordi.

**Non effettuare merge né distribuire l'APK prima della build e del collaudo.**
