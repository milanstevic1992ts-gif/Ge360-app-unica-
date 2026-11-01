package it.edilmilan.ge360.attrezzi

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.core.common.Ge360Contracts
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.core.common.ui.applyGe360Card
import it.edilmilan.ge360.core.common.ui.applyGe360Primary
import it.edilmilan.ge360.core.common.ui.applyGe360Secondary
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.scanner.DocumentStore
import java.util.Locale

/**
 * Operator-only V13 UI: no OCR mapping, no payment, invoice, stock or expense writes.
 * Shows separately planned, invoiced, delivered and consumed quantities.
 */
class MaterialEvidenceActivityV13 : AppCompatActivity() {
    private lateinit var ledger: MaterialDocumentEvidenceStoreV13
    private lateinit var inventory: MaterialInventoryStore
    private lateinit var plans: MaterialPlanStore
    private lateinit var scanner: DocumentStore
    private lateinit var sites: JobsiteStore
    private lateinit var body: LinearLayout
    private var jobFilter = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jobFilter = intent.getStringExtra(Ge360Contracts.EXTRA_JOBSITE_ID).orEmpty()
        ledger = MaterialDocumentEvidenceStoreV13(this)
        inventory = MaterialInventoryStore(this)
        plans = MaterialPlanStore(this)
        scanner = DocumentStore(this)
        sites = JobsiteStore(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
            setBackgroundColor(Ge360Brand.Background)
        }
        root.addView(buildScreenHeaderWithSubtitle(
            context = this, title = "Acquisti documentati",
            subtitle = "Fatture e DDT collegati manualmente ai materiali", onBack = { finish() }
        ))
        root.addView(label("Fatturato e consegnato restano separati. " +
            "Nessuna registrazione automatica di carichi, pagamenti o consumi.", 12f))
        root.addView(MaterialButton(this).apply {
            text = "+ COLLEGA RIGA FATTURA / DDT"
            applyGe360Primary()
            setOnClickListener { chooseLine() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(20))
        }
        root.addView(ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized) render()
    }

    override fun onDestroy() {
        if (::ledger.isInitialized) ledger.close()
        if (::inventory.isInitialized) inventory.close()
        if (::plans.isInitialized) plans.close()
        if (::scanner.isInitialized) scanner.close()
        if (::sites.isInitialized) sites.close()
        super.onDestroy()
    }

    private fun render() {
        body.removeAllViews()
        val rows = ledger.all().filter { jobFilter.isBlank() || it.jobsiteGe360Id == jobFilter }
        val active = rows.filter { it.active }
        val materials = inventory.list().associateBy { it.ge360Id }
        val docs = scanner.list().associateBy { it.ge360Id }
        val jobs = sites.list().associateBy { it.ge360Id }
        val trusted = active.filter { line ->
            val doc = docs[line.documentGe360Id]
            val item = materials[line.materialGe360Id]
            doc != null && doc.type.equals(line.documentKind, ignoreCase = true) &&
                (doc.jobsiteId.isBlank() || doc.jobsiteId == line.jobsiteGe360Id) &&
                doc.pdfPath.isNotBlank() && java.io.File(doc.pdfPath).isFile &&
                item != null && item.unit.equals(line.unit, ignoreCase = true) &&
                (line.jobsiteGe360Id.isBlank() || line.jobsiteGe360Id in jobs)
        }
        val plansByMaterial = plans.all()
        val fullHistory = inventory.hasCompleteMovementHistory()
        val movements = inventory.allMovementHistory(
            jobsiteGe360Id = jobFilter.takeIf { it.isNotBlank() })
        body.addView(label("RIEPILOGO MATERIALE · " + trusted.size +
            " righe verificabili · " + (active.size - trusted.size) +
            " da controllare · " + (rows.size - active.size) + " annullate", 12f))
        if (active.isEmpty()) body.addView(label("Nessuna riga attribuita. " +
            "Scegli il documento Scanner, il materiale, il cantiere e la quantità.", 13f))
        trusted.groupBy { it.materialGe360Id to it.jobsiteGe360Id }.forEach { (key, lines) ->
            val mat = materials[key.first]
            val unit = mat?.unit ?: lines.first().unit
            val values = MaterialDocumentEvidenceRulesV13.totals(
                trusted, key.first, key.second, unit)
            val plan = plansByMaterial.filter {
                it.materialGe360Id == key.first &&
                    it.jobsiteGe360Id.orEmpty() == key.second
            }.maxByOrNull { it.confirmedAt }
            val consumed = movements.filter {
                it.jobsiteId == key.second && it.materialGe360Id == key.first &&
                    it.type == MaterialStockMovement.TYPE_CONSUME &&
                    it.unit.trim().equals(unit.trim(), ignoreCase = true)
            }.sumOf { kotlin.math.abs(it.quantity) }
            val forecast = plan?.quantity?.let(::fmt) ?: "non registrato"
            val remaining = if (fullHistory) plan?.let {
                fmt(it.quantity - consumed) + " " + unit
            } ?: "non calcolabile" else "non calcolabile (storico incompleto)"
            val site = if (key.second.isBlank()) "Senza cantiere"
                else jobs[key.second]?.displayTitle() ?: "Cantiere mancante"
            addCard(mat?.name ?: "Materiale mancante", site + " · " + unit,
                "Previsto: " + forecast + " " + unit +
                    "\nFatturato: " + fmt(values.invoiced) + " " + unit +
                    "\nConsegnato DDT: " + fmt(values.delivered) + " " + unit +
                    "\nConsumo magazzino: " + fmt(consumed) + " " + unit +
                    "\nPrevisto − consumato: " + remaining +
                    "\nUnità non compatibili: " + values.invalidUnits +
                    "\nFatture e DDT NON sono sommabili.", null)
        }
        body.addView(label("RIGHE DOCUMENTALI CONFERMATE", 12f))
        rows.forEach { row ->
            val name = materials[row.materialGe360Id]?.name ?: "Materiale mancante"
            val doc = docs[row.documentGe360Id]
            addCard(
                (if (!row.active) "ANNULLATA · "
                 else if (row !in trusted) "DA VERIFICARE · " else "") + name,
                row.documentKind + " · " + (doc?.documentNumber ?: "documento non trovato"),
                fmt(row.quantity) + " " + row.unit + " · " +
                    (if (row.jobsiteGe360Id.isBlank()) "nessun cantiere"
                     else jobs[row.jobsiteGe360Id]?.displayTitle() ?: "cantiere mancante"),
                if (row.active) "ANNULLA RIGA" to {
                    AlertDialog.Builder(this).setTitle("Annullare questa riga?")
                        .setMessage("La riga resterà nello storico. " +
                            "PDF, registri Spese e magazzino resteranno invariati.")
                        .setNegativeButton("Indietro", null)
                        .setPositiveButton("Conferma annullamento") { _, _ ->
                            runCatching { ledger.void(row.ge360Id) }
                                .onSuccess { render() }
                                .onFailure { toast("Annullamento non riuscito") }
                        }.show()
                } else null)
        }
    }

