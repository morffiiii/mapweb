package app.terra.explore
import org.junit.Test
import org.junit.Assert.*
class ActivityMetricsTests {
    private fun walk()=Session(mode=Mode.walk,startedAt=1000,points=mutableListOf(Point(0.0,0.0,1000,5.0),Point(0.0001,0.0,11000,5.0)))
    @Test fun speedExpiresAndPauses() {
        assertEquals(4.003,ActivityMetrics.speed(walk(),11000),0.02)
        assertEquals(0.0,ActivityMetrics.speed(walk(),30000),0.0)
        assertEquals(0.0,ActivityMetrics.speed(walk().copy(pausedAt=11000),11000),0.0)
    }
    @Test fun energyUsesWeightAndFallback() {
        val base=ActivityMetrics.calories(walk(),70.0)
        assertTrue(base>0); assertEquals(base*2,ActivityMetrics.calories(walk(),140.0),0.001)
        assertEquals(base,ActivityMetrics.calories(walk(),null),0.0)
        assertEquals(base,ActivityMetrics.calories(walk(),Double.NaN),0.0)
    }
    @Test fun noEnergyAcrossGapsOrInCar() {
        val s=walk(); s.points[1]=s.points[1].copy(gap=true); assertEquals(0.0,ActivityMetrics.calories(s,null),0.0)
        s.points[1]=s.points[1].copy(t=90000,gap=false); assertEquals(0.0,ActivityMetrics.calories(s,null),0.0)
        assertEquals(0.0,ActivityMetrics.calories(walk().copy(mode=Mode.car),null),0.0)
    }
}
