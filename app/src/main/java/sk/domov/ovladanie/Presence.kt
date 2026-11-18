package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject

/*
 * Automatika príchod / odchod podľa domácej WiFi – logika bez Androidu (testuje sa v JVM, tools/PresenceTest.kt).
 * Služba (PresenceService.kt) sem posiela udalosti „domáca sieť je / nie je“ a vykoná akcie, ktoré automat vráti.
 */

// ------------------------------------------------------------------ domáca sieť

/**
 * Odtlačok domácej WiFi. Appka nepotrebuje polohu (na čítanie názvu siete), sieť pozná podľa adresy routera
 * a rozsahu adries; že je to naozaj domov, overí tak, že jej odpovie niektoré zariadenie s jeho local key.
 * Názov siete (SSID) sa uloží, len ak ho Android appke prezradí (s povolením polohy), a vtedy sa aj porovnáva.
 */
data class HomeNetwork(
    /** IPv4 adresa routera (predvolená brána), napr. 192.168.1.1. */
    val gateway: String,
    /** Rozsah adries siete, napr. 192.168.1.0/24. */
    val subnet: String,
    val ssid: String = "",
    val dhcpServer: String = "",
) {
    fun matches(current: HomeNetwork): Boolean {
        if (gateway.isBlank() || gateway != current.gateway || subnet != current.subnet) return false
        if (ssid.isNotBlank() && current.ssid.isNotBlank() && ssid != current.ssid) return false
        return true
    }

    /** Krátky popis do nastavení. */
    val title: String get() = (if (ssid.isNotBlank()) "„$ssid“, " else "") + "router $gateway, sieť $subnet"

    /** Broadcast adresa siete: 192.168.1.0/24 -> 192.168.1.255. */
    val broadcast: String get() = broadcastOf(subnet)

    fun toJson(): JSONObject = JSONObject().put("gateway", gateway).put("subnet", subnet).put("ssid", ssid).put("dhcp", dhcpServer)

    companion object {
        fun fromJson(o: JSONObject?): HomeNetwork? {
            if (o == null) return null
            val g = o.optString("gateway")
            if (g.isBlank()) return null
            return HomeNetwork(g, o.optString("subnet"), o.optString("ssid"), o.optString("dhcp"))
        }

        /** Odstráni úvodzovky a „<unknown ssid>“, ktoré Android vracia bez povolenia polohy. */
        fun cleanSsid(raw: String?): String {
            val s = raw?.trim()?.removeSurrounding("\"").orEmpty()
            return if (s.isBlank() || s == "<unknown ssid>" || s == "0x") "" else s
        }

        fun broadcastOf(subnet: String): String {
            val (ip, pre) = subnet.split('/').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1)?.toIntOrNull() ?: -1) }
            val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4 || pre !in 0..32) return "255.255.255.255"
            val net = parts.fold(0L) { acc, p -> (acc shl 8) or (p.toLong() and 0xFF) }
            val host = if (pre == 32) 0L else (0xFFFFFFFFL ushr pre)
            val b = (net or host) and 0xFFFFFFFFL
            return "${(b shr 24) and 0xFF}.${(b shr 16) and 0xFF}.${(b shr 8) and 0xFF}.${b and 0xFF}"
        }

        /** Rozsah siete z adresy a dĺžky prefixu: 192.168.1.37/24 -> 192.168.1.0/24. */
        fun subnetOf(ipv4: String, prefix: Int): String {
            val parts = ipv4.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4 || prefix !in 0..32) return ""
            val ip = parts.fold(0L) { acc, p -> (acc shl 8) or (p.toLong() and 0xFF) }
            val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            val net = ip and mask
            return "${(net shr 24) and 0xFF}.${(net shr 16) and 0xFF}.${(net shr 8) and 0xFF}.${net and 0xFF}/$prefix"
        }
    }
}

// ------------------------------------------------------------------ nastavenia

enum class DarkMode { SUN, MANUAL }

