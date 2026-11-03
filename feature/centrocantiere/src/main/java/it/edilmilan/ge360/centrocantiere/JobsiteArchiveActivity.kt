package it.edilmilan.ge360.centrocantiere

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
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
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.core.common.Ge360Contracts
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import java.io.File
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

class JobsiteArchiveActivity : AppCompatActivity() {
    private lateinit var jobsiteStore: JobsiteStore
    private lateinit var store: JobsiteArchiveStore
    private lateinit var jobsite: JobsiteRecord
    private lateinit var plansContainer: LinearLayout
    private lateinit var clientDocsContainer: LinearLayout
    private lateinit var clientNotesContainer: LinearLayout
    private lateinit var internalNotesContainer: LinearLayout
    private lateinit var summaryLabel: TextView

    private var pendingCategory: String = JobsiteArchiveCategories.PLAN

    private val scanner by lazy {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(50)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
        )
    }

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data) ?: return@registerForActivityResult
        val uri = scan.pdf?.uri ?: run {
            Toast.makeText(this, "PDF della scansione non disponibile", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        importPdf(uri, pendingCategory, JobsiteArchiveSources.SCAN)
    }

    private val importPdfLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importPdf(uri, pendingCategory, JobsiteArchiveSources.UPLOAD)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jobsiteStore = JobsiteStore(this)
        store = JobsiteArchiveStore(this)
        jobsite = resolveJobsite() ?: run {
            Toast.makeText(this, "Cantiere non trovato", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(buildScreen())
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        if (::summaryLabel.isInitialized) refreshAll()
    }

    override fun onDestroy() {
        store.close()
        jobsiteStore.close()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Ge360Brand.Surface)
        }
        root.addView(
            buildScreenHeaderWithSubtitle(
                context = this,
                title = "Cartella cantiere",
                subtitle = "${jobsite.code} · ${jobsite.displayTitle()}",
                onBack = { finish() }
            )
        )

        summaryLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(dp(4), dp(6), dp(4), dp(8))
        }
        root.addView(summaryLabel)

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)

        body.addView(sectionTitle("📐 PLANIMETRIE", "PDF originali, varianti e scansioni della planimetria. Ogni file resta legato a questo cantiere."))
        body.addView(actionPair(
            "＋ CARICA PDF" to { choosePdf(JobsiteArchiveCategories.PLAN) },
            "▣ SCANSIONA" to { startScan(JobsiteArchiveCategories.PLAN) }
        ))
        plansContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(plansContainer)

        body.addView(sectionTitle("📄 DOCUMENTI CLIENTE", "Capitolati, schede, scelte materiali, autorizzazioni e documenti consegnati dal cliente."))
        body.addView(actionPair(
            "＋ CARICA PDF" to { choosePdf(JobsiteArchiveCategories.CLIENT_DOCUMENT) },
            "▣ SCANSIONA" to { startScan(JobsiteArchiveCategories.CLIENT_DOCUMENT) }
        ))
        clientDocsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(clientDocsContainer)

        body.addView(sectionTitle("👤 APPUNTI CLIENTE", "Richieste, scelte, misure e decisioni concordate. Sono separati dagli appunti interni."))
        body.addView(fullButton("＋ NUOVO APPUNTO CLIENTE") { showMemoEditor(JobsiteMemoTypes.CLIENT) })
        clientNotesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(clientNotesContainer)

        body.addView(sectionTitle("🔒 APPUNTI INTERNI", "Solo uso interno GE360: non devono essere inseriti automaticamente in PDF, preventivi, WhatsApp o documenti per il cliente."))
        body.addView(fullButton("＋ NUOVO APPUNTO INTERNO") { showMemoEditor(JobsiteMemoTypes.INTERNAL) })
        internalNotesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(internalNotesContainer)

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun refreshAll() {
        val plans = store.files(jobsite.ge360Id, JobsiteArchiveCategories.PLAN)
        val docs = store.files(jobsite.ge360Id, JobsiteArchiveCategories.CLIENT_DOCUMENT)
        val clientNotes = store.memos(jobsite.ge360Id, JobsiteMemoTypes.CLIENT)
        val internalNotes = store.memos(jobsite.ge360Id, JobsiteMemoTypes.INTERNAL)
        summaryLabel.text = "${plans.size} planimetrie · ${docs.size} documenti cliente · ${clientNotes.size} appunti cliente · 🔒 ${internalNotes.size} interni"
        renderFiles(plansContainer, plans, "Nessuna planimetria caricata.")
        renderFiles(clientDocsContainer, docs, "Nessun documento cliente caricato.")
        renderMemos(clientNotesContainer, clientNotes, false)
        renderMemos(internalNotesContainer, internalNotes, true)
    }

    private fun renderFiles(container: LinearLayout, rows: List<JobsiteArchiveFile>, empty: String) {
        container.removeAllViews()
        if (rows.isEmpty()) {
            container.addView(emptyText(empty))
            return
        }
        rows.forEach { file ->
            container.addView(MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = Ge360Brand.Border
                setCardBackgroundColor(Ge360Brand.Surface)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(7)
                }
                isClickable = true
                setOnClickListener { openPdf(file) }
                setOnLongClickListener { showFileActions(file); true }
                addView(LinearLayout(this@JobsiteArchiveActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(13), dp(11), dp(13), dp(11))
                    addView(TextView(this@JobsiteArchiveActivity).apply {
                        text = file.title.ifBlank { if (file.category == JobsiteArchiveCategories.PLAN) "Planimetria" else "Documento cliente" }
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ge360Brand.Text)
                    })
                    addView(TextView(this@JobsiteArchiveActivity).apply {
                        text = "${file.source} · ${formatDate(file.updatedAt)}${if (file.note.isBlank()) "" else " · ${file.note}"}"
                        textSize = 11f
                        setTextColor(Ge360Brand.TextSecondary)
                        setPadding(0, dp(4), 0, 0)
                    })
                })
            })
        }
    }

    private fun renderMemos(container: LinearLayout, rows: List<JobsiteMemo>, internal: Boolean) {
        container.removeAllViews()
        if (rows.isEmpty()) {
            container.addView(emptyText(if (internal) "Nessun appunto interno." else "Nessun appunto cliente."))
            return
        }
        rows.forEach { memo ->
            val prefix = buildString {
                if (internal) append("🔒 ")
                if (memo.pinned) append("📌 ")
                when (memo.priority) {
                    JobsiteMemoPriorities.URGENT -> append("⚠ ")
                    JobsiteMemoPriorities.IMPORTANT -> append("! ")
                }
            }
            container.addView(MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = if (internal) Ge360Brand.Border else Ge360Brand.Border
                setCardBackgroundColor(Ge360Brand.Surface)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) }
                isClickable = true
                setOnClickListener { showMemoEditor(memo.type, memo) }
                setOnLongClickListener { showMemoActions(memo); true }
                addView(LinearLayout(this@JobsiteArchiveActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(13), dp(11), dp(13), dp(11))
                    addView(TextView(this@JobsiteArchiveActivity).apply {
                        text = prefix + memo.text
                        textSize = 13f
                        setTextColor(Ge360Brand.Text)
                    })
                    addView(TextView(this@JobsiteArchiveActivity).apply {
                        text = listOf(memo.priority, memo.author, formatDate(memo.updatedAt)).filter { it.isNotBlank() }.joinToString(" · ")
                        textSize = 10f
                        setTextColor(Ge360Brand.TextSecondary)
                        setPadding(0, dp(5), 0, 0)
                    })
                })
            })
        }
    }

    private fun choosePdf(category: String) {
        pendingCategory = category
        importPdfLauncher.launch(arrayOf("application/pdf"))
    }

    private fun startScan(category: String) {
        pendingCategory = category
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { sender -> scanLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { error ->
                Toast.makeText(this, "Scanner non disponibile: ${error.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun importPdf(uri: Uri, category: String, source: String) {
        val target = runCatching { copyPdf(uri, category) }.getOrElse { error ->
            Toast.makeText(this, "Impossibile salvare il PDF: ${error.message}", Toast.LENGTH_LONG).show()
            return
        }
        showFileEditor(
            JobsiteArchiveFile(
                jobsiteId = jobsite.ge360Id,
                jobsiteCode = jobsite.code,
                jobsiteTitle = jobsite.displayTitle(),
                category = category,
                filePath = target.absolutePath,
                mimeType = "application/pdf",
                source = source,
                title = if (category == JobsiteArchiveCategories.PLAN) "Planimetria ${LocalDate.now()}" else "Documento cliente ${LocalDate.now()}"
            ),
            deleteOnCancel = true
        )
    }

    private fun showFileEditor(source: JobsiteArchiveFile, deleteOnCancel: Boolean = false) {
        val value = source.copy()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
        val title = EditText(this).apply { setText(value.title); hint = "Titolo"; isSingleLine = true }
        val note = EditText(this).apply { setText(value.note); hint = "Nota / versione / riferimento"; minLines = 2; gravity = Gravity.TOP }
        body.addView(title, full(dp(54)))
        body.addView(note, full(dp(82)))
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (value.category == JobsiteArchiveCategories.PLAN) "Salva planimetria" else "Salva documento cliente")
            .setView(body)
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()
        fun cleanup() {
            if (deleteOnCancel) runCatching { File(value.filePath).takeIf { it.exists() }?.delete() }
        }
        dialog.setOnCancelListener { cleanup() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { cleanup(); dialog.dismiss() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = title.text.toString().trim()
                if (t.isBlank()) { title.error = "Scrivi un titolo"; return@setOnClickListener }
                value.title = t
                value.note = note.text.toString().trim()
                store.saveFile(value)
                dialog.dismiss()
                refreshAll()
            }
        }
        dialog.show()
    }

    private fun showFileActions(value: JobsiteArchiveFile) {
        AlertDialog.Builder(this)
            .setTitle(value.title.ifBlank { "Documento" })
            .setItems(arrayOf("Apri PDF", "Modifica titolo/note", "Elimina")) { _, which ->
                when (which) {
                    0 -> openPdf(value)
                    1 -> showFileEditor(value)
                    2 -> confirmDeleteFile(value)
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun confirmDeleteFile(value: JobsiteArchiveFile) {
        AlertDialog.Builder(this)
            .setTitle("Elimina file")
            .setMessage("Eliminare questo file dalla cartella del cantiere?")
            .setPositiveButton("Elimina") { _, _ ->
                runCatching { File(value.filePath).takeIf { it.exists() }?.delete() }
                store.deleteFile(value)
                refreshAll()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun openPdf(value: JobsiteArchiveFile) {
        runCatching {
            val file = File(value.filePath)
            require(file.exists()) { "PDF non trovato" }
            val uri = FileProvider.getUriForFile(this, "$packageName.scanner.files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, value.mimeType.ifBlank { "application/pdf" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Apri documento"))
        }.onFailure { Toast.makeText(this, it.message ?: "Impossibile aprire il PDF", Toast.LENGTH_LONG).show() }
    }

    private fun showMemoEditor(type: String, source: JobsiteMemo? = null) {
        val value = source?.copy() ?: JobsiteMemo(jobsiteId = jobsite.ge360Id, type = type)
        val internal = type == JobsiteMemoTypes.INTERNAL
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
        if (internal) body.addView(TextView(this).apply {
            text = "🔒 APPUNTO INTERNO · non viene trattato come nota cliente e non deve essere condiviso automaticamente."
            textSize = 11f
            setTextColor(Ge360Brand.Danger)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        val text = EditText(this).apply { setText(value.text); hint = if (internal) "Nota interna" else "Richiesta, scelta, misura o decisione cliente"; minLines = 4; gravity = Gravity.TOP }
        val author = EditText(this).apply { setText(value.author); hint = "Autore (opzionale)"; isSingleLine = true }
        val priority = Spinner(this).apply {
            adapter = ArrayAdapter(this@JobsiteArchiveActivity, android.R.layout.simple_spinner_dropdown_item, JobsiteMemoPriorities.ALL)
            setSelection(JobsiteMemoPriorities.ALL.indexOf(value.priority).coerceAtLeast(0))
        }
        val pinned = CheckBox(this).apply { this.text = "📌 Fissa in alto"; isChecked = value.pinned }
        body.addView(text, full(dp(120)))
        body.addView(author, full(dp(52)))
        body.addView(TextView(this).apply { this.text = "Priorità"; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(6), 0, 0) })
        body.addView(priority, full(dp(52)))
        body.addView(pinned)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (source == null) if (internal) "Nuovo appunto interno" else "Nuovo appunto cliente" else "Modifica appunto")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cleaned = text.text.toString().trim()
                if (cleaned.isBlank()) { text.error = "Scrivi un appunto"; return@setOnClickListener }
                value.jobsiteId = jobsite.ge360Id
                value.type = type
                value.text = cleaned
                value.author = author.text.toString().trim()
                value.priority = priority.selectedItem.toString()
                value.pinned = pinned.isChecked
                store.saveMemo(value)
                dialog.dismiss()
                refreshAll()
            }
        }
        dialog.show()
    }

    private fun showMemoActions(value: JobsiteMemo) {
        AlertDialog.Builder(this)
            .setTitle(if (value.type == JobsiteMemoTypes.INTERNAL) "🔒 Appunto interno" else "Appunto cliente")
            .setItems(arrayOf("Modifica", "Elimina")) { _, which ->
                if (which == 0) showMemoEditor(value.type, value) else confirmDeleteMemo(value)
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun confirmDeleteMemo(value: JobsiteMemo) {
        AlertDialog.Builder(this)
            .setTitle("Elimina appunto")
            .setMessage("Eliminare definitivamente questo appunto?")
            .setPositiveButton("Elimina") { _, _ -> store.deleteMemo(value); refreshAll() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun copyPdf(uri: Uri, category: String): File {
        val folder = File(filesDir, "scanner/jobsite_archive/${safe(jobsite.ge360Id)}").apply { mkdirs() }
        val prefix = if (category == JobsiteArchiveCategories.PLAN) "plan" else "client-doc"
        val target = File(folder, "$prefix-${System.currentTimeMillis()}.pdf")
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "File non leggibile" }
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        return target
    }

    private fun resolveJobsite(): JobsiteRecord? {
        val uuid = intent.getStringExtra(Ge360Contracts.EXTRA_JOBSITE_ID).orEmpty()
        val localId = intent.getLongExtra(Ge360Contracts.EXTRA_JOBSITE_LOCAL_ID, 0L)
        return jobsiteStore.list().firstOrNull {
            (uuid.isNotBlank() && it.ge360Id == uuid) || (localId > 0 && it.id == localId)
        }
    }

    private fun sectionTitle(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(17), dp(4), dp(7))
        addView(TextView(this@JobsiteArchiveActivity).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.Text)
        })
        addView(TextView(this@JobsiteArchiveActivity).apply {
            text = subtitle
            textSize = 11f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun actionPair(left: Pair<String, () -> Unit>, right: Pair<String, () -> Unit>): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(MaterialButton(this@JobsiteArchiveActivity).apply { text = left.first; setOnClickListener { left.second() } }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(4) })
        addView(MaterialButton(this@JobsiteArchiveActivity).apply { text = right.first; setOnClickListener { right.second() } }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(4) })
    }

    private fun fullButton(label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label
        setOnClickListener { action() }
        layoutParams = full(dp(52))
    }

    private fun emptyText(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(Ge360Brand.TextSecondary)
        setPadding(dp(5), dp(9), dp(5), dp(5))
    }

    private fun formatDate(timestamp: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.ITALY).format(Date(timestamp))
    private fun safe(value: String) = value.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(80).ifBlank { "jobsite" }
    private fun full(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
