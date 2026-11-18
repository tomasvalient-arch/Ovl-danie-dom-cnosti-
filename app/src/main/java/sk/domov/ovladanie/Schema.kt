package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToLong

/** Typ hodnoty podľa Tuya popisu (mapping z tinytuya wizardu). */
enum class DpType { BOOLEAN, INTEGER, ENUM, STRING, RAW, BITMAP, JSON, OTHER }

/**
 * Popis jednej hodnoty (DP) z Tuya cloudu: kód (napr. „cur_power“), typ, jednotka, rozsah, skala a možnosti.
 * Hodnota zo zariadenia je celé číslo; skutočná hodnota = číslo / 10^scale (napr. 2304 so scale 1 = 230,4 V).
 */
data class DpSchema(
    val code: String,
    val type: DpType,
    val unit: String = "",
    val min: Long? = null,
    val max: Long? = null,
    val scale: Int = 0,
    val step: Long = 1,
    val range: List<String> = emptyList(),
    /** Pôvodný typ a values tak, ako prišli (aby sa pri exporte nič nestratilo). */
    val rawType: String = "",
    val rawValues: String = "",
)

object Schema {
    // ------------------------------------------------------------ načítanie a uloženie (formát tinytuya)

    /**
     * Načíta „mapping“ z devices.json (tinytuya wizard, overené s 1.21), napr.
     * {"1": {"code": "switch_1", "type": "Boolean", "values": {}},
     *  "19": {"code": "cur_power", "type": "Integer", "values": {"unit": "W", "min": 0, "max": 99999, "scale": 1, "step": 1}},
     *  "4": {"code": "mode", "type": "Enum", "values": {"range": ["auto", "manual"]}}}
     * Values môže byť aj text s JSON (typ String alebo staršie verzie). Neplatné položky sa preskočia.
     */
    fun parseMapping(mapping: JSONObject?): Map<String, DpSchema> {
        if (mapping == null) return emptyMap()
        val out = HashMap<String, DpSchema>()
        for (dp in mapping.keys()) {
            val o = mapping.optJSONObject(dp) ?: continue
            val code = o.optString("code").trim()
            if (code.isEmpty()) continue
            val rawType = o.optString("type")
            val type = when (rawType.lowercase(Locale.ROOT)) {
                "boolean", "bool" -> DpType.BOOLEAN
                "integer", "value" -> DpType.INTEGER
                "enum" -> DpType.ENUM
                "string" -> DpType.STRING
                "raw" -> DpType.RAW
                "bitmap" -> DpType.BITMAP
                "json" -> DpType.JSON
                else -> DpType.OTHER
            }
            val rawValuesAny = o.opt("values")
            val values: JSONObject? = when (rawValuesAny) {
                is JSONObject -> rawValuesAny
                is String -> runCatching { JSONObject(rawValuesAny) }.getOrNull()
                else -> null
            }
            val range = values?.optJSONArray("range")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                ?: emptyList()
            out[dp] = DpSchema(
                code = code,
                type = type,
                unit = normalizeUnit(values?.optString("unit").orEmpty()),
                min = values?.optLongOrNull("min"),
                max = values?.optLongOrNull("max"),
                scale = values?.optInt("scale", 0)?.coerceIn(0, 6) ?: 0,
                step = (values?.optLongOrNull("step") ?: 1L).coerceAtLeast(1L),
                range = range,
                rawType = rawType,
                rawValues = when (rawValuesAny) {
                    null, JSONObject.NULL -> ""
                    else -> rawValuesAny.toString()
                },
            )
        }
        return out
    }

