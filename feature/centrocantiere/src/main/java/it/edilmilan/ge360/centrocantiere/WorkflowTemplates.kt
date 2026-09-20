package it.edilmilan.ge360.centrocantiere

import it.edilmilan.ge360.preventivi.EstimateItem
import java.util.Locale

data class WorkflowTaskTemplate(
    val phase: String,
    val title: String,
    val detail: String = "",
    val workPackId: String = "",
    val photoRequired: Boolean = false,
    val documentRequired: Boolean = false
)

data class WorkflowTemplate(
    val id: String,
    val title: String,
    val description: String,
    val workTypes: Set<String>,
    val tasks: List<WorkflowTaskTemplate>
)

object WorkflowTemplates {
    private fun task(
        phase: String,
        title: String,
        detail: String = "",
        pack: String = "",
        photo: Boolean = false,
        document: Boolean = false
    ) = WorkflowTaskTemplate(phase, title, detail, pack, photo, document)

    val BAGNO_COMPLETO = WorkflowTemplate(
        id = "bagno-completo",
        title = "Bagno completo",
        description = "Sequenza operativa completa dalla protezione alla consegna, con smaltimento, impianti, impermeabilizzazione e finiture.",
        workTypes = setOf("Bagno"),
        tasks = listOf(
            task("Preparazione", "Proteggere passaggi, pavimenti e arredi", pack = "protezioni"),
            task("Preparazione", "Verificare acqua, corrente e punti di intercettazione", "Prima di iniziare demolizioni e smontaggi."),
            task("Demolizione", "Smontare sanitari, rubinetterie e accessori", pack = "demolizioni"),
            task("Demolizione", "Demolire rivestimenti e pavimento previsti", pack = "demolizioni", photo = true),
            task("Demolizione", "Raccogliere, separare e caricare le macerie", pack = "demolizioni"),
            task("Smaltimento", "Trasportare e conferire i rifiuti da demolizione", "Collegare il formulario quando previsto.", pack = "demolizioni", document = true),
            task("Impianti", "Eseguire tracce idrauliche", pack = "tracce", photo = true),
            task("Impianti", "Eseguire tracce elettriche", pack = "tracce", photo = true),
            task("Impianti", "Fotografare impianti prima delle chiusure", "Documentare tubazioni, raccordi, quote e passaggi.", photo = true),
            task("Ripristini", "Chiudere tracce e ripristinare supporti", pack = "muratura"),
            task("Fondi", "Controllare planarità, quote e pendenze", "Correggere il fondo prima delle lavorazioni successive.", pack = "muratura"),
            task("Fondi", "Realizzare massetti o ripristini necessari", pack = "muratura"),
            task("Impermeabilizzazione", "Preparare il fondo e applicare impermeabilizzazione", pack = "impermeabilizzazione", photo = true),
            task("Impermeabilizzazione", "Controllare angoli, bandelle, scarichi e punti critici", pack = "impermeabilizzazione", photo = true),
            task("Posa", "Posare pavimento", pack = "piastrelle"),
            task("Posa", "Posare rivestimento", pack = "piastrelle"),
            task("Finiture", "Stuccare fughe e realizzare siliconature", pack = "piastrelle"),
            task("Installazioni", "Montare sanitari e rubinetterie"),
            task("Finiture", "Eseguire rasature e pittura previste", pack = "pittura"),
            task("Chiusura", "Pulizia finale e controllo difetti"),
            task("Chiusura", "Realizzare foto finali", photo = true),
            task("Chiusura", "Verificare documenti, extra e consegna al cliente", document = true)
        )
    )

