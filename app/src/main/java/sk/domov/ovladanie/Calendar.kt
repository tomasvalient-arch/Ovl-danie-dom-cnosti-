package sk.domov.ovladanie

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Kalendár nad existujúcimi [EventItem]: mriežka mesiaca (od pondelka), filtrovanie podľa
 * „pre koho“, výskyt v konkrétny deň, časované pripomienky. Bez Androidu, dá sa testovať.
 */
object Calendar {
    val WEEKDAYS = listOf("Po", "Ut", "St", "Št", "Pi", "So", "Ne")
    val MONTHS = listOf(
        "január", "február", "marec", "apríl", "máj", "jún",
        "júl", "august", "september", "október", "november", "december",
    )
    val MONTHS_GEN = listOf(
        "januára", "februára", "marca", "apríla", "mája", "júna",
        "júla", "augusta", "septembra", "októbra", "novembra", "decembra",
    )
    val WEEKDAYS_LONG = listOf("Pondelok", "Utorok", "Streda", "Štvrtok", "Piatok", "Sobota", "Nedeľa")

    const val COLOR_AMBER = "amber"
    val COLORS = listOf("amber", "rose", "teal", "blue", "purple", "peach")

    /** Hex farby bodiek / bloku (ARGB bez alfa v komentári pre widget). */
    fun colorHex(id: String): Long = when (id) {
        "rose" -> 0xFFE57373
        "teal" -> 0xFF4DB6AC
        "blue" -> 0xFF64B5F6
        "purple" -> 0xFFBA68C8
        "peach" -> 0xFFFFAB91
        else -> 0xFFFFB35A
    }

    data class CalDay(val date: LocalDate, val inMonth: Boolean)

    /** 6 týždňov × 7 dní, začiatok v pondelok, aj dni z predošlého / nasledujúceho mesiaca. */
    fun monthGrid(month: YearMonth): List<CalDay> {
        val first = month.atDay(1)
        val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
        return (0 until 42).map { i ->
            val d = start.plusDays(i.toLong())
            CalDay(d, d.year == month.year && d.monthValue == month.monthValue)
        }
    }

    fun weekStart(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())

    fun weekDays(date: LocalDate): List<LocalDate> {
        val start = weekStart(date)
        return (0 until 7).map { start.plusDays(it.toLong()) }
    }

    /** Má sa udalosť ukázať tomuto telefónu? */
    fun visibleTo(e: EventItem, myId: String): Boolean {
        if (e.deleted) return false
        if (e.audienceIds.isNotEmpty()) return myId in e.audienceIds
        if (e.audience.isBlank() || e.audience == "both" || e.audience == "all" || e.ownerId.isBlank()) return true
        return when (e.audience) {
            "me" -> e.ownerId == myId
            "partner" -> e.ownerId != myId
            else -> true
        }
    }

    /** Udalosti, ktoré má tento telefón ukazovať a pripomínať (rovnaké pravidlo ako [onDay]). */
    fun visibleAmong(events: List<EventItem>, myId: String): List<EventItem> =
        events.filter { visibleTo(it, myId) }

    fun happensOn(e: EventItem, date: LocalDate): Boolean = Events.happensOn(e, date)

    fun onDay(events: List<EventItem>, date: LocalDate, myId: String): List<EventItem> =
        events.filter { visibleTo(it, myId) }
            .flatMap { Events.parts(it) }
            .filter { happensOn(it, date) }
            .sortedWith(compareBy<EventItem> { if (it.startMin < 0) -1 else it.startMin }.thenBy { it.name.lowercase() })

    /** Farby bodiek v bunke mesiaca (max 3). Všeobecné meniny sa do bodiek nedávajú (len do zoznamu dňa). */
    fun dots(events: List<EventItem>, date: LocalDate, myId: String, includeMeniny: Boolean = false): List<String> {
        val ev = onDay(events, date, myId)
        val colors = ev.map { it.color.ifBlank { COLOR_AMBER } }.distinct().take(3).toMutableList()
        if (includeMeniny && ev.none { it.isMeniny } && Meniny.zoznam(date).isNotEmpty() && colors.size < 3) {
            colors += "peach"
        }
        return colors
    }

    fun monthTitle(month: YearMonth): String =
        MONTHS[month.monthValue - 1].replaceFirstChar { it.uppercase() } + " " + month.year

