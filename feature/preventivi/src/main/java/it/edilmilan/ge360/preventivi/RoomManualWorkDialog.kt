package it.edilmilan.ge360.preventivi

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

/**
 * Lavorazioni completamente libere legate a un singolo ambiente.
 * Non fanno parte della checklist standard e non vengono toccate quando la
 * checklist viene rigenerata. Restano normali EstimateItem, quindi possono
 * essere modificate o eliminate anche dall'editor generale del preventivo.
 *
 * I riferimenti normativi/tecnici vengono suggeriti in base al contenuto della
 * lavorazione ma restano sempre modificabili dall'utente. Una lavorazione può
 * inoltre essere salvata nella libreria personale e riutilizzata in futuro.
 */
object RoomManualWorkDialog {
    const val MANUAL_PREFIX = "ROOM-MANUAL/"

    fun prefixFor(roomId: String): String = "$MANUAL_PREFIX$roomId/"

    fun belongsToRoom(item: EstimateItem, roomId: String): Boolean =
        item.priceListCode.startsWith(prefixFor(roomId))

    fun show(
        context: Context,
        estimate: EstimateRecord,
        room: EstimateRoom,
        preset: SavedWorkTemplate? = null,
        onApplied: () -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun label(text: String) = TextView(context).apply {
            this.text = text
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(2))
        }
        fun single(hintText: String, value: String = "") = EditText(context).apply {
            hint = hintText
            isSingleLine = true
            if (value.isNotBlank()) setText(value)
        }
        fun amount(hintText: String, value: Double = 0.0, fallback: String = "") = EditText(context).apply {
            hint = hintText
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            when {
                value > 0 -> setText(value.toString().replace('.', ','))
                fallback.isNotBlank() -> setText(fallback)
            }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(14))
        }
        val name = single("Es. Ripristino spalletta finestra", preset?.name.orEmpty())
        val category = single("Categoria (facoltativa)", preset?.category.orEmpty())
        val description = EditText(context).apply {
            hint = "Descrizione libera della lavorazione"
            minLines = 4
            gravity = Gravity.TOP
            if (!preset?.description.isNullOrBlank()) setText(preset?.description)
        }
        val quantity = amount("Quantità", fallback = "1")
        val unit = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, EstimateStore.UNITS)
            setSelection(EstimateStore.UNITS.indexOf(preset?.unit ?: "corpo").coerceAtLeast(0))
        }
        val price = amount("Prezzo unitario / a corpo €", preset?.suggestedPrice ?: 0.0)
        val references = EditText(context).apply {
            hint = "Un riferimento per riga; puoi modificarli liberamente"
            minLines = 4
            gravity = Gravity.TOP
            if (!preset?.references.isNullOrEmpty()) setText(WorkReferenceRegistry.formatEditable(preset!!.references))
        }
        val included = CheckBox(context).apply {
            text = "Inclusa nel totale"
            isChecked = true
        }
        val optional = CheckBox(context).apply {
            text = "Opzionale / fuori totale"
            isChecked = false
        }
        val saveAsPersonal = CheckBox(context).apply {
            text = if (preset == null) "Salva anche nelle mie lavorazioni" else "Aggiorna questa lavorazione nella mia libreria"
            isChecked = false
        }

        fun suggestedRefs(): List<String> = WorkReferenceRegistry.suggest(
            name = name.text.toString(),
            category = category.text.toString(),
            description = description.text.toString()
        )

        root.addView(TextView(context).apply {
            text = "${room.name} · lavorazione libera"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(context).apply {
            text = if (room.pricingMode == RoomPricing.BODY) {
                "L'ambiente è a corpo: se questa lavorazione è già compresa nel prezzo della stanza, lascia il prezzo a 0. Se è un extra separato, puoi darle un prezzo proprio."
            } else {
                "Puoi usare corpo, m², ml, cad. o qualsiasi unità disponibile. Quantità e prezzo restano completamente modificabili."
            }
            textSize = 11f
            setPadding(0, dp(3), 0, dp(6))
        })
        root.addView(label("Nome lavorazione")); root.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        root.addView(label("Categoria")); root.addView(category, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        root.addView(label("Descrizione")); root.addView(description, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
        root.addView(label("Quantità")); root.addView(quantity, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        root.addView(label("Unità")); root.addView(unit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        root.addView(label("Prezzo")); root.addView(price, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        root.addView(label("Riferimenti normativi e tecnici"))
        root.addView(TextView(context).apply {
            text = "L'app propone i riferimenti in base alla lavorazione. Sono richiami 'ove applicabili', non dichiarazioni automatiche di conformità. Puoi aggiungere, togliere o correggere qualsiasi riferimento."
            textSize = 11f
        })
        root.addView(Button(context).apply {
            text = "Suggerisci riferimenti"
            setOnClickListener {
                references.setText(WorkReferenceRegistry.formatEditable(suggestedRefs()))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        root.addView(references, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130)))
        root.addView(included)
        root.addView(optional)
        root.addView(saveAsPersonal)

        fun parse(field: EditText): Double = field.text.toString().trim().replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

        val dialog = AlertDialog.Builder(context)
            .setTitle(if (preset == null) "+ Lavorazione manuale" else "+ ${preset.name}")
            .setView(ScrollView(context).apply { addView(root) })
            .setPositiveButton("Aggiungi", null)
            .setNegativeButton("Annulla", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cleanName = name.text.toString().trim()
                if (cleanName.isBlank()) {
                    name.error = "Inserisci il nome della lavorazione"
                    return@setOnClickListener
                }
                val selectedUnit = unit.selectedItem.toString()
                val cleanCategory = category.text.toString().trim().ifBlank { "Lavorazione manuale" }
                val rawDescription = description.text.toString().trim()
                val refs = WorkReferenceRegistry.parseEditable(references.text.toString()).ifEmpty {
                    WorkReferenceRegistry.suggest(cleanName, cleanCategory, rawDescription)
                }
                val fullDescription = WorkReferenceRegistry.appendToDescription(rawDescription, refs)
                val appliedPrice = parse(price)
                val item = EstimateItem(
                    ge360Id = UUID.randomUUID().toString(),
                    priceListCode = prefixFor(room.ge360Id) + UUID.randomUUID().toString().take(8).uppercase(),
                    section = RoomWorkCatalog.sectionFor(room),
                    room = room.name,
                    category = cleanCategory,
                    name = cleanName,
                    description = fullDescription,
                    quantity = parse(quantity),
                    unit = selectedUnit,
                    unitPrice = appliedPrice,
                    priceMode = if (selectedUnit in listOf("corpo", "forfait")) "body" else "unit",
                    included = included.isChecked,
                    optional = optional.isChecked,
                    internalNote = RoomWorkCatalog.attachMetadata("", room, QuantityBasis.MANUAL)
                )
                item.total = calculateItemTotal(item)
                estimate.items += item

                if (saveAsPersonal.isChecked) {
                    PreventiviCustomizationStore(context).saveWork(
                        SavedWorkTemplate(
                            id = preset?.id ?: UUID.randomUUID().toString(),
                            name = cleanName,
                            category = cleanCategory,
                            description = rawDescription,
                            unit = selectedUnit,
                            suggestedPrice = appliedPrice,
                            references = refs
                        )
                    )
                }

                dialog.dismiss()
                onApplied()
                Toast.makeText(
                    context,
                    if (saveAsPersonal.isChecked) "Aggiunta a ${room.name} e salvata nelle tue lavorazioni" else "Aggiunta a ${room.name}: ${item.name}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        dialog.show()
    }
}
