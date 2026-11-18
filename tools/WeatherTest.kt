import org.json.JSONObject
import sk.domov.ovladanie.*
import java.io.File

// Počasie: WMO kódy, parsovanie odpovede Open-Meteo (skutočná odpoveď v tools/testdata/weather), cache a obmedzenie obnovovania.

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

fun main() {
    // ---- WMO
    val codes = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)
    check("všetky WMO kódy majú slovenský text a ikonu", codes.all { Wmo.of(it).icon != WeatherIcon.UNKNOWN && Wmo.of(it).text.isNotBlank() })
    check("texty sa neopakujú", codes.map { Wmo.of(it).text }.toSet().size == codes.size)
    check("jasno cez deň / v noci", Wmo.of(0).let { it.text == "Jasno" && it.icon == WeatherIcon.CLEAR_DAY } && Wmo.of(0, isDay = false).icon == WeatherIcon.CLEAR_NIGHT)
    check("polojasno v noci", Wmo.of(2, false) == WeatherKind("Polojasno", WeatherIcon.PARTLY_NIGHT))
    check("dážď, silný dážď, búrka, sneženie, hmla", Wmo.of(61).text == "Slabý dážď" && Wmo.of(65).icon == WeatherIcon.HEAVY_RAIN &&
        Wmo.of(95).text == "Búrka" && Wmo.of(73).icon == WeatherIcon.SNOW && Wmo.of(45).icon == WeatherIcon.FOG && Wmo.of(66).icon == WeatherIcon.FREEZING)
    check("neznámy / chýbajúci kód", Wmo.of(42).icon == WeatherIcon.UNKNOWN && Wmo.of(null).text == "Neznáme počasie")

    // ---- parsovanie skutočnej odpovede
    val raw = File("tools/testdata/weather/forecast-bratislava.json").readText()
    val f = WeatherApi.parseForecast(raw)
    val src = JSONObject(raw)
    check("aktuálne: teplota, pocitová, vietor, kód, deň/noc", f.now.temp == src.getJSONObject("current").getDouble("temperature_2m") &&
        f.now.feels == src.getJSONObject("current").getDouble("apparent_temperature") && f.now.wind != null && f.now.code != null &&
        f.now.isDay == (src.getJSONObject("current").getInt("is_day") == 1))
    check("6 dní a 144 hodín", f.days.size == 6 && f.hours.size == 144 && f.timezone == "Europe/Bratislava")
    // východ a západ slnka sa z Open-Meteo už neberú (jediný zdroj je výpočet SunTimes v telefóne)
    check("adresa API nežiada sunrise/sunset", !WeatherApi.forecastUrl(48.7, 18.2).contains("sunrise"))
    check("dnes = prvý deň", WeatherPolicy.today(f)?.date == f.now.time.take(10))
    val next = WeatherPolicy.nextDays(f)
    check("pás predpovede: 5 nasledujúcich dní", next.size == 5 && next.first().date > f.now.time.take(10))
    val hrs = WeatherPolicy.nextHours(f)
    check("12 hodín od aktuálnej hodiny", hrs.size == 12 && hrs.first().time.take(13) == f.now.time.take(13))
    check("adresa API: všetky parametre, °C, km/h, auto časové pásmo", WeatherApi.forecastUrl(48.14816, 17.10674).let {
        it.startsWith("https://api.open-meteo.com/v1/forecast?latitude=48.1482&longitude=17.1067") && it.contains("timezone=auto") &&
            it.contains("daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max") && it.contains("wind_speed_unit=kmh") && !it.contains("fahrenheit")
    })
    // chýbajúce hodnoty (null) a chybné odpovede
    val partial = """{"current":{"time":"2026-11-16T10:00","temperature_2m":-0.4,"weather_code":null},
        "daily":{"time":["2026-11-16","2026-11-17"],"weather_code":[3,null],"temperature_2m_max":[2.0,null],"temperature_2m_min":[-3.0,1.0]},
        "hourly":{"time":["2026-11-16T09:00","2026-11-16T10:00","2026-11-16T11:00"],"temperature_2m":[0.1,null,0.5]}}"""
    val p = WeatherApi.parseForecast(partial)
    check("chýbajúce hodnoty sa preskočia", p.now.feels == null && p.now.code == null && p.now.isDay && p.days.size == 1 && p.hours.size == 2 && p.days[0].precip == null)
    check("-0,4 ° → 0°, -3 → -3°", WeatherPolicy.temp(-0.4) == "0°" && WeatherPolicy.temp(-3.0) == "-3°" && WeatherPolicy.temp(14.5) == "15°")
    check("hodiny po chýbajúcej hodnote", WeatherPolicy.nextHours(p).first().time == "2026-11-16T11:00")
    fun err(s: String) = runCatching { WeatherApi.parseForecast(s) }.exceptionOrNull()?.message
    check("chybná odpoveď → slovenská chyba", err("<html>") == "Nečitateľná odpoveď servera počasia." && err("{}") == "V odpovedi chýba aktuálne počasie." &&
        err("""{"error":true,"reason":"Latitude must be in range"}""")!!.startsWith("Server počasia odmietol"))

    // ---- hľadanie miest
    val places = WeatherApi.parsePlaces(File("tools/testdata/weather/geocoding-bratislava.json").readText())
    check("hľadanie: Bratislava, Bratislavský, Slovensko", places.first().label == "Bratislava, Bratislavský, Slovensko" && places.first().lat in 48.0..48.3)
    check("hľadanie: nič nenájdené", WeatherApi.parsePlaces(File("tools/testdata/weather/geocoding-none.json").readText()).isEmpty())
    check("hľadanie: adresa s diakritikou, language=sk", WeatherApi.searchUrl(" Žilina ") == "https://geocoding-api.open-meteo.com/v1/search?name=%C5%BDilina&count=8&language=sk&format=json")

    // ---- nastavenie a poloha
    check("bez nastavenia a bez polohy domu → nastaviť (žiadna Bratislava)", WeatherPolicy.location(WeatherSettings(), null, null) == null)
    check("bez nastavenia, poloha domu zo západu slnka", WeatherPolicy.location(WeatherSettings(), 48.7, 21.2) == WeatherLocation("Domov", 48.7, 21.2))
    val city = WeatherSettings(source = WeatherSource.CITY, name = "Žilina", lat = 49.22, lon = 18.74)
    check("vybrané mesto má prednosť", WeatherPolicy.location(city, 48.7, 21.2) == WeatherLocation("Žilina", 49.22, 18.74))
    check("poloha telefónu", WeatherPolicy.location(WeatherSettings(source = WeatherSource.PHONE, lat = 48.1, lon = 17.1), null, null)?.name == "Poloha telefónu")
    check("nastavenie tam a späť", WeatherSettings.fromJson(city.copy(visible = false).toJson()) == city.copy(visible = false))
    check("poškodené nastavenie → predvolené", WeatherSettings.fromJson("{x") == WeatherSettings() && WeatherSettings.fromJson(null).visible)

    // ---- cache a obmedzenie
    val loc = WeatherLocation("Žilina", 49.22, 18.74)
    val t0 = 1_800_000_000_000L
    val c = WeatherCache(raw, t0, 49.22, 18.74)
    check("cache tam a späť", WeatherCache.fromJson(c.toJson()) == c && WeatherCache.fromJson("zlé") == null)
    check("bez cache → stiahnuť", WeatherPolicy.shouldFetch(null, loc, t0, false))
    check("bez polohy → nič", !WeatherPolicy.shouldFetch(null, null, t0, true))
    check("29 min → nie, 30 min → áno", !WeatherPolicy.shouldFetch(c, loc, t0 + 29 * 60_000, false) && WeatherPolicy.shouldFetch(c, loc, t0 + 30 * 60_000, false))
    check("otvorenie appky hneď po stiahnutí → nie", !WeatherPolicy.shouldFetch(c, loc, t0 + 5_000, false))
    check("ručné obnovenie: po minúte áno, skôr nie", WeatherPolicy.shouldFetch(c, loc, t0 + 61_000, true) && !WeatherPolicy.shouldFetch(c, loc, t0 + 30_000, true))
    check("iné miesto → hneď", WeatherPolicy.shouldFetch(c, loc.copy(lat = 48.1), t0 + 5_000, false))
    check("po neúspechu nie hneď znova", !WeatherPolicy.shouldFetch(c, loc, t0 + 40 * 60_000, false, lastAttempt = t0 + 39 * 60_000) &&
        WeatherPolicy.shouldFetch(c, loc, t0 + 42 * 60_000, false, lastAttempt = t0 + 39 * 60_000))
    check("bez cache, po neúspechu 2 min pauza", !WeatherPolicy.shouldFetch(null, loc, t0 + 60_000, false, lastAttempt = t0) && WeatherPolicy.shouldFetch(null, loc, t0 + 60_000, true, lastAttempt = t0))
    check("čas v telefóne posunutý dozadu → stiahnuť", WeatherPolicy.shouldFetch(c, loc, t0 - 3600_000, false))
    check("text: práve teraz / pred 5 min / pred 2 h / pred 3 dňami", WeatherPolicy.ageText(t0, t0 + 20_000) == "aktualizované práve teraz" &&
        WeatherPolicy.ageText(t0, t0 + 5 * 60_000) == "aktualizované pred 5 min" && WeatherPolicy.ageText(t0, t0 + 2 * 3600_000 + 1) == "aktualizované pred 2 h" &&
        WeatherPolicy.ageText(t0, t0 + 3 * 86_400_000L) == "aktualizované pred 3 dňami")
    check("staré údaje po 6 h", !WeatherPolicy.isStale(t0, t0 + 5 * 3600_000) && WeatherPolicy.isStale(t0, t0 + 6 * 3600_000))
    check("dni po slovensky", WeatherPolicy.dayName("2026-11-16", "2026-11-16") == "Dnes" && WeatherPolicy.dayName("2026-11-17", "2026-11-16") == "Ut" &&
        WeatherPolicy.dayName("2026-11-19", "x") == "Št" && WeatherPolicy.dayName("2026-11-22", "x") == "Ne")

    // ---- záloha: nastavenie počasia áno, stiahnuté údaje nie; staršia záloha bez počasia
    check("záloha obsahuje nastavenie počasia, nie cache", BackupSpec.included("domov_weather", "settings") && !BackupSpec.included("domov_weather", "cache"))
    println("Weather: všetko OK")
}
