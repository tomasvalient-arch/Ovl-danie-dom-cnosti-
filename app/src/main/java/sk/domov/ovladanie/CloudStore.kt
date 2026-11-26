package sk.domov.ovladanie

import android.content.Context
import org.json.JSONObject

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
            // mazať len naozaj nepoužiteľné údaje (kľúč v Keystore sa stratil, poškodený obsah) – údaje treba zadať znova;
            // pri dočasnej chybe Keystore údaje ostanú a pri ďalšom načítaní sa skúsi znova
            if (SecretBox.isPermanent(e)) clear() else android.util.Log.w("Domov", "Tuya cloud: údaje sa teraz nedajú načítať", e)
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

    private fun encrypt(text: String): String = SecretBox.encrypt(SecretBox.CLOUD, text)

    private fun decrypt(stored: String): String = SecretBox.decrypt(SecretBox.CLOUD, stored)
}
