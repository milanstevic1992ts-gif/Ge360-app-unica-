package it.edilmilan.ge360.preventivi

import android.app.AlertDialog
import android.content.Context

/**
 * Compatibilità con le versioni precedenti dei configuratori guidati.
 * La UI nuova usa ambienti indipendenti, checklist libera e misure.
 */
object GuidedWorkCatalog {
    const val VERSION = "2026.09.5"

    private val legacyPrefixes = listOf("CUC-", "PIA-", "PAV-", "PIT-", "CAR-", "MAS-", "TER-", "IMP-", "DEM-", "APP-")

    fun isGuidedCode(code: String): Boolean =
        RoomWorkCatalog.isRoomWorkCode(code) ||
            code.startsWith(RoomManualWorkDialog.MANUAL_PREFIX) ||
            legacyPrefixes.any { code.startsWith(it) }

    fun referencesFor(items: List<EstimateItem>): List<String> {
        val refs = mutableListOf<String>()
        refs += RoomWorkCatalog.referencesFor(items)
        refs += WorkReferenceRegistry.referencesFor(items)
        items.filter {
            !RoomWorkCatalog.isRoomWorkCode(it.priceListCode) &&
                !it.priceListCode.startsWith(RoomManualWorkDialog.MANUAL_PREFIX)
        }.forEach { item ->
            val capCode = Regex("CAP-\\d{2}").find(item.priceListCode)?.value
            if (capCode != null) refs += StandardWorkCatalog.byCode(capCode)?.references.orEmpty()
        }
        return refs.distinct()
    }
}

object GuidedWorkConfigurator {
    fun openForEstimate(context: Context, estimate: EstimateRecord, onApplied: () -> Unit) {
        val lifecycle = EstimateLifecycleStore(context)
        val locked = lifecycle.isLocked(estimate.ge360Id)
        lifecycle.close()

        if (locked) {
            EstimateLifecycleUi.show(context, estimate, onApplied)
            return
        }

        if (estimate.status == EstimateStatuses.SENT) {
            AlertDialog.Builder(context)
                .setTitle("${estimate.number} · Inviato")
                .setItems(arrayOf("Continua a modificare lavorazioni", "Gestione contratto / accettazione")) { _, which ->
                    if (which == 0) openRoomsTracked(context, estimate, onApplied)
                    else EstimateLifecycleUi.show(context, estimate, onApplied)
                }
                .setNegativeButton("Chiudi", null)
                .show()
            return
        }

        openRoomsTracked(context, estimate, onApplied)
    }

    fun chooseConfigurator(context: Context, estimate: EstimateRecord, onApplied: () -> Unit) {
        openForEstimate(context, estimate, onApplied)
    }

    private fun openRoomsTracked(context: Context, estimate: EstimateRecord, onApplied: () -> Unit) {
        FlexibleRoomPlanner.openForEstimate(context, estimate) {
            EstimateLifecycleUi.recordSaved(context, estimate, "Configurazione lavorazioni aggiornata")
            onApplied()
        }
    }
}
