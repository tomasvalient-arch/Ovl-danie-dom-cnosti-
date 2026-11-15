package sk.domov.ovladanie

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Jedno Tuya zariadenie. [channels] sú čísla datapointov (DP), ktoré fungujú ako vypínače. */
data class Device(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val ip: String,
    val devId: String,
    val localKey: String,
    val version: String = "3.3",
    val channels: List<String> = listOf("1"),
)

sealed interface DeviceState {
    data object Loading : DeviceState
    data class Online(val values: Map<String, Boolean>) : DeviceState
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
                val ch = o.optJSONArray("channels")
                Device(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    ip = o.getString("ip"),
                    devId = o.getString("devId"),
                    localKey = o.getString("localKey"),
                    version = o.optString("version", "3.3"),
                    channels = if (ch == null) listOf("1") else (0 until ch.length()).map { ch.getString(it) },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(devices: List<Device>) {
        val arr = JSONArray()
        devices.forEach { d ->
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("name", d.name)
                    .put("ip", d.ip)
                    .put("devId", d.devId)
                    .put("localKey", d.localKey)
                    .put("version", d.version)
                    .put("channels", JSONArray(d.channels))
            )
        }
        prefs.edit().putString("devices", arr.toString()).apply()
    }
}

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
