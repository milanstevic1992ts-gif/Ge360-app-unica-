package it.edilmilan.ge360.clienti

import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/**
 * Observed client journey event, not a sentiment score or behavioral inference.
 * A jobsite link is optional and always an explicit GE360 UUID, not a name match.
 */
data class ClientFactualEventV1(
    val ge360Id: String,
    val clientGe360Id: String,
    val jobsiteGe360Id: String? = null,
    val kind: String,
    val description: String,
    val source: String = "Manuale",
    val operatorLabel: String = "Operatore (manuale)",
    val occurredAt: Long,
    val nextActionDate: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun isValid(): Boolean =
        ge360Id.isNotBlank() && clientGe360Id.isNotBlank() &&
        kind in KINDS && description.isNotBlank() && description.length <= 3000 &&
        source in SOURCES && operatorLabel.isNotBlank() &&
        occurredAt > 0 && createdAt > 0 &&
        (nextActionDate.isBlank() ||
            runCatching { LocalDate.parse(nextActionDate) }.isSuccess)

    fun toJson(): JSONObject = JSONObject()
        .put("ge360Id", ge360Id)
        .put("clientGe360Id", clientGe360Id)
        .put("jobsiteGe360Id", jobsiteGe360Id.orEmpty())
        .put("kind", kind)
        .put("description", description)
        .put("source", source)
        .put("operatorLabel", operatorLabel)
        .put("occurredAt", occurredAt)
        .put("nextActionDate", nextActionDate)
        .put("createdAt", createdAt)

    companion object {
        val KINDS = listOf(
            "Primo contatto", "Telefonata", "Messaggio", "Sopralluogo",
            "Richiesta cliente", "Preventivo inviato", "Preventivo accettato",
            "Lavori in corso", "Problema segnalato", "Apprezzamento espresso",
            "Impegno preso", "Consegna", "Follow-up", "Altro"
        )
        val SOURCES = listOf("Manuale", "Telefonata", "WhatsApp", "Email", "Di persona", "Documento")

        fun newManual(clientGe360Id: String, kind: String, description: String,
                      source: String, nextActionDate: String = "",
                      jobsiteGe360Id: String? = null): ClientFactualEventV1 =
            ClientFactualEventV1(
                ge360Id = UUID.randomUUID().toString(),
                clientGe360Id = clientGe360Id,
                jobsiteGe360Id = jobsiteGe360Id,
                kind = kind,
                description = description.trim(),
                source = source,
                occurredAt = System.currentTimeMillis(),
                nextActionDate = nextActionDate.trim()
            )

        fun fromJson(o: JSONObject): ClientFactualEventV1 = ClientFactualEventV1(
            ge360Id = o.getString("ge360Id"),
            clientGe360Id = o.getString("clientGe360Id"),
            jobsiteGe360Id = o.optString("jobsiteGe360Id").ifBlank { null },
            kind = o.getString("kind"),
            description = o.getString("description"),
            source = o.optString("source", "Manuale"),
            operatorLabel = o.optString("operatorLabel", "Operatore (manuale)"),
            occurredAt = o.getLong("occurredAt"),
            nextActionDate = o.optString("nextActionDate", ""),
            createdAt = o.getLong("createdAt")
        )
    }
}
