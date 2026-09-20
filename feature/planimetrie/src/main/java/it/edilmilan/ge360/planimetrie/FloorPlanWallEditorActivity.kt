package it.edilmilan.ge360.planimetrie

import android.content.Intent
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

class FloorPlanWallEditorActivity : AppCompatActivity() {
    private lateinit var store: FloorPlanStore
    private lateinit var room: FloorPlanRoom
    private lateinit var preview: WallProfileView
    private lateinit var summary: TextView
    private lateinit var wallsRoot: LinearLayout

    private val planId: String by lazy { intent.getStringExtra(EXTRA_PLAN_ID).orEmpty() }
    private val roomId: String by lazy { intent.getStringExtra(EXTRA_ROOM_ID).orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = FloorPlanStore(applicationContext)
        room = store.rooms(planId).firstOrNull { it.ge360Id == roomId } ?: run {
            finish()
            return
        }
        setContentView(buildUi())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) refresh()
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
            addView(MaterialButton(this@FloorPlanWallEditorActivity).apply {
                text = "‹"
                minWidth = dp(48)
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(54), dp(48)))
            addView(LinearLayout(this@FloorPlanWallEditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@FloorPlanWallEditorActivity).apply {
                    text = room.name
                    textSize = 22f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#202327"))
                })
                addView(TextView(this@FloorPlanWallEditorActivity).apply {
                    text = "Pareti quotate · snap 90°"
                    textSize = 12f
                    setTextColor(Color.parseColor("#6D7278"))
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            addView(MaterialButton(this@FloorPlanWallEditorActivity).apply {
                text = "Fine"
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(78), dp(48)))
        })

