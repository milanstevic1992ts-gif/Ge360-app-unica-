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
            val result = runCatching {
                if (Thread.currentThread().isInterrupted) return@runCatching
                val coordinator = BackupCoordinator(this)
                val result = coordinator.createBackup(includeGalleryFiles = true, copyToDrive = true)
                if (!Thread.currentThread().isInterrupted && coordinator.archive.persistentLocalReady()) {
                    coordinator.archive.copyToPersistentLocal(result.info.file)
                }
            }
            getSharedPreferences("ge360_backup_schedule", Context.MODE_PRIVATE).edit()
                .putString("last_status", result.fold(
                    onSuccess = { "Backup automatico completo riuscito" },
                    onFailure = { "Backup automatico fallito: ${it.message ?: it.javaClass.simpleName}" }
                ))
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
