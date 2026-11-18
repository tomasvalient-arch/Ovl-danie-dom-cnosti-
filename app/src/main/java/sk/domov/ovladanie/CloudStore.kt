package sk.domov.ovladanie

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Prístupové údaje k Tuya cloudu, zašifrované kľúčom v Android Keystore (kľúč sa nedá z telefónu vytiahnuť).
 * Ukladajú sa oddelene od zariadení a nikdy nie sú súčasťou exportu.
 */
class CloudStore(context: Context) {
    private val prefs = context.getSharedPreferences("domov_cloud", Context.MODE_PRIVATE)

    fun load(): CloudConfig? {
        val enc = prefs.getString("config", null) ?: return null
        return try {
            val o = JSONObject(decrypt(enc))
            CloudConfig(o.getString("id"), o.getString("secret"), DataCenter.of(o.optString("dc")))
        } catch (e: Exception) {
            // kľúč v Keystore sa stratil (napr. po obnove telefónu) – údaje treba zadať znova
            clear()
            null
        }
    }

    fun save(config: CloudConfig) {
        val json = JSONObject()
            .put("id", config.accessId.trim())
            .put("secret", config.accessSecret.trim())
            .put("dc", config.dataCenter.code)
        prefs.edit().putString("config", encrypt(json.toString())).apply()
    }

    fun clear() {
        prefs.edit().remove("config").apply()
    }

    /** Uloženie/zmazanie hneď na disk (obnovenie zo zálohy). null = zmazať. */
    fun saveNow(config: CloudConfig?): Boolean {
        if (config == null) return prefs.edit().remove("config").commit()
        val json = JSONObject().put("id", config.accessId.trim()).put("secret", config.accessSecret.trim()).put("dc", config.dataCenter.code)
        return prefs.edit().putString("config", encrypt(json.toString())).commit()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(text: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (iv, ct) = stored.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return c.doFinal(ct).toString(Charsets.UTF_8)
    }

    private companion object {
        const val ALIAS = "domov_tuya_cloud"
    }
}
