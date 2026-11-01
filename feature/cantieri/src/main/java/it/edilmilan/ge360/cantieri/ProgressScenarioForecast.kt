package it.edilmilan.ge360.cantieri

import java.security.MessageDigest
import org.json.JSONArray
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/**
 * Conditional, non-probabilistic scenarios from explicit dated measurements.
 * They never change the jobsite deadline and cannot assert a delivery date.
 * Rates use calendar days, including non-working days: no staffing model is inferred.
 */
data class ProgressFinishScenario(
    val label: String,
    val ratePointsPerDay: Double,
    val date: LocalDate
)

data class ProgressFinishAssessment(
    val state: State,
    val quality: String,
    val observations: Int,
    val spanDays: Long,
    val latestDate: LocalDate?,
    val latestPercent: Int?,
    val observedRate: Double?,
    val scenarios: List<ProgressFinishScenario>,
    val targetEnd: LocalDate?,
    val reasons: List<String>,
    val assumptions: List<String>
) {
    enum class State {
        NOT_READY, NEEDS_NEW_MEASUREMENT, CHANGED_BASELINE,
        STALE, IRREGULAR, READY, READY_WITH_BLOCKERS, COMPLETED
    }
    val canShowDates get() = scenarios.isNotEmpty()
}

object ProgressScenarioForecast {
    private const val MIN_OBSERVATIONS = 3
    private const val MIN_SPAN_DAYS = 7L
    private const val MAX_AGE_DAYS = 14L
    private const val MAX_SAMPLE_DAYS = 90L
    private const val MAX_HORIZON_DAYS = 730L

