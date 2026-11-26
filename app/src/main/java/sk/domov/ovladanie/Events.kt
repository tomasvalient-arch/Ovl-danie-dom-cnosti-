package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Udalosť alebo narodeniny s upozorneniami vopred. */
data class EventItem(
    val id: String,
    val name: String,
    /** Narodeniny: opakujú sa každý rok a upozornenie vie napísať vek. */
    val birthday: Boolean,
    /** Jednorazová udalosť: jej rok. Narodeniny: rok narodenia (0 = neznámy). Opakovaná udalosť: 0. */
    val year: Int,
    val month: Int,
    val day: Int,
    val yearly: Boolean,
    /** Za koľko dní vopred upozorniť (0 = v ten deň). */
    val offsets: List<Int>,
    /** Meniny osoby (len pri narodeninách): mesiac a deň, 0 = bez menín. Upozornenia platia pre narodeniny aj meniny. */
    val meninyMonth: Int = 0,
    val meninyDay: Int = 0,
    /** Len odvodená položka (nikdy sa neukladá): meniny osoby ako samostatný dátum, pozri [Events.parts]. */
    val isMeniny: Boolean = false,
    /** Minúty od polnoci; záporné = celý deň (staré udalosti bez času). */
    val startMin: Int = -1,
    val endMin: Int = -1,
    /** amber, rose, teal, blue, purple, peach */
    val color: String = "amber",
    /**
     * Kto má udalosť vidieť. Kompatibilné hodnoty: me | partner | both (relatívne k [ownerId]).
     * Nové výbery konkrétnych členov: members + [audienceIds]. Prázdne [audienceIds] = použiť [audience].
     */
    val audience: String = "both",
    val ownerId: String = "",
    val updatedAt: Long = 0L,
    val deleted: Boolean = false,
    /** Minúty pred začiatkom pre časovanú pripomienku; -1 = len denné [offsets]. */
    val remindMin: Int = -1,
    /** Telefón, ktorý položku naposledy zmenil (remíza pri zlučovaní). */
    val deviceId: String = "",
    /** Konkrétni členovia (id telefónov). Platí pri audience = members, alebo ako doplnok. */
    val audienceIds: List<String> = emptyList(),
)

/** Upozornenie, ktoré je na rade: [offset] je najbližšie k udalosti, [keys] sú všetky upozornenia, ktoré sa tým vybavili. */
data class EventDue(val event: EventItem, val occurrence: LocalDate, val offset: Int, val keys: List<String>)

/**
 * Udalosti a narodeniny bez Androidu: najbližší výskyt, ktoré upozornenia sú na rade, texty a uloženie do JSON.
 * Budík v telefóne sa spustí raz denne v nastavený čas; ostatné je výpočet v tomto súbore.
 */
object Events {
    const val DEFAULT_TIME_MIN = 8 * 60
    const val MAX_EVENTS = 300
    const val MAX_NAME = 60
    const val MAX_OFFSET = 365

    /** Prípona id odvodenej položky s meninami; ostáva v kľúčoch odoslaných upozornení a v id notifikácie. */
    const val MENINY_SUFFIX = "~n"

    /** Rýchle voľby „upozorniť vopred“ (dni). */
    val PRESETS = listOf(0, 1, 3, 7, 14, 30)

    private val MONTHS = listOf(
        "januára", "februára", "marca", "apríla", "mája", "júna",
        "júla", "augusta", "septembra", "októbra", "novembra", "decembra",
    )
    private val MONTHS_SHORT = listOf("jan", "feb", "mar", "apr", "máj", "jún", "júl", "aug", "sep", "okt", "nov", "dec")
    private val WEEKDAYS = listOf("pondelok", "utorok", "streda", "štvrtok", "piatok", "sobota", "nedeľa")

