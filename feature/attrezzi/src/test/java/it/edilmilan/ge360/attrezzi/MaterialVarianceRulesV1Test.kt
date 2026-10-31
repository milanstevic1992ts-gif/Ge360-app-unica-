package it.edilmilan.ge360.attrezzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Examples for the future QA gate. NOT executed in this development-only branch. */
class MaterialVarianceRulesV1Test {
    private fun move(type: String, qty: Double, unit: String = "sacchi", jobsite: String = "job-1") =
        MaterialStockMovement(
            ge360Id = "m-$type-$qty",
            materialGe360Id = "material-1",
            materialName = "Rasante",
            type = type,
            quantity = qty,
            unit = unit,
            jobsiteId = jobsite
        )

    @Test
    fun distinct_load_consumption_and_return_are_preserved() {
        val plan = PlannedMaterialQuantityV1("material-1", 16.0, "sacchi", "job-1")
        val result = MaterialVarianceRulesV1.compare("material-1", "sacchi", plan, listOf(
            move(MaterialStockMovement.TYPE_LOAD, 17.0),
            move(MaterialStockMovement.TYPE_CONSUME, -15.0),
            move(MaterialStockMovement.TYPE_RETURN, 1.0)
        ), jobsiteGe360Id = "job-1", completeHistory = true)
        assertEquals(17.0, result.observedLoads, 0.001)
        assertEquals(15.0, result.observedConsumption, 0.001)
        assertEquals(1.0, result.observedReturnsToStock, 0.001)
        assertEquals(-1.0, result.consumptionDelta ?: Double.NaN, 0.001)
        assertEquals(MaterialComparisonStatusV1.COMPARABLE, result.status)
    }

    @Test
    fun partial_history_does_not_produce_a_false_variance() {
        val plan = PlannedMaterialQuantityV1("material-1", 16.0, "sacchi", "job-1")
        val result = MaterialVarianceRulesV1.compare("material-1", "sacchi", plan,
            listOf(move(MaterialStockMovement.TYPE_CONSUME, -15.0)), jobsiteGe360Id = "job-1")
        assertEquals(MaterialComparisonStatusV1.PARTIAL_HISTORY, result.status)
        assertNull(result.consumptionDelta)
        assertNull(result.consumptionPct)
    }

    @Test
    fun incompatible_units_cannot_be_compared() {
        val plan = PlannedMaterialQuantityV1("material-1", 12.0, "sacchi", "job-1")
        val result = MaterialVarianceRulesV1.compare("material-1", "sacchi", plan,
            listOf(move(MaterialStockMovement.TYPE_CONSUME, -150.0, "kg")),
            jobsiteGe360Id = "job-1", completeHistory = true)
        assertEquals(MaterialComparisonStatusV1.UNIT_MISMATCH, result.status)
        assertNull(result.consumptionDelta)
    }

    @Test
    fun plan_is_optional_and_no_cantiere_is_required() {
        val result = MaterialVarianceRulesV1.compare(
            "material-1", "sacchi", null, listOf(move(MaterialStockMovement.TYPE_LOAD, 5.0)),
            completeHistory = true
        )
        assertEquals(MaterialComparisonStatusV1.NO_PLAN, result.status)
        assertNull(result.consumptionDelta)
    }

    @Test
    fun other_jobsite_consumption_is_not_included_in_jobsite_view() {
        val plan = PlannedMaterialQuantityV1("material-1", 2.0, "sacchi", "job-1")
        val result = MaterialVarianceRulesV1.compare("material-1", "sacchi", plan, listOf(
            move(MaterialStockMovement.TYPE_CONSUME, -2.0, jobsite = "job-1"),
            move(MaterialStockMovement.TYPE_CONSUME, -9.0, jobsite = "job-2")
        ), jobsiteGe360Id = "job-1", completeHistory = true)
        assertEquals(2.0, result.observedConsumption, 0.001)
        assertEquals(1, result.recordedMovements)
    }

    @Test
    fun invalid_or_unknown_quantity_is_rejected() {
        val plan = PlannedMaterialQuantityV1("material-1", 1.0, "sacchi", "job-1")
        assertThrows(IllegalArgumentException::class.java) {
            MaterialVarianceRulesV1.compare("material-1", "sacchi", plan,
                listOf(move(MaterialStockMovement.TYPE_CONSUME, Double.NaN)),
                jobsiteGe360Id = "job-1", completeHistory = true)
        }
    }


    @Test
    fun same_name_is_not_enough_to_combine_materials() {
        val plan = PlannedMaterialQuantityV1("material-1", 10.0, "sacchi", "job-1")
        val other = MaterialStockMovement(
            ge360Id = "other", materialGe360Id = "material-2",
            materialName = "Rasante", type = MaterialStockMovement.TYPE_CONSUME,
            quantity = -9.0, unit = "sacchi", jobsiteId = "job-1"
        )
        val result = MaterialVarianceRulesV1.compare(
            "material-1", "sacchi", plan, listOf(other),
            jobsiteGe360Id = "job-1", completeHistory = true
        )
        assertEquals(0.0, result.observedConsumption, 0.0)
        assertEquals(-10.0, result.consumptionDelta ?: Double.NaN, 0.0001)
    }

    @Test
    fun no_automatic_percent_when_planned_quantity_is_zero() {
        val plan = PlannedMaterialQuantityV1("material-1", 0.0, "sacchi", "job-1")
        val result = MaterialVarianceRulesV1.compare(
            "material-1", "sacchi", plan, listOf(
                move(MaterialStockMovement.TYPE_CONSUME, -2.0)
            ), jobsiteGe360Id = "job-1", completeHistory = true
        )
        assertEquals(2.0, result.consumptionDelta ?: Double.NaN, 0.0001)
        assertNull(result.consumptionPct)
    }

    @Test
    fun material_plan_revision_uses_explicit_material_and_cantiere_uuids() {
        val revision = MaterialPlanRevisionV1(
            ge360Id = "revision-1",
            materialGe360Id = "material-1",
            jobsiteGe360Id = "job-1",
            quantity = 16.0,
            unit = "sacchi",
            confirmedAt = 1761948000000L
        )
        val quantity = revision.toQuantity()
        assertEquals("material-1", quantity.materialGe360Id)
        assertEquals("job-1", quantity.jobsiteGe360Id)
        assertEquals("revision-1", quantity.planVersion)
        assertEquals(16.0, quantity.quantity, 0.0)
    }

}
