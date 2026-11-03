package it.edilmilan.ge360.spese

import android.app.Activity
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.core.common.ui.BaseGeActivity
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.core.common.ui.GeFilterRow
import it.edilmilan.ge360.core.common.ui.GeListContainer
import it.edilmilan.ge360.core.common.ui.GeRadius
import it.edilmilan.ge360.core.common.ui.GeSpace
import it.edilmilan.ge360.core.common.ui.GeType
import it.edilmilan.ge360.core.common.ui.applyGe360Card
import it.edilmilan.ge360.core.common.ui.applyGe360Subtitle
import it.edilmilan.ge360.core.common.ui.applyGe360Title
import it.edilmilan.ge360.core.common.ui.brandRounded
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.core.common.ui.dp
import it.edilmilan.ge360.core.common.ui.geActionBar
import it.edilmilan.ge360.core.common.ui.geCancelPending
import it.edilmilan.ge360.core.common.ui.geEmptyState
import it.edilmilan.ge360.core.common.ui.geFieldLabel
import it.edilmilan.ge360.core.common.ui.geFilterRow
import it.edilmilan.ge360.core.common.ui.geInput
import it.edilmilan.ge360.core.common.ui.geLoad
import it.edilmilan.ge360.core.common.ui.geLoadDebounced
import it.edilmilan.ge360.core.common.ui.gePrimaryButton
import it.edilmilan.ge360.core.common.ui.geScreen
import it.edilmilan.ge360.core.common.ui.geSearchInput
import it.edilmilan.ge360.core.common.ui.geSecondaryButton
import it.edilmilan.ge360.core.common.ui.geStatRow
import it.edilmilan.ge360.core.common.ui.geStatTile
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Dashboard movimenti.
 *
 * Schermata di riferimento per la migrazione al design system: qui non ci sono
 * piu' colori scritti a mano, numeri di padding inventati, letture del database
 * sul thread della UI ne' righe di lista ricostruite da zero a ogni scorrimento.
 */
class ExpensesActivity : BaseGeActivity() {

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
    private lateinit var listContainer: GeListContainer
    private lateinit var filterRow: GeFilterRow

    private val adapter = FinanceAdapter()
    private var activeFilter = FinanceStore.FILTER_ALL
    private var pendingImportReplace = false

    /** Token del caricamento ritardato della ricerca, per annullarlo in uscita. */
    private val searchToken = Any()

