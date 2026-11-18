package sk.domov.ovladanie

import java.time.Instant
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Fáza mesiaca počítaná lokálne (Meeus, Astronomical Algorithms, 2. vyd., vzorec 48.2), bez internetu.
 * Osvetlenie je geocentrické (poloha nie je potrebná). Presnosť typicky do 0,3 % voči efemeridám.
 * Na severnej pologuli priberá sprava. Súbor nepoužíva Android, testuje sa v JVM (tools/MoonTest.kt).
 */
data class MoonNext(val name: String, val atMillis: Long) {
    /** Napr. „Spln Ut 24. 11.“ */
    fun label(tz: TimeZone = TimeZone.getDefault()): String = "$name ${Moon.formatShort(atMillis, tz)}"
}

data class MoonInfo(
    val illumination: Double,
    val percent: Int,
    val signedPhaseAngle: Double,
    val waxing: Boolean,
    val lightOnRight: Boolean,
    val name: String,
    val next: MoonNext? = null,
) {
    /** Text do pásu: „Prvá štvrť · 51 %“. */
    val text: String get() = "$name · $percent %"
}

object Moon {
    private const val RAD = Math.PI / 180.0
    /** ΔT ≈ 72 s (TT − UTC) pre polovicu 2020-tych; na percentá nemá merateľný vplyv. */
    private const val DELTA_T_SEC = 72.0
    private val DAYS = listOf("Po", "Ut", "St", "Št", "Pi", "So", "Ne")

    private fun julianDay(epochMillis: Long): Double = epochMillis / 86_400_000.0 + 2_440_587.5

    private fun norm360(x: Double): Double {
        val r = x % 360.0
        return if (r < 0) r + 360.0 else r
    }

    private fun wrap180(x: Double): Double {
        var r = (x + 180.0) % 360.0
        if (r < 0) r += 360.0
        r -= 180.0
        return if (r == -180.0) 180.0 else r
    }

    /** Fázový uhol v stupňoch: +180 nový → +90 prvá štvrť → 0 spln → −90 posledná štvrť → −180 nový. */
    fun signedAngle(epochMillis: Long): Double {
        val jde = julianDay(epochMillis) + DELTA_T_SEC / 86_400.0
        val t = (jde - 2_451_545.0) / 36_525.0
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t2 * t2
        val d = norm360(297.8501921 + 445267.1114034 * t - 0.0018819 * t2 + t3 / 545868.0 - t4 / 113065000.0)
        val m = norm360(357.5291092 + 35999.0502909 * t - 0.0001536 * t2 + t3 / 24490000.0)
        val mp = norm360(134.9633964 + 477198.8675055 * t + 0.0087414 * t2 + t3 / 69699.0 - t4 / 14712000.0)
        return wrap180(
            180.0
                - d
                - 6.289 * sin(RAD * mp)
                + 2.100 * sin(RAD * m)
                - 1.274 * sin(RAD * (2 * d - mp))
                - 0.658 * sin(RAD * (2 * d))
                - 0.214 * sin(RAD * (2 * mp))
                - 0.110 * sin(RAD * d),
        )
    }

    fun phaseName(illumination: Double, waxing: Boolean): String {
        val k = illumination * 100.0
        return when {
            k < 2.0 -> "Nový mesiac"
            k > 98.0 -> "Spln"
            abs(k - 50.0) <= 3.0 -> if (waxing) "Prvá štvrť" else "Posledná štvrť"
            waxing && k < 50.0 -> "Priberajúci kosák"
            waxing -> "Priberajúci"
            k > 50.0 -> "Ubúdajúci"
            else -> "Ubúdajúci kosák"
        }
    }

    fun phase(epochMillis: Long, northernHemisphere: Boolean = true, includeNext: Boolean = true): MoonInfo {
        val i = signedAngle(epochMillis)
        var illumination = (1.0 + cos(RAD * i)) / 2.0
        if (illumination < 0.0) illumination = 0.0
        if (illumination > 1.0) illumination = 1.0
        val waxing = i > 0.0
        var percent = (illumination * 100.0).roundToInt()
        if (percent == 100 && illumination < 0.995) percent = 99
        if (percent == 0 && illumination > 0.005) percent = 1
        return MoonInfo(
            illumination = illumination,
            percent = percent,
            signedPhaseAngle = i,
            waxing = waxing,
            lightOnRight = if (northernHemisphere) waxing else !waxing,
            name = phaseName(illumination, waxing),
            next = if (includeNext) nextMajor(epochMillis) else null,
        )
    }

    /** Skratka dňa a dátum ako v predpovedi počasia: „Ut 24. 11.“ */
    fun formatShort(epochMillis: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val d = Instant.ofEpochMilli(epochMillis).atZone(tz.toZoneId())
        val day = DAYS[d.dayOfWeek.value - 1]
        return "$day ${d.dayOfMonth}. ${d.monthValue}."
    }

    private fun nearestMajor(i: Double): Pair<String, Double> {
        val cands = arrayOf(
            "Prvá štvrť" to abs(i - 90.0),
            "Spln" to abs(i),
            "Posledná štvrť" to abs(i + 90.0),
            "Nový mesiac" to min(abs(i - 180.0), abs(i + 180.0)),
        )
        return cands.minBy { it.second }
    }

    /**
     * Najbližšia hlavná fáza po [epochMillis] (presný okamih novu / štvrte / splnu).
     * Fáza, ktorá práve prebehla (do 20 min), sa preskočí.
     */
    fun nextMajor(epochMillis: Long): MoonNext {
        val minFuture = epochMillis + 20 * 60_000L
        val end = epochMillis + 20L * 86_400_000L
        val step = 30 * 60_000L
        var t = epochMillis + step
        var prevD = nearestMajor(signedAngle(epochMillis)).second
        while (t <= end) {
            val (name, d) = nearestMajor(signedAngle(t))
            val nextD = nearestMajor(signedAngle(t + step)).second
            if (d < 8.0 && d <= prevD && d <= nextD) {
                val refined = refine(t - 2 * step, t + 2 * step, name)
                if (refined.second > minFuture) return MoonNext(refined.first, refined.second)
            }
            prevD = d
            t += step
        }
        return MoonNext("Spln", epochMillis + (7.4 * 86_400_000L).toLong())
    }

    private fun distFor(name: String, i: Double): Double = when (name) {
        "Prvá štvrť" -> abs(i - 90.0)
        "Spln" -> abs(i)
        "Posledná štvrť" -> abs(i + 90.0)
        else -> min(abs(i - 180.0), abs(i + 180.0))
    }

    private fun refine(lo0: Long, hi0: Long, name: String): Pair<String, Long> {
        var lo = lo0
        var hi = hi0
        repeat(24) {
            val mid = (lo + hi) / 2
            val left = (lo + mid) / 2
            if (distFor(name, signedAngle(left)) < distFor(name, signedAngle(mid))) hi = mid else lo = left
        }
        var best = lo
        var bestD = distFor(name, signedAngle(lo))
        var s = lo
        while (s <= hi) {
            val d = distFor(name, signedAngle(s))
            if (d < bestD) { bestD = d; best = s }
            s += 60_000L
        }
        return name to best
    }
}
