package it.edilmilan.ge360.attrezzi

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import it.edilmilan.ge360.scanner.ScannerDocument
import it.edilmilan.ge360.scanner.DocumentStore
import it.edilmilan.ge360.cantieri.JobsiteStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Operator-confirmed provenance of invoiced or delivered material quantities.
 *
 * A Scanner FATTURA is an INVOICED quantity; DDT is a DELIVERED quantity.
 * These independent measures are NEVER added together. Neither is a warehouse
 * receipt, payment, cash cost, OCR guess, or proof that goods were consumed.
 */
data class MaterialDocumentLineV13(
    val ge360Id: String,
    val documentGe360Id: String,
    val materialGe360Id: String,
    val jobsiteGe360Id: String,
    val documentKind: String,
    val quantity: Double,
    val unit: String,
    val confirmedAt: Long,
    val voidedAt: Long = 0L
) {
    init {
        require(ge360Id.isNotBlank() && documentGe360Id.isNotBlank() &&
            materialGe360Id.isNotBlank() && unit.isNotBlank())
        require(documentKind in setOf("FATTURA", "DDT"))
        require(quantity.isFinite() && quantity > 0.0)
        require(confirmedAt > 0L && (voidedAt == 0L || voidedAt >= confirmedAt))
    }
    val active: Boolean get() = voidedAt == 0L
}

/** Pure, unit-aware comparison of confirmed lines; no price/stock inference. */
object MaterialDocumentEvidenceRulesV13 {
    data class Totals(
        val invoiced: Double,
        val delivered: Double,
        val invoiceLines: Int,
        val ddtLines: Int,
        val invalidUnits: Int
    )
    fun totals(
        lines: List<MaterialDocumentLineV13>,
        materialId: String,
        jobsiteId: String,
        unit: String
    ): Totals {
        val scoped = lines.filter { it.active && it.materialGe360Id == materialId &&
            it.jobsiteGe360Id == jobsiteId }
        val valid = scoped.filter { it.unit.trim().equals(unit.trim(), ignoreCase = true) }
        return Totals(
            invoiced = valid.filter { it.documentKind == "FATTURA" }.sumOf { it.quantity },
            delivered = valid.filter { it.documentKind == "DDT" }.sumOf { it.quantity },
            invoiceLines = valid.count { it.documentKind == "FATTURA" },
            ddtLines = valid.count { it.documentKind == "DDT" },
            invalidUnits = scoped.size - valid.size
        )
    }
}

