# Design system GE360

Come costruire una schermata GE360 e come portarci quelle vecchie.

Tutto sta in `core/common/.../ui/`. Nessun modulo deve avere colori, spaziature
o dimensioni di testo scritti a mano.

---

## Perché

Misurato sul codice prima di questo lavoro:

| | prima |
|---|---|
| file che usano `Ge360Brand` | 11 su 113 |
| colori scritti a mano nei moduli (`Color.rgb` / `parseColor`) | 242 |
| `dp()` riscritto nei moduli | 53 volte |
| `setPadding()` con valori arbitrari | 516 |
| `RecyclerView` | 0 |
| righe di lista che riusano la vista (`convertView`) | 1 |
| cartelle `values-night` | 0 |

Il design system c'era già, ma lo usava circa un decimo della UI. Il resto
reinventava ogni volta card, righe, campi e spaziature, e il risultato è che
due moduli affiancati non hanno mai lo stesso respiro.

---

## I pezzi

### Colori — `Ge360Brand`

I valori stanno in `core/common/res/values/ge360_palette.xml` e nel gemello
`values-night/`. `Ge360Brand` li espone come proprietà che **cambiano da sole**
quando il telefono passa a tema scuro:

```kotlin
view.setBackgroundColor(Ge360Brand.Surface)
text.setTextColor(Ge360Brand.TextSecondary)
```

Tre token nuovi rispetto a prima:

| token | quando |
|---|---|
| `OnPrimary` | testo/icona **sopra** un riempimento `PrimaryDark` |
| `Accent` | ocra usato **come testo o icona** sopra `Surface`/`Background` |
| `Danger` / `Success` | stati, non decorazione |

La distinzione tra `PrimaryDark` e `Accent` serve proprio al tema scuro: il
riempimento di un bottone resta scuro (perché il testo è bianco), mentre l'ocra
usato come testo va schiarito o diventa illeggibile.

Se `Ge360Brand.sync()` non venisse mai chiamata, la palette resta esattamente
quella chiara di prima: il tema scuro non può rompere l'aspetto esistente.

### Spaziature e testo — `GeSpace`, `GeType`, `GeRadius`, `GeTouch`

```kotlin
setPadding(dp(GeSpace.LG), dp(GeSpace.MD), dp(GeSpace.LG), dp(GeSpace.MD))
title.applyGe360Title(GeType.H2)
```

`GeSpace`: `XS` 4 · `SM` 8 · `MD` 12 · `LG` 16 · `XL` 20 · `XXL` 28.
`GeType`: `DISPLAY` 26 · `TITLE` 22 · `H2` 18 · `H3` 16 · `BODY` 14 ·
`BODY_SM` 13 · `CAPTION`/`EYEBROW` 11.5 · `MICRO` 10.5.

Se un valore non c'è, quasi sempre serve quello più vicino, non uno nuovo.

### Componenti — `GeComponents.kt`

| funzione | cosa dà |
|---|---|
| `geScreen()` | colonna radice con margini e fondo standard |
| `geSectionCard(title, subtitle) { }` | card di sezione |
| `geStatTile(label, accent, onClick) { v, d -> }` | riquadro con numero grande |
| `geStatRow(a, b)` | riga di riquadri a larghezza uguale |
| `geFilterRow(options, selected) { }` | filtri a pillola, è già una View |
| `geFieldLabel(text)` / `geInput(...)` / `geSearchInput(hint)` | campi di form |
| `geEmptyState(title, message, action) { }` | stato vuoto o errore |
| `GeListContainer` | lista + stato vuoto sovrapposti |
| `geActionBar(...)` / `gePrimaryButton` / `geSecondaryButton` | azioni in fondo |

### Lavoro in background — `GeAsync.kt`

```kotlin
geLoad({ store.search(query, filter) }) { outcome ->
    outcome.onSuccess { render(it) }
           .onFailure { showError(it) }
}
```

Il lavoro va su un pool di background e il risultato torna sul thread della UI
**solo se l'Activity è ancora viva**. Lo schema vecchio
(`thread { ... runOnUiThread { ... } }`) non controllava mai la seconda cosa.

Per i campi di ricerca c'è `geLoadDebounced(token, work, onResult)`: senza, ogni
lettera digitata lanciava una lettura completa del database.

### Miniature — `GeThumbnails`

```kotlin
GeThumbnails.load(imageView, photo.localUri, thumbSizePx)
```

Decodifica in background, `inSampleSize` calcolato sulle dimensioni reali,
cache `LruCache` su un ottavo della heap, e controllo del tag per non mettere la
foto sbagliata in una vista riciclata.

### Base delle schermate — `BaseGeActivity`

```kotlin
class MiaActivity : BaseGeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = MioStore(this).managed()   // chiuso in onDestroy
    }
}
```

---

## Migrare una schermata

Ordine consigliato, un modulo alla volta. `ExpensesActivity` e
`GalleryActivity` sono i due esempi completi da copiare.

1. **Base class**: `AppCompatActivity` → `BaseGeActivity`, e `.managed()` su
   ogni store creato nella schermata. Risolve le connessioni SQLite mai chiuse
   (succedeva in 13 Activity su 44).

2. **Dialog**: `import android.app.AlertDialog` →
   `import androidx.appcompat.app.AlertDialog`. L'API è identica. Senza questo
   cambio i dialog restano bianchi in tema scuro, perché quelli di sistema non
   leggono il tema dell'app. **Questo è un find-replace da fare ovunque.**

3. **`dp()`**: cancellare la copia privata e aggiungere
   `import it.edilmilan.ge360.core.common.ui.dp`.

4. **Colori**: ogni `Color.rgb(...)` / `Color.parseColor(...)` va sostituito col
   token corrispondente. Se il colore non esiste nella palette, aggiungerlo a
   **entrambi** i file `ge360_palette.xml`, non al modulo.

5. **Spaziature**: i numeri dentro `setPadding` diventano `GeSpace.*`,
   i `textSize` diventano `GeType.*`.

6. **Liste**: `ListView` + `BaseAdapter` → `RecyclerView` + ViewHolder dentro un
   `GeListContainer`. Il punto non è la classe: è che `onCreateViewHolder`
   costruisca le viste **una volta** e `onBindViewHolder` si limiti a riempirle.
   Prima ogni riga veniva ricostruita da zero a ogni scorrimento.

7. **Letture**: ogni chiamata allo store dentro `onCreate`/`onResume`/una
   callback va dentro `geLoad`. Le ricerche dentro `geLoadDebounced`.

8. **Stato vuoto**: lista vuota → `geEmptyState`, con un'azione che risolve
   (azzera i filtri, crea il primo elemento, configura la cartella).

---

## Verifiche prima del merge

Da fare a mano, su dispositivo:

- [ ] avvio e navigazione tra le cinque sezioni, in chiaro e in scuro
- [ ] Spese: saldo del mese corretto, ricerca fluida, filtri, nuovo movimento,
      modifica, eliminazione, export/import, sincronizzazione Firefly
- [ ] Galleria: griglia scorrevole senza scatti, scatto, import multiplo,
      metadati, copia Drive, eliminazione
- [ ] barra in basso non coperta dalla maniglia di sistema (telefono a gesti)
- [ ] rotazione schermo su entrambe le schermate
- [ ] cambio tema chiaro/scuro con l'app aperta

Vale la pena attivare `StrictMode` nei build di debug: segnala da solo le
letture su thread UI che restano negli altri moduli.
