package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Ktorému nákupnému zoznamu položka patrí. Existujúce položky bez príznačenia sú spoločné. */
enum class ShopListKind(val code: String, val title: String) {
    MINE("m", "Môj"),
    SHARED("s", "Spoločný");

    val other: ShopListKind get() = if (this == MINE) SHARED else MINE
    val hint: String get() = if (this == MINE) "Len pre mňa…" else "Do spoločného…"
    val moveLabel: String get() = if (this == MINE) "Presunúť do spoločného" else "Presunúť do môjho"

    companion object {
        fun from(raw: String?): ShopListKind = if (raw == MINE.code) MINE else SHARED
    }
}

/** Ktorý pohľad je na obrazovke Nákup otvorený. */
enum class ShopView {
    MINE, BOTH, SHARED;

    val kind: ShopListKind? get() = when (this) {
        MINE -> ShopListKind.MINE
        SHARED -> ShopListKind.SHARED
        BOTH -> null
    }
    val title: String get() = when (this) {
        MINE -> ShopListKind.MINE.title
        SHARED -> ShopListKind.SHARED.title
        BOTH -> "Oba"
    }
    fun next(delta: Int): ShopView {
        val all = entries
        return all[(ordinal + delta).floorMod(all.size)]
    }
    companion object {
        fun from(raw: String?): ShopView = when (raw) {
            "m", "mine" -> MINE
            "s", "shared" -> SHARED
            else -> BOTH
        }
        fun of(kind: ShopListKind) = if (kind == ShopListKind.MINE) MINE else SHARED
    }
}

private fun Int.floorMod(m: Int): Int = ((this % m) + m) % m

/** Položka nákupného zoznamu. [done] = už kúpené (zosivie a presunie sa na koniec). */
data class ShopItem(
    val id: String,
    val name: String,
    val done: Boolean,
    /** Kedy sa pridala (nové položky sú hore). */
    val addedAt: Long,
    /** Kedy sa označila ako kúpená (naposledy kúpené je hore medzi kúpenými). */
    val doneAt: Long = 0L,
    val addedById: String = "",
    val addedByName: String = "",
    val updatedAt: Long = 0L,
    val deleted: Boolean = false,
    val deviceId: String = "",
    /** Môj = len na tomto telefóne; Spoločný = synchronizácia s domácnosťou. Chýbajúce = spoločný. */
    val list: ShopListKind = ShopListKind.SHARED,
)

/**
 * Nákupný zoznam bez Androidu: pridanie, odškrtnutie, vymazanie kúpeného, poradie a texty.
 * Množstvo sa píše rovno do názvu („Mlieko 2 l“) – zoznam je zámerne jednoduchý, bez kategórií a jednotiek.
 */
object ShoppingList {
    const val MAX_ITEMS = 200
    const val MAX_NAME = 80

    /**
     * Po pridaní niektoré klávesnice (IME, najmä Enter / Hotovo) ešte raz pošlú práve odoslaný
     * text do poľa – aj keď sme ho vyprázdnili a vytvorili nanovo (oprava z 1.43). Takú ozvenu
     * treba ignorovať; akékoľvek iné znenie je už nové písanie.
     */
    fun acceptFieldChange(incoming: String, justSubmitted: String?): Boolean {
        if (justSubmitted.isNullOrBlank()) return true
        val inc = incoming.trim()
        if (inc.isEmpty()) return true
        val submitted = justSubmitted.trim()
        if (inc.equals(submitted, ignoreCase = true)) return false
        if (inc.equals(clean(submitted), ignoreCase = true)) return false
        return true
    }

