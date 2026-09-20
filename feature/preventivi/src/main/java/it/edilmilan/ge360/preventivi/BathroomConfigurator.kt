package it.edilmilan.ge360.preventivi

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

/**
 * Configuratore unico per la ristrutturazione del bagno.
 *
 * Filosofia:
 * - lavorazioni normalmente presenti: già attive ma sempre disattivabili;
 * - scelte alternative: obbligatorie e mutuamente esclusive;
 * - lavorazioni fuori standard: disattivate finché l'utente non le abilita;
 * - nessun prezzo viene imposto dal configuratore;
 * - riaprendo il configuratore vengono preservati prezzi e modifiche delle voci ancora selezionate.
 */
data class BathroomWorkLine(
    val code: String,
    val title: String,
    val description: String,
    val unit: String,
    val category: String,
    val section: String = "Bagno",
    val references: List<String> = emptyList(),
    val enabledByDefault: Boolean = true
) {
    fun toEstimateItem(): EstimateItem = EstimateItem(
        ge360Id = UUID.randomUUID().toString(),
        priceListCode = code,
        section = section,
        room = "Bagno",
        category = category,
        name = title,
        description = buildString {
            append(description.trim())
            if (references.isNotEmpty()) {
                append("\n\nRiferimenti tecnici, ove applicabili: ")
                append(references.joinToString("; "))
                append('.')
            }
        },
        quantity = 1.0,
        unit = unit,
        unitPrice = 0.0,
        priceMode = if (unit in listOf("corpo", "forfait")) "body" else "unit",
        included = true,
        optional = false
    )
}

object BathroomWorkCatalog {
    const val VERSION = "2026.09.1"
    const val PREFIX = "BAGNO-"

    private fun cap(code: String, enabledByDefault: Boolean = true): BathroomWorkLine {
        val entry = requireNotNull(StandardWorkCatalog.byCode(code)) { "Voce capitolato mancante: $code" }
        return BathroomWorkLine(
            code = entry.code,
            title = entry.title,
            description = entry.description,
            unit = entry.unit,
            category = entry.group,
            section = "Bagno",
            references = entry.references,
            enabledByDefault = enabledByDefault
        )
    }

    val demolitionBath = BathroomWorkLine(
        code = "BAGNO-DEM-VASCA",
        title = "Rimozione della vasca esistente",
        description = "Rimozione controllata della vasca esistente e delle parti di rivestimento o muratura strettamente necessarie; separazione dei materiali, raccolta del materiale di risulta e preparazione della zona per le lavorazioni successive.",
        unit = "corpo",
        category = "Demolizioni bagno"
    )

    val demolitionShower = BathroomWorkLine(
        code = "BAGNO-DEM-DOCCIA",
        title = "Rimozione della doccia esistente",
        description = "Rimozione controllata del piatto doccia esistente e delle parti di rivestimento o muratura strettamente necessarie; separazione dei materiali, raccolta del materiale di risulta e preparazione della zona per le lavorazioni successive.",
        unit = "corpo",
        category = "Demolizioni bagno"
    )

    val floorDemolition = BathroomWorkLine(
        code = "BAGNO-PAV-DEM",
        title = "Demolizione del pavimento in piastrelle",
        description = "Demolizione controllata delle piastrelle del pavimento esistente, rimozione dei residui di adesivo incoerenti, raccolta del materiale di risulta e pulizia iniziale del fondo prima della verifica e regolarizzazione del supporto.",
        unit = "m²",
        category = "Pavimento bagno"
    )

    val floorOverlay = BathroomWorkLine(
        code = "BAGNO-PAV-SOVR",
        title = "Preparazione pavimento per posa in sovrapposizione",
        description = "Verifica della stabilità e adesione delle piastrelle esistenti; rimozione o ripristino degli elementi non idonei; pulizia accurata, sgrassaggio e rimozione di cere, detergenti, polvere e contaminanti; eventuale irruvidimento quando richiesto dal sistema di posa; applicazione di primer o promotore di adesione/aggrappante chimico compatibile con il supporto, con successivo rispetto dei consumi e dei tempi tecnici indicati dal produttore prima della posa delle nuove piastrelle.",
        unit = "m²",
        category = "Pavimento bagno",
        references = listOf("UNI 11493-1:2025", "UNI EN 12004-1:2017")
    )

