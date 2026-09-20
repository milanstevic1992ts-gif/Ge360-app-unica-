package it.edilmilan.ge360.preventivi

import java.util.Locale
import java.util.UUID

data class StandardWorkEntry(
    val code: String,
    val number: Int,
    val group: String,
    val title: String,
    val description: String,
    val unit: String,
    val section: String = "Resto della casa",
    val references: List<String> = emptyList(),
    val tags: List<String> = emptyList()
) {
    fun searchableText(): String = listOf(code, number.toString(), group, title, description, references.joinToString(" "), tags.joinToString(" "))
        .joinToString(" ")
        .lowercase(Locale.ROOT)

    fun customerDescription(): String = buildString {
        append(description.trim())
        if (references.isNotEmpty()) {
            append("\n\nRiferimenti tecnici, ove applicabili: ")
            append(references.joinToString("; "))
            append('.')
        }
    }

    fun toEstimateItem(): EstimateItem = EstimateItem(
        ge360Id = UUID.randomUUID().toString(),
        priceListCode = code,
        section = section,
        category = group,
        name = title,
        description = customerDescription(),
        quantity = 1.0,
        unit = unit,
        unitPrice = 0.0,
        priceMode = if (unit in listOf("corpo", "forfait")) "body" else "unit",
        included = true,
        optional = false
    )
}

object StandardWorkCatalog {
    const val VERSION = "2026.09"
    const val PREFIX = "CAP-"

    const val GENERAL_CLAUSE = """Le lavorazioni saranno eseguite secondo la regola dell’arte e nel rispetto delle disposizioni normative applicabili alla specifica opera. I prodotti saranno impiegati secondo le rispettive schede tecniche, destinazioni d’uso, condizioni del supporto e tempi tecnici prescritti dal produttore. Eventuali difetti, anomalie o condizioni del supporto non rilevabili prima delle demolizioni o della rimozione dei rivestimenti saranno valutati dopo la loro scoperta. Qualora le condizioni effettive di sottofondi, murature, impianti o strutture risultino differenti da quelle rilevabili al momento del preventivo, le eventuali lavorazioni aggiuntive necessarie saranno comunicate e quantificate prima della loro esecuzione."""

    val MAIN_REFERENCES = listOf(
        "D.Lgs. 81/2008 e s.m.i. — salute e sicurezza sul lavoro",
        "D.Lgs. 152/2006 e s.m.i., Parte IV — gestione dei rifiuti",
        "UNI 11493-1:2025 — piastrellature ceramiche",
        "UNI EN 12004-1:2017 — adesivi per piastrelle ceramiche",
        "UNI EN 13888-1:2022 — sigillanti per fughe di piastrelle ceramiche",
        "UNI EN 14891:2017 — impermeabilizzanti liquidi sotto piastrellatura",
        "UNI 11944:2024 — massetti per pavimentazioni",
        "UNI 11424:2015 — sistemi in lastre di gesso rivestito su orditura metallica",
        "UNI 11515-1:2026 — pavimentazioni interne resilienti e laminate",
        "UNI EN ISO 13788:2013 — valutazione del rischio di condensazione e muffa"
    )

