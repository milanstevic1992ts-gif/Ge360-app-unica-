package it.edilmilan.ge360.preventivi

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

object EstimateLifecycleUi {
    fun show(context: Context, estimate: EstimateRecord, onApplied: () -> Unit) {
        val lifecycle = EstimateLifecycleStore(context)
        val locked = lifecycle.isLocked(estimate.ge360Id)
        val children = lifecycle.children(lifecycle.rootEstimate(estimate.ge360Id))
        lifecycle.close()

        AlertDialog.Builder(context)
            .setTitle("Gestione contratto")
            .setMessage(buildString {
                append(if (locked) "🔒 Preventivo accettato e congelato" else "Preventivo modificabile")
                if (children.isNotEmpty()) append("\nDocumenti collegati: ").append(children.size)
            })
            .setItems(
                arrayOf(
                    "Storico modifiche",
                    if (locked) "Vedi snapshot accettato" else "Accetta e congela preventivo",
                    "+ Crea Extra",
                    "+ Crea Variante",
                    "Riepilogo contratto + extra",
                    "Prezzi intelligenti / storico",
                    "Calcolatore ricarico e margine"
                )
            ) { _, which ->
                when (which) {
                    0 -> showHistory(context, estimate)
                    1 -> if (locked) showAcceptedSnapshot(context, estimate) else acceptDialog(context, estimate, onApplied)
                    2 -> createChild(context, estimate, EstimateRelationTypes.EXTRA)
                    3 -> createChild(context, estimate, EstimateRelationTypes.VARIANT)
                    4 -> showContractSummary(context, estimate)
                    5 -> showPriceHistory(context)
                    6 -> marginCalculator(context)
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    fun ensureEditable(context: Context, estimate: EstimateRecord): Boolean {
        if (estimate.ge360Id.isBlank()) return true
        val store = EstimateLifecycleStore(context)
        val locked = store.isLocked(estimate.ge360Id)
        store.close()
        if (!locked) return true
        AlertDialog.Builder(context)
            .setTitle("Preventivo bloccato")
            .setMessage("Questo preventivo è stato accettato e congelato. Per cambiare lavorazioni o prezzi crea una Variante o un Extra: l'accordo originale resta invariato.")
            .setPositiveButton("Gestisci") { _, _ -> show(context, estimate) {} }
            .setNegativeButton("Chiudi", null)
            .show()
        return false
    }

    fun recordSaved(context: Context, estimate: EstimateRecord, title: String = "Preventivo aggiornato") {
        val store = EstimateLifecycleStore(context)
        runCatching { store.recordSmartSave(estimate, title) }
        store.close()
    }

    private fun showHistory(context: Context, estimate: EstimateRecord) {
        val store = EstimateLifecycleStore(context)
        if (store.history(estimate.ge360Id).isEmpty()) store.recordSmartSave(estimate, "Stato attuale registrato")
        val rows = store.history(estimate.ge360Id)
        store.close()
        val text = rows.joinToString("\n\n") { row ->
            "${formatDate(row.createdAt)} · ${row.title}" + if (row.detail.isBlank()) "" else "\n${row.detail}"
        }.ifBlank { "Nessun evento registrato" }
        textDialog(context, "Storico ${estimate.number}", text)
    }

    private fun acceptDialog(context: Context, estimate: EstimateRecord, onApplied: () -> Unit) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 10), dp(context, 18), dp(context, 8))
        }
        val signer = EditText(context).apply { hint = "Nome di chi accetta (facoltativo)"; isSingleLine = true }
        val note = EditText(context).apply { hint = "Nota accettazione (facoltativa)"; minLines = 2 }
        root.addView(TextView(context).apply {
            text = "Con LAVORO ACCETTATO verranno collegati un contratto congelato e un cantiere effettivo (stato Programmato). Le lavorazioni incluse diventeranno fasi operative, senza segnare il lavoro come già iniziato. Eventuali modifiche all'accordo originale passeranno da Extra o Variante."
        })
        root.addView(signer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 50)))
        root.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 80)))

        AlertDialog.Builder(context)
            .setTitle("LAVORO ACCETTATO · ${estimate.number}")
            .setView(root)
            .setPositiveButton("CONFERMA E ATTIVA", null)
            .setNegativeButton("Annulla", null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        runCatching {
                            EstimateAcceptanceEngine.acceptAndActivate(
                                context, estimate.ge360Id,
                                signer.text.toString(), note.text.toString()
                            )
                        }.onSuccess { result ->
                            dismiss()
                            onApplied()
                            toast(context, "✓ Lavoro accettato. Cantiere ${result.jobsiteCode} programmato.")
                        }.onFailure { error ->
                            toast(context, error.message ?: "Impossibile attivare il lavoro")
                        }
                    }
                }
                show()
            }
    }

    private fun showAcceptedSnapshot(context: Context, estimate: EstimateRecord) {
        val store = EstimateLifecycleStore(context)
        val snapshot = store.acceptedSnapshot(estimate.ge360Id)
        store.close()
        textDialog(context, "Snapshot accettato", snapshot ?: "Snapshot non disponibile")
    }

    private fun createChild(context: Context, source: EstimateRecord, type: String) {
        if (source.ge360Id.isBlank()) {
            toast(context, "Salva prima il preventivo")
            return
        }
        val estimates = EstimateStore(context)
        val lifecycle = EstimateLifecycleStore(context)
        try {
            val rootId = lifecycle.rootEstimate(source.ge360Id)
            val root = estimates.getByGe360Id(rootId) ?: source
            val existingCount = lifecycle.children(rootId).count { it.type == type }
            val child = EstimateRecord(
                clientId = root.clientId,
                clientGe360Id = root.clientGe360Id,
                clientName = root.clientName,
                jobsiteId = root.jobsiteId,
                jobsiteCode = root.jobsiteCode,
                jobsiteTitle = root.jobsiteTitle,
                title = "$type ${(existingCount + 1).toString().padStart(2, '0')} · ${root.title.ifBlank { root.workType }}",
                workType = root.workType,
                status = EstimateStatuses.DRAFT,
                documentType = type,
                validityDays = root.validityDays,
                generalDescription = if (type == EstimateRelationTypes.EXTRA) "Lavorazioni extra rispetto al preventivo ${root.number}." else "Variante rispetto al preventivo ${root.number}.",
                materialsIncluded = "",
                materialsClient = root.materialsClient,
                exclusions = root.exclusions,
                finalNotes = "Documento collegato a ${root.number}."
            )
            estimates.save(child)
            val relation = lifecycle.link(child.ge360Id, rootId, type)
            lifecycle.recordEvent(child, "CREATE_$type", "$type ${relation.sequence} creato", "Collegato a ${root.number}", force = true)
            toast(context, "Creato ${child.number} · $type ${relation.sequence}")
        } finally {
            estimates.close()
            lifecycle.close()
        }
    }

    private fun showContractSummary(context: Context, estimate: EstimateRecord) {
        val estimates = EstimateStore(context)
        val lifecycle = EstimateLifecycleStore(context)
        try {
            val rootId = lifecycle.rootEstimate(estimate.ge360Id)
            val root = estimates.getByGe360Id(rootId) ?: estimate
            val acceptedJson = lifecycle.acceptedSnapshot(rootId)
            val accepted = acceptedJson?.let { runCatching { JSONObject(it) }.getOrNull() }
            val base = accepted?.optDouble("finalTotal", root.finalTotal) ?: root.finalTotal
            val baseStatus = if (accepted != null) "Accettato · snapshot" else root.status
            val children = lifecycle.children(rootId)
            val rows = children.mapNotNull { rel -> estimates.getByGe360Id(rel.childGe360Id)?.let { rel to it } }
            val approvedChildren = rows.filter { (_, child) -> child.status in setOf(EstimateStatuses.ACCEPTED, EstimateStatuses.IN_PROGRESS, EstimateStatuses.COMPLETED) }
            val approvedAdjustments = approvedChildren.sumOf { it.second.finalTotal }
            val total = base + approvedAdjustments
            val text = buildString {
                append("CONTRATTO BASE\n")
                append(root.number).append(" · ").append(euro(base)).append(" · ").append(baseStatus)
                if (rows.isNotEmpty()) {
                    append("\n\nDOCUMENTI COLLEGATI\n")
                    rows.forEach { (rel, child) ->
                        append("• ").append(rel.type).append(' ').append(rel.sequence)
                            .append(" · ").append(child.number)
                            .append(" · ").append(euro(child.finalTotal))
                            .append(" · ").append(child.status).append('\n')
                    }
                }
                append("\nExtra/varianti approvati: ").append(euro(approvedAdjustments))
                append("\nVALORE CONTRATTUALE AGGIORNATO: ").append(euro(total))
            }
            textDialog(context, "Contratto + varianti", text)
        } finally {
            estimates.close()
            lifecycle.close()
        }
    }

    private fun showPriceHistory(context: Context) {
        val estimates = EstimateStore(context)
        try {
            val stats = EstimateLearningEngine.stats(estimates)
            if (stats.isEmpty()) {
                textDialog(context, "Prezzi intelligenti", "Non ci sono ancora abbastanza preventivi accettati/completati con prezzi valorizzati. GE360 inizierà a costruire lo storico mentre lo usi.")
                return
            }
            val text = stats.take(60).joinToString("\n\n") { s ->
                "${s.name} [${s.unit}]\n" +
                    "Usata ${s.uses} volte · ultimo ${euro(s.lastPrice)} · medio ${euro(s.averagePrice)} · min ${euro(s.minPrice)} · max ${euro(s.maxPrice)}"
            }
            textDialog(context, "Prezzi reali GE360", text)
        } finally { estimates.close() }
    }

    private fun marginCalculator(context: Context) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 8), dp(context, 18), dp(context, 8))
        }
        fun field(hint: String) = EditText(context).apply {
            this.hint = hint
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 50)))
        }
        val cost = field("Costo €")
        val percent = field("Percentuale %")
        val out = TextView(context).apply { setPadding(0, dp(context, 10), 0, 0) }
        root.addView(out)
        fun parse(e: EditText) = e.text.toString().trim().replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

        val dialog = AlertDialog.Builder(context)
            .setTitle("Ricarico ≠ margine")
            .setView(root)
            .setPositiveButton("Calcola margine", null)
            .setNeutralButton("Calcola ricarico", null)
            .setNegativeButton("Chiudi", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val c = parse(cost); val p = parse(percent)
                val price = EstimateLearningEngine.priceFromMargin(c, p)
                out.text = "Per ottenere margine $p% su costo ${euro(c)} → prezzo ${euro(price)}"
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val c = parse(cost); val p = parse(percent)
                val price = EstimateLearningEngine.priceFromMarkup(c, p)
                out.text = "Con ricarico $p% su costo ${euro(c)} → prezzo ${euro(price)}"
            }
        }
        dialog.show()
    }

    private fun textDialog(context: Context, title: String, text: String) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(ScrollView(context).apply {
                addView(TextView(context).apply {
                    this.text = text
                    setTextIsSelectable(true)
                    setPadding(dp(context, 18), dp(context, 10), dp(context, 18), dp(context, 18))
                })
            })
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    private fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private fun euro(v: Double) = NumberFormat.getCurrencyInstance(Locale.ITALY).format(v)
    private fun formatDate(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.ITALY).format(Date(value))
}
