package sk.domov.ovladanie

import java.io.IOException
import java.util.Locale

/*
 * Zigbee (a iné podzariadenia) cez Tuya bránu. Podzariadenie nemá vlastnú IP adresu ani local key: appka sa pripojí
 * k bráne (jej IP, Device ID a local key) a v príkaze uvedie ID podzariadenia (cid = node_id z Tuya cloudu).
 * Súbor nepoužíva Android, testuje sa v JVM (tools/ZigbeeTest.kt).
 */

/** Kam sa pripojiť: IP, Device ID, local key a verzia zariadenia, ku ktorému sa naozaj pripájame, a cid podzariadenia. */
data class Route(val ip: String, val devId: String, val localKey: String, val version: String, val cid: String) {
    override fun toString(): String = "Route(ip=$ip, devId=$devId, localKey=${masked(localKey)}, version=$version, cid=$cid)"
}

/**
 * Brány v appke. Podzariadenie si IP a kľúč berie z brány, takže pri zmene IP brány (hľadanie v sieti) alebo kľúča
 * (synchronizácia) netreba upravovať každé podzariadenie. Zoznam sa obnoví pri každom načítaní a uložení zariadení
 * (DeviceStore), takže ho majú aj widgety a automatika bez ďalšieho nastavovania.
 */
object Gateways {
    @Volatile private var gateways: Map<String, Device> = emptyMap()
    @Volatile private var children: Map<String, Int> = emptyMap()

    /** Kategórie Tuya brán (Zigbee, viacmódová). Brána sa pozná aj podľa toho, že má podzariadenia. */
    val CATEGORIES = setOf("wg2", "wfcon")

    fun update(devices: List<Device>) {
        gateways = devices.filter { !it.isSub }.associateBy { it.devId }
        val counts = HashMap<String, Int>()
        devices.filter { it.isSub }.forEach { counts[it.gatewayId] = (counts[it.gatewayId] ?: 0) + 1 }
        children = counts
    }

    /** Je to brána (má kategóriu brány alebo za ňou sú podzariadenia v appke)? */
    fun isGateway(d: Device): Boolean =
        !d.isSub && (d.category.trim().lowercase(Locale.ROOT) in CATEGORIES || (children[d.devId] ?: 0) > 0)

    /** Koľko podzariadení má brána v appke. */
    fun childCount(d: Device): Int = children[d.devId] ?: 0

    /** Brána, cez ktorú ide podzariadenie (alebo null, ak nie je v appke). */
    fun gatewayOf(d: Device): Device? = if (d.isSub) gateways[d.gatewayId] else null

    /**
     * Pripojenie zariadenia: samostatné zariadenie priamo, podzariadenie cez bránu. Ak bránu appka nepozná alebo nemá
     * IP adresu, vyhodí IOException s vysvetlením (zobrazí sa pri zariadení).
     */
    fun route(d: Device): Route {
        if (!d.isSub) return Route(d.ip, d.devId, d.localKey, d.version, "")
        val gw = gateways[d.gatewayId]
            ?: throw IOException("Brána tohto zariadenia nie je v appke. Pridaj ju cez Synchronizovať s Tuya cloudom.")
        if (gw.ip.isBlank()) throw IOException("Brána „${gw.name}“ nemá IP adresu. Nájdi ju v sieti (Hľadať v sieti).")
        if (gw.localKey.length != 16) throw IOException("Brána „${gw.name}“ nemá local key. Spusti Synchronizovať s Tuya cloudom.")
        return Route(gw.ip, gw.devId, gw.localKey, gw.version, d.cid)
    }

    /** Podzariadenia jednej brány sa nikdy nepýtajú naraz (brána spojenia zvláda po jednom). */
    fun lockKey(d: Device): String = if (d.isSub) d.gatewayId else d.devId
}

/** Druh senzora. */
enum class SensorKind(val title: String) {
    DOOR("Senzor dverí / okna"),
    MOTION("Senzor pohybu"),
    CLIMATE("Teplota a vlhkosť"),
    LEAK("Senzor vody"),
    SMOKE("Senzor dymu"),
    GAS("Senzor plynu"),
    CO("Senzor CO"),
    BUTTON("Tlačidlo"),
}

/** Aktuálne hodnoty senzora pripravené na zobrazenie. */
data class SensorReading(
    val kind: SensorKind,
    /** Hlavná hodnota: „Zatvorené“, „Pohyb“, „21,4 °C“. Prázdne = zatiaľ nie je známa. */
    val title: String,
    /** Doplnok: „Vlhkosť 48 %“. Môže byť prázdny. */
    val detail: String,
    /** Zvýrazniť (otvorené, pohyb, poplach). */
    val alarm: Boolean,
    /** Stav batérie v % (0–100), alebo null, ak ho zariadenie nehlási. */
    val battery: Int?,
    val batteryLow: Boolean,
    /** Zariadenie hlási násilné otvorenie krytu. */
    val tamper: Boolean,
) {
    val known: Boolean get() = title.isNotEmpty()

    /** Text batérie na dlaždicu / do detailu: „Batéria 87 %“, „Batéria slabá“, alebo prázdny. */
    val batteryText: String
        get() = when {
            battery != null -> "Batéria $battery %" + if (batteryLow) " (slabá)" else ""
            batteryLow -> "Batéria slabá"
            else -> ""
        }
}

