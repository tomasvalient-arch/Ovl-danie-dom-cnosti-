package sk.domov.ovladanie

import java.util.Base64
import kotlin.math.roundToInt

/*
 * Svetlá s viacerými nezávislými zdrojmi (kanálmi), napr. stropné svetlo s hlavným bielym svetlom
 * a farebným podhľadom (BlitzWolf BW-CLT1, Ledvance Planon backlight, Annaror 5LRGB…).
 *
 * Tuya na to používa dva spôsoby:
 *  1. MIX – hodnota „mix_rgbcw“ (zvyčajne DP 51, typ raw/base64, 12 bajtov), popis podľa tuya-local
 *     (ledvance_smart_plabl100x25b.yaml, cloudybay_dualzone_ceilinglight.yaml, issue #4024):
 *       bajty 0–1  príznaky: bit 0x01 hlavné (biele) svetlo, bit 0x02 farebné svetlo (0x04 je bežne nastavený)
 *       bajty 2–3  odtieň 0–360, 4–5 sýtosť 0–1000, 6–7 jas farby 0–1000
 *       bajty 8–9  jas bieleho 0–1000, 10–11 teplota bieleho 0–1000 (0 = teplá)
 *     Hlavný vypínač (20 switch_led) zapína/vypína celé svetlo. Režim 21 (white/colour) pri takom svetle prepína,
 *     ktorý zdroj svieti – preto sa v appke zdroje navzájom vypínali.
 *  2. SWITCHES – samostatné vypínače: switch_led + switch_night_light / switch_backlight, alebo switch_led_1, _2…
 *     (rovnako ako integrácia Tuya v Home Assistant: xdd → switch_night_light, tgq/tdq → switch_led_1/2).
 *
 * Detekcia: podľa kódov z popisu hodnôt (Tuya cloud / devices.json), alebo podľa hodnôt zo zariadenia,
 * a dá sa prepísať ručne ([Device.lightRoles]). Čistý Kotlin bez Androidu (test: tools/LightChannelsTest.kt).
 */

enum class LightBackend { MIX, SWITCHES }

data class LightChannel(
    val key: String,
    val name: String,
    val main: Boolean,
    /** SWITCHES: vlastný vypínač kanála. */
    val switchDp: String? = null,
    /** MIX: bit kanála v príznakoch. */
    val mixBit: Int = 0,
    /** Režim (white/colour) v rámci kanála – len hlavné svetlo typu xdd. */
    val modeDp: String? = null,
    val brightDp: String? = null,
    val brightMin: Int = 10,
    val brightMax: Int = 1000,
    val tempDp: String? = null,
    val tempMax: Int = 1000,
    val colourDp: String? = null,
    val hasWhite: Boolean = true,
    val hasColour: Boolean = false,
) {
    /** Má jas (biely alebo farebný). */
    val hasBrightness: Boolean get() = brightDp != null || mixBit != 0 || colourDp != null
    val hasTemp: Boolean get() = (tempDp != null || mixBit == LightChannels.MIX_WHITE) && hasWhite
}

data class MultiLight(
    val backend: LightBackend,
    /** Vypínač celého svetla (MIX: 20), pri SWITCHES zvyčajne vypínač hlavného kanála. */
    val powerDp: String?,
    val mixDp: String?,
    val channels: List<LightChannel>,
    /** Pri príchode zapnúť všetky kanály (inak len hlavný). */
    val arriveAll: Boolean = false,
    /** Ďalšie čísla hodnôt, ktoré ovládanie rieši (MIX: 21–24 svetla, aby sa nezobrazovali dvakrát). */
    val extraHandled: Set<String> = emptySet(),
) {
    val main: LightChannel get() = channels.firstOrNull { it.main } ?: channels.first()
    val handled: Set<String>
        get() = buildSet {
            powerDp?.let { add(it) }
            mixDp?.let { add(it) }
            channels.forEach { c -> listOfNotNull(c.switchDp, c.modeDp, c.brightDp, c.tempDp, c.colourDp).forEach { add(it) } }
            addAll(extraHandled)
        }
    val description: String
        get() = when (backend) {
            LightBackend.MIX -> "zmiešané svetlo (DP $mixDp mix_rgbcw)"
            LightBackend.SWITCHES -> "samostatné vypínače (" + channels.joinToString { "DP ${it.switchDp}" } + ")"
        }
}

data class ChannelState(
    val on: Boolean,
    /** Jas 1–100 % (pri farebnom režime jas farby). */
    val percent: Int,
    val temp: Float,
    val colour: Hsv,
    /** Kanál práve svieti farebne (len kanál, ktorý vie bielu aj farbu). */
    val colourMode: Boolean,
)

/** Rozbalená hodnota mix_rgbcw. */
data class MixValue(val flags: Int, val h: Int, val s: Int, val v: Int, val wb: Int, val wc: Int, val hex: Boolean = false)

object LightChannels {
    const val MIX_WHITE = 0x01
    const val MIX_COLOUR = 0x02
    private const val MIX_BASE = 0x04

