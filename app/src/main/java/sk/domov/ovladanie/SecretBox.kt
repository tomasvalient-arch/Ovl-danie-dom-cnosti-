package sk.domov.ovladanie

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Spoločné šifrovanie citlivých údajov kľúčom z Android Keystore (AES-256-GCM, kľúč sa nedá z telefónu vytiahnuť).
 * Každé úložisko má vlastný alias kľúča (aliasy ostali rovnaké ako predtým, takže staré údaje sa dajú prečítať).
 *
 * Textový formát: base64(iv) + ":" + base64(šifrový text). Bajtový formát: iv (12 B) + šifrový text.
 */
object SecretBox {
    const val CLOUD = "domov_tuya_cloud"
    const val EVOHOME = "domov_evohome"
    const val PC = "domov_pc_keys"
    const val BACKUP = "domov_backup"
    const val LOCAL_KEYS = "domov_local_keys"

    private fun key(alias: String): SecretKey = synchronized(this) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        gen.generateKey()
    }

    fun seal(alias: String, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(alias))
        return c.iv + c.doFinal(data)
    }

    fun open(alias: String, data: ByteArray): ByteArray {
        if (data.size < 12 + 16) throw CorruptSecretException("príliš krátke dáta")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }

    fun encrypt(alias: String, text: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(alias))
        val ct = c.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun decrypt(alias: String, stored: String): String {
        val parts = stored.split(":", limit = 2)
        if (parts.size != 2) throw CorruptSecretException("chýba oddeľovač")
        val (iv, ct) = try {
            parts.map { Base64.decode(it, Base64.NO_WRAP) }
        } catch (e: IllegalArgumentException) {
            throw CorruptSecretException("neplatné base64", e)
        }
        if (iv.size != 12) throw CorruptSecretException("neplatná dĺžka IV")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(128, iv))
        return c.doFinal(ct).toString(Charsets.UTF_8)
    }

    /**
     * true = uložené údaje sú naozaj nepoužiteľné a môžu sa zmazať: zlý autentifikačný tag (kľúč v Keystore sa stratil
     * alebo dáta niekto zmenil), poškodený formát alebo neplatný JSON vo vnútri.
     * false = dočasná chyba (Keystore práve nedostupný, chyba poskytovateľa…) – údaje sa nesmú mazať.
     */
    fun isPermanent(e: Throwable): Boolean = when (e) {
        is AEADBadTagException, is CorruptSecretException, is JSONException -> true
        else -> e.cause?.let { it !== e && isPermanent(it) } ?: false
    }
}

/** Uložené šifrované dáta majú zlý formát (nie sú to výsledky [SecretBox.encrypt]/[SecretBox.seal]). */
class CorruptSecretException(message: String, cause: Throwable? = null) : Exception(message, cause)
