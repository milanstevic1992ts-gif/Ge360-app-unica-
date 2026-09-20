package it.edilmilan.ge360.attrezzi

import android.content.Context
import it.edilmilan.ge360.core.common.BackupMerge
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/**
 * Pianificazioni della preparazione furgone condivise tra Attrezzi e Dashboard.
 *
 * Rimane volutamente locale/offline: i dati sono piccoli, vengono salvati subito e
 * possono essere letti dalla Home senza introdurre un database duplicato.
 */
data class VanPreparationPlan(
    val id: String = UUID.randomUUID().toString(),
    val jobsiteId: String,
    val jobsiteCode: String,
    val jobsiteTitle: String,
    val date: String,
    val note: String = "",
    val toolCount: Int = 0,
    val materialCount: Int = 0,
    val missingCount: Int = 0,
    val completed: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

class VanPreparationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<VanPreparationPlan> = decode(prefs.getString(KEY_PLANS, "[]").orEmpty())
        .sortedWith(compareBy<VanPreparationPlan> { it.date }.thenBy { it.jobsiteCode })

    fun listUpcoming(from: LocalDate = LocalDate.now(), limit: Int = 8): List<VanPreparationPlan> =
        list()
            .filter { !it.completed }
            .filter { runCatching { LocalDate.parse(it.date) }.getOrNull()?.let { day -> !day.isBefore(from) } == true }
            .take(limit)

    fun find(jobsiteId: String, date: String): VanPreparationPlan? =
        list().firstOrNull { it.jobsiteId == jobsiteId && it.date == date }

    fun save(plan: VanPreparationPlan): VanPreparationPlan {
        val rows = list().toMutableList()
        val sameId = rows.indexOfFirst { it.id == plan.id }
        val sameSlot = rows.indexOfFirst { it.jobsiteId == plan.jobsiteId && it.date == plan.date }
        val normalized = plan.copy(updatedAt = System.currentTimeMillis())
        when {
            sameId >= 0 -> rows[sameId] = normalized
            sameSlot >= 0 -> rows[sameSlot] = normalized.copy(id = rows[sameSlot].id)
            else -> rows += normalized
        }
        persist(rows)
        return find(plan.jobsiteId, plan.date) ?: normalized
    }

    fun setCompleted(id: String, completed: Boolean) {
        persist(list().map { if (it.id == id) it.copy(completed = completed, updatedAt = System.currentTimeMillis()) else it })
    }

    fun delete(id: String) {
        persist(list().filterNot { it.id == id })
    }

    fun exportJson(): String = JSONObject().apply {
        put("schema", BACKUP_SCHEMA)
        put("exportedAt", System.currentTimeMillis())
        put("plans", JSONArray().apply { list().forEach { plan ->
            put(JSONObject().apply {
                put("id", plan.id)
                put("jobsiteId", plan.jobsiteId)
                put("jobsiteCode", plan.jobsiteCode)
                put("jobsiteTitle", plan.jobsiteTitle)
                put("date", plan.date)
                put("note", plan.note)
                put("toolCount", plan.toolCount)
                put("materialCount", plan.materialCount)
                put("missingCount", plan.missingCount)
                put("completed", plan.completed)
                put("updatedAt", plan.updatedAt)
            })
        } })
    }.toString(2)

    fun importJson(json: String, replace: Boolean): Int {
        val root = JSONObject(json)
        require(root.optString("schema") == BACKUP_SCHEMA) { "Backup preparazioni furgone non compatibile" }
        val incoming = decode((root.optJSONArray("plans") ?: JSONArray()).toString())
        val merged = BackupMerge.byKey(list(), incoming, replace, VanPreparationPlan::id)
        persist(merged)
        return incoming.size
    }

    private fun persist(rows: List<VanPreparationPlan>) {
        val json = JSONArray()
        rows.sortedWith(compareBy<VanPreparationPlan> { it.date }.thenBy { it.jobsiteCode }).forEach { plan ->
            json.put(JSONObject().apply {
                put("id", plan.id)
                put("jobsiteId", plan.jobsiteId)
                put("jobsiteCode", plan.jobsiteCode)
                put("jobsiteTitle", plan.jobsiteTitle)
                put("date", plan.date)
                put("note", plan.note)
                put("toolCount", plan.toolCount)
                put("materialCount", plan.materialCount)
                put("missingCount", plan.missingCount)
                put("completed", plan.completed)
                put("updatedAt", plan.updatedAt)
            })
        }
        prefs.edit().putString(KEY_PLANS, json.toString()).apply()
    }

    private fun decode(raw: String): List<VanPreparationPlan> = runCatching {
        val array = JSONArray(raw.ifBlank { "[]" })
        val rows = mutableListOf<VanPreparationPlan>()
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val jobsiteId = row.optString("jobsiteId")
            val date = row.optString("date")
            if (jobsiteId.isBlank() || date.isBlank()) continue
            rows += VanPreparationPlan(
                id = row.optString("id").ifBlank { UUID.randomUUID().toString() },
                jobsiteId = jobsiteId,
                jobsiteCode = row.optString("jobsiteCode"),
                jobsiteTitle = row.optString("jobsiteTitle"),
                date = date,
                note = row.optString("note"),
                toolCount = row.optInt("toolCount", 0),
                materialCount = row.optInt("materialCount", 0),
                missingCount = row.optInt("missingCount", 0),
                completed = row.optBoolean("completed", false),
                updatedAt = row.optLong("updatedAt", 0L)
            )
        }
        rows
    }.getOrDefault(emptyList())

    companion object {
        const val BACKUP_SCHEMA = "ge360.attrezzi.van-preparations.v1"
        private const val PREFS = "ge360_van_preparations"
        private const val KEY_PLANS = "plans_json"
    }
}
