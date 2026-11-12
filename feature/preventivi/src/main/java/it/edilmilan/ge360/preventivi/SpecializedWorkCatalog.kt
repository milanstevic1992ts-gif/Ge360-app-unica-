package it.edilmilan.ge360.preventivi

/**
 * Lavorazioni di dettaglio per i sopralluoghi EDIL MILAN STEVIC.
 * Non assegna mai prezzi: prezzo e quantità devono essere confermati sul posto.
 * Le voci del capitolato StandardWorkCatalog restano separate e richiamabili.
 */
object SpecializedWorkCatalog {
    private fun w(
        code: String,
        title: String,
        description: String,
        unit: String,
        basis: QuantityBasis,
        category: String
    ) = RoomWorkTemplate(
        code = "SPEC-$code",
        title = title,
        description = description,
        unit = unit,
        basis = basis,
        category = category,
        enabledByDefault = false
    )

    val entries: List<RoomWorkTemplate> = listOf(
        // Demolizione e rimozione
        w("DEM-INTONACO", "Rimozione intonaco esistente",
            "Rimozione localizzata o completa dell'intonaco deteriorato e pulizia del supporto; quantità effettiva da verificare dopo battitura.", "m²", QuantityBasis.WALLS, "Demolizioni"),
        w("DEM-MALTE", "Rimozione malte ammalorate sotto intonaco",
            "Rimozione degli strati di malta incoerenti o distaccati dopo verifica del fondo, con raccolta del materiale di risulta.", "m²", QuantityBasis.WALLS, "Demolizioni"),
        w("DEM-TRAMEZZI", "Demolizione tramezza non portante",
            "Demolizione di divisorio accertato non strutturale; verifiche e autorizzazioni restano a carico dei soggetti competenti.", "m²", QuantityBasis.MANUAL, "Demolizioni"),
        w("DEM-BATTISCOPA", "Rimozione battiscopa esistente",
            "Rimozione controllata del battiscopa senza computare automaticamente la preparazione o posa della nuova finitura.", "ml", QuantityBasis.PERIMETER, "Demolizioni"),
        w("DEM-SPC", "Rimozione pavimento SPC, LVT o laminato",
            "Rimozione della pavimentazione flottante e dei suoi accessori, distinta dalla posa del nuovo pavimento.", "m²", QuantityBasis.FLOOR, "Demolizioni"),

        // Opere murarie e serramenti
        w("RIP-INTONACO", "Rifacimento intonaco e malta di ripristino",
            "Ricostruzione delle malte e dell'intonaco su muratura preparata, con regolarizzazione e asciugatura prima della finitura.", "m²", QuantityBasis.WALLS, "Murature e ripristini"),
        w("RIP-SPALLETTA-FIN", "Ripristino murario finestra dopo posa serramento",
            "Formazione e ripristino spallette, architravatura di finitura e davanzale interno ove previsto, esclusi gli interventi strutturali non autorizzati.", "cad.", QuantityBasis.MANUAL, "Serramenti e aperture"),
        w("RIP-FALSO-FIN", "Ripristino murario finestra dopo falso telaio",
            "Ripristino spallette e finiture dopo la sola posa del falso telaio; eventuali lavori successivi al serramento da verificare separatamente.", "cad.", QuantityBasis.MANUAL, "Serramenti e aperture"),
        w("RIP-SPALLETTA-PF", "Ripristino murario portafinestra",
            "Regolarizzazione e ricostruzione delle spallette dopo installazione della portafinestra, con finitura edile concordata.", "cad.", QuantityBasis.MANUAL, "Serramenti e aperture"),
        w("RIP-FALSO-PF", "Ripristino murario portafinestra dopo falso telaio",
            "Ripristino spallette a seguito della posa del falso telaio, esclusi i ripristini successivi alla posa del serramento completo se non pattuiti.", "cad.", QuantityBasis.MANUAL, "Serramenti e aperture"),
        w("RIP-ARCHITRAVE", "Ripristino o formazione architrave",
            "Opere murarie per architrave solo previa verifica della natura strutturale, del progetto e delle autorizzazioni necessarie.", "cad.", QuantityBasis.MANUAL, "Murature e ripristini"),
        w("RIP-ARCO", "Formazione arco e finitura delle spallette",
            "Modellazione e finitura di un'apertura ad arco su supporto esistente idoneo, con esclusione degli interventi strutturali non verificati.", "cad.", QuantityBasis.MANUAL, "Murature e ripristini"),
        w("RIP-RADIATORE", "Nicchia o ripristino parete termosifone",
            "Ripristino delle malte, creazione o chiusura di nicchia e finiture murarie nell'area del radiatore.", "cad.", QuantityBasis.MANUAL, "Murature e ripristini"),
        w("CAROTAGGIO", "Carotaggio e foro passante",
            "Realizzazione di foro passante su supporto compatibile dopo verifica di impianti e struttura; eventuali autorizzazioni escluse.", "cad.", QuantityBasis.MANUAL, "Murature e ripristini"),

        // Rasature e pitture
        w("RAS-ARMATA", "Rasatura armata con rete",
            "Preparazione supporto, rasante con rete di armatura e sovrapposizioni, copertura, seconda mano e rifinitura.", "m²", QuantityBasis.WALLS, "Rasature e pitture"),
        w("STUCCO-Q2", "Stuccatura cartongesso livello Q2",
            "Trattamento giunti, viti, profili e levigatura per livello di finitura Q2 concordato.", "m²", QuantityBasis.WALLS_CEILING, "Rasature e pitture"),
        w("STUCCO-Q3", "Stuccatura cartongesso livello Q3",
            "Finitura superiore al Q2 con rasatura di affinamento delle superfici previste e levigatura.", "m²", QuantityBasis.WALLS_CEILING, "Rasature e pitture"),
        w("STUCCO-Q4", "Stuccatura cartongesso livello Q4",
            "Rasatura e preparazione estese per finitura ad elevata uniformità, da verificare rispetto a luce e rivestimento finale.", "m²", QuantityBasis.WALLS_CEILING, "Rasature e pitture"),
        w("PARASPIGOLI", "Posa paraspigoli e profili di rinforzo",
            "Posa e allineamento di paraspigoli e profili, fissaggio e raccordo con rasatura delle superfici adiacenti.", "ml", QuantityBasis.MANUAL, "Rasature e pitture"),
        w("PITT-LAV-BIANCA", "Pitturazione lavabile bianca",
            "Preparazione, fissativo ove necessario e pittura lavabile bianca su superfici concordate.", "m²", QuantityBasis.WALLS_CEILING, "Rasature e pitture"),
        w("PITT-LAV-COLORE", "Pitturazione lavabile colorata",
            "Preparazione, campionatura/colore concordato e ciclo di pittura lavabile colorata.", "m²", QuantityBasis.WALLS_CEILING, "Rasature e pitture"),
        w("PITT-TRASPIRANTE", "Pitturazione traspirante",
            "Preparazione e ciclo pittorico traspirante idoneo al supporto e al locale.", "m²", QuantityBasis.WALLS_CEILING, "Rasature e pitture"),
        w("PITT-ANTIMUFFA", "Pitturazione antimuffa",
            "Applicazione del ciclo previsto solo dopo verifica della causa e dell'idoneità del supporto; non sostituisce il risanamento.", "m²", QuantityBasis.WALLS_CEILING, "Rasature e pitture"),
        w("CARTA-PARATI", "Posa carta da parati",
            "Controllo della planarità, preparazione del fondo, posa e giunzione del rivestimento decorativo secondo le istruzioni del prodotto.", "m²", QuantityBasis.WALLS, "Rasature e pitture"),

        // Cartongesso, isolamento e impianti
        w("CART-PARETE", "Parete divisoria in cartongesso",
            "Tracciamento, orditura, lastre, rinforzi e trattamento giunti secondo la stratigrafia prevista.", "m²", QuantityBasis.MANUAL, "Cartongesso e isolamento"),
        w("CART-CONTROSOFFITTO", "Controsoffitto in cartongesso",
            "Orditura e lastre per controsoffitto, esclusa eventuale stuccatura e pittura quando valutate a parte.", "m²", QuantityBasis.CEILING, "Cartongesso e isolamento"),
        w("CART-VELETTA", "Veletta in cartongesso",
            "Struttura, tamponamento e finitura di veletta per mascheramento tubazioni o definizione architettonica.", "ml", QuantityBasis.MANUAL, "Cartongesso e isolamento"),
        w("CART-COPRI-CALDAIA", "Copertura tecnica per caldaia o tubazioni",
            "Realizzazione di volume o veletta ispezionabile, nel rispetto delle distanze e ventilazioni previste dal tecnico abilitato.", "corpo", QuantityBasis.BODY, "Cartongesso e isolamento"),
        w("ISOL-LANA-ROCCIA", "Isolamento in lana di roccia in controparete/controsoffitto",
            "Posa di isolamento fibroso nell'intercapedine prevista, senza garantire prestazioni acustiche non progettate.", "m²", QuantityBasis.MANUAL, "Cartongesso e isolamento"),
        w("ISOL-BARRIERA-VAPORE", "Posa barriera o freno al vapore",
            "Posa della membrana prevista dalla stratigrafia, con nastrature e sigillatura dei raccordi secondo progetto.", "m²", QuantityBasis.MANUAL, "Cartongesso e isolamento"),
        w("IMP-ASSIST-IDR", "Assistenze murarie all'idraulico",
            "Apertura e successiva chiusura delle tracce, predisposizioni e ripristini edili per lavorazioni dell'idraulico.", "corpo", QuantityBasis.BODY, "Impianti e assistenze"),
        w("IMP-ASSIST-ELET", "Assistenze murarie all'elettricista",
            "Tracce, fori e ripristini edili per l'impianto elettrico; installazione e certificazione dell'impianto restano ai soggetti abilitati.", "corpo", QuantityBasis.BODY, "Impianti e assistenze"),
        w("IMP-PRED-FARETTI", "Predisposizione corrugati per faretti",
            "Predisposizione dei passaggi e dei corrugati nei punti concordati, senza installazione dei dispositivi elettrici.", "cad.", QuantityBasis.MANUAL, "Impianti e assistenze"),

        // Pavimenti, massetti ed esterni
        w("PAV-AUTOLIV", "Autolivellante per regolarizzazione pavimento",
            "Verifica del supporto, primer se previsto e stesura di prodotto autolivellante nello spessore concordato.", "m²", QuantityBasis.FLOOR, "Pavimenti e massetti"),
        w("PAV-MASSETTO", "Formazione o rifacimento massetto",
            "Preparazione delle quote e posa del massetto secondo stratigrafia e tempi tecnici previsti.", "m²", QuantityBasis.FLOOR, "Pavimenti e massetti"),
        w("PAV-PREFINITO", "Posa parquet prefinito",
            "Preparazione del supporto e posa del parquet prefinito con sistema previsto, tagli e raccordi.", "m²", QuantityBasis.FLOOR, "Pavimenti e massetti"),
        w("PAV-LEV-PARQUET", "Levigatura / raschiatura parquet esistente",
            "Levigatura e preparazione della pavimentazione in legno esistente, con ciclo di finitura da concordare separatamente.", "m²", QuantityBasis.FLOOR, "Pavimenti e massetti"),
        w("EST-PENDENZE", "Formazione pendenze terrazza o balcone",
            "Preparazione e regolarizzazione delle pendenze prima della successiva impermeabilizzazione e/o posa.", "m²", QuantityBasis.FLOOR, "Terrazzi e impermeabilizzazioni"),
        w("EST-BANDE", "Bandelle e raccordi impermeabilizzazione",
            "Trattamento dei raccordi, degli angoli e dei punti critici con componenti compatibili col sistema impermeabilizzante.", "ml", QuantityBasis.MANUAL, "Terrazzi e impermeabilizzazioni")
    )

    init {
        require(entries.map { it.code }.distinct().size == entries.size) { "Codici specializzati duplicati" }
    }
}
