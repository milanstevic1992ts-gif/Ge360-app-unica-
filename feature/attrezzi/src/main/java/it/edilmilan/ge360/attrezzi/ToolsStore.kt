package it.edilmilan.ge360.attrezzi

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ResourceKind { TOOL, MATERIAL }

data class CatalogResource(
    var id: Long = 0,
    var ge360Id: String = "",
    var kind: ResourceKind = ResourceKind.TOOL,
    var name: String = "",
    var category: String = "Altro",
    var unit: String = "pz",
    var note: String = "",
    var active: Boolean = true,
    var updatedAt: Long = System.currentTimeMillis()
)

data class JobResource(
    var id: Long = 0,
    var ge360Id: String = "",
    var jobsiteId: String = "",
    var jobsiteCode: String = "",
    var jobsiteTitle: String = "",
    var catalogId: Long = 0,
    var catalogGe360Id: String = "",
    var kind: ResourceKind = ResourceKind.TOOL,
    var name: String = "",
    var quantity: Double = 1.0,
    var unit: String = "pz",
    var status: String = STATUS_TO_BRING,
    var note: String = "",
    var updatedAt: Long = System.currentTimeMillis()
) {
    companion object { const val STATUS_TO_BRING = "Da portare" }
}

data class ResourceRequest(
    var id: Long = 0,
    var ge360Id: String = "",
    var jobsiteId: String = "",
    var jobsiteCode: String = "",
    var jobsiteTitle: String = "",
    var kind: ResourceKind = ResourceKind.MATERIAL,
    var name: String = "",
    var quantity: Double = 1.0,
    var unit: String = "pz",
    var note: String = "",
    var urgent: Boolean = false,
    var requestedBy: String = "",
    var status: String = REQUESTED,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    companion object { const val REQUESTED = "Richiesto" }
}

data class TeamTask(
    var id: Long = 0,
    var ge360Id: String = "",
    var jobsiteId: String = "",
    var jobsiteCode: String = "",
    var jobsiteTitle: String = "",
    var type: String = "Nota",
    var title: String = "",
    var description: String = "",
    var dueDate: String = "",
    var assignedTo: String = "",
    var priority: Int = 0,
    var status: String = TASK_TODO,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    companion object { const val TASK_TODO = "Da fare" }
}

data class JobResourceSummary(
    val tools: Int,
    val materials: Int,
    val toBring: Int,
    val missing: Int,
    val openRequests: Int,
    val openTasks: Int
)

class ToolsStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE catalog_resources(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ge360_id TEXT NOT NULL UNIQUE,
            kind TEXT NOT NULL,
            name TEXT NOT NULL,
            category TEXT NOT NULL DEFAULT 'Altro',
            unit TEXT NOT NULL DEFAULT 'pz',
            note TEXT NOT NULL DEFAULT '',
            active INTEGER NOT NULL DEFAULT 1,
            updated_at INTEGER NOT NULL
        )""")
        db.execSQL("""CREATE TABLE job_resources(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ge360_id TEXT NOT NULL UNIQUE,
            jobsite_id TEXT NOT NULL,
            jobsite_code TEXT NOT NULL DEFAULT '',
            jobsite_title TEXT NOT NULL DEFAULT '',
            catalog_id INTEGER NOT NULL DEFAULT 0,
            catalog_ge360_id TEXT NOT NULL DEFAULT '',
            kind TEXT NOT NULL,
            name TEXT NOT NULL,
            quantity REAL NOT NULL DEFAULT 1,
            unit TEXT NOT NULL DEFAULT 'pz',
            status TEXT NOT NULL DEFAULT 'Da portare',
            note TEXT NOT NULL DEFAULT '',
            updated_at INTEGER NOT NULL
        )""")
        db.execSQL("""CREATE TABLE resource_requests(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ge360_id TEXT NOT NULL UNIQUE,
            jobsite_id TEXT NOT NULL,
            jobsite_code TEXT NOT NULL DEFAULT '',
            jobsite_title TEXT NOT NULL DEFAULT '',
            kind TEXT NOT NULL,
            name TEXT NOT NULL,
            quantity REAL NOT NULL DEFAULT 1,
            unit TEXT NOT NULL DEFAULT 'pz',
            note TEXT NOT NULL DEFAULT '',
            urgent INTEGER NOT NULL DEFAULT 0,
            requested_by TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'Richiesto',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""")
        db.execSQL("""CREATE TABLE team_tasks(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ge360_id TEXT NOT NULL UNIQUE,
            jobsite_id TEXT NOT NULL,
            jobsite_code TEXT NOT NULL DEFAULT '',
            jobsite_title TEXT NOT NULL DEFAULT '',
            type TEXT NOT NULL DEFAULT 'Nota',
            title TEXT NOT NULL,
            description TEXT NOT NULL DEFAULT '',
            due_date TEXT NOT NULL DEFAULT '',
            assigned_to TEXT NOT NULL DEFAULT '',
            priority INTEGER NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'Da fare',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX idx_job_resources_job ON job_resources(jobsite_id, kind, status)")
        db.execSQL("CREATE INDEX idx_requests_job ON resource_requests(jobsite_id, status, updated_at DESC)")
        db.execSQL("CREATE INDEX idx_tasks_job ON team_tasks(jobsite_id, status, due_date)")
        seedCatalog(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun catalog(query: String = "", kind: ResourceKind? = null): List<CatalogResource> {
        val where = mutableListOf("active=1")
        val args = mutableListOf<String>()
        if (query.isNotBlank()) {
            val like = "%${query.trim()}%"
            where += "(name LIKE ? OR category LIKE ? OR note LIKE ?)"
            repeat(3) { args += like }
        }
        if (kind != null) { where += "kind=?"; args += kind.name }
        val out = mutableListOf<CatalogResource>()
        readableDatabase.query("catalog_resources", null, where.joinToString(" AND "), args.toTypedArray(), null, null, "kind, category, name COLLATE NOCASE").use { c ->
            while (c.moveToNext()) out += catalogFromCursor(c)
        }
        return out
    }

    fun saveCatalog(item: CatalogResource): Long {
        if (item.ge360Id.isBlank()) item.ge360Id = UUID.randomUUID().toString()
        item.updatedAt = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("ge360_id", item.ge360Id); put("kind", item.kind.name); put("name", item.name.trim())
            put("category", item.category.trim().ifEmpty { "Altro" }); put("unit", item.unit.trim().ifEmpty { "pz" })
            put("note", item.note.trim()); put("active", if (item.active) 1 else 0); put("updated_at", item.updatedAt)
        }
        return if (item.id > 0) { writableDatabase.update("catalog_resources", values, "id=?", arrayOf(item.id.toString())); item.id }
        else writableDatabase.insertOrThrow("catalog_resources", null, values).also { item.id = it }
    }

    fun jobResources(jobsiteId: String): List<JobResource> {
        if (jobsiteId.isBlank()) return emptyList()
        val out = mutableListOf<JobResource>()
        readableDatabase.query("job_resources", null, "jobsite_id=?", arrayOf(jobsiteId), null, null, "kind, status, name COLLATE NOCASE").use { c -> while (c.moveToNext()) out += jobResourceFromCursor(c) }
        return out
    }

    fun saveJobResource(item: JobResource): Long {
        if (item.ge360Id.isBlank()) item.ge360Id = UUID.randomUUID().toString()
        item.updatedAt = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("ge360_id", item.ge360Id); put("jobsite_id", item.jobsiteId); put("jobsite_code", item.jobsiteCode); put("jobsite_title", item.jobsiteTitle)
            put("catalog_id", item.catalogId); put("catalog_ge360_id", item.catalogGe360Id); put("kind", item.kind.name); put("name", item.name.trim())
            put("quantity", item.quantity.coerceAtLeast(0.01)); put("unit", item.unit.trim().ifEmpty { "pz" }); put("status", item.status); put("note", item.note.trim()); put("updated_at", item.updatedAt)
        }
        return if (item.id > 0) { writableDatabase.update("job_resources", values, "id=?", arrayOf(item.id.toString())); item.id }
        else writableDatabase.insertOrThrow("job_resources", null, values).also { item.id = it }
    }

    fun deleteJobResource(id: Long) { writableDatabase.delete("job_resources", "id=?", arrayOf(id.toString())) }

    fun requests(jobsiteId: String = ""): List<ResourceRequest> {
        val where = if (jobsiteId.isBlank()) null else "jobsite_id=?"
        val args = if (jobsiteId.isBlank()) null else arrayOf(jobsiteId)
        val out = mutableListOf<ResourceRequest>()
        readableDatabase.query("resource_requests", null, where, args, null, null, "CASE WHEN urgent=1 THEN 0 ELSE 1 END, updated_at DESC").use { c -> while (c.moveToNext()) out += requestFromCursor(c) }
        return out
    }

    fun saveRequest(item: ResourceRequest): Long {
        val now = System.currentTimeMillis()
        if (item.ge360Id.isBlank()) item.ge360Id = UUID.randomUUID().toString()
        if (item.createdAt <= 0) item.createdAt = now
        item.updatedAt = now
        val values = ContentValues().apply {
            put("ge360_id", item.ge360Id); put("jobsite_id", item.jobsiteId); put("jobsite_code", item.jobsiteCode); put("jobsite_title", item.jobsiteTitle)
            put("kind", item.kind.name); put("name", item.name.trim()); put("quantity", item.quantity.coerceAtLeast(0.01)); put("unit", item.unit.trim().ifEmpty { "pz" })
            put("note", item.note.trim()); put("urgent", if (item.urgent) 1 else 0); put("requested_by", item.requestedBy.trim()); put("status", item.status)
            put("created_at", item.createdAt); put("updated_at", item.updatedAt)
        }
        return if (item.id > 0) { writableDatabase.update("resource_requests", values, "id=?", arrayOf(item.id.toString())); item.id }
        else writableDatabase.insertOrThrow("resource_requests", null, values).also { item.id = it }
    }

    fun tasks(jobsiteId: String = ""): List<TeamTask> {
        val where = if (jobsiteId.isBlank()) null else "jobsite_id=?"
        val args = if (jobsiteId.isBlank()) null else arrayOf(jobsiteId)
        val out = mutableListOf<TeamTask>()
        readableDatabase.query("team_tasks", null, where, args, null, null, "CASE status WHEN 'Da fare' THEN 0 WHEN 'In corso' THEN 1 ELSE 2 END, priority DESC, due_date, updated_at DESC").use { c -> while (c.moveToNext()) out += taskFromCursor(c) }
        return out
    }

    fun saveTask(item: TeamTask): Long {
        val now = System.currentTimeMillis()
        if (item.ge360Id.isBlank()) item.ge360Id = UUID.randomUUID().toString()
        if (item.createdAt <= 0) item.createdAt = now
        item.updatedAt = now
        val values = ContentValues().apply {
            put("ge360_id", item.ge360Id); put("jobsite_id", item.jobsiteId); put("jobsite_code", item.jobsiteCode); put("jobsite_title", item.jobsiteTitle)
            put("type", item.type); put("title", item.title.trim()); put("description", item.description.trim()); put("due_date", item.dueDate.trim())
            put("assigned_to", item.assignedTo.trim()); put("priority", item.priority.coerceIn(0, 3)); put("status", item.status)
            put("created_at", item.createdAt); put("updated_at", item.updatedAt)
        }
        return if (item.id > 0) { writableDatabase.update("team_tasks", values, "id=?", arrayOf(item.id.toString())); item.id }
        else writableDatabase.insertOrThrow("team_tasks", null, values).also { item.id = it }
    }

    fun summary(jobsiteId: String): JobResourceSummary {
        val resources = jobResources(jobsiteId)
        val req = requests(jobsiteId)
        val taskRows = tasks(jobsiteId)
        return JobResourceSummary(
            tools = resources.count { it.kind == ResourceKind.TOOL },
            materials = resources.count { it.kind == ResourceKind.MATERIAL },
            toBring = resources.count { it.status in setOf("Da portare", "Da comprare", "Comprato") },
            missing = resources.count { it.status in setOf("Mancante", "Da comprare") },
            openRequests = req.count { it.status !in setOf("Consegnato", "Rifiutato", "Annullato") },
            openTasks = taskRows.count { it.status !in setOf("Fatto", "Annullato") }
        )
    }

    fun exportJson(): String {
        val root = JSONObject().put("schema", "ge360.attrezzi.v1").put("exportedAt", System.currentTimeMillis())
        root.put("catalog", JSONArray().apply { catalogAll().forEach { put(catalogToJson(it)) } })
        root.put("jobResources", JSONArray().apply { allJobResources().forEach { put(jobResourceToJson(it)) } })
        root.put("requests", JSONArray().apply { requests().forEach { put(requestToJson(it)) } })
        root.put("tasks", JSONArray().apply { tasks().forEach { put(taskToJson(it)) } })

        val calculationStore = MaterialCalculationStore(appContext)
        try {
            root.put("materialCalculations", JSONObject(calculationStore.exportJson()))
        } finally {
            calculationStore.close()
        }

        val materialStore = MaterialInventoryStore(appContext)
        try {
            root.put("materials2", JSONObject(materialStore.exportJson()))
        } finally {
            materialStore.close()
        }
        return root.toString(2)
    }

    fun importJson(json: String, replaceAll: Boolean): Int {
        val root = JSONObject(json)
        require(root.optString("schema") == "ge360.attrezzi.v1") { "Formato backup non riconosciuto" }
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            if (replaceAll) {
                db.delete("team_tasks", null, null); db.delete("resource_requests", null, null); db.delete("job_resources", null, null); db.delete("catalog_resources", null, null)
            }
            root.optJSONArray("catalog")?.let { rows -> for (i in 0 until rows.length()) { val x = catalogFromJson(rows.getJSONObject(i)); findCatalogByGe360(x.ge360Id)?.let { x.id = it.id }; saveCatalog(x); count++ } }
            root.optJSONArray("jobResources")?.let { rows -> for (i in 0 until rows.length()) { val x = jobResourceFromJson(rows.getJSONObject(i)); findJobResourceByGe360(x.ge360Id)?.let { x.id = it.id }; saveJobResource(x); count++ } }
            root.optJSONArray("requests")?.let { rows -> for (i in 0 until rows.length()) { val x = requestFromJson(rows.getJSONObject(i)); findRequestByGe360(x.ge360Id)?.let { x.id = it.id }; saveRequest(x); count++ } }
            root.optJSONArray("tasks")?.let { rows -> for (i in 0 until rows.length()) { val x = taskFromJson(rows.getJSONObject(i)); findTaskByGe360(x.ge360Id)?.let { x.id = it.id }; saveTask(x); count++ } }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }

        root.optJSONObject("materialCalculations")?.let { calculationsJson ->
            val calculationStore = MaterialCalculationStore(appContext)
            try {
                count += calculationStore.importJson(calculationsJson.toString(), replaceAll)
            } finally {
                calculationStore.close()
            }
        }

        root.optJSONObject("materials2")?.let { materialsJson ->
            val materialStore = MaterialInventoryStore(appContext)
            try {
                count += materialStore.importJson(materialsJson.toString(), replaceAll)
            } finally {
                materialStore.close()
            }
        }
        return count
    }

    private fun catalogAll(): List<CatalogResource> { val out=mutableListOf<CatalogResource>(); readableDatabase.query("catalog_resources",null,null,null,null,null,"kind, category, name").use{c->while(c.moveToNext())out+=catalogFromCursor(c)}; return out }
    private fun allJobResources(): List<JobResource> { val out=mutableListOf<JobResource>(); readableDatabase.query("job_resources",null,null,null,null,null,"jobsite_code, kind, name").use{c->while(c.moveToNext())out+=jobResourceFromCursor(c)}; return out }
    private fun findCatalogByGe360(id:String)=findOne("catalog_resources",id,::catalogFromCursor)
    private fun findJobResourceByGe360(id:String)=findOne("job_resources",id,::jobResourceFromCursor)
    private fun findRequestByGe360(id:String)=findOne("resource_requests",id,::requestFromCursor)
    private fun findTaskByGe360(id:String)=findOne("team_tasks",id,::taskFromCursor)
    private fun <T> findOne(table:String,id:String, mapper:(Cursor)->T):T? { if(id.isBlank()) return null; readableDatabase.query(table,null,"ge360_id=?",arrayOf(id),null,null,null,"1").use{c->return if(c.moveToFirst())mapper(c) else null} }

    private fun catalogFromCursor(c: Cursor)=CatalogResource(c.getLong(c.getColumnIndexOrThrow("id")),c.getString(c.getColumnIndexOrThrow("ge360_id")),ResourceKind.valueOf(c.getString(c.getColumnIndexOrThrow("kind"))),c.getString(c.getColumnIndexOrThrow("name")),c.getString(c.getColumnIndexOrThrow("category")),c.getString(c.getColumnIndexOrThrow("unit")),c.getString(c.getColumnIndexOrThrow("note")),c.getInt(c.getColumnIndexOrThrow("active"))==1,c.getLong(c.getColumnIndexOrThrow("updated_at")))
    private fun jobResourceFromCursor(c: Cursor)=JobResource(c.getLong(c.getColumnIndexOrThrow("id")),c.getString(c.getColumnIndexOrThrow("ge360_id")),c.getString(c.getColumnIndexOrThrow("jobsite_id")),c.getString(c.getColumnIndexOrThrow("jobsite_code")),c.getString(c.getColumnIndexOrThrow("jobsite_title")),c.getLong(c.getColumnIndexOrThrow("catalog_id")),c.getString(c.getColumnIndexOrThrow("catalog_ge360_id")),ResourceKind.valueOf(c.getString(c.getColumnIndexOrThrow("kind"))),c.getString(c.getColumnIndexOrThrow("name")),c.getDouble(c.getColumnIndexOrThrow("quantity")),c.getString(c.getColumnIndexOrThrow("unit")),c.getString(c.getColumnIndexOrThrow("status")),c.getString(c.getColumnIndexOrThrow("note")),c.getLong(c.getColumnIndexOrThrow("updated_at")))
    private fun requestFromCursor(c: Cursor)=ResourceRequest(c.getLong(c.getColumnIndexOrThrow("id")),c.getString(c.getColumnIndexOrThrow("ge360_id")),c.getString(c.getColumnIndexOrThrow("jobsite_id")),c.getString(c.getColumnIndexOrThrow("jobsite_code")),c.getString(c.getColumnIndexOrThrow("jobsite_title")),ResourceKind.valueOf(c.getString(c.getColumnIndexOrThrow("kind"))),c.getString(c.getColumnIndexOrThrow("name")),c.getDouble(c.getColumnIndexOrThrow("quantity")),c.getString(c.getColumnIndexOrThrow("unit")),c.getString(c.getColumnIndexOrThrow("note")),c.getInt(c.getColumnIndexOrThrow("urgent"))==1,c.getString(c.getColumnIndexOrThrow("requested_by")),c.getString(c.getColumnIndexOrThrow("status")),c.getLong(c.getColumnIndexOrThrow("created_at")),c.getLong(c.getColumnIndexOrThrow("updated_at")))
    private fun taskFromCursor(c: Cursor)=TeamTask(c.getLong(c.getColumnIndexOrThrow("id")),c.getString(c.getColumnIndexOrThrow("ge360_id")),c.getString(c.getColumnIndexOrThrow("jobsite_id")),c.getString(c.getColumnIndexOrThrow("jobsite_code")),c.getString(c.getColumnIndexOrThrow("jobsite_title")),c.getString(c.getColumnIndexOrThrow("type")),c.getString(c.getColumnIndexOrThrow("title")),c.getString(c.getColumnIndexOrThrow("description")),c.getString(c.getColumnIndexOrThrow("due_date")),c.getString(c.getColumnIndexOrThrow("assigned_to")),c.getInt(c.getColumnIndexOrThrow("priority")),c.getString(c.getColumnIndexOrThrow("status")),c.getLong(c.getColumnIndexOrThrow("created_at")),c.getLong(c.getColumnIndexOrThrow("updated_at")))

    private fun catalogToJson(x:CatalogResource)=JSONObject().put("ge360Id",x.ge360Id).put("kind",x.kind.name).put("name",x.name).put("category",x.category).put("unit",x.unit).put("note",x.note).put("active",x.active).put("updatedAt",x.updatedAt)
    private fun jobResourceToJson(x:JobResource)=JSONObject().put("ge360Id",x.ge360Id).put("jobsiteId",x.jobsiteId).put("jobsiteCode",x.jobsiteCode).put("jobsiteTitle",x.jobsiteTitle).put("catalogGe360Id",x.catalogGe360Id).put("kind",x.kind.name).put("name",x.name).put("quantity",x.quantity).put("unit",x.unit).put("status",x.status).put("note",x.note).put("updatedAt",x.updatedAt)
    private fun requestToJson(x:ResourceRequest)=JSONObject().put("ge360Id",x.ge360Id).put("jobsiteId",x.jobsiteId).put("jobsiteCode",x.jobsiteCode).put("jobsiteTitle",x.jobsiteTitle).put("kind",x.kind.name).put("name",x.name).put("quantity",x.quantity).put("unit",x.unit).put("note",x.note).put("urgent",x.urgent).put("requestedBy",x.requestedBy).put("status",x.status).put("createdAt",x.createdAt).put("updatedAt",x.updatedAt)
    private fun taskToJson(x:TeamTask)=JSONObject().put("ge360Id",x.ge360Id).put("jobsiteId",x.jobsiteId).put("jobsiteCode",x.jobsiteCode).put("jobsiteTitle",x.jobsiteTitle).put("type",x.type).put("title",x.title).put("description",x.description).put("dueDate",x.dueDate).put("assignedTo",x.assignedTo).put("priority",x.priority).put("status",x.status).put("createdAt",x.createdAt).put("updatedAt",x.updatedAt)

    private fun catalogFromJson(o:JSONObject)=CatalogResource(ge360Id=o.optString("ge360Id",UUID.randomUUID().toString()),kind=parseKind(o.optString("kind")),name=o.optString("name"),category=o.optString("category","Altro"),unit=o.optString("unit","pz"),note=o.optString("note"),active=o.optBoolean("active",true),updatedAt=o.optLong("updatedAt",System.currentTimeMillis()))
    private fun jobResourceFromJson(o:JSONObject)=JobResource(ge360Id=o.optString("ge360Id",UUID.randomUUID().toString()),jobsiteId=o.optString("jobsiteId"),jobsiteCode=o.optString("jobsiteCode"),jobsiteTitle=o.optString("jobsiteTitle"),catalogGe360Id=o.optString("catalogGe360Id"),kind=parseKind(o.optString("kind")),name=o.optString("name"),quantity=o.optDouble("quantity",1.0),unit=o.optString("unit","pz"),status=o.optString("status",JobResource.STATUS_TO_BRING),note=o.optString("note"),updatedAt=o.optLong("updatedAt",System.currentTimeMillis()))
    private fun requestFromJson(o:JSONObject)=ResourceRequest(ge360Id=o.optString("ge360Id",UUID.randomUUID().toString()),jobsiteId=o.optString("jobsiteId"),jobsiteCode=o.optString("jobsiteCode"),jobsiteTitle=o.optString("jobsiteTitle"),kind=parseKind(o.optString("kind","MATERIAL")),name=o.optString("name"),quantity=o.optDouble("quantity",1.0),unit=o.optString("unit","pz"),note=o.optString("note"),urgent=o.optBoolean("urgent",false),requestedBy=o.optString("requestedBy"),status=o.optString("status",ResourceRequest.REQUESTED),createdAt=o.optLong("createdAt",System.currentTimeMillis()),updatedAt=o.optLong("updatedAt",System.currentTimeMillis()))
    private fun taskFromJson(o:JSONObject)=TeamTask(ge360Id=o.optString("ge360Id",UUID.randomUUID().toString()),jobsiteId=o.optString("jobsiteId"),jobsiteCode=o.optString("jobsiteCode"),jobsiteTitle=o.optString("jobsiteTitle"),type=o.optString("type","Nota"),title=o.optString("title"),description=o.optString("description"),dueDate=o.optString("dueDate"),assignedTo=o.optString("assignedTo"),priority=o.optInt("priority",0),status=o.optString("status",TeamTask.TASK_TODO),createdAt=o.optLong("createdAt",System.currentTimeMillis()),updatedAt=o.optLong("updatedAt",System.currentTimeMillis()))
    private fun parseKind(value:String)=runCatching{ResourceKind.valueOf(value.uppercase())}.getOrDefault(ResourceKind.MATERIAL)

    private fun seedCatalog(db: SQLiteDatabase) {
        val rows = listOf(
            arrayOf("TOOL","Trapano avvitatore","Elettroutensili","pz","Batterie e caricatore"),
            arrayOf("TOOL","Tassellatore","Elettroutensili","pz","Punte SDS"),
            arrayOf("TOOL","Smerigliatrice / flex","Elettroutensili","pz","Dischi adatti"),
            arrayOf("TOOL","Miscelatore elettrico","Elettroutensili","pz","Frusta pulita"),
            arrayOf("TOOL","Aspiratore professionale","Elettroutensili","pz","Sacchi e filtro"),
            arrayOf("TOOL","Laser autolivellante","Misura","pz","Treppiede e batterie"),
            arrayOf("TOOL","Martello demolitore","Demolizione","pz","Scalpelli e grasso"),
            arrayOf("TOOL","Tagliapiastrelle","Piastrelle","pz","Manuale o elettrica"),
            arrayOf("TOOL","Alzalastre","Cartongesso","pz","Controsoffitti"),
            arrayOf("TOOL","Faro da cantiere","Elettroutensili","pz","Batteria o cavo"),
            arrayOf("TOOL","Prolunga / avvolgicavo","Elettroutensili","pz","Controllare lunghezza"),
            arrayOf("TOOL","DPI / protezioni personali","Sicurezza","set","Guanti, occhiali, cuffie"),
            arrayOf("MATERIAL","Teli e protezioni","Protezioni","m²","Protezione passaggi e arredi"),
            arrayOf("MATERIAL","Malta","Malte e rasanti","sacco",""),
            arrayOf("MATERIAL","Rasante","Malte e rasanti","sacco",""),
            arrayOf("MATERIAL","Rete in fibra","Malte e rasanti","m²",""),
            arrayOf("MATERIAL","Primer / fissativo","Preparazione fondi","l",""),
            arrayOf("MATERIAL","Impermeabilizzante","Impermeabilizzazione","kg",""),
            arrayOf("MATERIAL","Colla piastrelle","Posa piastrelle","sacco",""),
            arrayOf("MATERIAL","Stucco fughe","Posa piastrelle","kg",""),
            arrayOf("MATERIAL","Profili cartongesso","Sistemi cartongesso","ml",""),
            arrayOf("MATERIAL","Lastre cartongesso","Sistemi cartongesso","pz",""),
            arrayOf("MATERIAL","Viti cartongesso","Sistemi cartongesso","scatola",""),
            arrayOf("MATERIAL","Pittura","Pittura e finiture","l",""),
            arrayOf("MATERIAL","Silicone / sigillante","Consumabili","pz","")
        )
        rows.forEach { r ->
            val v=ContentValues().apply{put("ge360_id",UUID.randomUUID().toString());put("kind",r[0]);put("name",r[1]);put("category",r[2]);put("unit",r[3]);put("note",r[4]);put("active",1);put("updated_at",System.currentTimeMillis())}
            db.insert("catalog_resources",null,v)
        }
    }

    companion object {
        val RESOURCE_STATUSES = listOf("Da portare", "In furgone", "In cantiere", "Mancante", "Da comprare", "Comprato", "Consegnato", "Rientrato")
        val REQUEST_STATUSES = listOf("Richiesto", "Approvato", "Disponibile", "Comprato", "Consegnato", "Rifiutato", "Annullato")
        val TASK_TYPES = listOf("Porta attrezzi", "Compra materiale", "Ritira materiale", "Consegna materiale", "Foto", "Misura", "Lavoro", "Nota", "Altro")
        val TASK_STATUSES = listOf("Da fare", "Accettato", "In corso", "Fatto", "Bloccato", "Annullato")
        val UNITS = listOf("pz", "set", "sacco", "scatola", "kg", "l", "m²", "ml", "m", "corpo")
        private const val DB_NAME = "ge360_attrezzi.db"
        private const val DB_VERSION = 1
    }
}
