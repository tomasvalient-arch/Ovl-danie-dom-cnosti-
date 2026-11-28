package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject

/** Druh zmeny spoločného nákupného zoznamu, ktorú urobil iný člen domácnosti. */
enum class ShopChangeKind(val code: String, val label: String) {
    ADDED("a", "Pridané"),
    DONE("d", "Kúpené"),
    UNDONE("u", "Vrátené do zoznamu"),
    RENAMED("r", "Premenované"),
    REMOVED("x", "Odstránené");

    companion object {
        fun from(code: String?): ShopChangeKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Jedna zmena spoločného zoznamu od iného telefónu. [name] je názov po zmene (pri odstránení názov odstránenej veci),
 * [oldName] je vyplnený len pri premenovaní. [actorId] je telefón, ktorý zmenu urobil, [actorName] jeho meno (môže byť prázdne).
 */
data class ShopChange(
    val kind: ShopChangeKind,
    val name: String,
    val actorId: String = "",
    val actorName: String = "",
    val oldName: String = "",
    val at: Long = 0L,
)

/**
 * Upozornenia na zmeny spoločného nákupu bez Androidu: čo je zmena, o ktorej má zmysel informovať, ako sa zmeny
 * zbierajú, kým ich človek neotvorí, a texty notifikácie. Texty sú zámerne bez rodu („Pridané: Mlieko“), meno je len v nadpise.
 */
object ShoppingChanges {
    /** Najviac toľko neprečítaných zmien sa drží; staršie sa zahodia. */
    const val MAX_UNSEEN = 30
    /** Neprečítané zmeny staršie ako toto sa už nezobrazujú (ľudia si ich medzitým všimnú v zozname). */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    /** Koľko riadkov sa ukáže v rozbalenej notifikácii. */
    const val MAX_LINES = 6
    /** Koľko vecí sa vymenuje v jednoriadkovom súhrne. */
    private const val SUMMARY_NAMES = 3

    /**
     * Čo sa stalo s jednou položkou po zlúčení: [before] = stav v tomto telefóne pred zlúčením (null = nikdy tu nebola),
     * [after] = stav po zlúčení. Vráti null, keď to nie je zmena, o ktorej sa oplatí upozorniť:
     * nie je v spoločnom zozname, urobil ju tento telefón, nezmenilo sa nič viditeľné, alebo ide o odstránenie už kúpenej
     * položky („Vymazať kúpené“ je len upratovanie – kúpenie sa oznámilo už predtým).
     */
    fun change(before: ShopItem?, after: ShopItem, selfId: String): ShopChange? {
        if (after.list != ShopListKind.SHARED) return null
        val actor = after.deviceId
        if (actor.isNotBlank() && actor == selfId) return null
        val wasLive = before != null && !before.deleted
        val byName = if (after.addedById.isNotBlank() && after.addedById == actor) after.addedByName.trim() else ""
        fun make(kind: ShopChangeKind, name: String, old: String = "") =
            ShopChange(kind, name.trim(), actor, byName, old.trim(), after.updatedAt)
        if (after.deleted) {
            if (before == null || !wasLive || before.done) return null
            return make(ShopChangeKind.REMOVED, after.name.ifBlank { before.name })
        }
        if (!wasLive) return make(if (after.done) ShopChangeKind.DONE else ShopChangeKind.ADDED, after.name)
        val b = before!!
        return when {
            b.done != after.done -> when {
                after.done -> make(ShopChangeKind.DONE, after.name)
                // „pridať“ kúpenú vec znova ju vráti medzi nekúpené s novým časom pridania: pre ľudí je to nové pridanie
                after.addedAt > b.addedAt -> make(ShopChangeKind.ADDED, after.name)
                else -> make(ShopChangeKind.UNDONE, after.name)
            }
            b.name != after.name -> make(ShopChangeKind.RENAMED, after.name, b.name)
            else -> null
        }
    }

    private fun fold(name: String): String = ShoppingHistory.fold(name)

    /** Dvojice zmien, ktoré sa navzájom rušia (pridané a hneď odstránené, kúpené a hneď vrátené). */
    private fun cancels(old: ShopChangeKind, new: ShopChangeKind): Boolean =
        (old == ShopChangeKind.ADDED && new == ShopChangeKind.REMOVED) ||
            (old == ShopChangeKind.DONE && new == ShopChangeKind.UNDONE) ||
            (old == ShopChangeKind.UNDONE && new == ShopChangeKind.DONE)

    /**
     * Pripočíta nové zmeny k neprečítaným (čas zmeny = [now], kedy sme ju dostali): staré a príliš dlhé sa zahodia, zmena, ktorú druhá hneď zruší, zmizne
     * (pridané Mlieko a potom odstránené Mlieko = nič), a rovnaká zmena sa nezopakuje. Vždy ostane najviac [MAX_UNSEEN].
     */
    fun accumulate(unseen: List<ShopChange>, fresh: List<ShopChange>, now: Long): List<ShopChange> {
        val out = ArrayList<ShopChange>()
        for (c in unseen) if (now - c.at < MAX_AGE_MS) out += c
        for (raw in fresh) {
            // čas sa píše podľa toho, kedy sme zmenu dostali (nie podľa hodín odosielateľa, ktoré môžu ísť inak)
            val c = raw.copy(at = now)
            val key = fold(c.name)
            val idx = out.indexOfLast { fold(it.name) == key && (cancels(it.kind, c.kind) || (it.kind == c.kind && it.actorId == c.actorId)) }
            if (idx >= 0) {
                if (cancels(out[idx].kind, c.kind)) out.removeAt(idx)
                else out[idx] = c   // rovnaká zmena znova: ostane len novšia
                continue
            }
            out += c
        }
        return if (out.size > MAX_UNSEEN) out.subList(out.size - MAX_UNSEEN, out.size).toList() else out
    }

    /** Jeden riadok o jednej zmene. */
    fun line(c: ShopChange): String = when (c.kind) {
        ShopChangeKind.RENAMED ->
            if (c.oldName.isBlank()) "${c.kind.label}: ${c.name}" else "${c.kind.label}: ${c.oldName} → ${c.name}"
        else -> "${c.kind.label}: ${c.name}"
    }

    /** Mená tých, čo zmeny urobili, bez opakovania a bez prázdnych. */
    fun actors(changes: List<ShopChange>): List<String> =
        changes.map { it.actorName.trim() }.filter { it.isNotEmpty() }.distinctBy { fold(it) }

    /** Nadpis: „Nákup · Katka“, „Nákup · Katka, Tomáš“, bez mena „Spoločný nákup“. */
    fun title(changes: List<ShopChange>): String {
        val names = actors(changes)
        return when {
            names.isEmpty() -> "Spoločný nákup"
            names.size <= 2 -> "Nákup · " + names.joinToString(", ")
            else -> "Nákup · ${names[0]}, ${names[1]} a ďalší"
        }
    }

    /**
     * Jednoriadkový súhrn pre zbalenú notifikáciu a zamknutú obrazovku. Jedna zmena = jej riadok; viac zmien sa
     * zoskupí podľa druhu: „Pridané: Mlieko, Chlieb, Syr +2 · Kúpené: Maslo“.
     */
    fun summary(changes: List<ShopChange>): String {
        if (changes.isEmpty()) return ""
        if (changes.size == 1) return line(changes[0])
        val parts = ArrayList<String>()
        for (kind in ShopChangeKind.entries) {
            val names = changes.filter { it.kind == kind }.map { it.name }.distinctBy { fold(it) }
            if (names.isEmpty()) continue
            val shown = names.take(SUMMARY_NAMES).joinToString(", ")
            val more = names.size - SUMMARY_NAMES
            parts += kind.label + ": " + shown + if (more > 0) " +$more" else ""
        }
        return parts.joinToString(" · ")
    }

    /**
     * Riadky rozbalenej notifikácie (najnovšie posledné) a počet zmien, ktoré sa nezmestili.
     * Keď zmeny robilo viac ľudí, za riadok sa pripíše meno.
     */
    fun lines(changes: List<ShopChange>, max: Int = MAX_LINES): Pair<List<String>, Int> {
        val several = actors(changes).size > 1
        val all = changes.map { c ->
            val who = c.actorName.trim()
            if (several && who.isNotEmpty()) line(c) + " ($who)" else line(c)
        }
        if (all.size <= max) return all to 0
        return all.takeLast(max) to (all.size - max)
    }

    /** Uloženie neprečítaných zmien (SharedPreferences). */
    fun toJson(changes: List<ShopChange>): String {
        val a = JSONArray()
        for (c in changes) {
            val o = JSONObject().put("k", c.kind.code).put("n", c.name).put("t", c.at)
            if (c.actorId.isNotBlank()) o.put("i", c.actorId)
            if (c.actorName.isNotBlank()) o.put("p", c.actorName)
            if (c.oldName.isNotBlank()) o.put("o", c.oldName)
            a.put(o)
        }
        return a.toString()
    }

    /** Poškodený alebo prázdny záznam = žiadne zmeny (nikdy výnimka). */
    fun fromJson(raw: String?): List<ShopChange> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            val out = ArrayList<ShopChange>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val kind = ShopChangeKind.from(o.optString("k")) ?: continue
                val name = o.optString("n").trim()
                if (name.isEmpty()) continue
                out += ShopChange(kind, name, o.optString("i"), o.optString("p"), o.optString("o"), o.optLong("t"))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}
