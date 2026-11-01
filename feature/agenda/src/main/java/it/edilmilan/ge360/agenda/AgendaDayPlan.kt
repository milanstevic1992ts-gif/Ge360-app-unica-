package it.edilmilan.ge360.agenda

import java.util.Calendar

/** A non-owning dashboard projection: rows link back to their original records. */
data class AgendaDayItem(
    val key: String,
    val title: String,
    val description: String,
    val at: Long,
    val dateOnly: Boolean,
    val urgent: Boolean,
    val entry: AgendaEntry? = null,
    val source: AgendaSourceDeadline? = null
)

data class AgendaDaySnapshot(
    val overdue: List<AgendaDayItem>,
    val today: List<AgendaDayItem>,
    val upcoming: List<AgendaDayItem>,
    val later: List<AgendaDayItem>,
    val unplanned: List<AgendaDayItem>
) {
    val dueNow get() = overdue.size + today.size
    val total get() = dueNow + upcoming.size + later.size + unplanned.size
}

object AgendaDayPlan {
    fun build(
        notes: List<AgendaEntry>,
        external: List<AgendaSourceDeadline>,
        now: Long = System.currentTimeMillis()
    ): AgendaDaySnapshot {
        val start = AgendaCalendar.midnight(now)
        val nextDay = AgendaCalendar.nextDay(now)
        val endOfWeek = Calendar.getInstance().apply {
            timeInMillis = start
            add(Calendar.DAY_OF_YEAR, 7)
        }.timeInMillis

        val agendaItems = notes.asSequence()
            .filter { it.kind != AgendaEntry.NOTE && !it.completed }
            .map { entry ->
                AgendaDayItem(
                    key = "agenda:" + entry.id,
                    title = entry.title,
                    description = if (entry.kind == AgendaEntry.EVENT) "Appuntamento GE360" else "Attività GE360",
                    at = entry.dueAt,
                    dateOnly = false,
                    urgent = entry.priority == AgendaEntry.HIGH,
                    entry = entry
                )
            }.toList()
        val sourceItems = external.asSequence()
            .filter { it.sourceType != AgendaSourceDeadline.JOB_WORKFLOW_NOTE }
            .map { origin ->
                AgendaDayItem(
                    key = origin.key,
                    title = origin.title,
                    description = origin.subtitle,
                    at = origin.dateAt,
                    dateOnly = true,
                    urgent = origin.subtitle.contains("Bloccato", ignoreCase = true),
                    source = origin
                )
            }.toList()

        // Merge only by canonical source key. Never guess identity using titles.
        val all = (agendaItems + sourceItems).distinctBy { it.key }
        val rank = compareByDescending<AgendaDayItem> { it.urgent }
            .thenBy { if (it.at > 0L) it.at else Long.MAX_VALUE }
            .thenBy { it.key }
        val overdue = mutableListOf<AgendaDayItem>()
        val today = mutableListOf<AgendaDayItem>()
        val upcoming = mutableListOf<AgendaDayItem>()
        val later = mutableListOf<AgendaDayItem>()
        val unplanned = mutableListOf<AgendaDayItem>()
        all.forEach { item ->
            when {
                item.at <= 0L -> unplanned += item
                item.dateOnly && item.at < start -> overdue += item
                !item.dateOnly && item.at < now -> overdue += item
                item.at < nextDay -> today += item
                item.at < endOfWeek -> upcoming += item
                else -> later += item
            }
        }
        return AgendaDaySnapshot(
            overdue.sortedWith(rank), today.sortedWith(rank),
            upcoming.sortedWith(rank), later.sortedWith(rank), unplanned.sortedWith(rank)
        )
    }
}