    /** Kódy vypínačov ďalších kanálov a ich slovenské názvy. */
    private val EXTRA_SWITCHES = listOf(
        "switch_backlight" to "Podhľad",
        "switch_night_light" to "Nočné svetlo",
        "switch_atmosphere" to "Podhľad",
        "atmosphere_switch" to "Podhľad",
        "switch_ambient" to "Podhľad",
        "ambient_switch" to "Podhľad",
        "switch_led_backlight" to "Podhľad",
        "backlight_switch" to "Podhľad",
    )

    /** Kľúče ručného nastavenia (Device.lightRoles). */
    const val ROLE_MODEL = "model" // auto | single | mix | switches
    const val ROLE_MIX = "mix"
    const val ROLE_POWER = "power"
    const val ROLE_MAIN_SWITCH = "main_switch"
    const val ROLE_BACK_SWITCH = "back_switch"
    const val ROLE_BACK_BRIGHT = "back_bright"
    const val ROLE_BACK_COLOUR = "back_colour"
    const val ROLE_ARRIVE_ALL = "arrive_all"

    // ------------------------------------------------------------ kódovanie mix_rgbcw

    fun decodeMix(raw: Any?): MixValue? {
        val text = (raw as? String)?.trim() ?: return null
        val bytes: ByteArray
        val hex: Boolean
        if (text.length == 24 && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            bytes = ByteArray(12) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            hex = true
        } else {
            bytes = try {
                Base64.getDecoder().decode(text)
            } catch (e: IllegalArgumentException) {
                return null
            }
            hex = false
        }
        if (bytes.size != 12) return null
        fun w(i: Int) = ((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff)
        return MixValue(w(0), w(2), w(4), w(6), w(8), w(10), hex)
    }

    fun encodeMix(m: MixValue): String {
        val words = intArrayOf(m.flags, m.h.coerceIn(0, 360), m.s.coerceIn(0, 1000), m.v.coerceIn(0, 1000), m.wb.coerceIn(0, 1000), m.wc.coerceIn(0, 1000))
        val bytes = ByteArray(12)
        words.forEachIndexed { i, x -> bytes[i * 2] = ((x shr 8) and 0xff).toByte(); bytes[i * 2 + 1] = (x and 0xff).toByte() }
        return if (m.hex) bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) } else Base64.getEncoder().encodeToString(bytes)
    }

    /** Aktuálna hodnota mix; ak ju zariadenie (ešte) nenahlásilo, odvodí sa z bežných hodnôt svetla 20–24. */
    fun currentMix(ml: MultiLight, values: Map<String, Any>): MixValue {
        decodeMix(values[ml.mixDp ?: ""])?.let { return it }
        val on = ml.powerDp?.let { values[it] == true } ?: false
        val mode = values["21"] as? String
        val colour = Light.parseColor(LightFormat.V2, values["24"] as? String)
        val flags = MIX_BASE or (if (on && mode != "colour") MIX_WHITE else 0) or (if (on && mode == "colour") MIX_COLOUR else 0)
        return MixValue(
            flags = flags,
            h = colour.h.roundToInt(), s = (colour.s * 1000).roundToInt(), v = (colour.v * 1000).roundToInt(),
            wb = ((values["22"] as? Number)?.toInt() ?: 1000).coerceIn(10, 1000),
            wc = ((values["23"] as? Number)?.toInt() ?: 0).coerceIn(0, 1000),
        )
    }

    // ------------------------------------------------------------ rozpoznanie

    private fun codeDp(schema: Map<String, DpSchema>, vararg codes: String): String? {
        for (c in codes) schema.entries.firstOrNull { it.value.code.equals(c, ignoreCase = true) }?.let { return it.key }
        return null
    }

    private fun roleDp(roles: Map<String, String>, key: String): String? = roles[key]?.trim()?.takeIf { it.isNotEmpty() }

    private fun isSwitch(dp: String, values: Map<String, Any>, schema: Map<String, DpSchema>): Boolean =
        values[dp] is Boolean || (values[dp] == null && schema[dp]?.type == DpType.BOOLEAN)

    /** Rozpozná svetlo s viacerými kanálmi; null = bežné svetlo (alebo iné zariadenie) – zobrazí sa ako doteraz. */
    fun detect(device: Device, values: Map<String, Any>): MultiLight? = try {
        detectInner(device.schema, values, device.lightRoles, device.labels, device.category, device.name + " " + device.model)
    } catch (e: Exception) {
        null
    }

    fun detect(
        schema: Map<String, DpSchema>,
        values: Map<String, Any>,
        roles: Map<String, String> = emptyMap(),
        category: String = "",
        name: String = "",
    ): MultiLight? = try {
        detectInner(schema, values, roles, emptyMap(), category, name)
    } catch (e: Exception) {
        null
    }

    /** Kategórie stropných a panelových svetiel (xdd = stropné, fsd = stropný ventilátor so svetlom…). */
    private val CEILING_CATEGORIES = setOf("xdd", "fsd", "fwd", "gyd", "sxd")
    private val INDEXED_LED = Regex("switch_led_(\\d)")
    private val CEILING_NAME = Regex("(?i)(clt|ceiling|strop|panel|backlight|podhľad|podhlad|ambient|atmosph|dual|annaror|planon)")

    /**
     * mix_rgbcw majú aj obyčajné RGBCW žiarovky (biela aj farba naraz v jednej žiarovke). Ako dva samostatné zdroje
     * sa preto automaticky berie len pri stropnom/panelovom svetle (kategória alebo názov), inak len ručne.
     */
    fun mixLooksLikeTwoSources(category: String, name: String): Boolean =
        category.lowercase() in CEILING_CATEGORIES || CEILING_NAME.containsMatchIn(name)

    /** Má zariadenie hodnotu mix_rgbcw (aj keď sa automaticky nepoužila)? Na ponuku „Zapnúť samostatné ovládanie“. */
    fun hasMixCapability(device: Device, values: Map<String, Any>): Boolean =
        device.schema.values.any { it.code.equals("mix_rgbcw", true) } || (decodeMix(values["51"]) != null && Light.detect(values) != null)

    private fun detectInner(
        schema: Map<String, DpSchema>,
        values: Map<String, Any>,
        roles: Map<String, String>,
        labels: Map<String, String>,
        category: String,
        name: String,
    ): MultiLight? {
        val model = roles[ROLE_MODEL]?.trim().orEmpty()
        if (model == "single") return null
        val arriveAll = roles[ROLE_ARRIVE_ALL] == "1"
        val mixAllowed = model == "mix" || roles[ROLE_MIX] != null || mixLooksLikeTwoSources(category, name)
        if (model != "switches" && mixAllowed) detectMix(schema, values, roles, model == "mix")?.let { return it.copy(arriveAll = arriveAll) }
        if (model != "mix") detectSwitches(schema, values, roles, labels)?.let { return it.copy(arriveAll = arriveAll) }
        return null
    }

    private fun detectMix(schema: Map<String, DpSchema>, values: Map<String, Any>, roles: Map<String, String>, forced: Boolean): MultiLight? {
        val mixDp = roleDp(roles, ROLE_MIX)
            ?: codeDp(schema, "mix_rgbcw")
            // bez popisu z cloudu: DP 51 s platnou 12-bajtovou hodnotou pri svetle 20/21/22
            ?: "51".takeIf { schema["51"] == null && decodeMix(values["51"]) != null && Light.detect(values)?.format == LightFormat.V2 }
            ?: "51".takeIf { forced }
            ?: return null
        if (!forced && roles[ROLE_MIX] == null && schema[mixDp] == null && decodeMix(values[mixDp]) == null) return null
        val power = roleDp(roles, ROLE_POWER) ?: codeDp(schema, "switch_led") ?: "20".takeIf { values["20"] is Boolean || schema.isEmpty() }
        val handledExtra = setOfNotNull(
            codeDp(schema, "work_mode") ?: "21".takeIf { values["21"] is String },
            codeDp(schema, "bright_value_v2", "bright_value") ?: "22".takeIf { values["22"] is Number },
            codeDp(schema, "temp_value_v2", "temp_value") ?: "23".takeIf { values["23"] is Number },
            codeDp(schema, "colour_data_v2", "colour_data") ?: "24".takeIf { values["24"] is String },
        )
        return MultiLight(
            backend = LightBackend.MIX,
            powerDp = power,
            mixDp = mixDp,
            channels = listOf(
                LightChannel("main", "Hlavné svetlo", main = true, mixBit = MIX_WHITE, hasWhite = true, hasColour = false),
                LightChannel("back", "Podhľad", main = false, mixBit = MIX_COLOUR, hasWhite = false, hasColour = true),
            ),
            extraHandled = handledExtra,
        )
    }

    private fun detectSwitches(schema: Map<String, DpSchema>, values: Map<String, Any>, roles: Map<String, String>, labels: Map<String, String>): MultiLight? {
        val channels = mutableListOf<LightChannel>()
        val single = Light.detect(values)
        val mainSwitch = roleDp(roles, ROLE_MAIN_SWITCH) ?: codeDp(schema, "switch_led") ?: single?.power
        val manualBack = roleDp(roles, ROLE_BACK_SWITCH)

        // indexované svetlá: switch_led_1, switch_led_2… (bez spoločného switch_led)
        val indexed = schema.entries.mapNotNull { (dp, s) ->
            INDEXED_LED.matchEntire(s.code)?.let { it.groupValues[1].toInt() to dp }
        }.filter { isSwitch(it.second, values, schema) }.sortedBy { it.first }

        if (mainSwitch != null && isSwitch(mainSwitch, values, schema)) {
            val spec = if (single?.power == mainSwitch) single else null
            channels += LightChannel(
                key = "main",
                name = labels[mainSwitch]?.takeIf { manualBack != null || indexed.isNotEmpty() } ?: "Hlavné svetlo",
                main = true,
                switchDp = mainSwitch,
                modeDp = spec?.mode ?: codeDp(schema, "work_mode"),
                brightDp = spec?.bright ?: codeDp(schema, "bright_value_v2", "bright_value"),
                brightMin = Bright.range(schema, spec?.bright ?: codeDp(schema, "bright_value_v2", "bright_value"))?.first ?: if (spec?.format == LightFormat.V1) 25 else 10,
                brightMax = Bright.range(schema, spec?.bright ?: codeDp(schema, "bright_value_v2", "bright_value"))?.second ?: if (spec?.format == LightFormat.V1) 255 else 1000,
                tempDp = spec?.temp ?: codeDp(schema, "temp_value_v2", "temp_value"),
                tempMax = schema[spec?.temp ?: codeDp(schema, "temp_value_v2", "temp_value") ?: ""]?.max?.toInt()?.takeIf { it > 0 } ?: if (spec?.format == LightFormat.V1) 255 else 1000,
                colourDp = (spec?.color ?: codeDp(schema, "colour_data_v2", "colour_data")).takeIf { spec == null || spec.format == LightFormat.V2 },
                hasWhite = true,
                hasColour = (spec?.color ?: codeDp(schema, "colour_data_v2", "colour_data")) != null && (spec == null || spec.format == LightFormat.V2),
            )
        }
        if (manualBack != null && manualBack != mainSwitch) {
            channels += backChannel("back", labels[manualBack] ?: "Podhľad", manualBack, roleDp(roles, ROLE_BACK_BRIGHT), roleDp(roles, ROLE_BACK_COLOUR), schema)
        } else {
            for ((code, name) in EXTRA_SWITCHES) {
                val dp = codeDp(schema, code) ?: continue
                if (dp == mainSwitch || !isSwitch(dp, values, schema) || channels.any { it.switchDp == dp }) continue
                val suffix = code.removePrefix("switch_").removeSuffix("_switch")
                channels += backChannel(
                    "back_$dp", labels[dp] ?: name, dp,
                    roleDp(roles, ROLE_BACK_BRIGHT) ?: codeDp(schema, "bright_value_$suffix", "${suffix}_bright", "bright_$suffix"),
                    roleDp(roles, ROLE_BACK_COLOUR) ?: codeDp(schema, "colour_data_$suffix", "${suffix}_colour", "colour_$suffix"),
                    schema,
                )
            }
            indexed.forEach { (n, dp) ->
                if (dp == mainSwitch || channels.any { it.switchDp == dp }) return@forEach
                val isMain = channels.isEmpty()
                channels += LightChannel(
                    key = "led_$n",
                    name = labels[dp] ?: if (isMain && indexed.size == 1) "Hlavné svetlo" else "Svetlo $n",
                    main = isMain,
                    switchDp = dp,
                    brightDp = codeDp(schema, "bright_value_$n"),
                    brightMin = schema[codeDp(schema, "bright_value_$n") ?: ""]?.min?.toInt() ?: 10,
                    brightMax = schema[codeDp(schema, "bright_value_$n") ?: ""]?.max?.toInt() ?: 1000,
                    tempDp = codeDp(schema, "temp_value_$n"),
                    colourDp = codeDp(schema, "colour_data_$n"),
                    hasWhite = true,
                    hasColour = codeDp(schema, "colour_data_$n") != null,
                )
            }
        }
        if (channels.size < 2) return null
        return MultiLight(LightBackend.SWITCHES, powerDp = channels.first { it.main }.switchDp, mixDp = null, channels = channels)
    }

    private fun backChannel(key: String, name: String, switchDp: String, bright: String?, colour: String?, schema: Map<String, DpSchema>): LightChannel {
        val bs = schema[bright ?: ""]
        return LightChannel(
            key = key, name = name, main = false, switchDp = switchDp,
            brightDp = bright, brightMin = bs?.min?.toInt() ?: 10, brightMax = bs?.max?.toInt()?.takeIf { it > 0 } ?: 1000,
            colourDp = colour, hasWhite = colour == null, hasColour = colour != null,
        )
    }

    // ------------------------------------------------------------ stav

    fun read(ml: MultiLight, ch: LightChannel, values: Map<String, Any>): ChannelState {
        if (ml.backend == LightBackend.MIX) {
            val m = currentMix(ml, values)
            val master = ml.powerDp?.let { values[it] == true } ?: true
            val on = master && (m.flags and ch.mixBit) != 0
            return if (ch.mixBit == MIX_WHITE) {
                ChannelState(on, Bright.percent(m.wb, 10, 1000), (m.wc / 1000f).coerceIn(0f, 1f), Hsv(m.h.toFloat(), m.s / 1000f, m.v / 1000f), false)
            } else {
                val hsv = Hsv(m.h.toFloat().coerceIn(0f, 360f), (m.s / 1000f).coerceIn(0f, 1f), (m.v / 1000f).coerceIn(0f, 1f))
                ChannelState(on, (hsv.v * 100).roundToInt().coerceIn(1, 100), 0f, hsv, true)
            }
        }
        val on = ch.switchDp?.let { values[it] == true } ?: false
        val mode = ch.modeDp?.let { values[it] as? String }
        val colourMode = ch.hasColour && (mode == "colour" || mode == "color" || !ch.hasWhite)
        val colour = Light.parseColor(LightFormat.V2, values[ch.colourDp ?: ""] as? String)
        val rawBright = (values[ch.brightDp ?: ""] as? Number)?.toDouble()
        val whitePercent = Bright.percent(rawBright, ch.brightMin, ch.brightMax)
        val temp = (((values[ch.tempDp ?: ""] as? Number)?.toDouble() ?: 0.0) / ch.tempMax).toFloat().coerceIn(0f, 1f)
        return ChannelState(on, if (colourMode) (colour.v * 100).roundToInt().coerceIn(1, 100) else whitePercent, temp, colour, colourMode)
    }

    fun anyOn(ml: MultiLight, values: Map<String, Any>): Boolean = ml.channels.any { read(ml, it, values).on }

    /** Krátky popis na dlaždicu: „Hlavné 80 % · Podhľad“, „Vypnuté“. */
    fun summary(ml: MultiLight, values: Map<String, Any>): String {
        val parts = ml.channels.mapNotNull { ch ->
            val st = read(ml, ch, values)
            if (!st.on) null else if (ch.main) "${shortName(ch)} ${st.percent} %" else shortName(ch)
        }
        return if (parts.isEmpty()) "Vypnuté" else "Zapnuté · " + parts.joinToString(" + ")
    }

    private fun shortName(ch: LightChannel) = if (ch.name == "Hlavné svetlo") "Hlavné" else ch.name

    // ------------------------------------------------------------ príkazy

    private fun mixCommand(ml: MultiLight, values: Map<String, Any>, change: (MixValue, Boolean) -> MixValue): Map<String, Any> {
        val cur = currentMix(ml, values)
        val master = ml.powerDp?.let { values[it] == true } ?: true
        // keď je celé svetlo vypnuté, nesvieti žiadny kanál (aj keď si príznaky pamätajú posledný stav)
        val base = if (master) cur else cur.copy(flags = (cur.flags and (MIX_WHITE or MIX_COLOUR).inv()) or MIX_BASE)
        val next = change(base, master)
        val anyOn = (next.flags and (MIX_WHITE or MIX_COLOUR)) != 0
        val out = LinkedHashMap<String, Any>()
        ml.powerDp?.let { p -> if (anyOn != master || !anyOn) out[p] = anyOn }
        ml.mixDp?.let { out[it] = encodeMix(next) }
        return out
    }

    /** Zapne alebo vypne jeden kanál (ostatné ostanú, ako sú). */
    fun setOn(ml: MultiLight, ch: LightChannel, on: Boolean, values: Map<String, Any>): Map<String, Any> {
        if (ml.backend == LightBackend.MIX) {
            return mixCommand(ml, values) { m, _ -> m.copy(flags = if (on) m.flags or ch.mixBit or MIX_BASE else m.flags and ch.mixBit.inv()) }
        }
        return ch.switchDp?.let { mapOf(it to on) } ?: emptyMap()
    }

    fun brightness(ml: MultiLight, ch: LightChannel, percent: Int, values: Map<String, Any>): Map<String, Any> {
        val p = percent.coerceIn(1, 100)
        if (ml.backend == LightBackend.MIX) {
            return mixCommand(ml, values) { m, _ ->
                if (ch.mixBit == MIX_WHITE) m.copy(flags = m.flags or MIX_WHITE, wb = Bright.raw(p, 10, 1000)) else m.copy(flags = m.flags or MIX_COLOUR, v = Bright.raw(p, 10, 1000))
            }
        }
        val st = read(ml, ch, values)
        if (st.colourMode && ch.colourDp != null) return colour(ml, ch, st.colour.copy(v = p / 100f), values)
        val dp = ch.brightDp ?: return emptyMap()
        val raw = Bright.raw(p, ch.brightMin, ch.brightMax)
        return LinkedHashMap<String, Any>().apply {
            ch.modeDp?.let { put(it, "white") }
            put(dp, raw)
        }
    }

    fun temp(ml: MultiLight, ch: LightChannel, temp: Float, values: Map<String, Any>): Map<String, Any> {
        val t = temp.coerceIn(0f, 1f)
        if (ml.backend == LightBackend.MIX) {
            return mixCommand(ml, values) { m, _ -> m.copy(flags = m.flags or MIX_WHITE, wc = (t * 1000).roundToInt()) }
        }
        val dp = ch.tempDp ?: return emptyMap()
        return LinkedHashMap<String, Any>().apply {
            ch.modeDp?.let { put(it, "white") }
            put(dp, (t * ch.tempMax).roundToInt())
        }
    }

    fun colour(ml: MultiLight, ch: LightChannel, hsv: Hsv, values: Map<String, Any>): Map<String, Any> {
        if (ml.backend == LightBackend.MIX) {
            return mixCommand(ml, values) { m, _ ->
                m.copy(flags = m.flags or MIX_COLOUR, h = hsv.h.roundToInt(), s = (hsv.s * 1000).roundToInt(), v = maxOf(10, (hsv.v * 1000).roundToInt()))
            }
        }
        val dp = ch.colourDp ?: return emptyMap()
        return LinkedHashMap<String, Any>().apply {
            ch.modeDp?.let { put(it, "colour") }
            put(dp, Light.encodeColor(LightFormat.V2, hsv))
        }
    }

    /** Prepnutie Biela / Farba v rámci kanála (len kanál s režimom). */
    fun mode(ch: LightChannel, colour: Boolean): Map<String, Any> = ch.modeDp?.let { mapOf(it to if (colour) "colour" else "white") } ?: emptyMap()

    /** Vypne všetky kanály (Všetko vypnúť, odchod, Noc). */
    fun allOff(ml: MultiLight, values: Map<String, Any>): Map<String, Any> {
        if (ml.backend == LightBackend.MIX) return mapOf((ml.powerDp ?: return mixCommand(ml, values) { m, _ -> m.copy(flags = MIX_BASE) }) to false)
        return ml.channels.mapNotNull { it.switchDp }.associateWith { false }
    }

    /** Príchod: zapne hlavné svetlo (ostatné kanály len ak je to nastavené). */
    fun arrive(ml: MultiLight, values: Map<String, Any>): Map<String, Any> {
        if (ml.backend == LightBackend.MIX) {
            val bits = if (ml.arriveAll) MIX_WHITE or MIX_COLOUR else MIX_WHITE
            return mixCommand(ml, values) { m, master -> m.copy(flags = (if (master) m.flags else MIX_BASE) or bits) }
        }
        val targets = if (ml.arriveAll) ml.channels else listOf(ml.main)
        return targets.mapNotNull { it.switchDp }.associateWith { true }
    }

    /** Noc: hlavné svetlo zapnuté, teplá biela s jasom [percent] %, ostatné kanály vypnuté. */
    fun dim(ml: MultiLight, values: Map<String, Any>, percent: Int): Map<String, Any> {
        val p = percent.coerceIn(1, 100)
        if (ml.backend == LightBackend.MIX) {
            return mixCommand(ml, values) { m, _ -> m.copy(flags = MIX_BASE or MIX_WHITE, wb = Bright.raw(p, 10, 1000), wc = 0) }
        }
        val main = ml.main
        return LinkedHashMap<String, Any>().apply {
            ml.channels.filter { !it.main }.forEach { c -> c.switchDp?.let { put(it, false) } }
            main.switchDp?.let { put(it, true) }
            main.modeDp?.let { put(it, "white") }
            main.brightDp?.let { put(it, Bright.raw(p, main.brightMin, main.brightMax)) }
            main.tempDp?.let { put(it, 0) }
        }
    }

    /**
     * DP, ktoré treba pri čítaní stavu vyžiadať zvlášť (UPDATEDPS), lebo ich zariadenie v bežnom stave neposiela –
     * zmiešaná hodnota mix_rgbcw. Pýtame sa len pri svetle s ručným/known mix alebo pri stropnom svetle.
     */
    fun hiddenDps(device: Device): List<String> = runCatching {
        val roles = device.lightRoles
        if (roles[ROLE_MODEL] == "single" || roles[ROLE_MODEL] == "switches") return@runCatching emptyList()
        val mix = roleDp(roles, ROLE_MIX) ?: codeDp(device.schema, "mix_rgbcw")
            ?: "51".takeIf { roles[ROLE_MODEL] == "mix" || mixLooksLikeTwoSources(device.category, device.name + " " + device.model) }
        // pri stropnom svetle bez popisu svetla (napr. zásuvka s názvom „strop“) sa nepýtame
        if (mix == null || (device.schema.isNotEmpty() && device.schema[mix] == null && device.schema.values.none { it.code.startsWith("switch_led") })) {
            emptyList()
        } else {
            listOf(mix)
        }
    }.getOrDefault(emptyList())

    /** Nový stav bez skrytých DP (zariadenie ich neposlalo) si ich ponechá z predchádzajúceho stavu. */
    fun keepHidden(device: Device, previous: Map<String, Any>?, fresh: Map<String, Any>): Map<String, Any> {
        if (previous.isNullOrEmpty()) return fresh
        val missing = hiddenDps(device).filter { it !in fresh && previous[it] != null }
        if (missing.isEmpty()) return fresh
        val out = fresh.toMutableMap()
        missing.forEach { out[it] = previous[it]!! }
        return out.toSortedMap(compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
    }

    /**
     * Ponúknuť „Ovládať samostatne“? Keď svetlo hlási mix_rgbcw, alebo je to stropné svetlo s režimom white/colour
     * (napr. BW-CLT1 – DP 51 je skrytý a cloud ho v popise /v1.1 neuvádza).
     */
    fun suggestMix(device: Device, values: Map<String, Any>): Boolean = runCatching {
        if (device.lightRoles[ROLE_MODEL] != null) return@runCatching false
        hasMixCapability(device, values) ||
            (mixLooksLikeTwoSources(device.category, device.name + " " + device.model) && Light.detect(values)?.let { it.format == LightFormat.V2 && it.color != null } == true)
    }.getOrDefault(false)

    /** Cloud kódy na vypnutie všetkých kanálov (odchod mimo WiFi). */
    fun offCodes(ml: MultiLight, schema: Map<String, DpSchema>): List<String> =
        if (ml.backend == LightBackend.MIX) listOfNotNull(ml.powerDp?.let { schema[it]?.code } ?: "switch_led")
        else ml.channels.mapNotNull { c -> c.switchDp?.let { schema[it]?.code } }
}

