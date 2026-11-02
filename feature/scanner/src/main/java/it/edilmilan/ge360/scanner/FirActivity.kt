package it.edilmilan.ge360.scanner

import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.core.common.Ge360Contracts
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import java.time.LocalDate
import java.util.Locale

class FirActivity : AppCompatActivity() {
    private lateinit var store: FirStore
    private lateinit var jobsiteStore: JobsiteStore
    private lateinit var listView: ListView
    private lateinit var adapter: FirAdapter
    private lateinit var searchInput: EditText
    private lateinit var statusLabel: TextView
    private var contextJobsite: JobsiteRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = FirStore(this)
        jobsiteStore = JobsiteStore(this)
        contextJobsite = resolveRequestedJobsite()
        setContentView(buildScreen())
        loadRows()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) loadRows()
    }

    override fun onDestroy() {
        store.close()
        jobsiteStore.close()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        root.addView(buildScreenHeaderWithSubtitle(
            context = this,
            title = "FIR · Smaltimento ruderi",
            subtitle = contextJobsite?.let { "${it.code} · ${it.displayTitle()}" }
                ?: "Formulario rifiuti · cantieri · RENTRI/xFIR",
            onBack = { finish() }
        ))

        root.addView(TextView(this).apply {
            text = "Archivio operativo GE360 per formulari rifiuti. Conserva dati di cantiere, EER, quantità, trasportatore, destinatario e dati della pesa. L'emissione/trasmissione ufficiale resta sul sistema previsto da RENTRI."
            textSize = 11f
            setPadding(dp(4), dp(5), dp(4), dp(8))
        })

        statusLabel = TextView(this).apply {
            textSize = 13f
            setPadding(dp(4), dp(5), dp(4), dp(7))
        }
        root.addView(statusLabel)

        searchInput = EditText(this).apply {
            hint = "Cerca FIR, EER, rifiuto, trasportatore, impianto…"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = loadRows()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(searchInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        adapter = FirAdapter()
        listView = ListView(this).apply {
            dividerHeight = 0
            adapter = this@FirActivity.adapter
            setOnItemClickListener { _, _, position, _ -> showDetail(this@FirActivity.adapter.items[position]) }
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(MaterialButton(this@FirActivity).apply {
                text = "＋ NUOVO FIR"
                setOnClickListener { showEditor(newRecord()) }
            }, LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginEnd = dp(5) })
            addView(MaterialButton(this@FirActivity).apply {
                text = "▣ SCANSIONA FIR"
                setOnClickListener { openScannerForFir() }
            }, LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(5) })
        })
        return root
    }

    private fun newRecord(): FirRecord = FirRecord(
        jobsiteId = contextJobsite?.ge360Id.orEmpty(),
        jobsiteCode = contextJobsite?.code.orEmpty(),
        jobsiteTitle = contextJobsite?.displayTitle().orEmpty(),
        issueDate = LocalDate.now().toString(),
        mode = FirStore.MODE_DIGITAL
    )

    private fun showEditor(source: FirRecord) {
        val record = source.copy()
        val jobsites = jobsiteStore.list()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(8))
        }

        fun label(textValue: String) = TextView(this).apply {
            text = textValue
            setTypeface(typeface, Typeface.BOLD)
            textSize = 12f
            setPadding(0, dp(9), 0, 0)
        }
        fun field(value: String = "", hintValue: String = "", multiline: Boolean = false): EditText {
            val input = EditText(this).apply {
                setText(value)
                hint = hintValue
                if (multiline) {
                    minLines = 3
                    gravity = Gravity.TOP
                } else isSingleLine = true
            }
            content.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (multiline) dp(94) else dp(52)))
            return input
        }
        fun numberField(value: Double?, hintValue: String): EditText = field(value?.let(::plainNumber).orEmpty(), hintValue).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        content.addView(label("Cantiere"))
        val jobsiteLabels = mutableListOf("Seleziona cantiere").apply { addAll(jobsites.map { "${it.code} · ${it.displayTitle()}" }) }
        val jobsiteSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@FirActivity, android.R.layout.simple_spinner_dropdown_item, jobsiteLabels)
            val idx = jobsites.indexOfFirst { it.ge360Id == record.jobsiteId }
            setSelection(if (idx >= 0) idx + 1 else 0)
            isEnabled = contextJobsite == null
        }
        content.addView(jobsiteSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        content.addView(label("Modalità FIR"))
        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@FirActivity, android.R.layout.simple_spinner_dropdown_item, FirStore.MODES)
            setSelection(FirStore.MODES.indexOf(record.mode).coerceAtLeast(0))
        }
        content.addView(modeSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        content.addView(TextView(this).apply {
            text = "Verifica sempre gli aggiornamenti RENTRI prima dell'emissione."
            textSize = 10f
        })

        content.addView(label("Numero FIR")); val number = field(record.firNumber, "Numero formulario")
        content.addView(label("ID RENTRI / xFIR")); val rentri = field(record.rentriId, "Identificativo digitale, se presente")
        content.addView(label("Data (AAAA-MM-GG)")); val date = field(record.issueDate, LocalDate.now().toString())
        content.addView(label("Codice EER")); val eer = field(record.eerCode, "es. 17 01 07 · verificare classificazione")
        content.addView(label("Descrizione rifiuto / ruderi")); val waste = field(record.wasteDescription, "es. miscugli da demolizione", multiline = true)

        content.addView(label("Quantità dichiarata"))
        val quantityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val quantity = EditText(this).apply {
            setText(record.quantity?.let(::plainNumber).orEmpty())
            hint = "Quantità"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val unitSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@FirActivity, android.R.layout.simple_spinner_dropdown_item, FirStore.UNITS)
            setSelection(FirStore.UNITS.indexOf(record.unit).coerceAtLeast(0))
        }
        quantityRow.addView(quantity, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(5) })
        quantityRow.addView(unitSpinner, LinearLayout.LayoutParams(dp(100), dp(52)).apply { marginStart = dp(5) })
        content.addView(quantityRow)

        content.addView(label("Trasportatore")); val transporter = field(record.transporter, "Impresa / trasportatore")
        content.addView(label("Targa mezzo")); val plate = field(record.vehiclePlate, "Targa")
        content.addView(label("Impianto destinatario")); val plant = field(record.destinationPlant, "Impianto / destinazione")
        content.addView(label("Quantità/peso accettato")); val accepted = numberField(record.acceptedQuantity, "Dato di chiusura")

        content.addView(label("⚖ PESA IMPIANTO · OCR / CORREZIONE MANUALE"))
        content.addView(TextView(this).apply {
            text = "I dati vengono estratti dalle pagine successive/tagliando pesa del FIR. Puoi correggerli manualmente prima o dopo la scansione."
            textSize = 10f
        })
        content.addView(label("Data entrata (AAAA-MM-GG)")); val entryDate = field(record.entryDate, "Data entrata")
        content.addView(label("Ora entrata (HH:MM)")); val entryTime = field(record.entryTime, "Ora entrata")
        content.addView(label("Peso entrata kg")); val entryWeight = numberField(record.entryWeightKg, "Peso entrata")
        content.addView(label("Data uscita (AAAA-MM-GG)")); val exitDate = field(record.exitDate, "Data uscita")
        content.addView(label("Ora uscita (HH:MM)")); val exitTime = field(record.exitTime, "Ora uscita")
        content.addView(label("Peso uscita kg")); val exitWeight = numberField(record.exitWeightKg, "Peso uscita / tara")
        content.addView(label("Peso totale entrata kg")); val totalEntryWeight = numberField(record.totalEntryWeightKg, "Peso totale entrata")
        content.addView(label("Peso netto kg")); val netWeight = numberField(record.netWeightKg, "Peso netto conferito")

        content.addView(label("Stato"))
        val statusSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@FirActivity, android.R.layout.simple_spinner_dropdown_item, FirStore.STATUSES)
            setSelection(FirStore.STATUSES.indexOf(record.status).coerceAtLeast(0))
        }
        content.addView(statusSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        content.addView(label("Note")); val notes = field(record.notes, "Annotazioni, anomalie, riferimenti", multiline = true)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (record.id > 0) "Modifica FIR" else "Nuovo FIR")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedJob = if (jobsiteSpinner.selectedItemPosition > 0) jobsites[jobsiteSpinner.selectedItemPosition - 1] else contextJobsite
                if (selectedJob == null) {
                    Toast.makeText(this, "Seleziona il cantiere del FIR", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val dateValue = date.text.toString().trim()
                if (!validOptionalDate(dateValue)) {
                    date.error = "Usa AAAA-MM-GG"
                    return@setOnClickListener
                }
                val entryDateValue = entryDate.text.toString().trim()
                if (!validOptionalDate(entryDateValue)) {
                    entryDate.error = "Usa AAAA-MM-GG"
                    return@setOnClickListener
                }
                val exitDateValue = exitDate.text.toString().trim()
                if (!validOptionalDate(exitDateValue)) {
                    exitDate.error = "Usa AAAA-MM-GG"
                    return@setOnClickListener
                }
                val entryTimeValue = entryTime.text.toString().trim()
                if (!validOptionalTime(entryTimeValue)) {
                    entryTime.error = "Usa HH:MM"
                    return@setOnClickListener
                }
                val exitTimeValue = exitTime.text.toString().trim()
                if (!validOptionalTime(exitTimeValue)) {
                    exitTime.error = "Usa HH:MM"
                    return@setOnClickListener
                }

                record.jobsiteId = selectedJob.ge360Id
                record.jobsiteCode = selectedJob.code
                record.jobsiteTitle = selectedJob.displayTitle()
                record.mode = modeSpinner.selectedItem.toString()
                record.firNumber = number.text.toString().trim()
                record.rentriId = rentri.text.toString().trim()
                record.issueDate = dateValue
                record.eerCode = eer.text.toString().trim()
                record.wasteDescription = waste.text.toString().trim()
                record.quantity = parseNumber(quantity.text.toString())
                record.unit = unitSpinner.selectedItem.toString()
                record.transporter = transporter.text.toString().trim()
                record.vehiclePlate = plate.text.toString().trim().uppercase(Locale.ITALY)
                record.destinationPlant = plant.text.toString().trim()
                record.entryDate = entryDateValue
                record.entryTime = entryTimeValue
                record.entryWeightKg = parseNumber(entryWeight.text.toString())
                record.exitDate = exitDateValue
                record.exitTime = exitTimeValue
                record.exitWeightKg = parseNumber(exitWeight.text.toString())
                record.totalEntryWeightKg = parseNumber(totalEntryWeight.text.toString())
                record.netWeightKg = parseNumber(netWeight.text.toString())
                record.acceptedQuantity = parseNumber(accepted.text.toString()) ?: record.netWeightKg
                record.status = statusSpinner.selectedItem.toString()
                record.notes = notes.text.toString().trim()
                store.save(record)
                dialog.dismiss()
                loadRows()
                Toast.makeText(this, "FIR salvato nel cantiere", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun showDetail(record: FirRecord) {
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
        body.addView(TextView(this).apply {
            text = record.firNumber.ifBlank { "FIR da completare" }
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
        })
        info("Cantiere", listOf(record.jobsiteCode, record.jobsiteTitle).filter { it.isNotBlank() }.joinToString(" · "))
        info("Modalità", record.mode)
        info("Data", record.issueDate)
        info("ID RENTRI/xFIR", record.rentriId)
        info("EER", record.eerCode)
        info("Rifiuto", record.wasteDescription)
        info("Quantità dichiarata", formatQuantity(record.quantity, record.unit))
        info("Trasportatore", record.transporter)
        info("Targa", record.vehiclePlate)
        info("Destinatario", record.destinationPlant)
        info("Accettato", formatQuantity(record.acceptedQuantity, if (record.netWeightKg != null) "kg" else record.unit))

        body.addView(TextView(this).apply {
            text = "⚖ PESA IMPIANTO"
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14f
            setPadding(0, dp(12), 0, dp(2))
        })
        info("Entrata", formatDateTime(record.entryDate, record.entryTime))
        info("Peso entrata", formatKg(record.entryWeightKg))
        info("Uscita", formatDateTime(record.exitDate, record.exitTime))
        info("Peso uscita", formatKg(record.exitWeightKg))
        info("Peso totale entrata", formatKg(record.totalEntryWeightKg))
        info("Peso netto", formatKg(record.netWeightKg))

        info("Stato", record.status)
        info("Note", record.notes)

        body.addView(MaterialButton(this).apply {
            text = "Modifica FIR"
            setOnClickListener { showEditor(store.get(record.id) ?: record) }
        })
        body.addView(MaterialButton(this).apply {
            text = "▣ Scansiona / allega FIR nei Documenti"
            setOnClickListener { openScannerForFir(record) }
        })
        body.addView(MaterialButton(this).apply {
            text = "Elimina FIR"
            setOnClickListener { confirmDelete(record) }
        })

        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun confirmDelete(record: FirRecord) {
        AlertDialog.Builder(this)
            .setTitle("Elimina FIR")
            .setMessage("Eliminare questo record FIR? Eventuali PDF già salvati in Documenti non verranno cancellati.")
            .setPositiveButton("Elimina") { _, _ ->
                store.delete(record.id)
                loadRows()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun openScannerForFir(record: FirRecord? = null) {
        val job = record?.jobsiteId?.takeIf { it.isNotBlank() }?.let { id -> jobsiteStore.list().firstOrNull { it.ge360Id == id } } ?: contextJobsite
        startActivity(Intent(this, ScannerActivity::class.java).apply {
            putExtra(ScannerActivity.EXTRA_PREFILL_TYPE, "FIR")
            job?.let {
                putExtra(Ge360Contracts.EXTRA_JOBSITE_ID, it.ge360Id)
                putExtra(Ge360Contracts.EXTRA_JOBSITE_LOCAL_ID, it.id)
                putExtra(Ge360Contracts.EXTRA_JOBSITE_CODE, it.code)
            }
        })
    }

    private fun resolveRequestedJobsite(): JobsiteRecord? {
        val uuid = intent.getStringExtra(Ge360Contracts.EXTRA_JOBSITE_ID).orEmpty()
        val localId = intent.getLongExtra(Ge360Contracts.EXTRA_JOBSITE_LOCAL_ID, 0L)
        return jobsiteStore.list().firstOrNull {
            (uuid.isNotBlank() && it.ge360Id == uuid) || (localId > 0 && it.id == localId)
        }
    }

    private fun loadRows() {
        if (!::adapter.isInitialized) return
        val rows = store.list(
            jobsiteId = contextJobsite?.ge360Id.orEmpty(),
            query = if (::searchInput.isInitialized) searchInput.text.toString() else ""
        )
        adapter.items = rows
        adapter.notifyDataSetChanged()
        val closed = rows.count { it.status == FirStore.STATUS_CLOSED }
        statusLabel.text = "${rows.size} FIR · ${rows.size - closed} aperti · $closed chiusi${contextJobsite?.let { " · ${it.code}" } ?: ""}"
    }

    private inner class FirAdapter : BaseAdapter() {
        var items: List<FirRecord> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val f = items[position]
            val card = MaterialCardView(this@FirActivity).apply {
                radius = dp(14).toFloat()
                cardElevation = dp(1).toFloat()
            }
            card.addView(LinearLayout(this@FirActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                addView(TextView(this@FirActivity).apply {
                    text = f.firNumber.ifBlank { "FIR da compilare" }
                    setTypeface(typeface, Typeface.BOLD)
                    textSize = 16f
                })
                addView(TextView(this@FirActivity).apply {
                    text = listOf(f.issueDate.ifBlank { "senza data" }, f.mode, f.status).joinToString(" · ")
                    textSize = 12f
                    setPadding(0, dp(3), 0, 0)
                })
                addView(TextView(this@FirActivity).apply {
                    text = listOf(
                        f.eerCode.takeIf { it.isNotBlank() }?.let { "EER $it" }.orEmpty(),
                        f.wasteDescription,
                        formatQuantity(f.quantity, f.unit),
                        f.netWeightKg?.let { "netto ${plainNumber(it)} kg" }.orEmpty(),
                        f.jobsiteCode
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    textSize = 12f
                    setPadding(0, dp(3), 0, 0)
                })
            })
            return LinearLayout(this@FirActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(4))
                addView(card)
            }
        }
    }

    private fun validOptionalDate(value: String): Boolean = value.isBlank() || runCatching { LocalDate.parse(value) }.isSuccess
    private fun validOptionalTime(value: String): Boolean {
        if (value.isBlank()) return true
        val parts = value.split(':')
        if (parts.size != 2) return false
        val hour = parts[0].toIntOrNull() ?: return false
        val minute = parts[1].toIntOrNull() ?: return false
        return hour in 0..23 && minute in 0..59
    }
    private fun parseNumber(value: String): Double? = value.trim().replace(',', '.').toDoubleOrNull()
    private fun plainNumber(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString().replace('.', ',')
    private fun formatQuantity(value: Double?, unit: String): String = value?.let { "${plainNumber(it)} $unit" }.orEmpty()
    private fun formatKg(value: Double?): String = value?.let { "${plainNumber(it)} kg" }.orEmpty()
    private fun formatDateTime(date: String, time: String): String = listOf(date, time).filter { it.isNotBlank() }.joinToString(" · ")
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
