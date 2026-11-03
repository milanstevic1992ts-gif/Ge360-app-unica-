package it.edilmilan.ge360.attrezzi

import android.content.Intent
import androidx.appcompat.app.AlertDialog
import it.edilmilan.ge360.core.common.Ge360Contracts
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.core.common.ui.applyGe360Card
import it.edilmilan.ge360.core.common.ui.applyGe360Primary
import it.edilmilan.ge360.core.common.ui.applyGe360Secondary
import it.edilmilan.ge360.core.common.ui.brandRounded
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.core.common.ui.ge360Badge
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Materiali 2.0: magazzino, acquisti e movimenti.
 *
 * Nessun componente viene inizializzato dal bootstrap principale: gli store vengono aperti
 * esclusivamente quando l'utente entra in questa Activity.
 */
class MaterialsActivity : AppCompatActivity() {
    private lateinit var inventory: MaterialInventoryStore
    private lateinit var plans: MaterialPlanStore
    private lateinit var tools: ToolsStore
    private lateinit var jobsites: JobsiteStore
    private lateinit var content: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var lowOnly: CheckBox
    private lateinit var summaryLabel: TextView
    private var mode = MODE_STOCK
    private var jobRows: List<JobsiteRecord> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inventory = MaterialInventoryStore(this)
        plans = MaterialPlanStore(this)
        tools = ToolsStore(this)
        jobsites = JobsiteStore(this)
        jobRows = jobsites.list()

