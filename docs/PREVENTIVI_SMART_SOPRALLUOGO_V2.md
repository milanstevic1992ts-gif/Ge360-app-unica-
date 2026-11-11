# GE360 App Unica — Preventivi Smart Sopralluogo V2 (Android)

## Obiettivo
L'editor storico di GE360 rimane disponibile, ma la raccolta dati in cantiere
passa a una schermata guidata. Si riutilizzano cataloghi e database esistenti:
non si crea una seconda app.

**Queste modifiche sono nel branch di lavoro, non nella versione distribuita.**

## Flusso in otto passaggi
Dal modulo Preventivi, toccare SOPRALLUOGO SMART · 8 PASSI.
1. Cliente, titolo, riferimento cantiere e descrizione
2. Ambienti multipli (bagno, cucina, stanza, terrazza, altro)
3. Misure L×P×H o m², m² pareti, soffitto e perimetro inseriti manualmente,
   inclusa altezza rivestimenti del bagno o m² rivestimento misurati
4. Demolizioni; alternative bagno da scegliere esplicitamente
5. Impianti e tracce
6. Finiture, checklist GE360, lavorazioni libere senza limiti per ambiente,
   quantità, prezzi unitari/a corpo e optional
7. Materiali inclusi, materiali cliente, esclusioni, note, foto private e export JSON
8. Controlli, totale provvisorio, copia/condivisione del prompt V3.1,
   creazione esplicita di una bozza Preventivi

I sopralluoghi si salvano offline e si possono riprendere dall'archivio.
La checklist usa le lavorazioni già presenti in RoomWorkCatalog; con un
tocco si può aggiungere la base standard del tipo di ambiente, senza
imporre prezzi o cancellare voci già selezionate.
La checklist completa PWA da 91 campi e 30 regole discussa ieri
**non è ancora interamente riprodotta**; questo è il primo flusso Android
integrato che usa il catalogo GE360 esistente.

## Regole
- Pavimento bagno: demolizione O sovrapposizione O mantenimento O da decidere
- Rimozione bagno: vasca O doccia O nessuna O da decidere
- Le dimensioni geometriche sono indicative e le misure manuali prevalgono
- Porte e finestre non vengono detratte senza una misura specifica
- Quantità manuali sconosciute = 0, mai inventate; viene mostrato un avviso
- Nessun prezzo inventato. I prezzi a zero sono segnalati
- Gli optional sono fuori dal totale, compresi gli extra manuali
- La piastrellatura delle pareti bagno non assume automaticamente che le
  mattonelle arrivino al soffitto: serve altezza/m² esplicita o quantità manuale
- Se si usa il prezzo a corpo, le lavorazioni analitiche della stessa stanza
  sono descrittive e non raddoppiano l'importo
- Per il prompt le note/costi interni non sono trasmessi

## Dati e tutela dei preventivi esistenti
Ogni scheda sopralluogo temporanea usa il contratto
ge360.smart.sopralluogo.v1 ed è salvata in preferenze private Android.
Non modifica il database dei preventivi finché l'utente non conferma
CREA BOZZA PREVENTIVO GE360.

La conversione salva un nuovo EstimateRecord in stato Bozza,
più i locali in EstimateRoomStore e i metadati sulle voci.
Le quantità provenienti dallo Smart Sopralluogo rimangono protette dal
ricalcolo silenzioso quando vengono riaperte con i configuratori storici,
ma possono essere modificate esplicitamente dall'operatore.
L'ID unico della sessione impedisce la creazione accidentale di una
seconda bozza al secondo tocco; il documento creato può essere aperto
nell'editor standard GE360. Nessuna scrittura su preventivi già accettati.

## Foto ed esportazioni
Le immagini selezionate vengono copiate nella memoria privata dell'app.
Sono visibili e rimovibili dal sopralluogo.
Esporta dati JSON salva testi, misure, lavorazioni e riferimenti ai nomi
dei file **ma non include i file fotografici**. Non equivale a un backup
completo dei dati/foto, né sostituisce il backup SQLite di GE360.
Non disinstallare l'app con sopralluoghi da recuperare.

La funzione Prompt IA compone offline un testo strutturato destinato a
GPT Preventivi Premium V3.1; è possibile copiarlo o condividerlo solo
manualmente, dopo la verifica dei dati. Il documento PDF finale viene
creato nel GPT, non automaticamente dall'app.

## Collaudi da fare successivamente
- Test unitari SmartSurveyDraftV1Test e EstimateSopralluogoPromptV1Test
- Su telefono: offline, sospensione/ripresa, misure manuali,
  checklist bagno, foto, prompt e creazione bozza
- Verifica dei totali per prezzo a corpo, optional e lavorazioni libere
- Quote bagno con rivestimenti parziali e altezza di posa distinta
- Verifica non regressione dei preventivi già esistenti e delle metriche
  importate dal Rilievo avanzato

**Nessuna compilazione APK o GitHub Action avviata per questa modifica.**

## Limitazioni
Non è presente sincronizzazione automatica con la PWA Debian,
né generazione automatica di PDF, né allegati foto nel prompt.
I sorgenti PWA completi non sono ancora caricati nel repository
debian-preventivi e il suo sviluppo rimane indipendente.
