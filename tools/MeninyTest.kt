import sk.domov.ovladanie.*
import java.time.LocalDate
import kotlin.system.exitProcess

private var failed = 0
private fun check(name: String, ok: Boolean) {
    println((if (ok) "OK   " else "CHYBA ") + name)
    if (!ok) failed++
}

fun main() {
    // úplnosť: 366 dní (aj 29. 2.), 475 mien, 4 dni bez mena (údaje z Oficiálneho kalendária, Ministerstvo kultúry SR)
    var dni = 0
    var mena = 0
    val bezMena = mutableListOf<String>()
    for (m in 1..12) {
        val dlzka = java.time.YearMonth.of(2024, m).lengthOfMonth()
        for (d in 1..dlzka) {
            dni++
            val n = Meniny.mena(m, d)
            if (n.isBlank()) bezMena += "$d. $m." else mena += n.split(", ").size
        }
    }
    check("366 dní", dni == 366)
    check("475 mien (je $mena)", mena == 475)
    check("dni bez mena: 1. 1., 1. 5., 2. 11., 25. 12. (je $bezMena)", bezMena == listOf("1. 1.", "1. 5.", "2. 11.", "25. 12."))

    // vzorky z oficiálneho kalendária
    check("2. 1. Alexandra, Sandra, Karina", Meniny.mena(1, 2) == "Alexandra, Sandra, Karina")
    check("10. 1. Daša", Meniny.mena(1, 10) == "Daša")
    check("24. 1. Timotej, Timon, Tímea", Meniny.mena(1, 24) == "Timotej, Timon, Tímea")
    check("29. 2. Radomír", Meniny.mena(2, 29) == "Radomír")
    check("5. 7. Cyril, Metod", Meniny.mena(7, 5) == "Cyril, Metod")
    check("16. 7. Drahomír, Drahomíra", Meniny.mena(7, 16) == "Drahomír, Drahomíra")
    check("24. 12. Adam, Eva", Meniny.mena(12, 24) == "Adam, Eva")
    check("31. 12. Silvester", Meniny.mena(12, 31) == "Silvester")

    // formát
    val streda = LocalDate.of(2026, 11, 18)
    check("dnes: ${Meniny.dnes(streda)}", Meniny.dnes(streda) == "Streda 18. novembra · Meniny: Eugen")
    check("zajtra: ${Meniny.zajtra(streda)}", Meniny.zajtra(streda) == "Zajtra 19. novembra · Alžbeta, Liliana")
    check("nový rok", Meniny.dnes(LocalDate.of(2026, 1, 1)) == "Štvrtok 1. januára · Nový rok, Deň vzniku SR")
    check("31. 12. → zajtra 1. 1.", Meniny.zajtra(LocalDate.of(2026, 12, 31)) == "Zajtra 1. januára · Nový rok, Deň vzniku SR")
    check("28. 2. 2027 → zajtra 1. 3. (nie 29. 2.)", Meniny.zajtra(LocalDate.of(2027, 2, 28)).startsWith("Zajtra 1. marca · "))
    check("28. 2. 2028 → zajtra 29. 2. Radomír", Meniny.zajtra(LocalDate.of(2028, 2, 28)) == "Zajtra 29. februára · Radomír")
    check("1. 5. Sviatok práce", Meniny.dnes(LocalDate.of(2026, 5, 1)) == "Piatok 1. mája · Sviatok práce")
    check("2. 11. Pamiatka zosnulých", Meniny.dnes(LocalDate.of(2026, 11, 2)) == "Pondelok 2. novembra · Pamiatka zosnulých")
    check("25. 12. sviatok", Meniny.dnes(LocalDate.of(2026, 12, 25)) == "Piatok 25. decembra · 1. sviatok vianočný")
    // všetky dni roka 2028 (priestupný) idú bez výnimky
    var d = LocalDate.of(2028, 1, 1)
    var ok = true
    while (d.year == 2028) { runCatching { Meniny.dnes(d); Meniny.zajtra(d) }.onFailure { ok = false }; d = d.plusDays(1) }
    check("všetky dni 2028 sa naformátujú", ok)
    if (failed > 0) { println("$failed chýb"); exitProcess(1) }
}
