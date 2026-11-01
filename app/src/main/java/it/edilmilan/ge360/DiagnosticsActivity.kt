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
import it.edilmilan.ge360.backup.DiagnosticsInspectorV11
import it.edilmilan.ge360.sync.DataHubSyncCoordinator
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Diagnostica manuale, mai eseguita nel bootstrap dell'app.
 * Serve per capire rapidamente stato dati/storage/sync/crash prima di una release o dopo un problema.
 */
class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout
    private var lastReport: String = ""
    private var lastScan: DiagnosticsInspectorV11.Report? = null
    private var scanning = false
    private lateinit var scanButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        renderStatus()
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
                scanButton = this
                setOnClickListener { startDiagnosticScan() }
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

    private fun startDiagnosticScan() {
        if (scanning) return
        scanning = true
        scanButton.isEnabled = false
        scanButton.text = "ANALISI…"
        body.removeAllViews()
        addSection("CONTROLLO IN CORSO", "Lettura degli archivi e verifica allegati; nessuna modifica ai dati.")
        thread(name = "ge360-diagnostics-v11") {
            val scan = runCatching {
                DiagnosticsInspectorV11.inspect(applicationContext)
            }.getOrElse {
                DiagnosticsInspectorV11.Report(
                    System.currentTimeMillis(),
                    listOf(DiagnosticsInspectorV11.Finding(
                        "Sistema", "Diagnostica V11",
                        DiagnosticsInspectorV11.Level.ERRORE,
                        "Controllo non completato",
                        "Riapri la diagnostica e riprova, senza cancellare dati"
                    ))
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                lastScan = scan
                scanning = false
                scanButton.isEnabled = true
                scanButton.text = "RICONTROLLA"
                renderStatus()
            }
        }
    }

    private fun renderStatus() {
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

        val result = lastScan
        addSection("QUADRO DI SALUTE", "Controllo manuale: nessun dato viene corretto o cancellato.")
        if (result == null) {
            addStatusRow("Ancora da controllare", "Premi CONTROLLA DATI per verificare collegamenti, documenti e backup.")
        } else {
            addStatusRow(result.headline,
                "${result.successful} controlli OK · ${result.warnings} attenzioni · ${result.errors} errori" +
                    " · ${formatDate(result.checkedAt)}")
            result.findings.groupBy { it.section }.forEach { (name, findings) ->
                addSection(name.uppercase(Locale.ITALY), "Risultato della verifica manuale, non del funzionamento in tempo reale.")
                findings.forEach(::addFindingRow)
            }
        }

        addSection("SISTEMA", "Informazioni utili prima di installazioni, restore o debug.")
        rows.forEach { (label, value) -> addStatusRow(label, value) }

        // Do not display SAF URLs, which may expose names/accounts in screenshots.

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
                    renderStatus()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(8) })
        }

        lastReport = buildString {
            appendLine("GE360 · Centro diagnostico V11")
            appendLine("Controllo: ${result?.let { formatDate(it.checkedAt) } ?: "non eseguito"}")
            appendLine("Versione: ${identity.versionName} / ${identity.versionCode}")
            appendLine("Storage interno disponibile: ${formatBytes(internal.availableBytes)}")
            if (result != null) append(result.publicSummary())
            else appendLine("Nessuna verifica approfondita eseguita.")
            // No crash stacks, SAF URIs, filesystem paths or credentials.
        }
    }

    private fun addFindingRow(item: DiagnosticsInspectorV11.Finding) {
        val color = when (item.level) {
            DiagnosticsInspectorV11.Level.OK -> Color.parseColor("#137F6D")
            DiagnosticsInspectorV11.Level.ATTENZIONE -> Color.parseColor("#AB6819")
            DiagnosticsInspectorV11.Level.ERRORE -> Color.parseColor("#B63242")
            DiagnosticsInspectorV11.Level.INFO -> Color.parseColor("#3766A0")
        }
        body.addView(card(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(TextView(this@DiagnosticsActivity).apply {
                text = "${item.level.name} · ${item.title}"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color)
            })
            addView(TextView(this@DiagnosticsActivity).apply {
                text = item.detail
                textSize = 12f
                setTextColor(Color.parseColor("#394451"))
                setPadding(0, dp(5), 0, 0)
            })
            if (item.nextStep.isNotBlank()) addView(TextView(this@DiagnosticsActivity).apply {
                text = "Da fare: ${item.nextStep}"
                textSize = 11f
                setTextColor(Color.parseColor("#536273"))
                setPadding(0, dp(6), 0, 0)
            })
        }))
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
        if (scanning || lastScan == null) {
            Toast.makeText(this, "Esegui prima CONTROLLA DATI.", Toast.LENGTH_LONG).show()
            return
        }
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
