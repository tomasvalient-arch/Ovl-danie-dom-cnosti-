package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Jednoduché počasie z Open-Meteo (bez kľúča) – bez Androidu, testuje sa v tools/WeatherTest.kt.
 * Predpoveď: https://api.open-meteo.com/v1/forecast, hľadanie miest: https://geocoding-api.open-meteo.com/v1/search (language=sk).
 */

/** Ikona počasia (UI ju preloží na Material Symbols). */
enum class WeatherIcon { CLEAR_DAY, CLEAR_NIGHT, PARTLY_DAY, PARTLY_NIGHT, CLOUDY, FOG, DRIZZLE, RAIN, HEAVY_RAIN, FREEZING, SNOW, STORM, UNKNOWN }

data class WeatherKind(val text: String, val icon: WeatherIcon)

/** WMO kódy počasia (Open-Meteo `weather_code`) → slovenský popis a ikona. */
object Wmo {
    fun of(code: Int?, isDay: Boolean = true): WeatherKind = when (code) {
        0 -> WeatherKind("Jasno", if (isDay) WeatherIcon.CLEAR_DAY else WeatherIcon.CLEAR_NIGHT)
        1 -> WeatherKind("Prevažne jasno", if (isDay) WeatherIcon.PARTLY_DAY else WeatherIcon.PARTLY_NIGHT)
        2 -> WeatherKind("Polojasno", if (isDay) WeatherIcon.PARTLY_DAY else WeatherIcon.PARTLY_NIGHT)
        3 -> WeatherKind("Zamračené", WeatherIcon.CLOUDY)
        45 -> WeatherKind("Hmla", WeatherIcon.FOG)
        48 -> WeatherKind("Hmla s námrazou", WeatherIcon.FOG)
        51 -> WeatherKind("Slabé mrholenie", WeatherIcon.DRIZZLE)
        53 -> WeatherKind("Mrholenie", WeatherIcon.DRIZZLE)
        55 -> WeatherKind("Silné mrholenie", WeatherIcon.DRIZZLE)
        56 -> WeatherKind("Mrznúce mrholenie", WeatherIcon.FREEZING)
        57 -> WeatherKind("Silné mrznúce mrholenie", WeatherIcon.FREEZING)
        61 -> WeatherKind("Slabý dážď", WeatherIcon.RAIN)
        63 -> WeatherKind("Dážď", WeatherIcon.RAIN)
        65 -> WeatherKind("Silný dážď", WeatherIcon.HEAVY_RAIN)
        66 -> WeatherKind("Mrznúci dážď", WeatherIcon.FREEZING)
        67 -> WeatherKind("Silný mrznúci dážď", WeatherIcon.FREEZING)
        71 -> WeatherKind("Slabé sneženie", WeatherIcon.SNOW)
        73 -> WeatherKind("Sneženie", WeatherIcon.SNOW)
        75 -> WeatherKind("Silné sneženie", WeatherIcon.SNOW)
        77 -> WeatherKind("Snehové zrná", WeatherIcon.SNOW)
        80 -> WeatherKind("Slabé prehánky", WeatherIcon.RAIN)
        81 -> WeatherKind("Prehánky", WeatherIcon.RAIN)
        82 -> WeatherKind("Silné prehánky", WeatherIcon.HEAVY_RAIN)
        85 -> WeatherKind("Snehové prehánky", WeatherIcon.SNOW)
        86 -> WeatherKind("Silné snehové prehánky", WeatherIcon.SNOW)
        95 -> WeatherKind("Búrka", WeatherIcon.STORM)
        96 -> WeatherKind("Búrka s krúpami", WeatherIcon.STORM)
        99 -> WeatherKind("Silná búrka s krúpami", WeatherIcon.STORM)
        else -> WeatherKind("Neznáme počasie", WeatherIcon.UNKNOWN)
    }
}

data class WeatherNow(val time: String, val temp: Double, val feels: Double?, val code: Int?, val isDay: Boolean, val wind: Double?)
data class WeatherDay(
    val date: String, val code: Int?, val max: Double, val min: Double, val precip: Int?,
)
data class WeatherHour(val time: String, val temp: Double, val code: Int?, val precip: Int?, val isDay: Boolean)
data class Forecast(val now: WeatherNow, val days: List<WeatherDay>, val hours: List<WeatherHour>, val timezone: String = "")

/** Miesto z hľadania. */
data class Place(val name: String, val admin: String, val country: String, val lat: Double, val lon: Double) {
    val label: String get() = listOf(name, admin, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
}

object WeatherApi {
    const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    const val GEOCODING = "https://geocoding-api.open-meteo.com/v1/search"

    private fun coord(v: Double) = String.format(Locale.US, "%.4f", v)

