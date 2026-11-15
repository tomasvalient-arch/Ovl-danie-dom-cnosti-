package sk.domov.ovladanie

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Jedno Tuya zariadenie. Hodnoty (DP) sa z neho načítajú automaticky. */
data class Device(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val ip: String,
    val devId: String,
    val localKey: String,
    val version: String = "3.3",
    /** Vlastné názvy hodnôt: číslo DP -> názov (napr. "20" -> "Svetlo"). */
    val labels: Map<String, String> = emptyMap(),
    /** Čísla DP, ktoré sa nemajú zobrazovať. */
    val hidden: Set<String> = emptySet(),
    /** Číslo hodnoty (DP), ktorá je hlavný vypínač. Prázdne = nájde sa automaticky. */
    val powerDp: String = "",
)

sealed interface DeviceState {
    data object Loading : DeviceState

    /** Hodnoty môžu byť Boolean, číslo alebo text. */
    data class Online(val values: Map<String, Any>) : DeviceState

    data class Error(val message: String) : DeviceState
}

/** Ukladá zariadenia do súkromného úložiska appky (SharedPreferences). */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("domov", Context.MODE_PRIVATE)

    fun load(): List<Device> {
        val raw = prefs.getString("devices", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val labels = mutableMapOf<String, String>()
                val labelsJson = o.optJSONObject("labels")
                if (labelsJson != null) {
                    for (k in labelsJson.keys()) {
                        labels[k] = labelsJson.optString(k)
                    }
                }
                val hiddenArr = o.optJSONArray("hidden")
                val hidden = if (hiddenArr == null) {
                    emptySet()
                } else {
                    (0 until hiddenArr.length()).map { hiddenArr.getString(it) }.toSet()
                }
                Device(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    ip = o.getString("ip"),
                    devId = o.getString("devId"),
                    localKey = o.getString("localKey"),
                    version = o.optString("version", "3.3"),
                    labels = labels,
                    hidden = hidden,
                    powerDp = o.optString("powerDp", ""),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(devices: List<Device>) {
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
                    .put("localKey", d.localKey)
                    .put("version", d.version)
                    .put("labels", labels)
                    .put("hidden", JSONArray(d.hidden.toList()))
                    .put("powerDp", d.powerDp)
            )
        }
        prefs.edit().putString("devices", arr.toString()).apply()
    }
}

/**
 * Ktorá hodnota je hlavný vypínač: ručne nastavená, inak vypínač svetla,
 * inak prvá hodnota typu zapnuté/vypnuté. Vráti null, ak zariadenie žiadnu takú nemá.
 */
fun powerDpOf(device: Device, values: Map<String, Any>): String? {
    if (device.powerDp.isNotBlank() && values[device.powerDp] is Boolean) return device.powerDp
    Light.detect(values)?.let { return it.power }
    return values.entries.firstOrNull { it.value is Boolean }?.key
}

/** "20=Svetlo, 22=Jas" -> {20: Svetlo, 22: Jas} */
fun parseLabels(text: String): Map<String, String> =
    text.split(",").mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) {
            null
        } else {
            val key = part.substring(0, i).trim()
            val value = part.substring(i + 1).trim()
            if (key.isEmpty() || value.isEmpty()) null else key to value
        }
    }.toMap()

fun labelsToText(labels: Map<String, String>): String =
    labels.entries.joinToString(", ") { "${it.key}=${it.value}" }

/** "25, 26" -> {25, 26} */
fun parseHidden(text: String): Set<String> =
    text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/**
 * Import zo súboru devices.json, ktorý vytvorí `python -m tinytuya wizard`.
 * Očakáva pole objektov s poľami name, id, key a voliteľne ip a ver/version.
 */
fun parseTinytuyaJson(text: String): List<Device> {
    val arr = JSONArray(text.trim())
    val result = mutableListOf<Device>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val id = o.optString("id")
        val key = o.optString("key")
        if (id.isBlank() || key.length != 16) continue
        val ver = o.optString("ver", o.optString("version", "3.3")).ifBlank { "3.3" }
        result += Device(
            name = o.optString("name", id).ifBlank { id },
            ip = o.optString("ip", ""),
            devId = id,
            localKey = key,
            version = if (ver in listOf("3.3", "3.4", "3.5")) ver else "3.3",
        )
    }
    return result
}
