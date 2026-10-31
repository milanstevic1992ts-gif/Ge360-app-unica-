package it.edilmilan.ge360.giornata

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Offline-only ledger for confirmed accruals and manually registered payments.
 * Independent of the legacy FinanceStore / SlobodanWeeklyPay ledger.
 *
 * No payment creates a FinanceRecord; optional finance UUID is only a cross-reference.
 * Backup is versioned; import is transactional and never overwrites an existing UUID in merge mode.
 */
data class CompensationInvoiceLinkV1(
    val ge360Id: String,
    val accrualGe360Id: String,
    val scannerDocumentGe360Id: String
) {
    init {
        require(ge360Id.isNotBlank() && accrualGe360Id.isNotBlank() &&
            scannerDocumentGe360Id.isNotBlank())
    }
}

class CompensationStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE agreements(
            ge360_id TEXT PRIMARY KEY NOT NULL,
            collaborator_id TEXT NOT NULL,
            mode TEXT NOT NULL,
            amount_cents INTEGER NOT NULL CHECK(amount_cents >= 0),
            currency TEXT NOT NULL DEFAULT 'EUR',
            description TEXT NOT NULL DEFAULT '',
            jobsite_id TEXT NOT NULL DEFAULT ''
        )""")
        db.execSQL("""CREATE TABLE accruals(
            ge360_id TEXT PRIMARY KEY NOT NULL,
            agreement_id TEXT NOT NULL,
            amount_cents INTEGER NOT NULL CHECK(amount_cents >= 0),
            status TEXT NOT NULL,
            period_start TEXT NOT NULL,
            period_end TEXT NOT NULL,
            description TEXT NOT NULL DEFAULT ''
        )""")
        db.execSQL("CREATE INDEX idx_comp_accrual_agreement ON accruals(agreement_id)")
        db.execSQL("""CREATE TABLE payments(
            ge360_id TEXT PRIMARY KEY NOT NULL,
            accrual_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            amount_cents INTEGER NOT NULL CHECK(amount_cents > 0),
            recorded_at TEXT NOT NULL,
            proof_document_id TEXT NOT NULL DEFAULT '',
            finance_record_id TEXT NOT NULL DEFAULT '',
            reverses_payment_id TEXT NOT NULL DEFAULT ''
        )""")
        db.execSQL("CREATE INDEX idx_comp_payment_accrual ON payments(accrual_id)")
        db.execSQL("CREATE UNIQUE INDEX idx_comp_finance_ref ON payments(finance_record_id) WHERE finance_record_id <> '' AND kind = 'PAYMENT'")
        db.execSQL("""CREATE TABLE invoice_links(
            ge360_id TEXT PRIMARY KEY NOT NULL,
            accrual_id TEXT NOT NULL,
            scanner_document_id TEXT NOT NULL UNIQUE
        )""")
        db.execSQL("CREATE INDEX idx_comp_invoice_accrual ON invoice_links(accrual_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // The first schema has no legacy data to migrate. Future versions must be additive.
        require(newVersion == VERSION && oldVersion == VERSION) {
            "Unsupported compensation database migration; original data must be preserved"
        }
    }

    fun agreements(): List<CompensationAgreementV1> =
        read("agreements", "collaborator_id, ge360_id") { c ->
            CompensationAgreementV1(
                ge360Id = c.str("ge360_id"),
                collaboratorGe360Id = c.str("collaborator_id"),
                mode = CompensationMode.valueOf(c.str("mode")),
                agreedAmountCents = c.long("amount_cents"),
                description = c.str("description"),
                currency = c.str("currency"),
                jobsiteGe360Id = c.str("jobsite_id").ifBlank { null }
            )
        }

    fun accruals(): List<CompensationAccrualV1> =
        read("accruals", "period_start DESC, ge360_id") { c ->
            CompensationAccrualV1(
                ge360Id = c.str("ge360_id"),
                agreementGe360Id = c.str("agreement_id"),
                amountCents = c.long("amount_cents"),
                status = CompensationAccrualStatus.valueOf(c.str("status")),
                periodStart = LocalDate.parse(c.str("period_start")),
                periodEnd = LocalDate.parse(c.str("period_end")),
                description = c.str("description")
            )
        }

    fun payments(): List<CompensationPaymentEventV1> =
        read("payments", "recorded_at, ge360_id") { c ->
            CompensationPaymentEventV1(
                ge360Id = c.str("ge360_id"),
                accrualGe360Id = c.str("accrual_id"),
                kind = CompensationPaymentKind.valueOf(c.str("kind")),
                amountCents = c.long("amount_cents"),
                recordedAt = LocalDate.parse(c.str("recorded_at")),
                proofDocumentGe360Id = c.str("proof_document_id").ifBlank { null },
                financeRecordGe360Id = c.str("finance_record_id").ifBlank { null },
                reversesPaymentGe360Id = c.str("reverses_payment_id").ifBlank { null }
            )
        }

    /** Both rows are written in one SQLite transaction, never as a half-created compensation. */
    fun saveConfirmedAccrual(agreement: CompensationAgreementV1, accrual: CompensationAccrualV1) {
        require(accrual.agreementGe360Id == agreement.ge360Id) {
            "Accordo e maturato non corrispondenti"
        }
        require(accrual.status == CompensationAccrualStatus.CONFIRMED) {
            "Questa operazione richiede un maturato confermato"
        }
        CompensationLedgerRulesV1.validatePeriod(agreement.mode, accrual)
        val db = writableDatabase
        db.beginTransaction()
        try {
            require(agreements().none { it.ge360Id == agreement.ge360Id }) { "Accordo già registrato" }
            require(accruals().none { it.ge360Id == accrual.ge360Id }) { "Maturato già registrato" }
            db.insertOrThrow("agreements", null, agreementValues(agreement))
            db.insertOrThrow("accruals", null, accrualValues(accrual))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun saveAgreement(agreement: CompensationAgreementV1) {
        writableDatabase.beginTransaction()
        try {
            require(agreements().none { it.ge360Id == agreement.ge360Id }) {
                "Accordo già presente: nessuna sovrascrittura silenziosa"
            }
            writableDatabase.insertOrThrow("agreements", null, agreementValues(agreement))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun saveAccrual(accrual: CompensationAccrualV1) {
        writableDatabase.beginTransaction()
        try {
            val agreement = agreements().singleOrNull { it.ge360Id == accrual.agreementGe360Id }
                ?: error("Accordo non trovato")
            require(accruals().none { it.ge360Id == accrual.ge360Id }) { "Maturato già registrato" }
            CompensationLedgerRulesV1.validatePeriod(agreement.mode, accrual)
            writableDatabase.insertOrThrow("accruals", null, accrualValues(accrual))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun recordPayment(event: CompensationPaymentEventV1) {
        writableDatabase.beginTransaction()
        try {
            val accrual = accruals().singleOrNull { it.ge360Id == event.accrualGe360Id }
                ?: error("Maturato non trovato")
            val recorded = payments()
            require(recorded.none { it.ge360Id == event.ge360Id }) { "Evento già registrato" }
            // A linked FinanceRecord must be unique across all accruals, not just this one.
            val financeId = event.financeRecordGe360Id?.takeIf(String::isNotBlank)
            require(financeId == null || recorded.none {
                it.kind == CompensationPaymentKind.PAYMENT && it.financeRecordGe360Id == financeId
            }) { "Movimento Spese già collegato a un altro pagamento" }
            CompensationLedgerRulesV1.balance(
                accrual, recorded.filter { it.accrualGe360Id == accrual.ge360Id } + event
            )
            writableDatabase.insertOrThrow("payments", null, paymentValues(event))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun invoices(): List<CompensationInvoiceLinkV1> =
        read("invoice_links", "accrual_id, ge360_id") { c ->
            CompensationInvoiceLinkV1(c.str("ge360_id"), c.str("accrual_id"),
                c.str("scanner_document_id"))
        }

    fun attachInvoice(link: CompensationInvoiceLinkV1) {
        writableDatabase.beginTransaction()
        try {
            require(accruals().any { it.ge360Id == link.accrualGe360Id }) {
                "Maturato della fattura mancante"
            }
            require(invoices().none {
                it.ge360Id == link.ge360Id ||
                    it.scannerDocumentGe360Id == link.scannerDocumentGe360Id
            }) { "Fattura già associata" }
            writableDatabase.insertOrThrow("invoice_links", null, invoiceValues(link))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun balance(accrual: CompensationAccrualV1): CompensationBalanceV1 =
        CompensationLedgerRulesV1.balance(accrual,
            payments().filter { it.accrualGe360Id == accrual.ge360Id })

    /** No blob or PDF: document references stay with the authoritative DocumentStore. */
    fun exportJson(): String = JSONObject()
        .put("schema", SCHEMA)
        .put("exportedAt", System.currentTimeMillis())
        .put("agreements", JSONArray().apply { agreements().forEach { put(agreementJson(it)) } })
        .put("accruals", JSONArray().apply { accruals().forEach { put(accrualJson(it)) } })
        .put("payments", JSONArray().apply { payments().forEach { put(paymentJson(it)) } })
        .put("invoiceLinks", JSONArray().apply { invoices().forEach { put(invoiceJson(it)) } })
        .toString(2)

    fun importJson(json: String, replaceAll: Boolean): Int {
        val root = JSONObject(json)
        require(root.optString("schema") == SCHEMA) { "Formato backup Compensi sconosciuto" }
        val ag = decodeArray(root, "agreements", ::agreementFromJson)
        val ac = decodeArray(root, "accruals", ::accrualFromJson)
        val pay = decodeArray(root, "payments", ::paymentFromJson)
        val invoices = if (root.has("invoiceLinks")) {
            decodeArray(root, "invoiceLinks", ::invoiceFromJson)
        } else emptyList()
        require(invoices.distinctBy { it.ge360Id }.size == invoices.size)
        require(invoices.distinctBy { it.scannerDocumentGe360Id }.size == invoices.size)
        require(ag.distinctBy { it.ge360Id }.size == ag.size)
        require(ac.distinctBy { it.ge360Id }.size == ac.size)
        require(pay.distinctBy { it.ge360Id }.size == pay.size)

        val db = writableDatabase
        db.beginTransaction()
        try {
            if (replaceAll) {
                db.delete("invoice_links", null, null)
                db.delete("payments", null, null)
                db.delete("accruals", null, null)
                db.delete("agreements", null, null)
            }
            var inserted = 0
            ag.forEach { candidate ->
                val existing = agreements().singleOrNull { it.ge360Id == candidate.ge360Id }
                if (existing != null) {
                    require(existing == candidate) {
                        "Accordo già presente con dati divergenti; merge annullato"
                    }
                } else {
                    db.insertOrThrow("agreements", null, agreementValues(candidate))
                    inserted++
                }
            }
            val allAgreements = agreements().associateBy { it.ge360Id }
            ac.forEach { candidate ->
                val parent = allAgreements[candidate.agreementGe360Id]
                    ?: error("Accordo del maturato mancante")
                CompensationLedgerRulesV1.validatePeriod(parent.mode, candidate)
                val existing = accruals().singleOrNull { it.ge360Id == candidate.ge360Id }
                if (existing != null) {
                    require(existing == candidate) {
                        "Maturato già presente con dati divergenti; merge annullato"
                    }
                } else {
                    db.insertOrThrow("accruals", null, accrualValues(candidate))
                    inserted++
                }
            }
            val allAccruals = accruals().associateBy { it.ge360Id }
            pay.forEach { candidate ->
                require(allAccruals.containsKey(candidate.accrualGe360Id)) {
                    "Maturato del pagamento mancante"
                }
                val current = payments()
                val existing = current.singleOrNull { it.ge360Id == candidate.ge360Id }
                if (existing != null) {
                    require(existing == candidate) {
                        "Conflitto tra eventi Compensi con lo stesso UUID: nessuna sovrascrittura"
                    }
                } else {
                    val financeId = candidate.financeRecordGe360Id?.takeIf { it.isNotBlank() }
                    require(financeId == null || current.none {
                        it.kind == CompensationPaymentKind.PAYMENT &&
                            it.financeRecordGe360Id == financeId
                    }) { "Movimento Spese già collegato a un altro evento" }
                    db.insertOrThrow("payments", null, paymentValues(candidate))
                    inserted++
                }
            }
            invoices.forEach { candidate ->
                require(allAccruals.containsKey(candidate.accrualGe360Id)) {
                    "Maturato di fattura mancante"
                }
                val current = this.invoices()
                val existing = current.singleOrNull { it.ge360Id == candidate.ge360Id }
                if (existing != null) {
                    require(existing == candidate) {
                        "Collegamento fattura già presente con dati divergenti"
                    }
                } else {
                    require(current.none {
                        it.scannerDocumentGe360Id == candidate.scannerDocumentGe360Id
                    }) { "Lo stesso documento Scanner è già collegato a un altro maturato" }
                    db.insertOrThrow("invoice_links", null, invoiceValues(candidate))
                    inserted++
                }
            }
            // Validate the complete imported ledger before committing.
            val allPayments = payments()
            require(allPayments.mapNotNull {
                if (it.kind == CompensationPaymentKind.PAYMENT) it.financeRecordGe360Id else null
            }.filter(String::isNotBlank).let { it.distinct().size == it.size }) {
                "FinanceRecord duplicato"
            }
            allAccruals.values.forEach { item ->
                val events = allPayments.filter { it.accrualGe360Id == item.ge360Id }
                if (item.status == CompensationAccrualStatus.CONFIRMED) {
                    CompensationLedgerRulesV1.balance(item, events)
                } else require(events.isEmpty()) { "Pagamento su maturato non confermato" }
            }
            db.setTransactionSuccessful()
            return inserted
        } finally {
            db.endTransaction()
        }
    }

    private fun <T> read(table: String, order: String, convert: (Cursor) -> T): List<T> {
        val results = mutableListOf<T>()
        readableDatabase.query(table, null, null, null, null, null, order).use { c ->
            while (c.moveToNext()) results += convert(c)
        }
        return results
    }

    private fun Cursor.str(column: String) = getString(getColumnIndexOrThrow(column))
    private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))

    private fun invoiceValues(x: CompensationInvoiceLinkV1) = ContentValues().apply {
        put("ge360_id", x.ge360Id)
        put("accrual_id", x.accrualGe360Id)
        put("scanner_document_id", x.scannerDocumentGe360Id)
    }

    private fun agreementValues(x: CompensationAgreementV1) = ContentValues().apply {
        put("ge360_id", x.ge360Id)
        put("collaborator_id", x.collaboratorGe360Id)
        put("mode", x.mode.name)
        put("amount_cents", x.agreedAmountCents)
        put("currency", x.currency)
        put("description", x.description)
        put("jobsite_id", x.jobsiteGe360Id.orEmpty())
    }
    private fun accrualValues(x: CompensationAccrualV1) = ContentValues().apply {
        put("ge360_id", x.ge360Id)
        put("agreement_id", x.agreementGe360Id)
        put("amount_cents", x.amountCents)
        put("status", x.status.name)
        put("period_start", x.periodStart.toString())
        put("period_end", x.periodEnd.toString())
        put("description", x.description)
    }
    private fun paymentValues(x: CompensationPaymentEventV1) = ContentValues().apply {
        put("ge360_id", x.ge360Id)
        put("accrual_id", x.accrualGe360Id)
        put("kind", x.kind.name)
        put("amount_cents", x.amountCents)
        put("recorded_at", x.recordedAt.toString())
        put("proof_document_id", x.proofDocumentGe360Id.orEmpty())
        put("finance_record_id", x.financeRecordGe360Id.orEmpty())
        put("reverses_payment_id", x.reversesPaymentGe360Id.orEmpty())
    }

    private fun invoiceJson(x: CompensationInvoiceLinkV1) = JSONObject()
        .put("ge360Id", x.ge360Id)
        .put("accrualGe360Id", x.accrualGe360Id)
        .put("scannerDocumentGe360Id", x.scannerDocumentGe360Id)

    private fun agreementJson(x: CompensationAgreementV1) = JSONObject()
        .put("ge360Id", x.ge360Id).put("collaboratorGe360Id", x.collaboratorGe360Id)
        .put("mode", x.mode.name).put("agreedAmountCents", x.agreedAmountCents)
        .put("currency", x.currency).put("description", x.description)
        .put("jobsiteGe360Id", x.jobsiteGe360Id.orEmpty())

    private fun accrualJson(x: CompensationAccrualV1) = JSONObject()
        .put("ge360Id", x.ge360Id).put("agreementGe360Id", x.agreementGe360Id)
        .put("amountCents", x.amountCents).put("status", x.status.name)
        .put("periodStart", x.periodStart.toString()).put("periodEnd", x.periodEnd.toString())
        .put("description", x.description)

    private fun paymentJson(x: CompensationPaymentEventV1) = JSONObject()
        .put("ge360Id", x.ge360Id).put("accrualGe360Id", x.accrualGe360Id)
        .put("kind", x.kind.name).put("amountCents", x.amountCents)
        .put("recordedAt", x.recordedAt.toString())
        .put("proofDocumentGe360Id", x.proofDocumentGe360Id.orEmpty())
        .put("financeRecordGe360Id", x.financeRecordGe360Id.orEmpty())
        .put("reversesPaymentGe360Id", x.reversesPaymentGe360Id.orEmpty())

    private fun invoiceFromJson(x: JSONObject) = CompensationInvoiceLinkV1(
        ge360Id = x.getString("ge360Id"),
        accrualGe360Id = x.getString("accrualGe360Id"),
        scannerDocumentGe360Id = x.getString("scannerDocumentGe360Id")
    )

    private fun agreementFromJson(x: JSONObject) = CompensationAgreementV1(
        ge360Id = x.getString("ge360Id"), collaboratorGe360Id = x.getString("collaboratorGe360Id"),
        mode = CompensationMode.valueOf(x.getString("mode")),
        agreedAmountCents = x.getLong("agreedAmountCents"),
        description = x.optString("description"), currency = x.optString("currency", "EUR"),
        jobsiteGe360Id = x.optString("jobsiteGe360Id").ifBlank { null }
    )
    private fun accrualFromJson(x: JSONObject) = CompensationAccrualV1(
        ge360Id = x.getString("ge360Id"), agreementGe360Id = x.getString("agreementGe360Id"),
        amountCents = x.getLong("amountCents"),
        status = CompensationAccrualStatus.valueOf(x.getString("status")),
        periodStart = LocalDate.parse(x.getString("periodStart")),
        periodEnd = LocalDate.parse(x.getString("periodEnd")),
        description = x.optString("description")
    )
    private fun paymentFromJson(x: JSONObject) = CompensationPaymentEventV1(
        ge360Id = x.getString("ge360Id"), accrualGe360Id = x.getString("accrualGe360Id"),
        kind = CompensationPaymentKind.valueOf(x.getString("kind")),
        amountCents = x.getLong("amountCents"), recordedAt = LocalDate.parse(x.getString("recordedAt")),
        proofDocumentGe360Id = x.optString("proofDocumentGe360Id").ifBlank { null },
        financeRecordGe360Id = x.optString("financeRecordGe360Id").ifBlank { null },
        reversesPaymentGe360Id = x.optString("reversesPaymentGe360Id").ifBlank { null }
    )
    private fun <T> decodeArray(root: JSONObject, name: String, convert: (JSONObject) -> T): List<T> {
        val arr = root.getJSONArray(name)
        return (0 until arr.length()).map { convert(arr.getJSONObject(it)) }
    }

    companion object {
        const val SCHEMA = "ge360.compensi.v1"
        private const val DATABASE = "ge360_compensi_v1.db"
        private const val VERSION = 1
    }
}