class MaterialDocumentEvidenceStoreV13(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE, null, VERSION) {
    private val app = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE material_document_lines(
            ge360_id TEXT PRIMARY KEY NOT NULL,
            document_id TEXT NOT NULL,
            material_id TEXT NOT NULL,
            jobsite_id TEXT NOT NULL DEFAULT '',
            document_kind TEXT NOT NULL,
            quantity REAL NOT NULL CHECK(quantity > 0),
            unit TEXT NOT NULL,
            confirmed_at INTEGER NOT NULL,
            voided_at INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("CREATE INDEX idx_doc_line_material_job ON material_document_lines(material_id, jobsite_id)")
        db.execSQL("CREATE INDEX idx_doc_line_document ON material_document_lines(document_id)")
        db.execSQL("""CREATE UNIQUE INDEX idx_doc_line_active_unique
            ON material_document_lines(document_id,material_id,jobsite_id)
            WHERE voided_at=0""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion == newVersion) { "Migrazione V13 non prevista: preservare i dati" }
    }

    fun all(): List<MaterialDocumentLineV13> {
        val out = mutableListOf<MaterialDocumentLineV13>()
        readableDatabase.query("material_document_lines", null, null, null, null, null,
            "confirmed_at DESC, ge360_id").use { c ->
            while (c.moveToNext()) out += MaterialDocumentLineV13(
                ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
                documentGe360Id = c.getString(c.getColumnIndexOrThrow("document_id")),
                materialGe360Id = c.getString(c.getColumnIndexOrThrow("material_id")),
                jobsiteGe360Id = c.getString(c.getColumnIndexOrThrow("jobsite_id")),
                documentKind = c.getString(c.getColumnIndexOrThrow("document_kind")),
                quantity = c.getDouble(c.getColumnIndexOrThrow("quantity")),
                unit = c.getString(c.getColumnIndexOrThrow("unit")),
                confirmedAt = c.getLong(c.getColumnIndexOrThrow("confirmed_at")),
                voidedAt = c.getLong(c.getColumnIndexOrThrow("voided_at"))
            )
        }
        return out
    }

    fun forJobsite(jobsiteId: String): List<MaterialDocumentLineV13> =
        all().filter { it.jobsiteGe360Id == jobsiteId && it.active }

    fun confirm(
        doc: ScannerDocument,
        material: MaterialStockItem,
        jobsiteId: String?,
        quantity: Double
    ): MaterialDocumentLineV13 {
        require(doc.ge360Id.isNotBlank() && material.ge360Id.isNotBlank())
        val kind = doc.type.trim().uppercase(java.util.Locale.ROOT)
        require(kind in setOf("FATTURA", "DDT")) { "Selezionare una fattura o un DDT Scanner" }
        require(quantity.isFinite() && quantity > 0.0) { "Quantità non valida" }
        val scope = jobsiteId.orEmpty()
        // Re-read canonical data at confirmation time: never trust a stale
        // spinner row from an activity that may have been paused.
        val freshDoc = DocumentStore(app).use { it.getByGe360Id(doc.ge360Id) }
            ?: error("Documento Scanner non più presente")
        val freshMaterial = MaterialInventoryStore(app).use { store ->
            store.list().firstOrNull { it.ge360Id == material.ge360Id }
        } ?: error("Materiale non più presente nel magazzino")
        require(freshMaterial.unit == material.unit) {
            "Unità materiale modificata durante la conferma"
        }
        require(freshDoc.type.trim().uppercase(java.util.Locale.ROOT) == kind) {
            "Tipo documento modificato durante la conferma"
        }
        require(scope.isBlank() || JobsiteStore(app).use { store ->
            store.list().any { it.ge360Id == scope }
        }) { "Cantiere non più presente" }
        require(freshDoc.jobsiteId.isBlank() || freshDoc.jobsiteId == scope) {
            "Documento associato a un cantiere diverso. Verificare prima in Scanner"
        }
        require(freshDoc.pdfPath.isNotBlank() && java.io.File(freshDoc.pdfPath).isFile) {
            "Documento Scanner senza PDF locale verificabile: associare prima il file"
        }
        val line = MaterialDocumentLineV13(
            ge360Id = UUID.randomUUID().toString(),
            documentGe360Id = doc.ge360Id,
            materialGe360Id = material.ge360Id,
            jobsiteGe360Id = scope,
            documentKind = kind,
            quantity = quantity,
            unit = material.unit,
            confirmedAt = System.currentTimeMillis()
        )
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("material_document_lines", null, values(line))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return line
    }

    /** No physical deletion; historical confirmed entry remains traceable. */
    fun void(ge360Id: String): Boolean {
        val current = all().singleOrNull { it.ge360Id == ge360Id && it.active } ?: return false
        val count = writableDatabase.update("material_document_lines",
            ContentValues().apply { put("voided_at", System.currentTimeMillis().coerceAtLeast(current.confirmedAt)) },
            "ge360_id=? AND voided_at=0", arrayOf(ge360Id))
        return count == 1
    }

    fun exportJson(): String = JSONObject()
        .put("schema", SCHEMA)
        .put("exportedAt", System.currentTimeMillis())
        .put("lines", JSONArray().apply { all().forEach { put(toJson(it)) } })
        .toString(2)

    /**
     * Cross-module reference validation is performed by the coordinator prior
     * to any restore mutation. The SQLite transaction is local to this ledger.
     */
    fun importJson(json: String, replaceAll: Boolean): Int {
        val root = JSONObject(json)
        require(root.optString("schema") == SCHEMA) { "Formato righe documentali V13 non riconosciuto" }
        val arr = root.getJSONArray("lines")
        val incoming = (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
        require(incoming.map { it.ge360Id }.distinct().size == incoming.size) {
            "Backup V13: righe UUID duplicate"
        }
        require(incoming.filter { it.active }.map {
            Triple(it.documentGe360Id, it.materialGe360Id, it.jobsiteGe360Id)
        }.distinct().size == incoming.count { it.active }) {
            "Backup V13: doppia attribuzione attiva del medesimo documento/materiale/cantiere"
        }
        val existing = if (replaceAll) emptyMap()
            else all().associateBy { it.ge360Id }
        if (!replaceAll) incoming.forEach { line ->
            existing[line.ge360Id]?.let { prior ->
                require(prior == line) { "Backup V13: stesso UUID con contenuto diverso" }
            }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (replaceAll) db.delete("material_document_lines", null, null)
            var imported = 0
            incoming.forEach { line ->
                if (line.ge360Id !in existing) {
                    db.insertOrThrow("material_document_lines", null, values(line))
                    imported++
                }
            }
            db.setTransactionSuccessful()
            return imported
        } finally { db.endTransaction() }
    }

    private fun values(x: MaterialDocumentLineV13) = ContentValues().apply {
        put("ge360_id", x.ge360Id)
        put("document_id", x.documentGe360Id)
        put("material_id", x.materialGe360Id)
        put("jobsite_id", x.jobsiteGe360Id)
        put("document_kind", x.documentKind)
        put("quantity", x.quantity)
        put("unit", x.unit)
        put("confirmed_at", x.confirmedAt)
        put("voided_at", x.voidedAt)
    }

    companion object {
        const val SCHEMA = "ge360.material-document-evidence.v13"
        private const val DATABASE = "ge360_material_document_evidence_v13.db"
        private const val VERSION = 1

        fun toJson(x: MaterialDocumentLineV13): JSONObject = JSONObject()
            .put("ge360Id", x.ge360Id)
            .put("documentGe360Id", x.documentGe360Id)
            .put("materialGe360Id", x.materialGe360Id)
            .put("jobsiteGe360Id", x.jobsiteGe360Id)
            .put("documentKind", x.documentKind)
            .put("quantity", x.quantity)
            .put("unit", x.unit)
            .put("confirmedAt", x.confirmedAt)
            .put("voidedAt", x.voidedAt)

        fun parse(o: JSONObject) = MaterialDocumentLineV13(
            ge360Id = o.getString("ge360Id"),
            documentGe360Id = o.getString("documentGe360Id"),
            materialGe360Id = o.getString("materialGe360Id"),
            jobsiteGe360Id = o.optString("jobsiteGe360Id"),
            documentKind = o.getString("documentKind"),
            quantity = o.getDouble("quantity"),
            unit = o.getString("unit"),
            confirmedAt = o.getLong("confirmedAt"),
            voidedAt = o.optLong("voidedAt", 0L)
        )
    }
}