    fun clean(text: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).trim()
        return t.replaceFirstChar { it.uppercase() }
    }

    /** Dátum, ktorý sa nikdy nezrúti: 29. 2. v nepriestupnom roku je 28. 2. */
    fun safeDate(year: Int, month: Int, day: Int): LocalDate {
        val ym = YearMonth.of(year, month.coerceIn(1, 12))
        return ym.atDay(day.coerceIn(1, ym.lengthOfMonth()))
    }

    /** Najbližší výskyt od [today] (vrátane dneška); jednorazová udalosť, ktorá už prešla, nemá žiadny (null). */
    fun occurrence(e: EventItem, today: LocalDate): LocalDate? {
        if (e.yearly) {
            val thisYear = safeDate(today.year, e.month, e.day)
            return if (!thisYear.isBefore(today)) thisYear else safeDate(today.year + 1, e.month, e.day)
        }
        val d = safeDate(maxOf(e.year, 1), e.month, e.day)
        return if (!d.isBefore(today)) d else null
    }

    fun daysUntil(e: EventItem, today: LocalDate): Int? =
        occurrence(e, today)?.let { ChronoUnit.DAYS.between(today, it).toInt() }

    /**
     * Či [e] (už ako jedna časť z [parts]) pripadá na [date].
     * Rovnaké pravidlá ako [occurrence]: každoročné podľa mesiaca a dňa, jednorazové podľa uloženého roka.
     * Kalendár, domov, ráno, widget aj pripomienky používajú túto funkciu spolu s [occurrence].
     */
    fun happensOn(e: EventItem, date: LocalDate): Boolean {
        if (e.deleted) return false
        return if (e.yearly) safeDate(date.year, e.month, e.day) == date
        else safeDate(maxOf(e.year, 1), e.month, e.day) == date
    }

    /** Vek, ktorý osoba v ten deň dovŕši; null, ak to nie sú narodeniny alebo rok nie je známy. */
    fun age(e: EventItem, occ: LocalDate): Int? =
        if (e.birthday && e.year > 0 && occ.year > e.year) occ.year - e.year else null

    fun hasMeniny(e: EventItem): Boolean = e.birthday && !e.isMeniny && e.meninyMonth in 1..12 && e.meninyDay in 1..31

    /**
     * Dátumy, ktoré sa pripomínajú: samotná udalosť alebo narodeniny a pri osobe s meninami aj jej meniny
     * (odvodená položka s id + [MENINY_SUFFIX], každoročná, bez veku). Upozornenia vopred sú pre oba rovnaké.
     */
    fun parts(e: EventItem): List<EventItem> {
        if (!hasMeniny(e)) return listOf(e)
        val m = e.copy(
            id = e.id + MENINY_SUFFIX, birthday = false, isMeniny = true, year = 0,
            month = e.meninyMonth, day = e.meninyDay, yearly = true, meninyMonth = 0, meninyDay = 0,
        )
        return listOf(e, m)
    }

    fun expand(events: List<EventItem>): List<EventItem> = events.filter { !it.deleted }.flatMap { parts(it) }

    /** Id uloženého záznamu, z ktorého je odvodená položka (pre meniny id bez prípony). */
    fun baseId(id: String): String = id.removeSuffix(MENINY_SUFFIX)

    /** Okno upozornení: koľko dní vopred sa položka začne ukazovať (najväčšia voľba vopred); null = bez upozornenia, nikdy sa neukáže na Domove. */
    /**
     * Denné offsety („v ten deň“, „3 dni vopred“…). Pri udalosti s konkrétnym časom sa nepoužívajú –
     * tam platí len [EventItem.remindMin]. Staré záznamy s offsets=[0] pri časovanej udalosti sa ignorujú.
     */
    fun dayOffsets(e: EventItem): List<Int> = if (e.startMin >= 0) emptyList() else e.offsets

    /** Vyprázdni denné offsety u časovanej udalosti (migrácia pri čítaní). */
    fun normalize(e: EventItem): EventItem =
        if (e.startMin >= 0 && e.offsets.isNotEmpty()) e.copy(offsets = emptyList()) else e

    /** Okno na Domove: časovaná udalosť sa ukáže v deň výskytu; celodenná podľa denných offsetov. */
    fun window(e: EventItem): Int? = if (e.startMin >= 0) 0 else e.offsets.maxOrNull()

    /** Odvodené položky rozdelené podľa blízkosti: [soon] sú v okne upozornení (ukazujú sa na Domove), [later] sú budúce mimo okna, [past] jednorazové, ktoré už prešli. */
    data class EventGroups(val soon: List<EventItem>, val later: List<EventItem>, val past: List<EventItem>)

    fun groups(events: List<EventItem>, today: LocalDate): EventGroups {
        val soon = ArrayList<EventItem>()
        val later = ArrayList<EventItem>()
        val past = ArrayList<EventItem>()
        for (p in expand(events)) {
            val days = daysUntil(p, today)
            val w = window(p)
            if (days == null) past += p
            else if (w != null && days <= w) soon += p
            else later += p
        }
        val byDate = compareBy<EventItem>({ daysUntil(it, today) ?: Int.MAX_VALUE }, { it.name.lowercase() }, { if (it.isMeniny) 1 else 0 })
        return EventGroups(
            soon.sortedWith(byDate),
            later.sortedWith(byDate),
            past.sortedByDescending { safeDate(maxOf(it.year, 1), it.month, it.day) },
        )
    }

    /** Poradie v zozname: najbližšie hore, potom už prešlé (najnovšie prvé). */
    fun arrange(events: List<EventItem>, today: LocalDate): List<EventItem> {
        val upcoming = events.filter { occurrence(it, today) != null }
            .sortedWith(compareBy<EventItem>({ daysUntil(it, today) ?: Int.MAX_VALUE }, { it.name.lowercase() }))
        val past = events.filter { occurrence(it, today) == null }
            .sortedByDescending { safeDate(maxOf(it.year, 1), it.month, it.day) }
        return upcoming + past
    }

    // ------------------------------------------------------------------ čo je na rade

    fun key(e: EventItem, offset: Int, occ: LocalDate): String = "${e.id}|$offset|$occ"

    private fun isDue(trigger: LocalDate, now: LocalDateTime, time: LocalTime): Boolean {
        val today = now.toLocalDate()
        return trigger.isBefore(today) || (trigger == today && !now.toLocalTime().isBefore(time))
    }

    /**
     * Upozornenia, ktorých čas už nastal a ešte sa neposlali. Ak ich naraz nastalo viac pre jednu udalosť
     * (telefón bol vypnutý), pošle sa jedno – to najbližšie k udalosti – a ostatné sa vybavia s ním.
     */
    fun due(events: List<EventItem>, fired: Set<String>, now: LocalDateTime, time: LocalTime): List<EventDue> {
        val today = now.toLocalDate()
        val out = ArrayList<EventDue>()
        for (e in expand(events)) {
            val occ = occurrence(e, today) ?: continue
            val keys = ArrayList<String>()
            var best: Int? = null
            for (off in dayOffsets(e).distinct()) {
                val k = key(e, off, occ)
                if (k in fired) continue
                if (isDue(occ.minusDays(off.toLong()), now, time)) {
                    keys += k
                    if (best == null || off < best) best = off
                }
            }
            if (best != null) out += EventDue(e, occ, best, keys)
        }
        return out
    }

    /**
     * Po uložení udalosti sa upozornenia, ktorých čas už prešiel, považujú za vybavené (inak by po pridaní narodenín,
     * ktoré sú o 3 dni, hneď prišlo „týždeň vopred“). Staré záznamy tej istej udalosti sa zahodia.
     */
    fun afterSave(fired: Set<String>, e: EventItem, now: LocalDateTime, time: LocalTime): Set<String> {
        val kept = dropKeys(fired, e.id).toMutableSet()
        for (p in parts(e)) {
            val occ = occurrence(p, now.toLocalDate()) ?: continue
            for (off in dayOffsets(p).distinct()) {
                if (isDue(occ.minusDays(off.toLong()), now, time)) kept += key(p, off, occ)
            }
        }
        return kept
    }

    /** Záznamy o odoslaných upozorneniach bez tých, ktoré patria udalosti [id] (aj jej meninám). */
    fun dropKeys(fired: Set<String>, id: String): Set<String> =
        fired.filterNot { it.startsWith("$id|") || it.startsWith(id + MENINY_SUFFIX + "|") }.toSet()

    /** Zahodí záznamy o upozorneniach, ktorých udalosť už prešla. */
    fun prune(fired: Set<String>, today: LocalDate): Set<String> = fired.filter { k ->
        runCatching { !LocalDate.parse(k.substringAfterLast('|')).isBefore(today.minusDays(1)) }.getOrDefault(false)
    }.toSet()

    /** Najbližšia denná kontrola: dnes v nastavený čas, ak ešte neprešiel, inak zajtra. */
    fun nextCheck(now: LocalDateTime, time: LocalTime): LocalDateTime {
        val today = now.toLocalDate().atTime(time)
        return if (now.isBefore(today)) today else today.plusDays(1)
    }

    // ------------------------------------------------------------------ texty

    fun daysWord(n: Int): String = when {
        n == 1 -> "deň"
        n in 2..4 -> "dni"
        else -> "dní"
    }

    fun yearsText(n: Int): String = when {
        n == 1 -> "1 rok"
        n in 2..4 -> "$n roky"
        else -> "$n rokov"
    }

    /** „dnes“, „zajtra“, „o týždeň“, „o 3 dni“, „o 12 dní“. */
    fun inText(days: Int): String = when {
        days <= 0 -> "dnes"
        days == 1 -> "zajtra"
        days == 7 -> "o týždeň"
        days == 14 -> "o 2 týždne"
        else -> "o $days ${daysWord(days)}"
    }

    /** Veľké číslo a malý popis do zoznamu: („12“, „dní“), („Dnes“, „“), („Zajtra“, „“). */
    fun countdown(days: Int): Pair<String, String> = when {
        days <= 0 -> "Dnes" to ""
        days == 1 -> "Zajtra" to ""
        else -> "$days" to daysWord(days)
    }

    fun dateText(d: LocalDate, weekday: Boolean = false, year: Boolean = false): String {
        val base = "${d.dayOfMonth}. ${MONTHS[d.monthValue - 1]}"
        val withYear = if (year) "$base ${d.year}" else base
        return if (weekday) "${WEEKDAYS[d.dayOfWeek.value - 1]} $withYear" else withYear
    }

    private val TYPE_PREFIX = Regex("""(?i)^(meniny|narodeniny)\s*[-–—:]\s*""")
    private val TYPE_SUFFIX = Regex("""(?i)\s*[-–—:]?\s*(meniny|narodeniny)\s*$""")

    /** Meno osoby / udalosti bez predpony či prípony „Meniny“ / „Narodeniny“. */
    fun displayName(e: EventItem): String {
        var n = e.name.trim()
        n = n.replace(TYPE_PREFIX, "").trim()
        n = n.replace(TYPE_SUFFIX, "").trim()
        return n.ifBlank { e.name.trim() }
    }

    /**
     * Nadpis na domove, v kalendári, v upozornení a vo widgete.
     * Typ je oddelený od mena („Katkarinka · Narodeniny“), nikdy sa nepredponuje
     * „Meniny:“ na názov, ktorý už typ obsahuje.
     */
    fun title(e: EventItem): String {
        val name = displayName(e)
        val kind = partLabel(e)
        return if (kind.isEmpty()) name else "$name · $kind"
    }

    /** „Narodeniny“, „Meniny“ alebo prázdny text pre obyčajnú udalosť. */
    fun partLabel(e: EventItem): String = when {
        e.isMeniny -> "Meniny"
        e.birthday -> "Narodeniny"
        else -> ""
    }

    /** „Dnes“, „Zajtra“, inak dátum („piatok 20. novembra“) alebo „O 3 dni“. */
    fun whenLabel(days: Int, date: LocalDate? = null): String = when {
        days <= 0 -> "Dnes"
        days == 1 -> "Zajtra"
        date != null -> dateText(date, weekday = true)
        else -> inText(days).replaceFirstChar { it.uppercase() }
    }

    fun eventsWord(n: Int): String = when {
        n == 1 -> "1 udalosť"
        n in 2..4 -> "$n udalosti"
        else -> "$n udalostí"
    }

    /** Text na pilulke s odpočtom: „Dnes“, „Zajtra“, „4 dni“. */
    fun pillText(days: Int): String = countdown(days).let { if (it.second.isEmpty()) it.first else it.first + " " + it.second }

    /** Vek pri najbližších narodeninách („35 rokov“) alebo null. */
    fun ageCaption(e: EventItem, today: LocalDate): String? {
        val occ = occurrence(e, today) ?: return null
        val a = age(e, occ) ?: return null
        return yearsText(a)
    }

    /** Skratka mesiaca pre blok s dátumom v zozname: „mar“, „júl“. */
    fun monthShort(d: LocalDate): String = MONTHS_SHORT[d.monthValue - 1]

    /** Text voľby „vopred“ na čipe: „V ten deň“, „1 deň“, „3 dni“, „1 týždeň“, „30 dní“. */
    fun offsetLabel(off: Int): String = when (off) {
        0 -> "V ten deň"
        7 -> "1 týždeň"
        14 -> "2 týždne"
        else -> "$off ${daysWord(off)}"
    }

    /** „Upozorní: 1 týždeň vopred, v ten deň“. */
    fun offsetsText(offsets: List<Int>): String {
        if (offsets.isEmpty()) return "Bez upozornenia"
        val parts = offsets.distinct().sortedDescending().map { if (it == 0) "v ten deň" else offsetLabel(it).lowercase() + " vopred" }
        return "Upozorní: " + parts.joinToString(", ")
    }

    /** Časový úsek „14:00“ / „14:00–15:00“, prázdne pri celom dni. */
    fun timeRange(e: EventItem): String {
        if (e.startMin < 0) return ""
        val a = clockMinutes(e.startMin)
        return if (e.endMin > e.startMin) "$a–${clockMinutes(e.endMin)}" else a
    }

    fun clockMinutes(minutes: Int): String =
        String.format(java.util.Locale.ROOT, "%d:%02d", (minutes / 60).coerceIn(0, 23), (minutes % 60).coerceIn(0, 59))

    /** Druhý riadok v zozname: „nedeľa 25. novembra · každý rok · bude mať 30 rokov“. */
    fun dateLine(e: EventItem, today: LocalDate): String {
        val occ = occurrence(e, today)
        val parts = ArrayList<String>()
        if (occ != null) {
            parts += dateText(occ, weekday = true, year = !e.yearly)
            if (e.yearly) parts += "každý rok"
            val time = timeRange(e)
            if (time.isNotEmpty()) parts += time
            val a = age(e, occ)
            if (a != null) parts += (if (occ == today) "má " else "bude mať ") + yearsText(a)
        } else {
            parts += dateText(safeDate(maxOf(e.year, 1), e.month, e.day), year = true)
        }
        return parts.joinToString(" · ")
    }

    fun notificationBody(e: EventItem, occ: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(today, occ).toInt()
        val parts = ArrayList<String>()
        parts += inText(days).replaceFirstChar { it.uppercase() }
        parts += dateText(occ, weekday = true)
        val time = timeRange(e)
        if (time.isNotEmpty()) parts += time
        val a = age(e, occ)
        if (a != null) parts += (if (days <= 0) "má " else "bude mať ") + yearsText(a)
        return parts.joinToString(" · ")
    }

    /** Text tenkého riadka na Domove, keď sa nič nechystá. */
    fun slimText(events: List<EventItem>): String = if (events.isEmpty()) "Pridať" else "Nič sa nechystá"

    /** Podnadpis zoznamu udalostí: „3 čoskoro · 2 neskôr“. */
    fun subtitle(events: List<EventItem>, today: LocalDate): String {
        if (events.isEmpty()) return "Zatiaľ žiadne udalosti"
        val g = groups(events, today)
        val parts = ArrayList<String>()
        val first = if (g.soon.isEmpty()) "Nič sa nechystá" else "${g.soon.size} čoskoro"
        parts += first
        if (g.later.isNotEmpty()) parts += "${g.later.size} neskôr"
        if (g.past.isNotEmpty()) parts += "${g.past.size} prešlo"
        return parts.joinToString(" · ")
    }

    /** Podnadpis kalendára: koľko je dnes a ktorá udalosť je ďalšia. */
    fun headerSubtitle(events: List<EventItem>, today: LocalDate): String {
        if (events.none { !it.deleted }) return "Zatiaľ žiadne udalosti"
        val parts = expand(events)
        val todayCount = parts.count { happensOn(it, today) }
        val todayText = if (todayCount == 0) "Dnes nič" else "Dnes ${eventsWord(todayCount)}"
        val next = parts.mapNotNull { e ->
            val occ = occurrence(e, today) ?: return@mapNotNull null
            if (occ.isAfter(today)) e to occ else null
        }.minWithOrNull(
            compareBy({ it.second }, { displayName(it.first).lowercase() }, { if (it.first.isMeniny) 1 else 0 }),
        )
        val nextText = next?.let { (e, d) ->
            val days = ChronoUnit.DAYS.between(today, d).toInt()
            "ďalšia: ${whenLabel(days, d)} ${title(e)}"
        } ?: return todayText
        return "$todayText · $nextText"
    }

    // ------------------------------------------------------------------ plán upozornení (náhľad v dialógu)

    /** Kedy upozornenia skutočne prídu ([upcoming]: za koľko dní vopred a v ktorý deň) a ktoré už prešli ([skipped]). */
    data class ReminderPlan(val upcoming: List<Pair<Int, LocalDate>>, val skipped: List<Int>)

    fun plan(e: EventItem, now: LocalDateTime, time: LocalTime): ReminderPlan {
        val offs = e.offsets.distinct().sortedDescending()
        val occ = occurrence(e, now.toLocalDate()) ?: return ReminderPlan(emptyList(), offs)
        val up = ArrayList<Pair<Int, LocalDate>>()
        val skipped = ArrayList<Int>()
        for (off in offs) {
            val trigger = occ.minusDays(off.toLong())
            if (isDue(trigger, now, time)) skipped += off else up += off to trigger
        }
        return ReminderPlan(up, skipped)
    }

    /** „v ten deň“, „1 týždeň vopred“, „3 dni vopred“. */
    fun offsetPhrase(off: Int): String = if (off == 0) "v ten deň" else offsetLabel(off).lowercase() + " vopred"

    /** Riadky pod voľbami v dialógu: „Príde o 8:00: 18. novembra (1 týždeň vopred), …“ a „Už prešlo: …“. */
    fun planLines(p: ReminderPlan, time: LocalTime): List<String> {
        val clock = String.format(java.util.Locale.ROOT, "%d:%02d", time.hour, time.minute)
        val lines = ArrayList<String>()
        if (p.upcoming.isNotEmpty()) {
            lines += "Príde o $clock: " + p.upcoming.joinToString(", ") { dateText(it.second) + " (" + offsetPhrase(it.first) + ")" }
        }
        if (p.skipped.isNotEmpty()) lines += "Už prešlo, nepríde: " + p.skipped.joinToString(", ") { offsetPhrase(it) }
        if (lines.isEmpty()) lines += "Bez upozornenia – nepríde nič."
        return lines
    }

    /**
     * Riadky plánu pre všetky dátumy udalosti. Pri osobe s meninami začína každý riadok názvom dátumu
     * („Narodeniny – príde o 8:00: …“, „Meniny – už prešlo, nepríde: …“).
     */
    fun planLinesFor(e: EventItem, now: LocalDateTime, time: LocalTime): List<String> {
        if (e.offsets.isEmpty()) return listOf("Bez upozornenia – nepríde nič.")
        val ps = parts(e)
        if (ps.size == 1) return planLines(plan(ps[0], now, time), time)
        val out = ArrayList<String>()
        for (p in ps) for (line in planLines(plan(p, now, time), time)) {
            out += partLabel(p) + " – " + line.replaceFirstChar { it.lowercase() }
        }
        return out
    }


    private fun idsFrom(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            val id = a.optString(i).trim()
            if (id.isNotEmpty() && id !in out) out += id
        }
        return out
    }

    // ------------------------------------------------------------------ uloženie

    fun toJson(events: List<EventItem>): String {
        val a = JSONArray()
        events.forEach {
            val o = JSONObject().put("i", it.id).put("n", it.name).put("b", it.birthday).put("y", it.year)
                .put("m", it.month).put("d", it.day).put("r", it.yearly).put("o", JSONArray(it.offsets))
            if (hasMeniny(it)) o.put("mm", it.meninyMonth).put("md", it.meninyDay)
            if (it.startMin >= 0) o.put("sm", it.startMin)
            if (it.endMin >= 0) o.put("em", it.endMin)
            if (it.color.isNotBlank() && it.color != "amber") o.put("c", it.color)
            if (it.audience.isNotBlank() && it.audience != "both") o.put("a", it.audience)
            if (it.audienceIds.isNotEmpty()) o.put("ai", JSONArray(it.audienceIds))
            if (it.ownerId.isNotBlank()) o.put("w", it.ownerId)
            if (it.updatedAt != 0L) o.put("u", it.updatedAt)
            if (it.deleted) o.put("x", true)
            if (it.remindMin >= 0) o.put("rm", it.remindMin)
            if (it.deviceId.isNotBlank()) o.put("dv", it.deviceId)
            a.put(o)
        }
        return a.toString()
    }

    /** Poškodený alebo prázdny záznam = žiadne udalosti (nikdy výnimka); neplatné hodnoty sa opravia. */
    fun fromJson(raw: String?): List<EventItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            val out = ArrayList<EventItem>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val name = clean(o.optString("n"))
                val month = o.optInt("m", 0)
                val day = o.optInt("d", 0)
                val deleted = o.optBoolean("x", false)
                if (month !in 1..12 || day !in 1..31) continue
                if (name.isEmpty() && !deleted) continue
                val birthday = o.optBoolean("b")
                val mm = o.optInt("mm", 0)
                val md = o.optInt("md", 0)
                val withMeniny = birthday && mm in 1..12 && md in 1..31
                val offs = ArrayList<Int>()
                val oa = o.optJSONArray("o")
                if (oa != null) for (j in 0 until oa.length()) {
                    val v = oa.optInt(j, -1)
                    if (v in 0..MAX_OFFSET) offs += v
                }
                val startMin = o.optInt("sm", -1)
                out += EventItem(
                    id = o.optString("i").ifBlank { UUID.randomUUID().toString() },
                    name = name,
                    birthday = birthday,
                    year = o.optInt("y", 0).coerceAtLeast(0),
                    month = month,
                    day = day,
                    yearly = o.optBoolean("r"),
                    offsets = if (startMin >= 0) emptyList() else offs.distinct().sortedDescending(),
                    meninyMonth = if (withMeniny) mm else 0,
                    meninyDay = if (withMeniny) md else 0,
                    startMin = startMin,
                    endMin = o.optInt("em", -1),
                    color = o.optString("c").ifBlank { "amber" },
                    audience = o.optString("a").ifBlank { "both" },
                    ownerId = o.optString("w"),
                    updatedAt = o.optLong("u"),
                    deleted = deleted,
                    remindMin = o.optInt("rm", -1),
                    deviceId = o.optString("dv"),
                    audienceIds = idsFrom(o.optJSONArray("ai")),
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun firedToJson(fired: Set<String>): String = JSONArray(fired.toList()).toString()

    fun firedFromJson(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }
}

