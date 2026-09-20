package it.edilmilan.ge360.jarvis

/**
 * Regole pure della Fase 8. Nessuna di queste funzioni esegue azioni GE360:
 * decide soltanto quali avvisi possono essere mostrati e mantiene la deduplica locale.
 */
object JarvisProactivePolicy {
    private val severityRank = mapOf(
        "critical" to 0,
        "warning" to 1,
        "info" to 2
    )

    fun validSeverity(value: String): Boolean = value.trim().lowercase() in severityRank

    fun shouldNotify(
        id: String,
        severity: String,
        seenIds: Set<String>,
        includeInfo: Boolean
    ): Boolean {
        val cleanId = id.trim()
        val cleanSeverity = severity.trim().lowercase()
        if (cleanId.isBlank() || cleanId in seenIds || !validSeverity(cleanSeverity)) return false
        return includeInfo || cleanSeverity == "critical" || cleanSeverity == "warning"
    }

    fun minimumSeverity(includeInfo: Boolean): String = if (includeInfo) "info" else "warning"

    fun mergeSeen(previous: List<String>, newlySeen: Collection<String>, maxItems: Int = 200): List<String> {
        val safeMax = maxItems.coerceIn(20, 1_000)
        return (previous + newlySeen)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .takeLast(safeMax)
    }
}
