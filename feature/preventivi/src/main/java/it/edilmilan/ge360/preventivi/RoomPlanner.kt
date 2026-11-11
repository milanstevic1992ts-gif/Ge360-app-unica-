package it.edilmilan.ge360.preventivi

import androidx.appcompat.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.round

/**
 * Ambiente del preventivo. Le misure sono volutamente semplici e rettangolari:
 * lunghezza x larghezza x altezza. Le quantità calcolate sono proposte operative,
 * non vincoli: ogni EstimateItem rimane modificabile manualmente nell'editor.
 */
data class EstimateRoom(
    var ge360Id: String = UUID.randomUUID().toString(),
    var estimateGe360Id: String = "",
    var name: String = "Stanza",
    var type: String = RoomTypes.ROOM,
    var length: Double = 0.0,
    var width: Double = 0.0,
    var height: Double = 0.0,
    var pricingMode: String = RoomPricing.MEASURE,
    var bodyPrice: Double = 0.0,
    var selectedCodes: MutableSet<String> = mutableSetOf(),
    var sortOrder: Int = 0,
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun floorArea() = round2Room(length * width)
    fun ceilingArea() = floorArea()
    fun perimeter() = round2Room(2.0 * (length + width))
    fun wallArea() = round2Room(perimeter() * height)
    fun wallsPlusCeiling() = round2Room(wallArea() + ceilingArea())
    fun wallsPlusFloor() = round2Room(wallArea() + floorArea())

    fun measuresSummary(): String = if (length <= 0 || width <= 0) {
        "Misure non inserite"
    } else buildString {
        append("Pavimento ").append(fmtRoom(floorArea())).append(" m²")
        append(" · Soffitto ").append(fmtRoom(ceilingArea())).append(" m²")
        if (height > 0) {
            append(" · Pareti ").append(fmtRoom(wallArea())).append(" m²")
            append(" · Pareti+soffitto ").append(fmtRoom(wallsPlusCeiling())).append(" m²")
        }
        append(" · Perimetro ").append(fmtRoom(perimeter())).append(" ml")
    }
}

object RoomTypes {
    const val BATHROOM = "Bagno"
    const val KITCHEN = "Cucina"
    const val ROOM = "Stanza"
    const val BALCONY = "Balcone/Terrazzo"
    const val OTHER = "Altro"
    val ALL = listOf(BATHROOM, KITCHEN, ROOM, BALCONY, OTHER)
}

object RoomPricing {
    const val BODY = "A corpo"
    const val MEASURE = "A misura"
    val ALL = listOf(BODY, MEASURE)
}

enum class QuantityBasis {
    BODY,
    FLOOR,
    CEILING,
    WALLS,
    WALLS_CEILING,
    WALLS_FLOOR,
    PERIMETER,
    MANUAL
}

data class RoomWorkTemplate(
    val code: String,
    val title: String,
    val description: String,
    val unit: String,
    val basis: QuantityBasis,
    val category: String,
    val enabledByDefault: Boolean,
    val references: List<String> = emptyList()
)

object RoomWorkCatalog {
    const val ITEM_PREFIX = "ROOM/"
    private const val BODY_CODE = "BODY"
    private const val META_MARKER = "[GE360_ROOM_META]"

    private fun cap(
        code: String,
        capCode: String,
        basis: QuantityBasis,
        enabled: Boolean,
        title: String? = null,
        unit: String? = null
    ): RoomWorkTemplate {
        val e = requireNotNull(StandardWorkCatalog.byCode(capCode)) { "Voce capitolato mancante: $capCode" }
        return RoomWorkTemplate(code, title ?: e.title, e.description, unit ?: e.unit, basis, e.group, enabled, e.references)
    }

    private fun custom(
        code: String,
        title: String,
        description: String,
        unit: String,
        basis: QuantityBasis,
        category: String,
        enabled: Boolean = false,
        refs: List<String> = emptyList()
    ) = RoomWorkTemplate(code, title, description, unit, basis, category, enabled, refs)

    private val commonStart = listOf(
        cap("CANTIERE", "CAP-01", QuantityBasis.BODY, true)
    )
    private val commonEnd = listOf(
        cap("PULIZIA", "CAP-42", QuantityBasis.BODY, true)
    )

