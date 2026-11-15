package sk.domov.ovladanie

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.json.JSONObject
import sk.domov.ovladanie.tuya.TuyaDevice
import java.io.IOException

/**
 * Komunikácia so zariadením, spoločná pre appku aj widgety.
 * Všetky metódy blokujú (sieť), preto ich volaj mimo hlavného vlákna.
 */
object DeviceClient {

    /** Nájde WiFi sieť telefónu, alebo null ak telefón nie je na WiFi. */
    @Suppress("DEPRECATION")
    fun wifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        fun isWifi(n: Network): Boolean =
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val active = cm.activeNetwork
        if (active != null && isWifi(active)) return active
        return cm.allNetworks.firstOrNull { isWifi(it) }
    }

    private fun client(network: Network, d: Device): TuyaDevice {
        // Sockety viažeme priamo na WiFi sieť, aby sa nepoužilo mobilné dáta ani keď WiFi nemá internet.
        return TuyaDevice(d.ip, d.devId, d.localKey, d.version, network.socketFactory)
    }

    /** Načíta všetky hodnoty (DP) zariadenia, zoradené podľa čísla DP. */
    fun readState(network: Network, d: Device): Map<String, Any> {
        if (d.ip.isBlank()) throw IOException("Zariadenie nemá nastavenú IP adresu")
        val text = client(network, d).query()
        if (text.isBlank()) throw IOException("Zariadenie neposlalo žiadne dáta")
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException("Neočakávaná odpoveď zariadenia: $text")
        }
        val dps = json.optJSONObject("dps")
            ?: json.optJSONObject("data")?.optJSONObject("dps")
            ?: throw IOException("Neočakávaná odpoveď zariadenia: $text")
        val out = HashMap<String, Any>()
        for (key in dps.keys()) {
            val v = dps.get(key)
            if (v is Boolean || v is Number || v is String) out[key] = v
        }
        return out.toSortedMap(compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
    }

    /** Nastaví jednu alebo viac hodnôt naraz (Boolean, číslo alebo text). */
    fun setValues(network: Network, d: Device, values: Map<String, Any>) {
        if (values.isEmpty()) return
        val json = JSONObject()
        for ((k, v) in values) json.put(k, v)
        client(network, d).setDps(json.toString())
    }

    /** Dlhší popis chyby pre obrazovku zariadenia. */
    fun describe(t: Throwable): String = when (t) {
        is java.net.SocketTimeoutException -> "Zariadenie neodpovedá (skontroluj IP adresu a či je zapnuté)"
        is java.net.ConnectException -> "Nedá sa pripojiť (skontroluj IP adresu)"
        is IllegalArgumentException -> t.message ?: "Neplatné nastavenia zariadenia"
        else -> t.message ?: t.javaClass.simpleName
    }

    /** Veľmi krátky popis chyby do dlaždice alebo widgetu. */
    fun shortError(t: Throwable): String = when (t) {
        is java.net.SocketTimeoutException, is java.net.ConnectException -> "Nedostupné"
        else -> "Chyba"
    }
}

/** Naposledy známe hodnoty zariadení, aby widgety vedeli ukázať stav aj bez pripojenia. */
object StateCache {
    private fun prefs(context: Context) =
        context.getSharedPreferences("domov_cache", Context.MODE_PRIVATE)

    fun save(context: Context, deviceId: String, values: Map<String, Any>) {
        val json = JSONObject()
        for ((k, v) in values) json.put(k, v)
        prefs(context).edit().putString("values_$deviceId", json.toString()).apply()
    }

    fun load(context: Context, deviceId: String): Map<String, Any>? {
        val raw = prefs(context).getString("values_$deviceId", null) ?: return null
        return try {
            val json = JSONObject(raw)
            val out = HashMap<String, Any>()
            for (key in json.keys()) {
                val v = json.get(key)
                if (v is Boolean || v is Number || v is String) out[key] = v
            }
            out.toSortedMap(compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
        } catch (e: Exception) {
            null
        }
    }

    fun remove(context: Context, deviceId: String) {
        prefs(context).edit().remove("values_$deviceId").apply()
    }
}

/** Ktoré zariadenie patrí ktorému widgetu na ploche. */
object WidgetPrefs {
    private fun prefs(context: Context) =
        context.getSharedPreferences("domov_widgets", Context.MODE_PRIVATE)

    fun set(context: Context, widgetId: Int, deviceId: String) {
        prefs(context).edit().putString("w_$widgetId", deviceId).apply()
    }

    fun deviceId(context: Context, widgetId: Int): String? =
        prefs(context).getString("w_$widgetId", null)

    fun remove(context: Context, widgetId: Int) {
        prefs(context).edit().remove("w_$widgetId").apply()
    }
}