data class PresenceConfig(
    val enabled: Boolean = false,
    val home: HomeNetwork? = null,
    /** Zariadenia zapínané pri príchode (predvolene žiadne). */
    val arriveIds: Set<String> = emptySet(),
    /** Zariadenia vypínané pri odchode; null = predvolené (všetky svetlá a zásuvky). */
    val departIds: Set<String>? = null,
    /** Koľko minút po odpojení od domácej WiFi sa vyhodnotí odchod. */
    val departDelayMin: Int = 3,
    val onlyWhenDark: Boolean = false,
    val darkMode: DarkMode = DarkMode.SUN,
    val lat: Double? = null,
    val lon: Double? = null,
    /** Ručný čas „tmy“ v minútach od polnoci (od – do, môže prechádzať cez polnoc). */
    val darkFrom: Int = 17 * 60,
    val darkTo: Int = 6 * 60 + 30,
    val notifyDone: Boolean = true,
    /** Poistka bez cloudu: zariadenia, ktorým sa kým si doma priebežne nastavuje odpočet. Predvolene vypnutá. */
    val guardEnabled: Boolean = false,
    val guardIds: Set<String> = emptySet(),
    val guardMinutes: Int = 15,
    val guardRefreshMin: Int = 5,
    /** Iný telefón domácnosti sa počíta ako „doma“, ak sa ozval najviac toľko minút pred odchodom. */
    val memberWindowMin: Int = 10,
    /** Zapnúť pri príchode aj vtedy, keď už je doma iný člen domácnosti. */
    val arriveIfSomeoneHome: Boolean = false,
) {
    val memberWindowMs: Long get() = memberWindowMin.coerceIn(1, 120) * 60_000L

    val departDelayMs: Long get() = departDelayMin.coerceIn(1, 60) * 60_000L

    /** Odpočet poistky musí byť dosť dlhší než interval obnovy, inak by svetlá zhasínali aj doma. */
    val guardSeconds: Int get() = maxOf(guardMinutes, guardRefreshMin + 5) * 60

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        home?.let { put("home", it.toJson()) }
        put("arrive", JSONArray(arriveIds.toList()))
        departIds?.let { put("depart", JSONArray(it.toList())) }
        put("delay", departDelayMin)
        put("onlyDark", onlyWhenDark)
        put("darkMode", darkMode.name)
        lat?.let { put("lat", it) }
        lon?.let { put("lon", it) }
        put("darkFrom", darkFrom)
        put("darkTo", darkTo)
        put("notify", notifyDone)
        put("guard", guardEnabled)
        put("guardIds", JSONArray(guardIds.toList()))
        put("guardMin", guardMinutes)
        put("guardRefresh", guardRefreshMin)
        put("memberWindow", memberWindowMin)
        put("arriveIfHome", arriveIfSomeoneHome)
    }

    companion object {
        private fun ids(a: JSONArray?): Set<String>? = a?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.toSet() }

        fun fromJson(text: String?): PresenceConfig {
            if (text.isNullOrBlank()) return PresenceConfig()
            return try {
                val o = JSONObject(text)
                PresenceConfig(
                    enabled = o.optBoolean("enabled", false),
                    home = HomeNetwork.fromJson(o.optJSONObject("home")),
                    arriveIds = ids(o.optJSONArray("arrive")) ?: emptySet(),
                    departIds = ids(o.optJSONArray("depart")),
                    departDelayMin = o.optInt("delay", 3).coerceIn(1, 60),
                    onlyWhenDark = o.optBoolean("onlyDark", false),
                    darkMode = runCatching { DarkMode.valueOf(o.optString("darkMode")) }.getOrDefault(DarkMode.SUN),
                    lat = if (o.has("lat")) o.optDouble("lat") else null,
                    lon = if (o.has("lon")) o.optDouble("lon") else null,
                    darkFrom = o.optInt("darkFrom", 17 * 60),
                    darkTo = o.optInt("darkTo", 6 * 60 + 30),
                    notifyDone = o.optBoolean("notify", true),
                    guardEnabled = o.optBoolean("guard", false),
                    guardIds = ids(o.optJSONArray("guardIds")) ?: emptySet(),
                    guardMinutes = o.optInt("guardMin", 15).coerceIn(5, 240),
                    guardRefreshMin = o.optInt("guardRefresh", 5).coerceIn(1, 60),
                    memberWindowMin = o.optInt("memberWindow", 10).coerceIn(1, 120),
                    arriveIfSomeoneHome = o.optBoolean("arriveIfHome", false),
                )
            } catch (e: Exception) {
                PresenceConfig()
            }
        }
    }
}

// ------------------------------------------------------------------ stavový automat

enum class PresenceState(val title: String) {
    UNKNOWN("zatiaľ neznáme"),
    HOME("doma"),
    LEAVING("odpojený od domácej WiFi, čakám"),
    AWAY("mimo domu"),
}

