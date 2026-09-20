package it.edilmilan.ge360.jarvis

import android.content.Context
import it.edilmilan.ge360.attrezzi.ResourceKind
import it.edilmilan.ge360.attrezzi.ToolsStore
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.core.bridge.TrainerScope
import it.edilmilan.ge360.core.bridge.TrainerScopes

data class TrainerScopeTypeOption(
    val type: String,
    val label: String
) {
    override fun toString(): String = label
}

data class TrainerScopeOption(
    val type: String,
    val id: String,
    val label: String
) {
    fun asScope(): TrainerScope = TrainerScope(type, id, label)
    override fun toString(): String = label
}

/**
 * Legge solo gli store locali già presenti nell'App Unica.
 * Gli ID inviati al Training Bridge sono gli stessi ge360Id sincronizzati nel Data Hub,
 * così il backend può applicare una regola scoped senza indovinare il target.
 */
class TrainerScopeCatalog(context: Context) {
    private val appContext = context.applicationContext

    val types: List<TrainerScopeTypeOption> = listOf(
        TrainerScopeTypeOption("global", "Globale"),
        TrainerScopeTypeOption("jobsite", "Cantiere"),
        TrainerScopeTypeOption("work_type", "Tipo di lavoro"),
        TrainerScopeTypeOption("tool", "Attrezzo"),
        TrainerScopeTypeOption("material", "Materiale")
    )

    fun options(type: String): List<TrainerScopeOption> = when (type.trim().lowercase()) {
        "global" -> listOf(TrainerScopeOption("global", "", "Globale"))
        "jobsite" -> jobsiteOptions()
        "work_type" -> workTypeOptions()
        "tool" -> resourceOptions(ResourceKind.TOOL, "tool")
        "material" -> resourceOptions(ResourceKind.MATERIAL, "material")
        else -> emptyList()
    }

    private fun jobsiteOptions(): List<TrainerScopeOption> {
        val store = JobsiteStore(appContext)
        return try {
            store.list()
                .filter { it.ge360Id.isNotBlank() }
                .map { job ->
                    val title = job.displayTitle().trim().ifBlank { job.code }
                    val detail = job.address.trim()
                    TrainerScopeOption(
                        type = "jobsite",
                        id = job.ge360Id.trim(),
                        label = if (detail.isNotBlank() && !title.contains(detail, ignoreCase = true)) {
                            "$title · $detail"
                        } else title
                    )
                }
                .distinctBy { it.id }
                .sortedBy { it.label.lowercase() }
        } finally {
            store.close()
        }
    }

    private fun workTypeOptions(): List<TrainerScopeOption> = JobsiteStore.WORK_TYPES
        .mapNotNull { label ->
            val id = TrainerScopes.workTypeId(label)
            if (id.isBlank()) null else TrainerScopeOption("work_type", id, label)
        }
        .distinctBy { it.id }

    private fun resourceOptions(kind: ResourceKind, type: String): List<TrainerScopeOption> {
        val store = ToolsStore(appContext)
        return try {
            store.catalog(kind = kind)
                .filter { it.ge360Id.isNotBlank() && it.name.isNotBlank() }
                .map { item ->
                    val category = item.category.trim()
                    TrainerScopeOption(
                        type = type,
                        id = item.ge360Id.trim(),
                        label = if (category.isBlank() || category.equals("Altro", ignoreCase = true)) {
                            item.name.trim()
                        } else {
                            "${item.name.trim()} · $category"
                        }
                    )
                }
                .distinctBy { it.id }
                .sortedBy { it.label.lowercase() }
        } finally {
            store.close()
        }
    }
}
