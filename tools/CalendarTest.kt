import sk.domov.ovladanie.Calendar
import sk.domov.ovladanie.EventItem
import sk.domov.ovladanie.Events
import sk.domov.ovladanie.Member
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth


fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

fun ev(
    id: String, name: String, year: Int, month: Int, day: Int,
    yearly: Boolean = false, startMin: Int = -1, endMin: Int = -1,
    audience: String = "both", ownerId: String = "", deleted: Boolean = false,
    remindMin: Int = -1, birthday: Boolean = false,
) = EventItem(id, name, birthday, year, month, day, yearly, listOf(0), startMin = startMin, endMin = endMin, audience = audience, ownerId = ownerId, deleted = deleted, remindMin = remindMin)

fun main() {
    val nov = YearMonth.of(2026, 11)
    val grid = Calendar.monthGrid(nov)
    check("mesiac má 42 buniek", grid.size == 42)
    check("začína v pondelok", grid.first().date.dayOfWeek.value == 1)
    check("1. nov 2026 je v mriežke a v mesiaci", grid.any { it.date == LocalDate.of(2026, 11, 1) && it.inMonth })
    check("dni mimo mesiaca sú označené", grid.first().inMonth.let { grid.first().date.monthValue != 11 || it })

    val today = LocalDate.of(2026, 11, 24)
    val zubar = ev("z", "Zubár", 2026, 11, 24, startMin = 14 * 60, endMin = 15 * 60, ownerId = "me")
    val visit = ev("v", "Návšteva", 2026, 11, 28, yearly = false, audience = "both", ownerId = "me")
    val secret = ev("s", "Len ja", 2026, 11, 24, audience = "me", ownerId = "me")
    val forHer = ev("p", "Len ona", 2026, 11, 24, audience = "partner", ownerId = "me")
    val gone = ev("g", "Zmazané", 2026, 11, 24, deleted = true)
    val bday = ev("b", "Jana", 1995, 11, 29, yearly = true, birthday = true)

    check("zubár je 24. 11.", Calendar.happensOn(zubar, today))
    check("návšteva nie je 24. 11.", !Calendar.happensOn(visit, today))
    check("narodeniny o 5 dní", Calendar.happensOn(bday, LocalDate.of(2026, 11, 29)))
    check("zmazané sa neukáže", !Calendar.happensOn(gone, today))

    check("ja vidím svoje súkromné", Calendar.visibleTo(secret, "me"))
    check("priateľka nevidí moje súkromné", !Calendar.visibleTo(secret, "her"))
    check("priateľka vidí „pre ňu“", Calendar.visibleTo(forHer, "her"))
    check("ja nevidím „len partner“", !Calendar.visibleTo(forHer, "me"))
    check("obaja vidia spoločnú", Calendar.visibleTo(visit, "her"))
    check("zmazané nie je viditeľné", !Calendar.visibleTo(gone, "me"))

    val day = Calendar.onDay(listOf(zubar, visit, secret, forHer, gone, bday), today, "me")
    check("v mojom dni je zubár a súkromné, nie partner / zmazané / návšteva", day.map { it.id }.toSet() == setOf("z", "s"))
    val herDay = Calendar.onDay(listOf(zubar, secret, forHer), today, "her")
    check("ona vidí zubára (obaja default) a „pre ňu“", herDay.map { it.id }.toSet() == setOf("z", "p"))

    val all = listOf(zubar, visit, secret, forHer, gone, bday)
    val mine = Calendar.visibleAmong(all, "me")
    check("visibleAmong: ja vidím spoločné a svoje, nie partner a zmazané", mine.map { it.id }.toSet() == setOf("z", "v", "s", "b"))
    val hers = Calendar.visibleAmong(all, "her")
    check("visibleAmong: ona vidí spoločné a pre ňu, nie moje súkromné", hers.map { it.id }.toSet() == setOf("z", "v", "p", "b"))
    val herOnlyTimed = forHer.copy(remindMin = 60, startMin = 14 * 60)
    val dueMine = Calendar.dueTimed(
        Calendar.visibleAmong(listOf(herOnlyTimed, zubar.copy(remindMin = 60)), "me"),
        LocalDateTime.of(2026, 11, 24, 13, 5),
        emptySet(),
    )
    check("pripomienka len pre ňu nejde na mojom telefóne", dueMine.map { it.event.id }.toSet() == setOf("z"))
    val dueHers = Calendar.dueTimed(
        Calendar.visibleAmong(listOf(herOnlyTimed, zubar.copy(remindMin = 60)), "her"),
        LocalDateTime.of(2026, 11, 24, 13, 5),
        emptySet(),
    )
    check("ona dostane pripomienku pre ňu aj spoločný zubár", dueHers.map { it.event.id }.toSet() == setOf("p", "z"))

    val now = LocalDateTime.of(2026, 11, 24, 12, 0)
    val timed = zubar.copy(remindMin = 60)
    val at = Calendar.nextTimedAt(timed, now)
    check("pripomienka hodinu pred 14:00 je 13:00", at == LocalDateTime.of(2026, 11, 24, 13, 0))
    val later = Calendar.nextTimedAt(timed, LocalDateTime.of(2026, 11, 24, 13, 30))
    check("po čase pripomienky už ďalšia nie je (jednorazová)", later == null)
    val due = Calendar.dueTimed(listOf(timed), LocalDateTime.of(2026, 11, 24, 13, 5), emptySet())
    check("due po 13:00", due.size == 1 && due[0].key == Calendar.timedKey(timed, today) && due[0].occurrence == today)
    val already = Calendar.dueTimed(listOf(timed), LocalDateTime.of(2026, 11, 24, 13, 5), setOf(Calendar.timedKey(timed, today)))
    check("vypálená sa neopakuje", already.isEmpty())
    val midnight = LocalDateTime.of(2026, 11, 25, 1, 0)
    val late = Calendar.dueTimed(listOf(timed), midnight, emptySet())
    check("po polnoci do 3:00 ostane včerajší výskyt jednorazovej",
        late.size == 1 && late[0].occurrence == today && late[0].key == Calendar.timedKey(timed, today))
    check("text po polnoci má včerajší dátum, nie dnešok",
        Events.notificationBody(timed, late[0].occurrence, midnight.toLocalDate()).contains("24. novembra") &&
            !Events.notificationBody(timed, late[0].occurrence, midnight.toLocalDate()).contains("25. novembra"))
    val yearlyTimed = timed.copy(id = "yt", yearly = true, year = 0)
    val lateYearly = Calendar.dueTimed(listOf(yearlyTimed), midnight, emptySet())
    check("ročná po polnoci používa včerajší výskyt, nie budúci rok",
        lateYearly.size == 1 && lateYearly[0].occurrence == today &&
            Events.notificationBody(yearlyTimed, lateYearly[0].occurrence, midnight.toLocalDate()).contains("24. novembra"))
    val tooLate = Calendar.dueTimed(listOf(timed), LocalDateTime.of(2026, 11, 25, 3, 0), emptySet())
    check("o 3:00 už dobehnutie skončilo", tooLate.isEmpty())

    val daily = LocalTime.of(8, 0)
    val alarm = Calendar.nextAlarm(listOf(timed), now, daily)
    check("bližší je časovaný 13:00, nie zajtrajších 8:00", alarm.hour == 13)
    val after = Calendar.nextAlarm(listOf(timed), LocalDateTime.of(2026, 11, 24, 16, 0), daily)
    check("po udalosti ostane denný budík zajtra ráno", after == LocalDateTime.of(2026, 11, 25, 8, 0))

    check("rozsah času", Events.timeRange(zubar) == "14:00–15:00")
    check("celý deň nemá čas", Events.timeRange(visit).isEmpty())
    check("názov mesiaca", Calendar.monthTitle(nov) == "November 2026")
    check("štítok bez mien: me", Calendar.audienceLabel("me") == "Ja")
    check("štítok bez mien: partner nie je Priateľka", Calendar.audienceLabel("partner") == "Ostatní")
    check("štítok bez mien: both", Calendar.audienceLabel("both") == "Všetci")
    check("štítok nikdy neobsahuje Priateľka", Calendar.audienceLabel("partner") != "Priateľka")

    val tomas = Calendar.HousePerson("me", "Tomáš", self = true)
    val jana = Calendar.HousePerson("her", "Jana")
    val otec = Calendar.HousePerson("dad", "Otec")
    val tri = listOf(tomas, jana, otec)
    val dvaja = listOf(tomas, jana)

    check("zoznam ľudí má vlastné meno a ja", Calendar.people("me", "Tomáš", listOf(Member("her", "Jana", 1L))).let { it[0].chipLabel() == "Tomáš · ja" && it[1].name == "Jana" })
    check("chýbajúce meno je Člen 2", Calendar.people("me", "Tomáš", listOf(Member("her", "", 1L)))[1].name == "Člen 2")
    check("nikdy fallback Priateľka", Calendar.people("me", "Tomáš", listOf(Member("her", "", 1L)))[1].name != "Priateľka")

    val onlyMe = ev("m", "Súkromné", 2026, 11, 24, audience = "me", ownerId = "me")
    val forPartner = ev("p2", "Len ostatní", 2026, 11, 24, audience = "partner", ownerId = "me")
    val forAll = ev("a", "Spoločná", 2026, 11, 24, audience = "both", ownerId = "me")
    val forJana = ev("j", "Len Jana", 2026, 11, 24, audience = "members", ownerId = "me").copy(audienceIds = listOf("her"))
    val oldJson = Events.fromJson("""[{"i":"old","n":"Stará","b":false,"y":2026,"m":11,"d":24,"r":false,"o":[0],"a":"partner","w":"me"}]""")
    check("starý partner sa prečíta", oldJson.single().audience == "partner" && oldJson.single().audienceIds.isEmpty())
    check("starý partner vidí tretí člen (legacy = všetci okrem autora)", Calendar.visibleTo(oldJson.single(), "dad"))
    check("starý partner nevidí autor", !Calendar.visibleTo(oldJson.single(), "me"))
    check("members + ai vidí len Jana", Calendar.visibleTo(forJana, "her") && !Calendar.visibleTo(forJana, "me") && !Calendar.visibleTo(forJana, "dad"))

    check("štítok me je Tomáš", Calendar.audienceLabel(onlyMe, dvaja) == "Tomáš")
    check("štítok partner pri 2 je Jana", Calendar.audienceLabel(forPartner, dvaja) == "Jana")
    check("štítok partner pri 3 sú mená ostatných", Calendar.audienceLabel(forPartner, tri) == "Jana, Otec")
    check("štítok both pri viacerých je Všetci", Calendar.audienceLabel(forAll, tri) == "Všetci")
    check("štítok both pri jednom je prázdny", Calendar.audienceLabel(forAll, listOf(tomas)).isEmpty())
    check("štítok members je Jana", Calendar.audienceLabel(forJana, tri) == "Jana")
    check("štítok s menami nie je Priateľka", !listOf(Calendar.audienceLabel(forPartner, dvaja), Calendar.audienceLabel(forPartner, tri), Calendar.audienceLabel(forJana, tri)).any { "Priateľka" in it || "priateľka" in it })

    val encAll = Calendar.encodeAudience(Calendar.AudienceSel(true, emptySet()), "me", tri)
    check("zápis všetci ostáva both", encAll == ("both" to emptyList<String>()))
    val encMe = Calendar.encodeAudience(Calendar.AudienceSel(false, setOf("me")), "me", tri)
    check("zápis len ja ostáva me", encMe == ("me" to emptyList<String>()))
    val encOthers = Calendar.encodeAudience(Calendar.AudienceSel(false, setOf("her", "dad")), "me", tri)
    check("zápis všetci okrem autora ostáva partner (kompatibilita)", encOthers == ("partner" to emptyList<String>()))
    val encOne = Calendar.encodeAudience(Calendar.AudienceSel(false, setOf("her")), "me", tri)
    check("zápis jednej osoby je members + id", encOne.first == "members" && encOne.second == listOf("her"))

    val round = Events.fromJson(Events.toJson(listOf(forJana))).single()
    check("JSON ai prežije", round.audience == "members" && round.audienceIds == listOf("her"))
    val oldBoth = Events.fromJson("""[{"i":"b","n":"Spoločná","b":false,"y":2026,"m":11,"d":24,"r":false,"o":[0]}]""").single()
    check("starý záznam bez a je both", oldBoth.audience == "both" && oldBoth.audienceIds.isEmpty())
    check("decode both je všetci", Calendar.decodeAudience(oldBoth, tri).all)
    check("decode partner bez členov je legacy", Calendar.decodeAudience(forPartner, listOf(tomas)).legacyPartner)
    val recoded = Calendar.encodeAudience(Calendar.decodeAudience(forPartner, listOf(tomas)), "me", listOf(tomas))
    check("partner bez známych členov sa zapíše zas ako partner", recoded == ("partner" to emptyList<String>()))

    // bodky len pre vlastné udalosti; všeobecné meniny (takmer každý deň) sa do mriežky nedávajú
    val feliz = EventItem("f", "Félix sviatok", false, 0, 11, 20, true, listOf(0), color = "teal")
    check("vlastná udalosť má bodku", Calendar.dots(listOf(feliz), LocalDate.of(2026, 11, 20), "me").isNotEmpty())
    check("deň len so všeobecnými meninami je bez bodky", Calendar.dots(emptyList(), LocalDate.of(2026, 11, 25), "me").isEmpty())
    check("zapnuté všeobecné meniny pridajú bodku (zostáva k dispozícii)", Calendar.dots(emptyList(), LocalDate.of(2026, 11, 25), "me", includeMeniny = true).isNotEmpty())

    // ---- formulár: Uložiť pri prázdnom názve (1.59) a zápis s vyplneným názvom
    val thu = LocalDate.of(2026, 11, 26)
    val alone = listOf(Calendar.HousePerson("me", "Tomáš", self = true))
    fun draft(
        name: String = "",
        birthday: Boolean = false,
        yearKnown: Boolean = false,
        birthYear: Int = 0,
        date: LocalDate = thu,
        yearly: Boolean = false,
        allDay: Boolean = false,
        startMin: Int = 14 * 60 + 45,
        endMin: Int = 15 * 60,
        offsets: List<Int> = listOf(0),
        remindMin: Int = 1440,
        allAudience: Boolean = true,
        selectedIds: Set<String> = emptySet(),
        legacyPartner: Boolean = false,
        existingOwnerId: String? = null,
        color: String = "peach",
        meninyOn: Boolean = false,
        meninyMonth: Int = 0,
        meninyDay: Int = 0,
    ) = Calendar.EventDraft(
        name = name, birthday = birthday, yearKnown = yearKnown, birthYear = birthYear, date = date,
        yearly = yearly, allDay = allDay, startMin = startMin, endMin = endMin, offsets = offsets,
        remindMin = remindMin, meninyOn = meninyOn, meninyMonth = meninyMonth, meninyDay = meninyDay,
        color = color, allAudience = allAudience, selectedIds = selectedIds, legacyPartner = legacyPartner,
        existingOwnerId = existingOwnerId,
    )

    val empty = draft(name = "")
    check("prázdny názov: treba doplniť", Calendar.formIssue(empty) == Calendar.EventFormIssue.NEED_NAME)
    check("prázdny názov: Zadaj názov", Calendar.formHint(empty) == "Zadaj názov")
    check("len medzery tiež treba názov", Calendar.formIssue(draft(name = "   ")) == Calendar.EventFormIssue.NEED_NAME)
    val filled = draft(name = "zubár")
    check("vyplnený názov je v poriadku", Calendar.formIssue(filled) == Calendar.EventFormIssue.NONE && Calendar.formHint(filled) == null)

    val saved = Calendar.buildEvent(filled, "me", alone, 1_000L, "new-1")
    check("názov sa očistí a veľké písmeno", saved.name == "Zubár")
    check("časovaná: začiatok 14:45 a koniec 15:00", saved.startMin == 14 * 60 + 45 && saved.endMin == 15 * 60)
    check("pripomienka 1 deň vopred ostane (remindMin 1440)", saved.remindMin == 1440)
    check("časovaná nemá denné offsety (1.54)", saved.offsets.isEmpty() && Events.dayOffsets(saved).isEmpty())
    check("jediný člen: audience both (skryté Pre koho, all=true, prázdne ids)", saved.audience == "both" && saved.audienceIds.isEmpty())
    check("jediný člen vidí uloženú udalosť", Calendar.visibleTo(saved, "me"))
    check("farba a dátum zo snímky", saved.color == "peach" && saved.year == 2026 && saved.month == 11 && saved.day == 26 && !saved.yearly && !saved.birthday)
    check("vlastník je tento telefón", saved.ownerId == "me" && saved.deviceId == "me")

    val inverted = Calendar.buildEvent(draft(name = "X", startMin = 15 * 60, endMin = 14 * 60 + 45), "me", alone, 1L, "inv")
    check("koniec pred začiatkom sa posunie na začiatok", inverted.startMin == 15 * 60 && inverted.endMin == 15 * 60)

    val emptyWho = Calendar.buildEvent(draft(name = "X", allAudience = false, selectedIds = emptySet()), "me", alone, 1L, "who")
    check("prázdny výber Pre koho (skrytý riadok) sa zapíše ako both, nie members bez id", emptyWho.audience == "both" && emptyWho.audienceIds.isEmpty())
    check("a je viditeľná na tomto telefóne", Calendar.visibleTo(emptyWho, "me"))

    val bdayEmpty = draft(name = "", birthday = true, allDay = true)
    check("narodeniny bez mena: Zadaj meno", Calendar.formIssue(bdayEmpty) == Calendar.EventFormIssue.NEED_NAME && Calendar.formHint(bdayEmpty) == "Zadaj meno")
    val born = Calendar.buildEvent(
        draft(name = "Jana", birthday = true, yearKnown = false, allDay = true, remindMin = 60, offsets = listOf(0, 1)),
        "me", alone, 2L, "b1",
    )
    check("narodeniny bez roku: yearly, bez času, rok 0", born.birthday && born.yearly && born.year == 0 && born.startMin < 0 && born.remindMin < 0)
    check("narodeniny si nechajú denné offsety", born.offsets == listOf(1, 0))
    val bdayYear = Calendar.buildEvent(
        draft(name = "Jana", birthday = true, yearKnown = true, birthYear = 1996, allDay = true, date = LocalDate.of(2026, 11, 26)),
        "me", alone, 3L, "b2",
    )
    check("narodeniny so známym rokom", bdayYear.year == 1996 && bdayYear.month == 11 && bdayYear.day == 26)

    val edited = Calendar.buildEvent(
        draft(name = "Zubár kontrola", startMin = 15 * 60, endMin = 16 * 60, remindMin = 10, color = "teal", existingOwnerId = "me"),
        "me", alone, 9_000L, "new-1",
    )
    check("úprava zachová id a vlastníka", edited.id == "new-1" && edited.ownerId == "me")
    check("úprava zmení názov, čas a pripomienku", edited.name == "Zubár kontrola" && edited.startMin == 15 * 60 && edited.remindMin == 10 && edited.color == "teal" && edited.updatedAt == 9_000L)

    val two = listOf(Calendar.HousePerson("me", "Tomáš", self = true), Calendar.HousePerson("her", "Jana"))
    val onlyHer = Calendar.buildEvent(draft(name = "Pre ňu", allAudience = false, selectedIds = setOf("her")), "me", two, 4L, "p")
    check("výber len Jana pri dvoch členoch ostáva partner (kompatibilita)", onlyHer.audience == "partner" && onlyHer.audienceIds.isEmpty())
    check("ja ju nevidím, ona áno", !Calendar.visibleTo(onlyHer, "me") && Calendar.visibleTo(onlyHer, "her"))

    println("CalendarTest OK")
}