    /** Zbytočné medzery preč a prvé písmeno veľké: „  mlieko   2 l “ → „Mlieko 2 l“. */
    fun clean(text: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).trim()
        return t.replaceFirstChar { it.uppercase() }
    }

    /**
     * Pridá položku. Ak taká už v zozname je (bez ohľadu na veľkosť písmen): kúpená sa vráti medzi nekúpené
     * (a ide hore), nekúpená sa nezduplikuje.
     */
    fun add(
        items: List<ShopItem>,
        text: String,
        now: Long,
        newId: () -> String = { UUID.randomUUID().toString() },
        addedById: String = "",
        addedByName: String = "",
        deviceId: String = "",
        list: ShopListKind = ShopListKind.SHARED,
    ): List<ShopItem> {
        val name = clean(text)
        if (name.isEmpty()) return items
        val live = items.filter { !it.deleted && it.list == list }
        val same = live.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val tomb = items.firstOrNull { it.deleted && it.list == list && it.name.equals(name, ignoreCase = true) }
        return when {
            same == null && tomb != null -> items.map {
                if (it.id == tomb.id) it.copy(
                    deleted = false, done = false, addedAt = now, doneAt = 0L, updatedAt = now,
                    addedById = addedById.ifBlank { it.addedById },
                    addedByName = addedByName.ifBlank { it.addedByName },
                    deviceId = deviceId.ifBlank { it.deviceId },
                ) else it
            }
            same == null -> if (live.size >= MAX_ITEMS) items else items + ShopItem(
                newId(), name, false, now, 0L, addedById, addedByName, now, false, deviceId, list,
            )
            same.done -> items.map {
                if (it.id == same.id) it.copy(done = false, addedAt = now, doneAt = 0L, updatedAt = now, deviceId = deviceId.ifBlank { it.deviceId }) else it
            }
            else -> items
        }
    }

    /** Prepne kúpené / nekúpené. */
    fun toggle(items: List<ShopItem>, id: String, now: Long): List<ShopItem> =
        items.map { if (it.id == id && !it.deleted) it.copy(done = !it.done, doneAt = if (it.done) 0L else now, updatedAt = now) else it }

    /** „Vymazať kúpené“. */
    fun clearDone(items: List<ShopItem>): List<ShopItem> = items.filter { !it.done }

    /** Odstráni jednu položku (potiahnutím do strany). */
    fun remove(items: List<ShopItem>, id: String): List<ShopItem> = items.filter { it.id != id }

    /**
     * „Späť“ po odstránení: vráti položku presne takú, aká bola. Nič nespraví, ak tam už je (rovnaké id),
     * ak medzitým pribudla nekúpená s rovnakým názvom, alebo je zoznam plný.
     */
    fun restore(items: List<ShopItem>, item: ShopItem): List<ShopItem> = when {
        items.any { it.id == item.id && !it.deleted } -> items
        items.any { !it.deleted && !it.done && it.list == item.list && it.name.equals(item.name, ignoreCase = true) } -> items
        items.count { !it.deleted && it.list == item.list } >= MAX_ITEMS -> items
        items.any { it.id == item.id } -> items.map { if (it.id == item.id) item.copy(deleted = false, updatedAt = maxOf(item.updatedAt, it.updatedAt)) else it }
        else -> items + item
    }

    /**
     * Premenovanie (podržaním položky). Prázdny názov nič nezmení. Ak už je v zozname iná položka s rovnakým názvom:
     * nekúpená → premenovaná sa zlúči do nej (nezduplikuje sa), kúpená → tá kúpená sa odstráni a premenovaná ostane.
     */
    fun rename(items: List<ShopItem>, id: String, text: String): List<ShopItem> {
        val name = clean(text)
        val item = items.firstOrNull { it.id == id && !it.deleted } ?: return items
        if (name.isEmpty() || name == item.name) return items
        val other = items.firstOrNull { it.id != id && !it.deleted && it.list == item.list && it.name.equals(name, ignoreCase = true) }
        return when {
            other == null -> items.map { if (it.id == id) it.copy(name = name, updatedAt = maxOf(it.updatedAt, 1L)) else it }
            !other.done -> items.filter { it.id != id }
            else -> items.filter { it.id != other.id }.map { if (it.id == id) it.copy(name = name, updatedAt = maxOf(it.updatedAt, 1L)) else it }
        }
    }

    /** Poradie na obrazovke: nekúpené (najnovšie hore), potom kúpené (naposledy kúpené hore). */
    fun arrange(items: List<ShopItem>): List<ShopItem> =
        items.filter { !it.deleted && !it.done }.sortedByDescending { it.addedAt } + items.filter { !it.deleted && it.done }.sortedByDescending { it.doneAt }

    fun left(items: List<ShopItem>, kind: ShopListKind? = null): Int =
        items.count { !it.deleted && !it.done && (kind == null || it.list == kind) }

    fun of(items: List<ShopItem>, kind: ShopListKind): List<ShopItem> = items.filter { it.list == kind }

    /** Položky, ktoré smú ísť do synchronizácie domácnosti: len spoločný zoznam (aj tombstone). */
    fun forSync(items: List<ShopItem>): List<ShopItem> = items.filter { it.list == ShopListKind.SHARED }

    /** 1 položka, 2–4 položky, 5 a viac položiek. */
    fun itemsText(n: Int): String = when {
        n == 1 -> "1 položka"
        n in 2..4 -> "$n položky"
        else -> "$n položiek"
    }

    /** Text vpravo v riadku Nákup na Domove. */
    fun rowText(items: List<ShopItem>): String = when {
        items.none { !it.deleted } -> "Prázdny"
        left(items) == 0 -> "Všetko kúpené"
        else -> itemsText(left(items))
    }

    /** Podnadpis obrazovky zoznamu. */
    fun subtitle(items: List<ShopItem>): String = when {
        items.none { !it.deleted } -> "Zoznam je prázdny"
        left(items) == 0 -> "Všetko kúpené"
        else -> "Zostáva: ${left(items)}"
    }

    fun toJson(items: List<ShopItem>): String {
        val a = JSONArray()
        items.forEach {
            val o = JSONObject().put("i", it.id).put("n", it.name).put("d", it.done).put("a", it.addedAt).put("t", it.doneAt)
            if (it.addedById.isNotBlank()) o.put("b", it.addedById)
            if (it.addedByName.isNotBlank()) o.put("p", it.addedByName)
            if (it.updatedAt != 0L) o.put("u", it.updatedAt)
            if (it.deleted) o.put("x", true)
            if (it.deviceId.isNotBlank()) o.put("dv", it.deviceId)
            if (it.list == ShopListKind.MINE) o.put("l", ShopListKind.MINE.code)
            a.put(o)
        }
        return a.toString()
    }

    /** Poškodený alebo prázdny záznam = prázdny zoznam (nikdy výnimka). */
    fun fromJson(raw: String?): List<ShopItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            val out = ArrayList<ShopItem>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val name = o.optString("n").trim()
                val deleted = o.optBoolean("x", false)
                if (name.isEmpty() && !deleted) continue
                val id = o.optString("i").ifBlank { UUID.randomUUID().toString() }
                out += ShopItem(
                    id, name, o.optBoolean("d"), o.optLong("a"), o.optLong("t"),
                    o.optString("b"), o.optString("p"), o.optLong("u"), deleted, o.optString("dv"),
                    ShopListKind.from(o.optString("l")),
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
    /** „Pridané · Tomáš“ – meno z položky, bez odhadu rodu. */
    fun addedByText(item: ShopItem): String {
        val n = item.addedByName.trim()
        return if (n.isEmpty()) "" else "Pridané · $n"
    }

    /** Označí kúpené položky ako zmazané (tombstone pre synchronizáciu). [kind] = len tento zoznam, null = oba. */
    fun tombstoneDone(items: List<ShopItem>, now: Long, deviceId: String = "", kind: ShopListKind? = null): List<ShopItem> =
        items.map {
            if (!it.deleted && it.done && (kind == null || it.list == kind))
                it.copy(deleted = true, updatedAt = now, deviceId = deviceId.ifBlank { it.deviceId })
            else it
        }

    fun tombstone(items: List<ShopItem>, id: String, now: Long, deviceId: String = ""): List<ShopItem> =
        items.map { if (it.id == id && !it.deleted) it.copy(deleted = true, updatedAt = now, deviceId = deviceId.ifBlank { it.deviceId }) else it }

    /**
     * Presunie položku do druhého zoznamu. Súkromná → spoločná: ostane to isté id a začne sa synchronizovať.
     * Spoločná → súkromná: pôvodné id dostane tombstone (pre domácnosť) a na telefóne vznikne nová súkromná kópia.
     * Ak cieľ už má rovnaký názov, zlúči sa ako [add] a zdroj sa odstráni / opatruje tombstone.
     */
    fun move(
        items: List<ShopItem>,
        id: String,
        now: Long,
        newId: () -> String = { UUID.randomUUID().toString() },
        deviceId: String = "",
    ): List<ShopItem> {
        val item = items.firstOrNull { it.id == id && !it.deleted } ?: return items
        val target = item.list.other
        val dev = deviceId.ifBlank { item.deviceId }
        val clash = items.firstOrNull { !it.deleted && it.list == target && it.name.equals(item.name, ignoreCase = true) }
        val withTarget = when {
            // v cieli už je nekúpená rovnaká vec → stačí zdroj odstrániť
            clash != null && !clash.done -> items
            // v cieli je kúpená rovnaká vec → vráti sa medzi nekúpené
            clash != null -> items.map {
                if (it.id == clash.id) it.copy(done = item.done, doneAt = item.doneAt, addedAt = now, updatedAt = now, deviceId = dev) else it
            }
            items.count { !it.deleted && it.list == target } >= MAX_ITEMS -> return items
            // súkromná → spoločná: to isté id, odteraz sa synchronizuje
            item.list == ShopListKind.MINE -> return items.map {
                if (it.id == item.id) it.copy(list = ShopListKind.SHARED, updatedAt = now, deviceId = dev) else it
            }
            // spoločná → súkromná: nová súkromná kópia (pôvodné id dostane tombstone nižšie)
            else -> items + item.copy(id = newId(), list = ShopListKind.MINE, updatedAt = now, deviceId = dev)
        }
        return tombstone(withTarget, item.id, now, dev)
    }

    fun viewSubtitle(view: ShopView, items: List<ShopItem>): String = when (view) {
        ShopView.BOTH -> "Môj ${left(items, ShopListKind.MINE)} · Spoločný ${left(items, ShopListKind.SHARED)}"
        ShopView.MINE -> "Len na tomto telefóne"
        ShopView.SHARED -> when {
            items.none { !it.deleted && it.list == ShopListKind.SHARED } -> "Zdieľané s domácnosťou"
            left(items, ShopListKind.SHARED) == 0 -> "Všetko kúpené · zdieľané"
            else -> "Zostáva ${left(items, ShopListKind.SHARED)} · zdieľané"
        }
    }
}

