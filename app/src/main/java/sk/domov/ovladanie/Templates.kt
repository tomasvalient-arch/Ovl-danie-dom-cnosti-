package sk.domov.ovladanie

import java.util.Locale

/** Číselná hodnota so skalou a jednotkou (meranie alebo nastavenie), napr. príkon v W so skalou 1. */
data class NumDp(
    val dp: String,
    val scale: Int = 0,
    val unit: String = "",
    val min: Long? = null,
    val max: Long? = null,
    val step: Long = 1,
) {
    val schema: DpSchema get() = DpSchema(code = "", type = DpType.INTEGER, unit = unit, min = min, max = max, scale = scale, step = step)

    /** Skutočná hodnota (po delení skalou), alebo null ak ju zariadenie neposlalo. */
    fun real(values: Map<String, Any>): Double? = (values[dp] as? Number)?.let { Schema.scaled(it, scale) }

    /** Text na zobrazenie, napr. „230,4 V“. */
    fun text(values: Map<String, Any>): String? = real(values)?.let { v ->
        val n = Schema.formatNumber(v, scale)
        if (unit.isBlank()) n else "$n $unit"
    }

    /** Príkaz: nastav skutočnú hodnotu (zaokrúhli na krok a oreže na min/max). */
    fun command(real: Double): Map<String, Any> = mapOf(dp to Schema.toRaw(schema, real))

    val realMin: Double? get() = min?.let { Schema.scaled(it, scale) }
    val realMax: Double? get() = max?.let { Schema.scaled(it, scale) }
    val realStep: Double get() = Schema.scaled(step, scale)
}

/** Výber z možností (Enum), napr. režim „auto“ / „manual“. */
data class EnumDp(val dp: String, val code: String, val options: List<String>) {
    fun label(value: String): String = Schema.enumLabel(code, value)
}

enum class TemplateKind(val title: String) {
    SOCKET("Zásuvka"),
    STRIP("Viacnásobná zásuvka / vypínač"),
    THERMOSTAT("Termostat"),
    HEATER("Ohrievač"),
    FAN("Ventilátor"),
    HUMIDIFIER("Zvlhčovač"),
    DEHUMIDIFIER("Odvlhčovač"),
    COVER("Žalúzie / rolety"),
}

/** Šablóna ovládania pre bežný typ zariadenia. [guessed] = rozpoznané len odhadom podľa čísel hodnôt. */
sealed interface DeviceTemplate {
    val kind: TemplateKind
    val guessed: Boolean
}

/** Zásuvka (aj s meraním spotreby) alebo viacnásobná zásuvka / vypínač. */
data class SocketTemplate(
    override val kind: TemplateKind,
    val switches: List<String>,
    val countdown: NumDp?,
    val power: NumDp?,
    val voltage: NumDp?,
    val current: NumDp?,
    val energy: NumDp?,
    override val guessed: Boolean = false,
) : DeviceTemplate {
    val hasMetering: Boolean get() = power != null || voltage != null || current != null || energy != null
}

/** Termostat, ohrievač, ventilátor, zvlhčovač alebo odvlhčovač. */
data class ClimateTemplate(
    override val kind: TemplateKind,
    val power: String?,
    /** Nastavená teplota alebo vlhkosť. */
    val target: NumDp?,
    /** Aktuálna teplota alebo vlhkosť. */
    val current: NumDp?,
    /** Aktuálna teplota pri zvlhčovači (ak ju hlási). */
    val currentTemp: NumDp? = null,
    val mode: EnumDp? = null,
    val speedNum: NumDp? = null,
    val speedEnum: EnumDp? = null,
    val oscillation: String? = null,
    val childLock: String? = null,
    override val guessed: Boolean = false,
) : DeviceTemplate

/** Žalúzie, rolety, závesy: otvoriť / zastaviť / zatvoriť a poloha v %. */
data class CoverTemplate(
    val control: EnumDp?,
    val position: NumDp?,
    val positionState: NumDp?,
    override val guessed: Boolean = false,
) : DeviceTemplate {
    override val kind: TemplateKind get() = TemplateKind.COVER
}

object Templates {
    private val SWITCH_CODE = Regex("^switch(_\\d+|_usb\\d*)?$", RegexOption.IGNORE_CASE)

    /**
     * Rozpozná typ zariadenia. Najprv podľa kódov zo schémy (devices.json z tinytuya) a kategórie,
     * inak odhadom podľa čísel hodnôt (bežná zásuvka s meraním, viacnásobný vypínač, rolety).
     * Svetlá a fritézu rieši Light a Fryer, preto ich volaj skôr.
     */
    fun detect(values: Map<String, Any>, schema: Map<String, DpSchema> = emptyMap(), category: String = ""): DeviceTemplate? {
        if (schema.isNotEmpty()) {
            fromSchema(values, schema, category.lowercase(Locale.ROOT))?.let { return it }
        }
        return guess(values)
    }

    // ------------------------------------------------------------ podľa schémy

