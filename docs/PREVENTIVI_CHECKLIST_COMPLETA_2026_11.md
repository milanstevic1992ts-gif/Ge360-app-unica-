# GE360 — Preventivi Smart: checklist completa per sopralluoghi

Data: 12 novembre 2026. **Branch di sviluppo, non ancora distribuito né collaudato su Android.**

## Obiettivo

Rendere la raccolta dati in sopralluogo **simile a un Fluent Form**: cliente → ambienti → misure → lavori da spuntare → quantità/prezzi → foto e note → bozza preventivo.

Non è un'app separata. Si integra in **Preventivi → Sopralluogo Smart**, mantenendo le 8 schermate, lo schema `ge360.smart.sopralluogo.v1` e i dati offline esistenti.

## Novità sviluppate

1. **Catalogo completo delle 42 voci** (`StandardWorkCatalog.entries`) disponibile per ciascun tipo di ambiente. Dove una voce esiste già, la riutilizziamo senza duplicarne il codice di capitolato.
2. **25 lavorazioni specialistiche** frequenti: demolizioni locali, rimozione intonaco/malte, autolivellante, SPC a pacchetto, parquet posa/levigatura, rasatura armata, Q3/Q4, profili, velette copritubi, copricaldaia, lana di roccia, freno/barriera al vapore, carta da parati, ripristini finestra e portafinestra, carotaggio, architrave, nicchie, predisposizioni idrauliche/elettriche, faretti, radiatori e climatizzazione.
3. **Checklist completa unica** visibile nel passo Demolizioni per ogni ambiente. Ricerca in tempo reale per titolo, codice, categoria e testo tecnico, gruppi per categorie, spunte multiple. **Applica** per salvare; **Annulla** senza alterare selezioni.
4. Restano anche le checklist brevi divise per fase Demolizioni / Impianti / Finiture, e la selezione rapida delle lavorazioni standard.
5. **Compila quantità e prezzi · tutto insieme** nel passo Finiture: un modulo scorrevole per tutti i lavori selezionati, con quantità, prezzo e optional. Convalida tutti i numeri prima di salvare.
6. Possibilità di inserire **lavorazioni libere** senza limite pratico per stanza.
7. Le **alternative bagno** (demolizione/sovrapposizione/mantenimento del pavimento; rimozione vasca/doccia/nessuna) rimangono scelte esclusive, non mischiate alle spunte della checklist generale.
8. Avviso quando il pacchetto SPC rimozione+posa viene scelto insieme a rimozione o posa separate, per verificare il rischio di doppio conteggio.

## Un sopralluogo tipo

- Cliente e indirizzo: compilazione una volta.
- Aggiungi **Bagno**, **Cucina**, **Soggiorno** (o qualunque altro ambiente).
- Inserisci L×P×H oppure misure manuali per ambienti irregolari (m² pareti, soffitti, pavimento, metri lineari). Le aperture non vengono detratte automaticamente.
- Apri *Checklist completa* sul bagno, cerca `nicchia`, `autolivellante`, `impermeabilizzazione`, `piastrelle`; spunta le voci effettive.
- Specifica i due gruppi di alternative bagno senza attivarli insieme.
- Nel passo Finiture, apri **Compila quantità e prezzi · tutto insieme** e conferma i dati.
- Inserisci foto e note, verifica avvisi e premi **Crea bozza preventivo**. I dati passano all'editor standard solo dopo conferma.

## Garanzie e limiti

- Prezzi **mai** riempiti automaticamente. Le quantità generiche non deducibili dalla stanza rimangono a zero per essere confermate; quantità geometriche calcolate sono solo proposte modificabili.
- Nessuna conversione automatica a preventivo inviato/accettato; solo bozza su conferma dell'utente.
- Impianti di idraulico ed elettricista sono segnalati *da coordinare*; la voce non attribuisce conformità o certificazioni all'impresa edile.
- Le voci a corpo e le singole lavorazioni possono concettualmente sovrapporsi. Il controllo SPC è un primo presidio; **verificare sempre il riepilogo prima dell'invio**.
- Un sopralluogo può essere salvato e ripreso offline; le foto sono gestite dalla memoria privata dell'app e il backup unificato va collaudato su telefono.
- **Non** vengono aggiunte integrazioni IA automatiche, PDF automatici, server Debian o costi API.
- **Non** è stata creata un'APK, avviata una GitHub Action o provata la compilazione Gradle.

## Test aggiunti

`RoomWorkCatalogCompletenessTest.kt` copre:
- 42/42 voci raggiungibili per ogni tipo ambiente e codici di lavoro non duplicati;
- disponibilità delle lavorazioni specialistiche e nessuna preselezione involontaria;
- nessuna quantità inferita per voci generiche `MANUAL`;
- avviso doppio conteggio SPC.

Questi test **sono stati aggiunti al codice ma non ancora eseguiti**. Prima del merge compilare e verificare manualmente su Android: apertura checklist, filtro e spunte, annullamento, prezzo/quantità, alternative bagno, persistenza offline, conversione bozza e backup/restore.
