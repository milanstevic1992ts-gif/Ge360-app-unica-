# Correzioni audit integrità — 2026-09-03

Questa revisione risolve i rischi ad alta priorità rilevati nell'audit completo della App Unica.

## Dati e ripristino

- Il backup comprende ora ambienti, cronologia e personalizzazioni Preventivi, pacchetti personalizzati Attrezzi/Materiali e preparazioni Furgone.
- Il backup automatico giornaliero include anche le fotografie leggibili.
- Ogni restore crea prima un backup completo di sicurezza e tenta il rollback automatico se un passaggio fallisce.
- La schermata Backup mostra l'ultimo esito dell'esecuzione automatica.

## Persistenza cantiere

- La materializzazione viene richiesta anche alla sospensione delle Activity e parte subito al loro arresto.
- La replica locale/Drive viene avviata solo dopo la materializzazione, evitando di replicare cartelle non aggiornate.
- Le cartelle cantiere includono anche dati Preventivi collegati, Planimetrie e preparazioni Furgone.

## Data Hub e navigazione

- Il Data Hub non invia più automaticamente dati al rientro in Home.
- Un dispositivo nuovo non può sovrascrivere un modulo server già popolato adottandone alla cieca la revisione.
- Il comando di sincronizzazione è esplicito nella sezione Sistema.
- Le schede recenti aprono direttamente il cantiere o il preventivo selezionato.

## Verifica automatica

- Le pull request compilano risorse e Kotlin debug ed eseguono i test unitari.
- Un test unitario copre la regola di sicurezza delle revisioni Data Hub.
- Un controllo dedicato impedisce di perdere nuovamente moduli obbligatori da Backup o Data Hub.
- Gli import incrementali condividono una sola regola di merge, coperta da test unitari.
- La CI rifiuta futuri incrementi di versione SQLite privi di una migrazione esplicita.
- La cartella cantiere usa un'impronta stabile: schermate senza modifiche non riscrivono né replicano inutilmente tutti i dati.

La cifratura portabile del file ZIP e il pull Data Hub restano evoluzioni separate: richiedono una politica esplicita per chiavi, merge e cancellazioni e non vengono abilitate implicitamente da questa correzione.
