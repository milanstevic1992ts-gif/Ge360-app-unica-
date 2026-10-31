package it.edilmilan.ge360.attrezzi

import kotlin.math.abs

/**
 * GE360 Materiali: comparison rules over the EXISTING MaterialInventoryStore ledger.
 *
 * TYPE_LOAD is a stock load, NOT automatically an invoiced purchase.
 * TYPE_RETURN is the legacy positive return-to-stock movement, NOT a supplier refund.
 * No inventory mutation, estimate mutation, pricing, sync or automatic learning.
 */
data class PlannedMaterialQuantityV1(
    val materialGe360Id: String,
    val quantity: Double,
    val unit: String,
    val jobsiteGe360Id: String? = null,
    val estimateGe360Id: String? = null,
    val planVersion: String? = null
) {
    init {
        require(materialGe360Id.isNotBlank() && unit.isNotBlank())
        require(quantity.isFinite() && quantity >= 0.0) { "Quantità prevista non valida" }
    }
}

enum class MaterialComparisonStatusV1 {
    COMPARABLE,
    NO_PLAN,
    UNIT_MISMATCH,
    PARTIAL_HISTORY
}

data class MaterialVarianceV1(
    val materialGe360Id: String,
    val unit: String,
    val observedLoads: Double,
    val observedConsumption: Double,
    val observedReturnsToStock: Double,
    val observedAdjustments: Double,
    val plannedConsumption: Double?,
    val consumptionDelta: Double?,
    val consumptionPct: Double?,
    val recordedMovements: Int,
    val status: MaterialComparisonStatusV1
)

object MaterialVarianceRulesV1 {
    /**
     * A recorded movement must be explicitly linked to the requested cantiere,
     * unless a caller requests a global (null) view.
     */
    fun compare(
        materialGe360Id: String,
        unit: String,
        plan: PlannedMaterialQuantityV1?,
        movements: List<MaterialStockMovement>,
        jobsiteGe360Id: String? = null,
        completeHistory: Boolean = false
    ): MaterialVarianceV1 {
        require(materialGe360Id.isNotBlank() && unit.isNotBlank())
        if (plan != null) {
            require(plan.materialGe360Id == materialGe360Id) { "Piano riferito a un altro materiale" }
            require(plan.jobsiteGe360Id == jobsiteGe360Id) { "Perimetro cantiere diverso" }
        }
        val relevant = movements.filter {
            it.materialGe360Id == materialGe360Id &&
                (jobsiteGe360Id == null || it.jobsiteId == jobsiteGe360Id)
        }
        require(relevant.all { it.quantity.isFinite() && it.quantity != 0.0 }) {
            "Movimento con quantità non valida"
        }
        val mismatch = relevant.any { it.unit.trim().lowercase() != unit.trim().lowercase() } ||
            (plan != null && plan.unit.trim().lowercase() != unit.trim().lowercase())

        fun sumType(type: String): Double =
            relevant.filter { it.type == type }.sumOf { abs(it.quantity) }

        val loads = sumType(MaterialStockMovement.TYPE_LOAD)
        val consumption = sumType(MaterialStockMovement.TYPE_CONSUME)
        val returns = sumType(MaterialStockMovement.TYPE_RETURN)
        val adjustments = sumType(MaterialStockMovement.TYPE_ADJUST_PLUS) -
            sumType(MaterialStockMovement.TYPE_ADJUST_MINUS)

        val status = when {
            mismatch -> MaterialComparisonStatusV1.UNIT_MISMATCH
            !completeHistory -> MaterialComparisonStatusV1.PARTIAL_HISTORY
            plan == null -> MaterialComparisonStatusV1.NO_PLAN
            else -> MaterialComparisonStatusV1.COMPARABLE
        }
        val delta = if (status == MaterialComparisonStatusV1.COMPARABLE && plan != null) {
            consumption - plan.quantity
        } else null
        val percent = if (delta != null && plan != null && plan.quantity > 0.0) {
            delta / plan.quantity * 100.0
        } else null
        return MaterialVarianceV1(
            materialGe360Id = materialGe360Id,
            unit = unit,
            observedLoads = loads,
            observedConsumption = consumption,
            observedReturnsToStock = returns,
            observedAdjustments = adjustments,
            plannedConsumption = plan?.quantity,
            consumptionDelta = delta,
            consumptionPct = percent,
            recordedMovements = relevant.size,
            status = status
        )
    }
}
