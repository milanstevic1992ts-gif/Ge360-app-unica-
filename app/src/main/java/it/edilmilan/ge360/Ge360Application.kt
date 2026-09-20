package it.edilmilan.ge360

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Process
import it.edilmilan.ge360.jarvis.JarvisProactiveScheduler
import java.io.PrintWriter
import java.io.StringWriter

class Ge360Application : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_CRASH, writer.toString().take(24_000))
                    .putLong(KEY_LAST_CRASH_AT, System.currentTimeMillis())
                    .commit()
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
            }
        }

        // Fase 8: il controllo proattivo parte dopo il bootstrap, mai prima della prima UI.
        // WorkManager esegue soltanto letture del feed Jarvis e notifiche locali.
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { JarvisProactiveScheduler.schedule(applicationContext) }
        }, 2_500L)
    }

    companion object {
        private const val PREFS = "ge360_crash_recorder"
        private const val KEY_LAST_CRASH = "last_crash"
        private const val KEY_LAST_CRASH_AT = "last_crash_at"

        fun lastCrash(application: Application): String =
            application.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_LAST_CRASH, "")
                .orEmpty()

        fun lastCrashAt(application: Application): Long =
            application.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(KEY_LAST_CRASH_AT, 0L)

        fun clearLastCrash(application: Application) {
            application.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_CRASH)
                .remove(KEY_LAST_CRASH_AT)
                .apply()
        }
    }
}
