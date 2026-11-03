package it.edilmilan.ge360.galleria

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.core.common.ui.BaseGeActivity
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.core.common.ui.GeFilterRow
import it.edilmilan.ge360.core.common.ui.GeListContainer
import it.edilmilan.ge360.core.common.ui.GeSpace
import it.edilmilan.ge360.core.common.ui.GeThumbnails
import it.edilmilan.ge360.core.common.ui.GeType
import it.edilmilan.ge360.core.common.ui.applyGe360Card
import it.edilmilan.ge360.core.common.ui.applyGe360Subtitle
import it.edilmilan.ge360.core.common.ui.applyGe360Title
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.core.common.ui.dp
import it.edilmilan.ge360.core.common.ui.geActionBar
import it.edilmilan.ge360.core.common.ui.geCancelPending
import it.edilmilan.ge360.core.common.ui.geEmptyState
import it.edilmilan.ge360.core.common.ui.geFieldLabel
import it.edilmilan.ge360.core.common.ui.geFilterRow
import it.edilmilan.ge360.core.common.ui.geInput
import it.edilmilan.ge360.core.common.ui.geLoad
import it.edilmilan.ge360.core.common.ui.geLoadDebounced
import it.edilmilan.ge360.core.common.ui.gePrimaryButton
import it.edilmilan.ge360.core.common.ui.geScreen
import it.edilmilan.ge360.core.common.ui.geSearchInput
import it.edilmilan.ge360.core.common.ui.geSecondaryButton
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Galleria foto di cantiere.
 *
 * Seconda schermata di riferimento del design system. Qui il guadagno grosso e'
 * nelle miniature: prima ogni riga decodificava la foto per intero dentro
 * `getView`, sul thread della UI, senza cache; adesso le miniature sono
 * ridimensionate in background e tenute in [GeThumbnails], e le foto si vedono
 * in griglia invece che in una lista a una colonna.
 */
class GalleryActivity : BaseGeActivity() {

    private lateinit var store: GalleryStore
    private lateinit var archive: GalleryArchive
    private lateinit var jobsites: JobsiteStore

    private lateinit var jobSpinner: Spinner
    private lateinit var phaseRow: GeFilterRow
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var listContainer: GeListContainer

    private val adapter = PhotoAdapter()
    private var jobRows: List<JobsiteRecord> = emptyList()
    private var favoritesOnly = false
    private var replaceOnImport = false
    private var selectedPhase = PHASE_ALL
    private var cameraTempFile: File? = null
    private var thumbSizePx = 0

    private val searchToken = Any()

    private data class IngestResult(val photo: GalleryPhoto, val duplicate: Boolean)

    private data class Screen(
        val rows: List<GalleryPhoto>,
        val statusText: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GalleryStore(this).managed()
        jobsites = JobsiteStore(this).managed()
        archive = GalleryArchive(this)
        setContentView(buildScreen())
        loadJobsites(keepId = null)
    }

    override fun onResume() {
        super.onResume()
        if (::listContainer.isInitialized) {
            loadJobsites(keepId = selectedJob()?.ge360Id)
        }
    }

