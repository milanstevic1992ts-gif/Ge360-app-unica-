package it.edilmilan.ge360.preventivi

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.math.round

object EstimateStatuses {
    const val DRAFT = "Bozza"
    const val REVIEW = "Da controllare"
    const val READY = "Pronto"
    const val SENT = "Inviato"
    const val ACCEPTED = "Accettato"
    const val REJECTED = "Rifiutato"
    const val EXPIRED = "Scaduto"
    const val IN_PROGRESS = "Lavoro iniziato"
    const val COMPLETED = "Completato"
    val ALL = listOf(DRAFT, REVIEW, READY, SENT, ACCEPTED, REJECTED, EXPIRED, IN_PROGRESS, COMPLETED)

    fun fromLegacy(value: String): String = when (value.lowercase(Locale.ROOT)) {
        "draft" -> DRAFT; "review" -> REVIEW; "ready" -> READY; "sent" -> SENT
        "accepted" -> ACCEPTED; "rejected" -> REJECTED; "expired" -> EXPIRED
        "in_progress" -> IN_PROGRESS; "completed" -> COMPLETED
        else -> value.ifBlank { DRAFT }
    }
}

data class PriceEntry(
    var id: Long = 0,
    var code: String = "",
    var name: String = "",
    var category: String = "Personalizzate",
    var unit: String = "corpo",
    var suggestedPrice: Double? = null,
    var priceMode: String = "unit",
    var active: Boolean = true,
    var updatedAt: Long = System.currentTimeMillis()
)

data class EstimateItem(
    var id: Long = 0,
    var ge360Id: String = "",
    var estimateId: Long = 0,
    var priceListCode: String = "",
    var section: String = "Resto della casa",
    var room: String = "",
    var category: String = "Personalizzate",
    var name: String = "Lavorazione",
    var description: String = "",
    var quantity: Double = 1.0,
    var unit: String = "corpo",
    var unitPrice: Double = 0.0,
    var priceMode: String = "unit",
    var included: Boolean = true,
    var optional: Boolean = false,
    var internalNote: String = "",
    var total: Double = 0.0
)

data class EstimateRecord(
    var id: Long = 0,
    var ge360Id: String = "",
    var legacyId: String = "",
    var number: String = "",
    var clientId: Long = 0,
    var clientGe360Id: String = "",
    var clientName: String = "",
    var jobsiteId: String = "",
    var jobsiteCode: String = "",
    var jobsiteTitle: String = "",
    var title: String = "",
    var workType: String = "",
    var status: String = EstimateStatuses.DRAFT,
    var documentType: String = "Provvisorio",
    var validityDays: Int = 30,
    var generalDescription: String = "",
    var materialsIncluded: String = "",
    var materialsClient: String = "",
    var exclusions: String = "",
    var finalNotes: String = "",
    var discountType: String = "none",
    var discountValue: Double = 0.0,
    var surchargeType: String = "none",
    var surchargeValue: Double = 0.0,
    var rounding: Double = 0.0,
    var subtotal: Double = 0.0,
    var finalTotal: Double = 0.0,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var items: MutableList<EstimateItem> = mutableListOf()
)

data class EstimateTotals(val subtotal: Double, val discount: Double, val surcharge: Double, val rounding: Double, val finalTotal: Double)

fun calculateItemTotal(item: EstimateItem): Double {
    if (!item.included || item.optional) return 0.0
    val total = if (item.priceMode == "body" || item.unit in listOf("corpo", "forfait")) item.unitPrice else item.quantity * item.unitPrice
    return round2(total.coerceAtLeast(0.0))
}

fun recalculateEstimate(record: EstimateRecord): EstimateTotals {
    record.items.forEach { it.total = calculateItemTotal(it) }
    val subtotal = round2(record.items.sumOf { it.total })
    val discount = adjust(subtotal, record.discountType, record.discountValue)
    val afterDiscount = round2(subtotal - discount)
    val surcharge = adjust(afterDiscount, record.surchargeType, record.surchargeValue)
    val final = round2(afterDiscount + surcharge + record.rounding)
    record.subtotal = subtotal
    record.finalTotal = final
    return EstimateTotals(subtotal, discount, surcharge, record.rounding, final)
}