    val entries: List<StandardWorkEntry> = listOf(
        e(1, "Cantiere e protezioni", "Allestimento e protezione del cantiere", """Organizzazione preliminare dell’area di lavoro e delle fasi operative; individuazione delle zone di deposito temporaneo; predisposizione di utensili e attrezzature; protezione di pavimenti, porte, serramenti, arredi fissi, spigoli e percorsi mediante materiali idonei; eventuale separazione provvisoria delle aree per limitare la diffusione delle polveri; raccolta progressiva dei materiali di risulta e pulizia sommaria al termine delle fasi di lavoro.""", "corpo", refs = listOf("D.Lgs. 81/2008 e s.m.i."), tags = listOf("protezione", "nylon", "cartone", "polvere")),
        e(2, "Demolizioni e smaltimenti", "Demolizioni e rimozioni", """Demolizione o rimozione controllata, secondo quanto previsto dal singolo intervento, di pavimenti, rivestimenti a parete, laminato/SPC/parquet flottante, battiscopa, vasca, piatto doccia, muretti e manufatti non strutturali. Comprende la rimozione delle parti incoerenti strettamente interessate, la raccolta dei materiali e la preparazione iniziale dei supporti. Qualora un elemento possa avere funzione strutturale, l’intervento resta subordinato alle verifiche tecniche necessarie.""", "corpo", tags = listOf("demolizione", "rimozione", "vasca", "piatto doccia", "pavimento", "rivestimento")),
        e(3, "Demolizioni e smaltimenti", "Raccolta, trasporto e smaltimento materiali di risulta", """Raccolta e, ove necessario, insaccamento dei materiali provenienti dalle lavorazioni; movimentazione fino al punto di carico; carico sul mezzo; trasporto e conferimento presso impianto autorizzato secondo tipologia e classificazione del rifiuto, nel rispetto degli adempimenti ambientali applicabili.""", "corpo", refs = listOf("D.Lgs. 152/2006 e s.m.i., Parte IV"), tags = listOf("macerie", "discarica", "rifiuti", "trasporto")),
        e(4, "Murature e ripristini", "Ripristino delle murature", """Battitura e verifica preliminare delle superfici; rimozione di intonaci, malte e parti incoerenti; pulizia del fondo; ricostruzione localizzata con malte compatibili; riempimento di cavità e mancanze; correzione delle principali irregolarità e preparazione del supporto per rasatura, pitturazione, impermeabilizzazione o rivestimento.""", "m²", tags = listOf("intonaco", "malta", "ripristino", "muratura")),
        e(5, "Murature e ripristini", "Chiusura delle tracce impiantistiche", """Pulizia delle tracce dopo le lavorazioni impiantistiche, eliminazione di polvere e materiale incoerente, eventuale preparazione del supporto, riempimento con malta idonea, ricostruzione progressiva dello spessore, regolarizzazione, rasatura e preparazione per la successiva pitturazione o posa del rivestimento.""", "ml", tags = listOf("tracce", "idraulico", "elettricista", "malta")),
        e(6, "Massetti e supporti", "Massetti e sottofondi", """Demolizione del massetto ove prevista; raccolta dei materiali; pulizia e preparazione del fondo; rimozione delle parti friabili; regolarizzazione delle zone deteriorate; definizione delle quote; posa della rete elettrosaldata quando prevista con sovrapposizione e legatura; formazione delle guide; getto, distribuzione, staggiatura, livellamento e regolarizzazione finale; rispetto dei tempi tecnici prima delle lavorazioni successive.""", "m²", refs = listOf("UNI 11944:2024, ove applicabile"), tags = listOf("massetto", "rete", "elettrosaldata", "getto", "guide")),
        e(7, "Massetti e supporti", "Rasatura e preparazione dei supporti", """Preparazione del supporto e applicazione di rasante localizzato o completo; regolarizzazione; eventuale seconda mano e rifinitura. Quando prevista la rasatura armata, inserimento di rete idonea nel primo strato, corretta sovrapposizione, copertura con successivo strato di rasante e finitura finale.""", "m²", tags = listOf("rasatura", "rete", "supporto")),
        e(8, "Pitture e finiture", "Stuccatura e finitura di pareti e soffitti", """Stuccatura di piccoli fori, imperfezioni e fessurazioni superficiali; ripristino delle zone interessate da lavorazioni murarie; regolarizzazione delle superfici; carteggiatura e preparazione per fissativo e pittura. Sulle superfici in cartongesso il livello di finitura viene definito in funzione della finitura finale richiesta e delle condizioni del supporto.""", "m²", tags = listOf("stucco", "carteggiatura", "pareti", "soffitto")),
        e(9, "Pitture e finiture", "Pitturazione", """Protezione delle superfici adiacenti, spolveratura e pulizia; stuccatura e carteggiatura ove necessarie; applicazione di primer o fissativo compatibile; applicazione delle mani di pittura necessarie alla copertura uniforme. Per bagni e ambienti umidi viene impiegato un ciclo pittorico specifico, compatibile con il supporto e con le condizioni ambientali.""", "m²", tags = listOf("pittura", "fissativo", "bagno", "lavabile")),
        e(10, "Pitture e finiture", "Trattamento di superfici con presenza di muffa", """Rimozione delle finiture deteriorate, pulizia e trattamento preliminare ove previsto; ripristino di intonaco o rasatura ammalorati e applicazione del ciclo di finitura concordato. Quando previsto, posa di un sistema termico interno con pannelli specifici, trattamento dei giunti, rasatura e preparazione alla pittura. La soluzione deve essere scelta in funzione della causa effettiva del fenomeno, che può dipendere da condensazione, ponti termici, infiltrazioni o umidità della muratura.""", "m²", refs = listOf("UNI EN ISO 13788:2013, ove pertinente"), tags = listOf("muffa", "condensa", "ponte termico", "pannelli")),
        e(11, "Cartongesso", "Opere in cartongesso", """Tracciamento e installazione dell’orditura metallica; realizzazione, secondo progetto, di pareti, contropareti, controsoffitti o velette; posa delle lastre; eventuali rinforzi, inserimento di materiale isolante o pannelli OSB; trattamento dei giunti, stuccatura delle viti, protezione degli spigoli e preparazione della superficie alla finitura.""", "m²", refs = listOf("UNI 11424:2015"), tags = listOf("cartongesso", "controsoffitto", "veletta", "osb", "lana di roccia")),
        e(12, "Pavimenti e rivestimenti", "Preparazione dei pavimenti esistenti", """Verifica, per quanto rilevabile in fase esecutiva, di consistenza, stabilità, pulizia, planarità, parti distaccate, fughe profonde e dislivelli; pulizia e rasatura delle fughe quando si posa su pavimento esistente; eventuale primerizzazione e stesura di autolivellante nello spessore necessario, nel rispetto delle prescrizioni e dei tempi di maturazione del sistema utilizzato.""", "m²", tags = listOf("fughe", "autolivellante", "planarità", "primer")),
        e(13, "Pavimenti e rivestimenti", "Posa pavimento SPC / LVT / laminato", """Verifica preliminare e controllo della planarità del supporto; eventuale preparazione o regolarizzazione; posa del materassino previsto dal sistema; posa delle doghe secondo schema concordato; tagli perimetrali; rispetto degli spazi di dilatazione; raccordi tra ambienti e installazione degli eventuali profili di finitura.""", "m²", refs = listOf("UNI 11515-1:2026"), tags = listOf("spc", "lvt", "laminato", "flottante")),
        e(14, "Pavimenti e rivestimenti", "Posa di piastrelle", """Controllo di consistenza e planarità del supporto; pulizia, eventuale primerizzazione e regolarizzazione; tracciamento delle linee di posa; preparazione e stesura dell’adesivo con spatola adeguata; posa, controllo di allineamenti e quote, formazione delle fughe, tagli e pulizia progressiva. Doppia spalmatura quando richiesta da formato, supporto, destinazione d’uso o sistema di posa. Fugatura con prodotto idoneo e pulizia finale.""", "m²", refs = listOf("UNI 11493-1:2025", "UNI EN 12004-1:2017", "UNI EN 13888-1:2022"), tags = listOf("piastrelle", "posa", "fugatura", "doppia spalmatura")),
        e(15, "Pavimenti e rivestimenti", "Posa di piastrelle di grande formato e grandi lastre", """Verifica particolarmente accurata della planarità; movimentazione e taglio con attrezzature idonee; preparazione delle superfici; distribuzione dell’adesivo secondo sistema previsto; doppia spalmatura ove necessaria; controllo della copertura e dell’adesione; eventuali sistemi di livellamento; realizzazione dei giunti, fughe e finiture previste.""", "m²", refs = listOf("UNI 11493-1:2025"), tags = listOf("grandi lastre", "grande formato", "livellatori")),
        e(16, "Pavimenti e rivestimenti", "Posa mosaico", """Preparazione e regolarizzazione accurata del supporto; tracciamento; applicazione dell’adesivo; posa dei fogli o degli elementi; controllo dell’allineamento delle tessere; tagli; fugatura e pulizia finale.""", "m²", refs = listOf("UNI 11493-1:2025, ove applicabile"), tags = listOf("mosaico")),
        e(17, "Pavimenti e rivestimenti", "Tagli a 45° e finiture speciali", """Misurazione e taglio degli elementi con attrezzatura idonea; formazione e rifinitura dello spigolo a 45°; accostamento, controllo dell’allineamento e sigillatura o fugatura secondo il sistema previsto. Lavorazione specialistica da conteggiare separatamente rispetto alla posa ordinaria.""", "ml", tags = listOf("45", "jolly", "spigolo", "taglio")),
        e(18, "Pavimenti e rivestimenti", "Posa battiscopa", """Pulizia e preparazione della fascia di posa; taglio degli elementi; applicazione dell’adesivo; posizionamento; realizzazione degli angoli; eventuale sigillatura o stuccatura e pulizia finale.""", "ml", tags = listOf("battiscopa", "zoccolino")),
        e(19, "Pavimenti e rivestimenti", "Profili di finitura e raccordo", """Misurazione, taglio e posizionamento del profilo; fissaggio; realizzazione dei raccordi tra pavimentazioni differenti o quote diverse e rifinitura finale.""", "ml", tags = listOf("profili", "raccordo", "soglia")),
        e(20, "Impermeabilizzazioni e bagno", "Impermeabilizzazione sotto piastrella", """Pulizia, rimozione delle parti incoerenti e regolarizzazione del supporto; verifica della compatibilità con il sistema impermeabilizzante; trattamento di angoli, raccordi parete-parete e parete-pavimento, attraversamenti e punti singolari con componenti previsti dal sistema; applicazione degli strati prescritti rispettando consumi e tempi indicati dal produttore; controllo della continuità e maturazione prima della posa del rivestimento.""", "m²", refs = listOf("UNI EN 14891:2017 per i prodotti liquidi, ove applicabile", "UNI 11493-1:2025 per la piastrellatura"), tags = listOf("impermeabilizzazione", "nanoflex", "guaina", "bande")),
        e(21, "Impermeabilizzazioni e bagno", "Impermeabilizzazione zona doccia", """Preparazione di pareti e pavimento; trattamento degli angoli e dei raccordi; cura dei punti di collegamento con piatto doccia o scarico; applicazione continua del sistema impermeabilizzante nelle zone previste e preparazione per la successiva posa delle piastrelle.""", "corpo", "Bagno", refs = listOf("UNI EN 14891:2017, ove applicabile", "UNI 11493-1:2025"), tags = listOf("doccia", "impermeabilizzazione")),
        e(22, "Impermeabilizzazioni e bagno", "Doccia a filo pavimento", """Demolizione delle porzioni necessarie e pulizia del supporto; definizione della quota finita; ricostruzione del sottofondo e formazione delle pendenze verso lo scarico; regolarizzazione; trattamento dei raccordi e impermeabilizzazione con particolare cura dello scarico; posa del rivestimento mantenendo le pendenze, tagli, fugatura e finiture.""", "corpo", "Bagno", refs = listOf("UNI EN 14891:2017, ove applicabile", "UNI 11493-1:2025"), tags = listOf("filo pavimento", "pendenza", "scarico")),
        e(23, "Impermeabilizzazioni e bagno", "Piatto doccia in muratura", """Preparazione del fondo; costruzione della base; formazione delle pendenze e regolarizzazione; impermeabilizzazione; trattamento di angoli e raccordo con lo scarico; preparazione e successivo rivestimento con piastrelle o mosaico secondo progetto.""", "corpo", "Bagno", refs = listOf("UNI EN 14891:2017, ove applicabile", "UNI 11493-1:2025"), tags = listOf("piatto doccia", "muratura")),
        e(24, "Impermeabilizzazioni e bagno", "Realizzazione nicchia doccia", """Tracciamento e apertura o realizzazione della struttura; formazione dei lati e del fondo con adeguata conformazione; regolarizzazione; impermeabilizzazione completa; posa del rivestimento, tagli, fugatura e finitura degli spigoli.""", "cad.", "Bagno", refs = listOf("UNI EN 14891:2017, ove applicabile", "UNI 11493-1:2025"), tags = listOf("nicchia", "doccia")),
        e(25, "Cartongesso e vani tecnici", "Nicchia lavatrice e asciugatrice", """Realizzazione o adattamento del vano mediante opere murarie o in cartongesso; correzione degli allineamenti; ripristino delle superfici; rasatura e preparazione alla finitura.""", "cad.", tags = listOf("lavatrice", "asciugatrice", "nicchia")),
        e(26, "Cartongesso e vani tecnici", "Chiusura di nicchia esistente", """Preparazione del vano; realizzazione della struttura di chiusura; collegamento alle superfici esistenti; rinforzo dei raccordi; rasatura e preparazione alla pitturazione o al rivestimento.""", "cad.", tags = listOf("nicchia", "chiusura")),
        e(27, "Murature e ripristini", "Muretto per cassetta WC da incasso", """Realizzazione delle opere murarie necessarie al contenimento della cassetta; costruzione del muretto o della controparete; collegamento alle murature esistenti; regolarizzazione e preparazione per impermeabilizzazione o rivestimento. Le apparecchiature interne devono essere preventivamente posizionate secondo le rispettive competenze.""", "cad.", "Bagno", tags = listOf("wc", "cassetta", "geberit", "muretto")),
        e(28, "Murature e ripristini", "Costruzione di nuovo muretto", """Tracciamento; preparazione delle superfici di collegamento; realizzazione della muratura; collegamento agli elementi esistenti; controllo di verticalità e allineamento; intonacatura o rasatura e preparazione alla finitura.""", "m²", tags = listOf("muretto", "muratura")),
        e(29, "Cartongesso e vani tecnici", "Controparete tecnica", """Tracciamento e realizzazione della struttura; predisposizione degli spazi necessari alle installazioni tecniche previste; chiusura; trattamento dei giunti; rasatura e preparazione alla successiva finitura.""", "m²", refs = listOf("UNI 11424:2015 se realizzata in sistemi a secco"), tags = listOf("controparete", "tecnica", "impianti")),
        e(30, "Murature e ripristini", "Modifica vano porta", """Tracciamento della nuova dimensione; demolizione controllata delle porzioni previste; ricostruzione delle parti necessarie; formazione delle spallette; regolarizzazione e ripristino dell’intonaco; preparazione per il nuovo serramento interno. In presenza di elementi strutturali, architravi o murature potenzialmente portanti, l’intervento è subordinato alle verifiche e prescrizioni tecniche necessarie.""", "cad.", tags = listOf("porta", "vano", "spallette", "architrave")),
        e(31, "Murature e ripristini", "Porta scorrevole con controtelaio a scomparsa", """Tracciamento e preparazione dell’apertura mediante demolizioni strettamente necessarie; posizionamento del controtelaio con controllo di quota, piombo e livello; fissaggio; completamento della muratura o del rivestimento del cassonetto; ripristino delle spallette, rasatura e preparazione alla pitturazione.""", "cad.", tags = listOf("porta scorrevole", "scrigno", "controtelaio", "cassonetto")),
        e(32, "Impermeabilizzazioni e bagno", "Trasformazione vasca in doccia", """Rimozione della vasca e delle parti murarie strettamente necessarie; pulizia; ripristino e regolarizzazione di fondo e pareti; formazione della nuova zona doccia; impermeabilizzazione; posa dei nuovi rivestimenti; fugatura, sigillature e finiture edili.""", "corpo", "Bagno", refs = listOf("UNI EN 14891:2017, ove applicabile", "UNI 11493-1:2025"), tags = listOf("vasca", "doccia", "conversione")),
        e(33, "Murature e ripristini", "Rimozione di scalino e regolarizzazione del bagno", """Demolizione dello scalino esistente; raccolta dei materiali; preparazione del supporto; ricostruzione del fondo alla nuova quota; regolarizzazione e preparazione per impermeabilizzazione e/o posa.""", "corpo", "Bagno", tags = listOf("scalino", "bagno", "quota")),
        e(34, "Cartongesso", "Controsoffitto bagno", """Tracciamento della quota; installazione dell’orditura metallica; impiego di lastre idonee alle condizioni dell’ambiente; chiusura, trattamento dei giunti, stuccatura e preparazione per un ciclo pittorico adatto all’ambiente bagno.""", "m²", "Bagno", refs = listOf("UNI 11424:2015"), tags = listOf("controsoffitto", "bagno")),
        e(35, "Cucina", "Cucina — ripristini edili", """Pacchetto di lavorazioni edili da comporre secondo necessità: demolizione dei rivestimenti esistenti, ripristino delle murature, chiusura delle tracce, regolarizzazione e rasatura, posa di nuovi rivestimenti o pavimenti, battiscopa, stuccatura, pitturazione e finiture finali. Nel singolo preventivo devono essere mantenute esclusivamente le lavorazioni effettivamente comprese.""", "corpo", tags = listOf("cucina", "ripristini")),
        e(36, "Esterni", "Terrazze e balconi", """Protezione delle zone adiacenti; rimozione delle parti deteriorate, pulizia e verifica del supporto; ripristino delle malte e regolarizzazione; eventuale trattamento delle pendenze interessate; quando previsto, impermeabilizzazione sotto pavimentazione con cura dei raccordi e rispetto dei tempi tecnici; posa del nuovo pavimento, fughe, giunti e finiture perimetrali.""", "m²", "Esterni", refs = listOf("UNI 11493-1:2025, ove pertinente", "UNI EN 14891:2017 per sistemi liquidi sotto piastrella, ove applicabile"), tags = listOf("terrazzo", "balcone", "esterno", "impermeabilizzazione")),
        e(37, "Pitture e finiture", "Isolamento termico interno localizzato", """Preparazione della parete, eliminazione delle parti deteriorate e regolarizzazione; posa del sistema isolante previsto; cura dei raccordi; rasatura e preparazione alla pitturazione. In presenza di condensa o muffa, la compatibilità della soluzione deve essere valutata rispetto alle condizioni termoigrometriche della parete.""", "m²", refs = listOf("UNI EN ISO 13788:2013, ove pertinente"), tags = listOf("isolamento", "termico", "muffa", "condensa")),
        e(38, "Cantiere e protezioni", "Lavorazioni in appartamento abitato", """Maggiori protezioni degli ambienti e dei percorsi utilizzati dal cliente; delimitazione delle aree operative; organizzazione delle lavorazioni per fasi; pulizia più frequente e movimentazione controllata dei materiali. La permanenza degli occupanti può comportare tempi operativi maggiori rispetto a un immobile completamente libero.""", "corpo", tags = listOf("abitato", "protezione", "occupato")),
        e(39, "Cantiere e protezioni", "Accesso difficoltoso e trasporto manuale", """In assenza di ascensore, montacarichi o accesso diretto: movimentazione manuale di materiali, attrezzature e materiali di risulta, con maggiori operazioni di carico e scarico. L’eventuale maggiorazione viene definita in funzione del piano, della distanza, del peso e delle condizioni effettive di accesso.""", "corpo", tags = listOf("scale", "senza ascensore", "trasporto", "piano")),
        e(40, "Demolizioni e smaltimenti", "Smaltimenti eccezionali", """Gestione separata dei materiali che, per quantità, peso, dimensioni, tipologia o classificazione, richiedono modalità differenti rispetto al normale materiale da demolizione. La voce viene valutata separatamente in base alle condizioni effettive e alla normativa ambientale applicabile.""", "corpo", refs = listOf("D.Lgs. 152/2006 e s.m.i., Parte IV"), tags = listOf("smaltimento", "eccezionale", "rifiuti")),
        e(41, "Pitture e finiture", "Sigillature e finiture edili", """Sigillatura dei raccordi previsti; chiusura delle piccole discontinuità; rifinitura di spigoli e raccordi tra materiali differenti; sistemazione delle piccole imperfezioni derivanti dalle lavorazioni e preparazione alla consegna.""", "corpo", tags = listOf("sigillante", "silicone", "finiture")),
        e(42, "Cantiere e protezioni", "Pulizia finale delle aree di lavoro", """Raccolta degli ultimi materiali di risulta; rimozione delle protezioni di cantiere; pulizia sommaria delle superfici interessate; rimozione delle principali tracce di lavorazione e riordino dell’area. La voce identifica la pulizia conseguente alle opere edili e non un servizio professionale di pulizia dell’intero immobile.""", "corpo", tags = listOf("pulizia", "fine lavori", "consegna"))
    )

    val groups: List<String> = entries.map { it.group }.distinct()

    fun search(query: String = "", group: String? = null): List<StandardWorkEntry> {
        val q = query.trim().lowercase(Locale.ROOT)
        return entries.filter { entry ->
            (group.isNullOrBlank() || entry.group == group) && (q.isBlank() || entry.searchableText().contains(q))
        }
    }

    fun byCode(code: String): StandardWorkEntry? = entries.firstOrNull { it.code == code }

    fun referencesFor(items: List<EstimateItem>): List<String> {
        val codes = items.map { it.priceListCode }.toSet()
        return entries.filter { it.code in codes }.flatMap { it.references }.distinct()
    }

    private fun e(
        number: Int,
        group: String,
        title: String,
        description: String,
        unit: String,
        section: String = "Resto della casa",
        refs: List<String> = emptyList(),
        tags: List<String> = emptyList()
    ) = StandardWorkEntry(
        code = "$PREFIX${number.toString().padStart(2, '0')}",
        number = number,
        group = group,
        title = title,
        description = description,
        unit = unit,
        section = section,
        references = refs,
        tags = tags
    )
}