    /** Uloží schému v rovnakom formáte, aký vytvára tinytuya (export appky sa dá použiť aj v tinytuya). */
    fun toJson(schema: Map<String, DpSchema>): JSONObject {
        val o = JSONObject()
        schema.toSortedMap(compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it })).forEach { (dp, s) ->
            val item = JSONObject().put("code", s.code).put("type", s.rawType.ifBlank { s.type.name.lowercase().replaceFirstChar { it.uppercase() } })
            val values: Any = when {
                s.type == DpType.STRING -> s.rawValues
                s.rawValues.isNotBlank() -> runCatching { JSONObject(s.rawValues) }.getOrElse { s.rawValues }
                else -> JSONObject().apply {
                    if (s.unit.isNotBlank()) put("unit", s.unit)
                    s.min?.let { put("min", it) }
                    s.max?.let { put("max", it) }
                    if (s.type == DpType.INTEGER) put("scale", s.scale).put("step", s.step)
                    if (s.range.isNotEmpty()) put("range", JSONArray(s.range))
                }
            }
            item.put("values", values)
            o.put(dp, item)
        }
        return o
    }

    private fun JSONObject.optLongOrNull(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        return when (val v = opt(name)) {
            is Number -> v.toLong()
            is String -> v.trim().toDoubleOrNull()?.toLong()
            else -> null
        }
    }

    private fun normalizeUnit(unit: String): String = when (unit.trim()) {
        "℃", "c", "C" -> "°C"
        "℉", "f", "F" -> "°F"
        "秒" -> "s"
        "kwh", "KWH", "kWH" -> "kWh"
        "w" -> "W"
        "v" -> "V"
        "ma" -> "mA"
        else -> unit.trim()
    }

    // ------------------------------------------------------------ hodnoty

    /** Číslo zo zariadenia -> skutočná hodnota podľa skaly. */
    fun scaled(raw: Number, scale: Int): Double = raw.toDouble() / 10.0.pow(scale)

    /** Skutočná hodnota -> celé číslo pre zariadenie (zaokrúhlené na krok a orezané na min/max). */
    fun toRaw(s: DpSchema, real: Double): Long {
        var raw = (real * 10.0.pow(s.scale)).roundToLong()
        val base = s.min ?: 0L
        if (s.step > 1) raw = base + ((raw - base).toDouble() / s.step).roundToLong() * s.step
        s.min?.let { raw = maxOf(raw, it) }
        s.max?.let { raw = minOf(raw, it) }
        return raw
    }

    /** Číslo podľa skaly so slovenskou desatinnou čiarkou (230,4). */
    fun formatNumber(value: Double, scale: Int): String {
        val text = if (scale <= 0) value.roundToLong().toString() else String.format(Locale.ROOT, "%.${scale}f", value)
        return text.replace('.', ',')
    }

    /** Hodnota na zobrazenie: so skalou, jednotkou a preloženým textom možnosti. */
    fun format(s: DpSchema?, value: Any): String {
        if (s == null) return value.toString()
        return when {
            value is Boolean -> if (value) "Zapnuté" else "Vypnuté"
            value is Number && (s.type == DpType.INTEGER || s.scale > 0) -> {
                val num = formatNumber(scaled(value, s.scale), s.scale)
                if (s.unit.isBlank()) num else "$num ${s.unit}"
            }
            value is String && s.type == DpType.ENUM -> enumLabel(s.code, value)
            else -> value.toString()
        }
    }

    /** Hodnoty, ktoré zariadenie len hlási (meranie, stav, chyba) – v appke sa nedajú meniť. */
    fun isReadOnly(code: String): Boolean {
        val c = code.lowercase(Locale.ROOT)
        if (c in READ_ONLY) return true
        return c.startsWith("cur_") || c.startsWith("fault") || c.endsWith("_current") || c.endsWith("_state") ||
            c.startsWith("battery") || c.endsWith("_alarm") || c.startsWith("va_") ||
            c.endsWith("_left") || c.startsWith("filter_life") || c.endsWith("_indoor") || c.endsWith("_outdoor")
    }

    private val READ_ONLY = setOf(
        "add_ele", "temp_current", "humidity_current", "humidity_value", "fault", "work_state", "status",
        "percent_state", "battery", "battery_state", "battery_percentage", "countdown_left", "pm25", "co2_value",
        "voc_value", "ch2o_value", "electricity_left", "temp_current_f", "situation_set", "power_consumption",
        "total_ele", "phase_a", "phase_b", "phase_c", "remain_time", "doorcontact_state", "pir",
    )

    // ------------------------------------------------------------ názvy po slovensky

    private val CODE_LABELS = mapOf(
        "switch" to "Vypínač",
        "switch_on" to "Vypínač",
        "power" to "Vypínač",
        "switch_led" to "Svetlo",
        "led_switch" to "Svetlo",
        "light" to "Svetlo",
        "switch_backlight" to "Podsvietenie",
        "countdown" to "Odpočet (vypnutie o)",
        "countdown_1" to "Odpočet (vypnutie o)",
        "countdown_left" to "Zostávajúci čas",
        "cur_power" to "Príkon",
        "cur_voltage" to "Napätie",
        "cur_current" to "Prúd",
        "add_ele" to "Spotreba",
        "total_ele" to "Celková spotreba",
        "relay_status" to "Stav po výpadku prúdu",
        "light_mode" to "Kontrolka",
        "child_lock" to "Detská poistka",
        "temp_set" to "Nastavená teplota",
        "temp_set_f" to "Nastavená teplota (°F)",
        "temp_current" to "Aktuálna teplota",
        "temp_current_f" to "Aktuálna teplota (°F)",
        "temp_unit_convert" to "Jednotka teploty",
        "upper_temp" to "Horná hranica teploty",
        "lower_temp" to "Dolná hranica teploty",
        "temp_correction" to "Korekcia teploty",
        "mode" to "Režim",
        "work_mode" to "Režim",
        "work_state" to "Stav",
        "status" to "Stav",
        "level" to "Stupeň",
        "bright_value" to "Jas",
        "bright_value_v2" to "Jas",
        "temp_value" to "Teplota bielej",
        "temp_value_v2" to "Teplota bielej",
        "colour_data" to "Farba",
        "colour_data_v2" to "Farba",
        "scene_data" to "Scéna",
        "scene_data_v2" to "Scéna",
        "music_data" to "Hudba",
        "fan_speed" to "Rýchlosť ventilátora",
        "fan_speed_enum" to "Rýchlosť ventilátora",
        "fan_speed_percent" to "Rýchlosť ventilátora",
        "fan_direction" to "Smer otáčania",
        "fan_switch" to "Ventilátor",
        "switch_horizontal" to "Otáčanie (vodorovne)",
        "switch_vertical" to "Otáčanie (zvisle)",
        "oscillation" to "Otáčanie",
        "shake" to "Otáčanie",
        "anion" to "Ionizátor",
        "humidity_set" to "Nastavená vlhkosť",
        "humidity_current" to "Aktuálna vlhkosť",
        "humidity_indoor" to "Vlhkosť v miestnosti",
        "humidity_value" to "Vlhkosť",
        "dehumidify_set_value" to "Nastavená vlhkosť",
        "dehumidify_set_enum" to "Nastavená vlhkosť",
        "spray_mode" to "Režim rozprašovania",
        "mist" to "Hmla",
        "percent_control" to "Poloha (nastaviť)",
        "percent_state" to "Poloha",
        "position" to "Poloha",
        "control" to "Ovládanie",
        "control_back_mode" to "Opačný smer motora",
        "control_back" to "Opačný smer motora",
        "time_total" to "Čas prechodu",
        "situation_set" to "Koncová poloha",
        "fault" to "Porucha",
        "battery_percentage" to "Batéria",
        "battery_state" to "Batéria",
        "filter_life" to "Životnosť filtra",
        "filter_reset" to "Reset filtra",
        "pm25" to "PM2,5",
        "co2_value" to "CO₂",
        "sleep" to "Spánok",
        "eco" to "Úsporný režim",
        "heat" to "Kúrenie",
        "frost" to "Ochrana proti mrazu",
        "window_check" to "Detekcia otvoreného okna",
        "window_state" to "Okno",
        "valve_state" to "Ventil",
        "timer" to "Časovač",
        "cycle_time" to "Cyklický časovač",
        "random_time" to "Náhodný časovač",
        "inching" to "Impulzný režim",
        "overcharge_switch" to "Ochrana proti prebíjaniu",
        "work_power" to "Výkon",
        "remain_time" to "Zostávajúci čas",
        "lock" to "Zámok",
        "beep" to "Zvuk",
        "sound" to "Zvuk",
    )

    // regulárne výrazy sa skompilujú raz (label/enumLabel sa volajú pri každom vykreslení zoznamov)
    private val RX_SWITCH = Regex("^switch_?(\\d+)$")
    private val RX_USB = Regex("^switch_usb_?(\\d*)$")
    private val RX_COUNTDOWN = Regex("^countdown_?(\\d+)$")
    private val RX_RELAY = Regex("^relay_status_?(\\d+)$")
    private val RX_SPACES = Regex("\\s+")
    private val RX_LEVEL = Regex("^(level|gear|speed)_?(\\d+)$")
    private val RX_HOURS = Regex("^(\\d+)h$")
    private val RX_MINUTES = Regex("^(\\d+)min$")

    /** Slovenský názov podľa kódu; neznámy kód zobrazí čitateľne („light_mode2“ -> „Light mode2“). */
    fun label(code: String): String {
        val c = code.trim().lowercase(Locale.ROOT)
        CODE_LABELS[c]?.let { return it }
        RX_SWITCH.find(c)?.let { return "Vypínač ${it.groupValues[1]}" }
        RX_USB.find(c)?.let { return "USB" + it.groupValues[1].let { n -> if (n.isEmpty()) "" else " $n" } }
        RX_COUNTDOWN.find(c)?.let { return "Odpočet ${it.groupValues[1]}" }
        RX_RELAY.find(c)?.let { return "Stav po výpadku ${it.groupValues[1]}" }
        val words = code.trim().replace('_', ' ').replace(RX_SPACES, " ")
        return words.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    private val ENUM_LABELS = mapOf(
        "auto" to "Automaticky", "manual" to "Ručne", "smart" to "Inteligentný", "eco" to "Úsporný",
        "comfort" to "Komfort", "sleep" to "Spánok", "holiday" to "Dovolenka", "program" to "Program",
        "hold" to "Podržať", "temp_auto" to "Dočasne automaticky", "temporary" to "Dočasne",
        "heat" to "Kúrenie", "hot" to "Kúrenie", "cool" to "Chladenie", "cold" to "Chladenie", "fan" to "Ventilátor",
        "wind" to "Ventilátor", "dry" to "Sušenie", "dehumidify" to "Odvlhčovanie", "humidify" to "Zvlhčovanie",
        "low" to "Nízka", "mid" to "Stredná", "middle" to "Stredná", "medium" to "Stredná", "high" to "Vysoká",
        "strong" to "Silná", "turbo" to "Turbo", "quiet" to "Tichý", "mute" to "Tichý", "normal" to "Normálny",
        "nature" to "Prirodzený", "natural" to "Prirodzený", "fresh" to "Čerstvý vzduch", "baby" to "Pre deti",
        "continuous" to "Nepretržite", "interval" to "S prestávkami", "large" to "Veľa", "small" to "Málo",
        "open" to "Otvoriť", "close" to "Zatvoriť", "stop" to "Zastaviť", "continue" to "Pokračovať",
        "forward" to "Dopredu", "reverse" to "Dozadu", "back" to "Opačne",
        "on" to "Zapnuté", "off" to "Vypnuté", "power_on" to "Zapnuté", "power_off" to "Vypnuté",
        "memory" to "Posledný stav", "last" to "Posledný stav", "none" to "Nič", "relay" to "Podľa zásuvky",
        "pos" to "Ukazovateľ polohy",
        "white" to "Biela", "colour" to "Farba", "color" to "Farba", "scene" to "Scéna", "music" to "Hudba",
        "standby" to "Pohotovosť", "working" to "Pracuje", "idle" to "Nečinné", "opening" to "Otvára sa",
        "closing" to "Zatvára sa", "fully_open" to "Otvorené", "fully_close" to "Zatvorené",
        "celsius" to "°C", "fahrenheit" to "°F", "c" to "°C", "f" to "°F",
    )

    /** Preklad bežných hodnôt možností (napr. „auto“ -> „Automaticky“); neznáme ukáže tak, ako sú. */
    fun enumLabel(code: String, value: String): String {
        val v = value.trim().lowercase(Locale.ROOT)
        ENUM_LABELS[v]?.let { return it }
        RX_LEVEL.find(v)?.let { return "Stupeň ${it.groupValues[2]}" }
        RX_HOURS.find(v)?.let { return "${it.groupValues[1]} h" }
        RX_MINUTES.find(v)?.let { return "${it.groupValues[1]} min" }
        return value
    }

    /** Hlavný vypínač podľa kódov (switch, switch_1, …), ak ho schéma pozná a hodnota je zapnuté/vypnuté. */
    fun powerDp(schema: Map<String, DpSchema>, values: Map<String, Any>): String? {
        if (schema.isEmpty()) return null
        for (code in listOf("switch", "switch_on", "power", "switch_1", "switch_led", "led_switch")) {
            val dp = dpOf(schema, code) ?: continue
            if (values[dp] is Boolean) return dp
        }
        return null
    }

    /** Číslo DP s daným kódom, alebo null. */
    fun dpOf(schema: Map<String, DpSchema>, code: String): String? =
        schema.entries.firstOrNull { it.value.code.equals(code, ignoreCase = true) }?.key
}
