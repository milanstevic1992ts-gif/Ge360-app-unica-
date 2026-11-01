package it.edilmilan.ge360.core.common

/**
 * Contratti stabili condivisi tra i moduli GE360.
 * Gli ID GE360 sono UUID persistenti; gli ID SQLite locali non devono uscire dal modulo proprietario.
 */
object Ge360Contracts {
    const val ENTITY_CLIENT = "client"
    const val ENTITY_JOBSITE = "jobsite"
    const val ENTITY_ESTIMATE = "estimate"
    const val ENTITY_EXPENSE = "expense"
    const val ENTITY_DOCUMENT = "document"
    const val ENTITY_PHOTO = "photo"
    const val ENTITY_TOOL_REQUEST = "tool_request"

    const val EXTRA_CLIENT_LOCAL_ID = "ge360_client_id"
    const val EXTRA_CLIENT_ID = "ge360_client_uuid"
    const val EXTRA_JOBSITE_LOCAL_ID = "ge360_jobsite_local_id"
    const val EXTRA_JOBSITE_ID = "ge360_jobsite_id"
    const val EXTRA_JOBSITE_CODE = "ge360_jobsite_code"

    // Agenda context uses existing canonical UUID extras.
    const val EXTRA_AGENDA_CREATE_KIND = "ge360.agenda.create_kind"
    const val EXTRA_AGENDA_CONTEXT_LABEL = "ge360.agenda.context_label"

    const val ACTION_OPEN_CLIENT = "it.edilmilan.ge360.action.OPEN_CLIENT"
    const val ACTION_OPEN_JOBSITES = "it.edilmilan.ge360.action.OPEN_JOBSITES"
    const val ACTION_OPEN_JOBSITE = "it.edilmilan.ge360.action.OPEN_JOBSITE"
}

data class Ge360EntityRef(
    val type: String,
    val id: String,
    val localId: Long = 0,
    val label: String = ""
)