    fun forecastUrl(lat: Double, lon: Double): String = "$FORECAST?latitude=${coord(lat)}&longitude=${coord(lon)}" +
        "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,is_day" +
        "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
        "&timezone=auto&forecast_days=6&wind_speed_unit=kmh"

    fun searchUrl(query: String): String = "$GEOCODING?name=${URLEncoder.encode(query.trim(), "UTF-8")}&count=8&language=sk&format=json"

    private fun num(a: JSONArray?, i: Int): Double? = if (a == null || i >= a.length() || a.isNull(i)) null else a.optDouble(i).takeIf { !it.isNaN() }
    private fun int(a: JSONArray?, i: Int): Int? = num(a, i)?.roundToInt()
    private fun numOf(o: JSONObject, k: String): Double? = if (!o.has(k) || o.isNull(k)) null else o.optDouble(k).takeIf { !it.isNaN() }

    /** Rozparsuje odpoveď predpovede. Chybná odpoveď → IllegalArgumentException so slovenskou správou. */
    fun parseForecast(raw: String): Forecast {
        val o = try { JSONObject(raw) } catch (e: Exception) { throw IllegalArgumentException("Nečitateľná odpoveď servera počasia.") }
        if (o.optBoolean("error", false)) throw IllegalArgumentException("Server počasia odmietol požiadavku: ${o.optString("reason")}")
        val c = o.optJSONObject("current") ?: throw IllegalArgumentException("V odpovedi chýba aktuálne počasie.")
        val temp = numOf(c, "temperature_2m") ?: throw IllegalArgumentException("V odpovedi chýba teplota.")
        val now = WeatherNow(
            time = c.optString("time"),
            temp = temp,
            feels = numOf(c, "apparent_temperature"),
            code = numOf(c, "weather_code")?.roundToInt(),
            isDay = (numOf(c, "is_day") ?: 1.0) >= 0.5,
            wind = numOf(c, "wind_speed_10m"),
        )
        val days = ArrayList<WeatherDay>()
        o.optJSONObject("daily")?.let { d ->
            val t = d.optJSONArray("time") ?: JSONArray()
            for (i in 0 until t.length()) {
                val max = num(d.optJSONArray("temperature_2m_max"), i) ?: continue
                val min = num(d.optJSONArray("temperature_2m_min"), i) ?: continue
                days += WeatherDay(
                    t.optString(i), int(d.optJSONArray("weather_code"), i), max, min, int(d.optJSONArray("precipitation_probability_max"), i),
                )
            }
        }
        val hours = ArrayList<WeatherHour>()
        o.optJSONObject("hourly")?.let { h ->
            val t = h.optJSONArray("time") ?: JSONArray()
            for (i in 0 until t.length()) {
                val tp = num(h.optJSONArray("temperature_2m"), i) ?: continue
                hours += WeatherHour(t.optString(i), tp, int(h.optJSONArray("weather_code"), i), int(h.optJSONArray("precipitation_probability"), i),
                    (num(h.optJSONArray("is_day"), i) ?: 1.0) >= 0.5)
            }
        }
        return Forecast(now, days, hours, o.optString("timezone"))
    }

    fun parsePlaces(raw: String): List<Place> {
        val o = try { JSONObject(raw) } catch (e: Exception) { throw IllegalArgumentException("Nečitateľná odpoveď servera počasia.") }
        val a = o.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val p = a.optJSONObject(i) ?: return@mapNotNull null
            val lat = numOf(p, "latitude") ?: return@mapNotNull null
            val lon = numOf(p, "longitude") ?: return@mapNotNull null
            val name = p.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Place(name, p.optString("admin1"), p.optString("country"), lat, lon)
        }
    }
}

/** Nastavenie počasia (zálohuje sa). */
enum class WeatherSource { NONE, PHONE, CITY }

data class WeatherSettings(
    val visible: Boolean = true,
    val source: WeatherSource = WeatherSource.NONE,
    val name: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
) {
    fun toJson(): String = JSONObject().put("visible", visible).put("source", source.name).put("name", name)
        .apply { if (lat != null && lon != null) { put("lat", lat); put("lon", lon) } }.toString()

    companion object {
        fun fromJson(raw: String?): WeatherSettings {
            if (raw.isNullOrBlank()) return WeatherSettings()
            return try {
                val o = JSONObject(raw)
                WeatherSettings(
                    visible = o.optBoolean("visible", true),
                    source = runCatching { WeatherSource.valueOf(o.optString("source")) }.getOrDefault(WeatherSource.NONE),
                    name = o.optString("name"),
                    lat = if (o.has("lat")) o.optDouble("lat").takeIf { !it.isNaN() } else null,
                    lon = if (o.has("lon")) o.optDouble("lon").takeIf { !it.isNaN() } else null,
                )
            } catch (e: Exception) {
                WeatherSettings()
            }
        }
    }
}

