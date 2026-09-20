package it.edilmilan.ge360.scanner

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class FirRecord(
    var id: Long = 0,
    var ge360Id: String = "",
    var jobsiteId: String = "",
    var jobsiteCode: String = "",
    var jobsiteTitle: String = "",
    var mode: String = FirStore.MODE_DIGITAL,
    var firNumber: String = "",
    var rentriId: String = "",
    var issueDate: String = "",
    var eerCode: String = "",
    var wasteDescription: String = "",
    var quantity: Double? = null,
    var unit: String = "kg",
    var transporter: String = "",
    var vehiclePlate: String = "",
    var destinationPlant: String = "",
    var acceptedQuantity: Double? = null,
    var entryDate: String = "",
    var entryTime: String = "",
    var entryWeightKg: Double? = null,
    var exitDate: String = "",
    var exitTime: String = "",
    var exitWeightKg: Double? = null,
    var totalEntryWeightKg: Double? = null,
    var netWeightKg: Double? = null,
    var status: String = FirStore.STATUS_DRAFT,
    var notes: String = "",
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

data class FirSummary(
    val total: Int,
    val open: Int,
    val closed: Int
)

class FirStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                jobsite_id TEXT NOT NULL DEFAULT '',
                jobsite_code TEXT NOT NULL DEFAULT '',
                jobsite_title TEXT NOT NULL DEFAULT '',
                mode TEXT NOT NULL DEFAULT '$MODE_DIGITAL',
                fir_number TEXT NOT NULL DEFAULT '',
                rentri_id TEXT NOT NULL DEFAULT '',
                issue_date TEXT NOT NULL DEFAULT '',
                eer_code TEXT NOT NULL DEFAULT '',
                waste_description TEXT NOT NULL DEFAULT '',
                quantity REAL,
                unit TEXT NOT NULL DEFAULT 'kg',
                transporter TEXT NOT NULL DEFAULT '',
                vehicle_plate TEXT NOT NULL DEFAULT '',
                destination_plant TEXT NOT NULL DEFAULT '',
                accepted_quantity REAL,
                entry_date TEXT NOT NULL DEFAULT '',
                entry_time TEXT NOT NULL DEFAULT '',
                entry_weight_kg REAL,
                exit_date TEXT NOT NULL DEFAULT '',
                exit_time TEXT NOT NULL DEFAULT '',
                exit_weight_kg REAL,
                total_entry_weight_kg REAL,
                net_weight_kg REAL,
                status TEXT NOT NULL DEFAULT '$STATUS_DRAFT',
                notes TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN entry_date TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN entry_time TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN entry_weight_kg REAL")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN exit_date TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN exit_time TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN exit_weight_kg REAL")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN total_entry_weight_kg REAL")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN net_weight_kg REAL")
        }
        createIndexes(db)
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fir_jobsite ON $TABLE(jobsite_id, issue_date DESC, created_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fir_number ON $TABLE(fir_number)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fir_eer ON $TABLE(eer_code)")
    }

    fun list(jobsiteId: String = "", query: String = ""): List<FirRecord> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (jobsiteId.isNotBlank()) {
            clauses += "jobsite_id=?"
            args += jobsiteId
        }
        val q = query.trim()
        if (q.isNotBlank()) {
            val like = "%$q%"
            clauses += "(fir_number LIKE ? OR rentri_id LIKE ? OR eer_code LIKE ? OR waste_description LIKE ? OR transporter LIKE ? OR destination_plant LIKE ? OR jobsite_code LIKE ? OR jobsite_title LIKE ?)"
            repeat(8) { args += like }
        }
        val where = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val out = mutableListOf<FirRecord>()
        readableDatabase.query(TABLE, null, where, args.takeIf { it.isNotEmpty() }?.toTypedArray(), null, null, "issue_date DESC, created_at DESC, id DESC").use { c ->
            while (c.moveToNext()) out += fromCursor(c)
        }
        return out
    }

    fun forJobsite(jobsiteId: String): List<FirRecord> = list(jobsiteId = jobsiteId)

    fun get(id: Long): FirRecord? {
        readableDatabase.query(TABLE, null, "id=?", arrayOf(id.toString()), null, null, null, "1").use { c ->
            return if (c.moveToFirst()) fromCursor(c) else null
        }
    }

    fun findByNumber(jobsiteId: String, firNumber: String): FirRecord? {
        val wanted = normalizeFirNumber(firNumber)
        if (wanted.isBlank()) return null
        return list(jobsiteId = jobsiteId).firstOrNull { normalizeFirNumber(it.firNumber) == wanted }
    }

    /**
     * Un PDF/scansione FIR e la scheda strutturata rappresentano lo stesso formulario.
     * Se arriva una pagina aggiuntiva (per esempio il foglio con la pesa), aggiorniamo
     * il record gia' presente invece di creare un secondo FIR.
     */
    fun mergeFromScannerDocument(document: ScannerDocument, ocr: FirOcrResult): FirRecord? {
        if (!document.type.equals("FIR", ignoreCase = true)) return null
        if (document.jobsiteId.isBlank()) return null
        val number = (ocr.number ?: document.documentNumber).trim()
        if (number.isBlank()) return null

        val record = findByNumber(document.jobsiteId, number) ?: FirRecord(
            jobsiteId = document.jobsiteId,
            jobsiteCode = document.jobsiteCode,
            jobsiteTitle = document.jobsiteTitle,
            firNumber = number,
            issueDate = document.documentDate
        )
        record.jobsiteId = document.jobsiteId
        record.jobsiteCode = document.jobsiteCode
        record.jobsiteTitle = document.jobsiteTitle
        record.firNumber = number
        if (record.issueDate.isBlank() && document.documentDate.isNotBlank()) record.issueDate = document.documentDate

        ocr.entryDate?.let { record.entryDate = it }
        ocr.entryTime?.let { record.entryTime = it }
        ocr.entryWeightKg?.let { record.entryWeightKg = it }
        ocr.exitDate?.let { record.exitDate = it }
        ocr.exitTime?.let { record.exitTime = it }
        ocr.exitWeightKg?.let { record.exitWeightKg = it }
        ocr.totalEntryWeightKg?.let { record.totalEntryWeightKg = it }
        ocr.netWeightKg?.let { record.netWeightKg = it }
        save(record)
        return record
    }

    fun save(record: FirRecord): Long {
        val now = System.currentTimeMillis()
        if (record.id <= 0 && record.jobsiteId.isNotBlank() && record.firNumber.isNotBlank()) {
            findByNumber(record.jobsiteId, record.firNumber)?.let { existing ->
                record.id = existing.id
                if (record.ge360Id.isBlank()) record.ge360Id = existing.ge360Id
                if (record.createdAt <= 0) record.createdAt = existing.createdAt
            }
        }
        if (record.ge360Id.isBlank()) record.ge360Id = UUID.randomUUID().toString()
        if (record.createdAt <= 0) record.createdAt = now
        record.updatedAt = now
        val values = ContentValues().apply {
            put("ge360_id", record.ge360Id)
            put("jobsite_id", record.jobsiteId)
            put("jobsite_code", record.jobsiteCode)
            put("jobsite_title", record.jobsiteTitle)
            put("mode", record.mode)
            put("fir_number", record.firNumber)
            put("rentri_id", record.rentriId)
            put("issue_date", record.issueDate)
            put("eer_code", record.eerCode)
            put("waste_description", record.wasteDescription)
            if (record.quantity == null) putNull("quantity") else put("quantity", record.quantity)
            put("unit", record.unit)
            put("transporter", record.transporter)
            put("vehicle_plate", record.vehiclePlate)
            put("destination_plant", record.destinationPlant)
            if (record.acceptedQuantity == null) putNull("accepted_quantity") else put("accepted_quantity", record.acceptedQuantity)
            put("entry_date", record.entryDate)
            put("entry_time", record.entryTime)
            if (record.entryWeightKg == null) putNull("entry_weight_kg") else put("entry_weight_kg", record.entryWeightKg)
            put("exit_date", record.exitDate)
            put("exit_time", record.exitTime)
            if (record.exitWeightKg == null) putNull("exit_weight_kg") else put("exit_weight_kg", record.exitWeightKg)
            if (record.totalEntryWeightKg == null) putNull("total_entry_weight_kg") else put("total_entry_weight_kg", record.totalEntryWeightKg)
            if (record.netWeightKg == null) putNull("net_weight_kg") else put("net_weight_kg", record.netWeightKg)
            put("status", record.status)
            put("notes", record.notes)
            put("created_at", record.createdAt)
            put("updated_at", record.updatedAt)
        }
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

    fun summary(jobsiteId: String): FirSummary {
        val rows = forJobsite(jobsiteId)
        val closed = rows.count { it.status == STATUS_CLOSED }
        return FirSummary(rows.size, rows.size - closed, closed)
    }

    fun exportJson(jobsiteId: String = ""): String {
        val root = JSONObject()
        root.put("schema", "ge360.fir.v2")
        root.put("exported_at", System.currentTimeMillis())
        val arr = JSONArray()
        list(jobsiteId).forEach { f ->
            arr.put(JSONObject().apply {
                put("ge360_id", f.ge360Id)
                put("jobsite_id", f.jobsiteId)
                put("jobsite_code", f.jobsiteCode)
                put("jobsite_title", f.jobsiteTitle)
                put("mode", f.mode)
                put("fir_number", f.firNumber)
                put("rentri_id", f.rentriId)
                put("issue_date", f.issueDate)
                put("eer_code", f.eerCode)
                put("waste_description", f.wasteDescription)
                if (f.quantity != null) put("quantity", f.quantity)
                put("unit", f.unit)
                put("transporter", f.transporter)
                put("vehicle_plate", f.vehiclePlate)
                put("destination_plant", f.destinationPlant)
                if (f.acceptedQuantity != null) put("accepted_quantity", f.acceptedQuantity)
                put("entry_date", f.entryDate)
                put("entry_time", f.entryTime)
                if (f.entryWeightKg != null) put("entry_weight_kg", f.entryWeightKg)
                put("exit_date", f.exitDate)
                put("exit_time", f.exitTime)
                if (f.exitWeightKg != null) put("exit_weight_kg", f.exitWeightKg)
                if (f.totalEntryWeightKg != null) put("total_entry_weight_kg", f.totalEntryWeightKg)
                if (f.netWeightKg != null) put("net_weight_kg", f.netWeightKg)
                put("status", f.status)
                put("notes", f.notes)
                put("created_at", f.createdAt)
                put("updated_at", f.updatedAt)
            })
        }
        root.put("fir", arr)
        return root.toString(2)
    }

    fun importJson(json: String, replace: Boolean): Int {
        val root = JSONObject(json)
        require(root.optString("schema", "ge360.fir.v1").startsWith("ge360.fir")) { "Backup FIR non valido" }
        if (replace) writableDatabase.delete(TABLE, null, null)
        val existing = list().associateBy { it.ge360Id }.toMutableMap()
        val rows = root.optJSONArray("fir") ?: JSONArray()
        var count = 0
        for (i in 0 until rows.length()) {
            val o = rows.getJSONObject(i)
            val ge360Id = o.optString("ge360_id", "").ifBlank { UUID.randomUUID().toString() }
            val record = existing[ge360Id]?.copy() ?: FirRecord(ge360Id = ge360Id)
            record.jobsiteId = o.optString("jobsite_id", record.jobsiteId)
            record.jobsiteCode = o.optString("jobsite_code", record.jobsiteCode)
            record.jobsiteTitle = o.optString("jobsite_title", record.jobsiteTitle)
            record.mode = o.optString("mode", record.mode).ifBlank { MODE_DIGITAL }
            record.firNumber = o.optString("fir_number", record.firNumber)
            record.rentriId = o.optString("rentri_id", record.rentriId)
            record.issueDate = o.optString("issue_date", record.issueDate)
            record.eerCode = o.optString("eer_code", record.eerCode)
            record.wasteDescription = o.optString("waste_description", record.wasteDescription)
            record.quantity = o.optNullableDouble("quantity")
            record.unit = o.optString("unit", record.unit).ifBlank { "kg" }
            record.transporter = o.optString("transporter", record.transporter)
            record.vehiclePlate = o.optString("vehicle_plate", record.vehiclePlate)
            record.destinationPlant = o.optString("destination_plant", record.destinationPlant)
            record.acceptedQuantity = o.optNullableDouble("accepted_quantity")
            record.entryDate = o.optString("entry_date", record.entryDate)
            record.entryTime = o.optString("entry_time", record.entryTime)
            record.entryWeightKg = o.optNullableDouble("entry_weight_kg")
            record.exitDate = o.optString("exit_date", record.exitDate)
            record.exitTime = o.optString("exit_time", record.exitTime)
            record.exitWeightKg = o.optNullableDouble("exit_weight_kg")
            record.totalEntryWeightKg = o.optNullableDouble("total_entry_weight_kg")
            record.netWeightKg = o.optNullableDouble("net_weight_kg")
            record.status = o.optString("status", record.status).ifBlank { STATUS_DRAFT }
            record.notes = o.optString("notes", record.notes)
            record.createdAt = o.optLong("created_at", record.createdAt)
            record.updatedAt = o.optLong("updated_at", record.updatedAt)
            save(record)
            existing[ge360Id] = record
            count++
        }
        return count
    }

    private fun fromCursor(c: Cursor) = FirRecord(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
        jobsiteId = c.getString(c.getColumnIndexOrThrow("jobsite_id")),
        jobsiteCode = c.getString(c.getColumnIndexOrThrow("jobsite_code")),
        jobsiteTitle = c.getString(c.getColumnIndexOrThrow("jobsite_title")),
        mode = c.getString(c.getColumnIndexOrThrow("mode")),
        firNumber = c.getString(c.getColumnIndexOrThrow("fir_number")),
        rentriId = c.getString(c.getColumnIndexOrThrow("rentri_id")),
        issueDate = c.getString(c.getColumnIndexOrThrow("issue_date")),
        eerCode = c.getString(c.getColumnIndexOrThrow("eer_code")),
        wasteDescription = c.getString(c.getColumnIndexOrThrow("waste_description")),
        quantity = c.nullableDouble("quantity"),
        unit = c.getString(c.getColumnIndexOrThrow("unit")),
        transporter = c.getString(c.getColumnIndexOrThrow("transporter")),
        vehiclePlate = c.getString(c.getColumnIndexOrThrow("vehicle_plate")),
        destinationPlant = c.getString(c.getColumnIndexOrThrow("destination_plant")),
        acceptedQuantity = c.nullableDouble("accepted_quantity"),
        entryDate = c.getString(c.getColumnIndexOrThrow("entry_date")),
        entryTime = c.getString(c.getColumnIndexOrThrow("entry_time")),
        entryWeightKg = c.nullableDouble("entry_weight_kg"),
        exitDate = c.getString(c.getColumnIndexOrThrow("exit_date")),
        exitTime = c.getString(c.getColumnIndexOrThrow("exit_time")),
        exitWeightKg = c.nullableDouble("exit_weight_kg"),
        totalEntryWeightKg = c.nullableDouble("total_entry_weight_kg"),
        netWeightKg = c.nullableDouble("net_weight_kg"),
        status = c.getString(c.getColumnIndexOrThrow("status")),
        notes = c.getString(c.getColumnIndexOrThrow("notes")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun Cursor.nullableDouble(name: String): Double? {
        val index = getColumnIndexOrThrow(name)
        return if (isNull(index)) null else getDouble(index)
    }

    private fun JSONObject.optNullableDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name).takeUnless { it.isNaN() }
    }

    private fun normalizeFirNumber(value: String): String = value
        .uppercase(Locale.ITALY)
        .replace(Regex("[^A-Z0-9]"), "")

    companion object {
        const val MODE_DIGITAL = "Digitale (xFIR)"
        const val MODE_PAPER = "Cartaceo"
        val MODES = listOf(MODE_DIGITAL, MODE_PAPER)

        const val STATUS_DRAFT = "DA COMPILARE"
        const val STATUS_ISSUED = "EMESSO"
        const val STATUS_DELIVERED = "CONSEGNATO"
        const val STATUS_CLOSED = "CHIUSO / COPIA COMPLETA"
        val STATUSES = listOf(STATUS_DRAFT, STATUS_ISSUED, STATUS_DELIVERED, STATUS_CLOSED)

        val UNITS = listOf("kg", "t", "m³")

        private const val TABLE = "fir_records"
        private const val DB_NAME = "ge360_fir.db"
        private const val DB_VERSION = 2
    }
}
