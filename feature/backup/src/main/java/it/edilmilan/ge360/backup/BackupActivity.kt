package it.edilmilan.ge360.backup

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class BackupActivity : AppCompatActivity() {
    private lateinit var coordinator: BackupCoordinator
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var auto: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = BackupCoordinator(this)
        setContentView(buildScreen())
        BackupScheduler.schedule(this)
        refresh()
    }

    private fun buildScreen(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(24)) }
        scroll.addView(root)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(MaterialButton(this@BackupActivity).apply { text = "←"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(54), dp(48)))
            addView(LinearLayout(this@BackupActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@BackupActivity).apply { text = "Backup & sincronizzazione"; textSize = 23f; setTypeface(typeface, Typeface.BOLD) })
                addView(TextView(this@BackupActivity).apply { text = "GE360 · telefono + Drive · ultimi 10"; textSize = 13f })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        root.addView(card().apply {
            addView(LinearLayout(this@BackupActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
                status = TextView(this@BackupActivity).apply { textSize = 14f }
                addView(status)
                auto = CheckBox(this@BackupActivity).apply {
                    text = "Backup automatico giornaliero"
                    isChecked = BackupScheduler.isEnabled(this@BackupActivity)
                    setOnCheckedChangeListener { _, checked -> BackupScheduler.setEnabled(this@BackupActivity, checked); refresh() }
                }
                addView(auto)
            })
        })

        root.addView(button("Crea backup adesso") { runBackup(false) })
        root.addView(button("Backup completo con fotografie") { confirmFullBackup() })
        root.addView(button("Scegli cartella backup sul telefono") { choosePersistentLocal() })
        root.addView(button("Copia ultimo backup nella cartella telefono") { copyLatestPersistentLocal() })
        root.addView(button("Scegli cartella Google Drive") { chooseDrive() })
        root.addView(button("Copia ultimo backup su Drive") { copyLatestToDrive() })
        root.addView(button("Ripristina da backup GE360") { chooseRestore() })

        root.addView(TextView(this).apply {
            text = "Copie di lavoro locali"; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(2), dp(18), dp(2), dp(8))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        root.addView(TextView(this).apply {
            text = "La copia di lavoro interna può sparire disinstallando l'app. Per un vero backup locale scegli una cartella del telefono: GE360 manterrà lì gli ultimi 10 ZIP. Il backup automatico giornaliero include tutti i dati, PDF e fotografie leggibili."
            textSize = 11f; setPadding(dp(2), dp(14), dp(2), 0)
        })
        return scroll
    }

    private fun runBackup(full: Boolean) {
        status.text = if (full) "Creazione backup completo…" else "Creazione backup…"
        thread(name = "ge360-manual-backup") {
            val result = runCatching {
                val created = coordinator.createBackup(full, copyToDrive = coordinator.archive.driveReady())
                val localCopied = if (coordinator.archive.persistentLocalReady()) {
                    runCatching { coordinator.archive.copyToPersistentLocal(created.info.file) }.isSuccess
                } else false
                created to localCopied
            }
            runOnUiThread {
                result.onSuccess { (created, localCopied) ->
                    toast("Backup verificato: ${created.info.file.name}")
                    status.text = "Backup OK · ${human(created.info.sizeBytes)} · SHA-256 ${created.info.sha256.take(12)}…" +
                        (if (localCopied) " · copia telefono OK" else "") +
                        (if (created.copiedToDrive) " · Drive OK" else "")
                    if (full && created.galleryFilesSkipped > 0) toast("${created.galleryFilesSkipped} foto non leggibili sono state saltate")
                    refreshList()
                }.onFailure { status.text = "Errore backup: ${it.message}"; toast(status.text.toString()) }
            }
        }
    }

    private fun confirmFullBackup() {
        AlertDialog.Builder(this)
            .setTitle("Backup completo")
            .setMessage("Include anche tutte le fotografie leggibili della Galleria. Il file può diventare molto grande. Procedere?")
            .setPositiveButton("Procedi") { _, _ -> runBackup(true) }
            .setNegativeButton("Annulla", null).show()
    }

    private fun choosePersistentLocal() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQ_LOCAL)
    }

    private fun chooseDrive() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQ_DRIVE)
    }

    private fun copyLatestPersistentLocal() {
        val file = coordinator.archive.latestLocal() ?: return toast("Nessun backup disponibile")
        if (!coordinator.archive.persistentLocalReady()) return toast("Configura prima la cartella backup sul telefono")
        status.text = "Copia nella cartella telefono…"
        thread {
            val result = runCatching { coordinator.archive.copyToPersistentLocal(file) }
            runOnUiThread { result.onSuccess { toast("Backup copiato sul telefono"); refresh() }.onFailure { toast("Errore copia locale: ${it.message}") } }
        }
    }

    private fun copyLatestToDrive() {
        val file = coordinator.archive.latestLocal() ?: return toast("Nessun backup disponibile")
        if (!coordinator.archive.driveReady()) return toast("Configura prima la cartella Drive")
        status.text = "Copia su Drive…"
        thread {
            val result = runCatching { coordinator.archive.copyToDrive(file) }
            runOnUiThread { result.onSuccess { toast("Backup copiato su Drive"); refresh() }.onFailure { toast("Errore Drive: ${it.message}") } }
        }
    }

    private fun chooseRestore() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
        }, REQ_RESTORE)
    }

    private fun askRestore(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Ripristina backup")
            .setMessage("Unire il backup ai dati attuali oppure sostituire i dati GE360? Sostituisci è distruttivo per i record attuali dei moduli ripristinati.")
            .setPositiveButton("Unisci") { _, _ -> restore(uri, false) }
            .setNegativeButton("Sostituisci") { _, _ -> confirmReplace(uri) }
            .setNeutralButton("Annulla", null).show()
    }

    private fun confirmReplace(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Conferma sostituzione")
            .setMessage("I dati correnti dei moduli verranno sostituiti con quelli del backup. Continua solo se il backup è quello corretto.")
            .setPositiveButton("Sostituisci") { _, _ -> restore(uri, true) }
            .setNegativeButton("Annulla", null).show()
    }

    private fun restore(uri: Uri, replace: Boolean) {
        status.text = "Verifica checksum e ripristino…"
        thread(name = "ge360-restore") {
            val result = runCatching {
                val file = coordinator.importFromUri(uri)
                coordinator.restore(file, replace)
            }
            runOnUiThread {
                result.onSuccess { r ->
                    val total = r.counts.values.sum()
                    status.text = "Ripristino completato · $total record · ${r.scannerFiles} PDF · ${r.galleryFiles} foto"
                    val detail = buildString {
                        r.counts.forEach { (k, v) -> appendLine("$k: $v") }
                        if (r.warnings.isNotEmpty()) { appendLine(); appendLine("Avvisi:"); r.warnings.forEach { appendLine("• $it") } }
                    }
                    AlertDialog.Builder(this).setTitle("Ripristino completato").setMessage(detail.trim()).setPositiveButton("OK", null).show()
                }.onFailure { status.text = "Ripristino fallito: ${it.message}"; toast(status.text.toString()) }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_LOCAL -> { coordinator.archive.setPersistentLocalRoot(uri); JobsiteReplicaCoordinator.requestSync(this); toast("Cartella backup telefono salvata"); refresh() }
            REQ_DRIVE -> { coordinator.archive.setDriveRoot(uri); JobsiteReplicaCoordinator.requestSync(this); toast("Cartella Drive salvata"); refresh() }
            REQ_RESTORE -> askRestore(uri)
        }
    }

    private fun refresh() {
        val a = coordinator.archive
        val latest = a.latestLocal()
        status.text = buildString {
            append("Copia di lavoro: ")
            append(if (latest == null) "nessun backup" else "${latest.name} · ${human(latest.length())}")
            append("\nBackup telefono persistente: ")
            append(if (a.persistentLocalReady()) "configurato" else "DA CONFIGURARE")
            append("\nGoogle Drive: ")
            append(if (a.driveReady()) "configurato" else "non configurato")
            val whenAt = a.lastBackupAt()
            if (whenAt > 0) append("\nUltimo backup creato: ${date(whenAt)}")
            if (a.lastPersistentLocalAt() > 0) append("\nUltima copia telefono: ${date(a.lastPersistentLocalAt())}")
            if (a.lastDriveBackupAt() > 0) append("\nUltima copia Drive: ${date(a.lastDriveBackupAt())}")
            BackupScheduler.lastStatus(this@BackupActivity).takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
        }
        refreshList()
    }

    private fun refreshList() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val rows = coordinator.archive.localBackups()
        if (rows.isEmpty()) list.addView(TextView(this).apply { text = "Nessuna copia di lavoro."; setPadding(dp(4), dp(8), dp(4), dp(8)) })
        rows.forEach { file ->
            list.addView(card().apply {
                addView(TextView(this@BackupActivity).apply {
                    text = "${file.name}\n${human(file.length())} · ${date(file.lastModified())}"
                    textSize = 13f; setPadding(dp(12), dp(10), dp(12), dp(10)); setTextIsSelectable(true)
                })
            })
        }
    }

    private fun button(text: String, action: () -> Unit) = MaterialButton(this).apply {
        this.text = text; setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(8) }
    }
    private fun card() = MaterialCardView(this).apply { radius = dp(14).toFloat(); cardElevation = dp(1).toFloat() }
    private fun human(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.ITALY, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.ITALY, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.ITALY, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
    private fun date(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.ITALY).format(Date(ms))
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_LOCAL = 800
        private const val REQ_DRIVE = 801
        private const val REQ_RESTORE = 802
    }
}