/**
 * Jedna vec z histórie nákupov: koľkokrát sa pridala, kedy naposledy, a (od 1.45) kedy sa naposledy kúpila
 * ([bought] = časy odškrtnutia, najviac [ShoppingHistory.MAX_BOUGHT], vzostupne). Z nich sa počíta, kedy treba kúpiť znova.
 */
data class ShopHistoryEntry(val name: String, val count: Int, val lastAt: Long, val bought: List<Long> = emptyList())

/**
 * História pridaných vecí bez Androidu. Prežije „Vymazať kúpené“ – maže sa z nej len ručne (podržaním návrhu)
 * alebo keď sa nekúpená položka hneď odstráni (preklep). Porovnáva sa bez ohľadu na veľkosť písmen a diakritiku.
 */
object ShoppingHistory {
    const val MAX = 300
    const val SUGGESTIONS = 5
    const val CHIPS = 10
    /** Koľko posledných nákupov jednej veci si pamätať (na odhad, ako často sa kupuje). */
    const val MAX_BOUGHT = 10
    const val DAY_MS = 24L * 60 * 60 * 1000
    /** Kým nie sú aspoň 2 nákupy, ráta sa, že sa vec kupuje raz za týždeň. */
    const val DEFAULT_INTERVAL_MS = 7 * DAY_MS

