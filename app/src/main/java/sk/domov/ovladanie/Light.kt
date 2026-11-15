package sk.domov.ovladanie

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Svetlá od Tuye používajú dve rodiny čísel hodnôt (DP):
 *  - V2 (novšie): 20 vypínač, 21 režim, 22 jas (10–1000), 23 teplota (0–1000), 24 farba (12 hex znakov HHHHSSSSVVVV)
 *  - V1 (staršie): 1 vypínač, 2 režim, 3 jas (25–255), 4 teplota (0–255), 5 farba (14 hex znakov RRGGBBHHHHSSVV)
 * Tento súbor je čistý Kotlin bez Androidu, aby sa dal otestovať aj mimo telefónu.
 */
enum class LightFormat { V1, V2 }

data class LightSpec(
    val format: LightFormat,
    val power: String,
    val mode: String,
    val bright: String,
    val temp: String?,
    val color: String?,
) {
    /** Tieto čísla hodnôt rieši ovládanie svetla, ostatné sú v „Všetky hodnoty“. */
    val handled: Set<String> get() = setOfNotNull(power, mode, bright, temp, color)
}

/**
 * Svetlo s dvoma nezávislými zdrojmi (napr. stropné svetlo: hlavné biele + podhľadové farebné).
 * Nemá režim (hodnota 21): biele svetlo má jas (22) a teplotu (23), farebné má farbu (24),
 * ktorej jas je súčasťou farby. Vypínače jednotlivých sekcií (ak ich zariadenie má) sa nastavujú ručne.
 */
data class DualSpec(
    val bright: String,
    val temp: String,
    val colour: String,
    val whiteSwitch: String?,
    val colourSwitch: String?,
) {
    val handled: Set<String> get() = setOfNotNull(bright, temp, colour, whiteSwitch, colourSwitch)
}

data class DualState(
    val whitePercent: Int,
    val temp: Float,
    val colour: Hsv,
    /** Stav vypínača bielej sekcie, alebo null ak vypínač nie je nastavený. */
    val whiteOn: Boolean?,
    val colourOn: Boolean?,
)

/** Odtieň 0–360, sýtosť 0–1, jas 0–1. */
data class Hsv(val h: Float, val s: Float, val v: Float)

data class LightState(
    val on: Boolean,
    /** "white", "colour", "scene", "music"… */
    val mode: String,
    /** Jas bieleho svetla v percentách 1–100. */
    val whitePercent: Int,
    /** Teplota 0 (teplá) až 1 (studená). */
    val temp: Float,
    val color: Hsv,
) {
    val isColour: Boolean get() = mode == "colour" || mode == "color"
    val isWhite: Boolean get() = mode == "white"
}

object Light {
    private fun isMode(x: Any?): Boolean =
        x is String && (x == "white" || x == "colour" || x == "color" || x == "music" || x.startsWith("scene"))

    /** Pozná svetlo podľa toho, aké hodnoty zariadenie vracia. Ak nesedí ani V2 ani V1, vráti null. */
    fun detect(values: Map<String, Any>): LightSpec? {
        if (values["20"] is Boolean && isMode(values["21"]) && values["22"] is Number) {
            return LightSpec(
                format = LightFormat.V2,
                power = "20", mode = "21", bright = "22",
                temp = if (values["23"] is Number) "23" else null,
                color = if (values["24"] is String) "24" else null,
            )
        }
        if (values["1"] is Boolean && isMode(values["2"]) && values["3"] is Number) {
            return LightSpec(
                format = LightFormat.V1,
                power = "1", mode = "2", bright = "3",
                temp = if (values["4"] is Number) "4" else null,
                color = if (values["5"] is String) "5" else null,
            )
        }
        return null
    }

    /** Je zariadenie svetlo (jednoduché alebo s dvoma zdrojmi)? */
    fun isLight(values: Map<String, Any>): Boolean =
        detect(values) != null || detectDual(values, "", "") != null

