package it.edilmilan.ge360.centrocantiere

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A plan referencing canonical WorkflowTask UUIDs, never a duplicate checklist.
 * Days are operator-entered MON-FRI workdays, NOT automatically inferred.
 */
data class WorkflowTaskPlan(
    val taskGe360Id: String,
    val jobsiteId: String,
    val plannedWorkdays: Int,
    val predecessorIds: List<String>,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class TaskScheduleLine(
    val task: WorkflowTask,
    val plannedWorkdays: Int?,
    val predecessorIds: List<String>,
    val start: LocalDate?,
    val finish: LocalDate?,
    val state: State,
    val reason: String
) {
    enum class State {
        DONE, NOT_NEEDED, BLOCKED, MISSING_PLAN, WAITING_DEPENDENCY, SCHEDULED
    }
    val isActive: Boolean get() = state != State.DONE && state != State.NOT_NEEDED
}

data class TaskScheduleReport(
    val jobsiteId: String,
    val anchor: LocalDate,
    val targetEnd: LocalDate?,
    val lines: List<TaskScheduleLine>,
    val possibleFinish: LocalDate?,
    val fullyPlanned: Boolean,
    val problems: List<String>,
    val totalPlannedWorkdays: Int
) {
    val active: Int get() = lines.count { it.isActive }
    val completed: Int get() = lines.count { it.state == TaskScheduleLine.State.DONE }
    val scheduled: Int get() = lines.count { it.state == TaskScheduleLine.State.SCHEDULED }
    val blocked: Int get() = lines.count { it.state == TaskScheduleLine.State.BLOCKED }
    val missing: Int get() = lines.count {
        it.state == TaskScheduleLine.State.MISSING_PLAN ||
            it.state == TaskScheduleLine.State.WAITING_DEPENDENCY
    }
}

/**
 * Finish-to-start precedence. Independent tasks run in parallel IN THEORY.
 * This is NOT capacity scheduling: it does not assign crews, tools or materials,
 * and does not account for Italian holidays, weather or subcontractor availability.
 */
object TaskScheduleEngine {
    private const val MAX_TASK_DAYS = 260

    fun validateCandidate(
        task: WorkflowTask,
        candidate: WorkflowTaskPlan,
        tasks: List<WorkflowTask>,
        existing: List<WorkflowTaskPlan>
    ) {
        require(task.ge360Id.isNotBlank() && task.jobsiteId.isNotBlank()) { "Attività senza UUID cantiere" }
        require(candidate.taskGe360Id == task.ge360Id && candidate.jobsiteId == task.jobsiteId) {
            "Il piano deve appartenere alla lavorazione originale"
        }
        require(candidate.plannedWorkdays in 0..MAX_TASK_DAYS) { "Durata valida: da 1 a 260 giorni, oppure non definita" }
        require(candidate.predecessorIds.distinct().size == candidate.predecessorIds.size) {
            "Non ripetere la stessa dipendenza"
        }
        require(task.ge360Id !in candidate.predecessorIds) { "Un'attività non può dipendere da sé stessa" }
        val local = tasks.filter { it.jobsiteId == task.jobsiteId && it.ge360Id.isNotBlank() }
        val ids = local.map { it.ge360Id }.toSet()
        require(candidate.predecessorIds.all { it in ids }) {
            "Una dipendenza è assente o appartiene a un altro cantiere"
        }
        val all = (existing.filter { it.taskGe360Id != task.ge360Id } + candidate)
            .associateBy { it.taskGe360Id }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun check(id: String) {
            if (id in visiting) error("Dipendenze circolari: rimuovi il collegamento che chiude il ciclo")
            if (id in visited) return
            visiting += id
            all[id]?.predecessorIds.orEmpty().forEach(::check)
            visiting -= id
            visited += id
        }
        ids.forEach(::check)
    }

    fun project(
        tasks: List<WorkflowTask>,
        plans: List<WorkflowTaskPlan>,
        today: LocalDate = LocalDate.now(),
        targetEndIso: String = ""
    ): TaskScheduleReport {
        val byTask = tasks.filter { it.ge360Id.isNotBlank() }.associateBy { it.ge360Id }
        val jobsiteId = tasks.firstOrNull()?.jobsiteId.orEmpty()
        val plansById = plans.filter { it.jobsiteId == jobsiteId }.associateBy { it.taskGe360Id }
        val anchor = nextWorkday(today)
        val memo = mutableMapOf<String, TaskScheduleLine>()
        val visiting = mutableSetOf<String>()

        fun line(taskId: String): TaskScheduleLine {
            memo[taskId]?.let { return it }
            val task = byTask[taskId] ?: error("Attività non presente")
            val plan = plansById[taskId]
            val predecessors = plan?.predecessorIds.orEmpty()
            fun make(state: TaskScheduleLine.State, reason: String, start: LocalDate? = null, finish: LocalDate? = null) =
                TaskScheduleLine(task, plan?.plannedWorkdays?.takeIf { it > 0 }, predecessors, start, finish, state, reason)
            if (!visiting.add(taskId)) {
                return make(TaskScheduleLine.State.WAITING_DEPENDENCY, "Dipendenze circolari: correggi il piano")
            }
            val answer = when (task.status) {
                WorkflowStatuses.DONE -> make(TaskScheduleLine.State.DONE, "Già completata; data effettiva non dedotta")
                WorkflowStatuses.NOT_NEEDED -> make(TaskScheduleLine.State.NOT_NEEDED, "Esclusa dalla checklist")
                WorkflowStatuses.BLOCKED -> make(TaskScheduleLine.State.BLOCKED, "Lavorazione bloccata nello stato originale")
                else -> {
                    val missingRefs = predecessors.filter { it !in byTask || byTask[it]?.jobsiteId != task.jobsiteId }
                    when {
                        missingRefs.isNotEmpty() ->
                            make(TaskScheduleLine.State.WAITING_DEPENDENCY, "Predecessore eliminato o non presente: correggi le dipendenze")
                        plan == null || plan.plannedWorkdays <= 0 || plan.plannedWorkdays > MAX_TASK_DAYS ->
                            make(TaskScheduleLine.State.MISSING_PLAN, "Durata della lavorazione non configurata")
                        else -> {
                            val dependencies = predecessors.map(::line)
                            when {
                                dependencies.any { it.state == TaskScheduleLine.State.BLOCKED } ->
                                    make(TaskScheduleLine.State.BLOCKED, "Una lavorazione precedente è bloccata")
                                task.status == WorkflowStatuses.IN_PROGRESS && dependencies.any {
                                    it.state != TaskScheduleLine.State.DONE &&
                                        it.state != TaskScheduleLine.State.NOT_NEEDED
                                } -> make(TaskScheduleLine.State.WAITING_DEPENDENCY,
                                    "Attività già in corso ma predecessori non conclusi: correggi o conferma la checklist")
                                dependencies.any {
                                    it.state != TaskScheduleLine.State.DONE &&
                                        it.state != TaskScheduleLine.State.NOT_NEEDED &&
                                        it.state != TaskScheduleLine.State.SCHEDULED
                                } ->
                                    make(TaskScheduleLine.State.WAITING_DEPENDENCY, "Una dipendenza non ha una fine calcolabile")
                                else -> {
                                    val dependencyEnd = dependencies.mapNotNull { it.finish }.maxOrNull()
                                    val start = if (dependencyEnd == null) anchor else
                                        maxOf(anchor, nextWorkday(dependencyEnd.plusDays(1)))
                                    val end = addWorkdays(start, plan.plannedWorkdays - 1)
                                    make(TaskScheduleLine.State.SCHEDULED,
                                        if (task.status == WorkflowStatuses.IN_PROGRESS)
                                            "Durata inserita interpretata come giorni ancora necessari; squadra non allocata"
                                        else "In parallelo alle attività indipendenti; squadra non allocata",
                                        start, end)
                                }
                            }
                        }
                    }
                }
            }
            visiting -= taskId
            memo[taskId] = answer
            return answer
        }

        val lines = tasks.map { line(it.ge360Id) }
        val active = lines.filter { it.isActive }
        val complete = active.isNotEmpty() && active.all { it.state == TaskScheduleLine.State.SCHEDULED }
        val expected = if (complete) active.mapNotNull { it.finish }.maxOrNull() else null
        val target = runCatching { LocalDate.parse(targetEndIso) }.getOrNull()
        val warnings = mutableListOf<String>()
        if (tasks.isEmpty()) warnings += "Nessuna lavorazione nel cantiere"
        if (active.isEmpty() && tasks.isNotEmpty()) warnings += "Nessuna lavorazione residua: verificare la chiusura effettiva"
        val unplanned = active.count { it.state == TaskScheduleLine.State.MISSING_PLAN }
        val blocked = active.count { it.state == TaskScheduleLine.State.BLOCKED }
        val pending = active.count { it.state == TaskScheduleLine.State.WAITING_DEPENDENCY }
        if (unplanned > 0) warnings += "$unplanned lavorazioni senza durata"
        if (blocked > 0) warnings += "$blocked lavorazioni bloccate, incluse dipendenze impedite"
        if (pending > 0) warnings += "$pending lavorazioni con dipendenze non risolte"
        if (expected != null && target != null && expected.isAfter(target)) {
            val gap = ChronoUnit.DAYS.between(target, expected)
            warnings += "Il piano teorico termina $gap giorni di calendario oltre la fine prevista"
        }
        if (complete) warnings +=
            "Fine solo teorica in giorni lun–ven: non considera disponibilità squadra, festività, materiali o collaudo"
        return TaskScheduleReport(
            jobsiteId, anchor, target, lines, expected, complete, warnings,
            active.sumOf { it.plannedWorkdays ?: 0 }
        )
    }

    private fun nextWorkday(date: LocalDate): LocalDate {
        var d = date
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
        return d
    }

    private fun addWorkdays(start: LocalDate, additional: Int): LocalDate {
        var date = nextWorkday(start)
        repeat(additional) { date = nextWorkday(date.plusDays(1)) }
        return date
    }
}
