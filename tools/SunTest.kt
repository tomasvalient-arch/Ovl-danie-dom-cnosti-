import sk.domov.ovladanie.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone
import kotlin.math.abs
import kotlin.system.exitProcess

private var failed = 0
private fun check(name: String, ok: Boolean) {
    println((if (ok) "OK   " else "CHYBA ") + name)
    if (!ok) failed++
}

/**
 * Referencie pre Bánovce nad Bebravou (48,72° N, 18,26° E), miestny čas Europe/Bratislava:
 *  - tad = timeanddate.com/sun/@48.72,18.26 (stiahnuté 17. 11. 2026), HH:MM
 *  - ss  = api.sunrise-sunset.org (NOAA rovnice), HH:MM:SS
 */
private data class Ref(val date: String, val tadRise: String, val tadSet: String, val ssRise: String, val ssSet: String)

private val REFS = listOf(
    Ref("2026-01-15", "07:34", "16:18", "07:34:21", "16:18:20"), // zima
    Ref("2026-03-28", "05:33", "18:11", "05:32:46", "18:11:17"), // posledný deň SEČ
    Ref("2026-03-29", "06:31", "19:13", "06:30:40", "19:12:46"), // prvý deň letného času
    Ref("2026-06-21", "04:43", "20:53", "04:43:56", "20:53:36"), // leto, slnovrat
    Ref("2026-10-24", "07:20", "17:40", "07:20:58", "17:41:19"), // posledný deň SELČ
    Ref("2026-10-25", "06:22", "16:39", "06:22:32", "16:39:32"), // späť na SEČ
    Ref("2026-11-17", "06:58", "16:04", "06:58:33", "16:05:14"), // deň nahlásenia rozdielu
    Ref("2026-12-21", "07:36", "15:53", "07:36:54", "15:53:05"), // zimný slnovrat
)

fun main() {
    val zone = ZoneId.of("Europe/Bratislava")
    val tz = TimeZone.getTimeZone(zone)
    val banovce = SunPlace(48.72, 18.26, SunSource.WEATHER, "Bánovce nad Bebravou")
    fun min(hhmm: String) = LocalTime.parse(hhmm).toSecondOfDay() / 60
    fun sec(hhmmss: String) = LocalTime.parse(hhmmss).toSecondOfDay()

    for (r in REFS) {
        // okamih počas dňa (8:00 miestneho času) – výsledok má byť pre tento miestny dátum
        val now = LocalDate.parse(r.date).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val (rise, set) = SunTimes.day(banovce, now, tz)
        check("Bánovce ${r.date} východ ${Darkness.hhmm(rise!!)} vs timeanddate ${r.tadRise} (±1 min)", abs(rise - min(r.tadRise)) <= 1)
        check("Bánovce ${r.date} západ ${Darkness.hhmm(set!!)} vs timeanddate ${r.tadSet} (±1 min)", abs(set - min(r.tadSet)) <= 1)
        // na sekundy proti sunrise-sunset.org
        val dayStart = Sun.utcDayStart(now + tz.getOffset(now))
        val e1 = Sun.event(48.72, 18.26, dayStart, true)!!
        val e2 = Sun.event(48.72, 18.26, dayStart, false)!!
        fun localSec(t: Long) = (java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalTime().toSecondOfDay())
        check("Bánovce ${r.date} východ na sekundy (±60 s od sunrise-sunset.org, rozdiel ${localSec(e1) - sec(r.ssRise)} s)", abs(localSec(e1) - sec(r.ssRise)) <= 60)
        check("Bánovce ${r.date} západ na sekundy (±60 s od sunrise-sunset.org, rozdiel ${localSec(e2) - sec(r.ssSet)} s)", abs(localSec(e2) - sec(r.ssSet)) <= 60)
    }

    // príčina pôvodného rozdielu: predvolená poloha (stred SR, 19,70° E) dáva západ o ~6 min skôr
    val nov17 = LocalDate.parse("2026-11-17").atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
    val center = SunTimes.day(SunTimes.choose(null, null, null, null, null, null), nov17, tz).second!!
    check("stred SR 17.11. západ ${Darkness.hhmm(center)} (pôvodných 15:59)", center == 15 * 60 + 59)

    // výber polohy
    val w = Triple("Bánovce nad Bebravou", 48.72, 18.26)
    val phoneNear = SunTimes.choose(48.71 to 18.25, null, null, w.first, w.second, w.third)
    check("telefón má prednosť a blízko miesta počasia dostane jeho názov", phoneNear.source == SunSource.PHONE && phoneNear.name == w.first)
    val phoneFar = SunTimes.choose(48.15 to 17.11, 48.0, 18.0, w.first, w.second, w.third)
    check("telefón ďaleko od miesta počasia → bez názvu (súradnice)", phoneFar.source == SunSource.PHONE && phoneFar.name.isEmpty() && phoneFar.label().contains("48.15"))
    check("bez telefónu → poloha domu z automatiky", SunTimes.choose(null, 48.0, 18.0, w.first, w.second, w.third).source == SunSource.HOME)
    val weather = SunTimes.choose(null, null, null, w.first, w.second, w.third)
    check("bez telefónu a domu → miesto počasia", weather.source == SunSource.WEATHER && weather.label() == "podľa miesta počasia – Bánovce nad Bebravou")
    check("všeobecný názov miesta počasia sa nepoužije", SunTimes.choose(null, null, null, "Poloha telefónu", 48.72, 18.26).name.isEmpty())
    check("západná dĺžka sa píše ako W", SunPlace(39.24, -123.17, SunSource.PHONE).label().endsWith("39.24° N, 123.17° W"))
    check("nič → predvolená poloha", SunTimes.choose(null, null, null, null, null, null).source == SunSource.DEFAULT)
    check("vzdialenosť Bánovce–Bratislava ~ 115 km", SunTimes.distanceKm(48.72, 18.26, 48.15, 17.11) in 100.0..130.0)

    if (failed > 0) { println("SunTest: $failed CHÝB"); exitProcess(1) }
    println("SunTest: všetko OK")
}