    override fun onDestroy() {
        geCancelPending(searchToken)
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Costruzione della schermata
    // -----------------------------------------------------------------------

    private fun buildScreen(): View {
        val root = geScreen()

        root.addView(
            buildScreenHeaderWithSubtitle(
                context = this,
                title = "Galleria cantieri",
                subtitle = "Originali sul telefono · Drive secondario",
                onBack = { finish() },
                trailingLabel = "Archivio",
                onTrailingClick = { archiveMenu() }
            )
        )

        jobSpinner = Spinner(this)
        root.addView(
            jobSpinner,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        )

        search = geSearchInput("Cerca note, tag, file…").apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                    refresh(debounced = true)

                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search)

        // Fasi e preferite in un'unica riga di filtri, invece di uno spinner
        // piu' due bottoni slegati.
        phaseRow = geFilterRow(listOf(PHASE_ALL) + GalleryPhases.ALL + FAVORITES, PHASE_ALL) { choice ->
            favoritesOnly = choice == FAVORITES
            selectedPhase = if (choice == FAVORITES) PHASE_ALL else choice
            refresh()
        }
        root.addView(phaseRow)

        status = TextView(this).apply {
            applyGe360Subtitle(GeType.CAPTION)
            setPadding(dp(GeSpace.XS), dp(GeSpace.MD), dp(GeSpace.XS), dp(GeSpace.XS))
        }
        root.addView(status)

        thumbSizePx = resources.displayMetrics.widthPixels / columnCount()
        listContainer = GeListContainer(this).apply {
            list.layoutManager = GridLayoutManager(this@GalleryActivity, columnCount())
            list.adapter = adapter
            list.setHasFixedSize(true)
        }
        root.addView(
            listContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        root.addView(
            geActionBar(
                gePrimaryButton("Scatta foto") { capturePhoto() },
                geSecondaryButton("Da galleria") { importPhotos() }
            )
        )
        return root
    }

    /** Due colonne sul telefono, tre o piu' su schermi larghi. */
    private fun columnCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return (widthDp / 180).coerceIn(2, 4)
    }

    // -----------------------------------------------------------------------
    // Dati
    // -----------------------------------------------------------------------

    private fun loadJobsites(keepId: String?) {
        geLoad({ jobsites.list() }) { outcome ->
            outcome.onSuccess { rows ->
                jobRows = rows
                installJobSpinner(keepId)
                refresh()
            }.onFailure {
                jobRows = emptyList()
                installJobSpinner(null)
                refresh()
            }
        }
    }

    private fun installJobSpinner(keepId: String?) {
        val labels = mutableListOf("Tutti i cantieri").apply {
            addAll(jobRows.map { "${it.code} · ${it.displayTitle()}" })
        }
        jobSpinner.onItemSelectedListener = null
        jobSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val index = jobRows.indexOfFirst { it.ge360Id == keepId }
        jobSpinner.setSelection(if (index >= 0) index + 1 else 0)
        jobSpinner.onItemSelectedListener = Selected { refresh() }
    }

    private fun selectedJob(): JobsiteRecord? =
        if (::jobSpinner.isInitialized) jobRows.getOrNull(jobSpinner.selectedItemPosition - 1) else null

    private fun phaseForNewPhoto(): String =
        selectedPhase.takeIf { it != PHASE_ALL } ?: GalleryPhases.DURING

    private fun refresh(debounced: Boolean = false) {
        if (!::listContainer.isInitialized) return

        val job = selectedJob()
        val jobId = job?.ge360Id.orEmpty()
        val phase = selectedPhase
        val query = if (::search.isInitialized) search.text.toString() else ""
        val favorites = favoritesOnly

        val work = {
            val rows = store.list(
                jobsiteId = jobId,
                phase = phase,
                query = query,
                favoritesOnly = favorites
            )
            val text = if (job == null) {
                "${rows.size} foto · archivio locale ${if (archive.localReady()) "OK" else "da configurare"} · " +
                    "Drive ${if (archive.driveReady()) "OK" else "non configurato"}"
            } else {
                val s = store.summary(job.ge360Id)
                "${job.code} · ${s.total} foto · prima ${s.before} · durante ${s.during} · " +
                    "dopo ${s.after} · problemi ${s.problems} · Drive attesa ${s.drivePending}"
            }
            Screen(rows, text)
        }

        val onResult: (Result<Screen>) -> Unit = { outcome ->
            outcome.onSuccess { screen ->
                status.text = screen.statusText
                adapter.submit(screen.rows)
                if (screen.rows.isEmpty()) {
                    listContainer.showMessage(emptyStateView())
                } else {
                    listContainer.showList()
                }
            }.onFailure { error ->
                listContainer.showMessage(
                    geEmptyState(
                        title = "Indice non leggibile",
                        message = error.message.orEmpty().take(160),
                        actionLabel = "Riprova"
                    ) { refresh() }
                )
            }
        }

        if (debounced) {
            geLoadDebounced(searchToken, work = work, onResult = onResult)
        } else {
            geLoad(work, onResult)
        }
    }

