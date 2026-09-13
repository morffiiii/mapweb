package app.terra.explore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

class LocalAccount(context: Context) {
    private val file=AtomicFile(File(context.filesDir,"local-account.enc"))
    fun exists()=file.baseFile.exists()
    private fun key(create: Boolean): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if(store.containsAlias("TerraLocalAccount")) return store.getKey("TerraLocalAccount",null) as SecretKey
        check(create) { "Ключ локального профиля недоступен" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").also {
            it.init(KeyGenParameterSpec.Builder("TerraLocalAccount",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun register(email: String,password: String) {
        check(!exists()) { "На этом телефоне уже есть профиль" }
        val salt=ByteArray(16).also { SecureRandom().nextBytes(it) }
        val record=JSONObject().put("email",email.lowercase()).put("salt",Base64.encodeToString(salt,Base64.NO_WRAP)).put("hash",Base64.encodeToString(derive(password,salt),Base64.NO_WRAP))
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key(true)); val data=cipher.iv+cipher.doFinal(record.toString().toByteArray(Charsets.UTF_8))
        val out=file.startWrite(); try { out.write(data); file.finishWrite(out) } catch(e: Exception) { file.failWrite(out); throw e }
    }
    fun login(email: String,password: String): Boolean = try {
        val data=file.readFully(); require(data.size>12); val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,data.copyOfRange(0,12)))
        val record=JSONObject(String(cipher.doFinal(data.copyOfRange(12,data.size)),Charsets.UTF_8))
        record.getString("email")==email.lowercase() && MessageDigest.isEqual(Base64.decode(record.getString("hash"),Base64.NO_WRAP),derive(password,Base64.decode(record.getString("salt"),Base64.NO_WRAP)))
    } catch(e: Exception) { false }
    companion object {
        fun derive(password: String,salt: ByteArray): ByteArray { val spec=PBEKeySpec(password.toCharArray(),salt,210000,256); return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() } }
    }
}