    private val bathroom = commonStart + listOf(
        custom("DEM-RIV", "Rimozione rivestimenti murari", "Demolizione controllata dei rivestimenti murari previsti, rimozione dei residui incoerenti, raccolta progressiva del materiale e pulizia iniziale dei supporti.", "m²", QuantityBasis.WALLS, "Demolizioni e smaltimenti", true),
        custom("DEM-SAN", "Rimozione sanitari esistenti", "Rimozione dei sanitari e degli accessori previsti, movimentazione nell'area di cantiere e predisposizione del materiale per il successivo trasporto e smaltimento.", "corpo", QuantityBasis.BODY, "Demolizioni e smaltimenti", true),
        cap("SMALT", "CAP-03", QuantityBasis.BODY, true),
        cap("RIP-MUR", "CAP-04", QuantityBasis.WALLS, true),
        cap("TRACCE", "CAP-05", QuantityBasis.MANUAL, true),
        cap("RASATURA", "CAP-07", QuantityBasis.WALLS, true),
        cap("IMP", "CAP-20", QuantityBasis.MANUAL, true),
        cap("IMP-DOCCIA", "CAP-21", QuantityBasis.MANUAL, true),
        cap("PIASTRELLE", "CAP-14", QuantityBasis.WALLS_FLOOR, true),
        cap("STUCCO", "CAP-08", QuantityBasis.CEILING, true),
        cap("PITTURA", "CAP-09", QuantityBasis.CEILING, true),
        cap("SIGILL", "CAP-41", QuantityBasis.BODY, true),
        custom("DEM-VASCA", "Rimozione vasca esistente", "Rimozione controllata della vasca esistente e delle sole parti di rivestimento o muratura necessarie alla lavorazione; raccolta e preparazione della zona per le opere successive.", "corpo", QuantityBasis.BODY, "Demolizioni e smaltimenti"),
        custom("DEM-DOCCIA", "Rimozione doccia esistente", "Rimozione controllata del piatto doccia esistente e delle sole parti di rivestimento o muratura necessarie alla lavorazione; raccolta e preparazione della zona per le opere successive.", "corpo", QuantityBasis.BODY, "Demolizioni e smaltimenti"),
        custom("DEM-PAV", "Demolizione pavimento esistente", "Demolizione controllata delle piastrelle del pavimento, rimozione dei residui incoerenti e pulizia del fondo.", "m²", QuantityBasis.FLOOR, "Demolizioni e smaltimenti"),
        custom("POSA-SU-POSA", "Preparazione pavimento per posa su posa", "Verifica della stabilità delle piastrelle esistenti; pulizia e sgrassaggio accurati; rimozione di cere, detergenti, polvere e contaminanti; eventuale irruvidimento se previsto dal sistema; applicazione di primer o promotore di adesione/aggrappante chimico compatibile e rispetto dei tempi tecnici prima della posa.", "m²", QuantityBasis.FLOOR, "Pavimenti e rivestimenti", refs = listOf("UNI 11493-1:2025", "UNI EN 12004-1:2017")),
        cap("DOCCIA-FILO", "CAP-22", QuantityBasis.BODY, false),
        cap("PIATTO-MUR", "CAP-23", QuantityBasis.BODY, false),
        cap("NICCHIA", "CAP-24", QuantityBasis.BODY, false),
        cap("MURETTO-WC", "CAP-27", QuantityBasis.BODY, false),
        cap("SCALINO", "CAP-33", QuantityBasis.BODY, false),
        cap("CONTROSOFF", "CAP-34", QuantityBasis.CEILING, false),
        cap("GRANDE-FORMATO", "CAP-15", QuantityBasis.WALLS_FLOOR, false),
        cap("MOSAICO", "CAP-16", QuantityBasis.MANUAL, false),
        cap("TAGLI45", "CAP-17", QuantityBasis.MANUAL, false),
        cap("MUFFA", "CAP-10", QuantityBasis.MANUAL, false),
        cap("ISOLAMENTO", "CAP-37", QuantityBasis.MANUAL, false),
        cap("ACCESSO", "CAP-39", QuantityBasis.BODY, false),
        cap("SMALT-EXTRA", "CAP-40", QuantityBasis.BODY, false)
    ) + commonEnd

    private val kitchen = commonStart + listOf(
        custom("DEM-RIV", "Rimozione rivestimenti cucina", "Demolizione controllata dei rivestimenti murari previsti e pulizia iniziale del supporto.", "m²", QuantityBasis.WALLS, "Demolizioni e smaltimenti", true),
        cap("SMALT", "CAP-03", QuantityBasis.BODY, true),
        cap("RIP-MUR", "CAP-04", QuantityBasis.WALLS, true),
        cap("TRACCE", "CAP-05", QuantityBasis.MANUAL, true),
        cap("RASATURA", "CAP-07", QuantityBasis.WALLS, true),
        cap("RIVESTIMENTO", "CAP-14", QuantityBasis.MANUAL, true, "Posa rivestimento cucina"),
        cap("STUCCO", "CAP-08", QuantityBasis.WALLS_CEILING, true),
        cap("PITTURA", "CAP-09", QuantityBasis.WALLS_CEILING, true),
        custom("DEM-PAV", "Demolizione pavimento esistente", "Demolizione controllata della pavimentazione esistente, raccolta del materiale e pulizia del fondo.", "m²", QuantityBasis.FLOOR, "Demolizioni e smaltimenti"),
        custom("POSA-SU-POSA", "Preparazione pavimento per posa su posa", "Verifica di stabilità; pulizia e sgrassaggio; eventuale irruvidimento; applicazione di promotore di adesione/aggrappante chimico compatibile prima della posa.", "m²", QuantityBasis.FLOOR, "Pavimenti e rivestimenti", refs = listOf("UNI 11493-1:2025", "UNI EN 12004-1:2017")),
        cap("PAV-PIASTRELLE", "CAP-14", QuantityBasis.FLOOR, false, "Posa pavimento in piastrelle"),
        cap("PAV-SPC", "CAP-13", QuantityBasis.FLOOR, false),
        cap("BATTISCOPA", "CAP-18", QuantityBasis.PERIMETER, false),
        cap("GRANDE-FORMATO", "CAP-15", QuantityBasis.MANUAL, false),
        cap("TAGLI45", "CAP-17", QuantityBasis.MANUAL, false),
        cap("CARTONGESSO", "CAP-11", QuantityBasis.MANUAL, false),
        cap("PORTA", "CAP-31", QuantityBasis.BODY, false)
    ) + commonEnd