    val baseLines: List<BathroomWorkLine> = listOf(
        cap("CAP-01"),
        BathroomWorkLine(
            code = "BAGNO-DEM-RIV",
            title = "Rimozione dei rivestimenti murari esistenti",
            description = "Demolizione controllata delle piastrelle e dei rivestimenti murari previsti, rimozione dei residui incoerenti, raccolta progressiva del materiale e prima pulizia del supporto.",
            unit = "m²",
            category = "Demolizioni bagno"
        ),
        BathroomWorkLine(
            code = "BAGNO-DEM-SAN",
            title = "Rimozione sanitari esistenti",
            description = "Rimozione dei sanitari e degli accessori previsti, movimentazione nell'area di cantiere, raccolta e predisposizione per il successivo trasporto e smaltimento secondo quanto previsto dal preventivo.",
            unit = "corpo",
            category = "Demolizioni bagno"
        ),
        cap("CAP-03"),
        cap("CAP-04"),
        cap("CAP-05"),
        cap("CAP-07"),
        cap("CAP-20"),
        cap("CAP-21"),
        cap("CAP-14"),
        cap("CAP-08"),
        cap("CAP-09"),
        cap("CAP-41"),
        cap("CAP-42")
    )

    val extraLines: List<BathroomWorkLine> = listOf(
        cap("CAP-22", false),
        cap("CAP-23", false),
        cap("CAP-24", false),
        cap("CAP-27", false),
        cap("CAP-33", false),
        cap("CAP-34", false),
        cap("CAP-15", false),
        cap("CAP-16", false),
        cap("CAP-17", false),
        cap("CAP-10", false),
        cap("CAP-37", false),
        cap("CAP-38", false),
        cap("CAP-39", false),
        cap("CAP-40", false)
    )

    fun floorLines(choice: BathroomFloorChoice): List<BathroomWorkLine> = when (choice) {
        BathroomFloorChoice.DEMOLISH -> listOf(floorDemolition, cap("CAP-12"))
        BathroomFloorChoice.OVERLAY -> listOf(floorOverlay)
    }

    fun wetAreaLine(choice: BathroomExistingWetArea): BathroomWorkLine = when (choice) {
        BathroomExistingWetArea.BATH -> demolitionBath
        BathroomExistingWetArea.SHOWER -> demolitionShower
    }

    val managedCodes: Set<String> by lazy {
        (baseLines + extraLines + listOf(demolitionBath, demolitionShower, floorDemolition, floorOverlay, cap("CAP-12")))
            .map { it.code }
            .toSet()
    }

    fun referencesFor(items: List<EstimateItem>): List<String> {
        val codes = items.map { it.priceListCode }.toSet()
        val all = baseLines + extraLines + listOf(demolitionBath, demolitionShower, floorDemolition, floorOverlay, cap("CAP-12"))
        return all.filter { it.code in codes }.flatMap { it.references }.distinct()
    }
}

enum class BathroomExistingWetArea { BATH, SHOWER }
enum class BathroomFloorChoice { DEMOLISH, OVERLAY }

object BathroomConfigurator {
    fun show(context: Context, estimate: EstimateRecord, onApplied: () -> Unit) {
        if (estimate.workType != "Ristrutturazione bagno") {
            GuidedWorkConfigurator.openForEstimate(context, estimate, onApplied)
            return
        }

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun heading(text: String) = TextView(context).apply {
            this.text = text
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        }
        fun note(text: String) = TextView(context).apply {
            this.text = text
            textSize = 11f
            setPadding(0, 0, 0, dp(6))
        }

        val existingByCode = estimate.items.associateBy { it.priceListCode }
        val hasPreviousConfiguration = estimate.items.any { it.priceListCode in BathroomWorkCatalog.managedCodes }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
        }

