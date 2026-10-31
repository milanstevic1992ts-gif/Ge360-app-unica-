package it.edilmilan.ge360.attrezzi

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Immutable, user-confirmed expected quantities. Not another stock/material catalog. */
data class MaterialPlanRevisionV1(
    val ge360Id: String,
    val materialGe360Id: String,
    val jobsiteGe360Id: String?,
    val quantity: Double,
    val unit: String,
    val confirmedAt: Long,
    val sourceType: String = "MANUAL",
    val sourceCalculationGe360Id: String? = null
) {
    init {
        require(ge360Id.isNotBlank() && materialGe360Id.isNotBlank())
        require(unit.isNotBlank() && quantity.isFinite() && quantity >= 0.0)
        require(confirmedAt > 0)
        require(sourceType == "MANUAL" || sourceType == "CALCULATION")
        if (sourceType == "CALCULATION") require(!sourceCalculationGe360Id.isNullOrBlank())
    }

    fun toQuantity() = PlannedMaterialQuantityV1(
        materialGe360Id = materialGe360Id,
        quantity = quantity,
        unit = unit,
        jobsiteGe360Id = jobsiteGe360Id,
        planVersion = ge360Id
    )
}

class MaterialPlanStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE material_plan_revisions(
            ge360_id TEXT NOT NULL PRIMARY KEY,
            material_ge360_id TEXT NOT NULL,
            jobsite_ge360_id TEXT NOT NULL DEFAULT '',
            quantity REAL NOT NULL CHECK(quantity >= 0),
            unit TEXT NOT NULL,
            confirmed_at INTEGER NOT NULL,
            source_type TEXT NOT NULL DEFAULT 'MANUAL',
            source_calculation_id TEXT NOT NULL DEFAULT ''
        )""")
        db.execSQL("CREATE INDEX idx_material_plans_scope ON material_plan_revisions(material_ge360_id,jobsite_ge360_id,confirmed_at DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Migrazione piani materiali non prevista; preservare i dati esistenti")
    }

    fun all(): List<MaterialPlanRevisionV1> {
        val out = mutableListOf<MaterialPlanRevisionV1>()
        readableDatabase.query(
            "material_plan_revisions", null, null, null, null, null,
            "confirmed_at DESC, ge360_id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) out += fromCursor(cursor)
        }
        return out
    }

    fun latest(materialGe360Id: String, jobsiteGe360Id: String?): MaterialPlanRevisionV1? =
        all().firstOrNull {
            it.materialGe360Id == materialGe360Id &&
                it.jobsiteGe360Id.orEmpty() == jobsiteGe360Id.orEmpty()
        }

    /**
     * Plan is a confirmed snapshot; updates always append a new revision.
     * Matching is UUID-based and units are checked against the existing stock item.
     */
    fun saveConfirmed(item: MaterialStockItem, plan: MaterialPlanRevisionV1) {
        require(plan.materialGe360Id == item.ge360Id) { "UUID materiale diverso" }
        require(plan.unit.trim().equals(item.unit.trim(), ignoreCase = true)) {
            "Unità del piano incompatibile con il materiale"
        }
        require(plan.sourceType == "MANUAL") {
            "Collegamenti a calcoli materiali richiedono conferma della fonte"
        }
        writableDatabase.insertOrThrow("material_plan_revisions", null, values(plan))
    }

    fun exportJson(): String = JSONObject()
        .put("schema", SCHEMA)
        .put("exportedAt", System.currentTimeMillis())
        .put("revisions", JSONArray().apply { all().forEach { put(toJson(it)) } })
        .toString(2)

    fun importJson(json: String, replaceAll: Boolean): Int {
        val root = JSONObject(json)
        require(root.optString("schema") == SCHEMA) { "Versione piani materiali non riconosciuta" }
        val arr = root.getJSONArray("revisions")
        val rows = (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        require(rows.map { it.ge360Id }.distinct().size == rows.size) {
            "UUID piani materiali duplicati"
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (replaceAll) db.delete("material_plan_revisions", null, null)
            val known = all().associateBy { it.ge360Id }
            var inserted = 0
            rows.forEach { plan ->
                val existing = known[plan.ge360Id]
                if (existing == null) {
                    db.insertOrThrow("material_plan_revisions", null, values(plan))
                    inserted++
                } else require(existing == plan) {
                    "Conflitto revisione quantità prevista: nessuna sovrascrittura"
                }
            }
            db.setTransactionSuccessful()
            return inserted
        } finally {
            db.endTransaction()
        }
    }

    private fun values(x: MaterialPlanRevisionV1) = ContentValues().apply {
        put("ge360_id", x.ge360Id)
        put("material_ge360_id", x.materialGe360Id)
        put("jobsite_ge360_id", x.jobsiteGe360Id.orEmpty())
        put("quantity", x.quantity)
        put("unit", x.unit)
        put("confirmed_at", x.confirmedAt)
        put("source_type", x.sourceType)
        put("source_calculation_id", x.sourceCalculationGe360Id.orEmpty())
    }

    private fun fromCursor(c: Cursor) = MaterialPlanRevisionV1(
        ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
        materialGe360Id = c.getString(c.getColumnIndexOrThrow("material_ge360_id")),
        jobsiteGe360Id = c.getString(c.getColumnIndexOrThrow("jobsite_ge360_id")).ifBlank { null },
        quantity = c.getDouble(c.getColumnIndexOrThrow("quantity")),
        unit = c.getString(c.getColumnIndexOrThrow("unit")),
        confirmedAt = c.getLong(c.getColumnIndexOrThrow("confirmed_at")),
        sourceType = c.getString(c.getColumnIndexOrThrow("source_type")),
        sourceCalculationGe360Id =
            c.getString(c.getColumnIndexOrThrow("source_calculation_id")).ifBlank { null }
    )

    private fun toJson(x: MaterialPlanRevisionV1) = JSONObject()
        .put("ge360Id", x.ge360Id)
        .put("materialGe360Id", x.materialGe360Id)
        .put("jobsiteGe360Id", x.jobsiteGe360Id.orEmpty())
        .put("quantity", x.quantity)
        .put("unit", x.unit)
        .put("confirmedAt", x.confirmedAt)
        .put("sourceType", x.sourceType)
        .put("sourceCalculationGe360Id", x.sourceCalculationGe360Id.orEmpty())

    private fun fromJson(x: JSONObject) = MaterialPlanRevisionV1(
        ge360Id = x.getString("ge360Id"),
        materialGe360Id = x.getString("materialGe360Id"),
        jobsiteGe360Id = x.optString("jobsiteGe360Id").ifBlank { null },
        quantity = x.getDouble("quantity"),
        unit = x.getString("unit"),
        confirmedAt = x.getLong("confirmedAt"),
        sourceType = x.optString("sourceType", "MANUAL"),
        sourceCalculationGe360Id = x.optString("sourceCalculationGe360Id").ifBlank { null }
    )

    companion object {
        const val SCHEMA = "ge360.material-plans.v1"
        private const val DATABASE = "ge360_material_plans_v1.db"
        private const val VERSION = 1
        fun newManual(item: MaterialStockItem, jobsiteId: String?, quantity: Double) =
            MaterialPlanRevisionV1(
                ge360Id = UUID.randomUUID().toString(),
                materialGe360Id = item.ge360Id,
                jobsiteGe360Id = jobsiteId,
                quantity = quantity,
                unit = item.unit,
                confirmedAt = System.currentTimeMillis()
            )
    }
}