    private fun fromSchema(values: Map<String, Any>, schema: Map<String, DpSchema>, category: String): DeviceTemplate? {
        fun dp(vararg codes: String): String? = codes.firstNotNullOfOrNull { Schema.dpOf(schema, it) }
        fun num(dp: String?, unit: String = "", min: Long? = null, max: Long? = null): NumDp? {
            if (dp == null) return null
            val s = schema[dp] ?: return NumDp(dp, unit = unit, min = min, max = max)
            if (s.type != DpType.INTEGER) return null
            return NumDp(dp, s.scale, s.unit.ifBlank { unit }, s.min ?: min, s.max ?: max, s.step)
        }
        fun enumOf(dp: String?): EnumDp? {
            if (dp == null) return null
            val s = schema[dp] ?: return null
            if (s.type != DpType.ENUM || s.range.isEmpty()) return null
            return EnumDp(dp, s.code, s.range)
        }
        fun bool(vararg codes: String): String? = dp(*codes)?.takeIf { schema[it]?.type == DpType.BOOLEAN }

        // žalúzie / rolety
        val control = dp("control", "mach_operate")
        val controlEnum = enumOf(control)
        val percent = dp("percent_control", "position")
        if (category in setOf("cl", "clkg", "mc") ||
            (controlEnum != null && controlEnum.options.any { it.equals("open", true) || it.equals("close", true) }) ||
            percent != null && schema[percent]?.code == "percent_control"
        ) {
            if (controlEnum != null || percent != null) {
                return CoverTemplate(
                    control = controlEnum,
                    position = num(percent, "%", 0, 100),
                    positionState = num(dp("percent_state"), "%", 0, 100),
                )
            }
        }

        val power = Schema.powerDp(schema, values)
        val mode = enumOf(dp("mode", "work_mode"))
        val childLock = bool("child_lock", "lock")

        // termostat / ohrievač
        val tempSet = dp("temp_set", "temp_set_c")
        if (tempSet != null || category in setOf("qn", "wk", "rs", "wkf")) {
            val kind = if (category == "qn" || category == "rs") TemplateKind.HEATER else TemplateKind.THERMOSTAT
            return ClimateTemplate(
                kind = kind,
                power = power,
                target = num(tempSet, "°C", 5, 35),
                current = num(dp("temp_current", "temp_current_c", "temp_indoor"), "°C"),
                mode = mode,
                speedEnum = enumOf(dp("level", "fan_speed_enum")),
                childLock = childLock,
            )
        }

        // zvlhčovač / odvlhčovač
        val dehumidSet = dp("dehumidify_set_value", "dehumidify_set_enum")
        val humSet = dp("humidity_set")
        if (category == "cs" || category == "jsq" || dehumidSet != null || humSet != null) {
            val kind = if (category == "cs" || dehumidSet != null) TemplateKind.DEHUMIDIFIER else TemplateKind.HUMIDIFIER
            val setDp = dehumidSet ?: humSet
            return ClimateTemplate(
                kind = kind,
                power = power,
                target = num(setDp, "%", 25, 80),
                current = num(dp("humidity_current", "humidity_indoor", "humidity_value"), "%"),
                currentTemp = num(dp("temp_current", "temp_indoor"), "°C"),
                mode = mode ?: enumOf(dp("spray_mode")),
                speedEnum = enumOf(dp("fan_speed_enum", "mist", "level")),
                childLock = childLock,
            )
        }

        // ventilátor
        val speed = dp("fan_speed", "fan_speed_percent", "speed")
        val speedEnum = enumOf(dp("fan_speed_enum", "level")) ?: enumOf(speed)
        if (category == "fs" || category == "fsd" || speed != null || speedEnum != null) {
            return ClimateTemplate(
                kind = TemplateKind.FAN,
                power = power ?: bool("fan_switch"),
                target = null,
                current = num(dp("temp_current"), "°C"),
                mode = mode,
                speedNum = if (speedEnum == null) num(speed, "", 1, 100) else null,
                speedEnum = speedEnum,
                oscillation = bool("switch_horizontal", "oscillation", "shake", "switch_vertical"),
                childLock = childLock,
            )
        }

        // zásuvka / vypínač
        val switches = schema.entries
            .filter { (_, s) -> s.type == DpType.BOOLEAN && SWITCH_CODE.matches(s.code) }
            .map { it.key }
            .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
        val metering = listOf("cur_power", "cur_voltage", "cur_current", "add_ele").any { Schema.dpOf(schema, it) != null }
        if (switches.isNotEmpty() && (metering || switches.size >= 2 || category in setOf("cz", "pc", "kg", "tdq"))) {
            return SocketTemplate(
                kind = if (switches.size >= 2) TemplateKind.STRIP else TemplateKind.SOCKET,
                switches = switches,
                countdown = num(dp("countdown_1", "countdown"), "s", 0, 86400),
                power = num(dp("cur_power"), "W"),
                voltage = num(dp("cur_voltage"), "V"),
                current = num(dp("cur_current"), "mA"),
                energy = num(dp("add_ele"), "kWh"),
            )
        }
        return null
    }

