# GE360 Preventivi — ponte Sopralluogo → GPT Premium V3.1

## Obiettivo

Integrare nell'app **GE360 Android** il flusso progettato nel repository
[debian-preventivi](https://github.com/milanstevic1992ts-gif/debian-preventivi):
raccolta rapida dei dati in cantiere, prompt strutturato e generazione successiva
del PDF tramite il GPT specializzato, senza invocare API IA dal telefono.

Il repository Debian, al 10 novembre 2026, contiene il progetto e le istruzioni
di sviluppo della PWA ma **non** i sorgenti applicativi completi. Il ponte Android
è quindi **un'esportazione manuale**, non una sincronizzazione o un import PWA.

## Utilizzo sull'app Android

1. Aprire **Preventivi** e selezionare un preventivo.
2. In **Lavorazioni guidate, capitolato e prezzi**, inserire ambienti,
   misure (geometriche o manuali), checklist e voci a corpo/a misura.
3. Aprire **Prompt IA**.
4. Leggere le verifiche suggerite. Non sono modifiche automatiche del preventivo.
5. **Anteprima**, **Copia prompt** o **Condividi**. Incollare volontariamente il
   testo nel GPT *Preventivi Premium V3.1* per ottenere il documento/PDF.

L'app non esegue nessuna chiamata a servizi IA, non modifica preventivi accettati
e non genera da sola il PDF finale.

## Contratto di interscambio

- Identificatore: ge360.sopralluogo.prompt.v1
- Formato: prompt in italiano con oggetto JSON strutturato
- Oggetto estimate: titolo, cliente, cantiere, stato, lavori, condizioni,
  materiali, esclusioni e totali già presenti in GE360
- rooms: nome, tipo, dimensioni, misure di sopralluogo sintetiche e
  prezzo a corpo/a misura
- includedWorks: sole lavorazioni comprese, con quantità e prezzi
- optionalWorksOutsideTotal: lavorazioni opzionali, **non** sommate al totale
- excludedWorksCount: numero delle voci disattivate, non esportate per nome
- checksToConfirm: suggerimenti per completare o verificare i dati mancanti
- internalCostsIncluded: false; note interne e costi aziendali non trasmessi
- photosAttached: false; nessuna fotografia allegata implicitamente

Il JSON nasce per poter essere riutilizzato come **schema di scambio** dalla
futura PWA Debian. La PWA dovrà creare un proprio import esplicito e validato,
con migrazione di schema se necessario; il solo matching del nome schema non
significa che oggi il flusso sia sincronizzato.

## Controlli del sopralluogo

- Cliente, oggetto lavori, ambienti e misure da completare.
- Voci incluse senza prezzo, con cautela per gli ambienti a corpo.
- Nel bagno: demolizione pavimento **oppure** posa su posa;
  rimozione vasca **oppure** rimozione doccia, quando previste.
- Se nessuna alternativa è scelta, suggerire un controllo, **senza forzarla**:
  potrebbe trattarsi di un bagno dove la lavorazione non è prevista.

I controlli non cambiano la checklist, non scrivono sul database e non
aggiungono prezzi. Restano disponibili tutte le lavorazioni manuali.

## Privacy, costi, test

- Nessun dato cliente, foto, database o token deve essere committato nel repository.
- La condivisione avviene solo dopo un tap esplicito. Il contenuto può includere
  informazioni sul cliente: verificare prima l'app di destinazione.
- Nessun GitHub Action viene avviato dall'integrazione; i workflow sono
  workflow_dispatch manuali.
- Test locali previsti: :feature:preventivi:testDebugUnitTest e
  :app:assembleDebug su Debian/Android SDK. La validazione su smartphone
  e su un preventivo reale resta necessaria prima del merge.
- I dati economici non sono trasferiti automaticamente al backend Debian.

## Possibili estensioni

Quando il progetto Debian avrà sorgenti frontend/backend e uno schema stabile:
import JSON esplicito e versionato, sessioni sopralluogo separate dal preventivo
accettato, allegati fotografici collegati a cantiere/ambiente e sincronizzazione
Tailscale autenticata con gestione dei conflitti. Non è parte di questa fase.
