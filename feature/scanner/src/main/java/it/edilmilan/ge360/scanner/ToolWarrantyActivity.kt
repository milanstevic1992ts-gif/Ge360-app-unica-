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
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
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
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import java.io.File
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/**
 * Vista dedicata agli acquisti degli attrezzi.
 *
 * Usa lo stesso DocumentStore dello Scanner generale: il PDF esiste una volta sola, entra
 * automaticamente nel backup GE360 e rimane ricercabile anche dall'archivio Documenti.
 */
class ToolWarrantyActivity : AppCompatActivity() {
    private lateinit var store: DocumentStore
    private lateinit var adapter: ReceiptAdapter
    private lateinit var searchInput: EditText
    private lateinit var statusLabel: TextView
    private lateinit var processing: ProgressBar
    private var pendingPdf: File? = null

    private val recognizerDelegate = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val recognizer by recognizerDelegate
    private val scanner by lazy {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(10)
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
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            ?: return@registerForActivityResult
        val pdfUri = scan.pdf?.uri
        if (pdfUri == null) {
            toast("PDF della scansione non disponibile")
            return@registerForActivityResult
        }
        ingest(pdfUri, scan.pages?.map { it.imageUri }.orEmpty())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DocumentStore(this)
        ToolWarrantyArchive.root(this)
        setContentView(buildScreen())
        refresh()

        val editId = intent.getLongExtra(EXTRA_DOCUMENT_ID, 0L)
        if (editId > 0L) {
            store.get(editId)?.takeIf(ToolWarrantyArchive::isWarrantyReceipt)?.let(::showEditor)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) refresh()
    }

    override fun onDestroy() {
        pendingPdf?.takeIf { it.exists() }?.delete()
        if (::store.isInitialized) store.close()
        if (recognizerDelegate.isInitialized()) recognizer.close()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        root.addView(buildScreenHeaderWithSubtitle(
            context = this,
            title = "Scontrini garanzia",
            subtitle = "Attrezzo · tipo · data d'acquisto · PDF",
            onBack = { finish() }
        ))

        processing = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(processing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))

