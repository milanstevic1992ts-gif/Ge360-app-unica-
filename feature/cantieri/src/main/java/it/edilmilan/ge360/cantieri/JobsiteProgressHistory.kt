package it.edilmilan.ge360.cantieri

import java.time.LocalDate

/**
 * An explicit, immutable observation of jobsite physical progress.
 * No snapshot is backdated or inferred from a later state.
 * The phase list and weights are captured with the measurement.
 */
data class JobsiteProgressSnapshot(
    val ge360Id: String,
    val jobsiteId: String,
    val capturedAt: Long,
    val observedDate: String,
    val percent: Int,
    val totalWeight: Int,
    val phaseCount: Int,
    val phaseFingerprint: String,
    val phasesJson: String,
    val targetEndDate: String,
    val jobsiteStatus: String,
    val note: String
)

/**
 * A comparison is possible only for genuine observations on separate days
 * whose phase IDs and weights did not change. Rate is not an ETA.
 */
data class JobsiteProgressTrend(
    val observations: Int,
    val observedDays: Int,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
    val firstPercent: Int?,
    val lastPercent: Int?,
    val deltaPoints: Int?,
    val pointsPerDay: Double?,
    val explanation: String
)

object JobsiteProgressHistory {
    fun analyze(snapshots: List<JobsiteProgressSnapshot>): JobsiteProgressTrend {
        if (snapshots.isEmpty()) return JobsiteProgressTrend(
            0, 0, null, null, null, null, null, null,
            "Nessuna rilevazione storica: lo stato attuale non viene trasformato in una data passata."
        )
        val valid = snapshots.mapNotNull { item ->
            runCatching { LocalDate.parse(item.observedDate) }.getOrNull()?.let { it to item }
        }.sortedWith(compareBy<Pair<LocalDate, JobsiteProgressSnapshot>> { it.first }
            .thenBy { it.second.capturedAt })
        if (valid.isEmpty()) return JobsiteProgressTrend(
            snapshots.size, 0, null, null, null, null, null, null,
            "Date delle rilevazioni non leggibili; confronto non disponibile."
        )
        // Multiple observations on one date remain in the audit history,
        // but never masquerade as observations on distinct days.
        val byDay = valid.groupBy { it.first }.toSortedMap()
        val daily = byDay.values.map { group -> group.maxBy { it.second.capturedAt } }
        val first = daily.first()
        val last = daily.last()
        val elapsed = java.time.temporal.ChronoUnit.DAYS.between(first.first, last.first)
        val sameStructure = daily.map { it.second.phaseFingerprint }.distinct().size == 1
        val reason = when {
            daily.size < 2 -> "Serve almeno una seconda rilevazione in un altro giorno."
            !sameStructure -> "Fasi o pesi modificati tra le rilevazioni: la differenza percentuale non è omogenea."
            elapsed <= 0 -> "Intervallo tra rilevazioni insufficiente."
            else -> "Variazione fisica osservata: non è una previsione di consegna né una produttività certificata."
        }
        val delta = if (daily.size >= 2 && sameStructure && elapsed > 0) {
            last.second.percent - first.second.percent
        } else null
        return JobsiteProgressTrend(
            observations = snapshots.size,
            observedDays = daily.size,
            firstDate = first.first,
            lastDate = last.first,
            firstPercent = first.second.percent,
            lastPercent = last.second.percent,
            deltaPoints = delta,
            pointsPerDay = delta?.toDouble()?.div(elapsed.toDouble()),
            explanation = reason
        )
    }
}
