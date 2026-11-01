package it.edilmilan.ge360.attrezzi

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.core.common.Ge360Contracts
import java.util.Locale

class WorkPreparationActivity : AppCompatActivity() {

    private lateinit var store: ToolsStore
    private lateinit var customPackStore: CustomWorkPackStore
    private lateinit var jobsites: JobsiteStore
    private lateinit var jobSpinner: Spinner
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var adapter: RowAdapter
    private var jobRows: List<JobsiteRecord> = emptyList()
    private var mode = MODE_PACKS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ToolsStore(this)
        customPackStore = CustomWorkPackStore(this)
        StandardWorkCatalog.ensureCatalog(store)
        jobsites = JobsiteStore(this)
        jobRows = jobsites.list()
        setContentView(buildScreen())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            val keepId = selectedJob()?.ge360Id
            jobRows = jobsites.list()
            installJobSpinner(keepId)
            refresh()
        }
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }

        root.addView(buildScreenHeaderWithSubtitle(
            context = this,
            title = "Prepara lavoro",
            subtitle = "Pacchetti rapidi · attrezzi · materiali",
            onBack = { finish() }
        ))

        jobSpinner = Spinner(this)
        installJobSpinner(intent.getStringExtra(Ge360Contracts.EXTRA_JOBSITE_ID))
        root.addView(jobSpinner, lp52())

        status = TextView(this).apply {
            textSize = 13f
            setPadding(dp(4), dp(5), dp(4), dp(7))
        }
        root.addView(status)

        val modeBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            MODE_PACKS to "📋 Pacchetti",
            MODE_TOOLS to "🔧 Attrezzi",
            MODE_MATERIALS to "📦 Materiali"
        ).forEach { (id, label) ->
            modeBar.addView(MaterialButton(this).apply {
                text = label
                textSize = 12f
                setOnClickListener {
                    mode = id
                    search.setText("")
                    refresh()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply {
                marginEnd = dp(6)
            })
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(modeBar)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))

        search = EditText(this).apply {
            hint = "Cerca lavoro, attrezzo o materiale…"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search, lp52())

        adapter = RowAdapter()
        root.addView(ListView(this).apply {
            dividerHeight = 0
            adapter = this@WorkPreparationActivity.adapter
            setOnItemClickListener { _, _, position, _ -> openRow(this@WorkPreparationActivity.adapter.rows[position]) }
            setOnItemLongClickListener { _, _, position, _ ->
                longPressRow(this@WorkPreparationActivity.adapter.rows[position])
                true
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(MaterialButton(this).apply {
            text = "📤 CONDIVIDI LISTA"
            minHeight = dp(52)
            setOnClickListener { shareCurrentJob() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            bottomMargin = dp(4)
        })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(MaterialButton(this@WorkPreparationActivity).apply {
                text = "+ PACCHETTO ATTREZZI"
                textSize = 11f
                setOnClickListener { createCustomPack(ResourceKind.TOOL) }
            }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(4) })
            addView(MaterialButton(this@WorkPreparationActivity).apply {
                text = "+ PACCHETTO MATERIALI"
                textSize = 11f
                setOnClickListener { createCustomPack(ResourceKind.MATERIAL) }
            }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(4) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))

        root.addView(MaterialButton(this).apply {
            text = "GESTIONE COMPLETA / MODIFICA CATALOGO"
            minHeight = dp(52)
            setOnClickListener { startActivity(Intent(this@WorkPreparationActivity, ToolsActivity::class.java)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        return root
    }

    private fun installJobSpinner(keepId: String?) {
        if (!::jobSpinner.isInitialized) return
        val labels = mutableListOf("Scegli cantiere").apply {
            addAll(jobRows.map { "${it.code} · ${it.displayTitle()}" })
        }
        jobSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val index = jobRows.indexOfFirst { it.ge360Id == keepId }
        jobSpinner.setSelection(if (index >= 0) index + 1 else 0)
        jobSpinner.onItemSelectedListener = Selected { refresh() }
    }

    private fun selectedJob(): JobsiteRecord? = jobRows.getOrNull(jobSpinner.selectedItemPosition - 1)

    private fun shareCurrentJob() {
        val job = selectedJob() ?: return toast("Scegli prima un cantiere")
        JobResourceShare.show(this, job, store.jobResources(job.ge360Id))
    }

    private fun refresh() {
        if (!::adapter.isInitialized) return
        val q = if (::search.isInitialized) search.text.toString().trim().lowercase(Locale.ITALY) else ""
        val rows = when (mode) {
            MODE_TOOLS -> store.catalog(q, ResourceKind.TOOL).map {
                Row("catalog", it.id, "🔧 ${it.name}", "${it.category} · ${it.unit}${if (it.note.isBlank()) "" else " · ${it.note}"}", it)
            }
            MODE_MATERIALS -> store.catalog(q, ResourceKind.MATERIAL).map {
                Row("catalog", it.id, "📦 ${it.name}", "${it.category} · ${it.unit}${if (it.note.isBlank()) "" else " · ${it.note}"}", it)
            }
            else -> {
                val customRows = customPackStore.list().filter { pack ->
                    q.isBlank() || pack.title.lowercase(Locale.ITALY).contains(q) ||
                        pack.items.any { it.name.lowercase(Locale.ITALY).contains(q) }
                }.map { pack ->
                    Row(
                        "custom_pack",
                        pack.id.hashCode().toLong(),
                        "${if (pack.kind == ResourceKind.TOOL) "🔧" else "📦"} ${pack.title}",
                        "PERSONALIZZATO · ${pack.items.size} voci · tieni premuto per modificare",
                        pack
                    )
                }
                val standardRows = StandardWorkCatalog.packs.filter { pack ->
                    q.isBlank() || pack.title.lowercase(Locale.ITALY).contains(q) ||
                        pack.description.lowercase(Locale.ITALY).contains(q) ||
                        pack.items.any { it.name.lowercase(Locale.ITALY).contains(q) }
                }.mapIndexed { index, pack ->
                    Row(
                        "pack",
                        -(index + 1).toLong(),
                        "${pack.icon} ${pack.title}",
                        "${pack.toolsCount} attrezzi · ${pack.materialsCount} materiali · ${pack.description}",
                        pack
                    )
                }
                customRows + standardRows
            }
        }
        adapter.rows = rows
        adapter.notifyDataSetChanged()

        val job = selectedJob()
        status.text = when {
            job == null && mode == MODE_PACKS -> "Pacchetti salvati: ${customPackStore.list().size}. Scegli un cantiere per usarli."
            job == null -> "Scegli un cantiere. Poi tocca una voce per aggiungerla."
            mode == MODE_PACKS -> "${job.code} · scegli un pacchetto: la checklist è già preselezionata e puoi togliere ciò che non serve."
            mode == MODE_TOOLS -> "${job.code} · lista attrezzi · tocca una voce per metterla in preparazione."
            else -> "${job.code} · lista materiali · tocca una voce; per i prodotti a sacco scegli subito quanti sacchi servono."
        }
    }

    private fun openRow(row: Row) {
        when (row.type) {
            "pack" -> openPack(row.payload as WorkPack)
            "custom_pack" -> openPack((row.payload as CustomWorkPack).toWorkPack())
            "catalog" -> addCatalogResource(row.payload as CatalogResource)
        }
    }

    private fun longPressRow(row: Row) {
        if (row.type == "custom_pack") manageCustomPack(row.payload as CustomWorkPack)
    }

    private fun createCustomPack(kind: ResourceKind) {
        val available = store.catalog("", kind)
        if (available.isEmpty()) return toast("Prima aggiungi almeno una voce al catalogo")
        val name = EditText(this).apply {
            hint = if (kind == ResourceKind.TOOL) "Es. Demolizione leggera" else "Es. Materiali bagno standard"
            isSingleLine = true
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (kind == ResourceKind.TOOL) "Nuovo pacchetto attrezzi" else "Nuovo pacchetto materiali")
            .setMessage("Dagli un nome. Nel passaggio successivo scegli cosa contiene.")
            .setView(name)
            .setPositiveButton("Avanti", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = name.text.toString().trim()
                if (title.isBlank()) {
                    name.error = "Nome obbligatorio"
                    return@setOnClickListener
                }
                dialog.dismiss()
                chooseCustomPackItems(kind, title, null)
            }
        }
        dialog.show()
    }

    private fun chooseCustomPackItems(kind: ResourceKind, title: String, existing: CustomWorkPack?) {
        val available = store.catalog("", kind)
        if (available.isEmpty()) return toast("Nessuna voce disponibile nel catalogo")
        val oldKeys = existing?.items?.map { resourceKey(it.kind, it.name) }?.toSet().orEmpty()
        val selected = BooleanArray(available.size) { index ->
            resourceKey(available[index].kind, available[index].name) in oldKeys
        }
        val labels = available.map { "${it.name} · ${it.category}" }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle("$title · scegli le voci")
            .setMultiChoiceItems(labels, selected) { _, which, checked -> selected[which] = checked }
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chosen = available.filterIndexed { index, _ -> selected[index] }
                if (chosen.isEmpty()) {
                    toast("Seleziona almeno una voce")
                    return@setOnClickListener
                }
                val pack = CustomWorkPack(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    title = title,
                    kind = kind,
                    items = chosen.map { item ->
                        WorkResourceSpec(
                            kind = item.kind,
                            name = item.name,
                            category = item.category,
                            unit = item.unit,
                            quantity = 1.0,
                            note = item.note
                        )
                    }
                )
                customPackStore.save(pack)
                dialog.dismiss()
                mode = MODE_PACKS
                refresh()
                toast("Pacchetto '$title' salvato")
            }
        }
        dialog.show()
    }

    private fun manageCustomPack(pack: CustomWorkPack) {
        AlertDialog.Builder(this)
            .setTitle(pack.title)
            .setItems(arrayOf("✏️ Modifica nome e contenuto", "🗑️ Elimina pacchetto")) { _, which ->
                when (which) {
                    0 -> renameAndEditCustomPack(pack)
                    1 -> confirmDeleteCustomPack(pack)
                }
            }
            .show()
    }

    private fun renameAndEditCustomPack(pack: CustomWorkPack) {
        val name = EditText(this).apply {
            setText(pack.title)
            selectAll()
            isSingleLine = true
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Modifica pacchetto")
            .setView(name)
            .setPositiveButton("Avanti", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = name.text.toString().trim()
                if (title.isBlank()) {
                    name.error = "Nome obbligatorio"
                    return@setOnClickListener
                }
                dialog.dismiss()
                chooseCustomPackItems(pack.kind, title, pack)
            }
        }
        dialog.show()
    }

    private fun confirmDeleteCustomPack(pack: CustomWorkPack) {
        AlertDialog.Builder(this)
            .setTitle("Eliminare ${pack.title}?")
            .setMessage("Viene eliminato solo il pacchetto salvato. Gli attrezzi o materiali già inseriti nei cantieri restano dove sono.")
            .setPositiveButton("Elimina") { _, _ ->
                customPackStore.delete(pack.id)
                refresh()
                toast("Pacchetto eliminato")
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun openPack(pack: WorkPack) {
        val job = selectedJob() ?: return toast("Scegli prima un cantiere")
        val selected = BooleanArray(pack.items.size) { true }
        val labels = pack.items.map {
            val icon = if (it.kind == ResourceKind.TOOL) "🔧" else "📦"
            "$icon ${it.name}${if (it.quantity == 1.0) "" else " · ${it.quantity.clean()} ${it.unit}"}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("${pack.icon} ${pack.title} · ${job.code}")
            .setMultiChoiceItems(labels, selected) { _, which, checked -> selected[which] = checked }
            .setPositiveButton("Aggiungi selezionati") { _, _ ->
                val chosen = pack.items.filterIndexed { index, _ -> selected[index] }
                addSpecsToJob(job, chosen, pack.title)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun addSpecsToJob(job: JobsiteRecord, specs: List<WorkResourceSpec>, source: String) {
        if (specs.isEmpty()) return toast("Non hai selezionato nessuna voce")
        val existingKeys = store.jobResources(job.ge360Id).map {
            resourceKey(it.kind, it.name)
        }.toMutableSet()
        val catalog = store.catalog().associateBy { resourceKey(it.kind, it.name) }
        var added = 0
        var skipped = 0

        specs.forEach { spec ->
            val key = resourceKey(spec.kind, spec.name)
            if (key in existingKeys) {
                skipped++
            } else {
                val c = catalog[key]
                store.saveJobResource(
                    JobResource(
                        jobsiteId = job.ge360Id,
                        jobsiteCode = job.code,
                        jobsiteTitle = job.displayTitle(),
                        catalogId = c?.id ?: 0,
                        catalogGe360Id = c?.ge360Id.orEmpty(),
                        kind = spec.kind,
                        name = spec.name,
                        quantity = spec.quantity,
                        unit = spec.unit,
                        status = if (spec.kind == ResourceKind.TOOL) "Da portare" else "Da comprare",
                        note = listOf(spec.note, "Pacchetto: $source").filter { it.isNotBlank() }.joinToString(" · ")
                    )
                )
                existingKeys += key
                added++
            }
        }
        toast("$source: aggiunti $added${if (skipped > 0) " · $skipped già presenti" else ""}")
        refresh()
    }

    private fun addCatalogResource(item: CatalogResource) {
        val job = selectedJob() ?: return toast("Scegli prima un cantiere")
        val key = resourceKey(item.kind, item.name)
        val existing = store.jobResources(job.ge360Id).firstOrNull { resourceKey(it.kind, it.name) == key }

        if (item.kind == ResourceKind.MATERIAL && isSackUnit(item.unit)) {
            showSackQuantityDialog(job, item, existing)
            return
        }

        if (existing != null) return toast("${item.name} è già nella preparazione di ${job.code}")
        saveCatalogResourceToJob(job, item, 1.0, null)
    }

    private fun isSackUnit(unit: String): Boolean =
        unit.trim().lowercase(Locale.ITALY) in setOf("sacco", "sacchi")

    private fun showSackQuantityDialog(job: JobsiteRecord, item: CatalogResource, existing: JobResource?) {
        var quantity = existing?.quantity?.toInt()?.coerceAtLeast(1) ?: 1

        val quantityLabel = TextView(this).apply {
            text = quantity.toString()
            textSize = 42f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(10))
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(MaterialButton(this@WorkPreparationActivity).apply {
                text = "−"
                textSize = 26f
                minHeight = dp(58)
                setOnClickListener {
                    if (quantity > 1) quantity--
                    quantityLabel.text = quantity.toString()
                }
            }, LinearLayout.LayoutParams(0, dp(62), 1f).apply { marginEnd = dp(8) })
            addView(MaterialButton(this@WorkPreparationActivity).apply {
                text = "+"
                textSize = 26f
                minHeight = dp(58)
                setOnClickListener {
                    quantity++
                    quantityLabel.text = quantity.toString()
                }
            }, LinearLayout.LayoutParams(0, dp(62), 1f).apply { marginStart = dp(8) })
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(4))
            addView(TextView(this@WorkPreparationActivity).apply {
                text = "QUANTI SACCHI?"
                textSize = 22f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, 0)
            })
            addView(TextView(this@WorkPreparationActivity).apply {
                text = "${item.name} · ${job.code}"
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, 0)
            })
            addView(quantityLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(82)))
            addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)))
        }

        AlertDialog.Builder(this)
            .setView(content)
            .setPositiveButton(if (existing == null) "AGGIUNGI" else "AGGIORNA") { _, _ ->
                saveCatalogResourceToJob(job, item, quantity.toDouble(), existing)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun saveCatalogResourceToJob(
        job: JobsiteRecord,
        item: CatalogResource,
        quantity: Double,
        existing: JobResource?
    ) {
        val record = existing ?: JobResource(
            jobsiteId = job.ge360Id,
            jobsiteCode = job.code,
            jobsiteTitle = job.displayTitle(),
            catalogId = item.id,
            catalogGe360Id = item.ge360Id,
            kind = item.kind,
            name = item.name,
            unit = item.unit,
            status = if (item.kind == ResourceKind.TOOL) "Da portare" else "Da comprare",
            note = item.note
        )
        record.quantity = quantity
        record.unit = item.unit
        record.note = item.note
        store.saveJobResource(record)
        toast("${item.name} · ${quantity.clean()} ${item.unit} · ${job.code}")
        refresh()
    }

    private fun resourceKey(kind: ResourceKind, name: String): String =
        "${kind.name}:${name.trim().lowercase(Locale.ITALY)}"

    private fun Double.clean(): String =
        if (this % 1.0 == 0.0) toInt().toString() else String.format(Locale.ITALY, "%.2f", this)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun lp52() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Row(
        val type: String,
        val id: Long,
        val title: String,
        val subtitle: String,
        val payload: Any
    )

    private inner class RowAdapter : BaseAdapter() {
        var rows: List<Row> = emptyList()
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = rows[position]
            return MaterialCardView(this@WorkPreparationActivity).apply {
                radius = dp(14).toFloat()
                cardElevation = dp(1).toFloat()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dp(4), 0, dp(4))
                }
                addView(LinearLayout(this@WorkPreparationActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    addView(TextView(this@WorkPreparationActivity).apply {
                        text = row.title
                        textSize = 16f
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    addView(TextView(this@WorkPreparationActivity).apply {
                        text = row.subtitle
                        textSize = 12f
                        setPadding(0, dp(4), 0, 0)
                    })
                })
            }
        }
    }

    private class Selected(private val block: () -> Unit) : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = block()
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    companion object {
        private const val MODE_PACKS = "packs"
        private const val MODE_TOOLS = "tools"
        private const val MODE_MATERIALS = "materials"
    }
}
