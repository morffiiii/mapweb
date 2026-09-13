package app.terra.explore
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.*
class StreakTests {
    private val today=LocalDate.of(2026,9,12)
    @Test fun yesterdayStillCounts() { val s=WalkingStreak.status(setOf("2026-09-10","2026-09-11"),emptyList(),today); assertEquals(2,s.count); assertFalse(s.canRestore) }
    @Test fun oneMissRestoredOnce() { val walked=setOf("2026-09-10","2026-09-12"); assertTrue(WalkingStreak.status(walked,emptyList(),today).canRestore); val s=WalkingStreak.status(walked,listOf(WalkingStreak.Restore("2026-09-11","2026-09-12")),today); assertEquals(3,s.count); assertEquals(2,s.remaining); assertFalse(s.canRestore) }
    @Test fun monthlyLimitAndReset() { val used=listOf(1,3,5).map { WalkingStreak.Restore("2026-09-0$it","2026-09-0${it+1}") }; assertFalse(WalkingStreak.status(setOf("2026-09-10"),used,today).canRestore); assertEquals(3,WalkingStreak.status(emptySet(),used,LocalDate.of(2026,10,1)).remaining); assertFalse(WalkingStreak.status(setOf("2026-09-09"),emptyList(),today).canRestore) }
}
