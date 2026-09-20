package it.edilmilan.ge360.attrezzi

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max

data class MaterialInput(
    val key: String,
    val label: String,
    val defaultValue: Double,
    val suffix: String = ""
)

data class MaterialResult(
    val name: String,
    val quantity: Double,
    val unit: String,
    val purchase: Boolean = true,
    val note: String = ""
)

data class MaterialWorkSpec(
    val id: String,
    val title: String,
    val subtitle: String,
    val inputs: List<MaterialInput>,
    val calculate: (Map<String, Double>) -> List<MaterialResult>
)

data class MaterialCalculationRecord(
    var id: Long = 0,
    var ge360Id: String = "",
    var workType: String = "",
    var title: String = "",
    var inputsJson: String = "{}",
    var resultsJson: String = "[]",
    var jobsiteId: String = "",
    var jobsiteCode: String = "",
    var jobsiteTitle: String = "",
    var createdAt: Long = System.currentTimeMillis()
)

object MaterialCalculationEngine {
    private fun pct(v: Double) = 1.0 + v.coerceAtLeast(0.0) / 100.0
    private fun bags(total: Double, bag: Double) = ceil(total / bag.coerceAtLeast(0.01))

    val specs: List<MaterialWorkSpec> = listOf(
        MaterialWorkSpec(
            id = "MURATURA_FORATI",
            title = "Muro in forati",
            subtitle = "Mattoni + malta in base a lunghezza, altezza e formato",
            inputs = listOf(
                MaterialInput("length", "Lunghezza muro", 4.0, "m"),
                MaterialInput("height", "Altezza muro", 2.7, "m"),
                MaterialInput("brickLength", "Lunghezza mattone", 25.0, "cm"),
                MaterialInput("brickHeight", "Altezza mattone", 12.0, "cm"),
                MaterialInput("brickThickness", "Spessore mattone", 8.0, "cm"),
                MaterialInput("joint", "Giunto malta", 10.0, "mm"),
                MaterialInput("waste", "Sfrido", 8.0, "%")
            )
        ) { v ->
            val area = v.getValue("length") * v.getValue("height")
            val jointM = v.getValue("joint") / 1000.0
            val moduleArea = (v.getValue("brickLength") / 100.0 + jointM) * (v.getValue("brickHeight") / 100.0 + jointM)
            val rawBricks = area / max(moduleArea, 0.0001)
            val bricks = ceil(rawBricks * pct(v.getValue("waste")))
            val wallVolume = area * (v.getValue("brickThickness") / 100.0)
            val brickVolume = (v.getValue("brickLength") / 100.0) * (v.getValue("brickHeight") / 100.0) * (v.getValue("brickThickness") / 100.0)
            val mortarM3 = max(0.0, wallVolume - rawBricks * brickVolume) * 1.10
            val mortarKg = mortarM3 * 1800.0
            listOf(
                MaterialResult("Forati", bricks, "pz"),
                MaterialResult("Malta pronta", ceil(mortarKg), "kg", note = "Stima con densità 1.800 kg/m³"),
                MaterialResult("Volume malta", mortarM3, "m³", purchase = false)
            )
        },
        MaterialWorkSpec(
            id = "PITTURAZIONE",
            title = "Pitturazione",
            subtitle = "Litri di pittura in base a superficie, mani e resa",
            inputs = listOf(
                MaterialInput("area", "Superficie", 50.0, "m²"),
                MaterialInput("coats", "Numero mani", 2.0),
                MaterialInput("coverage", "Resa prodotto", 10.0, "m²/L"),
                MaterialInput("waste", "Margine", 10.0, "%")
            )
        ) { v ->
            val liters = v.getValue("area") * v.getValue("coats") / max(v.getValue("coverage"), 0.1) * pct(v.getValue("waste"))
            listOf(MaterialResult("Pittura", liters, "L"))
        },
        MaterialWorkSpec(
            id = "CONTROSOFFITTO",
            title = "Controsoffitto cartongesso",
            subtitle = "Lastre, viti e profili principali",
            inputs = listOf(
                MaterialInput("area", "Superficie", 20.0, "m²"),
                MaterialInput("sheetLength", "Lunghezza lastra", 2.0, "m"),
                MaterialInput("sheetWidth", "Larghezza lastra", 1.2, "m"),
                MaterialInput("waste", "Sfrido lastre", 10.0, "%"),
                MaterialInput("screws", "Viti per m²", 20.0, "pz/m²"),
                MaterialInput("profiles", "Profili per m²", 2.8, "m/m²")
            )
        ) { v ->
            val area = v.getValue("area")
            val sheetArea = max(v.getValue("sheetLength") * v.getValue("sheetWidth"), 0.01)
            listOf(
                MaterialResult("Lastre cartongesso", ceil(area / sheetArea * pct(v.getValue("waste"))), "pz"),
                MaterialResult("Viti cartongesso", ceil(area * v.getValue("screws")), "pz"),
                MaterialResult("Profili metallici", area * v.getValue("profiles"), "m")
            )
        },
        MaterialWorkSpec(
            id = "AUTOLIVELLANTE",
            title = "Autolivellante",
            subtitle = "Kg e sacchi in base a superficie e spessore",
            inputs = listOf(
                MaterialInput("area", "Superficie", 25.0, "m²"),
                MaterialInput("thickness", "Spessore medio", 5.0, "mm"),
                MaterialInput("consumption", "Consumo", 1.7, "kg/m²/mm"),
                MaterialInput("bag", "Peso sacco", 25.0, "kg"),
                MaterialInput("waste", "Margine", 7.0, "%")
            )
        ) { v ->
            val kg = v.getValue("area") * v.getValue("thickness") * v.getValue("consumption") * pct(v.getValue("waste"))
            listOf(
                MaterialResult("Autolivellante", kg, "kg", purchase = false),
                MaterialResult("Sacchi autolivellante", bags(kg, v.getValue("bag")), "sacchi")
            )
        },
        MaterialWorkSpec(
            id = "MASSETTO",
            title = "Massetto",
            subtitle = "Volume e sacchi premiscelato",
            inputs = listOf(
                MaterialInput("area", "Superficie", 40.0, "m²"),
                MaterialInput("thickness", "Spessore", 6.0, "cm"),
                MaterialInput("density", "Consumo premiscelato", 1900.0, "kg/m³"),
                MaterialInput("bag", "Peso sacco", 25.0, "kg"),
                MaterialInput("waste", "Margine", 7.0, "%")
            )
        ) { v ->
            val m3 = v.getValue("area") * v.getValue("thickness") / 100.0 * pct(v.getValue("waste"))
            val kg = m3 * v.getValue("density")
            listOf(
                MaterialResult("Volume massetto", m3, "m³", purchase = false),
                MaterialResult("Premiscelato massetto", kg, "kg", purchase = false),
                MaterialResult("Sacchi massetto", bags(kg, v.getValue("bag")), "sacchi")
            )
        },
        MaterialWorkSpec(
            id = "PIASTRELLE",
            title = "Posa piastrelle",
            subtitle = "Piastrelle, collante e stucco fughe",
            inputs = listOf(
                MaterialInput("area", "Superficie", 20.0, "m²"),
                MaterialInput("tileWidth", "Larghezza piastrella", 60.0, "cm"),
                MaterialInput("tileHeight", "Altezza piastrella", 60.0, "cm"),
                MaterialInput("waste", "Sfrido piastrelle", 10.0, "%"),
                MaterialInput("adhesive", "Collante", 4.0, "kg/m²"),
                MaterialInput("grout", "Stucco fughe", 0.35, "kg/m²")
            )
        ) { v ->
            val area = v.getValue("area")
            val tileArea = max(v.getValue("tileWidth") * v.getValue("tileHeight") / 10000.0, 0.001)
            val adhesiveKg = area * v.getValue("adhesive") * 1.05
            listOf(
                MaterialResult("Piastrelle", ceil(area / tileArea * pct(v.getValue("waste"))), "pz"),
                MaterialResult("Collante piastrelle", ceil(adhesiveKg / 25.0), "sacchi 25 kg"),
                MaterialResult("Stucco fughe", area * v.getValue("grout") * 1.10, "kg")
            )
        },
        MaterialWorkSpec(
            id = "RASATURA",
            title = "Rasatura",
            subtitle = "Rasante in base a superficie, mani e consumo",
            inputs = listOf(
                MaterialInput("area", "Superficie", 60.0, "m²"),
                MaterialInput("coats", "Numero mani", 2.0),
                MaterialInput("consumption", "Consumo per mano", 1.2, "kg/m²"),
                MaterialInput("bag", "Peso sacco", 25.0, "kg"),
                MaterialInput("waste", "Margine", 7.0, "%")
            )
        ) { v ->
            val kg = v.getValue("area") * v.getValue("coats") * v.getValue("consumption") * pct(v.getValue("waste"))
            listOf(MaterialResult("Sacchi rasante", bags(kg, v.getValue("bag")), "sacchi"), MaterialResult("Rasante totale", kg, "kg", purchase = false))
        },
        MaterialWorkSpec(
            id = "INTONACO",
            title = "Intonaco",
            subtitle = "Kg e sacchi in base a superficie e spessore",
            inputs = listOf(
                MaterialInput("area", "Superficie", 30.0, "m²"),
                MaterialInput("thickness", "Spessore", 10.0, "mm"),
                MaterialInput("consumption", "Consumo", 1.4, "kg/m²/mm"),
                MaterialInput("bag", "Peso sacco", 25.0, "kg"),
                MaterialInput("waste", "Margine", 7.0, "%")
            )
        ) { v ->
            val kg = v.getValue("area") * v.getValue("thickness") * v.getValue("consumption") * pct(v.getValue("waste"))
            listOf(MaterialResult("Sacchi intonaco", bags(kg, v.getValue("bag")), "sacchi"), MaterialResult("Intonaco totale", kg, "kg", purchase = false))
        }
    )