    private val room = commonStart + listOf(
        cap("STUCCO", "CAP-08", QuantityBasis.WALLS_CEILING, true),
        cap("PITTURA", "CAP-09", QuantityBasis.WALLS_CEILING, true),
        cap("RASATURA", "CAP-07", QuantityBasis.WALLS_CEILING, false),
        custom("RAS-ARM", "Rasatura armata con rete", "Preparazione del fondo, primo strato di rasante, inserimento di rete con corretta sovrapposizione, copertura, regolarizzazione e finitura.", "m²", QuantityBasis.WALLS_CEILING, "Pitture e finiture"),
        cap("RIP-MUR", "CAP-04", QuantityBasis.WALLS, false),
        custom("DEM-PAV", "Demolizione pavimento esistente", "Demolizione controllata della pavimentazione esistente, raccolta e pulizia del fondo.", "m²", QuantityBasis.FLOOR, "Demolizioni e smaltimenti"),
        custom("POSA-SU-POSA", "Preparazione per posa su posa", "Verifica, pulizia e sgrassaggio del pavimento esistente; eventuale irruvidimento e applicazione di promotore di adesione/aggrappante chimico compatibile.", "m²", QuantityBasis.FLOOR, "Pavimenti e rivestimenti"),
        cap("SPC", "CAP-13", QuantityBasis.FLOOR, false),
        cap("PIASTRELLE", "CAP-14", QuantityBasis.FLOOR, false),
        cap("BATTISCOPA", "CAP-18", QuantityBasis.PERIMETER, false),
        cap("CONTROSOFF", "CAP-11", QuantityBasis.CEILING, false, "Controsoffitto in cartongesso"),
        cap("CONTROPARETE", "CAP-29", QuantityBasis.MANUAL, false),
        cap("PORTA-SCORR", "CAP-31", QuantityBasis.BODY, false),
        cap("MUFFA", "CAP-10", QuantityBasis.MANUAL, false)
    ) + commonEnd

    private val balcony = commonStart + listOf(
        custom("DEM-PAV", "Demolizione pavimentazione esistente", "Demolizione controllata della pavimentazione esterna, raccolta del materiale e pulizia del fondo.", "m²", QuantityBasis.FLOOR, "Demolizioni e smaltimenti"),
        cap("SMALT", "CAP-03", QuantityBasis.BODY, false),
        custom("POSA-SU-POSA", "Preparazione per posa in sovrapposizione", "Verifica della pavimentazione esistente; pulizia e sgrassaggio; eventuale irruvidimento e applicazione di promotore di adesione compatibile prima della posa.", "m²", QuantityBasis.FLOOR, "Pavimenti e rivestimenti"),
        custom("PENDENZE", "Ripristino o formazione pendenze", "Regolarizzazione o formazione delle pendenze necessarie allo smaltimento delle acque con materiali compatibili con il ciclo successivo.", "m²", QuantityBasis.FLOOR, "Massetti e supporti"),
        cap("IMP", "CAP-20", QuantityBasis.FLOOR, false, "Impermeabilizzazione sotto pavimentazione"),
        cap("PIASTRELLE", "CAP-14", QuantityBasis.FLOOR, false, "Posa pavimentazione esterna"),
        cap("RIP-MUR", "CAP-04", QuantityBasis.WALLS, false),
        cap("PITTURA", "CAP-09", QuantityBasis.MANUAL, false),
        cap("SIGILL", "CAP-41", QuantityBasis.BODY, false),
        cap("ACCESSO", "CAP-39", QuantityBasis.BODY, false),
        cap("SMALT-EXTRA", "CAP-40", QuantityBasis.BODY, false)
    ) + commonEnd

