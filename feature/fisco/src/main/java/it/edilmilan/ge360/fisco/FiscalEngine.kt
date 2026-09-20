package it.edilmilan.ge360.fisco

import it.edilmilan.ge360.spese.FinanceRecord
import it.edilmilan.ge360.spese.FinanceScope
import it.edilmilan.ge360.spese.FinanceType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

object FiscalParams2026 {
    const val YEAR = 2026
    const val FORFETTARIO_LIMIT = 85_000.0
    const val IMMEDIATE_EXIT = 100_000.0
    const val PROFITABILITY_BUILDING = 0.86
    const val FORFETTARIO_RATE = 0.15
    const val STARTUP_RATE = 0.05
    const val STAMP = 2.0

    const val INPS_MIN_INCOME = 18_808.0
    const val INPS_FIXED_IVS = 4_513.92
    const val INPS_MATERNITY = 7.44
    const val INPS_RATE = 0.24
    const val INPS_SECOND_THRESHOLD = 56_224.0
    const val INPS_SECOND_RATE = 0.25
    const val INPS_MAX = 122_295.0
    const val INPS_REDUCTION = 0.35

    const val REGIONAL_LOW_THRESHOLD = 15_000.0
    const val REGIONAL_LOW_RATE = 0.007
    const val REGIONAL_RATE = 0.0123
    const val MUNICIPAL_EXEMPT = 12_500.0
    const val MUNICIPAL_RATE = 0.008

    const val ACCOUNTANT_FORFETTARIO = 700.0
    const val ACCOUNTANT_ORDINARY = 2_200.0
}

data class FiscalSettings(
    val year: Int = FiscalParams2026.YEAR,
    val inpsReduction35: Boolean = false,
    val forfettarioRate: Double = FiscalParams2026.FORFETTARIO_RATE,
    val privateClientShare: Double = 0.95,
    val passVatToPrivateClients: Boolean = true,
    val privateVatRate: Double = 0.10,
    val stampInvoices: Int = 0,
    val otherDeductions: Double = 0.0,
    val otherTaxCredits: Double = 0.0,
    val accountantForfettario: Double = FiscalParams2026.ACCOUNTANT_FORFETTARIO,
    val accountantOrdinary: Double = FiscalParams2026.ACCOUNTANT_ORDINARY
)

data class FiscalExpenseRule(
    val code: String,
    val deductibleRate: Double,
    val vatDeductibleRate: Double,
    val assumedVatRate: Double,
    val warning: String = ""
)

data class FiscalExpense(
    val sourceId: String,
    val category: String,
    val cashAmount: Double,
    val taxableAmount: Double,
    val vatAmount: Double,
    val deductibleCost: Double,
    val recoverableVat: Double,
    val warning: String = ""
)

data class FiscalInput(
    val year: Int,
    val revenue: Double,
    val cashExpenses: Double,
    val movements: Int,
    val expenses: List<FiscalExpense>,
    val settings: FiscalSettings
)

data class InpsResult(
    val base: Double,
    val fixed: Double,
    val excess: Double,
    val maternity: Double,
    val total: Double,
    val reductionApplied: Double,
    val savedByReduction: Double,
    val creditedMonthsEstimate: Double
)

data class RegimeResult(
    val regime: String,
    val revenue: Double,
    val effectiveRevenue: Double,
    val realCosts: Double,
    val fiscalIncome: Double,
    val inps: InpsResult,
    val tax: Double,
    val regionalTax: Double,
    val municipalTax: Double,
    val recoverableVat: Double,
    val stampDuty: Double,
    val accountantCost: Double,
    val fiscalCharges: Double,
    val netCashEstimate: Double,
    val pressurePercent: Double,
    val notes: List<String>
)

data class FiscalComparison(
    val input: FiscalInput,
    val forfettario: RegimeResult,
    val ordinary: RegimeResult,
    val recommended: String,
    val deltaNet: Double,
    val breakEvenCostPercent: Double?,
    val warnings: List<String>,
    val categoryWarnings: List<String>
)

object FiscalEngine {
    fun inputFromFinance(records: List<FinanceRecord>, settings: FiscalSettings): FiscalInput {
        require(settings.year == FiscalParams2026.YEAR) { "Parametri fiscali disponibili solo per il 2026" }
        val prefix = "${settings.year}-"
        val rows = records.filter { it.scope == FinanceScope.BUSINESS && it.date.startsWith(prefix) }
        val revenue = rows.filter { it.type == FinanceType.INCOME }.sumOf { max(0.0, it.amount) }
        val expenseRows = rows.filter { it.type == FinanceType.EXPENSE && it.amount > 0 }
        val expenses = expenseRows.map(::evaluateExpense)
        return FiscalInput(
            year = settings.year,
            revenue = round2(revenue),
            cashExpenses = round2(expenses.sumOf { it.cashAmount }),
            movements = rows.size,
            expenses = expenses,
            settings = settings
        )
    }

