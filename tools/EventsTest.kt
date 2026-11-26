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

    // časovaná udalosť so starým offsets=[0] nesmie dostať dennú pripomienku o 8:00
    val timedOld = ev("tm", "Zubár", birthday = false, year = 2026, m = 11, d = 18, yearly = false, offs = listOf(0))
        .copy(startMin = 14 * 60, remindMin = 60)
    check("stará časovaná s offsetom 0 nedostane dennú pripomienku o 8:00",
        Events.due(listOf(timedOld), emptySet(), LocalDateTime.of(2026, 11, 18, 8, 0), t8).isEmpty())
    check("dayOffsets pri časovanej sú prázdne", Events.dayOffsets(timedOld).isEmpty())
    check("normalize vyčistí offsety časovanej a celodennú nechá",
        Events.normalize(timedOld).offsets.isEmpty() && Events.normalize(ev("a")).offsets == listOf(7, 0))
    val loadedTimed = Events.fromJson("""[{"i":"tm","n":"Zubar","b":false,"y":2026,"m":11,"d":18,"r":false,"o":[0],"sm":840,"rm":60}]""")[0]
    check("fromJson vyčistí offsety časovanej", loadedTimed.startMin == 840 && loadedTimed.offsets.isEmpty() && loadedTimed.remindMin == 60)
    check("okno časovanej je deň výskytu, ostane v čoskoro",
        Events.window(timedOld) == 0 && Events.groups(listOf(timedOld), today).soon.map { it.id } == listOf("tm"))
    val dueTimedAt13 = Calendar.dueTimed(listOf(timedOld), LocalDateTime.of(2026, 11, 18, 13, 5), emptySet())
    check("časovaná pripomienku o 13:00 stále má",
        dueTimedAt13.size == 1 && dueTimedAt13[0].occurrence == today && dueTimedAt13[0].event.id == "tm")
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
    check("nadpis narodenín", Events.title(b) == "Priateľka · Narodeniny")
    check("upozornenie týždeň vopred", Events.notificationBody(b, LocalDate.of(2026, 11, 25), today) == "O týždeň · streda 25. novembra · bude mať 30 rokov")
    check("upozornenie v ten deň", Events.notificationBody(b, LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25)) == "Dnes · streda 25. novembra · má 30 rokov")
    check("upozornenie bez veku", Events.notificationBody(once, LocalDate.of(2026, 12, 24), today) == "O 36 dní · štvrtok 24. decembra")
    check("riadok zoznamu", Events.dateLine(b, today) == "streda 25. novembra · každý rok · bude mať 30 rokov")
    check("riadok jednorazovej", Events.dateLine(once, today) == "štvrtok 24. decembra 2026")
    check("tenký riadok na Domove: prázdne", Events.slimText(emptyList()) == "Pridať")
    check("tenký riadok na Domove: nič sa nechystá", Events.slimText(listOf(b)) == "Nič sa nechystá")
    check("pilulka: dnes, zajtra, dni", Events.pillText(0) == "Dnes" && Events.pillText(1) == "Zajtra" && Events.pillText(4) == "4 dni" && Events.pillText(7) == "7 dní")
    check("vek v riadku", Events.ageCaption(b, today) == "30 rokov" && Events.ageCaption(once, today) == null)
    check("skratka mesiaca", Events.monthShort(LocalDate.of(2027, 3, 14)) == "mar" && Events.monthShort(LocalDate.of(2027, 7, 17)) == "júl")

    // plán upozornení (náhľad v dialógu)
    val p1 = Events.plan(b, LocalDateTime.of(2026, 11, 10, 10, 0), t8)
    check("plán: obe upozornenia prídu", p1.upcoming == listOf(7 to LocalDate.of(2026, 11, 18), 0 to LocalDate.of(2026, 11, 25)) && p1.skipped.isEmpty())
    val p2 = Events.plan(b, LocalDateTime.of(2026, 11, 22, 10, 0), t8)
    check("plán: týždeň vopred už prešiel", p2.upcoming == listOf(0 to LocalDate.of(2026, 11, 25)) && p2.skipped == listOf(7))
    val p3 = Events.plan(b, LocalDateTime.of(2026, 11, 25, 9, 0), t8)
    check("plán: v deň udalosti po čase nepríde nič", p3.upcoming.isEmpty() && p3.skipped == listOf(7, 0))
    val p4 = Events.plan(past, LocalDateTime.of(2026, 11, 18, 9, 0), t8)
    check("plán: jednorazová v minulosti", p4.upcoming.isEmpty() && p4.skipped == listOf(7))
    check("text plánu", Events.planLines(p1, t8) == listOf("Príde o 8:00: 18. novembra (1 týždeň vopred), 25. novembra (v ten deň)"))
    check("text plánu: preskočené", Events.planLines(p2, t8) == listOf("Príde o 8:00: 25. novembra (v ten deň)", "Už prešlo, nepríde: 1 týždeň vopred"))
    check("text plánu: bez upozornenia", Events.planLines(Events.plan(b.copy(offsets = emptyList()), LocalDateTime.of(2026, 11, 10, 10, 0), t8), t8) == listOf("Bez upozornenia – nepríde nič."))
    check("plán sedí s tým, čo pošle kontrola", Events.due(listOf(b), Events.afterSave(emptySet(), b, LocalDateTime.of(2026, 11, 22, 10, 0), t8), LocalDateTime.of(2026, 11, 25, 8, 0), t8).let { it.size == 1 && it[0].offset == 0 })
    check("rok narodenia: vek v zozname", Events.dateLine(ev("x", year = 1990), today) == "streda 25. novembra · každý rok · bude mať 36 rokov")
    check("dieťa narodené tento rok: bez veku", Events.dateLine(ev("x", year = 2026), today) == "streda 25. novembra · každý rok")


    // okno upozornení: na Domove sa ukáže len to, čo sa blíži (najväčšia voľba vopred), nič sa nezbalí
    val far = ev("far", name = "Lucia", m = 3, d = 14)                       // o 116 dní, okno 7 dní
    val soonB = ev("b1", name = "Marek", m = 11, d = 22, offs = listOf(7, 1, 0)) // o 4 dni
    val todayOnce = once.copy(id = "t", name = "Zubár", month = 11, day = 18, offsets = listOf(1, 0))
    val noOffs = ev("n", name = "Bez", m = 11, d = 20, offs = emptyList())
    check("okno = najväčšia voľba vopred", Events.window(b) == 7 && Events.window(noOffs) == null && Events.window(once) == 7)
    val g = Events.groups(listOf(far, b, soonB, todayOnce, noOffs, past), today)
    check("čoskoro: všetky v okne, od najbližšieho", g.soon.map { it.id } == listOf("t", "b1", "e1"))
    check("neskôr: mimo okna a bez upozornenia, podľa dátumu", g.later.map { it.id } == listOf("n", "far"))
    check("prešlo: jednorazová, ktorá už prešla", g.past.map { it.id } == listOf("c"))
    check("narodeniny o 300 dní nie sú v okne", Events.groups(listOf(ev("x", m = 9, d = 10)), today).let { it.soon.isEmpty() && it.later.size == 1 })
    check("v deň udalosti je ešte v okne, deň po nej už nie", Events.groups(listOf(b), LocalDate.of(2026, 11, 25)).soon.size == 1 &&
        Events.groups(listOf(b), LocalDate.of(2026, 11, 26)).soon.isEmpty())
    check("okno začína presne týždeň vopred", Events.groups(listOf(b), LocalDate.of(2026, 11, 18)).soon.size == 1 &&
        Events.groups(listOf(b), LocalDate.of(2026, 11, 17)).soon.isEmpty())
    check("viac udalostí = viac riadkov (nič sa nezbalí)", Events.groups(List(8) { ev("m$it", name = "Osoba $it", m = 11, d = 20) }, today).soon.size == 8)
    check("podnadpis", Events.subtitle(listOf(b, soonB, far, past), today) == "2 čoskoro · 1 neskôr · 1 prešlo")
    check("podnadpis: nič sa nechystá", Events.subtitle(listOf(far), today) == "Nič sa nechystá · 1 neskôr")
    check("podnadpis: prázdne", Events.subtitle(emptyList(), today) == "Zatiaľ žiadne udalosti")

    // meniny: druhý dátum tej istej osoby
    val km = ev("k", name = "Katka", m = 6, d = 2, offs = listOf(7, 0)).copy(meninyMonth = 11, meninyDay = 25)
    check("osoba s meninami má dva dátumy", Events.hasMeniny(km) && Events.parts(km).size == 2 && Events.parts(b).size == 1)
    val km2 = Events.parts(km)[1]
    check("odvodené meniny: id, názov, každoročné, bez veku", km2.id == "k~n" && km2.isMeniny && km2.yearly && km2.year == 0 && km2.month == 11 && km2.day == 25 &&
        Events.title(km2) == "Katka · Meniny" && Events.age(km2, LocalDate.of(2026, 11, 25)) == null && Events.partLabel(km2) == "Meniny")
    check("id odvodenej položky sa vráti na uložený záznam", Events.baseId(km2.id) == "k" && Events.baseId("k") == "k")
    check("meniny len pri narodeninách", !Events.hasMeniny(km.copy(birthday = false)) && Events.parts(km.copy(birthday = false)).size == 1)
    check("meniny s neplatným dátumom sa ignorujú", !Events.hasMeniny(km.copy(meninyMonth = 13)) && !Events.hasMeniny(km.copy(meninyDay = 0)))
    check("meniny o týždeň sú v okne, narodeniny až o rok mimo", Events.groups(listOf(km), today).soon.map { it.id } == listOf("k~n") && Events.groups(listOf(km), today).later.map { it.id } == listOf("k"))
    check("pred narodeninami sú v okne narodeniny, meniny mimo", Events.groups(listOf(km), LocalDate.of(2026, 5, 28)).soon.map { it.id } == listOf("k") &&
        Events.groups(listOf(km), LocalDate.of(2026, 5, 28)).later.map { it.id } == listOf("k~n"))
    check("riadok menín", Events.dateLine(km2, today) == "streda 25. novembra · každý rok")
    val dm = Events.due(listOf(km), emptySet(), LocalDateTime.of(2026, 11, 18, 8, 0), t8)
    check("upozornenie na meniny príde samostatne", dm.size == 1 && dm[0].event.id == "k~n" && dm[0].offset == 7 && dm[0].keys == listOf("k~n|7|2026-11-25"))
    val dm2 = Events.due(listOf(km), emptySet(), LocalDateTime.of(2026, 6, 2, 8, 0), t8)
    check("v deň narodenín príde len narodeninové upozornenie", dm2.size == 1 && dm2[0].event.id == "k" && dm2[0].offset == 0)
    val dm3 = Events.due(listOf(km), setOf("k~n|7|2026-11-25"), LocalDateTime.of(2026, 11, 20, 9, 0), t8)
    check("odoslané meniny sa nezopakujú", dm3.isEmpty())
    check("uloženie osoby s meninami vybaví upozornenia, ktorých čas už prešiel",
        Events.afterSave(emptySet(), km, LocalDateTime.of(2026, 11, 22, 10, 0), t8) == setOf("k~n|7|2026-11-25"))
    check("uloženie zahodí staré záznamy udalosti aj jej menín, cudzie ostanú",
        Events.afterSave(setOf("k|7|2026-06-02", "k~n|0|2026-11-25", "k2|0|2026-12-01"), km, LocalDateTime.of(2026, 11, 10, 10, 0), t8) == setOf("k2|0|2026-12-01"))
    check("odstránenie menín pri úprave zahodí ich záznamy", Events.afterSave(setOf("k~n|7|2026-11-25"), km.copy(meninyMonth = 0, meninyDay = 0), LocalDateTime.of(2026, 11, 10, 10, 0), t8).isEmpty())
    check("dropKeys: id k sa nepomýli s k2 ani k~n", Events.dropKeys(setOf("k|0|2026-06-02", "k~n|0|2026-11-25", "k2|0|2026-12-01", "k2~n|0|2026-12-01"), "k") == setOf("k2|0|2026-12-01", "k2~n|0|2026-12-01"))
    check("prune funguje aj pre kľúče menín", Events.prune(setOf("k~n|0|2026-11-25", "k~n|0|2026-11-10"), today) == setOf("k~n|0|2026-11-25"))
    val pl = Events.planLinesFor(km, LocalDateTime.of(2026, 11, 22, 10, 0), t8)
    check("plán pre osobu s meninami", pl == listOf(
        "Narodeniny – príde o 8:00: 26. mája (1 týždeň vopred), 2. júna (v ten deň)",
        "Meniny – príde o 8:00: 25. novembra (v ten deň)",
        "Meniny – už prešlo, nepríde: 1 týždeň vopred",
    ))
    check("plán bez upozornení", Events.planLinesFor(km.copy(offsets = emptyList()), LocalDateTime.of(2026, 11, 22, 10, 0), t8) == listOf("Bez upozornenia – nepríde nič."))
    check("plán pre obyčajnú udalosť sa nemení", Events.planLinesFor(b, LocalDateTime.of(2026, 11, 22, 10, 0), t8) == listOf("Príde o 8:00: 25. novembra (v ten deň)", "Už prešlo, nepríde: 1 týždeň vopred"))
    val kmJson = Events.fromJson(Events.toJson(listOf(km, b)))
    check("JSON s meninami tam a späť", kmJson == listOf(km, b) && kmJson[0].meninyMonth == 11 && kmJson[1].meninyMonth == 0)
    check("starý záznam bez menín sa načíta", Events.fromJson("""[{"i":"s","n":"Ok","b":true,"y":1990,"m":3,"d":4,"r":true,"o":[7,0]}]""")[0].meninyMonth == 0)
    check("meniny pri udalosti, ktorá nie je narodeniny, sa zahodia", Events.fromJson("""[{"i":"s","n":"Ok","b":false,"m":3,"d":4,"mm":5,"md":6,"o":[0]}]""")[0].meninyMonth == 0)
    check("meniny s neplatným dňom sa zahodia", Events.fromJson("""[{"i":"s","n":"Ok","b":true,"m":3,"d":4,"mm":5,"md":40,"o":[0]}]""")[0].meninyDay == 0)

    // dátum menín podľa mena z kalendára (Meniny.kt)
    check("Katarína má meniny 25. 11.", Meniny.dniPreMeno("Katarína") == listOf(11 to 25))
    check("bez diakritiky a veľkosti písmen", Meniny.dniPreMeno("katarina") == listOf(11 to 25) && Meniny.dniPreMeno("  EUGEN ") == listOf(11 to 18))
    check("berie sa prvé slovo", Meniny.dniPreMeno("Katarína Nováková") == listOf(11 to 25))
    check("zdrobnenina ani prázdny text nie sú v kalendári", Meniny.dniPreMeno("Katka").isEmpty() && Meniny.dniPreMeno("").isEmpty() && Meniny.dniPreMeno("   ").isEmpty())
    check("rovnaké meno s diakritikou aj bez nej na jednom dni je jeden dátum", Meniny.dniPreMeno("Matiáš") == listOf(2 to 24) && Meniny.dniPreMeno("matias") == listOf(2 to 24))

    // výber dátumu na bubnoch
    val min = LocalDate.of(2026, 11, 18)
    check("deň sa skráti na dĺžku mesiaca", DatePick.clamp(2027, 2, 31, null, null) == LocalDate.of(2027, 2, 28))
    check("29. 2. v priestupnom roku ostane", DatePick.clamp(2028, 2, 29, null, null) == LocalDate.of(2028, 2, 29) && DatePick.clamp(DatePick.LEAP_YEAR, 2, 29, null, null) == LocalDate.of(2024, 2, 29))
    check("mesiac 13 sa opraví na december", DatePick.clamp(2026, 13, 5, null, null) == LocalDate.of(2026, 12, 5))
    check("minulý deň sa vráti na dnešok", DatePick.clamp(2026, 11, 17, min, null) == min && DatePick.clamp(2026, 3, 1, min, null) == min)
    check("dnes a neskôr ostáva", DatePick.clamp(2026, 11, 18, min, null) == min && DatePick.clamp(2027, 1, 5, min, null) == LocalDate.of(2027, 1, 5))
    check("narodený v budúcnosti sa vráti na dnešok", DatePick.clamp(2026, 12, 1, null, min) == min && DatePick.clamp(2026, 11, 18, null, min) == min && DatePick.clamp(1996, 11, 25, null, min) == LocalDate.of(1996, 11, 25))
    check("zmena roka na minulý ťahá dátum na dnešok", DatePick.clamp(2026, 3, 14, min, null) == min)
    check("platnosť dňa", DatePick.isValid(2027, 2, 28, null, null) && !DatePick.isValid(2027, 2, 29, null, null) && !DatePick.isValid(2027, 4, 31, null, null) && DatePick.isValid(2028, 2, 29, null, null))
    check("platnosť dňa v rozsahu", !DatePick.isValid(2026, 11, 17, min, null) && DatePick.isValid(2026, 11, 18, min, null) && !DatePick.isValid(2026, 11, 19, null, min))
    check("mesiac s platným dňom", !DatePick.monthHasValid(2026, 10, min, null) && DatePick.monthHasValid(2026, 11, min, null) && DatePick.monthHasValid(2027, 1, min, null) &&
        DatePick.monthHasValid(2026, 11, null, min) && !DatePick.monthHasValid(2026, 12, null, min))
    check("dĺžky mesiacov", DatePick.lengthOf(2027, 2) == 28 && DatePick.lengthOf(2028, 2) == 29 && DatePick.lengthOf(2027, 4) == 30 && DatePick.lengthOf(2027, 12) == 31)
    check("dvojica bubnov: každý deň a mesiac sa dá vybrať v referenčnom roku", (1..12).all { m -> (1..DatePick.lengthOf(DatePick.LEAP_YEAR, m)).all { d -> DatePick.isValid(DatePick.LEAP_YEAR, m, d, null, null) } })

    // uloženie
    val list = listOf(b, once.copy(offsets = listOf(14, 3, 0)))
    check("JSON tam a späť", Events.fromJson(Events.toJson(list)) == list)
    val bad = Events.fromJson("""[{"i":"x","n":"  mlieko ","m":13,"d":1},{"i":"y","n":"ok","m":2,"d":29,"y":2024,"o":[3,-1,400,3,0]}]""")
    check("neplatný mesiac sa preskočí, názov a upozornenia sa opravia", bad.size == 1 && bad[0].name == "Ok" && bad[0].offsets == listOf(3, 0) && !bad[0].yearly)
    check("poškodený JSON = žiadne udalosti", Events.fromJson("{zle").isEmpty() && Events.fromJson(null).isEmpty() && Events.fromJson("").isEmpty())
    val fr = setOf("e1|7|2026-11-25", "e2|0|2026-12-01")
    check("záznamy o odoslaných tam a späť", Events.firedFromJson(Events.firedToJson(fr)) == fr && Events.firedFromJson("zle").isEmpty())

    // dátumy výskytu a skladanie nadpisu (jeden zdroj pravdy – 1.49)
    val shot = LocalDate.of(2026, 11, 25)
    val person = ev("kat", name = "Katkarinka narodeniny", m = 11, d = 26, offs = listOf(7, 1, 0)).copy(meninyMonth = 11, meninyDay = 20)
    val parts = Events.parts(person)
    check("osoba má narodeniny aj meniny ako dve časti", parts.size == 2 && parts[0].birthday && parts[1].isMeniny)
    check("meniny pripadajú na 20. 11., nie na zajtra", Events.happensOn(parts[1], LocalDate.of(2026, 11, 20)) && !Events.happensOn(parts[1], LocalDate.of(2026, 11, 26)))
    check("narodeniny pripadajú na 26. 11.", Events.happensOn(parts[0], LocalDate.of(2026, 11, 26)) && !Events.happensOn(parts[0], LocalDate.of(2026, 11, 20)))
    check("z 25. 11. sú narodeniny zajtra, meniny až budúci rok",
        Events.occurrence(parts[0], shot) == LocalDate.of(2026, 11, 26) && Events.daysUntil(parts[0], shot) == 1 &&
            Events.occurrence(parts[1], shot) == LocalDate.of(2027, 11, 20))
    check("Calendar.happensOn sa zhoduje s Events.happensOn",
        Calendar.happensOn(parts[1], LocalDate.of(2026, 11, 20)) && Calendar.happensOn(parts[0], LocalDate.of(2026, 11, 26)))
    check("occurrence a happensOn sedia: výskyt je ten deň",
        parts.all { e -> Events.occurrence(e, shot)?.let { Events.happensOn(e, it) } == true })
    check("zloženie nadpisu odstráni typ z mena a dá ho nabok",
        Events.displayName(person) == "Katkarinka" &&
            Events.title(parts[0]) == "Katkarinka · Narodeniny" &&
            Events.title(parts[1]) == "Katkarinka · Meniny")
    check("predpona Meniny: sa nezdvojuje",
        Events.title(km2.copy(name = "Meniny: Katka")) == "Katka · Meniny" &&
            Events.displayName(ev("x", name = "Katka – meniny")) == "Katka")
    check("obyčajná udalosť ostane bez typu", Events.title(once.copy(name = "Zubár")) == "Zubár")
    check("popisky Dnes / Zajtra / dátum",
        Events.whenLabel(0) == "Dnes" && Events.whenLabel(1) == "Zajtra" &&
            Events.whenLabel(5, LocalDate.of(2026, 11, 30)) == "pondelok 30. novembra")
    val head = Events.headerSubtitle(listOf(person), shot)
    check("podnadpis kalendára: dnes nič, ďalšia zajtra narodeniny",
        head.startsWith("Dnes nič") && head.contains("Zajtra") && head.contains("Katkarinka") && head.contains("Narodeniny") && !head.contains("Meniny: Katkarinka narodeniny"))
    check("každoročná udalosť po termíne skočí na budúci rok",
        Events.occurrence(ev("x", m = 11, d = 20), shot) == LocalDate.of(2027, 11, 20))
    check("jednorazová po termíne nemá výskyt",
        Events.occurrence(once.copy(year = 2026, month = 11, day = 20, yearly = false), shot) == null)
    check("skupiny z 25. 11.: narodeniny čoskoro, meniny neskôr (nie zajtra)",
        Events.groups(listOf(person), shot).let { it.soon.map { e -> e.id } == listOf("kat") && it.later.map { e -> e.id } == listOf("kat~n") })

    println("Všetko v poriadku")
}
