package app.terra.explore
class ModeSpeedGuard {
    private var highSince: Long?=null
    private var safeSince: Long?=null
    private var lastTime=0L
    var allowsDiscovery=true; private set
    var warning=false; private set
    fun update(kmh: Double,time: Long,mode: Mode) {
        if(!kmh.isFinite() || kmh<0 || time<=lastTime) return
        val limit=when(mode.category) { Mode.walk -> 22.0; Mode.bike -> 65.0; else -> Double.POSITIVE_INFINITY }
        if(time-lastTime>5000) { highSince=null; safeSince=null }; lastTime=time
        if(kmh>limit) { safeSince=null; if(highSince==null) highSince=time; allowsDiscovery=false; warning=time-(highSince ?: time)>=8000 }
        else { highSince=null; if(safeSince==null) safeSince=time; if(allowsDiscovery || time-(safeSince ?: time)>=3000) { allowsDiscovery=true; warning=false } }
    }
}
