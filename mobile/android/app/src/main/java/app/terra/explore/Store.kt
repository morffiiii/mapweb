package app.terra.explore

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Store private constructor(context: Context) {
    val sessions = mutableListOf<Session>()
    var active: Session? = null
    var position: Point? = null
    var message = "Определяем местоположение…"
    var blocked = false
    var pending: Mode? = null
    val listeners = mutableSetOf<() -> Unit>()
    private val file = AtomicFile(File(context.filesDir,"terra-state.json"))
    private var durable = "{\"sessions\":[]}"
    init { if(file.baseFile.exists()) try {
        durable = String(file.readFully(),Charsets.UTF_8); restore(durable)
        active?.let { if(it.pausedAt==null) { it.pausedAt = it.points.lastOrNull()?.t ?: it.startedAt; it.breakNext = true; save() } }
    } catch(e: Exception) { blocked = true; message = "Не удалось прочитать сохранение. Файл сохранён для восстановления." } }
    private fun restore(text: String) {
        val root = JSONObject(text); val array = root.getJSONArray("sessions"); require(array.length()<=10000)
        val loaded = (0 until array.length()).map { Session.parse(array.getJSONObject(it)).also { s -> s.validate() } }
        sessions.clear(); sessions.addAll(loaded)
        active = root.optJSONObject("active")?.let { Session.parse(it).also { s -> s.validate(false) } }
    }
    fun save(): Boolean {
        if(blocked) return false
        var stream: java.io.FileOutputStream? = null
        return try {
            val text = JSONObject().put("sessions",JSONArray(sessions.map { it.json() })).put("active",active?.json()).toString()
            stream = file.startWrite(); stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream); durable = text; true
        } catch(e: Exception) { file.failWrite(stream); restore(durable); active?.pausedAt = System.currentTimeMillis(); pending = null; message = "Не удалось сохранить маршрут. Запись остановлена."; notifyChanged(); false }
    }
    fun notifyChanged() { listeners.toList().forEach { it() } }
    fun export(): ByteArray = if(blocked) file.readFully() else JSONObject().put("version",1).put("sessions",JSONArray(sessions.map { it.json() })).toString().toByteArray(Charsets.UTF_8)
    fun import(bytes: ByteArray) {
        require(active==null) { "Сначала заверши маршрут" }; require(!blocked && bytes.size<=25000000)
        val root=JSONObject(String(bytes,Charsets.UTF_8)); require(root.getInt("version")==1)
        val array=root.getJSONArray("sessions"); require(array.length()<=10000)
        val incoming=(0 until array.length()).map { Session.parse(array.getJSONObject(it)).also { s -> s.validate() } }
        require(incoming.sumOf { it.points.size }<=200000 && incoming.map { it.id }.toSet().size==incoming.size)
        val ids=sessions.map { it.id }.toSet(); sessions.addAll(incoming.filter { it.id !in ids }); check(save()); message="Маршруты восстановлены"; notifyChanged()
    }
    companion object { @Volatile private var instance: Store? = null; fun get(context: Context): Store = instance ?: synchronized(this) { instance ?: Store(context.applicationContext).also { instance=it } } }
}