/** Vlastnosť z modelu vecí Tuya cloudu (/v2.0/cloud/thing/{id}/model) – obsahuje aj DP, ktoré v popise /v1.1 chýbajú. */
data class ThingProp(val dp: Int, val code: String, val name: String, val type: String, val accessMode: String, val spec: String)

/** Posledná hodnota z cloudu (/v2.0/cloud/thing/{id}/shadow/properties). */
data class ShadowProp(val dp: Int, val code: String, val type: String, val value: Any?)

/** Údaje z Tuya cloudu do technického info (null = cloud nie je nastavený). */
data class CloudTech(
    val props: List<ThingProp> = emptyList(),
    val shadow: List<ShadowProp> = emptyList(),
    val modelError: String? = null,
    val shadowError: String? = null,
)

/** Rozšírené technické info: všetky lokálne DP, cloud a záznam sledovania zmien. */
data class TechExtra(
    /** Všetky DP, ktoré zariadenie poslalo (stav + vyžiadanie UPDATEDPS + hlásenia). null = ešte nenačítané. */
    val localAll: Map<String, Any>? = null,
    val localNote: String? = null,
    val cloud: CloudTech? = null,
    val cloudNote: String? = null,
    val log: List<String> = emptyList(),
    /** Príkazy, ktoré appka poslala zariadeniu (denník v pamäti). */
    val commands: List<String> = emptyList(),
)