        statusLabel = TextView(this).apply {
            textSize = 13f
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        root.addView(statusLabel)

        searchInput = EditText(this).apply {
            hint = "Cerca attrezzo, tipo, negozio o data…"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(searchInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        adapter = ReceiptAdapter()
        root.addView(ListView(this).apply {
            dividerHeight = 0
            adapter = this@ToolWarrantyActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                showDetails(this@ToolWarrantyActivity.adapter.items[position])
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(MaterialButton(this).apply {
            text = "▣ SCANSIONA SCONTRINO"
            textSize = 15f
            minHeight = dp(56)
            setOnClickListener { startScan() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        return root
    }

    private fun startScan() {
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { error ->
                toast("Scanner non disponibile: ${error.message}")
            }
    }

    private fun ingest(pdfUri: Uri, pages: List<Uri>) {
        setProcessing(true, "Salvataggio temporaneo e lettura scontrino…")
        val pending = runCatching { ToolWarrantyArchive.createPendingPdf(this, pdfUri) }.getOrElse { error ->
            setProcessing(false, "Errore acquisizione")
            toast(error.message ?: "Impossibile salvare lo scontrino")
            return
        }
        pendingPdf?.delete()
        pendingPdf = pending

        if (pages.isEmpty()) {
            finishOcr(pending, "")
            return
        }
        val texts = MutableList(pages.size) { "" }
        fun recognize(index: Int) {
            if (index >= pages.size) {
                finishOcr(pending, texts.joinToString("\n\n"))
                return
            }
            val image = runCatching { InputImage.fromFilePath(this, pages[index]) }.getOrNull()
            if (image == null) {
                recognize(index + 1)
                return
            }
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    texts[index] = text.text
                    recognize(index + 1)
                }
                .addOnFailureListener { recognize(index + 1) }
        }
        recognize(0)
    }

    private fun finishOcr(pending: File, ocrText: String) {
        val parsed = DocumentParser.parse(ocrText)
        setProcessing(false, "Scansione pronta · completa i dati dell'attrezzo")
        showEditor(
            ScannerDocument(
                ge360Id = UUID.randomUUID().toString(),
                type = ToolWarrantyArchive.DOCUMENT_TYPE,
                documentDate = parsed.documentDate.orEmpty().ifBlank { LocalDate.now().toString() },
                supplier = parsed.supplier.orEmpty(),
                totalAmount = parsed.totalAmount,
                confidence = parsed.confidence,
                ocrText = ocrText,
                pdfPath = pending.absolutePath
            )
        )
    }

    private fun showEditor(source: ScannerDocument) {
        val record = source.copy()
        val isNew = record.id <= 0L
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun label(value: String) {
            content.addView(TextView(this).apply {
                text = value
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(8), 0, 0)
            })
        }
        fun field(value: String, hintValue: String = ""): EditText = EditText(this).apply {
            setText(value)
            hint = hintValue
            isSingleLine = true
        }.also { content.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))) }

        label("Nome attrezzo *")
        val toolName = field(record.toolName, "Es. Tassellatore Makita HR2470")
        label("Tipo attrezzo *")
        val toolType = field(record.toolType, "Es. Elettroutensile, misura, manuale")
        label("Data acquisto * (AAAA-MM-GG)")
        val purchaseDate = field(record.documentDate, LocalDate.now().toString())
        label("Negozio / fornitore")
        val supplier = field(record.supplier, "Es. Tecnomat")
        label("Totale €")
        val total = field(record.totalAmount?.toString()?.replace('.', ',').orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        content.addView(TextView(this).apply {
            text = "Il PDF viene archiviato in “GE360/Scontrini garanzia”, incluso nel backup e indicizzato con nome, tipo e data."
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isNew) "Nuovo scontrino garanzia" else "Modifica scontrino")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla") { _, _ -> cleanupPending(record) }
            .create()
        dialog.setOnCancelListener { cleanupPending(record) }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nameValue = toolName.text.toString().trim()
                val typeValue = toolType.text.toString().trim()
                val dateValue = purchaseDate.text.toString().trim()
                if (nameValue.isBlank()) {
                    toolName.error = "Indica il nome dell'attrezzo"
                    return@setOnClickListener
                }
                if (typeValue.isBlank()) {
                    toolType.error = "Indica il tipo di attrezzo"
                    return@setOnClickListener
                }
                if (dateValue.isBlank() || runCatching { LocalDate.parse(dateValue) }.isFailure) {
                    purchaseDate.error = "Usa una data valida: AAAA-MM-GG"
                    return@setOnClickListener
                }
                val amountText = total.text.toString().trim().replace(',', '.')
                val amount = amountText.takeIf { it.isNotBlank() }?.toDoubleOrNull()
                if (amountText.isNotBlank() && (amount == null || amount < 0.0)) {
                    total.error = "Importo non valido"
                    return@setOnClickListener
                }

                record.type = ToolWarrantyArchive.DOCUMENT_TYPE
                record.toolName = nameValue
                record.toolType = typeValue
                record.documentDate = dateValue
                record.supplier = supplier.text.toString().trim()
                record.totalAmount = amount
                record.documentNumber = ""
                record.category = ""
                record.jobsiteId = ""
                record.jobsiteCode = ""
                record.jobsiteTitle = ""

                runCatching {
                    val archived = ToolWarrantyArchive.commitPdf(
                        context = this,
                        sourcePath = record.pdfPath,
                        purchaseDate = record.documentDate,
                        toolName = record.toolName,
                        ge360Id = record.ge360Id
                    )
                    record.pdfPath = archived.absolutePath
                    store.save(record)
                    pendingPdf = null
                    ToolWarrantyArchive.writeIndex(this, warrantyRows())
                }.onSuccess {
                    dialog.dismiss()
                    refresh()
                    toast("Scontrino salvato in Scontrini garanzia")
                }.onFailure { error ->
                    toast("Salvataggio non riuscito: ${error.message}")
                }
            }
        }
        dialog.show()
    }

    private fun cleanupPending(record: ScannerDocument) {
        if (record.id > 0L) return
        pendingPdf?.takeIf { it.absolutePath == record.pdfPath && it.exists() }?.delete()
        pendingPdf = null
    }

    private fun showDetails(record: ScannerDocument) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        body.addView(TextView(this).apply {
            text = record.toolName.ifBlank { "Attrezzo" }
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
        })
        fun info(label: String, value: String) {
            body.addView(TextView(this).apply {
                text = "$label · ${value.ifBlank { "—" }}"
                setPadding(0, dp(6), 0, 0)
            })
        }
        info("Tipo", record.toolType)
        info("Data acquisto", record.documentDate)
        info("Negozio", record.supplier)
        info("Totale", record.totalAmount?.let(::euro).orEmpty())
        info("Cartella", ToolWarrantyArchive.ROOT_FOLDER)

        body.addView(MaterialButton(this).apply {
            text = "Apri PDF"
            setOnClickListener { openPdf(record) }
        })
        body.addView(MaterialButton(this).apply {
            text = "Modifica dati"
            setOnClickListener { showEditor(store.get(record.id) ?: record) }
        })
        body.addView(MaterialButton(this).apply {
            text = "Elimina scontrino"
            setOnClickListener { confirmDelete(record) }
        })

        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun openPdf(record: ScannerDocument) {
        runCatching {
            val file = File(record.pdfPath)
            require(file.exists() && file.isFile) { "PDF non trovato" }
            val uri = FileProvider.getUriForFile(this, "$packageName.scanner.files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Apri scontrino"))
        }.onFailure { toast(it.message ?: "Impossibile aprire il PDF") }
    }

