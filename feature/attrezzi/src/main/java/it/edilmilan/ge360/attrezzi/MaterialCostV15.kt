package it.edilmilan.ge360.attrezzi

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import it.edilmilan.ge360.scanner.DocumentStore
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.UUID

/**
 * All amounts are euro cents, NET of VAT. They are manually confirmed, NEVER
 * derived from the total of an invoice, scanner OCR, a DDT or a stock movement.
 * These are documentary allocations, not bank payments or Finance postings.
 */
data class MaterialInvoiceCostV15(
    val ge360Id: String,
    val invoiceLineGe360Id: String,
    val netCents: Long,
    val confirmedAt: Long,
    val voidedAt: Long = 0L
) {
    init {
        require(ge360Id.isNotBlank() && invoiceLineGe360Id.isNotBlank())
        require(netCents >= 0 && confirmedAt > 0)
        require(voidedAt == 0L || voidedAt >= confirmedAt)
    }
    val active: Boolean get() = voidedAt == 0L
}

data class MaterialBudgetRevisionV15(
    val ge360Id: String,
    val planRevisionGe360Id: String,
    val materialGe360Id: String,
    val jobsiteGe360Id: String,
    val unit: String,
    val unitNetCents: Long,
    val confirmedAt: Long
) {
    init {
        require(ge360Id.isNotBlank() && planRevisionGe360Id.isNotBlank() &&
            materialGe360Id.isNotBlank() && unit.isNotBlank())
        require(unitNetCents >= 0 && confirmedAt > 0)
    }
}

/** Pure read models for prices; missing coverage is not silently treated as zero cost. */
object MaterialCostRulesV15 {
    fun parseEuroCents(input: String): Long {
        val value = input.trim()
        require(Regex("""^\d{1,10}([,.]\d{1,2})?$""").matches(value)) {
            "Usa un importo EUR non negativo, con al massimo due decimali"
        }
        return BigDecimal(value.replace(',', '.'))
            .movePointRight(2).longValueExact()
    }

    fun euro(cents: Long): String = String.format(Locale.ITALY, "%,.2f €", cents / 100.0)

