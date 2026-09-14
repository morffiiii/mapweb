package app.terra.explore
import android.content.Context
import android.hardware.*
import android.os.SystemClock
class StepRecorder(context: Context,private val receive: (Int) -> Unit): SensorEventListener {
    private val manager=context.getSystemService(SensorManager::class.java)
    private var sensor: Sensor?=null
    private var baseline: Float?=null
    private var startTime=0L
    fun start(): Boolean {
        stop(); sensor=manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val device=sensor ?: return false; startTime=SystemClock.elapsedRealtimeNanos()
        return try { manager.registerListener(this,device,SensorManager.SENSOR_DELAY_NORMAL,0) } catch(e: SecurityException) { false }
    }
    fun stop() { manager.unregisterListener(this); baseline=null; sensor=null }
    override fun onSensorChanged(event: SensorEvent) {
        if(sensor==null || event.timestamp<startTime) return
        if(event.sensor.type==Sensor.TYPE_STEP_DETECTOR) receive(1)
        else { val count=event.values[0]; val delta=baseline?.let { (count-it).toInt().coerceAtLeast(0) } ?: 0; baseline=count; if(delta>0) receive(delta) }
    }
    override fun onAccuracyChanged(sensor: Sensor?,accuracy: Int) {}
}
