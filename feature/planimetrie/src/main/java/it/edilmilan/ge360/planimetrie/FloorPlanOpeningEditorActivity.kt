package it.edilmilan.ge360.planimetrie

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class FloorPlanOpeningEditorActivity : AppCompatActivity() {
    private lateinit var store: FloorPlanStore
    private lateinit var wall: FloorPlanWall
    private lateinit var preview: WallProfileView
    private lateinit var summary: TextView
    private lateinit var listRoot: LinearLayout

    private val planId by lazy { intent.getStringExtra(EXTRA_PLAN_ID).orEmpty() }
    private val roomId by lazy { intent.getStringExtra(EXTRA_ROOM_ID).orEmpty() }
    private val wallId by lazy { intent.getStringExtra(EXTRA_WALL_ID).orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = FloorPlanStore(applicationContext)
        wall = store.walls(roomId).firstOrNull { it.ge360Id == wallId } ?: run { finish(); return }
        setContentView(buildUi())
        refresh()
    }

    override fun onDestroy() {
        store.close()
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(30))
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(MaterialButton(this@FloorPlanOpeningEditorActivity).apply {
                text = "‹"
                minWidth = dp(48)
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(54), dp(48)))
            addView(LinearLayout(this@FloorPlanOpeningEditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@FloorPlanOpeningEditorActivity).apply {
                    text = "Porte / finestre"
                    textSize = 22f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#202327"))
                })
                addView(TextView(this@FloorPlanOpeningEditorActivity).apply {
                    text = "Parete ${fmt(wall.length)} m · ${WallDirections.label(wall.direction)}"
                    textSize = 12f
                    setTextColor(Color.parseColor("#6D7278"))
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            addView(MaterialButton(this@FloorPlanOpeningEditorActivity).apply {
                text = "Fine"
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(78), dp(48)))
        })

        preview = WallProfileView(this)
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)).apply { topMargin = dp(14) })

        summary = TextView(this).apply {
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#30343A"))
            setPadding(0, dp(10), 0, dp(8))
        }
        root.addView(summary)

        root.addView(TextView(this).apply {
            text = "La distanza è misurata dall'inizio della parete. Porte e finestre sono solo elementi del rilievo: NON vengono sottratte dai m² lordi delle pareti."
            textSize = 11f
            setTextColor(Color.parseColor("#74797F"))
            setPadding(0, 0, 0, dp(12))
        })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(MaterialButton(this@FloorPlanOpeningEditorActivity).apply {
                text = "+ Porta"
                setOnClickListener { openingDialog(null, OpeningTypes.DOOR) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(5) })
            addView(MaterialButton(this@FloorPlanOpeningEditorActivity).apply {
                text = "+ Finestra"
                setOnClickListener { openingDialog(null, OpeningTypes.WINDOW) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(5) })
        })

        listRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listRoot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        return ScrollView(this).apply { addView(root) }
    }

    private fun refresh() {
        wall = store.walls(roomId).firstOrNull { it.ge360Id == wallId } ?: wall
        val rows = FloorPlanWallOpenings.openings(wall.note).sortedBy { it.offset }
        preview.walls = listOf(wall.copy(sortOrder = 0))
        summary.text = if (rows.isEmpty()) {
            "Nessuna apertura · parete ${fmt(wall.length)} m"
        } else {
            "${rows.size} aperture · ${FloorPlanWallOpenings.countLabel(wall.note)} · parete ${fmt(wall.length)} m"
        }
        render(rows)
    }

    private fun render(rows: List<FloorPlanOpening>) {
        listRoot.removeAllViews()
        if (rows.isEmpty()) {
            listRoot.addView(TextView(this).apply {
                text = "Aggiungi una porta o una finestra e indica dove si trova lungo questa parete."
                textSize = 13f
                setTextColor(Color.parseColor("#74797F"))
                setPadding(dp(4), dp(10), dp(4), dp(10))
            })
            return
        }

        rows.forEach { opening ->
            listRoot.addView(MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = Color.parseColor("#E4E6E8")
                setCardBackgroundColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                setOnClickListener { openingActions(opening) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                addView(LinearLayout(this@FloorPlanOpeningEditorActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(12), dp(12))
                    addView(LinearLayout(this@FloorPlanOpeningEditorActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(this@FloorPlanOpeningEditorActivity).apply {
                            text = opening.type
                            textSize = 15f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(Color.parseColor("#202327"))
                        })
                        addView(TextView(this@FloorPlanOpeningEditorActivity).apply {
                            text = opening.label().substringAfter(" · ", opening.label())
                            textSize = 12f
                            setTextColor(Color.parseColor("#6D7278"))
                            setPadding(0, dp(3), 0, 0)
                        })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(this@FloorPlanOpeningEditorActivity).apply {
                        text = "›"
                        textSize = 24f
                        setTextColor(Color.parseColor("#B85700"))
                    })
                })
            })
        }
    }

    private fun openingActions(opening: FloorPlanOpening) {
        AlertDialog.Builder(this)
            .setTitle(opening.type)
            .setItems(arrayOf("Modifica", "Elimina")) { _, which ->
                if (which == 0) openingDialog(opening, opening.type)
                else {
                    val rows = FloorPlanWallOpenings.openings(wall.note).filterNot { it.id == opening.id }
                    saveRows(rows)
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun openingDialog(existing: FloorPlanOpening?, requestedType: String) {
        val opening = existing ?: FloorPlanOpening(
            type = requestedType,
            width = if (requestedType == OpeningTypes.WINDOW) 1.2 else 0.8,
            height = if (requestedType == OpeningTypes.WINDOW) 1.4 else 2.1,
            sillHeight = if (requestedType == OpeningTypes.WINDOW) 0.9 else 0.0
        )

        fun number(hintText: String, value: Double) = EditText(this).apply {
            hint = hintText
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (value > 0.0) setText(fmt(value))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(8))
        }
        val offset = number("Distanza dall'inizio parete m", opening.offset)
        val width = number("Larghezza apertura m", opening.width)
        val height = number("Altezza apertura m", opening.height)
        val sill = number("Davanzale da pavimento m", opening.sillHeight)
        val swing = Spinner(this).apply {
            adapter = ArrayAdapter(this@FloorPlanOpeningEditorActivity, android.R.layout.simple_spinner_dropdown_item, DoorSwings.ALL)
            setSelection(DoorSwings.ALL.indexOf(opening.swing).coerceAtLeast(0))
        }
        val note = EditText(this).apply {
            hint = "Nota facoltativa"
            isSingleLine = true
            setText(opening.note)
        }

        root.addView(TextView(this).apply {
            text = "Parete ${fmt(wall.length)} m. L'apertura deve stare interamente dentro la parete e non sovrapporsi alle altre."
            textSize = 11f
            setTextColor(Color.parseColor("#74797F"))
            setPadding(0, dp(3), 0, dp(8))
        })
        root.addView(offset, lp(52))
        root.addView(width, lp(52))
        root.addView(height, lp(52))
        if (requestedType == OpeningTypes.WINDOW) root.addView(sill, lp(52))
        if (requestedType == OpeningTypes.DOOR) root.addView(swing, lp(52))
        root.addView(note, lp(52))

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nuova $requestedType" else "Modifica $requestedType")
            .setView(root)
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun p(field: EditText): Double = field.text.toString().trim().replace(',', '.').toDoubleOrNull() ?: 0.0
                val updated = opening.copy(
                    type = requestedType,
                    offset = p(offset),
                    width = p(width),
                    height = p(height),
                    sillHeight = if (requestedType == OpeningTypes.WINDOW) p(sill) else 0.0,
                    swing = if (requestedType == OpeningTypes.DOOR) swing.selectedItem.toString() else DoorSwings.NONE,
                    note = note.text.toString().trim()
                )
                val error = FloorPlanWallOpenings.validate(updated, wall.length)
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val current = FloorPlanWallOpenings.openings(wall.note)
                val conflict = FloorPlanWallOpenings.overlapConflict(updated, current)
                if (conflict != null) {
                    Toast.makeText(this, "L'apertura si sovrappone a ${conflict.type.lowercase()} da ${fmt(conflict.offset)} m", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val rows = current.toMutableList()
                val index = rows.indexOfFirst { it.id == updated.id }
                if (index >= 0) rows[index] = updated else rows += updated
                saveRows(rows.sortedBy { it.offset })
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun saveRows(rows: List<FloorPlanOpening>) {
        wall.note = FloorPlanWallOpenings.attach(FloorPlanWallOpenings.userNote(wall.note), rows)
        store.saveWall(wall)
        refresh()
    }

    private fun lp(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)).apply { bottomMargin = dp(7) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PLAN_ID = "floor_plan_id"
        const val EXTRA_ROOM_ID = "floor_plan_room_id"
        const val EXTRA_WALL_ID = "floor_plan_wall_id"
    }
}
