package it.edilmilan.ge360.spese

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

class ExpensesActivity : AppCompatActivity() {
    private lateinit var store: FinanceStore
    private lateinit var jobsiteStore: JobsiteStore
    private lateinit var searchInput: EditText
    private lateinit var summaryLabel: TextView
    private lateinit var monthLabel: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: FinanceAdapter
    private var activeFilter = FinanceStore.FILTER_ALL
    private var pendingImportReplace = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = FinanceStore(this)
        jobsiteStore = JobsiteStore(this)
        setContentView(buildScreen())
        loadData()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) loadData()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }

        root.addView(buildScreenHeaderWithSubtitle(
            context = this,
            title = "Spese",
            subtitle = "Entrate, uscite e costi per cantiere",
            onBack = { finish() },
            trailingLabel = "Dati",
            onTrailingClick = { showDataMenu() }
        ))

        monthLabel = TextView(this).apply {
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(10), dp(4), dp(2))
        }
        root.addView(monthLabel)

        summaryLabel = TextView(this).apply {
            textSize = 14f
            setPadding(dp(4), dp(2), dp(4), dp(8))
        }
        root.addView(summaryLabel)

        searchInput = EditText(this).apply {
            hint = "Cerca categoria, nota, cantiere, data…"
            isSingleLine = true
            setPadding(dp(14), dp(8), dp(14), dp(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = loadData()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(searchInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        FinanceStore.FILTERS.forEach { filter ->
            filters.addView(MaterialButton(this).apply {
                text = filter
                textSize = 12f
                setOnClickListener {
                    activeFilter = filter
                    loadData()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(6) })
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(filters)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        adapter = FinanceAdapter()
        listView = ListView(this).apply {
            dividerHeight = 0
            adapter = this@ExpensesActivity.adapter
            setOnItemClickListener { _, _, position, _ -> showEditor(this@ExpensesActivity.adapter.items[position]) }
            setOnItemLongClickListener { _, _, position, _ ->
                val item = this@ExpensesActivity.adapter.items[position]
                confirmDelete(item)
                true
            }
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(MaterialButton(this@ExpensesActivity).apply {
                text = "Riepilogo cantieri"
                setOnClickListener { showJobsiteSummary() }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
            addView(MaterialButton(this@ExpensesActivity).apply {
                text = "+ Movimento"
                setOnClickListener { showEditor(FinanceRecord(date = LocalDate.now().toString())) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        })
        return root
    }

    private fun loadData() {
        if (!::adapter.isInitialized) return
        val rows = store.search(if (::searchInput.isInitialized) searchInput.text.toString() else "", activeFilter)
        adapter.items = rows
        adapter.notifyDataSetChanged()

        val month = YearMonth.now()
        val allMonth = store.all().filter { runCatching { YearMonth.from(LocalDate.parse(it.date)) }.getOrNull() == month }
        val income = allMonth.filter { it.type == FinanceType.INCOME }.sumOf { it.amount }
        val expense = allMonth.filter { it.type == FinanceType.EXPENSE }.sumOf { it.amount }
        val businessExpense = allMonth.filter { it.type == FinanceType.EXPENSE && it.scope == FinanceScope.BUSINESS }.sumOf { it.amount }
        monthLabel.text = "${month.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ITALIAN).replaceFirstChar { it.uppercase() }} ${month.year} · saldo ${euro(income - expense)}"
        summaryLabel.text = "Entrate ${euro(income)} · Uscite ${euro(expense)} · Costi azienda ${euro(businessExpense)} · ${rows.size} movimenti visibili"
    }

    private fun showEditor(source: FinanceRecord) {
        val record = source.copy()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        }

        content.addView(label("Tipo"))
        val typeSpinner = Spinner(this)
        val typeLabels = listOf("Uscita", "Entrata")
        typeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, typeLabels)
        typeSpinner.setSelection(if (record.type == FinanceType.INCOME) 1 else 0)
        content.addView(typeSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        content.addView(label("Ambito"))
        val scopeSpinner = Spinner(this)
        val scopeLabels = listOf("Azienda", "Privato")
        scopeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, scopeLabels)
        scopeSpinner.setSelection(if (record.scope == FinanceScope.PRIVATE) 1 else 0)
        content.addView(scopeSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        content.addView(label("Importo €"))
        val amount = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (record.amount > 0) record.amount.toString().replace('.', ',') else "")
            hint = "0,00"
        }
        content.addView(amount, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        content.addView(label("Categoria"))
        val category = Spinner(this)
        fun categoriesFor(type: FinanceType): List<String> = if (type == FinanceType.EXPENSE) FinanceStore.EXPENSE_CATEGORIES else FinanceStore.INCOME_CATEGORIES
        fun refreshCategories(selected: String = "") {
            val selectedType = if (typeSpinner.selectedItemPosition == 1) FinanceType.INCOME else FinanceType.EXPENSE
            val values = categoriesFor(selectedType)
            category.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
            val idx = values.indexOf(selected)
            if (idx >= 0) category.setSelection(idx)
        }
        refreshCategories(record.category)
        content.addView(category, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        typeSpinner.setOnItemSelectedListener(SimpleItemSelectedListener { refreshCategories(if (record.id > 0) record.category else "") })

        content.addView(label("Cantiere (solo Azienda)"))
        val jobsites = jobsiteStore.list()
        val jobsiteLabels = mutableListOf("Nessun cantiere").apply {
            addAll(jobsites.map { "${it.code} · ${it.displayTitle()}" })
        }
        val jobsiteSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ExpensesActivity, android.R.layout.simple_spinner_dropdown_item, jobsiteLabels)
            val idx = jobsites.indexOfFirst { it.ge360Id == record.jobsiteId }
            setSelection(if (idx >= 0) idx + 1 else 0)
        }
        content.addView(jobsiteSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        content.addView(label("Data (AAAA-MM-GG)"))
        val date = EditText(this).apply {
            setText(record.date.ifBlank { LocalDate.now().toString() })
            isSingleLine = true
        }
        content.addView(date, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        content.addView(label("Nota"))
        val note = EditText(this).apply {
            setText(record.note)
            minLines = 3
            gravity = Gravity.TOP
        }
        content.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90)))

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (record.id > 0) "Modifica movimento" else "Nuovo movimento")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = amount.text.toString().replace(',', '.').toDoubleOrNull()
                if (value == null || value <= 0) {
                    amount.error = "Inserisci un importo valido"
                    return@setOnClickListener
                }
                val parsedDate = runCatching { LocalDate.parse(date.text.toString().trim()) }.getOrNull()
                if (parsedDate == null) {
                    date.error = "Usa il formato AAAA-MM-GG"
                    return@setOnClickListener
                }
                record.type = if (typeSpinner.selectedItemPosition == 1) FinanceType.INCOME else FinanceType.EXPENSE
                record.scope = if (scopeSpinner.selectedItemPosition == 1) FinanceScope.PRIVATE else FinanceScope.BUSINESS
                record.amount = value
                record.category = category.selectedItem?.toString().orEmpty().ifBlank { "Altro" }
                record.note = note.text.toString().trim()
                record.date = parsedDate.toString()
                if (record.scope == FinanceScope.BUSINESS && jobsiteSpinner.selectedItemPosition > 0) {
                    val jobsite = jobsites[jobsiteSpinner.selectedItemPosition - 1]
                    record.jobsiteId = jobsite.ge360Id
                    record.jobsiteCode = jobsite.code
                    record.jobsiteTitle = jobsite.displayTitle()
                } else {
                    record.jobsiteId = ""
                    record.jobsiteCode = ""
                    record.jobsiteTitle = ""
                }
                store.save(record)
                dialog.dismiss()
                loadData()
                FireflyFinanceSync.syncAsync(this, record) {
                    runOnUiThread {
                        loadData()
                        if (!it.ok) {
                            Toast.makeText(
                                this,
                                "Salvato sul telefono · Firefly da riprovare",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(record: FinanceRecord) {
        AlertDialog.Builder(this)
            .setTitle("Elimina movimento")
            .setMessage("Eliminare ${record.category} da ${euro(record.amount)} del ${formatDate(record.date)}?")
            .setPositiveButton("Elimina") { _, _ -> store.delete(record.id); loadData() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showJobsiteSummary() {
        val jobsites = jobsiteStore.list().filter { store.forJobsite(it.ge360Id).isNotEmpty() }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        if (jobsites.isEmpty()) {
            body.addView(TextView(this).apply { text = "Nessun movimento è ancora collegato a un cantiere." })
        } else {
            jobsites.forEach { jobsite ->
                val s = store.jobsiteSummary(jobsite.ge360Id)
                body.addView(MaterialCardView(this).apply {
                    radius = dp(14).toFloat()
                    addView(LinearLayout(this@ExpensesActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        addView(TextView(this@ExpensesActivity).apply {
                            text = "${jobsite.code} · ${jobsite.displayTitle()}"
                            setTypeface(typeface, Typeface.BOLD)
                            textSize = 16f
                        })
                        addView(TextView(this@ExpensesActivity).apply {
                            text = "Entrate ${euro(s.income)} · Uscite ${euro(s.expense)}\nSaldo movimenti ${euro(s.balance)} · ${s.movements} registrazioni"
                            textSize = 13f
                            setPadding(0, dp(5), 0, 0)
                        })
                    })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Riepilogo per cantiere")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun showDataMenu() {
        val choices = arrayOf(
            "Esporta backup JSON",
            "Importa e unisci",
            "Importa e sostituisci",
            "Esporta CSV",
            "Riprova sincronizzazione Firefly"
        )
        AlertDialog.Builder(this)
            .setTitle("Dati Spese")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "ge360-spese-backup.json")
                    }, REQ_EXPORT_JSON)
                    1 -> {
                        pendingImportReplace = false
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE) }, REQ_IMPORT_JSON)
                    }
                    2 -> {
                        pendingImportReplace = true
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE) }, REQ_IMPORT_JSON)
                    }
                    3 -> startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_TITLE, "ge360-spese.csv")
                    }, REQ_EXPORT_CSV)
                    4 -> {
                        FireflyFinanceSync.syncPendingAsync(this, store.all()) { result ->
                            runOnUiThread {
                                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                                loadData()
                            }
                        }
                    }
                }
            }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQ_EXPORT_JSON -> writeText(uri, store.exportJson(), "Backup JSON esportato")
            REQ_EXPORT_CSV -> writeText(uri, store.exportCsv(), "CSV esportato")
            REQ_IMPORT_JSON -> {
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("File non leggibile")
                    store.importJson(text, pendingImportReplace)
                }.onSuccess { count ->
                    Toast.makeText(this, "Importati $count movimenti", Toast.LENGTH_LONG).show()
                    loadData()
                }.onFailure {
                    Toast.makeText(this, "Importazione non riuscita: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun writeText(uri: android.net.Uri, text: String, message: String) {
        runCatching {
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) } ?: error("File non scrivibile")
        }.onSuccess { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, "Esportazione non riuscita: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private inner class FinanceAdapter : BaseAdapter() {
        var items: List<FinanceRecord> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val r = items[position]
            val card = MaterialCardView(this@ExpensesActivity).apply {
                radius = dp(15).toFloat()
                cardElevation = dp(1).toFloat()
                layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val body = LinearLayout(this@ExpensesActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            body.addView(LinearLayout(this@ExpensesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(this@ExpensesActivity).apply {
                    text = r.category
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@ExpensesActivity).apply {
                    text = (if (r.type == FinanceType.INCOME) "+" else "−") + euro(r.amount)
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                })
            })
            body.addView(TextView(this@ExpensesActivity).apply {
                val scope = if (r.scope == FinanceScope.BUSINESS) "Azienda" else "Privato"
                val jobsite = if (r.jobsiteCode.isBlank()) "" else " · ${r.jobsiteCode} ${r.jobsiteTitle}"
                val firefly = when (r.fireflyStatus) {
                    FireflySyncStatus.SYNCED -> " · Firefly ✓"
                    FireflySyncStatus.ERROR -> " · Firefly ⚠"
                    FireflySyncStatus.PENDING -> " · Firefly …"
                }
                text = "$scope · ${formatDate(r.date)}$jobsite$firefly${if (r.note.isBlank()) "" else "\n${r.note}"}"
                textSize = 12f
                setPadding(0, dp(4), 0, 0)
            })
            card.addView(body)
            return LinearLayout(this@ExpensesActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(4))
                addView(card)
            }
        }
    }

    private class SimpleItemSelectedListener(val block: () -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = block()
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private fun euro(value: Double): String = NumberFormat.getCurrencyInstance(Locale.ITALY).format(value)
    private fun formatDate(value: String): String = runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) }.getOrDefault(value)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_EXPORT_JSON = 501
        private const val REQ_IMPORT_JSON = 502
        private const val REQ_EXPORT_CSV = 503
    }
}
