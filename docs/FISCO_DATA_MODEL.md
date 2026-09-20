# GE360 Fisco — modello dati e regole

## Scopo

`feature:fisco` è un modulo di controllo e simulazione fiscale. Non mantiene una seconda contabilità e non modifica i movimenti economici.

Fonte dati operativa:

`feature:spese / FinanceStore -> movimenti BUSINESS dell'anno -> FiscalEngine -> confronto Forfettario / Ordinario`

I movimenti `PRIVATE` sono esclusi.

## Parametri 2026

Il motore Kotlin porta nel modulo i parametri 2026 del repository `edil-fisco-hub`:

- coefficiente redditività costruzioni: 86%;
- limite forfettario: 85.000 €;
- soglia uscita immediata: 100.000 €;
- imposta sostitutiva: 15%, oppure 5% se l'utente seleziona esplicitamente startup;
- INPS Gestione Artigiani: minimale, fisso, aliquote 24%/25%, maternità e riduzione opzionale 35%;
- IRPEF: 23% / 33% / 43%;
- addizionale regionale FVG e comunale Trieste;
- costi gestione stimati separati per forfettario e ordinario.

I parametri devono restare centralizzati nel motore e versionati per anno. Non aggiungere aliquote sparse nella UI.

## Interpretazione di Spese

`FinanceStore` oggi registra importo di cassa, categoria, data, ambito e cantiere, ma non conserva ancora per ogni movimento:

- imponibile fattura;
- IVA esposta;
- aliquota IVA;
- reverse charge;
- percentuale di deducibilità;
- percentuale di detraibilità IVA;
- bene ammortizzabile / quota annua;
- tipo veicolo.

Per questo `FiscalEngine` usa regole conservative per categoria e mostra avvertenze quando il dato non è sufficiente.

Esempi:

- Materiali, Smaltimenti e Trasporti: stima standard con IVA 22% e piena inerenza;
- Subappalti: costo deducibile, ma IVA/reverse charge non ricostruito dalla sola voce Spese;
- Collaboratori: costo considerato, ma natura del compenso/contributi da verificare;
- Attrezzi: la stima non distingue acquisto immediatamente deducibile da cespite ammortizzabile;
- Auto/Carburante: percentuali prudenti finché non viene configurato il tipo di veicolo;
- categorie sconosciute: nessun vantaggio fiscale automatico.

## Output

Il confronto mantiene distinti:

- ricavi incassati;
- uscite reali di cassa;
- reddito fiscale stimato;
- INPS;
- imposta sostitutiva oppure IRPEF/addizionali;
- IVA acquisti potenzialmente recuperabile;
- costo gestione stimato;
- netto di cassa stimato;
- pressione fiscale/contributiva stimata;
- differenza tra i due regimi;
- punto di pareggio dei costi stimato sul mix di categorie corrente.

La parola **stimato** è intenzionale: il modulo non compila dichiarazioni e non sostituisce la classificazione fiscale delle fatture.

## Impostazioni locali

La UI salva in `SharedPreferences` solo ipotesi di simulazione:

- riduzione INPS 35% sì/no;
- aliquota forfettario 15%/5%;
- quota clienti privati;
- capacità di ribaltare l'IVA sui privati;
- numero fatture soggette a bollo.

Nessuna di queste impostazioni modifica `FinanceStore`.

## Evoluzione prevista

Per arrivare alla parità completa con FiscoHub legacy:

1. estendere Scanner/Spese con imponibile, IVA, aliquota, reverse charge e documento sorgente;
2. configurare veicoli e beni ammortizzabili;
3. portare rettifica IVA art. 19-bis2 e scadenzario;
4. versionare i parametri fiscali 2027+;
5. aggiungere test automatici del motore con casi calcolati a mano;
6. verificare i risultati con dati reali e commercialista prima di usarli per decisioni fiscali definitive.
