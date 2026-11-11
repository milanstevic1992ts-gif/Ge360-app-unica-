package it.edilmilan.ge360.attrezzi

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import java.util.Locale

/**
 * Unico ingresso per "ricorda": riutilizza ToolsStore e la Checklist furgone.
 * Non duplica note, inventario o tabelle e non scrive nulla senza conferma.
 */
object ResourceReminderRules {
    const val TOOL_TO_BRING = 0
    const val MATERIAL_TO_BRING = 1
    const val MATERIAL_TO_BUY = 2

    val choices = listOf(
        "🔧 Attrezzo da portare",
        "📦 Materiale da portare",
        "🛒 Materiale da comprare"
    )

    fun kind(choice: Int): ResourceKind =
        if (choice == TOOL_TO_BRING) ResourceKind.TOOL else ResourceKind.MATERIAL

    fun status(choice: Int): String =
        if (choice == MATERIAL_TO_BUY) "Da comprare" else "Da portare"

    fun amount(input: String): Double? {
        val number = input.trim().replace(',', '.').toDoubleOrNull()
        return number?.takeIf { it.isFinite() && it > 0.0 && it <= 100000.0 }
    }

    /** Comprare non equivale a prendere/caricare: rimane una voce attiva. */
    fun needsPurchase(item: JobResource): Boolean =
        item.kind == ResourceKind.MATERIAL && item.status in setOf("Da comprare", "Mancante")

    fun afterPurchase(item: JobResource): String {
        require(needsPurchase(item)) { "Solo un materiale mancante può essere marcato comprato" }
        return "Comprato"
    }

    fun same(kind: ResourceKind, name: String, existing: JobResource): Boolean =
        existing.kind == kind && existing.name.trim().lowercase(Locale.ITALY) ==
            name.trim().lowercase(Locale.ITALY)

    fun findDuplicate(rows: List<JobResource>, kind: ResourceKind, name: String): JobResource? =
        rows.firstOrNull { same(kind, name, it) }
}

object ResourceReminderDialog {
    fun show(
        context: Context,
        preferredJobsiteId: String = "",
        suggestedTitle: String = "",
        onSaved: (JobsiteRecord) -> Unit = {}
    ) {
        val jobsDb = JobsiteStore(context)
        val jobs = try {
            jobsDb.list().filter {
                it.ge360Id.isNotBlank() &&
                    it.status !in setOf(JobsiteStore.STATUS_ARCHIVED, JobsiteStore.STATUS_COMPLETED)
            }
        } finally { jobsDb.close() }

        if (jobs.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("Prima scegli un cantiere")
                .setMessage("Per ritrovare gli attrezzi e i materiali nella checklist, serve un cantiere attivo. Le note generiche possono rimanere in Note e Agenda.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun field(hintValue: String, value: String = "") = EditText(context).apply {
            hint = hintValue
            setText(value)
            isSingleLine = true
        }
        fun label(text: String) = TextView(context).apply {
            this.text = text
            textSize = 13f
            setPadding(0, dp(10), 0, dp(2))
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
        }
        box.addView(label("Cantiere"))
        val jobSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                jobs.map { "${it.code} · ${it.displayTitle()}" })
            setSelection(jobs.indexOfFirst { it.ge360Id == preferredJobsiteId }.coerceAtLeast(0))
        }
        box.addView(jobSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        box.addView(label("Cosa ricordare?"))
        val name = field("Es. Flessibile, 6 sacchi di rasante", suggestedTitle)
        box.addView(name)

        box.addView(label("Destinazione"))
        val kindSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, ResourceReminderRules.choices)
        }
        box.addView(kindSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        box.addView(label("Quantità e unità"))
        val quantity = field("1", "1").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val unit = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, ToolsStore.UNITS)
        }
        val quantityRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        quantityRow.addView(quantity, LinearLayout.LayoutParams(0, dp(52), 1f))
        quantityRow.addView(unit, LinearLayout.LayoutParams(0, dp(52), 1f))
        box.addView(quantityRow)

        box.addView(label("Nota facoltativa (non copiata automaticamente dalle note originali)"))
        val note = EditText(context).apply {
            hint = "Es. Nel ripostiglio / portare due dischi di ricambio"
            minLines = 2
            maxLines = 4
        }
        box.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))

        val dialog = AlertDialog.Builder(context)
            .setTitle("Ricorda per il cantiere")
            .setMessage("La voce apparirà nella checklist del furgone. Per i materiali da comprare, prima spunterai Comprato e poi Preso.")
            .setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton("AGGIUNGI", null)
            .setNegativeButton("Annulla", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val job = jobs.getOrNull(jobSpinner.selectedItemPosition) ?: return@setOnClickListener
                val title = name.text.toString().trim()
                val amount = ResourceReminderRules.amount(quantity.text.toString())
                if (title.isBlank()) { name.error = "Scrivi cosa ricordare"; return@setOnClickListener }
                if (amount == null) { quantity.error = "Quantità positiva valida"; return@setOnClickListener }
                val kind = ResourceReminderRules.kind(kindSpinner.selectedItemPosition)
                val status = ResourceReminderRules.status(kindSpinner.selectedItemPosition)
                val unitName = ToolsStore.UNITS[unit.selectedItemPosition]
                val memo = note.text.toString().trim()

                val store = ToolsStore(context)
                val duplicate = try {
                    ResourceReminderRules.findDuplicate(store.jobResources(job.ge360Id), kind, title)
                } finally { store.close() }

                val submit: () -> Unit = {
                    runCatching {
                        val db = ToolsStore(context)
                        try {
                            // Rileggi prima di scrivere: stessa voce, stesso cantiere,
                            // stesso tipo. Niente duplicazioni accidentali.
                            val old = ResourceReminderRules.findDuplicate(
                                db.jobResources(job.ge360Id), kind, title
                            )
                            val row = old ?: JobResource(
                                jobsiteId = job.ge360Id,
                                jobsiteCode = job.code,
                                jobsiteTitle = job.displayTitle(),
                                kind = kind,
                                name = title
                            )
                            row.quantity = amount
                            row.unit = unitName
                            row.status = status
                            if (memo.isNotBlank()) row.note = memo
                            db.saveJobResource(row)
                        } finally { db.close() }
                    }.onSuccess {
                        dialog.dismiss()
                        Toast.makeText(context, "$title aggiunto alla checklist di ${job.code}", Toast.LENGTH_LONG).show()
                        onSaved(job)
                    }.onFailure {
                        Toast.makeText(context, it.message ?: "Impossibile salvare", Toast.LENGTH_LONG).show()
                    }
                }
                if (duplicate == null) submit()
                else AlertDialog.Builder(context)
                    .setTitle("Voce già presente")
                    .setMessage(
                        "$title è già nella lista di ${job.code}.\n" +
                            "Vuoi aggiornare quantità e stato? Le note esistenti restano, salvo nuova nota esplicita."
                    )
                    .setPositiveButton("AGGIORNA") { _, _ -> submit() }
                    .setNegativeButton("Annulla", null).show()
            }
        }
        dialog.show()
    }
}
