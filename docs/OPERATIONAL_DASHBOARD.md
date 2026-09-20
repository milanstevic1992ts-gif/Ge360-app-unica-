# GE360 — Dashboard operativa e diagnostica

## Dashboard OGGI

La dashboard completa espone un blocco **OGGI** sopra la panoramica economica.

Il blocco legge esclusivamente dati locali e mostra:

- timer Giornata attivi, inclusi i timer multi-persona;
- assegnazioni squadra pianificate per la data corrente;
- rapportini presenti oggi;
- cantieri attivi o programmati;
- segnalazione di rapportini con problemi;
- segnalazione di rapportini con materiali richiesti.

Il componente non viene caricato dal `SafeHomeFragment` e non avvia rete, sincronizzazione o job in background. È quindi separato dal bootstrap sicuro usato per prevenire crash all'avvio.

## Stato GE360

In `Altro → Sistema → Stato GE360` è disponibile una diagnostica manuale.

Mostra senza modificare i dati:

- versione e package installati;
- spazio libero interno ed esterno dell'app;
- permessi persistenti Android SAF;
- ultimo stato e ultima sincronizzazione Data Hub riuscita;
- presenza e timestamp dell'ultimo crash registrato localmente.

Il pulsante **CONTROLLA DATI** apre in sola lettura i principali store locali e segnala immediatamente eventuali errori di apertura. Il report può essere condiviso come testo per assistenza tecnica.

La diagnostica non viene eseguita automaticamente all'avvio.
