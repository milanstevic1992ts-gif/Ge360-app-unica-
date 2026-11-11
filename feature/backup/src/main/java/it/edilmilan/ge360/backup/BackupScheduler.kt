package it.edilmilan.ge360.backup

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlin.concurrent.thread

object BackupScheduler {
    private const val JOB_ID = 360910
    private const val PREFS = "ge360_backup_schedule"

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        if (enabled) schedule(context) else cancel(context)
    }

    fun schedule(context: Context) {
        if (!isEnabled(context)) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, BackupJobService::class.java))
            .setPeriodic(24L * 60L * 60L * 1000L, 2L * 60L * 60L * 1000L)
            .setPersisted(true)
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            .build()
        scheduler.schedule(info)
    }

    fun cancel(context: Context) = context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)

    fun lastStatus(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("last_status", "").orEmpty()
}

class BackupJobService : JobService() {
    @Volatile private var stopped = false
    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        if (!BackupScheduler.isEnabled(this)) return false
        if (worker?.isAlive == true) return false
        stopped = false
        worker = thread(name = "ge360-backup") {
            // Per la doppia copia non basta aver creato il ZIP locale: le due
            // destinazioni SAF devono essere scritte e SHA-256 verificate.
            val result = runCatching {
                val coordinator = BackupCoordinator(this)
                val a = coordinator.archive
                val created = coordinator.createBackup(includeGalleryFiles = true, copyToDrive = false)
                if (Thread.currentThread().isInterrupted) return@runCatching "Backup interrotto"
                val local = if (a.persistentLocalReady()) runCatching {
                    a.copyToPersistentLocal(created.info.file)
                    "OK"
                }.getOrElse { "ERRORE: " + (it.message ?: "copia") } else "DA CONFIGURARE"
                val drive = if (a.driveReady()) runCatching {
                    a.copyToDrive(created.info.file)
                    "OK"
                }.getOrElse { "ERRORE: " + (it.message ?: "copia") } else "DA CONFIGURARE"
                val completed = local == "OK" && drive == "OK"
                (if (completed) "Backup doppia copia riuscito" else "Backup INCOMPLETO") +
                    " · archivio interno OK · telefono: " + local + " · Drive: " + drive
            }
            getSharedPreferences("ge360_backup_schedule", Context.MODE_PRIVATE).edit()
                .putString("last_status", result.getOrElse { "Backup automatico fallito: " +
                    (it.message ?: it.javaClass.simpleName) })
                .putLong("last_attempt_at", System.currentTimeMillis())
                .apply()
            if (!stopped && !Thread.currentThread().isInterrupted) jobFinished(params, false)
            worker = null
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        stopped = true
        worker?.interrupt()
        worker = null
        // Nessun retry immediato: evita di sovrapporre un nuovo backup a I/O che il provider SAF potrebbe ancora chiudere.
        return false
    }

    override fun onDestroy() {
        stopped = true
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }
}
