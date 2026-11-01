package it.edilmilan.ge360.agenda

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class AgendaOccurrence(val entry: AgendaEntry, val at: Long)

/** Calendar projection only: future repetitions never create duplicate records. */
object AgendaCalendar {
    fun midnight(epoch: Long): Long = Calendar.getInstance().apply {
        timeInMillis = epoch
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun nextDay(epoch: Long): Long = Calendar.getInstance().apply {
        timeInMillis = midnight(epoch)
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    fun bounds(mode: String, anchor: Long): Pair<Long, Long> {
        val c = Calendar.getInstance().apply { timeInMillis = midnight(anchor) }
        if (mode == "Mese") {
            c.set(Calendar.DAY_OF_MONTH, 1)
        } else {
            c.add(Calendar.DAY_OF_YEAR, -((c.get(Calendar.DAY_OF_WEEK) + 5) % 7))
        }
        val from = c.timeInMillis
        c.add(if (mode == "Mese") Calendar.MONTH else Calendar.WEEK_OF_YEAR, 1)
        return from to c.timeInMillis
    }

    fun moved(mode: String, anchor: Long, offset: Int): Long = Calendar.getInstance().apply {
        timeInMillis = anchor
        add(if (mode == "Mese") Calendar.MONTH else Calendar.WEEK_OF_YEAR, offset)
    }.timeInMillis

    fun occurrences(entry: AgendaEntry, from: Long, until: Long): List<AgendaOccurrence> {
        if (entry.kind == AgendaEntry.NOTE || entry.completed || entry.dueAt <= 0 || from >= until) return emptyList()
        var next = entry.dueAt
        if (next < from && entry.repeat != AgendaEntry.REPEAT_NONE) {
            next = AgendaRecurrence.nextAfter(next, from - 1, entry.repeat, entry.repeatAnchorDay)
        }
        val result = mutableListOf<AgendaOccurrence>()
        while (next >= from && next < until && result.size < 65) {
            result += AgendaOccurrence(entry, next)
            if (entry.repeat == AgendaEntry.REPEAT_NONE) break
            next = AgendaRecurrence.nextAfter(next, next, entry.repeat, entry.repeatAnchorDay)
        }
        return result
    }

    fun panel(
        context: Context,
        mode: String,
        anchor: Long,
        selected: Long?,
        items: List<AgendaEntry>,
        sourceDates: List<Long> = emptyList(),
        onMove: (Int) -> Unit,
        onSelect: (Long?) -> Unit
    ): View {
        val (from, until) = bounds(mode, anchor)
        val marked = (items.flatMap { occurrences(it, from, until) }.map { midnight(it.at) } +
            sourceDates.filter { it >= from && it < until }.map(::midnight)).toSet()
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val title = if (mode == "Mese") {
            SimpleDateFormat("MMMM yyyy", Locale.ITALY).format(Date(from))
        } else {
            SimpleDateFormat("dd MMM", Locale.ITALY).format(Date(from)) + " – " +
                SimpleDateFormat("dd MMM", Locale.ITALY).format(Date(until - 1))
        }
        val nav = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        nav.addView(MaterialButton(context).apply {
            text = "‹"
            setOnClickListener { onMove(-1) }
        }, LinearLayout.LayoutParams(dp(54), dp(48)))
        nav.addView(TextView(context).apply {
            text = title.replaceFirstChar { it.uppercase() }
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Ge360Brand.ForestDark)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        nav.addView(MaterialButton(context).apply {
            text = "›"
            setOnClickListener { onMove(1) }
        }, LinearLayout.LayoutParams(dp(54), dp(48)))
        container.addView(nav)
        val labels = LinearLayout(context)
        listOf("L", "M", "M", "G", "V", "S", "D").forEach { name ->
            labels.addView(TextView(context).apply {
                text = name
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(Ge360Brand.TextSecondary)
            }, LinearLayout.LayoutParams(0, dp(22), 1f))
        }
        container.addView(labels)
        val gridStart = Calendar.getInstance().apply {
            timeInMillis = from
            if (mode == "Mese") add(Calendar.DAY_OF_YEAR, -((get(Calendar.DAY_OF_WEEK) + 5) % 7))
        }
        for (week in 0 until (if (mode == "Mese") 6 else 1)) {
            val row = LinearLayout(context)
            for (dayIndex in 0..6) {
                val c = Calendar.getInstance().apply {
                    timeInMillis = gridStart.timeInMillis
                    add(Calendar.DAY_OF_YEAR, week * 7 + dayIndex)
                }
                val day = c.timeInMillis
                val active = day >= from && day < until
                row.addView(TextView(context).apply {
                    text = c.get(Calendar.DAY_OF_MONTH).toString() + if (day in marked) "\n•" else ""
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(if (selected == day) Ge360Brand.PrimaryDark else if (active) Ge360Brand.ForestDark else Ge360Brand.TextSecondary)
                    setTypeface(typeface, if (selected == day) Typeface.BOLD else Typeface.NORMAL)
                    alpha = if (active) 1f else 0.42f
                    isClickable = active
                    isFocusable = active
                    setOnClickListener { if (active) onSelect(if (selected == day) null else day) }
                }, LinearLayout.LayoutParams(0, dp(44), 1f))
            }
            container.addView(row)
        }
        if (selected != null) container.addView(MaterialButton(context).apply {
            text = "Mostra tutto il periodo"
            isAllCaps = false
            setOnClickListener { onSelect(null) }
        })
        return container
    }
}
