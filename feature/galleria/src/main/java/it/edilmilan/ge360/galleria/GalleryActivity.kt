package it.edilmilan.ge360.galleria

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class GalleryActivity : AppCompatActivity() {
    private lateinit var store: GalleryStore
    private lateinit var archive: GalleryArchive
    private lateinit var jobsites: JobsiteStore
    private lateinit var jobSpinner: Spinner
    private lateinit var phaseSpinner: Spinner
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var adapter: PhotoAdapter
    private var jobRows: List<JobsiteRecord> = emptyList()
    private var favoritesOnly = false
    private var replaceOnImport = false
    private var cameraTempFile: File? = null

    private data class IngestResult(val photo: GalleryPhoto, val duplicate: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GalleryStore(this)
        archive = GalleryArchive(this)
        jobsites = JobsiteStore(this)
        jobRows = jobsites.list()
        setContentView(buildScreen())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            val keep = selectedJob()?.ge360Id
            jobRows = jobsites.list()
            installJobs(keep)
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
            title = "Galleria cantieri",
            subtitle = "Originali sul telefono · Drive secondario",
            onBack = { finish() },
            trailingLabel = "Archivio",
            onTrailingClick = { archiveMenu() }
        ))

        jobSpinner = Spinner(this)
        installJobs(null)
        root.addView(jobSpinner, lp52())

        phaseSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@GalleryActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("TUTTE") + GalleryPhases.ALL
            )
            onItemSelectedListener = Selected { refresh() }
        }
        root.addView(phaseSpinner, lp52())

        status = TextView(this).apply { textSize = 13f; setPadding(dp(4), dp(5), dp(4), dp(7)) }
        root.addView(status)

        search = EditText(this).apply {
            hint = "Cerca note, tag, file…"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search, lp52())

        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filters.addView(MaterialButton(this).apply {
            text = "Tutte"
            setOnClickListener { favoritesOnly = false; refresh() }
        })
        filters.addView(MaterialButton(this).apply {
            text = "★ Preferite"
            setOnClickListener { favoritesOnly = true; refresh() }
        })
        root.addView(HorizontalScrollView(this).apply { addView(filters); isHorizontalScrollBarEnabled = false }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        adapter = PhotoAdapter()
        root.addView(ListView(this).apply {
            dividerHeight = 0
            adapter = this@GalleryActivity.adapter
            setOnItemClickListener { _, _, p, _ -> photoActions(this@GalleryActivity.adapter.rows[p]) }
            setOnItemLongClickListener { _, _, p, _ -> editMetadata(this@GalleryActivity.adapter.rows[p]); true }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(MaterialButton(this@GalleryActivity).apply {
                text = "📷 Scatta"
                setOnClickListener { capturePhoto() }
            }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(6) })
            addView(MaterialButton(this@GalleryActivity).apply {
                text = "＋ Galleria"
                setOnClickListener { importPhotos() }
            }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(6) })
        })
        return root
    }

    private fun installJobs(keepId: String?) {
        if (!::jobSpinner.isInitialized) return
        val labels = mutableListOf("Tutti i cantieri").apply {
            addAll(jobRows.map { "${it.code} · ${it.displayTitle()}" })
        }
        jobSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val index = jobRows.indexOfFirst { it.ge360Id == keepId }
        jobSpinner.setSelection(if (index >= 0) index + 1 else 0)
        jobSpinner.onItemSelectedListener = Selected { refresh() }
    }

    private fun selectedJob(): JobsiteRecord? = jobRows.getOrNull(jobSpinner.selectedItemPosition - 1)
    private fun selectedPhase(): String = phaseSpinner.selectedItem?.toString() ?: "TUTTE"
    private fun phaseForNewPhoto(): String = selectedPhase().takeIf { it != "TUTTE" } ?: GalleryPhases.DURING

    private fun refresh() {
        if (!::adapter.isInitialized) return
        val job = selectedJob()
        val rows = store.list(
            jobsiteId = job?.ge360Id.orEmpty(),
            phase = selectedPhase(),
            query = if (::search.isInitialized) search.text.toString() else "",
            favoritesOnly = favoritesOnly
        )
        adapter.rows = rows
        adapter.notifyDataSetChanged()
        status.text = if (job == null) {
            "${rows.size} foto · archivio locale ${if (archive.localReady()) "OK" else "da configurare"} · Drive ${if (archive.driveReady()) "OK" else "non configurato"}"
        } else {
            val s = store.summary(job.ge360Id)
            "${job.code} · ${s.total} foto · prima ${s.before} · durante ${s.during} · dopo ${s.after} · problemi ${s.problems} · Drive attesa ${s.drivePending}"
        }
    }

    private fun ensureNewPhotoContext(): JobsiteRecord? {
        val job = selectedJob()
        if (job == null) { toast("Scegli prima un cantiere"); return null }
        if (!archive.localReady()) { toast("Configura prima la cartella archivio"); archiveMenu(); return null }
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

    private fun ingest(uri: Uri) {
        val job = ensureNewPhotoContext() ?: return
        runCatching {
            val stored = archive.copyToLocal(uri, job, phaseForNewPhoto())
            val duplicate = store.findByHash(stored.sha256)
            if (duplicate != null) {
                archive.deleteLocal(stored.uri)
                return@runCatching IngestResult(duplicate, duplicate = true)
            }
            val photo = GalleryPhoto(
                jobsiteId = job.ge360Id,
                jobsiteCode = job.code,
                jobsiteTitle = job.displayTitle(),
                phase = phaseForNewPhoto(),
                localUri = stored.uri,
                fileName = stored.fileName,
                mimeType = stored.mimeType,
                sizeBytes = stored.sizeBytes,
                sha256 = stored.sha256
            ).also { store.save(it) }
            IngestResult(photo, duplicate = false)
        }.onSuccess { result ->
            toast(if (result.duplicate) "Foto già presente · copia duplicata rimossa" else "Foto salvata in ${result.photo.phase}")
            refresh()
        }.onFailure { toast("Salvataggio fallito: ${it.message}") }
    }

    private fun photoActions(photo: GalleryPhoto) {
        AlertDialog.Builder(this)
            .setTitle(photo.fileName)
            .setItems(arrayOf("Apri", "Modifica note/tag", if (photo.favorite) "Togli preferita" else "★ Preferita", "Copia su Drive", "Condividi", "Elimina")) { _, p ->
                when (p) {
                    0 -> openPhoto(photo)
                    1 -> editMetadata(photo.copy())
                    2 -> { photo.favorite = !photo.favorite; store.save(photo); refresh() }
                    3 -> syncOne(photo)
                    4 -> sharePhoto(photo)
                    5 -> confirmDelete(photo)
                }
            }.show()
    }

    private fun editMetadata(photo: GalleryPhoto) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val note = EditText(this).apply { hint = "Nota"; setText(photo.note); minLines = 3; gravity = Gravity.TOP }
        val tags = EditText(this).apply { hint = "Tag, separati da virgola"; setText(photo.tags); isSingleLine = true }
        val fav = CheckBox(this).apply { text = "Preferita"; isChecked = photo.favorite }
        box.addView(TextView(this).apply { text = "${photo.jobsiteCode} · ${photo.phase}"; setTypeface(typeface, Typeface.BOLD) })
        box.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100)))
        box.addView(tags, lp52())
        box.addView(fav, lp52())
        val d = AlertDialog.Builder(this).setTitle("Metadati foto").setView(box).setPositiveButton("Salva", null).setNegativeButton("Annulla", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                photo.note = note.text.toString().trim()
                photo.tags = tags.text.toString().trim()
                photo.favorite = fav.isChecked
                store.save(photo)
                d.dismiss(); refresh()
            }
        }
        d.show()
    }

    private fun syncOne(photo: GalleryPhoto) {
        val job = jobRows.firstOrNull { it.ge360Id == photo.jobsiteId } ?: jobsites.getByGe360Id(photo.jobsiteId)
        if (job == null) return toast("Cantiere non trovato")
        if (!archive.driveReady()) { toast("Configura prima la cartella Drive"); return archiveMenu() }
        runCatching { archive.copyToDrive(photo, job) }
            .onSuccess {
                photo.driveUri = it.uri
                photo.driveStatus = GalleryStore.DRIVE_OK
                store.save(photo)
                toast("Copia Drive completata")
                refresh()
            }
            .onFailure {
                photo.driveStatus = GalleryStore.DRIVE_ERROR
                store.save(photo)
                toast("Drive: ${it.message}")
                refresh()
            }
    }

    private fun syncPending() {
        if (!archive.driveReady()) return toast("Configura prima la cartella Drive")
        val pending = store.pendingDrive(selectedJob()?.ge360Id.orEmpty())
        var ok = 0
        var failed = 0
        pending.forEach { photo ->
            val job = jobRows.firstOrNull { it.ge360Id == photo.jobsiteId } ?: jobsites.getByGe360Id(photo.jobsiteId)
            if (job == null) { failed++; return@forEach }
            runCatching { archive.copyToDrive(photo, job) }
                .onSuccess { photo.driveUri = it.uri; photo.driveStatus = GalleryStore.DRIVE_OK; store.save(photo); ok++ }
                .onFailure { photo.driveStatus = GalleryStore.DRIVE_ERROR; store.save(photo); failed++ }
        }
        toast("Drive: $ok copiate${if (failed > 0) " · $failed errori" else ""}")
        refresh()
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
        AlertDialog.Builder(this).setTitle("Elimina foto")
            .setMessage("Eliminare l'originale locale e il suo indice GE360? La copia Drive, se presente, non viene eliminata.")
            .setPositiveButton("Elimina") { _, _ ->
                val removed = archive.deleteLocal(photo.localUri)
                if (removed) {
                    store.deleteMetadata(photo.id)
                    toast("Foto eliminata")
                } else toast("Non riesco a eliminare il file locale")
                refresh()
            }.setNegativeButton("Annulla", null).show()
    }

    private fun archiveMenu() {
        val local = if (archive.localReady()) "✓ Locale configurato" else "⚠ Locale da configurare"
        val drive = if (archive.driveReady()) "✓ Drive configurato" else "Drive non configurato"
        AlertDialog.Builder(this).setTitle("Archivio Galleria")
            .setItems(arrayOf(local, drive, "Sincronizza copie Drive in attesa", "Esporta indice JSON", "Importa indice e unisci", "Importa indice e sostituisci")) { _, p ->
                when (p) {
                    0 -> pickTree(REQ_LOCAL_ROOT)
                    1 -> pickTree(REQ_DRIVE_ROOT)
                    2 -> syncPending()
                    3 -> exportIndex()
                    4 -> { replaceOnImport = false; importIndex() }
                    5 -> { replaceOnImport = true; importIndex() }
                }
            }.show()
    }

    private fun pickTree(request: Int) = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }, request)

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
                cameraTempFile?.takeIf { it.exists() && it.length() > 0 }?.let { file ->
                    val uri = FileProvider.getUriForFile(this, "$packageName.gallery.files", file)
                    ingest(uri)
                    file.delete()
                } ?: toast("Scatto non disponibile")
            }
            cameraTempFile = null
            return
        }
        if (resultCode != Activity.RESULT_OK || data == null) return
        when (requestCode) {
            REQ_IMAGES -> {
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) ingest(clip.getItemAt(i).uri) }
                    ?: data.data?.let(::ingest)
            }
            REQ_LOCAL_ROOT -> data.data?.let { archive.setLocalRoot(it); toast("Archivio locale collegato"); refresh() }
            REQ_DRIVE_ROOT -> data.data?.let { archive.setDriveRoot(it); toast("Cartella Drive collegata"); refresh() }
            REQ_EXPORT -> data.data?.let { uri ->
                runCatching { contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(store.exportJson()) } ?: error("File non scrivibile") }
                    .onSuccess { toast("Indice esportato") }.onFailure { toast(it.message ?: "Errore export") }
            }
            REQ_IMPORT -> data.data?.let { uri ->
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("File non leggibile")
                    store.importMetadata(text, replaceOnImport)
                }.onSuccess { toast("Importati $it metadati"); refresh() }.onFailure { toast("Import fallito: ${it.message}") }
            }
        }
    }

    private inner class PhotoAdapter : BaseAdapter() {
        var rows: List<GalleryPhoto> = emptyList()
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val p = rows[position]
            return MaterialCardView(this@GalleryActivity).apply {
                radius = dp(14).toFloat()
                cardElevation = dp(1).toFloat()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(4)) }
                addView(LinearLayout(this@GalleryActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    addView(ImageView(this@GalleryActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(loadThumb(p.localUri))
                    }, LinearLayout.LayoutParams(dp(74), dp(74)).apply { marginEnd = dp(12) })
                    addView(LinearLayout(this@GalleryActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(this@GalleryActivity).apply {
                            text = "${if (p.favorite) "★ " else ""}${p.phase} · ${p.jobsiteCode}"
                            setTypeface(typeface, Typeface.BOLD)
                            textSize = 15f
                        })
                        addView(TextView(this@GalleryActivity).apply {
                            text = p.note.ifBlank { p.fileName }
                            textSize = 12f
                            maxLines = 2
                            setPadding(0, dp(4), 0, 0)
                        })
                        addView(TextView(this@GalleryActivity).apply {
                            text = "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.ITALY).format(Date(p.createdAt))} · Drive ${if (p.driveStatus == GalleryStore.DRIVE_OK) "✓" else "…"}"
                            textSize = 10f
                            setPadding(0, dp(4), 0, 0)
                        })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
            }
        }
    }

    private fun loadThumb(uriText: String): android.graphics.Bitmap? = runCatching {
        contentResolver.openInputStream(Uri.parse(uriText))?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = 8 })
        }
    }.getOrNull()

    private fun lp52() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private class Selected(private val block: () -> Unit) : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = block()
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    companion object {
        private const val REQ_CAMERA = 801
        private const val REQ_IMAGES = 802
        private const val REQ_LOCAL_ROOT = 803
        private const val REQ_DRIVE_ROOT = 804
        private const val REQ_EXPORT = 805
        private const val REQ_IMPORT = 806
    }
}