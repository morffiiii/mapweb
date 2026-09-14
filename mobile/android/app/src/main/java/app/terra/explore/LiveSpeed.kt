package app.terra.explore
class LiveSpeed {
    private val samples=mutableListOf<Pair<Long,Double>>()
    fun add(metersPerSecond: Double,accuracy: Double,time: Long,maximum: Double) {
        if(!metersPerSecond.isFinite() || metersPerSecond<0 || metersPerSecond>maximum || accuracy !in 0.0..3.0 || time<=(samples.lastOrNull()?.first ?: 0)) return
        samples.removeAll { time-it.first>3000 }; samples.add(time to metersPerSecond*3.6)
        if(metersPerSecond<0.3) { samples.clear(); samples.add(time to 0.0) }
        if(samples.size>3) samples.removeAt(0)
    }
    fun value(now: Long): Double? { val last=samples.lastOrNull() ?: return null; if(now-last.first !in -1000..4000) return null; return samples.map { it.second }.sorted()[samples.size/2] }
    fun reset() { samples.clear() }
}
