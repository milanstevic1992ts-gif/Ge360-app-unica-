package it.edilmilan.ge360.preventivi

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import it.edilmilan.ge360.cantieri.JobsiteProgressItem
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

object EstimateWorkflowTools {
    fun show(context: Context, estimate: EstimateRecord, onApplied: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Strumenti preventivo")
            .setItems(
                arrayOf(
                    "Gestione contratto · storico / extra / varianti",
                    "Le mie lavorazioni / backup",
                    "Modalità documento cliente",
                    "Costi interni e margine",
                    "Crea revisione del preventivo",
                    "Crea / aggiorna cantiere da preventivo"
                )
            ) { _, which ->
                when (which) {
                    0 -> EstimateLifecycleUi.show(context, estimate, onApplied)
                    1 -> customizationMenu(context)
                    2 -> presentationMenu(context, estimate)
                    3 -> costingMenu(context, estimate, onApplied)
                    4 -> revisionConfirm(context, estimate)
                    5 -> jobsiteConfirm(context, estimate, onApplied)
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun customizationMenu(context: Context) {
        val store = PreventiviCustomizationStore(context)
        val works = store.savedWorks()
        AlertDialog.Builder(context)
            .setTitle("Personalizzazioni GE360")
            .setMessage("${works.size} lavorazioni personali salvate. Il backup contiene lavorazioni e checklist predefinite ed è pensato per essere conservato/versionato anche nella repository.")
            .setItems(arrayOf("Le mie lavorazioni", "Copia backup JSON", "Condividi backup JSON", "Importa backup dagli appunti")) { _, which ->
                when (which) {
                    0 -> savedWorksMenu(context)
                    1 -> {
                        val json = store.exportReusableJson()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("GE360 preventivi customizations", json))
                        toast(context, "Backup copiato negli appunti")
                    }
                    2 -> shareText(context, store.exportReusableJson(), "Backup personalizzazioni GE360")
                    3 -> importFromClipboard(context, store)
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun savedWorksMenu(context: Context) {
        val store = PreventiviCustomizationStore(context)
        val works = store.savedWorks()
        if (works.isEmpty()) {
            toast(context, "Nessuna lavorazione personale salvata")
            return
        }
        val labels = works.map { "${it.name} · ${it.unit}${if (it.suggestedPrice > 0) " · ${euro(it.suggestedPrice)}" else ""}" }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("Le mie lavorazioni")
            .setItems(labels) { _, p ->
                val w = works[p]
                AlertDialog.Builder(context)
                    .setTitle(w.name)
                    .setMessage(buildString {
                        append(w.category).append(" · ").append(w.unit)
                        if (w.suggestedPrice > 0) append(" · ").append(euro(w.suggestedPrice))
                        if (w.description.isNotBlank()) append("\n\n").append(w.description)
                        if (w.references.isNotEmpty()) append("\n\nRiferimenti, ove applicabili:\n").append(w.references.joinToString("\n") { "• $it" })
                    })
                    .setNegativeButton("Chiudi", null)
                    .setNeutralButton("Elimina") { _, _ ->
                        store.deleteWork(w.id)
                        toast(context, "Lavorazione rimossa dalla libreria")
                    }
                    .show()
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun importFromClipboard(context: Context, store: PreventiviCustomizationStore) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isBlank()) {
            toast(context, "Appunti vuoti")
            return
        }
        runCatching { store.importReusableJson(text, replace = false) }
            .onSuccess { (works, checklists) -> toast(context, "Importate $works lavorazioni e $checklists checklist") }
            .onFailure { toast(context, "Backup non valido: ${it.message ?: "formato non riconosciuto"}") }
    }

    private fun presentationMenu(context: Context, estimate: EstimateRecord) {
        if (estimate.ge360Id.isBlank()) estimate.ge360Id = UUID.randomUUID().toString()
        val store = PreventiviCustomizationStore(context)
        val current = store.presentationMode(estimate.ge360Id)
        val checked = EstimatePresentationModes.ALL.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle("Documento cliente")
            .setSingleChoiceItems(EstimatePresentationModes.ALL.toTypedArray(), checked) { dialog, which ->
                val mode = EstimatePresentationModes.ALL[which]
                store.setPresentationMode(estimate.ge360Id, mode)
                dialog.dismiss()
                documentActions(context, estimate, mode)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun documentActions(context: Context, estimate: EstimateRecord, mode: String) {
        val preview = EstimatePresentationFormatter.build(estimate, mode)
        AlertDialog.Builder(context)
            .setTitle("$mode · documento cliente")
            .setItems(arrayOf("Anteprima testo", "Condividi")) { _, which ->
                if (which == 0) {
                    AlertDialog.Builder(context)
                        .setTitle("Anteprima $mode")
                        .setMessage(preview)
                        .setPositiveButton("Chiudi", null)
                        .show()
                } else shareText(context, preview, "Preventivo ${estimate.number.ifBlank { estimate.title }}")
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun costingMenu(context: Context, estimate: EstimateRecord, onApplied: () -> Unit) {
        val t = recalculateEstimate(estimate)
        val cost = EstimateItemCosting.totalCost(estimate)
        val margin = t.finalTotal - cost
        val hours = EstimateItemCosting.totalHours(estimate)
        val rows = estimate.items.filter { it.included && !it.optional }
        val labels = rows.map { item ->
            val c = EstimateItemCosting.read(item)
            "${item.room.ifBlank { item.section }} · ${item.name}\nCosto ${euro(c.total())}${if (c.estimatedHours > 0) " · ${fmt(c.estimatedHours)} h" else ""}"
        }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("Costi e margine")
            .setMessage(
                "Ricavi preventivo: ${euro(t.finalTotal)}\n" +
                    "Costi interni stimati: ${euro(cost)}\n" +
                    "Ore previste: ${fmt(hours)} h\n" +
                    "Margine operativo stimato: ${euro(margin)}" +
                    if (t.finalTotal > 0) " · ${fmt(margin * 100.0 / t.finalTotal)}%" else ""
            )
            .setItems(labels) { _, p -> editItemCost(context, estimate, rows[p], onApplied) }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun editItemCost(context: Context, estimate: EstimateRecord, item: EstimateItem, onApplied: () -> Unit) {
        val old = EstimateItemCosting.read(item)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 8), dp(context, 18), dp(context, 8))
        }
        fun field(label: String, value: Double): EditText {
            root.addView(TextView(context).apply { text = label; setTypeface(typeface, Typeface.BOLD) })
            return EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                isSingleLine = true
                if (value > 0) setText(fmt(value))
                root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 50)))
            }
        }
        val materials = field("Materiali €", old.materials)
        val labor = field("Manodopera interna €", old.labor)
        val collaborators = field("Collaboratori €", old.collaborators)
        val other = field("Altri costi €", old.other)
        val hours = field("Ore previste", old.estimatedHours)
        fun parse(e: EditText) = e.text.toString().trim().replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

        AlertDialog.Builder(context)
            .setTitle(item.name)
            .setView(ScrollView(context).apply { addView(root) })
            .setPositiveButton("Salva") { _, _ ->
                EstimateItemCosting.write(item, ItemCostProfile(parse(materials), parse(labor), parse(collaborators), parse(other), parse(hours)))
                EstimateLifecycleUi.recordSaved(context, estimate, "Costi interni aggiornati")
                onApplied()
                costingMenu(context, estimate, onApplied)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun revisionConfirm(context: Context, estimate: EstimateRecord) {
        AlertDialog.Builder(context)
            .setTitle("Crea revisione?")
            .setMessage("Verrà creato un nuovo preventivo indipendente. Ambienti, misure manuali, lavorazioni, costi interni e prezzi vengono copiati; il preventivo originale resta invariato.")
            .setPositiveButton("Crea revisione") { _, _ -> createRevision(context, estimate) }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun createRevision(context: Context, source: EstimateRecord) {
        val estimateStore = EstimateStore(context)
        val roomStore = EstimateRoomStore(context)
        val custom = PreventiviCustomizationStore(context)
        val lifecycle = EstimateLifecycleStore(context)
        try {
            val copy = estimateStore.duplicate(source)
            copy.documentType = "Variante"
            copy.title = source.title.ifBlank { source.workType }.let { "$it · revisione" }

            val roomIds = mutableMapOf<String, String>()
            val roomRows = mutableMapOf<String, EstimateRoom>()
            copy.items.forEach { item ->
                val meta = RoomWorkCatalog.parseMetadata(item.internalNote) ?: return@forEach
                val oldId = meta.optString("id")
                if (oldId.isBlank()) return@forEach
                val newId = roomIds.getOrPut(oldId) { UUID.randomUUID().toString() }
                val selected = mutableSetOf<String>()
                meta.optJSONArray("selectedCodes")?.let { a -> for (i in 0 until a.length()) selected += a.optString(i) }
                val newRoom = roomRows.getOrPut(oldId) {
                    EstimateRoom(
                        ge360Id = newId,
                        estimateGe360Id = copy.ge360Id,
                        name = meta.optString("name", item.room.ifBlank { "Stanza" }),
                        type = meta.optString("type", RoomTypes.OTHER),
                        length = meta.optDouble("length", 0.0),
                        width = meta.optDouble("width", 0.0),
                        height = meta.optDouble("height", 0.0),
                        pricingMode = meta.optString("pricingMode", RoomPricing.MEASURE),
                        bodyPrice = meta.optDouble("bodyPrice", 0.0),
                        selectedCodes = selected
                    )
                }
                item.priceListCode = item.priceListCode
                    .replace("${RoomWorkCatalog.ITEM_PREFIX}$oldId/", "${RoomWorkCatalog.ITEM_PREFIX}$newId/")
                    .replace("${RoomManualWorkDialog.MANUAL_PREFIX}$oldId/", "${RoomManualWorkDialog.MANUAL_PREFIX}$newId/")
                val basis = runCatching { QuantityBasis.valueOf(meta.optString("basis", QuantityBasis.MANUAL.name)) }.getOrDefault(QuantityBasis.MANUAL)
                item.internalNote = RoomWorkCatalog.attachMetadata(item.internalNote, newRoom, basis)
            }
            roomRows.values.forEachIndexed { index, r -> r.sortOrder = index; roomStore.save(r) }
            roomIds.forEach { (oldId, newId) -> custom.copyManualMeasures(oldId, newId) }
            custom.copyPresentationMode(source.ge360Id, copy.ge360Id)
            estimateStore.save(copy)
            val rootId = lifecycle.rootEstimate(source.ge360Id)
            val relation = lifecycle.link(copy.ge360Id, rootId, EstimateRelationTypes.REVISION)
            lifecycle.recordEvent(copy, "REVISION", "Revisione ${relation.sequence} creata", "Derivata da ${source.number}", force = true)
            toast(context, "Creata revisione ${copy.number}")
        } finally {
            lifecycle.close()
            estimateStore.close()
            roomStore.close()
        }
    }

    private fun jobsiteConfirm(context: Context, estimate: EstimateRecord, onApplied: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Preventivo → cantiere")
            .setMessage("Se il cantiere non esiste verrà creato. Le lavorazioni incluse vengono trasferite come fasi operative. Un cantiere già avviato non viene sovrascritto nelle sue percentuali di avanzamento.")
            .setPositiveButton("Procedi") { _, _ -> createOrUpdateJobsite(context, estimate, onApplied) }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun createOrUpdateJobsite(context: Context, estimate: EstimateRecord, onApplied: () -> Unit) {
        val jobs = JobsiteStore(context)
        val estimates = EstimateStore(context)
        try {
            val existing = estimate.jobsiteId.takeIf { it.isNotBlank() }?.let(jobs::getByGe360Id)
            val job = existing ?: JobsiteRecord(
                clientId = estimate.clientId,
                clientGe360Id = estimate.clientGe360Id,
                clientName = estimate.clientName,
                title = estimate.title.ifBlank { estimate.workType },
                workType = estimate.workType
            )
            job.clientId = estimate.clientId
            job.clientGe360Id = estimate.clientGe360Id
            job.clientName = estimate.clientName
            job.title = estimate.title.ifBlank { estimate.workType }
            job.workType = estimate.workType
            job.notes = buildString {
                append("Origine: preventivo ").append(estimate.number.ifBlank { "GE360" })
                if (estimate.generalDescription.isNotBlank()) append("\n\n").append(estimate.generalDescription)
                if (estimate.materialsIncluded.isNotBlank()) append("\n\nMateriali inclusi: ").append(estimate.materialsIncluded)
                if (estimate.exclusions.isNotBlank()) append("\n\nEsclusioni: ").append(estimate.exclusions)
            }
            jobs.save(job)

            if (jobs.progressItems(job.ge360Id).isEmpty()) {
                estimate.items
                    .filter { it.included && !it.optional && it.category != "Prezzo ambiente" }
                    .forEachIndexed { index, item ->
                        jobs.saveProgressItem(
                            JobsiteProgressItem(
                                jobsiteId = job.ge360Id,
                                phase = item.room.ifBlank { item.section.ifBlank { "Lavorazioni" } },
                                title = item.name,
                                weight = 10,
                                sortOrder = index,
                                note = buildString {
                                    append(fmt(item.quantity)).append(' ').append(item.unit)
                                    if (item.description.isNotBlank()) append("\n").append(item.description)
                                }
                            )
                        )
                    }
            }

            estimate.jobsiteId = job.ge360Id
            estimate.jobsiteCode = job.code
            estimate.jobsiteTitle = job.displayTitle()
            estimates.save(estimate)
            EstimateLifecycleUi.recordSaved(context, estimate, "Preventivo collegato al cantiere ${job.code}")
            onApplied()
            toast(context, "Cantiere ${job.code} collegato al preventivo")
        } finally {
            jobs.close()
            estimates.close()
        }
    }

    private fun shareText(context: Context, text: String, subject: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, subject).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    private fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private fun fmt(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.ITALY, "%.2f", v)
    private fun euro(v: Double): String = NumberFormat.getCurrencyInstance(Locale.ITALY).format(v)
}

object EstimatePresentationFormatter {
    fun build(record: EstimateRecord, mode: String): String {
        val totals = recalculateEstimate(record)
        val included = record.items.filter { it.included && !it.optional }
        val bodyCodes = included.filter { it.category == "Prezzo ambiente" }.associateBy { it.room }
        val grouped = included.filterNot { it.category == "Prezzo ambiente" }.groupBy { it.room.ifBlank { it.section.ifBlank { "Lavorazioni" } } }
        val workText = when (mode) {
            EstimatePresentationModes.BODY -> grouped.entries.joinToString("\n\n") { (room, rows) ->
                val body = bodyCodes[room]
                buildString {
                    append(room.uppercase(Locale.ITALY))
                    if (body != null) append(" — ").append(euro(body.unitPrice))
                    append("\n")
                    rows.forEach { append("• ").append(it.name).append('\n') }
                }.trimEnd()
            }
            EstimatePresentationModes.SYNTHETIC -> grouped.entries.joinToString("\n\n") { (room, rows) ->
                buildString {
                    append(room.uppercase(Locale.ITALY)).append('\n')
                    rows.forEach { item ->
                        append("• ").append(item.name)
                        if (bodyCodes[room] == null) append(" — ").append(priceText(item))
                        append('\n')
                    }
                    bodyCodes[room]?.let { append("Totale ambiente: ").append(euro(it.unitPrice)) }
                }.trimEnd()
            }
            else -> included.groupBy { it.room.ifBlank { it.section.ifBlank { "Lavorazioni" } } }.entries.joinToString("\n\n") { (room, rows) ->
                room.uppercase(Locale.ITALY) + "\n" + rows.joinToString("\n\n") { item ->
                    buildString {
                        append("• ").append(item.name).append(" — ").append(priceText(item))
                        if (item.description.isNotBlank()) append("\n  ").append(item.description.replace("\n", "\n  "))
                    }
                }
            }
        }.ifBlank { "Nessuna lavorazione inserita" }

        return buildString {
            append("PREVENTIVO ").append(record.number.ifBlank { "GE360" }).append('\n')
            append("Cliente: ").append(record.clientName.ifBlank { "—" }).append('\n')
            append("Titolo: ").append(record.title.ifBlank { record.workType }).append("\n\n")
            if (record.generalDescription.isNotBlank()) append(record.generalDescription).append("\n\n")
            append(workText)
            append("\n\nTOTALE: ").append(euro(totals.finalTotal))
            if (record.materialsIncluded.isNotBlank()) append("\n\nMateriali inclusi:\n").append(record.materialsIncluded)
            if (record.materialsClient.isNotBlank()) append("\n\nMateriali a carico cliente:\n").append(record.materialsClient)
            if (record.exclusions.isNotBlank()) append("\n\nEsclusioni:\n").append(record.exclusions)
            if (record.finalNotes.isNotBlank()) append("\n\nNote:\n").append(record.finalNotes)
        }
    }

    private fun priceText(item: EstimateItem): String = if (item.priceMode == "body" || item.unit in listOf("corpo", "forfait")) {
        euro(item.unitPrice)
    } else {
        "${fmt(item.quantity)} ${item.unit} × ${euro(item.unitPrice)} = ${euro(item.total)}"
    }

    private fun fmt(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.ITALY, "%.2f", v)
    private fun euro(v: Double): String = NumberFormat.getCurrencyInstance(Locale.ITALY).format(v)
}