    val RISTRUTTURAZIONE = WorkflowTemplate(
        id = "ristrutturazione",
        title = "Ristrutturazione",
        description = "Base flessibile per ristrutturazioni con protezioni, demolizioni, impianti, ripristini e finiture.",
        workTypes = setOf("Ristrutturazione", "Cucina", "Opere murarie", "Manutenzione"),
        tasks = listOf(
            task("Preparazione", "Proteggere zone di passaggio e parti da conservare", pack = "protezioni"),
            task("Preparazione", "Verificare lavorazioni, quote e interferenze"),
            task("Demolizione", "Eseguire demolizioni previste", pack = "demolizioni", photo = true),
            task("Smaltimento", "Caricare e conferire materiali di risulta", pack = "demolizioni", document = true),
            task("Impianti", "Eseguire tracce e predisposizioni previste", pack = "tracce", photo = true),
            task("Impianti", "Fotografare impianti prima delle chiusure", photo = true),
            task("Ripristini", "Chiudere tracce e ripristinare murature", pack = "muratura"),
            task("Fondi", "Controllare planarità e preparare i supporti", pack = "muratura"),
            task("Lavorazioni", "Eseguire lavorazioni principali da preventivo"),
            task("Finiture", "Eseguire rasature, pitture e finiture", pack = "pittura"),
            task("Chiusura", "Pulizia, foto finali e verifica lavori", photo = true),
            task("Chiusura", "Verificare extra, documenti e consegna", document = true)
        )
    )

    val PAVIMENTI = WorkflowTemplate(
        id = "pavimenti",
        title = "Pavimenti",
        description = "Controllo fondo, preparazione, posa e finiture per piastrelle, SPC, LVT o laminato.",
        workTypes = setOf("Pavimenti"),
        tasks = listOf(
            task("Preparazione", "Proteggere le aree non interessate", pack = "protezioni"),
            task("Demolizione", "Rimuovere pavimento o rivestimento esistente se previsto", pack = "demolizioni"),
            task("Fondi", "Controllare planarità, umidità e consistenza del supporto", photo = true),
            task("Fondi", "Rasare o livellare il fondo dove necessario", pack = "muratura"),
            task("Posa", "Impostare direzione, prima fila e giunti"),
            task("Posa", "Posare il pavimento previsto", pack = "piastrelle"),
            task("Finiture", "Posare battiscopa, profili e terminali"),
            task("Chiusura", "Pulizia e foto finali", photo = true)
        )
    )

    val CARTONGESSO = WorkflowTemplate(
        id = "cartongesso",
        title = "Cartongesso",
        description = "Tracciamento, struttura, impianti, lastre e finiture.",
        workTypes = setOf("Cartongesso"),
        tasks = listOf(
            task("Preparazione", "Proteggere area di lavoro", pack = "protezioni"),
            task("Tracciamento", "Tracciare quote, livelli e ingombri", pack = "cartongesso"),
            task("Struttura", "Montare struttura metallica", pack = "cartongesso", photo = true),
            task("Impianti", "Eseguire predisposizioni prima della chiusura", photo = true),
            task("Chiusura", "Posare lastre e rinforzi previsti", pack = "cartongesso"),
            task("Finiture", "Stuccare giunti e paraspigoli", pack = "cartongesso"),
            task("Finiture", "Levigare e preparare alla pittura", pack = "cartongesso"),
            task("Chiusura", "Pulizia e foto finali", photo = true)
        )
    )

    val TINTEGGIATURA = WorkflowTemplate(
        id = "tinteggiatura",
        title = "Tinteggiatura",
        description = "Protezione, preparazione, fissativo, pittura e consegna.",
        workTypes = setOf("Tinteggiatura"),
        tasks = listOf(
            task("Preparazione", "Proteggere pavimenti, serramenti e arredi", pack = "protezioni"),
            task("Preparazione", "Controllare crepe, distacchi e supporto", photo = true),
            task("Preparazione", "Stuccare, rasare e carteggiare dove necessario", pack = "pittura"),
            task("Pittura", "Applicare fissativo o primer quando necessario", pack = "pittura"),
            task("Pittura", "Applicare mani di pittura previste", pack = "pittura"),
            task("Chiusura", "Ritocchi, pulizia e foto finali", photo = true)
        )
    )

