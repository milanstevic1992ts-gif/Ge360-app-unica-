package it.edilmilan.ge360.jarvis

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import it.edilmilan.ge360.core.bridge.BridgeService
import it.edilmilan.ge360.core.bridge.JsonHttpClient
import it.edilmilan.ge360.core.bridge.SecureBridgeSettings
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class JarvisProactiveSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    var includeInfo: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_INFO, false)
        set(value) { prefs.edit().putBoolean(KEY_INCLUDE_INFO, value).apply() }

    val lastRevision: String get() = prefs.getString(KEY_LAST_REVISION, "").orEmpty()
    val lastCheckAt: Long get() = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
    val lastError: String get() = prefs.getString(KEY_LAST_ERROR, "").orEmpty()

    fun seenIds(): List<String> = prefs.getString(KEY_SEEN_IDS, "")
        .orEmpty()
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()

    fun markSeen(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val merged = JarvisProactivePolicy.mergeSeen(seenIds(), ids)
        prefs.edit().putString(KEY_SEEN_IDS, merged.joinToString("\n")).apply()
    }

    fun recordSuccess(revision: String) {
        prefs.edit()
            .putString(KEY_LAST_REVISION, revision.trim().take(80))
            .putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis())
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun recordError(message: String) {
        prefs.edit()
            .putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis())
            .putString(KEY_LAST_ERROR, message.trim().take(500))
            .apply()
    }

    fun clearSeen() {
        prefs.edit().remove(KEY_SEEN_IDS).remove(KEY_LAST_REVISION).apply()
    }

    companion object {
        private const val PREFS = "ge360_jarvis_proactive"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INCLUDE_INFO = "include_info"
        private const val KEY_SEEN_IDS = "seen_ids"
        private const val KEY_LAST_REVISION = "last_revision"
        private const val KEY_LAST_CHECK_AT = "last_check_at"
        private const val KEY_LAST_ERROR = "last_error"
    }
}

object JarvisProactiveNotifications {
    const val CHANNEL_ID = "ge360_jarvis_proactive"

    fun permissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Jarvis · avvisi operativi",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Promemoria e anomalie operative rilevate dal backend GE360"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun post(context: Context, item: JSONObject): Boolean {
        if (!permissionGranted(context)) return false
        val id = item.optString("id").trim()
        val title = item.optString("title").trim().ifBlank { "Avviso Jarvis" }
        val text = item.optString("spokenText").trim().ifBlank { title }
        if (id.isBlank()) return false

        ensureChannel(context)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                id.hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val severity = item.optString("severity").trim().lowercase()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Jarvis · $title")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(
                if (severity == "critical") NotificationCompat.PRIORITY_MAX
                else NotificationCompat.PRIORITY_HIGH
            )
        if (pendingIntent != null) builder.setContentIntent(pendingIntent)

        return runCatching {
            NotificationManagerCompat.from(context).notify(id.hashCode(), builder.build())
            true
        }.getOrDefault(false)
    }
}

class JarvisProactiveWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val proactive = JarvisProactiveSettings(applicationContext)
        if (!proactive.enabled) return Result.success()
        if (!JarvisProactiveNotifications.permissionGranted(applicationContext)) {
            proactive.recordError("Permesso notifiche non concesso")
            return Result.success()
        }

        val server = SecureBridgeSettings(applicationContext).get(BridgeService.JARVIS)
        if (!server.configured) {
            proactive.recordError("Backend Jarvis non configurato")
            return Result.success()
        }

        return try {
            val minimum = JarvisProactivePolicy.minimumSeverity(proactive.includeInfo)
            val root = JsonHttpClient().request(
                config = server,
                path = "/api/v1/jarvis/proactive?limit=50&minimum_severity=$minimum",
                timeoutMs = 15_000
            ).jsonObject()

            // Contratto di sicurezza Fase 8: leggere avvisi non può mai implicare azioni backend.
            if (root.optBoolean("actionsExecuted", true)) {
                proactive.recordError("Feed proattivo rifiutato: actionsExecuted non è false")
                return Result.failure()
            }

            val items = root.optJSONArray("items")
            val seen = proactive.seenIds().toSet()
            val posted = mutableListOf<String>()
            if (items != null) {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val severity = item.optString("severity").trim()
                    if (!JarvisProactivePolicy.shouldNotify(id, severity, seen, proactive.includeInfo)) {
                        continue
                    }
                    if (JarvisProactiveNotifications.post(applicationContext, item)) posted += id
                }
            }
            proactive.markSeen(posted)
            proactive.recordSuccess(root.optString("revision"))
            Result.success()
        } catch (error: Exception) {
            proactive.recordError(error.message ?: error.javaClass.simpleName)
            Result.retry()
        }
    }
}

object JarvisProactiveScheduler {
    private const val PERIODIC_WORK = "ge360-jarvis-proactive-periodic"
    private const val IMMEDIATE_WORK = "ge360-jarvis-proactive-now"

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val settings = JarvisProactiveSettings(appContext)
        if (!settings.enabled) {
            cancel(appContext)
            return
        }
        JarvisProactiveNotifications.ensureChannel(appContext)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<JarvisProactiveWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )
        checkNow(appContext)
    }

    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<JarvisProactiveWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context) {
        val work = WorkManager.getInstance(context.applicationContext)
        work.cancelUniqueWork(PERIODIC_WORK)
        work.cancelUniqueWork(IMMEDIATE_WORK)
    }
}
