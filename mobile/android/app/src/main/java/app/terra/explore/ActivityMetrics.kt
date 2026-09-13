package app.terra.explore
object ActivityMetrics {
    fun speed(s: Session,now: Long=System.currentTimeMillis()): Double {
        if(s.pausedAt!=null || s.endedAt!=null || s.points.size<2) return 0.0
        val b=s.points.last(); val a=s.points[s.points.lastIndex-1]
        return if(now-b.t<=15000 && a.connects(b)) a.distance(b)/((b.t-a.t)/1000.0)*3.6 else 0.0
    }
    fun calories(s: Session,weight: Double?): Double {
        if(s.mode.category!=Mode.walk && s.mode.category!=Mode.bike) return 0.0
        val kg=weight?.takeIf { it.isFinite() && it in 10.0..400.0 } ?: 70.0
        return s.points.zipWithNext().sumOf { (a,b) ->
            if(!a.connects(b)) 0.0 else {
                val seconds=(b.t-a.t)/1000.0; val kmh=a.distance(b)/seconds*3.6
                val met=if(s.mode.category==Mode.walk) when { kmh<3.2 -> 2.3; kmh<4 -> 2.8; kmh<4.8 -> 3.5; kmh<5.6 -> 3.8; kmh<6.4 -> 4.8; else -> 5.5 } else when { kmh<16 -> 4.0; kmh<19.3 -> 6.8; kmh<22.5 -> 8.0; kmh<25.7 -> 10.0; else -> 12.0 }
                if(kmh<0.5) 0.0 else (met-1)*kg*seconds/3600
            }
        }
    }
}
