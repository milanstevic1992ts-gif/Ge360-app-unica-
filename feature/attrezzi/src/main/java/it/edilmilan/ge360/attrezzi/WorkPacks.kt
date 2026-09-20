package it.edilmilan.ge360.attrezzi

import java.util.Locale

data class WorkResourceSpec(
    val kind: ResourceKind,
    val name: String,
    val category: String,
    val unit: String = "pz",
    val quantity: Double = 1.0,
    val note: String = ""
)

data class WorkPack(
    val id: String,
    val icon: String,
    val title: String,
    val description: String,
    val items: List<WorkResourceSpec>
) {
    val toolsCount: Int get() = items.count { it.kind == ResourceKind.TOOL }
    val materialsCount: Int get() = items.count { it.kind == ResourceKind.MATERIAL }
}

object StandardWorkCatalog {

    private fun tool(name: String, category: String, unit: String = "pz", note: String = "") =
        WorkResourceSpec(ResourceKind.TOOL, name, category, unit, 1.0, note)

    private fun material(name: String, category: String, unit: String = "pz", note: String = "") =
        WorkResourceSpec(ResourceKind.MATERIAL, name, category, unit, 1.0, note)

    val resources: List<WorkResourceSpec> = listOf(
        tool("Trapano avvitatore", "Elettroutensili", note = "Batterie e caricatore"),
        tool("Tassellatore", "Elettroutensili", note = "Punte SDS"),
        tool("Martello demolitore", "Demolizione", note = "Scalpelli e grasso"),
        tool("Smerigliatrice / flex", "Elettroutensili", note = "Dischi adatti al lavoro"),
        tool("Miscelatore elettrico", "Elettroutensili", note = "Frusta pulita"),
        tool("Aspiratore professionale", "Pulizia", note = "Sacchi e filtro"),
        tool("Laser autolivellante", "Misura", note = "Treppiede e batterie"),
        tool("Metro", "Misura"),
        tool("Livella", "Misura"),
        tool("Squadra", "Misura"),
        tool("Matite / marcatori", "Misura", unit = "set"),
        tool("Tagliapiastrelle", "Piastrelle", note = "Manuale o elettrica"),
        tool("Spatole dentate", "Piastrelle", unit = "set"),
        tool("Frattazzo gomma", "Piastrelle"),
        tool("Spugne piastrellista", "Piastrelle", unit = "set"),
        tool("Ventose piastrelle", "Piastrelle", unit = "set"),
        tool("Alzalastre", "Cartongesso", note = "Controsoffitti"),
        tool("Cesoia profili", "Cartongesso"),
        tool("Taglierino", "Utensili manuali"),
        tool("Pialla cartongesso", "Cartongesso"),
        tool("Spatole stuccatura", "Finiture", unit = "set"),
        tool("Levigatrice giraffa", "Finiture"),
        tool("Rulli e pennelli", "Pittura", unit = "set"),
        tool("Asta telescopica", "Pittura"),
        tool("Griglia / vaschetta pittura", "Pittura"),
        tool("Mazza", "Demolizione"),
        tool("Martello", "Utensili manuali"),
        tool("Scalpelli manuali", "Demolizione", unit = "set"),
        tool("Piede di porco", "Demolizione"),
        tool("Pala", "Demolizione"),
        tool("Carriola", "Movimentazione"),
        tool("Secchi da cantiere", "Movimentazione", unit = "set"),
        tool("Scopa e paletta", "Pulizia", unit = "set"),
        tool("Cazzuole e frattazzi", "Muratura", unit = "set"),
        tool("Staggia", "Muratura"),
        tool("Martello di gomma", "Pavimenti"),
        tool("Distanziatori pavimento", "Pavimenti", unit = "set"),
        tool("Troncatrice", "Taglio"),
        tool("Seghetto alternativo", "Taglio"),
        tool("Faro da cantiere", "Elettroutensili", note = "Batteria o cavo"),
        tool("Prolunga / avvolgicavo", "Elettroutensili", note = "Controllare lunghezza"),
        tool("Scala", "Accesso"),
        tool("DPI / protezioni personali", "Sicurezza", unit = "set", note = "Guanti, occhiali, cuffie, maschera"),

        material("Teli e protezioni", "Protezioni", unit = "m²", note = "Protezione passaggi e arredi"),
        material("Cartone protezione pavimenti", "Protezioni", unit = "m²"),
        material("Nylon di protezione", "Protezioni", unit = "m²"),
        material("Nastro carta", "Protezioni"),
        material("Sacchi macerie", "Demolizione", unit = "pz"),
        material("Malta", "Malte e rasanti", unit = "sacco"),
        material("MG35", "Malte e rasanti", unit = "sacco", note = "Malta premiscelata"),
        material("KCI", "Malte e rasanti", unit = "sacco", note = "Intonaco di fondo"),
        material("KD2", "Malte e rasanti", unit = "sacco", note = "Intonaco fibrorinforzato"),
        material("T50", "Malte e rasanti", unit = "sacco"),
        material("Cemento rapido", "Malte e rasanti", unit = "sacco"),
        material("Rasante", "Malte e rasanti", unit = "sacco"),
        material("Rete in fibra", "Malte e rasanti", unit = "m²"),
        material("Primer / fissativo", "Preparazione fondi", unit = "l"),
        material("Stucco muro", "Malte e rasanti", unit = "kg"),
        material("Carta abrasiva", "Consumabili", unit = "set"),
        material("Impermeabilizzante", "Impermeabilizzazione", unit = "kg"),
        material("Bandella impermeabile", "Impermeabilizzazione", unit = "ml"),
        material("Angolari / manicotti impermeabili", "Impermeabilizzazione", unit = "set"),
        material("Colla piastrelle", "Posa piastrelle", unit = "sacco"),
        material("Kerakoll H40", "Posa piastrelle", unit = "sacco", note = "Gel-adesivo"),
        material("Stucco fughe", "Posa piastrelle", unit = "kg"),
        material("Livellatori piastrelle", "Posa piastrelle", unit = "scatola"),
        material("Cunei piastrelle", "Posa piastrelle", unit = "scatola"),
        material("Profili cartongesso", "Sistemi cartongesso", unit = "ml"),
        material("Lastre cartongesso", "Sistemi cartongesso", unit = "pz"),
        material("Viti cartongesso", "Sistemi cartongesso", unit = "scatola"),
        material("Nastro giunti cartongesso", "Sistemi cartongesso", unit = "ml"),
        material("Stucco cartongesso", "Sistemi cartongesso", unit = "sacco"),
        material("Paraspigoli", "Sistemi cartongesso", unit = "ml"),
        material("Pittura", "Pittura e finiture", unit = "l"),
        material("Silicone / sigillante", "Consumabili"),
        material("Sigillante acrilico", "Consumabili"),
        material("Schiuma poliuretanica", "Consumabili"),
        material("Tasselli e viti", "Fissaggi", unit = "scatola"),
        material("SPC / laminato", "Pavimenti", unit = "m²"),
        material("Materassino pavimento", "Pavimenti", unit = "m²"),
        material("Barriera vapore", "Pavimenti", unit = "m²"),
        material("Battiscopa", "Pavimenti", unit = "ml"),
        material("Profili soglia / terminali", "Pavimenti", unit = "ml"),
        material("Architrave", "Muratura", unit = "pz"),
        material("Cassonetto porta scorrevole", "Porte", unit = "pz")
    )

