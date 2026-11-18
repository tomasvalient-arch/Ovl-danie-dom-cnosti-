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

    /** Rýchle voľby „upozorniť vopred“ (dni). */
    val PRESETS = listOf(0, 1, 3, 7, 14, 30)

    private val MONTHS = listOf(
        "januára", "februára", "marca", "apríla", "mája", "júna",
        "júla", "augusta", "septembra", "októbra", "novembra", "decembra",
    )
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

    /** Vek, ktorý osoba v ten deň dovŕši; null, ak to nie sú narodeniny alebo rok nie je známy. */
    fun age(e: EventItem, occ: LocalDate): Int? =
        if (e.birthday && e.year > 0 && occ.year > e.year) occ.year - e.year else null

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
        for (e in events) {
            val occ = occurrence(e, today) ?: continue
            val keys = ArrayList<String>()
            var best: Int? = null
            for (off in e.offsets.distinct()) {
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
        val kept = fired.filterNot { it.startsWith(e.id + "|") }.toMutableSet()
        val occ = occurrence(e, now.toLocalDate()) ?: return kept
        for (off in e.offsets.distinct()) {
            if (isDue(occ.minusDays(off.toLong()), now, time)) kept += key(e, off, occ)
        }
        return kept
    }

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

    fun title(e: EventItem): String = if (e.birthday) "Narodeniny: ${e.name}" else e.name

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

    /** Druhý riadok v zozname: „nedeľa 25. novembra · každý rok · bude mať 30 rokov“. */
    fun dateLine(e: EventItem, today: LocalDate): String {
        val occ = occurrence(e, today)
        val parts = ArrayList<String>()
        if (occ != null) {
            parts += dateText(occ, weekday = true, year = !e.yearly)
            if (e.yearly) parts += "každý rok"
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
        val a = age(e, occ)
        if (a != null) parts += (if (days <= 0) "má " else "bude mať ") + yearsText(a)
        return parts.joinToString(" · ")
    }

    /** Pravá strana riadku na Domove: „o 12 dní · Narodeniny: Priateľka“. */
    fun rowText(events: List<EventItem>, today: LocalDate): String {
        if (events.isEmpty()) return "Pridať"
        val next = arrange(events, today).firstOrNull { occurrence(it, today) != null } ?: return "Nič nadchádza"
        return inText(daysUntil(next, today) ?: 0) + " · " + title(next)
    }

    /** Podnadpis obrazovky. */
    fun subtitle(events: List<EventItem>, today: LocalDate): String {
        if (events.isEmpty()) return "Zatiaľ žiadne udalosti"
        val next = arrange(events, today).firstOrNull { occurrence(it, today) != null } ?: return "Nič nadchádza"
        return "Najbližšia: " + inText(daysUntil(next, today) ?: 0)
    }

    // ------------------------------------------------------------------ uloženie

    fun toJson(events: List<EventItem>): String {
        val a = JSONArray()
        events.forEach {
            a.put(
                JSONObject().put("i", it.id).put("n", it.name).put("b", it.birthday).put("y", it.year)
                    .put("m", it.month).put("d", it.day).put("r", it.yearly).put("o", JSONArray(it.offsets)),
            )
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
                if (name.isEmpty() || month !in 1..12 || day !in 1..31) continue
                val offs = ArrayList<Int>()
                val oa = o.optJSONArray("o")
                if (oa != null) for (j in 0 until oa.length()) {
                    val v = oa.optInt(j, -1)
                    if (v in 0..MAX_OFFSET) offs += v
                }
                out += EventItem(
                    id = o.optString("i").ifBlank { UUID.randomUUID().toString() },
                    name = name,
                    birthday = o.optBoolean("b"),
                    year = o.optInt("y", 0).coerceAtLeast(0),
                    month = month,
                    day = day,
                    yearly = o.optBoolean("r"),
                    offsets = offs.distinct().sortedDescending(),
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
