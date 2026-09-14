package app.terra.explore

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

enum class Mode(val title: String, val speed: Double) {
    walk("Пешком",12.0), bike("Вело",35.0), car("Авто",90.0), moto("Другое",90.0), other("Другое",100.0);
    val category: Mode get() = if(this==moto) other else this
    val color: Int get() = when(category) { walk -> 0xFFFF5C1F.toInt(); bike -> 0xFF19C9DE.toInt(); car -> 0xFFAA79FF.toInt(); else -> 0xFFFF5798.toInt() }
    companion object { val visible=listOf(walk,car,bike,other) }
}
data class Point(val lat: Double, val lng: Double, val t: Long, val accuracy: Double, val gap: Boolean = false, val excludeDiscovery: Boolean = false) {
    fun valid() = lat.isFinite() && lng.isFinite() && accuracy.isFinite() && abs(lat)<=85 && abs(lng)<=180 && accuracy in 0.0..60.0
    fun distance(p: Point): Double {
        val a = Math.toRadians(lat); val b = Math.toRadians(p.lat)
        val h = sin((b-a)/2).pow(2)+cos(a)*cos(b)*sin(Math.toRadians(p.lng-lng)/2).pow(2)
        return 12742000*asin(sqrt(h.coerceIn(0.0,1.0)))
    }
    fun connects(p: Point) = !p.gap && p.t>t && p.t-t<=60000
    fun accepts(previous: Point?, mode: Mode): Boolean = valid() && (previous == null || (t>previous.t && distance(previous)>=4 && distance(previous)/((t-previous.t)/1000.0)<=mode.speed))
    fun json() = JSONObject().put("lat",lat).put("lng",lng).put("t",t).put("accuracy",accuracy).put("break",gap).put("excludeDiscovery",excludeDiscovery)
    companion object { fun parse(j: JSONObject) = Point(j.getDouble("lat"),j.getDouble("lng"),j.getLong("t"),j.getDouble("accuracy"),j.optBoolean("break"),j.optBoolean("excludeDiscovery")) }
}

object Discovery {
    const val radius = 17.5
    fun cells(sessions: List<Session>): Set<String> {
        val cells=hashSetOf<String>()
        fun stamp(p: Point) {
            val row=floor(p.lat*111195/20).toInt()
            for(y in row-2..row+2) {
                val lat=(y+0.5)*20/111195; val step=20/(111195*cos(Math.toRadians(lat))); val column=floor(p.lng/step).toInt()
                for(x in column-2..column+2) if(p.distance(Point(lat,(x+0.5)*step,p.t,0.0))<=radius) cells.add("$y:$x")
            }
        }
        for(s in sessions) for((i,p) in s.points.withIndex()) {
            if(p.excludeDiscovery) continue
            stamp(p)
            if(i>0 && !s.points[i-1].excludeDiscovery && s.points[i-1].connects(p)) {
                val a=s.points[i-1]; val count=ceil(a.distance(p)/15).toInt().coerceAtMost(500)
                var dlng=p.lng-a.lng; if(dlng>180) dlng-=360; if(dlng< -180) dlng+=360
                for(j in 1 until count) { val f=j.toDouble()/count; stamp(Point(a.lat+(p.lat-a.lat)*f,(a.lng+dlng*f+540)%360-180,p.t,0.0)) }
            }
        }
        return cells
    }
}
data class Session(val id: String = java.util.UUID.randomUUID().toString(), val mode: Mode, val startedAt: Long = System.currentTimeMillis(), var endedAt: Long? = null, var pausedAt: Long? = null, var pausedMs: Long = 0, var breakNext: Boolean = false, val points: MutableList<Point> = mutableListOf(), var steps: Int? = null) {
    fun distance() = points.zipWithNext().sumOf { (a,b) -> if(a.connects(b)) a.distance(b) else 0.0 }
    fun duration(now: Long = System.currentTimeMillis()) = ((endedAt ?: pausedAt ?: now)-startedAt-pausedMs).coerceAtLeast(0)/1000
    fun json() = JSONObject().put("id",id).put("mode",mode.name).put("startedAt",startedAt).put("endedAt",endedAt).put("pausedAt",pausedAt).put("pausedMs",pausedMs).put("breakNext",breakNext).put("points",JSONArray(points.map { it.json() })).put("steps",steps)
    fun validate(finished: Boolean = true) {
        require(id.isNotBlank() && startedAt>=0 && pausedMs>=0 && (!finished || endedAt!=null))
        require(endedAt == null || (endedAt!!>=startedAt && pausedMs<=endedAt!!-startedAt))
        require(pausedAt == null || pausedAt!!>=startedAt)
        points.forEachIndexed { i,p -> require(p.valid() && p.t>=startedAt && p.t<=(endedAt ?: Long.MAX_VALUE) && (i==0 || p.t>points[i-1].t)) }
    }
    companion object { fun parse(j: JSONObject): Session {
        val s = Session(j.getString("id"),Mode.valueOf(j.getString("mode")),j.getLong("startedAt"), if(j.isNull("endedAt")) null else j.getLong("endedAt"), if(j.isNull("pausedAt")) null else j.getLong("pausedAt"),j.optLong("pausedMs"),j.optBoolean("breakNext"))
        s.steps=if(j.isNull("steps")) null else j.getInt("steps")
        val a = j.getJSONArray("points"); require(a.length()<=200000); for(i in 0 until a.length()) s.points.add(Point.parse(a.getJSONObject(i))); return s
    } }
}