/**
 * Senzory (dvere/okná, pohyb, teplota a vlhkosť, voda, dym, plyn, tlačidlá). Druh sa určí podľa popisu hodnôt z Tuya
 * cloudu (kódy ako doorcontact_state, pir, va_temperature) a podľa kategórie zariadenia; čísla hodnôt (DP) sa berú
 * z popisu, a ak chýba, z bežných čísel danej kategórie. Senzory appka len zobrazuje, nikdy im nič neposiela.
 */
object Sensors {
    private val CATEGORY_KIND = mapOf(
        "mcs" to SensorKind.DOOR,
        "pir" to SensorKind.MOTION,
        "hps" to SensorKind.MOTION,
        "wsdcg" to SensorKind.CLIMATE,
        "sj" to SensorKind.LEAK,
        "ywbj" to SensorKind.SMOKE,
        "rqbj" to SensorKind.GAS,
        "cobj" to SensorKind.CO,
        "wxkg" to SensorKind.BUTTON,
    )

    /** Kód hodnoty -> druh (rozhoduje popis z cloudu; je presnejší ako kategória). */
    private val CODE_KIND = linkedMapOf(
        "doorcontact_state" to SensorKind.DOOR,
        "watersensor_state" to SensorKind.LEAK,
        "smoke_sensor_status" to SensorKind.SMOKE,
        "smoke_sensor_state" to SensorKind.SMOKE,
        "gas_sensor_status" to SensorKind.GAS,
        "gas_sensor_state" to SensorKind.GAS,
        "co_status" to SensorKind.CO,
        "co_state" to SensorKind.CO,
        "pir" to SensorKind.MOTION,
        "presence_state" to SensorKind.MOTION,
        "va_temperature" to SensorKind.CLIMATE,
        "va_humidity" to SensorKind.CLIMATE,
        "switch_mode1" to SensorKind.BUTTON,
    )

    /** Čísla hodnôt, keď zariadenie nemá popis z cloudu (podľa Tuya dokumentácie kategórií). */
    private val DEFAULT_MAIN_DP = mapOf(
        SensorKind.DOOR to "1", SensorKind.MOTION to "1", SensorKind.LEAK to "1",
        SensorKind.SMOKE to "1", SensorKind.GAS to "1", SensorKind.CO to "1",
    )

    private val MAIN_CODES = mapOf(
        SensorKind.DOOR to listOf("doorcontact_state"),
        SensorKind.MOTION to listOf("pir", "presence_state"),
        SensorKind.LEAK to listOf("watersensor_state"),
        SensorKind.SMOKE to listOf("smoke_sensor_status", "smoke_sensor_state"),
        SensorKind.GAS to listOf("gas_sensor_status", "gas_sensor_state"),
        SensorKind.CO to listOf("co_status", "co_state"),
    )

    /** Druh senzora, alebo null, ak to nie je senzor. Nepotrebuje hodnoty, takže funguje aj pred prvým načítaním. */
    fun kind(d: Device): SensorKind? {
        if (d.schema.isNotEmpty()) {
            val codes = HashSet<String>()
            for (s in d.schema.values) codes.add(s.code.lowercase(Locale.ROOT))
            // poradie v CODE_KIND rozhoduje, keď má zariadenie viac druhov (napr. pohyb aj teplota: berie sa pohyb)
            for ((code, kind) in CODE_KIND) {
                if (code in codes) return kind
            }
        }
        return CATEGORY_KIND[d.category.trim().lowercase(Locale.ROOT)]
    }

    fun isSensor(d: Device): Boolean = kind(d) != null

    private fun dpOf(d: Device, vararg codes: String): String? {
        for (c in codes) Schema.dpOf(d.schema, c)?.let { return it }
        return null
    }

    private fun asDouble(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().replace(',', '.').toDoubleOrNull()
        else -> null
    }

    private fun isAlarmValue(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is String -> v.trim().lowercase(Locale.ROOT).let { it == "alarm" || it == "1" || it == "true" || it == "on" }
        is Number -> v.toInt() != 0
        else -> false
    }

    /** Teplota s ohľadom na skalu z popisu (va_temperature býva ×10); bez popisu sa berie ×10 (Zigbee senzory). */
    private fun temperatureText(d: Device, dp: String, raw: Any?): String? {
        val n = asDouble(raw) ?: return null
        val s = d.schema[dp]
        val scale = s?.scale ?: if (raw is Number && raw !is Double && raw !is Float) 1 else 0
        val unit = s?.unit?.takeIf { it.isNotBlank() } ?: "°C"
        return Schema.formatNumber(Schema.scaled(n, scale), 1) + " " + unit
    }

    private fun humidityText(d: Device, dp: String, raw: Any?): String? {
        val n = asDouble(raw) ?: return null
        val scale = d.schema[dp]?.scale ?: 0
        return Schema.formatNumber(Schema.scaled(n, scale), 0) + " %"
    }