    private val other = commonStart + listOf(
        cap("DEM", "CAP-02", QuantityBasis.MANUAL, false),
        cap("SMALT", "CAP-03", QuantityBasis.BODY, false),
        cap("RIP-MUR", "CAP-04", QuantityBasis.WALLS, false),
        cap("TRACCE", "CAP-05", QuantityBasis.MANUAL, false),
        cap("RASATURA", "CAP-07", QuantityBasis.WALLS_CEILING, false),
        cap("PITTURA", "CAP-09", QuantityBasis.WALLS_CEILING, false),
        cap("CARTONGESSO", "CAP-11", QuantityBasis.MANUAL, false),
        cap("PREP-PAV", "CAP-12", QuantityBasis.FLOOR, false),
        cap("SPC", "CAP-13", QuantityBasis.FLOOR, false),
        cap("PIASTRELLE", "CAP-14", QuantityBasis.FLOOR, false),
        cap("BATTISCOPA", "CAP-18", QuantityBasis.PERIMETER, false),
        cap("IMP", "CAP-20", QuantityBasis.MANUAL, false),
        cap("ACCESSO", "CAP-39", QuantityBasis.BODY, false)
    ) + commonEnd

    fun forType(type: String): List<RoomWorkTemplate> = when (type) {
        RoomTypes.BATHROOM -> bathroom
        RoomTypes.KITCHEN -> kitchen
        RoomTypes.ROOM -> room
        RoomTypes.BALCONY -> balcony
        else -> other
    }.distinctBy { it.code }

    fun itemCode(roomId: String, workCode: String) = "$ITEM_PREFIX$roomId/$workCode"
    fun bodyItemCode(roomId: String) = itemCode(roomId, BODY_CODE)
    fun isRoomWorkCode(code: String) = code.startsWith(ITEM_PREFIX)

    fun quantity(room: EstimateRoom, basis: QuantityBasis): Double = when (basis) {
        QuantityBasis.BODY -> 1.0
        QuantityBasis.FLOOR -> room.floorArea()
        QuantityBasis.CEILING -> room.ceilingArea()
        QuantityBasis.WALLS -> room.wallArea()
        QuantityBasis.WALLS_CEILING -> room.wallsPlusCeiling()
        QuantityBasis.WALLS_FLOOR -> room.wallsPlusFloor()
        QuantityBasis.PERIMETER -> room.perimeter()
        QuantityBasis.MANUAL -> 1.0
    }.let { if (it <= 0) 1.0 else it }

    fun sectionFor(room: EstimateRoom): String = when (room.type) {
        RoomTypes.BATHROOM -> "Bagno"
        RoomTypes.KITCHEN -> "Cucina"
        RoomTypes.BALCONY -> "Balcone/Terrazzo"
        RoomTypes.ROOM -> "Camera"
        else -> "Resto della casa"
    }

    fun referencesFor(items: List<EstimateItem>): List<String> {
        val refs = mutableListOf<String>()
        items.filter { isRoomWorkCode(it.priceListCode) }.forEach { item ->
            val workCode = item.priceListCode.substringAfterLast('/')
            val template = (bathroom + kitchen + room + balcony + other).firstOrNull { it.code == workCode }
            if (template != null) refs += template.references
        }
        return refs.distinct()
    }

    fun metadataJson(room: EstimateRoom, basis: QuantityBasis? = null): String = JSONObject().apply {
        put("id", room.ge360Id)
        put("estimateId", room.estimateGe360Id)
        put("name", room.name)
        put("type", room.type)
        put("length", room.length)
        put("width", room.width)
        put("height", room.height)
        put("pricingMode", room.pricingMode)
        put("bodyPrice", room.bodyPrice)
        put("selectedCodes", JSONArray(room.selectedCodes.toList()))
        if (basis != null) put("basis", basis.name)
    }.toString()

    fun attachMetadata(existingNote: String, room: EstimateRoom, basis: QuantityBasis? = null): String {
        val clean = existingNote.substringBefore(META_MARKER).trimEnd()
        return buildString {
            if (clean.isNotBlank()) append(clean).append('\n')
            append(META_MARKER).append(metadataJson(room, basis))
        }
    }

    fun parseMetadata(note: String): JSONObject? {
        val p = note.indexOf(META_MARKER)
        if (p < 0) return null
        return runCatching { JSONObject(note.substring(p + META_MARKER.length)) }.getOrNull()
    }
}