/** Spracovanie textov zo zariadenia (bez Androidu). */
object LocalDps {
    /** dps z odpovede alebo hlásenia: {"dps":{…}} alebo {"data":{"dps":{…}}}. Iné polia (devId…) sa zahodia. */
    fun parse(text: String?): Map<String, Any>? {
        if (text.isNullOrBlank()) return null
        val json = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return null
        val dps = json.optJSONObject("dps") ?: json.optJSONObject("data")?.optJSONObject("dps") ?: return null
        val out = LinkedHashMap<String, Any>()
        for (k in dps.keys()) {
            val v = dps.opt(k)
            if (v is Boolean || v is Number || v is String) out[k] = v
        }
        return out
    }

    /** Zlúči viac odpovedí (neskoršie prepíšu skoršie), zoradené podľa čísla DP. */
    fun merge(texts: List<String>): Map<String, Any> {
        val out = HashMap<String, Any>()
        texts.forEach { t -> parse(t)?.let { out.putAll(it) } }
        return out.toSortedMap(compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
    }

    /** Skupiny DP na vyžiadanie v technickom info: 1–120 po 20 a zvyšok 121–255. */
    val PROBE_GROUPS: List<List<Int>> = (1..120).chunked(20) + listOf((121..255).toList())

    /** Jeden riadok záznamu sledovania: čas a zmenené DP (bez devId a iných identifikátorov). */
    fun logLine(time: String, dps: Map<String, Any>, schema: Map<String, DpSchema>): String =
        "$time  " + dps.entries.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }.joinToString("; ") { (dp, v) ->
            "DP $dp = ${TechInfo.valueText(v)}" + (schema[dp]?.let { " [${it.code}]" } ?: "")
        }
}

