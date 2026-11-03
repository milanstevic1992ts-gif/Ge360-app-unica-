package it.edilmilan.ge360.preventivi

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import java.util.Locale
import java.util.UUID

/**
 * UI flessibile degli ambienti.
 *
 * Lo standard propone una base ma non impone vincoli: checklist libera,
 * lavorazioni manuali, misure geometriche o manuali per forme irregolari,
 * prezzo a corpo/a misura, duplicazione e preferenze personali esportabili.
 */
object FlexibleRoomPlanner {
    fun openForEstimate(context: Context, estimate: EstimateRecord, onApplied: () -> Unit) {
        if (estimate.ge360Id.isBlank()) estimate.ge360Id = UUID.randomUUID().toString()
        val store = EstimateRoomStore(context)
        val rooms = store.hydrateFromEstimate(estimate)
        if (rooms.isEmpty() && estimate.workType != "Ristrutturazione intero appartamento") {
            val room = defaultRoom(estimate).apply {
                estimateGe360Id = estimate.ge360Id
                sortOrder = 0
            }
            editRoom(context, estimate, room, store, true) {
                store.close()
                onApplied()
            }
        } else {
            showRooms(context, estimate, store, onApplied)
        }
    }

    private fun defaultRoom(estimate: EstimateRecord): EstimateRoom = when (estimate.workType) {
        "Ristrutturazione bagno" -> EstimateRoom(name = "Bagno", type = RoomTypes.BATHROOM, pricingMode = RoomPricing.BODY)
        "Ristrutturazione cucina" -> EstimateRoom(name = "Cucina", type = RoomTypes.KITCHEN, pricingMode = RoomPricing.MEASURE)
        "Terrazze e balconi" -> EstimateRoom(name = "Balcone/Terrazzo", type = RoomTypes.BALCONY, pricingMode = RoomPricing.MEASURE)
        else -> EstimateRoom(name = estimate.title.ifBlank { "Stanza" }, type = RoomTypes.ROOM, pricingMode = RoomPricing.MEASURE)
    }