    fun spec(id: String): MaterialWorkSpec = specs.firstOrNull { it.id == id } ?: specs.first()

    fun inputsToJson(values: Map<String, Double>): String = JSONObject().apply {
        values.forEach { (key, value) -> put(key, value) }
    }.toString()

    fun resultsToJson(results: List<MaterialResult>): String = JSONArray().apply {
        results.forEach { r ->
            put(JSONObject().put("name", r.name).put("quantity", r.quantity).put("unit", r.unit).put("purchase", r.purchase).put("note", r.note))
        }
    }.toString()

    fun resultsFromJson(json: String): List<MaterialResult> {
        val array = runCatching { JSONArray(json) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(MaterialResult(o.optString("name"), o.optDouble("quantity"), o.optString("unit"), o.optBoolean("purchase", true), o.optString("note")))
            }
        }
    }
}

class MaterialCalculationStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE calculations(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ge360_id TEXT NOT NULL UNIQUE,
            work_type TEXT NOT NULL,
            title TEXT NOT NULL,
            inputs_json TEXT NOT NULL,
            results_json TEXT NOT NULL,
            jobsite_id TEXT NOT NULL DEFAULT '',
            jobsite_code TEXT NOT NULL DEFAULT '',
            jobsite_title TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX idx_material_calculations_date ON calculations(created_at DESC)")
        db.execSQL("CREATE INDEX idx_material_calculations_job ON calculations(jobsite_id, created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun save(record: MaterialCalculationRecord): Long {
        if (record.ge360Id.isBlank()) record.ge360Id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("ge360_id", record.ge360Id)
            put("work_type", record.workType)
            put("title", record.title)
            put("inputs_json", record.inputsJson)
            put("results_json", record.resultsJson)
            put("jobsite_id", record.jobsiteId)
            put("jobsite_code", record.jobsiteCode)
            put("jobsite_title", record.jobsiteTitle)
            put("created_at", record.createdAt)
        }
        return if (record.id > 0) {
            writableDatabase.update("calculations", values, "id=?", arrayOf(record.id.toString()))
            record.id
        } else writableDatabase.insertOrThrow("calculations", null, values).also { record.id = it }
    }

    fun list(limit: Int = 100): List<MaterialCalculationRecord> {
        val out = mutableListOf<MaterialCalculationRecord>()
        readableDatabase.query("calculations", null, null, null, null, null, "created_at DESC", limit.coerceAtLeast(1).toString()).use { c ->
            while (c.moveToNext()) {
                out += MaterialCalculationRecord(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
                    workType = c.getString(c.getColumnIndexOrThrow("work_type")),
                    title = c.getString(c.getColumnIndexOrThrow("title")),
                    inputsJson = c.getString(c.getColumnIndexOrThrow("inputs_json")),
                    resultsJson = c.getString(c.getColumnIndexOrThrow("results_json")),
                    jobsiteId = c.getString(c.getColumnIndexOrThrow("jobsite_id")),
                    jobsiteCode = c.getString(c.getColumnIndexOrThrow("jobsite_code")),
                    jobsiteTitle = c.getString(c.getColumnIndexOrThrow("jobsite_title")),
                    createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                )
            }
        }
        return out
    }

    fun exportJson(): String = JSONObject().apply {
        put("schema", BACKUP_SCHEMA)
        put("calculations", JSONArray().apply {
            list(10000).forEach { r ->
                put(JSONObject()
                    .put("ge360Id", r.ge360Id)
                    .put("workType", r.workType)
                    .put("title", r.title)
                    .put("inputsJson", r.inputsJson)
                    .put("resultsJson", r.resultsJson)
                    .put("jobsiteId", r.jobsiteId)
                    .put("jobsiteCode", r.jobsiteCode)
                    .put("jobsiteTitle", r.jobsiteTitle)
                    .put("createdAt", r.createdAt))
            }
        })
    }.toString()

    fun importJson(json: String, replaceAll: Boolean): Int {
        val root = JSONObject(json)
        require(root.optString("schema") == BACKUP_SCHEMA) { "Backup Calcolo materiali non valido" }
        if (replaceAll) writableDatabase.delete("calculations", null, null)
        val existing = list(10000).associateBy { it.ge360Id }
        val rows = root.optJSONArray("calculations") ?: JSONArray()
        var count = 0
        for (i in 0 until rows.length()) {
            val o = rows.getJSONObject(i)
            val uuid = o.optString("ge360Id", UUID.randomUUID().toString())
            val record = MaterialCalculationRecord(
                id = existing[uuid]?.id ?: 0,
                ge360Id = uuid,
                workType = o.optString("workType"),
                title = o.optString("title"),
                inputsJson = o.optString("inputsJson", "{}"),
                resultsJson = o.optString("resultsJson", "[]"),
                jobsiteId = o.optString("jobsiteId"),
                jobsiteCode = o.optString("jobsiteCode"),
                jobsiteTitle = o.optString("jobsiteTitle"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
            save(record)
            count++
        }
        return count
    }

    companion object {
        const val BACKUP_SCHEMA = "ge360.material-calculations.v1"
        private const val DB_NAME = "ge360_material_calculations.db"
        private const val DB_VERSION = 1
    }
}
