package it.edilmilan.ge360.planimetrie

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
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
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.cantieri.JobsitesActivity
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.preventivi.EstimateRecord
import it.edilmilan.ge360.preventivi.EstimateRoom
import it.edilmilan.ge360.preventivi.EstimateRoomStore
import it.edilmilan.ge360.preventivi.EstimateStatuses
import it.edilmilan.ge360.preventivi.EstimateStore
import it.edilmilan.ge360.preventivi.EstimatesActivity
import it.edilmilan.ge360.preventivi.ManualRoomMeasures
import it.edilmilan.ge360.preventivi.PreventiviCustomizationStore
import it.edilmilan.ge360.preventivi.RoomPricing
import it.edilmilan.ge360.preventivi.RoomTypes
import java.util.UUID

class FloorPlanEditorActivity : AppCompatActivity() {
    private lateinit var store: FloorPlanStore
    private lateinit var plan: FloorPlanRecord
    private lateinit var canvas: FloorPlanCanvasView
    private lateinit var roomRoot: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var totalView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = FloorPlanStore(applicationContext)
        val id = intent.getStringExtra(EXTRA_PLAN_ID).orEmpty()
        plan = store.get(id) ?: run { finish(); return }
        setContentView(buildUi())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized && ::plan.isInitialized && ::canvas.isInitialized) refresh()
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
            addView(MaterialButton(this@FloorPlanEditorActivity).apply {
                text = "‹"
                minWidth = dp(48)
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(54), dp(48)))
            addView(LinearLayout(this@FloorPlanEditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                titleView = TextView(this@FloorPlanEditorActivity).apply {
                    textSize = 22f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Ge360Brand.Text)
                }
                statusView = TextView(this@FloorPlanEditorActivity).apply {
                    textSize = 12f
                    setPadding(0, dp(3), 0, 0)
                }
                addView(titleView)
                addView(statusView)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            addView(MaterialButton(this@FloorPlanEditorActivity).apply {
                text = "⋮"
                minWidth = dp(48)
                setOnClickListener { planMenu() }
            }, LinearLayout.LayoutParams(dp(54), dp(48)))
        })

        canvas = FloorPlanCanvasView(this).apply {
            onRoomMoved = { room -> store.saveRoom(room) }
        }
        root.addView(canvas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)).apply { topMargin = dp(16) })
        root.addView(TextView(this).apply {
            text = "1. Aggiungi gli ambienti  ·  2. trascinali per comporre la pianta  ·  3. collega a preventivo o cantiere. Lo snap allinea automaticamente i muri vicini."
            textSize = 11f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(dp(2), dp(7), dp(2), 0)
        })

        totalView = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.Text)
            setPadding(0, dp(14), 0, dp(8))
        }
        root.addView(totalView)

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(MaterialButton(this@FloorPlanEditorActivity).apply {
                text = "+ Ambiente"
                setOnClickListener { roomDialog(null) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
            addView(MaterialButton(this@FloorPlanEditorActivity).apply {
                text = "Cantiere / Preventivo"
                setOnClickListener { linkMenu() }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        })

        roomRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(roomRoot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        return ScrollView(this).apply { addView(root) }
    }

    private fun refresh() {
        plan = store.get(plan.ge360Id) ?: plan
        val rooms = store.rooms(plan.ge360Id)
        titleView.text = plan.title
        val links = buildList {
            if (plan.jobsiteId.isNotBlank()) add("cantiere")
            if (plan.estimateId.isNotBlank()) add("preventivo")
        }
        statusView.text = if (links.isEmpty()) "Rilievo indipendente · non collegato" else "Collegato a ${links.joinToString(" + ")}"
        statusView.setTextColor(if (links.isEmpty()) Ge360Brand.Accent else Ge360Brand.Success)
        totalView.text = "${rooms.size} ambienti · superficie rilevata ${fmt(rooms.sumOf { it.floorArea() })} m²"
        canvas.rooms = rooms
        canvas.wallProfiles = rooms.associate { it.ge360Id to store.walls(it.ge360Id) }
        renderRooms(rooms)
    }

    private fun renderRooms(rooms: List<FloorPlanRoom>) {
        roomRoot.removeAllViews()
        if (rooms.isEmpty()) {
            roomRoot.addView(TextView(this).apply {
                text = "Nessun ambiente. Premi + Ambiente e scegli il metodo più comodo per il rilievo."
                textSize = 13f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(dp(4), dp(10), dp(4), dp(10))
            })
            return
        }
        rooms.forEach { room ->
            val wallRows = if (room.mode == FloorPlanRoomModes.WALLS) store.walls(room.ge360Id) else emptyList()
            val openings = wallRows.sumOf { FloorPlanWallOpenings.openings(it.note).size }
            roomRoot.addView(MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = Ge360Brand.Border
                setCardBackgroundColor(Ge360Brand.Surface)
                isClickable = true
                isFocusable = true
                setOnClickListener { roomActions(room) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                addView(LinearLayout(this@FloorPlanEditorActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    addView(TextView(this@FloorPlanEditorActivity).apply {
                        text = "${room.name} · ${room.roomType}"
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ge360Brand.Text)
                    })
                    addView(TextView(this@FloorPlanEditorActivity).apply {
                        text = buildString {
                            append(room.summary())
                            if (wallRows.isNotEmpty()) append("\n").append(FloorPlanWallGeometry.description(wallRows))
                            if (openings > 0) append(" · ").append(openings).append(if (openings == 1) " apertura" else " aperture")
                        }
                        textSize = 12f
                        setTextColor(Ge360Brand.TextSecondary)
                        setPadding(0, dp(4), 0, 0)
                    })
                })
            })
        }
    }

    private fun roomActions(room: FloorPlanRoom) {
        val wallLabel = if (room.mode == FloorPlanRoomModes.WALLS) "Apri pareti quotate" else "Pareti quotate"
        val options = arrayOf("Modifica dati ambiente", wallLabel, "Elimina ambiente")
        AlertDialog.Builder(this)
            .setTitle(room.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> roomDialog(room)
                    1 -> openWallEditor(room)
                    2 -> AlertDialog.Builder(this)
                        .setTitle("Eliminare ${room.name}?")
                        .setMessage("Vengono rimossi ambiente, pareti, porte e finestre. Il resto del rilievo non cambia.")
                        .setPositiveButton("Elimina") { _, _ -> store.deleteRoom(room.ge360Id); refresh() }
                        .setNegativeButton("Annulla", null)
                        .show()
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun openWallEditor(room: FloorPlanRoom) {
        startActivity(Intent(this, FloorPlanWallEditorActivity::class.java).apply {
            putExtra(FloorPlanWallEditorActivity.EXTRA_PLAN_ID, plan.ge360Id)
            putExtra(FloorPlanWallEditorActivity.EXTRA_ROOM_ID, room.ge360Id)
        })
    }

    private fun roomDialog(existing: FloorPlanRoom?) {
        val room = existing?.copy() ?: FloorPlanRoom(
            floorPlanId = plan.ge360Id,
            sortOrder = store.rooms(plan.ge360Id).size
        ).also { autoPosition(it) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(8))
        }
        fun number(hint: String, value: Double = 0.0) = EditText(this).apply {
            this.hint = hint
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (value > 0.0) setText(fmt(value))
        }

        val name = EditText(this).apply { hint = "Nome ambiente"; isSingleLine = true; setText(room.name) }
        val types = listOf("Bagno", "Cucina", "Stanza", "Camera", "Corridoio", "Balcone/Terrazzo", "Altro")
        val type = Spinner(this).apply {
            adapter = ArrayAdapter(this@FloorPlanEditorActivity, android.R.layout.simple_spinner_dropdown_item, types)
            setSelection(types.indexOf(room.roomType).coerceAtLeast(0))
        }
        val mode = Spinner(this).apply {
            adapter = ArrayAdapter(this@FloorPlanEditorActivity, android.R.layout.simple_spinner_dropdown_item, FloorPlanRoomModes.ALL)
            setSelection(FloorPlanRoomModes.ALL.indexOf(room.mode).coerceAtLeast(0))
        }
        val height = number("Altezza ambiente m", room.height)
        val helper = TextView(this).apply {
            textSize = 11f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(0, dp(5), 0, dp(8))
        }

        val length = number("Lunghezza m", room.length)
        val width = number("Larghezza m", room.width)
        val sizeGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(length, lp(50))
            addView(width, lp(50))
        }

        val cutW = number("Larghezza rientro m", room.cutWidth)
        val cutH = number("Profondità rientro m", room.cutHeight)
        val lGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(cutW, lp(50))
            addView(cutH, lp(50))
        }

        val floor = number("Pavimento m²", room.manualFloor)
        val ceiling = number("Soffitto m²", room.manualCeiling)
        val walls = number("Pareti lorde m²", room.manualWalls)
        val perimeter = number("Perimetro ml", room.manualPerimeter)
        val manualGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(floor, lp(48))
            addView(ceiling, lp(48))
            addView(walls, lp(48))
            addView(perimeter, lp(48))
        }

        root.addView(name, lp(50))
        root.addView(type, lp(50))
        root.addView(mode, lp(50))
        root.addView(height, lp(50))
        root.addView(helper)
        root.addView(sizeGroup)
        root.addView(lGroup)
        root.addView(manualGroup)

        fun updateModeUi() {
            when (mode.selectedItem?.toString()) {
                FloorPlanRoomModes.RECTANGULAR -> {
                    helper.text = "Metodo veloce: inserisci lunghezza, larghezza e altezza."
                    sizeGroup.visibility = View.VISIBLE
                    lGroup.visibility = View.GONE
                    manualGroup.visibility = View.GONE
                }
                FloorPlanRoomModes.L_SHAPE -> {
                    helper.text = "Inserisci l'ingombro esterno e le due misure del rientro."
                    sizeGroup.visibility = View.VISIBLE
                    lGroup.visibility = View.VISIBLE
                    manualGroup.visibility = View.GONE
                }
                FloorPlanRoomModes.MANUAL -> {
                    helper.text = "Per forme strane: inserisci direttamente le quantità reali. L × W è facoltativo e serve solo all'ingombro grafico."
                    sizeGroup.visibility = View.VISIBLE
                    lGroup.visibility = View.GONE
                    manualGroup.visibility = View.VISIBLE
                }
                else -> {
                    helper.text = "Metodo da cantiere: salva l'ambiente e poi inserisci una parete alla volta. Lunghezza e larghezza verranno calcolate dal profilo."
                    sizeGroup.visibility = View.GONE
                    lGroup.visibility = View.GONE
                    manualGroup.visibility = View.GONE
                }
            }
        }
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateModeUi()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updateModeUi()

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nuovo ambiente" else room.name)
            .setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cleanName = name.text.toString().trim()
                if (cleanName.isBlank()) {
                    name.error = "Inserisci il nome"
                    return@setOnClickListener
                }
                fun p(e: EditText) = e.text.toString().trim().replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                val selectedMode = mode.selectedItem.toString()
                val l = p(length)
                val w = p(width)
                val mf = p(floor)
                if (selectedMode in listOf(FloorPlanRoomModes.RECTANGULAR, FloorPlanRoomModes.L_SHAPE) && (l <= 0.0 || w <= 0.0)) {
                    Toast.makeText(this, "Inserisci lunghezza e larghezza", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (selectedMode == FloorPlanRoomModes.MANUAL && mf <= 0.0 && (l <= 0.0 || w <= 0.0)) {
                    Toast.makeText(this, "Inserisci almeno i m² del pavimento oppure L × W", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                room.name = cleanName
                room.roomType = type.selectedItem.toString()
                room.mode = selectedMode
                room.length = l
                room.width = w
                room.height = p(height)
                room.cutWidth = p(cutW)
                room.cutHeight = p(cutH)
                room.manualFloor = mf
                room.manualCeiling = p(ceiling)
                room.manualWalls = p(walls)
                room.manualPerimeter = p(perimeter)
                if (room.mode == FloorPlanRoomModes.L_SHAPE && (room.cutWidth <= 0.0 || room.cutHeight <= 0.0 || room.cutWidth >= room.length || room.cutHeight >= room.width)) {
                    Toast.makeText(this, "Il rientro deve essere maggiore di zero e più piccolo dell'ingombro", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (room.mode != FloorPlanRoomModes.WALLS || store.walls(room.ge360Id).isEmpty()) {
                    room.drawWidth = room.length.takeIf { it > 0.0 } ?: room.footprintWidth()
                    room.drawHeight = room.width.takeIf { it > 0.0 } ?: room.footprintHeight()
                }
                store.saveRoom(room)
                if (room.mode == FloorPlanRoomModes.WALLS && store.walls(room.ge360Id).isNotEmpty()) store.syncRoomFromWalls(room)
                plan.updatedAt = System.currentTimeMillis()
                store.save(plan)
                dialog.dismiss()
                refresh()
                if (selectedMode == FloorPlanRoomModes.WALLS) openWallEditor(room)
            }
        }
        dialog.show()
    }

    private fun autoPosition(room: FloorPlanRoom) {
        val existing = store.rooms(plan.ge360Id)
        if (existing.isEmpty()) {
            room.drawX = 0.0
            room.drawY = 0.0
            return
        }
        val index = existing.size
        room.drawX = (index % 2) * 5.0
        room.drawY = (index / 2) * 4.0
    }

    private fun planMenu() {
        AlertDialog.Builder(this)
            .setTitle(plan.title)
            .setItems(arrayOf("Modifica dati rilievo", "Elimina rilievo")) { _, which ->
                if (which == 0) editPlanDialog() else AlertDialog.Builder(this)
                    .setTitle("Eliminare il rilievo?")
                    .setMessage("Viene eliminato il rilievo locale e i suoi ambienti. Eventuali cantieri o preventivi collegati restano invariati.")
                    .setPositiveButton("Elimina") { _, _ -> store.delete(plan); finish() }
                    .setNegativeButton("Annulla", null)
                    .show()
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun editPlanDialog() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val title = EditText(this).apply { setText(plan.title); isSingleLine = true }
        val kind = Spinner(this).apply {
            adapter = ArrayAdapter(this@FloorPlanEditorActivity, android.R.layout.simple_spinner_dropdown_item, FloorPlanKinds.ALL)
            setSelection(FloorPlanKinds.ALL.indexOf(plan.kind).coerceAtLeast(0))
        }
        val client = EditText(this).apply { hint = "Cliente"; setText(plan.clientName); isSingleLine = true }
        val notes = EditText(this).apply { hint = "Note"; setText(plan.notes); minLines = 3 }
        root.addView(title, lp(50))
        root.addView(kind, lp(50))
        root.addView(client, lp(50))
        root.addView(notes, lp(90))
        AlertDialog.Builder(this)
            .setTitle("Dati rilievo")
            .setView(root)
            .setPositiveButton("Salva") { _, _ ->
                plan.title = title.text.toString().trim().ifBlank { plan.title }
                plan.kind = kind.selectedItem.toString()
                plan.clientName = client.text.toString().trim()
                plan.notes = notes.text.toString().trim()
                store.save(plan)
                refresh()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun linkMenu() {
        val options = mutableListOf<String>()
        if (plan.jobsiteId.isBlank()) {
            options += "Collega a cantiere esistente"
            options += "Crea nuovo cantiere dal rilievo"
        } else {
            options += "Cambia cantiere collegato"
            options += "Apri cantieri"
            options += "Scollega dal cantiere"
        }
        options += if (plan.estimateId.isBlank()) "Crea preventivo dagli ambienti" else "Apri preventivi"

        AlertDialog.Builder(this)
            .setTitle("Cantiere / Preventivo")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Collega a cantiere esistente", "Cambia cantiere collegato" -> chooseExistingJobsite()
                    "Crea nuovo cantiere dal rilievo" -> createJobsite()
                    "Scollega dal cantiere" -> {
                        plan.jobsiteId = ""
                        store.save(plan)
                        refresh()
                    }
                    "Crea preventivo dagli ambienti" -> createEstimate()
                    "Apri preventivi" -> startActivity(Intent(this, EstimatesActivity::class.java))
                    "Apri cantieri" -> startActivity(Intent(this, JobsitesActivity::class.java))
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun chooseExistingJobsite() {
        val jobs = JobsiteStore(applicationContext)
        val rows = jobs.list()
        if (rows.isEmpty()) {
            jobs.close()
            Toast.makeText(this, "Non ci sono cantieri da collegare", Toast.LENGTH_LONG).show()
            return
        }
        val labels = rows.map { "${it.code} · ${it.displayTitle()} · ${it.status}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Scegli cantiere")
            .setItems(labels) { _, index ->
                plan.jobsiteId = rows[index].ge360Id
                store.save(plan)
                jobs.close()
                refresh()
            }
            .setOnCancelListener { jobs.close() }
            .show()
    }

    private fun createJobsite() {
        val jobs = JobsiteStore(applicationContext)
        try {
            val job = JobsiteRecord(
                clientName = plan.clientName,
                title = plan.title,
                workType = plan.kind,
                status = JobsiteRecord.STATUS_INSPECTION,
                notes = buildString {
                    append("Creato dal rilievo GE360 ").append(plan.ge360Id)
                    if (plan.notes.isNotBlank()) append("\n\n").append(plan.notes)
                }
            )
            jobs.save(job)
            plan.jobsiteId = job.ge360Id
            store.save(plan)
            refresh()
            Toast.makeText(this, "Creato e collegato ${job.code}", Toast.LENGTH_LONG).show()
        } finally {
            jobs.close()
        }
    }

    private fun createEstimate() {
        val rooms = store.rooms(plan.ge360Id)
        if (rooms.isEmpty()) {
            Toast.makeText(this, "Aggiungi almeno un ambiente prima del preventivo", Toast.LENGTH_LONG).show()
            return
        }
        val invalidProfiles = rooms.filter {
            it.mode == FloorPlanRoomModes.WALLS && !FloorPlanWallGeometry.isValidClosedProfile(store.walls(it.ge360Id))
        }
        if (invalidProfiles.isNotEmpty()) {
            Toast.makeText(
                this,
                "Completa o correggi prima le pareti di: ${invalidProfiles.joinToString { it.name }}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val estimates = EstimateStore(applicationContext)
        val estimateRooms = EstimateRoomStore(applicationContext)
        val custom = PreventiviCustomizationStore(applicationContext)
        try {
            val estimate = EstimateRecord(
                clientName = plan.clientName,
                jobsiteId = plan.jobsiteId,
                title = plan.title,
                workType = inferWorkType(rooms),
                status = EstimateStatuses.DRAFT,
                documentType = "Provvisorio",
                generalDescription = "Preventivo generato dal rilievo GE360 ${plan.ge360Id}.",
                finalNotes = "Rilievo sorgente: ${plan.title}."
            )
            estimates.save(estimate)
            rooms.forEachIndexed { index, source ->
                val roomId = UUID.randomUUID().toString()
                val target = EstimateRoom(
                    ge360Id = roomId,
                    estimateGe360Id = estimate.ge360Id,
                    name = source.name,
                    type = estimateRoomType(source),
                    length = source.length,
                    width = source.width,
                    height = source.height,
                    pricingMode = RoomPricing.MEASURE,
                    sortOrder = index
                )
                estimateRooms.save(target)
                if (source.mode != FloorPlanRoomModes.RECTANGULAR) {
                    custom.saveManualMeasures(
                        roomId,
                        ManualRoomMeasures(
                            enabled = true,
                            floor = source.floorArea(),
                            ceiling = source.ceilingArea(),
                            walls = source.wallArea(),
                            perimeter = source.perimeter()
                        )
                    )
                }
            }
            plan.estimateId = estimate.ge360Id
            store.save(plan)
            refresh()
            Toast.makeText(this, "Creato preventivo ${estimate.number} con ${rooms.size} ambienti", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, EstimatesActivity::class.java))
        } finally {
            estimates.close()
            estimateRooms.close()
        }
    }

    private fun estimateRoomType(room: FloorPlanRoom): String = when {
        room.roomType.contains("Bagno", true) -> RoomTypes.BATHROOM
        room.roomType.contains("Cucina", true) -> RoomTypes.KITCHEN
        room.roomType.contains("Balcone", true) || room.roomType.contains("Terraz", true) -> RoomTypes.BALCONY
        room.roomType in listOf("Stanza", "Camera", "Corridoio") -> RoomTypes.ROOM
        else -> RoomTypes.OTHER
    }

    private fun inferWorkType(rooms: List<FloorPlanRoom>): String = when {
        rooms.size > 1 -> "Ristrutturazione intero appartamento"
        rooms.first().roomType.contains("Bagno", true) -> "Ristrutturazione bagno"
        rooms.first().roomType.contains("Cucina", true) -> "Ristrutturazione cucina"
        rooms.first().roomType.contains("Balcone", true) || rooms.first().roomType.contains("Terraz", true) -> "Terrazze e balconi"
        else -> "Lavorazioni personalizzate"
    }

    private fun lp(h: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(h)).apply { bottomMargin = dp(7) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PLAN_ID = "floor_plan_id"
    }
}
