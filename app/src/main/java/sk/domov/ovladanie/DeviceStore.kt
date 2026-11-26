package sk.domov.ovladanie

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ukladá zariadenia do súkromného úložiska appky (SharedPreferences "domov", kľúč "devices").
 *
 * Local keys sú v uloženom JSON zašifrované kľúčom z Android Keystore ([SecretBox.LOCAL_KEYS]) s predponou
 * [ENC_PREFIX]; staršie nešifrované hodnoty sa pri prvom načítaní prešifrujú (migrácia). Záloha (.domov) a zdieľanie
 * pracujú stále s nešifrovanými kľúčmi – prevod robí [plainJson] / [sealedJson] (pozri AndroidPrefsSink).
 *
 * Ak sa uložený zoznam nedá prečítať, pôvodné dáta sa odložia do "devices_backup", nastaví sa príznak [KEY_BROKEN]
 * a [save] je zablokované, kým sa používateľ v Nastaveniach nerozhodne (obnoviť / poslať dáta / začať odznova).
 */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** true = uložený zoznam je nečitateľný a čaká na rozhodnutie používateľa; ukladanie je zablokované. */
    val isBroken: Boolean get() = prefs.getBoolean(KEY_BROKEN, false)

    fun load(): List<Device> {
        if (isBroken) return emptyList()
        val raw = prefs.getString("devices", null) ?: return emptyList()
        val parsed = try {
            DeviceList.parseStored(raw)
        } catch (e: Exception) {
            // nečitateľné dáta nezahodíme: odložia sa a ukladanie sa zablokuje, aby ich prázdny zoznam neprepísal
            android.util.Log.w("Domov", "zoznam zariadení sa nedá prečítať", e)
            prefs.edit().putString(KEY_BACKUP, raw).putBoolean(KEY_BROKEN, true).commit()
            return emptyList()
        }
        val opened = openKeys(parsed)
        if (opened.migrate && !keysUnavailable) {
            // migrácia: nešifrované local keys z verzie 1.36 a starších sa hneď zapíšu zašifrované
            runCatching { save(opened.devices) }
        }
        Gateways.update(opened.devices)
        return opened.devices
    }

    fun save(devices: List<Device>) {
        if (isBroken) throw DevicesBlockedException()
        if (keysUnavailable) throw IllegalStateException("Kľúče zariadení sa teraz nedajú odomknúť (Keystore). Reštartuj appku.")
        val arr = JSONArray()
        devices.forEach { d ->
            val labels = JSONObject()
            d.labels.forEach { (k, v) -> labels.put(k, v) }
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("name", d.name)
                    .put("ip", d.ip)
                    .put("devId", d.devId)
                    .put("localKey", sealKey(d.localKey))
                    .put("version", d.version)
                    .put("labels", labels)
                    .put("hidden", JSONArray(d.hidden.toList()))
                    .put("powerDp", d.powerDp)
                    .put("whiteSwitchDp", d.whiteSwitchDp)
                    .put("colourSwitchDp", d.colourSwitchDp)
                    .put("category", d.category)
                    .put("group", d.group)
                    .put("productId", d.productId)
                    .put("model", d.model)
                    .apply { if (d.isSub) put("gatewayId", d.gatewayId).put("cid", d.cid) }
                    .apply { if (d.lightRoles.isNotEmpty()) put("lightRoles", rolesToJson(d.lightRoles)) }
                    .apply { if (d.schema.isNotEmpty()) put("mapping", Schema.toJson(d.schema)) }
            )
        }
        prefs.edit().putString("devices", arr.toString()).apply()
        Gateways.update(devices)
    }

    // ------------------------------------------------------------ poškodený zoznam (záchrana)

    /** Pôvodné (nečitateľné) dáta zoznamu, napr. na poslanie / skopírovanie. */
    fun brokenRaw(): String? = prefs.getString(KEY_BACKUP, null)

    /**
     * Skúsi obnoviť zoznam z "devices_backup" (napr. po aktualizácii appky, ktorá ho už vie prečítať).
     * Pri úspechu ho zapíše (zašifrované kľúče), zruší blokovanie a vráti zariadenia; inak vráti null a nič nemení.
     */
    fun rescue(): List<Device>? {
        val raw = brokenRaw() ?: return null
        val parsed = runCatching { DeviceList.parseStored(raw) }.getOrNull() ?: return null
        val opened = openKeys(parsed)
        if (keysUnavailable) return null
        prefs.edit().remove(KEY_BROKEN).commit()
        save(opened.devices)
        return opened.devices
    }

    /** Začať s prázdnym zoznamom: zruší blokovanie; pôvodné dáta ostanú v "devices_backup" (prepíšu sa až pri ďalšej chybe). */
    fun discardBroken() {
        prefs.edit().remove(KEY_BROKEN).remove("devices").commit()
    }

    private class Opened(val devices: List<Device>, val migrate: Boolean)

    private fun openKeys(list: List<Device>): Opened {
        var migrate = false
        val out = list.map { d ->
            val k = d.localKey
            when {
                k.isEmpty() -> d
                !k.startsWith(ENC_PREFIX) -> { migrate = true; d }
                else -> try {
                    d.copy(localKey = openKey(k))
                } catch (e: Exception) {
                    if (SecretBox.isPermanent(e)) {
                        // kľúč v Keystore sa stratil: local key treba stiahnuť znova (Tuya cloud / import), zariadenie ostane
                        android.util.Log.w("Domov", "local key zariadenia ${d.name} sa nedá odšifrovať", e)
                        migrate = true
                        d.copy(localKey = "")
                    } else {
                        keysUnavailable = true
                        android.util.Log.w("Domov", "Keystore je dočasne nedostupný – ukladanie zariadení zablokované", e)
                        d.copy(localKey = "")
                    }
                }
            }
        }
        return Opened(out, migrate)
    }

    companion object {
        const val FILE = "domov"
        const val KEY_BACKUP = "devices_backup"
        const val KEY_BROKEN = "devices_broken"
        const val ENC_PREFIX = "enc1:"

        /** Dočasná chyba Keystore pri načítaní kľúčov – kým appka beží, zoznam sa radšej nezapisuje (neprepíšu sa kľúče). */
        @Volatile
        private var keysUnavailable = false

        /**
         * Vyrovnávacia pamäť šifrovaných ↔ nešifrovaných kľúčov, aby sa pri každom uložení / načítaní (napr. posun dlaždice,
         * widget) nevolal Keystore pre každé zariadenie znova. Kľúče sú v pamäti procesu aj tak (v objektoch Device).
         */
        private val sealedByPlain = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val plainBySealed = java.util.concurrent.ConcurrentHashMap<String, String>()

        private fun sealKey(plain: String): String {
            if (plain.isEmpty()) return ""
            sealedByPlain[plain]?.let { return it }
            val sealed = ENC_PREFIX + SecretBox.encrypt(SecretBox.LOCAL_KEYS, plain)
            sealedByPlain[plain] = sealed
            plainBySealed[sealed] = plain
            return sealed
        }

        private fun openKey(sealed: String): String {
            plainBySealed[sealed]?.let { return it }
            val plain = SecretBox.decrypt(SecretBox.LOCAL_KEYS, sealed.removePrefix(ENC_PREFIX))
            plainBySealed[sealed] = plain
            sealedByPlain.putIfAbsent(plain, sealed)
            return plain
        }

        /** Uložený JSON zoznamu → rovnaký JSON s nešifrovanými local keys (do zálohy). Ostatné polia sa nemenia. */
        fun plainJson(stored: String): String = transformKeys(stored) { k ->
            if (k.startsWith(ENC_PREFIX)) {
                try {
                    openKey(k)
                } catch (e: Exception) {
                    if (SecretBox.isPermanent(e)) "" else throw java.io.IOException("Kľúče zariadení sa teraz nedajú odomknúť. Skús to znova.", e)
                }
            } else k
        }

        /** JSON zoznamu s nešifrovanými local keys (zo zálohy) → na uloženie so zašifrovanými. */
        fun sealedJson(plain: String): String = transformKeys(plain) { k -> if (k.startsWith(ENC_PREFIX)) k else sealKey(k) }

        private fun transformKeys(json: String, f: (String) -> String): String {
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return json
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val k = o.optString("localKey")
                if (k.isNotEmpty()) o.put("localKey", f(k))
            }
            return arr.toString()
        }
    }
}

/** Uložený zoznam zariadení je poškodený – kým sa to v Nastaveniach nevyrieši, nič sa nezapisuje. */
class DevicesBlockedException : IllegalStateException("Zoznam zariadení je poškodený – rieš to v Nastaveniach › Pozor – zoznam zariadení.")
