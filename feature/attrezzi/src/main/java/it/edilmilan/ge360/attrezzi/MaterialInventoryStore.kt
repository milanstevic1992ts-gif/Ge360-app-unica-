package it.edilmilan.ge360.attrezzi

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Archivio Materiali 2.0.
 *
 * Viene aperto solo dalla schermata Materiali: non parte nel bootstrap di GE360.
 * Le richieste di acquisto restano nel ToolsStore per evitare duplicazioni; qui vivono
 * invece anagrafica avanzata, giacenze e movimenti di magazzino.
 */
data class MaterialStockItem(
    var id: Long = 0,
    var ge360Id: String = "",
    var catalogGe360Id: String = "",
    var name: String = "",
    var category: String = "Altro",
    var unit: String = "pz",
    var brand: String = "",
    var supplier: String = "",
    var packageSize: Double = 1.0,
    var packageUnit: String = "",
    var stockQty: Double = 0.0,
    var minQty: Double = 0.0,
    var targetQty: Double = 0.0,
    var avgUnitCost: Double = 0.0,
    var location: String = "",
    var barcode: String = "",
    var note: String = "",
    var active: Boolean = true,
    var updatedAt: Long = System.currentTimeMillis()
) {
    val isLow: Boolean get() = minQty > 0.0 && stockQty <= minQty
    val targetShortage: Double
        get() {
            val target = if (targetQty > 0.0) targetQty else minQty
            return (target - stockQty).coerceAtLeast(0.0)
        }
    val inventoryValue: Double get() = stockQty.coerceAtLeast(0.0) * avgUnitCost.coerceAtLeast(0.0)
}

data class MaterialStockMovement(
    var id: Long = 0,
    var ge360Id: String = "",
    var materialGe360Id: String = "",
    var materialName: String = "",
    var type: String = TYPE_LOAD,
    /** Quantità firmata: positiva = entrata, negativa = uscita. */
    var quantity: Double = 0.0,
    var unit: String = "pz",
    var unitCost: Double = 0.0,
    var jobsiteId: String = "",
    var jobsiteCode: String = "",
    var jobsiteTitle: String = "",
    var note: String = "",
    var createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_LOAD = "Carico"
        const val TYPE_CONSUME = "Consumo"
        const val TYPE_RETURN = "Reso"
        const val TYPE_ADJUST_PLUS = "Rettifica +"
        const val TYPE_ADJUST_MINUS = "Rettifica -"
        val TYPES = listOf(TYPE_LOAD, TYPE_CONSUME, TYPE_RETURN, TYPE_ADJUST_PLUS, TYPE_ADJUST_MINUS)
    }
}

data class MaterialInventorySummary(
    val activeMaterials: Int,
    val lowStock: Int,
    val unitsInStock: Double,
    val inventoryValue: Double,
    val movements: Int
)

class MaterialInventoryStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {
    private val provenance = context.applicationContext.getSharedPreferences(
        "ge360_material_movement_provenance", Context.MODE_PRIVATE
    )

