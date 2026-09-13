package app.terra.explore

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.location.*
import android.os.*
import android.view.*
import android.widget.*
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.*
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import kotlin.math.*

class MainActivity: Activity(), LocationListener {
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private lateinit var fog: FogView
    private lateinit var store: Store
    private lateinit var location: LocationManager
    private lateinit var status: TextView
    private lateinit var metric: TextView
    private lateinit var start: Button
    private lateinit var finish: Button
    private lateinit var modes: Spinner
    private var centered=false
    private var dark=true
    private var layer: Mode?=null
    private val accent=Color.rgb(255,92,31)
    private val handler=Handler(Looper.getMainLooper())
    private val refreshListener: () -> Unit = { refresh() }
    private val tick=object: Runnable { override fun run() { refresh(); handler.postDelayed(this,1000) } }
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); MapLibre.getInstance(this); store=Store.get(this); location=getSystemService(LocationManager::class.java)
        dark=when(getPreferences(0).getInt("theme",0)) { 1 -> false; 2 -> true; else -> resources.configuration.uiMode and 0x30 == 0x20 }
        window.statusBarColor=if(dark) Color.rgb(24,26,31) else Color.WHITE
        window.navigationBarColor=window.statusBarColor
        val root=FrameLayout(this); root.setBackgroundColor(window.statusBarColor); root.fitsSystemWindows=true; setContentView(root)
        mapView=MapView(this); mapView.onCreate(state); root.addView(mapView,FrameLayout.LayoutParams(-1,-1))
        fog=FogView(this); root.addView(fog,FrameLayout.LayoutParams(-1,-1))
        val top=LinearLayout(this); top.gravity=Gravity.CENTER_VERTICAL
        val title=text("TERRA ↗",26); title.typeface=Typeface.DEFAULT_BOLD; top.addView(title,LinearLayout.LayoutParams(0,-2,1f)); top.addView(button("Слои") { layers() }); panel(root,top,true)
        val bottom=LinearLayout(this); bottom.orientation=LinearLayout.VERTICAL
        status=text("",12); metric=text("",25); metric.typeface=Typeface.MONOSPACE
        bottom.addView(status); bottom.addView(metric)
        modes=Spinner(this); modes.adapter=object: ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,Mode.entries.map { it.title }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = super.getView(position,convertView,parent).also { (it as TextView).setTextColor(if(dark) Color.WHITE else Color.BLACK); it.setPadding(dp(8),dp(10),dp(8),dp(10)) }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        bottom.addView(modes)
        val actions=LinearLayout(this); start=button("Начать прогулку ↗") { toggle() }; start.setTextColor(Color.WHITE); start.background=shape(accent)
        finish=button("Завершить") { command("stop") }; actions.addView(start,LinearLayout.LayoutParams(0,dp(54),1f)); actions.addView(finish,LinearLayout.LayoutParams(0,dp(54),1f)); bottom.addView(actions)
        val nav=LinearLayout(this)
        nav.addView(button("История") { history() },LinearLayout.LayoutParams(0,dp(54),1f)); nav.addView(button("Где я") { locate() },LinearLayout.LayoutParams(0,dp(54),1f)); nav.addView(button("Настройки") { settings() },LinearLayout.LayoutParams(0,dp(54),1f)); bottom.addView(nav); panel(root,bottom,false)
        mapView.getMapAsync { m -> map=m; m.uiSettings.isCompassEnabled=true; m.addOnCameraMoveListener { fog.invalidate() }; loadStyle(); refresh() }
        store.listeners.add(refreshListener); refresh()
    }
    private fun loadStyle() {
        // Public OSM raster tiles: no API key, attribution remains in native map UI.
        val style="""{"version":8,"sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"maxzoom":19,"attribution":"© OpenStreetMap contributors"}},"layers":[{"id":"map","type":"raster","source":"osm"}]}"""
        map?.setStyle(Style.Builder().fromJson(style)) { fog.invalidate() }
        map?.uiSettings?.setAttributionMargins(dp(4),0,0,dp(320))
        map?.uiSettings?.setLogoMargins(dp(4),0,0,dp(350))
    }
    private fun shape(color: Int)=GradientDrawable().also { it.setColor(color); it.cornerRadius=dp(20).toFloat() }
    private fun text(value: String,size: Int)=TextView(this).also { it.text=value; it.textSize=size.toFloat(); it.setTextColor(if(dark) Color.WHITE else Color.rgb(30,31,36)); it.setPadding(0,dp(4),0,dp(4)) }
    private fun button(value: String,action: () -> Unit)=Button(this).also { it.text=value; it.textSize=12f; it.isAllCaps=false; it.setTextColor(accent); it.setBackgroundColor(Color.TRANSPARENT); it.setOnClickListener { action() } }
    private fun panel(root: FrameLayout,content: LinearLayout,top: Boolean) {
        content.setPadding(dp(16),dp(10),dp(16),dp(10)); content.background=shape(if(dark) Color.argb(245,24,26,31) else Color.argb(245,250,249,247)); content.elevation=dp(8).toFloat()
        val p=FrameLayout.LayoutParams(-1,-2,if(top) Gravity.TOP else Gravity.BOTTOM); p.setMargins(dp(12),dp(10),dp(12),dp(10)); root.addView(content,p)
    }
    private fun permitted()=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    private fun watch() {
        if(!permitted()) { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),20); return }
        try {
            location.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,4f,this)
            if(location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) location.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,2000,4f,this)
            if(!location.isProviderEnabled(LocationManager.GPS_PROVIDER)) { store.message="Включи геолокацию в настройках телефона"; refresh() }
        } catch(e: Exception) { store.message="Не удалось включить GPS"; refresh() }
    }
    private fun locate() { if(!permitted()) { watch(); return }; store.position?.let { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat,it.lng),16.0)) } ?: run { store.message="Ждём сигнал GPS…"; watch(); refresh() } }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==20) { if(permitted()) watch() else { store.message="Нужна точная геопозиция. Разреши её в настройках Terra."; refresh(); AlertDialog.Builder(this).setMessage(store.message).setPositiveButton("Настройки") { _,_ -> startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName"))) }.setNegativeButton("Позже",null).show() } }
    }
    private fun toggle() {
        if(store.pending!=null) { command("pause"); return }
        if(store.active!=null && store.active?.pausedAt==null) { command("pause"); return }
        if(!permitted()) { watch(); return }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),21)
        val intent=Intent(this,TrackingService::class.java).putExtra("mode",Mode.entries[modes.selectedItemPosition].name)
        try { startForegroundService(intent) } catch(e: Exception) { alert("Не удалось начать запись: ${e.localizedMessage}") }
    }
    private fun command(action: String) { startService(Intent(this,TrackingService::class.java).setAction(action)) }
    private fun refresh() {
        if(!::status.isInitialized) return
        status.text=store.message
        val s=store.active
        metric.text=if(s==null) "Твой мир. Твой путь." else "%.2f км  %02d:%02d".format(s.distance()/1000,s.duration()/60,s.duration()%60)
        start.text=if(store.pending!=null) "Отменить ожидание" else if(s==null) "Начать прогулку ↗" else if(s.pausedAt==null) "Пауза" else "Продолжить"
        start.isEnabled=!store.blocked; finish.visibility=if(s==null) View.GONE else View.VISIBLE; modes.isEnabled=s==null && store.pending==null
        if(s!=null) modes.setSelection(s.mode.ordinal)
        if(!centered && store.position!=null && map!=null) { centered=true; locate() }; fog.invalidate()
    }
    private fun layers() { AlertDialog.Builder(this).setTitle("Открытые территории").setItems(arrayOf("Все способы")+Mode.entries.map { it.title }) { _,i -> layer=if(i==0) null else Mode.entries[i-1]; fog.invalidate() }.show() }
    private fun history() {
        val sessions=store.sessions.reversed()
        if(sessions.isEmpty()) { alert("Здесь появятся твои прогулки"); return }
        val labels=sessions.map { "${it.mode.title} · %.2f км\n%s · %d мин".format(it.distance()/1000,java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(it.startedAt)),it.duration()/60) }
        AlertDialog.Builder(this).setTitle("История").setItems(labels.toTypedArray()) { _,i -> val s=sessions[i]; layer=s.mode; s.points.firstOrNull()?.let { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat,it.lng),15.0)) }; fog.invalidate() }.setNegativeButton("Закрыть",null).show()
    }
    private fun settings() {
        val items=arrayOf("Тема системы","Светлая тема","Тёмная тема","Экспорт маршрутов","Импорт маршрутов","О записи в фоне")
        AlertDialog.Builder(this).setTitle("Terra · 2.0").setItems(items) { _,i -> when(i) {
            0,1,2 -> { getPreferences(0).edit().putInt("theme",i).apply(); recreate() }
            3 -> startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").putExtra(Intent.EXTRA_TITLE,"Terra-backup.json").addCategory(Intent.CATEGORY_OPENABLE),30)
            4 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),31)
            else -> alert("Нажми «Начать прогулку» и дождись GPS. Запись продолжится с заблокированным экраном. Не останавливай Terra принудительно. Если телефон прерывает запись, разреши работу без ограничений батареи в настройках Terra. Радиус открытия — 35 м. Маршруты хранятся на телефоне; экспортируй их перед переустановкой.")
        } }.show()
    }
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request,result,data); if(result!=RESULT_OK) return; val uri=data?.data ?: return
        try { if(request==30) { contentResolver.openOutputStream(uri)?.use { it.write(store.export()) } ?: error("Файл недоступен") }
            if(request==31) { val bytes=contentResolver.openInputStream(uri)?.use { it.readNBytes(25000001) } ?: error("Файл недоступен"); store.import(bytes) }
        } catch(e: Exception) { alert("Не удалось обработать файл: ${e.localizedMessage}") }
    }
    private fun alert(message: String) { AlertDialog.Builder(this).setMessage(message).setPositiveButton("Понятно",null).show() }
    override fun onLocationChanged(l: Location) { if(System.currentTimeMillis()-l.time !in -5000..30000) return; store.position=Point(l.latitude,l.longitude,l.time,l.accuracy.toDouble()); if(store.active?.pausedAt!=null || (store.active==null && store.pending==null)) store.message="GPS ±${l.accuracy.toInt()} м"; refresh() }
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume(); watch(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); location.removeUpdates(this); mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onDestroy() { store.listeners.remove(refreshListener); mapView.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    private inner class FogView(context: Context): View(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        init { isClickable=false; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO; setLayerType(LAYER_TYPE_SOFTWARE,null) }
        override fun onTouchEvent(event: android.view.MotionEvent)=false
        override fun onDraw(canvas: Canvas) {
            val m=map ?: return; val save=canvas.saveLayer(0f,0f,width.toFloat(),height.toFloat(),null)
            canvas.drawColor(if(dark) Color.argb(230,23,25,32) else Color.argb(218,182,184,189))
            paint.xfermode=PorterDuffXfermode(PorterDuff.Mode.CLEAR); paint.style=Paint.Style.FILL; paint.strokeCap=Paint.Cap.ROUND
            val sessions=store.sessions + listOfNotNull(store.active)
            for(s in sessions) { if(layer!=null && s.mode!=layer) continue
                for((i,p) in s.points.withIndex()) {
                    val xy=m.projection.toScreenLocation(LatLng(p.lat,p.lng)); val radius=(35/m.projection.getMetersPerPixelAtLatitude(p.lat)).toFloat()
                    canvas.drawCircle(xy.x,xy.y,radius,paint)
                    if(i>0 && s.points[i-1].connects(p) && abs(p.lng-s.points[i-1].lng)<180) { val a=m.projection.toScreenLocation(LatLng(s.points[i-1].lat,s.points[i-1].lng)); paint.strokeWidth=radius*2; canvas.drawLine(a.x,a.y,xy.x,xy.y,paint) }
                }
            }
            paint.xfermode=null; canvas.restoreToCount(save)
            store.position?.let { val p=m.projection.toScreenLocation(LatLng(it.lat,it.lng)); paint.color=Color.WHITE; canvas.drawCircle(p.x,p.y,dp(9).toFloat(),paint); paint.color=accent; canvas.drawCircle(p.x,p.y,dp(6).toFloat(),paint) }
        }
    }
}