    /**
     * The same identity/weight fingerprint used by JobsiteStore capture.
     * Titles and percentages do not affect comparability; reweighted phases do.
     */
    fun fingerprint(phases: List<JobsiteProgressItem>): String {
        val canonical = phases.sortedBy { it.ge360Id }.joinToString("|") {
            it.ge360Id + ":" + it.weight
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Avoid silently trusting equal overall % when individual phases have changed. */
    private fun samePhases(
        latest: JobsiteProgressSnapshot,
        current: List<JobsiteProgressItem>
    ): Boolean {
        if (latest.phaseCount != current.size) return false
        val recorded = runCatching {
            val rows = JSONArray(latest.phasesJson)
            (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                row.getString("ge360Id") to
                    (row.getInt("weight") to row.getInt("percent"))
            }
        }.getOrNull() ?: return false
        if (recorded.size != current.size) return false
        val byId = recorded.toMap()
        if (byId.size != current.size) return false
        return current.all { phase ->
            byId[phase.ge360Id] == (phase.weight to phase.progressPercent)
        }
    }

    fun evaluate(
        snapshots: List<JobsiteProgressSnapshot>,
        current: JobsiteProgressSummary?,
        targetDateIso: String,
        today: LocalDate = LocalDate.now(),
        hasUnresolvedBlockers: Boolean = false,
        isOperational: Boolean = true
    ): ProgressFinishAssessment {
        val target = runCatching { LocalDate.parse(targetDateIso) }.getOrNull()
        val assumptions = listOf(
            "Ipotesi: dal giorno di oggi il ritmo riprende come nel periodo osservato",
            "Scenario lento = 75% del ritmo storico, base = 100%, veloce = 125%; fattori ipotetici, non intervalli statistici",
            "Giorni di calendario, non giorni lavorativi. Ferie, meteo, forniture e dipendenze non sono modellati",
            "Le date descrivono il raggiungimento teorico del 100% fisico, non consegna, collaudo o fine contrattuale"
        )
        fun stop(
            state: ProgressFinishAssessment.State,
            reason: String,
            observations: Int = 0,
            span: Long = 0L,
            latest: LocalDate? = null,
            percent: Int? = null
        ) = ProgressFinishAssessment(
            state, "Non valutabile", observations, span, latest, percent, null,
            emptyList(), target, listOf(reason), assumptions
        )

        if (!isOperational) return stop(
            ProgressFinishAssessment.State.NOT_READY,
            "Cantiere in sopralluogo/preventivo: non esiste ancora una fase operativa da proiettare"
        )
        if (current == null || current.items.isEmpty()) return stop(
            ProgressFinishAssessment.State.NOT_READY,
            "Nessuna misurazione attuale delle fasi: verifica Avanzamento & SAL"
        )
        if (current.percent >= 100) return stop(
            ProgressFinishAssessment.State.COMPLETED,
            "Avanzamento fisico attuale al 100%: verifica separatamente consegna e chiusura del cantiere",
            percent = current.percent
        )
        if (snapshots.isEmpty()) return stop(
            ProgressFinishAssessment.State.NOT_READY,
            "Registra rilevazioni datate in Avanzamento & SAL: non esiste uno storico"
        )

        val parsed = snapshots.mapNotNull { row ->
            runCatching { LocalDate.parse(row.observedDate) }.getOrNull()?.let { date -> date to row }
        }
        if (parsed.size != snapshots.size || parsed.any { it.first.isAfter(today) }) return stop(
            ProgressFinishAssessment.State.NOT_READY,
            "Storico con date non valide o future: nessuna proiezione"
        )
        // Keep the last measurement on each actual date.
        val daily = parsed.groupBy { it.first }.toSortedMap().values.map { rows ->
            rows.maxBy { it.second.capturedAt }
        }
        val latest = daily.last()
        val age = ChronoUnit.DAYS.between(latest.first, today)
        if (age > MAX_AGE_DAYS) return stop(
            ProgressFinishAssessment.State.STALE,
            "Ultima rilevazione di " + age + " giorni fa: registra nuovamente lo stato reale",
            daily.size, 0L, latest.first, latest.second.percent
        )

        val latestFingerprint = latest.second.phaseFingerprint
        val presentFingerprint = fingerprint(current.items)
        if (latestFingerprint != presentFingerprint) return stop(
            ProgressFinishAssessment.State.CHANGED_BASELINE,
            "Fasi o pesi correnti diversi dall'ultima rilevazione: registra il nuovo riferimento",
            daily.size, 0L, latest.first, latest.second.percent
        )
        if (current.percent != latest.second.percent) return stop(
            ProgressFinishAssessment.State.NEEDS_NEW_MEASUREMENT,
            "Avanzamento attuale " + current.percent + "% diverso dall'ultima rilevazione (" +
                latest.second.percent + "%): registra una nuova misura prima della simulazione",
            daily.size, 0L, latest.first, latest.second.percent
        )

        if (!samePhases(latest.second, current.items)) return stop(
            ProgressFinishAssessment.State.NEEDS_NEW_MEASUREMENT,
            "Le percentuali delle singole fasi sono cambiate anche se il totale è uguale: registra la situazione attuale",
            daily.size, 0L, latest.first, latest.second.percent
        )

        val stableRecent = daily.asReversed()
            .takeWhile { it.second.phaseFingerprint == latestFingerprint }
            .asReversed()
            .filter { !it.first.isBefore(today.minusDays(MAX_SAMPLE_DAYS)) }
            .takeLast(8)
        val span = if (stableRecent.isEmpty()) 0L else ChronoUnit.DAYS.between(
            stableRecent.first().first, stableRecent.last().first
        )
        if (stableRecent.size < MIN_OBSERVATIONS || span < MIN_SPAN_DAYS) return stop(
            ProgressFinishAssessment.State.NOT_READY,
            "Richieste almeno " + MIN_OBSERVATIONS + " osservazioni su " +
                MIN_SPAN_DAYS + " giorni con identiche fasi e pesi; presenti " +
                stableRecent.size + " osservazioni su " + span + " giorni",
            stableRecent.size, span, latest.first, latest.second.percent
        )
        val consecutiveDeltas = stableRecent.zipWithNext { a, b ->
            b.second.percent - a.second.percent
        }
        if (consecutiveDeltas.any { it < 0 }) return stop(
            ProgressFinishAssessment.State.IRREGULAR,
            "La percentuale è diminuita nel periodo: verifica revisioni/correzioni delle misure",
            stableRecent.size, span, latest.first, latest.second.percent
        )
        if (consecutiveDeltas.count { it > 0 } < 2) return stop(
            ProgressFinishAssessment.State.NOT_READY,
            "Serve crescita misurata in almeno due intervalli distinti; non è lecito estrapolare dal solo salto",
            stableRecent.size, span, latest.first, latest.second.percent
        )
        val gain = stableRecent.last().second.percent - stableRecent.first().second.percent
        val observedRate = gain.toDouble() / span
        if (gain < 3 || !observedRate.isFinite() || observedRate <= 0.0) return stop(
            ProgressFinishAssessment.State.NOT_READY,
            "Avanzamento osservato insufficiente per scenari numerici",
            stableRecent.size, span, latest.first, latest.second.percent
        )
        val remaining = 100 - current.percent
        val profiles = listOf(
            "Lento (75%)" to 0.75, "Base (100%)" to 1.0, "Veloce (125%)" to 1.25
        )
        val computed = profiles.mapNotNull { (label, factor) ->
            val rate = observedRate * factor
            val days = ceil(remaining / rate).toLong()
            if (days <= 0L || days > MAX_HORIZON_DAYS) null
            else ProgressFinishScenario(label, rate, today.plusDays(days))
        }
        if (computed.size != profiles.size) return stop(
            ProgressFinishAssessment.State.NOT_READY,
            "Una o più proiezioni superano due anni: ritmo osservato insufficiente per stimare date",
            stableRecent.size, span, latest.first, latest.second.percent
        )
        val quality = if (stableRecent.size >= 5 && span >= 21 && age <= 3L)
            "Storico più consistente (non probabilistico)"
        else "Preliminare: pochi giorni o osservazioni; verifica spesso"
        val reasons = mutableListOf(
            "Avanzamento: " + stableRecent.first().second.percent + "% → " +
                stableRecent.last().second.percent + "% in " + span + " giorni di calendario",
            "Ultima rilevazione: " + latest.first + " (" + age + " giorni fa)",
            "Ritmo medio osservato: " +
                String.format(java.util.Locale.ITALY, "%.2f", observedRate) + " punti/giorno"
        )
        if (target == null) reasons += "Fine prevista assente/non valida: nessun confronto con una scadenza"
        if (hasUnresolvedBlockers) reasons +=
            "Sono presenti lavorazioni o materiali non risolti: tutti gli scenari presuppongono lo sblocco"
        return ProgressFinishAssessment(
            if (hasUnresolvedBlockers) ProgressFinishAssessment.State.READY_WITH_BLOCKERS
            else ProgressFinishAssessment.State.READY,
            quality, stableRecent.size, span, latest.first, current.percent,
            observedRate, computed, target, reasons, assumptions
        )
    }
}
