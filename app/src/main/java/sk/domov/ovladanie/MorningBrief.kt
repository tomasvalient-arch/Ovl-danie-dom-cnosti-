package sk.domov.ovladanie

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Ranný prehľad bez Androidu: čo sa zmestí do jednej rannej notifikácie a kedy ju poslať.
 * Zber údajov (sieť, zariadenia) je v MorningBriefAndroid.kt; tu je len skladanie textu a plánovanie, aby sa dalo testovať.
 */

/** Stav zariadení pre prehľad: [on] = názvy zapnutých, [checked] = koľko sa zisťovalo, [unknown] = koľko sa neozvalo. */
data class BriefDevices(val on: List<String>, val checked: Int, val unknown: Int)

/** Údaje pre jeden prehľad. Chýbajúci zdroj (null / prázdne) sa v texte jednoducho vynechá. */
data class BriefContent(
    val date: LocalDate,
    val weather: WeatherDay? = null,
    val events: List<EventItem> = emptyList(),
    val devices: BriefDevices? = null,
    /** Koľko položiek ostáva kúpiť; null = časť vynechať, 0 = tiež vynechať. */
    val shopLeft: Int? = null,
    /** Od 1.52: zvlášť Môj a Spoločný; keď sú zadané, prebijú [shopLeft]. */
    val shopMine: Int? = null,
    val shopShared: Int? = null,
)

data class BriefText(val title: String, val short: String, val big: String, val lines: List<String>)

object MorningBrief {
    const val DEFAULT_TIME_MIN = 7 * 60

    /** Keď telefón v čase prehľadu nebežal (vypnutý, budík meškal), prehľad príde ešte do toľkých minút po čase. */
    const val CATCH_UP_MIN = 180

    /** Ako dlho platí príznak „práve sa posiela“, kým sa smie začať znova (pád procesu, visíci zber). */
    const val SEND_GUARD_MS = 2L * 60 * 1000

    /** Ďalší pokus po neúspešnom odoslaní, kým sme v catch-up okne. */
    const val RETRY_MIN = 15

    /** Najviac toľko názvov zapnutých zariadení, ďalšie sa len spočítajú. */
    const val MAX_DEVICE_NAMES = 6

    private val WEEKDAYS = listOf("pondelok", "utorok", "streda", "štvrtok", "piatok", "sobota", "nedeľa")
    private val MONTHS = listOf(
        "januára", "februára", "marca", "apríla", "mája", "júna",
        "júla", "augusta", "septembra", "októbra", "novembra", "decembra",
    )

    fun dateText(d: LocalDate): String = "${WEEKDAYS[d.dayOfWeek.value - 1]} ${d.dayOfMonth}. ${MONTHS[d.monthValue - 1]}"

    fun title(d: LocalDate): String = "Dobré ráno – ${dateText(d)}"

    /** „Počasie: Polojasno, 2° až 7°, zrážky 40 %“ */
    fun weatherLine(w: WeatherDay): String = buildString {
        append("Počasie: ").append(Wmo.of(w.code).text)
        append(", ").append(WeatherPolicy.temp(w.min)).append(" až ").append(WeatherPolicy.temp(w.max))
        w.precip?.let { if (it > 0) append(", zrážky ").append(it).append(" %") else append(", bez zrážok") }
    }

    /** „Meniny: Klement“; v deň bez mena názov sviatku („Nový rok, Deň vzniku SR“). */
    fun meninyLine(d: LocalDate): String =
        if (Meniny.zoznam(d).isEmpty()) Meniny.text(d).replaceFirstChar { it.uppercase() } else "Meniny: ${Meniny.text(d)}"

    private fun who(e: EventItem): String {
        val name = Events.displayName(e)
        val kind = Events.partLabel(e)
        return if (kind.isEmpty()) name else "$name – ${kind.lowercase()}"
    }

    /**
     * Udalosti na dnes („Dnes: Jana – narodeniny (30 rokov)“) a pripomienky vopred, ktoré pripadli na dnešok
     * („O týždeň: Peter – meniny (pondelok 30. novembra)“). Poradie: najbližšie prvé, potom podľa mena.
     */
    fun eventLines(events: List<EventItem>, today: LocalDate): List<String> {
        data class Line(val days: Int, val name: String, val text: String)
        val out = ArrayList<Line>()
        for (p in Events.expand(events)) {
            val occ = Events.occurrence(p, today) ?: continue
            val days = ChronoUnit.DAYS.between(today, occ).toInt()
            if (days == 0) {
                val age = Events.age(p, occ)?.let { " (${Events.yearsText(it)})" } ?: ""
                val time = Events.timeRange(p).let { if (it.isEmpty()) "" else " ($it)" }
                out += Line(0, p.name.lowercase(), "Dnes: ${who(p)}$age$time")
            } else if (days > 0 && days in Events.dayOffsets(p)) {
                val age = Events.age(p, occ)?.let { ", bude mať ${Events.yearsText(it)}" } ?: ""
                out += Line(days, p.name.lowercase(), "${Events.inText(days).replaceFirstChar { it.uppercase() }}: ${who(p)} (${dateText(occ)}$age)")
            }
        }
        return out.sortedWith(compareBy({ it.days }, { it.name })).map { it.text }
    }

    /** „Nákup: zostáva 5 položiek“; null keď nie je čo hlásiť. */
    fun shopLine(left: Int?): String? = shopLine(mine = null, shared = null, total = left)

