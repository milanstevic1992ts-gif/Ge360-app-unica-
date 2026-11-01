package it.edilmilan.ge360.planimetrie

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

data class FloorPlanRecord(
    var id: Long = 0,
    var ge360Id: String = UUID.randomUUID().toString(),
    var title: String = "Rilievo",
    var kind: String = "Appartamento",
    var clientName: String = "",
    var notes: String = "",
    var jobsiteId: String = "",
    var estimateId: String = "",
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

object FloorPlanKinds {
    val ALL = listOf("Appartamento", "Bagno", "Cucina", "Locale", "Terrazzo/Balcone", "Altro")
}

object FloorPlanRoomModes {
    const val RECTANGULAR = "Rettangolare"
    const val L_SHAPE = "Forma a L"
    const val MANUAL = "Irregolare / misure manuali"
    const val WALLS = "Pareti quotate"
    val ALL = listOf(RECTANGULAR, L_SHAPE, MANUAL, WALLS)
}

data class FloorPlanRoom(
    var id: Long = 0,
    var ge360Id: String = UUID.randomUUID().toString(),
    var floorPlanId: String = "",
    var name: String = "Stanza",
    var roomType: String = "Stanza",
    var mode: String = FloorPlanRoomModes.RECTANGULAR,
    var length: Double = 0.0,
    var width: Double = 0.0,
    var height: Double = 0.0,
    var cutWidth: Double = 0.0,
    var cutHeight: Double = 0.0,
    var manualFloor: Double = 0.0,
    var manualCeiling: Double = 0.0,
    var manualWalls: Double = 0.0,
    var manualPerimeter: Double = 0.0,
    var drawX: Double = 0.0,
    var drawY: Double = 0.0,
    var drawWidth: Double = 0.0,
    var drawHeight: Double = 0.0,
    var sortOrder: Int = 0,
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun floorArea(): Double = when (mode) {
        FloorPlanRoomModes.WALLS -> manualFloor.coerceAtLeast(0.0)
        FloorPlanRoomModes.MANUAL -> manualFloor.takeIf { it > 0.0 } ?: round2(length * width)
        FloorPlanRoomModes.L_SHAPE ->
            round2((length * width - cutWidth.coerceAtMost(length) * cutHeight.coerceAtMost(width)).coerceAtLeast(0.0))
        else -> round2(length * width)
    }

    fun ceilingArea(): Double = when (mode) {
        FloorPlanRoomModes.WALLS -> manualCeiling.takeIf { it > 0.0 } ?: floorArea()
        FloorPlanRoomModes.MANUAL -> manualCeiling.takeIf { it > 0.0 } ?: floorArea()
        else -> floorArea()
    }

    fun perimeter(): Double = when (mode) {
        FloorPlanRoomModes.WALLS -> manualPerimeter.coerceAtLeast(0.0)
        FloorPlanRoomModes.MANUAL -> manualPerimeter.takeIf { it > 0.0 } ?: round2(2.0 * (length + width))
        else -> round2(2.0 * (length + width))
    }

    fun wallArea(): Double = when (mode) {
        FloorPlanRoomModes.WALLS -> manualWalls.takeIf { it > 0.0 } ?: round2(perimeter() * height)
        FloorPlanRoomModes.MANUAL -> manualWalls.takeIf { it > 0.0 } ?: round2(perimeter() * height)
        else -> round2(perimeter() * height)
    }

    fun footprintWidth(): Double = drawWidth.takeIf { it > 0.0 }
        ?: length.takeIf { it > 0.0 }
        ?: sqrt(max(floorArea(), 1.0))

    fun footprintHeight(): Double = drawHeight.takeIf { it > 0.0 }
        ?: width.takeIf { it > 0.0 }
        ?: sqrt(max(floorArea(), 1.0))

    fun summary(): String = buildString {
        append(mode).append(" · pavimento ").append(fmt(floorArea())).append(" m²")
        if (height > 0.0 || manualWalls > 0.0) append(" · pareti ").append(fmt(wallArea())).append(" m²")
        append(" · perimetro ").append(fmt(perimeter())).append(" ml")
    }
}

class FloorPlanStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE floor_plans(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL,
                kind TEXT NOT NULL,
                client_name TEXT NOT NULL DEFAULT '',
                notes TEXT NOT NULL DEFAULT '',
                jobsite_id TEXT NOT NULL DEFAULT '',
                estimate_id TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE floor_plan_rooms(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                floor_plan_id TEXT NOT NULL,
                name TEXT NOT NULL,
                room_type TEXT NOT NULL,
                mode TEXT NOT NULL,
                length REAL NOT NULL DEFAULT 0,
                width REAL NOT NULL DEFAULT 0,
                height REAL NOT NULL DEFAULT 0,
                cut_width REAL NOT NULL DEFAULT 0,
                cut_height REAL NOT NULL DEFAULT 0,
                manual_floor REAL NOT NULL DEFAULT 0,
                manual_ceiling REAL NOT NULL DEFAULT 0,
                manual_walls REAL NOT NULL DEFAULT 0,
                manual_perimeter REAL NOT NULL DEFAULT 0,
                draw_x REAL NOT NULL DEFAULT 0,
                draw_y REAL NOT NULL DEFAULT 0,
                draw_width REAL NOT NULL DEFAULT 0,
                draw_height REAL NOT NULL DEFAULT 0,
                sort_order INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_floor_plan_updated ON floor_plans(updated_at DESC)")
        db.execSQL("CREATE INDEX idx_floor_plan_rooms_plan ON floor_plan_rooms(floor_plan_id, sort_order, id)")
        createWallsTable(db)
        createBindingsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createWallsTable(db)
        if (oldVersion < 3) createBindingsTable(db)
        else if (oldVersion < 4) {
            db.execSQL("ALTER TABLE floor_plan_work_bindings ADD COLUMN geometry_fingerprint TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE floor_plan_work_bindings ADD COLUMN wall_ge360_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("DROP INDEX IF EXISTS idx_bindings_no_duplicate")
            createBindingIndexesV4(db)
        }
    }

    private fun createBindingIndexesV4(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bindings_plan ON floor_plan_work_bindings(plan_ge360_id)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_bindings_v4_unique ON floor_plan_work_bindings(plan_ge360_id,room_ge360_id,estimate_ge360_id,work_code,surface,room_revision_at,wall_ge360_id)")
    }

    private fun createBindingsTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS floor_plan_work_bindings (
            ge360_id TEXT PRIMARY KEY,
            plan_ge360_id TEXT NOT NULL,
            room_ge360_id TEXT NOT NULL,
            estimate_ge360_id TEXT NOT NULL,
            work_code TEXT NOT NULL,
            surface TEXT NOT NULL,
            quantity_m2 REAL NOT NULL,
            room_revision_at INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            geometry_fingerprint TEXT NOT NULL DEFAULT '',
            wall_ge360_id TEXT NOT NULL DEFAULT ''
        )""")
        createBindingIndexesV4(db)
    }

    private fun createWallsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS floor_plan_walls(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                floor_plan_id TEXT NOT NULL,
                room_id TEXT NOT NULL,
                length REAL NOT NULL DEFAULT 0,
                direction INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '',
                sort_order INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_floor_plan_walls_room ON floor_plan_walls(room_id, sort_order, id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_floor_plan_walls_plan ON floor_plan_walls(floor_plan_id, room_id)")
    }

    fun workBindings(planId: String): List<PlanWorkBindingV1> {
        val result = mutableListOf<PlanWorkBindingV1>()
        readableDatabase.query("floor_plan_work_bindings", null, "plan_ge360_id=?",
            arrayOf(planId), null, null, "created_at DESC").use { c ->
            while (c.moveToNext()) result += PlanWorkBindingV1(
                ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
                planGe360Id = c.getString(c.getColumnIndexOrThrow("plan_ge360_id")),
                roomGe360Id = c.getString(c.getColumnIndexOrThrow("room_ge360_id")),
                estimateGe360Id = c.getString(c.getColumnIndexOrThrow("estimate_ge360_id")),
                workCode = c.getString(c.getColumnIndexOrThrow("work_code")),
                surface = c.getString(c.getColumnIndexOrThrow("surface")),
                quantityM2 = c.getDouble(c.getColumnIndexOrThrow("quantity_m2")),
                roomRevisionAt = c.getLong(c.getColumnIndexOrThrow("room_revision_at")),
                createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                geometryFingerprint = c.getString(c.getColumnIndexOrThrow("geometry_fingerprint")),
                wallGe360Id = c.getString(c.getColumnIndexOrThrow("wall_ge360_id")).ifBlank { null }
            )
        }
        return result
    }

    private fun bindingValues(b: PlanWorkBindingV1) = ContentValues().apply {
        put("ge360_id", b.ge360Id); put("plan_ge360_id", b.planGe360Id)
        put("room_ge360_id", b.roomGe360Id); put("estimate_ge360_id", b.estimateGe360Id)
        put("work_code", b.workCode); put("surface", b.surface)
        put("quantity_m2", b.quantityM2); put("room_revision_at", b.roomRevisionAt)
        put("created_at", b.createdAt)
        put("geometry_fingerprint", b.geometryFingerprint)
        put("wall_ge360_id", b.wallGe360Id.orEmpty())
    }

    fun addWorkBindings(rows: List<PlanWorkBindingV1>) {
        require(rows.isNotEmpty() && rows.distinctBy { it.ge360Id }.size == rows.size)
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { b ->
                require(b.valid()) { "Collegamento non valido" }
                require(get(b.planGe360Id) != null) { "Rilievo non trovato" }
                val room = rooms(b.planGe360Id).singleOrNull { it.ge360Id == b.roomGe360Id }
                    ?: error("Ambiente non trovato")
                require(b.geometryFingerprint.isNotBlank()) {
                    "Selezione senza impronta geometrica: ricalcola dal rilievo"
                }
                require(room.updatedAt == b.roomRevisionAt &&
                    PlanGeometryQuantitiesV2.matches(b, room, walls(room.ge360Id))) {
                    "Stanza, muri o aperture modificati: ricontrollare le superfici"
                }
                require(workBindings(b.planGe360Id).none { old ->
                    old.roomGe360Id == b.roomGe360Id &&
                        old.estimateGe360Id == b.estimateGe360Id &&
                        old.workCode == b.workCode && old.surface == b.surface &&
                        old.wallGe360Id.orEmpty() == b.wallGe360Id.orEmpty() &&
                        old.geometryFingerprint == b.geometryFingerprint
                }) { "Questa lavorazione è già collegata alla medesima geometria" }
                db.insertOrThrow("floor_plan_work_bindings", null, bindingValues(b))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun list(): List<FloorPlanRecord> {
        val out = mutableListOf<FloorPlanRecord>()
        readableDatabase.query("floor_plans", null, null, null, null, null, "updated_at DESC").use { c ->
            while (c.moveToNext()) out += FloorPlanRecord(
                id = c.getLong(c.getColumnIndexOrThrow("id")),
                ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
                title = c.getString(c.getColumnIndexOrThrow("title")),
                kind = c.getString(c.getColumnIndexOrThrow("kind")),
                clientName = c.getString(c.getColumnIndexOrThrow("client_name")),
                notes = c.getString(c.getColumnIndexOrThrow("notes")),
                jobsiteId = c.getString(c.getColumnIndexOrThrow("jobsite_id")),
                estimateId = c.getString(c.getColumnIndexOrThrow("estimate_id")),
                createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
            )
        }
        return out
    }

    fun get(ge360Id: String): FloorPlanRecord? = readableDatabase.query(
        "floor_plans", null, "ge360_id=?", arrayOf(ge360Id), null, null, null, "1"
    ).use { c ->
        if (!c.moveToFirst()) null else FloorPlanRecord(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
            title = c.getString(c.getColumnIndexOrThrow("title")),
            kind = c.getString(c.getColumnIndexOrThrow("kind")),
            clientName = c.getString(c.getColumnIndexOrThrow("client_name")),
            notes = c.getString(c.getColumnIndexOrThrow("notes")),
            jobsiteId = c.getString(c.getColumnIndexOrThrow("jobsite_id")),
            estimateId = c.getString(c.getColumnIndexOrThrow("estimate_id")),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
        )
    }

    fun save(record: FloorPlanRecord) {
        val now = System.currentTimeMillis()
        if (record.ge360Id.isBlank()) record.ge360Id = UUID.randomUUID().toString()
        val existing = get(record.ge360Id)
        if (existing != null && record.id <= 0L) record.id = existing.id
        if (record.createdAt <= 0L) record.createdAt = existing?.createdAt ?: now
        record.updatedAt = now
        val values = ContentValues().apply {
            put("ge360_id", record.ge360Id)
            put("title", record.title.trim().ifBlank { "Rilievo" })
            put("kind", record.kind)
            put("client_name", record.clientName.trim())
            put("notes", record.notes.trim())
            put("jobsite_id", record.jobsiteId)
            put("estimate_id", record.estimateId)
            put("created_at", record.createdAt)
            put("updated_at", record.updatedAt)
        }
        record.id = if (record.id > 0L) {
            writableDatabase.update("floor_plans", values, "id=?", arrayOf(record.id.toString()))
            record.id
        } else {
            writableDatabase.insertOrThrow("floor_plans", null, values)
        }
    }

    fun delete(record: FloorPlanRecord) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("floor_plan_work_bindings", "plan_ge360_id=?", arrayOf(record.ge360Id))
            db.delete("floor_plan_walls", "floor_plan_id=?", arrayOf(record.ge360Id))
            db.delete("floor_plan_rooms", "floor_plan_id=?", arrayOf(record.ge360Id))
            db.delete("floor_plans", "ge360_id=?", arrayOf(record.ge360Id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun rooms(floorPlanId: String): MutableList<FloorPlanRoom> {
        val out = mutableListOf<FloorPlanRoom>()
        readableDatabase.query("floor_plan_rooms", null, "floor_plan_id=?", arrayOf(floorPlanId), null, null, "sort_order,id").use { c ->
            while (c.moveToNext()) out += FloorPlanRoom(
                id = c.getLong(c.getColumnIndexOrThrow("id")),
                ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
                floorPlanId = c.getString(c.getColumnIndexOrThrow("floor_plan_id")),
                name = c.getString(c.getColumnIndexOrThrow("name")),
                roomType = c.getString(c.getColumnIndexOrThrow("room_type")),
                mode = c.getString(c.getColumnIndexOrThrow("mode")),
                length = c.getDouble(c.getColumnIndexOrThrow("length")),
                width = c.getDouble(c.getColumnIndexOrThrow("width")),
                height = c.getDouble(c.getColumnIndexOrThrow("height")),
                cutWidth = c.getDouble(c.getColumnIndexOrThrow("cut_width")),
                cutHeight = c.getDouble(c.getColumnIndexOrThrow("cut_height")),
                manualFloor = c.getDouble(c.getColumnIndexOrThrow("manual_floor")),
                manualCeiling = c.getDouble(c.getColumnIndexOrThrow("manual_ceiling")),
                manualWalls = c.getDouble(c.getColumnIndexOrThrow("manual_walls")),
                manualPerimeter = c.getDouble(c.getColumnIndexOrThrow("manual_perimeter")),
                drawX = c.getDouble(c.getColumnIndexOrThrow("draw_x")),
                drawY = c.getDouble(c.getColumnIndexOrThrow("draw_y")),
                drawWidth = c.getDouble(c.getColumnIndexOrThrow("draw_width")),
                drawHeight = c.getDouble(c.getColumnIndexOrThrow("draw_height")),
                sortOrder = c.getInt(c.getColumnIndexOrThrow("sort_order")),
                updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
            )
        }
        return out
    }

    fun saveRoom(room: FloorPlanRoom) {
        if (room.ge360Id.isBlank()) room.ge360Id = UUID.randomUUID().toString()
        room.updatedAt = System.currentTimeMillis()
        if (room.drawWidth <= 0.0) room.drawWidth = room.footprintWidth()
        if (room.drawHeight <= 0.0) room.drawHeight = room.footprintHeight()
        val v = ContentValues().apply {
            put("ge360_id", room.ge360Id)
            put("floor_plan_id", room.floorPlanId)
            put("name", room.name.trim().ifBlank { "Stanza" })
            put("room_type", room.roomType)
            put("mode", room.mode)
            put("length", room.length.coerceAtLeast(0.0))
            put("width", room.width.coerceAtLeast(0.0))
            put("height", room.height.coerceAtLeast(0.0))
            put("cut_width", room.cutWidth.coerceAtLeast(0.0))
            put("cut_height", room.cutHeight.coerceAtLeast(0.0))
            put("manual_floor", room.manualFloor.coerceAtLeast(0.0))
            put("manual_ceiling", room.manualCeiling.coerceAtLeast(0.0))
            put("manual_walls", room.manualWalls.coerceAtLeast(0.0))
            put("manual_perimeter", room.manualPerimeter.coerceAtLeast(0.0))
            put("draw_x", room.drawX.coerceAtLeast(0.0))
            put("draw_y", room.drawY.coerceAtLeast(0.0))
            put("draw_width", room.drawWidth.coerceAtLeast(0.0))
            put("draw_height", room.drawHeight.coerceAtLeast(0.0))
            put("sort_order", room.sortOrder)
            put("updated_at", room.updatedAt)
        }
        writableDatabase.insertWithOnConflict("floor_plan_rooms", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteRoom(roomId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("floor_plan_walls", "room_id=?", arrayOf(roomId))
            db.delete("floor_plan_rooms", "ge360_id=?", arrayOf(roomId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun walls(roomId: String): MutableList<FloorPlanWall> {
        val out = mutableListOf<FloorPlanWall>()
        readableDatabase.query("floor_plan_walls", null, "room_id=?", arrayOf(roomId), null, null, "sort_order,id").use { c ->
            while (c.moveToNext()) out += FloorPlanWall(
                id = c.getLong(c.getColumnIndexOrThrow("id")),
                ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
                floorPlanId = c.getString(c.getColumnIndexOrThrow("floor_plan_id")),
                roomId = c.getString(c.getColumnIndexOrThrow("room_id")),
                length = c.getDouble(c.getColumnIndexOrThrow("length")),
                direction = c.getInt(c.getColumnIndexOrThrow("direction")),
                note = c.getString(c.getColumnIndexOrThrow("note")),
                sortOrder = c.getInt(c.getColumnIndexOrThrow("sort_order")),
                updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
            )
        }
        return out
    }

    fun saveWall(wall: FloorPlanWall) {
        if (wall.ge360Id.isBlank()) wall.ge360Id = UUID.randomUUID().toString()
        wall.updatedAt = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("ge360_id", wall.ge360Id)
            put("floor_plan_id", wall.floorPlanId)
            put("room_id", wall.roomId)
            put("length", wall.length.coerceAtLeast(0.0))
            put("direction", WallDirections.normalized(wall.direction))
            put("note", wall.note.trim())
            put("sort_order", wall.sortOrder)
            put("updated_at", wall.updatedAt)
        }
        writableDatabase.insertWithOnConflict("floor_plan_walls", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteWall(wallId: String) {
        writableDatabase.delete("floor_plan_walls", "ge360_id=?", arrayOf(wallId))
    }

    fun clearWalls(roomId: String) {
        writableDatabase.delete("floor_plan_walls", "room_id=?", arrayOf(roomId))
    }

    fun replaceWalls(room: FloorPlanRoom, rows: List<FloorPlanWall>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("floor_plan_walls", "room_id=?", arrayOf(room.ge360Id))
            rows.forEachIndexed { index, source ->
                val wall = source.copy(
                    floorPlanId = room.floorPlanId,
                    roomId = room.ge360Id,
                    sortOrder = index,
                    updatedAt = System.currentTimeMillis()
                )
                val values = ContentValues().apply {
                    put("ge360_id", wall.ge360Id)
                    put("floor_plan_id", wall.floorPlanId)
                    put("room_id", wall.roomId)
                    put("length", wall.length.coerceAtLeast(0.0))
                    put("direction", WallDirections.normalized(wall.direction))
                    put("note", wall.note.trim())
                    put("sort_order", wall.sortOrder)
                    put("updated_at", wall.updatedAt)
                }
                db.insertWithOnConflict("floor_plan_walls", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }

        if (rows.isEmpty()) {
            room.mode = FloorPlanRoomModes.WALLS
            room.manualFloor = 0.0
            room.manualCeiling = 0.0
            room.manualWalls = 0.0
            room.manualPerimeter = 0.0
            saveRoom(room)
        } else {
            syncRoomFromWalls(room)
        }
    }

    fun syncRoomFromWalls(room: FloorPlanRoom): FloorPlanRoom {
        val rows = walls(room.ge360Id)
        if (rows.isEmpty()) return room
        val bounds = FloorPlanWallGeometry.bounds(rows)
        val area = FloorPlanWallGeometry.area(rows)
        val perimeter = FloorPlanWallGeometry.perimeter(rows)
        room.mode = FloorPlanRoomModes.WALLS
        room.manualFloor = area
        room.manualCeiling = area
        room.manualPerimeter = perimeter
        room.manualWalls = if (room.height > 0.0) round2(perimeter * room.height) else 0.0
        room.length = bounds.width
        room.width = bounds.height
        room.drawWidth = bounds.width.coerceAtLeast(0.5)
        room.drawHeight = bounds.height.coerceAtLeast(0.5)
        saveRoom(room)
        return room
    }

    /** Backup portabile del rilievo e delle sue geometrie/quantità. */
    fun exportJson(): String = JSONObject().apply {
        put("schema", BACKUP_SCHEMA)
        put("exportedAt", System.currentTimeMillis())
        put("workBindings", JSONArray().apply {
            list().forEach { p -> workBindings(p.ge360Id).forEach { put(it.toJson()) } }
        })
        put("floorPlans", JSONArray().apply {
            list().forEach { plan ->
                put(JSONObject().apply {
                    put("ge360Id", plan.ge360Id)
                    put("title", plan.title)
                    put("kind", plan.kind)
                    put("clientName", plan.clientName)
                    put("notes", plan.notes)
                    put("jobsiteId", plan.jobsiteId)
                    put("estimateId", plan.estimateId)
                    put("createdAt", plan.createdAt)
                    put("updatedAt", plan.updatedAt)
                    put("rooms", JSONArray().apply {
                        rooms(plan.ge360Id).forEach { room -> put(roomToJson(room)) }
                    })
                })
            }
        })
    }.toString(2)

    /** Merge per ge360Id oppure sostituzione completa, come gli altri moduli GE360. */
    fun importJson(json: String, replaceAll: Boolean): Int {
        val root = JSONObject(json)
        require(root.optString("schema") == BACKUP_SCHEMA) { "Backup Planimetrie non compatibile" }
        val rows = root.optJSONArray("floorPlans") ?: JSONArray()
        val bindingRows = root.optJSONArray("workBindings")
        val incomingBindings = if (bindingRows == null) null else
            (0 until bindingRows.length()).map {
                PlanWorkBindingV1.fromJson(bindingRows.getJSONObject(it))
            }.also {
                require(it.all { item -> item.valid() })
                require(it.distinctBy { item -> item.ge360Id }.size == it.size)
            }
        if (replaceAll) {
            if (incomingBindings != null) writableDatabase.delete("floor_plan_work_bindings", null, null)
            clearAll()
        }
        var count = 0
        for (i in 0 until rows.length()) {
            val o = rows.optJSONObject(i) ?: continue
            val planId = o.optString("ge360Id").ifBlank { UUID.randomUUID().toString() }
            val existing = get(planId)
            val plan = FloorPlanRecord(
                id = existing?.id ?: 0L,
                ge360Id = planId,
                title = o.optString("title", "Rilievo"),
                kind = o.optString("kind", "Appartamento"),
                clientName = o.optString("clientName", ""),
                notes = o.optString("notes", ""),
                jobsiteId = o.optString("jobsiteId", ""),
                estimateId = o.optString("estimateId", ""),
                createdAt = o.optLong("createdAt", existing?.createdAt ?: System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
            save(plan)
            val incomingRoomIds = mutableSetOf<String>()
            val roomRows = o.optJSONArray("rooms") ?: JSONArray()
            for (j in 0 until roomRows.length()) {
                val ro = roomRows.optJSONObject(j) ?: continue
                val room = roomFromJson(ro, planId)
                incomingRoomIds += room.ge360Id
                saveRoom(room)
                val wallRows = ro.optJSONArray("walls")
                if (wallRows != null) {
                    val parsed = mutableListOf<FloorPlanWall>()
                    for (k in 0 until wallRows.length()) {
                        wallRows.optJSONObject(k)?.let { parsed += wallFromJson(it, planId, room.ge360Id, k) }
                    }
                    replaceWalls(room, parsed)
                }
            }
            if (replaceAll) {
                rooms(planId).filterNot { it.ge360Id in incomingRoomIds }.forEach { deleteRoom(it.ge360Id) }
            }
            count++
        }
        if (incomingBindings != null) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                incomingBindings.forEach { incoming ->
                    require(get(incoming.planGe360Id) != null) { "Rilievo collegato non presente" }
                    val old = workBindings(incoming.planGe360Id)
                        .singleOrNull { it.ge360Id == incoming.ge360Id }
                    if (old != null) require(old == incoming) { "Conflitto UUID collegamento rilievo" }
                    else db.insertOrThrow("floor_plan_work_bindings", null, bindingValues(incoming))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        return count
    }

    private fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("floor_plan_walls", null, null)
            db.delete("floor_plan_rooms", null, null)
            db.delete("floor_plans", null, null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun roomToJson(room: FloorPlanRoom) = JSONObject().apply {
        put("ge360Id", room.ge360Id)
        put("name", room.name)
        put("roomType", room.roomType)
        put("mode", room.mode)
        put("length", room.length)
        put("width", room.width)
        put("height", room.height)
        put("cutWidth", room.cutWidth)
        put("cutHeight", room.cutHeight)
        put("manualFloor", room.manualFloor)
        put("manualCeiling", room.manualCeiling)
        put("manualWalls", room.manualWalls)
        put("manualPerimeter", room.manualPerimeter)
        put("drawX", room.drawX)
        put("drawY", room.drawY)
        put("drawWidth", room.drawWidth)
        put("drawHeight", room.drawHeight)
        put("sortOrder", room.sortOrder)
        put("updatedAt", room.updatedAt)
        put("walls", JSONArray().apply { walls(room.ge360Id).forEach { put(wallToJson(it)) } })
    }

    private fun roomFromJson(o: JSONObject, planId: String) = FloorPlanRoom(
        ge360Id = o.optString("ge360Id").ifBlank { UUID.randomUUID().toString() },
        floorPlanId = planId,
        name = o.optString("name", "Stanza"),
        roomType = o.optString("roomType", "Stanza"),
        mode = o.optString("mode", FloorPlanRoomModes.RECTANGULAR).takeIf { it in FloorPlanRoomModes.ALL } ?: FloorPlanRoomModes.RECTANGULAR,
        length = o.optDouble("length", 0.0),
        width = o.optDouble("width", 0.0),
        height = o.optDouble("height", 0.0),
        cutWidth = o.optDouble("cutWidth", 0.0),
        cutHeight = o.optDouble("cutHeight", 0.0),
        manualFloor = o.optDouble("manualFloor", 0.0),
        manualCeiling = o.optDouble("manualCeiling", 0.0),
        manualWalls = o.optDouble("manualWalls", 0.0),
        manualPerimeter = o.optDouble("manualPerimeter", 0.0),
        drawX = o.optDouble("drawX", 0.0),
        drawY = o.optDouble("drawY", 0.0),
        drawWidth = o.optDouble("drawWidth", 0.0),
        drawHeight = o.optDouble("drawHeight", 0.0),
        sortOrder = o.optInt("sortOrder", 0),
        updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun wallToJson(wall: FloorPlanWall) = JSONObject().apply {
        put("ge360Id", wall.ge360Id)
        put("length", wall.length)
        put("direction", WallDirections.normalized(wall.direction))
        put("note", wall.note)
        put("sortOrder", wall.sortOrder)
        put("updatedAt", wall.updatedAt)
    }

    private fun wallFromJson(o: JSONObject, planId: String, roomId: String, fallbackOrder: Int) = FloorPlanWall(
        ge360Id = o.optString("ge360Id").ifBlank { UUID.randomUUID().toString() },
        floorPlanId = planId,
        roomId = roomId,
        length = o.optDouble("length", 0.0),
        direction = WallDirections.normalized(o.optInt("direction", WallDirections.EAST)),
        note = o.optString("note", ""),
        sortOrder = o.optInt("sortOrder", fallbackOrder),
        updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    companion object {
        private const val DB_NAME = "ge360_rilievi.db"
        private const val DB_VERSION = 4
        const val BACKUP_SCHEMA = "ge360.planimetrie.v1"
    }
}

private fun round2(v: Double): Double = round(v * 100.0) / 100.0
internal fun fmt(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(java.util.Locale.ITALY, "%.2f", v)