    val TERRAZZA = WorkflowTemplate(
        id = "terrazza",
        title = "Terrazza",
        description = "Demolizione, controllo supporto, pendenze, impermeabilizzazione, posa e finiture.",
        workTypes = setOf("Terrazza"),
        tasks = listOf(
            task("Preparazione", "Proteggere accessi e aree adiacenti", pack = "protezioni"),
            task("Demolizione", "Rimuovere pavimento e strati previsti", pack = "demolizioni", photo = true),
            task("Smaltimento", "Caricare e conferire materiali di risulta", pack = "demolizioni", document = true),
            task("Fondi", "Controllare supporto, crepe, quote e pendenze", photo = true),
            task("Fondi", "Ripristinare massetto e pendenze", pack = "muratura"),
            task("Impermeabilizzazione", "Impermeabilizzare superficie e punti critici", pack = "impermeabilizzazione", photo = true),
            task("Posa", "Posare pavimentazione", pack = "piastrelle"),
            task("Finiture", "Stuccare fughe e sigillare raccordi", pack = "piastrelle"),
            task("Controlli", "Verificare scarichi e deflusso acqua", photo = true),
            task("Chiusura", "Pulizia e foto finali", photo = true)
        )
    )

    val DEMOLIZIONI = WorkflowTemplate(
        id = "demolizioni",
        title = "Demolizioni e smaltimento",
        description = "Protezione, demolizione, raccolta, trasporto e documentazione rifiuti.",
        workTypes = emptySet(),
        tasks = listOf(
            task("Preparazione", "Proteggere area di lavoro e passaggi", pack = "protezioni"),
            task("Demolizione", "Eseguire demolizioni previste", pack = "demolizioni", photo = true),
            task("Demolizione", "Separare e raccogliere materiali di risulta", pack = "demolizioni"),
            task("Smaltimento", "Caricare e trasportare i rifiuti", pack = "demolizioni"),
            task("Smaltimento", "Collegare FIR o documento di conferimento quando previsto", document = true)
        )
    )

    val ALL = listOf(BAGNO_COMPLETO, RISTRUTTURAZIONE, PAVIMENTI, CARTONGESSO, TINTEGGIATURA, TERRAZZA, DEMOLIZIONI)

    fun suggestedFor(workType: String): WorkflowTemplate {
        val normalized = workType.trim().lowercase(Locale.ITALY)
        return ALL.firstOrNull { template -> template.workTypes.any { it.lowercase(Locale.ITALY) == normalized } }
            ?: RISTRUTTURAZIONE
    }

    fun fromEstimateItem(item: EstimateItem): WorkflowTaskTemplate {
        val raw = listOf(item.name, item.description, item.category, item.section, item.room)
            .joinToString(" ")
            .lowercase(Locale.ITALY)
        val title = item.name.trim().ifBlank { item.description.trim().take(90).ifBlank { "Lavorazione da preventivo" } }
        return when {
            containsAny(raw, "demol", "rimozion", "smont") -> task("Demolizione", title, item.description, "demolizioni", photo = true)
            containsAny(raw, "smalt", "macer", "rifiut", "trasporto", "conferimento", "fir") -> task("Smaltimento", title, item.description, "demolizioni", document = true)
            containsAny(raw, "idraulic", "scaric", "tubaz", "sanitari", "rubinett") -> task("Impianti", title, item.description, "tracce", photo = true)
            containsAny(raw, "elettric", "presa", "interrutt", "punto luce") -> task("Impianti", title, item.description, "tracce", photo = true)
            containsAny(raw, "impermeabil", "guaina", "bandella") -> task("Impermeabilizzazione", title, item.description, "impermeabilizzazione", photo = true)
            containsAny(raw, "piastrell", "rivestiment", "gres", "fug", "posa pav") -> task("Posa", title, item.description, "piastrelle")
            containsAny(raw, "spc", "laminat", "lvt", "battiscopa") -> task("Posa", title, item.description, "pavimenti")
            containsAny(raw, "cartong", "controsoff", "veletta") -> task("Cartongesso", title, item.description, "cartongesso", photo = true)
            containsAny(raw, "rasatur", "stucc", "pittur", "tintegg", "fissativ") -> task("Finiture", title, item.description, "pittura")
            containsAny(raw, "masset", "muratur", "riprist", "traccia", "intonac") -> task("Ripristini", title, item.description, "muratura")
            else -> task("Lavorazioni", title, item.description)
        }
    }

    private fun containsAny(value: String, vararg needles: String): Boolean = needles.any(value::contains)
}
