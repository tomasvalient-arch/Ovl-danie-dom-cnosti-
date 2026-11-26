package sk.domov.ovladanie

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import sk.domov.ovladanie.pc.PcCrypto

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
) {
    override fun toString(): String = "PcEntry(serverId=$serverId, name=$name, key=${masked(key)}, hosts=$hosts, port=$port, macs=$macs)"
}

object PcStore {
    private const val FILE = "domov_pc"

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
    fun list(context: Context): List<PcEntry> = read(context) ?: emptyList()

    /**
     * Načíta spárované PC. null = údaje sa práve nedajú prečítať (dočasná chyba Keystore) – vtedy sa nič nemaže
     * a zmeny ([save], [update], [remove]) sa nezapíšu, aby neprepísali existujúce párovania prázdnym zoznamom.
     */
    private fun read(context: Context): List<PcEntry>? {
        val enc = prefs(context).getString("pcs", null) ?: return emptyList()
        return try {
            val arr = JSONArray(decrypt(enc))
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            if (SecretBox.isPermanent(e)) {
                // kľúč v Keystore sa stratil (napr. po obnove telefónu) alebo sú údaje poškodené: spárovať treba znova
                prefs(context).edit().remove("pcs").commit()
                emptyList()
            } else {
                android.util.Log.w("Domov", "PC: spárované PC sa teraz nedajú načítať", e)
                null
            }
        }
    }

    private fun readForWrite(context: Context): MutableList<PcEntry> =
        read(context)?.toMutableList() ?: throw PcStoreUnavailableException()

    @Synchronized
    fun get(context: Context, serverId: String): PcEntry? = list(context).firstOrNull { it.serverId == serverId }

    /** Pridá PC, alebo nahradí spárovaný PC s rovnakým ID (nové párovanie). */
    @Synchronized
    fun save(context: Context, entry: PcEntry) {
        val all = readForWrite(context)
        val i = all.indexOfFirst { it.serverId == entry.serverId }
        if (i >= 0) all[i] = entry else all.add(entry)
        write(context, all)
    }

    @Synchronized
    fun update(context: Context, serverId: String, change: (PcEntry) -> PcEntry): PcEntry? {
        val all = read(context)?.toMutableList() ?: return null
        val i = all.indexOfFirst { it.serverId == serverId }
        if (i < 0) return null
        val next = change(all[i])
        all[i] = next
        write(context, all)
        return next
    }

    @Synchronized
    fun remove(context: Context, serverId: String) {
        write(context, readForWrite(context).filter { it.serverId != serverId })
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

    // --- šifrovanie kľúčom z Android Keystore (spoločné so CloudStore, pozri SecretBox) ---

    private fun encrypt(text: String): String = SecretBox.encrypt(SecretBox.PC, text)

    private fun decrypt(stored: String): String = SecretBox.decrypt(SecretBox.PC, stored)
}

/** Spárované PC sa práve nedajú načítať (dočasná chyba Keystore) – zmena sa nezapísala, nič sa nezmazalo. */
class PcStoreUnavailableException : IllegalStateException("Spárované PC sa teraz nedajú načítať. Skús to znova o chvíľu.")