        root.addView(heading("1. Cosa c'è adesso nella zona doccia/vasca?"))
        root.addView(note("Scelta obbligatoria: nel preventivo entra una sola demolizione."))
        val wetOptions = listOf("Seleziona…", "Vasca esistente", "Doccia esistente")
        val wetSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, wetOptions)
            setSelection(
                when {
                    existingByCode.containsKey(BathroomWorkCatalog.demolitionBath.code) -> 1
                    existingByCode.containsKey(BathroomWorkCatalog.demolitionShower.code) -> 2
                    else -> 0
                }
            )
        }
        root.addView(wetSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        root.addView(heading("2. Come viene eseguito il pavimento?"))
        root.addView(note("Scelta obbligatoria: demolizione del pavimento oppure posa in sovrapposizione."))
        val floorOptions = listOf("Seleziona…", "Demolire le piastrelle esistenti", "Posa su posa / sovrapposizione")
        val floorSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, floorOptions)
            setSelection(
                when {
                    existingByCode.containsKey(BathroomWorkCatalog.floorDemolition.code) -> 1
                    existingByCode.containsKey(BathroomWorkCatalog.floorOverlay.code) -> 2
                    else -> 0
                }
            )
        }
        root.addView(floorSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        root.addView(heading("3. Lavorazioni standard"))
        root.addView(note("Sono già attive nel bagno nuovo. Togli la spunta se una lavorazione non serve."))
        val baseChecks = BathroomWorkCatalog.baseLines.map { line ->
            line to CheckBox(context).apply {
                text = line.title
                isChecked = if (hasPreviousConfiguration) existingByCode.containsKey(line.code) else line.enabledByDefault
                setPadding(0, dp(2), 0, dp(2))
                root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

        root.addView(heading("4. Lavorazioni fuori standard / extra"))
        root.addView(note("Sono spente di default. Attivale solo quando fanno realmente parte del lavoro."))
        val extraChecks = BathroomWorkCatalog.extraLines.map { line ->
            line to CheckBox(context).apply {
                text = line.title
                isChecked = existingByCode.containsKey(line.code)
                setPadding(0, dp(2), 0, dp(2))
                root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

        val scroll = ScrollView(context).apply { addView(root) }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Configura bagno")
            .setView(scroll)
            .setPositiveButton("Applica", null)
            .setNegativeButton("Annulla", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val wetChoice = when (wetSpinner.selectedItemPosition) {
                    1 -> BathroomExistingWetArea.BATH
                    2 -> BathroomExistingWetArea.SHOWER
                    else -> null
                }
                if (wetChoice == null) {
                    Toast.makeText(context, "Scegli se nel bagno c'è una vasca o una doccia da rimuovere", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val floorChoice = when (floorSpinner.selectedItemPosition) {
                    1 -> BathroomFloorChoice.DEMOLISH
                    2 -> BathroomFloorChoice.OVERLAY
                    else -> null
                }
                if (floorChoice == null) {
                    Toast.makeText(context, "Scegli se demolire il pavimento o posare in sovrapposizione", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val selected = buildList {
                    add(BathroomWorkCatalog.wetAreaLine(wetChoice))
                    addAll(BathroomWorkCatalog.floorLines(floorChoice))
                    addAll(baseChecks.filter { it.second.isChecked }.map { it.first })
                    addAll(extraChecks.filter { it.second.isChecked }.map { it.first })
                }.distinctBy { it.code }

                val selectedCodes = selected.map { it.code }.toSet()
                val preserved = estimate.items.filterNot { it.priceListCode in BathroomWorkCatalog.managedCodes }.toMutableList()
                selected.forEach { line ->
                    preserved += existingByCode[line.code] ?: line.toEstimateItem()
                }
                estimate.items.clear()
                estimate.items.addAll(preserved)

                dialog.dismiss()
                onApplied()
                Toast.makeText(context, "Bagno aggiornato: ${selectedCodes.size} lavorazioni attive", Toast.LENGTH_LONG).show()
            }
        }
        dialog.show()
    }
}