    private val byName: Map<String, WorkResourceSpec> = resources.associateBy { it.name }

    private fun packItems(vararg names: String): List<WorkResourceSpec> = names.mapNotNull { byName[it] }

    val packs: List<WorkPack> = listOf(
        WorkPack(
            "protezioni", "🛡️", "Protezione cantiere",
            "Prima di iniziare: passaggi, pavimenti, polvere e sicurezza.",
            packItems(
                "Teli e protezioni", "Cartone protezione pavimenti", "Nylon di protezione", "Nastro carta",
                "DPI / protezioni personali", "Aspiratore professionale", "Scopa e paletta", "Faro da cantiere", "Prolunga / avvolgicavo"
            )
        ),
        WorkPack(
            "demolizioni", "🔨", "Demolizioni",
            "Demolizione murature, pavimenti, rivestimenti e smaltimento iniziale.",
            packItems(
                "Martello demolitore", "Tassellatore", "Smerigliatrice / flex", "Mazza", "Martello", "Scalpelli manuali",
                "Piede di porco", "Pala", "Carriola", "Secchi da cantiere", "Aspiratore professionale", "Scopa e paletta",
                "Faro da cantiere", "Prolunga / avvolgicavo", "DPI / protezioni personali", "Sacchi macerie", "Teli e protezioni"
            )
        ),
        WorkPack(
            "muratura", "🧱", "Muratura e ripristini",
            "Chiusura tracce, ripristini, spallette, piccoli massetti e murature.",
            packItems(
                "Miscelatore elettrico", "Tassellatore", "Trapano avvitatore", "Cazzuole e frattazzi", "Staggia", "Secchi da cantiere",
                "Laser autolivellante", "Metro", "Livella", "Faro da cantiere", "Prolunga / avvolgicavo", "DPI / protezioni personali",
                "Malta", "Cemento rapido", "Rasante", "Rete in fibra", "Primer / fissativo", "Teli e protezioni"
            )
        ),
        WorkPack(
            "tracce", "⚡", "Tracce impianti",
            "Apertura e chiusura tracce per elettrico, idraulico e climatizzazione.",
            packItems(
                "Tassellatore", "Martello demolitore", "Smerigliatrice / flex", "Aspiratore professionale", "Miscelatore elettrico",
                "Cazzuole e frattazzi", "Secchi da cantiere", "Laser autolivellante", "Metro", "DPI / protezioni personali",
                "Sacchi macerie", "Malta", "Rasante", "Primer / fissativo", "Teli e protezioni"
            )
        ),
        WorkPack(
            "cartongesso", "🧰", "Cartongesso pareti",
            "Pareti, contropareti, chiusure e velette.",
            packItems(
                "Trapano avvitatore", "Tassellatore", "Laser autolivellante", "Metro", "Livella", "Squadra", "Matite / marcatori",
                "Cesoia profili", "Taglierino", "Pialla cartongesso", "Spatole stuccatura", "Levigatrice giraffa", "Aspiratore professionale",
                "Faro da cantiere", "Prolunga / avvolgicavo", "DPI / protezioni personali", "Profili cartongesso", "Lastre cartongesso",
                "Viti cartongesso", "Nastro giunti cartongesso", "Stucco cartongesso", "Paraspigoli", "Tasselli e viti"
            )
        ),
        WorkPack(
            "controsoffitto", "⬜", "Controsoffitto cartongesso",
            "Pacchetto dedicato ai soffitti: struttura, lastre, posa e finitura.",
            packItems(
                "Trapano avvitatore", "Tassellatore", "Laser autolivellante", "Metro", "Livella", "Squadra", "Alzalastre", "Cesoia profili",
                "Taglierino", "Spatole stuccatura", "Levigatrice giraffa", "Scala", "Faro da cantiere", "Prolunga / avvolgicavo",
                "DPI / protezioni personali", "Profili cartongesso", "Lastre cartongesso", "Viti cartongesso", "Nastro giunti cartongesso",
                "Stucco cartongesso", "Paraspigoli", "Tasselli e viti"
            )
        ),
        WorkPack(
            "piastrelle", "◼️", "Posa piastrelle",
            "Preparazione, taglio, posa, livellamento, fughe e silicone.",
            packItems(
                "Tagliapiastrelle", "Smerigliatrice / flex", "Miscelatore elettrico", "Laser autolivellante", "Metro", "Livella",
                "Spatole dentate", "Frattazzo gomma", "Spugne piastrellista", "Ventose piastrelle", "Secchi da cantiere",
                "Aspiratore professionale", "Faro da cantiere", "Prolunga / avvolgicavo", "DPI / protezioni personali",
                "Primer / fissativo", "Colla piastrelle", "Stucco fughe", "Livellatori piastrelle", "Cunei piastrelle", "Silicone / sigillante"
            )
        ),
        WorkPack(
            "impermeabilizzazione", "💧", "Impermeabilizzazione",
            "Doccia, bagno o terrazzo: fondo, membrane e punti critici.",
            packItems(
                "Miscelatore elettrico", "Spatole stuccatura", "Cazzuole e frattazzi", "Secchi da cantiere", "Metro", "Livella",
                "DPI / protezioni personali", "Primer / fissativo", "Impermeabilizzante", "Bandella impermeabile",
                "Angolari / manicotti impermeabili", "Silicone / sigillante"
            )
        ),
        WorkPack(
            "bagno", "🚿", "Bagno completo",
            "Pacchetto largo: protezioni, demolizione, ripristini, impermeabilizzazione e piastrelle.",
            packItems(
                "Martello demolitore", "Tassellatore", "Smerigliatrice / flex", "Trapano avvitatore", "Miscelatore elettrico",
                "Aspiratore professionale", "Laser autolivellante", "Metro", "Livella", "Tagliapiastrelle", "Spatole dentate",
                "Frattazzo gomma", "Spugne piastrellista", "Secchi da cantiere", "Faro da cantiere", "Prolunga / avvolgicavo",
                "DPI / protezioni personali", "Teli e protezioni", "Sacchi macerie", "Malta", "Rasante", "Primer / fissativo",
                "Impermeabilizzante", "Bandella impermeabile", "Angolari / manicotti impermeabili", "Colla piastrelle", "Stucco fughe",
                "Livellatori piastrelle", "Cunei piastrelle", "Silicone / sigillante"
            )
        ),
        WorkPack(
            "rasatura", "🧱", "Rasatura armata",
            "Preparazione fondo, rete, rasatura e finitura.",
            packItems(
                "Miscelatore elettrico", "Cazzuole e frattazzi", "Spatole stuccatura", "Secchi da cantiere", "Levigatrice giraffa",
                "Aspiratore professionale", "Faro da cantiere", "Prolunga / avvolgicavo", "DPI / protezioni personali",
                "Primer / fissativo", "Rasante", "Rete in fibra", "Carta abrasiva", "Teli e protezioni"
            )
        ),
        WorkPack(
            "pittura", "🎨", "Tinteggiatura",
            "Protezione, preparazione, rasature locali, fissativo e pittura.",
            packItems(
                "Rulli e pennelli", "Asta telescopica", "Griglia / vaschetta pittura", "Spatole stuccatura", "Levigatrice giraffa",
                "Aspiratore professionale", "Scala", "Faro da cantiere", "Prolunga / avvolgicavo", "DPI / protezioni personali",
                "Teli e protezioni", "Nylon di protezione", "Nastro carta", "Stucco muro", "Carta abrasiva", "Primer / fissativo", "Pittura", "Sigillante acrilico"
            )
        ),
        WorkPack(
            "spc", "🪵", "SPC / laminato",
            "Preparazione e posa pavimento flottante con finiture perimetrali.",
            packItems(
                "Metro", "Squadra", "Matite / marcatori", "Taglierino", "Seghetto alternativo", "Troncatrice", "Martello di gomma",
                "Distanziatori pavimento", "Aspiratore professionale", "DPI / protezioni personali", "SPC / laminato", "Materassino pavimento",
                "Barriera vapore", "Battiscopa", "Profili soglia / terminali", "Silicone / sigillante"
            )
        ),
        WorkPack(
            "porta_scorr", "🚪", "Porta scorrevole / Eclisse",
            "Apertura vano, architrave, cassonetto, messa in bolla e ripristini.",
            packItems(
                "Martello demolitore", "Tassellatore", "Smerigliatrice / flex", "Trapano avvitatore", "Laser autolivellante", "Metro", "Livella",
                "Miscelatore elettrico", "Cazzuole e frattazzi", "Secchi da cantiere", "Faro da cantiere", "Prolunga / avvolgicavo",
                "DPI / protezioni personali", "Teli e protezioni", "Sacchi macerie", "Malta", "Rasante", "Primer / fissativo",
                "Architrave", "Cassonetto porta scorrevole", "Tasselli e viti", "Schiuma poliuretanica"
            )
        )
    )

    fun ensureCatalog(store: ToolsStore): Int {
        val existing = store.catalog().map {
            "${it.kind.name}:${it.name.trim().lowercase(Locale.ITALY)}"
        }.toMutableSet()
        var added = 0
        resources.forEach { item ->
            val key = "${item.kind.name}:${item.name.trim().lowercase(Locale.ITALY)}"
            if (key !in existing) {
                store.saveCatalog(
                    CatalogResource(
                        kind = item.kind,
                        name = item.name,
                        category = item.category,
                        unit = item.unit,
                        note = item.note
                    )
                )
                existing += key
                added++
            }
        }
        return added
    }
}
