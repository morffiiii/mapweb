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
    private lateinit var fog: FogView
    private lateinit var store: Store
    private lateinit var location: LocationManager
    private lateinit var status: TextView
    private lateinit var metric: TextView
    private lateinit var start: Button
    private lateinit var finish: Button
    private lateinit var modes: Spinner
    private lateinit var root: FrameLayout
    private val pages=mutableListOf<ScrollView>()
    private var following=false
    private var wasRecording=false
    private var lastCameraPoint: Point?=null
    private var replaySessions: List<Session>?=null
    private var replayTask: Runnable?=null
    private var selectedPhoto: String?=null
    private var photoPreview: ImageView?=null
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
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent","Terra/2.0 (https://github.com/morffiiii/mapweb)").build()) }.build())
        store=Store.get(this); location=getSystemService(LocationManager::class.java)
        dark=when(getPreferences(0).getInt("theme",0)) { 1 -> false; 2 -> true; else -> resources.configuration.uiMode and 0x30 == 0x20 }
        window.statusBarColor=if(dark) Color.rgb(24,26,31) else Color.WHITE
        window.navigationBarColor=window.statusBarColor
        root=FrameLayout(this); root.setBackgroundColor(window.statusBarColor); root.fitsSystemWindows=true; setContentView(root)
        mapView=MapView(this); mapView.onCreate(state); root.addView(mapView,FrameLayout.LayoutParams(-1,-1))
        fog=FogView(this); root.addView(fog,FrameLayout.LayoutParams(-1,-1))
        val top=LinearLayout(this); top.gravity=Gravity.CENTER_VERTICAL
        val title=text("TERRA ↗",26); title.typeface=Typeface.DEFAULT_BOLD; top.addView(title,LinearLayout.LayoutParams(0,-2,1f)); top.addView(navIcon("Слои", "layers") { layers() }); panel(root,top,true)
        val bottom=LinearLayout(this); bottom.orientation=LinearLayout.VERTICAL
        status=text("",12); metric=text("",25); metric.typeface=Typeface.MONOSPACE
        bottom.addView(status); bottom.addView(metric)
        modes=Spinner(this); modes.adapter=object: ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,Mode.visible.map { it.title }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = super.getView(position,convertView,parent).also { (it as TextView).setTextColor(if(dark) Color.WHITE else Color.BLACK); it.setPadding(dp(8),dp(10),dp(8),dp(10)) }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        bottom.addView(modes)
        val actions=LinearLayout(this); start=button("Начать прогулку ↗") { toggle() }; start.setTextColor(Color.WHITE); start.background=shape(accent)
        finish=button("Завершить") { val s=store.active; command("stop"); if(s!=null) handler.postDelayed({ replay(s) },250) }; actions.addView(start,LinearLayout.LayoutParams(0,dp(54),1f)); actions.addView(finish,LinearLayout.LayoutParams(0,dp(54),1f)); bottom.addView(actions)
        val nav=LinearLayout(this)
        nav.addView(navIcon("История", "history") { history() },LinearLayout.LayoutParams(0,dp(54),1f)); nav.addView(navIcon("Где я", "location") { following=true; locate() },LinearLayout.LayoutParams(0,dp(54),1f)); nav.addView(navIcon("Настройки", "settings") { settings() },LinearLayout.LayoutParams(0,dp(54),1f)); nav.addView(navIcon("Профиль", "profile") { profile() },LinearLayout.LayoutParams(0,dp(54),1f)); bottom.addView(nav)
        val attribution=text("© OpenStreetMap contributors · MapLibre",10)
        attribution.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://www.openstreetmap.org/copyright"))) }; bottom.addView(attribution); panel(root,bottom,false)
        mapView.getMapAsync { m -> map=m; m.uiSettings.isCompassEnabled=true; m.addOnCameraMoveListener { fog.invalidate() }; m.addOnCameraMoveStartedListener { reason -> if(reason==1) following=false }; m.addOnMapLongClickListener { coordinate -> markPlace(coordinate); true }; loadStyle(); refresh() }
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
            location.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0f,this)
            if(location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) location.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,2000,0f,this)
            if(!location.isProviderEnabled(LocationManager.GPS_PROVIDER)) { store.message="Включи геолокацию в настройках телефона"; refresh() }
        } catch(e: Exception) { store.message="Не удалось включить GPS"; refresh() }
    }
    private fun locate() { if(!permitted()) { watch(); return }; store.position?.let { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat,it.lng),15.5)) } ?: run { store.message="Ждём сигнал GPS…"; watch(); refresh() } }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==20) { if(permitted()) watch() else { store.message="Нужна точная геопозиция. Разреши её в настройках Terra."; refresh(); AlertDialog.Builder(this).setMessage(store.message).setPositiveButton("Настройки") { _,_ -> startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName"))) }.setNegativeButton("Позже",null).show() } }
    }
    private fun toggle() {
        if(store.pending!=null) { command("pause"); return }
        if(store.active!=null && store.active?.pausedAt==null) { command("pause"); return }
        if(!permitted()) { watch(); return }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),21)
        val intent=Intent(this,TrackingService::class.java).putExtra("mode",Mode.visible[modes.selectedItemPosition].name)
        try { startForegroundService(intent) } catch(e: Exception) { alert("Не удалось начать запись: ${e.localizedMessage}") }
    }
    private fun command(action: String) { startService(Intent(this,TrackingService::class.java).setAction(action)) }
    private fun refresh() {
        if(!::status.isInitialized) return
        status.text=store.message
        val s=store.active
        val recording=s!=null && s.pausedAt==null
        if(recording && !wasRecording) following=true
        wasRecording=recording
        if(recording && following && replaySessions==null) store.position?.let { p -> if(lastCameraPoint==null || lastCameraPoint!!.distance(p)>5) { lastCameraPoint=p; map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat,p.lng),15.5),700) } }
        metric.text=if(s==null) "Твой мир. Твой путь." else "%.2f км  %02d:%02d".format(s.distance()/1000,s.duration()/60,s.duration()%60)
        start.text=if(store.pending!=null) "Отменить ожидание" else if(s==null) "Начать прогулку ↗" else if(s.pausedAt==null) "Пауза" else "Продолжить"
        start.isEnabled=!store.blocked; finish.visibility=if(s==null) View.GONE else View.VISIBLE; modes.isEnabled=s==null && store.pending==null
        if(s!=null) modes.setSelection(Mode.visible.indexOf(s.mode.category))
        if(!centered && store.position!=null && map!=null) { centered=true; locate() }; fog.invalidate()
    }
    private fun navIcon(label: String, kind: String, action: () -> Unit): ImageButton = ImageButton(this).also { b ->
        b.contentDescription=label; b.setBackgroundColor(Color.TRANSPARENT); b.setPadding(dp(12),dp(12),dp(12),dp(12))
        b.setImageDrawable(object: android.graphics.drawable.Drawable() {
            val p=Paint(Paint.ANTI_ALIAS_FLAG).also { it.color=accent; it.strokeWidth=1.8f; it.style=Paint.Style.STROKE; it.strokeCap=Paint.Cap.ROUND }
            override fun draw(c: Canvas) { c.save(); c.translate(bounds.left.toFloat(),bounds.top.toFloat()); c.scale(bounds.width()/24f,bounds.height()/24f)
                when(kind) {
                    "history" -> { c.drawCircle(12f,12f,8f,p); c.drawLine(12f,7f,12f,12f,p); c.drawLine(12f,12f,16f,14f,p) }
                    "location" -> { val path=Path(); path.moveTo(20f,4f); path.lineTo(14f,21f); path.lineTo(10f,14f); path.lineTo(3f,10f); path.close(); c.drawPath(path,p) }
                    "profile" -> { c.drawCircle(12f,8f,4f,p); c.drawArc(4f,13f,20f,27f,180f,180f,false,p) }
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
    private fun closePage(column: LinearLayout) { val scroll=column.parent as? ScrollView ?: return; root.removeView(scroll); pages.remove(scroll) }
    private fun closePages() { pages.forEach { root.removeView(it) }; pages.clear() }
    @Deprecated("Back navigation compatibility") override fun onBackPressed() { if(pages.isNotEmpty()) { root.removeView(pages.removeAt(pages.lastIndex)) } else super.onBackPressed() }
    private fun row(page: LinearLayout,title: String, detail: String="", color: Int=accent, action: () -> Unit) {
        val b=button(title + if(detail.isBlank()) "" else "\n$detail",action); b.gravity=Gravity.START or Gravity.CENTER_VERTICAL; b.textSize=16f; b.setTextColor(color); b.setPadding(dp(18),dp(18),dp(18),dp(18)); b.background=shape(if(dark) Color.rgb(32,34,42) else Color.WHITE)
        val params=LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(14); page.addView(b,params)
    }
    private fun layers() {
        val p=page("Слои"); p.addView(text("В общем слое у каждого способа передвижения свой цвет.",15))
        row(p,"Все способы",if(layer==null) "Выбрано" else "") { layer=null; fog.invalidate(); closePage(p) }
        Mode.visible.forEach { m -> row(p,m.title,if(layer==m) "Выбран" else "",m.color) { layer=m; fog.invalidate(); closePage(p) } }
    }
    private fun history() {
        val p=page("История")
        if(store.sessions.isEmpty()) p.addView(text("Первый маршрут ещё впереди. Начни прогулку — здесь останется её история.",21))
        store.sessions.reversed().forEach { s -> row(p,"${s.mode.title} · %.2f км".format(s.distance()/1000),"${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(s.startedAt))}\n${s.duration()/60} мин · Смотреть повтор",s.mode.color) { closePages(); replay(s) } }
    }
    private fun settings() {
        val p=page("Настройки"); p.addView(text("Внешний вид",18))
        listOf("Как на телефоне","Светлая","Тёмная").forEachIndexed { i,name -> row(p,name,if(getPreferences(0).getInt("theme",0)==i) "Выбрана" else "") { getPreferences(0).edit().putInt("theme",i).apply(); recreate() } }
        row(p,"Геопозиция","Разрешения и работа в фоне") { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName"))) }
        p.addView(text("Запись включается сразу. Пока GPS уточняется, неточные координаты не сохраняются. Не останавливай Terra принудительно во время прогулки.\n\nКарта: OpenStreetMap. Яндекс Карты требуют ключа MapKit.\nTerra · 2.1\nМаршруты и места хранятся на этом телефоне.",15))
    }
    private fun personal(): org.json.JSONObject = org.json.JSONObject(getPreferences(0).getString("personal","{}") ?: "{}")
    private fun savePersonal(p: org.json.JSONObject) { check(getPreferences(0).edit().putString("personal",p.toString()).commit()) { "Не удалось сохранить" } }
    private fun profile() {
        val p=page("Твой профиль"); val data=personal(); val name=EditText(this); name.setText(data.optString("name","Исследователь")); name.setTextColor(if(dark) Color.WHITE else Color.BLACK); name.textSize=26f; name.setSingleLine(); p.addView(name)
        row(p,"Сохранить имя") { try { val next=personal(); next.put("name",name.text.toString().take(60)); savePersonal(next); name.clearFocus() } catch(e: Exception) { alert(e.localizedMessage ?: "Ошибка сохранения") } }
        val sessions=store.sessions.toList(); p.addView(text("%.2f км".format(sessions.sumOf { it.distance() }/1000),36))
        p.addView(text("${sessions.size} маршрутов · ${sessions.count { it.startedAt>=System.currentTimeMillis()-7*86400000L }} за 7 дней\n${sessions.sumOf { it.duration() }/60} минут в движении",16))
        val streak=WalkingStreak.status(WalkingStreak.days(sessions),restores())
        p.addView(text("🔥 ${streak.count} дней подряд",28)); p.addView(text((if(streak.walkedToday) "Сегодня прогулка засчитана." else "Прогулка от 5 минут с движением продолжит серию.")+"\nВосстановлений в этом месяце: ${streak.remaining} из 3.",16))
        if(streak.canRestore) row(p,"Восстановить серию","Закрыть вчерашний пропуск · 1 восстановление") {
            val current=WalkingStreak.status(WalkingStreak.days(store.sessions),restores())
            if(current.canRestore) try { val data=personal(); val a=data.optJSONArray("restores") ?: org.json.JSONArray(); a.put(org.json.JSONObject().put("day",current.yesterday).put("usedOn",current.today)); data.put("restores",a); savePersonal(data); closePage(p); profile() } catch(e: Exception) { alert("Не удалось восстановить серию") }
        }
        val area=text("Считаем открытую площадь…",22); p.addView(area)
        val territory=text("Моя территория — круг 500 м вокруг выбранного места, а не административная граница района.",15); p.addView(territory)
        Thread { val cells=Discovery.cells(sessions); val t=data.optJSONObject("territory"); val coverage=if(t!=null) territoryCoverage(t,cells) else null
            runOnUiThread { area.text="≈ %.3f км² открыто".format(cells.size*0.0004); if(coverage!=null) territory.text="Моя территория · %.1f%%\nКруг 500 м вокруг выбранной точки".format(coverage) }
        }.start()
        row(p,"Исследовать этот квартал","Территория вокруг меня · 500 м") { val pos=store.position; if(pos==null) alert("Сначала дождись координат GPS") else try { val next=personal(); next.put("territory",org.json.JSONObject().put("lat",pos.lat).put("lng",pos.lng)); savePersonal(next); closePage(p); profile() } catch(e: Exception) { alert("Не удалось сохранить территорию") } }
        Mode.visible.forEach { m -> val s=sessions.filter { it.mode.category==m }; row(p,m.title,"%.2f км · %d маршрутов".format(s.sumOf { it.distance() }/1000,s.size),m.color) { layer=m; fog.invalidate(); closePages() } }
        row(p,"Неизведанное рядом","Найти неоткрытые участки") { nearby() }
        row(p,"Мои места","Удерживай карту, чтобы добавить заметку или фото") { places() }
        row(p,"Мои цели","Расстояние, маршруты, открытая площадь") { goals() }
        row(p,"Достижения","Награды за настоящие открытия") { achievements() }
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
    private fun achievements() {
        val p=page("Достижения"); p.addView(text("Открываются по твоим настоящим маршрутам.",16)); val sessions=store.sessions
        val days=sessions.filter { it.points.isNotEmpty() }.map { java.time.Instant.ofEpochMilli(it.startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay() }.toSet().sorted()
        var longest=0; var streak=0; var last: Long?=null; for(day in days) { streak=if(last!=null && day-last!!==1L) streak+1 else 1; longest=max(longest,streak); last=day }
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
                found.forEach { target -> row(p,"Неоткрытый участок","${pos.distance(target).toInt()} м от тебя") { following=false; map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(target.lat,target.lng),16.0)); map?.addMarker(org.maplibre.android.annotations.MarkerOptions().position(LatLng(target.lat,target.lng)).title("Неоткрытый участок")); closePages() } }
            }
        }.start()
    }
    private fun replay(s: Session) {
        if(s.points.isEmpty()) return
        replayTask?.let { handler.removeCallbacks(it) }; following=false; layer=null
        val base=store.sessions.filter { it.id!=s.id }; var frame=0
        val bounds=org.maplibre.android.geometry.LatLngBounds.Builder(); s.points.forEach { bounds.include(LatLng(it.lat,it.lng)) }
        if(s.points.size>1) map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(),dp(60),dp(140),dp(60),dp(340))) else map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(s.points[0].lat,s.points[0].lng),16.0))
        val task=object: Runnable { override fun run() { frame++; replaySessions=base+s.copy(points=s.points.take(max(1,s.points.size*frame/75)).toMutableList()); fog.invalidate(); status.text="Повтор маршрута · ${min(100,frame*100/75)}%"; if(frame<75) handler.postDelayed(this,80) else { replaySessions=null; replayTask=null; refresh() } } }; replayTask=task; handler.post(task)
    }
    private fun markPlace(coordinate: LatLng) {
        val p=page("Новое место"); p.addView(text("Что хочется запомнить здесь?",18)); val note=EditText(this); note.setTextColor(if(dark) Color.WHITE else Color.BLACK); note.minLines=3; note.hint="Заметка"; p.addView(note)
        selectedPhoto=null; val preview=ImageView(this); preview.adjustViewBounds=true; preview.visibility=View.GONE; p.addView(preview,LinearLayout.LayoutParams(-1,dp(180))); photoPreview=preview
        row(p,"Добавить фото") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),40) }
        row(p,"Сохранить место") { try {
            val data=personal(); val places=data.optJSONArray("places") ?: org.json.JSONArray(); val place=org.json.JSONObject().put("lat",coordinate.latitude).put("lng",coordinate.longitude).put("note",note.text.toString().take(2000)).put("photo",selectedPhoto)
            places.put(place); data.put("places",places); savePersonal(data); map?.addMarker(org.maplibre.android.annotations.MarkerOptions().position(coordinate).title(note.text.toString())); closePage(p)
        } catch(e: Exception) { alert("Не удалось сохранить место") } }
    }
    private fun places() {
        val p=page("Мои места"); val places=personal().optJSONArray("places") ?: org.json.JSONArray()
        if(places.length()==0) p.addView(text("Удерживай карту, чтобы добавить заметку или фото.",20))
        for(i in 0 until places.length()) { val place=places.getJSONObject(i)
            val path=place.optString("photo"); if(path.isNotEmpty()) { val image=ImageView(this); image.setImageBitmap(BitmapFactory.decodeFile(java.io.File(filesDir,path).path)); image.scaleType=ImageView.ScaleType.CENTER_CROP; p.addView(image,LinearLayout.LayoutParams(-1,dp(180))) }
            row(p,place.optString("note").ifBlank { "Моё место" },"Показать на карте") { following=false; map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(place.getDouble("lat"),place.getDouble("lng")),16.0)); closePages() }
        }
    }
    override fun onActivityResult(request: Int,result: Int,data: Intent?) {
        super.onActivityResult(request,result,data); if(request!=40 || result!=RESULT_OK) return; val uri=data?.data ?: return
        try {
            val options=BitmapFactory.Options().also { it.inJustDecodeBounds=true }; contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) }
            require(options.outWidth>0 && options.outHeight>0); options.inSampleSize=max(1,max(options.outWidth,options.outHeight)/1400); options.inJustDecodeBounds=false
            val bitmap=contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) } ?: error("Фото недоступно")
            val path="place-${java.util.UUID.randomUUID()}.jpg"; java.io.File(filesDir,path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,80,it) }; selectedPhoto=path; photoPreview?.setImageBitmap(bitmap); photoPreview?.visibility=View.VISIBLE
        } catch(e: Exception) { alert("Не удалось открыть фото") }
    }
    private fun alert(message: String) { page("Terra").addView(text(message,18)) }
    override fun onLocationChanged(l: Location) { if(System.currentTimeMillis()-l.time !in -5000..30000) return; store.position=Point(l.latitude,l.longitude,l.time,l.accuracy.toDouble()); if(store.active?.pausedAt!=null || (store.active==null && store.pending==null)) store.message="GPS ±${l.accuracy.toInt()} м"; refresh() }
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { store.message="Нет GPS. Включи геолокацию в настройках."; refresh() }
    @Deprecated("Required on Android 8–10")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume(); watch(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); location.removeUpdates(this); mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); store.listeners.remove(refreshListener); mapView.onDestroy(); super.onDestroy() }
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
            val sessions=replaySessions ?: (store.sessions + listOfNotNull(store.active))
            for(s in sessions) { if(layer!=null && s.mode.category!=layer) continue
                for((i,p) in s.points.withIndex()) {
                    val xy=m.projection.toScreenLocation(LatLng(p.lat,p.lng)); val radius=(35/m.projection.getMetersPerPixelAtLatitude(p.lat)).toFloat()
                    canvas.drawCircle(xy.x,xy.y,radius,paint)
                    if(i>0 && s.points[i-1].connects(p) && abs(p.lng-s.points[i-1].lng)<180) { val a=m.projection.toScreenLocation(LatLng(s.points[i-1].lat,s.points[i-1].lng)); paint.strokeWidth=radius*2; canvas.drawLine(a.x,a.y,xy.x,xy.y,paint) }
                }
            }
            paint.xfermode=null; canvas.restoreToCount(save)
            paint.strokeWidth=dp(3).toFloat()
            for(s in sessions) { if(layer!=null && s.mode.category!=layer) continue; paint.color=s.mode.color
                for((i,p) in s.points.withIndex()) { val b=m.projection.toScreenLocation(LatLng(p.lat,p.lng))
                    if(i>0 && s.points[i-1].connects(p) && abs(s.points[i-1].lng-p.lng)<180) { val a=m.projection.toScreenLocation(LatLng(s.points[i-1].lat,s.points[i-1].lng)); canvas.drawLine(a.x,a.y,b.x,b.y,paint) } else canvas.drawCircle(b.x,b.y,dp(2).toFloat(),paint)
                }
            }
            val places=personal().optJSONArray("places") ?: org.json.JSONArray(); paint.color=accent
            for(i in 0 until places.length()) { val place=places.getJSONObject(i); val xy=m.projection.toScreenLocation(LatLng(place.getDouble("lat"),place.getDouble("lng"))); canvas.drawCircle(xy.x,xy.y,dp(5).toFloat(),paint) }
            store.position?.let { val p=m.projection.toScreenLocation(LatLng(it.lat,it.lng)); paint.color=Color.WHITE; canvas.drawCircle(p.x,p.y,dp(9).toFloat(),paint); paint.color=accent; canvas.drawCircle(p.x,p.y,dp(6).toFloat(),paint) }
        }
    }
}
