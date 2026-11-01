package it.edilmilan.ge360.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only link integrity gate, BEFORE the first write of a restore.
 *
 * Checks explicit, canonical GE360 UUID relationships. Blank and historical
 * non-UUID identifiers are not silently mapped to new records; their existing
 * importers own legacy migration. Nonexistent UUID links fail closed.
 *
 * No prices, balances, payments, document contents or notes are modified.
 */
internal object RestoreLinksV10 {
    private val uuid = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )

    data class Snapshot(
        val clients: String,
        val jobs: String,
        val estimates: String,
        val scanner: String,
        val gallery: String,
        val floorPlans: String? = null,
        val jobsiteArchive: String? = null
    )

    data class Existing(
        val clientIds: Set<String> = emptySet(),
        val jobIds: Set<String> = emptySet(),
        val estimateIds: Set<String> = emptySet(),
        val planRooms: Map<String, Set<String>> = emptyMap()
    )

    data class Report(
        val checkedLinks: Int,
        val checkedRecords: Int,
        val historicalLinksSkipped: Int
    )

    fun check(snapshot: Snapshot, replaceAll: Boolean, local: Existing = Existing()): Report {
        val clients = JSONObject(snapshot.clients).getJSONArray("clients")
        val jobs = JSONObject(snapshot.jobs).getJSONArray("jobsites")
        val estimates = JSONObject(snapshot.estimates).getJSONArray("estimates")
        val documents = JSONObject(snapshot.scanner).getJSONArray("documents")
        val photos = JSONObject(snapshot.gallery).getJSONArray("photos")

        val clientIds = uniqueIds(clients, "Clienti", allowBlank = true) +
            if (replaceAll) emptySet() else local.clientIds
        val jobIds = uniqueIds(jobs, "Cantieri") +
            if (replaceAll) emptySet() else local.jobIds
        val estimateIds = uniqueIds(estimates, "Preventivi") +
            if (replaceAll) emptySet() else local.estimateIds

        val plansJson = snapshot.floorPlans?.let(::JSONObject)
        val plans = plansJson?.getJSONArray("floorPlans")
        val importedRoomIds = linkedMapOf<String, Set<String>>()
        if (plans != null) {
            uniqueIds(plans, "Planimetrie")
            each(plans) { plan ->
                val planId = plan.getString("ge360Id")
                val rooms = plan.getJSONArray("rooms")
                importedRoomIds[planId] = uniqueIds(rooms, "Stanze del rilievo")
            }
        }
        val planRooms = linkedMapOf<String, Set<String>>()
        if (!replaceAll) planRooms.putAll(local.planRooms)
        planRooms.putAll(importedRoomIds.mapValues { (id, rooms) ->
            rooms + if (!replaceAll) local.planRooms[id].orEmpty() else emptySet()
        })

        var checked = 0
        var historical = 0
        fun link(id: String, available: Set<String>, kind: String) {
            if (id.isBlank()) return
            if (!uuid.matches(id)) {
                historical++
                return
            }
            checked++
            require(id in available) {
                "Backup incoerente: riferimento $kind non presente (UUID $id)"
            }
        }
        fun job(id: String) = link(id, jobIds, "cantiere")
        fun client(id: String) = link(id, clientIds, "cliente")
        fun estimate(id: String) = link(id, estimateIds, "preventivo")

        each(jobs) { client(it.optString("clientGe360Id")) }
        each(estimates) {
            client(it.optString("clientGe360Id"))
            job(it.optString("jobsiteId"))
        }

        val progress = JSONObject(snapshot.jobs).optJSONArray("progressItems")
        val milestones = JSONObject(snapshot.jobs).optJSONArray("milestones")
        progress?.let { rows ->
            uniqueIds(rows, "Avanzamenti cantiere")
            each(rows) { job(it.optString("jobsiteId")) }
        }
        milestones?.let { rows ->
            uniqueIds(rows, "Scadenze cantiere")
            each(rows) { job(it.optString("jobsiteId")) }
        }

        uniqueIds(documents, "Documenti Scanner")
        each(documents) { job(it.optString("jobsite_id")) }

        uniqueIds(photos, "Fotografie")
        each(photos) { job(it.optString("jobsiteId")) }

        plans?.let { rows ->
            each(rows) {
                job(it.optString("jobsiteId"))
                estimate(it.optString("estimateId"))
            }
            plansJson.optJSONArray("workBindings")?.let { bindings ->
                uniqueIds(bindings, "Collegamenti rilievi")
                each(bindings) { binding ->
                    val planId = binding.optString("planGe360Id")
                    val roomId = binding.optString("roomGe360Id")
                    if (uuid.matches(planId)) {
                        checked++
                        require(planId in planRooms) {
                            "Backup incoerente: rilievo del collegamento non presente ($planId)"
                        }
                        if (uuid.matches(roomId)) {
                            checked++
                            require(roomId in planRooms[planId].orEmpty()) {
                                "Backup incoerente: stanza del collegamento non presente ($roomId)"
                            }
                        } else if (roomId.isNotBlank()) historical++
                    } else if (planId.isNotBlank()) historical++
                    estimate(binding.optString("estimateGe360Id"))
                }
            }
        }

        snapshot.jobsiteArchive?.let { json ->
            val root = JSONObject(json)
            val files = root.getJSONArray("files")
            val memos = root.getJSONArray("memos")
            uniqueIds(files, "Documenti cantiere")
            uniqueIds(memos, "Note cantiere")
            each(files) { job(it.optString("jobsiteId")) }
            each(memos) { job(it.optString("jobsiteId")) }
        }
        return Report(
            checkedLinks = checked,
            checkedRecords = clients.length() + jobs.length() + estimates.length() +
                documents.length() + photos.length() + (plans?.length() ?: 0),
            historicalLinksSkipped = historical
        )
    }

    private inline fun each(rows: JSONArray, block: (JSONObject) -> Unit) {
        for (i in 0 until rows.length()) block(rows.getJSONObject(i))
    }

    private fun uniqueIds(rows: JSONArray, kind: String, allowBlank: Boolean = false): Set<String> {
        val result = mutableSetOf<String>()
        each(rows) { record ->
            val id = record.optString("ge360Id").ifBlank { record.optString("ge360_id") }.trim()
            require(allowBlank || id.isNotBlank()) { "Backup incoerente: UUID mancante in $kind" }
            if (id.isNotBlank()) {
                require(result.add(id)) { "Backup incoerente: UUID duplicato in $kind ($id)" }
            }
        }
        return result
    }
}
