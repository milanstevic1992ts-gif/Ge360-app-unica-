package it.edilmilan.ge360.preventivi

import android.content.Context
import it.edilmilan.ge360.cantieri.JobsiteProgressItem
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Passaggio canonico Preventivo -> Contratto accettato -> Cantiere effettivo.
 *
 * La scrittura coinvolge tre database locali indipendenti: NON è una
 * transazione ACID multi-DB. L'UUID cantiere deterministico e gli upsert
 * consentono di riprendere dopo un arresto, senza creare un secondo cantiere.
 * Lo snapshot si congela solo DOPO aver persistito il legame preventivo/cantiere.
 */
object EstimateAcceptanceEngine {
    data class Result(val jobsiteId: String, val jobsiteCode: String, val newlyAccepted: Boolean)

    fun deterministicJobsiteId(estimateGe360Id: String): String {
        require(estimateGe360Id.isNotBlank())
        return UUID.nameUUIDFromBytes(
            ("ge360:accepted:jobsite:" + estimateGe360Id).toByteArray(StandardCharsets.UTF_8)
        ).toString()
    }

    fun isAcceptable(record: EstimateRecord): Boolean =
        record.status !in setOf(EstimateStatuses.REJECTED, EstimateStatuses.EXPIRED, EstimateStatuses.COMPLETED) &&
            record.ge360Id.isNotBlank() &&
            record.id > 0 &&
            record.clientName.isNotBlank() &&
            record.title.isNotBlank() &&
            record.items.any { it.included && !it.optional } &&
            record.finalTotal > 0.0

    @Synchronized
    fun acceptAndActivate(
        context: Context,
        estimateGe360Id: String,
        signer: String = "",
        note: String = ""
    ): Result {
        val estimates = EstimateStore(context)
        val jobs = JobsiteStore(context)
        val lifecycle = EstimateLifecycleStore(context)
        try {
            val record = estimates.getByGe360Id(estimateGe360Id)
                ?: error("Preventivo non trovato. Salva il preventivo prima di accettarlo.")
            val wasLocked = lifecycle.isLocked(record.ge360Id)
            if (!wasLocked) {
                require(isAcceptable(record)) {
                    "Prima dell'accettazione completa cliente, titolo, lavorazioni e importo positivo."
                }
            }

            // Se l'utente aveva gia' collegato un cantiere usa lo stesso UUID.
            // Se una precedente attivazione è stata interrotta prima del legame,
            // il deterministico permette di riprendere senza duplicare.
            val jobId = record.jobsiteId.ifBlank { deterministicJobsiteId(record.ge360Id) }
            val existing = jobs.getByGe360Id(jobId)
            val job = existing ?: JobsiteRecord(
                ge360Id = jobId,
                clientId = record.clientId,
                clientGe360Id = record.clientGe360Id,
                clientName = record.clientName,
                title = record.title,
                workType = record.workType,
                status = JobsiteStore.STATUS_SCHEDULED
            )
            if (existing != null) {
                // Non riportare indietro lavori gia' iniziati, sospesi, conclusi
                // o con avanzamenti registrati.
                if (job.status in setOf(
                    JobsiteStore.STATUS_INSPECTION,
                    JobsiteStore.STATUS_ESTIMATE
                )) {
                    job.status = JobsiteStore.STATUS_SCHEDULED
                }
            }
            if (job.clientGe360Id.isBlank()) job.clientGe360Id = record.clientGe360Id
            if (job.clientId <= 0) job.clientId = record.clientId
            if (job.clientName.isBlank()) job.clientName = record.clientName
            if (job.title.isBlank()) job.title = record.title
            if (job.workType.isBlank()) job.workType = record.workType
            if (job.notes.isBlank()) {
                job.notes = "Lavoro confermato dal preventivo " + record.number +
                    (if (record.generalDescription.isBlank()) "" else "\n\n" + record.generalDescription)
            }
            jobs.save(job)

            // Idempotenza anche se la prima attivazione è stata interrotta
            // a metà trasferimento delle singole lavorazioni.
            val recorded = jobs.progressItems(job.ge360Id).map { it.ge360Id }.toSet()
            val payableWorks = record.items
                .filter { it.included && !it.optional && it.category != "Prezzo ambiente" }
            fun phaseId(itemId: String) = UUID.nameUUIDFromBytes(
                ("ge360:accepted:phase:" + record.ge360Id + ":" + itemId)
                    .toByteArray(StandardCharsets.UTF_8)
            ).toString()
            val seededIds = payableWorks.mapIndexed { index, item ->
                phaseId(item.ge360Id.ifBlank { index.toString() })
            }.toSet()
            // Un cantiere preesistente con fasi manuali NON deve ricevere
            // automaticamente duplicati. Completiamo solo una nostra
            // precedente attivazione interrotta, o un cantiere senza fasi.
            val maySeed = recorded.isEmpty() || recorded.any { it in seededIds }
            if (maySeed) payableWorks.forEachIndexed { index, item ->
                    val sourceId = item.ge360Id.ifBlank { index.toString() }
                    val deterministicPhaseId = phaseId(sourceId)
                    if (deterministicPhaseId !in recorded) {
                        jobs.saveProgressItem(
                            JobsiteProgressItem(
                                ge360Id = deterministicPhaseId,
                                jobsiteId = job.ge360Id,
                                phase = item.room.ifBlank { item.section.ifBlank { "Lavorazioni" } },
                                title = item.name,
                                note = buildString {
                                    append(item.quantity).append(' ').append(item.unit)
                                    if (item.description.isNotBlank()) append('\n').append(item.description)
                                },
                                weight = 10,
                                sortOrder = index
                            )
                        )
                    }
                }

            // Può essere ripetuto dopo un crash; non riscrive lo snapshot.
            val statusNeedsAccept = record.status !in setOf(
                EstimateStatuses.ACCEPTED, EstimateStatuses.IN_PROGRESS, EstimateStatuses.COMPLETED
            )
            val linkNeedsSave = record.jobsiteId != job.ge360Id ||
                record.jobsiteCode != job.code || record.jobsiteTitle != job.displayTitle() ||
                statusNeedsAccept
            if (statusNeedsAccept) record.status = EstimateStatuses.ACCEPTED
            record.jobsiteId = job.ge360Id
            record.jobsiteCode = job.code
            record.jobsiteTitle = job.displayTitle()
            if (linkNeedsSave) estimates.save(record)
            if (!wasLocked) {
                lifecycle.freezeAccepted(record, signer, note)
            }
            return Result(job.ge360Id, job.code, !wasLocked)
        } finally {
            lifecycle.close()
            jobs.close()
            estimates.close()
        }
    }
}