data class PresenceStatus(val state: PresenceState = PresenceState.UNKNOWN, val since: Long = 0L) {
    fun toJson(): JSONObject = JSONObject().put("state", state.name).put("since", since)

    companion object {
        fun fromJson(text: String?): PresenceStatus = try {
            val o = JSONObject(text ?: "")
            PresenceStatus(PresenceState.valueOf(o.optString("state")), o.optLong("since"))
        } catch (e: Exception) {
            PresenceStatus()
        }
    }
}

sealed interface PresenceEvent {
    val time: Long

    /** Telefón je na overenej domácej WiFi. */
    data class HomeSeen(override val time: Long) : PresenceEvent

    /** Telefón už nie je na domácej WiFi (odpojil sa, alebo po štarte na nej nie je). */
    data class HomeLost(override val time: Long) : PresenceEvent

    /** Pravidelná kontrola (časovač odchodu). */
    data class Tick(override val time: Long) : PresenceEvent
}

enum class PresenceAction { ARRIVE, DEPART }

data class PresenceStep(val status: PresenceStatus, val action: PresenceAction? = null)

object PresenceMachine {
    /**
     * Prechod automatu:
     * - mimo domu + domáca sieť → doma, akcia PRÍCHOD,
     * - doma + strata siete → čakanie (LEAVING),
     * - čakanie + návrat siete do [delayMs] → doma bez akcie (krátky výpadok),
     * - čakanie dlhšie než [delayMs] → mimo domu, akcia ODCHOD,
     * - neznámy stav (prvé spustenie) → doma / mimo domu bez akcie.
     */
    fun step(s: PresenceStatus, e: PresenceEvent, delayMs: Long): PresenceStep = when (e) {
        is PresenceEvent.HomeSeen -> when (s.state) {
            PresenceState.HOME -> PresenceStep(s)
            PresenceState.LEAVING -> PresenceStep(PresenceStatus(PresenceState.HOME, e.time))
            PresenceState.AWAY -> PresenceStep(PresenceStatus(PresenceState.HOME, e.time), PresenceAction.ARRIVE)
            PresenceState.UNKNOWN -> PresenceStep(PresenceStatus(PresenceState.HOME, e.time))
        }
        is PresenceEvent.HomeLost -> when (s.state) {
            PresenceState.HOME -> PresenceStep(PresenceStatus(PresenceState.LEAVING, e.time))
            PresenceState.LEAVING, PresenceState.AWAY -> PresenceStep(s)
            PresenceState.UNKNOWN -> PresenceStep(PresenceStatus(PresenceState.AWAY, e.time))
        }
        is PresenceEvent.Tick ->
            if (s.state == PresenceState.LEAVING && e.time - s.since >= delayMs)
                PresenceStep(PresenceStatus(PresenceState.AWAY, e.time), PresenceAction.DEPART)
            else PresenceStep(s)
    }

    /** Kedy treba najbližšie poslať Tick (ms od 1970), alebo null. */
    fun dueAt(s: PresenceStatus, delayMs: Long): Long? =
        if (s.state == PresenceState.LEAVING) s.since + delayMs else null
}

// ------------------------------------------------------------------ tma

object Darkness {
    /** Poloha len z nastavení automatiky (bez telefónu a počasia) – pre testy a keď nie je k dispozícii Android. */
    fun place(cfg: PresenceConfig): SunPlace = SunTimes.choose(null, cfg.lat, cfg.lon, null, null, null)

    /**
     * Je teraz „po zotmení“? Podľa slnka sa počíta pre dnešný dátum a [place] (z [SunTimes], rovnaký zdroj ako zobrazený čas).
     * [minuteOfDay] = miestny čas pre ručný rozsah.
     */
    fun isDark(cfg: PresenceConfig, nowMillis: Long, minuteOfDay: Int, place: SunPlace = place(cfg)): Boolean {
        if (cfg.darkMode == DarkMode.SUN) return SunTimes.isDark(place, nowMillis)
        return inWindow(minuteOfDay, cfg.darkFrom, cfg.darkTo)
    }

    /** Je minúta v rozsahu od – do (vrátane prechodu cez polnoc, napr. 17:00 – 6:30)? */
    fun inWindow(minute: Int, from: Int, to: Int): Boolean = when {
        from == to -> true
        from < to -> minute in from until to
        else -> minute >= from || minute < to
    }