    private fun showRooms(context: Context, estimate: EstimateRecord, store: EstimateRoomStore, onApplied: () -> Unit) {
        val rooms = store.hydrateFromEstimate(estimate)
        val custom = PreventiviCustomizationStore(context)
        val labels = rooms.map { r ->
            val manualCount = estimate.items.count { RoomManualWorkDialog.belongsToRoom(it, r.ge360Id) }
            buildString {
                append(r.name).append(" · ").append(r.type)
                append("\n").append(custom.manualMeasuresSummary(r))
                append("\n").append(r.pricingMode)
                if (r.pricingMode == RoomPricing.BODY) append(" · € ").append(fmt(r.bodyPrice))
                if (manualCount > 0) append(" · ").append(manualCount).append(" manuali")
            }
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Ambienti / zone")
            .setMessage("Aggiungi Bagno, Cucina, Stanza 1, Stanza 2, Balcone… Ogni ambiente ha misure, prezzo, checklist e lavorazioni manuali indipendenti. Porte e finestre restano comprese nei m².")
            .setItems(labels) { _, p -> roomMenu(context, estimate, rooms[p], store, onApplied) }
            .setPositiveButton("+ Aggiungi ambiente") { _, _ ->
                val room = EstimateRoom(estimateGe360Id = estimate.ge360Id, sortOrder = rooms.size)
                editRoom(context, estimate, room, store, true) {
                    onApplied()
                    showRooms(context, estimate, store, onApplied)
                }
            }
            .setNeutralButton("Strumenti") { _, _ -> EstimateWorkflowTools.show(context, estimate, onApplied) }
            .setNegativeButton("Chiudi") { _, _ -> store.close() }
            .show()
    }

    private fun roomMenu(context: Context, estimate: EstimateRecord, room: EstimateRoom, store: EstimateRoomStore, onApplied: () -> Unit) {
        val manualCount = estimate.items.count { RoomManualWorkDialog.belongsToRoom(it, room.ge360Id) }
        val custom = PreventiviCustomizationStore(context)
        val irregular = custom.manualMeasures(room.ge360Id).enabled
        val options = arrayOf(
            "Apri checklist / misure",
            if (irregular) "Misure manuali / forma irregolare · ATTIVE" else "Misure manuali / forma irregolare",
            "+ Aggiungi lavorazione manuale",
            "+ Inserisci dalle mie lavorazioni",
            "Duplica ambiente",
            "Salva checklist come predefinita",
            "Elimina ambiente"
        )
        AlertDialog.Builder(context)
            .setTitle(room.name)
            .setMessage("Checklist standard modificabile + lavorazioni manuali senza limiti.${if (manualCount > 0) "\nLavorazioni manuali presenti: $manualCount" else ""}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> editRoom(context, estimate, room, store, false) {
                        onApplied()
                        showRooms(context, estimate, store, onApplied)
                    }
                    1 -> editManualMeasures(context, estimate, room, store, onApplied)
                    2 -> RoomManualWorkDialog.show(context, estimate, room) {
                        onApplied()
                        showRooms(context, estimate, store, onApplied)
                    }
                    3 -> pickSavedWork(context, estimate, room, store, onApplied)
                    4 -> duplicateRoom(context, estimate, room, store, onApplied)
                    5 -> {
                        custom.saveChecklistDefault(room.type, room.selectedCodes)
                        Toast.makeText(context, "Checklist ${room.type} salvata come tua predefinita", Toast.LENGTH_LONG).show()
                        showRooms(context, estimate, store, onApplied)
                    }
                    6 -> deleteRoomConfirm(context, estimate, room, store, onApplied)
                }
            }
            .setNegativeButton("Indietro") { _, _ -> showRooms(context, estimate, store, onApplied) }
            .show()
    }

    private fun editManualMeasures(
        context: Context,
        estimate: EstimateRecord,
        room: EstimateRoom,
        store: EstimateRoomStore,
        onApplied: () -> Unit
    ) {
        val custom = PreventiviCustomizationStore(context)
        val current = custom.manualMeasures(room.ge360Id)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun field(label: String, value: Double): EditText {
            val input = EditText(context).apply {
                hint = label
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                if (value > 0) setText(fmt(value))
            }
            return input
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
        }
        val enabled = CheckBox(context).apply {
            text = "Usa misure manuali per questo ambiente"
            isChecked = current.enabled
        }
        val floor = field("Pavimento m²", current.floor)
        val ceiling = field("Soffitto m²", current.ceiling)
        val walls = field("Pareti lorde m²", current.walls)
        val perimeter = field("Perimetro ml", current.perimeter)
        root.addView(enabled)
        root.addView(TextView(context).apply {
            text = "Per corridoi, stanze a L o forme irregolari inserisci direttamente le quantità reali. Porte e finestre NON vengono sottratte. Un campo lasciato a 0 continua a usare il calcolo L×W×H della stanza."
            textSize = 11f
            setPadding(0, dp(4), 0, dp(8))
        })
        listOf(floor, ceiling, walls, perimeter).forEach { root.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))) }
        fun parse(e: EditText) = e.text.toString().trim().replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

        AlertDialog.Builder(context)
            .setTitle("${room.name} · misure manuali")
            .setView(root)
            .setPositiveButton("Salva") { _, _ ->
                custom.saveManualMeasures(
                    room.ge360Id,
                    ManualRoomMeasures(enabled.isChecked, parse(floor), parse(ceiling), parse(walls), parse(perimeter))
                )
                syncRoomItems(context, estimate, room)
                onApplied()
                showRooms(context, estimate, store, onApplied)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun pickSavedWork(
        context: Context,
        estimate: EstimateRecord,
        room: EstimateRoom,
        store: EstimateRoomStore,
        onApplied: () -> Unit
    ) {
        val rows = PreventiviCustomizationStore(context).savedWorks()
        if (rows.isEmpty()) {
            Toast.makeText(context, "Non hai ancora lavorazioni personali salvate", Toast.LENGTH_LONG).show()
            showRooms(context, estimate, store, onApplied)
            return
        }
        val labels = rows.map { "${it.name} · ${it.unit}${if (it.suggestedPrice > 0) " · € ${fmt(it.suggestedPrice)}" else ""}" }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("Le mie lavorazioni · ${room.name}")
            .setItems(labels) { _, p ->
                RoomManualWorkDialog.show(context, estimate, room, rows[p]) {
                    onApplied()
                    showRooms(context, estimate, store, onApplied)
                }
            }
            .setNegativeButton("Annulla") { _, _ -> showRooms(context, estimate, store, onApplied) }
            .show()
    }

    private fun duplicateRoom(
        context: Context,
        estimate: EstimateRecord,
        source: EstimateRoom,
        store: EstimateRoomStore,
        onApplied: () -> Unit
    ) {
        val target = source.copy(
            ge360Id = UUID.randomUUID().toString(),
            estimateGe360Id = estimate.ge360Id,
            name = "${source.name} copia",
            selectedCodes = source.selectedCodes.toMutableSet(),
            sortOrder = store.list(estimate.ge360Id).size,
            updatedAt = System.currentTimeMillis()
        )
        store.save(target)
        val custom = PreventiviCustomizationStore(context)
        custom.copyManualMeasures(source.ge360Id, target.ge360Id)

        val oldAuto = "${RoomWorkCatalog.ITEM_PREFIX}${source.ge360Id}/"
        val newAuto = "${RoomWorkCatalog.ITEM_PREFIX}${target.ge360Id}/"
        val oldManual = RoomManualWorkDialog.prefixFor(source.ge360Id)
        val newManual = RoomManualWorkDialog.prefixFor(target.ge360Id)
        val clones = estimate.items.filter {
            it.priceListCode.startsWith(oldAuto) || it.priceListCode.startsWith(oldManual)
        }.map { old ->
            val meta = RoomWorkCatalog.parseMetadata(old.internalNote)
            val basis = runCatching { QuantityBasis.valueOf(meta?.optString("basis", QuantityBasis.MANUAL.name) ?: QuantityBasis.MANUAL.name) }
                .getOrDefault(QuantityBasis.MANUAL)
            old.copy(
                id = 0,
                ge360Id = UUID.randomUUID().toString(),
                estimateId = estimate.id,
                priceListCode = old.priceListCode.replace(oldAuto, newAuto).replace(oldManual, newManual),
                room = target.name,
                internalNote = RoomWorkCatalog.attachMetadata(old.internalNote, target, basis)
            )
        }
        estimate.items += clones
        syncRoomItems(context, estimate, target)
        onApplied()
        Toast.makeText(context, "Creata ${target.name}", Toast.LENGTH_LONG).show()
        showRooms(context, estimate, store, onApplied)
    }

    private fun deleteRoomConfirm(
        context: Context,
        estimate: EstimateRecord,
        room: EstimateRoom,
        store: EstimateRoomStore,
        onApplied: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle("Elimina ${room.name}?")
            .setMessage("Verranno rimosse le voci automatiche e le lavorazioni manuali collegate a questo ambiente.")
            .setPositiveButton("Elimina") { _, _ ->
                val autoPrefix = "${RoomWorkCatalog.ITEM_PREFIX}${room.ge360Id}/"
                val manualPrefix = RoomManualWorkDialog.prefixFor(room.ge360Id)
                estimate.items.removeAll {
                    it.priceListCode.startsWith(autoPrefix) || it.priceListCode.startsWith(manualPrefix)
                }
                PreventiviCustomizationStore(context).deleteManualMeasures(room.ge360Id)
                store.delete(room.ge360Id)
                onApplied()
                showRooms(context, estimate, store, onApplied)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun editRoom(
        context: Context,
        estimate: EstimateRecord,
        room: EstimateRoom,
        store: EstimateRoomStore,
        isNew: Boolean,
        onApplied: () -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun label(t: String) = TextView(context).apply {
            text = t
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(2))
        }
        fun decimal(v: Double, hintText: String) = EditText(context).apply {
            hint = hintText
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (v > 0) setText(fmt(v))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(14))
        }
        val name = EditText(context).apply { hint = "Nome: Stanza 1, Bagno, Cucina…"; setText(room.name); isSingleLine = true }
        val type = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, RoomTypes.ALL)
            setSelection(RoomTypes.ALL.indexOf(room.type).coerceAtLeast(0))
        }
        val length = decimal(room.length, "Lunghezza m")
        val width = decimal(room.width, "Larghezza m")
        val height = decimal(room.height, "Altezza m")
        val pricing = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, RoomPricing.ALL)
            setSelection(RoomPricing.ALL.indexOf(room.pricingMode).coerceAtLeast(0))
        }
        val bodyPrice = decimal(room.bodyPrice, "Prezzo a corpo €")
        val measures = TextView(context).apply { textSize = 11f; setPadding(0, dp(5), 0, dp(7)) }

        root.addView(label("Nome ambiente")); root.addView(name, lp(dp(50)))
        root.addView(label("Tipo ambiente")); root.addView(type, lp(dp(50)))
        root.addView(label("Lunghezza · larghezza · altezza"))
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(length, LinearLayout.LayoutParams(0, dp(50), 1f))
            addView(width, LinearLayout.LayoutParams(0, dp(50), 1f))
            addView(height, LinearLayout.LayoutParams(0, dp(50), 1f))
        })
        root.addView(measures)
        root.addView(TextView(context).apply {
            text = "Il calcolo delle pareti è lordo: porte e finestre restano comprese. Per forme irregolari puoi attivare le misure manuali dal menu della stanza."
            textSize = 11f
        })
        root.addView(label("Prezzo ambiente")); root.addView(pricing, lp(dp(50)))
        root.addView(TextView(context).apply {
            text = "A corpo = una sola cifra per tutto l'ambiente. A misura = le quantità m²/ml vengono proposte usando le misure. Puoi sempre correggere ogni voce dopo."
            textSize = 11f
        })
        root.addView(bodyPrice, lp(dp(50)))

        fun parse(f: EditText): Double = f.text.toString().trim().replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        fun updateMeasures() {
            val tmp = room.copy(length = parse(length), width = parse(width), height = parse(height))
            measures.text = tmp.measuresSummary()
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateMeasures()
            override fun afterTextChanged(s: Editable?) = Unit
        }
        length.addTextChangedListener(watcher)
        width.addTextChangedListener(watcher)
        height.addTextChangedListener(watcher)
        updateMeasures()

        root.addView(label("Checklist lavorazioni"))
        root.addView(TextView(context).apply {
            text = "Nessuna regola rigida: spunta o togli quello che vuoi. Le voci attive sono solo una base iniziale. Puoi salvare in seguito questa combinazione come tua checklist predefinita."
            textSize = 11f
            setPadding(0, 0, 0, dp(6))
        })
        val checklist = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(checklist)

        var renderedType = room.type
        val selected = if (room.selectedCodes.isNotEmpty()) {
            room.selectedCodes.toMutableSet()
        } else {
            defaultsFor(context, room.type)
        }

        fun render(newType: String, resetForTypeChange: Boolean) {
            if (resetForTypeChange) {
                selected.clear()
                selected += defaultsFor(context, newType)
            }
            renderedType = newType
            checklist.removeAllViews()
            RoomWorkCatalog.forType(newType).forEach { template ->
                val cb = CheckBox(context).apply {
                    text = "${template.title} · ${basisText(template.basis)}"
                    isChecked = template.code in selected
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected += template.code else selected -= template.code
                    }
                }
                checklist.addView(cb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        render(room.type, false)
        type.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val newType = type.selectedItem?.toString() ?: room.type
                if (newType != renderedType) render(newType, true)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(if (isNew) "Nuovo ambiente" else room.name)
            .setView(ScrollView(context).apply { addView(root) })
            .setPositiveButton("Applica", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cleanName = name.text.toString().trim()
                if (cleanName.isBlank()) { name.error = "Inserisci il nome"; return@setOnClickListener }
                room.name = cleanName
                room.type = type.selectedItem.toString()
                room.length = parse(length)
                room.width = parse(width)
                room.height = parse(height)
                room.pricingMode = pricing.selectedItem.toString()
                room.bodyPrice = parse(bodyPrice)
                val allowed = RoomWorkCatalog.forType(room.type).map { it.code }.toSet()
                room.selectedCodes = selected.filter { it in allowed }.toMutableSet()
                store.save(room)
                syncRoomItems(context, estimate, room)
                dialog.dismiss()
                onApplied()
                Toast.makeText(context, "${room.name}: ${room.selectedCodes.size} lavorazioni standard attive", Toast.LENGTH_LONG).show()
            }
        }
        dialog.show()
    }

    private fun syncRoomItems(context: Context, estimate: EstimateRecord, room: EstimateRoom) {
        val prefix = "${RoomWorkCatalog.ITEM_PREFIX}${room.ge360Id}/"
        val manualPrefix = RoomManualWorkDialog.prefixFor(room.ge360Id)
        val existing = estimate.items.filter { it.priceListCode.startsWith(prefix) }.associateBy { it.priceListCode }
        val preserved = estimate.items.filterNot { it.priceListCode.startsWith(prefix) }.toMutableList()
        val section = RoomWorkCatalog.sectionFor(room)
        val custom = PreventiviCustomizationStore(context)

        preserved.filter { it.priceListCode.startsWith(manualPrefix) }.forEach { manual ->
            manual.section = section
            manual.room = room.name
            manual.internalNote = RoomWorkCatalog.attachMetadata(manual.internalNote, room, QuantityBasis.MANUAL)
            manual.total = calculateItemTotal(manual)
        }

        RoomWorkCatalog.forType(room.type).filter { it.code in room.selectedCodes }.forEach { template ->
            val code = RoomWorkCatalog.itemCode(room.ge360Id, template.code)
            val item = existing[code] ?: EstimateItem(
                ge360Id = UUID.randomUUID().toString(),
                priceListCode = code,
                category = template.category,
                name = template.title,
                description = template.descriptionWithRefs(),
                included = true,
                optional = false
            )
            item.section = section
            item.room = room.name
            item.quantity = custom.quantity(room, template.basis)
            item.unit = template.unit
            item.priceMode = if (template.unit in listOf("corpo", "forfait")) "body" else "unit"
            if (room.pricingMode == RoomPricing.BODY) item.unitPrice = 0.0
            item.internalNote = RoomWorkCatalog.attachMetadata(item.internalNote, room, template.basis)
            item.total = calculateItemTotal(item)
            preserved += item
        }

        if (room.pricingMode == RoomPricing.BODY) {
            val code = RoomWorkCatalog.bodyItemCode(room.ge360Id)
            val body = existing[code] ?: EstimateItem(
                ge360Id = UUID.randomUUID().toString(),
                priceListCode = code,
                category = "Prezzo ambiente",
                description = "Corrispettivo a corpo riferito alle lavorazioni selezionate nella checklist dell'ambiente.",
                included = true,
                optional = false
            )
            body.section = section
            body.room = room.name
            body.name = "${room.name} — prezzo a corpo"
            body.quantity = 1.0
            body.unit = "corpo"
            body.priceMode = "body"
            body.unitPrice = room.bodyPrice
            body.internalNote = RoomWorkCatalog.attachMetadata(body.internalNote, room, QuantityBasis.BODY)
            body.total = calculateItemTotal(body)
            preserved += body
        }

        estimate.items.clear()
        estimate.items.addAll(preserved)
    }

    private fun defaultsFor(context: Context, type: String): MutableSet<String> {
        val catalogDefaults = RoomWorkCatalog.forType(type).filter { it.enabledByDefault }.map { it.code }.toMutableSet()
        return PreventiviCustomizationStore(context).checklistDefault(type)?.toMutableSet() ?: catalogDefaults
    }

    private fun RoomWorkTemplate.descriptionWithRefs(): String = buildString {
        append(description.trim())
        if (references.isNotEmpty()) append("\n\nRiferimenti tecnici, ove applicabili: ").append(references.joinToString("; ")).append('.')
    }

    private fun basisText(basis: QuantityBasis): String = when (basis) {
        QuantityBasis.BODY -> "corpo"
        QuantityBasis.FLOOR -> "pavimento m²"
        QuantityBasis.CEILING -> "soffitto m²"
        QuantityBasis.WALLS -> "pareti m²"
        QuantityBasis.WALLS_CEILING -> "pareti+soffitto m²"
        QuantityBasis.WALLS_FLOOR -> "pareti+pavimento m²"
        QuantityBasis.PERIMETER -> "perimetro ml"
        QuantityBasis.MANUAL -> "manuale"
    }

    private fun lp(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    private fun fmt(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.ITALY, "%.2f", v)
}
