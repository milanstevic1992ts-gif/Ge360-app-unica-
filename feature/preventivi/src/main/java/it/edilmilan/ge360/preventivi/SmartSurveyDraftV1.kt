package it.edilmilan.ge360.preventivi

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.round

/**
 * Rilievo di preventivazione indipendente dall'archivio commerciale:
 * solo un'esplicita conversione genera un EstimateRecord (sempre BOZZA).
 */
data class SmartSurveyRoomV1(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Ambiente",
    var type: String = RoomTypes.ROOM,
    var length: Double = 0.0,
    var width: Double = 0.0,
    var height: Double = 0.0,
    var floorManual: Double = 0.0,
    var wallsManual: Double = 0.0,
    var ceilingManual: Double = 0.0,
    var perimeterManual: Double = 0.0,
    var tilingHeight: Double = 0.0,
    var tilingWallsManual: Double = 0.0,
    var floorChoice: String = "da-decidere",
    var wetChoice: String = "da-decidere",
    var pricingMode: String = RoomPricing.MEASURE,
    var bodyPrice: Double = 0.0,
    val workCodes: MutableSet<String> = linkedSetOf(),
    val unitPrices: MutableMap<String, Double> = mutableMapOf(),
    val quantities: MutableMap<String, Double> = mutableMapOf(),
    val options: MutableSet<String> = linkedSetOf()
) {
    fun floor() = if (floorManual > 0) floorManual else if (length > 0 && width > 0) length * width else 0.0
    fun ceiling() = if (ceilingManual > 0) ceilingManual else floor()
    fun perimeter() = if (perimeterManual > 0) perimeterManual else if (length > 0 && width > 0) 2 * (length + width) else 0.0
    fun walls() = if (wallsManual > 0) wallsManual else if (height > 0) perimeter() * height else 0.0
    fun tilingWalls() = if (tilingWallsManual > 0) tilingWallsManual else if (tilingHeight > 0) perimeter() * tilingHeight else 0.0
    fun toRoom(estimateId: String, index: Int) = EstimateRoom(
        ge360Id = id, estimateGe360Id = estimateId,
        name = name.trim().ifBlank { "Ambiente ${index + 1}" }, type = type,
        length = length, width = width, height = height,
        pricingMode = pricingMode, bodyPrice = bodyPrice,
        selectedCodes = workCodes.toMutableSet(), sortOrder = index
    )
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("type", type)
        put("length", length); put("width", width); put("height", height)
        put("floorManual", floorManual); put("wallsManual", wallsManual)
        put("ceilingManual", ceilingManual); put("perimeterManual", perimeterManual)
        put("tilingHeight", tilingHeight); put("tilingWallsManual", tilingWallsManual)
        put("floorChoice", floorChoice); put("wetChoice", wetChoice)
        put("pricingMode", pricingMode); put("bodyPrice", bodyPrice)
        put("workCodes", JSONArray(workCodes.toList()))
        put("unitPrices", JSONObject().apply { unitPrices.forEach { (k, v) -> put(k, v) } })
        put("quantities", JSONObject().apply { quantities.forEach { (k, v) -> put(k, v) } })
        put("options", JSONArray(options.toList()))
    }
    companion object {
        fun fromJson(o: JSONObject): SmartSurveyRoomV1 {
            fun set(key: String) = linkedSetOf<String>().apply {
                o.optJSONArray(key)?.let { a -> for (i in 0 until a.length()) add(a.optString(i)) }
            }
            fun prices(key: String) = mutableMapOf<String, Double>().apply {
                o.optJSONObject(key)?.let { j -> j.keys().forEach { k ->
                    j.optDouble(k, 0.0).takeIf { it.isFinite() && it >= 0 }?.let { put(k, it) }
                } }
            }
            fun n(key: String) = o.optDouble(key, 0.0).let { if (it.isFinite() && it >= 0) it else 0.0 }
            return SmartSurveyRoomV1(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name", "Ambiente"),
                type = o.optString("type", RoomTypes.ROOM).takeIf { it in RoomTypes.ALL } ?: RoomTypes.OTHER,
                length = n("length"), width = n("width"), height = n("height"),
                floorManual = n("floorManual"), wallsManual = n("wallsManual"),
                ceilingManual = n("ceilingManual"), perimeterManual = n("perimeterManual"),
                tilingHeight = n("tilingHeight"), tilingWallsManual = n("tilingWallsManual"),
                floorChoice = o.optString("floorChoice", SmartSurveyDraftV1.UNKNOWN),
                wetChoice = o.optString("wetChoice", SmartSurveyDraftV1.UNKNOWN),
                pricingMode = o.optString("pricingMode", RoomPricing.MEASURE)
                    .takeIf { it in RoomPricing.ALL } ?: RoomPricing.MEASURE,
                bodyPrice = n("bodyPrice"), workCodes = set("workCodes"),
                unitPrices = prices("unitPrices"), quantities = prices("quantities"), options = set("options")
            )
        }
    }
}

