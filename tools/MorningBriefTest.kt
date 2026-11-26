import sk.domov.ovladanie.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.system.exitProcess

private var failed = 0
private fun check(name: String, ok: Boolean) {
    println((if (ok) "OK   " else "CHYBA ") + name)
    if (!ok) failed++
}

private fun ev(id: String, name: String, m: Int, d: Int, birthday: Boolean = false, year: Int = 0, offsets: List<Int> = listOf(0), meninyM: Int = 0, meninyD: Int = 0) =
    EventItem(id, name, birthday, year, m, d, yearly = birthday || year == 0, offsets = offsets, meninyMonth = meninyM, meninyDay = meninyD)

private fun dev(id: String, name: String, category: String, powerDp: String = "") =
    Device(id = id, name = name, ip = "192.168.1.10", devId = "d$id", localKey = "k", version = "3.3", category = category, powerDp = powerDp)

fun main() {
    val today = LocalDate.of(2026, 11, 23) // pondelok, meniny Klement
    val weather = WeatherDay("2026-11-23", 2, 7.4, 1.6, 40)

    // ---- skladanie textu
    val full = MorningBrief.compose(
        BriefContent(
            date = today,
            weather = weather,
            events = listOf(
                ev("a", "Jana", 11, 23, birthday = true, year = 1996),
                ev("b", "Zubár", 11, 23, year = 2026),
                ev("c", "Peter", 6, 29, birthday = true, offsets = listOf(7, 0), meninyM = 11, meninyD = 30),
                ev("d", "Výročie", 12, 24, offsets = listOf(30)),
            ),
            devices = BriefDevices(listOf("Obývačka svetlo", "Zásuvka TV"), checked = 5, unknown = 1),
        ),
    )
    println(full.title); println(full.big)
    check("nadpis s dňom", full.title == "Dobré ráno – pondelok 23. novembra")
    check("počasie: popis, min až max, zrážky", full.lines[0] == "Počasie: Polojasno, 2° až 7°, zrážky 40 %")
    check("krátky text = prvý riadok", full.short == full.lines[0])
    check("meniny", full.lines[1] == "Meniny: Klement")
    check("dnes: narodeniny s vekom", "Dnes: Jana – narodeniny (30 rokov)" in full.lines)
    check("dnes: obyčajná udalosť", "Dnes: Zubár" in full.lines)
    check("pripomienka vopred: meniny o týždeň", "O týždeň: Peter – meniny (pondelok 30. novembra)" in full.lines)
    check("pripomienka mimo dnešného dňa sa nevypíše", full.lines.none { it.contains("Výročie") })
    check("zapnuté zariadenia + neozvalo sa", full.lines.last() == "Zapnuté: Obývačka svetlo, Zásuvka TV (1 zariadenie sa neozvalo)")
    check("poradie: počasie, meniny, udalosti, zariadenia", full.lines.size == 6 && full.big.lines() == full.lines)

    // ---- výpadky zdrojov: prehľad príde so zvyškom
    val noWeather = MorningBrief.compose(BriefContent(date = today))
    check("bez počasia, udalostí a zariadení ostanú meniny", noWeather.lines == listOf("Meniny: Klement") && noWeather.short == "Meniny: Klement")
    check("všetky zariadenia mlčia = časť vynechaná", MorningBrief.devicesLine(BriefDevices(emptyList(), 3, 3)) == null)
    check("nič na zistenie = časť vynechaná", MorningBrief.devicesLine(BriefDevices(emptyList(), 0, 0)) == null)
    check("všetko vypnuté", MorningBrief.devicesLine(BriefDevices(emptyList(), 4, 0)) == "Všetko je vypnuté")
    check("neozvali sa 3", MorningBrief.devicesLine(BriefDevices(emptyList(), 6, 3)) == "Všetko je vypnuté (3 zariadenia sa neozvali)")
    check("neozvalo sa 5", MorningBrief.devicesLine(BriefDevices(listOf("A"), 9, 5)) == "Zapnuté: A (5 zariadení sa neozvalo)")
    check("veľa zapnutých: 6 mien a zvyšok číslom",
        MorningBrief.devicesLine(BriefDevices((1..8).map { "S$it" }, 8, 0)) == "Zapnuté: S1, S2, S3, S4, S5, S6 a ešte 2")
    check("bez zrážok", MorningBrief.weatherLine(WeatherDay("x", 0, 3.0, -0.2, 0)) == "Počasie: Jasno, 0° až 3°, bez zrážok")
    check("zrážky neznáme", MorningBrief.weatherLine(WeatherDay("x", 3, 3.0, -2.0, null)) == "Počasie: Zamračené, -2° až 3°")
    check("deň bez mena = sviatok", MorningBrief.meninyLine(LocalDate.of(2026, 1, 1)).startsWith("Nový rok"))
    check("narodeniny bez roku: bez veku", MorningBrief.eventLines(listOf(ev("x", "Ema", 11, 23, birthday = true)), today) == listOf("Dnes: Ema – narodeniny"))
    check("pripomienka zajtra s vekom",
        MorningBrief.eventLines(listOf(ev("y", "Ivan", 11, 24, birthday = true, year = 2000, offsets = listOf(1))), today) ==
            listOf("Zajtra: Ivan – narodeniny (utorok 24. novembra, bude mať 26 rokov)"))

    // ---- zapnuté / vypnuté zariadenie
    val lamp = dev("1", "Lampa", "dj")
    check("svetlo zapnuté", MorningBrief.isOn(lamp, mapOf("20" to true, "21" to "white", "22" to 500)) == true)
    check("svetlo vypnuté", MorningBrief.isOn(lamp, mapOf("20" to false, "21" to "white", "22" to 500)) == false)
    check("bez stavu = neznáme", MorningBrief.isOn(lamp, emptyMap()) == null)
    val plug = dev("2", "Zásuvka", "cz", powerDp = "1")
    check("zásuvka s ručne nastaveným vypínačom", MorningBrief.isOn(plug, mapOf("1" to true)) == true)
    check("zásuvka: kandidát", MorningBrief.candidate(plug, emptyMap()))
    val sensor = Device(id = "3", name = "Teplomer", ip = "", devId = "d3", localKey = "", version = "3.3", category = "wsdcg")
    check("senzor sa nezisťuje", !MorningBrief.candidate(sensor, emptyMap()))
    val fryer = dev("4", "Fritéza", "kqzg")
    check("fritéza: kandidát, ale bez stavu nič", MorningBrief.candidate(fryer, emptyMap()) && MorningBrief.isOn(fryer, emptyMap()) == null)

    // ---- plánovanie
    val seven = LocalTime.of(7, 0)
    check("budík dnes o 7:00", MorningBrief.nextAlarm(LocalDateTime.of(2026, 11, 23, 6, 0), seven) == LocalDateTime.of(2026, 11, 23, 7, 0))
    check("budík zajtra po 7:00", MorningBrief.nextAlarm(LocalDateTime.of(2026, 11, 23, 7, 0), seven) == LocalDateTime.of(2026, 11, 24, 7, 0))
    check("o 7:00 je na rade", MorningBrief.isDue(LocalDateTime.of(2026, 11, 23, 7, 0), seven, today.minusDays(1)))
    check("pred 7:00 nie", !MorningBrief.isDue(LocalDateTime.of(2026, 11, 23, 6, 59), seven, null))
    check("dnes už odišiel = nie znova", !MorningBrief.isDue(LocalDateTime.of(2026, 11, 23, 7, 5), seven, today))
    check("zmeškaný (telefón zapnutý o 9:30) príde", MorningBrief.isDue(LocalDateTime.of(2026, 11, 23, 9, 30), seven, null))
    check("po 3 hodinách už nie", !MorningBrief.isDue(LocalDateTime.of(2026, 11, 23, 10, 0), seven, null))
    check("neskorý čas 23:00: okno končí o polnoci", MorningBrief.isDue(LocalDateTime.of(2026, 11, 23, 23, 59), LocalTime.of(23, 0), null) &&
        !MorningBrief.isDue(LocalDateTime.of(2026, 11, 24, 0, 30), LocalTime.of(23, 0), null))

    // ---- odoslanie: markSent až po úspechu, krátky strážca proti duplicite
    val at705 = LocalDateTime.of(2026, 11, 23, 7, 5)
    check("bez príznaku sa smie začať", MorningBrief.canStartSend(1_000L, null))
    check("prázdny príznak sa smie začať", MorningBrief.canStartSend(1_000L, 0L))
    check("práve prebieha = nie", !MorningBrief.canStartSend(1_000L, 1_000L))
    check("tesne pred 2 minútami ešte nie", !MorningBrief.canStartSend(1_000L + MorningBrief.SEND_GUARD_MS - 1, 1_000L))
    check("po 2 minútach znova", MorningBrief.canStartSend(1_000L + MorningBrief.SEND_GUARD_MS, 1_000L))
    check("po neúspechu je ešte na rade (lastSent nezmenený)", MorningBrief.isDue(at705, seven, null))
    check("ďalší pokus o 15 minút v okne", MorningBrief.nextRetry(at705, seven, null) == LocalDateTime.of(2026, 11, 23, 7, 20))
    check("pred časom prehľadu sa neplánuje retry", MorningBrief.nextRetry(LocalDateTime.of(2026, 11, 23, 6, 0), seven, null) == null)
    check("po úspechu (dnes odišiel) sa neplánuje retry", MorningBrief.nextRetry(at705, seven, today) == null)
    check("na konci okna už retry nie je", MorningBrief.nextRetry(LocalDateTime.of(2026, 11, 23, 9, 50), seven, null) == null)
    check("ešte v okne je retry", MorningBrief.nextRetry(LocalDateTime.of(2026, 11, 23, 9, 44), seven, null) == LocalDateTime.of(2026, 11, 23, 9, 59))

    if (failed > 0) { println("MorningBriefTest: $failed chýb"); exitProcess(1) }
    println("MorningBriefTest: všetko OK")
}
