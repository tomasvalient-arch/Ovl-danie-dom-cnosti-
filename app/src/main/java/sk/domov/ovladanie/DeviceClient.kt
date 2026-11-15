package sk.domov.ovladanie

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import sk.domov.ovladanie.tuya.TuyaDevice
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Komunikácia so zariadením, spoločná pre appku aj widgety.
 * Všetky metódy blokujú (sieť), preto ich volaj mimo hlavného vlákna.
 */
object DeviceClient {
    /** Na jedno zariadenie naraz len jedno spojenie (lacné Tuya čipy viac spojení nezvládajú). */
    private val locks = ConcurrentHashMap<String, Any>()

    private fun isTransient(e: Exception): Boolean =
        e is TuyaDevice.DeviceException ||
            e is java.io.EOFException ||
            (e is java.net.SocketException &&
                e !is java.net.ConnectException &&
                e !is java.net.NoRouteToHostException)

    /**
     * Vykoná operáciu so zariadením tak, aby sa dve operácie na tom istom zariadení nikdy neprekrývali,
     * a pri dočasnej chybe (kód od zariadenia, prerušené spojenie) ju skúsi ešte dvakrát.
     * Ak zariadenie vôbec neodpovedá (timeout), neopakuje sa, aby užívateľ zbytočne nečakal.
     */
    private fun <T> exclusive(d: Device, block: () -> T): T {
        val lock = locks.getOrPut(d.devId) { Any() }
        synchronized(lock) {
            for (attempt in 1..3) {
                try {
                    return block()
                } catch (e: Exception) {
                    if (attempt == 3 || !isTransient(e)) throw e
                    Thread.sleep(300L * attempt)
                }
            }
        }
        throw IllegalStateException("nedosiahnuteľné")
    }

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
    fun readState(network: Network, d: Device): Map<String, Any> = exclusive(d) { readStateOnce(network, d) }

    private fun readStateOnce(network: Network, d: Device): Map<String, Any> {
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
        exclusive(d) { client(network, d).setDps(json.toString()) }
    }

    /** Dlhší popis chyby pre obrazovku zariadenia. */
    fun describe(t: Throwable): String = when (t) {
        is java.net.SocketTimeoutException -> "Zariadenie neodpovedá (skontroluj IP adresu a či je zapnuté)"
        is java.net.ConnectException -> "Nedá sa pripojiť (skontroluj IP adresu)"
        is TuyaDevice.DeviceException ->
            "Zariadenie vrátilo chybu (kód ${t.code}). Býva to dočasné, skús znova. " +
                "Ak sa to opakuje, vypni zariadenie zo zásuvky na pár sekúnd."
        is java.io.EOFException -> "Zariadenie zavrelo spojenie. Skús znova."
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

/**
 * Režimy fritézy, ktoré appka už videla (napr. „Chips“). Zariadenie ich neposiela ako zoznam, takže sa appka
 * naučí každú hodnotu, keď sa raz objaví (aj po prepnutí režimu v Tuya appke).
 */
object ModeMemory {
    private fun prefs(context: Context) =
        context.getSharedPreferences("domov_modes", Context.MODE_PRIVATE)

    fun remember(context: Context, deviceId: String, mode: String) {
        if (mode.isBlank()) return
        val known = list(context, deviceId)
        if (mode in known) return
        prefs(context).edit().putString("modes_$deviceId", JSONArray(known + mode).toString()).apply()
    }

    fun list(context: Context, deviceId: String): List<String> {
        val raw = prefs(context).getString("modes_$deviceId", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
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