    /** Od 1.52: keď sú zadané počty zoznamov, vypíše oba; inak ostane pôvodná veta so súčtom. */
    fun shopLine(mine: Int?, shared: Int?, total: Int? = null): String? {
        if (mine != null || shared != null) {
            val m = (mine ?: 0).coerceAtLeast(0)
            val s = (shared ?: 0).coerceAtLeast(0)
            if (m <= 0 && s <= 0) return null
            return when {
                m > 0 && s > 0 -> "Nákup: môj ${ShoppingList.itemsText(m)}, spoločný ${ShoppingList.itemsText(s)}"
                m > 0 -> "Nákup: môj ${ShoppingList.itemsText(m)}"
                else -> "Nákup: spoločný ${ShoppingList.itemsText(s)}"
            }
        }
        val n = total ?: return null
        if (n <= 0) return null
        return "Nákup: zostáva ${ShoppingList.itemsText(n)}"
    }

    /**
     * „Zapnuté: Obývačka svetlo, Zásuvka TV“ / „Všetko je vypnuté“, s poznámkou o zariadeniach, ktoré sa neozvali.
     * null = časť sa vynechá (nič sa nezisťovalo alebo sa neozvalo nič).
     */
    fun devicesLine(d: BriefDevices?): String? {
        if (d == null || d.checked <= 0 || d.unknown >= d.checked) return null
        val base = if (d.on.isEmpty()) "Všetko je vypnuté" else {
            val shown = d.on.take(MAX_DEVICE_NAMES).joinToString(", ")
            val more = d.on.size - MAX_DEVICE_NAMES
            "Zapnuté: $shown" + if (more > 0) " a ešte $more" else ""
        }
        val note = when {
            d.unknown <= 0 -> ""
            d.unknown == 1 -> " (1 zariadenie sa neozvalo)"
            d.unknown in 2..4 -> " (${d.unknown} zariadenia sa neozvali)"
            else -> " (${d.unknown} zariadení sa neozvalo)"
        }
        return base + note
    }

    fun compose(c: BriefContent): BriefText {
        val lines = ArrayList<String>()
        c.weather?.let { lines += weatherLine(it) }
        lines += meninyLine(c.date)
        lines += eventLines(c.events, c.date)
        shopLine(c.shopMine, c.shopShared, c.shopLeft)?.let { lines += it }
        devicesLine(c.devices)?.let { lines += it }
        return BriefText(title(c.date), lines.first(), lines.joinToString("\n"), lines)
    }

    // ------------------------------------------------------------------ zapnuté zariadenia

    /** Druhy, ktoré prehľad hlási: svetlá, zásuvky, klíma/ventilátor, ohrievač a ostatné s istým vypínačom (nie termostat, roleta, senzor). */
    fun reports(kind: AutoKind): Boolean =
        kind == AutoKind.LIGHT || kind == AutoKind.SOCKET || kind == AutoKind.FAN || kind == AutoKind.HEATER ||
            kind == AutoKind.FRYER || kind == AutoKind.OTHER

    /** Dá sa zariadenie vôbec zisťovať (brána, senzory, termostaty a rolety nie)? Rozhoduje sa podľa posledného známeho stavu. */
    fun candidate(d: Device, lastKnown: Map<String, Any>): Boolean = reports(AutoDevices.kind(d, lastKnown))

    /** Je zariadenie podľa čerstvého stavu zapnuté? null = nedá sa isto povedať (bez istého vypínača). */
    fun isOn(d: Device, values: Map<String, Any>): Boolean? {
        if (values.isEmpty()) return null
        val kind = AutoDevices.kind(d, values)
        if (!reports(kind)) return false
        if (kind == AutoKind.FRYER) return SafeOff.fryerRunning(values)
        val dp = SafeOff.certainPower(d, values) ?: return null
        return values[dp] as? Boolean
    }

    // ------------------------------------------------------------------ plánovanie

    /** Najbližší budík: dnes v nastavený čas, ak ešte neprešiel, inak zajtra. */
    fun nextAlarm(now: LocalDateTime, time: LocalTime): LocalDateTime = Events.nextCheck(now, time)

    /**
     * Má sa prehľad poslať teraz? Dnes ešte neodišiel ([lastSent]) a sme v okne od nastaveného času do
     * [CATCH_UP_MIN] minút po ňom (najneskôr do polnoci). Inak sa čaká na zajtrajší budík.
     */
    fun isDue(now: LocalDateTime, time: LocalTime, lastSent: LocalDate?): Boolean {
        val today = now.toLocalDate()
        if (lastSent != null && !lastSent.isBefore(today)) return false
        val start = today.atTime(time)
        val end = minOf(start.plusMinutes(CATCH_UP_MIN.toLong()), today.plusDays(1).atStartOfDay())
        return !now.isBefore(start) && now.isBefore(end)
    }

    /** Možno začať zber? Nie, ak už jeden prebieha a [SEND_GUARD_MS] ešte neuplynuli. */
    fun canStartSend(nowMs: Long, startedAtMs: Long?): Boolean {
        if (startedAtMs == null || startedAtMs <= 0L) return true
        return nowMs - startedAtMs >= SEND_GUARD_MS
    }

    /**
     * Ďalší pokus po neúspechu: o [RETRY_MIN] minút, ak to ešte spadá do catch-up okna; inak null
     * (použije sa bežný budík [nextAlarm]).
     */
    fun nextRetry(now: LocalDateTime, time: LocalTime, lastSent: LocalDate?): LocalDateTime? {
        if (!isDue(now, time, lastSent)) return null
        val today = now.toLocalDate()
        val end = minOf(today.atTime(time).plusMinutes(CATCH_UP_MIN.toLong()), today.plusDays(1).atStartOfDay())
        val retry = now.plusMinutes(RETRY_MIN.toLong())
        return retry.takeIf { it.isBefore(end) }
    }
}