/** Technický popis zariadenia na poslanie (bez local key a IP, Device ID čiastočne skryté). */
object TechInfo {
    fun maskId(id: String): String = if (id.length <= 8) "…" + id.takeLast(2) else id.take(4) + "…" + id.takeLast(4)

    internal fun valueText(v: Any?): String = when (v) {
        null -> "—"
        is String -> "\"" + v + "\"" + (LightChannels.decodeMix(v)?.let { m ->
            "  (mix: príznaky 0x%04x, H %d, S %d, V %d, biela jas %d, teplota %d)".format(m.flags, m.h, m.s, m.v, m.wb, m.wc)
        } ?: "")
        else -> v.toString()
    }

    /** Odstráni z textu Device ID, local key a IP (pre istotu aj v texte z cloudu alebo zo zariadenia). */
    fun redact(text: String, device: Device): String {
        var t = text
        if (device.devId.length > 4) t = t.replace(device.devId, maskId(device.devId))
        if (device.localKey.length >= 8) t = t.replace(device.localKey, "•••")
        if (device.ip.isNotBlank()) t = t.replace(device.ip, "x.x.x.x")
        return t
    }

    fun build(device: Device, values: Map<String, Any>?, detected: String, appVersion: String, extra: TechExtra = TechExtra()): String = redact(buildString {
        appendLine("Domov $appVersion – technické info o zariadení")
        appendLine("Názov: ${device.name}")
        appendLine("Kategória: ${device.category.ifBlank { "neznáma (bez Tuya cloudu)" }}")
        appendLine("Produkt (product_id): ${device.productId.ifBlank { "neznámy – doplní sa po Synchronizovať s Tuya cloudom" }}")
        if (device.model.isNotBlank()) appendLine("Model / názov produktu: ${device.model}")
        appendLine("Device ID: ${maskId(device.devId)}")
        appendLine("Protokol: ${device.version}")
        appendLine("Rozpoznané ako: $detected")
        if (device.powerDp.isNotBlank()) appendLine("Ručne nastavený hlavný vypínač: DP ${device.powerDp}")
        if (device.whiteSwitchDp.isNotBlank() || device.colourSwitchDp.isNotBlank()) {
            appendLine("Ručne nastavené vypínače 2 zdrojov: biele ${device.whiteSwitchDp.ifBlank { "—" }}, farebné ${device.colourSwitchDp.ifBlank { "—" }}")
        }
        if (device.lightRoles.isNotEmpty()) appendLine("Ručné nastavenie kanálov: " + device.lightRoles.entries.joinToString { "${it.key}=${it.value}" })
        appendLine()
        appendLine("Popis hodnôt (schema z Tuya cloudu):")
        if (device.schema.isEmpty()) appendLine("  (žiadny – zariadenie nebolo pridané z Tuya cloudu ani z devices.json s mappingom)")
        device.schema.entries.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }.forEach { (dp, s) ->
            val ex = buildList {
                if (s.min != null || s.max != null) add("rozsah ${s.min ?: "?"}–${s.max ?: "?"}")
                if (s.scale != 0) add("scale ${s.scale}")
                if (s.step != 1L) add("krok ${s.step}")
                if (s.unit.isNotBlank()) add("jednotka ${s.unit}")
                if (s.range.isNotEmpty()) add("možnosti ${s.range.joinToString("/")}")
            }
            appendLine("  DP $dp  ${s.code}  ${s.rawType.ifBlank { s.type.name }}" + if (ex.isEmpty()) "" else "  (" + ex.joinToString(", ") + ")")
        }
        appendLine()
        appendLine("Aktuálne hodnoty zo zariadenia:")
        if (values.isNullOrEmpty()) appendLine("  (zariadenie práve neodpovedá)")
        values?.entries?.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }?.forEach { (dp, v) ->
            appendLine("  DP $dp = ${valueText(v)}" + (device.schema[dp]?.let { "  [${it.code}]" } ?: ""))
        }

