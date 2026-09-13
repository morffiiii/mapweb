package app.terra.explore
import org.junit.Test
import org.junit.Assert.*
class TrackingTests {
    @Test fun overlappingDiscoveryCountedOnce() {
        val s=Session(mode=Mode.walk,startedAt=0,points=mutableListOf(Point(55.0,37.0,1000,5.0)))
        assertTrue(Discovery.cells(listOf(s)).isNotEmpty()); assertEquals(Discovery.cells(listOf(s)),Discovery.cells(listOf(s,s)))
    }
    @Test fun filtersGPS() {
        val a=Point(55.0,37.0,1000,5.0)
        assertFalse(Point(55.0,37.1,2000,5.0).accepts(a,Mode.walk))
        assertFalse(Point(55.0,37.001,10000,100.0).accepts(a,Mode.walk))
        assertTrue(Point(55.0,37.0001,11000,5.0).accepts(a,Mode.walk))
    }
    @Test fun pauseAndGPSGap() {
        val s=Session(mode=Mode.walk,startedAt=1000,endedAt=110000,pausedMs=10000,points=mutableListOf(Point(55.0,37.0,1000,5.0),Point(55.0,37.001,11000,5.0,true),Point(55.0,37.002,100000,5.0)))
        assertEquals(0.0,s.distance(),0.01); assertEquals(99L,s.duration())
    }
    @Test fun backupRoundTrip() {
        val s=Session(mode=Mode.bike,startedAt=1000,endedAt=3000,points=mutableListOf(Point(55.0,37.0,2000,5.0)))
        val decoded=Session.parse(s.json()); decoded.validate(); assertEquals(s.id,decoded.id); assertEquals(s.points,decoded.points)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsMalformedBackup() { Session(mode=Mode.walk,startedAt=1000,endedAt=500).validate() }
}
