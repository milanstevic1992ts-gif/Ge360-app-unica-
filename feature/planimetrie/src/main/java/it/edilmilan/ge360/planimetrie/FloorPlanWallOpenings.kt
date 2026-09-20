package it.edilmilan.ge360.planimetrie

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Aperture grafiche associate ad una parete quotata.
 *
 * La loro presenza NON modifica area, perimetro o m² pareti: in GE360 le
 * superfici murarie restano lorde, comprese porte e finestre.
 */
data class FloorPlanOpening(
    val id: String = UUID.randomUUID().toString(),
    val type: String = OpeningTypes.DOOR,
    val offset: Double = 0.0,
    val width: Double = 0.8,
    val height: Double = 2.1,
    val sillHeight: Double = 0.0,
    val swing: String = DoorSwings.NONE,
    val note: String = ""
) {
    fun label(): String = buildString {
        append(if (type == OpeningTypes.WINDOW) "Finestra" else "Porta")
        append(" · ").append(fmt(width)).append(" m")
        append(" · da ").append(fmt(offset)).append(" m")
        if (type == OpeningTypes.WINDOW && sillHeight > 0.0) append(" · davanzale ").append(fmt(sillHeight)).append(" m")
        if (type == OpeningTypes.DOOR && swing != DoorSwings.NONE) append(" · ").append(swing)
        if (note.isNotBlank()) append(" · ").append(note)
    }
}

object OpeningTypes {
    const val DOOR = "Porta"
    const val WINDOW = "Finestra"
    val ALL = listOf(DOOR, WINDOW)
}

object DoorSwings {
    const val NONE = "Apertura non indicata"
    const val LEFT = "Anta a sinistra"
    const val RIGHT = "Anta a destra"
    val ALL = listOf(NONE, LEFT, RIGHT)
}

/**
 * Le aperture vengono salvate dentro il metadato della parete. In questo modo
 * seguono automaticamente la parete nei backup ge360.planimetrie.v1 senza una
 * migrazione distruttiva del database.
 */
object FloorPlanWallOpenings {
    const val META_MARKER = "[GE360_OPENINGS]"

    fun userNote(raw: String): String = raw.substringBefore(META_MARKER).trimEnd()

    fun openings(raw: String): List<FloorPlanOpening> {
        val at = raw.indexOf(META_MARKER)
        if (at < 0) return emptyList()
        val json = raw.substring(at + META_MARKER.length).trim()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val type = o.optString("type", OpeningTypes.DOOR).takeIf { it in OpeningTypes.ALL } ?: OpeningTypes.DOOR
                add(
                    FloorPlanOpening(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        type = type,
                        offset = o.optDouble("offset", 0.0).coerceAtLeast(0.0),
                        width = o.optDouble("width", if (type == OpeningTypes.WINDOW) 1.2 else 0.8).coerceAtLeast(0.01),
                        height = o.optDouble("height", if (type == OpeningTypes.WINDOW) 1.4 else 2.1).coerceAtLeast(0.0),
                        sillHeight = o.optDouble("sillHeight", 0.0).coerceAtLeast(0.0),
                        swing = o.optString("swing", DoorSwings.NONE).takeIf { it in DoorSwings.ALL } ?: DoorSwings.NONE,
                        note = o.optString("note", "")
                    )
                )
            }
        }
    }

    fun attach(userNote: String, openings: List<FloorPlanOpening>): String = buildString {
        val clean = userNote.trim()
        if (clean.isNotBlank()) append(clean)
        if (openings.isNotEmpty()) {
            if (isNotEmpty()) append('\n')
            append(META_MARKER)
            append(JSONArray().apply {
                openings.forEach { opening ->
                    put(JSONObject().apply {
                        put("id", opening.id)
                        put("type", opening.type)
                        put("offset", opening.offset.coerceAtLeast(0.0))
                        put("width", opening.width.coerceAtLeast(0.01))
                        put("height", opening.height.coerceAtLeast(0.0))
                        put("sillHeight", opening.sillHeight.coerceAtLeast(0.0))
                        put("swing", opening.swing)
                        put("note", opening.note.trim())
                    })
                }
            }.toString())
        }
    }

    fun validate(opening: FloorPlanOpening, wallLength: Double): String? = when {
        wallLength <= 0.0 -> "Parete senza lunghezza valida"
        opening.width <= 0.0 -> "La larghezza deve essere maggiore di zero"
        opening.offset < 0.0 -> "La distanza dall'inizio parete non può essere negativa"
        opening.offset + opening.width > wallLength + 0.001 ->
            "L'apertura supera la parete: ${fmt(opening.offset)} + ${fmt(opening.width)} > ${fmt(wallLength)} m"
        else -> null
    }

    fun overlapConflict(opening: FloorPlanOpening, existing: List<FloorPlanOpening>): FloorPlanOpening? {
        val a1 = opening.offset
        val a2 = opening.offset + opening.width
        return existing.firstOrNull { other ->
            if (other.id == opening.id) return@firstOrNull false
            val b1 = other.offset
            val b2 = other.offset + other.width
            min(a2, b2) - max(a1, b1) > 0.001
        }
    }

    fun countLabel(raw: String): String {
        val rows = openings(raw)
        val doors = rows.count { it.type == OpeningTypes.DOOR }
        val windows = rows.count { it.type == OpeningTypes.WINDOW }
        return buildList {
            if (doors > 0) add("$doors ${if (doors == 1) "porta" else "porte"}")
            if (windows > 0) add("$windows ${if (windows == 1) "finestra" else "finestre"}")
        }.joinToString(" · ")
    }
}
