# Navigazione GE360 — shell multi-pagina 2026.09

## Principio

GE360 usa una shell principale con cinque destinazioni stabili:

1. Home
2. Cantieri
3. Preventivi
4. Rilievi
5. Altro

La barra inferiore serve solo per le destinazioni principali. I moduli operativi esistenti restano Activity indipendenti e non vengono duplicati dentro la shell.

## Home → "Oggi"

La prima destinazione si chiama **Oggi** ed è `TodayFragment`.

Il principio era già scritto qui — *"la Home è una dashboard operativa, non un catalogo di moduli"* — ma la pratica lo contraddiceva: la Home era diventata un elenco di otto card numerate, di cui Cantieri, Preventivi e Rilievi duplicavano la barra in basso, e delle quattro cose che si fanno ogni giorno ne compariva una sola (Spese). Ore, foto e scansione stavano in `Altro`, a tre o quattro tocchi.

"Oggi" contiene, in questo ordine:

1. **il piano del giorno** — in arretrato, oggi, e il conteggio della settimana. Riusa `AgendaDayPlan`, non lo riscrive;
2. **le quattro azioni quotidiane**, un tocco ciascuna: Ore, Foto, Spesa, Scansiona;
3. **i collegamenti** a La mia giornata, Agenda, Settimana cantieri, Avvisi Jarvis.

Cantieri, Preventivi e Rilievi **non** compaiono: sono già nella barra, a un tocco.

Due azioni arrivano direttamente al punto, non alla schermata: `ExpensesActivity.EXTRA_NEW_EXPENSE` apre il form del movimento, `ScannerActivity.EXTRA_SCAN_NOW` avvia la scansione. Il gesto quotidiano è segnare uno scontrino, non sfogliare un archivio.

**Vincolo da non perdere.** "Oggi" rispetta la regola che ha fatto nascere `SafeHomeFragment`: niente lettura di dati prima del primo frame. Titolo, azioni e collegamenti si disegnano senza toccare SQLite; il piano del giorno arriva dopo, in background, con ogni fonte protetta separatamente. Se "Oggi" non riesce ad aprirsi, `MainActivity` ripiega su `SafeHomeFragment`.

La dashboard completa (`HomeFragment`: KPI, cantieri da controllare, furgone, economia) resta dietro "Quadro completo".

## Cantieri

La pagina Cantieri mostra conteggi per stato e una shortlist dei lavori da continuare. L'Activity Cantieri resta il centro operativo completo del lavoro.

Evoluzione prevista: dentro il singolo cantiere usare sezioni contestuali come Panoramica, Cronologia, File/Foto ed Economia senza aggiungerle alla barra globale.

## Preventivi

La pagina Preventivi mostra stato commerciale sintetico: da preparare, inviati, accettati, varianti/extra e documenti recenti. La gestione completa rimane nel modulo Preventivi.

## Rilievi

Rilievi è una destinazione primaria. Mostra rilievi indipendenti e collegati, accesso diretto agli ultimi rilievi e creazione rapida. Il rilievo continua a poter nascere prima di cliente, preventivo o cantiere.

## Altro

Contiene gli strumenti secondari organizzati per area:

- Lavoro e organizzazione: Clienti, Giornata, Spese, Scanner
- Cantiere: Attrezzi e materiali, Galleria, Messaggi, Fisco
- Sistema: Marketing, Jarvis / IA, Backup

Nuovi moduli secondari devono finire qui invece di creare nuove destinazioni globali, salvo che diventino realmente una delle 3–5 funzioni principali dell'app.

## Quick Add

Il FAB `+` della shell apre le azioni frequenti:

- Cantiere
- Preventivo
- Nuovo rilievo
- Spesa / entrata
- Scansiona documento

Il Quick Add non contiene impostazioni o funzioni rare.

## Compatibilità

La modifica è principalmente di navigazione. Non cambia gli schemi database di Cantieri, Preventivi, Rilievi o altri moduli. Le Activity esistenti restano utilizzabili e possono essere migrate gradualmente senza una riscrittura monolitica.

## Regola UX

Ogni nuova funzione deve rispondere a una domanda:

- serve ogni giorno e rappresenta un'area principale? -> valutare una destinazione primaria;
- appartiene a un cantiere/preventivo/rilievo? -> metterla dentro quel contesto;
- è uno strumento secondario? -> `Altro`;
- è un'azione frequente di creazione? -> valutare `+` rapido.

Obiettivo: massimo cinque destinazioni principali e pochi tocchi anche con una mano in cantiere.
