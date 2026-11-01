package it.edilmilan.ge360.agenda

import android.content.Context
import it.edilmilan.ge360.giornata.DailyStore
import it.edilmilan.ge360.giornata.PersonnelAssignment
import it.edilmilan.ge360.giornata.WorkSession
import java.time.LocalDate
import java.util.Locale

/**
 * Audit of canonical Giornata timer evidence. All quantities are minute-based.
 * The past-day variance excludes today and future dates to avoid false delays.
 * Does not infer that unrecorded time means a job was not performed.
 */
data class WeeklyLaborDayAudit(
    val date: LocalDate,
    val plannedMinutes: Long,
    val recordedMinutes: Long,
    val unplannedMinutes: Long,
    val runningMinutes: Long,
    val sessionCount: Int,
    val incompleteTimers: Int
) {
}

data class WeeklyLaborJobAudit(
    val jobsiteId: String,
    val plannedMinutes: Long,
    val recordedMinutes: Long,
    val closedPlannedMinutes: Long,
    val closedRecordedMinutes: Long,
    val unplannedMinutes: Long,
    val runningTimers: Int
) {
    val closedVariance: Long get() = closedRecordedMinutes - closedPlannedMinutes
}

data class WeeklyExecutionSnapshot(
    val daily: List<WeeklyLaborDayAudit>,
    val byJob: Map<String, WeeklyLaborJobAudit>,
    val warnings: List<String>,
    val plannedMinutes: Long,
    val recordedMinutes: Long,
    val closedPlannedMinutes: Long,
    val closedRecordedMinutes: Long,
    val unplannedMinutes: Long,
    val runningTimers: Int
) {
    val closedVariance: Long get() = closedRecordedMinutes - closedPlannedMinutes
    val completedDays: Int get() = daily.count { it.date.isBefore(LocalDate.now()) }
}

/** No persistence: every reload reflects the authoritative DailyStore. */
object WeeklyExecutionComparison {
    private data class Evidence(val session: WorkSession, val minutes: Long, val matched: Boolean)

    fun load(
        context: Context,
        monday: LocalDate,
        assignments: List<PersonnelAssignment>,
        now: Long = System.currentTimeMillis(),
        today: LocalDate = LocalDate.now()
    ): WeeklyExecutionSnapshot {
        val store = DailyStore(context)
        val sessions: List<WorkSession>
        try {
            sessions = (0L..6L).flatMap { offset -> store.sessions(monday.plusDays(offset).toString()) }
        } finally {
            store.close()
        }
        return calculate(monday, assignments, sessions, now, today)
    }

    fun calculate(
        monday: LocalDate,
        assignments: List<PersonnelAssignment>,
        sessions: List<WorkSession>,
        now: Long,
        today: LocalDate
    ): WeeklyExecutionSnapshot {
        val warnings = mutableListOf<String>()
        val validPlan = assignments.filter { it.status != PersonnelAssignment.STATUS_CANCELLED }
        val planByDay = validPlan.groupBy { it.workDate }
        val seen = mutableSetOf<String>()
        val actual = sessions.asSequence().filter {
            // A UUID is the only reliable way to de-duplicate a recorded timer.
            it.ge360Id.isBlank() || seen.add(it.ge360Id)
        }.mapNotNull { session ->
            val date = runCatching { LocalDate.parse(session.workDate) }.getOrNull()
            if (date == null || date.isBefore(monday) || !date.isBefore(monday.plusDays(7))) {
                warnings += "Timer con data non valida o esterna alla settimana"
                return@mapNotNull null
            }
            if (session.isRunning() && date != today) {
                warnings += "Timer ancora aperto in una giornata precedente: verificarlo in Giornata"
                return@mapNotNull Evidence(session, 0L, false)
            }
            val row = planByDay[session.workDate].orEmpty()
                .filter { it.jobsiteId == session.jobsiteId }
            val matched = if (session.personnelId.isNotBlank()) {
                row.any { it.personnelId == session.personnelId }
            } else {
                val sameName = row.filter {
                    it.personnelName.trim().lowercase(Locale.ITALY) ==
                        session.collaborator.trim().lowercase(Locale.ITALY)
                }
                sameName.size == 1
            }
            Evidence(session, session.durationMinutes(now), matched)
        }.toList()
        if (actual.any { it.session.jobsiteId.isBlank() }) {
            warnings += "Timer senza UUID cantiere: il tempo è nel totale, non attribuito a un cantiere"
        }
        val days = (0L..6L).map { off ->
            val day = monday.plusDays(off)
            val planned = planByDay[day.toString()].orEmpty().sumOf { it.plannedMinutes.toLong() }
            val dayActual = actual.filter { it.session.workDate == day.toString() }
            WeeklyLaborDayAudit(
                date = day,
                plannedMinutes = planned,
                recordedMinutes = dayActual.sumOf { it.minutes },
                unplannedMinutes = dayActual.filterNot { it.matched }.sumOf { it.minutes },
                runningMinutes = dayActual.filter { it.session.isRunning() }.sumOf { it.minutes },
                sessionCount = dayActual.count { it.minutes > 0 || !it.session.isRunning() },
                incompleteTimers = dayActual.count {
                    it.session.isRunning() && day.isBefore(today)
                }
            )
        }
        val allJobs = (validPlan.map { it.jobsiteId } +
            actual.map { it.session.jobsiteId }).filter { it.isNotBlank() }.distinct()
        val jobs = allJobs.associateWith { id ->
            val planned = validPlan.filter { it.jobsiteId == id }
            val observed = actual.filter { it.session.jobsiteId == id }
            WeeklyLaborJobAudit(
                jobsiteId = id,
                plannedMinutes = planned.sumOf { it.plannedMinutes.toLong() },
                recordedMinutes = observed.sumOf { it.minutes },
                closedPlannedMinutes = planned.filter {
                    runCatching { LocalDate.parse(it.workDate).isBefore(today) }.getOrDefault(false)
                }.sumOf { it.plannedMinutes.toLong() },
                closedRecordedMinutes = observed.filter {
                    runCatching { LocalDate.parse(it.session.workDate).isBefore(today) }.getOrDefault(false)
                }.sumOf { it.minutes },
                unplannedMinutes = observed.filterNot { it.matched }.sumOf { it.minutes },
                runningTimers = observed.count { it.session.isRunning() }
            )
        }
        val past = days.filter { it.date.isBefore(today) }
        return WeeklyExecutionSnapshot(
            daily = days,
            byJob = jobs,
            warnings = warnings.distinct(),
            plannedMinutes = days.sumOf { it.plannedMinutes },
            recordedMinutes = days.sumOf { it.recordedMinutes },
            closedPlannedMinutes = past.sumOf { it.plannedMinutes },
            closedRecordedMinutes = past.sumOf { it.recordedMinutes },
            unplannedMinutes = days.sumOf { it.unplannedMinutes },
            runningTimers = actual.count { it.session.isRunning() }
        )
    }
}
