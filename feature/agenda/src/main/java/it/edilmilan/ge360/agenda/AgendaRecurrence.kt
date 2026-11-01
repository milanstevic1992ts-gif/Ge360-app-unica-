package it.edilmilan.ge360.agenda

import java.util.Calendar

/** Recurrence uses local civil dates. Monthly repeats retain the original day. */
object AgendaRecurrence {
    fun nextAfter(dueAt: Long, after: Long, rule: String, anchorDay: Int = 0): Long {
        require(dueAt > 0 && rule != AgendaEntry.REPEAT_NONE && rule in AgendaEntry.REPEAT_OPTIONS)
        val clock = Calendar.getInstance().apply { timeInMillis = dueAt }
        val day = if (anchorDay in 1..31) anchorDay else clock.get(Calendar.DAY_OF_MONTH)
        kotlin.repeat(20_000) {
            val before = clock.timeInMillis
            when (rule) {
                AgendaEntry.REPEAT_DAILY -> clock.add(Calendar.DAY_OF_YEAR, 1)
                AgendaEntry.REPEAT_WEEKLY -> clock.add(Calendar.WEEK_OF_YEAR, 1)
                AgendaEntry.REPEAT_MONTHLY -> {
                    clock.set(Calendar.DAY_OF_MONTH, 1)
                    clock.add(Calendar.MONTH, 1)
                    clock.set(Calendar.DAY_OF_MONTH, minOf(day, clock.getActualMaximum(Calendar.DAY_OF_MONTH)))
                }
            }
            val next = clock.timeInMillis
            check(next > before) { "Ricorrenza non progressiva" }
            if (next > after) return next
        }
        error("Ricorrenza troppo lontana nel tempo")
    }
}