    fun compare(input: FiscalInput): FiscalComparison {
        val base = compareWithoutBreakEven(input)
        val breakEven = findBreakEven(input)
        val warnings = buildWarnings(input)
        val categoryWarnings = input.expenses.mapNotNull { e ->
            e.warning.takeIf { it.isNotBlank() }?.let { "${e.category}: $it" }
        }.distinct()
        return FiscalComparison(
            input = input,
            forfettario = base.first,
            ordinary = base.second,
            recommended = if (base.first.netCashEstimate >= base.second.netCashEstimate) "Forfettario" else "Ordinario",
            deltaNet = round2(abs(base.first.netCashEstimate - base.second.netCashEstimate)),
            breakEvenCostPercent = breakEven,
            warnings = warnings,
            categoryWarnings = categoryWarnings
        )
    }

    private fun compareWithoutBreakEven(input: FiscalInput): Pair<RegimeResult, RegimeResult> =
        calculateForfettario(input) to calculateOrdinary(input)

    private fun calculateForfettario(input: FiscalInput): RegimeResult {
        val s = input.settings
        val grossFiscalIncome = round2(input.revenue * FiscalParams2026.PROFITABILITY_BUILDING)
        val inps = calculateInps(grossFiscalIncome, s.inpsReduction35)
        val taxable = round2(max(0.0, grossFiscalIncome - inps.total - max(0.0, s.otherDeductions)))
        val substituteTax = round2(taxable * s.forfettarioRate.coerceIn(0.0, 1.0))
        val stamps = round2(max(0, s.stampInvoices) * FiscalParams2026.STAMP)
        val accountant = round2(max(0.0, s.accountantForfettario))
        val fiscalCharges = round2(inps.total + substituteTax + stamps + accountant)
        val net = round2(input.revenue - input.cashExpenses - fiscalCharges)
        val notes = mutableListOf<String>()
        if (s.inpsReduction35) notes += "Riduzione INPS 35% applicata alla simulazione; verifica che l'opzione sia effettivamente attiva."
        notes += "Nel forfettario i costi reali riducono la cassa ma non il reddito calcolato col coefficiente 86%."
        return RegimeResult(
            regime = "Forfettario",
            revenue = input.revenue,
            effectiveRevenue = input.revenue,
            realCosts = input.cashExpenses,
            fiscalIncome = taxable,
            inps = inps,
            tax = substituteTax,
            regionalTax = 0.0,
            municipalTax = 0.0,
            recoverableVat = 0.0,
            stampDuty = stamps,
            accountantCost = accountant,
            fiscalCharges = fiscalCharges,
            netCashEstimate = net,
            pressurePercent = percent(fiscalCharges, input.revenue),
            notes = notes
        )
    }

    private fun calculateOrdinary(input: FiscalInput): RegimeResult {
        val s = input.settings
        val privateShare = s.privateClientShare.coerceIn(0.0, 1.0)
        val privateGross = input.revenue * privateShare
        val businessRevenue = input.revenue - privateGross
        val privateNet = if (s.passVatToPrivateClients) privateGross else privateGross / (1.0 + s.privateVatRate.coerceAtLeast(0.0))
        val effectiveRevenue = round2(privateNet + businessRevenue)
        val deductibleCosts = round2(input.expenses.sumOf { it.deductibleCost })
        val recoverableVat = round2(input.expenses.sumOf { it.recoverableVat })
        val businessIncome = round2(effectiveRevenue - deductibleCosts)
        val inps = calculateInps(businessIncome, false)
        val taxable = round2(max(0.0, businessIncome - inps.total - max(0.0, s.otherDeductions)))
        val grossIrpef = calculateIrpefGross(taxable)
        val detraction = calculateSmallBusinessDetraction(max(0.0, businessIncome))
        val irpef = round2(max(0.0, grossIrpef - detraction - max(0.0, s.otherTaxCredits)))
        val regional = if (irpef > 0) round2(taxable * if (taxable <= FiscalParams2026.REGIONAL_LOW_THRESHOLD) FiscalParams2026.REGIONAL_LOW_RATE else FiscalParams2026.REGIONAL_RATE) else 0.0
        val municipal = if (irpef > 0 && taxable > FiscalParams2026.MUNICIPAL_EXEMPT) round2(taxable * FiscalParams2026.MUNICIPAL_RATE) else 0.0
        val accountant = round2(max(0.0, s.accountantOrdinary))
        val fiscalCharges = round2(inps.total + irpef + regional + municipal + accountant)
        val net = round2(effectiveRevenue - input.cashExpenses + recoverableVat - fiscalCharges)
        val notes = mutableListOf<String>()
        if (!s.passVatToPrivateClients && privateShare > 0) notes += "La simulazione assume che l'IVA sui clienti privati venga assorbita nel prezzo finale."
        notes += "IVA e deducibilità sono stimate dalle categorie Spese: per il calcolo fiscale definitivo servono dati fattura più dettagliati."
        return RegimeResult(
            regime = "Ordinario",
            revenue = input.revenue,
            effectiveRevenue = effectiveRevenue,
            realCosts = input.cashExpenses,
            fiscalIncome = taxable,
            inps = inps,
            tax = irpef,
            regionalTax = regional,
            municipalTax = municipal,
            recoverableVat = recoverableVat,
            stampDuty = 0.0,
            accountantCost = accountant,
            fiscalCharges = fiscalCharges,
            netCashEstimate = net,
            pressurePercent = percent(fiscalCharges, effectiveRevenue),
            notes = notes
        )
    }

