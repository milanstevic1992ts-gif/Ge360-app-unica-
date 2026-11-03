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
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.scanner.DocumentStore
import it.edilmilan.ge360.scanner.ScannerDocument
import it.edilmilan.ge360.spese.FinanceStore
import it.edilmilan.ge360.spese.FinanceRecord
import it.edilmilan.ge360.spese.FinanceScope
import it.edilmilan.ge360.spese.FinanceType
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
    private lateinit var finance: FinanceStore
    private lateinit var body: LinearLayout
    private var pendingInvoiceAccrualId: String? = null
    private var pendingProofPaymentId: String? = null
    private val proofPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val paymentId = pendingProofPaymentId
        pendingProofPaymentId = null
        if (uri == null || paymentId == null) return@registerForActivityResult
        runCatching {
            val payment = ledger.payments().singleOrNull { it.ge360Id == paymentId }
                ?: error("Pagamento da documentare non trovato")
            require(payment.kind == CompensationPaymentKind.PAYMENT) {
                "Seleziona un pagamento, non uno storno"
            }
            val documentId = UUID.randomUUID().toString()
            val target = File(filesDir, "scanner/compensi/payment_$documentId.pdf")
            require(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "PDF non leggibile" }
                target.outputStream().use { output -> input.copyTo(output) }
            }
            DocumentStore(this).use { store ->
                store.save(ScannerDocument(
                    ge360Id = documentId,
                    type = "PROVA_PAGAMENTO_COLLABORATORE",
                    documentDate = payment.recordedAt.toString(),
                    category = "Compensi",
                    pdfPath = target.absolutePath
                ))
            }
            ledger.attachPaymentProof(paymentId, documentId)
        }.onSuccess {
            toast("Comprovante allegato. Il pagamento resta una registrazione dichiarata.")
            refresh()
        }.onFailure { toast(it.message ?: "Comprovante non collegato") }
    }
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
        finance = FinanceStore(this)
        pendingInvoiceAccrualId = savedInstanceState?.getString("pending_invoice_accrual_id")
        pendingProofPaymentId = savedInstanceState?.getString("pending_proof_payment_id")
        setContentView(screen())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized) refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_invoice_accrual_id", pendingInvoiceAccrualId)
        outState.putString("pending_proof_payment_id", pendingProofPaymentId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        ledger.close()
        finance.close()
        jobs.close()
        daily.close()
        super.onDestroy()
    }

    private fun screen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ge360Brand.Background)
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
        val payEvents = ledger.payments()
        val movements = finance.all().associateBy { it.ge360Id }
        val usedFinance = payEvents.mapNotNull { it.financeRecordGe360Id?.takeIf(String::isNotBlank) }.toSet()
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
                setBackgroundColor(Ge360Brand.Surface)
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
                val entries = payEvents.filter { it.accrualGe360Id == row.ge360Id }
                val reversedIds = entries.filter { it.kind == CompensationPaymentKind.REVERSAL }
                    .mapNotNull { it.reversesPaymentGe360Id }.toSet()
                entries.filter { it.kind == CompensationPaymentKind.PAYMENT }.forEach { payment ->
                    val linkedId = payment.financeRecordGe360Id
                    card.addView(text(
                        "Pagamento ${payment.recordedAt} · ${money(payment.amountCents)}",
                        12f, true
                    ))
                    if (payment.proofDocumentGe360Id.isNullOrBlank()) {
                        card.addView(action("ALLEGA COMPROVANTE PDF") {
                            pendingProofPaymentId = payment.ge360Id
                            proofPicker.launch(arrayOf("application/pdf"))
                        })
                    } else {
                        card.addView(text("Comprovante PDF registrato · ID ${payment.proofDocumentGe360Id.takeLast(8)}", 12f, false))
                    }
                    if (payment.ge360Id in reversedIds) {
                        card.addView(text("Stornato nel registro Compensi", 12f, false))
                    } else if (!linkedId.isNullOrBlank()) {
                        val verified = CompensationFinanceReconciliation.linkedFinanceValid(
                            payment, movements[linkedId], agreement?.jobsiteGe360Id
                        )
                        card.addView(text(
                            if (verified) "Collegato a Spese · UUID ${linkedId.takeLast(12)}"
                            else "ATTENZIONE: movimento Spese assente o difforme; riconciliazione da verificare",
                            12f, verified
                        ))
                    } else {
                        card.addView(text("Non collegato a Spese", 12f, false))
                        val candidates = CompensationFinanceReconciliation.matching(
                            payment, agreement?.jobsiteGe360Id, movements.values.toList(), usedFinance
                        )
                        if (candidates.isNotEmpty()) {
                            card.addView(action("COLLEGA A SPESA ESISTENTE") {
                                chooseExistingFinance(payment, agreement?.jobsiteGe360Id, candidates)
                            })
                        }
                        card.addView(action("REGISTRA ANCHE IN SPESE") {
                            confirmNewFinanceExpense(payment, agreement?.jobsiteGe360Id)
                        })
                        card.addView(action("STORNA QUESTA REGISTRAZIONE") {
                            confirmReversal(payment)
                        })
                    }
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
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Nuovo compenso")
            .setView(form)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("SALVA MATURATO", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
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

    private fun chooseExistingFinance(
        payment: CompensationPaymentEventV1,
        jobsiteId: String?,
        candidates: List<FinanceRecord>
    ) {
        val labels = candidates.map { record ->
            "${record.date} · ${money(CompensationFinanceReconciliation.financeCents(record))}" +
                " · ${record.category} · ${record.note.take(45)} · ${record.ge360Id.takeLast(8)}"
        }
        val picker = Spinner(this).apply {
            adapter = ArrayAdapter(this@CompensationActivity,
                android.R.layout.simple_spinner_dropdown_item, labels)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
            addView(text(
                "Seleziona soltanto l'uscita che rappresenta realmente questo pagamento. " +
                    "Non verrà creata una seconda Spesa.", 12f, false))
            addView(picker)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Collega a movimento Spese esistente")
            .setView(container)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("COLLEGA", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    val selected = candidates[picker.selectedItemPosition]
                    // Always re-read; a record may have been edited while this dialog was open.
                    val current = finance.getByGe360Id(selected.ge360Id)
                        ?: error("Uscita non più disponibile")
                    CompensationFinanceReconciliation.validateLink(payment, current, jobsiteId)
                    ledger.linkFinanceRecord(payment.ge360Id, current)
                }.onSuccess {
                    dialog.dismiss()
                    refresh()
                    toast("Collegato a Spese esistente: nessuna nuova uscita creata.")
                }.onFailure { toast(it.message ?: "Collegamento non valido") }
            }
        }
        dialog.show()
    }

    private fun confirmNewFinanceExpense(
        payment: CompensationPaymentEventV1, jobsiteId: String?
    ) {
        val options = listOf("Collaboratori", "Collaboratori esterni")
        val categories = Spinner(this).apply {
            adapter = ArrayAdapter(this@CompensationActivity,
                android.R.layout.simple_spinner_dropdown_item, options)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(text(
                "Il movimento verrà aggiunto una sola volta a Spese aziendali. " +
                    "Controlla prima che non sia già presente nel tuo archivio: " +
                    "due uscite distinte con stesso importo e data possono essere reali.",
                12f, false
            ))
            addView(text("Categoria dell'uscita", 12f, true))
            addView(categories)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Registra uscita in Spese")
            .setView(container)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("REGISTRA USCITA", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    val stableId = "ge360-compensi-payment:${payment.ge360Id}"
                    val existing = finance.getByGe360Id(stableId)
                    val job = jobsiteId?.takeIf { it.isNotBlank() }
                        ?.let { jobs.getByGe360Id(it) }
                    val amount = BigDecimal(payment.amountCents).movePointLeft(2).toDouble()
                    require(amount.isFinite() && amount > 0) { "Importo non rappresentabile in Spese" }
                    val expense = existing ?: FinanceRecord(
                        ge360Id = stableId,
                        type = FinanceType.EXPENSE,
                        amount = amount,
                        category = options[categories.selectedItemPosition],
                        note = "Pagamento registrato Compensi · ${payment.ge360Id}",
                        date = payment.recordedAt.toString(),
                        scope = FinanceScope.BUSINESS,
                        jobsiteId = jobsiteId.orEmpty(),
                        jobsiteCode = job?.code.orEmpty(),
                        jobsiteTitle = job?.displayTitle().orEmpty()
                    )
                    CompensationFinanceReconciliation.validateLink(
                        payment, expense, jobsiteId
                    )
                    if (existing == null) {
                        finance.save(expense)
                    }
                    val recorded = finance.getByGe360Id(stableId)
                        ?: error("Movimento Spese non verificabile dopo il salvataggio")
                    ledger.linkFinanceRecord(payment.ge360Id, recorded)
                }.onSuccess {
                    dialog.dismiss()
                    refresh()
                    toast("Uscita collegata a Compensi: una sola registrazione in Spese.")
                }.onFailure { toast(it.message ?: "Non è stato possibile registrare l'uscita") }
            }
        }
        dialog.show()
    }

    private fun confirmReversal(payment: CompensationPaymentEventV1) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Storna registrazione di pagamento")
            .setMessage("Questo storno conserva lo storico e ripristina il residuo. " +
                "Non modifica movimenti bancari né crea o cancella Spese.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("CONFERMA STORNO") { _, _ ->
                runCatching {
                    require(payment.financeRecordGe360Id.isNullOrBlank()) {
                        "Pagamento collegato a Spese: serve prima una riconciliazione contabile"
                    }
                    ledger.recordPayment(CompensationPaymentEventV1(
                        ge360Id = UUID.randomUUID().toString(),
                        accrualGe360Id = payment.accrualGe360Id,
                        kind = CompensationPaymentKind.REVERSAL,
                        amountCents = payment.amountCents,
                        recordedAt = LocalDate.now(),
                        reversesPaymentGe360Id = payment.ge360Id
                    ))
                }.onSuccess { refresh() }
                 .onFailure { toast(it.message ?: "Storno non registrato") }
            }.show()
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
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Registra pagamento dichiarato")
            .setMessage("Registra soltanto un pagamento già effettuato. Questa azione non crea una Spesa e non invia un bonifico.")
            .setView(form)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("CONFERMA", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
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
        setTextColor(if (bold) Ge360Brand.Text else Ge360Brand.TextSecondary)
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
