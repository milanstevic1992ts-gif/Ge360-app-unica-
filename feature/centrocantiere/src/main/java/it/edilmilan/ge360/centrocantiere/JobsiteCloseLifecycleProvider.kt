package it.edilmilan.ge360.centrocantiere

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.cantieri.JobsitesActivity
import it.edilmilan.ge360.core.common.Ge360Contracts
import it.edilmilan.ge360.scanner.FirStore

/**
 * Aggiunge il comando di chiusura direttamente in fondo alle schermate Centro Cantiere.
 *
 * Il provider viene inizializzato da Android all'avvio dell'app e registra un callback
 * leggero sul lifecycle. In questo modo il comando resta unico e coerente sia nel vero
 * Hub Cantiere sia nella schermata Cronologia / lavori, senza duplicare la logica di
 * archiviazione nelle due Activity.
 */
class JobsiteCloseLifecycleProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {

    override fun onCreate(): Boolean {
        val application = context?.applicationContext as? Application ?: return false
        application.registerActivityLifecycleCallbacks(this)
        return true
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity !is JobsiteHubActivity && activity !is JobsiteCenterActivity) return
        injectCloseSection(activity)
    }

    private fun injectCloseSection(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(CARD_TAG) != null) return

        val scroll = findScrollView(content) ?: return
        val body = scroll.getChildAt(0) as? LinearLayout ?: return
        val jobsite = resolveJobsite(activity) ?: return

        body.addView(
            buildCloseCard(activity, jobsite),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 18)
                bottomMargin = dp(activity, 18)
            }
        )
    }

    private fun buildCloseCard(activity: Activity, jobsite: JobsiteRecord): View {
        val archived = jobsite.status == JobsiteStore.STATUS_ARCHIVED
        val accent = if (archived) Color.parseColor("#2E7D32") else Color.parseColor("#B42318")
        val soft = if (archived) Color.parseColor("#EFF8F1") else Color.parseColor("#FFF1F0")

        return MaterialCardView(activity).apply {
            tag = CARD_TAG
            radius = dp(activity, 18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(activity, 1)
            strokeColor = accent
            setCardBackgroundColor(soft)
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(activity, 15), dp(activity, 14), dp(activity, 15), dp(activity, 14))

                addView(TextView(activity).apply {
                    text = if (archived) "CANTIERE ARCHIVIATO" else "CHIUSURA CANTIERE"
                    textSize = 11f
                    letterSpacing = 0.07f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(accent)
                })
                addView(TextView(activity).apply {
                    text = if (archived) "Questo cantiere non è più nell’operativo" else "Lavoro terminato?"
                    textSize = 18f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#25282C"))
                    setPadding(0, dp(activity, 3), 0, 0)
                })
                addView(TextView(activity).apply {
                    text = if (archived) {
                        "Tutti i dati restano nel database e nella cartella del cantiere. Puoi riaprirlo in qualsiasi momento."
                    } else {
                        "Archivia il cantiere e rimuovilo dalle viste operative. Foto, documenti/FIR, cronologia, note, checklist, preventivi, ore, spese e planimetrie non vengono cancellati."
                    }
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#5E646A"))
                    setPadding(0, dp(activity, 5), 0, dp(activity, 11))
                })
                addView(MaterialButton(activity).apply {
                    isAllCaps = false
                    text = if (archived) "↩ Riapri cantiere" else "✓ Fine cantiere"
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    backgroundTintList = ColorStateList.valueOf(accent)
                    setOnClickListener {
                        if (archived) confirmReopen(activity, jobsite) else confirmClose(activity, jobsite)
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 52)))
            })
        }
    }

    private fun confirmClose(activity: Activity, jobsite: JobsiteRecord) {
        val warning = closingWarnings(activity, jobsite)
        val message = buildString {
            append("Confermi la fine del cantiere ")
            append(jobsite.displayTitle())
            append("?\n\n")
            if (warning.isNotBlank()) {
                append(warning)
                append("\n\n")
            }
            append("Il cantiere verrà impostato come Archiviato e sparirà dall’operativo. ")
            append("Nessun dato viene eliminato: database e cartella cantiere restano completi e consultabili dall’Archivio.")
        }

        AlertDialog.Builder(activity)
            .setTitle("Fine cantiere · ${jobsite.code}")
            .setMessage(message)
            .setPositiveButton("ARCHIVIA CANTIERE") { _, _ -> archiveJobsite(activity, jobsite) }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun closingWarnings(activity: Activity, jobsite: JobsiteRecord): String {
        val workflowSummary = runCatching {
            JobsiteWorkflowStore(activity).let { store ->
                try {
                    store.summary(jobsite.ge360Id)
                } finally {
                    store.close()
                }
            }
        }.getOrNull()
        val firSummary = runCatching {
            FirStore(activity).let { store ->
                try {
                    store.summary(jobsite.ge360Id)
                } finally {
                    store.close()
                }
            }
        }.getOrNull()

        val openTasks = workflowSummary?.let { it.todo + it.inProgress + it.blocked } ?: 0
        val openFir = firSummary?.open ?: 0
        return buildList {
            if (openTasks > 0) add("⚠ $openTasks attività checklist non ancora completate")
            if (openFir > 0) add("♻ $openFir FIR ancora aperti")
        }.joinToString("\n")
    }

    private fun archiveJobsite(activity: Activity, jobsite: JobsiteRecord) {
        runCatching {
            JobsiteWorkflowStore(activity).let { workflow ->
                try {
                    workflow.recordEvent(
                        jobsite.ge360Id,
                        "JOBSITE_ARCHIVED",
                        "Fine cantiere",
                        "Cantiere archiviato e rimosso dalle viste operative"
                    )
                } finally {
                    workflow.close()
                }
            }
            JobsiteStore(activity).let { store ->
                try {
                    store.setStatus(jobsite.id, JobsiteStore.STATUS_ARCHIVED)
                } finally {
                    store.close()
                }
            }
        }.onFailure {
            Toast.makeText(activity, "Impossibile archiviare il cantiere: ${it.message ?: "errore"}", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(activity, "Cantiere archiviato · dati conservati", Toast.LENGTH_LONG).show()
        activity.startActivity(Intent(activity, JobsitesActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        activity.finish()
    }

    private fun confirmReopen(activity: Activity, jobsite: JobsiteRecord) {
        AlertDialog.Builder(activity)
            .setTitle("Riapri cantiere")
            .setMessage("Riportare ${jobsite.displayTitle()} nell’operativo come In corso?")
            .setPositiveButton("RIAPRI") { _, _ ->
                runCatching {
                    JobsiteWorkflowStore(activity).let { workflow ->
                        try {
                            workflow.recordEvent(
                                jobsite.ge360Id,
                                "JOBSITE_REOPENED",
                                "Cantiere riaperto",
                                "Cantiere riportato nell’operativo"
                            )
                        } finally {
                            workflow.close()
                        }
                    }
                    JobsiteStore(activity).let { store ->
                        try {
                            store.setStatus(jobsite.id, JobsiteStore.STATUS_ACTIVE)
                        } finally {
                            store.close()
                        }
                    }
                }.onFailure {
                    Toast.makeText(activity, "Impossibile riaprire il cantiere: ${it.message ?: "errore"}", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                Toast.makeText(activity, "Cantiere riaperto", Toast.LENGTH_SHORT).show()
                refreshCard(activity)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun refreshCard(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.findViewWithTag<View>(CARD_TAG)?.let { card ->
            (card.parent as? ViewGroup)?.removeView(card)
        }
        injectCloseSection(activity)
    }

    private fun resolveJobsite(activity: Activity): JobsiteRecord? {
        val uuid = activity.intent.getStringExtra(Ge360Contracts.EXTRA_JOBSITE_ID).orEmpty()
        val localId = activity.intent.getLongExtra(Ge360Contracts.EXTRA_JOBSITE_LOCAL_ID, 0L)
        return JobsiteStore(activity).let { store ->
            try {
                when {
                    uuid.isNotBlank() -> store.getByGe360Id(uuid)
                    localId > 0 -> store.get(localId)
                    else -> null
                }
            } finally {
                store.close()
            }
        }
    }

    private fun findScrollView(view: View): ScrollView? {
        if (view is ScrollView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findScrollView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val CARD_TAG = "ge360_jobsite_close_card"
    }
}