    fun calculateInps(baseIncome: Double, reduction35: Boolean): InpsResult {
        val base = min(max(0.0, baseIncome), FiscalParams2026.INPS_MAX)
        var excessFull = 0.0
        if (base > FiscalParams2026.INPS_MIN_INCOME) {
            val first = min(base, FiscalParams2026.INPS_SECOND_THRESHOLD) - FiscalParams2026.INPS_MIN_INCOME
            if (first > 0) excessFull += first * FiscalParams2026.INPS_RATE
            if (base > FiscalParams2026.INPS_SECOND_THRESHOLD) {
                excessFull += (base - FiscalParams2026.INPS_SECOND_THRESHOLD) * FiscalParams2026.INPS_SECOND_RATE
            }
        }
        val reduction = if (reduction35) FiscalParams2026.INPS_REDUCTION else 0.0
        val factor = 1.0 - reduction
        val fixed = round2(FiscalParams2026.INPS_FIXED_IVS * factor)
        val excess = round2(excessFull * factor)
        val total = round2(fixed + excess + FiscalParams2026.INPS_MATERNITY)
        val saved = round2((FiscalParams2026.INPS_FIXED_IVS + excessFull) - (fixed + excess))
        val creditedMonths = min(12.0, round2(((fixed + excess) / FiscalParams2026.INPS_FIXED_IVS) * 12.0))
        return InpsResult(base, fixed, excess, FiscalParams2026.INPS_MATERNITY, total, reduction, saved, creditedMonths)
    }

    private fun calculateIrpefGross(taxable: Double): Double {
        val base = max(0.0, taxable)
        val first = min(base, 28_000.0) * 0.23
        val second = if (base > 28_000.0) (min(base, 50_000.0) - 28_000.0) * 0.33 else 0.0
        val third = if (base > 50_000.0) (base - 50_000.0) * 0.43 else 0.0
        return round2(first + second + third)
    }

    private fun calculateSmallBusinessDetraction(income: Double): Double {
        val r = max(0.0, income)
        var value = when {
            r <= 0 -> 0.0
            r <= 5_500 -> 1_265.0
            r <= 28_000 -> 500.0 + 765.0 * (28_000.0 - r) / 22_500.0
            r <= 50_000 -> 500.0 * (50_000.0 - r) / 22_000.0
            else -> 0.0
        }
        if (r in 11_000.0..17_000.0) value += 50.0
        return round2(max(0.0, value))
    }

    private fun evaluateExpense(record: FinanceRecord): FiscalExpense {
        val rule = expenseRule(record.category)
        val cash = round2(max(0.0, record.amount))
        val taxable = if (rule.assumedVatRate > 0) round2(cash / (1.0 + rule.assumedVatRate)) else cash
        val vat = round2(cash - taxable)
        val recoverableVat = round2(vat * rule.vatDeductibleRate.coerceIn(0.0, 1.0))
        val nonRecoverableVat = round2(vat - recoverableVat)
        val deductible = round2((taxable + nonRecoverableVat) * rule.deductibleRate.coerceIn(0.0, 1.0))
        return FiscalExpense(
            sourceId = record.ge360Id,
            category = record.category,
            cashAmount = cash,
            taxableAmount = taxable,
            vatAmount = vat,
            deductibleCost = deductible,
            recoverableVat = recoverableVat,
            warning = rule.warning
        )
    }