        // Primo ingresso: porta nel nuovo magazzino le voci MATERIAL del catalogo esistente.
        inventory.ensureCatalogMirror(tools.catalog(kind = ResourceKind.MATERIAL))
        setContentView(buildScreen())
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) {
            jobRows = jobsites.list()
            render()
        }
    }

    override fun onDestroy() {
        inventory.close()
        plans.close()
        tools.close()
        jobsites.close()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setBackgroundColor(Ge360Brand.Background)
        }

        root.addView(
            buildScreenHeaderWithSubtitle(
                context = this,
                title = "Materiali 2.0",
                subtitle = "Magazzino · acquisti · consumi cantiere",
                onBack = { finish() }
            )
        )

        summaryLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(dp(4), dp(5), dp(4), dp(8))
        }
        root.addView(summaryLabel)
        root.addView(MaterialButton(this).apply {
            text = "Fatture e DDT → materiali · V13"
            applyGe360Secondary()
            setOnClickListener {
                startActivity(Intent(this@MaterialsActivity,
                    MaterialEvidenceActivityV13::class.java).apply {
                    // Preserve an explicit jobsite context; none is invented.
                    intent.getStringExtra(Ge360Contracts.EXTRA_JOBSITE_ID)?.let {
                        putExtra(Ge360Contracts.EXTRA_JOBSITE_ID, it)
                    }
                })
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(
            MODE_STOCK to "Magazzino",
            MODE_BUY to "Da acquistare",
            MODE_MOVEMENTS to "Movimenti"
        ).forEach { (value, label) ->
            modeRow.addView(MaterialButton(this).apply {
                text = label
                textSize = 11.5f
                applyGe360Secondary()
                setOnClickListener {
                    mode = value
                    render()
                }
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginEnd = dp(5)
            })
        }
        root.addView(modeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        searchField = EditText(this).apply {
            hint = "Cerca materiale, marca, fornitore, posizione…"
            isSingleLine = true
            setPadding(dp(12), 0, dp(12), 0)
            background = brandRounded(fill = Ge360Brand.Surface, stroke = Ge360Brand.BorderStrong, radiusDp = 14)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(searchField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(7)
        })

        lowOnly = CheckBox(this).apply {
            text = "Mostra solo sotto scorta"
            setTextColor(Ge360Brand.TextSecondary)
            setOnCheckedChangeListener { _, _ -> render() }
        }
        root.addView(lowOnly, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(20))
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(MaterialButton(this).apply {
            text = "+ Nuovo materiale"
            applyGe360Primary()
            setOnClickListener { editMaterial(MaterialStockItem()) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))

        return root
    }

    private fun render() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        val summary = inventory.summary()
        val openRequests = openMaterialRequests()
        summaryLabel.text = "${summary.activeMaterials} materiali · ${summary.lowStock} sotto scorta · ${openRequests.size} richieste aperte · valore ${money(summary.inventoryValue)}"

        if (::searchField.isInitialized) {
            searchField.visibility = if (mode == MODE_MOVEMENTS) View.GONE else View.VISIBLE
        }
        if (::lowOnly.isInitialized) {
            lowOnly.visibility = if (mode == MODE_STOCK) View.VISIBLE else View.GONE
        }

        when (mode) {
            MODE_BUY -> renderPurchases(openRequests)
            MODE_MOVEMENTS -> renderMovements()
            else -> renderStock()
        }
    }

    private fun renderStock() {
        val query = searchField.text.toString().trim()
        val rows = inventory.list(query, lowOnly.isChecked)
        addSectionHeader("MAGAZZINO", "Giacenza, minimo, obiettivo, costo e posizione")
        if (rows.isEmpty()) {
            addEmpty("Nessun materiale", if (lowOnly.isChecked) "Nessun materiale è sotto la scorta minima." else "Aggiungi un materiale oppure usa il catalogo esistente.")
            return
        }
        rows.forEach { item -> content.addView(materialCard(item)) }
    }

    private fun renderPurchases(requests: List<ResourceRequest>) {
        val q = searchField.text.toString().trim().lowercase(Locale.ITALY)
        val filteredRequests = requests.filter {
            q.isBlank() || listOf(it.name, it.jobsiteCode, it.jobsiteTitle, it.note, it.requestedBy)
                .any { value -> value.lowercase(Locale.ITALY).contains(q) }
        }
        val low = inventory.list(query = q).filter { it.isLow && it.targetShortage > 0.0 }

        addSectionHeader("RICHIESTE CANTIERI", "Richieste aperte provenienti da cantieri e Calcolo materiali")
        if (filteredRequests.isEmpty()) addEmpty("Nessuna richiesta aperta", "Le nuove richieste materiali compariranno qui automaticamente.")
        filteredRequests.forEach { request -> content.addView(requestCard(request)) }

        addSectionHeader("SCORTA DA RIPRISTINARE", "Materiali che hanno raggiunto o superato il minimo impostato")
        if (low.isEmpty()) addEmpty("Scorte regolari", "Non risultano materiali da reintegrare in base ai minimi impostati.")
        low.forEach { item -> content.addView(lowStockCard(item)) }
    }

    private fun renderMovements() {
        addSectionHeader("MOVIMENTI", "Ultimi carichi, consumi, resi e rettifiche")
        val rows = inventory.movements(limit = 250)
        if (rows.isEmpty()) {
            addEmpty("Nessun movimento", "Il primo carico o consumo verrà registrato qui.")
            return
        }
        rows.forEach { movement -> content.addView(movementCard(movement)) }
    }

    private fun materialCard(item: MaterialStockItem): View = MaterialCardView(this).apply {
        applyGe360Card(
            fill = if (item.isLow) Ge360Brand.WarningSoft else Ge360Brand.Surface,
            stroke = if (item.isLow) Ge360Brand.Primary else Ge360Brand.Border
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { openMaterial(item) }
        layoutParams = cardParams()
        addView(LinearLayout(this@MaterialsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))

            addView(LinearLayout(this@MaterialsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MaterialsActivity).apply {
                    text = item.name
                    textSize = 16.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Ge360Brand.ForestDark)
                    maxLines = 2
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (item.isLow) addView(this@MaterialsActivity.ge360Badge("SCORTA BASSA"))
            })

            addView(TextView(this@MaterialsActivity).apply {
                text = "${clean(item.stockQty)} ${item.unit} disponibili · minimo ${clean(item.minQty)} · obiettivo ${clean(item.targetQty)}"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (item.isLow) Ge360Brand.PrimaryDark else Ge360Brand.Success)
                setPadding(0, dp(5), 0, 0)
            })

            val details = listOfNotNull(
                item.category.takeIf { it.isNotBlank() },
                item.brand.takeIf { it.isNotBlank() },
                item.supplier.takeIf { it.isNotBlank() },
                item.location.takeIf { it.isNotBlank() }?.let { "📍 $it" },
                item.avgUnitCost.takeIf { it > 0.0 }?.let { "${money(it)}/${item.unit}" }
            ).joinToString(" · ")
            if (details.isNotBlank()) addView(TextView(this@MaterialsActivity).apply {
                text = details
                textSize = 11f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(0, dp(4), 0, 0)
            })
            addView(MaterialButton(this@MaterialsActivity).apply {
                text = "PREVISTO / CONSUMATO"
                textSize = 12f
                applyGe360Secondary()
                setOnClickListener { openMaterialVariance(item) }
            })
        })
    }

    private fun openMaterialVariance(item: MaterialStockItem) {
        val jobOptions = listOf("Nessun cantiere · tutti i movimenti") +
            jobRows.map { it.displayTitle() }
        val jobIds = listOf<String?>(null) + jobRows.map { it.ge360Id }
        val jobSelect = spinner(jobOptions, jobOptions.first())
        val plannedQty = numberField(0.0)
        val result = TextView(this).apply {
            textSize = 13f
            setTextColor(Ge360Brand.Text)
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        val form = formBox()
        addLabeled(form, "AMBITO (FACOLTATIVO)", jobSelect, 56)
        addLabeled(form, "QUANTITÀ PREVISTA · ${item.unit}", plannedQty, 56)
        form.addView(result)
        form.addView(MaterialButton(this).apply {
            text = "AGGIORNA CONFRONTO"
            applyGe360Secondary()
            setOnClickListener { showMaterialVariance(item, jobIds[jobSelect.selectedItemPosition], result) }
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle("Previsto / consumato · ${item.name}")
            .setMessage("Le quantità previste sono confermate manualmente. " +
                "Un carico non prova un acquisto, un reso è rientro a stock.")
            .setView(form)
            .setNegativeButton("Chiudi", null)
            .setPositiveButton("SALVA PREVISIONE", null)
            .create()
        jobSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                val selected = jobIds[position]
                val previous = plans.latest(item.ge360Id, selected)
                plannedQty.setText(previous?.let { clean(it.quantity) } ?: "")
                showMaterialVariance(item, selected, result)
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    val qty = plannedQty.text.toString().trim().replace(',', '.').toDoubleOrNull()
                        ?: error("Inserisci una quantità valida")
                    require(qty.isFinite() && qty > 0) { "La previsione deve essere maggiore di zero" }
                    val chosenJobsiteId = jobIds[jobSelect.selectedItemPosition]
                    plans.saveConfirmed(item, MaterialPlanStore.newManual(item, chosenJobsiteId, qty))
                }.onSuccess {
                    dialog.dismiss()
                    render()
                    toast("Quantità prevista salvata come nuova revisione")
                }.onFailure { toast(it.message ?: "Previsione non salvata") }
            }
        }
        dialog.show()
    }

    private fun showMaterialVariance(
        item: MaterialStockItem, jobsiteId: String?, target: TextView
    ) {
        val planned = plans.latest(item.ge360Id, jobsiteId)?.toQuantity()
        val movements = inventory.allMovementHistory(item.ge360Id, jobsiteId)
        val summary = MaterialVarianceRulesV1.compare(
            materialGe360Id = item.ge360Id, unit = item.unit, plan = planned,
            movements = movements, jobsiteGe360Id = jobsiteId,
            completeHistory = inventory.hasCompleteMovementHistory()
        )
        val delta = when (summary.status) {
            MaterialComparisonStatusV1.COMPARABLE ->
                "Scostamento consumato−previsto: ${clean(summary.consumptionDelta ?: 0.0)} ${item.unit}"
            MaterialComparisonStatusV1.NO_PLAN -> "Previsione non ancora registrata"
            MaterialComparisonStatusV1.PARTIAL_HISTORY ->
                "Storico non garantito completo: scostamento non calcolabile"
            MaterialComparisonStatusV1.UNIT_MISMATCH ->
                "Unità incompatibili: scostamento non calcolabile"
        }
        target.text = "Previsto: ${planned?.let { clean(it.quantity) } ?: "non registrato"} ${item.unit}" +
            "\nCaricato: ${clean(summary.observedLoads)} ${item.unit}" +
            "\nConsumato: ${clean(summary.observedConsumption)} ${item.unit}" +
            "\nRientrato a stock: ${clean(summary.observedReturnsToStock)} ${item.unit}" +
            "\n${delta}" +
            "\nDati del registro locale; nessuna fattura o acquisto automaticamente dedotti."
    }

    private fun requestCard(request: ResourceRequest): View = MaterialCardView(this).apply {
        applyGe360Card(fill = if (request.urgent) Ge360Brand.DangerSoft else Ge360Brand.Surface)
        isClickable = true
        setOnClickListener { openRequest(request) }
        layoutParams = cardParams()
        addView(LinearLayout(this@MaterialsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(LinearLayout(this@MaterialsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MaterialsActivity).apply {
                    text = request.name
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Ge360Brand.ForestDark)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(this@MaterialsActivity.ge360Badge(if (request.urgent) "URGENTE" else request.status))
            })
            addView(TextView(this@MaterialsActivity).apply {
                text = "${clean(request.quantity)} ${request.unit}${if (request.jobsiteCode.isNotBlank()) " · ${request.jobsiteCode}" else ""}${if (request.requestedBy.isNotBlank()) " · ${request.requestedBy}" else ""}"
                textSize = 12f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(0, dp(4), 0, 0)
            })
        })
    }

    private fun lowStockCard(item: MaterialStockItem): View = MaterialCardView(this).apply {
        applyGe360Card(fill = Ge360Brand.WarningSoft, stroke = Ge360Brand.Primary)
        isClickable = true
        setOnClickListener { openMaterial(item) }
        layoutParams = cardParams()
        addView(LinearLayout(this@MaterialsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(LinearLayout(this@MaterialsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MaterialsActivity).apply {
                    text = item.name
                    textSize = 15.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Ge360Brand.ForestDark)
                })
                addView(TextView(this@MaterialsActivity).apply {
                    text = "In stock ${clean(item.stockQty)} ${item.unit} · suggerito acquistare ${clean(item.targetShortage)} ${item.unit}"
                    textSize = 11.5f
                    setTextColor(Ge360Brand.PrimaryDark)
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MaterialsActivity).apply {
                text = "›"
                textSize = 24f
                setTextColor(Ge360Brand.PrimaryDark)
            })
        })
    }

    private fun movementCard(movement: MaterialStockMovement): View = MaterialCardView(this).apply {
        applyGe360Card()
        layoutParams = cardParams()
        addView(LinearLayout(this@MaterialsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            addView(LinearLayout(this@MaterialsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MaterialsActivity).apply {
                    text = movement.materialName
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Ge360Brand.ForestDark)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(this@MaterialsActivity.ge360Badge(movement.type))
            })
            val sign = if (movement.quantity > 0) "+" else ""
            addView(TextView(this@MaterialsActivity).apply {
                text = "$sign${clean(movement.quantity)} ${movement.unit} · ${formatDate(movement.createdAt)}${if (movement.jobsiteCode.isNotBlank()) " · ${movement.jobsiteCode}" else ""}"
                textSize = 12f
                setTextColor(if (movement.quantity >= 0) Ge360Brand.Success else Ge360Brand.Danger)
                setPadding(0, dp(4), 0, 0)
            })
            if (movement.note.isNotBlank()) addView(TextView(this@MaterialsActivity).apply {
                text = movement.note
                textSize = 11f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(0, dp(3), 0, 0)
            })
        })
    }

    private fun openMaterial(item: MaterialStockItem) {
        val actions = arrayOf("Carico", "Consumo", "Reso", "Rettifica +", "Rettifica -", "Modifica scheda", "Storico materiale", "Disattiva")
        AlertDialog.Builder(this)
            .setTitle("${item.name} · ${clean(item.stockQty)} ${item.unit}")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> movementDialog(item, MaterialStockMovement.TYPE_LOAD)
                    1 -> movementDialog(item, MaterialStockMovement.TYPE_CONSUME)
                    2 -> movementDialog(item, MaterialStockMovement.TYPE_RETURN)
                    3 -> movementDialog(item, MaterialStockMovement.TYPE_ADJUST_PLUS)
                    4 -> movementDialog(item, MaterialStockMovement.TYPE_ADJUST_MINUS)
                    5 -> editMaterial(item.copy())
                    6 -> showMaterialHistory(item)
                    7 -> confirmDeactivate(item)
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun editMaterial(item: MaterialStockItem) {
        val box = formBox()
        val name = textField(item.name, "Nome materiale")
        val category = textField(item.category, "Categoria")
        val unit = spinner(ToolsStore.UNITS, item.unit)
        val brand = textField(item.brand, "Marca")
        val supplier = textField(item.supplier, "Fornitore")
        val packageSize = numberField(item.packageSize)
        val packageUnit = textField(item.packageUnit, "es. sacco 25 kg")
        val minQty = numberField(item.minQty)
        val targetQty = numberField(item.targetQty)
        val avgCost = numberField(item.avgUnitCost)
        val location = textField(item.location, "es. Garage scaffale B2")
        val barcode = textField(item.barcode, "EAN / barcode")
        val note = multiline(item.note, "Note")

        addLabeled(box, "Nome", name)
        addLabeled(box, "Categoria", category)
        addLabeled(box, "Unità di misura", unit)
        addLabeled(box, "Marca", brand)
        addLabeled(box, "Fornitore", supplier)
        addLabeled(box, "Quantità per confezione", packageSize)
        addLabeled(box, "Descrizione confezione", packageUnit)
        addLabeled(box, "Scorta minima", minQty)
        addLabeled(box, "Scorta obiettivo", targetQty)
        addLabeled(box, "Costo medio per unità €", avgCost)
        addLabeled(box, "Ubicazione", location)
        addLabeled(box, "Barcode", barcode)
        addLabeled(box, "Note", note, 86)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (item.id > 0) "Modifica materiale" else "Nuovo materiale")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (name.text.toString().isBlank()) {
                    name.error = "Nome obbligatorio"
                    return@setOnClickListener
                }
                item.name = name.text.toString().trim()
                item.category = category.text.toString().trim().ifBlank { "Altro" }
                item.unit = unit.selectedItem.toString()
                item.brand = brand.text.toString().trim()
                item.supplier = supplier.text.toString().trim()
                item.packageSize = parse(packageSize.text.toString()).coerceAtLeast(0.0)
                item.packageUnit = packageUnit.text.toString().trim()
                item.minQty = parse(minQty.text.toString()).coerceAtLeast(0.0)
                item.targetQty = parse(targetQty.text.toString()).coerceAtLeast(item.minQty)
                item.avgUnitCost = parse(avgCost.text.toString()).coerceAtLeast(0.0)
                item.location = location.text.toString().trim()
                item.barcode = barcode.text.toString().trim()
                item.note = note.text.toString().trim()

                if (item.catalogGe360Id.isBlank()) {
                    val catalog = CatalogResource(
                        kind = ResourceKind.MATERIAL,
                        name = item.name,
                        category = item.category,
                        unit = item.unit,
                        note = item.note
                    )
                    tools.saveCatalog(catalog)
                    item.catalogGe360Id = catalog.ge360Id
                }
                inventory.save(item)
                dialog.dismiss()
                render()
            }
        }
        dialog.show()
    }

    private fun movementDialog(item: MaterialStockItem, defaultType: String) {
        val box = formBox()
        val type = spinner(MaterialStockMovement.TYPES, defaultType)
        val quantity = numberField(1.0)
        val unitCost = numberField(
            if (defaultType == MaterialStockMovement.TYPE_LOAD || defaultType == MaterialStockMovement.TYPE_CONSUME) item.avgUnitCost else 0.0
        )
        val jobLabels = mutableListOf("Nessun cantiere").apply {
            addAll(jobRows.map { "${it.code} · ${it.displayTitle()}" })
        }
        val job = Spinner(this).apply {
            adapter = ArrayAdapter(this@MaterialsActivity, android.R.layout.simple_spinner_dropdown_item, jobLabels)
        }
        val note = multiline("", "Nota movimento")

        addLabeled(box, "Tipo", type)
        addLabeled(box, "Quantità (${item.unit})", quantity)
        addLabeled(box, "Costo per ${item.unit} € (facoltativo)", unitCost)
        addLabeled(box, "Cantiere (facoltativo)", job)
        addLabeled(box, "Nota", note, 82)

        val dialog = AlertDialog.Builder(this)
            .setTitle(item.name)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Registra", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val qty = parse(quantity.text.toString())
                if (qty <= 0.0) {
                    quantity.error = "Quantità obbligatoria"
                    return@setOnClickListener
                }
                val selectedJob = jobRows.getOrNull(job.selectedItemPosition - 1)
                runCatching {
                    inventory.recordMovement(
                        item = item,
                        type = type.selectedItem.toString(),
                        quantity = qty,
                        unitCost = parse(unitCost.text.toString()),
                        jobsiteId = selectedJob?.ge360Id.orEmpty(),
                        jobsiteCode = selectedJob?.code.orEmpty(),
                        jobsiteTitle = selectedJob?.displayTitle().orEmpty(),
                        note = note.text.toString().trim()
                    )
                }.onSuccess {
                    dialog.dismiss()
                    render()
                    toast("Movimento registrato")
                }.onFailure { error ->
                    toast(error.message ?: "Movimento non riuscito")
                }
            }
        }
        dialog.show()
    }

    private fun openRequest(request: ResourceRequest) {
        val stock = inventory.findByName(request.name, request.unit)
        val canUseStock = stock != null && stock.stockQty + 0.000001 >= request.quantity
        val actions = mutableListOf<String>()
        if (canUseStock) actions += "Usa ${clean(request.quantity)} ${request.unit} dal magazzino"
        actions += "Cambia stato"
        actions += "Segna comprato"
        actions += "Segna consegnato"

        AlertDialog.Builder(this)
            .setTitle(request.name)
            .setMessage("${clean(request.quantity)} ${request.unit}${if (request.jobsiteTitle.isNotBlank()) " · ${request.jobsiteTitle}" else ""}\nStato: ${request.status}")
            .setItems(actions.toTypedArray()) { _, which ->
                val label = actions[which]
                when {
                    label.startsWith("Usa ") && stock != null -> fulfillFromStock(request, stock)
                    label == "Segna comprato" -> updateRequestStatus(request, "Comprato")
                    label == "Segna consegnato" -> updateRequestStatus(request, "Consegnato")
                    else -> chooseRequestStatus(request)
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun fulfillFromStock(request: ResourceRequest, stock: MaterialStockItem) {
        runCatching {
            inventory.recordMovement(
                item = stock,
                type = MaterialStockMovement.TYPE_CONSUME,
                quantity = request.quantity,
                unitCost = stock.avgUnitCost,
                jobsiteId = request.jobsiteId,
                jobsiteCode = request.jobsiteCode,
                jobsiteTitle = request.jobsiteTitle,
                note = "Richiesta ${request.requestedBy.ifBlank { "cantiere" }} · ${request.note}".trim()
            )
            request.status = "Consegnato"
            tools.saveRequest(request)
        }.onSuccess {
            toast("Prelevato dal magazzino e richiesta chiusa")
            render()
        }.onFailure { toast(it.message ?: "Operazione non riuscita") }
    }

    private fun chooseRequestStatus(request: ResourceRequest) {
        AlertDialog.Builder(this)
            .setTitle(request.name)
            .setItems(ToolsStore.REQUEST_STATUSES.toTypedArray()) { _, which ->
                updateRequestStatus(request, ToolsStore.REQUEST_STATUSES[which])
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun updateRequestStatus(request: ResourceRequest, status: String) {
        request.status = status
        tools.saveRequest(request)
        render()
    }

    private fun showMaterialHistory(item: MaterialStockItem) {
        val rows = inventory.movements(item.ge360Id, 100)
        val text = if (rows.isEmpty()) "Nessun movimento registrato." else rows.joinToString("\n\n") { row ->
            val sign = if (row.quantity > 0) "+" else ""
            "$sign${clean(row.quantity)} ${row.unit} · ${row.type} · ${formatDate(row.createdAt)}${if (row.jobsiteCode.isNotBlank()) " · ${row.jobsiteCode}" else ""}${if (row.note.isNotBlank()) "\n${row.note}" else ""}"
        }
        AlertDialog.Builder(this)
            .setTitle("Storico · ${item.name}")
            .setMessage(text)
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun confirmDeactivate(item: MaterialStockItem) {
        AlertDialog.Builder(this)
            .setTitle("Disattivare ${item.name}?")
            .setMessage("La scheda sparirà dal magazzino attivo. Lo storico dei movimenti resta salvato.")
            .setPositiveButton("Disattiva") { _, _ ->
                inventory.deactivate(item.id)
                render()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun openMaterialRequests(): List<ResourceRequest> = tools.requests()
        .filter { it.kind == ResourceKind.MATERIAL }
        .filter { it.status !in setOf("Consegnato", "Rifiutato", "Annullato") }

    private fun addSectionHeader(title: String, subtitle: String) {
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(15), dp(3), dp(8))
            addView(TextView(this@MaterialsActivity).apply {
                text = title
                textSize = 12f
                letterSpacing = 0.07f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ge360Brand.PrimaryDark)
            })
            addView(TextView(this@MaterialsActivity).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(0, dp(2), 0, 0)
            })
        })
    }

    private fun addEmpty(title: String, subtitle: String) {
        content.addView(MaterialCardView(this).apply {
            applyGe360Card(fill = Ge360Brand.SurfaceWarm)
            layoutParams = cardParams()
            addView(LinearLayout(this@MaterialsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(13), dp(14), dp(13))
                addView(TextView(this@MaterialsActivity).apply {
                    text = title
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Ge360Brand.ForestDark)
                })
                addView(TextView(this@MaterialsActivity).apply {
                    text = subtitle
                    textSize = 11f
                    setTextColor(Ge360Brand.TextSecondary)
                    setPadding(0, dp(3), 0, 0)
                })
            })
        })
    }

    private fun formBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(10))
    }

    private fun addLabeled(box: LinearLayout, label: String, view: View, height: Int = 54) {
        box.addView(TextView(this).apply {
            text = label
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(dp(2), dp(7), dp(2), dp(3))
        })
        box.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)))
    }

    private fun textField(value: String, hintValue: String) = EditText(this).apply {
        setText(value)
        hint = hintValue
        isSingleLine = true
    }

    private fun numberField(value: Double) = EditText(this).apply {
        setText(clean(value))
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }

    private fun multiline(value: String, hintValue: String) = EditText(this).apply {
        setText(value)
        hint = hintValue
        gravity = Gravity.TOP
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }

    private fun spinner(values: List<String>, selected: String) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MaterialsActivity, android.R.layout.simple_spinner_dropdown_item, values)
        val index = values.indexOf(selected)
        if (index >= 0) setSelection(index)
    }

    private fun cardParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(8)
    }

    private fun parse(value: String): Double = value.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
    private fun clean(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(Locale.ITALY, value)
    private fun money(value: Double): String = NumberFormat.getCurrencyInstance(Locale.ITALY).format(value)
    private fun formatDate(millis: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(millis))
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MODE_STOCK = 0
        private const val MODE_BUY = 1
        private const val MODE_MOVEMENTS = 2
    }
}
