package it.edilmilan.ge360.planimetrie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contratti geometrici da collaudare in seguito, nessuna build eseguita. */
class PlanWorkBindingTest {
    private fun room() = FloorPlanRoom(
        ge360Id = "room-uuid", floorPlanId = "plan-uuid",
        name = "Cucina", length = 4.0, width = 3.0, height = 2.8,
        updatedAt = 1234L
    )

    @Test fun pavimento_e_soffitto_sono_misurati_separatamente() {
        assertEquals(12.0, PlanWorkBindingV1.quantity(room(), PlanWorkBindingV1.FLOOR), 0.001)
        assertEquals(12.0, PlanWorkBindingV1.quantity(room(), PlanWorkBindingV1.CEILING), 0.001)
    }

    @Test fun l_identita_del_preventivo_e_del_rilievo_e_uuid_non_nome() {
        val b = PlanWorkBindingV1.fromRoom(room(), "estimate-uuid", "CAP-03", PlanWorkBindingV1.FLOOR)
        assertEquals("room-uuid", b.roomGe360Id)
        assertEquals("plan-uuid", b.planGe360Id)
        assertEquals("estimate-uuid", b.estimateGe360Id)
        assertEquals("CAP-03", b.workCode)
        assertTrue(b.valid())
    }

    @Test fun nessun_importo_e_calcolato_dalla_selezione() {
        val b = PlanWorkBindingV1.fromRoom(room(), "estimate-uuid", "CAP-03", PlanWorkBindingV1.WALLS)
        assertTrue(b.quantityM2 > 0.0)
        assertFalse(b.copy(quantityM2 = Double.NaN).valid())
    }
}