    private fun expenseRule(categoryRaw: String): FiscalExpenseRule {
        return when (categoryRaw.trim().lowercase()) {
            "materiali" -> FiscalExpenseRule("MATERIALI", 1.0, 1.0, 0.22)
            "subappalti" -> FiscalExpenseRule("SUBAPPALTI", 1.0, 0.0, 0.0, "IVA/reverse charge non ricostruibile dalla sola voce Spese.")
            "collaboratori" -> FiscalExpenseRule("COLLABORATORI", 1.0, 0.0, 0.0, "Verificare natura del compenso, fattura e contributi.")
            "smaltimenti" -> FiscalExpenseRule("SMALTIMENTO", 1.0, 1.0, 0.22)
            "trasporti" -> FiscalExpenseRule("TRASPORTI", 1.0, 1.0, 0.22)
            "attrezzi" -> FiscalExpenseRule("ATTREZZI", 1.0, 1.0, 0.22, "La stima non distingue beni immediatamente deducibili da beni ammortizzabili.")
            "carburante" -> FiscalExpenseRule("CARBURANTE", 0.20, 0.40, 0.22, "Percentuali prudenti da autovettura: configurare il tipo di veicolo prima di usare il dato fiscalmente.")
            "auto" -> FiscalExpenseRule("AUTO", 0.20, 0.40, 0.22, "Deduzione/detrazione dipendono dal veicolo e dall'uso effettivo.")
            "bollette" -> FiscalExpenseRule("UTENZE", 1.0, 1.0, 0.22, "Verificare che l'utenza sia effettivamente inerente all'attività.")
            "casa", "spesa" -> FiscalExpenseRule("NON_DEDUCIBILE", 0.0, 0.0, 0.0, "Categoria normalmente personale: esclusa dalla stima fiscale anche se marcata Azienda.")
            else -> FiscalExpenseRule("DA_VERIFICARE", 0.0, 0.0, 0.0, "Categoria non classificata: nessun vantaggio fiscale applicato automaticamente.")
        }
    }

    private fun findBreakEven(input: FiscalInput): Double? {
        if (input.revenue <= 0) return null
        val currentCosts = input.cashExpenses
        val sourceExpenses = input.expenses
        if (sourceExpenses.isEmpty() || currentCosts <= 0) return null
        var bestPercent: Double? = null
        var bestAbs = Double.MAX_VALUE
        var p = 0.0
        while (p <= 0.80 + 0.0001) {
            val target = input.revenue * p
            val scale = target / currentCosts
            val scaled = sourceExpenses.map {
                it.copy(
                    cashAmount = it.cashAmount * scale,
                    taxableAmount = it.taxableAmount * scale,
                    vatAmount = it.vatAmount * scale,
                    deductibleCost = it.deductibleCost * scale,
                    recoverableVat = it.recoverableVat * scale
                )
            }
            val candidate = input.copy(cashExpenses = target, expenses = scaled)
            val pair = compareWithoutBreakEven(candidate)
            val delta = abs(pair.first.netCashEstimate - pair.second.netCashEstimate)
            if (delta < bestAbs) { bestAbs = delta; bestPercent = p * 100.0 }
            p += 0.005
        }
        return bestPercent?.let(::round2)
    }

    private fun buildWarnings(input: FiscalInput): List<String> {
        val out = mutableListOf<String>()
        val r = input.revenue
        when {
            r > FiscalParams2026.IMMEDIATE_EXIT -> out += "Ricavi oltre 100.000 €: la simulazione segnala la soglia di uscita immediata dal forfettario. Verificare subito col commercialista."
            r > FiscalParams2026.FORFETTARIO_LIMIT -> out += "Ricavi oltre 85.000 €: verificare l'uscita dal forfettario dall'anno successivo."
            r > FiscalParams2026.FORFETTARIO_LIMIT * 0.88 -> out += "Ricavi vicini al limite forfettario di 85.000 €: margine residuo ${round2(FiscalParams2026.FORFETTARIO_LIMIT - r)} €."
        }
        if (input.movements == 0) out += "Nessun movimento Azienda trovato per il ${input.year}."
        if (input.settings.privateClientShare !in 0.0..1.0) out += "Quota clienti privati corretta automaticamente nell'intervallo 0–100%."
        out += "Stima gestionale: non emette dichiarazioni, non sostituisce la classificazione fiscale delle fatture e non considera automaticamente acconti/saldi già versati."
        return out
    }

    private fun percent(part: Double, total: Double): Double = if (total > 0) round2(part / total * 100.0) else 0.0
    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}