    fun dayTitle(date: LocalDate, today: LocalDate): String {
        val name = WEEKDAYS_LONG[date.dayOfWeek.value - 1]
        val body = "$name ${date.dayOfMonth}. ${MONTHS_GEN[date.monthValue - 1]}"
        return when (ChronoUnit.DAYS.between(today, date)) {
            0L -> "Dnes · $body"
            1L -> "Zajtra · $body"
            -1L -> "Včera · $body"
            else -> body
        }
    }

    /** Člen domácnosti pre výber „Pre koho“. */
    data class HousePerson(val id: String, val name: String, val self: Boolean = false) {
        fun chipLabel(): String = if (self) "$name · ja" else name
    }

    /** Výber adresátov v formulári. [legacyPartner] = staré „partner“ bez známych ostatných členov. */
    data class AudienceSel(val all: Boolean, val ids: Set<String>, val legacyPartner: Boolean = false)

    /**
     * Zoznam ľudí na čipy: tento telefón (vlastné meno) a ostatní členovia podľa mena.
     * Chýbajúce meno → „Člen 2“, nikdy „Priateľka“.
     */
    fun people(selfId: String, selfName: String, members: List<Member>): List<HousePerson> {
        val self = HousePerson(selfId, selfName.trim().ifBlank { "Ja" }, self = true)
        val others = members.filter { it.id.isNotBlank() && it.id != selfId }
            .sortedBy { it.name.ifBlank { it.id } }
        return listOf(self) + others.mapIndexed { i, m ->
            HousePerson(m.id, m.name.trim().ifBlank { "Člen ${i + 2}" })
        }
    }

    /** Doplní neznámych adresátov (napr. člen, ktorý ešte nie je v zozname) ako „Člen N“. */
    fun peopleWithAudience(people: List<HousePerson>, selectedIds: Set<String>): List<HousePerson> {
        val extra = selectedIds.filter { id -> id.isNotBlank() && people.none { it.id == id } }.sorted()
        return people + extra.mapIndexed { i, id -> HousePerson(id, "Člen ${people.size + i + 1}") }
    }

    fun namesFor(ids: Collection<String>, people: List<HousePerson>): List<String> {
        val unknown = ids.filter { id -> people.none { it.id == id } }.sorted()
        val order = ids.sortedBy { id ->
            val i = people.indexOfFirst { it.id == id }
            if (i >= 0) i else 1_000 + unknown.indexOf(id)
        }
        return order.map { id ->
            people.find { it.id == id }?.name
                ?: "Člen ${people.size + 1 + unknown.indexOf(id)}"
        }
    }

    fun decodeAudience(e: EventItem, people: List<HousePerson>): AudienceSel {
        if (e.audienceIds.isNotEmpty()) {
            val known = people.map { it.id }.toSet()
            val ids = e.audienceIds.filter { it.isNotBlank() }.toSet()
            if (known.isNotEmpty() && ids.containsAll(known) && ids.all { it in known }) {
                return AudienceSel(all = true, ids = emptySet())
            }
            return AudienceSel(all = false, ids = ids)
        }
        val owner = e.ownerId
        return when (e.audience) {
            "me" -> {
                val id = owner.ifBlank { people.firstOrNull { it.self }?.id.orEmpty() }
                AudienceSel(all = false, ids = if (id.isBlank()) emptySet() else setOf(id))
            }
            "partner" -> {
                val others = people.filter { it.id != owner }.map { it.id }.toSet()
                if (others.isEmpty()) AudienceSel(all = false, ids = emptySet(), legacyPartner = true)
                else AudienceSel(all = false, ids = others)
            }
            else -> AudienceSel(all = true, ids = emptySet())
        }
    }

    /**
     * Zápis spätne čitateľný starším telefónom: both / me / partner bez zoznamu id.
     * Konkrétna podmnožina (nie „všetci okrem autora“) ide ako members + audienceIds.
     */
    fun encodeAudience(sel: AudienceSel, ownerId: String, people: List<HousePerson>): Pair<String, List<String>> {
        if (sel.all) return "both" to emptyList()
        val ids = sel.ids.filter { it.isNotBlank() }.toSet()
        if (ids.isEmpty()) {
            return if (sel.legacyPartner) "partner" to emptyList() else "both" to emptyList()
        }
        val known = people.map { it.id }.toSet()
        if (known.isNotEmpty() && ids.containsAll(known) && ids.all { it in known }) {
            return "both" to emptyList()
        }
        if (ids == setOf(ownerId)) return "me" to emptyList()
        val others = known - ownerId
        if (others.isNotEmpty() && ids == others) return "partner" to emptyList()
        return "members" to ids.sorted()
    }

