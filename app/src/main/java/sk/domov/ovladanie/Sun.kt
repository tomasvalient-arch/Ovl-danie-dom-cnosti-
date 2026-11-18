package sk.domov.ovladanie

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan
import java.util.Locale
import java.util.TimeZone

/**
 * Východ a západ slnka počítaný lokálne (rovnice NOAA), bez internetu. Presnosť asi ±1 minúta.
 * „Západ“ = horný okraj slnka na obzore so započítaním lomu svetla (výška stredu −0,833°), ako v bežných tabuľkách.
 * Súbor nepoužíva Android, testuje sa v JVM (tools/PresenceTest.kt).
 */
object Sun {
    const val HORIZON = -0.833

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    private fun julianDay(epochMillis: Long): Double = epochMillis / 86_400_000.0 + 2440587.5

    /** Deklinácia slnka (stupne) a časová rovnica (minúty) v danom okamihu. */
    private fun position(epochMillis: Long): Pair<Double, Double> {
        val t = (julianDay(epochMillis) - 2451545.0) / 36525.0
        val l0 = ((280.46646 + t * (36000.76983 + t * 0.0003032)) % 360 + 360) % 360
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(rad(m)) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(rad(2 * m)) * (0.019993 - 0.000101 * t) + sin(rad(3 * m)) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1934.136 * t
        val lambda = trueLong - 0.00569 - 0.00478 * sin(rad(omega))
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = eps0 + 0.00256 * cos(rad(omega))
        val decl = deg(asin(sin(rad(eps)) * sin(rad(lambda))))
        val y = tan(rad(eps / 2)).let { it * it }
        val eqTime = 4 * deg(
            y * sin(2 * rad(l0)) - 2 * e * sin(rad(m)) + 4 * e * y * sin(rad(m)) * cos(2 * rad(l0)) -
                0.5 * y * y * sin(4 * rad(l0)) - 1.25 * e * e * sin(2 * rad(m)),
        )
        return decl to eqTime
    }

    /** Výška stredu slnka nad obzorom (stupne) v danom okamihu a mieste. */
    fun elevation(lat: Double, lon: Double, epochMillis: Long): Double {
        val (decl, eqTime) = position(epochMillis)
        val minutesUtc = ((epochMillis % 86_400_000L + 86_400_000L) % 86_400_000L) / 60_000.0
        val trueSolar = ((minutesUtc + eqTime + 4 * lon) % 1440 + 1440) % 1440
        val hourAngle = trueSolar / 4 - 180
        val cosZen = sin(rad(lat)) * sin(rad(decl)) + cos(rad(lat)) * cos(rad(decl)) * cos(rad(hourAngle))
        return 90 - deg(acos(cosZen.coerceIn(-1.0, 1.0)))
    }

    /** Je tma (slnko pod obzorom)? [offsetMinutes] > 0 = „tma“ už toľko minút pred západom a do toľko minút po východe. */
    fun isDark(lat: Double, lon: Double, epochMillis: Long, offsetMinutes: Int = 0): Boolean =
        elevation(lat, lon, epochMillis + offsetMinutes * 60_000L) < HORIZON ||
            elevation(lat, lon, epochMillis - offsetMinutes * 60_000L) < HORIZON

    /**
     * Východ ([rising] = true) alebo západ slnka okolo miestneho slnečného poludnia dňa, ktorý začína [dayStartUtcMillis]
     * (polnoc UTC daného dátumu). Vráti čas v ms od 1970, alebo null pri polárnom dni / noci.
     */
    fun event(lat: Double, lon: Double, dayStartUtcMillis: Long, rising: Boolean): Long? {
        // prvý odhad: slnečné poludnie dňa na danej dĺžke
        var tMillis = dayStartUtcMillis + ((720 - 4 * lon) * 60_000).toLong()
        repeat(4) {
            val (decl, eqTime) = position(tMillis)
            val cosH = (sin(rad(HORIZON)) - sin(rad(lat)) * sin(rad(decl))) / (cos(rad(lat)) * cos(rad(decl)))
            if (cosH < -1 || cosH > 1) return null
            val h = deg(acos(cosH))
            val noon = 720 - 4 * lon - eqTime
            val minutes = if (rising) noon - 4 * h else noon + 4 * h
            val next = dayStartUtcMillis + (minutes * 60_000).toLong()
            if (abs(next - tMillis) < 1000) return next
            tMillis = next
        }
        return tMillis
    }