        preview = WallProfileView(this)
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(350)).apply { topMargin = dp(14) })

        summary = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#30343A"))
            setPadding(dp(2), dp(12), dp(2), dp(10))
        }
        root.addView(summary)

        root.addView(TextView(this).apply {
            text = "Inserisci i muri in ordine. Ogni nuova parete parte dalla precedente. Tocca una parete per porte e finestre. Le aperture restano comprese nei m² lordi."
            textSize = 11f
            setTextColor(Color.parseColor("#74797F"))
            setPadding(dp(2), 0, dp(2), dp(12))
        })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(MaterialButton(this@FloorPlanWallEditorActivity).apply {
                text = "+ Parete"
                setOnClickListener { wallDialog(null) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(5) })
            addView(MaterialButton(this@FloorPlanWallEditorActivity).apply {
                text = "Chiudi stanza"
                setOnClickListener { closeProfile() }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(5) })
        })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(MaterialButton(this@FloorPlanWallEditorActivity).apply {
                text = "Da rettangolo"
                setOnClickListener { generateRectangle() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5); topMargin = dp(8) })
            addView(MaterialButton(this@FloorPlanWallEditorActivity).apply {
                text = "Svuota"
                setOnClickListener { clearProfile() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5); topMargin = dp(8) })
        })

        root.addView(TextView(this).apply {
            text = "PARETI"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#767B82"))
            setPadding(0, dp(20), 0, dp(8))
        })

        wallsRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(wallsRoot)

        return ScrollView(this).apply { addView(root) }
    }

    private fun refresh() {
        room = store.rooms(planId).firstOrNull { it.ge360Id == roomId } ?: room
        val walls = store.walls(room.ge360Id)
        if (walls.isNotEmpty()) room = store.syncRoomFromWalls(room)
        preview.walls = walls
        val valid = FloorPlanWallGeometry.isValidClosedProfile(walls)
        val openings = walls.sumOf { FloorPlanWallOpenings.openings(it.note).size }
        summary.text = buildString {
            append(FloorPlanWallGeometry.description(walls))
            if (openings > 0) append(" · ").append(openings).append(if (openings == 1) " apertura" else " aperture")
            if (valid && room.height > 0.0) append(" · pareti lorde ${fmt(room.wallArea())} m²")
            if (room.height > 0.0) append(" · H ${fmt(room.height)} m")
        }
        summary.setTextColor(Color.parseColor(if (valid) "#277A49" else "#B85700"))
        renderWalls(walls)
    }

    private fun renderWalls(walls: List<FloorPlanWall>) {
        wallsRoot.removeAllViews()
        if (walls.isEmpty()) {
            wallsRoot.addView(TextView(this).apply {
                text = "Nessuna parete. Premi + Parete, oppure “Da rettangolo” se hai già L × W."
                textSize = 13f
                setTextColor(Color.parseColor("#74797F"))
                setPadding(dp(4), dp(10), dp(4), dp(10))
            })
            return
        }

        walls.sortedBy { it.sortOrder }.forEachIndexed { index, wall ->
            val openingLabel = FloorPlanWallOpenings.countLabel(wall.note)
            val userNote = FloorPlanWallOpenings.userNote(wall.note)
            wallsRoot.addView(MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = Color.parseColor("#E4E6E8")
                setCardBackgroundColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                setOnClickListener { wallActions(wall) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(7)
                }
                addView(LinearLayout(this@FloorPlanWallEditorActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(12), dp(12))
                    addView(LinearLayout(this@FloorPlanWallEditorActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(this@FloorPlanWallEditorActivity).apply {
                            text = "Parete ${index + 1} · ${fmt(wall.length)} m"
                            textSize = 15f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(Color.parseColor("#202327"))
                        })
                        addView(TextView(this@FloorPlanWallEditorActivity).apply {
                            text = buildList {
                                add(WallDirections.label(wall.direction))
                                if (openingLabel.isNotBlank()) add(openingLabel)
                                if (userNote.isNotBlank()) add(userNote)
                            }.joinToString(" · ")
                            textSize = 12f
                            setTextColor(Color.parseColor(if (openingLabel.isBlank()) "#6D7278" else "#B85700"))
                            setPadding(0, dp(3), 0, 0)
                        })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(this@FloorPlanWallEditorActivity).apply {
                        text = "›"
                        textSize = 24f
                        setTextColor(Color.parseColor("#B85700"))
                    })
                })
            })
        }
    }

    private fun wallDialog(existing: FloorPlanWall?) {
        val walls = store.walls(room.ge360Id)
        val wall = existing?.copy() ?: FloorPlanWall(
            floorPlanId = room.floorPlanId,
            roomId = room.ge360Id,
            direction = FloorPlanWallGeometry.suggestedNextDirection(walls),
            sortOrder = walls.size
        )
        val existingOpenings = FloorPlanWallOpenings.openings(wall.note)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(8))
        }
        val length = EditText(this).apply {
            hint = "Lunghezza parete in metri"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (wall.length > 0.0) setText(fmt(wall.length))
        }
        val direction = Spinner(this).apply {
            adapter = ArrayAdapter(this@FloorPlanWallEditorActivity, android.R.layout.simple_spinner_dropdown_item, WallDirections.LABELS)
            val normalized = WallDirections.normalized(wall.direction)
            setSelection(WallDirections.ALL.indexOf(normalized).coerceAtLeast(0))
        }
        val note = EditText(this).apply {
            hint = "Nota facoltativa"
            isSingleLine = true
            setText(FloorPlanWallOpenings.userNote(wall.note))
        }
        root.addView(TextView(this).apply {
            text = "Lunghezza + direzione. La parete parte automaticamente dal punto finale della precedente."
            textSize = 11f
            setTextColor(Color.parseColor("#74797F"))
            setPadding(0, dp(3), 0, dp(8))
        })
        root.addView(length, lp(52))
        root.addView(direction, lp(52))
        root.addView(note, lp(52))

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Aggiungi parete" else "Modifica parete")
            .setView(root)
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val meters = length.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (meters == null || meters <= 0.0) {
                    length.error = "Inserisci una lunghezza maggiore di zero"
                    return@setOnClickListener
                }
                val invalid = existingOpenings.firstOrNull { FloorPlanWallOpenings.validate(it, meters) != null }
                if (invalid != null) {
                    Toast.makeText(
                        this,
                        "Prima sposta o ridimensiona ${invalid.type.lowercase()}: con ${fmt(meters)} m uscirebbe dalla parete",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                wall.length = meters
                wall.direction = WallDirections.fromLabel(direction.selectedItem.toString())
                wall.note = FloorPlanWallOpenings.attach(note.text.toString().trim(), existingOpenings)

                val candidate = walls.toMutableList()
                val index = candidate.indexOfFirst { it.ge360Id == wall.ge360Id }
                if (index >= 0) candidate[index] = wall else candidate += wall
                if (FloorPlanWallGeometry.hasSelfIntersections(candidate)) {
                    Toast.makeText(this, "Questa parete incrocia un altro muro. Cambia lunghezza o direzione.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                store.saveWall(wall)
                store.syncRoomFromWalls(room)
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    private fun wallActions(wall: FloorPlanWall) {
        AlertDialog.Builder(this)
            .setTitle("${fmt(wall.length)} m · ${WallDirections.label(wall.direction)}")
            .setItems(arrayOf("Modifica parete", "Porte / finestre", "Elimina parete")) { _, which ->
                when (which) {
                    0 -> wallDialog(wall)
                    1 -> openOpeningEditor(wall)
                    else -> confirmDeleteWall(wall)
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun confirmDeleteWall(wall: FloorPlanWall) {
        val openings = FloorPlanWallOpenings.openings(wall.note).size
        AlertDialog.Builder(this)
            .setTitle("Eliminare la parete?")
            .setMessage(buildString {
                append("La geometria della stanza cambierà.")
                if (openings > 0) append(" Verranno eliminate anche ").append(openings).append(if (openings == 1) " apertura." else " aperture.")
            })
            .setPositiveButton("Elimina") { _, _ ->
                val remaining = store.walls(room.ge360Id).filterNot { it.ge360Id == wall.ge360Id }
                store.replaceWalls(room, remaining)
                refresh()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun openOpeningEditor(wall: FloorPlanWall) {
        startActivity(Intent(this, FloorPlanOpeningEditorActivity::class.java).apply {
            putExtra(FloorPlanOpeningEditorActivity.EXTRA_PLAN_ID, planId)
            putExtra(FloorPlanOpeningEditorActivity.EXTRA_ROOM_ID, roomId)
            putExtra(FloorPlanOpeningEditorActivity.EXTRA_WALL_ID, wall.ge360Id)
        })
    }

    private fun closeProfile() {
        val current = store.walls(room.ge360Id)
        if (current.size < 2) {
            Toast.makeText(this, "Inserisci almeno due pareti prima della chiusura", Toast.LENGTH_LONG).show()
            return
        }
        if (FloorPlanWallGeometry.isValidClosedProfile(current)) {
            Toast.makeText(this, "La stanza è già chiusa", Toast.LENGTH_SHORT).show()
            return
        }
        if (FloorPlanWallGeometry.isClosed(current)) {
            Toast.makeText(this, "Il profilo torna all'origine ma non è valido: correggi i muri incrociati.", Toast.LENGTH_LONG).show()
            return
        }
        val closing = FloorPlanWallGeometry.closureSegments(current, room.floorPlanId, room.ge360Id)
        if (closing.isEmpty()) {
            Toast.makeText(this, "Il profilo è già sul punto iniziale", Toast.LENGTH_SHORT).show()
            return
        }
        val all = current + closing
        if (FloorPlanWallGeometry.hasSelfIntersections(all)) {
            Toast.makeText(this, "La chiusura automatica attraverserebbe un altro muro. Correggi prima il profilo.", Toast.LENGTH_LONG).show()
            return
        }
        if (!FloorPlanWallGeometry.isValidClosedProfile(all)) {
            Toast.makeText(this, "Non è possibile creare una stanza valida con queste pareti.", Toast.LENGTH_LONG).show()
            return
        }
        store.replaceWalls(room, all)
        val saved = store.walls(room.ge360Id)
        Toast.makeText(this, "Stanza chiusa · ${fmt(FloorPlanWallGeometry.area(saved))} m²", Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun generateRectangle() {
        if (room.length <= 0.0 || room.width <= 0.0) {
            Toast.makeText(this, "Prima inserisci lunghezza e larghezza nell'ambiente", Toast.LENGTH_LONG).show()
            return
        }
        val rows = listOf(
            FloorPlanWall(floorPlanId = room.floorPlanId, roomId = room.ge360Id, length = room.length, direction = WallDirections.EAST, sortOrder = 0),
            FloorPlanWall(floorPlanId = room.floorPlanId, roomId = room.ge360Id, length = room.width, direction = WallDirections.SOUTH, sortOrder = 1),
            FloorPlanWall(floorPlanId = room.floorPlanId, roomId = room.ge360Id, length = room.length, direction = WallDirections.WEST, sortOrder = 2),
            FloorPlanWall(floorPlanId = room.floorPlanId, roomId = room.ge360Id, length = room.width, direction = WallDirections.NORTH, sortOrder = 3)
        )
        if (store.walls(room.ge360Id).isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Sostituire le pareti?")
                .setMessage("Le pareti attuali e le relative porte/finestre verranno sostituite con il rettangolo ${fmt(room.length)} × ${fmt(room.width)} m.")
                .setPositiveButton("Sostituisci") { _, _ -> store.replaceWalls(room, rows); refresh() }
                .setNegativeButton("Annulla", null)
                .show()
        } else {
            store.replaceWalls(room, rows)
            refresh()
        }
    }

    private fun clearProfile() {
        if (store.walls(room.ge360Id).isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Svuotare le pareti quotate?")
            .setMessage("Vengono rimosse pareti, porte e finestre. Nome, altezza e posizione dell'ambiente restano disponibili.")
            .setPositiveButton("Svuota") { _, _ ->
                store.clearWalls(room.ge360Id)
                room.mode = FloorPlanRoomModes.WALLS
                room.manualFloor = 0.0
                room.manualCeiling = 0.0
                room.manualWalls = 0.0
                room.manualPerimeter = 0.0
                store.saveRoom(room)
                refresh()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun lp(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)).apply {
        bottomMargin = dp(7)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PLAN_ID = "floor_plan_id"
        const val EXTRA_ROOM_ID = "floor_plan_room_id"
    }
}
