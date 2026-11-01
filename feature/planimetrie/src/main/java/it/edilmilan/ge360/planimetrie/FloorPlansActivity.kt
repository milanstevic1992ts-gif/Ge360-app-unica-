package it.edilmilan.ge360.planimetrie

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import java.math.BigDecimal
import it.edilmilan.ge360.preventivi.MeasuredEstimateDraftV1
import it.edilmilan.ge360.preventivi.EstimateStatuses
import it.edilmilan.ge360.preventivi.MetricProvenanceV2
import it.edilmilan.ge360.preventivi.EstimatesActivity
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Toast
import it.edilmilan.ge360.preventivi.EstimateStore
import it.edilmilan.ge360.preventivi.StandardWorkCatalog
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class FloorPlansActivity : AppCompatActivity() {
    private lateinit var store: FloorPlanStore
    private lateinit var estimateStore: EstimateStore
    private lateinit var listRoot: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = FloorPlanStore(applicationContext)
        estimateStore = EstimateStore(applicationContext)
        setContentView(buildUi())
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_CREATE_NEW, false)) {
            listRoot.post { if (!isFinishing && !isDestroyed) newPlanDialog() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::listRoot.isInitialized) renderList()
    }

    override fun onDestroy() {
        store.close()
        estimateStore.close()
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(MaterialButton(this@FloorPlansActivity).apply {
                text = "‹"
                setOnClickListener { finish() }
                minWidth = dp(48)
            }, LinearLayout.LayoutParams(dp(54), dp(48)))
            addView(LinearLayout(this@FloorPlansActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@FloorPlansActivity).apply {
                    text = "Rilievi / Planimetrie"
                    textSize = 24f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#202327"))
                })
                addView(TextView(this@FloorPlansActivity).apply {
                    text = "Il rilievo nasce indipendente. Poi lo colleghi a preventivo o cantiere."
                    textSize = 12f
                    setTextColor(Color.parseColor("#74797F"))
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        })

        root.addView(MaterialButton(this).apply {
            text = "+ Nuovo rilievo"
            setOnClickListener { newPlanDialog() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(18) })

        listRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listRoot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        return ScrollView(this).apply { addView(root) }
    }

    private fun renderList() {
        listRoot.removeAllViews()
        val plans = store.list()
        if (plans.isEmpty()) {
            listRoot.addView(TextView(this).apply {
                text = "Nessun rilievo ancora. Puoi crearne uno anche prima di avere un cantiere."
                textSize = 14f
                setTextColor(Color.parseColor("#74797F"))
                setPadding(dp(4), dp(18), dp(4), dp(18))
            })
            return
        }
        plans.forEach { plan -> listRoot.addView(planCard(plan)) }
    }

    private fun planCard(plan: FloorPlanRecord): MaterialCardView {
        val roomCount = store.rooms(plan.ge360Id).size
        val linked = when {
            plan.jobsiteId.isNotBlank() && plan.estimateId.isNotBlank() -> "Cantiere + preventivo collegati"
            plan.jobsiteId.isNotBlank() -> "Cantiere collegato"
            plan.estimateId.isNotBlank() -> "Preventivo collegato"
            else -> "Non collegato"
        }
        return MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#E4E6E8")
            setCardBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            setOnClickListener { openPlan(plan.ge360Id) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            addView(LinearLayout(this@FloorPlansActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(TextView(this@FloorPlansActivity).apply {
                    text = plan.title
                    textSize = 17f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#202327"))
                })
                addView(TextView(this@FloorPlansActivity).apply {
                    text = listOf(plan.kind, "$roomCount ambienti", linked).joinToString(" · ")
                    textSize = 12f
                    setTextColor(Color.parseColor(if (linked == "Non collegato") "#B85700" else "#277A49"))
                    setPadding(0, dp(5), 0, 0)
                })
                if (plan.clientName.isNotBlank()) addView(TextView(this@FloorPlansActivity).apply {
                    text = "Cliente: ${plan.clientName}"
                    textSize = 12f
                    setTextColor(Color.parseColor("#666B71"))
                    setPadding(0, dp(4), 0, 0)
                })
                addView(TextView(this@FloorPlansActivity).apply {
                    text = "Aggiornato ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.ITALY).format(Date(plan.updatedAt))}"
                    textSize = 11f
                    setTextColor(Color.parseColor("#8A8E93"))
                    setPadding(0, dp(4), 0, 0)
                })
                addView(MaterialButton(this@FloorPlansActivity).apply {
                    text = "COLLEGA AMBIENTI / LAVORAZIONI"
                    setOnClickListener { chooseWorkBinding(plan) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
                val bindings = store.workBindings(plan.ge360Id)
                if (bindings.isNotEmpty()) {
                    addView(MaterialButton(this@FloorPlansActivity).apply {
                        text = "RIVEDI QUANTITÀ → BOZZA PREVENTIVO"
                        setOnClickListener { openMeasuredReview(plan) }
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
                    ))
                    addView(MaterialButton(this@FloorPlansActivity).apply {
                        text = "NUOVA VARIANTE · CONFRONTA METRATURE"
                        setOnClickListener { openMetricVariantProposal(plan) }
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
                    ))
                    val linkedRooms = store.rooms(plan.ge360Id).associateBy { it.ge360Id }
                    val stale = bindings.count { binding ->
                        linkedRooms[binding.roomGe360Id]?.let { room ->
                            !PlanGeometryQuantitiesV2.matches(
                                binding, room, store.walls(room.ge360Id)
                            )
                        } ?: true
                    }
                    addView(TextView(this@FloorPlansActivity).apply {
                        text = "${bindings.size} selezioni · " +
                            (if (stale > 0) "${stale} da rivedere dopo modifiche al rilievo" else
                                "geometria stanza invariata rispetto alla selezione") +
                            " · preventivi non modificati"
                        textSize = 11f
                        setTextColor(Color.parseColor(if (stale > 0) "#B85700" else "#277A49"))
                    })
                }
            })
        }
    }

    /**
     * Strict operator review: existing immutable source bindings, explicit manual
     * deduction and a disabled zero-price estimate item. Never modify prior rows.
     */
    /**
     * Surface-scoped V3 variant proposal. It never replaces, edits or prices the
     * original quote line; price delta is for reference only.
     */
    private fun openMetricVariantProposal(plan: FloorPlanRecord) {
        val rooms = store.rooms(plan.ge360Id).associateBy { it.ge360Id }
        val sourcesCache = mutableMapOf<String, List<MetricProvenanceV2>>()
        val selections = store.workBindings(plan.ge360Id).flatMap { newBinding ->
            val room = rooms[newBinding.roomGe360Id] ?: return@flatMap emptyList()
            if (!PlanGeometryQuantitiesV2.matches(
                    newBinding, room, store.walls(room.ge360Id))) return@flatMap emptyList()
            val sources = sourcesCache.getOrPut(newBinding.estimateGe360Id) {
                estimateStore.metricSources(newBinding.estimateGe360Id)
            }
            sources.filter { previous ->
                previous.trust == MetricProvenanceV2.TRUST_VERIFIED &&
                    previous.planGe360Id == newBinding.planGe360Id &&
                    previous.roomGe360Id == newBinding.roomGe360Id &&
                    previous.wallGe360Id.orEmpty() == newBinding.wallGe360Id.orEmpty() &&
                    previous.workCode == newBinding.workCode &&
                    previous.surface == newBinding.surface &&
                    previous.sourceBindingGe360Id != newBinding.ge360Id &&
                    previous.geometryFingerprint != newBinding.geometryFingerprint
            }.map { old -> Triple(room, newBinding, old) }
        }
        if (selections.isEmpty()) {
            Toast.makeText(this,
                "Non ci sono ancora nuove revisioni compatibili con metrature importate. " +
                    "Modifica il rilievo e collega nuovamente la superficie.",
                Toast.LENGTH_LONG).show()
            return
        }
        val labels = selections.map { (room, new, old) ->
            room.name + " · " + new.workCode + " · " + new.surface +
                " · " + String.format(Locale.ITALY, "%.2f → %.2f m²",
                    old.reviewedQuantityM2, new.quantityM2)
        }
        AlertDialog.Builder(this).setTitle("Confronta nuova metratura")
            .setItems(labels.toTypedArray()) { _, index ->
                val (room, newBinding, old) = selections[index]
                reviewMetricVariantProposal(plan, room, old, newBinding)
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun reviewMetricVariantProposal(
        plan: FloorPlanRecord,
        room: FloorPlanRoom,
        old: MetricProvenanceV2,
        newBinding: PlanWorkBindingV1
    ) {
        val quote = estimateStore.getByGe360Id(newBinding.estimateGe360Id)
            ?: return
        val previous = quote.items.singleOrNull { it.ge360Id == old.itemGe360Id }
            ?: run {
                Toast.makeText(this, "Voce originale non disponibile",
                    Toast.LENGTH_LONG).show()
                return
            }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(12))
        }
        box.addView(TextView(this).apply {
            text = "Preventivo: ${quote.number} · ${quote.status}\n" +
                "Ambiente: ${room.name}\n" +
                "Lavorazione: ${newBinding.workCode}\n" +
                "Superficie: ${newBinding.surface}\n" +
                "Vecchia revisione: " +
                String.format(Locale.ITALY, "%.2f m²", old.reviewedQuantityM2) + "\n" +
                "Nuova geometria: " +
                String.format(Locale.ITALY, "%.2f m²", newBinding.quantityM2) + "\n" +
                "Aperture già sottratte: " +
                String.format(Locale.ITALY, "%.2f m²", newBinding.openingDeductionsM2) + "\n" +
                "Prezzo precedente: " +
                String.format(Locale.ITALY, "%.2f €/m²", previous.unitPrice) +
                "\nIl preventivo originale NON verrà modificato."
            textSize = 13f
        })
        val additional = EditText(this).apply {
            hint = "Ulteriore detrazione nella nuova revisione (m²)"
            setText("0")
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val reason = EditText(this).apply {
            hint = "Motivo della variante · cosa è cambiato e perché (obbligatorio)"
            minLines = 2
        }
        val compare = TextView(this).apply { textSize = 14f }
        val refresh = MaterialButton(this).apply {
            text = "CALCOLA DIFFERENZA"
            setOnClickListener {
                runCatching {
                    val x = BigDecimal(additional.text.toString().trim()
                        .replace(',', '.')).setScale(2, java.math.RoundingMode.UNNECESSARY)
                    val next = BigDecimal.valueOf(newBinding.quantityM2).subtract(x)
                    require(next > BigDecimal.ZERO) { "La nuova superficie deve essere positiva" }
                    val delta = next.subtract(BigDecimal.valueOf(old.reviewedQuantityM2))
                    val euro = if (previous.included && !previous.optional)
                        delta.multiply(BigDecimal.valueOf(previous.unitPrice))
                            .setScale(2, java.math.RoundingMode.HALF_UP)
                    else BigDecimal.ZERO
                    compare.text = "Nuova misura ${next.toPlainString()} m² · " +
                        "Delta ${delta.toPlainString()} m² · " +
                        "Impatto indicativo ${euro.toPlainString()} € " +
                        "(solo riferimento, NON contabilizzato)"
                }.onFailure { compare.text = it.message ?: "Detrazione non valida" }
            }
        }
        box.addView(additional)
        box.addView(reason)
        box.addView(refresh)
        box.addView(compare)
        val confirm = CheckBox(this).apply {
            text = "Confermo la revisione geometrica: registra SOLO una proposta, " +
                "senza cambiare importi, fatture o preventivo originale."
        }
        box.addView(confirm)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Proponi variante di metratura")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Annulla", null)
            .setPositiveButton("SALVA PROPOSTA", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    require(confirm.isChecked) { "Confermare la proposta di variante" }
                    val actualRoom = store.rooms(plan.ge360Id)
                        .singleOrNull { it.ge360Id == room.ge360Id }
                        ?: error("Ambiente non trovato")
                    require(PlanGeometryQuantitiesV2.matches(newBinding,
                        actualRoom, store.walls(room.ge360Id))) {
                        "Geometria variata durante la revisione: ripetere l'operazione"
                    }
                    val deduction = BigDecimal(additional.text.toString().trim()
                        .replace(',', '.')).setScale(2, java.math.RoundingMode.UNNECESSARY)
                        .toDouble()
                    val motivation = reason.text.toString().trim()
                    val candidate = MeasuredEstimateDraftV1(
                        sourceBindingGe360Id = newBinding.ge360Id,
                        planGe360Id = newBinding.planGe360Id,
                        roomGe360Id = newBinding.roomGe360Id,
                        wallGe360Id = newBinding.wallGe360Id,
                        roomLabel = actualRoom.name,
                        workCode = newBinding.workCode,
                        surface = newBinding.surface,
                        geometryFingerprint = newBinding.geometryFingerprint,
                        measuredQuantityM2 = newBinding.quantityM2,
                        grossM2 = newBinding.grossM2,
                        openingDeductionsM2 = newBinding.openingDeductionsM2,
                        additionalDeductionM2 = deduction,
                        deductionReason = if (deduction > 0.0) motivation else ""
                    )
                    require(candidate.valid()) { "Misura della variante non valida" }
                    estimateStore.proposeMetricVariant(
                        quote.ge360Id, quote.updatedAt,
                        old.itemGe360Id, candidate, motivation
                    )
                }.onSuccess { variant ->
                    dialog.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("Proposta di variante registrata")
                        .setMessage("Delta: " +
                            String.format(Locale.ITALY, "%+.2f m²", variant.deltaQuantityM2()) +
                            "\nImpatto solo indicativo: " +
                            String.format(Locale.ITALY, "%+.2f €", variant.referenceDeltaEuro()) +
                            "\n\nNessuna modifica al preventivo originale. " +
                            "La verifica tecnica si trova nella scheda Preventivi.")
                        .setPositiveButton("APRI PREVENTIVI") { _, _ ->
                            startActivity(Intent(this, EstimatesActivity::class.java)
                                .putExtra(EstimatesActivity.EXTRA_ESTIMATE_ID, quote.ge360Id))
                        }
                        .setNegativeButton("CHIUDI", null)
                        .show()
                }.onFailure {
                    Toast.makeText(this, it.message ?: "Variante non registrata",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun openMeasuredReview(plan: FloorPlanRecord) {
        val roomIndex = store.rooms(plan.ge360Id).associateBy { it.ge360Id }
        val bindings = store.workBindings(plan.ge360Id)
        if (bindings.isEmpty()) {
            Toast.makeText(this, "Prima collega almeno una lavorazione dal rilievo",
                Toast.LENGTH_LONG).show()
            return
        }
        val labels = bindings.map { binding ->
            val room = roomIndex[binding.roomGe360Id]
            val valid = room != null &&
                PlanGeometryQuantitiesV2.matches(binding, room, store.walls(room.ge360Id))
            val status = if (valid) "PRONTA PER REVISIONE" else "MISURE DA RICONFERMARE"
            "${room?.name ?: "Ambiente rimosso"} · ${binding.workCode} · " +
                "${binding.surface} · " +
                String.format(Locale.ITALY, "%.2f", binding.quantityM2) +
                " m² · $status"
        }
        AlertDialog.Builder(this)
            .setTitle("Selezioni dal rilievo")
            .setItems(labels.toTypedArray()) { _, index ->
                reviewMeasuredBinding(plan, bindings[index])
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun reviewMeasuredBinding(plan: FloorPlanRecord, binding: PlanWorkBindingV1) {
        val room = store.rooms(plan.ge360Id)
            .singleOrNull { it.ge360Id == binding.roomGe360Id }
        if (room == null || !PlanGeometryQuantitiesV2.matches(
                binding, room, store.walls(room.ge360Id))) {
            Toast.makeText(this, "Geometria variata: ripeti la selezione dal rilievo",
                Toast.LENGTH_LONG).show()
            return
        }
        val estimate = estimateStore.getByGe360Id(binding.estimateGe360Id)
        if (estimate == null || estimate.status !in setOf(
                EstimateStatuses.DRAFT, EstimateStatuses.REVIEW)) {
            Toast.makeText(this, "Serve un preventivo esistente in bozza o da controllare",
                Toast.LENGTH_LONG).show()
            return
        }
        if (plan.jobsiteId.isNotBlank() && estimate.jobsiteId.isNotBlank() &&
            plan.jobsiteId != estimate.jobsiteId) {
            Toast.makeText(this, "Cantiere del rilievo diverso da quello del preventivo",
                Toast.LENGTH_LONG).show()
            return
        }
        val work = StandardWorkCatalog.byCode(binding.workCode)
        if (work == null || work.unit != "m²") {
            Toast.makeText(this, "Lavorazione m² non più disponibile nel catalogo",
                Toast.LENGTH_LONG).show()
            return
        }
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        preview.addView(TextView(this).apply {
            text = "Preventivo ${estimate.number} · ${estimate.status}\n" +
                "Ambiente: ${room.name}\n" +
                "Lavorazione: ${work.code} · ${work.title}\n" +
                "Superficie: ${binding.surface}" +
                (binding.wallGe360Id?.let { "\nParete UUID: $it" } ?: "") +
                "\nLordo: ${String.format(Locale.ITALY, "%.2f", binding.grossM2)} m²" +
                "\nAperture già detratte: " +
                "${String.format(Locale.ITALY, "%.2f", binding.openingDeductionsM2)} m²" +
                "\nNetto rilevato: " +
                "${String.format(Locale.ITALY, "%.2f", binding.quantityM2)} m²"
            textSize = 14f
        })
        val deduction = EditText(this).apply {
            hint = "Ulteriore detrazione manuale m² (0 se nessuna)"
            setText("0")
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val reason = EditText(this).apply {
            hint = "Motivazione della detrazione (obbligatoria se diversa da zero)"
            minLines = 2
        }
        val relatedRows = estimate.items.filter { it.priceListCode == binding.workCode &&
            it.room.trim().equals(room.name.trim(), ignoreCase = true) }
        preview.addView(TextView(this).apply {
            text = if (relatedRows.isEmpty())
                "Nessuna voce con lo stesso codice e ambiente riconosciuta."
            else "ATTENZIONE: ${relatedRows.size} voci esistenti con codice e ambiente " +
                "uguali. La sovrapposizione di superfici già importate blocca l'operazione."
            textSize = 12f
            setPadding(0, dp(12), 0, dp(4))
        })
        preview.addView(deduction)
        preview.addView(reason)
        val verifyManual = CheckBox(this).apply {
            text = "Ho controllato le altre voci: non sto conteggiando due volte " +
                "una lavorazione manuale."
        }
        val verifyReason = EditText(this).apply {
            hint = "Motivo della verifica (se esistono voci manuali simili)"
        }
        preview.addView(verifyManual)
        preview.addView(verifyReason)
        val confirm = CheckBox(this).apply {
            text = "CONFERMO la quantità revisionata. Crea soltanto una voce " +
                "fuori totale con prezzo 0 €, da prezzare manualmente."
        }
        preview.addView(confirm)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Revisione metratura → bozza")
            .setView(ScrollView(this).apply { addView(preview) })
            .setNegativeButton("Annulla", null)
            .setPositiveButton("CONFERMA VOCE FUORI TOTALE", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    require(confirm.isChecked) { "Conferma esplicitamente la revisione" }
                    // Re-read geometry + quote immediately before touching the quote database.
                    val latestRoom = store.rooms(plan.ge360Id)
                        .singleOrNull { it.ge360Id == binding.roomGe360Id }
                        ?: error("Ambiente non più disponibile")
                    require(PlanGeometryQuantitiesV2.matches(
                        binding, latestRoom, store.walls(latestRoom.ge360Id)
                    )) { "Geometria modificata: selezione scaduta" }
                    val extra = BigDecimal(deduction.text.toString().trim()
                        .replace(',', '.')).toDouble()
                    val request = MeasuredEstimateDraftV1(
                        sourceBindingGe360Id = binding.ge360Id,
                        planGe360Id = binding.planGe360Id,
                        roomGe360Id = binding.roomGe360Id,
                        wallGe360Id = binding.wallGe360Id,
                        roomLabel = latestRoom.name,
                        workCode = binding.workCode,
                        surface = binding.surface,
                        geometryFingerprint = binding.geometryFingerprint,
                        measuredQuantityM2 = binding.quantityM2,
                        grossM2 = binding.grossM2,
                        openingDeductionsM2 = binding.openingDeductionsM2,
                        additionalDeductionM2 = extra,
                        deductionReason = reason.text.toString().trim(),
                        manualOverlapAcknowledged = verifyManual.isChecked,
                        manualOverlapReason = verifyReason.text.toString().trim()
                    )
                    require(request.valid()) {
                        "Controllare la detrazione: numeri non validi, eccedenti o senza motivo"
                    }
                    estimateStore.insertMeasuredReviewLine(
                        estimate.ge360Id, estimate.updatedAt, request
                    )
                }.onSuccess {
                    dialog.dismiss()
                    renderList()
                    AlertDialog.Builder(this)
                        .setTitle("Voce di revisione aggiunta")
                        .setMessage("Quantità trasferita nel preventivo ${estimate.number}. " +
                            "La nuova voce è fuori totale con prezzo 0 €. " +
                            "Le altre voci e i totali restano invariati.")
                        .setPositiveButton("APRI PREVENTIVO") { _, _ ->
                            startActivity(Intent(this, EstimatesActivity::class.java)
                                .putExtra(EstimatesActivity.EXTRA_ESTIMATE_ID, estimate.ge360Id))
                        }
                        .setNegativeButton("RESTA NEI RILIEVI", null)
                        .show()
                }.onFailure {
                    Toast.makeText(this,
                        it.message ?: "Trasferimento non eseguito", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun chooseWorkBinding(plan: FloorPlanRecord) {
        val rooms = store.rooms(plan.ge360Id)
        val estimates = estimateStore.list().filter { it.ge360Id.isNotBlank() &&
            it.status in setOf(EstimateStatuses.DRAFT, EstimateStatuses.REVIEW) }
            .filter { plan.jobsiteId.isBlank() || it.jobsiteId.isBlank() ||
                it.jobsiteId == plan.jobsiteId }
        val works = StandardWorkCatalog.search().filter { it.unit == "m²" }
        if (rooms.isEmpty() || estimates.isEmpty() || works.isEmpty()) {
            Toast.makeText(this,
                "Servono ambienti, un preventivo esistente e una lavorazione a m²",
                Toast.LENGTH_LONG).show()
            return
        }

        val surfaces = listOf(
            PlanWorkBindingV1.FLOOR to "Pavimento · stanza/e",
            PlanWorkBindingV1.CEILING to "Soffitto · stanza/e",
            PlanWorkBindingV1.WALLS to "Tutte le pareti · lordo",
            PlanWorkBindingV1.WALLS_NET to "Tutte le pareti · netto aperture",
            PlanWorkBindingV1.SINGLE_WALL_GROSS to "Pareti singole · lordo",
            PlanWorkBindingV1.SINGLE_WALL_NET to "Pareti singole · netto aperture"
        )
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val estimateSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@FloorPlansActivity,
                android.R.layout.simple_spinner_dropdown_item,
                estimates.map { it.number.ifBlank { "Preventivo" } + " · " + it.title })
        }
        val surfaceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@FloorPlansActivity,
                android.R.layout.simple_spinner_dropdown_item,
                surfaces.map { it.second })
        }
        val workSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@FloorPlansActivity,
                android.R.layout.simple_spinner_dropdown_item,
                works.map { it.code + " · " + it.title })
        }
        form.addView(TextView(this).apply { text = "PREVENTIVO ESISTENTE · NESSUN PREZZO MODIFICATO" })
        form.addView(estimateSpinner)
        form.addView(TextView(this).apply { text = "SUPERFICIE DA LAVORARE" })
        form.addView(surfaceSpinner)
        form.addView(TextView(this).apply { text = "LAVORAZIONE CATALOGO" })
        form.addView(workSpinner)

        val roomHeader = TextView(this).apply {
            text = "AMBIENTI DA SELEZIONARE · più di uno possibile"
            setPadding(0, dp(10), 0, dp(4))
        }
        val roomBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val selectedRooms = rooms.map { room ->
            room to CheckBox(this).apply {
                text = room.name + " · " +
                    String.format(Locale.ITALY, "%.2f", room.floorArea()) + " m² pav."
                roomBox.addView(this)
            }
        }
        form.addView(roomHeader)
        form.addView(MaterialButton(this).apply {
            text = "SELEZIONA / DESELEZIONA TUTTE LE STANZE"
            setOnClickListener {
                val checkAll = selectedRooms.any { !it.second.isChecked }
                selectedRooms.forEach { it.second.isChecked = checkAll }
            }
        })
        form.addView(roomBox)

        val wallHeader = TextView(this).apply {
            text = "PARETI SPECIFICHE · ogni parete è una faccia, non due"
            setPadding(0, dp(10), 0, dp(4))
        }
        val wallBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val selectedWalls = rooms.flatMap { room ->
            store.walls(room.ge360Id).mapIndexed { i, wall ->
                Triple(room, wall, CheckBox(this).apply {
                    text = room.name + " · Parete " + (i + 1) +
                        " · " + String.format(Locale.ITALY, "%.2f", wall.length) + " ml" +
                        FloorPlanWallOpenings.countLabel(wall.note).let {
                            label -> if (label.isBlank()) "" else " · " + label
                        }
                    wallBox.addView(this)
                })
            }
        }
        form.addView(wallHeader)
        val wallSelectAll = MaterialButton(this).apply {
            text = "SELEZIONA / DESELEZIONA TUTTE LE PARETI"
            setOnClickListener {
                val checkAll = selectedWalls.any { !it.third.isChecked }
                selectedWalls.forEach { it.third.isChecked = checkAll }
            }
        }
        form.addView(wallSelectAll)
        form.addView(wallBox)
        val guide = TextView(this).apply {
            text = "Le pareti nette sottraggono solo aperture valide e non sovrapposte. " +
                "Ambienti irregolari: il profilo deve essere chiuso. " +
                "Le quantità sono fotografie della geometria, NON nuove righe economiche."
            textSize = 12f
            setPadding(0, dp(8), 0, dp(8))
        }
        form.addView(guide)
        val preview = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(10))
        }
        form.addView(MaterialButton(this).apply {
            text = "CONTROLLA MISURE"
            setOnClickListener {
                runCatching {
                    val area = surfaces[surfaceSpinner.selectedItemPosition].first
                    val current = selectedMeasurements(area, selectedRooms, selectedWalls)
                    val total = current.sumOf { it.second.quantityM2 }
                    val deductions = current.sumOf { it.second.openingDeductionsM2 }
                    preview.text = current.size.toString() + " superfici selezionate · " +
                        String.format(Locale.ITALY, "%.2f", total) + " m²" +
                        " · aperture detratte: " +
                        String.format(Locale.ITALY, "%.2f", deductions) + " m²"
                }.onFailure { preview.text = it.message ?: "Controllare geometria e aperture" }
            }
        })
        form.addView(preview)
        surfaceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?,
                                        position: Int, id: Long) {
                val single = surfaces[position].first in listOf(
                    PlanWorkBindingV1.SINGLE_WALL_GROSS, PlanWorkBindingV1.SINGLE_WALL_NET
                )
                roomHeader.visibility = if (single) View.GONE else View.VISIBLE
                roomBox.visibility = if (single) View.GONE else View.VISIBLE
                wallHeader.visibility = if (single) View.VISIBLE else View.GONE
                wallSelectAll.visibility = if (single) View.VISIBLE else View.GONE
                wallBox.visibility = if (single) View.VISIBLE else View.GONE
                preview.text = ""
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Rilievo → lavorazioni selezionate")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Annulla", null)
            .setPositiveButton("SALVA SELEZIONI", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    val estimate = estimates[estimateSpinner.selectedItemPosition]
                    require(plan.jobsiteId.isBlank() || estimate.jobsiteId.isBlank() ||
                        plan.jobsiteId == estimate.jobsiteId) {
                        "Preventivo collegato a un cantiere diverso"
                    }
                    val area = surfaces[surfaceSpinner.selectedItemPosition].first
                    val workCode = works[workSpinner.selectedItemPosition].code
                    val measured = selectedMeasurements(area, selectedRooms, selectedWalls)
                    store.addWorkBindings(measured.map { (room, quantity) ->
                        PlanWorkBindingV1.fromMeasured(
                            room, estimate.ge360Id, workCode, quantity
                        )
                    })
                }.onSuccess {
                    dialog.dismiss()
                    renderList()
                    Toast.makeText(this,
                        "Selezioni salvate: preventivo e prezzi invariati",
                        Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(this,
                        it.message ?: "Selezioni non salvate", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun selectedMeasurements(
        surface: String,
        selectedRooms: List<Pair<FloorPlanRoom, CheckBox>>,
        selectedWalls: List<Triple<FloorPlanRoom, FloorPlanWall, CheckBox>>
    ): List<Pair<FloorPlanRoom, PlanGeometryQuantitiesV2.Measured>> {
        val single = surface == PlanWorkBindingV1.SINGLE_WALL_GROSS ||
            surface == PlanWorkBindingV1.SINGLE_WALL_NET
        val roomWallRows = mutableMapOf<String, List<FloorPlanWall>>()
        fun walls(room: FloorPlanRoom) =
            roomWallRows.getOrPut(room.ge360Id) { store.walls(room.ge360Id) }
        val measurements = if (single) selectedWalls.filter { it.third.isChecked }
            .map { (room, wall, _) ->
                room to PlanGeometryQuantitiesV2.measure(
                    room, walls(room), surface, wall.ge360Id
                )
            }
        else selectedRooms.filter { it.second.isChecked }
            .map { (room, _) ->
                room to PlanGeometryQuantitiesV2.measure(room, walls(room), surface)
            }
        require(measurements.isNotEmpty()) {
            if (single) "Seleziona una o più pareti" else "Seleziona uno o più ambienti"
        }
        return measurements
    }

    private fun newPlanDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val title = EditText(this).apply { hint = "Nome rilievo / indirizzo"; isSingleLine = true }
        val kind = Spinner(this).apply { adapter = ArrayAdapter(this@FloorPlansActivity, android.R.layout.simple_spinner_dropdown_item, FloorPlanKinds.ALL) }
        val client = EditText(this).apply { hint = "Cliente (facoltativo)"; isSingleLine = true }
        val notes = EditText(this).apply { hint = "Note sopralluogo"; minLines = 3 }
        root.addView(title, lp(52)); root.addView(kind, lp(52)); root.addView(client, lp(52)); root.addView(notes, lp(92))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Nuovo rilievo")
            .setView(root)
            .setPositiveButton("Crea", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val clean = title.text.toString().trim()
                if (clean.isBlank()) { title.error = "Inserisci un nome"; return@setOnClickListener }
                val plan = FloorPlanRecord(
                    title = clean,
                    kind = kind.selectedItem.toString(),
                    clientName = client.text.toString().trim(),
                    notes = notes.text.toString().trim()
                )
                store.save(plan)
                dialog.dismiss()
                openPlan(plan.ge360Id)
            }
        }
        dialog.show()
    }

    private fun openPlan(id: String) {
        startActivity(Intent(this, FloorPlanEditorActivity::class.java).putExtra(FloorPlanEditorActivity.EXTRA_PLAN_ID, id))
    }

    private fun lp(h: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h)).apply { bottomMargin = dp(8) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_CREATE_NEW = "create_new_floor_plan"
    }
}
