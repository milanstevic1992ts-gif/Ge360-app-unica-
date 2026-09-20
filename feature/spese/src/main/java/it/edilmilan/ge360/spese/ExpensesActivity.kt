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
    private lateinit var monthLabel: TextView
    private lateinit var balanceValue: TextView
    private lateinit var balanceDetail: TextView
    private lateinit var incomeValue: TextView
    private lateinit var expenseValue: TextView
    private lateinit var businessValue: TextView
    private lateinit var businessDetail: TextView
    private lateinit var privateValue: TextView
    private lateinit var privateDetail: TextView
    private lateinit var visibleCountLabel: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: FinanceAdapter
    private val filterButtons = mutableMapOf<String, MaterialButton>()
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
            subtitle = "Dashboard entrate, uscite, privato e azienda",
            onBack = { finish() },
            trailingLabel = "Dati",
            onTrailingClick = { showDataMenu() }
        ))

        monthLabel = TextView(this).apply {
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(10), dp(4), dp(8))
        }
        root.addView(monthLabel)

        root.addView(metricCard(
            title = "Saldo del mese",
            onClick = { setActiveFilter(FinanceStore.FILTER_ALL) },
            bind = { value, detail ->
                balanceValue = value
                balanceDetail = detail
            }
        ), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104)).apply {
            bottomMargin = dp(8)
        })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(metricCard(
                title = "Entrate",
                onClick = { setActiveFilter("Entrate") },
                bind = { value, _ -> incomeValue = value }
            ), LinearLayout.LayoutParams(0, dp(108), 1f).apply { marginEnd = dp(4) })
            addView(metricCard(
                title = "Uscite",
                onClick = { setActiveFilter("Uscite") },
                bind = { value, _ -> expenseValue = value }
            ), LinearLayout.LayoutParams(0, dp(108), 1f).apply { marginStart = dp(4) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(108)).apply {
            bottomMargin = dp(8)
        })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(metricCard(
                title = "Azienda",
                onClick = { setActiveFilter("Azienda") },
                bind = { value, detail ->
                    businessValue = value
                    businessDetail = detail
                }
            ), LinearLayout.LayoutParams(0, dp(114), 1f).apply { marginEnd = dp(4) })
            addView(metricCard(
                title = "Privato",
                onClick = { setActiveFilter("Privato") },
                bind = { value, detail ->
                    privateValue = value
                    privateDetail = detail
                }
            ), LinearLayout.LayoutParams(0, dp(114), 1f).apply { marginStart = dp(4) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(114)).apply {
            bottomMargin = dp(8)
        })

        visibleCountLabel = TextView(this).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(4), dp(4), dp(6))
        }
        root.addView(visibleCountLabel)

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
            val button = MaterialButton(this).apply {
                textSize = 11f
                setOnClickListener { setActiveFilter(filter) }
            }
            filterButtons[filter] = button
            filters.addView(
                button,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(6) }
            )
        }
        refreshFilterButtons()
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
                text = "Cantieri"
                setOnClickListener { showJobsiteSummary() }
            }, LinearLayout.LayoutParams(0, dp(54), 0.8f).apply { marginEnd = dp(4) })
            addView(MaterialButton(this@ExpensesActivity).apply {
                text = "+ Uscita"
                setOnClickListener {
                    showEditor(
                        FinanceRecord(date = LocalDate.now().toString(), type = FinanceType.EXPENSE),
                        FinanceType.EXPENSE
                    )
                }
            }, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
            addView(MaterialButton(this@ExpensesActivity).apply {
                text = "+ Entrata"
                setOnClickListener {
                    showEditor(
                        FinanceRecord(date = LocalDate.now().toString(), type = FinanceType.INCOME),
                        FinanceType.INCOME
                    )
                }
            }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(4) })
        })
        return root
    }

    private fun metricCard(
        title: String,
        onClick: () -> Unit,
        bind: (TextView, TextView) -> Unit
    ): MaterialCardView {
        val value = TextView(this).apply {
            text = euro(0.0)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        val detail = TextView(this).apply {
            textSize = 11.5f
            setPadding(0, dp(4), 0, 0)
        }
        bind(value, detail)
        return MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(LinearLayout(this@ExpensesActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                addView(TextView(this@ExpensesActivity).apply {
                    text = title
                    textSize = 12.5f
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(value)
                addView(detail)
            })
        }
    }

    private fun setActiveFilter(filter: String) {
        activeFilter = filter
        refreshFilterButtons()
        if (::adapter.isInitialized) loadData()
    }

    private fun refreshFilterButtons() {
        filterButtons.forEach { (filter, button) ->
            button.text = if (filter == activeFilter) "✓ $filter" else filter
        }
    }

    private fun loadData() {
        if (!::adapter.isInitialized) return
        val rows = store.search(if (::searchInput.isInitialized) searchInput.text.toString() else "", activeFilter)
        adapter.items = rows
        adapter.notifyDataSetChanged()

        val month = YearMonth.now()
        val allMonth = store.all().filter {
            runCatching { YearMonth.from(LocalDate.parse(it.date)) }.getOrNull() == month
        }

        val income = allMonth.filter { it.type == FinanceType.INCOME }.sumOf { it.amount }
        val expense = allMonth.filter { it.type == FinanceType.EXPENSE }.sumOf { it.amount }

        val businessIncome = allMonth.filter {
            it.scope == FinanceScope.BUSINESS && it.type == FinanceType.INCOME
        }.sumOf { it.amount }
        val businessExpense = allMonth.filter {
            it.scope == FinanceScope.BUSINESS && it.type == FinanceType.EXPENSE
        }.sumOf { it.amount }

        val privateIncome = allMonth.filter {
            it.scope == FinanceScope.PRIVATE && it.type == FinanceType.INCOME
        }.sumOf { it.amount }
        val privateExpense = allMonth.filter {
            it.scope == FinanceScope.PRIVATE && it.type == FinanceType.EXPENSE
        }.sumOf { it.amount }

        monthLabel.text = "\${month.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ITALIAN).replaceFirstChar { it.uppercase() }} \${month.year}"

        balanceValue.text = euro(income - expense)
        balanceDetail.text = "Entrate \${euro(income)} · Uscite \${euro(expense)}"
        incomeValue.text = euro(income)
        expenseValue.text = euro(expense)

        businessValue.text = euro(businessIncome - businessExpense)
        businessDetail.text = "Entrate \${euro(businessIncome)}\\nUscite \${euro(businessExpense)}"

        privateValue.text = euro(privateIncome - privateExpense)
        privateDetail.text = "Entrate \${euro(privateIncome)}\\nUscite \${euro(privateExpense)}"

        visibleCountLabel.text = if (activeFilter == FinanceStore.FILTER_ALL) {
            "Movimenti · \${rows.size} visibili"
        } else {
            "Movimenti · \${rows.size} visibili · $activeFilter"
        }
    }

    private fun showEditor(source: FinanceRecord, initialType: FinanceType? = null) {
        val record = source.copy()
        if (record.id == 0L && initialType != null) record.type = initialType

        var selectedType = record.type
        var selectedScope = record.scope

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(3))
        }

        content.addView(label("Tipo movimento"))
        val expenseButton = MaterialButton(this).apply { text = "Uscita" }
        val incomeButton = MaterialButton(this).apply { text = "Entrata" }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(expenseButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(4) })
            addView(incomeButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(4) })
        })

        content.addView(label("È un movimento aziendale o privato?"))
        val businessButton = MaterialButton(this).apply { text = "Azienda" }
        val privateButton = MaterialButton(this).apply { text = "Privato" }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(businessButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(4) })
            addView(privateButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(4) })
        })

        val scopeHint = TextView(this).apply {
            textSize = 12f
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        content.addView(scopeHint)

        content.addView(label("Importo €"))
        val amount = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (record.amount > 0) record.amount.toString().replace('.', ',') else "")
            hint = "0,00"
            textSize = 20f
        }
        content.addView(amount, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        content.addView(label("Categoria"))
        val category = Spinner(this)
        content.addView(category, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        val jobsites = jobsiteStore.list()
        val jobsiteLabels = mutableListOf("Nessun cantiere").apply {
            addAll(jobsites.map { "\${it.code} · \${it.displayTitle()}" })
        }
        val jobsiteSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ExpensesActivity, android.R.layout.simple_spinner_dropdown_item, jobsiteLabels)
            val idx = jobsites.indexOfFirst { it.ge360Id == record.jobsiteId }
            setSelection(if (idx >= 0) idx + 1 else 0)
        }
        val jobsiteBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Cantiere (facoltativo)"))
            addView(jobsiteSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }
        content.addView(jobsiteBlock)

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
            hint = "Es. acquisto piastrelle, spesa supermercato, acconto cliente…"
        }
        content.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90)))

        fun categoriesFor(type: FinanceType, scope: FinanceScope): List<String> = when {
            type == FinanceType.EXPENSE && scope == FinanceScope.BUSINESS -> FinanceStore.BUSINESS_EXPENSE_CATEGORIES
            type == FinanceType.EXPENSE && scope == FinanceScope.PRIVATE -> FinanceStore.PRIVATE_EXPENSE_CATEGORIES
            type == FinanceType.INCOME && scope == FinanceScope.BUSINESS -> FinanceStore.BUSINESS_INCOME_CATEGORIES
            else -> FinanceStore.PRIVATE_INCOME_CATEGORIES
        }

        fun refreshCategories(preferred: String? = null) {
            val values = categoriesFor(selectedType, selectedScope)
            val previous = preferred ?: category.selectedItem?.toString().orEmpty()
            category.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
            val wanted = when {
                previous in values -> previous
                record.category in values -> record.category
                "Altro" in values -> "Altro"
                else -> values.first()
            }
            val idx = values.indexOf(wanted)
            if (idx >= 0) category.setSelection(idx)
        }

        fun refreshTypeUi() {
            expenseButton.text = if (selectedType == FinanceType.EXPENSE) "✓ Uscita" else "Uscita"
            incomeButton.text = if (selectedType == FinanceType.INCOME) "✓ Entrata" else "Entrata"
        }

        fun refreshScopeUi() {
            businessButton.text = if (selectedScope == FinanceScope.BUSINESS) "✓ Azienda" else "Azienda"
            privateButton.text = if (selectedScope == FinanceScope.PRIVATE) "✓ Privato" else "Privato"
            scopeHint.text = if (selectedScope == FinanceScope.BUSINESS) {
                "Movimento dell'impresa. Puoi anche collegarlo a un cantiere."
            } else {
                "Movimento personale. Non verrà collegato ai cantieri."
            }
            jobsiteBlock.visibility = if (selectedScope == FinanceScope.BUSINESS) View.VISIBLE else View.GONE
        }

        expenseButton.setOnClickListener {
            val previous = category.selectedItem?.toString()
            selectedType = FinanceType.EXPENSE
            refreshTypeUi()
            refreshCategories(previous)
        }
        incomeButton.setOnClickListener {
            val previous = category.selectedItem?.toString()
            selectedType = FinanceType.INCOME
            refreshTypeUi()
            refreshCategories(previous)
        }
        businessButton.setOnClickListener {
            val previous = category.selectedItem?.toString()
            selectedScope = FinanceScope.BUSINESS
            refreshScopeUi()
            refreshCategories(previous)
        }
        privateButton.setOnClickListener {
            val previous = category.selectedItem?.toString()
            selectedScope = FinanceScope.PRIVATE
            refreshScopeUi()
            refreshCategories(previous)
        }

        refreshTypeUi()
        refreshScopeUi()
        refreshCategories(record.category)

        val title = when {
            record.id > 0 -> "Modifica movimento"
            selectedType == FinanceType.INCOME -> "Nuova entrata"
            else -> "Nuova uscita"
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
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

                record.type = selectedType
                record.scope = selectedScope
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
