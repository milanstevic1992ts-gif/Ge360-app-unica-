# Navigazione GE360 — shell multi-pagina 2026.09

## Principio

GE360 usa una shell principale con cinque destinazioni stabili:

1. Home
2. Cantieri
3. Preventivi
4. Rilievi
5. Altro

La barra inferiore serve solo per le destinazioni principali. I moduli operativi esistenti restano Activity indipendenti e non vengono duplicati dentro la shell.

## Home

La Home è una dashboard operativa, non un catalogo di moduli. Mostra KPI, cantieri da controllare, preparazione furgone, alert, stato cantieri ed economia. L'elenco completo degli strumenti è stato spostato in `Altro`.

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
