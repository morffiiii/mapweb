package app.terra.explore
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId

object WalkingStreak {
    data class Restore(val day: String,val usedOn: String)
    data class Status(val count: Int,val remaining: Int,val canRestore: Boolean,val yesterday: String,val today: String,val walkedToday: Boolean)
    fun days(sessions: List<Session>): Set<String> = sessions.filter { it.mode.category==Mode.walk && it.endedAt!=null && it.duration()>=300 && it.distance()>0 }.map { Instant.ofEpochMilli(it.endedAt!!).atZone(ZoneId.systemDefault()).toLocalDate().toString() }.toSet()
    fun status(walked: Set<String>, restores: List<Restore>, today: LocalDate=LocalDate.now()): Status {
        val all=walked+restores.map { it.day }; val yesterday=today.minusDays(1).toString(); val used=restores.count { it.usedOn.take(7)==today.toString().take(7) }
        var cursor=if(today.toString() in all) today else today.minusDays(1); var count=0
        while(cursor.toString() in all) { count++; cursor=cursor.minusDays(1) }
        return Status(count,(3-used).coerceAtLeast(0),used<3 && yesterday !in all && today.minusDays(2).toString() in all,yesterday,today.toString(),today.toString() in walked)
    }
}