    private fun emptyStateView(): View {
        val filtering = search.text.isNotBlank() || favoritesOnly || selectedPhase != PHASE_ALL
        return when {
            filtering -> geEmptyState(
                title = "Nessuna foto",
                message = "Nessun risultato con questi filtri.",
                actionLabel = "Azzera filtri"
            ) {
                search.setText("")
                favoritesOnly = false
                selectedPhase = PHASE_ALL
                phaseRow.select(PHASE_ALL)
                refresh()
            }

            !archive.localReady() -> geEmptyState(
                title = "Archivio da configurare",
                message = "Scegli la cartella del telefono dove salvare gli originali delle foto.",
                actionLabel = "Scegli cartella"
            ) { pickTree(REQ_LOCAL_ROOT) }

            else -> geEmptyState(
                title = "Ancora nessuna foto",
                message = "Scatta la prima foto del cantiere: resta sul telefono e viene indicizzata per fase.",
                actionLabel = "Scatta foto"
            ) { capturePhoto() }
        }
    }

    // -----------------------------------------------------------------------
    // Griglia
    // -----------------------------------------------------------------------

    private class CellViews(
        val card: MaterialCardView,
        val image: ImageView,
        val caption: TextView,
        val meta: TextView
    )

    private inner class PhotoHolder(val views: CellViews) : RecyclerView.ViewHolder(views.card)

