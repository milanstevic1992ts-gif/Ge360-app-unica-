package it.edilmilan.ge360.agenda

import android.content.Context
import it.edilmilan.ge360.cantieri.JobsiteMilestone
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.clienti.ClientStore
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
    }
}

data class AgendaSourceResult(
    val deadlines: List<AgendaSourceDeadline>,
    val warnings: List<String>
)

object AgendaSourceProjection {
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
        return AgendaSourceResult(
            rows.distinctBy { it.key }.sortedWith(compareBy<AgendaSourceDeadline> { it.dateAt }.thenBy { it.key }),
            errors
        )
    }
}
