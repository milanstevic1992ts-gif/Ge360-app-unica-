package it.edilmilan.ge360.planimetrie

import it.edilmilan.ge360.preventivi.EstimateRecord
import it.edilmilan.ge360.preventivi.RoomTypes
import it.edilmilan.ge360.preventivi.RoomWorkCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RilievoQuoteEngineTest {
    private fun sourceRoom() = FloorPlanRoom(
        ge360Id = "room-1", floorPlanId = "plan-1", name = "Camera 1",
        roomType = "Camera", mode = FloorPlanRoomModes.RECTANGULAR,
        length = 4.0, width = 3.0, height = 2.7
    )
    private fun walls(): List<FloorPlanWall> = listOf(
        FloorPlanWall(ge360Id="wall-1", floorPlanId="plan-1",roomId="room-1",
            length=4.0,direction=WallDirections.EAST,sortOrder=0,
            note=FloorPlanWallOpenings.attach("Parete finestra", listOf(
                FloorPlanOpening(id="window-1", type=OpeningTypes.WINDOW,
                    width=1.2, height=1.4, offset=0.8, sillHeight=0.9)
            ))),
        FloorPlanWall(ge360Id="wall-2", floorPlanId="plan-1",roomId="room-1",
            length=3.0,direction=WallDirections.SOUTH,sortOrder=1),
        FloorPlanWall(ge360Id="wall-3", floorPlanId="plan-1",roomId="room-1",
            length=4.0,direction=WallDirections.WEST,sortOrder=2),
        FloorPlanWall(ge360Id="wall-4", floorPlanId="plan-1",roomId="room-1",
            length=3.0,direction=WallDirections.NORTH,sortOrder=3)
    )

    @Test fun reusesRealRoomAndWallUuids() {
        val targets = RilievoQuoteEngine.targets(sourceRoom(), walls())
        assertEquals(8, targets.size)
        assertEquals("wall:wall-1", targets[4].id)
        assertEquals("Parete finestra", targets[4].title)
        assertEquals("floor", targets[3].id)
        assertEquals("ceiling", targets[2].id)
    }

    @Test fun wallNetQuantityUsesRealOpeningsAndFloorUsesMeasuredArea() {
        val room = sourceRoom()
        val works = RoomWorkCatalog.forType(RoomTypes.ROOM)
        val stucco = works.first { it.code == "STUCCO" }
        val floor = works.first { it.code == "SPC" }
        assertEquals(9.12, RilievoQuoteEngine.suggestedQuantity(
            room, walls(), "wall:wall-1", stucco), 0.001)
        assertEquals(12.0, RilievoQuoteEngine.suggestedQuantity(
            room, walls(), RilievoQuoteEngine.SCOPE_FLOOR, floor), 0.001)
    }

    @Test fun invalidWallProfileRequiresManualQuantityRatherThanGuess() {
        val w = walls().take(2)
        val stucco = RoomWorkCatalog.forType(RoomTypes.ROOM).first { it.code=="STUCCO" }
        assertEquals(0.0, RilievoQuoteEngine.suggestedQuantity(
            sourceRoom(), w, "wall:wall-1", stucco), 0.001)
    }

    @Test fun sameWorkCanBeAssignedToTwoWallsButNotWholeRoomAndWall() {
        val quote = EstimateRecord()
        quote.items += it.edilmilan.ge360.preventivi.EstimateItem(
            priceListCode=RilievoQuoteEngine.itemCode("p","r","wall:w1","STUCCO"))
        assertFalse(RilievoQuoteEngine.overlap(quote,"p","r","wall:w2","STUCCO"))
        assertTrue(RilievoQuoteEngine.overlap(quote,"p","r","room","STUCCO"))
        assertTrue(RilievoQuoteEngine.overlap(quote,"p","r","walls","STUCCO"))
        assertFalse(RilievoQuoteEngine.overlap(quote,"p","other","room","STUCCO"))
    }

    @Test fun createdLineRetainsRoomSurfaceAndGeometryFingerprint() {
        val room=sourceRoom()
        val w=walls()
        val scope=RilievoQuoteEngine.targets(room,w)[4]
        val template=RoomWorkCatalog.forType(RoomTypes.ROOM).first { it.code=="STUCCO" }
        val item=RilievoQuoteEngine.item(
            FloorPlanRecord(ge360Id="plan-1",title="Appartamento"), room,w,scope,template)
        assertEquals(9.12,item.quantity,0.001)
        assertEquals("Camera 1",item.room)
        assertTrue(item.name.contains("Parete finestra"))
        assertEquals(0.0,item.unitPrice,0.001)
        assertTrue(item.priceListCode.startsWith("RILIEVO-QUOTE/plan-1/room-1/wall:wall-1/"))
        assertEquals(RilievoQuoteEngine.snapshot(room,w),RilievoQuoteEngine.previousFingerprint(item))
        val changed=w.toMutableList()
        changed[0]=changed[0].copy(note="Parete finestra senza aperture")
        assertTrue(RilievoQuoteEngine.previousFingerprint(item) !=
            RilievoQuoteEngine.snapshot(room,changed))
    }
}