    /**
     * Pozná svetlo s dvoma zdrojmi: číselný jas (22), číselná teplota (23) a farba (24) vo formáte HHHHSSSSVVVV,
     * bez režimu (21). [whiteSwitch] a [colourSwitch] sú čísla hodnôt vypínačov sekcií (prázdne = žiadny).
     */
    fun detectDual(values: Map<String, Any>, whiteSwitch: String, colourSwitch: String): DualSpec? {
        if (values["21"] is String) return null
        val colour = values["24"] as? String ?: return null
        if (values["22"] !is Number || values["23"] !is Number) return null
        if (colour.length < 12 || !colour.take(12).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return DualSpec(
            bright = "22",
            temp = "23",
            colour = "24",
            whiteSwitch = whiteSwitch.trim().ifBlank { null },
            colourSwitch = colourSwitch.trim().ifBlank { null },
        )
    }

    fun readDual(spec: DualSpec, values: Map<String, Any>): DualState {
        val percent = (((values[spec.bright] as? Number)?.toDouble() ?: 1000.0) / 10.0).roundToInt().coerceIn(1, 100)
        val temp = (((values[spec.temp] as? Number)?.toDouble() ?: 0.0) / 1000.0).toFloat().coerceIn(0f, 1f)
        val colour = parseColor(LightFormat.V2, values[spec.colour] as? String)
        return DualState(
            whitePercent = percent,
            temp = temp,
            colour = colour,
            whiteOn = spec.whiteSwitch?.let { values[it] == true },
            colourOn = spec.colourSwitch?.let { values[it] == true },
        )
    }

    fun dualWhiteBrightness(spec: DualSpec, percent: Int): Map<String, Any> =
        mapOf(spec.bright to max(10, percent.coerceIn(1, 100) * 10))

    fun dualTemp(spec: DualSpec, temp: Float): Map<String, Any> =
        mapOf(spec.temp to (temp.coerceIn(0f, 1f) * 1000f).roundToInt())

    fun dualColour(spec: DualSpec, hsv: Hsv): Map<String, Any> =
        mapOf(spec.colour to encodeColor(LightFormat.V2, hsv))

    // ------------------------------------------------------------ čítanie stavu

    fun read(spec: LightSpec, values: Map<String, Any>): LightState {
        val v2 = spec.format == LightFormat.V2
        val on = values[spec.power] == true
        val mode = (values[spec.mode] as? String) ?: "white"
        val rawBright = (values[spec.bright] as? Number)?.toDouble() ?: (if (v2) 1000.0 else 255.0)
        val percent = (if (v2) rawBright / 10.0 else rawBright * 100.0 / 255.0).roundToInt().coerceIn(1, 100)
        val rawTemp = (values[spec.temp ?: ""] as? Number)?.toDouble() ?: 0.0
        val temp = (rawTemp / (if (v2) 1000.0 else 255.0)).toFloat().coerceIn(0f, 1f)
        val color = parseColor(spec.format, values[spec.color ?: ""] as? String)
        return LightState(on, mode, percent, temp, color)
    }

    /** Krátky popis do zoznamu, napr. "Zapnuté · Biela 80 %". */
    fun summary(spec: LightSpec, values: Map<String, Any>): String {
        val s = read(spec, values)
        if (!s.on) return "Vypnuté"
        return when {
            s.isColour -> "Zapnuté · Farba ${(s.color.v * 100).roundToInt()} %"
            s.isWhite -> "Zapnuté · Biela ${s.whitePercent} %"
            s.mode == "music" -> "Zapnuté · Hudba"
            else -> "Zapnuté · Scéna"
        }
    }

    // ------------------------------------------------------------ príkazy (čo poslať zariadeniu)

    fun power(spec: LightSpec, on: Boolean): Map<String, Any> = mapOf(spec.power to on)

    fun mode(spec: LightSpec, mode: String): Map<String, Any> = mapOf(spec.mode to mode)

    /** Zmení jas bieleho svetla (1–100 %) a prepne na bielu. */
    fun whiteBrightness(spec: LightSpec, percent: Int): Map<String, Any> {
        val p = percent.coerceIn(1, 100)
        val raw = if (spec.format == LightFormat.V2) max(10, p * 10) else max(25, (p * 255.0 / 100.0).roundToInt())
        return mapOf(spec.mode to "white", spec.bright to raw)
    }

    /** Zmení teplotu bieleho svetla (0 = teplá, 1 = studená) a prepne na bielu. */
    fun whiteTemp(spec: LightSpec, temp: Float): Map<String, Any> {
        val key = spec.temp ?: return emptyMap()
        val t = temp.coerceIn(0f, 1f)
        val raw = (t * (if (spec.format == LightFormat.V2) 1000f else 255f)).roundToInt()
        return mapOf(spec.mode to "white", key to raw)
    }

    /** Nastaví farbu a prepne na farebný režim. */
    fun colour(spec: LightSpec, hsv: Hsv): Map<String, Any> {
        val key = spec.color ?: return emptyMap()
        return mapOf(spec.mode to "colour", key to encodeColor(spec.format, hsv))
    }

    // ------------------------------------------------------------ kódovanie farby

    fun encodeColor(format: LightFormat, hsv: Hsv): String {
        val h = hsv.h.coerceIn(0f, 360f).roundToInt()
        val s = hsv.s.coerceIn(0f, 1f)
        val v = hsv.v.coerceIn(0f, 1f)
        return if (format == LightFormat.V2) {
            hex(h, 4) + hex((s * 1000).roundToInt(), 4) + hex((v * 1000).roundToInt(), 4)
        } else {
            val rgb = hsvToRgb(hsv.h, s, v)
            hex(rgb[0], 2) + hex(rgb[1], 2) + hex(rgb[2], 2) +
                hex(h, 4) + hex((s * 255).roundToInt(), 2) + hex((v * 255).roundToInt(), 2)
        }
    }

    fun parseColor(format: LightFormat, text: String?): Hsv {
        val fallback = Hsv(0f, 1f, 1f)
        if (text == null) return fallback
        return try {
            if (format == LightFormat.V2 && text.length >= 12) {
                val h = text.substring(0, 4).toInt(16)
                val s = text.substring(4, 8).toInt(16)
                val v = text.substring(8, 12).toInt(16)
                Hsv(h.toFloat().coerceIn(0f, 360f), (s / 1000f).coerceIn(0f, 1f), (v / 1000f).coerceIn(0f, 1f))
            } else if (format == LightFormat.V1 && text.length >= 14) {
                val h = text.substring(6, 10).toInt(16)
                val s = text.substring(10, 12).toInt(16)
                val v = text.substring(12, 14).toInt(16)
                Hsv(h.toFloat().coerceIn(0f, 360f), (s / 255f).coerceIn(0f, 1f), (v / 255f).coerceIn(0f, 1f))
            } else {
                fallback
            }
        } catch (e: NumberFormatException) {
            fallback
        }
    }

    private fun hex(value: Int, width: Int): String =
        Integer.toHexString(value.coerceAtLeast(0)).padStart(width, '0').takeLast(width)

    /** HSV (odtieň 0–360, sýtosť a jas 0–1) na RGB 0–255. */
    fun hsvToRgb(h: Float, s: Float, v: Float): IntArray {
        val hh = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return intArrayOf(
            min(255, ((r1 + m) * 255f).roundToInt()),
            min(255, ((g1 + m) * 255f).roundToInt()),
            min(255, ((b1 + m) * 255f).roundToInt()),
        )
    }
}