/**
 * Výber dátumu na bubnoch (deň, mesiac, rok) bez Androidu. Bubon sa po zastavení opraví na platný dátum:
 * deň sa skráti na dĺžku mesiaca (31. → 28. 2.) a dátum sa dá do rozsahu [min, max] (jednorazová udalosť nie je v minulosti,
 * narodený nie je v budúcnosti). Hodnoty, ktoré sa nedajú vybrať, sa na bubne zašednú.
 */
object DatePick {
    /** Referenčný rok pre dátumy bez roku (meniny, každoročné udalosti): priestupný, aby sa dalo vybrať aj 29. 2. */
    const val LEAP_YEAR = 2024

    fun lengthOf(year: Int, month: Int): Int = YearMonth.of(year, month.coerceIn(1, 12)).lengthOfMonth()

    /** Dátum sa dá vybrať: deň v mesiaci existuje a je v rozsahu (null = bez hranice). */
    fun isValid(year: Int, month: Int, day: Int, min: LocalDate?, max: LocalDate?): Boolean {
        if (month !in 1..12 || day < 1 || day > lengthOf(year, month)) return false
        val d = LocalDate.of(year, month, day)
        return (min == null || !d.isBefore(min)) && (max == null || !d.isAfter(max))
    }

    /** Opraví vybranú trojicu na platný dátum: deň skráti na dĺžku mesiaca, potom ho dá do rozsahu. */
    fun clamp(year: Int, month: Int, day: Int, min: LocalDate?, max: LocalDate?): LocalDate {
        val m = month.coerceIn(1, 12)
        val d = LocalDate.of(year, m, day.coerceIn(1, lengthOf(year, m)))
        if (min != null && d.isBefore(min)) return min
        if (max != null && d.isAfter(max)) return max
        return d
    }

    /** Mesiac má aspoň jeden deň, ktorý sa dá vybrať (inak sa na bubne zašedne celý). */
    fun monthHasValid(year: Int, month: Int, min: LocalDate?, max: LocalDate?): Boolean {
        val ym = YearMonth.of(year, month.coerceIn(1, 12))
        return (min == null || !ym.atEndOfMonth().isBefore(min)) && (max == null || !ym.atDay(1).isAfter(max))
    }
}
