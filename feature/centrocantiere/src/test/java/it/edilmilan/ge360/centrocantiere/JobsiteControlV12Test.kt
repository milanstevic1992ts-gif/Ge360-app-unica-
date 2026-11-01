package it.edilmilan.ge360.centrocantiere

import it.edilmilan.ge360.attrezzi.MaterialPlanRevisionV1
import it.edilmilan.ge360.attrezzi.MaterialStockMovement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic contracts for the V12 projection. Written but NOT EXECUTED.
 * Never invoke repositories, SQLite, network or mutable user data here.
 */
class JobsiteControlV12Test {
    private val job = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val mat = "33333333-3333-4333-8333-333333333333"

    private fun plan(
        quantity: Double, revision: Long, jobsite: String? = job,
        unit: String = "sacchi"
    ) = MaterialPlanRevisionV1(
        ge360Id = "plan-$revision",
        materialGe360Id = mat,
        jobsiteGe360Id = jobsite,
        quantity = quantity,
        unit = unit,
        confirmedAt = revision
    )

    private fun movement(
        quantity: Double, type: String = MaterialStockMovement.TYPE_CONSUME,
        site: String = job, unit: String = "sacchi"
    ) = MaterialStockMovement(
        ge360Id = "move-${site.hashCode()}-${quantity.toInt()}-$type",
        materialGe360Id = mat,
        materialName = "Synthetic material",
        type = type,
        quantity = quantity,
        unit = unit,
        jobsiteId = site
    )

    @Test fun latestSiteSpecificPlanIsUsedAndOverconsumptionDetected() {
        val pulse = JobsiteControlV12.materialPulse(job,
            listOf(plan(12.0, revision = 1), plan(5.0, revision = 2)),
            listOf(movement(-7.0)))
        assertEquals(1, pulse.planned)
        assertEquals(1, pulse.comparable)
        assertEquals(1, pulse.overPlan)
        assertEquals(0, pulse.unitMismatch)
    }

    @Test fun anotherJobAndGlobalPlanCannotSatisfyThisJob() {
        val pulse = JobsiteControlV12.materialPulse(job,
            listOf(plan(2.0, revision = 3, jobsite = other),
                plan(1.0, revision = 4, jobsite = null)),
            listOf(movement(-3.0), movement(-9.0, site = other)))
        assertEquals(0, pulse.planned)
        assertEquals(1, pulse.withoutPlan)
        assertEquals(1, pulse.movements)
        assertEquals(0, pulse.overPlan)
    }

    @Test fun incompatibleUnitsAreNeverComparedNumerically() {
        val pulse = JobsiteControlV12.materialPulse(job,
            listOf(plan(5.0, revision = 1, unit = "kg")),
            listOf(movement(-7.0, unit = "sacchi")))
        assertEquals(0, pulse.comparable)
        assertEquals(1, pulse.unitMismatch)
        assertEquals(0, pulse.overPlan)
    }

    @Test fun warehouseLoadIsNotInterpretedAsConsumptionOrPurchase() {
        val pulse = JobsiteControlV12.materialPulse(job,
            listOf(plan(2.0, revision = 1)),
            listOf(movement(10.0, type = MaterialStockMovement.TYPE_LOAD)))
        assertEquals(1, pulse.comparable)
        assertEquals(0, pulse.overPlan)
        assertTrue(pulse.movements == 1)
    }

    @Test fun unplannedIndependentJobsiteCanHaveNoMaterialSignals() {
        val pulse = JobsiteControlV12.materialPulse(job, emptyList(), emptyList())
        assertEquals(0, pulse.planned)
        assertEquals(0, pulse.comparable)
        assertEquals(0, pulse.movements)
    }

    @Test fun issueSummaryOnlyCountsActionableSignals() {
        val pulse = JobsiteControlV12.Pulse(job, 1000L, listOf(
            JobsiteControlV12.Signal("Preventivi", "0", "", JobsiteControlV12.Level.INFO),
            JobsiteControlV12.Signal("Materiali", "2", "", JobsiteControlV12.Level.ATTENZIONE),
            JobsiteControlV12.Signal("Lavorazioni", "Bloccate", "", JobsiteControlV12.Level.CRITICO)
        ))
        assertEquals(2, pulse.issues)
        assertEquals(1, pulse.critical)
        assertEquals("Interventi da verificare", pulse.headline)
    }
}