    /** Starý trojstavový štítok bez zoznamu členov (nikdy „Priateľka“). */
    fun audienceLabel(audience: String): String = when (audience) {
        "me" -> "Ja"
        "partner" -> "Ostatní"
        "members" -> ""
        else -> "Všetci"
    }

    fun audienceLabel(e: EventItem, people: List<HousePerson> = emptyList()): String {
        val sel = decodeAudience(e, people)
        if (sel.all) return if (people.size <= 1) "" else "Všetci"
        if (sel.legacyPartner) return "Ostatní"
        return namesFor(sel.ids, people).joinToString(", ")
    }

    fun remindLabel(allDay: Boolean, remindMin: Int, offsets: List<Int>): String {
        if (!allDay && remindMin >= 0) return when (remindMin) {
            0 -> "V čase začiatku"
            10 -> "10 minút vopred"
            30 -> "30 minút vopred"
            60 -> "1 hodinu vopred"
            120 -> "2 hodiny vopred"
            1440 -> "1 deň vopred"
            else -> if (remindMin < 60) "$remindMin min. vopred" else "${remindMin / 60} h vopred"
        }
        if (offsets.isEmpty()) return "Bez pripomienky"
        return offsets.sortedDescending().joinToString(", ") { Events.inText(it) }
    }

    fun repeatLabel(yearly: Boolean, birthday: Boolean): String = when {
        birthday || yearly -> "Každý rok"
        else -> "Neopakuje sa"
    }

    /**
     * Najbližší čas pripomienky pre udalosť s konkrétnym časom ([remindMin] >= 0, nie celý deň).
     * Ročné udalosti po tohtoročnom výskyte skúšajú budúci rok.
     */
    fun nextTimedAt(e: EventItem, now: LocalDateTime): LocalDateTime? {
        if (e.deleted || e.startMin < 0 || e.remindMin < 0) return null
        fun at(occ: LocalDate): LocalDateTime {
            val start = occ.atTime(LocalTime.of((e.startMin / 60).coerceIn(0, 23), (e.startMin % 60).coerceIn(0, 59)))
            return start.minusMinutes(e.remindMin.toLong())
        }
        val occ = Events.occurrence(e, now.toLocalDate()) ?: return null
        val first = at(occ)
        if (first.isAfter(now)) return first
        if (!e.yearly) return null
        val next = Events.occurrence(e, occ.plusDays(1)) ?: return null
        val second = at(next)
        return second.takeIf { it.isAfter(now) }
    }

    fun nextTimed(events: List<EventItem>, now: LocalDateTime): LocalDateTime? =
        events.mapNotNull { nextTimedAt(it, now) }.minOrNull()

    /** Kľúč vypálenej časovanej pripomienky, aby neprišla znova. */
    fun timedKey(e: EventItem, occ: LocalDate): String = "${e.id}|t|${e.remindMin}|$occ"

    /** Splatná časovaná pripomienka s dátumom výskytu (aj včerajším po polnoci do 3:00). */
    data class TimedDue(val event: EventItem, val occurrence: LocalDate, val key: String)

    /**
     * Časované pripomienky, ktoré už mali prísť ([now] je po čase pripomienky) a ešte nie sú vo [fired].
     * Dočasné dobehnutie: do konca dňa výskytu + 3 hodiny.
     */
    fun dueTimed(events: List<EventItem>, now: LocalDateTime, fired: Set<String>): List<TimedDue> {
        val out = ArrayList<TimedDue>()
        for (e in events) {
            if (e.deleted || e.startMin < 0 || e.remindMin < 0) continue
            val today = now.toLocalDate()
            val candidates = ArrayList<LocalDate>()
            Events.occurrence(e, today.minusDays(1))?.let { candidates += it }
            Events.occurrence(e, today)?.let { if (it !in candidates) candidates += it }
            for (occ in candidates) {
                val start = occ.atTime(LocalTime.of((e.startMin / 60).coerceIn(0, 23), (e.startMin % 60).coerceIn(0, 59)))
                val at = start.minusMinutes(e.remindMin.toLong())
                val until = occ.plusDays(1).atStartOfDay().plusHours(3)
                if (!at.isAfter(now) && now.isBefore(until)) {
                    val key = timedKey(e, occ)
                    if (key !in fired) out += TimedDue(e, occ, key)
                }
            }
        }
        return out
    }