    /** Istantanea dei dati che servono alla schermata, calcolata in background. */
    private data class Screen(
        val rows: List<FinanceRecord>,
        val month: MonthFinanceSummary
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = FinanceStore(this).managed()
        jobsiteStore = JobsiteStore(this).managed()
        setContentView(buildScreen())
        reload()

        // Arrivo diretto da "Oggi": apre subito il form, cosi' segnare uno
        // scontrino e' un tocco invece di tre.
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_NEW_EXPENSE, false)) {
            val type = if (intent.getBooleanExtra(EXTRA_AS_INCOME, false)) {
                FinanceType.INCOME
            } else {
                FinanceType.EXPENSE
            }
            showEditor(FinanceRecord(date = LocalDate.now().toString(), type = type), type)
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        geCancelPending(searchToken)
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Costruzione della schermata
    // -----------------------------------------------------------------------

    private fun buildScreen(): View {
        val root = geScreen()

        root.addView(
            buildScreenHeaderWithSubtitle(
                context = this,
                title = "Spese",
                subtitle = "Entrate, uscite, privato e azienda",
                onBack = { finish() },
                trailingLabel = "Dati",
                onTrailingClick = { showDataMenu() }
            )
        )

        val scrollable = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        monthLabel = TextView(this).apply {
            applyGe360Title(GeType.H2)
            setPadding(dp(GeSpace.XS), dp(GeSpace.SM), dp(GeSpace.XS), dp(GeSpace.SM))
        }
        scrollable.addView(monthLabel)

        // Saldo: il numero che conta, a tutta larghezza e senza vicini.
        scrollable.addView(
            geStatTile(
                label = "Saldo del mese",
                onClick = { setActiveFilter(FinanceStore.FILTER_ALL) }
            ) { value, detail ->
                balanceValue = value
                balanceDetail = detail
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        scrollable.addView(
            geStatRow(
                geStatTile(
                    label = "Entrate",
                    accent = Ge360Brand.Success,
                    onClick = { setActiveFilter("Entrate") }
                ) { value, _ -> incomeValue = value },
                geStatTile(
                    label = "Uscite",
                    accent = Ge360Brand.Danger,
                    onClick = { setActiveFilter("Uscite") }
                ) { value, _ -> expenseValue = value }
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(GeSpace.SM) }
        )

        scrollable.addView(
            geStatRow(
                geStatTile(
                    label = "Azienda",
                    onClick = { setActiveFilter("Azienda") }
                ) { value, detail ->
                    businessValue = value
                    businessDetail = detail
                },
                geStatTile(
                    label = "Privato",
                    accent = Ge360Brand.TechnicalBlue,
                    onClick = { setActiveFilter("Privato") }
                ) { value, detail ->
                    privateValue = value
                    privateDetail = detail
                }
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(GeSpace.SM) }
        )

        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(scrollable)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        searchInput = geSearchInput("Cerca categoria, nota, cantiere, data…").apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                    reload(debounced = true)

                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(searchInput)

        filterRow = geFilterRow(FinanceStore.FILTERS, activeFilter) { setActiveFilter(it) }
        root.addView(filterRow)

        visibleCountLabel = TextView(this).apply {
            applyGe360Subtitle(GeType.CAPTION)
            setPadding(dp(GeSpace.XS), dp(GeSpace.MD), dp(GeSpace.XS), dp(GeSpace.XS))
        }
        root.addView(visibleCountLabel)

        listContainer = GeListContainer(this).apply {
            list.layoutManager = LinearLayoutManager(this@ExpensesActivity)
            list.adapter = adapter
            list.setHasFixedSize(true)
        }
        root.addView(
            listContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        root.addView(
            geActionBar(
                geSecondaryButton("Cantieri") { showJobsiteSummary() },
                gePrimaryButton("+ Uscita") {
                    showEditor(
                        FinanceRecord(date = LocalDate.now().toString(), type = FinanceType.EXPENSE),
                        FinanceType.EXPENSE
                    )
                },
                gePrimaryButton("+ Entrata") {
                    showEditor(
                        FinanceRecord(date = LocalDate.now().toString(), type = FinanceType.INCOME),
                        FinanceType.INCOME
                    )
                }
            )
        )

        return root
    }

    // -----------------------------------------------------------------------
    // Caricamento dati
    // -----------------------------------------------------------------------

    private fun setActiveFilter(filter: String) {
        activeFilter = filter
        filterRow.select(filter)
        reload()
    }

    /**
     * Ricarica la schermata.
     *
     * Tutto il lavoro sul database sta dentro [load]: gira su un thread di
     * background e il risultato arriva solo se la schermata e' ancora aperta.
     * Con [debounced] la partenza aspetta che l'utente smetta di scrivere.
     */
    private fun reload(debounced: Boolean = false) {
        if (!::listContainer.isInitialized) return
        val query = if (::searchInput.isInitialized) searchInput.text.toString() else ""
        val filter = activeFilter
        val month = YearMonth.now()

        val work = {
            Screen(
                rows = store.search(query, filter),
                month = store.monthSummary(month.toString())
            )
        }
        val onResult: (Result<Screen>) -> Unit = { outcome ->
            outcome
                .onSuccess { render(month, it) }
                .onFailure { showLoadError(it) }
        }

        if (debounced) {
            geLoadDebounced(searchToken, work = work, onResult = onResult)
        } else {
            geLoad(work, onResult)
        }
    }

    private fun render(month: YearMonth, screen: Screen) {
        val summary = screen.month

        monthLabel.text = "${
            month.month.getDisplayName(TextStyle.FULL, Locale.ITALIAN)
                .replaceFirstChar { it.uppercase() }
        } ${month.year}"

        balanceValue.text = euro(summary.balance)
        balanceValue.setTextColor(
            if (summary.balance < 0) Ge360Brand.Danger else Ge360Brand.ForestDark
        )
        balanceDetail.text = "Entrate ${euro(summary.income)} · Uscite ${euro(summary.expense)}"

        incomeValue.text = euro(summary.income)
        expenseValue.text = euro(summary.expense)

        businessValue.text = euro(summary.businessBalance)
        businessDetail.text =
            "Entrate ${euro(summary.businessIncome)}\nUscite ${euro(summary.businessExpense)}"

        privateValue.text = euro(summary.privateBalance)
        privateDetail.text =
            "Entrate ${euro(summary.privateIncome)}\nUscite ${euro(summary.privateExpense)}"

        visibleCountLabel.text = if (activeFilter == FinanceStore.FILTER_ALL) {
            "Movimenti · ${screen.rows.size} visibili"
        } else {
            "Movimenti · ${screen.rows.size} visibili · $activeFilter"
        }

        adapter.submit(screen.rows)
        if (screen.rows.isEmpty()) {
            listContainer.showMessage(emptyStateView())
        } else {
            listContainer.showList()
        }
    }

    private fun emptyStateView(): View {
        val searching = searchInput.text.isNotBlank() || activeFilter != FinanceStore.FILTER_ALL
        return if (searching) {
            geEmptyState(
                title = "Nessun movimento",
                message = "Nessun risultato per questa ricerca o questo filtro.",
                actionLabel = "Azzera filtri"
            ) {
                searchInput.setText("")
                setActiveFilter(FinanceStore.FILTER_ALL)
            }
        } else {
            geEmptyState(
                title = "Ancora nessun movimento",
                message = "Registra la prima uscita o entrata: il saldo del mese si aggiorna da solo.",
                actionLabel = "+ Uscita"
            ) {
                showEditor(
                    FinanceRecord(date = LocalDate.now().toString(), type = FinanceType.EXPENSE),
                    FinanceType.EXPENSE
                )
            }
        }
    }

    /**
     * Un errore di lettura non lascia piu' la schermata vuota e muta.
     */
    private fun showLoadError(error: Throwable) {
        listContainer.showMessage(
            geEmptyState(
                title = "Dati non leggibili",
                message = "Non riesco a leggere l'archivio movimenti.\n${error.message.orEmpty().take(140)}",
                actionLabel = "Riprova"
            ) { reload() }
        )
    }

    // -----------------------------------------------------------------------
    // Lista
    // -----------------------------------------------------------------------

    /** Le viste di una riga, costruite una volta sola e poi riusate. */
    private class RowViews(
        val card: MaterialCardView,
        val accent: View,
        val title: TextView,
        val amount: TextView,
        val meta: TextView
    )

    private inner class FinanceHolder(val views: RowViews) : RecyclerView.ViewHolder(views.card)

    private inner class FinanceAdapter : RecyclerView.Adapter<FinanceHolder>() {

        private var items: List<FinanceRecord> = emptyList()

        fun submit(rows: List<FinanceRecord>) {
            items = rows
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FinanceHolder {
            val context = this@ExpensesActivity

            val accent = View(context)
            val title = TextView(context).apply {
                applyGe360Title(GeType.H3)
                maxLines = 1
            }
            val amount = TextView(context).apply {
                textSize = GeType.H3
                setTypeface(typeface, Typeface.BOLD)
            }
            val meta = TextView(context).apply {
                applyGe360Subtitle(GeType.CAPTION)
                setPadding(0, context.dp(GeSpace.XS), 0, 0)
            }

            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        title,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(amount)
                })
                addView(meta)
            }

            val body = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    context.dp(GeSpace.MD),
                    context.dp(GeSpace.MD),
                    context.dp(GeSpace.MD),
                    context.dp(GeSpace.MD)
                )
                addView(
                    accent,
                    LinearLayout.LayoutParams(context.dp(4), context.dp(36)).apply {
                        marginEnd = context.dp(GeSpace.MD)
                    }
                )
                addView(
                    texts,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }

            val card = MaterialCardView(context).apply {
                applyGe360Card()
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = context.dp(GeSpace.XS)
                    bottomMargin = context.dp(GeSpace.XS)
                }
                addView(body)
            }

            return FinanceHolder(RowViews(card, accent, title, amount, meta))
        }

        override fun onBindViewHolder(holder: FinanceHolder, position: Int) {
            val record = items[position]
            val views = holder.views
            val income = record.type == FinanceType.INCOME
            val color = if (income) Ge360Brand.Success else Ge360Brand.Danger

            views.accent.background = brandRounded(
                fill = color,
                stroke = color,
                radiusDp = GeRadius.SMALL,
                strokeDp = 0
            )

            views.title.text = record.category
            views.amount.text = (if (income) "+" else "−") + euro(record.amount)
            views.amount.setTextColor(if (income) Ge360Brand.Success else Ge360Brand.Text)

            val scope = if (record.scope == FinanceScope.BUSINESS) "Azienda" else "Privato"
            val jobsite =
                if (record.jobsiteCode.isBlank()) "" else " · ${record.jobsiteCode} ${record.jobsiteTitle}"
            val firefly = when (record.fireflyStatus) {
                FireflySyncStatus.SYNCED -> " · Firefly ✓"
                FireflySyncStatus.ERROR -> " · Firefly ⚠"
                FireflySyncStatus.PENDING -> " · Firefly …"
                // Non si sa se la transazione e' stata creata: va guardata su
                // Firefly, non ritentata alla cieca.
                FireflySyncStatus.UNCERTAIN -> " · Firefly da verificare"
            }
            val note = if (record.note.isBlank()) "" else "\n${record.note}"
            views.meta.text = "$scope · ${formatDate(record.date)}$jobsite$firefly$note"

            views.card.setOnClickListener { showEditor(record) }
            views.card.setOnLongClickListener {
                confirmDelete(record)
                true
            }
        }
    }

    // -----------------------------------------------------------------------
    // Editor movimento
    // -----------------------------------------------------------------------

    private fun showEditor(source: FinanceRecord, initialType: FinanceType? = null) {
        val record = source.copy()
        if (record.id == 0L && initialType != null) record.type = initialType

        var selectedType = record.type
        var selectedScope = record.scope

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(GeSpace.XL), dp(GeSpace.SM), dp(GeSpace.XL), dp(GeSpace.SM))
        }

        val scopeHint = TextView(this).apply {
            applyGe360Subtitle(GeType.CAPTION)
            setPadding(dp(GeSpace.XS), dp(GeSpace.XS), dp(GeSpace.XS), dp(GeSpace.XS))
        }

        val amount = geInput(
            hint = "0,00",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            initial = if (record.amount > 0) record.amount.toString().replace('.', ',') else ""
        ).apply { textSize = GeType.TITLE }

        val category = Spinner(this)

        val jobsites: List<JobsiteRecord> = runCatching { jobsiteStore.list() }.getOrDefault(emptyList())
        val jobsiteLabels = mutableListOf("Nessun cantiere").apply {
            addAll(jobsites.map { "${it.code} · ${it.displayTitle()}" })
        }
        val jobsiteSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ExpensesActivity,
                android.R.layout.simple_spinner_dropdown_item,
                jobsiteLabels
            )
            val index = jobsites.indexOfFirst { it.ge360Id == record.jobsiteId }
            setSelection(if (index >= 0) index + 1 else 0)
        }
        val jobsiteBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(geFieldLabel("Cantiere (facoltativo)"))
            addView(
                jobsiteSpinner,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            )
        }

        val date = geInput(initial = record.date.ifBlank { LocalDate.now().toString() })

        val note = geInput(
            hint = "Es. acquisto piastrelle, spesa supermercato, acconto cliente…",
            singleLine = false,
            initial = record.note
        ).apply { minLines = 3 }

        fun categoriesFor(type: FinanceType, scope: FinanceScope): List<String> = when {
            type == FinanceType.EXPENSE && scope == FinanceScope.BUSINESS -> FinanceStore.BUSINESS_EXPENSE_CATEGORIES
            type == FinanceType.EXPENSE && scope == FinanceScope.PRIVATE -> FinanceStore.PRIVATE_EXPENSE_CATEGORIES
            type == FinanceType.INCOME && scope == FinanceScope.BUSINESS -> FinanceStore.BUSINESS_INCOME_CATEGORIES
            else -> FinanceStore.PRIVATE_INCOME_CATEGORIES
        }

        fun refreshCategories(preferred: String? = null) {
            val values = categoriesFor(selectedType, selectedScope)
            val previous = preferred ?: category.selectedItem?.toString().orEmpty()
            category.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                values
            )
            val wanted = when {
                previous in values -> previous
                record.category in values -> record.category
                "Altro" in values -> "Altro"
                else -> values.first()
            }
            val index = values.indexOf(wanted)
            if (index >= 0) category.setSelection(index)
        }

        fun refreshScopeUi() {
            scopeHint.text = if (selectedScope == FinanceScope.BUSINESS) {
                "Movimento dell'impresa. Puoi anche collegarlo a un cantiere."
            } else {
                "Movimento personale. Non verrà collegato ai cantieri."
            }
            jobsiteBlock.visibility =
                if (selectedScope == FinanceScope.BUSINESS) View.VISIBLE else View.GONE
        }

        // I selettori si creano ora: le loro callback usano le funzioni qui
        // sopra, e la riga di filtri aggiorna da sola il proprio stato visivo.
        val typeSelector = geFilterRow(
            listOf("Uscita", "Entrata"),
            if (selectedType == FinanceType.INCOME) "Entrata" else "Uscita"
        ) { choice ->
            val previous = category.selectedItem?.toString()
            selectedType = if (choice == "Entrata") FinanceType.INCOME else FinanceType.EXPENSE
            refreshCategories(previous)
        }
        val scopeSelector = geFilterRow(
            listOf("Azienda", "Privato"),
            if (selectedScope == FinanceScope.BUSINESS) "Azienda" else "Privato"
        ) { choice ->
            val previous = category.selectedItem?.toString()
            selectedScope = if (choice == "Azienda") FinanceScope.BUSINESS else FinanceScope.PRIVATE
            refreshScopeUi()
            refreshCategories(previous)
        }

        // Montaggio del form, nell'ordine in cui lo si legge.
        content.addView(geFieldLabel("Tipo movimento"))
        content.addView(typeSelector)
        content.addView(geFieldLabel("È un movimento aziendale o privato?"))
        content.addView(scopeSelector)
        content.addView(scopeHint)
        content.addView(geFieldLabel("Importo €"))
        content.addView(
            amount,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        )
        content.addView(geFieldLabel("Categoria"))
        content.addView(
            category,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        )
        content.addView(jobsiteBlock)
        content.addView(geFieldLabel("Data (AAAA-MM-GG)"))
        content.addView(
            date,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        )
        content.addView(geFieldLabel("Nota"))
        content.addView(
            note,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90))
        )

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

                geLoad({ store.save(record) }) { outcome ->
                    outcome.onSuccess {
                        dialog.dismiss()
                        reload()
                        FireflyFinanceSync.syncAsync(this, record) { result ->
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    reload()
                                    if (!result.ok) {
                                        toast(
                                            if (result.uncertain) {
                                                "Salvato sul telefono · controlla su Firefly: l'esito non è confermato"
                                            } else {
                                                "Salvato sul telefono · Firefly da riprovare"
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }.onFailure {
                        toast("Salvataggio non riuscito: ${it.message}")
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
            .setPositiveButton("Elimina") { _, _ ->
                geLoad({ store.delete(record.id) }) { outcome ->
                    outcome.onSuccess { reload() }
                        .onFailure { toast("Eliminazione non riuscita: ${it.message}") }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // -----------------------------------------------------------------------
    // Riepiloghi e dati
    // -----------------------------------------------------------------------

    private fun showJobsiteSummary() {
        geLoad({
            jobsiteStore.list()
                .mapNotNull { jobsite ->
                    val summary = store.jobsiteSummary(jobsite.ge360Id)
                    if (summary.movements == 0) null else jobsite to summary
                }
        }) { outcome ->
            outcome.onSuccess { rows -> renderJobsiteSummary(rows) }
                .onFailure { toast("Riepilogo non disponibile: ${it.message}") }
        }
    }

    private fun renderJobsiteSummary(rows: List<Pair<JobsiteRecord, JobsiteFinanceSummary>>) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(GeSpace.XL), dp(GeSpace.SM), dp(GeSpace.XL), dp(GeSpace.SM))
        }
        if (rows.isEmpty()) {
            body.addView(TextView(this).apply {
                text = "Nessun movimento è ancora collegato a un cantiere."
                applyGe360Subtitle(GeType.BODY)
            })
        } else {
            rows.forEach { (jobsite, summary) ->
                body.addView(
                    MaterialCardView(this).apply {
                        applyGe360Card()
                        addView(LinearLayout(this@ExpensesActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(
                                dp(GeSpace.LG),
                                dp(GeSpace.MD),
                                dp(GeSpace.LG),
                                dp(GeSpace.MD)
                            )
                            addView(TextView(this@ExpensesActivity).apply {
                                text = "${jobsite.code} · ${jobsite.displayTitle()}"
                                applyGe360Title(GeType.H3)
                            })
                            addView(TextView(this@ExpensesActivity).apply {
                                text = "Entrate ${euro(summary.income)} · Uscite ${euro(summary.expense)}\n" +
                                    "Saldo ${euro(summary.balance)} · ${summary.movements} registrazioni"
                                applyGe360Subtitle(GeType.BODY_SM)
                                setPadding(0, dp(GeSpace.XS), 0, 0)
                            })
                        })
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(GeSpace.SM) }
                )
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
            "Riprova sincronizzazione Firefly",
            "Forza anche i movimenti da verificare"
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
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            type = "application/json"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }, REQ_IMPORT_JSON)
                    }

                    2 -> {
                        pendingImportReplace = true
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            type = "application/json"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }, REQ_IMPORT_JSON)
                    }

                    3 -> startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_TITLE, "ge360-spese.csv")
                    }, REQ_EXPORT_CSV)

                    4 -> retryFirefly(includeUncertain = false)
                    5 -> confirmForceUncertain()
                }
            }
            .show()
    }

    private fun retryFirefly(includeUncertain: Boolean) {
        geLoad({ store.all() }) { outcome ->
            outcome.onSuccess { records ->
                FireflyFinanceSync.syncPendingAsync(this, records, includeUncertain) { result ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            toast(result.message)
                            reload()
                        }
                    }
                }
            }.onFailure { toast("Lettura movimenti non riuscita: ${it.message}") }
        }
    }

    /**
     * Il reinvio forzato puo' creare un doppione se il server non rispetta la
     * chiave di idempotenza: va chiesto, non fatto di nascosto.
     */
    private fun confirmForceUncertain() {
        AlertDialog.Builder(this)
            .setTitle("Forzare i movimenti da verificare?")
            .setMessage(
                "Questi movimenti potrebbero essere già stati registrati su Firefly: " +
                    "la risposta del server si era persa.\n\n" +
                    "Controlla su Firefly prima di procedere, altrimenti rischi un movimento doppio."
            )
            .setPositiveButton("Ho verificato, procedi") { _, _ ->
                retryFirefly(includeUncertain = true)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQ_EXPORT_JSON -> exportTo(uri, "Backup JSON esportato") { store.exportJson() }
            REQ_EXPORT_CSV -> exportTo(uri, "CSV esportato") { store.exportCsv() }
            REQ_IMPORT_JSON -> {
                val replace = pendingImportReplace
                geLoad({
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("File non leggibile")
                    store.importJson(text, replace)
                }) { outcome ->
                    outcome.onSuccess { count ->
                        toast("Importati $count movimenti")
                        reload()
                    }.onFailure { toast("Importazione non riuscita: ${it.message}") }
                }
            }
        }
    }

    /** Export: serializzazione e scrittura del file fuori dal thread della UI. */
    private fun exportTo(uri: android.net.Uri, message: String, produce: () -> String) {
        geLoad({
            val text = produce()
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
                ?: error("File non scrivibile")
        }) { outcome ->
            outcome.onSuccess { toast(message) }
                .onFailure { toast("Esportazione non riuscita: ${it.message}") }
        }
    }

    // -----------------------------------------------------------------------

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun euro(value: Double): String =
        NumberFormat.getCurrencyInstance(Locale.ITALY).format(value)

    private fun formatDate(value: String): String = runCatching {
        LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    }.getOrDefault(value)

    companion object {
        /** Apre la schermata con il form del nuovo movimento gia' aperto. */
        const val EXTRA_NEW_EXPENSE = "ge360_new_expense"

        /** Da usare con [EXTRA_NEW_EXPENSE] per partire da un'entrata. */
        const val EXTRA_AS_INCOME = "ge360_new_expense_income"

        private const val REQ_EXPORT_JSON = 501
        private const val REQ_IMPORT_JSON = 502
        private const val REQ_EXPORT_CSV = 503
    }
}
