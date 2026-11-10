package it.edilmilan.ge360.planimetrie

import android.content.Intent
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import it.edilmilan.ge360.preventivi.EstimateLifecycleStore
import it.edilmilan.ge360.preventivi.EstimateLifecycleUi
import it.edilmilan.ge360.preventivi.EstimateRecord
import it.edilmilan.ge360.preventivi.EstimateStatuses
import it.edilmilan.ge360.preventivi.EstimateStore
import it.edilmilan.ge360.preventivi.EstimatesActivity
import it.edilmilan.ge360.preventivi.recalculateEstimate
import kotlin.math.abs

/**
 * Solo l'operatore può trasformare le metrature in righe di revisione.
 * Niente prezzi, inclusioni, contratti, cantieri o misure nativi toccati da soli.
 */
object AdvancedSurveyEstimateFlow {
    fun open(activity: AppCompatActivity, source: SurveyEstimateHandoffV1) {
        val quotes = EstimateStore(activity)
        val contracts = EstimateLifecycleStore(activity)
        val targets = try {
            quotes.list().filter {
                it.status in setOf(EstimateStatuses.DRAFT, EstimateStatuses.REVIEW) &&
                    !contracts.isLocked(it.ge360Id)
            }
        } finally {
            quotes.close()
            contracts.close()
        }
        val labels = arrayOf("+ Crea nuova bozza da questo rilievo") +
            targets.map {
                "${it.number} · ${it.clientName.ifBlank { it.title.ifBlank { "Bozza" } }}"
            }
        AlertDialog.Builder(activity)
            .setTitle("Rilievo → Preventivi GE360")
            .setMessage(
                "Scegli una bozza esistente oppure creane una nuova. " +
                    "Le quantità saranno righe TECNICHE ESCLUSE dal totale. " +
                    "Non si generano automaticamente lavorazioni o prezzi."
            )
            .setItems(labels) { _, index ->
                val selected = if (index == 0) null else targets[index - 1].ge360Id
                confirm(activity, source, selected)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun confirm(
        activity: AppCompatActivity,
        source: SurveyEstimateHandoffV1,
        targetId: String?
    ) {
        val quotes = EstimateStore(activity)
        val contracts = EstimateLifecycleStore(activity)
        val target = try {
            targetId?.let(quotes::getByGe360Id)
        } finally {
            quotes.close()
            contracts.close()
        }

        if (targetId != null && target == null) {
            toast(activity, "La bozza è stata cancellata o non è più disponibile")
            return
        }
        if (target != null && SurveyEstimateTransferV1.containsPlan(target, source.planId)) {
            toast(activity, "Questo rilievo è già presente nel preventivo: evita il doppio conteggio")
            return
        }

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        box.addView(TextView(activity).apply {
            text = (
                "Destinazione: " +
                    (target?.number?.ifBlank { target.title } ?: "Nuova bozza") +
                    "\n\n" + SurveyEstimateTransferV1.preview(source)
                )
            textSize = 13f
        })
        val agreed = CheckBox(activity).apply {
            text = "Ho controllato le superfici del calcolo. Confermo di " +
                "importarle SOLO come dati tecnici esclusi, senza prezzi."
            isChecked = false
            setPadding(0, dp(14), 0, 0)
        }
        box.addView(agreed)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Conferma importazione metrature")
            .setView(ScrollView(activity).apply {
                addView(box, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            })
            .setNegativeButton("Annulla", null)
            .setPositiveButton("IMPORTA IN BOZZA", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!agreed.isChecked) {
                    toast(activity, "Conferma prima la verifica del rilievo")
                    return@setOnClickListener
                }
                runCatching {
                    val estimates = EstimateStore(activity)
                    val lifecycle = EstimateLifecycleStore(activity)
                    try {
                        val current = targetId?.let(estimates::getByGe360Id)
                        if (targetId != null) requireNotNull(current) {
                            "La bozza non è più disponibile"
                        }
                        if (current != null) {
                            require(current.status in setOf(
                                EstimateStatuses.DRAFT, EstimateStatuses.REVIEW
                            )) { "Il preventivo non è più modificabile come bozza" }
                            require(!lifecycle.isLocked(current.ge360Id)) {
                                "Il preventivo ha già uno snapshot accettato"
                            }
                            require(!SurveyEstimateTransferV1.containsPlan(current, source.planId)) {
                                "Questo rilievo è già importato: creare una revisione se necessario"
                            }
                            // La bozza scelta non deve essere cambiata durante la conferma.
                            require(current.updatedAt == target?.updatedAt) {
                                "La bozza è stata aggiornata: riapri la selezione"
                            }
                        }
                        val estimate = current ?: EstimateRecord(
                            title = "Rilievo: ${source.planName}",
                            workType = "Rilievo tecnico",
                            status = EstimateStatuses.DRAFT
                        )
                        val priorTotal = current?.finalTotal ?: 0.0
                        estimate.items.addAll(SurveyEstimateTransferV1.buildDraftItems(source))
                        val previewQuote = estimate.copy(
                            items = estimate.items.map { it.copy() }.toMutableList()
                        )
                        recalculateEstimate(previewQuote)
                        require(abs(previewQuote.finalTotal - priorTotal) <= 0.001) {
                            "Il totale è cambiato: importazione annullata"
                        }
                        estimates.save(estimate)
                        EstimateLifecycleUi.recordSaved(
                            activity, estimate,
                            "Importate metrature Rilievo ${source.planId}, versione ${source.version}"
                        )
                        estimate.ge360Id
                    } finally {
                        lifecycle.close()
                        estimates.close()
                    }
                }.onSuccess { estimateId ->
                    dialog.dismiss()
                    toast(activity, "Metrature importate, escluse dal totale. Apri la bozza per lavorazioni e prezzi.")
                    activity.startActivity(
                        Intent(activity, EstimatesActivity::class.java)
                            .putExtra(EstimatesActivity.EXTRA_ESTIMATE_ID, estimateId)
                    )
                }.onFailure { error ->
                    toast(activity, error.message ?: "Importazione non riuscita")
                }
            }
        }
        dialog.show()
    }

    private fun toast(activity: AppCompatActivity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