    fun hhmm(minute: Int): String = "%d:%02d".format(((minute / 60) % 24 + 24) % 24, ((minute % 60) + 60) % 60)

    /** „17:30“ / „7.05“ / „7“ -> minúty od polnoci, alebo null. */
    fun parseHhmm(text: String): Int? {
        val t = text.trim().replace('.', ':').replace(',', ':')
        val parts = t.split(':')
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val m = if (parts.size > 1) parts[1].trim().ifEmpty { "0" }.toIntOrNull() ?: return null else 0
        if (h !in 0..23 || m !in 0..59 || parts.size > 2) return null
        return h * 60 + m
    }
}

// ------------------------------------------------------------------ zariadenia

/** Druh zariadenia z pohľadu automatiky. */
enum class AutoKind { LIGHT, SOCKET, FAN, FRYER, HEATER, THERMOSTAT, COVER, OTHER, UNKNOWN }

object AutoDevices {
    private val SWITCH_N = Regex("switch_\\d+")

    private val LIGHT_CATEGORIES = setOf("dj", "dd", "xdd", "fwd", "dc", "fsd", "tgq", "tyndj", "gyd", "sxd")
    private val SOCKET_CATEGORIES = setOf("cz", "pc", "kg", "tdq", "dlq", "wkcz")

    /** Spotrebiče, ktoré sa pri príchode nikdy neponúkajú (ohrievače, rýchlovarné kanvice, fritézy…). */
    private val HOT_CATEGORIES = setOf("qn", "rs", "bh", "dr", "mzj", "kfj", "dbl", "znrb", "kt", "wk", "wkf", "xfj_heat", "kqzg")

    /** Kategória spotrebiča, ktorý hreje (ohrievač, kanvica, fritéza…). */
    fun hotCategory(category: String): Boolean = category.trim().lowercase() in HOT_CATEGORIES

    fun kind(d: Device, values: Map<String, Any>): AutoKind {
        // fritéza aj podľa názvu/modelu (vypnutá nemusí poslať všetky hodnoty)
        if (SafeOff.looksLikeFryer(d, values)) return AutoKind.FRYER
        val cat = d.category.lowercase()
        if (cat in setOf("qn", "rs", "dr", "dbl", "bh", "mzj", "kfj", "znrb")) return AutoKind.HEATER
        if (Light.isLight(values) || cat in LIGHT_CATEGORIES) return AutoKind.LIGHT
        if (d.schema.values.any { it.code == "switch_led" || it.code == "led_switch" }) return AutoKind.LIGHT
        when (val t = Templates.detect(values, d.schema, d.category)) {
            is SocketTemplate -> return AutoKind.SOCKET
            is ClimateTemplate -> return when (t.kind) {
                TemplateKind.HEATER -> AutoKind.HEATER
                TemplateKind.THERMOSTAT -> AutoKind.THERMOSTAT
                else -> AutoKind.FAN
            }
            is CoverTemplate -> return AutoKind.COVER
            null -> {}
        }
        if (cat in SOCKET_CATEGORIES) return AutoKind.SOCKET
        if (cat in HOT_CATEGORIES) return AutoKind.HEATER
        // o zariadení appka nič nevie (nikdy neodpovedalo, nemá popis z cloudu) – môže to byť aj ohrievač
        if (values.isEmpty() && d.schema.isEmpty() && cat.isBlank()) return AutoKind.UNKNOWN
        return AutoKind.OTHER
    }

    /** Dá sa zariadenie ponúknuť na zapnutie pri príchode? Fritézy, ohrievače, termostaty, rolety a neznáme zariadenia nie. */
    fun canArrive(kind: AutoKind): Boolean = kind == AutoKind.LIGHT || kind == AutoKind.SOCKET || kind == AutoKind.FAN || kind == AutoKind.OTHER

    /** Dá sa zariadenie vypínať pri odchode (má vypínač)? */
    fun canDepart(kind: AutoKind): Boolean = kind != AutoKind.COVER

    /** Predvolene sa pri odchode vypínajú všetky svetlá a zásuvky. */
    fun defaultDepart(kind: AutoKind): Boolean = kind == AutoKind.LIGHT || kind == AutoKind.SOCKET

    fun departIds(cfg: PresenceConfig, devices: List<Device>, values: (Device) -> Map<String, Any>): Set<String> =
        cfg.departIds ?: devices.filter { defaultDepart(kind(it, values(it))) }.map { it.id }.toSet()

