package it.edilmilan.ge360.agenda

import android.content.Context
import it.edilmilan.ge360.cantieri.JobsiteMilestone
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.clienti.ClientStore
import it.edilmilan.ge360.centrocantiere.JobsiteWorkflowStore
import it.edilmilan.ge360.centrocantiere.WorkflowStatuses
import it.edilmilan.ge360.centrocantiere.WorkflowSources
import java.time.LocalDate
import java.time.ZoneId

/** Read-only deadlines owned by Clienti/Cantieri; no copies in AgendaStore. */
data class AgendaSourceDeadline(
    val key: String,
    val sourceType: String,
    val title: String,
    val subtitle: String,
    val dateAt: Long,
    val clientGe360Id: String = "",
    val jobsiteGe360Id: String = ""
) {
    companion object {
        const val CLIENT_FACT = "CLIENT_FACT"
        const val JOB_END = "JOB_END"
        const val JOB_MILESTONE = "JOB_MILESTONE"
        const val JOB_WORKFLOW_TASK = "JOB_WORKFLOW_TASK"
        const val JOB_WORKFLOW_NOTE = "JOB_WORKFLOW_NOTE"
    }
}

data class AgendaSourceResult(
    val deadlines: List<AgendaSourceDeadline>,
    val warnings: List<String>
)

object AgendaSourceProjection {
    private const val HOME_REMINDER_PREFIX = "dashboard-reminder:"
    private const val GENERAL_REMINDER_SCOPE = "__ge360_home_general__"

    // Home reminder dates are stored as ISO dates in WorkflowTask.templateId.
    // These are date-only obligations: do not infer an appointment time.
    private fun reminderDate(token: String): Long? =
        if (token.startsWith(HOME_REMINDER_PREFIX)) day(token.removePrefix(HOME_REMINDER_PREFIX)) else null

