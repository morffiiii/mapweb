package app.terra.explore

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.*
import android.os.*

class TrackingService: Service(), LocationListener {
    private lateinit var store: Store
    private lateinit var location: LocationManager
    private lateinit var steps: StepRecorder
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); store=Store.get(this); location=getSystemService(LocationManager::class.java)
        steps=StepRecorder(this) { delta -> store.active?.let { if(it.pausedAt==null && it.mode==Mode.walk) { it.steps=(it.steps ?: 0)+delta; if(!store.save()) stopSelf(); store.notifyChanged() } } }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("recording","Запись маршрута",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action=="stop") { finish(); return START_NOT_STICKY }
        if(intent?.action=="pause") { pause(); return START_NOT_STICKY }
        if(store.blocked || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) { store.message="Разреши точную геопозицию для записи"; store.notifyChanged(); stopSelf(); return START_NOT_STICKY }
        val mode=runCatching { Mode.valueOf(intent?.getStringExtra("mode") ?: "walk") }.getOrDefault(Mode.walk)
        store.pending=store.active?.mode ?: mode; store.message="Ждём точный GPS для записи…"
        startForeground(1,notification())
        val now=System.currentTimeMillis()
        if(store.active==null) store.active=Session(mode=mode,startedAt=now)
        else store.active?.let { s -> s.pausedAt?.let { s.pausedMs+=now-it }; s.pausedAt=null; s.breakNext=true }
        store.liveSpeed.reset(); store.modeGuard=ModeSpeedGuard(); store.stepsAvailable=store.active?.mode==Mode.walk && steps.start()
        store.pending=null; store.message="Запись начата · уточняем GPS…"
        if(!store.save()) { stopSelf(); return START_NOT_STICKY }
        try { location.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0f,this); if(location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) location.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,2000,0f,this) }
        catch(e: Exception) { store.message="Включи геолокацию и разреши доступ Terra"; store.pending=null; stopSelf() }
        store.position?.let { if(System.currentTimeMillis()-it.t in 0..20000) accept(it.copy(t=now)) }
        store.notifyChanged(); return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,TrackingService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"recording").setSmallIcon(R.drawable.ic_terra).setContentTitle("Terra · запись маршрута").setContentText("Можно заблокировать экран").setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Завершить",stop).build()).build()
    }
    private fun accept(p: Point) {
        if(!p.valid()) return
        val now=System.currentTimeMillis()
        if(store.pending!=null) {
            val s=store.active
            if(s==null) store.active=Session(mode=store.pending!!,startedAt=now,points=mutableListOf(p.copy(t=now)))
            else if(s.pausedAt!=null) { s.pausedMs+=now-s.pausedAt!!; s.pausedAt=null; s.breakNext=true }
            store.pending=null
        }
        val s=store.active ?: return
        if(s.pausedAt!=null) return
        val next=p.copy(gap=s.breakNext,excludeDiscovery=!store.modeGuard.allowsDiscovery)
        if(next.t>=s.startedAt && next.accepts(s.points.lastOrNull(),s.mode)) { s.points.add(next); s.breakNext=false }
        if(!store.save()) { stopSelf(); return }
        store.message="GPS ±${p.accuracy.toInt()} м · идёт запись"; store.notifyChanged()
    }
    override fun onLocationChanged(l: Location) {
        if(System.currentTimeMillis()-l.time !in -5000..30000) return
        val p=Point(l.latitude,l.longitude,l.time,l.accuracy.toDouble()); if(l.time<(store.position?.t ?: 0)) return; store.position=p; if(l.hasSpeed()) store.liveSpeed.add(l.speed.toDouble(),if(l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond.toDouble() else 2.5,l.time,100.0); if(l.hasSpeed() && (!l.hasSpeedAccuracy() || l.speedAccuracyMetersPerSecond<=3)) store.active?.let { store.modeGuard.update(l.speed*3.6,l.time,it.mode) }; accept(p); store.notifyChanged()
    }
    private fun pause() { steps.stop(); store.liveSpeed.reset(); store.pending=null; store.active?.let { if(it.pausedAt==null) it.pausedAt=System.currentTimeMillis() }; store.save(); store.message="Запись на паузе"; store.notifyChanged(); stopSelf() }
    private fun finish() { steps.stop(); store.liveSpeed.reset(); store.pending=null; store.active?.let { val now=System.currentTimeMillis(); it.pausedAt?.let { p -> it.pausedMs+=now-p }; it.pausedAt=null; it.endedAt=now; store.sessions.add(it) }; store.active=null; store.save(); store.message="Маршрут сохранён"; store.notifyChanged(); stopSelf() }
    override fun onDestroy() { steps.stop(); location.removeUpdates(this); if(store.active?.pausedAt==null && store.active!=null) { store.active?.pausedAt=System.currentTimeMillis(); store.save() }; store.pending=null; store.notifyChanged(); super.onDestroy() }
    override fun onProviderDisabled(provider: String) { store.message="Нет GPS. Проверь, включена ли геолокация."; store.notifyChanged() }
    override fun onProviderEnabled(provider: String) {}
    @Deprecated("Required on Android 8–10")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