    // ------------------------------------------------------------ odhad podľa čísel hodnôt

    private fun guess(values: Map<String, Any>): DeviceTemplate? {
        // rolety: 1 = ovládanie (open/stop/close), 2 = poloha v %
        val c = (values["1"] as? String)?.lowercase(Locale.ROOT)
        if (c != null && c in setOf("open", "stop", "close", "continue") && values["2"] is Number) {
            return CoverTemplate(
                control = EnumDp("1", "control", listOf("open", "stop", "close")),
                position = NumDp("2", unit = "%", min = 0, max = 100),
                positionState = if (values["3"] is Number) NumDp("3", unit = "%", min = 0, max = 100) else null,
                guessed = true,
            )
        }
        if (values["1"] !is Boolean) return null

        // viac vypínačov za sebou: 1, 2, 3…
        val switches = (1..8).map { it.toString() }.takeWhile { values[it] is Boolean }
        // zásuvka s meraním (štandardná Tuya sada): 17 spotreba, 18 prúd mA, 19 príkon W/10, 20 napätie V/10
        val meter = values["18"] is Number && values["19"] is Number && values["20"] is Number
        // staršia sada: 4 prúd, 5 príkon, 6 napätie
        val meterOld = !meter && switches.size == 1 && values["4"] is Number && values["5"] is Number &&
            (values["6"] as? Number)?.toInt() in 900..2600
        if (meter || meterOld) {
            val base = if (meter) 18 else 4
            return SocketTemplate(
                kind = if (switches.size >= 2) TemplateKind.STRIP else TemplateKind.SOCKET,
                switches = switches,
                countdown = if (values["9"] is Number) NumDp("9", unit = "s", min = 0, max = 86400) else null,
                current = NumDp("$base", unit = "mA"),
                power = NumDp("${base + 1}", scale = 1, unit = "W"),
                voltage = NumDp("${base + 2}", scale = 1, unit = "V"),
                energy = if (meter && values["17"] is Number) NumDp("17", scale = 3, unit = "kWh") else null,
                guessed = true,
            )
        }
        if (switches.size >= 2) {
            return SocketTemplate(
                kind = TemplateKind.STRIP,
                switches = switches,
                countdown = null, power = null, voltage = null, current = null, energy = null,
                guessed = true,
            )
        }
        return null
    }

    // ------------------------------------------------------------ texty a bezpečnosť

    /** Krátky popis pre dlaždicu, napr. „Zapnuté · 120,5 W“. Null = použije sa bežný popis. */
    fun summary(t: DeviceTemplate, values: Map<String, Any>, powerDp: String?): String? {
        val on = powerDp?.let { values[it] == true }
        val onText = when (on) {
            true -> "Zapnuté"
            false -> "Vypnuté"
            null -> null
        }
        return when (t) {
            is SocketTemplate -> {
                if (t.switches.size >= 2) {
                    val n = t.switches.count { values[it] == true }
                    (if (n == 0) "Vypnuté · 0 z ${t.switches.size}" else "Zapnuté $n z ${t.switches.size}") +
                        (t.power?.text(values)?.let { " · $it" } ?: "")
                } else {
                    listOfNotNull(onText, t.power?.text(values)).joinToString(" · ").ifBlank { null }
                }
            }
            is ClimateTemplate -> {
                if (on == false) return "Vypnuté"
                val cur = t.current?.text(values)
                val target = t.target?.text(values)
                val detail = when {
                    cur != null && target != null -> "$cur → $target"
                    else -> cur ?: target
                }
                listOfNotNull(onText, detail).joinToString(" · ").ifBlank { null }
            }
            is CoverTemplate -> {
                val pos = (t.positionState ?: t.position)?.text(values)
                pos?.let { "Poloha $it" }
            }
        }
    }

    /**
     * Ak príkaz zapína ohrievač, vráti text potvrdenia (ohrev bez dozoru môže byť nebezpečný). Inak null.
     * Platí pre veľké tlačidlo aj ovládanie v šablóne; widget a dlažica ovládajú len hlavný vypínač ako doteraz.
     */
    fun confirmation(t: DeviceTemplate?, powerDp: String?, command: Map<String, Any>): String? {
        if (t !is ClimateTemplate || t.kind != TemplateKind.HEATER) return null
        val dp = t.power ?: powerDp ?: return null
        if (command[dp] != true) return null
        return "Ohrievač sa začne ohrievať. Zapínaj ho len keď je v miestnosti niekto, kto naň dohliadne, " +
            "a nič horľavé nie je v jeho blízkosti."
    }

    /** Ovládacie príkazy roliet: otvoriť / zastaviť / zatvoriť podľa možností, ktoré zariadenie pozná. */
    fun coverCommand(t: CoverTemplate, action: String): Map<String, Any>? {
        val control = t.control ?: return null
        val value = control.options.firstOrNull { it.equals(action, ignoreCase = true) } ?: return null
        return mapOf(control.dp to value)
    }
}
