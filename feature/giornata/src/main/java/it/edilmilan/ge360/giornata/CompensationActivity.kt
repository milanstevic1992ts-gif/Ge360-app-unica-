package it.edilmilan.ge360.giornata

import android.graphics.Color
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
import androidx.activity.result.contract.ActivityResultContracts
import it.edilmilan.ge360.scanner.DocumentStore
import it.edilmilan.ge360.scanner.ScannerDocument
import java.io.File
import com.google.android.material.button.MaterialButton
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/**
 * Simple offline-first front end for the new Compensi V1 ledger.
 * Historical payments remain in CollaboratorsActivity / ExternalCollaboratorsActivity.
 * No automatic FinanceRecord creation; payment status means "registered by the operator".
 */
class CompensationActivity : AppCompatActivity() {
    private lateinit var daily: DailyStore
    private lateinit var jobs: JobsiteStore
    private lateinit var ledger: CompensationStore
    private lateinit var body: LinearLayout
    private var pendingInvoiceAccrualId: String? = null
    private val invoicePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val accrualId = pendingInvoiceAccrualId
        pendingInvoiceAccrualId = null
        if (uri == null || accrualId == null) return@registerForActivityResult
        runCatching {
            require(ledger.accruals().any { it.ge360Id == accrualId }) { "Maturato non presente" }
            val documentId = UUID.randomUUID().toString()
            val target = File(filesDir, "scanner/compensi/$documentId.pdf")
            require(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "PDF non leggibile" }
                target.outputStream().use { output -> input.copyTo(output) }
            }
            val saved = ScannerDocument(
                ge360Id = documentId,
                type = "FATTURA_COLLABORATORE",
                documentDate = LocalDate.now().toString(),
                category = "Compensi",
                pdfPath = target.absolutePath
            )
            DocumentStore(this).use { store -> store.save(saved) }
            ledger.attachInvoice(CompensationInvoiceLinkV1(
                ge360Id = UUID.randomUUID().toString(),
                accrualGe360Id = accrualId,
                scannerDocumentGe360Id = documentId
            ))
        }.onSuccess {
            toast("Fattura collegata al maturato: pagamento non registrato.")
            refresh()
        }.onFailure { toast(it.message ?: "Allegato non salvato") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        daily = DailyStore(this)
        jobs = JobsiteStore(this)
        ledger = CompensationStore(this)
        pendingInvoiceAccrualId = savedInstanceState?.getString("pending_invoice_accrual_id")
        setContentView(screen())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized) refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_invoice_accrual_id", pendingInvoiceAccrualId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        ledger.close()
        jobs.close()
        daily.close()
        super.onDestroy()
    }

    private fun screen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F6F5F2"))
            setPadding(dp(14), dp(8), dp(14), dp(12))
        }
        root.addView(buildScreenHeaderWithSubtitle(
            context = this, title = "Compensi",
            subtitle = "Settimanali e a cottimo · archivio locale",
            onBack = { finish() }
        ))
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        return root
    }

    private fun refresh() {
        body.removeAllViews()
        val agreements = ledger.agreements().associateBy { it.ge360Id }
        val personnel = daily.personnel(false).associateBy { it.ge360Id }
        val rows = ledger.accruals()
        val invoices = ledger.invoices().groupBy { it.accrualGe360Id }
        val confirmed = rows.filter { it.status == CompensationAccrualStatus.CONFIRMED }
        val remaining = confirmed.sumOf { ledger.balance(it).remainingCents }
        body.addView(text("REGISTRO COMPENSI", 21f, true))
        body.addView(text(
            "${rows.size} maturati registrati · Residuo dichiarato ${money(remaining)}",
            13f, false
        ))
        body.addView(text(
            "Le ore e i pagamenti storici restano negli archivi precedenti. " +
                "Le registrazioni in questa schermata non eseguono bonifici e non creano Spese.",
            12f, false
        ))
        body.addView(action("+ NUOVO COMPENSO") { newCompensation() })
        if (rows.isEmpty()) {
            body.addView(text("Nessun compenso nel nuovo registro. Premi Nuovo compenso.", 13f, false))
        }
        rows.forEach { row ->
            val agreement = agreements[row.agreementGe360Id]
            val person = personnel[agreement?.collaboratorGe360Id]?.name ?: "Collaboratore non disponibile"
            val label = if (agreement?.mode == CompensationMode.WEEKLY) "Settimanale" else "A cottimo"
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                setBackgroundColor(Color.WHITE)
            }
            card.addView(text("$person · $label", 17f, true))
            card.addView(text("${row.periodStart} · ${row.description.ifBlank { "Prestazione" }}", 12f, false))
            card.addView(text("Maturato confermato: ${money(row.amountCents)}", 14f, true))
            val attached = invoices[row.ge360Id].orEmpty()
            card.addView(text(
                if (attached.isEmpty()) "Nessuna fattura allegata" else "${attached.size} fatture collegate",
                12f, false
            ))
            card.addView(action("ALLEGA FATTURA PDF") {
                pendingInvoiceAccrualId = row.ge360Id
                invoicePicker.launch(arrayOf("application/pdf"))
            })
            if (row.status == CompensationAccrualStatus.CONFIRMED) {
                val balance = ledger.balance(row)
                card.addView(text(
                    "Pagamento registrato: ${money(balance.paidCents)} · Da registrare: ${money(balance.remainingCents)}",
                    13f, false
                ))
                if (balance.remainingCents > 0L) {
                    card.addView(action("REGISTRA PAGAMENTO") { recordPayment(row) })
                } else if (balance.fullyPaid) {
                    card.addView(text("Interamente registrato come pagato", 12f, true))
                }
            } else card.addView(text("Stato: ${row.status.name}", 12f, false))
            val box = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
            body.addView(card, box)
        }
    }

    private fun newCompensation() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }
        val name = edit("Nome collaboratore (obbligatorio)")
        val modes = listOf("Settimanale", "A cottimo")
        val mode = Spinner(this).apply {
            adapter = ArrayAdapter(this@CompensationActivity,
                android.R.layout.simple_spinner_dropdown_item, modes)
        }
        val amount = edit("Importo concordato €", true)
        val date = edit("Data (AAAA-MM-GG)").apply {
            setText(CompensationLedgerRulesV1.currentWeekStart().toString())
        }
        val description = edit("Descrizione lavoro / cottimo (facoltativa)")
        val sites = jobs.list()
        val labels = listOf("Senza cantiere") + sites.map { it.displayTitle() }
        val jobsite = Spinner(this).apply {
            adapter = ArrayAdapter(this@CompensationActivity,
                android.R.layout.simple_spinner_dropdown_item, labels)
        }
        form.addView(text("COLLABORATORE", 12f, true))
        form.addView(name)
        form.addView(text("MODALITÀ", 12f, true))
        form.addView(mode)
        form.addView(text("IMPORTO E PERIODO", 12f, true))
        form.addView(amount)
        form.addView(date)
        form.addView(description)
        form.addView(text("CANTIERE (FACOLTATIVO)", 12f, true))
        form.addView(jobsite)
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Nuovo compenso")
            .setView(form)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("SALVA MATURATO", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    val collaboratorName = name.text.toString().trim()
                    require(collaboratorName.isNotEmpty()) { "Inserisci il nome del collaboratore" }
                    val euros = parseMoney(amount.text.toString())
                    require(euros > 0L) { "L'importo deve essere positivo" }
                    val inputDate = LocalDate.parse(date.text.toString().trim())
                    val kind = if (mode.selectedItemPosition == 0) CompensationMode.WEEKLY
                    else CompensationMode.PIECEWORK
                    val start = if (kind == CompensationMode.WEEKLY) {
                        CompensationLedgerRulesV1.isoWeekStart(inputDate)
                    } else inputDate
                    val end = if (kind == CompensationMode.WEEKLY) start.plusDays(6) else start
                    val found = daily.personnel(false).firstOrNull {
                        it.name.trim().equals(collaboratorName, ignoreCase = true)
                    }
                    val person = found ?: PersonnelRecord(name = collaboratorName).also {
                        daily.savePersonnel(it)
                    }
                    val jobsiteId = sites.getOrNull(jobsite.selectedItemPosition - 1)?.ge360Id
                    val agreement = CompensationAgreementV1(
                        ge360Id = UUID.randomUUID().toString(),
                        collaboratorGe360Id = person.ge360Id,
                        mode = kind,
                        agreedAmountCents = euros,
                        description = description.text.toString().trim(),
                        jobsiteGe360Id = jobsiteId
                    )
                    val accrual = CompensationAccrualV1(
                        ge360Id = UUID.randomUUID().toString(),
                        agreementGe360Id = agreement.ge360Id,
                        amountCents = euros,
                        status = CompensationAccrualStatus.CONFIRMED,
                        periodStart = start,
                        periodEnd = end,
                        description = description.text.toString().trim()
                    )
                    CompensationLedgerRulesV1.validatePeriod(kind, accrual)
                    ledger.saveConfirmedAccrual(agreement, accrual)
                }.onSuccess {
                    dialog.dismiss()
                    refresh()
                }.onFailure { toast(it.message ?: "Salvataggio non riuscito") }
            }
        }
        dialog.show()
    }

    private fun recordPayment(row: CompensationAccrualV1) {
        val balance = ledger.balance(row)
        if (balance.remainingCents <= 0L) return
        val input = edit("Pagamento registrato €", true).apply {
            setText(BigDecimal(balance.remainingCents).movePointLeft(2).toPlainString())
        }
        val paymentDate = edit("Data pagamento (AAAA-MM-GG)").apply {
            setText(LocalDate.now().toString())
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(input)
            addView(paymentDate)
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Registra pagamento dichiarato")
            .setMessage("Registra soltanto un pagamento già effettuato. Questa azione non crea una Spesa e non invia un bonifico.")
            .setView(form)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("CONFERMA", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    val cents = parseMoney(input.text.toString())
                    require(cents > 0L && cents <= balance.remainingCents) {
                        "Importo superiore al residuo o non valido"
                    }
                    ledger.recordPayment(CompensationPaymentEventV1(
                        ge360Id = UUID.randomUUID().toString(),
                        accrualGe360Id = row.ge360Id,
                        kind = CompensationPaymentKind.PAYMENT,
                        amountCents = cents,
                        recordedAt = LocalDate.parse(paymentDate.text.toString().trim())
                    ))
                }.onSuccess {
                    dialog.dismiss()
                    refresh()
                }.onFailure { toast(it.message ?: "Pagamento non registrato") }
            }
        }
        dialog.show()
    }

    private fun money(cents: Long): String =
        NumberFormat.getCurrencyInstance(Locale.ITALY)
            .format(BigDecimal(cents).movePointLeft(2))

    private fun parseMoney(raw: String): Long {
        val cleaned = raw.trim().replace("€", "").replace(" ", "").replace(',', '.')
        return BigDecimal(cleaned).movePointRight(2)
            .setScale(0, RoundingMode.UNNECESSARY).longValueExact()
    }

    private fun edit(hintText: String, numeric: Boolean = false) = EditText(this).apply {
        hint = hintText
        isSingleLine = true
        minHeight = dp(50)
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
    private fun text(value: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.parseColor(if (bold) "#27362F" else "#5C625D"))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(4), dp(8), dp(4), dp(6))
    }
    private fun action(label: String, pressed: () -> Unit) = MaterialButton(this).apply {
        text = label
        minHeight = dp(50)
        setOnClickListener { pressed() }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
