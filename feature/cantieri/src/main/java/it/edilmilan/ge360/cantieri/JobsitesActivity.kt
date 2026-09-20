package it.edilmilan.ge360.cantieri

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.clienti.ClientRecord
import it.edilmilan.ge360.clienti.ClientStore
import it.edilmilan.ge360.core.common.Ge360Contracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JobsitesActivity : AppCompatActivity() {
    private lateinit var store: JobsiteStore
    private lateinit var clients: ClientStore
    private lateinit var listContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var countLabel: TextView
    private var activeStatus = "Tutti"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = JobsiteStore(this)
        clients = ClientStore(this)
        setContentView(buildScreen())
        loadJobsites()
    }

    override fun onResume() {
        super.onResume()
        if (::listContainer.isInitialized) loadJobsites()
    }

    override fun onDestroy() {
        if (::store.isInitialized) store.close()
        if (::clients.isInitialized) clients.close()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Button(this).apply {
            text = "‹"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(54), dp(48)))
        header.addView(TextView(this).apply {
            text = "Cantieri"
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "＋"
            contentDescription = "Nuovo cantiere"
            setOnClickListener { editJobsite(null) }
        }, LinearLayout.LayoutParams(dp(54), dp(48)))
        header.addView(Button(this).apply {
            text = "⋮"
            contentDescription = "Backup cantieri"
            setOnClickListener { showDataMenu(this) }
        }, LinearLayout.LayoutParams(dp(54), dp(48)))
        root.addView(header)

        root.addView(TextView(this).apply {
            text = "Tocca un cantiere per aprire il Centro Cantiere · Fine cantiere lo archivia e lo toglie dall’operativo"
            textSize = 13f
            setPadding(0, dp(4), 0, dp(10))
        })

        searchInput = EditText(this).apply {
            hint = "Cerca codice, cliente, indirizzo, lavoro…"
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = loadJobsites()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(searchInput, matchWrap())

        val filterScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "Tutti",
            JobsiteStore.STATUS_INSPECTION,
            JobsiteStore.STATUS_ESTIMATE,
            JobsiteStore.STATUS_SCHEDULED,
            JobsiteStore.STATUS_ACTIVE,
            JobsiteStore.STATUS_PAUSED,
            JobsiteStore.STATUS_COMPLETED,
            JobsiteStore.STATUS_ARCHIVED
        ).forEach { status ->
            filterRow.addView(Button(this).apply {
                text = if (status == JobsiteStore.STATUS_ARCHIVED) "Archivio" else status
                textSize = 12f
                setOnClickListener {
                    activeStatus = status
                    loadJobsites()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(5) })
        }
        filterScroll.addView(filterRow)
        root.addView(filterScroll, matchWrap())

        countLabel = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(countLabel)

        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listContainer)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun loadJobsites() {
        if (!::listContainer.isInitialized || !::searchInput.isInitialized) return
        val query = searchInput.text?.toString().orEmpty()
        val rows = if (activeStatus == "Tutti") {
            store.list(query).filter { it.status != JobsiteStore.STATUS_ARCHIVED }
        } else {
            store.list(query, activeStatus)
        }
        listContainer.removeAllViews()
        val label = if (activeStatus == JobsiteStore.STATUS_ARCHIVED) "Archivio" else activeStatus
        countLabel.text = "${rows.size} ${if (rows.size == 1) "cantiere" else "cantieri"} · $label"
        if (rows.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = if (activeStatus == JobsiteStore.STATUS_ARCHIVED) {
                    "Archivio vuoto. I cantieri chiusi compariranno qui."
                } else {
                    "Nessun cantiere. Premi + per creare il primo."
                }
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(20))
            })
            return
        }
        rows.forEach { listContainer.addView(jobsiteCard(it)) }
    }

    private fun jobsiteCard(jobsite: JobsiteRecord): View {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(1).toFloat()
            setContentPadding(dp(16), dp(14), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { openCenter(jobsite) }
            setOnLongClickListener {
                showDetail(jobsite)
                true
            }
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(TextView(this).apply {
            text = "${jobsite.code} · ${jobsite.status}"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        })
        body.addView(TextView(this).apply {
            text = jobsite.displayTitle()
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, 0)
        })
        body.addView(TextView(this).apply {
            text = listOf(jobsite.clientName, jobsite.workType, jobsite.address)
                .filter { it.isNotBlank() }.joinToString(" · ")
            textSize = 13f
            setPadding(0, dp(4), 0, 0)
        })
        if (jobsite.startDate.isNotBlank() || jobsite.targetEndDate.isNotBlank()) {
            body.addView(TextView(this).apply {
                text = "${jobsite.startDate.ifBlank { "—" }} → ${jobsite.targetEndDate.ifBlank { "—" }}"
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
            })
        }

        body.addView(TextView(this).apply {
            text = "Apri Centro Cantiere  ›"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(9), 0, dp(5))
        })

        body.addView(Button(this).apply {
            isAllCaps = false
            text = if (jobsite.status == JobsiteStore.STATUS_ARCHIVED) "↩ Riapri cantiere" else "✓ Fine cantiere"
            textSize = 12f
            setOnClickListener {
                if (jobsite.status == JobsiteStore.STATUS_ARCHIVED) reopenJobsite(jobsite)
                else closeJobsite(jobsite)
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))

        card.addView(body)
        return card
    }

    private fun closeJobsite(jobsite: JobsiteRecord) {
        AlertDialog.Builder(this)
            .setTitle("Fine cantiere · ${jobsite.code}")
            .setMessage(
                "Confermi la chiusura di ${jobsite.displayTitle()}?\n\n" +
                    "Il cantiere verrà impostato come Archiviato e sparirà dalle viste operative. " +
                    "Rimangono nel database e nella cartella del cantiere tutti i dati collegati: " +
                    "foto, documenti, FIR, note/checklist, cronologia, preventivi, ore, spese, planimetrie e dati dei moduli.\n\n" +
                    "Potrai sempre trovarlo nel filtro Archivio e riaprirlo."
            )
            .setPositiveButton("ARCHIVIA CANTIERE") { _, _ ->
                store.setStatus(jobsite.id, JobsiteStore.STATUS_ARCHIVED)
                Toast.makeText(this, "Cantiere archiviato. Salvataggio finale richiesto.", Toast.LENGTH_LONG).show()
                // Uscendo dalla schermata il coordinatore globale di persistenza forza la materializzazione
                // della cartella cantiere e la replica di sicurezza.
                finish()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun reopenJobsite(jobsite: JobsiteRecord) {
        AlertDialog.Builder(this)
            .setTitle("Riapri cantiere")
            .setMessage("Riportare ${jobsite.displayTitle()} nell’operativo come In corso?")
            .setPositiveButton("RIAPRI") { _, _ ->
                store.setStatus(jobsite.id, JobsiteStore.STATUS_ACTIVE)
                loadJobsites()
                Toast.makeText(this, "Cantiere riaperto", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun openCenter(jobsite: JobsiteRecord) {
        val intent = Intent(Ge360Contracts.ACTION_OPEN_JOBSITE).apply {
            setPackage(packageName)
            putExtra(Ge360Contracts.EXTRA_JOBSITE_ID, jobsite.ge360Id)
            putExtra(Ge360Contracts.EXTRA_JOBSITE_LOCAL_ID, jobsite.id)
            putExtra(Ge360Contracts.EXTRA_JOBSITE_CODE, jobsite.code)
        }
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
        else Toast.makeText(this, "Centro Cantiere non disponibile in questa build", Toast.LENGTH_LONG).show()
    }

    private fun showDetail(jobsite: JobsiteRecord) {
        val current = store.get(jobsite.id) ?: return
        val text = buildString {
            append(current.code).append("\n")
            append("Stato: ").append(current.status).append("\n")
            append("Cliente: ").append(current.clientName.ifBlank { "—" }).append("\n")
            append("Lavoro: ").append(current.workType.ifBlank { "—" }).append("\n")
            append("Indirizzo: ").append(current.address.ifBlank { "—" }).append("\n")
            append("Inizio: ").append(current.startDate.ifBlank { "—" }).append("\n")
            append("Fine prevista: ").append(current.targetEndDate.ifBlank { "—" })
            if (current.notes.isNotBlank()) append("\n\n").append(current.notes)
        }
        AlertDialog.Builder(this)
            .setTitle(current.displayTitle())
            .setMessage(text)
            .setPositiveButton("Modifica") { _, _ -> editJobsite(current) }
            .setNeutralButton("Stato") { _, _ -> chooseStatus(current) }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun chooseStatus(jobsite: JobsiteRecord) {
        val values = JobsiteStore.STATUSES.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Stato · ${jobsite.code}")
            .setItems(values) { _, which ->
                store.setStatus(jobsite.id, values[which])
                loadJobsites()
            }
            .show()
    }

    private fun editJobsite(existing: JobsiteRecord?) {
        val allClients = clients.search()
        val clientOptions = mutableListOf<ClientRecord?>().apply { add(null); addAll(allClients) }
        val clientLabels = clientOptions.map { it?.fullName() ?: "Nessun cliente collegato" }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }

        val clientSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@JobsitesActivity, android.R.layout.simple_spinner_dropdown_item, clientLabels)
            val selected = existing?.clientId ?: intent.getLongExtra(EXTRA_CLIENT_ID, 0)
            val index = clientOptions.indexOfFirst { it?.id == selected }.coerceAtLeast(0)
            setSelection(index)
        }
        content.addView(label("Cliente")); content.addView(clientSpinner)

        val title = field("Nome cantiere", existing?.title.orEmpty())
        val address = field("Indirizzo", existing?.address.orEmpty())
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@JobsitesActivity, android.R.layout.simple_spinner_dropdown_item, JobsiteStore.WORK_TYPES)
            val index = JobsiteStore.WORK_TYPES.indexOf(existing?.workType).takeIf { it >= 0 } ?: 0
            setSelection(index)
        }
        val statusSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@JobsitesActivity, android.R.layout.simple_spinner_dropdown_item, JobsiteStore.STATUSES)
            val index = JobsiteStore.STATUSES.indexOf(existing?.status).takeIf { it >= 0 } ?: 0
            setSelection(index)
        }
        val start = field("Data inizio (gg/mm/aaaa)", existing?.startDate.orEmpty())
        val end = field("Fine prevista (gg/mm/aaaa)", existing?.targetEndDate.orEmpty())
        val notes = field("Note", existing?.notes.orEmpty(), multiline = true)

        content.addView(label("Nome")); content.addView(title)
        content.addView(label("Indirizzo")); content.addView(address)
        content.addView(label("Tipo lavoro")); content.addView(typeSpinner)
        content.addView(label("Stato")); content.addView(statusSpinner)
        content.addView(label("Inizio")); content.addView(start)
        content.addView(label("Fine prevista")); content.addView(end)
        content.addView(label("Note")); content.addView(notes)

        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nuovo cantiere" else "Modifica ${existing.code}")
            .setView(scroll)
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .apply {
                if (existing != null) setNeutralButton("Elimina", null)
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chosenClient = clientOptions[clientSpinner.selectedItemPosition]
                val record = existing ?: JobsiteRecord()
                record.clientId = chosenClient?.id ?: 0
                record.clientGe360Id = chosenClient?.ge360Id.orEmpty()
                record.clientName = chosenClient?.fullName().orEmpty()
                record.title = title.text.toString().trim()
                record.address = address.text.toString().trim().ifEmpty { chosenClient?.address.orEmpty() }
                record.workType = typeSpinner.selectedItem.toString()
                record.status = statusSpinner.selectedItem.toString()
                record.startDate = start.text.toString().trim()
                record.targetEndDate = end.text.toString().trim()
                record.notes = notes.text.toString().trim()
                if (record.title.isBlank() && record.address.isBlank() && record.clientName.isBlank()) {
                    Toast.makeText(this, "Inserisci almeno cliente, nome o indirizzo", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                store.save(record)
                dialog.dismiss()
                loadJobsites()
            }
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Elimina cantiere")
                        .setMessage("Eliminare ${existing.code}? Questa operazione non elimina il cliente.")
                        .setPositiveButton("Elimina") { _, _ ->
                            store.delete(existing.id)
                            dialog.dismiss()
                            loadJobsites()
                        }
                        .setNegativeButton("Annulla", null)
                        .show()
                }
            }
        }
        dialog.show()
    }

    private fun showDataMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Esporta backup cantieri")
            menu.add("Importa backup · unisci")
            menu.add("Importa backup · sostituisci")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "Esporta backup cantieri" -> exportBackup()
                    "Importa backup · unisci" -> importBackup(false)
                    "Importa backup · sostituisci" -> importBackup(true)
                }
                true
            }
            show()
        }
    }

    private fun exportBackup() {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ITALY).format(Date())
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "GE360-cantieri_$stamp.json")
        }, REQUEST_EXPORT)
    }

    private fun importBackup(replace: Boolean) {
        pendingReplace = replace
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }, REQUEST_IMPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            if (requestCode == REQUEST_EXPORT) {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(store.exportJson()) }
                Toast.makeText(this, "Backup cantieri creato", Toast.LENGTH_SHORT).show()
            } else if (requestCode == REQUEST_IMPORT) {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
                val count = store.importJson(json, pendingReplace)
                loadJobsites()
                Toast.makeText(this, "$count cantieri importati", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Backup non valido: ${e.message ?: "errore"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(10), 0, 0)
    }

    private fun field(hintText: String, value: String, multiline: Boolean = false) = EditText(this).apply {
        hint = hintText
        setText(value)
        if (multiline) {
            minLines = 3
            gravity = Gravity.TOP
        } else setSingleLine(true)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_CLIENT_ID = "ge360_client_id"
        private const val REQUEST_EXPORT = 501
        private const val REQUEST_IMPORT = 502
        private var pendingReplace = false
    }
}
