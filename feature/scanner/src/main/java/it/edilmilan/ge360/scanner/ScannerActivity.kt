package it.edilmilan.ge360.scanner

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.core.common.Ge360Contracts
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.spese.FinanceRecord
import it.edilmilan.ge360.spese.FinanceScope
import it.edilmilan.ge360.spese.FinanceStore
import it.edilmilan.ge360.spese.FinanceType
import java.io.File
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

class ScannerActivity : AppCompatActivity() {
    private lateinit var store: DocumentStore
    private lateinit var jobsiteStore: JobsiteStore
    private lateinit var financeStore: FinanceStore
    private lateinit var listView: ListView
    private lateinit var adapter: DocumentAdapter
    private lateinit var searchInput: EditText
    private lateinit var statusLabel: TextView
    private lateinit var processing: ProgressBar
    private var contextJobsite: JobsiteRecord? = null

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val scanner by lazy {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(50)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
    }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data) ?: return@registerForActivityResult
        val pdfUri = scan.pdf?.uri
        val pages = scan.pages?.map { it.imageUri }.orEmpty()
        if (pdfUri == null) {
            Toast.makeText(this, "PDF della scansione non disponibile", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        ingest(pdfUri, pages)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DocumentStore(this)
        jobsiteStore = JobsiteStore(this)
        financeStore = FinanceStore(this)
        contextJobsite = resolveRequestedJobsite()
        setContentView(buildScreen())
        loadDocuments()

        // Arrivo diretto da "Oggi": parte subito la scansione, perche' il gesto
        // quotidiano e' inquadrare uno scontrino, non sfogliare l'archivio.
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_SCAN_NOW, false)) {
            startScan()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) loadDocuments()
    }

    override fun onDestroy() {
        recognizer.close()
        store.close()
        jobsiteStore.close()
        financeStore.close()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        root.addView(buildScreenHeaderWithSubtitle(
            context = this,
            title = "Documenti",
            subtitle = contextJobsite?.let { "${it.code} · ${it.displayTitle()} · PDF · FIR" }
                ?: "PDF · OCR locale · FIR · Spese · Cantieri",
            onBack = { finish() },
            trailingLabel = "Esporta",
            onTrailingClick = { exportMetadata() }
        ))

        processing = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(processing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))

        statusLabel = TextView(this).apply {
            setPadding(dp(4), dp(8), dp(4), dp(8))
            textSize = 13f
        }
        root.addView(statusLabel)

        searchInput = EditText(this).apply {
            hint = "Cerca FIR, DDT, fattura, cantiere, OCR…"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = loadDocuments()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(searchInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        adapter = DocumentAdapter()
        listView = ListView(this).apply {
            dividerHeight = 0
            adapter = this@ScannerActivity.adapter
            setOnItemClickListener { _, _, position, _ -> showDocumentDetail(this@ScannerActivity.adapter.items[position]) }
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(MaterialButton(this@ScannerActivity).apply {
                text = "♻ FIR / RUDERI"
                textSize = 14f
                setOnClickListener { openFirModule() }
            }, LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginEnd = dp(5) })
            addView(MaterialButton(this@ScannerActivity).apply {
                text = "▣ SCANSIONA"
                textSize = 14f
                setOnClickListener { startScan() }
            }, LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(5) })
        })
        return root
    }

    private fun startScan() {
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { sender -> scanLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { error ->
                Toast.makeText(this, "Scanner non disponibile: ${error.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun ingest(pdfUri: Uri, pages: List<Uri>) {
        setProcessing(true, "Acquisizione PDF e OCR in corso…")
        val pdfPath = runCatching { copyPdf(pdfUri) }.getOrElse { error ->
            setProcessing(false, "Errore acquisizione")
            Toast.makeText(this, "Impossibile salvare il PDF: ${error.message}", Toast.LENGTH_LONG).show()
            return
        }
        if (pages.isEmpty()) {
            finishOcr(pdfPath, "")
            return
        }
        val texts = MutableList(pages.size) { "" }
        fun process(index: Int) {
            if (index >= pages.size) {
                finishOcr(pdfPath, texts.joinToString("\n\n"))
                return
            }
            val image = runCatching { InputImage.fromFilePath(this, pages[index]) }.getOrNull()
            if (image == null) {
                process(index + 1)
                return
            }
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    texts[index] = text.text
                    process(index + 1)
                }
                .addOnFailureListener { process(index + 1) }
        }
        process(0)
    }

    private fun finishOcr(pdfPath: String, ocrText: String) {
        val parsed = DocumentParser.parse(ocrText)
        val requestedType = intent.getStringExtra(EXTRA_PREFILL_TYPE).orEmpty().trim()
        val record = ScannerDocument(
            type = requestedType.ifBlank { parsed.type },
            documentDate = parsed.documentDate.orEmpty(),
            documentNumber = parsed.documentNumber.orEmpty(),
            supplier = parsed.supplier.orEmpty(),
            category = parsed.category.orEmpty(),
            taxableAmount = parsed.taxableAmount,
            vatAmount = parsed.vatAmount,
            totalAmount = parsed.totalAmount,
            confidence = parsed.confidence,
            ocrText = ocrText,
            pdfPath = pdfPath
        )
        contextJobsite?.let { applyJobsite(record, it) }
        if (record.jobsiteId.isBlank()) {
            val suggested = parsed.suggestedJob?.lowercase(Locale.ITALY).orEmpty()
            if (suggested.isNotBlank()) {
                jobsiteStore.list().firstOrNull {
                    it.address.lowercase(Locale.ITALY) in suggested ||
                        it.displayTitle().lowercase(Locale.ITALY) in suggested ||
                        suggested in it.address.lowercase(Locale.ITALY)
                }?.let { applyJobsite(record, it) }
            }
        }
        setProcessing(false, "OCR completato · controlla i dati")
        showReview(record)
    }

    private fun showReview(source: ScannerDocument) {
        if (source.id > 0L && ToolWarrantyArchive.isWarrantyReceipt(source)) {
            startActivity(Intent(this, ToolWarrantyActivity::class.java).apply {
                putExtra(ToolWarrantyActivity.EXTRA_DOCUMENT_ID, source.id)
            })
            return
        }
        val record = source.copy()
        val linkedExpense = record.expenseGe360Id.takeIf { it.isNotBlank() }?.let(financeStore::getByGe360Id)
        val staleExpenseLink = record.expenseGe360Id.isNotBlank() && linkedExpense == null
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun label(value: String) = TextView(this).apply {
            text = value
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        }
        fun field(value: String, hintValue: String = ""): EditText = EditText(this).apply {
            setText(value)
            hint = hintValue
            isSingleLine = true
        }.also { content.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))) }

        content.addView(label("Tipo documento"))
        val types = listOf("FIR", "FATTURA", "DDT", "SCONTRINO", "RICEVUTA", "ALTRO")
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ScannerActivity, android.R.layout.simple_spinner_dropdown_item, types)
            setSelection(types.indexOf(record.type).takeIf { it >= 0 } ?: types.lastIndex)
        }
        content.addView(typeSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        content.addView(label("Fornitore / soggetto")); val supplier = field(record.supplier)
        content.addView(label("Numero documento")); val number = field(record.documentNumber)
        content.addView(label("Data (AAAA-MM-GG)")); val date = field(record.documentDate, LocalDate.now().toString())
        content.addView(label("Totale €")); val total = field(record.totalAmount?.toString()?.replace('.', ',').orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        content.addView(label("Categoria Spesa"))
        val categories = FinanceStore.EXPENSE_CATEGORIES
        val categorySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ScannerActivity, android.R.layout.simple_spinner_dropdown_item, categories)
            val idx = categories.indexOf(record.category)
            setSelection(if (idx >= 0) idx else categories.indexOf("Altro"))
        }
        content.addView(categorySpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        content.addView(label("Cantiere"))
        val jobsites = jobsiteStore.list()
        val jobsiteLabels = mutableListOf("Nessun cantiere").apply { addAll(jobsites.map { "${it.code} · ${it.displayTitle()}" }) }
        val jobsiteSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ScannerActivity, android.R.layout.simple_spinner_dropdown_item, jobsiteLabels)
            val idx = jobsites.indexOfFirst { it.ge360Id == record.jobsiteId }
            setSelection(if (idx >= 0) idx + 1 else 0)
        }
        content.addView(jobsiteSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        val createExpense = CheckBox(this).apply {
            text = when {
                linkedExpense != null -> "Aggiorna anche la voce Uscita collegata in Spese"
                staleExpenseLink -> "Ricrea la voce Uscita mancante in Spese"
                else -> "Crea anche una voce Uscita in Spese"
            }
            isChecked = linkedExpense != null || (record.totalAmount != null && record.type !in setOf("DDT", "FIR"))
            setPadding(0, dp(10), 0, dp(4))
        }
        content.addView(createExpense)
        if (record.type == "FIR") content.addView(TextView(this).apply {
            text = "♻ Per EER, quantità, trasportatore, destinatario e stato usa il modulo FIR / Smaltimento ruderi. Qui resta archiviato il PDF/scansione."
            textSize = 12f
        })
        if (staleExpenseLink) content.addView(TextView(this).apply {
            text = "⚠ La Spesa precedentemente collegata non esiste più. Puoi ricrearla oppure lasciare la casella vuota per rimuovere il collegamento non valido."
            textSize = 12f
        })
        content.addView(TextView(this).apply {
            text = "OCR confidenza: ${(record.confidence * 100).toInt()}% · Controlla sempre i dati prima di salvare."
            textSize = 12f
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (record.id > 0) "Modifica documento" else "Controlla scansione")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla") { _, _ -> cleanupUnsavedPdf(record) }
            .create()
        dialog.setOnCancelListener { cleanupUnsavedPdf(record) }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsedDate = date.text.toString().trim()
                if (parsedDate.isNotBlank() && runCatching { LocalDate.parse(parsedDate) }.isFailure) {
                    date.error = "Usa AAAA-MM-GG"
                    return@setOnClickListener
                }
                val amount = total.text.toString().replace(',', '.').toDoubleOrNull()
                if (createExpense.isChecked && (amount == null || amount <= 0.0)) {
                    total.error = "Serve un totale valido per creare/aggiornare la Spesa"
                    return@setOnClickListener
                }
                record.type = typeSpinner.selectedItem.toString()
                record.supplier = supplier.text.toString().trim()
                record.documentNumber = number.text.toString().trim()
                record.documentDate = parsedDate
                record.totalAmount = amount
                record.category = categorySpinner.selectedItem.toString()
                if (jobsiteSpinner.selectedItemPosition > 0) applyJobsite(record, jobsites[jobsiteSpinner.selectedItemPosition - 1])
                else clearJobsite(record)
                if (staleExpenseLink && !createExpense.isChecked) {
                    record.expenseGe360Id = ""
                    record.status = ScannerDocument.STATUS_REVIEWED
                }
                store.save(record)

                if (createExpense.isChecked && amount != null && amount > 0.0) {
                    val tx = linkedExpense?.copy() ?: FinanceRecord(type = FinanceType.EXPENSE)
                    tx.type = FinanceType.EXPENSE
                    tx.amount = amount
                    tx.category = record.category.ifBlank { "Altro" }
                    tx.note = listOf(record.supplier, record.type, record.documentNumber).filter { it.isNotBlank() }.joinToString(" · ")
                    tx.date = record.documentDate.ifBlank { LocalDate.now().toString() }
                    tx.scope = FinanceScope.BUSINESS
                    tx.jobsiteId = record.jobsiteId
                    tx.jobsiteCode = record.jobsiteCode
                    tx.jobsiteTitle = record.jobsiteTitle
                    financeStore.save(tx)
                    store.markExpenseCreated(record.id, tx.ge360Id)
                }
                dialog.dismiss()
                loadDocuments()
                Toast.makeText(this, "Documento salvato", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun cleanupUnsavedPdf(record: ScannerDocument) {
        if (record.id > 0 || record.pdfPath.isBlank()) return
        runCatching { File(record.pdfPath).takeIf { it.exists() }?.delete() }
    }

    private fun showDocumentDetail(record: ScannerDocument) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun info(label: String, value: String) {
            body.addView(TextView(this).apply {
                text = "$label · ${value.ifBlank { "—" }}"
                setPadding(0, dp(5), 0, 0)
            })
        }
        val linkedExists = record.expenseGe360Id.isNotBlank() && financeStore.getByGe360Id(record.expenseGe360Id) != null
        val warrantyReceipt = ToolWarrantyArchive.isWarrantyReceipt(record)
        body.addView(TextView(this).apply {
            text = if (warrantyReceipt) record.toolName.ifBlank { "Scontrino garanzia" }
            else record.supplier.ifBlank { record.type }
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
        })
        info("Tipo", if (warrantyReceipt) "Scontrino garanzia" else record.type)
        if (warrantyReceipt) {
            info("Attrezzo", record.toolName)
            info("Tipo attrezzo", record.toolType)
            info("Data acquisto", record.documentDate)
            info("Negozio", record.supplier)
            info("Cartella", ToolWarrantyArchive.ROOT_FOLDER)
        } else {
            info("Numero", record.documentNumber)
            info("Data", record.documentDate)
            info("Categoria", record.category)
            info("Cantiere", listOf(record.jobsiteCode, record.jobsiteTitle).filter { it.isNotBlank() }.joinToString(" · "))
            info("Spese", when {
                linkedExists -> "Movimento collegato"
                record.expenseGe360Id.isNotBlank() -> "Collegamento non valido · Spesa mancante"
                else -> "Non creata"
            })
        }
        info("Totale", record.totalAmount?.let(::euro).orEmpty())
        info("OCR", "${(record.confidence * 100).toInt()}%")

        if (record.type == "FIR") body.addView(MaterialButton(this).apply {
            text = "♻ Apri modulo FIR / ruderi"
            setOnClickListener { openFirModule() }
        })
        body.addView(MaterialButton(this).apply {
            text = "Apri PDF"
            setOnClickListener { openPdf(record) }
        })
        body.addView(MaterialButton(this).apply {
            text = if (warrantyReceipt) "Modifica dati garanzia" else "Modifica / crea Spesa"
            setOnClickListener {
                if (warrantyReceipt) {
                    startActivity(Intent(this@ScannerActivity, ToolWarrantyActivity::class.java).apply {
                        putExtra(ToolWarrantyActivity.EXTRA_DOCUMENT_ID, record.id)
                    })
                } else {
                    showReview(store.get(record.id) ?: record)
                }
            }
        })
        body.addView(MaterialButton(this).apply {
            text = "Elimina documento"
            setOnClickListener { confirmDelete(record) }
        })

        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun confirmDelete(record: ScannerDocument) {
        AlertDialog.Builder(this)
            .setTitle("Elimina documento")
            .setMessage("Eliminare il documento e il PDF locale? L'eventuale movimento Spese collegato non verrà cancellato automaticamente.")
            .setPositiveButton("Elimina") { _, _ ->
                runCatching { File(record.pdfPath).delete() }
                store.delete(record.id)
                if (ToolWarrantyArchive.isWarrantyReceipt(record)) {
                    ToolWarrantyArchive.writeIndex(this, store.list())
                }
                loadDocuments()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun openPdf(record: ScannerDocument) {
        runCatching {
            val file = File(record.pdfPath)
            require(file.exists()) { "PDF non trovato" }
            val uri = FileProvider.getUriForFile(this, "$packageName.scanner.files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Apri PDF"))
        }.onFailure { Toast.makeText(this, it.message ?: "Impossibile aprire PDF", Toast.LENGTH_LONG).show() }
    }

    private fun copyPdf(uri: Uri): String {
        val now = LocalDate.now()
        val folder = File(filesDir, "scanner/${now.year}/${now.monthValue.toString().padStart(2, '0')}").apply { mkdirs() }
        val file = File(folder, "GE360-${System.currentTimeMillis()}.pdf")
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Flusso PDF non leggibile" }
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file.absolutePath
    }

    private fun applyJobsite(record: ScannerDocument, jobsite: JobsiteRecord) {
        record.jobsiteId = jobsite.ge360Id
        record.jobsiteCode = jobsite.code
        record.jobsiteTitle = jobsite.displayTitle()
    }

    private fun clearJobsite(record: ScannerDocument) {
        record.jobsiteId = ""
        record.jobsiteCode = ""
        record.jobsiteTitle = ""
    }

    private fun resolveRequestedJobsite(): JobsiteRecord? {
        val uuid = intent.getStringExtra(Ge360Contracts.EXTRA_JOBSITE_ID).orEmpty()
        val localId = intent.getLongExtra(Ge360Contracts.EXTRA_JOBSITE_LOCAL_ID, 0L)
        return jobsiteStore.list().firstOrNull {
            (uuid.isNotBlank() && it.ge360Id == uuid) || (localId > 0 && it.id == localId)
        }
    }

    private fun openFirModule() {
        startActivity(Intent(this, FirActivity::class.java).apply {
            contextJobsite?.let {
                putExtra(Ge360Contracts.EXTRA_JOBSITE_ID, it.ge360Id)
                putExtra(Ge360Contracts.EXTRA_JOBSITE_LOCAL_ID, it.id)
                putExtra(Ge360Contracts.EXTRA_JOBSITE_CODE, it.code)
            }
        })
    }

    private fun loadDocuments() {
        if (!::adapter.isInitialized) return
        val allRows = store.list(if (::searchInput.isInitialized) searchInput.text.toString() else "")
        val rows = contextJobsite?.let { job -> allRows.filter { it.jobsiteId == job.ge360Id } } ?: allRows
        adapter.items = rows
        adapter.notifyDataSetChanged()
        val expenses = rows.count { it.expenseGe360Id.isNotBlank() && financeStore.getByGe360Id(it.expenseGe360Id) != null }
        val stale = rows.count { it.expenseGe360Id.isNotBlank() && financeStore.getByGe360Id(it.expenseGe360Id) == null }
        val fir = rows.count { it.type == "FIR" }
        statusLabel.text = "${rows.size} documenti · $fir FIR · $expenses in Spese${if (stale > 0) " · ⚠ $stale collegamenti mancanti" else ""}${contextJobsite?.let { " · ${it.code}" } ?: ""}"
    }

    private fun setProcessing(active: Boolean, message: String) {
        processing.visibility = if (active) View.VISIBLE else View.GONE
        statusLabel.text = message
    }

    private fun exportMetadata() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "ge360-scanner-metadata.json")
        }, REQ_EXPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT || resultCode != Activity.RESULT_OK || data?.data == null) return
        runCatching {
            contentResolver.openOutputStream(data.data!!, "wt")?.bufferedWriter()?.use { it.write(store.exportJson()) }
                ?: error("Destinazione non scrivibile")
        }.onSuccess { Toast.makeText(this, "Metadati Scanner esportati", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, "Export fallito: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private inner class DocumentAdapter : BaseAdapter() {
        var items: List<ScannerDocument> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val d = items[position]
            val card = MaterialCardView(this@ScannerActivity).apply {
                radius = dp(14).toFloat()
                cardElevation = dp(1).toFloat()
            }
            card.addView(LinearLayout(this@ScannerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                addView(TextView(this@ScannerActivity).apply {
                    text = if (ToolWarrantyArchive.isWarrantyReceipt(d)) {
                        d.toolName.ifBlank { "Scontrino garanzia" }
                    } else {
                        d.supplier.ifBlank { d.type }
                    }
                    setTypeface(typeface, Typeface.BOLD)
                    textSize = 16f
                })
                addView(TextView(this@ScannerActivity).apply {
                    val total = d.totalAmount?.let(::euro) ?: if (d.type == "FIR") "smaltimento rifiuti" else "totale da verificare"
                    val linked = d.expenseGe360Id.isNotBlank() && financeStore.getByGe360Id(d.expenseGe360Id) != null
                    val expense = if (linked) " · ✓ Spese" else if (d.expenseGe360Id.isNotBlank()) " · ⚠ Spesa mancante" else ""
                    val jobsite = if (d.jobsiteCode.isBlank()) "" else " · ${d.jobsiteCode}"
                    text = if (ToolWarrantyArchive.isWarrantyReceipt(d)) {
                        "Garanzia · ${d.toolType.ifBlank { "tipo da indicare" }} · ${d.documentDate.ifBlank { "senza data" }} · $total"
                    } else {
                        "${d.type} · ${d.documentDate.ifBlank { "senza data" }} · $total$jobsite$expense"
                    }
                    textSize = 12f
                    setPadding(0, dp(4), 0, 0)
                })
            })
            return LinearLayout(this@ScannerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(4))
                addView(card)
            }
        }
    }

    private fun euro(value: Double): String = NumberFormat.getCurrencyInstance(Locale.ITALY).format(value)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PREFILL_TYPE = "ge360_scanner_prefill_type"

        /** Avvia la scansione appena la schermata si apre. */
        const val EXTRA_SCAN_NOW = "ge360_scanner_scan_now"

        private const val REQ_EXPORT = 601
    }
}
