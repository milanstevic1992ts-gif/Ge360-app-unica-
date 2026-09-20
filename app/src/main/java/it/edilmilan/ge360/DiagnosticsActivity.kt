package it.edilmilan.ge360

import android.content.Intent
import android.content.pm.PackageInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.attrezzi.ToolsStore
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.clienti.ClientStore
import it.edilmilan.ge360.galleria.GalleryStore
import it.edilmilan.ge360.giornata.DailyStore
import it.edilmilan.ge360.preventivi.EstimateStore
import it.edilmilan.ge360.scanner.DocumentStore
import it.edilmilan.ge360.spese.FinanceStore
import it.edilmilan.ge360.sync.DataHubSyncCoordinator
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostica manuale, mai eseguita nel bootstrap dell'app.
 * Serve per capire rapidamente stato dati/storage/sync/crash prima di una release o dopo un problema.
 */
class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout
    private var lastReport: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        renderStatus(runDeepChecks = false)
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F6F7F9"))
            setPadding(dp(16), dp(14), dp(16), dp(20))
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(MaterialButton(this@DiagnosticsActivity).apply {
                text = "←"
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(54), dp(48)))
            addView(LinearLayout(this@DiagnosticsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@DiagnosticsActivity).apply {
                    text = "Stato GE360"
                    textSize = 25f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#17191C"))
                })
                addView(TextView(this@DiagnosticsActivity).apply {
                    text = "Diagnostica locale · nessuna modifica ai dati"
                    textSize = 12f
                    setTextColor(Color.parseColor("#6C7278"))
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(MaterialButton(this@DiagnosticsActivity).apply {
                text = "CONTROLLA DATI"
                setOnClickListener { renderStatus(runDeepChecks = true) }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(5) })
            addView(MaterialButton(this@DiagnosticsActivity).apply {
                text = "CONDIVIDI"
                setOnClickListener { shareReport() }
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(5) })
        }
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
            bottomMargin = dp(10)
        })

        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun renderStatus(runDeepChecks: Boolean) {
        body.removeAllViews()
        val app = application
        val identity = appIdentity()
        val crash = Ge360Application.lastCrash(app)
        val crashAt = Ge360Application.lastCrashAt(app)
        val syncAt = DataHubSyncCoordinator.lastSuccessAt(applicationContext)
        val syncStatus = DataHubSyncCoordinator.lastStatus(applicationContext)
        val persistedUris = contentResolver.persistedUriPermissions
        val internal = StatFs(filesDir.absolutePath)
        val externalPath = getExternalFilesDir(null)?.absolutePath
        val external = externalPath?.let(::StatFs)

        val rows = mutableListOf<Pair<String, String>>()
        rows += "Versione" to "${identity.versionName} · code ${identity.versionCode}"
        rows += "Package" to identity.packageName
        rows += "Storage interno libero" to formatBytes(internal.availableBytes)
        rows += "Storage esterno app" to (external?.availableBytes?.let(::formatBytes) ?: "Non disponibile")
        rows += "Permessi cartelle SAF" to persistedUris.size.toString()
        rows += "Ultima sync Data Hub" to if (syncAt > 0) formatDate(syncAt) else "Mai completata"
        rows += "Stato Data Hub" to syncStatus.ifBlank { "Nessun tentativo registrato" }
        rows += "Ultimo crash" to if (crash.isBlank()) "Nessun crash registrato" else "Registrato ${if (crashAt > 0) formatDate(crashAt) else ""}".trim()

        addSection("SISTEMA", "Informazioni utili prima di installazioni, restore o debug.")
        rows.forEach { (label, value) -> addStatusRow(label, value) }

        if (persistedUris.isNotEmpty()) {
            addSection("CARTELLE AUTORIZZATE", "Permessi persistenti Android SAF attualmente disponibili.")
            persistedUris.take(8).forEach { permission ->
                addStatusRow(
                    if (permission.isWritePermission) "Lettura + scrittura" else "Sola lettura",
                    permission.uri.toString()
                )
            }
        }

        if (crash.isNotBlank()) {
            addSection("ULTIMO CRASH", "Estratto del crash recorder locale. Non viene inviato automaticamente.")
            body.addView(card(TextView(this).apply {
                text = crash.take(4000)
                textSize = 10f
                setTextColor(Color.parseColor("#4D5359"))
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }))
            body.addView(MaterialButton(this).apply {
                text = "CANCELLA CRASH REGISTRATO"
                setOnClickListener {
                    Ge360Application.clearLastCrash(application)
                    Toast.makeText(this@DiagnosticsActivity, "Crash registrato cancellato", Toast.LENGTH_SHORT).show()
                    renderStatus(runDeepChecks = false)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) })
        }

        val checks = if (runDeepChecks) performDataChecks() else emptyList()
        if (runDeepChecks) {
            addSection("CONTROLLO ARCHIVI", "Apertura in sola lettura dei principali store GE360.")
            checks.forEach { result -> addStatusRow(result.first, result.second) }
        } else {
            addSection("CONTROLLO ARCHIVI", "Premi CONTROLLA DATI per aprire e leggere i principali database locali.")
        }

        lastReport = buildString {
            appendLine("GE360 DIAGNOSTICA")
            appendLine("Generato: ${formatDate(System.currentTimeMillis())}")
            rows.forEach { appendLine("${it.first}: ${it.second}") }
            if (persistedUris.isNotEmpty()) {
                appendLine("Cartelle SAF:")
                persistedUris.forEach { appendLine("- ${it.uri} · read=${it.isReadPermission} write=${it.isWritePermission}") }
            }
            if (checks.isNotEmpty()) {
                appendLine("Controllo archivi:")
                checks.forEach { appendLine("- ${it.first}: ${it.second}") }
            }
            if (crash.isNotBlank()) {
                appendLine("Ultimo crash:")
                appendLine(crash.take(8000))
            }
        }
    }

    private fun performDataChecks(): List<Pair<String, String>> {
        val app = applicationContext
        val out = mutableListOf<Pair<String, String>>()
        fun check(name: String, action: () -> Int) {
            out += runCatching { name to "OK · ${action()} record" }
                .getOrElse { name to "ERRORE · ${it.message ?: it.javaClass.simpleName}" }
        }

        check("Clienti") {
            val store = ClientStore(app)
            try { store.search().size } finally { store.close() }
        }
        check("Cantieri") {
            val store = JobsiteStore(app)
            try { store.list().size } finally { store.close() }
        }
        check("Giornata") {
            val store = DailyStore(app)
            try { store.reports(1000).size + store.sessions().size } finally { store.close() }
        }
        check("Preventivi") {
            val store = EstimateStore(app)
            try { store.list().size } finally { store.close() }
        }
        check("Spese") {
            val store = FinanceStore(app)
            try { store.all().size } finally { store.close() }
        }
        check("Scanner / documenti") {
            val store = DocumentStore(app)
            try { store.list().size } finally { store.close() }
        }
        check("Attrezzi / materiali") {
            val store = ToolsStore(app)
            try { if (store.exportJson().isBlank()) 0 else 1 } finally { store.close() }
        }
        check("Galleria") {
            val store = GalleryStore(app)
            try { store.list().size } finally { store.close() }
        }
        return out
    }

    private fun addSection(title: String, subtitle: String) {
        body.addView(TextView(this).apply {
            text = title
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#73797F"))
            setPadding(dp(3), dp(18), dp(3), dp(2))
        })
        body.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(Color.parseColor("#858A90"))
            setPadding(dp(3), 0, dp(3), dp(8))
        })
    }

    private fun addStatusRow(label: String, value: String) {
        body.addView(card(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(11), dp(13), dp(11))
            addView(TextView(this@DiagnosticsActivity).apply {
                text = label
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#30343A"))
            })
            addView(TextView(this@DiagnosticsActivity).apply {
                text = value
                textSize = 11f
                setTextColor(Color.parseColor("#666C72"))
                setPadding(0, dp(3), 0, 0)
            })
        }))
    }

    private fun card(child: View): View = MaterialCardView(this).apply {
        radius = dp(14).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = Color.parseColor("#E1E4E7")
        setCardBackgroundColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(7)
        }
        addView(child)
    }

    private fun shareReport() {
        if (lastReport.isBlank()) renderStatus(runDeepChecks = true)
        val identity = appIdentity()
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "GE360 diagnostica ${identity.versionName}")
            putExtra(Intent.EXTRA_TEXT, lastReport)
        }, "Condividi diagnostica GE360"))
    }

    private data class AppIdentity(
        val versionName: String,
        val versionCode: Long,
        val packageName: String
    )

    private fun appIdentity(): AppIdentity {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            legacyVersionCode(info)
        }
        return AppIdentity(
            versionName = info.versionName.orEmpty().ifBlank { "?" },
            versionCode = code,
            packageName = packageName
        )
    }

    @Suppress("DEPRECATION")
    private fun legacyVersionCode(info: PackageInfo): Long = info.versionCode.toLong()

    private fun formatDate(timestamp: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.ITALY).format(Date(timestamp))

    private fun formatBytes(bytes: Long): String {
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) String.format(Locale.ITALY, "%.1f GB", gb)
        else String.format(Locale.ITALY, "%.0f MB", bytes.toDouble() / (1024.0 * 1024.0))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