data class SmartSurveySessionV1(
    val id: String = UUID.randomUUID().toString(),
    var step: Int = 0,
    var clientName: String = "",
    var siteName: String = "",
    var title: String = "",
    var workType: String = "Ristrutturazione bagno",
    var description: String = "",
    var materialsIncluded: String = "",
    var materialsClient: String = "",
    var exclusions: String = "",
    var finalNotes: String = "",
    var validityDays: Int = 30,
    var generatedEstimateId: String = "",
    val rooms: MutableList<SmartSurveyRoomV1> = mutableListOf(),
    val photoFiles: MutableList<String> = mutableListOf(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson() = JSONObject().apply {
        put("schema", SmartSurveyDraftV1.SCHEMA); put("id", id); put("step", step)
        put("clientName", clientName); put("siteName", siteName); put("title", title)
        put("workType", workType); put("description", description)
        put("materialsIncluded", materialsIncluded); put("materialsClient", materialsClient)
        put("exclusions", exclusions); put("finalNotes", finalNotes)
        put("validityDays", validityDays); put("generatedEstimateId", generatedEstimateId)
        put("rooms", JSONArray().apply { rooms.forEach { put(it.toJson()) } })
        put("photoFiles", JSONArray(photoFiles.toList()))
        put("updatedAt", updatedAt)
    }
    companion object {
        fun fromJson(raw: String): SmartSurveySessionV1 {
            val o = JSONObject(raw)
            require(o.optString("schema") == SmartSurveyDraftV1.SCHEMA) { "Versione dati non supportata" }
            val rooms = mutableListOf<SmartSurveyRoomV1>()
            o.optJSONArray("rooms")?.let { a ->
                for (i in 0 until a.length().coerceAtMost(100)) a.optJSONObject(i)?.let { rooms += SmartSurveyRoomV1.fromJson(it) }
            }
            val photoFiles = mutableListOf<String>()
            o.optJSONArray("photoFiles")?.let { a -> for (i in 0 until a.length().coerceAtMost(150)) photoFiles += a.optString(i) }
            return SmartSurveySessionV1(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                step = o.optInt("step", 0).coerceIn(0, 7),
                clientName = o.optString("clientName"), siteName = o.optString("siteName"),
                title = o.optString("title"), workType = o.optString("workType", "Altro"),
                description = o.optString("description"),
                materialsIncluded = o.optString("materialsIncluded"), materialsClient = o.optString("materialsClient"),
                exclusions = o.optString("exclusions"), finalNotes = o.optString("finalNotes"),
                validityDays = o.optInt("validityDays", 30).coerceIn(1, 365),
                generatedEstimateId = o.optString("generatedEstimateId"),
                rooms = rooms, photoFiles = photoFiles, updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}

object SmartSurveyDraftV1 {
    const val SCHEMA = "ge360.smart.sopralluogo.v1"
    const val UNKNOWN = "da-decidere"
    const val DEMOLISH = "demolizione"
    const val OVERLAY = "sovrapposizione"
    const val KEEP = "mantenere"
    const val BATH = "vasca"
    const val SHOWER = "doccia"
    const val NONE = "nessuna"
    val STEPS = listOf(
        "Cliente e intervento", "Ambienti", "Misure", "Demolizioni",
        "Impianti", "Finiture e prezzi", "Materiali e foto", "Controllo finale"
    )

    fun setFloor(room: SmartSurveyRoomV1, choice: String) {
        require(choice in setOf(UNKNOWN, DEMOLISH, OVERLAY, KEEP))
        room.floorChoice = choice
        room.workCodes.removeAll(setOf("DEM-PAV", "POSA-SU-POSA"))
        if (choice == DEMOLISH) room.workCodes += "DEM-PAV"
        if (choice == OVERLAY) room.workCodes += "POSA-SU-POSA"
    }
    fun setWet(room: SmartSurveyRoomV1, choice: String) {
        require(choice in setOf(UNKNOWN, BATH, SHOWER, NONE))
        room.wetChoice = choice
        room.workCodes.removeAll(setOf("DEM-VASCA", "DEM-DOCCIA"))
        if (choice == BATH) room.workCodes += "DEM-VASCA"
        if (choice == SHOWER) room.workCodes += "DEM-DOCCIA"
    }
    fun quantity(room: SmartSurveyRoomV1, template: RoomWorkTemplate): Double {
        room.quantities[template.code]?.let { return it }
        val result = when (template.basis) {
            QuantityBasis.BODY -> 1.0
            QuantityBasis.FLOOR -> room.floor()
            QuantityBasis.CEILING -> room.ceiling()
            QuantityBasis.WALLS -> room.walls()
            QuantityBasis.WALLS_CEILING -> room.walls() + room.ceiling()
            QuantityBasis.WALLS_FLOOR -> if (room.type == RoomTypes.BATHROOM && template.code == "PIASTRELLE") {
                if (room.tilingWalls() > 0) room.tilingWalls() + room.floor() else 0.0
            } else room.walls() + room.floor()
            QuantityBasis.PERIMETER -> room.perimeter()
            QuantityBasis.MANUAL -> 0.0 // nessuna quantità inventata
        }
        return if (result.isFinite() && result > 0) round(result * 100.0) / 100.0 else 0.0
    }
    fun phase(t: RoomWorkTemplate): Int = when {
        t.code in setOf("DEM-PAV", "DEM-VASCA", "DEM-DOCCIA", "POSA-SU-POSA") -> 3
        t.code.startsWith("DEM") || t.code == "SMALT" || t.category.contains("demol", true) -> 3
        t.code.startsWith("IMP") || t.code == "TRACCE" ||
            t.category.contains("impiant", true) || t.title.contains("idraulic", true) ||
            t.title.contains("elettric", true) -> 4
        else -> 5
    }
    fun checks(session: SmartSurveySessionV1): List<String> = buildList {
        if (session.clientName.isBlank()) add("Cliente da completare")
        if (session.title.isBlank()) add("Oggetto dei lavori da completare")
        if (session.rooms.isEmpty()) add("Aggiungi almeno un ambiente")
        val names = session.rooms.map { it.name.trim().lowercase() }
        if (names.distinct().size != names.size) add("Ambientazioni con nomi uguali: distinguerle")
        session.rooms.forEach { room ->
            if (room.floor() <= 0) add("${room.name}: pavimento non misurato")
            if (room.type != RoomTypes.BALCONY && room.walls() <= 0) add("${room.name}: pareti non misurate")
            if (room.type == RoomTypes.BATHROOM && "PIASTRELLE" in room.workCodes && room.tilingWalls() <= 0 && "PIASTRELLE" !in room.quantities)
                add("${room.name}: altezza rivestimenti o m² piastrelle da indicare")
            if (room.type == RoomTypes.BATHROOM) {
                if (room.floorChoice == UNKNOWN) add("${room.name}: confermare demolizione / sovrapposizione / mantenimento pavimento")
                if (room.wetChoice == UNKNOWN) add("${room.name}: confermare vasca / doccia / nessuna")
            }
            val catalog = RoomWorkCatalog.forType(room.type).associateBy { it.code }
            val invalid = room.workCodes - catalog.keys
            if (invalid.isNotEmpty()) add("${room.name}: lavorazioni non valide per il tipo di ambiente: " + invalid.joinToString(", "))
            room.workCodes.forEach workLoop@ { code ->
                val template = catalog[code] ?: return@workLoop
                if (quantity(room, template) <= 0) add("${room.name} · ${template.title}: quantità da inserire")
                if (room.pricingMode != RoomPricing.BODY && (room.unitPrices[code] ?: 0.0) <= 0) {
                    add("${room.name} · ${template.title}: prezzo mancante")
                }
            }
            if (room.pricingMode == RoomPricing.BODY && room.bodyPrice <= 0)
                add("${room.name}: prezzo a corpo mancante")
        }
        if (session.rooms.all { it.workCodes.isEmpty() }) add("Nessuna lavorazione selezionata")
    }.distinct()
    fun roomModels(session: SmartSurveySessionV1) = session.rooms.mapIndexed { index, r -> r.toRoom(session.id, index) }

    fun toEstimate(session: SmartSurveySessionV1): EstimateRecord {
        require(session.generatedEstimateId.isBlank()) { "Esiste già una bozza collegata" }
        require(session.rooms.isNotEmpty()) { "Aggiungi almeno un ambiente" }
        val record = EstimateRecord(
            ge360Id = session.id, clientName = session.clientName.trim(),
            jobsiteTitle = session.siteName.trim(),
            title = session.title.trim().ifBlank { "Sopralluogo" },
            workType = session.workType,
            status = EstimateStatuses.DRAFT, documentType = "Provvisorio",
            validityDays = session.validityDays, generalDescription = session.description.trim(),
            materialsIncluded = session.materialsIncluded.trim(),
            materialsClient = session.materialsClient.trim(),
            exclusions = session.exclusions.trim(), finalNotes = session.finalNotes.trim()
        )
        session.rooms.forEachIndexed { index, sr ->
            val room = sr.toRoom(record.ge360Id, index)
            val catalog = RoomWorkCatalog.forType(room.type).associateBy { it.code }
            require(sr.workCodes.all { it in catalog }) { "Lavorazioni non compatibili con ${room.name}: correggi il tipo di ambiente" }
            sr.workCodes.forEach { code ->
                val template = catalog.getValue(code)
                val body = room.pricingMode == RoomPricing.BODY
                val item = EstimateItem(
                    ge360Id = UUID.randomUUID().toString(),
                    priceListCode = RoomWorkCatalog.itemCode(room.ge360Id, code),
                    section = RoomWorkCatalog.sectionFor(room), room = room.name,
                    category = template.category, name = template.title,
                    description = template.description +
                        (if (template.code == "PIASTRELLE" && room.type == RoomTypes.BATHROOM)
                            "\nRivestimento pareti dichiarato: " + sr.tilingWalls().toString() +
                            " m²; pavimento: " + sr.floor().toString() +
                            " m²; altezza rivestimento: " + sr.tilingHeight.toString() + " m."
                        else "") +
                        (if (template.references.isEmpty()) "" else "\nRiferimenti da verificare: " +
                            template.references.joinToString("; ")),
                    quantity = quantity(sr, template), unit = template.unit,
                    unitPrice = if (body) 0.0 else sr.unitPrices[code] ?: 0.0,
                    priceMode = if (template.unit in listOf("corpo", "forfait")) "body" else "unit",
                    included = !body, optional = code in sr.options && !body,
                    internalNote = RoomWorkCatalog.attachMetadata("", room, template.basis)
                )
                record.items += item
            }
            if (room.pricingMode == RoomPricing.BODY) record.items += EstimateItem(
                ge360Id = UUID.randomUUID().toString(),
                priceListCode = RoomWorkCatalog.bodyItemCode(room.ge360Id),
                section = RoomWorkCatalog.sectionFor(room), room = room.name,
                category = "Prezzo ambiente", name = "${room.name} — prezzo a corpo",
                description = "Prezzo complessivo per le lavorazioni selezionate nell'ambiente.",
                quantity = 1.0, unit = "corpo", unitPrice = room.bodyPrice, priceMode = "body",
                included = true, internalNote = RoomWorkCatalog.attachMetadata("", room, QuantityBasis.BODY)
            )
        }
        recalculateEstimate(record)
        return record
    }
}