    private fun confirmDelete(record: ScannerDocument) {
        AlertDialog.Builder(this)
            .setTitle("Elimina scontrino")
            .setMessage("Vuoi eliminare i dati e il PDF dalla cartella Scontrini garanzia?")
            .setPositiveButton("Elimina") { _, _ ->
                runCatching { File(record.pdfPath).takeIf { it.exists() }?.delete() }
                store.delete(record.id)
                ToolWarrantyArchive.writeIndex(this, warrantyRows())
                refresh()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun refresh() {
        if (!::adapter.isInitialized) return
        val rows = warrantyRows(if (::searchInput.isInitialized) searchInput.text.toString() else "")
        adapter.items = rows
        adapter.notifyDataSetChanged()
        statusLabel.text = when (rows.size) {
            0 -> "Nessuno scontrino · tocca SCANSIONA SCONTRINO"
            1 -> "1 scontrino · cartella Scontrini garanzia"
            else -> "${rows.size} scontrini · cartella Scontrini garanzia"
        }
        runCatching { ToolWarrantyArchive.writeIndex(this, warrantyRows()) }
    }

    private fun warrantyRows(query: String = ""): List<ScannerDocument> =
        store.list(query).filter(ToolWarrantyArchive::isWarrantyReceipt)

    private fun setProcessing(active: Boolean, message: String) {
        processing.visibility = if (active) View.VISIBLE else View.GONE
        statusLabel.text = message
    }

    private inner class ReceiptAdapter : BaseAdapter() {
        var items: List<ScannerDocument> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val receipt = items[position]
            val card = MaterialCardView(this@ToolWarrantyActivity).apply {
                radius = dp(14).toFloat()
                cardElevation = dp(1).toFloat()
            }
            card.addView(LinearLayout(this@ToolWarrantyActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                addView(TextView(this@ToolWarrantyActivity).apply {
                    text = receipt.toolName.ifBlank { "Attrezzo senza nome" }
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@ToolWarrantyActivity).apply {
                    val shop = receipt.supplier.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                    val amount = receipt.totalAmount?.let { " · ${euro(it)}" }.orEmpty()
                    text = "${receipt.toolType.ifBlank { "Tipo non indicato" }} · ${receipt.documentDate.ifBlank { "Data non indicata" }}$shop$amount"
                    textSize = 12f
                    setPadding(0, dp(4), 0, 0)
                })
            })
            return LinearLayout(this@ToolWarrantyActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(4))
                addView(card)
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun euro(value: Double): String = NumberFormat.getCurrencyInstance(Locale.ITALY).format(value)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_DOCUMENT_ID = "ge360_tool_warranty_document_id"
    }
}
