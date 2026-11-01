package it.edilmilan.ge360.core.backup

import android.content.Context
import java.io.File

/**
 * Removes ONLY GE360-owned scratch files left by a previous process.
 * Intended for Application.onCreate, BEFORE jobs/activities can start writes.
 * Files from any other feature and completed backup archives are untouched.
 *
 * File deletion is best-effort on flash storage, NOT cryptographic erasure.
 */
object BackupScratchV9 {
    private const val PREFS = "ge360_backup_scratch_v9"
    private val cacheNames = listOf(
        Regex("^ge360-v8-build-[0-9a-fA-F-]{36}\\.zip$"),
        Regex("^ge360-restore-[0-9a-fA-F-]{36}\\.ge360\\.zip$"),
        Regex("^ge360-rollback-[0-9a-fA-F-]{36}\\.ge360\\.zip$"),
        Regex("^ge360-restore-source-[0-9a-fA-F-]{36}\\.tmp$"),
        Regex("^restore_[0-9]+\\.ge360\\.zip$")
    )
    private val partialArchive = Regex(
        "^GE360_(AUTO|FULL)_[0-9]{8}_[0-9]{6}_[0-9a-fA-F]{8}\\.ge360\\.(zip|enc)\\.partial$"
    )

    data class Summary(val deleted: Int, val failed: Int)

    @Synchronized
    fun reclaimOnColdStart(context: Context): Summary {
        var deleted = 0
        var failed = 0
        val locations = listOf(
            context.cacheDir to { name: String -> cacheNames.any { it.matches(name) } },
            File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
                ?: context.filesDir, "GE360/Backups") to
                { name: String -> partialArchive.matches(name) }
        )
        locations.forEach { (parent, matches) ->
            parent.listFiles()?.forEach { file ->
                if (file.isFile && matches(file.name)) {
                    if (file.delete()) deleted++ else failed++
                }
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("deleted", deleted)
            .putInt("failed", failed)
            .putLong("checked_at", System.currentTimeMillis())
            .apply()
        return Summary(deleted, failed)
    }

    fun lastSummary(context: Context): Summary {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Summary(prefs.getInt("deleted", 0), prefs.getInt("failed", 0))
    }
}