        // všetky DP, ktoré zariadenie pošle lokálne (aj mimo popisu z cloudu)
        if (extra.localAll != null || extra.localNote != null) {
            appendLine()
            appendLine("Všetky DP zo zariadenia (stav + vyžiadanie DP 1–255 cez UPDATEDPS):")
            extra.localNote?.let { appendLine("  $it") }
            extra.localAll?.entries?.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }?.forEach { (dp, v) ->
                val tag = device.schema[dp]?.let { "[${it.code}]" } ?: "[mimo popisu z cloudu]"
                appendLine("  DP $dp = ${valueText(v)}  $tag")
            }
        }

        // model vecí a posledné hodnoty z Tuya cloudu
        if (extra.cloud != null || extra.cloudNote != null) {
            appendLine()
            appendLine("Tuya cloud – úplný model zariadenia (/v2.0/cloud/thing/…/model):")
            extra.cloudNote?.let { appendLine("  $it") }
            extra.cloud?.let { c ->
                c.modelError?.let { appendLine("  chyba: $it") }
                c.props.sortedBy { it.dp }.forEach { p ->
                    val inSchema = if (device.schema.containsKey(p.dp.toString())) "" else "  [mimo popisu /v1.1]"
                    appendLine("  DP ${p.dp}  ${p.code}  ${p.type}  ${p.accessMode}  ${p.name}  ${p.spec}$inSchema")
                }
                appendLine("Tuya cloud – posledné hodnoty (/v2.0/cloud/thing/…/shadow/properties):")
                c.shadowError?.let { appendLine("  chyba: $it") }
                c.shadow.sortedBy { it.dp }.forEach { p -> appendLine("  DP ${p.dp}  ${p.code} = ${valueText(p.value)}") }
            }
        }

        if (extra.log.isNotEmpty()) {
            appendLine()
            appendLine("Sledovanie zmien (čas v telefóne):")
            extra.log.forEach { appendLine("  $it") }
        }
        appendLine()
        appendLine("Príkazy z appky, automatiky a widgetu (čas v telefóne, od spustenia appky):")
        if (extra.commands.isEmpty()) appendLine("  (zatiaľ žiadne)") else extra.commands.forEach { appendLine("  $it") }
    }.trimEnd(), device)
}
