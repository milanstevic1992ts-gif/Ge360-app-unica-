# GE360 App Unica — Checklist lavorazioni completa per sopralluoghi

12 novembre 2026 · Ramo di sviluppo, **non ancora collaudato Android**.

## Obiettivo pratico
Nel percorso **Preventivi → Sopralluogo Smart · 8 passi**, la scelta delle lavorazioni ora può avvenire senza conoscere codici e senza dover creare voci libere per le opere ricorrenti.

Si conserva la logica: **Cliente → Ambienti → Misure → Demolizioni → Impianti → Finiture e prezzi → Materiali/foto → Controllo/Bozza**. Il sopralluogo è offline e non crea un preventivo fino alla conferma esplicita.

## Cosa è disponibile
- **42 lavorazioni** dal `StandardWorkCatalog` (capitolato già presente);
- **39 nuove lavorazioni specialistiche** (`SpecializedWorkCatalog`), incluse rimozione intonaco e malte, tramezze non portanti, ripristino finestre/portefinestre, archi/architravi, rasatura armata, Q2/Q3/Q4, paraspigoli, pitture bianca/colorata/traspirante/antimuffa, carta da parati, pareti/controsoffitti/velette in cartongesso, lana di roccia, barriera/freno vapore, assistenze murarie agli impiantisti, corrugati faretti, autolivellante, massetto, parquet prefinito/raschiatura, pendenze e bandelle terrazze;
- checklist di ambiente già esistente (bagno, cucina, stanza, terrazza, altro), incluse selezioni specifiche e alternative incompatibili del bagno;
- le voci del capitolato già rappresentate nell'ambiente non sono ripetute con un secondo codice `CAT-`;
- i lavori di dettaglio non sono preselezionati né prezzati automaticamente.

**Importante:** le 42 voci del capitolato sono disponibili in tutti i tipi di ambiente. Le 39 specialistiche non sono un nuovo capitolato contrattuale definitivo: i testi, i prezzi e le unità di misura sono da verificare nel singolo sopralluogo.

## Nuova checklist sul telefono
1. Nel passo **Demolizioni**, **Impianti** o **Finiture e prezzi**, tocca **CERCA E SPUNTA LAVORAZIONI** sul locale interessato.
2. Digita una parola: `massetto`, `SPC`, `rasatura`, `vasca`, `veletta` e così via.
3. Oppure filtra per **categoria** (es. demolizioni, pavimenti, pitture, murature).
4. Spunta o deseleziona le lavorazioni, anche in più categorie: le scelte rimangono in una selezione temporanea finché non tocchi **APPLICA**.
5. Nel passo Finiture usa **Quantità, prezzi e optional** per valorizzare soltanto le lavorazioni effettivamente comprese.
6. Le voci specialistiche e standard restano a prezzo zero fino a inserimento esplicito. Per quantità non ricavabili con sicurezza dalla geometria è richiesto inserimento manuale.
7. Mantieni le alternative specifiche del bagno (demolizione/posa su posa/mantenimento; vasca/doccia/nessuna) mediante i controlli esclusivi già esistenti.

## Prevenzione doppi conteggi
`SmartSurveyDraftV1.checks()` evidenzia casi potenzialmente sovrapposti:
- più livelli Q2/Q3/Q4 nello stesso ambiente;
- più cicli di pittura sulla stessa area;
- pittura generica insieme a una pittura specifica;
- rasatura armata insieme a rasatura ordinaria.

Sono **avvisi non distruttivi**: la stessa stanza può avere superfici differenti. L'artigiano conferma quantità e superfici. Le lavorazioni opzionali non alterano il totale; la modalità a corpo non somma le voci analitiche.

## Dati e backup
Non cambiano i formati di `SmartSurveySessionV1`, `EstimateRecord` o i database. Si riusano i codici `ROOM/<id>/<codice>`, il prezzo a zero e i metadati esistenti. I sopralluoghi e le foto seguono il backup centrale introdotto nel ramo del motore (da collaudare).

## Test previsti e limiti
Nuovo `SmartSurveyCatalogCoverageTest.kt`: verifica disponibilità di tutte le 42 voci in ciascun tipo di locale, 39 specialistiche, prezzi/quantità non inventati e avvisi sovrapposizioni.

**Non sono state eseguite compilazioni Gradle, test JUnit né prove su dispositivo. Nessun APK né GitHub Actions avviati**.

Collaudo richiesto prima di unire:
- compilazione del modulo preventivi e dell'app su Debian;
- sopralluogo con due locali, ricerche/filtri, spunte, opzionali, prezzo a corpo e a misura;
- variazione tipo locale, ripresa bozza e controllo nessuna perdita di voci;
- bagno con alternative esclusive e quantità rivestimento pareti;
- apertura vecchio preventivo, modifica e accordo già accettato;
- backup ZIP e ripristino.
