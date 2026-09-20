package it.edilmilan.ge360.scanner

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ScannerDocument(
    var id: Long = 0,
    var ge360Id: String = "",
    var type: String = "ALTRO",
    var documentDate: String = "",
    var documentNumber: String = "",
    var supplier: String = "",
    var category: String = "",
    var taxableAmount: Double? = null,
    var vatAmount: Double? = null,
    var totalAmount: Double? = null,
    var confidence: Double = 0.0,
    var ocrText: String = "",
    var pdfPath: String = "",
    var jobsiteId: String = "",
    var jobsiteCode: String = "",
    var jobsiteTitle: String = "",
    var expenseGe360Id: String = "",
    var status: String = STATUS_REVIEWED,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var toolName: String = "",
    var toolType: String = ""
) {
    companion object {
        const val STATUS_REVIEWED = "REVIEWED"
        const val STATUS_EXPENSE_CREATED = "EXPENSE_CREATED"
    }
}

class DocumentStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                type TEXT NOT NULL DEFAULT 'ALTRO',
                document_date TEXT NOT NULL DEFAULT '',
                document_number TEXT NOT NULL DEFAULT '',
                supplier TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL DEFAULT '',
                taxable_amount REAL,
                vat_amount REAL,
                total_amount REAL,
                confidence REAL NOT NULL DEFAULT 0,
                ocr_text TEXT NOT NULL DEFAULT '',
                pdf_path TEXT NOT NULL DEFAULT '',
                jobsite_id TEXT NOT NULL DEFAULT '',
                jobsite_code TEXT NOT NULL DEFAULT '',
                jobsite_title TEXT NOT NULL DEFAULT '',
                expense_ge360_id TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'REVIEWED',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                tool_name TEXT NOT NULL DEFAULT '',
                tool_type TEXT NOT NULL DEFAULT ''
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_scanner_date ON $TABLE(document_date DESC, created_at DESC)")
        db.execSQL("CREATE INDEX idx_scanner_jobsite ON $TABLE(jobsite_id, created_at DESC)")
        db.execSQL("CREATE INDEX idx_scanner_supplier ON $TABLE(supplier)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN tool_name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN tool_type TEXT NOT NULL DEFAULT ''")
        }
    }

    /** Archivio generale: ogni documento esiste una sola volta e può anche essere associato a un cantiere. */
    fun list(query: String = ""): List<ScannerDocument> = queryRows(query = query)

    /** Vista cantiere dello stesso archivio generale: nessuna copia fisica o record duplicato. */
    fun forJobsite(jobsiteId: String, query: String = ""): List<ScannerDocument> {
        if (jobsiteId.isBlank()) return emptyList()
        return queryRows(jobsiteId = jobsiteId, query = query)
    }

    fun get(id: Long): ScannerDocument? {
        readableDatabase.query(TABLE, null, "id=?", arrayOf(id.toString()), null, null, null, "1").use { c ->
            return if (c.moveToFirst()) fromCursor(c) else null
        }
    }

    fun getByGe360Id(ge360Id: String): ScannerDocument? {
        if (ge360Id.isBlank()) return null
        readableDatabase.query(TABLE, null, "ge360_id=?", arrayOf(ge360Id), null, null, null, "1").use { c ->
            return if (c.moveToFirst()) fromCursor(c) else null
        }
    }

    /**
     * Salvataggio canonico: ge360Id identifica il documento in modo stabile.
     * Se lo stesso documento arriva da un'altra vista (cantiere/archivio globale/restore), aggiorniamo il record esistente.
     * Per i FIR, il PDF multipagina viene anche riconciliato con FirStore tramite numero formulario + cantiere.
     */
    fun save(record: ScannerDocument): Long {
        val now = System.currentTimeMillis()
        if (record.ge360Id.isBlank()) record.ge360Id = UUID.randomUUID().toString()
        val existing = if (record.id <= 0) getByGe360Id(record.ge360Id) else null
        if (existing != null) {
            record.id = existing.id
            if (record.createdAt <= 0) record.createdAt = existing.createdAt
        }
        if (record.createdAt <= 0) record.createdAt = now
        record.updatedAt = now
        val values = toValues(record)
        val savedId = if (record.id > 0) {
            writableDatabase.update(TABLE, values, "id=?", arrayOf(record.id.toString()))
            record.id
        } else {
            writableDatabase.insertOrThrow(TABLE, null, values).also { record.id = it }
        }

        syncStructuredFir(record)
        return savedId
    }

    private fun syncStructuredFir(record: ScannerDocument) {
        if (!record.type.equals("FIR", ignoreCase = true)) return
        if (record.jobsiteId.isBlank() || record.ocrText.isBlank()) return
        val ocr = FirOcrClassifier.detect(record.ocrText) ?: return
        runCatching {
            FirStore(appContext).use { firStore ->
                firStore.mergeFromScannerDocument(record, ocr)
            }
        }
    }

    fun delete(id: Long) {
        writableDatabase.delete(TABLE, "id=?", arrayOf(id.toString()))
    }

    fun markExpenseCreated(id: Long, expenseGe360Id: String) {
        writableDatabase.update(TABLE, ContentValues().apply {
            put("expense_ge360_id", expenseGe360Id)
            put("status", ScannerDocument.STATUS_EXPENSE_CREATED)
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString()))
    }

    fun exportJson(): String {
        val root = JSONObject()
        root.put("schema", "ge360.scanner.v1")
        root.put("exported_at", System.currentTimeMillis())
        val arr = JSONArray()
        list().forEach { d ->
            arr.put(JSONObject().apply {
                put("ge360_id", d.ge360Id)
                put("type", d.type)
                put("document_date", d.documentDate)
                put("document_number", d.documentNumber)
                put("supplier", d.supplier)
                put("category", d.category)
                if (d.taxableAmount != null) put("taxable_amount", d.taxableAmount)
                if (d.vatAmount != null) put("vat_amount", d.vatAmount)
                if (d.totalAmount != null) put("total_amount", d.totalAmount)
                put("confidence", d.confidence)
                put("ocr_text", d.ocrText)
                put("pdf_path", d.pdfPath)
                put("jobsite_id", d.jobsiteId)
                put("jobsite_code", d.jobsiteCode)
                put("jobsite_title", d.jobsiteTitle)
                put("expense_ge360_id", d.expenseGe360Id)
                put("status", d.status)
                put("created_at", d.createdAt)
                put("updated_at", d.updatedAt)
                put("tool_name", d.toolName)
                put("tool_type", d.toolType)
            })
        }
        root.put("documents", arr)
        return root.toString(2)
    }

    private fun queryRows(jobsiteId: String = "", query: String = ""): List<ScannerDocument> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (jobsiteId.isNotBlank()) {
            clauses += "jobsite_id=?"
            args += jobsiteId
        }
        val q = query.trim()
        if (q.isNotBlank()) {
            val like = "%$q%"
            clauses += "(supplier LIKE ? OR document_number LIKE ? OR document_date LIKE ? OR type LIKE ? OR category LIKE ? OR jobsite_code LIKE ? OR jobsite_title LIKE ? OR ocr_text LIKE ? OR tool_name LIKE ? OR tool_type LIKE ?)"
            repeat(10) { args += like }
        }
        val where = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val out = mutableListOf<ScannerDocument>()
        readableDatabase.query(
            TABLE,
            null,
            where,
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            null,
            null,
            "created_at DESC, id DESC"
        ).use { c -> while (c.moveToNext()) out += fromCursor(c) }
        return out
    }

    private fun toValues(d: ScannerDocument) = ContentValues().apply {
        put("ge360_id", d.ge360Id)
        put("type", d.type)
        put("document_date", d.documentDate)
        put("document_number", d.documentNumber)
        put("supplier", d.supplier)
        put("category", d.category)
        if (d.taxableAmount == null) putNull("taxable_amount") else put("taxable_amount", d.taxableAmount)
        if (d.vatAmount == null) putNull("vat_amount") else put("vat_amount", d.vatAmount)
        if (d.totalAmount == null) putNull("total_amount") else put("total_amount", d.totalAmount)
        put("confidence", d.confidence)
        put("ocr_text", d.ocrText)
        put("pdf_path", d.pdfPath)
        put("jobsite_id", d.jobsiteId)
        put("jobsite_code", d.jobsiteCode)
        put("jobsite_title", d.jobsiteTitle)
        put("expense_ge360_id", d.expenseGe360Id)
        put("status", d.status)
        put("created_at", d.createdAt)
        put("updated_at", d.updatedAt)
        put("tool_name", d.toolName)
        put("tool_type", d.toolType)
    }

    private fun fromCursor(c: Cursor) = ScannerDocument(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
        type = c.getString(c.getColumnIndexOrThrow("type")),
        documentDate = c.getString(c.getColumnIndexOrThrow("document_date")),
        documentNumber = c.getString(c.getColumnIndexOrThrow("document_number")),
        supplier = c.getString(c.getColumnIndexOrThrow("supplier")),
        category = c.getString(c.getColumnIndexOrThrow("category")),
        taxableAmount = c.nullableDouble("taxable_amount"),
        vatAmount = c.nullableDouble("vat_amount"),
        totalAmount = c.nullableDouble("total_amount"),
        confidence = c.getDouble(c.getColumnIndexOrThrow("confidence")),
        ocrText = c.getString(c.getColumnIndexOrThrow("ocr_text")),
        pdfPath = c.getString(c.getColumnIndexOrThrow("pdf_path")),
        jobsiteId = c.getString(c.getColumnIndexOrThrow("jobsite_id")),
        jobsiteCode = c.getString(c.getColumnIndexOrThrow("jobsite_code")),
        jobsiteTitle = c.getString(c.getColumnIndexOrThrow("jobsite_title")),
        expenseGe360Id = c.getString(c.getColumnIndexOrThrow("expense_ge360_id")),
        status = c.getString(c.getColumnIndexOrThrow("status")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
        toolName = c.getString(c.getColumnIndexOrThrow("tool_name")),
        toolType = c.getString(c.getColumnIndexOrThrow("tool_type"))
    )

    private fun Cursor.nullableDouble(name: String): Double? {
        val index = getColumnIndexOrThrow(name)
        return if (isNull(index)) null else getDouble(index)
    }

    companion object {
        private const val TABLE = "scanner_documents"
        private const val DB_NAME = "ge360_scanner.db"
        private const val DB_VERSION = 2
    }
}
