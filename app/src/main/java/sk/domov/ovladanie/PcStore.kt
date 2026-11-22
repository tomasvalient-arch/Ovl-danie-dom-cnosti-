package sk.domov.ovladanie

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import sk.domov.ovladanie.pc.PcCrypto
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Spárovaný PC. [key] je trvalý kľúč tohto telefónu pre tento PC (ten, čo PC dal pri párovaní) – kto ho má,
 * môže PC ovládať, preto sa na disk ukladá len zašifrovaný kľúčom z Android Keystore a nikdy nie je v zálohe ani exporte.
 */
data class PcEntry(
    val serverId: String,
    val name: String,
    val key: ByteArray,
    val hosts: List<String>,
    val port: Int,
    val macs: List<String>,
    /** false = touchpad, true = priamy dotyk. */
    val direct: Boolean = false,
    /** 0 automaticky, 1 rýchly (menšie rozlíšenie), 2 ostrý (plné rozlíšenie). */
    val profile: Int = 0,
    /** 0 nízka, 1 stredná, 2 vysoká citlivosť myši. */
    val sensitivity: Int = 1,
    val natural: Boolean = true,
    val keepAwake: Boolean = true,
    /** Posledný zvolený monitor (255 = hlavný). */
    val monitor: Int = 255,
)

object PcStore {
    private const val FILE = "domov_pc"
    private const val ALIAS = "domov_pc_keys"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Názov tohto telefónu, ako ho uvidí PC v zozname spárovaných telefónov. */
    fun phoneName(): String {
        val m = Build.MANUFACTURER.orEmpty().trim()
        val d = Build.MODEL.orEmpty().trim()
        val n = if (d.startsWith(m, ignoreCase = true)) d else "$m $d"
        return n.trim().ifBlank { "Telefón" }.take(40)
    }

    /** Stále ID tohto telefónu (16 náhodných bajtov); nie je tajné, PC podľa neho pozná spárovaný telefón. */
    @Synchronized
    fun deviceId(context: Context): ByteArray {
        val p = prefs(context)
        val saved = p.getString("device", null)
        if (saved != null && saved.length == 32) {
            return try { PcCrypto.unhex(saved) } catch (e: Exception) { newDeviceId(p) }
        }
        return newDeviceId(p)
    }

    private fun newDeviceId(p: android.content.SharedPreferences): ByteArray {
        val id = PcCrypto.random(16)
        p.edit().putString("device", PcCrypto.hex(id)).commit()
        return id
    }

    @Synchronized
    fun list(context: Context): List<PcEntry> {
        val enc = prefs(context).getString("pcs", null) ?: return emptyList()
        return try {
            val arr = JSONArray(decrypt(enc))
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            // kľúč v Keystore sa stratil (napr. po obnove telefónu): spárovať treba znova
            prefs(context).edit().remove("pcs").commit()
            emptyList()
        }
    }

    @Synchronized
    fun get(context: Context, serverId: String): PcEntry? = list(context).firstOrNull { it.serverId == serverId }

    /** Pridá PC, alebo nahradí spárovaný PC s rovnakým ID (nové párovanie). */
    @Synchronized
    fun save(context: Context, entry: PcEntry) {
        val all = list(context).toMutableList()
        val i = all.indexOfFirst { it.serverId == entry.serverId }
        if (i >= 0) all[i] = entry else all.add(entry)
        write(context, all)
    }

    @Synchronized
    fun update(context: Context, serverId: String, change: (PcEntry) -> PcEntry): PcEntry? {
        val all = list(context).toMutableList()
        val i = all.indexOfFirst { it.serverId == serverId }
        if (i < 0) return null
        val next = change(all[i])
        all[i] = next
        write(context, all)
        return next
    }

    @Synchronized
    fun remove(context: Context, serverId: String) {
        write(context, list(context).filter { it.serverId != serverId })
    }

    private fun write(context: Context, all: List<PcEntry>) {
        val arr = JSONArray()
        all.forEach { arr.put(toJson(it)) }
        prefs(context).edit().putString("pcs", encrypt(arr.toString())).commit()
    }

    private fun toJson(e: PcEntry): JSONObject = JSONObject()
        .put("id", e.serverId)
        .put("name", e.name)
        .put("key", PcCrypto.hex(e.key))
        .put("hosts", JSONArray(e.hosts))
        .put("port", e.port)
        .put("macs", JSONArray(e.macs))
        .put("direct", e.direct)
        .put("profile", e.profile)
        .put("sens", e.sensitivity)
        .put("natural", e.natural)
        .put("awake", e.keepAwake)
        .put("monitor", e.monitor)

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    private fun fromJson(o: JSONObject): PcEntry = PcEntry(
        serverId = o.getString("id"),
        name = o.optString("name", "PC"),
        key = PcCrypto.unhex(o.getString("key")),
        hosts = strings(o.optJSONArray("hosts")),
        port = o.optInt("port", 47810),
        macs = strings(o.optJSONArray("macs")),
        direct = o.optBoolean("direct", false),
        profile = o.optInt("profile", 0).coerceIn(0, 2),
        sensitivity = o.optInt("sens", 1).coerceIn(0, 2),
        natural = o.optBoolean("natural", true),
        keepAwake = o.optBoolean("awake", true),
        monitor = o.optInt("monitor", 255).coerceIn(0, 255),
    )

    // --- šifrovanie kľúčom z Android Keystore (rovnako ako CloudStore) ---

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
}
