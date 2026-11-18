package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Ukladanie a úpravy zoznamu zariadení bez Androidu (testuje sa v tools/DeviceListTest.kt).
 */

/**
 * Uloží zoznam zariadení cez [write]. Nikdy sa nezavolá rekurzívne (ak by [write] alebo niečo, čo spustí, chcelo
 * uložiť znova, posledná verzia sa zapíše až po dokončení), a rovnaký zoznam sa druhýkrát nezapisuje.
 */
class DeviceSaver(private val write: (List<Device>) -> Unit) {
    private var saving = false
    private var pending: List<Device>? = null
    private var last: List<Device>? = null

    /** Koľkokrát sa naozaj zapisovalo (pre testy). */
    var writes = 0
        private set

    /** Vráti false, ak zápis zlyhal (zoznam v pamäti ostane, skúsi sa pri ďalšej zmene). */
    @Synchronized
    fun save(list: List<Device>): Boolean {
        if (saving) {
            pending = list
            return true
        }
        var next: List<Device>? = list
        var ok = true
        saving = true
        try {
            var rounds = 0
            while (next != null && rounds < 10) {
                val current: List<Device> = next
                next = null
                rounds++
                if (current != last) {
                    try {
                        write(current)
                        last = current
                        writes++
                    } catch (e: Exception) {
                        ok = false
                    }
                }
                next = pending
                pending = null
            }
        } finally {
            saving = false
            pending = null
        }
        return ok
    }

    /** Zoznam, ktorý je už uložený (napr. práve načítaný) – netreba ho zapisovať znova. */
    @Synchronized
    fun markSaved(list: List<Device>) {
        last = list
    }
}

object DeviceList {
    /**
     * Načíta uložené zariadenia. Poškodená alebo neúplná položka sa preskočí (nezahodí sa celý zoznam),
     * chýbajúce id sa doplní, duplicitné id sa nahradí novým (aby sa zariadenia v zozname nebili).
     */
    fun parseStored(raw: String?): List<Device> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = JSONArray(raw)
        val out = mutableListOf<Device>()
        val ids = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val d = runCatching { deviceFromStored(o) }.getOrNull() ?: continue
            out += if (!ids.add(d.id)) d.copy(id = UUID.randomUUID().toString()).also { ids.add(it.id) } else d
        }
        return out
    }

    private fun deviceFromStored(o: JSONObject): Device? {
        val devId = o.optString("devId").trim()
        if (devId.isEmpty()) return null
        val labels = mutableMapOf<String, String>()
        o.optJSONObject("labels")?.let { l -> for (k in l.keys()) labels[k] = l.optString(k) }
        val hidden = o.optJSONArray("hidden")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSet() } ?: emptySet()
        val schema = runCatching { Schema.parseMapping(o.optJSONObject("mapping")) }.getOrDefault(emptyMap())
        return Device(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name").ifBlank { devId },
            ip = o.optString("ip"),
            devId = devId,
            localKey = o.optString("localKey"),
            version = o.optString("version", "3.3").ifBlank { "3.3" },
            labels = labels,
            hidden = hidden,
            powerDp = o.optString("powerDp", ""),
            whiteSwitchDp = o.optString("whiteSwitchDp", ""),
            colourSwitchDp = o.optString("colourSwitchDp", ""),
            schema = schema,
            category = o.optString("category", ""),
            group = o.optString("group", ""),
            productId = o.optString("productId", ""),
            model = o.optString("model", ""),
            lightRoles = rolesFromJson(o.optJSONObject("lightRoles")),
        )
    }

    /** Nové alebo upravené zariadenie v zozname (podľa interného id). */
    fun upsert(list: List<Device>, device: Device): List<Device> =
        if (list.any { it.id == device.id }) list.map { if (it.id == device.id) device else it } else list + device

    /**
     * Zariadenie nájdené v sieti: ak ho appka pozná a hlási inú IP adresu, vráti opravené zariadenie, inak null
     * (nič sa nemení, nič sa neukladá – takže opakované správy z tej istej adresy nespúšťajú ďalšie ukladanie).
     */
    fun ipFix(list: List<Device>, found: FoundDevice): Device? {
        if (found.ip.isBlank()) return null
        val known = list.find { it.devId == found.devId } ?: return null
        return if (known.ip == found.ip) null else known.copy(ip = found.ip)
    }
}