private fun adjust(base: Double, type: String, value: Double): Double = when (type) {
    "percent" -> round2(base * value.coerceAtLeast(0.0) / 100.0)
    "fixed" -> round2(value.coerceAtLeast(0.0))
    else -> 0.0
}
private fun round2(value: Double) = round(value * 100.0) / 100.0

class EstimateStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE estimates(
            id INTEGER PRIMARY KEY AUTOINCREMENT, ge360_id TEXT NOT NULL UNIQUE, legacy_id TEXT NOT NULL DEFAULT '', number TEXT NOT NULL DEFAULT '',
            client_id INTEGER NOT NULL DEFAULT 0, client_ge360_id TEXT NOT NULL DEFAULT '', client_name TEXT NOT NULL DEFAULT '',
            jobsite_id TEXT NOT NULL DEFAULT '', jobsite_code TEXT NOT NULL DEFAULT '', jobsite_title TEXT NOT NULL DEFAULT '',
            title TEXT NOT NULL DEFAULT '', work_type TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'Bozza', document_type TEXT NOT NULL DEFAULT 'Provvisorio', validity_days INTEGER NOT NULL DEFAULT 30,
            general_description TEXT NOT NULL DEFAULT '', materials_included TEXT NOT NULL DEFAULT '', materials_client TEXT NOT NULL DEFAULT '', exclusions TEXT NOT NULL DEFAULT '', final_notes TEXT NOT NULL DEFAULT '',
            discount_type TEXT NOT NULL DEFAULT 'none', discount_value REAL NOT NULL DEFAULT 0, surcharge_type TEXT NOT NULL DEFAULT 'none', surcharge_value REAL NOT NULL DEFAULT 0,
            rounding REAL NOT NULL DEFAULT 0, subtotal REAL NOT NULL DEFAULT 0, final_total REAL NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE estimate_items(
            id INTEGER PRIMARY KEY AUTOINCREMENT, ge360_id TEXT NOT NULL UNIQUE, estimate_id INTEGER NOT NULL, price_list_code TEXT NOT NULL DEFAULT '',
            section_name TEXT NOT NULL DEFAULT '', room_name TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '', name TEXT NOT NULL DEFAULT '', description TEXT NOT NULL DEFAULT '',
            quantity REAL NOT NULL DEFAULT 1, unit TEXT NOT NULL DEFAULT 'corpo', unit_price REAL NOT NULL DEFAULT 0, price_mode TEXT NOT NULL DEFAULT 'unit', included INTEGER NOT NULL DEFAULT 1,
            optional INTEGER NOT NULL DEFAULT 0, internal_note TEXT NOT NULL DEFAULT '', total REAL NOT NULL DEFAULT 0,
            FOREIGN KEY(estimate_id) REFERENCES estimates(id) ON DELETE CASCADE)""")
        db.execSQL("""CREATE TABLE price_list(
            id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL UNIQUE, name TEXT NOT NULL, category TEXT NOT NULL DEFAULT 'Personalizzate', unit TEXT NOT NULL DEFAULT 'corpo',
            suggested_price REAL, price_mode TEXT NOT NULL DEFAULT 'unit', active INTEGER NOT NULL DEFAULT 1, updated_at INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX idx_est_status ON estimates(status, updated_at DESC)")
        db.execSQL("CREATE INDEX idx_est_job ON estimates(jobsite_id, updated_at DESC)")
        db.execSQL("CREATE INDEX idx_est_client ON estimates(client_id, updated_at DESC)")
        db.execSQL("CREATE INDEX idx_est_items ON estimate_items(estimate_id, id)")
        seedPriceList(db)
    }

    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun list(query: String = "", status: String = "Tutti"): List<EstimateRecord> {
        val clauses = mutableListOf<String>(); val args = mutableListOf<String>()
        if (query.isNotBlank()) {
            val like = "%${query.trim()}%"
            clauses += "(number LIKE ? OR client_name LIKE ? OR jobsite_code LIKE ? OR jobsite_title LIKE ? OR title LIKE ? OR work_type LIKE ?)"
            repeat(6) { args += like }
        }
        if (status != "Tutti") { clauses += "status=?"; args += status }
        val out = mutableListOf<EstimateRecord>()
        readableDatabase.query("estimates", null, clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "), args.takeIf { it.isNotEmpty() }?.toTypedArray(), null, null, "updated_at DESC")
            .use { c -> while (c.moveToNext()) out += recordFromCursor(c, true) }
        return out
    }

    fun get(id: Long): EstimateRecord? = readableDatabase.query("estimates", null, "id=?", arrayOf(id.toString()), null, null, null, "1").use { c -> if (c.moveToFirst()) recordFromCursor(c, true) else null }
    fun getByGe360Id(id: String): EstimateRecord? = if (id.isBlank()) null else readableDatabase.query("estimates", null, "ge360_id=?", arrayOf(id), null, null, null, "1").use { c -> if (c.moveToFirst()) recordFromCursor(c, true) else null }
    fun forJobsite(jobsiteId: String): List<EstimateRecord> = if (jobsiteId.isBlank()) emptyList() else {
        val out = mutableListOf<EstimateRecord>()
        readableDatabase.query("estimates", null, "jobsite_id=?", arrayOf(jobsiteId), null, null, "updated_at DESC").use { c -> while (c.moveToNext()) out += recordFromCursor(c, true) }
        out
    }

    /**
     * Explicit operator-confirmed measured draft import. Inserts only one disabled
     * zero-price row, atomically, without rebuilding historical estimate items.
     * This is NOT an accepted estimate modification or a priced quote line.
     */
    fun insertMeasuredReviewLine(
        estimateGe360Id: String,
        expectedEstimateUpdatedAt: Long,
        source: MeasuredEstimateDraftV1
    ): EstimateItem {
        require(source.valid()) { "Controllare quantità, detrazioni e dati della selezione" }
        val catalog = StandardWorkCatalog.byCode(source.workCode)
            ?: error("Lavorazione del catalogo non trovata")
        require(catalog.unit == "m²") { "La lavorazione deve essere misurata a m²" }
        val item = source.toReviewItem(catalog)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val estimate = getByGe360Id(estimateGe360Id)
                ?: error("Preventivo UUID non trovato")
            require(estimate.status in setOf(EstimateStatuses.DRAFT, EstimateStatuses.REVIEW)) {
                "Solo le bozze o i preventivi da controllare possono ricevere quantità"
            }
            require(estimate.updatedAt == expectedEstimateUpdatedAt) {
                "Preventivo aggiornato da un'altra schermata: riaprire la revisione"
            }
            val lines = estimate.items
            require(lines.none { it.ge360Id == item.ge360Id }) {
                "Questa selezione è già presente nel preventivo"
            }
            lines.forEach { previous ->
                val origin = MeasuredEstimateDraftV1.sourceOf(previous)
                if (origin != null && origin.optString("work") == source.workCode &&
                    origin.optString("plan") == source.planGe360Id &&
                    origin.optString("room") == source.roomGe360Id) {
                    require(origin.optString("binding") != source.sourceBindingGe360Id) {
                        "Questo collegamento è già stato importato"
                    }
                    val previousSurface = origin.optString("surface")
                    val previousWall = origin.optString("wall").ifBlank { null }
                    require(!MeasuredEstimateDraftV1.surfacesOverlap(
                        previousSurface, previousWall, source.surface, source.wallGe360Id
                    )) {
                        "Stessa lavorazione già presente su una superficie sovrapposta. " +
                            "Controllare e risolvere la voce precedente prima di importare."
                    }
                } else if (origin == null && previous.priceListCode == source.workCode &&
                    previous.room.trim().equals(source.roomLabel.trim(), ignoreCase = true)) {
                    require(source.manualOverlapAcknowledged &&
                        source.manualOverlapReason.trim().length >= 8) {
                        "Esiste una voce manuale simile per la stanza. " +
                            "Confermare l'assenza di doppio conteggio e motivare."
                    }
                }
            }
            // The review item has included=false and unitPrice=0, so totals never increase.
            item.estimateId = estimate.id
            db.insertOrThrow("estimate_items", null, itemValues(item))
            val now = maxOf(System.currentTimeMillis(), estimate.updatedAt + 1)
            val touched = db.update("estimates", ContentValues().apply {
                put("updated_at", now)
            }, "id=? AND updated_at=?", arrayOf(
                estimate.id.toString(), estimate.updatedAt.toString()
            ))
            check(touched == 1) { "Il preventivo è cambiato durante il salvataggio" }
            db.setTransactionSuccessful()
            return item
        } finally { db.endTransaction() }
    }

    fun save(record: EstimateRecord): Long {
        val editorRevision = record.updatedAt
        val now = System.currentTimeMillis()
        if (record.ge360Id.isBlank()) record.ge360Id = UUID.randomUUID().toString()
        if (record.number.isBlank()) record.number = nextNumber()
        if (record.createdAt <= 0) record.createdAt = now
        record.updatedAt = now
        recalculateEstimate(record)
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (record.id > 0) {
                // This legacy editor rewrites the entire set of quote items.
                // Protect a measured import from being erased by an outdated editor.
                val current = get(record.id)
                if (current != null && current.items.any {
                        MeasuredEstimateDraftV1.sourceOf(it) != null
                    }) {
                    require(current.updatedAt == editorRevision) {
                        "Il preventivo ha ricevuto nuove quantità dal rilievo: " +
                            "chiudi e riapri l'editor prima di salvare."
                    }
                }
            }
            val id = if (record.id > 0) { db.update("estimates", recordValues(record), "id=?", arrayOf(record.id.toString())); record.id }
            else db.insertOrThrow("estimates", null, recordValues(record)).also { record.id = it }
            db.delete("estimate_items", "estimate_id=?", arrayOf(id.toString()))
            record.items.forEach { item ->
                item.estimateId = id
                if (item.ge360Id.isBlank()) item.ge360Id = UUID.randomUUID().toString()
                item.total = calculateItemTotal(item)
                item.id = db.insertOrThrow("estimate_items", null, itemValues(item))
            }
            db.setTransactionSuccessful(); return id
        } finally { db.endTransaction() }
    }

    fun delete(id: Long) { writableDatabase.delete("estimates", "id=?", arrayOf(id.toString())) }
    fun duplicate(source: EstimateRecord): EstimateRecord = source.copy(
        id = 0, ge360Id = UUID.randomUUID().toString(), legacyId = "", number = "", status = EstimateStatuses.DRAFT,
        createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
        items = source.items.map { it.copy(id = 0, ge360Id = UUID.randomUUID().toString(), estimateId = 0) }.toMutableList()
    ).also { save(it) }

    fun priceList(query: String = ""): List<PriceEntry> {
        val out = mutableListOf<PriceEntry>()
        val where = if (query.isBlank()) "active=1" else "active=1 AND (code LIKE ? OR name LIKE ? OR category LIKE ?)"
        val args = if (query.isBlank()) null else Array(3) { "%${query.trim()}%" }
        readableDatabase.query("price_list", null, where, args, null, null, "category,name").use { c -> while (c.moveToNext()) out += priceFromCursor(c) }
        return out
    }

    fun savePrice(entry: PriceEntry): Long {
        if (entry.code.isBlank()) entry.code = "CUS-${UUID.randomUUID().toString().take(8).uppercase()}"
        entry.updatedAt = System.currentTimeMillis()
        val v = ContentValues().apply {
            put("code", entry.code.trim()); put("name", entry.name.trim()); put("category", entry.category.trim().ifBlank { "Personalizzate" }); put("unit", entry.unit.ifBlank { "corpo" })
            if (entry.suggestedPrice == null) putNull("suggested_price") else put("suggested_price", entry.suggestedPrice)
            put("price_mode", entry.priceMode); put("active", if (entry.active) 1 else 0); put("updated_at", entry.updatedAt)
        }
        return if (entry.id > 0) { writableDatabase.update("price_list", v, "id=?", arrayOf(entry.id.toString())); entry.id }
        else writableDatabase.insertWithOnConflict("price_list", null, v, SQLiteDatabase.CONFLICT_REPLACE).also { entry.id = it }
    }

    fun exportJson(): String = JSONObject().apply {
        put("schema", "ge360.preventivi.v1"); put("exportedAt", System.currentTimeMillis())
        put("estimates", JSONArray().apply { list().forEach { put(recordToJson(it)) } })
        put("priceList", JSONArray().apply { priceList().forEach { put(priceToJson(it)) } })
    }.toString(2)

    fun importJson(json: String, replaceAll: Boolean): Int {
        val root = JSONObject(json)
        if (replaceAll) { writableDatabase.delete("estimate_items", null, null); writableDatabase.delete("estimates", null, null) }
        return if (root.optString("schema") == "ge360.preventivi.v1") importNative(root) else importLegacy(root)
    }

    private fun importNative(root: JSONObject): Int {
        root.optJSONArray("priceList")?.let { a -> for (i in 0 until a.length()) savePrice(priceFromJson(a.getJSONObject(i))) }
        val rows = root.optJSONArray("estimates") ?: JSONArray(); var count = 0
        for (i in 0 until rows.length()) {
            val r = recordFromJson(rows.getJSONObject(i)); getByGe360Id(r.ge360Id)?.let { r.id = it.id }; save(r); count++
        }
        return count
    }

    private fun importLegacy(root: JSONObject): Int {
        root.optJSONArray("priceList")?.let { a ->
            for (i in 0 until a.length()) {
                val p = a.getJSONObject(i)
                savePrice(PriceEntry(code=p.optString("code","LEG-${i+1}"), name=p.optString("name","Lavorazione"), category=p.optString("category","Personalizzate"), unit=p.optString("unit","corpo"), suggestedPrice=if(p.has("suggestedPrice")&&!p.isNull("suggestedPrice"))p.optDouble("suggestedPrice")else null, priceMode=p.optString("priceMode","unit")))
            }
        }
        val separate = root.optJSONArray("estimateItems") ?: JSONArray()
        val grouped = mutableMapOf<String, MutableList<JSONObject>>()
        for (i in 0 until separate.length()) { val o=separate.getJSONObject(i); grouped.getOrPut(o.optString("estimateId","")){ mutableListOf() }.add(o) }
        val estimates = root.optJSONArray("estimates") ?: JSONArray(); var count=0
        for (i in 0 until estimates.length()) {
            val src=estimates.getJSONObject(i); val legacyId=src.optString("id",""); val client=src.optJSONObject("client")?:JSONObject(); val est=src.optJSONObject("estimate")?:JSONObject()
            val r=EstimateRecord(
                ge360Id=legacyId.ifBlank{UUID.randomUUID().toString()}, legacyId=legacyId, clientName=client.optString("name",""), title=est.optString("title",""), workType=est.optString("type",""),
                status=EstimateStatuses.fromLegacy(src.optString("status","draft")), documentType=est.optString("documentType","provvisorio").replaceFirstChar{it.uppercase()}, validityDays=est.optInt("validityDays",30),
                generalDescription=est.optString("generalDescription",""), materialsIncluded=est.optString("materialsIncluded",""), materialsClient=est.optString("materialsClient",""), exclusions=est.optString("exclusions",""), finalNotes=est.optString("finalNotes",""),
                discountType=est.optJSONObject("discount")?.optString("type","none")?:"none", discountValue=est.optJSONObject("discount")?.optDouble("value",0.0)?:0.0,
                surchargeType=est.optJSONObject("surcharge")?.optString("type","none")?:"none", surchargeValue=est.optJSONObject("surcharge")?.optDouble("value",0.0)?:0.0, rounding=est.optDouble("rounding",0.0)
            )
            val itemSources=mutableListOf<JSONObject>(); est.optJSONArray("items")?.let{a->for(j in 0 until a.length())itemSources+=a.getJSONObject(j)}
            if(itemSources.isEmpty())itemSources+=grouped[legacyId].orEmpty()
            r.items=itemSources.map(::legacyItem).toMutableList(); getByGe360Id(r.ge360Id)?.let{r.id=it.id}; save(r); count++
        }
        return count
    }

    private fun legacyItem(o: JSONObject)=EstimateItem(
        ge360Id=o.optString("id",UUID.randomUUID().toString()), priceListCode=o.optString("priceListCode",""), section=o.optString("sectionId","Resto della casa"), room=o.optString("roomName",""),
        category=o.optString("category","Personalizzate"), name=o.optString("name","Lavorazione"), description=o.optString("description",""), quantity=o.optDouble("quantity",1.0), unit=o.optString("unit","corpo"),
        unitPrice=o.optDouble("appliedPrice",o.optDouble("suggestedPrice",0.0)), priceMode=o.optString("priceMode","unit"), included=o.optBoolean("active",true)&&o.optString("status","included")=="included", optional=o.optString("status","")=="optional", internalNote=o.optString("internalNote","")
    )

    private fun nextNumber(): String {
        val prefix="PREV-${LocalDate.now().year}-"; var max=0
        readableDatabase.rawQuery("SELECT number FROM estimates WHERE number LIKE ?", arrayOf("$prefix%" )).use { c -> while(c.moveToNext()) max=maxOf(max,c.getString(0).removePrefix(prefix).toIntOrNull()?:0) }
        return prefix+(max+1).toString().padStart(3,'0')
    }

    private fun loadRecordItems(estimateId: Long): MutableList<EstimateItem> {
        val out=mutableListOf<EstimateItem>()
        readableDatabase.query("estimate_items",null,"estimate_id=?",arrayOf(estimateId.toString()),null,null,"id").use{c->while(c.moveToNext())out+=itemFromCursor(c)}
        return out
    }

    private fun recordFromCursor(c: Cursor, withItems: Boolean)=EstimateRecord(
        id=c.long("id"),ge360Id=c.str("ge360_id"),legacyId=c.str("legacy_id"),number=c.str("number"),clientId=c.long("client_id"),clientGe360Id=c.str("client_ge360_id"),clientName=c.str("client_name"),
        jobsiteId=c.str("jobsite_id"),jobsiteCode=c.str("jobsite_code"),jobsiteTitle=c.str("jobsite_title"),title=c.str("title"),workType=c.str("work_type"),status=c.str("status"),documentType=c.str("document_type"),validityDays=c.int("validity_days"),
        generalDescription=c.str("general_description"),materialsIncluded=c.str("materials_included"),materialsClient=c.str("materials_client"),exclusions=c.str("exclusions"),finalNotes=c.str("final_notes"),
        discountType=c.str("discount_type"),discountValue=c.dbl("discount_value"),surchargeType=c.str("surcharge_type"),surchargeValue=c.dbl("surcharge_value"),rounding=c.dbl("rounding"),subtotal=c.dbl("subtotal"),finalTotal=c.dbl("final_total"),createdAt=c.long("created_at"),updatedAt=c.long("updated_at"),items=if(withItems)loadRecordItems(c.long("id"))else mutableListOf()
    )

    private fun itemFromCursor(c:Cursor)=EstimateItem(id=c.long("id"),ge360Id=c.str("ge360_id"),estimateId=c.long("estimate_id"),priceListCode=c.str("price_list_code"),section=c.str("section_name"),room=c.str("room_name"),category=c.str("category"),name=c.str("name"),description=c.str("description"),quantity=c.dbl("quantity"),unit=c.str("unit"),unitPrice=c.dbl("unit_price"),priceMode=c.str("price_mode"),included=c.int("included")==1,optional=c.int("optional")==1,internalNote=c.str("internal_note"),total=c.dbl("total"))
    private fun priceFromCursor(c:Cursor)=PriceEntry(id=c.long("id"),code=c.str("code"),name=c.str("name"),category=c.str("category"),unit=c.str("unit"),suggestedPrice=if(c.isNull(c.getColumnIndexOrThrow("suggested_price")))null else c.dbl("suggested_price"),priceMode=c.str("price_mode"),active=c.int("active")==1,updatedAt=c.long("updated_at"))

    private fun recordValues(r:EstimateRecord)=ContentValues().apply{
        put("ge360_id",r.ge360Id);put("legacy_id",r.legacyId);put("number",r.number);put("client_id",r.clientId);put("client_ge360_id",r.clientGe360Id);put("client_name",r.clientName);put("jobsite_id",r.jobsiteId);put("jobsite_code",r.jobsiteCode);put("jobsite_title",r.jobsiteTitle);put("title",r.title);put("work_type",r.workType);put("status",r.status);put("document_type",r.documentType);put("validity_days",r.validityDays);put("general_description",r.generalDescription);put("materials_included",r.materialsIncluded);put("materials_client",r.materialsClient);put("exclusions",r.exclusions);put("final_notes",r.finalNotes);put("discount_type",r.discountType);put("discount_value",r.discountValue);put("surcharge_type",r.surchargeType);put("surcharge_value",r.surchargeValue);put("rounding",r.rounding);put("subtotal",r.subtotal);put("final_total",r.finalTotal);put("created_at",r.createdAt);put("updated_at",r.updatedAt)
    }
    private fun itemValues(i:EstimateItem)=ContentValues().apply{put("ge360_id",i.ge360Id);put("estimate_id",i.estimateId);put("price_list_code",i.priceListCode);put("section_name",i.section);put("room_name",i.room);put("category",i.category);put("name",i.name);put("description",i.description);put("quantity",i.quantity);put("unit",i.unit);put("unit_price",i.unitPrice);put("price_mode",i.priceMode);put("included",if(i.included)1 else 0);put("optional",if(i.optional)1 else 0);put("internal_note",i.internalNote);put("total",i.total)}

    private fun recordToJson(r:EstimateRecord)=JSONObject().apply{put("ge360Id",r.ge360Id);put("legacyId",r.legacyId);put("number",r.number);put("clientId",r.clientId);put("clientGe360Id",r.clientGe360Id);put("clientName",r.clientName);put("jobsiteId",r.jobsiteId);put("jobsiteCode",r.jobsiteCode);put("jobsiteTitle",r.jobsiteTitle);put("title",r.title);put("workType",r.workType);put("status",r.status);put("documentType",r.documentType);put("validityDays",r.validityDays);put("generalDescription",r.generalDescription);put("materialsIncluded",r.materialsIncluded);put("materialsClient",r.materialsClient);put("exclusions",r.exclusions);put("finalNotes",r.finalNotes);put("discountType",r.discountType);put("discountValue",r.discountValue);put("surchargeType",r.surchargeType);put("surchargeValue",r.surchargeValue);put("rounding",r.rounding);put("createdAt",r.createdAt);put("updatedAt",r.updatedAt);put("items",JSONArray().apply{r.items.forEach{put(itemToJson(it))}})}
    private fun recordFromJson(o:JSONObject)=EstimateRecord(ge360Id=o.optString("ge360Id",UUID.randomUUID().toString()),legacyId=o.optString("legacyId",""),number=o.optString("number",""),clientId=o.optLong("clientId",0),clientGe360Id=o.optString("clientGe360Id",""),clientName=o.optString("clientName",""),jobsiteId=o.optString("jobsiteId",""),jobsiteCode=o.optString("jobsiteCode",""),jobsiteTitle=o.optString("jobsiteTitle",""),title=o.optString("title",""),workType=o.optString("workType",""),status=o.optString("status",EstimateStatuses.DRAFT),documentType=o.optString("documentType","Provvisorio"),validityDays=o.optInt("validityDays",30),generalDescription=o.optString("generalDescription",""),materialsIncluded=o.optString("materialsIncluded",""),materialsClient=o.optString("materialsClient",""),exclusions=o.optString("exclusions",""),finalNotes=o.optString("finalNotes",""),discountType=o.optString("discountType","none"),discountValue=o.optDouble("discountValue",0.0),surchargeType=o.optString("surchargeType","none"),surchargeValue=o.optDouble("surchargeValue",0.0),rounding=o.optDouble("rounding",0.0),createdAt=o.optLong("createdAt",System.currentTimeMillis()),updatedAt=o.optLong("updatedAt",System.currentTimeMillis()),items=mutableListOf<EstimateItem>().apply{val a=o.optJSONArray("items")?:JSONArray();for(i in 0 until a.length())add(itemFromJson(a.getJSONObject(i)))})
    private fun itemToJson(i:EstimateItem)=JSONObject().apply{put("ge360Id",i.ge360Id);put("priceListCode",i.priceListCode);put("section",i.section);put("room",i.room);put("category",i.category);put("name",i.name);put("description",i.description);put("quantity",i.quantity);put("unit",i.unit);put("unitPrice",i.unitPrice);put("priceMode",i.priceMode);put("included",i.included);put("optional",i.optional);put("internalNote",i.internalNote)}
    private fun itemFromJson(o:JSONObject)=EstimateItem(ge360Id=o.optString("ge360Id",UUID.randomUUID().toString()),priceListCode=o.optString("priceListCode",""),section=o.optString("section","Resto della casa"),room=o.optString("room",""),category=o.optString("category","Personalizzate"),name=o.optString("name","Lavorazione"),description=o.optString("description",""),quantity=o.optDouble("quantity",1.0),unit=o.optString("unit","corpo"),unitPrice=o.optDouble("unitPrice",0.0),priceMode=o.optString("priceMode","unit"),included=o.optBoolean("included",true),optional=o.optBoolean("optional",false),internalNote=o.optString("internalNote",""))
    private fun priceToJson(p:PriceEntry)=JSONObject().apply{put("code",p.code);put("name",p.name);put("category",p.category);put("unit",p.unit);if(p.suggestedPrice==null)put("suggestedPrice",JSONObject.NULL)else put("suggestedPrice",p.suggestedPrice);put("priceMode",p.priceMode);put("active",p.active)}
    private fun priceFromJson(o:JSONObject)=PriceEntry(code=o.optString("code",""),name=o.optString("name","Lavorazione"),category=o.optString("category","Personalizzate"),unit=o.optString("unit","corpo"),suggestedPrice=if(o.has("suggestedPrice")&&!o.isNull("suggestedPrice"))o.optDouble("suggestedPrice")else null,priceMode=o.optString("priceMode","unit"),active=o.optBoolean("active",true))

    private fun seedPriceList(db:SQLiteDatabase){
        val now=System.currentTimeMillis()
        val seeds=listOf(
            arrayOf("RAS-RETE-001","Rasatura completa con rete","Rasature e pitturazioni","m²","20","unit"),arrayOf("PIT-LAV-001","Pitturazione lavabile","Rasature e pitturazioni","m²","10","unit"),arrayOf("PIT-BIA-001","Pitturazione bianca","Rasature e pitturazioni","m²","12","unit"),
            arrayOf("PAV-SPC-001","Posa pavimento SPC","Pavimenti","m²","21","unit"),arrayOf("PAV-PIA-001","Posa piastrelle","Pavimenti","m²","40","unit"),arrayOf("PAV-AUT-001","Autolivellante","Pavimenti","m²","40","unit"),arrayOf("IMP-NAN-001","Impermeabilizzazione Nanoflex","Pavimenti","m²","40","unit"),
            arrayOf("BAT-001","Posa battiscopa","Battiscopa e profili","ml","8","unit"),arrayOf("CAR-CON-001","Controsoffitto completo cartongesso","Cartongesso","m²","70","unit"),arrayOf("CAR-MUR-001","Muro cartongesso con pannelli OSB forniti","Cartongesso","m²","65","unit"),
            arrayOf("BAG-PIC-001","Bagno piccolo completo","Bagni a corpo","corpo","3800","body"),arrayOf("BAG-GRA-001","Bagno grande completo","Bagni a corpo","corpo","4200","body"),arrayOf("BAG-STD-001","Bagno completo standard","Bagni a corpo","corpo","4300","body"),
            arrayOf("TRAC-500","Chiusura tracce — intervento limitato","Chiusura tracce","corpo","500","body"),arrayOf("TRAC-900","Chiusura tracce — intervento medio-esteso","Chiusura tracce","corpo","900","body"),arrayOf("TRAC-1500","Chiusura tracce — intervento esteso","Chiusura tracce","corpo","1500","body")
        )
        seeds.forEach{s->db.insert("price_list",null,ContentValues().apply{put("code",s[0]);put("name",s[1]);put("category",s[2]);put("unit",s[3]);put("suggested_price",s[4].toDouble());put("price_mode",s[5]);put("active",1);put("updated_at",now)})}
    }

    private fun Cursor.str(name:String)=getString(getColumnIndexOrThrow(name))
    private fun Cursor.long(name:String)=getLong(getColumnIndexOrThrow(name))
    private fun Cursor.int(name:String)=getInt(getColumnIndexOrThrow(name))
    private fun Cursor.dbl(name:String)=getDouble(getColumnIndexOrThrow(name))

    companion object {
        val WORK_TYPES=listOf("Ristrutturazione bagno","Ristrutturazione intero appartamento","Pitturazione e rasatura","Pavimenti e battiscopa","Cartongesso e opere murarie","Lavorazioni personalizzate")
        val UNITS=listOf("corpo","m²","ml","cad.","ora","giornata","forfait")
        val SECTIONS=listOf("Bagno","Resto della casa","Esterni","Opere extra")
        private const val DB_NAME="ge360_preventivi.db"
        private const val DB_VERSION=1
    }
}
