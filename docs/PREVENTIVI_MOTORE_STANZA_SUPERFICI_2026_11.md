# GE360 App Unica — Motore stanza → parete / soffitto / pavimento → lavorazione

**Stato: PR in sviluppo, non ancora validata su dispositivo Android.** Creato il 12 novembre 2026.

## Obiettivo operativo

Durante il sopralluogo, anziché collegare sempre il lavoro a un locale intero, l'artigiano può scegliere una superficie specifica. Esempio:

- **Camera** → **Parete finestra** → rasatura armata, 9,00 m².
- **Camera** → **Parete porta** → stuccatura, 6,60 m².
- **Camera** → **Soffitto** → controsoffitto in cartongesso, 12,00 m².
- **Camera** → **Pavimento** → autolivellante + posa SPC, 12,00 m².
- **Camera** → **Tutto l'ambiente** → protezione cantiere a corpo.

## Interfaccia Android

Si usa il **Preventivi → Sopralluogo Smart** esistente, senza creare una seconda app.

1. **Passo 2 · Ambienti**: aggiungere Camera, Bagno, Cucina, Salotto ecc.
2. **Passo 3 · Misure**: inserire misure generali del locale. Nuovo pulsante **PARETI DI [STANZA]**: creare Parete 1, Parete finestra, Parete porta ecc., con lunghezza, altezza, aperture misurate e note.
3. **Passo 4 · Demolizioni**: **DOVE LAVORIAMO? → Stanza / Parete / Soffitto / Pavimento**. Selezionare una superficie, cercare e spuntare i lavori del catalogo completo.
4. **Passo 6 · Finiture e prezzi**: il selettore di superficie è disponibile anche qui. **COMPILA QUANTITÀ E PREZZI · TUTTO INSIEME** mostra separatamente ciascuna superficie, le proprie lavorazioni, le quantità e i prezzi.
5. **Passo 8 · Riepilogo**: il conteggio include le lavorazioni per superficie; la bozza preventiva viene creata solo con conferma esplicita.

**Nella prima versione il muro si seleziona per nome, non toccando una parete nel disegno OpenPlan3D.** Il collegamento visuale con la planimetria potrà essere aggiunto successivamente usando gli UUID.

## Motore dati

Nuovi oggetti in `SurveySurfaceEngine.kt`:

- `SurveyWallV1`: UUID stabile, nome parete, lunghezza, altezza, m² aperture, note.
- `SurveyScopedWorkV1`: UUID stabile, `workCode`, `scopeId`, quantità manuale opzionale, prezzo e flag optional.
- `SurveyWorkScope`: `room`, `floor`, `ceiling` o UUID parete.
- `SurveySurfaceEngine`: selezioni e conflitti, quantità, validazione, codici di riga, metadati.

Non cambia lo schema `ge360.smart.sopralluogo.v1`: in ogni `SmartSurveyRoomV1` sono aggiunti i campi JSON facoltativi `wallsList` e `scopedWorks`. I vecchi sopralluoghi senza questi campi restano compatibili. Nessuna nuova tabella SQLite.

### Calcolo quantità

- Parete: `(lunghezza × altezza) - aperture` m², mai negativa. Le aperture sono inserite manualmente; non si sottraggono automaticamente tutte le finestre.
- Soffitto: m² soffitto dal rilievo stanza (geometrico o manuale).
- Pavimento: m² pavimento dal rilievo stanza (geometrico o manuale).
- Lavorazioni in metri lineari: parete = lunghezza; pavimento = perimetro stanza. Per i quantitativi non deducibili (`cad.`, ecc.) è obbligatoria la conferma manuale prima di valorizzare economicamente.
- Lavorazioni `corpo`/`forfait`: quantità 1; importo sempre da confermare.
- Il campo quantità manuale prevale sulla proposta geometrica. Se si conferma la quantità proposta, resta dinamicamente legata alla superficie fino alla conversione.

La somma delle pareti censite viene confrontata con i m² pareti dichiarati per la stanza e, se superiore, produce un avviso. **Le misure delle pareti non modificano automaticamente il totale pareti generale**: questo evita ripetizioni o sovrascritture nascoste.

### Anti-doppio conteggio

- Non è permesso assegnare lo stesso codice di lavoro contemporaneamente a **tutto l'ambiente** e a una sua superficie.
- Quando si tenta, l'utente deve confermare lo **spostamento della lavorazione**. L'assegnazione precedente viene rimossa, senza sommare i due importi.
- Si può usare la stessa lavorazione su **pareti diverse**, perché sono superfici fisicamente distinte con ID e quantità proprie.
- La validazione strutturale blocca la conversione della bozza se esistono target inesistenti, codici incompatibili o duplicati di ambito.
- Il controllo già previsto per il pacchetto SPC rimozione+posa include anche le voci su superfici.
- I lavori a corpo per ambiente mantengono la separazione dei costi analitici e degli optional del flusso esistente.

### Preventivo, backup, prompt

- Ciascuna superficie genera una `EstimateItem` autonoma con prefisso `SURFACE/{roomUuid}/{scopeId}/{workCode}`.
- La voce contiene **nome stanza e superficie**, quantità, unità e prezzo; il prompt GPT V3.1 aggiunge anche un oggetto `surface` con nome e tipo, senza esportare i metadati interni completi.
- I metadati `[GE360_SURFACE_SCOPE_V1]` sono inseriti prima di quelli `[GE360_ROOM_META]`: il configuratore classico di ambienti continua così a ricostruire il locale.
- Modificare le lavorazioni di una stanza dall'editor classico non cancella silenziosamente le voci `SURFACE/`; eliminare la stanza le elimina esplicitamente insieme alle voci `ROOM/`.
- Le assegnazioni sono parte del JSON della sessione di Sopralluogo Smart già compreso nel piano di backup generale. Il ripristino reale su un altro telefono **resta da collaudare**.

## Verifiche

È stato aggiunto `SurveySurfaceEngineTest.kt` con casi di:

1. due pareti e stessa lavorazione, prezzi e superfici nette distinti;
2. blocco della sovrapposizione intera stanza/singola parete e spostamento esplicito;
3. pavimento e soffitto con metrature distinte;
4. serializzazione/ripristino JSON con UUID stabile, aperture, quantità e optional;
5. compatibilità con JSON precedenti privi di `wallsList` e `scopedWorks`;
6. rimozione di parete e delle sole sue lavorazioni;
7. importo a corpo unico senza sommare le righe analitiche;
8. invalidazione di ambiti inesistenti o duplicati;
9. presenza del nome della superficie nel payload GPT senza note interne.

I test sono **scritti, non ancora eseguiti**. Serve compilazione su Debian con Gradle e collaudo reale sul telefono, includendo backup/ripristino e interazione con l'editor tradizionale, prima di dichiarare la funzione pronta per il rilascio.

**Non sono stati creati APK né avviate GitHub Actions.**
