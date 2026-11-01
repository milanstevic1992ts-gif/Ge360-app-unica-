package it.edilmilan.ge360.agenda

import android.content.Context
import it.edilmilan.ge360.attrezzi.JobResourceSummary
import it.edilmilan.ge360.attrezzi.ToolsStore
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.cantieri.JobsiteProgressTrend
import it.edilmilan.ge360.cantieri.ProgressFinishAssessment
import it.edilmilan.ge360.centrocantiere.JobsiteWorkflowStore
import it.edilmilan.ge360.centrocantiere.WorkflowStatuses
import it.edilmilan.ge360.centrocantiere.TaskScheduleEngine
import it.edilmilan.ge360.centrocantiere.TaskScheduleReport
import it.edilmilan.ge360.giornata.DailyStore
import it.edilmilan.ge360.giornata.PersonnelAssignment
import it.edilmilan.ge360.giornata.PersonnelRecord
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Projection of existing owners. No new SQLite tables, no copied assignments. */
data class WeeklyJobHealth(
    val job: JobsiteRecord,
    val resources: JobResourceSummary?,
    val openWork: Int,
    val blockedWork: Int,
    val doneWork: Int,
    val progressPercent: Int?,
    val workflowAvailable: Boolean,
    val progressHistory: JobsiteProgressTrend?,
    val finishScenarios: ProgressFinishAssessment?,
    val taskSchedule: TaskScheduleReport?
) {
    val needsReview: Boolean
        get() = resources == null || !workflowAvailable ||
            (resources.missing > 0) || (resources.openRequests > 0) || blockedWork > 0
}

data class WeeklyStaffConflict(
    val personId: String,
    val date: LocalDate,
    val plannedMinutes: Int,
    val differentJobs: Int
)

data class WeeklyPlannerDay(
    val date: LocalDate,
    val assignments: List<PersonnelAssignment>,
    val agenda: List<AgendaOccurrence>,
    val deadlines: List<AgendaSourceDeadline>,
    val conflicts: List<WeeklyStaffConflict>
)

data class WeeklyPlannerSnapshot(
    val monday: LocalDate,
    val jobs: List<WeeklyJobHealth>,
    val people: List<PersonnelRecord>,
    val days: List<WeeklyPlannerDay>,
    val conflicts: List<WeeklyStaffConflict>,
    val execution: WeeklyExecutionSnapshot?,
    val warnings: List<String>
) {
    val assignmentCount get() = days.sumOf { it.assignments.size }
    val appointmentCount get() = days.sumOf { it.agenda.count { occurrence -> occurrence.entry.kind == AgendaEntry.EVENT } }
    val openIssues get() = jobs.count { it.needsReview }
}

object WeeklySitePlanner {
    fun mondayOf(day: LocalDate): LocalDate =
        day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun findConflicts(rows: List<PersonnelAssignment>): List<WeeklyStaffConflict> =
        rows.filter { it.status != PersonnelAssignment.STATUS_CANCELLED }
            .groupBy { it.personnelId to it.workDate }
            .mapNotNull { (key, assignments) ->
                val minutes = assignments.sumOf { it.plannedMinutes }
                val jobCount = assignments.map { it.jobsiteId }.distinct().size
                if (minutes <= 480 && jobCount <= 1) null
                else runCatching { LocalDate.parse(key.second) }.getOrNull()?.let { date ->
                    WeeklyStaffConflict(key.first, date, minutes, jobCount)
                }
            }.sortedWith(compareBy<WeeklyStaffConflict> { it.date }.thenBy { it.personId })