    /** Zariadenia na zapnutie pri príchode (len povolené druhy, aj keby bol uložený iný výber). */
    fun arriveIds(cfg: PresenceConfig, devices: List<Device>, values: (Device) -> Map<String, Any>): Set<String> =
        devices.filter { it.id in cfg.arriveIds && canArrive(kind(it, values(it))) }.map { it.id }.toSet()

    /** Lokálny príkaz na zapnutie pri príchode (hlavný vypínač), alebo null. */
    fun arriveCommand(d: Device, values: Map<String, Any>): Map<String, Any>? {
        val kind = kind(d, values)
        if (!canArrive(kind) || SafeOff.looksLikeFryer(d, values) || hotCategory(d.category)) return null
        // svetlo s podhľadom: len hlavné svetlo (podhľad len ak je to nastavené v Kanáloch svetla)
        LightChannels.detect(d, values)?.let { ml -> return LightChannels.arrive(ml, values).ifEmpty { null } }
        // ventilátor a iné zariadenia len s istým vypínačom (nie „prvá zapnutá/vypnutá hodnota“, mohol by to byť štart)
        val dp = (if (kind == AutoKind.LIGHT || kind == AutoKind.SOCKET) powerDpOf(d, values) else SafeOff.certainPower(d, values)) ?: return null
        return mapOf(dp to true)
    }

    /**
     * Cloud príkazy na vypnutie (kód hodnoty podľa popisu z Tuya cloudu). Vráti zoznam alternatív – skúšajú sa postupne,
     * kým jedna neprejde (bez popisu hodnôt appka kód len odhaduje: switch_led, switch_1, switch).
     */
    fun offCommands(d: Device, values: Map<String, Any>): List<List<Pair<String, Any>>> {
        val kind = kind(d, values)
        if (!canDepart(kind)) return emptyList()
        // fritéza: hromadne nikdy hlavný vypínač ani odhad (pozri SafeOff.cloudOff – len Stop, keď varí)
        if (SafeOff.looksLikeFryer(d, values)) return emptyList()
        val schema = d.schema
        // ohrievač, termostat, neznáme: len istý vypínač zo schémy, žiadne odhady kódov
        if (SafeOff.risky(d, values)) {
            val dp = SafeOff.certainPower(d, values) ?: Schema.powerDp(schema, values) ?: return emptyList()
            val code = schema[dp]?.code ?: return emptyList()
            return listOf(listOf(code to (false as Any)))
        }
        val out = mutableListOf<List<Pair<String, Any>>>()
        if (schema.isNotEmpty()) {
            val codes = LinkedHashSet<String>()
            val power = powerDpOf(d, values) ?: Schema.powerDp(schema, values)
            power?.let { schema[it]?.code }?.let { codes += it }
            // viacnásobná zásuvka: vypnúť všetky vypínače
            val t = Templates.detect(values, schema, d.category)
            if (t is SocketTemplate) t.switches.mapNotNull { schema[it]?.code }.forEach { codes += it }
            listOf(d.whiteSwitchDp, d.colourSwitchDp).filter { it.isNotBlank() }.mapNotNull { schema[it]?.code }.forEach { codes += it }
            LightChannels.detect(d, values)?.let { ml -> codes += LightChannels.offCodes(ml, schema) }
            if (codes.isEmpty()) {
                schema.values.filter { it.type == DpType.BOOLEAN && (it.code == "switch" || it.code.startsWith("switch_led") || it.code.matches(SWITCH_N)) }
                    .forEach { codes += it.code }
            }
            if (codes.isNotEmpty()) out += codes.map { it to (false as Any) }
        }
        val power = powerDpOf(d, values)
        val guesses = when {
            kind == AutoKind.LIGHT -> listOf("switch_led", "led_switch", "switch_1")
            power == "1" || kind == AutoKind.SOCKET -> listOf("switch_1", "switch", "switch_led")
            else -> listOf("switch", "switch_1", "switch_led")
        }
        for (g in guesses) {
            val alt = listOf(g to (false as Any))
            if (alt !in out) out += alt
        }
        return out
    }

    /** Telo cloud príkazu presne ako ho vytvorí tinytuya (json.dumps s medzerami). */
    fun commandBody(commands: List<Pair<String, Any>>): String =
        "{\"commands\": [" + commands.joinToString(", ") { (code, value) ->
            "{\"code\": " + JSONObject.quote(code) + ", \"value\": " + when (value) {
                is Boolean, is Number -> value.toString()
                else -> JSONObject.quote(value.toString())
            } + "}"
        } + "]}"

