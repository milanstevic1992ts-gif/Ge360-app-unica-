package it.edilmilan.ge360.giornata

import android.content.ContentValues
import it.edilmilan.ge360.cantieri.JobsiteRecord
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

private const val LABOR_DAY_NOTE_PREFIX = "Giornata retroattiva"
private const val STANDARD_DAY_HOURS = 8.0
const val SLOBODAN_DEFAULT_DAILY_RATE = 92.0

fun WorkSession.isLaborDayEntry(): Boolean = note.trim().startsWith(LABOR_DAY_NOTE_PREFIX, ignoreCase = true)

fun WorkSession.dailyRateSnapshot(): Double = hourlyRate.coerceAtLeast(0.0) * STANDARD_DAY_HOURS

fun WorkSession.laborDayUserNote(): String = note.trim()
    .removePrefix(LABOR_DAY_NOTE_PREFIX)
    .removePrefix(" ·")
    .trim()

fun DailyStore.laborDayEntriesForJobsite(jobsiteId: String): List<WorkSession> =
    sessionsForJobsite(jobsiteId).filter { it.isLaborDayEntry() }.sortedByDescending { it.workDate }

fun DailyStore.laborDayEntry(personnel: PersonnelRecord, jobsiteId: String, workDate: LocalDate): WorkSession? =
    sessionsForPersonnel(personnel, workDate, workDate)
        .firstOrNull { it.jobsiteId == jobsiteId && it.isLaborDayEntry() }

fun DailyStore.hasTimedSessionForDay(personnel: PersonnelRecord, jobsiteId: String, workDate: LocalDate): Boolean =
    sessionsForPersonnel(personnel, workDate, workDate)
        .any { it.jobsiteId == jobsiteId && !it.isLaborDayEntry() }

fun DailyStore.defaultDailyRateFor(personnel: PersonnelRecord): Double {
    val latestHistorical = sessionsForPersonnel(personnel)
        .firstOrNull { it.isLaborDayEntry() && it.hourlyRate > 0.0 }
        ?.dailyRateSnapshot()
        ?: 0.0
    if (latestHistorical > 0.0) return latestHistorical
    if (personnel.name.trim().equals("Slobodan", ignoreCase = true)) return SLOBODAN_DEFAULT_DAILY_RATE
    return personnel.hourlyRate.coerceAtLeast(0.0) * STANDARD_DAY_HOURS
}

fun DailyStore.saveLaborDayEntry(
    jobsite: JobsiteRecord,
    personnel: PersonnelRecord,
    workDate: LocalDate,
    hours: Double,
    dailyRate: Double,
    userNote: String = ""
): WorkSession {
    require(jobsite.ge360Id.isNotBlank()) { "Cantiere senza UUID GE360" }
    require(personnel.ge360Id.isNotBlank()) { "Operatore senza UUID GE360" }
    require(workDate.isBefore(LocalDate.now())) { "Per oggi usa il timer; il calendario manuale serve per i giorni precedenti" }
    require(hours in 0.25..24.0) { "Ore non valide" }
    require(dailyRate > 0.0) { "Tariffa giornaliera non valida" }

    val existing = laborDayEntry(personnel, jobsite.ge360Id, workDate)
    if (existing == null && hasTimedSessionForDay(personnel, jobsite.ge360Id, workDate)) {
        throw IllegalStateException("Per questo operatore e giorno esistono già ore registrate con il timer")
    }

    val zone = ZoneId.systemDefault()
    val startedAt = workDate.atTime(LocalTime.of(8, 0)).atZone(zone).toInstant().toEpochMilli()
    val endedAt = startedAt + (hours * 3_600_000.0).toLong()
    val now = System.currentTimeMillis()
    val note = buildString {
        append(LABOR_DAY_NOTE_PREFIX)
        userNote.trim().takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
    }
    val record = WorkSession(
        id = existing?.id ?: 0,
        ge360Id = existing?.ge360Id ?: UUID.randomUUID().toString(),
        personnelId = personnel.ge360Id,
        jobsiteId = jobsite.ge360Id,
        jobsiteCode = jobsite.code,
        jobsiteTitle = jobsite.displayTitle(),
        workDate = workDate.toString(),
        collaborator = personnel.name,
        hourlyRate = dailyRate / STANDARD_DAY_HOURS,
        startedAt = startedAt,
        endedAt = endedAt,
        note = note,
        createdAt = existing?.createdAt ?: now,
        updatedAt = now
    )
    val values = ContentValues().apply {
        put("ge360_id", record.ge360Id)
        put("personnel_id", record.personnelId)
        put("jobsite_id", record.jobsiteId)
        put("jobsite_code", record.jobsiteCode)
        put("jobsite_title", record.jobsiteTitle)
        put("work_date", record.workDate)
        put("collaborator", record.collaborator)
        put("hourly_rate", record.hourlyRate)
        put("started_at", record.startedAt)
        put("ended_at", record.endedAt)
        put("note", record.note)
        put("created_at", record.createdAt)
        put("updated_at", record.updatedAt)
    }
    if (record.id > 0) {
        writableDatabase.update("work_sessions", values, "id=?", arrayOf(record.id.toString()))
    } else {
        record.id = writableDatabase.insertOrThrow("work_sessions", null, values)
    }
    return record
}

fun DailyStore.deleteLaborDayEntry(id: Long): Boolean {
    val existing = getSession(id) ?: return false
    if (!existing.isLaborDayEntry()) return false
    return writableDatabase.delete("work_sessions", "id=?", arrayOf(id.toString())) > 0
}

fun DailyStore.laborDayHoursForJobsite(jobsiteId: String): Double =
    laborDayEntriesForJobsite(jobsiteId).sumOf { it.durationMinutes() }.toDouble() / 60.0

fun DailyStore.laborDayCostForJobsite(jobsiteId: String): Double =
    laborDayEntriesForJobsite(jobsiteId).sumOf { it.laborCost() }

fun DailyStore.laborDayWorkedPersonDaysForJobsite(jobsiteId: String): Int =
    laborDayEntriesForJobsite(jobsiteId)
        .map { "${it.personnelId.ifBlank { it.collaborator }}|${it.workDate}" }
        .distinct()
        .size