    /** Old exports did not declare completeness: they must not produce a false precise delta. */
    fun hasCompleteMovementHistory(): Boolean =
        provenance.getBoolean("complete_history_v1", false)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE materials(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                catalog_ge360_id TEXT NOT NULL DEFAULT '',
                name TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT 'Altro',
                unit TEXT NOT NULL DEFAULT 'pz',
                brand TEXT NOT NULL DEFAULT '',
                supplier TEXT NOT NULL DEFAULT '',
                package_size REAL NOT NULL DEFAULT 1,
                package_unit TEXT NOT NULL DEFAULT '',
                stock_qty REAL NOT NULL DEFAULT 0,
                min_qty REAL NOT NULL DEFAULT 0,
                target_qty REAL NOT NULL DEFAULT 0,
                avg_unit_cost REAL NOT NULL DEFAULT 0,
                location TEXT NOT NULL DEFAULT '',
                barcode TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                active INTEGER NOT NULL DEFAULT 1,
                updated_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE material_movements(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                material_ge360_id TEXT NOT NULL,
                material_name TEXT NOT NULL,
                type TEXT NOT NULL,
                quantity REAL NOT NULL,
                unit TEXT NOT NULL,
                unit_cost REAL NOT NULL DEFAULT 0,
                jobsite_id TEXT NOT NULL DEFAULT '',
                jobsite_code TEXT NOT NULL DEFAULT '',
                jobsite_title TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_materials_name ON materials(active, category, name COLLATE NOCASE)")
        db.execSQL("CREATE INDEX idx_materials_catalog ON materials(catalog_ge360_id)")
        db.execSQL("CREATE INDEX idx_material_movements_material ON material_movements(material_ge360_id, created_at DESC)")
        db.execSQL("CREATE INDEX idx_material_movements_job ON material_movements(jobsite_id, created_at DESC)")
        provenance.edit().putBoolean("complete_history_v1", true).apply()
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun ensureCatalogMirror(catalog: List<CatalogResource>): Int {
        var created = 0
        catalog.asSequence()
            .filter { it.kind == ResourceKind.MATERIAL && it.active }
            .forEach { resource ->
                if (findByCatalogGe360(resource.ge360Id) == null) {
                    save(
                        MaterialStockItem(
                            catalogGe360Id = resource.ge360Id,
                            name = resource.name,
                            category = resource.category,
                            unit = resource.unit,
                            packageUnit = resource.unit,
                            note = resource.note
                        )
                    )
                    created++
                }
            }
        return created
    }

    fun list(query: String = "", lowOnly: Boolean = false): List<MaterialStockItem> {
        val where = mutableListOf("active=1")
        val args = mutableListOf<String>()
        if (query.isNotBlank()) {
            val like = "%${query.trim()}%"
            where += "(name LIKE ? OR category LIKE ? OR brand LIKE ? OR supplier LIKE ? OR location LIKE ? OR barcode LIKE ? OR note LIKE ?)"
            repeat(7) { args += like }
        }
        if (lowOnly) where += "min_qty > 0 AND stock_qty <= min_qty"
        val out = mutableListOf<MaterialStockItem>()
        readableDatabase.query(
            "materials",
            null,
            where.joinToString(" AND "),
            args.toTypedArray(),
            null,
            null,
            "CASE WHEN min_qty > 0 AND stock_qty <= min_qty THEN 0 ELSE 1 END, category COLLATE NOCASE, name COLLATE NOCASE"
        ).use { c -> while (c.moveToNext()) out += materialFromCursor(c) }
        return out
    }

    fun save(item: MaterialStockItem): Long {
        if (item.ge360Id.isBlank()) item.ge360Id = UUID.randomUUID().toString()
        item.updatedAt = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("ge360_id", item.ge360Id)
            put("catalog_ge360_id", item.catalogGe360Id)
            put("name", item.name.trim())
            put("category", item.category.trim().ifBlank { "Altro" })
            put("unit", item.unit.trim().ifBlank { "pz" })
            put("brand", item.brand.trim())
            put("supplier", item.supplier.trim())
            put("package_size", item.packageSize.coerceAtLeast(0.0))
            put("package_unit", item.packageUnit.trim())
            put("stock_qty", item.stockQty)
            put("min_qty", item.minQty.coerceAtLeast(0.0))
            put("target_qty", item.targetQty.coerceAtLeast(0.0))
            put("avg_unit_cost", item.avgUnitCost.coerceAtLeast(0.0))
            put("location", item.location.trim())
            put("barcode", item.barcode.trim())
            put("note", item.note.trim())
            put("active", if (item.active) 1 else 0)
            put("updated_at", item.updatedAt)
        }
        return if (item.id > 0) {
            writableDatabase.update("materials", values, "id=?", arrayOf(item.id.toString()))
            item.id
        } else {
            writableDatabase.insertOrThrow("materials", null, values).also { item.id = it }
        }
    }

    fun deactivate(id: Long) {
        writableDatabase.update(
            "materials",
            ContentValues().apply {
                put("active", 0)
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(id.toString())
        )
    }

    fun findByGe360(id: String): MaterialStockItem? = findOne("ge360_id=?", arrayOf(id))

    fun findByCatalogGe360(id: String): MaterialStockItem? {
        if (id.isBlank()) return null
        return findOne("catalog_ge360_id=?", arrayOf(id))
    }

    fun findByName(name: String, unit: String = ""): MaterialStockItem? {
        if (name.isBlank()) return null
        val selection = if (unit.isBlank()) "active=1 AND name=? COLLATE NOCASE" else "active=1 AND name=? COLLATE NOCASE AND unit=? COLLATE NOCASE"
        val args = if (unit.isBlank()) arrayOf(name.trim()) else arrayOf(name.trim(), unit.trim())
        return findOne(selection, args)
    }

    /**
     * Registra un movimento e aggiorna la giacenza nella stessa transazione.
     * La quantità ricevuta è sempre assoluta; il segno viene deciso dal tipo.
     */
    fun recordMovement(
        item: MaterialStockItem,
        type: String,
        quantity: Double,
        unitCost: Double = 0.0,
        jobsiteId: String = "",
        jobsiteCode: String = "",
        jobsiteTitle: String = "",
        note: String = ""
    ): MaterialStockMovement {
        require(item.ge360Id.isNotBlank()) { "Materiale non salvato" }
        val absQty = quantity.coerceAtLeast(0.0)
        require(absQty > 0.0) { "Quantità non valida" }
        require(type in MaterialStockMovement.TYPES) { "Tipo movimento non valido" }

        val signedQty = when (type) {
            MaterialStockMovement.TYPE_CONSUME,
            MaterialStockMovement.TYPE_ADJUST_MINUS -> -absQty
            else -> absQty
        }

        val current = findByGe360(item.ge360Id) ?: error("Materiale non trovato")
        val newStock = current.stockQty + signedQty
        require(newStock >= -0.000001) { "Giacenza insufficiente: disponibili ${clean(current.stockQty)} ${current.unit}" }

        val now = System.currentTimeMillis()
        val movement = MaterialStockMovement(
            ge360Id = UUID.randomUUID().toString(),
            materialGe360Id = current.ge360Id,
            materialName = current.name,
            type = type,
            quantity = signedQty,
            unit = current.unit,
            unitCost = unitCost.coerceAtLeast(0.0),
            jobsiteId = jobsiteId,
            jobsiteCode = jobsiteCode,
            jobsiteTitle = jobsiteTitle,
            note = note.trim(),
            createdAt = now
        )

        val db = writableDatabase
        db.beginTransaction()
        try {
            var avgCost = current.avgUnitCost
            if (signedQty > 0.0 && unitCost > 0.0) {
                val oldStock = current.stockQty.coerceAtLeast(0.0)
                val denominator = oldStock + signedQty
                avgCost = if (denominator > 0.0) {
                    ((oldStock * current.avgUnitCost) + (signedQty * unitCost)) / denominator
                } else unitCost
            }

            db.update(
                "materials",
                ContentValues().apply {
                    put("stock_qty", newStock.coerceAtLeast(0.0))
                    put("avg_unit_cost", avgCost.coerceAtLeast(0.0))
                    put("updated_at", now)
                },
                "ge360_id=?",
                arrayOf(current.ge360Id)
            )
            db.insertOrThrow("material_movements", null, movementValues(movement)).also { movement.id = it }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return movement
    }

    fun movements(materialGe360Id: String = "", limit: Int = 200): List<MaterialStockMovement> {
        val where = if (materialGe360Id.isBlank()) null else "material_ge360_id=?"
        val args = if (materialGe360Id.isBlank()) null else arrayOf(materialGe360Id)
        val out = mutableListOf<MaterialStockMovement>()
        readableDatabase.query(
            "material_movements",
            null,
            where,
            args,
            null,
            null,
            "created_at DESC",
            limit.coerceIn(1, 1000).toString()
        ).use { c -> while (c.moveToNext()) out += movementFromCursor(c) }
        return out
    }

    /**
     * Complete local ledger, not the paginated UX list.
     * Used for full backups and for consumption comparisons with completeHistory=true.
     */
    fun allMovementHistory(
        materialGe360Id: String = "",
        jobsiteGe360Id: String? = null
    ): List<MaterialStockMovement> {
        val filters = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (materialGe360Id.isNotBlank()) {
            filters += "material_ge360_id=?"
            args += materialGe360Id
        }
        if (!jobsiteGe360Id.isNullOrBlank()) {
            filters += "jobsite_id=?"
            args += jobsiteGe360Id
        }
        val result = mutableListOf<MaterialStockMovement>()
        readableDatabase.query(
            "material_movements", null,
            filters.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            null, null, "created_at ASC, id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) result += movementFromCursor(cursor)
        }
        return result
    }

    fun summary(): MaterialInventorySummary {
        val materials = list()
        val movementCount = readableDatabase.rawQuery("SELECT COUNT(*) FROM material_movements", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        return MaterialInventorySummary(
            activeMaterials = materials.size,
            lowStock = materials.count { it.isLow },
            unitsInStock = materials.sumOf { it.stockQty.coerceAtLeast(0.0) },
            inventoryValue = materials.sumOf { it.inventoryValue },
            movements = movementCount
        )
    }

    fun exportJson(): String {
        val root = JSONObject()
            .put("schema", SCHEMA)
            .put("exportedAt", System.currentTimeMillis())
            .put("completeMovementHistory", hasCompleteMovementHistory())
            .put("materials", JSONArray().apply { allMaterials().forEach { put(materialToJson(it)) } })
            .put("movements", JSONArray().apply { allMovementHistory().forEach { put(movementToJson(it)) } })
        return root.toString(2)
    }

    fun importJson(json: String, replaceAll: Boolean): Int {
        val root = JSONObject(json)
        require(root.optString("schema") == SCHEMA) { "Formato Materiali 2.0 non riconosciuto" }
        val sourceHistoryComplete = root.optBoolean("completeMovementHistory", false)
        val hadCompleteHistory = hasCompleteMovementHistory()
        var count = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (replaceAll) {
                db.delete("material_movements", null, null)
                db.delete("materials", null, null)
            }
            root.optJSONArray("materials")?.let { rows ->
                for (i in 0 until rows.length()) {
                    val item = materialFromJson(rows.getJSONObject(i))
                    findByGe360(item.ge360Id)?.let { item.id = it.id }
                    save(item)
                    count++
                }
            }
            root.optJSONArray("movements")?.let { rows ->
                for (i in 0 until rows.length()) {
                    val movement = movementFromJson(rows.getJSONObject(i))
                    if (!movementExists(movement.ge360Id)) {
                        db.insertOrThrow("material_movements", null, movementValues(movement))
                        count++
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        provenance.edit().putBoolean("complete_history_v1",
            if (replaceAll) sourceHistoryComplete else hadCompleteHistory && sourceHistoryComplete
        ).apply()
        return count
    }

    private fun allMaterials(): List<MaterialStockItem> {
        val out = mutableListOf<MaterialStockItem>()
        readableDatabase.query("materials", null, null, null, null, null, "name COLLATE NOCASE").use { c ->
            while (c.moveToNext()) out += materialFromCursor(c)
        }
        return out
    }

    private fun findOne(selection: String, args: Array<String>): MaterialStockItem? {
        readableDatabase.query("materials", null, selection, args, null, null, null, "1").use { c ->
            return if (c.moveToFirst()) materialFromCursor(c) else null
        }
    }

    private fun movementExists(ge360Id: String): Boolean {
        if (ge360Id.isBlank()) return false
        readableDatabase.rawQuery(
            "SELECT 1 FROM material_movements WHERE ge360_id=? LIMIT 1",
            arrayOf(ge360Id)
        ).use { return it.moveToFirst() }
    }

    private fun materialFromCursor(c: Cursor) = MaterialStockItem(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
        catalogGe360Id = c.getString(c.getColumnIndexOrThrow("catalog_ge360_id")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        category = c.getString(c.getColumnIndexOrThrow("category")),
        unit = c.getString(c.getColumnIndexOrThrow("unit")),
        brand = c.getString(c.getColumnIndexOrThrow("brand")),
        supplier = c.getString(c.getColumnIndexOrThrow("supplier")),
        packageSize = c.getDouble(c.getColumnIndexOrThrow("package_size")),
        packageUnit = c.getString(c.getColumnIndexOrThrow("package_unit")),
        stockQty = c.getDouble(c.getColumnIndexOrThrow("stock_qty")),
        minQty = c.getDouble(c.getColumnIndexOrThrow("min_qty")),
        targetQty = c.getDouble(c.getColumnIndexOrThrow("target_qty")),
        avgUnitCost = c.getDouble(c.getColumnIndexOrThrow("avg_unit_cost")),
        location = c.getString(c.getColumnIndexOrThrow("location")),
        barcode = c.getString(c.getColumnIndexOrThrow("barcode")),
        note = c.getString(c.getColumnIndexOrThrow("note")),
        active = c.getInt(c.getColumnIndexOrThrow("active")) == 1,
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun movementFromCursor(c: Cursor) = MaterialStockMovement(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
        materialGe360Id = c.getString(c.getColumnIndexOrThrow("material_ge360_id")),
        materialName = c.getString(c.getColumnIndexOrThrow("material_name")),
        type = c.getString(c.getColumnIndexOrThrow("type")),
        quantity = c.getDouble(c.getColumnIndexOrThrow("quantity")),
        unit = c.getString(c.getColumnIndexOrThrow("unit")),
        unitCost = c.getDouble(c.getColumnIndexOrThrow("unit_cost")),
        jobsiteId = c.getString(c.getColumnIndexOrThrow("jobsite_id")),
        jobsiteCode = c.getString(c.getColumnIndexOrThrow("jobsite_code")),
        jobsiteTitle = c.getString(c.getColumnIndexOrThrow("jobsite_title")),
        note = c.getString(c.getColumnIndexOrThrow("note")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
    )

    private fun movementValues(x: MaterialStockMovement) = ContentValues().apply {
        put("ge360_id", x.ge360Id)
        put("material_ge360_id", x.materialGe360Id)
        put("material_name", x.materialName)
        put("type", x.type)
        put("quantity", x.quantity)
        put("unit", x.unit)
        put("unit_cost", x.unitCost)
        put("jobsite_id", x.jobsiteId)
        put("jobsite_code", x.jobsiteCode)
        put("jobsite_title", x.jobsiteTitle)
        put("note", x.note)
        put("created_at", x.createdAt)
    }

    private fun materialToJson(x: MaterialStockItem) = JSONObject()
        .put("ge360Id", x.ge360Id)
        .put("catalogGe360Id", x.catalogGe360Id)
        .put("name", x.name)
        .put("category", x.category)
        .put("unit", x.unit)
        .put("brand", x.brand)
        .put("supplier", x.supplier)
        .put("packageSize", x.packageSize)
        .put("packageUnit", x.packageUnit)
        .put("stockQty", x.stockQty)
        .put("minQty", x.minQty)
        .put("targetQty", x.targetQty)
        .put("avgUnitCost", x.avgUnitCost)
        .put("location", x.location)
        .put("barcode", x.barcode)
        .put("note", x.note)
        .put("active", x.active)
        .put("updatedAt", x.updatedAt)

    private fun movementToJson(x: MaterialStockMovement) = JSONObject()
        .put("ge360Id", x.ge360Id)
        .put("materialGe360Id", x.materialGe360Id)
        .put("materialName", x.materialName)
        .put("type", x.type)
        .put("quantity", x.quantity)
        .put("unit", x.unit)
        .put("unitCost", x.unitCost)
        .put("jobsiteId", x.jobsiteId)
        .put("jobsiteCode", x.jobsiteCode)
        .put("jobsiteTitle", x.jobsiteTitle)
        .put("note", x.note)
        .put("createdAt", x.createdAt)

    private fun materialFromJson(o: JSONObject) = MaterialStockItem(
        ge360Id = o.optString("ge360Id", UUID.randomUUID().toString()),
        catalogGe360Id = o.optString("catalogGe360Id"),
        name = o.optString("name"),
        category = o.optString("category", "Altro"),
        unit = o.optString("unit", "pz"),
        brand = o.optString("brand"),
        supplier = o.optString("supplier"),
        packageSize = o.optDouble("packageSize", 1.0),
        packageUnit = o.optString("packageUnit"),
        stockQty = o.optDouble("stockQty", 0.0),
        minQty = o.optDouble("minQty", 0.0),
        targetQty = o.optDouble("targetQty", 0.0),
        avgUnitCost = o.optDouble("avgUnitCost", 0.0),
        location = o.optString("location"),
        barcode = o.optString("barcode"),
        note = o.optString("note"),
        active = o.optBoolean("active", true),
        updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun movementFromJson(o: JSONObject) = MaterialStockMovement(
        ge360Id = o.optString("ge360Id", UUID.randomUUID().toString()),
        materialGe360Id = o.optString("materialGe360Id"),
        materialName = o.optString("materialName"),
        type = o.optString("type", MaterialStockMovement.TYPE_LOAD),
        quantity = o.optDouble("quantity", 0.0),
        unit = o.optString("unit", "pz"),
        unitCost = o.optDouble("unitCost", 0.0),
        jobsiteId = o.optString("jobsiteId"),
        jobsiteCode = o.optString("jobsiteCode"),
        jobsiteTitle = o.optString("jobsiteTitle"),
        note = o.optString("note"),
        createdAt = o.optLong("createdAt", System.currentTimeMillis())
    )

    private fun clean(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)

    companion object {
        const val SCHEMA = "ge360.materials.v2"
        private const val DB_NAME = "ge360_materials_v2.db"
        private const val DB_VERSION = 1
    }
}
