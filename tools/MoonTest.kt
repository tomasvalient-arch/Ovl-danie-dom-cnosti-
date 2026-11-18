import sk.domov.ovladanie.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import kotlin.math.abs
import kotlin.system.exitProcess

private var failed = 0
private fun check(name: String, ok: Boolean) {
    println((if (ok) "OK   " else "CHYBA ") + name)
    if (!ok) failed++
}

private val CET = ZoneId.of("Europe/Bratislava")
private val TZ = TimeZone.getTimeZone(CET)

private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
    LocalDateTime.of(y, m, d, h, min).atZone(CET).toInstant().toEpochMilli()

fun main() {
    // 17. 11. 2026, 15:14 SEČ – tesne po prvej štvrti (12:47)
    val now = at(2026, 11, 17, 15, 14)
    val p = Moon.phase(now)
    check("15:14 SEČ názov Prvá štvrť", p.name == "Prvá štvrť")
    check("15:14 SEČ 51 % (presne ${"%.2f".format(p.illumination * 100)} %)", p.percent == 51 && abs(p.illumination * 100 - 51.08) < 0.15)
    check("15:14 SEČ priberá, svetlo sprava", p.waxing && p.lightOnRight)
    check("15:14 text pásu", p.text == "Prvá štvrť · 51 %")

    val fq = Moon.phase(at(2026, 11, 17, 12, 47))
    check("presná prvá štvrť 12:47 ≈ 50 % (${"%.2f".format(fq.illumination * 100)} %)", abs(fq.illumination * 100 - 50.08) < 0.2 && fq.waxing)

    val neu = Moon.phase(at(2026, 11, 9, 8, 2))
    check("nový mesiac 9. 11. 8:02 < 2 % (${"%.2f".format(neu.illumination * 100)} %)", neu.illumination * 100 < 2.0 && neu.name == "Nový mesiac")

    val full = Moon.phase(at(2026, 11, 24, 15, 53))
    check("spln 24. 11. 15:53 > 98 % (${"%.2f".format(full.illumination * 100)} %)", full.illumination * 100 > 98.0 && full.name == "Spln")

    val lq = Moon.phase(at(2026, 12, 1, 7, 8))
    check("posledná štvrť 1. 12. 7:08 ≈ 50 % ubúda (${"%.2f".format(lq.illumination * 100)} %)", abs(lq.illumination * 100 - 50.0) < 1.0 && !lq.waxing && lq.name == "Posledná štvrť")

    // Ďalšia hlavná fáza – dátum (čas do cca 30 min voči timeanddate / PyEphem)
    fun near(got: Long, expect: Long, label: String, tolMin: Long = 30) {
        val d = abs(got - expect) / 60_000L
        check("$label (odchýlka ${d} min)", d <= tolMin)
    }
    val nextFromNow = p.next!!
    check("po 15:14 je ďalší Spln", nextFromNow.name == "Spln")
    near(nextFromNow.atMillis, at(2026, 11, 24, 15, 53), "spln 24. 11. 15:53")
    check("label Spln Ut 24. 11.", nextFromNow.label(TZ) == "Spln Ut 24. 11.")

    val beforeFq = Moon.phase(at(2026, 11, 17, 10, 0))
    check("pred 12:47 je ďalšia Prvá štvrť", beforeFq.next!!.name == "Prvá štvrť")
    near(beforeFq.next!!.atMillis, at(2026, 11, 17, 12, 47), "prvá štvrť 17. 11. 12:47")

    val afterFull = Moon.nextMajor(at(2026, 11, 24, 16, 30))
    check("po splne je Posledná štvrť", afterFull.name == "Posledná štvrť")
    near(afterFull.atMillis, at(2026, 12, 1, 7, 8), "posledná štvrť 1. 12. 7:08")

    val atNew = Moon.nextMajor(at(2026, 11, 9, 8, 2))
    check("v okamihu novu je ďalšia Prvá štvrť", atNew.name == "Prvá štvrť")
    near(atNew.atMillis, at(2026, 11, 17, 12, 47), "po nove prvá štvrť 17. 11.")

    check("formát Ut 24. 11.", Moon.formatShort(at(2026, 11, 24, 15, 53), TZ) == "Ut 24. 11.")
    check("formát Po 9. 11.", Moon.formatShort(at(2026, 11, 9, 8, 2), TZ) == "Po 9. 11.")

    // južná pologuľa: zrkadlo
    val south = Moon.phase(now, northernHemisphere = false)
    check("južná pologuľa: svetlo zľava pri priberaní", south.waxing && !south.lightOnRight)

    // max. odchýlka osvetlenia voči referenčným bodom (PyEphem) v novembri 2026 napoludnie
    // hodnoty z overenia pri návrhu (PyEphem 4.2.1), tolerancia 0,4 %
    val noonRefs = listOf(
        at(2026, 11, 1, 12, 0) to 54.58,
        at(2026, 11, 10, 12, 0) to 1.50,
        at(2026, 11, 17, 12, 0) to 49.80,
        at(2026, 11, 20, 12, 0) to 78.51,
        at(2026, 11, 24, 12, 0) to 99.77,
        at(2026, 11, 27, 12, 0) to 88.37,
    )
    var maxErr = 0.0
    for ((t, ephem) in noonRefs) {
        val err = abs(Moon.phase(t, includeNext = false).illumination * 100 - ephem)
        if (err > maxErr) maxErr = err
        check("nopoludnie vs PyEphem ${java.time.Instant.ofEpochMilli(t).atZone(CET).toLocalDate()} Δ=${"%.2f".format(err)} %", err <= 0.40)
    }
    println("najväčšia odchýlka voči PyEphem (vzorka): ${"%.2f".format(maxErr)} %")

    if (failed > 0) {
        println("NEÚSPECH: $failed kontrol(y)")
        exitProcess(1)
    }
    println("MoonTest: všetky kontroly prešli")
}