    /** Stav batérie z hodnôt: percentá, alebo len „slabá“ (enum low/middle/high). */
    private fun battery(d: Device, values: Map<String, Any>): Pair<Int?, Boolean> {
        var percent: Int? = null
        dpOf(d, "battery_percentage", "battery")?.let { dp ->
            asDouble(values[dp])?.let { percent = it.toInt().coerceIn(0, 100) }
        }
        var low = percent?.let { it <= 15 } ?: false
        dpOf(d, "battery_state")?.let { dp ->
            val st = (values[dp] as? String)?.trim()?.lowercase(Locale.ROOT)
            if (st == "low") low = true
        }
        return percent to low
    }

    /** Hodnoty senzora pripravené na zobrazenie; pre zariadenie, ktoré nie je senzor, vráti null. */
    fun read(d: Device, values: Map<String, Any>): SensorReading? {
        val kind = kind(d) ?: return null
        val (percent, low) = battery(d, values)
        val tamper = dpOf(d, "temper_alarm", "tamper_alarm")?.let { isAlarmValue(values[it]) } ?: false
        var title = ""
        var detail = ""
        var alarm = false
        when (kind) {
            SensorKind.DOOR -> {
                val dp = dpOf(d, *MAIN_CODES.getValue(kind).toTypedArray()) ?: DEFAULT_MAIN_DP[kind]
                val v = dp?.let { values[it] }
                if (v != null) {
                    // doorcontact_state: true = otvorené
                    alarm = isAlarmValue(v)
                    title = if (alarm) "Otvorené" else "Zatvorené"
                }
            }
            SensorKind.MOTION -> {
                val dp = dpOf(d, *MAIN_CODES.getValue(kind).toTypedArray()) ?: DEFAULT_MAIN_DP[kind]
                val v = dp?.let { values[it] }
                if (v != null) {
                    // pir: „pir“ = pohyb, „none“ = bez pohybu; presence_state: „presence“ / „none“
                    val s = (v as? String)?.trim()?.lowercase(Locale.ROOT)
                    alarm = if (s != null) s == "pir" || s == "presence" || s == "motion" || s == "1" || s == "true" else isAlarmValue(v)
                    title = if (alarm) "Pohyb" else "Bez pohybu"
                }
            }
            SensorKind.CLIMATE -> {
                val tDp = dpOf(d, "va_temperature", "temp_current") ?: "1"
                val hDp = dpOf(d, "va_humidity", "humidity_value", "humidity_current") ?: "2"
                val t = temperatureText(d, tDp, values[tDp])
                val h = humidityText(d, hDp, values[hDp])
                title = t ?: h.orEmpty()
                detail = if (t != null && h != null) "Vlhkosť $h" else ""
            }
            SensorKind.LEAK, SensorKind.SMOKE, SensorKind.GAS, SensorKind.CO -> {
                val dp = dpOf(d, *MAIN_CODES.getValue(kind).toTypedArray()) ?: DEFAULT_MAIN_DP[kind]
                val v = dp?.let { values[it] }
                if (v != null) {
                    alarm = isAlarmValue(v)
                    title = if (alarm) {
                        when (kind) {
                            SensorKind.LEAK -> "Únik vody"
                            SensorKind.SMOKE -> "Dym"
                            SensorKind.GAS -> "Únik plynu"
                            else -> "Oxid uhoľnatý"
                        }
                    } else {
                        if (kind == SensorKind.LEAK) "Sucho" else "V poriadku"
                    }
                }
            }
            SensorKind.BUTTON -> {
                title = "Tlačidlo"
                detail = "Stlačenia appka zatiaľ nehlási"
            }
        }
        return SensorReading(kind, title, detail, alarm, percent, low, tamper)
    }

    /** Krátky text stavu na dlaždicu a do zoznamov: „Zatvorené · Batéria 87 %“. */
    fun summary(r: SensorReading): String {
        val parts = ArrayList<String>()
        if (r.known) parts += r.title
        if (r.detail.isNotEmpty() && r.kind == SensorKind.CLIMATE) parts += r.detail
        if (r.tamper) parts += "Kryt otvorený"
        r.batteryText.takeIf { it.isNotEmpty() }?.let { parts += it }
        return if (parts.isEmpty()) "Čakám na hodnoty" else parts.joinToString(" · ")
    }

    /** Krátky text na dlaždicu: „Zatvorené“, „21,4 °C · 48 %“; slabá batéria sa pripojí na koniec. */
    fun tileText(r: SensorReading): String {
        if (!r.known) return "Čakám na hodnoty"
        val sb = StringBuilder(r.title)
        if (r.kind == SensorKind.CLIMATE && r.detail.isNotEmpty()) sb.append(" · ").append(r.detail.removePrefix("Vlhkosť "))
        if (r.batteryLow) sb.append(" · slabá batéria")
        return sb.toString()
    }

    /** Hodnoty (DP), ktoré sa pri senzore nikdy neposielajú (celé zariadenie je len na čítanie). */
    fun readOnly(d: Device): Boolean = isSensor(d)
}
