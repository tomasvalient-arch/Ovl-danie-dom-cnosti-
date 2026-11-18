import sk.domov.ovladanie.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// Test udalostí a narodenín: najbližší výskyt, kedy je upozornenie na rade, zmeškané upozornenia, texty a uloženie.

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

fun ev(
    id: String, name: String = "Priateľka", birthday: Boolean = true, year: Int = 1996, m: Int = 11, d: Int = 25,
    yearly: Boolean = true, offs: List<Int> = listOf(7, 0),
) = EventItem(id, name, birthday, year, m, d, yearly, offs)

fun main() {
    val today = LocalDate.of(2026, 11, 18) // streda
    val t8 = LocalTime.of(8, 0)

    // dátumy
    check("29. 2. v nepriestupnom roku je 28. 2.", Events.safeDate(2025, 2, 29) == LocalDate.of(2025, 2, 28))
    check("29. 2. v priestupnom roku ostane", Events.safeDate(2024, 2, 29) == LocalDate.of(2024, 2, 29))
    check("narodeniny tento rok", Events.occurrence(ev("a"), today) == LocalDate.of(2026, 11, 25))
    check("narodeniny už prešli, o rok", Events.occurrence(ev("a", m = 11, d = 10), today) == LocalDate.of(2027, 11, 10))
    check("narodeniny dnes", Events.occurrence(ev("a", m = 11, d = 18), today) == today)
    check("29. 2. opakované: ďalší rok 28. 2.", Events.occurrence(ev("l", m = 2, d = 29), today) == LocalDate.of(2027, 2, 28))
    check("29. 2. opakované: priestupný rok 29. 2.", Events.occurrence(ev("l", m = 2, d = 29), LocalDate.of(2027, 11, 18)) == LocalDate.of(2028, 2, 29))
    val once = ev("o", "Zubár", birthday = false, year = 2026, m = 12, d = 24, yearly = false, offs = listOf(7))
    check("jednorazová v budúcnosti", Events.occurrence(once, today) == LocalDate.of(2026, 12, 24))
    check("jednorazová dnes", Events.occurrence(once.copy(month = 11, day = 18), today) == today)
    check("jednorazová, ktorá prešla, nemá výskyt", Events.occurrence(once.copy(month = 11, day = 17), today) == null)
    check("dni do narodenín", Events.daysUntil(ev("a"), today) == 7)

    // vek
    check("vek 30", Events.age(ev("a"), LocalDate.of(2026, 11, 25)) == 30)
    check("neznámy rok = bez veku", Events.age(ev("a", year = 0), LocalDate.of(2026, 11, 25)) == null)
    check("udalosť nie je narodeniny = bez veku", Events.age(ev("a", birthday = false), LocalDate.of(2026, 11, 25)) == null)

    // poradie
    val past = once.copy(id = "c", month = 1, day = 5)
    check("najbližšie hore, prešlé na konci", Events.arrange(listOf(once, past, ev("b")), today).map { it.id } == listOf("b", "o", "c"))

    // čo je na rade
    val b = ev("e1")
    check("pred časom upozornenia nič", Events.due(listOf(b), emptySet(), LocalDateTime.of(2026, 11, 18, 7, 0), t8).isEmpty())
    val d1 = Events.due(listOf(b), emptySet(), LocalDateTime.of(2026, 11, 18, 8, 0), t8)
    check("o 8:00 príde týždeň vopred", d1.size == 1 && d1[0].offset == 7 && d1[0].keys.size == 1 && d1[0].occurrence == LocalDate.of(2026, 11, 25))
    val fired1 = d1[0].keys.toSet()
    check("po odoslaní už nič", Events.due(listOf(b), fired1, LocalDateTime.of(2026, 11, 18, 12, 0), t8).isEmpty())
    val d2 = Events.due(listOf(b), fired1, LocalDateTime.of(2026, 11, 25, 8, 0), t8)
    check("v ten deň príde druhé upozornenie", d2.size == 1 && d2[0].offset == 0)
    val d3 = Events.due(listOf(b), emptySet(), LocalDateTime.of(2026, 11, 22, 10, 0), t8)
    check("zmeškané upozornenie príde neskôr", d3.size == 1 && d3[0].offset == 7)
    val d4 = Events.due(listOf(b), emptySet(), LocalDateTime.of(2026, 11, 25, 9, 0), t8)
    check("viac zmeškaných = jedno, najbližšie k udalosti", d4.size == 1 && d4[0].offset == 0 && d4[0].keys.size == 2)
    check("po udalosti nič, kým nie je ďalší rok", Events.due(listOf(b), emptySet(), LocalDateTime.of(2026, 11, 26, 9, 0), t8).isEmpty())
    check("jednorazová, ktorá prešla, neupozorní", Events.due(listOf(past), emptySet(), LocalDateTime.of(2026, 11, 18, 9, 0), t8).isEmpty())

    // po uložení
    val af = Events.afterSave(emptySet(), b, LocalDateTime.of(2026, 11, 22, 10, 0), t8)
    check("po uložení: týždeň vopred je vybavený, v ten deň nie", af == setOf("e1|7|2026-11-25"))
    check("a nič nie je hneď na rade", Events.due(listOf(b), af, LocalDateTime.of(2026, 11, 22, 10, 0), t8).isEmpty())
    val af2 = Events.afterSave(setOf("e1|7|2026-11-25", "e2|0|2026-12-01"), b, LocalDateTime.of(2026, 11, 10, 10, 0), t8)
    check("staré záznamy tej istej udalosti preč, cudzie ostanú", af2 == setOf("e2|0|2026-12-01"))
    check("id e1 sa nepomýli s e10", Events.afterSave(setOf("e10|0|2026-12-01"), b, LocalDateTime.of(2026, 11, 10, 10, 0), t8) == setOf("e10|0|2026-12-01"))

    // čistenie záznamov a denná kontrola
    val pr = Events.prune(setOf("a|0|2026-11-16", "b|0|2026-11-17", "c|0|2026-11-18", "zzz"), today)
    check("prune: prešlé a poškodené preč", pr == setOf("b|0|2026-11-17", "c|0|2026-11-18"))
    check("ďalšia kontrola dnes o 8:00", Events.nextCheck(LocalDateTime.of(2026, 11, 18, 7, 0), t8) == LocalDateTime.of(2026, 11, 18, 8, 0))
    check("presne o 8:00 až zajtra", Events.nextCheck(LocalDateTime.of(2026, 11, 18, 8, 0), t8) == LocalDateTime.of(2026, 11, 19, 8, 0))
    check("večer až zajtra", Events.nextCheck(LocalDateTime.of(2026, 11, 18, 20, 0), t8) == LocalDateTime.of(2026, 11, 19, 8, 0))

    // texty
    check("dnes", Events.inText(0) == "dnes")
    check("zajtra", Events.inText(1) == "zajtra")
    check("o týždeň", Events.inText(7) == "o týždeň")
    check("o 2 týždne", Events.inText(14) == "o 2 týždne")
    check("o 3 dni", Events.inText(3) == "o 3 dni")
    check("o 12 dní", Events.inText(12) == "o 12 dní")
    check("odpočet 12 dní", Events.countdown(12) == ("12" to "dní"))
    check("odpočet 3 dni", Events.countdown(3) == ("3" to "dni"))
    check("odpočet dnes", Events.countdown(0) == ("Dnes" to ""))
    check("1 rok / 3 roky / 30 rokov", Events.yearsText(1) == "1 rok" && Events.yearsText(3) == "3 roky" && Events.yearsText(30) == "30 rokov")
    check("dátum s dňom v týždni", Events.dateText(LocalDate.of(2026, 11, 25), weekday = true) == "streda 25. novembra")
    check("dátum s rokom", Events.dateText(LocalDate.of(2026, 12, 3), year = true) == "3. decembra 2026")
    check("voľby vopred", Events.offsetLabel(0) == "V ten deň" && Events.offsetLabel(1) == "1 deň" && Events.offsetLabel(3) == "3 dni" &&
        Events.offsetLabel(7) == "1 týždeň" && Events.offsetLabel(30) == "30 dní")
    check("text upozornení", Events.offsetsText(listOf(7, 0)) == "Upozorní: 1 týždeň vopred, v ten deň")
    check("text upozornení 2", Events.offsetsText(listOf(1, 3)) == "Upozorní: 3 dni vopred, 1 deň vopred")
    check("bez upozornenia", Events.offsetsText(emptyList()) == "Bez upozornenia")
    check("nadpis narodenín", Events.title(b) == "Narodeniny: Priateľka")
    check("upozornenie týždeň vopred", Events.notificationBody(b, LocalDate.of(2026, 11, 25), today) == "O týždeň · streda 25. novembra · bude mať 30 rokov")
    check("upozornenie v ten deň", Events.notificationBody(b, LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25)) == "Dnes · streda 25. novembra · má 30 rokov")
    check("upozornenie bez veku", Events.notificationBody(once, LocalDate.of(2026, 12, 24), today) == "O 36 dní · štvrtok 24. decembra")
    check("riadok zoznamu", Events.dateLine(b, today) == "streda 25. novembra · každý rok · bude mať 30 rokov")
    check("riadok jednorazovej", Events.dateLine(once, today) == "štvrtok 24. decembra 2026")
    check("riadok na Domove", Events.rowText(listOf(once, b), today) == "o týždeň · Narodeniny: Priateľka")
    check("riadok na Domove: prázdne", Events.rowText(emptyList(), today) == "Pridať")
    check("riadok na Domove: len prešlé", Events.rowText(listOf(past), today) == "Nič nadchádza")
    check("podnadpis", Events.subtitle(listOf(b), today) == "Najbližšia: o týždeň")

    // uloženie
    val list = listOf(b, once.copy(offsets = listOf(14, 3, 0)))
    check("JSON tam a späť", Events.fromJson(Events.toJson(list)) == list)
    val bad = Events.fromJson("""[{"i":"x","n":"  mlieko ","m":13,"d":1},{"i":"y","n":"ok","m":2,"d":29,"y":2024,"o":[3,-1,400,3,0]}]""")
    check("neplatný mesiac sa preskočí, názov a upozornenia sa opravia", bad.size == 1 && bad[0].name == "Ok" && bad[0].offsets == listOf(3, 0) && !bad[0].yearly)
    check("poškodený JSON = žiadne udalosti", Events.fromJson("{zle").isEmpty() && Events.fromJson(null).isEmpty() && Events.fromJson("").isEmpty())
    val fr = setOf("e1|7|2026-11-25", "e2|0|2026-12-01")
    check("záznamy o odoslaných tam a späť", Events.firedFromJson(Events.firedToJson(fr)) == fr && Events.firedFromJson("zle").isEmpty())
    println("Všetko v poriadku")
}
