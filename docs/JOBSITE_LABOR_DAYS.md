# GE360 · Ore / manodopera cantiere

La funzione permette di registrare retroattivamente la presenza di un operatore in un cantiere e di valorizzarne il costo gestionale.

## Regole

- accesso dal Centro Cantiere → Ore / manodopera;
- scelta operatore dal personale GE360;
- scelta del giorno dal calendario per date precedenti a oggi;
- oggi resta gestito dal timer, per evitare doppio conteggio;
- 8 ore rappresentano una giornata standard;
- il costo parziale è proporzionale alle ore registrate;
- Slobodan ha un valore iniziale di 92 €/giorno;
- per gli altri operatori il valore iniziale deriva dalla tariffa oraria × 8, poi GE360 riusa l'ultima tariffa giornaliera storica registrata;
- la tariffa viene fotografata sulla singola sessione: modifiche future non cambiano il costo storico;
- se nello stesso giorno, cantiere e operatore esistono già ore da timer, la giornata retroattiva viene rifiutata;
- il pagamento reale del collaboratore resta separato nel modulo Spese.

## Persistenza

Le giornate retroattive vengono salvate nella tabella esistente `work_sessions` del modulo Giornata con marcatore `Giornata retroattiva`.

Per questo motivo:

- non viene introdotto un nuovo database;
- `DailyStore.totalMinutesForJobsite()` e `laborCostForJobsite()` le includono automaticamente;
- il backup/ripristino esistente di `giornata.json` le include già attraverso il blocco `sessions`;
- non viene modificato il bootstrap sicuro di MainActivity/SafeHome.