    fun plannedNetCents(plan: MaterialPlanRevisionV1, budget: MaterialBudgetRevisionV15): Long {
        require(plan.ge360Id == budget.planRevisionGe360Id &&
            plan.materialGe360Id == budget.materialGe360Id &&
            plan.jobsiteGe360Id.orEmpty() == budget.jobsiteGe360Id &&
            plan.unit.trim().equals(budget.unit.trim(), ignoreCase = true)) {
            "Budget non riferito alla revisione corrente del piano"
        }
        return BigDecimal.valueOf(plan.quantity)
            .multiply(BigDecimal.valueOf(budget.unitNetCents))
            .setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    data class Coverage(
        val confirmedNetCents: Long,
        val invoiceLines: Int,
        val valuedLines: Int,
        val unvaluedLines: Int,
        val orphanCosts: Int
    ) {
        val completeForRecordedLines: Boolean get() =
            invoiceLines > 0 && unvaluedLines == 0 && orphanCosts == 0
    }

    fun coverage(
        allLines: List<MaterialDocumentLineV13>,
        costs: List<MaterialInvoiceCostV15>,
        materialId: String,
        jobsiteId: String,
        eligibleLineIds: Set<String>
    ): Coverage {
        val lines = allLines.filter { it.active && it.documentKind == "FATTURA" &&
            it.materialGe360Id == materialId && it.jobsiteGe360Id == jobsiteId }
        val lineIds = lines.map { it.ge360Id }.toSet()
        val activeCosts = costs.filter { it.active && it.invoiceLineGe360Id in lineIds }
        val trusted = activeCosts.filter { it.invoiceLineGe360Id in eligibleLineIds }
        val valued = trusted.map { it.invoiceLineGe360Id }.toSet()
        val total = trusted.fold(0L) { n, row -> Math.addExact(n, row.netCents) }
        return Coverage(total, lines.size, valued.size,
            lines.size - valued.size, activeCosts.size - trusted.size)
    }
}

/**
 * Independent SQLite ledger: no schema migration or writes to Scanner,
 * material inventory, V13/V14, estimates, expenses or payments.
 */
class MaterialCostStoreV15(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE, null, 1) {
    private val app = context.applicationContext
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE invoice_cost_v15 (
            ge360_id TEXT PRIMARY KEY NOT NULL,
            invoice_line_id TEXT NOT NULL,
            net_cents INTEGER NOT NULL CHECK(net_cents >= 0),
            confirmed_at INTEGER NOT NULL,
            voided_at INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("""CREATE UNIQUE INDEX idx_v15_cost_active ON
            invoice_cost_v15(invoice_line_id) WHERE voided_at=0""")
        db.execSQL("""CREATE TABLE budget_revision_v15 (
            ge360_id TEXT PRIMARY KEY NOT NULL,
            plan_revision_id TEXT NOT NULL,
            material_id TEXT NOT NULL,
            jobsite_id TEXT NOT NULL DEFAULT '',
            unit TEXT NOT NULL,
            unit_net_cents INTEGER NOT NULL CHECK(unit_net_cents >= 0),
            confirmed_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX idx_v15_budget_plan ON budget_revision_v15(plan_revision_id,confirmed_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Migrazione costi V15 non autorizzata: preservare i dati")
    }

    fun invoiceCosts(): List<MaterialInvoiceCostV15> {
        val rows = mutableListOf<MaterialInvoiceCostV15>()
        readableDatabase.query("invoice_cost_v15", null, null, null, null, null,
            "confirmed_at DESC,ge360_id DESC").use { c ->
            while (c.moveToNext()) rows += MaterialInvoiceCostV15(
                c.getString(c.getColumnIndexOrThrow("ge360_id")),
                c.getString(c.getColumnIndexOrThrow("invoice_line_id")),
                c.getLong(c.getColumnIndexOrThrow("net_cents")),
                c.getLong(c.getColumnIndexOrThrow("confirmed_at")),
                c.getLong(c.getColumnIndexOrThrow("voided_at"))
            )
        }
        return rows
    }

    fun budgets(): List<MaterialBudgetRevisionV15> {
        val rows = mutableListOf<MaterialBudgetRevisionV15>()
        readableDatabase.query("budget_revision_v15", null, null, null, null, null,
            "confirmed_at DESC,ge360_id DESC").use { c ->
            while (c.moveToNext()) rows += MaterialBudgetRevisionV15(
                c.getString(c.getColumnIndexOrThrow("ge360_id")),
                c.getString(c.getColumnIndexOrThrow("plan_revision_id")),
                c.getString(c.getColumnIndexOrThrow("material_id")),
                c.getString(c.getColumnIndexOrThrow("jobsite_id")),
                c.getString(c.getColumnIndexOrThrow("unit")),
                c.getLong(c.getColumnIndexOrThrow("unit_net_cents")),
                c.getLong(c.getColumnIndexOrThrow("confirmed_at"))
            )
        }
        return rows
    }

    fun confirmInvoiceCost(invoiceLineId: String, netCents: Long): MaterialInvoiceCostV15 {
        require(netCents >= 0L)
        val v13 = MaterialDocumentEvidenceStoreV13(app)
        val scanner = DocumentStore(app)
        try {
            val source = v13.all().singleOrNull { it.ge360Id == invoiceLineId && it.active &&
                it.documentKind == "FATTURA" } ?: error("Riga fattura V13 attiva non trovata")
            val document = scanner.getByGe360Id(source.documentGe360Id)
                ?: error("Documento Scanner non trovato")
            require(document.type.trim().uppercase(Locale.ROOT) == "FATTURA" &&
                (document.jobsiteId.isBlank() || document.jobsiteId == source.jobsiteGe360Id) &&
                document.pdfPath.isNotBlank() && java.io.File(document.pdfPath).isFile) {
                "PDF della fattura o riferimento al cantiere non verificabile"
            }
            val stock = MaterialInventoryStore(app)
            try {
                require(stock.list().any {
                    it.ge360Id == source.materialGe360Id &&
                        it.unit.trim().equals(source.unit.trim(), ignoreCase = true)
                }) { "Materiale o unità originali non più verificabili" }
            } finally { stock.close() }
            val db = writableDatabase
            db.beginTransaction()
            try {
                require(invoiceCosts().none { it.active && it.invoiceLineGe360Id == invoiceLineId }) {
                    "Importo già confermato: annullare quello precedente"
                }
                val row = MaterialInvoiceCostV15(UUID.randomUUID().toString(),
                    invoiceLineId, netCents, System.currentTimeMillis())
                db.insertOrThrow("invoice_cost_v15", null, costValues(row))
                db.setTransactionSuccessful()
                return row
            } finally { db.endTransaction() }
        } finally { v13.close(); scanner.close() }
    }

    /** An immutable record is never rewritten: correction = void + new entry. */
    fun voidInvoiceCost(id: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val row = invoiceCosts().singleOrNull { it.ge360Id == id && it.active }
                ?: return false
            val result = db.update("invoice_cost_v15",
                ContentValues().apply {
                    put("voided_at", System.currentTimeMillis().coerceAtLeast(row.confirmedAt))
                }, "ge360_id=? AND voided_at=0", arrayOf(id)) == 1
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    fun confirmBudget(planId: String, unitNetCents: Long): MaterialBudgetRevisionV15 {
        require(unitNetCents >= 0L)
        val plans = MaterialPlanStore(app)
        val stock = MaterialInventoryStore(app)
        try {
            val plan = plans.all().singleOrNull { it.ge360Id == planId }
                ?: error("Revisione del piano non trovata")
            require(plans.latest(plan.materialGe360Id, plan.jobsiteGe360Id)?.ge360Id == planId) {
                "Il piano è stato aggiornato: selezionare l'ultima revisione"
            }
            require(stock.list().any { it.ge360Id == plan.materialGe360Id &&
                it.unit.trim().equals(plan.unit.trim(), ignoreCase = true) }) {
                "Materiale o unità del piano non disponibile"
            }
            if (!plan.jobsiteGe360Id.isNullOrBlank()) {
                val jobs = it.edilmilan.ge360.cantieri.JobsiteStore(app)
                try {
                    require(jobs.list().any { it.ge360Id == plan.jobsiteGe360Id }) {
                        "Cantiere del piano non più presente"
                    }
                } finally { jobs.close() }
            }
            val row = MaterialBudgetRevisionV15(UUID.randomUUID().toString(),
                plan.ge360Id, plan.materialGe360Id, plan.jobsiteGe360Id.orEmpty(),
                plan.unit, unitNetCents, System.currentTimeMillis())
            writableDatabase.insertOrThrow("budget_revision_v15", null, budgetValues(row))
            return row
        } finally { plans.close(); stock.close() }
    }

    fun exportJson(): String = JSONObject()
        .put("schema", SCHEMA)
        .put("invoiceCosts", JSONArray().apply { invoiceCosts().forEach { put(costToJson(it)) } })
        .put("budgets", JSONArray().apply { budgets().forEach { put(budgetToJson(it)) } })
        .toString(2)

    /** Whole V15 import is atomic; cross-module preflight runs in coordinator. */
    fun importJson(json: String, replaceAll: Boolean): Int {
        val o = JSONObject(json)
        require(o.getString("schema") == SCHEMA) { "Schema V15 non riconosciuto" }
        val prices = o.getJSONArray("invoiceCosts")
        val plans = o.getJSONArray("budgets")
        val incomingCosts = (0 until prices.length()).map { costFromJson(prices.getJSONObject(it)) }
        val incomingBudgets = (0 until plans.length()).map { budgetFromJson(plans.getJSONObject(it)) }
        require(incomingCosts.map { it.ge360Id }.distinct().size == incomingCosts.size &&
            incomingBudgets.map { it.ge360Id }.distinct().size == incomingBudgets.size) {
            "UUID V15 duplicati nel backup"
        }
        val activeIds = incomingCosts.filter { it.active }.map { it.invoiceLineGe360Id }
        require(activeIds.distinct().size == activeIds.size) {
            "Doppio prezzo attivo per la stessa riga fattura"
        }
        val knownCosts = if (replaceAll) emptyMap() else invoiceCosts().associateBy { it.ge360Id }
        val knownBudgets = if (replaceAll) emptyMap() else budgets().associateBy { it.ge360Id }
        incomingCosts.forEach { incoming ->
            knownCosts[incoming.ge360Id]?.let { require(it == incoming) {
                "UUID prezzo V15 già presente con contenuto divergente"
            } }
        }
        incomingBudgets.forEach { incoming ->
            knownBudgets[incoming.ge360Id]?.let { require(it == incoming) {
                "UUID budget V15 già presente con contenuto divergente"
            } }
        }
        val allActive = (knownCosts.values + incomingCosts)
            .distinctBy { it.ge360Id }.filter { it.active }
        require(allActive.map { it.invoiceLineGe360Id }.distinct().size == allActive.size) {
            "Unisci V15: più prezzi attivi per una riga fattura"
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (replaceAll) {
                db.delete("invoice_cost_v15", null, null)
                db.delete("budget_revision_v15", null, null)
            }
            var count = 0
            incomingCosts.forEach { row ->
                if (row.ge360Id !in knownCosts) {
                    db.insertOrThrow("invoice_cost_v15", null, costValues(row)); count++
                }
            }
            incomingBudgets.forEach { row ->
                if (row.ge360Id !in knownBudgets) {
                    db.insertOrThrow("budget_revision_v15", null, budgetValues(row)); count++
                }
            }
            db.setTransactionSuccessful()
            return count
        } finally { db.endTransaction() }
    }

    private fun costValues(x: MaterialInvoiceCostV15) = ContentValues().apply {
        put("ge360_id", x.ge360Id); put("invoice_line_id", x.invoiceLineGe360Id)
        put("net_cents", x.netCents); put("confirmed_at", x.confirmedAt)
        put("voided_at", x.voidedAt)
    }
    private fun budgetValues(x: MaterialBudgetRevisionV15) = ContentValues().apply {
        put("ge360_id", x.ge360Id); put("plan_revision_id", x.planRevisionGe360Id)
        put("material_id", x.materialGe360Id); put("jobsite_id", x.jobsiteGe360Id)
        put("unit", x.unit); put("unit_net_cents", x.unitNetCents)
        put("confirmed_at", x.confirmedAt)
    }
    companion object {
        const val SCHEMA = "ge360.material-cost.v15"
        private const val DATABASE = "ge360_material_cost_v15.db"
        fun costToJson(x: MaterialInvoiceCostV15) = JSONObject()
            .put("ge360Id", x.ge360Id)
            .put("invoiceLineGe360Id", x.invoiceLineGe360Id)
            .put("netCents", x.netCents).put("confirmedAt", x.confirmedAt)
            .put("voidedAt", x.voidedAt)
        fun budgetToJson(x: MaterialBudgetRevisionV15) = JSONObject()
            .put("ge360Id", x.ge360Id)
            .put("planRevisionGe360Id", x.planRevisionGe360Id)
            .put("materialGe360Id", x.materialGe360Id)
            .put("jobsiteGe360Id", x.jobsiteGe360Id)
            .put("unit", x.unit).put("unitNetCents", x.unitNetCents)
            .put("confirmedAt", x.confirmedAt)
        fun costFromJson(o: JSONObject) = MaterialInvoiceCostV15(
            o.getString("ge360Id"), o.getString("invoiceLineGe360Id"),
            o.getLong("netCents"), o.getLong("confirmedAt"), o.optLong("voidedAt"))
        fun budgetFromJson(o: JSONObject) = MaterialBudgetRevisionV15(
            o.getString("ge360Id"), o.getString("planRevisionGe360Id"),
            o.getString("materialGe360Id"), o.optString("jobsiteGe360Id"),
            o.getString("unit"), o.getLong("unitNetCents"), o.getLong("confirmedAt"))
    }
}
