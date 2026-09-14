package app.terra.explore

import android.content.Context
import android.graphics.*
import android.view.View
import android.widget.FrameLayout
import com.yandex.mapkit.Animation
import com.yandex.mapkit.ScreenPoint
import com.yandex.mapkit.geometry.Point as YPoint
import com.yandex.mapkit.map.*
import com.yandex.mapkit.mapview.MapView
import org.maplibre.android.geometry.LatLng
import kotlin.math.*

class YandexSurface(context: Context): FrameLayout(context) {
    val native = MapView(context)
    var onLongPress: ((LatLng) -> Unit)? = null
    var onTap: ((LatLng) -> Unit)? = null
    var onCamera: (() -> Unit)? = null
    var onGesture: (() -> Unit)? = null
    private var pendingMove: Pair<LatLng,Double>? = null
    private var loaded=false
    private val loadedListener=MapLoadedListener { loaded=true; applyPendingMove() }
    var route: Session? = null
    var routeCount = Int.MAX_VALUE
        set(value) { field=value; ink.invalidate() }
    private val cameraListener = CameraListener { _, _, reason, _ ->
        ink.invalidate(); onCamera?.invoke()
        if(reason==CameraUpdateReason.GESTURES) { pendingMove=null; onGesture?.invoke() }
    }
    private val inputListener = object: InputListener {
        override fun onMapTap(map: com.yandex.mapkit.map.Map, point: YPoint) { onTap?.invoke(LatLng(point.latitude,point.longitude)) }
        override fun onMapLongTap(map: com.yandex.mapkit.map.Map, point: YPoint) { onLongPress?.invoke(LatLng(point.latitude,point.longitude)) }
    }
    private val ink=object: View(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val session=route ?: return
            paint.strokeWidth=8*resources.displayMetrics.density; paint.strokeCap=Paint.Cap.ROUND; paint.color=session.mode.color
            session.points.take(routeCount).forEachIndexed { i,p ->
                if(i>0) { val previous=session.points[i-1]
                    if(previous.connects(p) && abs(previous.lng-p.lng)<180) { val a=screen(LatLng(previous.lat,previous.lng)); val b=screen(LatLng(p.lat,p.lng)); canvas.drawLine(a.x,a.y,b.x,b.y,paint) }
                }
            }
            listOfNotNull(session.points.firstOrNull(),session.points.take(routeCount).lastOrNull()).forEach { p ->
                val xy=screen(LatLng(p.lat,p.lng)); paint.color=Color.WHITE; canvas.drawCircle(xy.x,xy.y,9*resources.displayMetrics.density,paint)
                paint.color=session.mode.color; canvas.drawCircle(xy.x,xy.y,6*resources.displayMetrics.density,paint)
            }
        }
    }
    init {
        addView(native,LayoutParams(-1,-1)); addView(ink,LayoutParams(-1,-1))
        native.mapWindow.map.addCameraListener(java.lang.ref.WeakReference(cameraListener))
        native.mapWindow.map.addInputListener(java.lang.ref.WeakReference<InputListener>(inputListener))
        native.mapWindow.map.setMapLoadedListener(java.lang.ref.WeakReference(loadedListener))
        native.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> applyPendingMove() }
        native.mapWindow.map.isTiltGesturesEnabled=false
        native.mapWindow.map.logo.setAlignment(com.yandex.mapkit.logo.Alignment(com.yandex.mapkit.logo.HorizontalAlignment.LEFT,com.yandex.mapkit.logo.VerticalAlignment.BOTTOM))
    }
    fun start() { native.onStart() }
    fun stop() { native.onStop() }
    fun night(dark: Boolean) { native.mapWindow.map.isNightModeEnabled=dark }
    fun move(point: LatLng, zoom: Double=15.5) {
        pendingMove=point to zoom
        applyPendingMove()
    }
    private fun applyPendingMove() {
        val (point,zoom)=pendingMove ?: return
        if(native.width<=0 || native.height<=0 || !isAttachedToWindow) return
        if(loaded) pendingMove=null
        native.mapWindow.map.move(CameraPosition(YPoint(point.latitude,point.longitude),zoom.toFloat(),0f,0f),Animation(Animation.Type.SMOOTH,0.35f),null)
    }
    fun screen(point: LatLng): PointF {
        val p=native.mapWindow.worldToScreen(YPoint(point.latitude,point.longitude)) ?: return PointF(-100000f,-100000f)
        return PointF(p.x,p.y)
    }
    fun metersPerPixel(latitude: Double, longitude: Double): Double {
        val a=screen(LatLng(latitude,longitude)); val b=screen(LatLng(latitude+0.0001,longitude))
        return 11.1195/hypot((b.x-a.x).toDouble(),(b.y-a.y).toDouble()).coerceAtLeast(0.0001)
    }
    fun fit(session: Session) {
        val points=session.points
        if(points.isEmpty()) return
        val lat=(points.minOf { it.lat }+points.maxOf { it.lat })/2
        val lng=(points.minOf { it.lng }+points.maxOf { it.lng })/2
        val span=max((points.maxOf { it.lat }-points.minOf { it.lat })*111195,(points.maxOf { it.lng }-points.minOf { it.lng })*111195*cos(Math.toRadians(lat)))
        val logicalWidth=width/resources.displayMetrics.density
        val zoom=log2(156543.03392*cos(Math.toRadians(lat))*logicalWidth/max(500.0,span*1.4)).coerceIn(2.0,19.0)
        move(LatLng(lat,lng),zoom)
    }
}