    /** Polnoc UTC dátumu, v ktorom je daný okamih (pre [event]). */
    fun utcDayStart(epochMillis: Long): Long = floor(epochMillis / 86_400_000.0).toLong() * 86_400_000L
}

/** Odkiaľ je poloha pre výpočet slnka (v poradí presnosti). */
enum class SunSource { PHONE, HOME, WEATHER, DEFAULT }

/** Poloha, pre ktorú sa počíta východ a západ slnka; [name] = názov miesta, ak je známy. */
data class SunPlace(val lat: Double, val lon: Double, val source: SunSource, val name: String = "") {
    private fun coords() = String.format(Locale.US, "%.2f° %s, %.2f° %s", abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W")

    /** Krátky popis pre používateľa, napr. „podľa polohy telefónu – Bánovce nad Bebravou“. */
    fun label(): String = when (source) {
        SunSource.PHONE -> "podľa polohy telefónu – " + name.ifBlank { coords() }
        SunSource.HOME -> "podľa polohy domu – " + name.ifBlank { coords() }
        SunSource.WEATHER -> "podľa miesta počasia – " + name.ifBlank { coords() }
        SunSource.DEFAULT -> "podľa predvolenej polohy (stred Slovenska) – pre presný čas povoľ polohu alebo zadaj miesto"
    }
}

/**
 * Jediný zdroj času východu a západu slnka v appke (Domov, Automatika, Príchod a odchod, „len po zotmení“).
 * Výpočet NOAA ([Sun]), lom svetla −0,833°, časové pásmo s letným časom podľa dátumu udalosti.
 */
object SunTimes {
    /** Predvolená poloha, kým nie je známa žiadna iná: stred Slovenska (západ sa v rámci SR líši až o ~10 min). */
    const val DEFAULT_LAT = 48.72
    const val DEFAULT_LON = 19.70

    /** Do tejto vzdialenosti sa poloha telefónu pomenuje podľa miesta počasia (1 min času ≈ 18 km na východ/západ). */
    private const val SAME_PLACE_KM = 20.0

    private val GENERIC_NAMES = setOf("", "Poloha telefónu", "Vybrané miesto", "Domov")

    /**
     * Najpresnejšia dostupná poloha: telefón (ak má appka povolenie), potom poloha domu z nastavení automatiky,
     * potom miesto počasia, inak stred Slovenska.
     */
    fun choose(phone: Pair<Double, Double>?, homeLat: Double?, homeLon: Double?, weatherName: String?, weatherLat: Double?, weatherLon: Double?): SunPlace {
        val wName = weatherName?.trim()?.takeUnless { it in GENERIC_NAMES }.orEmpty()
        fun nameNear(lat: Double, lon: Double): String =
            if (wName.isNotEmpty() && weatherLat != null && weatherLon != null && distanceKm(lat, lon, weatherLat, weatherLon) <= SAME_PLACE_KM) wName else ""
        return when {
            phone != null -> SunPlace(phone.first, phone.second, SunSource.PHONE, nameNear(phone.first, phone.second))
            homeLat != null && homeLon != null -> SunPlace(homeLat, homeLon, SunSource.HOME, nameNear(homeLat, homeLon))
            weatherLat != null && weatherLon != null -> SunPlace(weatherLat, weatherLon, SunSource.WEATHER, wName)
            else -> SunPlace(DEFAULT_LAT, DEFAULT_LON, SunSource.DEFAULT)
        }
    }

    /**
     * Východ a západ (minúty od polnoci miestneho času) pre miestny dátum okamihu [now] v pásme [tz].
     * Posun pásma sa berie v okamihu udalosti (správne aj v deň zmeny letného času). Minúty sa nezaokrúhľujú nahor –
     * rovnako ako tabuľky (timeanddate.com).
     */
    fun day(place: SunPlace, now: Long, tz: TimeZone): Pair<Int?, Int?> {
        val dayStart = Sun.utcDayStart(now + tz.getOffset(now))
        fun local(t: Long?): Int? = t?.let { Math.floorMod(Math.floorDiv(it + tz.getOffset(it), 60_000L), 1440L).toInt() }
        return local(Sun.event(place.lat, place.lon, dayStart, true)) to local(Sun.event(place.lat, place.lon, dayStart, false))
    }

    fun isDark(place: SunPlace, now: Long): Boolean = Sun.isDark(place.lat, place.lon, now)

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 6371.0 * 2 * asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
    }
}