    fun load(context: Context, anchor: LocalDate): WeeklyPlannerSnapshot {
        val monday = mondayOf(anchor)
        val sunday = monday.plusDays(6)
        val warnings = mutableListOf<String>()
        val jobs = JobsiteStore(context)
        val staff = DailyStore(context)
        val tools = ToolsStore(context)
        val workflow = JobsiteWorkflowStore(context)
        val active: List<JobsiteRecord>
        val people: List<PersonnelRecord>
        val assignmentRows: List<PersonnelAssignment>
        val health: List<WeeklyJobHealth>
        try {
            active = jobs.list().filter {
                it.status != JobsiteStore.STATUS_ARCHIVED &&
                    it.status != JobsiteStore.STATUS_COMPLETED
            }
            people = staff.personnel(activeOnly = true)
            assignmentRows = staff.assignments(monday, sunday)
            health = active.map { job ->
                val summary = runCatching { tools.summary(job.ge360Id) }
                    .onFailure { warnings += "Materiali non leggibili per " + job.displayTitle() }
                    .getOrNull()
                val taskResult = runCatching { workflow.tasks(job.ge360Id) }
                    .onFailure { warnings += "Lavorazioni non leggibili per " + job.displayTitle() }
                val tasks = taskResult.getOrDefault(emptyList())
                WeeklyJobHealth(
                    job, summary,
                    tasks.count { it.status in setOf(WorkflowStatuses.TODO, WorkflowStatuses.IN_PROGRESS, WorkflowStatuses.BLOCKED) },
                    tasks.count { it.status == WorkflowStatuses.BLOCKED },
                    tasks.count { it.status == WorkflowStatuses.DONE },
                    runCatching { jobs.progressSummary(job.ge360Id)?.percent }
                        .onFailure { warnings += "Avanzamento non leggibile per " + job.displayTitle() }
                        .getOrNull(),
                    taskResult.isSuccess,
                    runCatching { jobs.progressTrend(job.ge360Id) }
                        .onFailure { warnings += "Storico avanzamento non leggibile per " + job.displayTitle() }
                        .getOrNull(),
                    runCatching {
                        jobs.progressForecast(job.ge360Id, hasUnresolvedBlockers =
                            (summary?.missing ?: 0) > 0 ||
                            (summary?.openRequests ?: 0) > 0 ||
                            tasks.any { it.status == WorkflowStatuses.BLOCKED } ||
                            job.status == JobsiteStore.STATUS_PAUSED ||
                            summary == null || taskResult.isFailure)
                    }.onFailure { warnings += "Scenario non leggibile per " + job.displayTitle() }
                        .getOrNull(),
                    if (taskResult.isFailure) null else runCatching {
                        TaskScheduleEngine.project(
                            tasks, workflow.taskPlans(job.ge360Id),
                            LocalDate.now(), job.targetEndDate
                        )
                    }.onFailure { warnings += "Piano lavorazioni non leggibile per " + job.displayTitle() }
                        .getOrNull()
                )
            }
        } finally {
            workflow.close()
            tools.close()
            staff.close()
            jobs.close()
        }

        val agenda = runCatching { AgendaStore(context).list() }.onFailure {
            warnings += "Note e Agenda non leggibili: nessuna modifica effettuata"
        }.getOrDefault(emptyList())
        val sources = AgendaSourceProjection.read(context)
        warnings.addAll(sources.warnings)
        val from = monday.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val until = monday.plusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val occurrences = agenda.flatMap { AgendaCalendar.occurrences(it, from, until) }
        val datedSources = sources.deadlines.filter { it.dateAt >= from && it.dateAt < until }
        val conflicts = findConflicts(assignmentRows)
        val execution = runCatching { WeeklyExecutionComparison.load(context, monday, assignmentRows) }
            .onFailure { warnings += "Consuntivo ore non disponibile: consultare Giornata" }
            .getOrNull()
        warnings.addAll(execution?.warnings.orEmpty())
        val week = (0L..6L).map { offset ->
            val day = monday.plusDays(offset)
            WeeklyPlannerDay(
                date = day,
                assignments = assignmentRows.filter { it.workDate == day.toString() },
                agenda = occurrences.filter { dayOf(it.at) == day },
                deadlines = datedSources.filter { dayOf(it.dateAt) == day },
                conflicts = conflicts.filter { it.date == day }
            )
        }
        return WeeklyPlannerSnapshot(monday, health, people, week, conflicts, execution, warnings.distinct())
    }

    fun dayOf(epochMillis: Long): LocalDate =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
}