    /** Hodnota odpočtu (DP) pre poistku: číslo hodnoty a či je v minútach (inak sekundy). Null = zariadenie odpočet nemá. */
    fun countdownDp(d: Device, values: Map<String, Any>): Pair<String, Boolean>? {
        val kind = kind(d, values)
        if (kind == AutoKind.FRYER || kind == AutoKind.COVER) return null
        val schema = d.schema
        if (schema.isNotEmpty()) {
            val power = powerDpOf(d, values)?.let { schema[it]?.code }
            val preferred = when {
                power == null -> listOf("countdown", "countdown_1")
                power == "switch_led" || power == "led_switch" -> listOf("countdown", "countdown_1")
                power.startsWith("switch_") -> listOf("countdown_" + power.removePrefix("switch_"), "countdown")
                else -> listOf("countdown", "countdown_1")
            }
            for (code in preferred) {
                val e = schema.entries.firstOrNull { it.value.code == code && it.value.type == DpType.INTEGER } ?: continue
                val minutes = e.value.unit.lowercase().startsWith("min")
                return e.key to minutes
            }
            return null
        }
        if (Light.detect(values)?.format == LightFormat.V2 && values["26"] is Number) return "26" to false
        val t = Templates.detect(values, schema, d.category)
        if (t is SocketTemplate) t.countdown?.let { return it.dp to false }
        // jednoduchá zásuvka: 1 = vypínač, 9 = odpočet v sekundách
        if (kind == AutoKind.SOCKET || (values["1"] is Boolean && Light.detect(values) == null)) {
            if (values["9"] is Number) return "9" to false
        }
        return null
    }
}

// ------------------------------------------------------------------ poistka

enum class GuardStep { SET, CLEAR, NONE }

object Guard {
    /**
     * Čo urobiť pri kontrole poistky jedného zariadenia (telefón je doma):
     * - zapnuté zariadenie: nastaviť odpočet, ak ešte nebol nastavený alebo od posledného ubehol interval obnovy,
     * - vypnuté zariadenie s naším odpočtom: zrušiť ho (Tuya odpočet zariadenie prepne, vypnuté by sa po dobehnutí zaplo!),
     * - inak nič.
     */
    fun decide(isOn: Boolean?, armed: Boolean, lastSet: Long, now: Long, refreshMs: Long): GuardStep = when {
        isOn == null -> GuardStep.NONE
        isOn && (!armed || now - lastSet >= refreshMs) -> GuardStep.SET
        !isOn && armed -> GuardStep.CLEAR
        else -> GuardStep.NONE
    }

    fun command(dp: String, minutes: Boolean, seconds: Int): Map<String, Any> =
        mapOf(dp to if (minutes) (seconds + 59) / 60 else seconds)
}

/** Popis počtu zariadení: „1 svetlo“, „4 svetlá“, „5 svetiel“ / „zariadenie…“. */
fun countText(n: Int, lights: Boolean): String = if (lights) when {
    n == 1 -> "1 svetlo"
    n in 2..4 -> "$n svetlá"
    else -> "$n svetiel"
} else when {
    n == 1 -> "1 zariadenie"
    n in 2..4 -> "$n zariadenia"
    else -> "$n zariadení"
}

/** Text tichej notifikácie služby v popredí (stav + poistka). */
fun presenceServiceText(state: PresenceState, guardEnabled: Boolean): String = when (state) {
    PresenceState.HOME -> "Si doma" + if (guardEnabled) " · poistka zapnutá" else ""
    PresenceState.LEAVING -> "Odpojený od domácej WiFi – čakám, či sa vrátiš"
    PresenceState.AWAY -> "Mimo domu – pri návrate zapnem vybrané"
    PresenceState.UNKNOWN -> "Zisťujem, či si doma…"
}

/**
 * Trvalú notifikáciu služby prekreslíme len pri zmene textu.
 * Opakovaný [android.app.NotificationManager.notify] s rovnakým textom na Androide 14+ znova ukáže
 * notifikáciu, ktorú používateľ odsunul, hoci stav automatiky sa nezmenil.
 */
fun shouldPostServiceNotification(lastPosted: String?, text: String): Boolean = lastPosted != text

