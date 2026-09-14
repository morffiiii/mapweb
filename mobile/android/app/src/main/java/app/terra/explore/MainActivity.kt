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
import org.maplibre.android.module.http.HttpRequestUtil
import okhttp3.OkHttpClient

class MainActivity: Activity(), LocationListener {
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var yandex: YandexSurface? = null
    private val yandexRoutes=mutableMapOf<ScrollView,YandexSurface>()
    private val nearbyYandex=mutableListOf<LatLng>()
    private var bottomPanel: View? = null
    private val useYandex get()=BuildConfig.YANDEX_MAPKIT_API_KEY.isNotBlank() && getPreferences(0).getString("mapProvider","yandex") == "yandex"
    companion object { private var yandexInitialized=false }

    private lateinit var fog: FogView
    private lateinit var store: Store
    private lateinit var location: LocationManager
    private lateinit var status: TextView
    private lateinit var dashboard: LinearLayout
    private val metricValues=mutableMapOf<String,TextView>()
    private lateinit var activity: TextView
    private val routeMaps=mutableMapOf<ScrollView,MapView>()
    private val videos=mutableMapOf<ScrollView,VideoView>()
    private val routeTasks=mutableMapOf<ScrollView,Runnable>()
    private val showFog get()=getPreferences(0).getBoolean("showFog",true)
    private val showPlaces get()=getPreferences(0).getBoolean("showPlaces",true)
    private lateinit var metric: TextView
    private lateinit var start: Button
    private lateinit var finish: Button
    private lateinit var modes: Spinner
    private lateinit var root: FrameLayout
    private val pages=mutableListOf<ScrollView>()
    private var following=false
    private var wasRecording=false
    private var lastCameraPoint: Point?=null
    private var selectedPhoto: String?=null
    private val editingMedia=mutableListOf<org.json.JSONObject>()
    private var mediaGallery: LinearLayout?=null
    private var mediaBusy=false
    private var cameraUri: android.net.Uri?=null
    private val nearbyMarkers=mutableListOf<org.maplibre.android.annotations.Marker>()
    private var photoPreview: ImageView?=null
    private var gate=false
    private var centered=false
    private var dark=true
    private var layer: Mode?=null
    private val accent=Color.rgb(255,92,31)
    private val handler=Handler(Looper.getMainLooper())
    private val refreshListener: () -> Unit = { refresh() }
    private val tick=object: Runnable { override fun run() { refresh(); handler.postDelayed(this,1000) } }
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); MapLibre.getInstance(this)
        if(!yandexInitialized && BuildConfig.YANDEX_MAPKIT_API_KEY.isNotBlank()) {
            com.yandex.mapkit.MapKitFactory.setApiKey(BuildConfig.YANDEX_MAPKIT_API_KEY)
            com.yandex.mapkit.MapKitFactory.initialize(applicationContext); yandexInitialized=true
        }
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent","Terra/2.0 (https://github.com/morffiiii/mapweb)").build()) }.build())
        store=Store.get(this); location=getSystemService(LocationManager::class.java)
        dark=when(getPreferences(0).getInt("theme",0)) { 1 -> false; 2 -> true; else -> resources.configuration.uiMode and 0x30 == 0x20 }
        window.statusBarColor=if(dark) Color.rgb(24,26,31) else Color.WHITE
        window.navigationBarColor=window.statusBarColor
        root=FrameLayout(this); root.setBackgroundColor(window.statusBarColor); root.fitsSystemWindows=true; setContentView(root)
        mapView=MapView(this); mapView.onCreate(state); root.addView(mapView,FrameLayout.LayoutParams(-1,-1))
        if(useYandex) {
            val surface=YandexSurface(this); surface.night(dark); yandex=surface
            root.addView(surface,FrameLayout.LayoutParams(-1,-1)); mapView.visibility=View.GONE
            surface.onLongPress={ markPlace(it) }; surface.onTap={ selectPlace(it) }
            surface.onCamera={ if(::fog.isInitialized) fog.invalidate() }; surface.onGesture={ following=false }
            root.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
                val available=(root.height-(bottomPanel?.height ?: 0)-dp(20)).coerceAtLeast(dp(100))
                if(surface.layoutParams.height!=available) surface.layoutParams=FrameLayout.LayoutParams(-1,available)
            }
        }
        fog=FogView(this); root.addView(fog,FrameLayout.LayoutParams(-1,-1))
        val top=LinearLayout(this); top.gravity=Gravity.CENTER_VERTICAL
        val title=text("TERRA ↗",26); title.typeface=Typeface.DEFAULT_BOLD; top.addView(navIcon("Слои", "layers") { layers() }); top.addView(navIcon("Настройки", "settings") { settings() }); title.gravity=Gravity.END; top.addView(title,LinearLayout.LayoutParams(0,-2,1f)); panel(root,top,true)
        val bottom=LinearLayout(this); bottom.orientation=LinearLayout.VERTICAL
        status=text("",12); metric=text("",25); metric.typeface=Typeface.MONOSPACE
        activity=text("",14)
        bottom.addView(status); bottom.addView(metric); dashboard=metricPanel(); bottom.addView(dashboard)
        modes=Spinner(this); modes.adapter=object: ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,Mode.visible.map { it.title }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = super.getView(position,convertView,parent).also { (it as TextView).setTextColor(if(dark) Color.WHITE else Color.BLACK); it.setPadding(dp(8),dp(10),dp(8),dp(10)) }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        bottom.addView(modes)
        val actions=LinearLayout(this); start=button("Начать прогулку ↗") { toggle() }; start.setTextColor(Color.WHITE); start.background=shape(accent)
        finish=button("Завершить") { val s=store.active; command("stop"); if(s!=null) handler.postDelayed({ if(store.active==null) replay(s) },250) }; actions.addView(start,LinearLayout.LayoutParams(0,dp(54),1f)); actions.addView(finish,LinearLayout.LayoutParams(0,dp(54),1f)); bottom.addView(actions)
        val nav=LinearLayout(this)
        nav.addView(navIcon("История", "history") { history() },LinearLayout.LayoutParams(0,dp(54),1f)); nav.addView(navIcon("Где я", "location") { following=true; locate() },LinearLayout.LayoutParams(0,dp(54),1f)); nav.addView(navIcon("Профиль", "profile") { profile() },LinearLayout.LayoutParams(0,dp(54),1f)); bottom.addView(nav)
        val attribution=text(if(useYandex) "Яндекс Карты" else "© OpenStreetMap contributors · MapLibre",10)
        attribution.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(if(useYandex) "https://yandex.ru/maps/" else "https://www.openstreetmap.org/copyright"))) }; bottom.addView(attribution); panel(root,bottom,false)
        mapView.getMapAsync { m -> map=m; m.uiSettings.isCompassEnabled=true; m.addOnCameraMoveListener { fog.invalidate() }; m.addOnCameraMoveStartedListener { reason -> if(reason==1) following=false }; m.addOnMapLongClickListener { coordinate -> markPlace(coordinate); true }; m.addOnMapClickListener { coordinate -> selectPlace(coordinate) }; loadStyle(); refresh() }
        store.listeners.add(refreshListener); refresh()
        gate=!getPreferences(0).getBoolean("onboardingComplete",false) || (!LocalAccount(this).exists() || !getPreferences(0).getBoolean("localSession",false))
        if(gate) welcome()
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
        if(!top) bottomPanel=content
        content.setPadding(dp(16),dp(10),dp(16),dp(10)); content.background=shape(if(dark) Color.argb(245,24,26,31) else Color.argb(245,250,249,247)); content.elevation=dp(8).toFloat()
        val p=FrameLayout.LayoutParams(-1,-2,if(top) Gravity.TOP else Gravity.BOTTOM); p.setMargins(dp(12),dp(10),dp(12),dp(10)); root.addView(content,p)
    }
    private fun permitted()=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    private fun watch() {
        if(!permitted()) { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),20); return }
        try {
            location.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0f,this)
            if(location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) location.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,2000,0f,this)
            if(!location.isProviderEnabled(LocationManager.GPS_PROVIDER)) { store.message="Включи геолокацию в настройках телефона"; refresh() }
        } catch(e: Exception) { store.message="Не удалось включить GPS"; refresh() }
    }
    private fun locate() { if(!permitted()) { watch(); return }; store.position?.let { moveMap(LatLng(it.lat,it.lng),15.5) } ?: run { store.message="Ждём сигнал GPS…"; watch(); refresh() } }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==22) { toggle(); return }
        if(code==20) { if(permitted()) watch() else { store.message="Нужна точная геопозиция. Разреши её в настройках Terra."; refresh(); AlertDialog.Builder(this).setMessage(store.message).setPositiveButton("Настройки") { _,_ -> startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName"))) }.setNegativeButton("Позже",null).show() } }
    }
    private fun toggle() {
        if(store.pending!=null) { command("pause"); return }
        if(store.active!=null && store.active?.pausedAt==null) { command("pause"); return }
        if(!permitted()) { watch(); return }
        if(Build.VERSION.SDK_INT>=29 && Mode.visible[modes.selectedItemPosition]==Mode.walk && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)!=PackageManager.PERMISSION_GRANTED && !getPreferences(0).getBoolean("stepsAsked",false)) { getPreferences(0).edit().putBoolean("stepsAsked",true).apply(); requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),22); return }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),21)
        val intent=Intent(this,TrackingService::class.java).putExtra("mode",Mode.visible[modes.selectedItemPosition].name)
        try { startForegroundService(intent) } catch(e: Exception) { alert("Не удалось начать запись: ${e.localizedMessage}") }
    }
    private fun command(action: String) { startService(Intent(this,TrackingService::class.java).setAction(action)) }
    private fun refresh() {
        if(!::status.isInitialized) return
        status.text=if(store.active?.pausedAt==null && store.active!=null && !store.modeGuard.allowsDiscovery) (if(store.modeGuard.warning) "Скорость не соответствует режиму · открытие приостановлено" else "Проверяем скорость · открытие приостановлено") else store.message
        dashboard.visibility=if(store.active==null) View.GONE else View.VISIBLE
        metric.visibility=if(store.active==null) View.VISIBLE else View.GONE
        modes.visibility=if(store.active==null) View.VISIBLE else View.GONE
        store.active?.let { s -> metricValues["km"]?.text="%.2f".format(s.distance()/1000); val sec=s.duration(); metricValues["time"]?.text=if(sec>=3600) "%d:%02d:%02d".format(sec/3600,sec/60%60,sec%60) else "%02d:%02d".format(sec/60,sec%60); metricValues["speed"]?.text=if(s.pausedAt!=null) "0.0" else store.liveSpeed.value(System.currentTimeMillis())?.let { "%.1f".format(it) } ?: "—"; metricValues["steps"]?.text=if(s.mode==Mode.walk && store.stepsAvailable) (s.steps ?: 0).toString() else "—"; metricValues["energy"]?.text="%.0f".format(ActivityMetrics.calories(s,personal().optDouble("weight"))) }
        activity.visibility=View.GONE
        store.active?.let { activity.text="%.1f км/ч · ≈ %.0f активных ккал".format(ActivityMetrics.speed(it),ActivityMetrics.calories(it,personal().optDouble("weight"))) }
        val s=store.active
        val recording=s!=null && s.pausedAt==null
        if(recording && !wasRecording) following=true
        wasRecording=recording
        if(recording && following) store.position?.let { p -> if(lastCameraPoint==null || lastCameraPoint!!.distance(p)>5) { lastCameraPoint=p; moveMap(LatLng(p.lat,p.lng),15.5) } }
        metric.text=if(s==null) "Твой мир. Твой путь." else "%.2f км  %02d:%02d".format(s.distance()/1000,s.duration()/60,s.duration()%60)
        start.text=if(store.pending!=null) "Отменить ожидание" else if(s==null) "Начать прогулку ↗" else if(s.pausedAt==null) "Пауза" else "Продолжить"
        start.isEnabled=!store.blocked; finish.visibility=if(s==null) View.GONE else View.VISIBLE; modes.isEnabled=s==null && store.pending==null
        if(s!=null) modes.setSelection(Mode.visible.indexOf(s.mode.category))
        if(!centered && store.position!=null && (map!=null || yandex!=null)) { centered=true; locate() }; fog.invalidate()
    }
    private fun metricPanel(): LinearLayout {
        val panel=LinearLayout(this); panel.orientation=LinearLayout.VERTICAL
        listOf(listOf(Triple("km","КМ","location"),Triple("time","ВРЕМЯ","history"),Triple("speed","КМ/Ч","speed")),listOf(Triple("steps","ШАГИ","steps"),Triple("energy","ККАЛ ≈","fire"))).forEach { group ->
            val row=LinearLayout(this)
            group.forEach { (key,unit,kind) -> val cell=LinearLayout(this); cell.orientation=LinearLayout.VERTICAL; val value=text("—",24); value.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); value.setSingleLine(); value.setAutoSizeTextTypeUniformWithConfiguration(14,24,1,android.util.TypedValue.COMPLEX_UNIT_SP); value.contentDescription=unit; metricValues[key]=value; cell.addView(value)
                val caption=text(unit,10); caption.setTextColor(if(dark) Color.LTGRAY else Color.DKGRAY); val icon=navIcon(unit,kind) {}.drawable; icon.setBounds(0,0,dp(14),dp(14)); caption.setCompoundDrawables(icon,null,null,null); caption.compoundDrawablePadding=dp(5); cell.addView(caption); cell.setPadding(0,dp(3),dp(6),dp(10)); row.addView(cell,LinearLayout.LayoutParams(0,-2,1f)) }; panel.addView(row)
        }; return panel
    }
    private fun annotationBitmap(discovery: Boolean): Bitmap {
        val bitmap=Bitmap.createBitmap(dp(32),dp(40),Bitmap.Config.ARGB_8888); val c=Canvas(bitmap); c.scale(dp(32)/32f,dp(40)/40f); val paint=Paint(Paint.ANTI_ALIAS_FLAG); paint.color=if(discovery) Mode.car.color else accent
        c.drawRoundRect(2f,2f,30f,32f,10f,10f,paint); val tail=Path(); tail.moveTo(12f,29f); tail.lineTo(16f,38f); tail.lineTo(20f,29f); tail.close(); c.drawPath(tail,paint)
        paint.color=Color.WHITE; val glyph=Path()
        if(discovery) { glyph.moveTo(16f,8f); glyph.lineTo(19f,14f); glyph.lineTo(25f,17f); glyph.lineTo(19f,20f); glyph.lineTo(16f,26f); glyph.lineTo(13f,20f); glyph.lineTo(7f,17f); glyph.lineTo(13f,14f) } else { glyph.moveTo(10f,9f); glyph.lineTo(22f,9f); glyph.lineTo(22f,26f); glyph.lineTo(16f,22f); glyph.lineTo(10f,26f) }; glyph.close(); c.drawPath(glyph,paint); return bitmap
    }
    private fun navIcon(label: String, kind: String, action: () -> Unit): ImageButton = ImageButton(this).also { b ->
        b.contentDescription=label; b.setBackgroundColor(Color.TRANSPARENT); b.setPadding(dp(12),dp(12),dp(12),dp(12))
        b.setImageDrawable(object: android.graphics.drawable.Drawable() {
            val p=Paint(Paint.ANTI_ALIAS_FLAG).also { it.color=accent; it.strokeWidth=1.8f; it.style=Paint.Style.STROKE; it.strokeCap=Paint.Cap.ROUND }
            override fun draw(c: Canvas) { c.save(); c.translate(bounds.left.toFloat(),bounds.top.toFloat()); c.scale(bounds.width()/24f,bounds.height()/24f)
                when(kind) {
                    "plus" -> { c.drawLine(12f,4f,12f,20f,p); c.drawLine(4f,12f,20f,12f,p) }
                    "close" -> { c.drawLine(6f,6f,18f,18f,p); c.drawLine(18f,6f,6f,18f,p) }
                    "play" -> { val path=Path(); path.moveTo(7f,4f); path.lineTo(21f,12f); path.lineTo(7f,20f); path.close(); c.drawPath(path,p) }
                    "camera" -> { c.drawRoundRect(2f,6f,22f,21f,3f,3f,p); c.drawCircle(12f,13f,4f,p); c.drawLine(7f,3f,17f,3f,p) }
                    "fire" -> { val path=Path(); path.moveTo(12f,2f); path.cubicTo(18f,10f,22f,12f,19f,18f); path.cubicTo(16f,24f,6f,23f,5f,16f); path.cubicTo(4f,11f,9f,8f,10f,5f); path.lineTo(11f,12f); path.close(); c.drawPath(path,p) }
                    "steps" -> { c.drawOval(4f,3f,10f,13f,p); c.drawOval(14f,10f,20f,20f,p); c.drawLine(5f,16f,9f,16f,p); c.drawLine(15f,23f,19f,23f,p) }
                    "speed" -> { c.drawArc(3f,4f,21f,22f,150f,240f,false,p); c.drawLine(12f,14f,17f,8f,p); c.drawCircle(12f,14f,2f,p) }
                    "history" -> { c.drawCircle(12f,12f,8f,p); c.drawLine(12f,7f,12f,12f,p); c.drawLine(12f,12f,16f,14f,p) }
                    "location" -> { val path=Path(); path.moveTo(20f,4f); path.lineTo(14f,21f); path.lineTo(10f,14f); path.lineTo(3f,10f); path.close(); c.drawPath(path,p) }
                    "profile" -> { c.drawCircle(12f,8f,4f,p); c.drawArc(4f,13f,20f,27f,180f,180f,false,p) }
                    "rhythm" -> { val path=Path(); path.moveTo(4f,21f); path.lineTo(11f,13f); path.lineTo(7f,9f); path.lineTo(20f,2f); path.lineTo(20f,9f); path.moveTo(20f,2f); path.lineTo(13f,2f); c.drawPath(path,p); c.drawCircle(4f,21f,2f,p) }
                    "layers" -> { for(y in listOf(5f,10f,15f)) { val path=Path(); path.moveTo(3f,y+3); path.lineTo(12f,y-1); path.lineTo(21f,y+3); path.lineTo(12f,y+7); path.close(); c.drawPath(path,p) } }
                    else -> { for((y,x) in listOf(6f to 9f,12f to 16f,18f to 7f)) { c.drawLine(3f,y,21f,y,p); c.drawCircle(x,y,2f,p) } }
                }; c.restore()
            }
            override fun setAlpha(a: Int) { p.alpha=a }; override fun setColorFilter(f: ColorFilter?) { p.colorFilter=f }; override fun getOpacity()=PixelFormat.TRANSLUCENT
            override fun getIntrinsicWidth()=dp(24); override fun getIntrinsicHeight()=dp(24)
        }); b.setOnClickListener { action() }
    }
    private fun page(title: String): LinearLayout {
        val scroll=ScrollView(this); scroll.setBackgroundColor(if(dark) Color.rgb(19,20,26) else Color.rgb(248,246,240)); scroll.isFillViewport=true
        val column=LinearLayout(this); column.orientation=LinearLayout.VERTICAL; column.setPadding(dp(24),dp(24),dp(24),dp(30)); scroll.addView(column)
        val head=LinearLayout(this); head.gravity=Gravity.CENTER_VERTICAL; head.addView(text(title,30).also { it.typeface=Typeface.DEFAULT_BOLD },LinearLayout.LayoutParams(0,-2,1f)); head.addView(button("✕") { closePage(column) },LinearLayout.LayoutParams(dp(48),dp(48))); column.addView(head)
        root.addView(scroll,FrameLayout.LayoutParams(-1,-1)); pages.add(scroll); scroll.alpha=0f; scroll.animate().alpha(1f).setDuration(180).start(); return column
    }
    private fun closePage(column: LinearLayout) { val scroll=column.parent as? ScrollView ?: return; disposePage(scroll); root.removeView(scroll); pages.remove(scroll) }
    private fun disposePage(scroll: ScrollView) { yandexRoutes.remove(scroll)?.stop(); videos.remove(scroll)?.stopPlayback(); routeTasks.remove(scroll)?.let { handler.removeCallbacks(it) }; routeMaps.remove(scroll)?.let { it.onPause(); it.onStop(); it.onDestroy() } }
    private fun closePages() { pages.forEach { disposePage(it); root.removeView(it) }; pages.clear() }
    @Deprecated("Back navigation compatibility") override fun onBackPressed() { if(gate) { finish(); return }; if(pages.isNotEmpty()) { val last=pages.removeAt(pages.lastIndex); disposePage(last); root.removeView(last) } else super.onBackPressed() }
    private fun row(page: LinearLayout,title: String, detail: String="", color: Int=accent, action: () -> Unit) {
        val b=button(title + if(detail.isBlank()) "" else "\n$detail",action); b.gravity=Gravity.START or Gravity.CENTER_VERTICAL; b.textSize=16f; b.setTextColor(if(dark) Color.WHITE else Color.rgb(30,31,36));
        val label=android.text.SpannableString(title + if(detail.isBlank()) "" else "\n$detail"); label.setSpan(android.text.style.StyleSpan(Typeface.BOLD),0,title.length,0); if(detail.isNotBlank()) { label.setSpan(android.text.style.ForegroundColorSpan(if(dark) Color.LTGRAY else Color.DKGRAY),title.length+1,label.length,0); label.setSpan(android.text.style.RelativeSizeSpan(0.82f),title.length+1,label.length,0) }; b.text=label; b.setPadding(dp(18),dp(18),dp(18),dp(18)); b.background=shape(if(dark) Color.rgb(32,34,42) else Color.WHITE)
        val params=LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(14); page.addView(b,params)
    }
    private fun entryPage(title: String): LinearLayout { closePages(); val p=page(title); (p.getChildAt(0) as LinearLayout).getChildAt(1).visibility=View.GONE; return p }
    private fun field(p: LinearLayout,hint: String,type: Int=android.text.InputType.TYPE_CLASS_TEXT): EditText {
        val f=EditText(this); f.hint=hint; f.contentDescription=hint; f.inputType=type; f.setTextColor(if(dark) Color.WHITE else Color.BLACK); f.setHintTextColor(Color.GRAY); f.textSize=17f; f.background=shape(if(dark) Color.rgb(32,34,42) else Color.WHITE); f.setPadding(dp(18),dp(16),dp(18),dp(16)); p.addView(f,LinearLayout.LayoutParams(-1,-2).also { it.topMargin=dp(12) }); return f
    }
    private fun welcome() {
        val p=entryPage("TERRA ↗"); p.addView(text("Мир становится твоим шаг за шагом.",32)); p.addView(text("Открывай карту прогулками, сохраняй места и находи свой ритм.",18))
        if(!LocalAccount(this).exists()) { row(p,"Создать профиль") { credentials(true) } }
        row(p,"Войти") { credentials(false) }; p.addView(text("Пока профиль работает только на этом телефоне. Облачного переноса и восстановления по почте ещё нет.",15))
    }
    private fun credentials(register: Boolean) {
        val p=entryPage(if(register) "Твой профиль" else "С возвращением"); p.addView(text("Локальный вход на этом устройстве. Почта служит логином и пока не подтверждается через сервер.",15))
        val email=field(p,"Почта",android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password=field(p,"Пароль · от 8 символов",android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val feedback=text("",15); p.addView(feedback); var busy=false
        row(p,if(register) "Зарегистрироваться" else "Войти") {
            if(!busy) {
                val mail=email.text.toString().trim(); val secret=password.text.toString()
                if(!android.util.Patterns.EMAIL_ADDRESS.matcher(mail).matches() || mail.length>254 || secret.length !in 8..256) feedback.text="Проверь почту и пароль: от 8 до 256 символов."
                else { busy=true; feedback.text="Сохраняем профиль…"; Thread {
                    var error: String?=null
                    try { val account=LocalAccount(this); if(register) account.register(mail,secret) else if(!account.login(mail,secret)) error="Почта или пароль не совпадают с профилем на этом телефоне." } catch(e: Exception) { error=e.localizedMessage ?: "Не удалось сохранить профиль" }
                    runOnUiThread { busy=false; if(!isDestroyed && !isFinishing) { if(error!=null) feedback.text=error else { getPreferences(0).edit().putBoolean("localSession",true).apply(); if(getPreferences(0).getBoolean("onboardingComplete",false)) greeting() else onboardingDetails() } } }
                }.start() }
            }
        }
        row(p,"Назад") { if(!busy) welcome() }
    }
    private fun onboardingDetails() {
        val p=entryPage("Познакомимся?"); p.addView(text("01 / 03 · Всё по желанию. Данные можно изменить в профиле.",16))
        val name=field(p,"Как тебя зовут?"); val age=field(p,"Возраст",android.text.InputType.TYPE_CLASS_NUMBER); val height=field(p,"Рост, см",8194); val weight=field(p,"Вес, кг",8194)
        val feedback=text("",15); p.addView(feedback)
        row(p,"Продолжить") {
            if(!validDetails(age.text.toString(),height.text.toString(),weight.text.toString())) feedback.text="Проверь значения или пропусти этот шаг."
            else try { val data=personal(); data.put("name",name.text.toString().ifBlank { "Исследователь" }.take(60)); data.put("age",age.text.toString().toIntOrNull()); data.put("height",height.text.toString().replace(',','.').toDoubleOrNull()); data.put("weight",weight.text.toString().replace(',','.').toDoubleOrNull()); savePersonal(data); onboardingGoals() } catch(e: Exception) { feedback.text="Не удалось сохранить данные" }
        }
        row(p,"Пропустить") { onboardingGoals() }
    }
    private fun validDetails(age: String,height: String,weight: String): Boolean {
        return (age.isBlank() || age.toIntOrNull()?.let { it in 1..120 }==true) && (height.isBlank() || height.replace(',','.').toDoubleOrNull()?.let { it in 50.0..250.0 }==true) && (weight.isBlank() || weight.replace(',','.').toDoubleOrNull()?.let { it in 10.0..400.0 }==true)
    }
    private fun choices(title: String,subtitle: String,options: List<String>,multiple: Boolean,done: (List<String>) -> Unit) {
        val p=entryPage(title); p.addView(text(subtitle,16)); val selected=linkedSetOf<String>(); val buttons=mutableListOf<Button>()
        options.forEach { option -> val b=button(option) { if(option in selected) selected.remove(option) else { if(!multiple) selected.clear(); selected.add(option) }; buttons.forEach { it.background=shape(if(it.text.toString() in selected) accent else if(dark) Color.rgb(32,34,42) else Color.WHITE); it.setTextColor(if(it.text.toString() in selected) Color.WHITE else accent) } }; b.background=shape(if(dark) Color.rgb(32,34,42) else Color.WHITE); b.textSize=18f; b.gravity=Gravity.START or Gravity.CENTER_VERTICAL; b.setPadding(dp(18),dp(18),dp(18),dp(18)); val params=LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(14); p.addView(b,params); buttons.add(b) }
        row(p,"Продолжить") { done(selected.toList()) }; row(p,"Пропустить") { done(emptyList()) }
    }
    private fun onboardingGoals() { choices("Что тебя зовёт?","02 / 03 · Можно выбрать несколько целей",listOf("Больше гулять","Открывать новые места","Кататься на велосипеде","Следить за активностью"),true) { values -> try { val data=personal(); data.put("intentions",org.json.JSONArray(values)); savePersonal(data); onboardingSource() } catch(e: Exception) { alert("Не удалось сохранить выбор") } } }
    private fun onboardingSource() { choices("Как ты нас нашёл?","03 / 03 · Ответ необязателен",listOf("Друзья","Социальные сети","Поиск","Другое"),false) { values -> try { val data=personal(); data.put("source",values.firstOrNull()); savePersonal(data); greeting() } catch(e: Exception) { alert("Не удалось сохранить выбор") } } }
    private fun greeting() { val p=entryPage("Привет,\n${personal().optString("name","Исследователь")}!"); p.addView(text("Первое открытие — выйти за дверь.",30)); p.addView(text("Начни свой маршрут. Карта запомнит путь, а «Ритм» поможет гулять регулярно.",18)); row(p,"Открыть мой мир") { getPreferences(0).edit().putBoolean("onboardingComplete",true).apply(); gate=false; closePages(); watch() } }
    private fun layers() {
        val p=page("Слои"); p.addView(text("Выбери, открытия каких способов показывать на карте.",15))
        listOf("Неоткрытые участки" to "showFog","Мои места" to "showPlaces").forEach { (title,key) ->
            val toggle=Switch(this); toggle.text=title; toggle.textSize=17f; toggle.setTextColor(if(dark) Color.WHITE else Color.BLACK); toggle.setPadding(0,dp(14),0,dp(14)); toggle.isChecked=getPreferences(0).getBoolean(key,true); toggle.setOnCheckedChangeListener { _,checked -> getPreferences(0).edit().putBoolean(key,checked).apply(); fog.invalidate() }; p.addView(toggle)
        }
        row(p,"Убрать найденные точки") { nearbyMarkers.forEach { map?.removeMarker(it) }; nearbyMarkers.clear(); nearbyYandex.clear(); fog.invalidate() }
        row(p,"Неизведанное рядом","Найти ещё не открытые участки") { nearby() }
        row(p,"Все способы",if(layer==null) "Выбрано" else "") { layer=null; fog.invalidate(); closePage(p) }
        Mode.visible.forEach { m -> row(p,m.title,if(layer==m) "Выбран" else "",m.color) { layer=m; fog.invalidate(); closePage(p) } }
    }
    private fun history(mode: Mode?=null) {
        val p=page("История")
        if(store.sessions.isEmpty()) p.addView(text("Первый маршрут ещё впереди. Начни прогулку — здесь останется её история.",21))
        store.sessions.filter { mode==null || it.mode.category==mode }.reversed().forEach { s -> row(p,"${s.mode.title} · %.2f км".format(s.distance()/1000),"${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(s.startedAt))}\n${s.duration()/60} мин · Открыть карту",s.mode.color) { replay(s) } }
    }
    private fun mapSettings() {
        val p=page("Карта")
        for((name,provider) in listOf("Яндекс Карты" to "yandex","OpenStreetMap" to "osm")) {
            row(p,name,if(useYandex == (provider=="yandex")) "Выбрана" else "Переключить") {
                if(provider=="yandex" && BuildConfig.YANDEX_MAPKIT_API_KEY.isBlank()) alert("В этой сборке не настроен ключ карты")
                else { getPreferences(0).edit().putString("mapProvider",provider).apply(); recreate() }
            }
        }
        p.addView(text("Яндекс: схема со светлой и тёмной темой. Спутниковые снимки в мобильном SDK Яндекса недоступны для сторонних приложений.",15))
        row(p,"Google Maps","Пока не подключена") { alert("Нужен отдельный ключ Maps SDK for Android и проект Google Cloud с биллингом.") }
    }
    private fun settings() {
        val p=page("Настройки"); row(p,"Карта","Провайдер и вид карты") { mapSettings() }; p.addView(text("Внешний вид",18))
        listOf("Как на телефоне","Светлая","Тёмная").forEachIndexed { i,name -> row(p,name,if(getPreferences(0).getInt("theme",0)==i) "Выбрана" else "") { getPreferences(0).edit().putInt("theme",i).apply(); recreate() } }
        row(p,"Шаги","Датчик и разрешение физической активности") { alert("Шаги считаются датчиком во время пешего маршрута. Если показано «—», проверь разрешение физической активности в настройках Terra. На устройстве без датчика шаги недоступны.") }
        row(p,"Геопозиция","Разрешения и работа в фоне") { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName"))) }
        p.addView(text("Запись включается сразу. Пока GPS уточняется, неточные координаты не сохраняются. Не останавливай Terra принудительно во время прогулки.\n\nКарты: Яндекс и OpenStreetMap.\nTerra · 2.3.1\nМаршруты и места хранятся на этом телефоне.",15))
    }
    private fun personal(): org.json.JSONObject = org.json.JSONObject(getPreferences(0).getString("personal","{}") ?: "{}")
    private fun savePersonal(p: org.json.JSONObject) { check(getPreferences(0).edit().putString("personal",p.toString()).commit()) { "Не удалось сохранить" } }
    private fun profile() {
        val p=page("Твой профиль"); val data=personal()
        val avatar=ImageView(this); val avatarPath=data.optString("avatar"); if(avatarPath.isNotBlank()) avatar.setImageBitmap(BitmapFactory.decodeFile(java.io.File(filesDir,avatarPath).path)) else avatar.setImageDrawable(navIcon("Фото","profile") {}.drawable)
        avatar.scaleType=ImageView.ScaleType.CENTER_CROP; avatar.background=shape(if(dark) Color.rgb(32,34,42) else Color.WHITE); avatar.clipToOutline=true; avatar.contentDescription="Фото профиля"; val identity=LinearLayout(this); identity.gravity=Gravity.CENTER_VERTICAL; identity.addView(avatar,LinearLayout.LayoutParams(dp(72),dp(72))); p.addView(identity)
        identity.addView(text(data.optString("name","Исследователь"),26),LinearLayout.LayoutParams(0,-2,1f).also { it.leftMargin=dp(18) })
        val sessions=store.sessions.toList(); p.addView(text("%.2f км".format(sessions.sumOf { it.distance() }/1000),36))
        p.addView(text("%d маршрутов · %d минут".format(sessions.size,sessions.sumOf { it.duration() }/60),16))
        val streak=WalkingStreak.status(WalkingStreak.days(sessions),restores())
        val rhythm=LinearLayout(this); rhythm.gravity=Gravity.CENTER_VERTICAL; rhythm.background=shape(if(dark) Color.rgb(32,34,42) else Color.WHITE); rhythm.setPadding(dp(16),dp(16),dp(16),dp(16)); rhythm.addView(navIcon("Ритм прогулок","rhythm") {},LinearLayout.LayoutParams(dp(72),dp(80))); rhythm.addView(text("РИТМ\n${streak.count} дней подряд",24)); p.addView(rhythm)
        val weekRow=LinearLayout(this); val walked=WalkingStreak.days(sessions); val restored=restores().map { it.day }.toSet()
        for(offset in -6L..0L) { val day=java.time.LocalDate.now().plusDays(offset); val key=day.toString(); val label=text(day.format(java.time.format.DateTimeFormatter.ofPattern("EE"))+"\n"+(if(key in walked) "●" else if(key in restored) "↻" else "○"),12); label.gravity=Gravity.CENTER; label.setTextColor(if(key in walked) accent else if(key in restored) Mode.car.color else Color.GRAY); weekRow.addView(label,LinearLayout.LayoutParams(0,-2,1f)) }; p.addView(weekRow)
        p.addView(text((if(streak.walkedToday) "Сегодня прогулка засчитана." else "Прогулка от 5 минут с движением продолжит серию.")+"\nВосстановлений в этом месяце: ${streak.remaining} из 3.",16))
        if(streak.canRestore) row(p,"Восстановить серию","Закрыть вчерашний пропуск · 1 восстановление") {
            val current=WalkingStreak.status(WalkingStreak.days(store.sessions),restores())
            if(current.canRestore) try { val data=personal(); val a=data.optJSONArray("restores") ?: org.json.JSONArray(); a.put(org.json.JSONObject().put("day",current.yesterday).put("usedOn",current.today)); data.put("restores",a); savePersonal(data); closePage(p); profile() } catch(e: Exception) { alert("Не удалось восстановить серию") }
        }
        p.addView(text("ТВОИ РАЗДЕЛЫ",13))
        row(p,"Статистика","Все маршруты и способы передвижения") { statistics() }
        row(p,"Мои места","Удерживай карту, чтобы добавить заметку или фото") { places() }
        row(p,"Мои цели","Расстояние, маршруты, открытая площадь") { goals() }
        row(p,"Достижения","Награды за настоящие открытия") { achievements() }
        row(p,"Личные данные","Имя, возраст, рост, вес и интересы") { editProfile() }
        row(p,"Выйти","Маршруты останутся на этом телефоне") { if(store.active!=null) alert("Сначала заверши текущую запись") else { getPreferences(0).edit().putBoolean("localSession",false).apply(); gate=true; location.removeUpdates(this); welcome() } }
    }
    private fun editProfile() {
        val p=page("Личные данные"); val data=personal(); val name=field(p,"Имя"); name.setText(data.optString("name","Исследователь")); val age=field(p,"Возраст",2); age.setText(data.optString("age")); val height=field(p,"Рост, см",8194); height.setText(data.optString("height")); val weight=field(p,"Вес, кг",8194); weight.setText(data.optString("weight")); p.addView(text("Все поля необязательны. Очисти значение, чтобы удалить его.",15))
        row(p,"Изменить фото") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),41) }
        row(p,"Сохранить") {
            if(!validDetails(age.text.toString(),height.text.toString(),weight.text.toString())) alert("Проверь возраст, рост и вес или оставь поля пустыми.")
            else try { val next=personal(); next.put("name",name.text.toString().ifBlank { "Исследователь" }.take(60)); next.put("age",age.text.toString().toIntOrNull()); next.put("height",height.text.toString().replace(',','.').toDoubleOrNull()); next.put("weight",weight.text.toString().replace(',','.').toDoubleOrNull()); savePersonal(next); closePages(); profile() } catch(e: Exception) { alert("Не удалось сохранить данные") }
        }
        p.addView(text("Интересы · сохраняются сразу",18))
        val selected=data.optJSONArray("intentions") ?: org.json.JSONArray(); val values=(0 until selected.length()).map { selected.getString(it) }.toMutableSet()
        listOf("Больше гулять","Открывать новые места","Кататься на велосипеде","Следить за активностью").forEach { option ->
            val chip=CheckBox(this); chip.text=option; chip.textSize=16f; chip.setTextColor(if(dark) Color.WHITE else Color.BLACK); chip.buttonTintList=android.content.res.ColorStateList.valueOf(accent); chip.isChecked=option in values; chip.setPadding(dp(12),dp(14),dp(12),dp(14)); p.addView(chip)
            chip.setOnClickListener { try { val nextValues=values.toMutableSet(); if(chip.isChecked) nextValues.add(option) else nextValues.remove(option); val next=personal(); next.put("intentions",org.json.JSONArray(nextValues.toList())); savePersonal(next); values.clear(); values.addAll(nextValues) } catch(e: Exception) { chip.isChecked=option in values; alert("Не удалось сохранить выбор") } }
        }
    }
    private fun goals() {
        val p=page("Мои цели"); p.addView(text("Прогресс учитывает все завершённые маршруты.",15))
        val title=EditText(this); title.hint="Название цели"; title.setTextColor(if(dark) Color.WHITE else Color.BLACK); p.addView(title)
        var kind="km"; val selector=LinearLayout(this)
        listOf("km" to "Км","routes" to "Маршруты","area" to "км²").forEach { (key,label) -> selector.addView(button(label) { kind=key; for(i in 0 until selector.childCount) selector.getChildAt(i).alpha=0.5f; selector.getChildAt(listOf("km","routes","area").indexOf(key)).alpha=1f },LinearLayout.LayoutParams(0,dp(48),1f)) }; p.addView(selector)
        val target=EditText(this); target.hint="Цель, например 10"; target.inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL; target.setTextColor(if(dark) Color.WHITE else Color.BLACK); p.addView(target)
        row(p,"Добавить цель") {
            val value=target.text.toString().replace(',','.').toDoubleOrNull()
            if(value==null || !value.isFinite() || value<=0 || value>1000000) alert("Введи положительное число до 1 000 000")
            else try { val data=personal(); val goals=data.optJSONArray("goals") ?: org.json.JSONArray(); goals.put(org.json.JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("title",title.text.toString().ifBlank { "Моя цель" }.take(80)).put("kind",kind).put("target",value)); data.put("goals",goals); savePersonal(data); closePage(p); goals() } catch(e: Exception) { alert("Не удалось сохранить цель") }
        }
        val saved=personal().optJSONArray("goals") ?: org.json.JSONArray(); val sessions=store.sessions.toList()
        Thread { val km=sessions.sumOf { it.distance() }/1000; val area=Discovery.cells(sessions).size*0.0004
            runOnUiThread { for(i in 0 until saved.length()) {
                val goal=saved.getJSONObject(i); val type=goal.getString("kind"); val value=if(type=="km") km else if(type=="routes") sessions.size.toDouble() else area; val total=goal.getDouble("target")
                p.addView(text(goal.getString("title"),24)); p.addView(text("%.2f / %.2f · %.0f%%".format(value,total,min(100.0,value/total*100)),16))
                val bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); bar.max=100; bar.progress=min(100.0,value/total*100).toInt(); bar.progressTintList=android.content.res.ColorStateList.valueOf(accent); p.addView(bar)
                row(p,if(value>=total) "Достигнуто · убрать цель" else "Убрать цель") { try { val data=personal(); val original=data.optJSONArray("goals") ?: org.json.JSONArray(); val next=org.json.JSONArray(); for(j in 0 until original.length()) if(original.getJSONObject(j).getString("id")!=goal.getString("id")) next.put(original.getJSONObject(j)); data.put("goals",next); savePersonal(data); closePage(p); goals() } catch(e: Exception) { alert("Не удалось обновить цель") } }
            } }
        }.start()
    }
    private fun restores(): List<WalkingStreak.Restore> { val a=personal().optJSONArray("restores") ?: org.json.JSONArray(); return (0 until a.length()).map { WalkingStreak.Restore(a.getJSONObject(it).getString("day"),a.getJSONObject(it).getString("usedOn")) } }
    private fun statistics() {
        val p=page("Статистика"); val sessions=store.sessions.toList()
        p.addView(text("%.2f км".format(sessions.sumOf { it.distance() }/1000),32)); p.addView(text("%d маршрутов · %d минут".format(sessions.size,sessions.sumOf { it.duration() }/60),16))
        val area=text("Считаем открытую площадь…",16); p.addView(area)
        Thread { val count=Discovery.cells(sessions).size; runOnUiThread { area.text="≈ %.3f км² открыто".format(count*0.0004) } }.start()
        Mode.visible.forEach { mode -> val routes=sessions.filter { it.mode.category==mode }; row(p,mode.title,"%.2f км · %d маршрутов".format(routes.sumOf { it.distance() }/1000,routes.size),mode.color) { history(mode) } }
        p.addView(text("≈ %.0f активных ккал".format(sessions.sumOf { ActivityMetrics.calories(it,personal().optDouble("weight")) }),26))
        p.addView(text("Оценка для ходьбы и велосипеда по скорости, времени движения и весу. Без веса используем 70 кг. Возраст и рост в этой формуле не участвуют. Паузы и авто не добавляют активных калорий; это не измерение расхода энергии.",15))
    }
    private fun achievements() {
        val p=page("Достижения"); p.addView(text("Открываются по твоим настоящим маршрутам.",16)); val sessions=store.sessions
        val days=sessions.filter { it.points.isNotEmpty() }.map { java.time.Instant.ofEpochMilli(it.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay() }.toSet().sorted()
        var longest=0; var streak=0; var last: Long?=null; for(day in days) { streak=if(last != null && day - last!! == 1L) streak+1 else 1; longest=max(longest,streak); last=day }
        val awards=listOf(Triple("Первый след","Заверши маршрут с координатами",sessions.any { it.points.isNotEmpty() }),Triple("Пешком интереснее","Пройди 10 км пешком",sessions.filter { it.mode.category==Mode.walk }.sumOf { it.distance() }>=10000),Triple("Исследователь","Заверши 10 маршрутов",sessions.size>=10),Triple("Неделя открытий","7 дней подряд с маршрутами",longest>=7),Triple("Коллекционер мест","Сохрани 5 личных мест",(personal().optJSONArray("places")?.length() ?: 0)>=5),Triple("Разными путями","Маршруты тремя способами",sessions.filter { it.points.isNotEmpty() }.map { it.mode.category }.toSet().size>=3))
        awards.forEach { (title,detail,earned) -> row(p,(if(earned) "★ " else "☆ ")+title,(if(earned) "Получено · " else "Ещё впереди · ")+detail,if(earned) accent else Color.GRAY) {} }
    }
    private fun territoryCoverage(t: org.json.JSONObject,cells: Set<String>): Double {
        val center=Point(t.getDouble("lat"),t.getDouble("lng"),0,0.0)
        val count=cells.count { key -> val parts=key.split(':'); val lat=(parts[0].toDouble()+0.5)*20/111195; val lng=(parts[1].toDouble()+0.5)*20/(111195*cos(Math.toRadians(lat))); center.distance(Point(lat,lng,0,0.0))<=500 }
        return (count*400/(Math.PI*500*500)*100).coerceAtMost(100.0)
    }
    private fun nearby() {
        val pos=store.position ?: run { alert("Для поиска нужны текущие координаты"); return }
        val p=page("Рядом с тобой"); p.addView(text("Неоткрытые участки. Расстояние по прямой; доступность прохода проверяй на месте.",15))
        val snapshot=store.sessions+listOfNotNull(store.active?.copy(points=store.active!!.points.toMutableList()))
        Thread { val cells=Discovery.cells(snapshot); val found=mutableListOf<Point>()
            for(radius in listOf(200.0,400.0,700.0,1000.0)) for(i in 0..11) {
                val a=i*Math.PI/6; val lat=pos.lat+cos(a)*radius/111195; val lng=pos.lng+sin(a)*radius/(111195*cos(Math.toRadians(pos.lat)))
                val row=floor(lat*111195/20).toInt(); val rowLat=(row+0.5)*20/111195; val col=floor(lng*111195*cos(Math.toRadians(rowLat))/20).toInt()
                if("$row:$col" !in cells && found.size<6) found.add(Point(lat,lng,0,0.0))
            }
            runOnUiThread { if(found.isEmpty()) p.addView(text("Рядом всё исследовано. Попробуй из другого места.",18))
                found.forEach { target -> row(p,"Неоткрытый участок","${pos.distance(target).toInt()} м от тебя") { following=false; moveMap(LatLng(target.lat,target.lng),16.0); if(useYandex) nearbyYandex.add(LatLng(target.lat,target.lng)); map?.addMarker(org.maplibre.android.annotations.MarkerOptions().position(LatLng(target.lat,target.lng)).title("Неоткрытый участок").icon(org.maplibre.android.annotations.IconFactory.getInstance(this).fromBitmap(annotationBitmap(true))))?.let { nearbyMarkers.add(it) }; closePages() } }
            }
        }.start()
    }
    private fun replay(s: Session) {
        val p=page("Твой маршрут"); val scroll=p.parent as ScrollView
        p.addView(text(s.mode.title+" · "+java.text.DateFormat.getDateTimeInstance().format(java.util.Date(s.startedAt)),16))
        p.addView(text("%.2f км · %d мин".format(s.distance()/1000,s.duration()/60),28))
        p.addView(text("≈ %.0f активных ккал".format(ActivityMetrics.calories(s,personal().optDouble("weight"))),16))
        p.addView(text("Шаги: "+(s.steps?.toString() ?: "—"),16))
        if(useYandex) {
            val preview=YandexSurface(this); preview.night(dark); preview.route=s; yandexRoutes[scroll]=preview
            p.addView(preview,LinearLayout.LayoutParams(-1,dp(420))); preview.start()
            preview.native.setOnTouchListener { v,event -> v.parent.requestDisallowInterceptTouchEvent(event.action!=MotionEvent.ACTION_UP && event.action!=MotionEvent.ACTION_CANCEL); false }
            preview.post { if(yandexRoutes[scroll]===preview) preview.fit(s) }
            if(s.points.isEmpty()) p.addView(text("В этой записи нет точных координат GPS.",16)) else row(p,"Воспроизвести") {
                routeTasks.remove(scroll)?.let { handler.removeCallbacks(it) }; var frame=0
                val task=object: Runnable { override fun run() { if(yandexRoutes[scroll]!==preview) return; frame++; preview.routeCount=max(1,s.points.size*frame/75); if(frame<75) handler.postDelayed(this,80) else routeTasks.remove(scroll) } }
                routeTasks[scroll]=task; handler.post(task)
            }
            return
        }
        val preview=MapView(this); preview.onCreate(null); p.addView(preview,LinearLayout.LayoutParams(-1,dp(420))); routeMaps[scroll]=preview; preview.onStart(); preview.onResume()
        preview.setOnTouchListener { v,event -> v.parent.requestDisallowInterceptTouchEvent(event.action!=MotionEvent.ACTION_UP && event.action!=MotionEvent.ACTION_CANCEL); false }
        preview.getMapAsync { route ->
            if(routeMaps[scroll]!==preview) return@getMapAsync
            val style="""{"version":8,"sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256}},"layers":[{"id":"map","type":"raster","source":"osm"}]}"""
            route.setStyle(Style.Builder().fromJson(style)) {
                if(routeMaps[scroll]!==preview) return@setStyle
                fun draw(count: Int) {
                    route.clear(); var segment=mutableListOf<LatLng>()
                    fun flush() { if(segment.size>1) route.addPolyline(org.maplibre.android.annotations.PolylineOptions().addAll(segment).width(8f).color(s.mode.color)); segment=mutableListOf() }
                    s.points.take(count).forEachIndexed { i,point -> if(i>0 && (!s.points[i-1].connects(point) || abs(s.points[i-1].lng-point.lng)>180)) flush(); segment.add(LatLng(point.lat,point.lng)) }; flush()
                    s.points.firstOrNull()?.let { route.addMarker(org.maplibre.android.annotations.MarkerOptions().position(LatLng(it.lat,it.lng)).title("Старт")) }
                    s.points.take(count).lastOrNull()?.let { route.addMarker(org.maplibre.android.annotations.MarkerOptions().position(LatLng(it.lat,it.lng)).title("Финиш")) }
                }
                draw(s.points.size)
                val positions=s.points.map { LatLng(it.lat,it.lng) }.distinct()
                if(positions.size>1) { val bounds=org.maplibre.android.geometry.LatLngBounds.Builder(); positions.forEach { bounds.include(it) }; preview.post { if(routeMaps[scroll]===preview) route.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(),dp(36))) } }
                else positions.firstOrNull()?.let { route.moveCamera(CameraUpdateFactory.newLatLngZoom(it,16.0)) }
                if(positions.isEmpty()) p.addView(text("В этой записи нет точных координат GPS.",16)) else row(p,"Воспроизвести") {
                    routeTasks.remove(scroll)?.let { handler.removeCallbacks(it) }; var frame=0
                    val task=object: Runnable { override fun run() { if(routeMaps[scroll]!==preview) return; frame++; draw(max(1,s.points.size*frame/75)); if(frame<75) handler.postDelayed(this,80) else routeTasks.remove(scroll) } }; routeTasks[scroll]=task; handler.post(task)
                }
            }
        }
        p.addView(text("© OpenStreetMap contributors · MapLibre",12))
    }
    private fun savedPlaces(): org.json.JSONArray {
        val data=personal(); val list=data.optJSONArray("places") ?: org.json.JSONArray(); var changed=false
        for(i in 0 until list.length()) if(!list.getJSONObject(i).has("id")) { list.getJSONObject(i).put("id",java.util.UUID.randomUUID().toString()); changed=true }
        if(changed) { data.put("places",list); savePersonal(data) }; return list
    }
    private fun moveMap(point: LatLng,zoom: Double) { yandex?.move(point,zoom) ?: map?.animateCamera(CameraUpdateFactory.newLatLngZoom(point,zoom)) }
    private fun screenPoint(point: LatLng): PointF = yandex?.screen(point) ?: map?.projection?.toScreenLocation(point) ?: PointF(-100000f,-100000f)
    private fun metersPerPixel(lat: Double,lng: Double): Double = yandex?.metersPerPixel(lat,lng) ?: map?.projection?.getMetersPerPixelAtLatitude(lat) ?: 1.0
    private fun selectPlace(coordinate: LatLng): Boolean {
        if(!showPlaces) return false
        val tap=screenPoint(coordinate)
        try { val places=savedPlaces(); val found=(0 until places.length()).map { places.getJSONObject(it) }.minByOrNull { val pos=screenPoint(LatLng(it.getDouble("lat"),it.getDouble("lng"))); hypot((pos.x-tap.x).toDouble(),(pos.y-tap.y).toDouble()) } ?: return false
            val pos=screenPoint(LatLng(found.getDouble("lat"),found.getDouble("lng"))); if(hypot((pos.x-tap.x).toDouble(),(pos.y-tap.y).toDouble())>dp(24)) return false
            placeDetails(found); return true
        } catch(e: Exception) { alert("Не удалось открыть место"); return true }
    }
    private fun placeDetails(place: org.json.JSONObject) {
        val p=page("Место"); p.addView(text(PlaceMedia.title(place),26)); if(place.optString("title").isNotBlank() || place.optString("note")!=PlaceMedia.title(place)) p.addView(text(place.optString("note"),17))
        PlaceMedia.items(place).forEach { p.addView(mediaCard(it),LinearLayout.LayoutParams(-1,dp(240)).also { it.topMargin=dp(12) }) }
        row(p,"Показать на карте") { following=false; map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(place.getDouble("lat"),place.getDouble("lng")),16.0)); closePages() }
        row(p,"Редактировать") { markPlace(LatLng(place.getDouble("lat"),place.getDouble("lng")),place) }
        row(p,"Удалить место") { val confirm=page("Удалить место?"); confirm.addView(text("Заметка исчезнет из твоих мест и с карты.",18)); row(confirm,"Удалить") { try { val old=savedPlaces(); val next=org.json.JSONArray(); for(i in 0 until old.length()) if(old.getJSONObject(i).getString("id")!=place.getString("id")) next.put(old.getJSONObject(i)); val data=personal(); data.put("places",next); savePersonal(data); fog.invalidate(); closePages(); places() } catch(e: Exception) { alert("Не удалось удалить место") } } }
    }
    private fun markPlace(coordinate: LatLng,existing: org.json.JSONObject?=null) {
        val p=page(if(existing==null) "Новое место" else "Редактировать место")
        val title=field(p,"Название места"); title.setText(existing?.optString("title") ?: "")
        p.addView(text("Описание",15)); val note=field(p,"Что хочется запомнить здесь?",android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE); note.minLines=4; note.gravity=Gravity.TOP; note.setText(existing?.optString("note") ?: "")
        editingMedia.clear(); if(existing!=null) editingMedia.addAll(PlaceMedia.items(existing)); mediaBusy=false
        val tools=LinearLayout(this); tools.addView(navIcon("Фото и видео из галереи","plus") { if(editingMedia.size<12 && !mediaBusy) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("image/*","video/*")).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true).addCategory(Intent.CATEGORY_OPENABLE),40) }); tools.addView(navIcon("Сделать фото","camera") { capturePlacePhoto() }); p.addView(tools)
        p.addView(text("До 12 фото и видео. Видео — до 100 МБ.",13)); val gallery=LinearLayout(this); gallery.orientation=LinearLayout.VERTICAL; mediaGallery=gallery; p.addView(gallery); renderMedia()
        row(p,"Сохранить место") {
            if(mediaBusy) alert("Дождись загрузки файлов") else try {
                val data=personal(); val list=savedPlaces(); val place=org.json.JSONObject().put("id",existing?.getString("id") ?: java.util.UUID.randomUUID().toString()).put("lat",coordinate.latitude).put("lng",coordinate.longitude).put("title",title.text.toString().trim().take(100)).put("note",note.text.toString().take(4000)).put("media",org.json.JSONArray(editingMedia.toList()))
                if(existing==null) list.put(place) else for(i in 0 until list.length()) if(list.getJSONObject(i).getString("id")==existing.getString("id")) list.put(i,place)
                data.put("places",list); savePersonal(data); fog.invalidate(); closePages(); placeDetails(place)
            } catch(e: Exception) { alert("Не удалось сохранить место") }
        }
    }
    private fun capturePlacePhoto() {
        if(mediaBusy || editingMedia.size>=12) return
        try { val dir=java.io.File(filesDir,"media"); dir.mkdirs(); val file=java.io.File(dir,"camera-"+java.util.UUID.randomUUID()+".jpg"); cameraUri=androidx.core.content.FileProvider.getUriForFile(this,packageName+".media",file)
            startActivityForResult(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT,cameraUri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION),42)
        } catch(e: Exception) { alert("Камера недоступна на этом устройстве") }
    }
    private fun renderMedia() { val gallery=mediaGallery ?: return; gallery.removeAllViews(); editingMedia.forEach { item -> val card=mediaCard(item); val remove=navIcon("Удалить вложение","close") { editingMedia.remove(item); renderMedia() }; remove.background=shape(if(dark) Color.rgb(32,34,42) else Color.WHITE); card.addView(remove,FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP or Gravity.END)); gallery.addView(card,LinearLayout.LayoutParams(-1,dp(240)).also { it.topMargin=dp(12) }) } }
    private fun mediaCard(item: org.json.JSONObject): FrameLayout {
        val card=FrameLayout(this); card.background=shape(if(dark) Color.rgb(32,34,42) else Color.WHITE); card.clipToOutline=true
        val image=ImageView(this); image.scaleType=ImageView.ScaleType.FIT_CENTER; card.addView(image,FrameLayout.LayoutParams(-1,-1)); val file=java.io.File(filesDir,item.getString("file"))
        if(item.optBoolean("video")) {
            Thread { val retriever=android.media.MediaMetadataRetriever(); val bitmap=try { retriever.setDataSource(file.path); retriever.getFrameAtTime(0) } catch(e: Exception) { null } finally { retriever.release() }; runOnUiThread { image.setImageBitmap(bitmap) } }.start()
            val play=navIcon("Смотреть видео","play") { val p=page("Видео"); val video=VideoView(this); videos[p.parent as ScrollView]=video; p.addView(video,LinearLayout.LayoutParams(-1,dp(460))); val controls=MediaController(this); controls.setAnchorView(video); video.setMediaController(controls); video.setVideoPath(file.path); video.setOnPreparedListener { video.start() }; video.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener { override fun onViewAttachedToWindow(v: View) {} ; override fun onViewDetachedFromWindow(v: View) { video.stopPlayback() } }) }; card.addView(play,FrameLayout.LayoutParams(dp(64),dp(64),Gravity.CENTER))
        } else image.setImageBitmap(BitmapFactory.decodeFile(file.path))
        return card
    }
    private fun places() {
        val p=page("Мои места"); val places=try { savedPlaces() } catch(e: Exception) { alert("Не удалось прочитать места"); return }
        if(places.length()==0) p.addView(text("Удерживай карту, чтобы добавить заметку или фото.",20))
        for(i in 0 until places.length()) { val place=places.getJSONObject(i)
            PlaceMedia.items(place).firstOrNull()?.let { p.addView(mediaCard(it),LinearLayout.LayoutParams(-1,dp(200)).also { it.topMargin=dp(12) }) }
            row(p,PlaceMedia.title(place),"Открыть заметку") { placeDetails(place) }
        }
    }
    override fun onActivityResult(request: Int,result: Int,data: Intent?) {
        super.onActivityResult(request,result,data)
        if(request in listOf(40,42)) { if(result==RESULT_OK) { val uris=if(request==42) listOfNotNull(cameraUri) else data?.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(data?.data); val room=(12-editingMedia.size).coerceAtLeast(0); mediaBusy=true; val targetGallery=mediaGallery; Thread { val results=uris.take(room).map { runCatching { PlaceMedia.import(this,it) } }; runOnUiThread { mediaBusy=false; if(!isDestroyed && targetGallery===mediaGallery && targetGallery?.isAttachedToWindow==true) { editingMedia.addAll(results.mapNotNull { it.getOrNull() }); renderMedia(); if(results.any { it.isFailure }) alert("Не все файлы добавлены. Проверь формат и размер видео (до 100 МБ).") } } }.start() }; return }
        if(request!=41 || result!=RESULT_OK) return; val uri=data?.data ?: return
        try {
            val options=BitmapFactory.Options().also { it.inJustDecodeBounds=true }; contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) }
            require(options.outWidth>0 && options.outHeight>0); options.inSampleSize=max(1,max(options.outWidth,options.outHeight)/1400); options.inJustDecodeBounds=false
            val bitmap=contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) } ?: error("Фото недоступно")
            val path=if(request==41) "avatar.jpg" else "place-${java.util.UUID.randomUUID()}.jpg"; java.io.File(filesDir,path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,80,it) }
            if(request==41) { val next=personal(); next.put("avatar",path); savePersonal(next); closePages(); profile() } else { selectedPhoto=path; photoPreview?.setImageBitmap(bitmap); photoPreview?.visibility=View.VISIBLE }
        } catch(e: Exception) { alert("Не удалось открыть фото") }
    }
    private fun alert(message: String) { page("Terra").addView(text(message,18)) }
    override fun onLocationChanged(l: Location) { if(System.currentTimeMillis()-l.time !in -5000..30000) return; if(l.time<(store.position?.t ?: 0)) return; store.position=Point(l.latitude,l.longitude,l.time,l.accuracy.toDouble()); if(l.hasSpeed()) store.liveSpeed.add(l.speed.toDouble(),if(l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond.toDouble() else 2.5,l.time,100.0); if(store.active?.pausedAt!=null || (store.active==null && store.pending==null)) store.message="GPS ±${l.accuracy.toInt()} м"; refresh() }
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { store.message="Нет GPS. Включи геолокацию в настройках."; refresh() }
    @Deprecated("Required on Android 8–10")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onStart() { super.onStart(); if(yandexInitialized) com.yandex.mapkit.MapKitFactory.getInstance().onStart(); yandex?.start(); yandexRoutes.values.forEach { it.start() }; mapView.onStart(); routeMaps.values.forEach { it.onStart() } }
    override fun onResume() { super.onResume(); mapView.onResume(); routeMaps.values.forEach { it.onResume() }; if(!gate) watch(); handler.post(tick) }
    override fun onPause() { videos.values.forEach { it.pause() }; handler.removeCallbacks(tick); location.removeUpdates(this); routeMaps.values.forEach { it.onPause() }; mapView.onPause(); super.onPause() }
    override fun onStop() { yandex?.stop(); yandexRoutes.values.forEach { it.stop() }; if(yandexInitialized) com.yandex.mapkit.MapKitFactory.getInstance().onStop(); routeMaps.values.forEach { it.onStop() }; mapView.onStop(); super.onStop() }
    override fun onDestroy() { yandexRoutes.clear(); videos.values.forEach { it.stopPlayback() }; videos.clear(); handler.removeCallbacksAndMessages(null); store.listeners.remove(refreshListener); routeMaps.values.forEach { it.onDestroy() }; routeMaps.clear(); mapView.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    private inner class FogView(context: Context): View(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private val placeIcon=annotationBitmap(false)
        init { isClickable=false; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO; setLayerType(LAYER_TYPE_SOFTWARE,null) }
        override fun onTouchEvent(event: android.view.MotionEvent)=false
        override fun onDraw(canvas: Canvas) {
            if(map==null && yandex==null) return; val save=canvas.saveLayer(0f,0f,width.toFloat(),height.toFloat(),null)
            if(showFog) canvas.drawColor(if(dark) Color.argb(230,23,25,32) else Color.argb(218,182,184,189))
            paint.xfermode=PorterDuffXfermode(PorterDuff.Mode.CLEAR); paint.style=Paint.Style.FILL; paint.strokeCap=Paint.Cap.ROUND
            yandex?.let { canvas.drawRect(0f,(it.height-dp(50)).toFloat(),dp(150).toFloat(),it.height.toFloat(),paint) }
            val sessions=store.sessions + listOfNotNull(store.active)
            for(s in sessions) { if(layer!=null && s.mode.category!=layer) continue
                for((i,p) in s.points.withIndex()) {
                    if(p.excludeDiscovery) continue
                    val xy=screenPoint(LatLng(p.lat,p.lng)); val radius=(Discovery.radius/metersPerPixel(p.lat,p.lng)).toFloat()
                    canvas.drawCircle(xy.x,xy.y,radius,paint)
                    if(i>0 && !s.points[i-1].excludeDiscovery && s.points[i-1].connects(p) && abs(p.lng-s.points[i-1].lng)<180) { val a=screenPoint(LatLng(s.points[i-1].lat,s.points[i-1].lng)); paint.strokeWidth=radius*2; canvas.drawLine(a.x,a.y,xy.x,xy.y,paint) }
                }
            }
            paint.xfermode=null; canvas.restoreToCount(save)
            val places=personal().optJSONArray("places") ?: org.json.JSONArray(); paint.color=accent
            if(showPlaces) for(i in 0 until places.length()) { val place=places.getJSONObject(i); val xy=screenPoint(LatLng(place.getDouble("lat"),place.getDouble("lng"))); canvas.drawBitmap(placeIcon,xy.x-dp(16),xy.y-dp(38),paint) }
            nearbyYandex.forEach { target -> val xy=screenPoint(target); canvas.drawBitmap(annotationBitmap(true),xy.x-dp(16),xy.y-dp(38),paint) }
            store.position?.let { val p=screenPoint(LatLng(it.lat,it.lng)); paint.color=Color.WHITE; canvas.drawCircle(p.x,p.y,dp(9).toFloat(),paint); paint.color=accent; canvas.drawCircle(p.x,p.y,dp(6).toFloat(),paint) }
        }
    }
}
