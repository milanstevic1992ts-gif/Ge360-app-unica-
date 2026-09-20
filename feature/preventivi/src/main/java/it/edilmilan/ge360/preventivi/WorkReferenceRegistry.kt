package it.edilmilan.ge360.preventivi

import java.util.Locale

/**
 * Riferimenti normativi e tecnici suggeriti in base alla lavorazione.
 * I richiami vengono sempre presentati come "ove applicabili": non costituiscono
 * una dichiarazione automatica di conformità e possono essere modificati nelle
 * lavorazioni manuali.
 */
object WorkReferenceRegistry {
    const val SAFETY = "D.Lgs. 81/2008 e s.m.i. — salute e sicurezza sul lavoro; Titolo IV ove applicabile ai cantieri temporanei o mobili"
    const val WASTE = "D.Lgs. 152/2006 e s.m.i., Parte IV — gestione dei rifiuti"
    const val TILES = "UNI 11493-1:2025 — piastrellature ceramiche: progettazione, installazione e manutenzione"
    const val TILE_ADHESIVES = "UNI EN 12004-1:2017 — adesivi per piastrelle ceramiche"
    const val TILE_GROUTS = "UNI EN 13888-1:2022 — sigillanti per fughe di piastrelle ceramiche"
    const val WATERPROOFING = "UNI EN 14891:2017 — prodotti impermeabilizzanti applicati liquidi sotto piastrellatura"
    const val SCREEDS = "UNI 11944:2024 — massetti per pavimentazioni"
    const val DRYWALL = "UNI 11424:2015 — sistemi costruttivi non portanti in lastre di gesso rivestito su orditura metallica"
    const val RESILIENT_FLOORS = "UNI 11515-1:2026 — pavimentazioni interne resilienti e laminate, ove pertinente"
    const val MOLD_CONDENSATION = "UNI EN ISO 13788:2013 — valutazione del rischio di condensazione superficiale/interstiziale e muffa"

    val MAIN = listOf(
        SAFETY,
        WASTE,
        TILES,
        TILE_ADHESIVES,
        TILE_GROUTS,
        WATERPROOFING,
        SCREEDS,
        DRYWALL,
        RESILIENT_FLOORS,
        MOLD_CONDENSATION
    )

    fun suggest(name: String, category: String = "", description: String = ""): List<String> {
        val text = listOf(name, category, description).joinToString(" ").lowercase(Locale.ROOT)
        val out = linkedSetOf(SAFETY)

        if (has(text, "demol", "rimozion", "smalt", "rifiut", "macer", "discarica", "materiale di risulta")) {
            out += WASTE
        }
        if (has(text, "piastrell", "rivestiment", "mosaico", "grande formato", "grandi lastre", "posa su posa", "sovrapposizion", "battiscopa ceramic")) {
            out += TILES
            out += TILE_ADHESIVES
        }
        if (has(text, "fug", "stuccatura piastrel", "sigillante per piastrel")) {
            out += TILE_GROUTS
        }
        if (has(text, "impermeabil", "nanoflex", "zona doccia", "doccia filo", "elemento di tenuta")) {
            out += WATERPROOFING
            if (has(text, "piastrell", "doccia", "bagno")) out += TILES
        }
        if (has(text, "massetto", "sottofondo", "autolivellante", "staggia", "rete elettrosaldata")) {
            out += SCREEDS
        }
        if (has(text, "cartongesso", "controsoffitto", "controparete", "veletta", "lastra di gesso", "orditura metallica")) {
            out += DRYWALL
        }
        if (has(text, "spc", "lvt", "laminato", "pavimento resiliente", "pavimento flottante")) {
            out += RESILIENT_FLOORS
        }
        if (has(text, "muffa", "condensa", "ponte termico", "umidità superficiale", "umidita superficiale")) {
            out += MOLD_CONDENSATION
        }
        return out.toList()
    }

    fun referencesFor(item: EstimateItem): List<String> =
        suggest(item.name, item.category, item.description)

    fun referencesFor(items: List<EstimateItem>): List<String> =
        items.flatMap(::referencesFor).distinct()

    fun parseEditable(value: String): List<String> = value
        .replace("\r", "\n")
        .split('\n', ';')
        .map { it.trim().trimEnd('.') }
        .filter { it.isNotBlank() }
        .distinct()

    fun formatEditable(refs: List<String>): String = refs.joinToString("\n")

    fun appendToDescription(description: String, refs: List<String>): String {
        val clean = description.substringBefore("\n\nRiferimenti normativi e tecnici, ove applicabili:").trim()
        if (refs.isEmpty()) return clean
        return buildString {
            append(clean)
            if (clean.isNotBlank()) append("\n\n")
            append("Riferimenti normativi e tecnici, ove applicabili: ")
            append(refs.joinToString("; "))
            append('.')
        }
    }

    private fun has(text: String, vararg needles: String): Boolean = needles.any { text.contains(it) }
}
