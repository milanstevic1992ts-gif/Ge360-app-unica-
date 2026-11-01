package it.edilmilan.ge360.agenda

import it.edilmilan.ge360.cantieri.JobsiteStore
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Explainable schedule alerts, NOT a finish-date forecast or a probability.
 * Uses authoritative end date, live workflow and resource status; no imaginary S-curve.
 */
data class JobsiteDelayAssessment(
    val jobsiteId: String,
    val level: Level,
    val reasons: List<String>,
    val missingEvidence: List<String>,
    val suggestedAction: String,
    val targetEnd: LocalDate?,
    val daysUntilTarget: Long?
) {
    enum class Level(val title: String, val order: Int) {
        OVERDUE("Scadenza prevista superata", 0),
        HIGH("Attenzione alta", 1),
        ATTENTION("Da verificare", 2),
        UNKNOWN("Dati da completare", 3),
        OBSERVE("Nessun blocco noto", 4),
        PREPARATION("In preparazione", 5)
    }

    val isActionable: Boolean get() = level in setOf(
        Level.OVERDUE, Level.HIGH, Level.ATTENTION, Level.UNKNOWN
    )
}

object JobsiteDelaySignals {
    fun evaluate(
        state: WeeklyJobHealth,
        today: LocalDate = LocalDate.now()
    ): JobsiteDelayAssessment {
        val job = state.job
        val deadline = runCatching { LocalDate.parse(job.targetEndDate) }.getOrNull()
        val daysLeft = deadline?.let { ChronoUnit.DAYS.between(today, it) }
        val blockers = state.blockedWork > 0
        val missingMaterials = (state.resources?.missing ?: 0) > 0
        val openRequests = (state.resources?.openRequests ?: 0) > 0
        val materialDataMissing = state.resources == null

        val missing = mutableListOf<String>()
        if (deadline == null) {
            missing += if (job.targetEndDate.isBlank())
                "Data di fine prevista non impostata"
            else "Data di fine prevista non valida"
        }
        if (state.progressPercent == null) missing += "Avanzamento percentuale non registrato"
        if (materialDataMissing) missing += "Riepilogo materiali non disponibile"
        if (!state.workflowAvailable) missing += "Checklist cantiere non disponibile"
        missing += "Durate e dipendenze delle lavorazioni non pianificate: impossibile stimare una nuova fine"

        val reasons = mutableListOf<String>()
        if (daysLeft != null) reasons += when {
            daysLeft < 0 -> "Fine prevista superata da " + (-daysLeft) + " giorni; cantiere non segnato completato"
            daysLeft == 0L -> "Fine prevista oggi"
            daysLeft in 1L..7L -> "Fine prevista tra " + daysLeft + " giorni"
            else -> "Fine prevista tra " + daysLeft + " giorni"
        }
        if (blockers) reasons += state.blockedWork.toString() + " lavorazioni bloccate nella checklist"
        if (missingMaterials) reasons += state.resources!!.missing.toString() + " materiali marcati mancanti o da comprare"
        if (openRequests) reasons += state.resources!!.openRequests.toString() + " richieste materiali non chiuse"
        if (job.status == JobsiteStore.STATUS_PAUSED) reasons += "Cantiere attualmente sospeso"
        if (state.progressPercent != null) reasons += "Avanzamento registrato: " + state.progressPercent + "% (dato attuale)"
        if (state.openWork > 0) reasons += state.openWork.toString() + " lavorazioni attualmente aperte"

        val preparation = job.status == JobsiteStore.STATUS_INSPECTION ||
            job.status == JobsiteStore.STATUS_ESTIMATE
        val obstacle = blockers || missingMaterials || openRequests ||
            job.status == JobsiteStore.STATUS_PAUSED
        val level = when {
            preparation -> JobsiteDelayAssessment.Level.PREPARATION
            daysLeft != null && daysLeft < 0 -> JobsiteDelayAssessment.Level.OVERDUE
            daysLeft != null && daysLeft <= 7 && (obstacle || state.openWork > 0 || state.progressPercent == null) ->
                JobsiteDelayAssessment.Level.HIGH
            obstacle || (daysLeft != null && daysLeft <= 7) -> JobsiteDelayAssessment.Level.ATTENTION
            deadline == null || materialDataMissing || !state.workflowAvailable || state.progressPercent == null ->
                JobsiteDelayAssessment.Level.UNKNOWN
            else -> JobsiteDelayAssessment.Level.OBSERVE
        }
        val action = when {
            preparation -> "Completa sopralluogo o preventivo; imposta il programma quando partiranno i lavori"
            level == JobsiteDelayAssessment.Level.OVERDUE ->
                "Conferma lo stato reale e aggiorna la fine prevista nella scheda originale"
            blockers -> "Verifica le attività bloccate e le condizioni necessarie per ripartire"
            missingMaterials -> "Controlla disponibilità e ordini dei materiali nel modulo dedicato"
            openRequests -> "Verifica le richieste materiali e le eventuali consegne"
            job.status == JobsiteStore.STATUS_PAUSED -> "Verifica le ragioni della sospensione e ripianifica"
            deadline == null -> "Imposta una data di fine realistica nella scheda cantiere"
            level == JobsiteDelayAssessment.Level.HIGH || level == JobsiteDelayAssessment.Level.ATTENTION ->
                "Confronta le lavorazioni aperte con i giorni rimasti"
            !state.workflowAvailable -> "Apri la checklist del cantiere e verifica le lavorazioni"
            state.progressPercent == null -> "Aggiorna l'avanzamento della scheda cantiere"
            else -> "Monitora il programma: assenza di blocchi noti non garantisce la consegna"
        }
        return JobsiteDelayAssessment(
            jobsiteId = job.ge360Id,
            level = level,
            reasons = reasons,
            missingEvidence = missing,
            suggestedAction = action,
            targetEnd = deadline,
            daysUntilTarget = daysLeft
        )
    }
}
