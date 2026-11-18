package sk.domov.ovladanie

import android.content.Context
import android.net.Network
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

    private fun client(network: Network, d: Device): TuyaDevice {
        // Sockety viažeme priamo na WiFi sieť, aby sa nepoužilo mobilné dáta ani keď WiFi nemá internet.
        return TuyaDevice(d.ip, d.devId, d.localKey, d.version, network.socketFactory)
    }

    /** Načíta všetky hodnoty (DP) zariadenia, zoradené podľa čísla DP. */
    fun readState(network: Network, d: Device): Map<String, Any> = exclusive(d) { closeLive(d); readStateOnce(network, d) }

    /** Koľkokrát po sebe zariadenie neposlalo vyžiadané skryté DP (po 3 razoch sa ďalej nepýtame). */
    private val hiddenMisses = ConcurrentHashMap<String, Int>()

    private fun readStateOnce(network: Network, d: Device): Map<String, Any> {
        if (d.ip.isBlank()) throw IOException("Zariadenie nemá nastavenú IP adresu")
        val hidden = LightChannels.hiddenDps(d).mapNotNull { it.toIntOrNull() }
        if (hidden.isNotEmpty() && (hiddenMisses[d.devId] ?: 0) < 3) {
            // skryté DP (napr. 51 mix_rgbcw) zariadenie v bežnom stave neposiela – vyžiadame ich v tom istom spojení
            val texts = client(network, d).queryWithExtra(arrayOf(hidden.toIntArray()), 900, true)
            val first = texts.firstOrNull().orEmpty()
            if (first.isBlank()) throw IOException("Zariadenie neposlalo žiadne dáta")
            if (LocalDps.parse(first) == null) throw IOException("Neočakávaná odpoveď zariadenia: $first")
            val merged = LocalDps.merge(texts)
            if (hidden.any { merged.containsKey(it.toString()) }) hiddenMisses.remove(d.devId) else hiddenMisses.merge(d.devId, 1, Int::plus)
            return merged
        }
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

    /**
     * Technické info: stav a potom vyžiada všetky DP 1–255 (UPDATEDPS po skupinách). Vráti všetko, čo zariadenie
     * poslalo, aj DP mimo popisu z cloudu.
     */
    fun probeAll(network: Network, d: Device): Map<String, Any> = exclusive(d) {
        if (d.ip.isBlank()) throw IOException("Zariadenie nemá nastavenú IP adresu")
        val groups = LocalDps.PROBE_GROUPS.map { it.toIntArray() }.toTypedArray()
        LocalDps.merge(client(network, d).queryWithExtra(groups, 1200, false))
    }

    /**
     * Sledovanie zmien: drží spojenie otvorené [durationMs] a každé prijaté hlásenie pošle do [onDps].
     * Výnimky von nepúšťa; [onInfo] dostane stav spojenia.
     */
    fun monitor(network: Network, d: Device, durationMs: Long, cancelled: () -> Boolean, onDps: (Map<String, Any>) -> Unit, onInfo: (String) -> Unit) {
        try {
            if (d.ip.isBlank()) {
                onInfo("Zariadenie nemá nastavenú IP adresu")
                return
            }
            val extra = LightChannels.hiddenDps(d).mapNotNull { it.toIntOrNull() }.toIntArray()
            client(network, d).monitor(extra, durationMs, object : TuyaDevice.MonitorListener {
                override fun onFrame(cmd: Int, retcode: Int, text: String) {
                    runCatching {
                        val dps = LocalDps.parse(text)
                        if (dps != null && dps.isNotEmpty()) onDps(dps) else if (retcode != 0) onInfo("zariadenie vrátilo kód $retcode")
                    }
                }

                override fun onInfo(message: String) {
                    runCatching { onInfo(message) }
                }
            }) { cancelled() }
        } catch (e: Exception) {
            runCatching { onInfo("chyba: " + describe(e)) }
        }
    }

    /** Nastaví jednu alebo viac hodnôt naraz (Boolean, číslo alebo text). */
    fun setValues(network: Network, d: Device, values: Map<String, Any>) {
        if (values.isEmpty()) return
        val json = JSONObject()
        for ((k, v) in values) json.put(k, v)
        exclusive(d) { closeLive(d); client(network, d).setDps(json.toString()) }
    }

    /** Trvalé spojenia pre posuvníky (devId → spojenie, čas posledného použitia). Zatvoria sa po 3 s nečinnosti. */
    private class LiveConn(val conn: TuyaDevice.Live, @Volatile var used: Long)

    private val liveConns = ConcurrentHashMap<String, LiveConn>()
    /** Časovač zatvárania nečinných spojení beží len kým je nejaké spojenie otvorené (predtým tikal každú sekundu navždy). */
    private val timerLock = Any()
    private var liveTimer: java.util.Timer? = null

    private fun ensureLiveTimer() = synchronized(timerLock) {
        if (liveTimer != null) return@synchronized
        liveTimer = java.util.Timer("domov-live-close", true).also { t ->
            runCatching {
                t.schedule(object : java.util.TimerTask() {
                    override fun run() {
                        runCatching { closeIdleLive() }
                        stopLiveTimerIfIdle()
                    }
                }, 1000, 1000)
            }
        }
    }

    private fun stopLiveTimerIfIdle() = synchronized(timerLock) {
        if (liveConns.isEmpty()) {
            liveTimer?.cancel()
            liveTimer = null
        }
    }

    private fun closeIdleLive() {
        val now = System.currentTimeMillis()
        liveConns.entries.toList().forEach { (id, c) ->
            if (now - c.used > 3000) {
                val lock = locks.getOrPut(id) { Any() }
                synchronized(lock) {
                    if (liveConns[id] === c && System.currentTimeMillis() - c.used > 3000) {
                        liveConns.remove(id)
                        runCatching { c.conn.close() }
                    }
                }
            }
        }
    }

    /**
     * Príkaz počas ťahania posuvníka: cez trvalé spojenie (pri 3.4/3.5 bez nového vyjednávania kľúča). Pri chybe sa
     * spojenie zavrie a príkaz sa skúsi ešte raz bežným spôsobom. Výnimku vyhodí len vtedy, keď zlyhá aj to.
     */
    fun liveSend(network: Network, d: Device, values: Map<String, Any>) {
        if (values.isEmpty()) return
        if (d.ip.isBlank()) throw IOException("Zariadenie nemá nastavenú IP adresu")
        val json = JSONObject()
        for ((k, v) in values) json.put(k, v)
        val lock = locks.getOrPut(d.devId) { Any() }
        synchronized(lock) {
            try {
                val c = liveConns[d.devId] ?: LiveConn(client(network, d).openLive(), 0L).also { liveConns[d.devId] = it }
                c.conn.set(json.toString())
                c.used = System.currentTimeMillis()
                ensureLiveTimer()
                return
            } catch (e: Exception) {
                liveConns.remove(d.devId)?.let { runCatching { it.conn.close() } }
            }
            client(network, d).setDps(json.toString())
        }
    }

    /** Zavrie trvalé spojenie zariadenia (pred bežným čítaním stavu, aby lacné čipy nemali dve spojenia). */
    fun closeLive(d: Device) {
        liveConns.remove(d.devId)?.let { runCatching { it.conn.close() } }
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
    fun prefs(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences("domov_cache", Context.MODE_PRIVATE)

    /** Odkiaľ je posledný známy stav (zobrazí sa na dlaždici, keď telefón nie je na WiFi). */
    const val SRC_WIFI = "cez WiFi"
    const val SRC_AUTO_WIFI = "automatika cez WiFi"
    const val SRC_AUTO_CLOUD = "automatika cez cloud"
    const val SRC_MANUAL_WIFI = "ručne cez WiFi"
    const val SRC_MANUAL_CLOUD = "ručne cez cloud"

    data class Meta(val time: Long, val source: String)

    fun save(context: Context, deviceId: String, values: Map<String, Any>, source: String = SRC_WIFI) {
        val json = JSONObject()
        for ((k, v) in values) json.put(k, v)
        // čas a zdroj v tom istom zápise, aby ich listener (HomeViewModel) videl spolu s hodnotami
        prefs(context).edit()
            .putLong("at_$deviceId", System.currentTimeMillis())
            .putString("src_$deviceId", source)
            .putString("values_$deviceId", json.toString())
            .apply()
    }

    /** Kedy a odkiaľ bol uložený posledný stav (null = zatiaľ nikdy, napr. stav spred verzie 1.17). */
    fun meta(context: Context, deviceId: String): Meta? {
        val p = prefs(context)
        val t = p.getLong("at_$deviceId", 0L)
        if (t <= 0L) return null
        return Meta(t, p.getString("src_$deviceId", null) ?: SRC_WIFI)
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
        prefs(context).edit().remove("values_$deviceId").remove("at_$deviceId").remove("src_$deviceId").apply()
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
