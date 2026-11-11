package it.edilmilan.ge360.backup

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.core.backup.BackupScratchV9
import it.edilmilan.ge360.planimetrie.AdvancedSurveyActivity
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

        root.addView(button("Proteggi i nuovi backup con password") { activateEncryption() })
        root.addView(button("Ripara accesso ai backup automatici") { chooseKeyRepair() })
        root.addView(button("BACKUP GE360 + OPENPLAN3D · telefono + Drive") { confirmFullBackup() })
        root.addView(button("Backup rapido solo dati (senza foto Galleria)") { runBackup(false) })
        root.addView(button("Scegli cartella backup sul telefono") { choosePersistentLocal() })
        root.addView(button("Copia ultimo backup nella cartella telefono") { copyLatestPersistentLocal() })
        root.addView(button("Scegli cartella Google Drive") { chooseDrive() })
        root.addView(button("Copia ultimo backup su Drive") { copyLatestToDrive() })
        root.addView(button("Ripristina da backup GE360") { chooseRestore() })
        root.addView(button("Esporta a parte i rilievi OpenPlan3D avanzati") {
            startActivity(Intent(this, AdvancedSurveyActivity::class.java))
        })
        root.addView(TextView(this).apply {
            text = "OpenPlan3D: prima di eseguire il backup apri Rilievi avanzati almeno una volta. GE360 copia piante, appunti, lavori, foto e dati offline nel deposito nativo; se un trasferimento è incompleto il backup si interrompe. Dopo un RIPRISTINO apri di nuovo OpenPlan3D per applicare i dati al disegnatore. Il backend Debian richiede un backup separato."
            textSize = 12f
            setPadding(dp(10), dp(10), dp(10), dp(14))
        })


        root.addView(TextView(this).apply {
            text = "Copie di lavoro locali"; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(dp(2), dp(18), dp(2), dp(8))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        root.addView(TextView(this).apply {
            text = "Cifratura V8: dopo l'attivazione, i NUOVI backup vengono protetti con password (AES-256-GCM). Salva la password fuori dal telefono: senza di essa non potrai recuperare i dati su un altro dispositivo. I vecchi ZIP non vengono cifrati automaticamente e vanno custoditi separatamente. La copia interna può sparire disinstallando l'app."
            textSize = 11f; setPadding(dp(2), dp(14), dp(2), 0)
        })
        return scroll
    }

    private fun secretInput(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        isSingleLine = true
    }

    /** One deliberate opt-in; existing .ge360.zip files are never rewritten. */
    private fun activateEncryption() {
        if (coordinator.archive.encryptionEnabled()) {
            toast("Cifratura già attiva. Custodisci la password di recupero fuori dal telefono.")
            return
        }
        val first = secretInput("Password di recupero (almeno 12 caratteri)")
        val repeated = secretInput("Ripeti la password")
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(TextView(this@BackupActivity).apply {
                text = "La password serve per recuperare un backup su un altro telefono. " +
                    "Android Keystore protegge la copia locale per i backup automatici. " +
                    "Se perdi la password, non esiste recupero tramite GE360. " +
                    "I vecchi ZIP rimangono leggibili e vanno protetti separatamente."
                textSize = 14f
            })
            addView(first)
            addView(repeated)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Attiva cifratura nuovi backup")
            .setView(layout)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("ATTIVA", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = first.text.toString().toCharArray()
                val repeat = repeated.text.toString().toCharArray()
                try {
                    require(password.contentEquals(repeat)) { "Le due password non coincidono" }
                    coordinator.archive.enableEncryption(password)
                    first.text.clear()
                    repeated.text.clear()
                    dialog.dismiss()
                    toast("Cifratura attiva per nuovi backup. Conserva la password!")
                    refresh()
                } catch (error: Exception) {
                    toast(error.message ?: "Impossibile attivare la cifratura")
                } finally {
                    password.fill('\u0000')
                    repeat.fill('\u0000')
                }
            }
        }
        dialog.show()
    }

    /**
     * Re-enroll an unavailable Android Keystore credential by proving knowledge
     * of the password against an existing encrypted backup chosen by the user.
     * No password survives the dialog/worker. Existing archives are unchanged.
     */
    private fun chooseKeyRepair() {
        if (!coordinator.archive.encryptionEnabled()) {
            toast("Attiva prima la cifratura per i nuovi backup")
            return
        }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }, REQ_KEY_REPAIR)
    }

    private fun askKeyRepair(uri: Uri) {
        val input = secretInput("Password originale del backup scelto")
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(TextView(this@BackupActivity).apply {
                text = "Usa una copia GE360 già cifrata e la sua password. " +
                    "Solo dopo la verifica verrà riparata la chiave Android per " +
                    "i backup futuri. I vecchi archivi conservano la loro password."
            })
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Recupera chiave locale")
            .setView(layout)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("VERIFICA E RIPARA") { _, _ ->
                val password = input.text.toString().toCharArray()
                input.text.clear()
                if (password.isEmpty()) {
                    toast("Password richiesta")
                    password.fill('\u0000')
                } else {
                    status.text = "Verifica archivio cifrato e recupero chiave locale…"
                    thread(name = "ge360-key-repair") {
                        val result = try {
                            runCatching { coordinator.archive.recoverLocalKeyFromUri(uri, password) }
                        } finally {
                            password.fill('\u0000')
                        }
                        runOnUiThread {
                            result.onSuccess {
                                toast("Chiave locale riparata per i backup futuri")
                                refresh()
                            }.onFailure {
                                status.text = "Riparazione chiave fallita: " +
                                    (it.message ?: "Errore")
                                toast(status.text.toString())
                            }
                        }
                    }
                }
            }.show()
    }

    private fun runBackup(full: Boolean) {
        status.text = if (full) "Preparazione backup completo e doppia copia…" else "Backup rapido senza foto Galleria…"
        thread(name = "ge360-manual-backup") {
            val result = runCatching {
                // Nessun falso OK se una delle copie manca o non verifica SHA-256.
                val created = coordinator.createBackup(full, copyToDrive = false)
                val archive = coordinator.archive
                val local = if (archive.persistentLocalReady()) {
                    runCatching { archive.copyToPersistentLocal(created.info.file) }
                } else null
                val drive = if (archive.driveReady()) {
                    runCatching { archive.copyToDrive(created.info.file) }
                } else null
                Triple(created, local, drive)
            }
            runOnUiThread {
                result.onSuccess { (created, local, drive) ->
                    val localOk = local?.isSuccess == true
                    val driveOk = drive?.isSuccess == true
                    val doubleOk = localOk && driveOk && full
                    status.text = buildString {
                        append(if (doubleOk) "✓ GE360 + OPENPLAN3D · DOPPIA COPIA VERIFICATA" else "⚠ Backup creato · DOPPIA COPIA NON COMPLETA")
                        append("\nArchivio interno: OK · ").append(human(created.info.sizeBytes))
                        append("\nSHA-256: ").append(created.info.sha256.take(16)).append("…")
                        append("\nCartella persistente telefono: ").append(
                            if (local == null) "NON CONFIGURATA" else if (localOk) "OK" else "ERRORE: " + local.exceptionOrNull()?.message
                        )
                        append("\nCartella Drive: ").append(
                            if (drive == null) "NON CONFIGURATA" else if (driveOk) "OK" else "ERRORE: " + drive.exceptionOrNull()?.message
                        )
                        if (!full) append("\nATTENZIONE: la modalità rapida non comprende le foto Galleria.")
                    }
                    if (doubleOk) toast("Backup completo su due destinazioni, checksum verificati")
                    else toast("Controlla le destinazioni: la doppia copia NON è completa")
                    if (created.galleryFilesSkipped > 0) toast(
                        "${created.galleryFilesSkipped} foto Galleria non leggibili sono state saltate"
                    )
                    refreshList()
                }.onFailure {
                    status.text = "Backup fallito: " + (it.message ?: "Errore")
                    toast(status.text.toString())
                }
            }
        }
    }

    private fun confirmFullBackup() {
        AlertDialog.Builder(this)
            .setTitle("Backup completo")
            .setMessage("Crea un archivio GE360 con database, foto Galleria, Sopralluoghi Smart e copia nativa verificata di OpenPlan3D (piante e foto). Servono cartelle diverse su telefono e Drive; per Drive è richiesta la password di cifratura. Se OpenPlan3D non ha ancora sincronizzato, il backup verrà interrotto. Procedere?")
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
        status.text = "Riconoscimento archivio GE360…"
        thread(name = "ge360-restore-header") {
            val result = runCatching { coordinator.requiresRecoveryPassword(uri) }
            runOnUiThread {
                result.onSuccess { encrypted ->
                    if (encrypted) askRecoveryPassword(uri, replace)
                    else performRestore(uri, replace, null)
                }.onFailure {
                    status.text = "Archivio non leggibile: " + (it.message ?: "Errore")
                    toast(status.text.toString())
                }
            }
        }
    }

    private fun askRecoveryPassword(uri: Uri, replace: Boolean) {
        val passwordInput = secretInput("Password del backup cifrato")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(TextView(this@BackupActivity).apply {
                text = "Backup GE360 cifrato: inserisci la password originale. " +
                    "La password non viene salvata durante il ripristino."
            })
            addView(passwordInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Sblocca backup V8")
            .setView(box)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("CONTINUA") { _, _ ->
                val password = passwordInput.text.toString().toCharArray()
                passwordInput.text.clear()
                if (password.isEmpty()) {
                    password.fill('\u0000')
                    toast("Password necessaria")
                } else {
                    performRestore(uri, replace, password)
                }
            }.show()
    }

    private fun performRestore(uri: Uri, replace: Boolean, password: CharArray?) {
        status.text = "Verifica cifratura, checksum e ripristino…"
        thread(name = "ge360-restore") {
            var temporary: java.io.File? = null
            val result = try {
                runCatching {
                    temporary = coordinator.importFromUri(uri, password)
                    coordinator.restore(requireNotNull(temporary), replace)
                }
            } finally {
                temporary?.delete()
                password?.fill('\u0000')
            }
            runOnUiThread {
                result.onSuccess { r ->
                    val total = r.counts.values.sum()
                    status.text = "Ripristino completato · $total record · ${r.scannerFiles} PDF · ${r.galleryFiles} foto"
                    val detail = buildString {
                        r.counts.forEach { (k, v) -> appendLine("$k: $v") }
                        if (r.warnings.isNotEmpty()) { appendLine(); appendLine("Avvisi:"); r.warnings.forEach { appendLine("• $it") } }
                    }
                    AlertDialog.Builder(this).setTitle("Ripristino completato")
                        .setMessage(detail.trim()).setPositiveButton("OK", null).show()
                }.onFailure {
                    status.text = "Ripristino fallito: " + (it.message ?: "Errore")
                    toast(status.text.toString())
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_LOCAL -> {
                runCatching { coordinator.archive.setPersistentLocalRoot(uri) }
                    .onSuccess { JobsiteReplicaCoordinator.requestSync(this); toast("Cartella backup telefono salvata"); refresh() }
                    .onFailure { toast(it.message ?: "Cartella telefono non valida") }
            }
            REQ_DRIVE -> {
                runCatching { coordinator.archive.setDriveRoot(uri) }
                    .onSuccess { JobsiteReplicaCoordinator.requestSync(this); toast("Cartella Drive salvata"); refresh() }
                    .onFailure { toast(it.message ?: "Cartella Drive non valida") }
            }
            REQ_RESTORE -> askRestore(uri)
            REQ_KEY_REPAIR -> askKeyRepair(uri)
        }
    }

    private fun refresh() {
        val a = coordinator.archive
        val latest = a.latestLocal()
        status.text = buildString {
            append("Cifratura nuovi backup: ")
            append(if (a.encryptionEnabled()) "ATTIVA (password richiesta per restore)" else "NON ATTIVA")
            append("\nCopia di lavoro: ")
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
            val recovered = BackupScratchV9.lastSummary(this@BackupActivity)
            if (recovered.deleted > 0 || recovered.failed > 0) {
                append("\nResidui backup da precedente interruzione: eliminati ")
                    .append(recovered.deleted)
                    .append(" · non eliminati ")
                    .append(recovered.failed)
            }
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
        private const val REQ_KEY_REPAIR = 803
    }
}