    private fun day(date: String): Long? = runCatching {
        LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    fun read(context: Context, clientUuid: String = "", jobsiteUuid: String = ""): AgendaSourceResult {
        val rows = mutableListOf<AgendaSourceDeadline>()
        val errors = mutableListOf<String>()
        runCatching {
            val clients = ClientStore(context)
            try {
                val records = if (clientUuid.isNotBlank()) {
                    listOfNotNull(clients.getByGe360Id(clientUuid))
                } else clients.search()
                val names = records.associateBy { it.ge360Id }
                clients.datedFactualActions(clientUuid).forEach eventLoop@{ event ->
                    if (jobsiteUuid.isNotBlank() && event.jobsiteGe360Id != jobsiteUuid) return@eventLoop
                    val client = names[event.clientGe360Id] ?: return@eventLoop
                    val at = day(event.nextActionDate) ?: return@eventLoop
                    rows += AgendaSourceDeadline(
                        key = "client-fact:" + event.ge360Id,
                        sourceType = AgendaSourceDeadline.CLIENT_FACT,
                        title = event.kind + " · " + client.fullName(),
                        subtitle = "Cronologia cliente · " + event.description.take(110) + " · data senza ora",
                        dateAt = at,
                        clientGe360Id = client.ge360Id,
                        jobsiteGe360Id = event.jobsiteGe360Id.orEmpty()
                    )
                }
            } finally { clients.close() }
        }.onFailure { errors += "Cronologia clienti non disponibile" }

        runCatching {
            val jobs = JobsiteStore(context)
            try {
                val records = when {
                    jobsiteUuid.isNotBlank() -> listOfNotNull(jobs.getByGe360Id(jobsiteUuid))
                    else -> jobs.list().filter { clientUuid.isBlank() || it.clientGe360Id == clientUuid }
                }
                records.filter { it.status != JobsiteStore.STATUS_ARCHIVED }.forEach { job ->
                    if (job.status != JobsiteStore.STATUS_COMPLETED) day(job.targetEndDate)?.let { at ->
                        rows += AgendaSourceDeadline(
                            key = "job-end:" + job.ge360Id,
                            sourceType = AgendaSourceDeadline.JOB_END,
                            title = "Fine prevista · " + job.displayTitle(),
                            subtitle = "Scheda cantiere · data indicativa senza ora",
                            dateAt = at, clientGe360Id = job.clientGe360Id,
                            jobsiteGe360Id = job.ge360Id
                        )
                    }
                    jobs.milestones(job.ge360Id)
                        .filter { it.status != JobsiteMilestone.STATUS_CLOSED && it.status != JobsiteMilestone.STATUS_CANCELLED }
                        .forEach { milestone ->
                            day(milestone.dueDate)?.let { at ->
                                rows += AgendaSourceDeadline(
                                    key = "job-milestone:" + milestone.ge360Id,
                                    sourceType = AgendaSourceDeadline.JOB_MILESTONE,
                                    title = milestone.title + " · " + job.displayTitle(),
                                    subtitle = "SAL/acconto · " + milestone.status + " · data senza ora",
                                    dateAt = at,
                                    clientGe360Id = job.clientGe360Id,
                                    jobsiteGe360Id = job.ge360Id
                                )
                            }
                        }
                }
            } finally { jobs.close() }
        }.onFailure { errors += "Scadenze cantieri non disponibili" }
        runCatching {
            val jobs = JobsiteStore(context)
            val workflow = JobsiteWorkflowStore(context)
            try {
                val projectList = if (jobsiteUuid.isNotBlank()) {
                    listOfNotNull(jobs.getByGe360Id(jobsiteUuid))
                } else jobs.list().filter { clientUuid.isBlank() || it.clientGe360Id == clientUuid }
                projectList.filter { it.status != JobsiteStore.STATUS_ARCHIVED }.forEach { job ->
                    workflow.tasks(job.ge360Id).filter { task ->
                        task.ge360Id.isNotBlank() &&
                            task.status in setOf(WorkflowStatuses.TODO, WorkflowStatuses.IN_PROGRESS, WorkflowStatuses.BLOCKED) &&
                            (jobsiteUuid.isNotBlank() || task.source == WorkflowSources.MANUAL)
                    }.forEach { task ->
                        val dueDate = reminderDate(task.templateId)
                        rows += AgendaSourceDeadline(
                            key = "job-task:" + task.ge360Id,
                            sourceType = AgendaSourceDeadline.JOB_WORKFLOW_TASK,
                            title = task.title + " · " + job.displayTitle(),
                            subtitle = if (task.templateId.startsWith(HOME_REMINDER_PREFIX))
                                "Promemoria Home · " + task.status + " · " + (if (dueDate == null) "senza data" else "data senza ora")
                                else "Checklist cantiere · " + task.status + " · nessuna data impostata",
                            dateAt = dueDate ?: 0L,
                            clientGe360Id = job.clientGe360Id,
                            jobsiteGe360Id = job.ge360Id
                        )
                    }
                    // Legacy internal notes are only exposed while browsing their exact jobsite.
                    if (jobsiteUuid.isNotBlank()) workflow.notes(job.ge360Id).forEach { note ->
                        if (note.ge360Id.isNotBlank()) rows += AgendaSourceDeadline(
                            key = "job-note:" + note.ge360Id,
                            sourceType = AgendaSourceDeadline.JOB_WORKFLOW_NOTE,
                            title = if (note.pinned) "Nota importante" else "Nota di cantiere",
                            subtitle = note.text.take(160) + " · conservata nel Centro Cantiere",
                            dateAt = 0L,
                            clientGe360Id = job.clientGe360Id,
                            jobsiteGe360Id = job.ge360Id
                        )
                    }
                }
                workflow.tasks(GENERAL_REMINDER_SCOPE).filter { task ->
                    task.ge360Id.isNotBlank() && task.templateId.startsWith(HOME_REMINDER_PREFIX) &&
                        task.status in setOf(WorkflowStatuses.TODO, WorkflowStatuses.IN_PROGRESS, WorkflowStatuses.BLOCKED)
                }.forEach { task ->
                    val dueDate = reminderDate(task.templateId)
                    rows += AgendaSourceDeadline(
                        key = "job-task:" + task.ge360Id,
                        sourceType = AgendaSourceDeadline.JOB_WORKFLOW_TASK,
                        title = task.title,
                        subtitle = "Promemoria generale Home · " + (if (dueDate == null) "senza data" else "data senza ora"),
                        dateAt = dueDate ?: 0L
                    )
                }
            } finally {
                workflow.close()
                jobs.close()
            }
        }.onFailure { errors += "Checklist e promemoria Home non disponibili" }
        return AgendaSourceResult(
            rows.distinctBy { it.key }.sortedWith(
                compareBy<AgendaSourceDeadline> { if (it.dateAt > 0L) it.dateAt else Long.MAX_VALUE }
                    .thenBy { it.key }
            ),
            errors
        )
    }
}
