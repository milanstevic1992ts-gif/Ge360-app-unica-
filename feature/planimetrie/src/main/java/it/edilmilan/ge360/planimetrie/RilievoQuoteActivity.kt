package it.edilmilan.ge360.planimetrie

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.preventivi.EstimateItem
import it.edilmilan.ge360.preventivi.EstimateLifecycleStore
import it.edilmilan.ge360.preventivi.EstimateRecord
import it.edilmilan.ge360.preventivi.EstimateStatuses
import it.edilmilan.ge360.preventivi.EstimateStore
import it.edilmilan.ge360.preventivi.EstimatesActivity
import it.edilmilan.ge360.preventivi.RoomWorkCatalog
import it.edilmilan.ge360.preventivi.RoomWorkTemplate
import it.edilmilan.ge360.preventivi.recalculateEstimate
import java.util.Locale
import java.util.UUID

/**
 * Flusso operativo principale:
 * Rilievi già misurati -> Preventivi -> locale -> muro/pavimento/soffitto -> lavori.
 *
 * Nessuna seconda raccolta di misure, nessuna checklist di otto schermate.
 * Scrive solo righe con prefisso RILIEVO-QUOTE/<plan>/... nel preventivo scelto.
 */
class RilievoQuoteActivity : AppCompatActivity() {
    private lateinit var plans: FloorPlanStore
    private lateinit var quotes: EstimateStore
    private lateinit var content: LinearLayout
    private var plan: FloorPlanRecord? = null
    private var estimate: EstimateRecord? = null
    private var roomId: String? = null
    private var scopeId: String? = null
    private var requestedEstimateId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ge360Brand.sync(this)
        plans = FloorPlanStore(this)
        quotes = EstimateStore(this)
        requestedEstimateId = intent.getStringExtra(EXTRA_ESTIMATE_ID).orEmpty()
        val requestedPlan = intent.getStringExtra(EXTRA_PLAN_ID)
        plan = requestedPlan?.let(plans::get)
        if (plan == null && requestedEstimateId.isNotBlank()) {
            plan = plans.list().firstOrNull { it.estimateId == requestedEstimateId }
        }
        refreshQuote()
        if (savedInstanceState != null) {
            roomId = savedInstanceState.getString("room")
            scopeId = savedInstanceState.getString("scope")
            if (plan == null) plan = savedInstanceState.getString("plan")?.let(plans::get)
            refreshQuote()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ge360Brand.Background)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(30))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) {
            plan = plan?.ge360Id?.let(plans::get)
            refreshQuote()
            render()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("plan", plan?.ge360Id)
        outState.putString("room", roomId)
        outState.putString("scope", scopeId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::plans.isInitialized) plans.close()
        if (::quotes.isInitialized) quotes.close()
        super.onDestroy()
    }

    private fun refreshQuote() {
        // Non collegare mai automaticamente un preventivo a un altro rilievo.
        val selected = plan?.estimateId.orEmpty()
        estimate = selected.takeIf { it.isNotBlank() }?.let(quotes::getByGe360Id)
    }

    private fun render() {
        content.removeAllViews()
        val back = MaterialButton(this).apply {
            text = "← Indietro"
            isAllCaps = false
            setOnClickListener { navigateBack() }
            backgroundTintList = ColorStateList.valueOf(Ge360Brand.Surface)
            setTextColor(Ge360Brand.Text)
        }
        content.addView(back, LinearLayout.LayoutParams(-2, dp(44)))
        title("Preventivo dal rilievo", 25)
        small("1 · Scegli rilievo   2 · Stanza   3 · Superficie   4 · Lavorazioni")
        when {
            plan == null -> showPlans()
            estimate == null -> showQuoteLink()
            roomId == null -> showRooms()
            scopeId == null -> showSurfaces()
            else -> showWorks()
        }
    }

    private fun showPlans() {
        title("Scegli un rilievo già salvato", 18)
        small("Le misure si prendono da Rilievi. Non devi inserirle di nuovo.")
        val list = plans.list()
        if (list.isEmpty()) {
            text("Non ci sono rilievi nativi nell'archivio. Prima crea e salva il rilievo.", true)
            action("Apri Rilievi", true) {
                startActivity(Intent(this, FloorPlansActivity::class.java))
            }
            return
        }
        list.forEach { record ->
            val rooms = plans.rooms(record.ge360Id)
            card(record.title, "${rooms.size} stanze · " +
                record.clientName.ifBlank { "Cliente da indicare" } +
                if (record.estimateId.isBlank()) "" else " · Preventivo collegato") {
                plan = record
                roomId = null; scopeId = null
                refreshQuote(); render()
            }
        }
        action("Apri Rilievi per correggere misure", false) {
            startActivity(Intent(this, FloorPlansActivity::class.java))
        }
    }

    private fun showQuoteLink() {
        val p = plan ?: return
        title(p.title, 20)
        small("Rilievo selezionato · ${plans.rooms(p.ge360Id).size} ambienti")
        text("Ora colleghiamo il rilievo a un preventivo. Le metrature restano nel rilievo.", true)
        if (requestedEstimateId.isNotBlank()) {
            val requested = quotes.getByGe360Id(requestedEstimateId)
            if (requested != null && canEdit(requested)) {
                action("Collega alla bozza selezionata · " +
                    requested.number.ifBlank { requested.title }, true) {
                    AlertDialog.Builder(this)
                        .setTitle("Conferma collegamento")
                        .setMessage("Usare le stanze di " + p.title +
                            " nel preventivo " + requested.number +
                            "? Nessuna metratura o lavorazione precedente viene modificata automaticamente.")
                        .setPositiveButton("Collega") { _, _ ->
                            val used = plans.list().firstOrNull { it.ge360Id != p.ge360Id && it.estimateId == requested.ge360Id }
                            if (used != null) toast("Preventivo già collegato al rilievo " + used.title)
                            else {
                                p.estimateId = requested.ge360Id
                                plans.save(p)
                                requestedEstimateId = ""
                                estimate = requested
                                render()
                            }
                        }
                        .setNegativeButton("Annulla", null)
                        .show()
                }
            }
        }
        action("＋ Crea preventivo da questo rilievo", true) { createQuote(p) }
        action("Collega a una bozza esistente", false) { chooseDraft(p) }
        action("Scegli un altro rilievo", false) { plan = null; roomId = null; scopeId = null; render() }
    }

    private fun createQuote(p: FloorPlanRecord) {
        if (plans.rooms(p.ge360Id).isEmpty()) return toast("Rilievo privo di ambienti")
        runCatching {
            val record = EstimateRecord(
                ge360Id = UUID.randomUUID().toString(),
                title = "Preventivo · " + p.title,
                workType = p.kind,
                jobsiteTitle = p.title,
                clientName = p.clientName,
                status = EstimateStatuses.DRAFT
            )
            quotes.save(record)
            val saved = requireNotNull(quotes.getByGe360Id(record.ge360Id))
            p.estimateId = saved.ge360Id
            plans.save(p)
            estimate = saved
            requestedEstimateId = ""
            roomId = null; scopeId = null
            render()
        }.onFailure { toast(it.message ?: "Impossibile creare il preventivo") }
    }

    private fun chooseDraft(p: FloorPlanRecord) {
        val list = quotes.list().filter {
            it.status in setOf(EstimateStatuses.DRAFT, EstimateStatuses.REVIEW) &&
                !isLocked(it)
        }
        if (list.isEmpty()) return toast("Nessuna bozza modificabile disponibile")
        AlertDialog.Builder(this)
            .setTitle("Collega a quale preventivo?")
            .setItems(list.map {
                it.number + " · " + it.clientName.ifBlank { it.title }
            }.toTypedArray()) { _, index ->
                val chosen = list[index]
                val usedElsewhere = plans.list().firstOrNull {
                    it.ge360Id != p.ge360Id && it.estimateId == chosen.ge360Id
                }
                if (usedElsewhere != null) {
                    toast("Preventivo già collegato a " + usedElsewhere.title)
                    return@setItems
                }
                p.estimateId = chosen.ge360Id
                plans.save(p)
                estimate = chosen
                requestedEstimateId = ""
                render()
            }
            .setNegativeButton("Annulla", null).show()
    }

    private fun showRooms() {
        val p = plan ?: return
        val quote = estimate ?: return
        title(p.title, 19)
        small("Rilievo → Preventivo " + quote.number.ifBlank { "in bozza" })
        headlineTotal(quote)
        if (!canEdit(quote)) {
            text("Questo preventivo non è più una bozza modificabile. Le misure restano consultabili.", true)
            action("Apri preventivo", true) { openEstimate(quote) }
            return
        }
        title("Scegli la stanza", 18)
        val rooms = plans.rooms(p.ge360Id)
        if (rooms.isEmpty()) text("Nessuna stanza salvata. Torna al rilievo e completa le misure.", true)
        rooms.forEach { room ->
            val workCount = quote.items.count {
                it.priceListCode.startsWith("${RilievoQuoteEngine.CODE_PREFIX}${p.ge360Id}/${room.ge360Id}/")
            }
            card(room.name, "${room.floorArea().fmt()} m² pav. · ${room.wallArea().fmt()} m² pareti · ${workCount} lavori") {
                roomId = room.ge360Id; scopeId = null; render()
            }
        }
        action("Apri preventivo completo / totale", true) { openEstimate(quote) }
        action("Correggi il rilievo originale", false) {
            startActivity(Intent(this, FloorPlansActivity::class.java))
        }
        action("Cambia rilievo", false) { plan = null; estimate = null; render() }
    }

    private fun showSurfaces() {
        val p = plan ?: return
        val room = plans.rooms(p.ge360Id).firstOrNull { it.ge360Id == roomId } ?: run {
            roomId = null; render(); return
        }
        val walls = plans.walls(room.ge360Id)
        title(room.name, 20)
        small("Misure dal rilievo · scegli DOVE fare il lavoro")
        card("Rilievo della stanza", room.summary()) {
            startActivity(Intent(this, FloorPlansActivity::class.java))
        }
        title("Seleziona la superficie", 17)
        if (walls.isEmpty()) {
            small("Non hai ancora quotato i muri uno per uno. Puoi usare pareti complessive, soffitto e pavimento, oppure tornare in Rilievi per aggiungere le pareti.")
            action("Quota le pareti in Rilievi", false) {
                startActivity(Intent(this, FloorPlansActivity::class.java))
            }
        }
        val quote = estimate ?: return
        RilievoQuoteEngine.targets(room, walls).forEach { target ->
            val workCount = quote.items.count {
                RilievoQuoteEngine.belongs(it, p.ge360Id, room.ge360Id, target.id)
            }
            card(target.title, target.detail + " · $workCount lavori") {
                scopeId = target.id; render()
            }
        }
    }

    private fun showWorks() {
        val p = plan ?: return
        val quote = estimate ?: return
        val room = plans.rooms(p.ge360Id).firstOrNull { it.ge360Id == roomId } ?: run {
            roomId = null; scopeId = null; render(); return
        }
        val walls = plans.walls(room.ge360Id)
        val target = RilievoQuoteEngine.targets(room, walls).firstOrNull { it.id == scopeId } ?: run {
            scopeId = null; render(); return
        }
        title("${room.name} / ${target.title}", 19)
        small(target.detail)
        if (!canEdit(quote)) {
            text("Preventivo non modificabile: torna all'archivio Preventivi.", true)
            return
        }
        val items = quote.items.filter {
            RilievoQuoteEngine.belongs(it, p.ge360Id, room.ge360Id, target.id)
        }
        val catalogue = RoomWorkCatalog.forType(RilievoQuoteEngine.roomType(room.roomType))
        val missing = items.count { it.quantity <= 0.0 || (it.included && !it.optional && it.unitPrice <= 0.0) }
        text("${items.size} lavorazioni · $missing quantità/prezzi da confermare", true)
        action("＋ SCEGLI LE LAVORAZIONI", true) {
            pickWorks(p, room, walls, target, catalogue)
        }
        if (items.isEmpty()) {
            small("Nessuna lavorazione ancora. Tocca Scegli le lavorazioni, spunta i lavori e conferma.")
        } else {
            items.forEach { item ->
                val geometry = RilievoQuoteEngine.snapshot(room, walls)
                val changed = RilievoQuoteEngine.previousFingerprint(item)?.let { it != geometry } ?: false
                val note = item.quantity.fmt() + " " + item.unit +
                    " × € " + item.unitPrice.fmt() +
                    (if (item.optional) " · OPTIONAL" else "") +
                    (if (changed) " · ⚠ RILIEVO MODIFICATO" else "")
                card(item.name.substringBeforeLast(" · "), note) {
                    editWork(p, room, walls, target, item)
                }
            }
            small("Tocca una lavorazione per modificare quantità o prezzo.")
        }
        headlineTotal(quote)
        action("← Altre superfici di " + room.name, false) { scopeId = null; render() }
        action("← Altre stanze", false) { roomId = null; scopeId = null; render() }
        action("Apri preventivo completo", false) { openEstimate(quote) }
    }

    private fun pickWorks(
        p: FloorPlanRecord, room: FloorPlanRoom, walls: List<FloorPlanWall>,
        target: RilievoQuoteEngine.Target, templates: List<RoomWorkTemplate>
    ) {
        val existing = estimate ?: return
        val existingCodes = existing.items.mapNotNull {
            RilievoQuoteEngine.workCode(it, p.ge360Id, room.ge360Id, target.id)
        }.toSet()
        val chosen = linkedSetOf<String>()
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        val search = EditText(this).apply {
            hint = "Cerca lavori: rasatura, SPC, pittura..."
            isSingleLine = true
        }
        holder.addView(search, LinearLayout.LayoutParams(-1, dp(52)))
        val counter = label("${existingCodes.size} già presenti · 0 nuove", 12, false)
        holder.addView(counter)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        holder.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(-1, dp(415)))
        fun update() {
            counter.text = "${existingCodes.size} già presenti · ${chosen.size} nuove"
        }
        fun filter(query: String) {
            list.removeAllViews()
            val q = query.trim().lowercase(Locale.ITALY)
            val visible = templates.filter {
                q.isBlank() || (it.title + " " + it.category + " " + it.code)
                    .lowercase(Locale.ITALY).contains(q)
            }
            visible.groupBy { it.category }.forEach { (group, rows) ->
                list.addView(label(group.uppercase(Locale.ITALY), 12, true))
                rows.forEach { work ->
                    list.addView(CheckBox(this).apply {
                        text = work.title + " · " + work.unit +
                            if (work.code in existingCodes) "  ✓ già inserita" else ""
                        isChecked = work.code in existingCodes || work.code in chosen
                        isEnabled = work.code !in existingCodes
                        minHeight = dp(48)
                        textSize = 14f
                        setTextColor(Ge360Brand.Text)
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) chosen += work.code else chosen -= work.code
                            update()
                        }
                    })
                }
            }
            if (visible.isEmpty()) list.addView(label("Nessuna lavorazione trovata", 14))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { filter(s?.toString().orEmpty()) }
        })
        filter("")
        update()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Lavorazioni · " + target.title)
            .setView(holder)
            .setPositiveButton("Aggiungi", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (chosen.isEmpty()) return@setOnClickListener dialog.dismiss()
                val current = requireQuoteEditable() ?: return@setOnClickListener
                val conflicts = chosen.filter { code ->
                    RilievoQuoteEngine.overlap(current, p.ge360Id, room.ge360Id, target.id, code)
                }
                if (conflicts.isNotEmpty()) return@setOnClickListener toast(
                    "${conflicts.size} lavori già presenti sull'intera stanza o su altre pareti. " +
                    "Rimuovili dalla vecchia superficie prima di aggiungerli qui."
                )
                val selected = templates.filter { it.code in chosen }
                selected.forEach { template ->
                    val itemCode = RilievoQuoteEngine.itemCode(
                        p.ge360Id, room.ge360Id, target.id, template.code
                    )
                    if (current.items.none { it.priceListCode == itemCode }) {
                        current.items += RilievoQuoteEngine.item(p, room, walls, target, template)
                    }
                }
                if (saveQuote(current)) { dialog.dismiss(); render() }
            }
        }
        dialog.show()
    }

    private fun editWork(
        p: FloorPlanRecord, room: FloorPlanRoom, walls: List<FloorPlanWall>,
        target: RilievoQuoteEngine.Target, item: EstimateItem
    ) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(10))
        }
        wrapper.addView(label(item.name, 16, true))
        val template = RoomWorkCatalog.forType(RilievoQuoteEngine.roomType(room.roomType))
            .firstOrNull {
                it.code == RilievoQuoteEngine.workCode(item, p.ge360Id, room.ge360Id, target.id)
            }
        val measured = template?.let { RilievoQuoteEngine.suggestedQuantity(room, walls, target.id, it) } ?: 0.0
        wrapper.addView(label(
            "Misura dal rilievo: " + (if (measured > 0.0) measured.fmt() + " " + item.unit else "da inserire") +
                ". Le quantità non vengono cambiate automaticamente se modifichi il rilievo.", 12
        ))
        wrapper.addView(label("Quantità (" + item.unit + ")", 13))
        val qty = number(item.quantity)
        wrapper.addView(qty)
        wrapper.addView(label("Prezzo unitario / a corpo (€)", 13))
        val price = number(item.unitPrice)
        wrapper.addView(price)
        val optional = CheckBox(this).apply {
            text = "Optional · fuori dal totale"
            isChecked = item.optional
        }
        wrapper.addView(optional)
        val dialog = AlertDialog.Builder(this)
            .setTitle(target.title)
            .setView(ScrollView(this).apply { addView(wrapper) })
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .setNeutralButton("Rimuovi", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val quantity = parse(qty)
                val unitPrice = parse(price)
                if (quantity == null || unitPrice == null) return@setOnClickListener toast("Inserisci valori validi")
                val current = requireQuoteEditable() ?: return@setOnClickListener
                val fresh = current.items.firstOrNull { it.priceListCode == item.priceListCode }
                    ?: return@setOnClickListener toast("Voce non trovata")
                fresh.quantity = quantity
                fresh.unitPrice = unitPrice
                fresh.optional = optional.isChecked
                // L'operatore ha rivisto la quantità dopo una eventuale revisione geometrica.
                fresh.internalNote = RilievoQuoteEngine.item(p, room, walls, target,
                    requireNotNull(template)).internalNote
                if (saveQuote(current)) { dialog.dismiss(); render() }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(this@RilievoQuoteActivity)
                    .setTitle("Rimuovere questa lavorazione?")
                    .setMessage(item.name + "\nLa rimozione riguarda solo questa superficie.")
                    .setPositiveButton("Rimuovi") { _, _ ->
                        val current = requireQuoteEditable() ?: return@setPositiveButton
                        current.items.removeAll { it.priceListCode == item.priceListCode }
                        if (saveQuote(current)) { dialog.dismiss(); render() }
                    }
                    .setNegativeButton("Annulla", null).show()
            }
        }
        dialog.show()
    }

    private fun canEdit(quote: EstimateRecord): Boolean =
        quote.status in setOf(EstimateStatuses.DRAFT, EstimateStatuses.REVIEW) && !isLocked(quote)

    private fun isLocked(quote: EstimateRecord): Boolean =
        EstimateLifecycleStore(this).use { it.isLocked(quote.ge360Id) }

    private fun requireQuoteEditable(): EstimateRecord? {
        val id = estimate?.ge360Id ?: return null
        val current = quotes.getByGe360Id(id) ?: return null
        if (!canEdit(current)) {
            toast("Preventivo bloccato o non più in bozza")
            return null
        }
        return current
    }

    private fun saveQuote(quote: EstimateRecord): Boolean = runCatching {
        require(canEdit(quote)) { "Preventivo non modificabile" }
        recalculateEstimate(quote)
        quotes.save(quote)
        estimate = quotes.getByGe360Id(quote.ge360Id)
        true
    }.onFailure { toast(it.message ?: "Salvataggio preventivo non riuscito") }.getOrDefault(false)

    private fun headlineTotal(quote: EstimateRecord) {
        text("Totale provvisorio € " + quote.finalTotal.fmt(), true)
        val pending = quote.items.count { item ->
            item.priceListCode.startsWith(RilievoQuoteEngine.CODE_PREFIX) &&
                !item.optional && (item.quantity <= 0.0 || item.unitPrice <= 0.0)
        }
        if (pending > 0) small("⚠ $pending quantità o prezzi da confermare prima dell'invio.")
    }

    private fun openEstimate(quote: EstimateRecord) {
        startActivity(Intent(this, EstimatesActivity::class.java).apply {
            putExtra(EstimatesActivity.EXTRA_ESTIMATE_ID, quote.ge360Id)
        })
    }

    private fun navigateBack() {
        when {
            scopeId != null -> scopeId = null
            roomId != null -> roomId = null
            plan != null -> { plan = null; estimate = null; requestedEstimateId = "" }
            else -> { finish(); return }
        }
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = navigateBack()

    private fun title(t: String, size: Int) { content.addView(label(t, size, true)) }
    private fun text(t: String, strong: Boolean = false) {
        content.addView(label(t, if (strong) 16 else 14, strong))
    }
    private fun small(t: String) { content.addView(label(t, 13)) }
    private fun label(t: String, size: Int, strong: Boolean = false): TextView =
        TextView(this).apply {
            text = t
            textSize = size.toFloat()
            setTextColor(if (strong) Ge360Brand.Text else Ge360Brand.TextSecondary)
            if (strong) setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(9), dp(4), dp(9))
        }
    private fun action(t: String, primary: Boolean, click: () -> Unit) {
        content.addView(MaterialButton(this).apply {
            text = t; isAllCaps = false
            minHeight = dp(52)
            backgroundTintList = ColorStateList.valueOf(
                if (primary) Ge360Brand.PrimaryDark else Ge360Brand.Surface)
            setTextColor(if (primary) Ge360Brand.OnPrimary else Ge360Brand.Text)
            setOnClickListener { click() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }
    private fun card(name: String, detail: String, click: () -> Unit) {
        content.addView(MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Ge360Brand.Border
            setCardBackgroundColor(Ge360Brand.Surface)
            isClickable = true; isFocusable = true
            setOnClickListener { click() }
            addView(LinearLayout(this@RilievoQuoteActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                addView(label(name, 16, true))
                addView(label(detail, 13))
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
    }
    private fun number(value: Double): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        isSingleLine = true
        setText(if (value > 0.0) value.fmt() else "")
    }
    private fun parse(field: EditText): Double? {
        val input = field.text.toString().trim().replace(',', '.')
        if (input.isEmpty()) return 0.0
        return input.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    }
    private fun Double.fmt() = String.format(Locale.ITALY, "%.2f", this)
    private fun dp(x: Int) = (resources.displayMetrics.density * x).toInt()
    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    companion object {
        const val EXTRA_PLAN_ID = "ge360_survey_quote_plan_id"
        const val EXTRA_ESTIMATE_ID = "ge360_survey_quote_estimate_id"
    }
}
