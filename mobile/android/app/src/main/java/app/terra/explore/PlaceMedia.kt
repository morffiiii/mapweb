package app.terra.explore
import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import org.json.JSONObject
import java.io.File
object PlaceMedia {
    fun import(context: Context,uri: Uri): JSONObject {
        val video=context.contentResolver.getType(uri)?.startsWith("video/")==true
        val dir=File(context.filesDir,"media").also { it.mkdirs() }
        val target=File(dir,java.util.UUID.randomUUID().toString()+if(video) ".mp4" else ".jpg")
        try {
            if(video) context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { out -> val buffer=ByteArray(65536); var total=0L; while(true) { val n=input.read(buffer); if(n<0) break; total+=n; require(total<=100_000_000) { "Видео должно быть меньше 100 МБ" }; out.write(buffer,0,n) } } }
            else {
                val options=BitmapFactory.Options().also { it.inJustDecodeBounds=true }; context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) }; require(options.outWidth>0 && options.outHeight>0)
                options.inSampleSize=maxOf(1,maxOf(options.outWidth,options.outHeight)/1600); options.inJustDecodeBounds=false
                val bitmap=context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) } ?: error("Не удалось прочитать фото")
                val orientation=runCatching { context.contentResolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1) } }.getOrDefault(1)
                val matrix=Matrix(); when(orientation) { 2 -> matrix.setScale(-1f,1f); 3 -> matrix.setRotate(180f); 4 -> matrix.setScale(1f,-1f); 5 -> { matrix.setRotate(90f); matrix.postScale(-1f,1f) }; 6 -> matrix.setRotate(90f); 7 -> { matrix.setRotate(270f); matrix.postScale(-1f,1f) }; 8 -> matrix.setRotate(270f) }
                val result=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true); target.outputStream().use { check(result.compress(Bitmap.CompressFormat.JPEG,85,it)) }; if(result!==bitmap) result.recycle(); bitmap.recycle()
            }
            return JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("file","media/"+target.name).put("video",video)
        } catch(e: Exception) { target.delete(); throw e }
    }
    fun items(place: JSONObject): List<JSONObject> {
        val array=place.optJSONArray("media"); if(array!=null) return (0 until array.length()).map { array.getJSONObject(it) }
        val photo=place.optString("photo"); return if(photo.isBlank() || photo=="null") emptyList() else listOf(JSONObject().put("id","legacy").put("file",photo).put("video",false))
    }
    fun title(place: JSONObject)=place.optString("title").ifBlank { place.optString("note").take(70).ifBlank { "Моё место" } }
}
