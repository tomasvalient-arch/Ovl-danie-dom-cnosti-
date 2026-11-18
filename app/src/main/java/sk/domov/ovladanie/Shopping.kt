package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Položka nákupného zoznamu. [done] = už kúpené (zosivie a presunie sa na koniec). */
data class ShopItem(
    val id: String,
    val name: String,
    val done: Boolean,
    /** Kedy sa pridala (nové položky sú hore). */
    val addedAt: Long,
    /** Kedy sa označila ako kúpená (naposledy kúpené je hore medzi kúpenými). */
    val doneAt: Long = 0L,
)

/**
 * Nákupný zoznam bez Androidu: pridanie, odškrtnutie, vymazanie kúpeného, poradie a texty.
 * Množstvo sa píše rovno do názvu („Mlieko 2 l“) – zoznam je zámerne jednoduchý, bez kategórií a jednotiek.
 */
object ShoppingList {
    const val MAX_ITEMS = 200
    const val MAX_NAME = 80

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
    ): List<ShopItem> {
        val name = clean(text)
        if (name.isEmpty()) return items
        val same = items.firstOrNull { it.name.equals(name, ignoreCase = true) }
        return when {
            same == null -> if (items.size >= MAX_ITEMS) items else items + ShopItem(newId(), name, false, now)
            same.done -> items.map { if (it.id == same.id) it.copy(done = false, addedAt = now, doneAt = 0L) else it }
            else -> items
        }
    }

    /** Prepne kúpené / nekúpené. */
    fun toggle(items: List<ShopItem>, id: String, now: Long): List<ShopItem> =
        items.map { if (it.id == id) it.copy(done = !it.done, doneAt = if (it.done) 0L else now) else it }

    /** „Vymazať kúpené“. */
    fun clearDone(items: List<ShopItem>): List<ShopItem> = items.filter { !it.done }

    /** Poradie na obrazovke: nekúpené (najnovšie hore), potom kúpené (naposledy kúpené hore). */
    fun arrange(items: List<ShopItem>): List<ShopItem> =
        items.filter { !it.done }.sortedByDescending { it.addedAt } + items.filter { it.done }.sortedByDescending { it.doneAt }

    fun left(items: List<ShopItem>): Int = items.count { !it.done }

    /** 1 položka, 2–4 položky, 5 a viac položiek. */
    fun itemsText(n: Int): String = when {
        n == 1 -> "1 položka"
        n in 2..4 -> "$n položky"
        else -> "$n položiek"
    }

    /** Text vpravo v riadku Nákup na Domove. */
    fun rowText(items: List<ShopItem>): String = when {
        items.isEmpty() -> "Prázdny"
        left(items) == 0 -> "Všetko kúpené"
        else -> itemsText(left(items))
    }

    /** Podnadpis obrazovky zoznamu. */
    fun subtitle(items: List<ShopItem>): String = when {
        items.isEmpty() -> "Zoznam je prázdny"
        left(items) == 0 -> "Všetko kúpené"
        else -> "Zostáva: ${left(items)}"
    }

    fun toJson(items: List<ShopItem>): String {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("i", it.id).put("n", it.name).put("d", it.done).put("a", it.addedAt).put("t", it.doneAt)) }
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
                if (name.isEmpty()) continue
                val id = o.optString("i").ifBlank { UUID.randomUUID().toString() }
                out += ShopItem(id, name, o.optBoolean("d"), o.optLong("a"), o.optLong("t"))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