    private inner class PhotoAdapter : RecyclerView.Adapter<PhotoHolder>() {

        private var items: List<GalleryPhoto> = emptyList()

        fun submit(rows: List<GalleryPhoto>) {
            items = rows
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoHolder {
            val context = this@GalleryActivity

            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(130)
                )
            }
            val caption = TextView(context).apply {
                applyGe360Title(GeType.BODY)
                maxLines = 1
            }
            val meta = TextView(context).apply {
                applyGe360Subtitle(GeType.MICRO)
                maxLines = 2
                setPadding(0, context.dp(2), 0, 0)
            }

            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(image)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(
                        context.dp(GeSpace.SM),
                        context.dp(GeSpace.SM),
                        context.dp(GeSpace.SM),
                        context.dp(GeSpace.SM)
                    )
                    addView(caption)
                    addView(meta)
                })
            }

            val card = MaterialCardView(context).apply {
                applyGe360Card()
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    val gap = context.dp(GeSpace.XS)
                    setMargins(gap, gap, gap, gap)
                }
                addView(body)
            }

            return PhotoHolder(CellViews(card, image, caption, meta))
        }

        override fun onBindViewHolder(holder: PhotoHolder, position: Int) {
            val photo = items[position]
            val views = holder.views

            GeThumbnails.load(views.image, photo.localUri, thumbSizePx)

            views.caption.text = "${if (photo.favorite) "★ " else ""}${photo.phase}"
            views.meta.text = buildString {
                append(photo.jobsiteCode.ifBlank { "senza cantiere" })
                append(" · ")
                append(formatMoment(photo.createdAt))
                append(" · ")
                append(if (photo.driveStatus == GalleryStore.DRIVE_OK) "Drive ✓" else "Drive …")
                val label = photo.note.ifBlank { photo.fileName }
                if (label.isNotBlank()) {
                    append("\n")
                    append(label)
                }
            }

            views.card.setOnClickListener { photoActions(photo) }
            views.card.setOnLongClickListener {
                editMetadata(photo.copy())
                true
            }
        }
    }

    // -----------------------------------------------------------------------
    // Acquisizione foto
    // -----------------------------------------------------------------------

    private fun ensureNewPhotoContext(): JobsiteRecord? {
        val job = selectedJob()
        if (job == null) {
            toast("Scegli prima un cantiere")
            return null
        }
        if (!archive.localReady()) {
            toast("Configura prima la cartella archivio")
            archiveMenu()
            return null
        }
        return job
    }

    private fun capturePhoto() {
        ensureNewPhotoContext() ?: return
        val dir = File(cacheDir, "gallery-camera").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        cameraTempFile = file
        val uri = FileProvider.getUriForFile(this, "$packageName.gallery.files", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("GE360 photo", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) == null) return toast("Fotocamera non disponibile")
        startActivityForResult(intent, REQ_CAMERA)
    }

    private fun importPhotos() {
        ensureNewPhotoContext() ?: return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, REQ_IMAGES)
    }

    /**
     * Importa una o piu' foto.
     *
     * Copia del file e calcolo dell'impronta SHA-256 sono I/O pesante: con una
     * selezione multipla, farlo sul thread della UI bloccava l'app per secondi.
     */
    private fun ingest(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val job = ensureNewPhotoContext() ?: return
        val phase = phaseForNewPhoto()

        geLoad({
            uris.map { uri ->
                val stored = archive.copyToLocal(uri, job, phase)
                val duplicate = store.findByHash(stored.sha256)
                if (duplicate != null) {
                    archive.deleteLocal(stored.uri)
                    IngestResult(duplicate, duplicate = true)
                } else {
                    val photo = GalleryPhoto(
                        jobsiteId = job.ge360Id,
                        jobsiteCode = job.code,
                        jobsiteTitle = job.displayTitle(),
                        phase = phase,
                        localUri = stored.uri,
                        fileName = stored.fileName,
                        mimeType = stored.mimeType,
                        sizeBytes = stored.sizeBytes,
                        sha256 = stored.sha256
                    ).also { store.save(it) }
                    IngestResult(photo, duplicate = false)
                }
            }
        }) { outcome ->
            outcome.onSuccess { results ->
                val duplicates = results.count { it.duplicate }
                val saved = results.size - duplicates
                toast(
                    when {
                        saved == 0 -> "Foto già presenti · nessuna aggiunta"
                        duplicates == 0 -> "$saved foto salvate in $phase"
                        else -> "$saved salvate · $duplicates duplicate scartate"
                    }
                )
                refresh()
            }.onFailure { toast("Salvataggio fallito: ${it.message}") }
        }
    }

    // -----------------------------------------------------------------------
    // Azioni su una foto
    // -----------------------------------------------------------------------

    private fun photoActions(photo: GalleryPhoto) {
        val actions = arrayOf(
            "Apri",
            "Modifica note/tag",
            if (photo.favorite) "Togli preferita" else "★ Preferita",
            "Copia su Drive",
            "Condividi",
            "Elimina"
        )
        AlertDialog.Builder(this)
            .setTitle(photo.fileName)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> openPhoto(photo)
                    1 -> editMetadata(photo.copy())
                    2 -> {
                        photo.favorite = !photo.favorite
                        geLoad({ store.save(photo) }) { it.onSuccess { refresh() } }
                    }

                    3 -> syncOne(photo)
                    4 -> sharePhoto(photo)
                    5 -> confirmDelete(photo)
                }
            }.show()
    }

    private fun editMetadata(photo: GalleryPhoto) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(GeSpace.XL), dp(GeSpace.SM), dp(GeSpace.XL), dp(GeSpace.SM))
        }

        val note = geInput(hint = "Nota", singleLine = false, initial = photo.note).apply { minLines = 3 }
        val tags = geInput(hint = "Tag, separati da virgola", initial = photo.tags)
        val favorite = CheckBox(this).apply {
            text = "Preferita"
            isChecked = photo.favorite
            setTextColor(Ge360Brand.Text)
        }

        box.addView(TextView(this).apply {
            text = "${photo.jobsiteCode} · ${photo.phase}"
            applyGe360Title(GeType.H3)
        })
        box.addView(geFieldLabel("Nota"))
        box.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100)))
        box.addView(geFieldLabel("Tag"))
        box.addView(tags, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        box.addView(favorite, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Metadati foto")
            .setView(box)
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                photo.note = note.text.toString().trim()
                photo.tags = tags.text.toString().trim()
                photo.favorite = favorite.isChecked
                geLoad({ store.save(photo) }) { outcome ->
                    outcome.onSuccess {
                        dialog.dismiss()
                        refresh()
                    }.onFailure { toast("Salvataggio non riuscito: ${it.message}") }
                }
            }
        }
        dialog.show()
    }

    private fun syncOne(photo: GalleryPhoto) {
        if (!archive.driveReady()) {
            toast("Configura prima la cartella Drive")
            return archiveMenu()
        }
        geLoad({
            val job = jobRows.firstOrNull { it.ge360Id == photo.jobsiteId }
                ?: jobsites.getByGe360Id(photo.jobsiteId)
                ?: error("Cantiere non trovato")
            runCatching { archive.copyToDrive(photo, job) }
                .onSuccess {
                    photo.driveUri = it.uri
                    photo.driveStatus = GalleryStore.DRIVE_OK
                    store.save(photo)
                }
                .onFailure {
                    photo.driveStatus = GalleryStore.DRIVE_ERROR
                    store.save(photo)
                    throw it
                }
        }) { outcome ->
            outcome.onSuccess { toast("Copia Drive completata") }
                .onFailure { toast("Drive: ${it.message}") }
            refresh()
        }
    }

    private fun syncPending() {
        if (!archive.driveReady()) return toast("Configura prima la cartella Drive")
        val jobId = selectedJob()?.ge360Id.orEmpty()

        geLoad({
            var ok = 0
            var failed = 0
            store.pendingDrive(jobId).forEach { photo ->
                val job = jobRows.firstOrNull { it.ge360Id == photo.jobsiteId }
                    ?: jobsites.getByGe360Id(photo.jobsiteId)
                if (job == null) {
                    failed++
                } else {
                    runCatching { archive.copyToDrive(photo, job) }
                        .onSuccess {
                            photo.driveUri = it.uri
                            photo.driveStatus = GalleryStore.DRIVE_OK
                            store.save(photo)
                            ok++
                        }
                        .onFailure {
                            photo.driveStatus = GalleryStore.DRIVE_ERROR
                            store.save(photo)
                            failed++
                        }
                }
            }
            ok to failed
        }) { outcome ->
            outcome.onSuccess { (ok, failed) ->
                toast("Drive: $ok copiate${if (failed > 0) " · $failed errori" else ""}")
                refresh()
            }.onFailure { toast("Sincronizzazione non riuscita: ${it.message}") }
        }
    }

    private fun openPhoto(photo: GalleryPhoto) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(photo.localUri), photo.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { toast("Impossibile aprire la foto") }
    }

    private fun sharePhoto(photo: GalleryPhoto) {
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = photo.mimeType
                putExtra(Intent.EXTRA_STREAM, Uri.parse(photo.localUri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Condividi foto"))
        }.onFailure { toast("Condivisione non disponibile") }
    }

    private fun confirmDelete(photo: GalleryPhoto) {
        AlertDialog.Builder(this)
            .setTitle("Elimina foto")
            .setMessage("Eliminare l'originale locale e il suo indice GE360? La copia Drive, se presente, non viene eliminata.")
            .setPositiveButton("Elimina") { _, _ ->
                geLoad({
                    val removed = archive.deleteLocal(photo.localUri)
                    if (removed) store.deleteMetadata(photo.id)
                    removed
                }) { outcome ->
                    outcome.onSuccess { removed ->
                        GeThumbnails.invalidate(photo.localUri)
                        toast(if (removed) "Foto eliminata" else "Non riesco a eliminare il file locale")
                        refresh()
                    }.onFailure { toast("Eliminazione non riuscita: ${it.message}") }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // -----------------------------------------------------------------------
    // Archivio
    // -----------------------------------------------------------------------

    private fun archiveMenu() {
        val local = if (archive.localReady()) "✓ Locale configurato" else "⚠ Locale da configurare"
        val drive = if (archive.driveReady()) "✓ Drive configurato" else "Drive non configurato"
        AlertDialog.Builder(this)
            .setTitle("Archivio Galleria")
            .setItems(
                arrayOf(
                    local,
                    drive,
                    "Sincronizza copie Drive in attesa",
                    "Esporta indice JSON",
                    "Importa indice e unisci",
                    "Importa indice e sostituisci"
                )
            ) { _, which ->
                when (which) {
                    0 -> pickTree(REQ_LOCAL_ROOT)
                    1 -> pickTree(REQ_DRIVE_ROOT)
                    2 -> syncPending()
                    3 -> exportIndex()
                    4 -> {
                        replaceOnImport = false
                        importIndex()
                    }

                    5 -> {
                        replaceOnImport = true
                        importIndex()
                    }
                }
            }.show()
    }

    private fun pickTree(request: Int) = startActivityForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }, request
    )

    private fun exportIndex() = startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_TITLE, "ge360-galleria-indice.json")
    }, REQ_EXPORT)

    private fun importIndex() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "application/json"
        addCategory(Intent.CATEGORY_OPENABLE)
    }, REQ_IMPORT)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_CAMERA) {
            if (resultCode == Activity.RESULT_OK) {
                val file = cameraTempFile?.takeIf { it.exists() && it.length() > 0 }
                if (file == null) {
                    toast("Scatto non disponibile")
                } else {
                    ingest(listOf(FileProvider.getUriForFile(this, "$packageName.gallery.files", file)))
                }
            }
            cameraTempFile = null
            return
        }

        if (resultCode != Activity.RESULT_OK || data == null) return
        when (requestCode) {
            REQ_IMAGES -> {
                val clip = data.clipData
                val uris = when {
                    clip != null -> (0 until clip.itemCount).map { clip.getItemAt(it).uri }
                    data.data != null -> listOf(data.data!!)
                    else -> emptyList()
                }
                ingest(uris)
            }

            REQ_LOCAL_ROOT -> data.data?.let {
                archive.setLocalRoot(it)
                toast("Archivio locale collegato")
                refresh()
            }

            REQ_DRIVE_ROOT -> data.data?.let {
                archive.setDriveRoot(it)
                toast("Cartella Drive collegata")
                refresh()
            }

            REQ_EXPORT -> data.data?.let { uri ->
                geLoad({
                    val text = store.exportJson()
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
                        ?: error("File non scrivibile")
                }) { outcome ->
                    outcome.onSuccess { toast("Indice esportato") }
                        .onFailure { toast(it.message ?: "Errore export") }
                }
            }

            REQ_IMPORT -> data.data?.let { uri ->
                val replace = replaceOnImport
                geLoad({
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("File non leggibile")
                    store.importMetadata(text, replace)
                }) { outcome ->
                    outcome.onSuccess {
                        toast("Importati $it metadati")
                        refresh()
                    }.onFailure { toast("Import fallito: ${it.message}") }
                }
            }
        }
    }

    // -----------------------------------------------------------------------

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun formatMoment(value: Long): String =
        DateFormat.getDateInstance(DateFormat.SHORT, Locale.ITALY).format(Date(value))

    private class Selected(private val block: () -> Unit) : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = block()
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    companion object {
        private const val PHASE_ALL = "TUTTE"
        private const val FAVORITES = "★ Preferite"

        private const val REQ_CAMERA = 801
        private const val REQ_IMAGES = 802
        private const val REQ_LOCAL_ROOT = 803
        private const val REQ_DRIVE_ROOT = 804
        private const val REQ_EXPORT = 805
        private const val REQ_IMPORT = 806
    }
}
