# Sincronizzazione Firefly — contratto

Come l'app GE360 invia i movimenti a Firefly III attraverso il bridge, e cosa
deve fare il server perché non nascano movimenti doppi.

## Il problema

La creazione di una transazione è una `POST`. Fra l'arrivo della richiesta al
server e l'arrivo della risposta al telefono c'è una finestra in cui la cosa può
rompersi: timeout di 10 secondi, Tailscale che cade, app chiusa dal sistema,
telefono che perde campo in cantiere.

Se si rompe lì, il server ha creato la transazione ma l'app non ha ricevuto
l'id. Il movimento resta senza `fireflyId`. Prima di questo lavoro veniva
segnato come errore e la sincronizzazione successiva rifaceva la `POST`:
**due transazioni identiche su Firefly, e un saldo sbagliato.**

Non è un caso di laboratorio: è il caso normale di chi lavora dove la rete va e
viene.

## Cosa fa il client

### 1. Chiave di idempotenza

Ogni creazione porta l'header:

```
Idempotency-Key: ge360-finance-<ge360Id del movimento>
```

La chiave è derivata dal **movimento**, non dal tentativo: due invii dello
stesso movimento portano la stessa chiave. Gli aggiornamenti (`PUT` su una
transazione che ha già un `fireflyId`) non la portano: sono ripetibili per
natura e non possono creare doppioni.

Il corpo contiene anche `source_record_id` con lo stesso `ge360Id`, utile come
chiave di riconciliazione lato Firefly.

### 2. Esito ignoto distinto dall'errore

`FireflySyncStatus` ha quattro valori, non tre:

| stato | significato | ritentato in automatico |
|---|---|---|
| `PENDING` | mai inviato | sì |
| `SYNCED` | confermato, `fireflyId` noto | no, non serve |
| `ERROR` | rifiutato dal server (4xx), non ha prodotto nulla | sì |
| `UNCERTAIN` | **non si sa se è stato creato** | **no** |

Un movimento diventa `UNCERTAIN` quando la creazione fallisce con:

- nessuna risposta (`IOException`: timeout, connessione caduta), oppure
- un `5xx`, perché la richiesta è arrivata e il server può aver scritto comunque.

Un `4xx` resta `ERROR`: la richiesta è stata rifiutata senza produrre nulla, e
ritentarla identica è inutile ma innocuo.

Nella lista Spese un movimento `UNCERTAIN` mostra "Firefly da verificare". Il
ritentativo in blocco lo salta e lo conta a parte. Per forzarlo c'è una voce
separata nel menu Dati, con una conferma che spiega il rischio.

**Questa seconda difesa funziona anche se il server ignora la chiave**, ed è
quella che protegge oggi.

## Cosa deve fare il server

Il bridge, su `POST /api/v1/integrations/firefly/transactions`:

1. **Leggere `Idempotency-Key`.** Se manca, comportarsi come prima.
2. **Cercare una transazione già creata con quella chiave.** Se c'è,
   restituirla — stesso `fireflyTransactionId`, codice `200` — **senza crearne
   un'altra**.
3. **Registrare la chiave nella stessa transazione** in cui crea la transazione
   su Firefly, non prima e non dopo: se i due passaggi non sono atomici la
   finestra si riapre, solo più stretta.
4. Conservare le chiavi per un tempo ragionevole: un movimento può restare
   `UNCERTAIN` sul telefono per giorni, se il cantiere è fuori campo.

Utile ma opzionale, per chiudere il cerchio:

5. **`GET .../transactions?source_record_id=<ge360Id>`** che dica se una
   transazione per quel movimento esiste già. Con questo il client potrebbe
   risolvere da solo gli `UNCERTAIN` invece di chiedere all'utente di guardare
   Firefly.

Finché il punto 5 non esiste, la verifica di un `UNCERTAIN` si fa a mano.

## Come verificarlo

Il punto 2 si prova facilmente: mandare due volte la stessa `POST` con la stessa
`Idempotency-Key` e controllare che su Firefly ci sia **una** transazione e che
le due risposte abbiano lo stesso `fireflyTransactionId`.

Lato app, la classificazione degli esiti è coperta dai test unitari di
`feature:spese`.