data class WeatherLocation(val name: String, val lat: Double, val lon: Double)

/** Uložená posledná odpoveď. */
data class WeatherCache(val raw: String, val fetchedAt: Long, val lat: Double, val lon: Double) {
    fun toJson(): String = JSONObject().put("raw", raw).put("at", fetchedAt).put("lat", lat).put("lon", lon).toString()

    companion object {
        fun fromJson(s: String?): WeatherCache? = runCatching {
            val o = JSONObject(s ?: return null)
            WeatherCache(o.getString("raw"), o.getLong("at"), o.getDouble("lat"), o.getDouble("lon"))
        }.getOrNull()
    }
}

object WeatherPolicy {
    const val MAX_AGE_MS = 30 * 60_000L
    /** Aj ručné obnovenie najviac raz za minútu. */
    const val FORCE_MIN_MS = 60_000L
    /** Po neúspechu to skúsiť znova najskôr o 2 minúty (pokiaľ nejde o ručné obnovenie). */
    const val RETRY_MS = 2 * 60_000L
    /** Staršie údaje sa stále ukazujú, len s poznámkou. */
    const val STALE_MS = 6 * 3600_000L

    /** Kde ukazovať počasie: vybrané miesto, inak poloha domu z nastavenia západu slnka, inak nikde (ukáže sa nastavenie). */
    fun location(s: WeatherSettings, homeLat: Double?, homeLon: Double?): WeatherLocation? = when {
        s.source != WeatherSource.NONE && s.lat != null && s.lon != null ->
            WeatherLocation(s.name.ifBlank { if (s.source == WeatherSource.PHONE) "Poloha telefónu" else "Vybrané miesto" }, s.lat, s.lon)
        homeLat != null && homeLon != null -> WeatherLocation("Domov", homeLat, homeLon)
        else -> null
    }

    fun samePlace(c: WeatherCache, l: WeatherLocation): Boolean = abs(c.lat - l.lat) < 0.01 && abs(c.lon - l.lon) < 0.01

    /** Treba stiahnuť nové údaje? */
    fun shouldFetch(cache: WeatherCache?, loc: WeatherLocation?, now: Long, force: Boolean, lastAttempt: Long = 0L): Boolean {
        if (loc == null) return false
        if (cache == null || !samePlace(cache, loc)) return force || now - lastAttempt >= RETRY_MS || lastAttempt > now
        val age = now - cache.fetchedAt
        if (age < 0) return true // zmenený čas v telefóne
        if (force) return age >= FORCE_MIN_MS && now - lastAttempt >= FORCE_MIN_MS
        return age >= MAX_AGE_MS && (now - lastAttempt >= RETRY_MS || lastAttempt > now)
    }

    fun ageText(fetchedAt: Long, now: Long): String {
        val min = ((now - fetchedAt) / 60_000L).coerceAtLeast(0)
        return when {
            min < 1 -> "aktualizované práve teraz"
            min < 60 -> "aktualizované pred $min min"
            min < 48 * 60 -> "aktualizované pred ${min / 60} h"
            else -> "aktualizované pred ${min / 1440} dňami"
        }
    }

    fun isStale(fetchedAt: Long, now: Long) = now - fetchedAt >= STALE_MS

    private val DAYS = listOf("Po", "Ut", "St", "Št", "Pi", "So", "Ne")

    /** Skratka dňa ("Po".."Ne"); dnes = "Dnes". */
    fun dayName(date: String, today: String): String = runCatching {
        if (date == today) "Dnes" else DAYS[LocalDate.parse(date).dayOfWeek.value - 1]
    }.getOrDefault(date.takeLast(5))

    /** Dni pre pás predpovede: nasledujúcich [n] dní po dnešku (podľa dátumu v aktuálnom čase miesta). */
    fun nextDays(f: Forecast, n: Int = 5): List<WeatherDay> {
        val today = f.now.time.take(10)
        val i = f.days.indexOfFirst { it.date == today }
        return if (i >= 0) f.days.drop(i + 1).take(n) else f.days.take(n)
    }

    fun today(f: Forecast): WeatherDay? = f.days.firstOrNull { it.date == f.now.time.take(10) } ?: f.days.firstOrNull()

    /** Hodiny od aktuálnej hodiny ďalej (podľa miestneho času miesta, napr. "2026-11-16T20:15" → od 20:00). */
    fun nextHours(f: Forecast, n: Int = 12): List<WeatherHour> {
        val hour = f.now.time.take(13)
        val i = f.hours.indexOfFirst { it.time.take(13) >= hour }
        return if (i < 0) emptyList() else f.hours.drop(i).take(n)
    }

    fun temp(t: Double): String = "${t.roundToInt().let { if (it == 0) 0 else it }}°"
}