    /** Ďalší budík: denný čas pripomienok alebo bližšia časovaná pripomienka. */
    fun nextAlarm(events: List<EventItem>, now: LocalDateTime, daily: LocalTime): LocalDateTime {
        val dailyAt = Events.nextCheck(now, daily)
        val timed = nextTimed(events, now) ?: return dailyAt
        return if (timed.isBefore(dailyAt)) timed else dailyAt
    }

    /** Prečo sa formulár ešte nemá zapísať. Iné polia (časy, pripomienka, skryté „pre koho“) uloženie neblokujú. */
    enum class EventFormIssue { NONE, NEED_NAME }

    /** Stav formulára Nová / Upraviť udalosť (bez Androidu, testovateľné). */
    data class EventDraft(
        val name: String,
        val birthday: Boolean = false,
        val yearKnown: Boolean = false,
        val birthYear: Int = 0,
        val date: LocalDate,
        val yearly: Boolean = false,
        val allDay: Boolean = true,
        val startMin: Int = 14 * 60,
        val endMin: Int = 15 * 60,
        val offsets: List<Int> = listOf(0),
        val remindMin: Int = 60,
        val meninyOn: Boolean = false,
        val meninyMonth: Int = 0,
        val meninyDay: Int = 0,
        val color: String = "amber",
        val allAudience: Boolean = true,
        val selectedIds: Set<String> = emptySet(),
        val legacyPartner: Boolean = false,
        val existingOwnerId: String? = null,
    )

    fun formIssue(draft: EventDraft): EventFormIssue =
        if (Events.clean(draft.name).isEmpty()) EventFormIssue.NEED_NAME else EventFormIssue.NONE

    /** Text pre pole názvu / mena, keď uloženie ešte nie je možné. */
    fun formHint(draft: EventDraft): String? = when (formIssue(draft)) {
        EventFormIssue.NEED_NAME -> if (draft.birthday) "Zadaj meno" else "Zadaj názov"
        EventFormIssue.NONE -> null
    }

    /**
     * Zostaví [EventItem] z formulára. Pri jedinom členovi domácnosti (skryté „Pre koho“)
     * ostáva výber „všetci“ / both. Časovaná udalosť má prázdne denné offsety (platí len [EventItem.remindMin]).
     */
    fun buildEvent(draft: EventDraft, myId: String, people: List<HousePerson>, nowMs: Long, id: String): EventItem {
        val owner = draft.existingOwnerId?.ifBlank { myId } ?: myId
        val (aud, aids) = encodeAudience(
            AudienceSel(draft.allAudience, draft.selectedIds, draft.legacyPartner),
            owner,
            people,
        )
        return EventItem(
            id = id,
            name = Events.clean(draft.name),
            birthday = draft.birthday,
            year = when {
                draft.birthday && draft.yearKnown -> draft.birthYear
                draft.birthday -> 0
                draft.yearly -> 0
                else -> draft.date.year
            },
            month = draft.date.monthValue,
            day = draft.date.dayOfMonth,
            yearly = draft.birthday || draft.yearly,
            offsets = if (draft.allDay || draft.birthday) draft.offsets.distinct().sortedDescending() else emptyList(),
            meninyMonth = if (draft.birthday && draft.meninyOn) draft.meninyMonth else 0,
            meninyDay = if (draft.birthday && draft.meninyOn) draft.meninyDay else 0,
            startMin = if (draft.allDay || draft.birthday) -1 else draft.startMin,
            endMin = if (draft.allDay || draft.birthday) -1 else draft.endMin.coerceAtLeast(draft.startMin),
            color = draft.color,
            audience = aud,
            ownerId = owner,
            updatedAt = nowMs,
            deleted = false,
            remindMin = if (draft.allDay || draft.birthday) -1 else draft.remindMin,
            deviceId = myId,
            audienceIds = aids,
        )
    }
}