    /** „Šalát“ → „salat“: malé písmená bez diakritiky (na porovnanie a hľadanie). */
    fun fold(text: String): String =
        java.text.Normalizer.normalize(text.trim().lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s+"), " ")

    /** Pridanie veci do zoznamu sa zapíše do histórie (+1, aktuálny zápis názvu, čas). */
    fun record(history: List<ShopHistoryEntry>, text: String, now: Long): List<ShopHistoryEntry> {
        val name = ShoppingList.clean(text)
        if (name.isEmpty()) return history
        val key = fold(name)
        val old = history.firstOrNull { fold(it.name) == key }
        val next = if (old == null) history + ShopHistoryEntry(name, 1, now)
        else history.map { if (it === old) it.copy(name = name, count = it.count + 1, lastAt = now) else it }
        if (next.size <= MAX) return next
        // plná história: vypadne to, čo sa pridávalo najmenej a najdávnejšie
        val drop = next.minWithOrNull(compareBy<ShopHistoryEntry> { it.count }.thenBy { it.lastAt })
        return next.filter { it !== drop }
    }

    /** Zníži počet o 1 (nekúpená položka hneď odstránená = asi preklep); pri nule vec z histórie zmizne. */
    fun forget(history: List<ShopHistoryEntry>, name: String): List<ShopHistoryEntry> {
        val key = fold(name)
        return history.mapNotNull { if (fold(it.name) == key) (if (it.count <= 1) null else it.copy(count = it.count - 1)) else it }
    }

    /** Úplne odstráni vec z návrhov (podržaním návrhu alebo tlačidla). */
    fun delete(history: List<ShopHistoryEntry>, name: String): List<ShopHistoryEntry> {
        val key = fold(name)
        return history.filter { fold(it.name) != key }
    }

    /** Premenovanie položky prenesie jej históriu na nový názov (preklep sa už nebude ponúkať). */
    fun rename(history: List<ShopHistoryEntry>, oldName: String, newName: String, now: Long): List<ShopHistoryEntry> {
        val name = ShoppingList.clean(newName)
        if (name.isEmpty() || fold(name) == fold(oldName)) {
            return if (name.isEmpty()) history else history.map { if (fold(it.name) == fold(oldName)) it.copy(name = name) else it }
        }
        val old = history.firstOrNull { fold(it.name) == fold(oldName) }
        val moved = (old?.count ?: 1)
        val movedBought = old?.bought ?: emptyList()
        val without = history.filter { it !== old }
        val target = without.firstOrNull { fold(it.name) == fold(name) }
        return if (target == null) without + ShopHistoryEntry(name, moved, now, movedBought)
        else without.map { if (it === target) ShopHistoryEntry(name, it.count + moved, now, mergeBought(it.bought, movedBought)) else it }
    }

    private fun onList(items: List<ShopItem>): Set<String> = items.filter { !it.deleted && !it.done }.map { fold(it.name) }.toSet()

    private val byUse = compareByDescending<ShopHistoryEntry> { it.count }.thenByDescending { it.lastAt }

    /**
     * Návrhy pri písaní: názov začína napísaným textom (alebo niektoré jeho slovo), bez ohľadu na veľkosť písmen
     * a diakritiku. Najprv zhoda od začiatku názvu, potom podľa toho, ako často sa vec pridáva. Bez vecí, ktoré už sú
     * v zozname nekúpené, a bez presne napísaného textu.
     */
    fun suggest(history: List<ShopHistoryEntry>, query: String, items: List<ShopItem>, limit: Int = SUGGESTIONS): List<ShopHistoryEntry> {
        val q = fold(query)
        if (q.isEmpty()) return emptyList()
        val skip = onList(items)
        val hits = history.filter { val f = fold(it.name); f != q && f !in skip && (f.startsWith(q) || f.split(' ').any { w -> w.startsWith(q) }) }
        return hits.sortedWith(compareByDescending<ShopHistoryEntry> { fold(it.name).startsWith(q) }.then(byUse)).take(limit)
    }

    private fun mergeBought(a: List<Long>, b: List<Long>): List<Long> = (a + b).distinct().sorted().takeLast(MAX_BOUGHT)

    /** Odškrtnutie položky ako kúpenej zapíše čas nákupu (ak vec v histórii nie je, pridá sa). */
    fun bought(history: List<ShopHistoryEntry>, text: String, at: Long): List<ShopHistoryEntry> {
        val name = ShoppingList.clean(text)
        if (name.isEmpty()) return history
        val key = fold(name)
        val old = history.firstOrNull { fold(it.name) == key }
            ?: return history + ShopHistoryEntry(name, 1, at, listOf(at))
        return history.map { if (it === old) it.copy(bought = mergeBought(it.bought, listOf(at))) else it }
    }

    /** Zrušené odškrtnutie (omyl): čas toho nákupu sa zabudne. */
    fun unbought(history: List<ShopHistoryEntry>, text: String, at: Long): List<ShopHistoryEntry> {
        val key = fold(text)
        return history.map { if (fold(it.name) == key) it.copy(bought = it.bought - at) else it }
    }

    /**
     * Ako často sa vec kupuje: medián medzier medzi nákupmi (aspoň 1 deň).
     * Pri menej ako 2 nákupoch [DEFAULT_INTERVAL_MS] (týždeň).
     */
    fun interval(e: ShopHistoryEntry): Long {
        val t = e.bought.sorted()
        if (t.size < 2) return DEFAULT_INTERVAL_MS
        val gaps = t.zipWithNext { a, b -> b - a }.sorted()
        val m = gaps.size / 2
        val median = if (gaps.size % 2 == 1) gaps[m] else (gaps[m - 1] + gaps[m]) / 2
        return median.coerceAtLeast(DAY_MS)
    }

    /** Posledný nákup; staršia história (pred 1.45) bez nákupov použije čas posledného pridania. */
    fun lastBought(e: ShopHistoryEntry): Long = e.bought.maxOrNull() ?: e.lastAt

    /** Koľko „intervalov“ uplynulo od posledného nákupu (1.0 = práve treba kúpiť, 2.0 = dvakrát po termíne). */
    fun dueRatio(e: ShopHistoryEntry, now: Long): Double = (now - lastBought(e)).toDouble() / interval(e)

    /**
     * Rýchle tlačidlá „Často kupuješ“: len veci, ktoré **treba kúpiť znova** – nie sú v zozname vôbec (ani kúpené,
     * ešte nevymazané) a od posledného nákupu uplynul aspoň ich obvyklý čas. Najviac po termíne hore, potom podľa počtu.
     */
    fun due(history: List<ShopHistoryEntry>, items: List<ShopItem>, now: Long, limit: Int = CHIPS): List<ShopHistoryEntry> {
        val skip = items.filter { !it.deleted }.map { fold(it.name) }.toSet()
        return history
            .filter { fold(it.name) !in skip && dueRatio(it, now) >= 1.0 }
            .sortedWith(compareByDescending<ShopHistoryEntry> { dueRatio(it, now) }.thenByDescending { it.count }.thenByDescending { it.lastAt })
            .take(limit)
    }

    fun toJson(history: List<ShopHistoryEntry>): String {
        val a = JSONArray()
        history.forEach {
            val o = JSONObject().put("n", it.name).put("c", it.count).put("t", it.lastAt)
            if (it.bought.isNotEmpty()) o.put("b", JSONArray(it.bought))
            a.put(o)
        }
        return a.toString()
    }

    /** Poškodený alebo prázdny záznam = prázdna história (nikdy výnimka). */
    fun fromJson(raw: String?): List<ShopHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            val out = ArrayList<ShopHistoryEntry>()
            val seen = HashSet<String>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val name = ShoppingList.clean(o.optString("n"))
                if (name.isEmpty() || !seen.add(fold(name))) continue
                // „b“ (časy nákupov) je od 1.45; staršia história ho nemá → prázdny zoznam
                val b = o.optJSONArray("b")
                val bought = if (b == null) emptyList() else (0 until b.length()).map { b.optLong(it, -1L) }.filter { it >= 0 }.distinct().sorted().takeLast(MAX_BOUGHT)
                out += ShopHistoryEntry(name, o.optInt("c", 1).coerceAtLeast(1), o.optLong("t"), bought)
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Prvé spustenie novej verzie: história sa naplní z terajšieho zoznamu (každá vec raz),
     * aby návrhy a tlačidlá fungovali hneď, nie až po týždni nakupovania.
     */
    fun seed(items: List<ShopItem>): List<ShopHistoryEntry> {
        var h = emptyList<ShopHistoryEntry>()
        items.filter { !it.deleted }.sortedBy { it.addedAt }.forEach { h = record(h, it.name, it.addedAt) }
        items.filter { !it.deleted && it.done && it.doneAt > 0 }.forEach { h = bought(h, it.name, it.doneAt) }
        return h
    }
}
