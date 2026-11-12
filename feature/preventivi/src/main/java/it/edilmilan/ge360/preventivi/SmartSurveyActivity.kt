package it.edilmilan.ge360.preventivi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.widget.ProgressBar
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * 8 schermate da usare durante il sopralluogo sul telefono.
 * Ogni pagina salva sul dispositivo. I preventivi si creano solo esplicitamente
 * alla fine: mai modifica automatica di preventivi già esistenti.
 */
class SmartSurveyActivity : AppCompatActivity() {
    private lateinit var sessions: SmartSurveyLocalStore
    private lateinit var survey: SmartSurveySessionV1
    private lateinit var heading: TextView
    private lateinit var progress: ProgressBar
    private lateinit var body: LinearLayout
    private lateinit var prev: MaterialButton
    private lateinit var next: MaterialButton
    private var collect: () -> Unit = {}

    private val jsonExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(survey.toJson().toString(2).toByteArray(Charsets.UTF_8))
            } ?: error("Impossibile aprire il documento")
            toast("Sopralluogo JSON esportato. Le immagini non sono incluse.")
        }.onFailure { toast("Esportazione fallita: " + it.message) }
    }

    private val photosPicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty() || survey.generatedEstimateId.isNotBlank()) return@registerForActivityResult
        var saved = 0
        val directory = File(filesDir, "smart_sopralluoghi/" + survey.id).apply { mkdirs() }
        for (uri in uris.take(20)) {
            val name = UUID.randomUUID().toString() + ".img"
            val output = File(directory, name)
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    output.outputStream().use { dest ->
                        val buffer = ByteArray(8192)
                        var count = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            count += n
                            require(count <= 15_000_000) { "Foto oltre 15 MB" }
                            dest.write(buffer, 0, n)
                        }
                        require(count > 0) { "Foto vuota" }
                    }
                } ?: error("File non leggibile")
                survey.photoFiles += name
                saved++
            } catch (_: Exception) {
                output.delete()
                toast("Una foto non è stata salvata (formato o dimensione)")
            }
        }
        persist()
        if (saved > 0) toast("$saved foto salvate sul telefono")
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessions = SmartSurveyLocalStore(this)
        survey = intent.getStringExtra(EXTRA_SURVEY_ID)?.let(sessions::get) ?: SmartSurveySessionV1()
        val linked = EstimateStore(this).use { it.getByGe360Id(survey.id) }
        if (linked != null && survey.generatedEstimateId.isBlank()) {
            survey.generatedEstimateId = linked.ge360Id
        }
        persist()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(8))
            setBackgroundColor(Ge360Brand.Background)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(button("‹ Esci") {
            capture()
            finish()
        })
        val title = TextView(this).apply {
            text = "GE360 · Sopralluogo Smart"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        }
        top.addView(title)
        root.addView(top)
        heading = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(12), dp(4), dp(6))
        }
        heading.setTextColor(Ge360Brand.ForestDark)
        root.addView(heading)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 8
            progressTintList = android.content.res.ColorStateList.valueOf(Ge360Brand.Accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ge360Brand.Border)
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)).apply { bottomMargin = dp(12) })
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(24))
        }
        root.addView(ScrollView(this).apply {
            fillViewport = true
            addView(body)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        prev = button("Indietro") { move(-1) }
        next = button("Avanti") { move(1) }
        footer.addView(prev, LinearLayout.LayoutParams(0, dp(52), 1f))
        footer.addView(next, LinearLayout.LayoutParams(0, dp(52), 1f))
        footer.setPadding(0, dp(4), 0, dp(2))
        root.addView(footer)
        setContentView(root)
        render()
    }

    override fun onPause() {
        if (::survey.isInitialized) capture()
        super.onPause()
    }

    private fun persist() {
        runCatching { sessions.save(survey) }
            .onFailure { toast("Salvataggio locale non riuscito: " + it.message) }
    }
    private fun capture() { collect(); persist() }
    private fun move(delta: Int) {
        capture()
        survey.step = (survey.step + delta).coerceIn(0, 7)
        persist()
        render()
    }

    private fun render() {
        body.removeAllViews()
        collect = {}
        heading.text = "PASSO ${survey.step + 1} DI 8 · " + SmartSurveyDraftV1.STEPS[survey.step]
        progress.progress = survey.step + 1
        prev.isEnabled = survey.step > 0
        next.isEnabled = survey.step < 7 && survey.generatedEstimateId.isBlank()
        next.text = if (survey.step < 7) "Avanti →" else "Finito"

        if (survey.generatedEstimateId.isNotBlank()) {
            info("Bozza già generata. Il sopralluogo originale resta archiviato e non riscrive il preventivo.")
            action("APRI IL PREVENTIVO") { openEstimate(survey.generatedEstimateId) }
            action("Copia prompt V3.1") { copyPrompt() }
            return
        }

        when (survey.step) {
            0 -> stepClient()
            1 -> stepRooms()
            2 -> stepMeasures()
            3, 4, 5 -> stepWorks(survey.step)
            6 -> stepMaterials()
            7 -> stepReview()
        }
    }

    private fun stepClient() {
        info("Inserisci solo i dati che conosci. Il sopralluogo è salvato offline e puoi completarlo più tardi.")
        val client = input("Cliente / nominativo", survey.clientName)
        val title = input("Titolo intervento", survey.title)
        val site = input("Indirizzo o riferimento del cantiere", survey.siteName)
        val types = listOf(
            "Ristrutturazione bagno", "Ristrutturazione cucina",
            "Ristrutturazione intero appartamento", "Tinteggiatura",
            "Cartongesso", "Pavimenti e rivestimenti", "Terrazze e balconi", "Altro"
        )
        val workType = spinner("Tipo intervento", types, types.indexOf(survey.workType).coerceAtLeast(0))
        val description = input("Descrizione del sopralluogo / richieste del cliente", survey.description, true)
        collect = {
            survey.clientName = client.text.toString().trim()
            survey.title = title.text.toString().trim()
            survey.siteName = site.text.toString().trim()
            survey.workType = workType.selectedItem.toString()
            survey.description = description.text.toString().trim()
        }
    }

    private fun stepRooms() {
        info("Aggiungi tutti gli ambienti: bagno, cucina, camere, corridoi, terrazze. Puoi lavorare per singolo locale.")
        survey.rooms.forEachIndexed { index, room ->
            action((index + 1).toString() + ". " + room.name + " · " + room.type + " · modifica") {
                editRoom(room, false)
            }
        }
        action("+ AGGIUNGI AMBIENTE") { editRoom(SmartSurveyRoomV1(name = "Ambiente " + (survey.rooms.size + 1)), true) }
        if (survey.rooms.isEmpty()) info("Nessun ambiente aggiunto. Nel prossimo passaggio potrai inserire le misure.")
    }

    private fun editRoom(room: SmartSurveyRoomV1, isNew: Boolean) {
        val layout = form()
        val name = input("Nome (es. Bagno principale)", room.name, holder = layout)
        val type = spinner("Tipo ambiente", RoomTypes.ALL, RoomTypes.ALL.indexOf(room.type).coerceAtLeast(0), layout)
        AlertDialog.Builder(this)
            .setTitle(if (isNew) "Nuovo ambiente" else "Modifica ambiente")
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Salva") { _, _ ->
                val newType = type.selectedItem.toString()
                val newName = name.text.toString().trim().ifBlank { "Ambiente" }
                val allowed = RoomWorkCatalog.forType(newType).map { it.code }.toSet()
                val toRemove = if (newType != room.type)
                    room.workCodes.filter { it !in allowed } +
                        room.scopedWorks.filter { it.workCode !in allowed }.map { it.workCode }
                else emptyList()
                val applyChange: () -> Unit = {
                    room.name = newName
                    if (newType != room.type) {
                        room.workCodes.retainAll(allowed)
                        room.unitPrices.keys.retainAll(allowed)
                        room.quantities.keys.retainAll(allowed)
                        room.options.retainAll(allowed)
                        room.scopedWorks.removeAll { it.workCode !in allowed }
                        if (newType != RoomTypes.BATHROOM) {
                            room.floorChoice = SmartSurveyDraftV1.UNKNOWN
                            room.wetChoice = SmartSurveyDraftV1.UNKNOWN
                        }
                    }
                    room.type = newType
                    if (isNew) survey.rooms += room
                    persist(); render()
                }
                if (toRemove.isNotEmpty()) {
                    AlertDialog.Builder(this).setTitle("Cambio tipo ambiente")
                        .setMessage(
                            "Il nuovo tipo non contiene " + toRemove.size +
                            " lavorazioni già scelte. Vuoi eliminarle solo da questo sopralluogo?"
                        )
                        .setPositiveButton("Conferma cambio") { _, _ -> applyChange() }
                        .setNegativeButton("Annulla", null).show()
                } else applyChange()
            }
            .setNeutralButton(if (isNew) "Annulla" else "Elimina") { _, _ ->
                if (!isNew) AlertDialog.Builder(this).setTitle("Eliminare questo ambiente?")
                    .setMessage("Verranno eliminate le sue misure e selezioni dal sopralluogo, non i preventivi esistenti.")
                    .setPositiveButton("Elimina") { _, _ -> survey.rooms.removeAll { it.id == room.id }; persist(); render() }
                    .setNegativeButton("Annulla", null).show()
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun stepMeasures() {
        info("Le superfici vengono proposte dalle misure L × P × H. Per stanze irregolari inserisci direttamente i m² e il perimetro. Le aperture NON vengono sottratte automaticamente.")
        survey.rooms.forEach { room ->
            val summary = "${room.name} · pav. ${fmt(room.floor())} m² · pareti ${fmt(room.walls())} m²"
            action(summary) { editMeasures(room) }
            action("🧱 PARETI DI " + room.name.uppercase(Locale.ITALY) +
                " · " + room.wallsList.size + " registrate") { manageWalls(room) }
            if (room.wallsList.isNotEmpty()) info(
                "Pareti censite: " + fmt(room.wallsList.sumOf { it.netArea() }) +
                    " m² netti (non sostituiscono automaticamente i m² totali dell'ambiente)."
            )
        }
        if (survey.rooms.isEmpty()) action("+ Aggiungi ambiente") { survey.step = 1; persist(); render() }
    }

    private fun editMeasures(room: SmartSurveyRoomV1) {
        val layout = form()
        val length = number("Lunghezza (m)", room.length, layout)
        val width = number("Larghezza (m)", room.width, layout)
        val height = number("Altezza (m)", room.height, layout)
        val floor = number("Pavimento misurato (m²) · prevale su L×P", room.floorManual, layout)
        val ceiling = number("Soffitto misurato (m²)", room.ceilingManual, layout)
        val walls = number("Pareti misurate (m²)", room.wallsManual, layout)
        val perimeter = number("Perimetro misurato (metri lineari)", room.perimeterManual, layout)
        val tilingHeight = number("Altezza rivestimento pareti bagno (m)", room.tilingHeight, layout)
        val tilingSurface = number("Rivestimento pareti bagno misurato (m²)", room.tilingWallsManual, layout)
        AlertDialog.Builder(this)
            .setTitle(room.name + " · misure")
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val vals = listOf(length, width, height, floor, ceiling, walls, perimeter, tilingHeight, tilingSurface).map(::amount)
                        if (vals.any { it == null }) return@setOnClickListener toast("Controlla le misure: usa numeri positivi")
                        val v = vals.map { it ?: 0.0 }
                        room.length = v[0]; room.width = v[1]; room.height = v[2]
                        room.floorManual = v[3]; room.ceilingManual = v[4]
                        room.wallsManual = v[5]; room.perimeterManual = v[6]
                        room.tilingHeight = v[7]; room.tilingWallsManual = v[8]
                        persist(); dismiss(); render()
                    }
                }
                show()
            }
    }

    /** Nome + dimensioni + aperture della parete, conservando l'UUID tra i rilievi. */
    private fun manageWalls(room: SmartSurveyRoomV1) {
        val labels = room.wallsList.map { wall ->
            "🧱 ${wall.name} · ${fmt(wall.netArea())} m² netti · " +
                room.scopedWorks.count { it.scopeId == wall.id } + " lavorazioni"
        } + "＋ AGGIUNGI PARETE"
        AlertDialog.Builder(this)
            .setTitle(room.name + " · pareti")
            .setItems(labels.toTypedArray()) { _, index ->
                if (index == room.wallsList.size) {
                    editWall(room, SurveyWallV1(name = "Parete " + (room.wallsList.size + 1)), true)
                } else editWall(room, room.wallsList[index], false)
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun editWall(room: SmartSurveyRoomV1, wall: SurveyWallV1, isNew: Boolean) {
        val layout = form()
        val name = input("Nome parete (es. Parete finestra / Parete 1)", wall.name, holder = layout)
        val width = number("Lunghezza parete (m)", wall.width, layout)
        val height = number("Altezza parete (m)", if (wall.height > 0.0) wall.height else room.height, layout)
        val openings = number("Aperture da sottrarre (m²) · facoltativo", wall.openingsArea, layout)
        val note = input("Note e particolarità della parete", wall.note, true, layout)
        info("Area netta parete = lunghezza × altezza − aperture misurate. " +
            "Le aperture non sono mai dedotte in automatico.", layout)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isNew) "Nuova parete · " + room.name else "Modifica · " + wall.name)
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Salva parete", null)
            .setNegativeButton("Annulla", null)
            .apply { if (!isNew) setNeutralButton("Elimina parete", null) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val w = amount(width)
                val h = amount(height)
                val o = amount(openings)
                if (w == null || h == null || o == null || w <= 0 || h <= 0 || o >= w * h) {
                    return@setOnClickListener toast("Controlla lunghezza, altezza e aperture (minori dell'area lorda)")
                }
                wall.name = name.text.toString().trim().ifBlank { "Parete " + (room.wallsList.size + 1) }
                wall.width = w; wall.height = h; wall.openingsArea = o
                wall.note = note.text.toString().trim()
                if (isNew) room.wallsList += wall
                persist()
                dialog.dismiss()
                render()
            }
            if (!isNew) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(this@SmartSurveyActivity)
                    .setTitle("Eliminare " + wall.name + "?")
                    .setMessage("Verranno eliminate anche le " +
                        room.scopedWorks.count { it.scopeId == wall.id } +
                        " lavorazioni assegnate a questa parete. Gli altri locali non cambiano.")
                    .setPositiveButton("Elimina") { _, _ ->
                        SurveySurfaceEngine.removeWall(room, wall.id)
                        persist()
                        dialog.dismiss()
                        render()
                    }.setNegativeButton("Annulla", null).show()
            }
        }
        dialog.show()
    }

    private fun chooseWorkSurface(room: SmartSurveyRoomV1) {
        val scopes = SurveySurfaceEngine.scopes(room)
        val options = scopes.map { scope ->
            val count = if (scope.id == SurveySurfaceEngine.ROOM) room.workCodes.size
            else room.scopedWorks.count { it.scopeId == scope.id }
            "${scope.title} · ${count} lavori"
        } + "＋ Aggiungi una nuova parete"
        AlertDialog.Builder(this)
            .setTitle(room.name + " · DOVE si eseguono i lavori?")
            .setItems(options.toTypedArray()) { _, index ->
                if (index == scopes.size) {
                    editWall(room, SurveyWallV1(name = "Parete " + (room.wallsList.size + 1)), true)
                } else {
                    checklist(room, null, scopes[index].id)
                }
            }
            .setNegativeButton("Indietro", null)
            .show()
    }

    private fun stepWorks(phase: Int) {
        info(when (phase) {
            3 -> "Demolizioni: seleziona ciò che è previsto. Per il bagno scegli alternative esclusive; non vengono assunte automaticamente."
            4 -> "Impianti: tracce, idraulico ed elettrico secondo la checklist del tipo di ambiente."
            else -> "Finiture e lavorazioni: seleziona le voci, poi imposta quantità/prezzi e opzionali. Nessun prezzo viene inventato."
        })
        survey.rooms.forEach { room ->
            val total = room.workCodes.count { code ->
                RoomWorkCatalog.forType(room.type).any { it.code == code && SmartSurveyDraftV1.phase(it) == phase }
            }
            label(room.name + " · " + total + " selezioni · " +
                room.scopedWorks.size + " lavori su superfici", true)
            if (phase == 3) {
                action("🧱 DOVE LAVORIAMO? · Stanza / Parete / Soffitto / Pavimento") {
                    chooseWorkSurface(room)
                }
                action("☑ CHECKLIST COMPLETA · TUTTE LE LAVORAZIONI") { checklist(room, null) }
                info("Cerca un lavoro per nome e spuntalo. Le voci del capitolato e le lavorazioni specifiche sono tutte disponibili; nessun prezzo precompilato.")
            }
            if (phase == 3) action("⚡ SUGGERISCI CHECKLIST STANDARD") {
                val defaults = RoomWorkCatalog.forType(room.type)
                    .filter { it.enabledByDefault && it.code !in setOf("DEM-PAV", "POSA-SU-POSA", "DEM-VASCA", "DEM-DOCCIA") }
                    .map { it.code }
                AlertDialog.Builder(this).setTitle("Checklist standard · " + room.name)
                    .setMessage("Aggiungo " + defaults.size + " lavorazioni usuali da verificare, senza cancellare selezioni o imporre prezzi. Le alternative del bagno restano da scegliere.")
                    .setPositiveButton("Aggiungi") { _, _ ->
                        room.workCodes.addAll(defaults)
                        persist(); render()
                    }.setNegativeButton("Annulla", null).show()
            }
            if (phase == 3 && room.type == RoomTypes.BATHROOM) {
                action("Pavimento: " + room.floorChoice) {
                    choose("Pavimento esistente", listOf(
                        SmartSurveyDraftV1.UNKNOWN to "Da decidere",
                        SmartSurveyDraftV1.DEMOLISH to "Demolire",
                        SmartSurveyDraftV1.OVERLAY to "Posa su posa",
                        SmartSurveyDraftV1.KEEP to "Mantenere"
                    ), room.floorChoice) { SmartSurveyDraftV1.setFloor(room, it) }
                }
                action("Area bagno: " + room.wetChoice) {
                    choose("Situazione vasca / doccia", listOf(
                        SmartSurveyDraftV1.UNKNOWN to "Da decidere",
                        SmartSurveyDraftV1.BATH to "Rimuovere vasca",
                        SmartSurveyDraftV1.SHOWER to "Rimuovere doccia",
                        SmartSurveyDraftV1.NONE to "Nessuna rimozione"
                    ), room.wetChoice) { SmartSurveyDraftV1.setWet(room, it) }
                }
            }
            action("☑ " + SmartSurveyDraftV1.STEPS[phase] + " · scegli lavori") { checklist(room, phase) }
            if (phase == 5) {
                action("€ COMPILA QUANTITÀ E PREZZI · TUTTO INSIEME") { quickPrices(room) }
                action("€ Modifica una singola voce") { editPrices(room) }
                action("＋ LAVORAZIONE LIBERA") { editManualWork(room, SmartSurveyManualWorkV1(), true) }
                room.manualWorks.forEach { custom ->
                    action("✎ " + custom.name.ifBlank { "Lavorazione da completare" } +
                        " · " + fmt(custom.quantity) + " " + custom.unit) {
                        editManualWork(room, custom, false)
                    }
                }
                action("Modalità: " + room.pricingMode + if (room.pricingMode == RoomPricing.BODY) " · € " + fmt(room.bodyPrice) else "") {
                    pricingMode(room)
                }
            }
        }
        if (survey.rooms.isEmpty()) action("+ Aggiungi ambiente") { survey.step = 1; persist(); render() }
    }

    private fun choose(title: String, choices: List<Pair<String, String>>, current: String, onSelect: (String) -> Unit) {
        val index = choices.indexOfFirst { it.first == current }.coerceAtLeast(0)
        var selected = index
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(choices.map { it.second }.toTypedArray(), index) { _, position -> selected = position }
            .setPositiveButton("Conferma") { _, _ -> onSelect(choices[selected].first); persist(); render() }
            .setNegativeButton("Annulla", null).show()
    }

    /**
     * Checklist unica in stile form: ricerca istantanea, categorie e multi-spunta.
     * Non modifica i dati fino a "Applica": Annulla ripristina le selezioni.
     */
    private fun checklist(room: SmartSurveyRoomV1, phase: Int?, scopeId: String = SurveySurfaceEngine.ROOM) {
        // Le alternative specifiche del bagno si scelgono dai comandi esclusivi.
        val excludedBath = if (room.type == RoomTypes.BATHROOM) {
            setOf("DEM-PAV", "POSA-SU-POSA", "DEM-VASCA", "DEM-DOCCIA")
        } else emptySet()
        val templates = RoomWorkCatalog.forType(room.type)
            .filter { it.code !in excludedBath && (phase == null || SmartSurveyDraftV1.phase(it) == phase) }
            .distinctBy { it.code }
        if (templates.isEmpty()) return toast("Nessuna voce prevista per questa fase")

        val chosen = if (scopeId == SurveySurfaceEngine.ROOM) room.workCodes.toMutableSet()
            else room.scopedWorks.filter { it.scopeId == scopeId }.map { it.workCode }.toMutableSet()
        val scopeName = SurveySurfaceEngine.scope(room, scopeId)?.title
            ?: return toast("Questa superficie non esiste più")
        val layout = form()
        val search = EditText(this).apply {
            hint = "🔎 Cerca: stuccatura, SPC, massetto, finestra..."
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        layout.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        val countLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(dp(4), dp(7), dp(4), dp(10))
        }
        layout.addView(countLabel)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(430)))

        fun showEntries(query: String) {
            list.removeAllViews()
            val normalized = query.trim().lowercase(Locale.ITALY)
            val visible = templates.filter { t ->
                normalized.isEmpty() ||
                    listOf(t.title, t.category, t.description, t.code)
                        .any { it.lowercase(Locale.ITALY).contains(normalized) }
            }
            countLabel.text = "${chosen.count { code -> templates.any { it.code == code } }} selezionate · ${visible.size} visualizzate"
            if (visible.isEmpty()) {
                info("Nessuna voce trovata. Puoi aggiungere una lavorazione libera nel passo Finiture.", list)
            }
            visible.groupBy { it.category }.forEach { (category, works) ->
                label(category.uppercase(Locale.ITALY), true, list)
                works.forEach { work ->
                    list.addView(CheckBox(this).apply {
                        text = work.title + " · " + work.unit
                        textSize = 14f
                        minHeight = dp(52)
                        isChecked = work.code in chosen
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) chosen += work.code else chosen -= work.code
                            countLabel.text = "${chosen.count { code -> templates.any { it.code == code } }} selezionate · ${visible.size} visualizzate"
                        }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = showEntries(s?.toString().orEmpty())
        })
        showEntries("")

        AlertDialog.Builder(this)
            .setTitle(room.name + " · " + scopeName)
            .setView(layout)
            .setPositiveButton("Applica selezioni") { _, _ ->
                val eligibleCodes = templates.map { it.code }.toSet()
                val selectedCodes = chosen.intersect(eligibleCodes)
                val overlaps = SurveySurfaceEngine.overlappingCodes(room, scopeId, selectedCodes)
                val apply: (Boolean) -> Unit = { replace ->
                    runCatching {
                        SurveySurfaceEngine.applySelection(
                            room, scopeId, eligibleCodes, selectedCodes, replaceOverlaps = replace
                        )
                        room.options.retainAll(room.workCodes)
                        persist()
                        render()
                    }.onFailure { toast(it.message ?: "Impossibile salvare le selezioni") }
                }
                if (overlaps.isEmpty()) apply(false)
                else AlertDialog.Builder(this)
                    .setTitle("Evitiamo di contare due volte?")
                    .setMessage(overlaps.size.toString() + " lavorazioni sono già attribuite " +
                        "all'intera stanza o a una superficie specifica. Vuoi SPOSTARLE su " +
                        scopeName + " rimuovendo la vecchia assegnazione e i suoi importi?")
                    .setPositiveButton("Sposta lavori") { _, _ -> apply(true) }
                    .setNegativeButton("Mantieni come prima", null)
                    .show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private data class QuickWorkInput(
        val template: RoomWorkTemplate,
        val assignment: SurveyScopedWorkV1?,
        val quantity: EditText,
        val price: EditText,
        val optional: CheckBox
    )

    /**
     * Un solo modulo: lavori sull'intera stanza + lavori su ogni superficie.
     * Identità per (room UUID, superficie UUID, workCode), senza mescolare i
     * prezzi di una parete con quelli delle altre.
     */
    private fun quickPrices(room: SmartSurveyRoomV1) {
        val catalog = RoomWorkCatalog.forType(room.type).associateBy { it.code }
        val general = room.workCodes.mapNotNull(catalog::get).sortedBy { it.title }
        val scoped = room.scopedWorks
            .filter { catalog[it.workCode] != null }
            .sortedWith(compareBy<SurveyScopedWorkV1> {
                SurveySurfaceEngine.scope(room, it.scopeId)?.title ?: ""
            }.thenBy { it.workCode })

        if (general.isEmpty() && scoped.isEmpty()) return toast("Scegli prima le lavorazioni nella checklist")

        val layout = form()
        info("Una quantità per stanza, parete, soffitto o pavimento. Le superfici misurate propongono una quantità; " +
            "puoi correggerla. Prezzi mai inventati, optional esclusi dal totale.", layout)
        val inputs = mutableListOf<QuickWorkInput>()

        fun addInput(work: RoomWorkTemplate, assigned: SurveyScopedWorkV1?) {
            val scopeName = if (assigned == null) "Tutto l'ambiente" else {
                SurveySurfaceEngine.scope(room, assigned.scopeId)?.title ?: "Superficie da verificare"
            }
            label(scopeName + " · " + work.title + " · " + work.unit, true, layout)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val q0 = if (assigned == null) SmartSurveyDraftV1.quantity(room, work)
                else SurveySurfaceEngine.quantity(room, assigned, work)
            val p0 = if (assigned == null) room.unitPrices[work.code] ?: 0.0 else assigned.unitPrice
            val quantity = numberField("Qtà " + work.unit, q0)
            val price = numberField("€ / " + work.unit, p0)
            row.addView(quantity, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(8) })
            row.addView(price, LinearLayout.LayoutParams(0, dp(52), 1f))
            layout.addView(row)
            val optional = CheckBox(this).apply {
                text = "Optional · fuori dal totale"
                isChecked = if (assigned == null) work.code in room.options else assigned.optional
                textSize = 12f
            }
            layout.addView(optional)
            inputs += QuickWorkInput(work, assigned, quantity, price, optional)
        }

        general.forEach { addInput(it, null) }
        scoped.forEach { assignment -> catalog[assignment.workCode]?.let { addInput(it, assignment) } }

        AlertDialog.Builder(this)
            .setTitle(room.name + " · Quantità e prezzi")
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Salva tutto", null)
            .setNegativeButton("Annulla", null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val updates = mutableListOf<Triple<QuickWorkInput, Double, Double>>()
                        for (entry in inputs) {
                            val quantity = amount(entry.quantity)
                            val price = amount(entry.price)
                            if (quantity == null || price == null) {
                                return@setOnClickListener toast(
                                    "Verifica quantità e prezzo: " + entry.template.title
                                )
                            }
                            updates += Triple(entry, quantity, price)
                        }
                        updates.forEach { (entry, quantity, price) ->
                            val assignment = entry.assignment
                            if (assignment == null) {
                                room.quantities[entry.template.code] = quantity
                                room.unitPrices[entry.template.code] = price
                                if (entry.optional.isChecked) room.options += entry.template.code
                                else room.options -= entry.template.code
                            } else {
                                // Se coincide con la geometria reale, lascia attivo
                                // il collegamento dinamico alla superficie.
                                val geometry = SurveySurfaceEngine.quantity(
                                    room, assignment.copy(manualQuantity = null), entry.template
                                )
                                assignment.manualQuantity = if (kotlin.math.abs(quantity - geometry) < 0.001)
                                    null else quantity
                                assignment.unitPrice = price
                                assignment.optional = entry.optional.isChecked
                            }
                        }
                        persist()
                        dismiss()
                        render()
                    }
                }
                show()
            }
    }

    private fun numberField(hint: String, value: Double): EditText = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        isSingleLine = true
        textSize = 15f
        setText(if (value > 0.0) fmt(value) else "")
    }

    private fun editPrices(room: SmartSurveyRoomV1) {
        val byCode = RoomWorkCatalog.forType(room.type).associateBy { it.code }
        val templates = room.workCodes.mapNotNull(byCode::get)
        if (templates.isEmpty()) return toast("Prima scegli una lavorazione")
        AlertDialog.Builder(this).setTitle(room.name + " · quantità e prezzi")
            .setItems(templates.map { t ->
                val q = SmartSurveyDraftV1.quantity(room, t)
                val p = room.unitPrices[t.code] ?: 0.0
                t.title + "\n" + fmt(q) + " " + t.unit + " · € " + fmt(p) +
                    if (t.code in room.options) " · OPTIONAL" else ""
            }.toTypedArray()) { _, which -> editWork(room, templates[which]) }
            .setNegativeButton("Chiudi", null).show()
    }

    private fun editWork(room: SmartSurveyRoomV1, template: RoomWorkTemplate) {
        val layout = form()
        info("La quantità geometrica è solo indicativa se le misure non sono complete.", layout)
        val quantity = number("Quantità " + template.unit, SmartSurveyDraftV1.quantity(room, template), layout, true)
        val price = number("Prezzo unitario € (0 = da definire)", room.unitPrices[template.code] ?: 0.0, layout)
        val optional = CheckBox(this).apply {
            text = "Optional: prezzo fuori dal totale"
            isChecked = template.code in room.options
        }
        layout.addView(optional)
        AlertDialog.Builder(this).setTitle(template.title)
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val q = amount(quantity); val p = amount(price)
                        if (q == null || p == null) return@setOnClickListener toast("Quantità / prezzo non valido")
                        room.quantities[template.code] = q
                        room.unitPrices[template.code] = p
                        if (optional.isChecked) room.options += template.code else room.options -= template.code
                        persist(); dismiss(); render()
                    }
                }
                show()
            }
    }

    private fun editManualWork(room: SmartSurveyRoomV1, work: SmartSurveyManualWorkV1, isNew: Boolean) {
        val layout = form()
        val name = input("Nome lavorazione (obbligatorio)", work.name, holder = layout)
        val description = input("Descrizione tecnica / dettagli", work.description, true, layout)
        val category = input("Categoria (facoltativa)", work.category, holder = layout)
        val quantity = number("Quantità effettiva", work.quantity, layout)
        val unitList = EstimateStore.UNITS
        val unit = spinner("Unità di misura", unitList,
            unitList.indexOf(work.unit).coerceAtLeast(0), layout)
        val price = number("Prezzo unitario o a corpo €", work.unitPrice, layout)
        val optional = CheckBox(this).apply {
            text = "Optional (separato dal totale)"
            isChecked = work.optional
        }
        layout.addView(optional)
        AlertDialog.Builder(this)
            .setTitle(if (isNew) "Nuova lavorazione · " + room.name else "Modifica · " + room.name)
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Salva", null)
            .setNeutralButton(if (isNew) "Annulla" else "Elimina", null)
            .setNegativeButton("Chiudi", null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val title = name.text.toString().trim()
                        val q = amount(quantity)
                        val p = amount(price)
                        if (title.isBlank() || q == null || p == null) {
                            toast("Inserisci un nome, una quantità e un prezzo validi")
                            return@setOnClickListener
                        }
                        work.name = title
                        work.description = description.text.toString().trim()
                        work.category = category.text.toString().trim().ifBlank { "Personalizzate" }
                        work.quantity = q
                        work.unit = unit.selectedItem.toString()
                        work.unitPrice = p
                        work.optional = optional.isChecked
                        if (isNew) room.manualWorks += work
                        persist(); dismiss(); render()
                    }
                    if (!isNew) getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        dismiss()
                        AlertDialog.Builder(this@SmartSurveyActivity)
                            .setTitle("Eliminare la lavorazione libera?")
                            .setMessage("Solo da questo sopralluogo; i preventivi già salvati non cambiano.")
                            .setPositiveButton("Elimina") { _, _ ->
                                room.manualWorks.removeAll { it.id == work.id }
                                persist(); render()
                            }.setNegativeButton("Annulla", null).show()
                    }
                }
                show()
            }
    }

    private fun pricingMode(room: SmartSurveyRoomV1) {
        choose("Modalità prezzo " + room.name, listOf(
            RoomPricing.MEASURE to "A misura", RoomPricing.BODY to "A corpo"
        ), room.pricingMode) {
            room.pricingMode = it
            if (it == RoomPricing.BODY) {
                val layout = form()
                val bodyPrice = number("Importo complessivo €", room.bodyPrice, layout)
                AlertDialog.Builder(this).setTitle("Prezzo a corpo · " + room.name).setView(layout)
                    .setPositiveButton("Salva") { _, _ ->
                        amount(bodyPrice)?.let { value -> room.bodyPrice = value }
                        persist(); render()
                    }.setNegativeButton("Annulla", null).show()
            }
        }
    }

    private fun stepMaterials() {
        info("Materiali e condizioni servono per preparare il documento professionale. Le foto restano private sul dispositivo; il prompt non allega immagini.")
        val included = input("Materiali a carico dell'impresa", survey.materialsIncluded, true)
        val client = input("Materiali forniti dal cliente", survey.materialsClient, true)
        val exclusions = input("Esclusioni dal preventivo", survey.exclusions, true)
        val notes = input("Note finali / criticità da verificare", survey.finalNotes, true)
        val validity = number("Validità dell'offerta (giorni)", survey.validityDays.toDouble(), body)
        label("Foto del sopralluogo · " + survey.photoFiles.size, true)
        action("📷 AGGIUNGI FOTO (anche più di una)") { capture(); photosPicker.launch("image/*") }
        action("💾 ESPORTA DATI SOPRALLUOGO JSON") {
            capture()
            jsonExport.launch("ge360-sopralluogo-" + survey.id.take(8) + ".json")
        }
        info("Il JSON contiene testi, misure e checklist. Le fotografie sono archiviate solo sul dispositivo e NON vengono incorporate nel JSON.")

        survey.photoFiles.toList().forEach { filename ->
            action("📷 Foto " + filename.take(8) + " · apri") { viewPhoto(filename) }
        }
        collect = {
            survey.materialsIncluded = included.text.toString().trim()
            survey.materialsClient = client.text.toString().trim()
            survey.exclusions = exclusions.text.toString().trim()
            survey.finalNotes = notes.text.toString().trim()
            survey.validityDays = ((amount(validity) ?: 30.0).toInt()).coerceIn(1, 365)
        }
    }

    private fun viewPhoto(filename: String) {
        if (filename !in survey.photoFiles || filename.contains('/') || filename.contains("..")) return
        val file = File(filesDir, "smart_sopralluoghi/" + survey.id + "/" + filename)
        if (!file.isFile) return toast("Foto non disponibile")
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = 4
        }) ?: return toast("Formato foto non visualizzabile")
        val preview = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
        }
        AlertDialog.Builder(this)
            .setTitle("Foto del sopralluogo")
            .setView(preview)
            .setPositiveButton("Chiudi", null)
            .setNeutralButton("Elimina foto") { _, _ ->
                AlertDialog.Builder(this).setTitle("Eliminare definitivamente la foto?")
                    .setPositiveButton("Elimina") { _, _ ->
                        file.delete()
                        survey.photoFiles.remove(filename)
                        persist(); render()
                    }.setNegativeButton("Annulla", null).show()
            }.show()
    }

    private fun stepReview() {
        info("Questo è un sopralluogo in bozza. Nessun cliente riceve nulla automaticamente.")
        label(survey.clientName.ifBlank { "Cliente non indicato" }, true)
        label(survey.title.ifBlank { "Intervento senza titolo" })
        label("Ambienti: " + survey.rooms.size + " · Foto locali: " + survey.photoFiles.size)
        val count = survey.rooms.sumOf { it.workCodes.size + it.scopedWorks.size + it.manualWorks.size }
        label("Lavorazioni selezionate: " + count, true)
        survey.rooms.forEach { room ->
            label("• " + room.name + " · pav. " + fmt(room.floor()) +
                " m² · pareti " + fmt(room.walls()) +
                " m² · " + room.wallsList.size + " pareti nominate · " +
                room.scopedWorks.size + " lavori per superficie")
        }
        val warnings = SmartSurveyDraftV1.checks(survey)
        label("Controlli da completare: " + warnings.size, true)
        if (warnings.isEmpty()) info("Tutti i controlli automatici sono soddisfatti.")
        else warnings.take(30).forEach { info("⚠ " + it) }
        if (warnings.size > 30) info("Altri " + (warnings.size - 30) + " controlli: verifica i dati degli ambienti.")
        if (survey.rooms.isNotEmpty()) {
            val estimate = runCatching { SmartSurveyDraftV1.toEstimate(survey) }.getOrNull()
            if (estimate != null) {
                label("TOTALE PROVVISORIO: € " + fmt(estimate.finalTotal), true)
                info("Il totale non è definitivo finché mancano quantità o prezzi. Gli optional restano fuori dal totale.")
                action("📋 COPIA PROMPT GPT PREVENTIVI PREMIUM V3.1") { copyPrompt() }
                action("CONDIVIDI PROMPT") {
                    runCatching {
                        val prompt = buildPrompt()
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, prompt)
                        }, "Condividi dati del sopralluogo"))
                    }.onFailure { toast(it.message ?: "Prompt non disponibile") }
                }
            }
            action("✓ CREA BOZZA PREVENTIVO GE360") { convertToDraft() }
        }
    }

    private fun buildPrompt(): String {
        val quote = if (survey.generatedEstimateId.isBlank()) SmartSurveyDraftV1.toEstimate(survey)
            else EstimateStore(this).use { it.getByGe360Id(survey.generatedEstimateId) }
                ?: error("Bozza non disponibile")
        val models = SmartSurveyDraftV1.roomModels(survey)
        return EstimateSopralluogoPromptV1.build(quote, models, { m ->
            val r = survey.rooms.firstOrNull { it.id == m.ge360Id }
            if (r == null) m.measuresSummary()
            else "Manuale / geometrico · pavimento " + fmt(r.floor()) + " m² · soffitto " +
                fmt(r.ceiling()) + " m² · pareti " + fmt(r.walls()) +
                " m² · perimetro " + fmt(r.perimeter()) +
                " ml · rivestimento pareti " + fmt(r.tilingWalls()) + " m²" +
                if (r.wallsList.isEmpty()) "" else " · PARETI MISURATE: " +
                    r.wallsList.joinToString(" | ") { wall ->
                        wall.name + " " + fmt(wall.width) + "×" + fmt(wall.height) +
                            " m; aperture " + fmt(wall.openingsArea) +
                            " m²; superficie netta " + fmt(wall.netArea()) + " m²"
                    }
        })
    }

    private fun copyPrompt() {
        runCatching {
            val text = buildPrompt()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("GE360 sopralluogo V3.1", text))
            toast("Prompt copiato. Contiene dati cliente: verifica prima di condividerli.")
        }.onFailure { toast(it.message ?: "Impossibile preparare il prompt") }
    }

    private fun convertToDraft() {
        capture()
        if (survey.generatedEstimateId.isNotBlank()) return openEstimate(survey.generatedEstimateId)
        if (survey.rooms.isEmpty()) return toast("Aggiungi un ambiente")
        val warnings = SmartSurveyDraftV1.checks(survey)
        AlertDialog.Builder(this)
            .setTitle("Creare una BOZZA Preventivi?")
            .setMessage(
                "Le " + survey.rooms.size + " stanze e le lavorazioni saranno copiate in una " +
                    "NUOVA bozza modificabile. Nessun preventivo precedente viene alterato.\n\n" +
                    (if (warnings.isNotEmpty()) "Attenzione: " + warnings.size +
                        " dati da verificare (es. prezzi e misure). La bozza NON è pronta per invio." else
                        "Controlli preliminari completati.") +
                    "\n\nConfermi?"
            )
            .setPositiveButton("Crea bozza") { _, _ ->
                runCatching {
                    val estimates = EstimateStore(this)
                    try {
                        val existing = estimates.getByGe360Id(survey.id)
                        val record = existing ?: SmartSurveyDraftV1.toEstimate(survey).also { estimates.save(it) }
                        survey.generatedEstimateId = record.ge360Id
                        persist() // idempotenza: il sopralluogo non può creare due quote
                        EstimateRoomStore(this).use { roomStore ->
                            SmartSurveyDraftV1.roomModels(survey).forEach(roomStore::save)
                        }
                        openEstimate(record.ge360Id)
                    } finally { estimates.close() }
                }.onFailure { toast("Bozza non completata: " + (it.message ?: "errore")) }
            }.setNegativeButton("Annulla", null).show()
    }

    private fun openEstimate(id: String) {
        startActivity(Intent(this, EstimatesActivity::class.java).putExtra(EstimatesActivity.EXTRA_ESTIMATE_ID, id))
        finish()
    }

    private fun label(value: String, bold: Boolean = false, holder: LinearLayout = body) {
        holder.addView(TextView(this).apply {
            text = value; textSize = if (bold) 16f else 14f
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(3), dp(12), dp(3), dp(5))
        })
    }
    private fun info(value: String, holder: LinearLayout = body) {
        holder.addView(TextView(this).apply {
            text = value; textSize = 13f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(dp(7), dp(9), dp(7), dp(9))
        })
    }
    private fun button(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label; isAllCaps = false
        setOnClickListener { onClick() }
    }
    private fun action(label: String, onClick: () -> Unit) {
        body.addView(button(label, onClick), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
    }
    private fun input(hint: String, value: String, multiline: Boolean = false, holder: LinearLayout = body): EditText {
        label(hint, false, holder)
        return EditText(this).apply {
            setText(value); this.hint = hint
            if (multiline) minLines = 2 else isSingleLine = true
            holder.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
    private fun number(hint: String, value: Double, holder: LinearLayout = body, showZero: Boolean = false): EditText {
        val field = input(hint, if (value > 0 || showZero) fmt(value) else "", holder = holder)
        field.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        return field
    }
    private fun amount(edit: EditText): Double? {
        val raw = edit.text.toString().trim().replace(',', '.')
        if (raw.isBlank()) return 0.0
        return raw.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    }
    private fun spinner(hint: String, choices: List<String>, selected: Int, holder: LinearLayout = body): Spinner {
        label(hint, holder = holder)
        return Spinner(this).apply {
            adapter = ArrayAdapter(this@SmartSurveyActivity, android.R.layout.simple_spinner_dropdown_item, choices)
            setSelection(selected.coerceIn(0, choices.lastIndex))
            holder.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }
    }
    private fun form() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(10)) }
    private fun fmt(n: Double) = String.format(Locale.ITALY, "%.2f", n)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    companion object { const val EXTRA_SURVEY_ID = "ge360_smart_survey_id" }
}
