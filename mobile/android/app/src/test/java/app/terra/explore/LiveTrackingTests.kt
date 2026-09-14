package app.terra.explore
import org.junit.Test
import org.junit.Assert.*
class LiveTrackingTests {
    @Test fun speedRejectsBadAccuracyAndExpires() {
        val speed=LiveSpeed(); speed.add(2.0,0.3,1000,100.0); assertEquals(7.2,speed.value(1000)!!,0.01)
        speed.add(50.0,10.0,2000,100.0); assertEquals(7.2,speed.value(2000)!!,0.01); assertNull(speed.value(6000))
        speed.add(0.0,0.5,7000,100.0); assertEquals(0.0,speed.value(7000)!!,0.0)
    }
    @Test fun wrongModeStopsDiscoveryAndRecovers() {
        val guard=ModeSpeedGuard(); for(t in 1000L..9000L step 1000) guard.update(30.0,t,Mode.walk)
        assertFalse(guard.allowsDiscovery); assertTrue(guard.warning)
        for(t in 10000L..13000L step 1000) guard.update(5.0,t,Mode.walk)
        assertTrue(guard.allowsDiscovery); assertFalse(guard.warning)
        guard.update(30.0,14000,Mode.bike); assertTrue(guard.allowsDiscovery)
    }
    @Test fun excludedPointsSurviveSave() {
        val session=Session(mode=Mode.walk,startedAt=0,endedAt=10000,points=mutableListOf(Point(55.0,37.0,1000,5.0,excludeDiscovery=true)),steps=15)
        val restored=Session.parse(session.json()); assertTrue(Discovery.cells(listOf(restored)).isEmpty()); assertEquals(15,restored.steps)
    }
}