class EstimateRoomStore(context: Context) : SQLiteOpenHelper(context, "ge360_preventivi_rooms.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE rooms(
            ge360_id TEXT PRIMARY KEY,
            estimate_ge360_id TEXT NOT NULL,
            name TEXT NOT NULL,
            type TEXT NOT NULL,
            length REAL NOT NULL DEFAULT 0,
            width REAL NOT NULL DEFAULT 0,
            height REAL NOT NULL DEFAULT 0,
            pricing_mode TEXT NOT NULL DEFAULT 'A misura',
            body_price REAL NOT NULL DEFAULT 0,
            selected_codes TEXT NOT NULL DEFAULT '[]',
            sort_order INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX idx_room_estimate ON rooms(estimate_ge360_id, sort_order, updated_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun list(estimateGe360Id: String): MutableList<EstimateRoom> {
        if (estimateGe360Id.isBlank()) return mutableListOf()
        val out = mutableListOf<EstimateRoom>()
        readableDatabase.query("rooms", null, "estimate_ge360_id=?", arrayOf(estimateGe360Id), null, null, "sort_order,name").use { c ->
            while (c.moveToNext()) {
                val selected = mutableSetOf<String>()
                val arr = runCatching { JSONArray(c.getString(c.getColumnIndexOrThrow("selected_codes"))) }.getOrDefault(JSONArray())
                for (i in 0 until arr.length()) selected += arr.optString(i)
                out += EstimateRoom(
                    ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
                    estimateGe360Id = estimateGe360Id,
                    name = c.getString(c.getColumnIndexOrThrow("name")),
                    type = c.getString(c.getColumnIndexOrThrow("type")),
                    length = c.getDouble(c.getColumnIndexOrThrow("length")),
                    width = c.getDouble(c.getColumnIndexOrThrow("width")),
                    height = c.getDouble(c.getColumnIndexOrThrow("height")),
                    pricingMode = c.getString(c.getColumnIndexOrThrow("pricing_mode")),
                    bodyPrice = c.getDouble(c.getColumnIndexOrThrow("body_price")),
                    selectedCodes = selected,
                    sortOrder = c.getInt(c.getColumnIndexOrThrow("sort_order")),
                    updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
                )
            }
        }
        return out
    }

    fun save(room: EstimateRoom) {
        room.updatedAt = System.currentTimeMillis()
        val v = ContentValues().apply {
            put("ge360_id", room.ge360Id)
            put("estimate_ge360_id", room.estimateGe360Id)
            put("name", room.name)
            put("type", room.type)
            put("length", room.length)
            put("width", room.width)
            put("height", room.height)
            put("pricing_mode", room.pricingMode)
            put("body_price", room.bodyPrice)
            put("selected_codes", JSONArray(room.selectedCodes.toList()).toString())
            put("sort_order", room.sortOrder)
            put("updated_at", room.updatedAt)
        }
        writableDatabase.insertWithOnConflict("rooms", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun delete(roomId: String) {
        writableDatabase.delete("rooms", "ge360_id=?", arrayOf(roomId))
    }

    fun exportJson(): String = JSONObject().apply {
        put("schema", BACKUP_SCHEMA)
        put("exportedAt", System.currentTimeMillis())
        put("rooms", JSONArray().apply {
            all().forEach { room ->
                put(JSONObject().apply {
                    put("ge360Id", room.ge360Id)
                    put("estimateGe360Id", room.estimateGe360Id)
                    put("name", room.name)
                    put("type", room.type)
                    put("length", room.length)
                    put("width", room.width)
                    put("height", room.height)
                    put("pricingMode", room.pricingMode)
                    put("bodyPrice", room.bodyPrice)
                    put("selectedCodes", JSONArray(room.selectedCodes.toList()))
                    put("sortOrder", room.sortOrder)
                    put("updatedAt", room.updatedAt)
                })
            }
        })
    }.toString(2)

    fun importJson(json: String, replace: Boolean): Int {
        val root = JSONObject(json)
        require(root.optString("schema") == BACKUP_SCHEMA) { "Backup ambienti preventivo non compatibile" }
        val rows = root.optJSONArray("rooms") ?: JSONArray()
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (replace) db.delete("rooms", null, null)
            for (i in 0 until rows.length()) {
                val item = rows.getJSONObject(i)
                val selected = mutableSetOf<String>()
                item.optJSONArray("selectedCodes")?.let { codes ->
                    for (j in 0 until codes.length()) selected += codes.optString(j)
                }
                save(EstimateRoom(
                    ge360Id = item.optString("ge360Id").ifBlank { UUID.randomUUID().toString() },
                    estimateGe360Id = item.optString("estimateGe360Id"),
                    name = item.optString("name", "Stanza"),
                    type = item.optString("type", RoomTypes.OTHER),
                    length = item.optDouble("length", 0.0),
                    width = item.optDouble("width", 0.0),
                    height = item.optDouble("height", 0.0),
                    pricingMode = item.optString("pricingMode", RoomPricing.MEASURE),
                    bodyPrice = item.optDouble("bodyPrice", 0.0),
                    selectedCodes = selected,
                    sortOrder = item.optInt("sortOrder", i),
                    updatedAt = item.optLong("updatedAt", System.currentTimeMillis())
                ))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return rows.length()
    }

    private fun all(): List<EstimateRoom> {
        val estimateIds = linkedSetOf<String>()
        readableDatabase.query("rooms", arrayOf("estimate_ge360_id"), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) estimateIds += cursor.getString(0)
        }
        return estimateIds.flatMap(::list)
    }

    /** Ricostruisce le stanze dai metadati presenti nelle voci del preventivo dopo import/duplica. */
    fun hydrateFromEstimate(estimate: EstimateRecord): MutableList<EstimateRoom> {
        val existing = list(estimate.ge360Id)
        if (existing.isNotEmpty()) return existing
        val byId = linkedMapOf<String, EstimateRoom>()
        estimate.items.forEach { item ->
            val meta = RoomWorkCatalog.parseMetadata(item.internalNote) ?: return@forEach
            val id = meta.optString("id")
            if (id.isBlank()) return@forEach
            val selected = mutableSetOf<String>()
            meta.optJSONArray("selectedCodes")?.let { a -> for (i in 0 until a.length()) selected += a.optString(i) }
            byId[id] = EstimateRoom(
                ge360Id = id,
                estimateGe360Id = estimate.ge360Id,
                name = meta.optString("name", item.room.ifBlank { "Stanza" }),
                type = meta.optString("type", RoomTypes.OTHER),
                length = meta.optDouble("length", 0.0),
                width = meta.optDouble("width", 0.0),
                height = meta.optDouble("height", 0.0),
                pricingMode = meta.optString("pricingMode", RoomPricing.MEASURE),
                bodyPrice = meta.optDouble("bodyPrice", 0.0),
                selectedCodes = selected
            )
        }
        byId.values.forEachIndexed { index, room -> room.sortOrder = index; save(room) }
        return byId.values.toMutableList()
    }

    companion object {
        const val BACKUP_SCHEMA = "ge360.preventivi.rooms.v1"
    }
}

object RoomPlanner {
    fun openForEstimate(context: Context, estimate: EstimateRecord, onApplied: () -> Unit) {
        if (estimate.ge360Id.isBlank()) estimate.ge360Id = UUID.randomUUID().toString()
        val store = EstimateRoomStore(context)
        val rooms = store.hydrateFromEstimate(estimate)
        if (rooms.isEmpty() && estimate.workType != "Ristrutturazione intero appartamento") {
            val room = defaultRoom(estimate)
            room.estimateGe360Id = estimate.ge360Id
            room.sortOrder = 0
            editRoom(context, estimate, room, store, true) {
                store.close()
                onApplied()
            }
        } else {
            showRooms(context, estimate, store, onApplied)
        }
    }

    private fun defaultRoom(estimate: EstimateRecord): EstimateRoom = when (estimate.workType) {
        "Ristrutturazione bagno" -> EstimateRoom(name = "Bagno", type = RoomTypes.BATHROOM, pricingMode = RoomPricing.BODY)
        "Ristrutturazione cucina" -> EstimateRoom(name = "Cucina", type = RoomTypes.KITCHEN)
        "Terrazze e balconi" -> EstimateRoom(name = "Balcone/Terrazzo", type = RoomTypes.BALCONY)
        else -> EstimateRoom(name = estimate.title.ifBlank { "Stanza" }, type = RoomTypes.ROOM)
    }

    private fun showRooms(context: Context, estimate: EstimateRecord, store: EstimateRoomStore, onApplied: () -> Unit) {
        val rooms = store.hydrateFromEstimate(estimate)
        val labels = rooms.map { room ->
            "${room.name} · ${room.type}\n${room.measuresSummary()}\n${room.pricingMode}${if (room.pricingMode == RoomPricing.BODY) " · € ${fmtRoom(room.bodyPrice)}" else ""}"
        }.toTypedArray()
        val dialog = AlertDialog.Builder(context)
            .setTitle("Ambienti / zone del preventivo")
            .setMessage("Ogni ambiente ha misure, modalità prezzo e checklist indipendenti. Nessuna lavorazione è obbligatoria.")
            .setItems(labels) { _, index -> roomActions(context, estimate, rooms[index], store, onApplied) }
            .setPositiveButton("+ Aggiungi ambiente") { _, _ ->
                val r = EstimateRoom(estimateGe360Id = estimate.ge360Id, sortOrder = rooms.size)
                editRoom(context, estimate, r, store, true) { onApplied(); showRooms(context, estimate, store, onApplied) }
            }
            .setNegativeButton("Chiudi") { _, _ -> store.close() }
            .create()
        dialog.show()
    }

    private fun roomActions(context: Context, estimate: EstimateRecord, room: EstimateRoom, store: EstimateRoomStore, onApplied: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(room.name)
            .setItems(arrayOf("Modifica misure e checklist", "Elimina ambiente")) { _, which ->
                if (which == 0) {
                    editRoom(context, estimate, room, store, false) { onApplied(); showRooms(context, estimate, store, onApplied) }
                } else {
                    AlertDialog.Builder(context)
                        .setTitle("Elimina ${room.name}?")
                        .setMessage("Verranno rimosse solo le voci generate automaticamente da questo ambiente. Le voci manuali restano intatte.")
                        .setPositiveButton("Elimina") { _, _ ->
                            estimate.items.removeAll { it.priceListCode.startsWith("${RoomWorkCatalog.ITEM_PREFIX}${room.ge360Id}/") }
                            store.delete(room.ge360Id)
                            onApplied()
                            showRooms(context, estimate, store, onApplied)
                        }
                        .setNegativeButton("Annulla", null)
                        .show()
                }
            }
            .setNegativeButton("Indietro") { _, _ -> showRooms(context, estimate, store, onApplied) }
            .show()
    }

    private fun editRoom(
        context: Context,
        estimate: EstimateRecord,
        room: EstimateRoom,
        store: EstimateRoomStore,
        isNew: Boolean,
        onApplied: () -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun label(text: String) = TextView(context).apply {
            this.text = text
            setTypeface(typeface, Typeface.BOLD)
            textSize = 12f
            setPadding(0, dp(8), 0, dp(2))
        }
        fun decimal(value: Double, hint: String) = EditText(context).apply {
            this.hint = hint
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (value > 0) setText(fmtRoom(value))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(14))
        }
        val name = EditText(context).apply { hint = "Nome ambiente (es. Stanza 1)"; setText(room.name); isSingleLine = true }
        val type = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, RoomTypes.ALL)
            setSelection(RoomTypes.ALL.indexOf(room.type).coerceAtLeast(0))
        }
        val length = decimal(room.length, "Lunghezza m")
        val width = decimal(room.width, "Larghezza m")
        val height = decimal(room.height, "Altezza m")
        val pricing = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, RoomPricing.ALL)
            setSelection(RoomPricing.ALL.indexOf(room.pricingMode).coerceAtLeast(0))
        }
        val bodyPrice = decimal(room.bodyPrice, "Prezzo a corpo €")
        val measures = TextView(context).apply { textSize = 11f; setPadding(0, dp(6), 0, dp(8)) }

        root.addView(label("Ambiente")); root.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        root.addView(label("Tipo ambiente")); root.addView(type, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        root.addView(label("Misure interne"))
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(length, LinearLayout.LayoutParams(0, dp(50), 1f))
            addView(width, LinearLayout.LayoutParams(0, dp(50), 1f))
            addView(height, LinearLayout.LayoutParams(0, dp(50), 1f))
        })
        root.addView(measures)
        root.addView(label("Modalità prezzo")); root.addView(pricing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        root.addView(TextView(context).apply {
            text = "A corpo: una sola voce economica per l'ambiente; la checklist descrive cosa è compreso. A misura: le voci in m²/ml ricevono automaticamente le quantità calcolate dalle misure."
            textSize = 11f
        })
        root.addView(bodyPrice, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))

        fun parse(field: EditText): Double = field.text.toString().trim().replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        fun updateMeasures() {
            val tmp = room.copy(length = parse(length), width = parse(width), height = parse(height))
            measures.text = tmp.measuresSummary()
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateMeasures()
            override fun afterTextChanged(s: Editable?) = Unit
        }
        length.addTextChangedListener(watcher); width.addTextChangedListener(watcher); height.addTextChangedListener(watcher); updateMeasures()

        root.addView(label("Checklist lavorazioni"))
        root.addView(TextView(context).apply {
            text = "Checklist libera: puoi attivare o disattivare qualsiasi voce. Dopo l'applicazione puoi ancora modificare quantità, prezzo e descrizione della singola voce oppure aggiungerne una manuale."
            textSize = 11f
            setPadding(0, 0, 0, dp(6))
        })

        var currentTemplates = RoomWorkCatalog.forType(room.type)
        val checklistBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(checklistBox)
        val selected = room.selectedCodes.toMutableSet()
        fun renderChecklist(newType: String) {
            checklistBox.removeAllViews()
            currentTemplates = RoomWorkCatalog.forType(newType)
            currentTemplates.forEach { template ->
                checklistBox.addView(CheckBox(context).apply {
                    text = "${template.title} · ${basisLabel(template.basis)}"
                    isChecked = if (room.selectedCodes.isNotEmpty()) template.code in selected else template.enabledByDefault
                    setOnCheckedChangeListener { _, checked -> if (checked) selected += template.code else selected -= template.code }
                    tag = template.code
                })
            }
        }
        renderChecklist(room.type)
        type.setOnItemSelectedListener(SimpleSelected {
            val newType = type.selectedItem?.toString() ?: room.type
            renderChecklist(newType)
        })

        val dialog = AlertDialog.Builder(context)
            .setTitle(if (isNew) "Nuovo ambiente" else "Modifica ${room.name}")
            .setView(ScrollView(context).apply { addView(root) })
            .setPositiveButton("Applica", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (name.text.toString().trim().isBlank()) { name.error = "Inserisci un nome"; return@setOnClickListener }
                room.name = name.text.toString().trim()
                room.type = type.selectedItem.toString()
                room.length = parse(length)
                room.width = parse(width)
                room.height = parse(height)
                room.pricingMode = pricing.selectedItem.toString()
                room.bodyPrice = parse(bodyPrice)
                room.selectedCodes = selected.filter { code -> RoomWorkCatalog.forType(room.type).any { it.code == code } }.toMutableSet()
                if (isNew && room.selectedCodes.isEmpty()) {
                    room.selectedCodes = RoomWorkCatalog.forType(room.type).filter { it.enabledByDefault }.map { it.code }.toMutableSet()
                }
                store.save(room)
                syncRoomItems(estimate, room)
                dialog.dismiss()
                onApplied()
                Toast.makeText(context, "${room.name}: checklist aggiornata", Toast.LENGTH_LONG).show()
            }
        }
        dialog.show()
    }

    private fun syncRoomItems(estimate: EstimateRecord, room: EstimateRoom) {
        val prefix = "${RoomWorkCatalog.ITEM_PREFIX}${room.ge360Id}/"
        val existing = estimate.items.filter { it.priceListCode.startsWith(prefix) }.associateBy { it.priceListCode }
        val preserved = estimate.items.filterNot { it.priceListCode.startsWith(prefix) }.toMutableList()
        val templates = RoomWorkCatalog.forType(room.type).filter { it.code in room.selectedCodes }
        val section = RoomWorkCatalog.sectionFor(room)

        templates.forEach { template ->
            val code = RoomWorkCatalog.itemCode(room.ge360Id, template.code)
            val old = existing[code]
            val item = old ?: EstimateItem(
                ge360Id = UUID.randomUUID().toString(),
                priceListCode = code,
                section = section,
                room = room.name,
                category = template.category,
                name = template.title,
                description = template.descriptionWithReferences(),
                quantity = RoomWorkCatalog.quantity(room, template.basis),
                unit = template.unit,
                unitPrice = 0.0,
                priceMode = if (template.unit in listOf("corpo", "forfait")) "body" else "unit",
                included = true,
                optional = false
            )
            item.section = section
            item.room = room.name
            if (!item.internalNote.contains(SmartSurveyDraftV1.QUANTITY_SOURCE_MARKER)) item.quantity = RoomWorkCatalog.quantity(room, template.basis)
            item.unit = template.unit
            item.priceMode = if (template.unit in listOf("corpo", "forfait")) "body" else "unit"
            if (room.pricingMode == RoomPricing.BODY) item.unitPrice = 0.0
            item.internalNote = RoomWorkCatalog.attachMetadata(item.internalNote, room, template.basis)
            item.total = calculateItemTotal(item)
            preserved += item
        }

        if (room.pricingMode == RoomPricing.BODY) {
            val code = RoomWorkCatalog.bodyItemCode(room.ge360Id)
            val body = existing[code] ?: EstimateItem(
                ge360Id = UUID.randomUUID().toString(),
                priceListCode = code,
                category = "Prezzo ambiente",
                name = "${room.name} — prezzo a corpo",
                description = "Corrispettivo a corpo per le lavorazioni selezionate nella checklist dell'ambiente ${room.name}.",
                included = true,
                optional = false
            )
            body.section = section
            body.room = room.name
            body.name = "${room.name} — prezzo a corpo"
            body.quantity = 1.0
            body.unit = "corpo"
            body.priceMode = "body"
            body.unitPrice = room.bodyPrice
            body.internalNote = RoomWorkCatalog.attachMetadata(body.internalNote, room, QuantityBasis.BODY)
            body.total = calculateItemTotal(body)
            preserved += body
        }

        estimate.items.clear()
        estimate.items.addAll(preserved)
    }

    private fun RoomWorkTemplate.descriptionWithReferences(): String = buildString {
        append(description.trim())
        if (references.isNotEmpty()) append("\n\nRiferimenti tecnici, ove applicabili: ").append(references.joinToString("; ")).append('.')
    }

    private fun basisLabel(basis: QuantityBasis): String = when (basis) {
        QuantityBasis.BODY -> "a corpo"
        QuantityBasis.FLOOR -> "pavimento m²"
        QuantityBasis.CEILING -> "soffitto m²"
        QuantityBasis.WALLS -> "pareti m²"
        QuantityBasis.WALLS_CEILING -> "pareti + soffitto m²"
        QuantityBasis.WALLS_FLOOR -> "pareti + pavimento m²"
        QuantityBasis.PERIMETER -> "perimetro ml"
        QuantityBasis.MANUAL -> "quantità manuale"
    }

    private class SimpleSelected(private val block: () -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = block()
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }
}

private fun round2Room(v: Double) = round(v * 100.0) / 100.0
private fun fmtRoom(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.ITALY, "%.2f", v)
