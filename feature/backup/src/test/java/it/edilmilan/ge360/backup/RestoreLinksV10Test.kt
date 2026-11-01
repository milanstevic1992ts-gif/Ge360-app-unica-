package it.edilmilan.ge360.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level regression contracts written for V10, NOT EXECUTED.
 * All values are synthetic; tests never access real client data.
 */
class RestoreLinksV10Test {
    private val client = "11111111-1111-4111-8111-111111111111"
    private val job = "22222222-2222-4222-8222-222222222222"
    private val estimate = "33333333-3333-4333-8333-333333333333"
    private val plan = "44444444-4444-4444-8444-444444444444"
    private val room = "55555555-5555-4555-8555-555555555555"

    private fun item(id: String, vararg attrs: Pair<String, String>): JSONObject =
        JSONObject().put("ge360Id", id).apply {
            attrs.forEach { (key, value) -> put(key, value) }
        }

    private fun snapshot(
        clients: JSONArray = JSONArray().put(item(client)),
        jobs: JSONArray = JSONArray().put(item(job, "clientGe360Id" to client)),
        estimates: JSONArray = JSONArray().put(
            item(estimate, "clientGe360Id" to client, "jobsiteId" to job)),
        scanner: JSONArray = JSONArray(),
        gallery: JSONArray = JSONArray(),
        plans: JSONObject? = null,
        archive: JSONObject? = null
    ) = RestoreLinksV10.Snapshot(
        clients = JSONObject().put("clients", clients).toString(),
        jobs = JSONObject().put("jobsites", jobs).toString(),
        estimates = JSONObject().put("estimates", estimates).toString(),
        scanner = JSONObject().put("documents", scanner).toString(),
        gallery = JSONObject().put("photos", gallery).toString(),
        floorPlans = plans?.toString(),
        jobsiteArchive = archive?.toString()
    )

    private fun rejected(snapshot: RestoreLinksV10.Snapshot, replace: Boolean = true,
                         local: RestoreLinksV10.Existing = RestoreLinksV10.Existing()) =
        assertTrue(runCatching { RestoreLinksV10.check(snapshot, replace, local) }.isFailure)

    @Test fun validGraphAndUnlinkedStandaloneRecordsAreAccepted() {
        val result = RestoreLinksV10.check(snapshot(), replaceAll = true)
        assertEquals(3, result.checkedRecords)
        assertEquals(3, result.checkedLinks)
        val independent = snapshot(
            jobs = JSONArray().put(item(job)),
            estimates = JSONArray().put(item(estimate))
        )
        RestoreLinksV10.check(independent, replaceAll = true)
    }

    @Test fun missingClientOrJobIsRejectedBeforeAnyRestoreWrite() {
        rejected(snapshot(clients = JSONArray()))
        rejected(snapshot(jobs = JSONArray()))
        rejected(snapshot(jobs = JSONArray().put(item(job, "clientGe360Id" to
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))))
    }

    @Test fun mergeCanReferToExistingUuidButReplaceAllCannot() {
        val incoming = snapshot(clients = JSONArray(), jobs = JSONArray(),
            estimates = JSONArray().put(item(estimate, "clientGe360Id" to client,
                "jobsiteId" to job)))
        val local = RestoreLinksV10.Existing(
            clientIds = setOf(client), jobIds = setOf(job)
        )
        RestoreLinksV10.check(incoming, replaceAll = false, local = local)
        rejected(incoming, replace = true, local = local)
    }

    @Test fun scannerAndGalleryCannotReferenceMissingExplicitUuidJob() {
        val external = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val scanner = JSONArray().put(JSONObject()
            .put("ge360_id", "scanner-legacy")
            .put("jobsite_id", external))
        rejected(snapshot(scanner = scanner))
        val gallery = JSONArray().put(item("gallery-one", "jobsiteId" to external))
        rejected(snapshot(gallery = gallery))
    }

    @Test fun duplicateUuidsAreRejectedWithinModule() {
        rejected(snapshot(clients = JSONArray().put(item(client)).put(item(client))))
        rejected(snapshot(estimates = JSONArray().put(item(estimate)).put(item(estimate))))
    }

    @Test fun planBindingsMustPointToARealPlanRoomAndEstimate() {
        val binding = item("binding-1", "planGe360Id" to plan,
            "roomGe360Id" to room, "estimateGe360Id" to estimate)
        val planJson = JSONObject()
            .put("floorPlans", JSONArray().put(item(plan).put(
                "rooms", JSONArray().put(item(room)))))
            .put("workBindings", JSONArray().put(binding))
        RestoreLinksV10.check(snapshot(plans = planJson), replaceAll = true)

        val broken = JSONObject(planJson.toString()).put("floorPlans",
            JSONArray().put(item(plan).put("rooms", JSONArray())))
        rejected(snapshot(plans = broken))
    }

    @Test fun legacyNonUuidReferencesAreNotReassigned() {
        val legacy = snapshot(jobs = JSONArray().put(
            item(job, "clientGe360Id" to "CLIENTE-STORICO-01")))
        val result = RestoreLinksV10.check(legacy, replaceAll = true)
        assertEquals(1, result.historicalLinksSkipped)
    }

    @Test fun archiveFileMustReferenceAvailableUuidJob() {
        val other = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val archive = JSONObject()
            .put("files", JSONArray().put(item("file-a", "jobsiteId" to other)))
            .put("memos", JSONArray())
        rejected(snapshot(archive = archive))
    }
}
