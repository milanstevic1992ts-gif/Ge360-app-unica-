package it.edilmilan.ge360.spese

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

enum class FinanceType { INCOME, EXPENSE }
enum class FinanceScope { PRIVATE, BUSINESS }
enum class FireflySyncStatus { PENDING, SYNCED, ERROR }

data class FinanceRecord(
    var id: Long = 0,
    var ge360Id: String = "",
    var type: FinanceType = FinanceType.EXPENSE,
    var amount: Double = 0.0,
    var category: String = "Altro",
    var note: String = "",
    var date: String = "",
    var scope: FinanceScope = FinanceScope.BUSINESS,
    var jobsiteId: String = "",
    var jobsiteCode: String = "",
    var jobsiteTitle: String = "",
    var fireflyId: String = "",
    var fireflyStatus: FireflySyncStatus = FireflySyncStatus.PENDING,
    var fireflyLastError: String = "",
    var fireflySyncedAt: Long = 0,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

data class JobsiteFinanceSummary(
    val income: Double,
    val expense: Double,
    val balance: Double,
    val movements: Int
)

class FinanceStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                type TEXT NOT NULL,
                amount REAL NOT NULL,
                category TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                date TEXT NOT NULL,
                scope TEXT NOT NULL,
                jobsite_id TEXT NOT NULL DEFAULT '',
                jobsite_code TEXT NOT NULL DEFAULT '',
                jobsite_title TEXT NOT NULL DEFAULT '',
                firefly_id TEXT NOT NULL DEFAULT '',
                firefly_status TEXT NOT NULL DEFAULT 'PENDING',
                firefly_last_error TEXT NOT NULL DEFAULT '',
                firefly_synced_at INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_finance_date ON $TABLE(date DESC, id DESC)")
        db.execSQL("CREATE INDEX idx_finance_scope ON $TABLE(scope, date DESC)")
        db.execSQL("CREATE INDEX idx_finance_jobsite ON $TABLE(jobsite_id, date DESC)")
        db.execSQL("CREATE INDEX idx_finance_category ON $TABLE(category)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN firefly_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN firefly_status TEXT NOT NULL DEFAULT 'PENDING'")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN firefly_last_error TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN firefly_synced_at INTEGER NOT NULL DEFAULT 0")
        }
    }

    fun all(): List<FinanceRecord> = queryInternal(null, null)

    fun search(query: String = "", filter: String = "Tutti"): List<FinanceRecord> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        val q = query.trim()
        if (q.isNotEmpty()) {
            val like = "%$q%"
            clauses += "(category LIKE ? OR note LIKE ? OR jobsite_code LIKE ? OR jobsite_title LIKE ? OR date LIKE ?)"
            repeat(5) { args += like }
        }
        when (filter) {
            "Azienda" -> { clauses += "scope=?"; args += FinanceScope.BUSINESS.name }
            "Privato" -> { clauses += "scope=?"; args += FinanceScope.PRIVATE.name }
            "Entrate" -> { clauses += "type=?"; args += FinanceType.INCOME.name }
            "Uscite" -> { clauses += "type=?"; args += FinanceType.EXPENSE.name }
            "Uscite azienda" -> {
                clauses += "type=?"; args += FinanceType.EXPENSE.name
                clauses += "scope=?"; args += FinanceScope.BUSINESS.name
            }
            "Uscite private" -> {
                clauses += "type=?"; args += FinanceType.EXPENSE.name
                clauses += "scope=?"; args += FinanceScope.PRIVATE.name
            }
            "Entrate azienda" -> {
                clauses += "type=?"; args += FinanceType.INCOME.name
                clauses += "scope=?"; args += FinanceScope.BUSINESS.name
            }
            "Entrate private" -> {
                clauses += "type=?"; args += FinanceType.INCOME.name
                clauses += "scope=?"; args += FinanceScope.PRIVATE.name
            }
            "Con cantiere" -> clauses += "jobsite_id<>''"
        }
        return queryInternal(
            clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            args.takeIf { it.isNotEmpty() }?.toTypedArray()
        )
    }

    fun get(id: Long): FinanceRecord? {
        readableDatabase.query(TABLE, null, "id=?", arrayOf(id.toString()), null, null, null, "1").use { c ->
            return if (c.moveToFirst()) fromCursor(c) else null
        }
    }

    fun getByGe360Id(ge360Id: String): FinanceRecord? {
        if (ge360Id.isBlank()) return null
        readableDatabase.query(TABLE, null, "ge360_id=?", arrayOf(ge360Id), null, null, null, "1").use { c ->
            return if (c.moveToFirst()) fromCursor(c) else null
        }
    }

    fun save(record: FinanceRecord): Long {
        val now = System.currentTimeMillis()
        if (record.ge360Id.isBlank()) record.ge360Id = UUID.randomUUID().toString()
        if (record.createdAt <= 0) record.createdAt = now
        record.updatedAt = now
        if (record.scope == FinanceScope.PRIVATE) {
            record.jobsiteId = ""
            record.jobsiteCode = ""
            record.jobsiteTitle = ""
        }
        record.fireflyStatus = FireflySyncStatus.PENDING
        record.fireflyLastError = ""
        val values = toValues(record)
        return if (record.id > 0) {
            writableDatabase.update(TABLE, values, "id=?", arrayOf(record.id.toString()))
            record.id
        } else {
            writableDatabase.insertOrThrow(TABLE, null, values).also { record.id = it }
        }
    }

    fun delete(id: Long) {
        writableDatabase.delete(TABLE, "id=?", arrayOf(id.toString()))
    }

    fun markFireflySynced(ge360Id: String, fireflyId: String) {
        if (ge360Id.isBlank() || fireflyId.isBlank()) return
        val values = ContentValues().apply {
            put("firefly_id", fireflyId.trim())
            put("firefly_status", FireflySyncStatus.SYNCED.name)
            put("firefly_last_error", "")
            put("firefly_synced_at", System.currentTimeMillis())
        }
        writableDatabase.update(TABLE, values, "ge360_id=?", arrayOf(ge360Id.trim()))
    }

    fun markFireflyError(ge360Id: String, message: String) {
        if (ge360Id.isBlank()) return
        val values = ContentValues().apply {
            put("firefly_status", FireflySyncStatus.ERROR.name)
            put("firefly_last_error", message.trim().take(500))
        }
        writableDatabase.update(TABLE, values, "ge360_id=?", arrayOf(ge360Id.trim()))
    }

    fun forJobsite(jobsiteId: String): List<FinanceRecord> {
        if (jobsiteId.isBlank()) return emptyList()
        return queryInternal("jobsite_id=?", arrayOf(jobsiteId.trim()))
    }

    fun jobsiteSummary(jobsiteId: String): JobsiteFinanceSummary {
        val rows = forJobsite(jobsiteId)
        val income = rows.filter { it.type == FinanceType.INCOME }.sumOf { it.amount }
        val expense = rows.filter { it.type == FinanceType.EXPENSE }.sumOf { it.amount }
        return JobsiteFinanceSummary(income, expense, income - expense, rows.size)
    }

    fun exportJson(): String {
        val root = JSONObject()
        root.put("schema", "ge360.spese.v3")
        root.put("exported_at", System.currentTimeMillis())
        val rows = JSONArray()
        all().forEach { rows.put(toJson(it)) }
        root.put("transactions", rows)
        return root.toString(2)
    }

    fun exportCsv(): String = buildString {
        appendLine("id;ge360_id;tipo;importo;categoria;nota;data;ambito;jobsite_id;codice_cantiere;cantiere;firefly_id;firefly_status")
        all().forEach { r ->
            appendLine(
                listOf(
                    r.id.toString(), q(r.ge360Id), r.type.name, r.amount.toString(), q(r.category), q(r.note),
                    r.date, r.scope.name, q(r.jobsiteId), q(r.jobsiteCode), q(r.jobsiteTitle),
                    q(r.fireflyId), r.fireflyStatus.name
                ).joinToString(";")
            )
        }
    }

    fun importJson(json: String, replaceAll: Boolean): Int {
        val root = JSONObject(json)
        val schema = root.optString("schema", "")
        val rows = root.getJSONArray("transactions")
        val db = writableDatabase
        var imported = 0
        db.beginTransaction()
        try {
            if (replaceAll) db.delete(TABLE, null, null)
            for (i in 0 until rows.length()) {
                val source = rows.getJSONObject(i)
                val legacy = schema == "spese360.v1" || !source.has("ge360_id") || source.optString("ge360_id").isBlank()
                val incoming = if (legacy) legacyFromJson(source) else fromJson(source)
                val existing = if (legacy) findLegacyDuplicate(incoming) else getByGe360Id(incoming.ge360Id)
                if (existing != null) {
                    incoming.id = existing.id
                    incoming.ge360Id = existing.ge360Id
                    incoming.createdAt = existing.createdAt
                    if (incoming.jobsiteId.isBlank() && existing.jobsiteId.isNotBlank()) {
                        incoming.jobsiteId = existing.jobsiteId
                        incoming.jobsiteCode = existing.jobsiteCode
                        incoming.jobsiteTitle = existing.jobsiteTitle
                    }
                }
                save(incoming)
                imported++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return imported
    }

    private fun findLegacyDuplicate(incoming: FinanceRecord): FinanceRecord? = all().firstOrNull {
        it.type == incoming.type &&
            kotlin.math.abs(it.amount - incoming.amount) < 0.001 &&
            it.category.equals(incoming.category, true) &&
            it.note.equals(incoming.note, true) &&
            it.date == incoming.date &&
            it.scope == incoming.scope
    }

    private fun queryInternal(where: String?, args: Array<String>?): List<FinanceRecord> {
        val out = mutableListOf<FinanceRecord>()
        readableDatabase.query(TABLE, null, where, args, null, null, "date DESC, id DESC").use { c ->
            while (c.moveToNext()) out += fromCursor(c)
        }
        return out
    }

    private fun toValues(r: FinanceRecord) = ContentValues().apply {
        put("ge360_id", r.ge360Id.trim())
        put("type", r.type.name)
        put("amount", r.amount)
        put("category", r.category.trim().ifEmpty { "Altro" })
        put("note", r.note.trim())
        put("date", r.date.trim())
        put("scope", r.scope.name)
        put("jobsite_id", r.jobsiteId.trim())
        put("jobsite_code", r.jobsiteCode.trim())
        put("jobsite_title", r.jobsiteTitle.trim())
        put("firefly_id", r.fireflyId.trim())
        put("firefly_status", r.fireflyStatus.name)
        put("firefly_last_error", r.fireflyLastError.trim())
        put("firefly_synced_at", r.fireflySyncedAt)
        put("created_at", r.createdAt)
        put("updated_at", r.updatedAt)
    }

    private fun fromCursor(c: Cursor) = FinanceRecord(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
        type = FinanceType.valueOf(c.getString(c.getColumnIndexOrThrow("type"))),
        amount = c.getDouble(c.getColumnIndexOrThrow("amount")),
        category = c.getString(c.getColumnIndexOrThrow("category")),
        note = c.getString(c.getColumnIndexOrThrow("note")),
        date = c.getString(c.getColumnIndexOrThrow("date")),
        scope = FinanceScope.valueOf(c.getString(c.getColumnIndexOrThrow("scope"))),
        jobsiteId = c.getString(c.getColumnIndexOrThrow("jobsite_id")),
        jobsiteCode = c.getString(c.getColumnIndexOrThrow("jobsite_code")),
        jobsiteTitle = c.getString(c.getColumnIndexOrThrow("jobsite_title")),
        fireflyId = c.getString(c.getColumnIndexOrThrow("firefly_id")),
        fireflyStatus = parseFireflyStatus(c.getString(c.getColumnIndexOrThrow("firefly_status"))),
        fireflyLastError = c.getString(c.getColumnIndexOrThrow("firefly_last_error")),
        fireflySyncedAt = c.getLong(c.getColumnIndexOrThrow("firefly_synced_at")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun toJson(r: FinanceRecord) = JSONObject().apply {
        put("id", r.id)
        put("ge360_id", r.ge360Id)
        put("type", r.type.name.lowercase(Locale.ROOT))
        put("amount", r.amount)
        put("category", r.category)
        put("note", r.note)
        put("date", r.date)
        put("scope", r.scope.name.lowercase(Locale.ROOT))
        put("jobsite_id", r.jobsiteId)
        put("jobsite_code", r.jobsiteCode)
        put("jobsite_title", r.jobsiteTitle)
        put("firefly_id", r.fireflyId)
        put("firefly_status", r.fireflyStatus.name.lowercase(Locale.ROOT))
        put("firefly_last_error", r.fireflyLastError)
        put("firefly_synced_at", r.fireflySyncedAt)
        put("created_at", r.createdAt)
        put("updated_at", r.updatedAt)
    }

    private fun fromJson(o: JSONObject) = FinanceRecord(
        ge360Id = o.optString("ge360_id", UUID.randomUUID().toString()),
        type = parseType(o.optString("type", "expense")),
        amount = o.optDouble("amount", 0.0),
        category = o.optString("category", "Altro"),
        note = o.optString("note", ""),
        date = o.optString("date", ""),
        scope = parseScope(o.optString("scope", "business")),
        jobsiteId = o.optString("jobsite_id", ""),
        jobsiteCode = o.optString("jobsite_code", ""),
        jobsiteTitle = o.optString("jobsite_title", ""),
        fireflyId = o.optString("firefly_id", ""),
        fireflyStatus = parseFireflyStatus(o.optString("firefly_status", "pending")),
        fireflyLastError = o.optString("firefly_last_error", ""),
        fireflySyncedAt = o.optLong("firefly_synced_at", 0),
        createdAt = o.optLong("created_at", System.currentTimeMillis()),
        updatedAt = o.optLong("updated_at", System.currentTimeMillis())
    )

    private fun legacyFromJson(o: JSONObject) = FinanceRecord(
        ge360Id = UUID.randomUUID().toString(),
        type = parseType(o.optString("type", "expense")),
        amount = o.optDouble("amount", 0.0),
        category = o.optString("category", "Altro"),
        note = o.optString("note", ""),
        date = o.optString("date", ""),
        scope = parseScope(o.optString("scope", "business"))
    )

    private fun parseType(value: String) = if (value.equals("income", true)) FinanceType.INCOME else FinanceType.EXPENSE
    private fun parseScope(value: String) = if (value.equals("private", true)) FinanceScope.PRIVATE else FinanceScope.BUSINESS
    private fun parseFireflyStatus(value: String) = runCatching {
        FireflySyncStatus.valueOf(value.trim().uppercase(Locale.ROOT))
    }.getOrDefault(FireflySyncStatus.PENDING)
    private fun q(value: String) = "\"${value.replace("\"", "\"\"")}\""

    companion object {
        const val FILTER_ALL = "Tutti"
        val FILTERS = listOf(
            "Tutti",
            "Azienda",
            "Privato",
            "Entrate",
            "Uscite",
            "Uscite azienda",
            "Uscite private",
            "Entrate azienda",
            "Entrate private",
            "Con cantiere"
        )

        val BUSINESS_EXPENSE_CATEGORIES = listOf(
            "Materiali", "Carburante", "Attrezzi", "Collaboratori", "Subappalti",
            "Smaltimenti", "Trasporti", "Noleggi", "Assicurazioni", "Commercialista",
            "Telefono / Internet", "Altro"
        )
        val PRIVATE_EXPENSE_CATEGORIES = listOf(
            "Casa", "Spesa", "Auto", "Bollette", "Salute", "Famiglia", "Svago", "Viaggi", "Altro"
        )
        val BUSINESS_INCOME_CATEGORIES = listOf(
            "Cliente", "Acconto cliente", "Saldo cliente", "Rimborso", "Vendita", "Altro"
        )
        val PRIVATE_INCOME_CATEGORIES = listOf(
            "Stipendio", "Rimborso", "Vendita", "Regalo", "Altro"
        )

        // Compatibilità per eventuali chiamanti esterni al modulo.
        val EXPENSE_CATEGORIES = (BUSINESS_EXPENSE_CATEGORIES + PRIVATE_EXPENSE_CATEGORIES).distinct()
        val INCOME_CATEGORIES = (BUSINESS_INCOME_CATEGORIES + PRIVATE_INCOME_CATEGORIES).distinct()

        private const val TABLE = "finance_transactions"
        private const val DB_NAME = "ge360_spese.db"
        private const val DB_VERSION = 2
    }
}