    private fun chooseLine() {
        val docs = scanner.list().filter {
            it.type.uppercase(Locale.ROOT) in setOf("FATTURA", "DDT") &&
                it.ge360Id.isNotBlank()
        }
        val stock = inventory.list().filter { it.ge360Id.isNotBlank() }
        val jobs = sites.list()
        if (docs.isEmpty() || stock.isEmpty()) {
            toast("Servono un documento Scanner FATTURA/DDT e un materiale")
            return
        }
        val documents = select(docs.map {
            it.type + " · " + it.documentNumber.ifBlank { "senza numero" } +
                " · " + it.supplier.take(45)
        })
        val materials = select(stock.map { it.name + " · " + it.unit })
        val jobIds = listOf<String?>(null) + jobs.map { it.ge360Id }
        val sitesField = select(listOf("Senza cantiere") +
            jobs.map { it.code + " · " + it.displayTitle() })
        val index = jobIds.indexOf(jobFilter.takeIf { it.isNotBlank() })
        if (index >= 0) sitesField.setSelection(index)
        val quantity = EditText(this).apply {
            hint = "Es. 12,5"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            fun field(title: String, view: View) {
                addView(label(title, 11f))
                addView(view, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
            }
            field("DOCUMENTO SCANNER", documents)
            field("MATERIALE ESATTO", materials)
            field("CANTIERE (FACOLTATIVO)", sitesField)
            field("QUANTITÀ VERIFICATA SUL DOCUMENTO", quantity)
            addView(label("Non vengono generati acquisti da OCR o da descrizioni simili.", 11f))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Collega documento e materiale")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Indietro", null)
            .setPositiveButton("CONFERMA", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val d = docs[documents.selectedItemPosition]
                val m = stock[materials.selectedItemPosition]
                val j = jobIds[sitesField.selectedItemPosition]
                val amount = quantity.text.toString().replace(',', '.').toDoubleOrNull()
                if (amount == null || !amount.isFinite() || amount <= 0.0) {
                    toast("Quantità positiva richiesta")
                    return@setOnClickListener
                }
                AlertDialog.Builder(this).setTitle("Conferma attribuzione manuale")
                    .setMessage(d.type + " " + d.documentNumber + " · " + m.name +
                        "\n" + fmt(amount) + " " + m.unit +
                        "\n" + (j ?: "Senza cantiere") +
                        "\nNon modifica stock o contabilità.")
                    .setNegativeButton("Correggi", null)
                    .setPositiveButton("REGISTRA") { _, _ ->
                        runCatching { ledger.confirm(d, m, j, amount) }
                            .onSuccess { dialog.dismiss(); render(); toast("Riga registrata") }
                            .onFailure { toast(it.message ?: "Attribuzione non riuscita") }
                    }.show()
            }
        }
        dialog.show()
    }

    private fun select(values: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MaterialEvidenceActivityV13,
            android.R.layout.simple_spinner_dropdown_item, values)
    }
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Ge360Brand.TextSecondary)
        setPadding(dp(4), dp(9), dp(4), dp(9))
    }
    private fun addCard(
        heading: String, subtitle: String, description: String,
        action: Pair<String, () -> Unit>?
    ) {
        body.addView(MaterialCardView(this).apply {
            applyGe360Card(fill = Ge360Brand.Surface, stroke = Ge360Brand.Border, radiusDp = 17)
            addView(LinearLayout(this@MaterialEvidenceActivityV13).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                listOf(heading, subtitle, description).forEachIndexed { index, value ->
                    addView(label(value, if (index == 0) 15f else 12f).apply {
                        if (index == 0) setTextColor(Ge360Brand.ForestDark)
                    })
                }
                if (action != null) addView(MaterialButton(this@MaterialEvidenceActivityV13).apply {
                    text = action.first
                    applyGe360Secondary()
                    setOnClickListener { action.second.invoke() }
                })
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(7) })
    }
    private fun fmt(q: Double) = String.format(Locale.ITALY, "%.2f", q)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}
