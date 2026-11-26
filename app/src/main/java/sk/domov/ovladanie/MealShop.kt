package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Prepojenie jedálnička a nákupu: z ktorých jedál sa položka v zozname vzala, a čo s ňou, keď sa plán zmení.
 *
 * Keď z „Doplniť nákup“ pridáš alebo zvýšíš položku, zapamätá sa [Link] (id položky, množstvo pridané z plánu a z ktorých
 * jedál). Ak sa potom jedlo zruší, preskočí, alebo sa zmení recept či počet porcií, [reconcile] navrhne znížiť alebo
 * odstrániť to, čo z plánu už netreba, a zároveň dorátať to, čo pribudlo – ako jeden návrh na jednu položku.
 * Nič sa nemení samo: návrhy sa uplatnia až po potvrdení. Čistá logika bez Androidu; väzby sa ukladajú len v tomto telefóne.
 */
object MealShop {
    /**
     * [added] = množstvo pridané z plánu v základnej jednotke ([family] g / ml / ks), [parts] = jedlá, z ktorých pochádza
     * (sú to hrubé potreby jedál; podiel zrušených jedál z nich určuje, koľko sa odráta z [added]).
     */
    data class Link(
        val itemId: String,
        val key: String,
        val family: String,
        val added: Double,
        val parts: List<Meals.Part>,
    )

    /** Pridanie / zvýšenie položky z plánu: väzba sa vytvorí alebo doplní (jedlo sa nahradí novším záznamom). */
    fun record(links: List<Link>, itemId: String, key: String, family: String, addedBase: Double, parts: List<Meals.Part>): List<Link> {
        if (itemId.isBlank() || addedBase <= 0.0) return links
        val old = links.firstOrNull { it.itemId == itemId }
        val merged = LinkedHashMap<String, Meals.Part>()
        old?.parts?.forEach { merged[it.mealId] = it }
        parts.forEach { merged[it.mealId] = it }
        val link = Link(itemId, key, family, (old?.added ?: 0.0) + addedBase, merged.values.toList())
        return links.filter { it.itemId != itemId } + link
    }

    /** Väzby na položky, ktoré sú ešte v zozname a nekúpené (kúpená alebo odstránená položka už nič nepotrebuje). */
    fun prune(links: List<Link>, items: List<ShopItem>): List<Link> =
        links.filter { l -> items.any { it.id == l.itemId && !it.deleted && !it.done } }

    /** Je táto časť zastaraná: jedlo zmizlo, preskočilo sa, alebo má iný recept či porcie, než sa počítalo? */
    fun partStale(part: Meals.Part, meals: List<Meals.Meal>, recipes: List<Meals.Recipe>): Boolean {
        val meal = meals.firstOrNull { it.id == part.mealId && !it.deleted } ?: return true
        if (meal.skipped || meal.recipeId.isEmpty()) return true
        val recipe = recipes.firstOrNull { it.id == meal.recipeId && !it.deleted } ?: return true
        return Meals.sig(meal, recipe) != part.sig
    }

    /** Podiel zastaraných častí (podľa množstva) 0..1. */
    fun staleFraction(link: Link, meals: List<Meals.Meal>, recipes: List<Meals.Recipe>): Double {
        val total = link.parts.sumOf { it.base }
        if (total <= 0.0) return 0.0
        val stale = link.parts.filter { partStale(it, meals, recipes) }.sumOf { it.base }
        return (stale / total).coerceIn(0.0, 1.0)
    }

    /** Návrh zmeny jednej položky v zozname: [newText] prázdny = odstrániť. */
    data class Reduction(
        val item: ShopItem,
        val link: Link,
        val newText: String,
        /** Koľko základných jednotiek sa z väzby odráta ([Link.added] sa o to zníži). */
        val shareBase: Double,
    )

    private const val EPS = 1e-6

    fun reductions(links: List<Link>, items: List<ShopItem>, meals: List<Meals.Meal>, recipes: List<Meals.Recipe>): List<Reduction> {
        val out = ArrayList<Reduction>()
        for (link in links) {
            val item = items.firstOrNull { it.id == link.itemId && !it.deleted && !it.done } ?: continue
            val frac = staleFraction(link, meals, recipes)
            if (frac <= EPS) continue
            val parsed = Meals.parseAmount(item.name)
            if (!Meals.hasQuantity(item.name) || !Meals.isKnownUnit(parsed.unit) || Meals.family(parsed.unit) != link.family) continue
            val current = Meals.toBase(parsed.amount, parsed.unit)
            val share = (link.added * frac).coerceAtMost(link.added)
            val remain = current - share
            val tiny = if (link.family == "ks") 0.5 else 10.0
            val whole = frac >= 1.0 - EPS && current <= link.added + EPS
            if (whole || remain < tiny - EPS) {
                out += Reduction(item, link, "", share)
            } else {
                val q = Meals.pretty(remain, link.family)
                val r = Meals.roundScaled(q.amount, q.unit)
                out += Reduction(item, link, Meals.formatShop(parsed.name, r.amount, r.unit), share)
            }
        }
        return out
    }

    /** Väzba po uplatnení návrhu: zrušené časti preč, pridané množstvo znížené; null = väzba zaniká (položka odstránená). */
    fun afterReduction(r: Reduction, meals: List<Meals.Meal>, recipes: List<Meals.Recipe>): Link? {
        if (r.newText.isEmpty()) return null
        val parts = r.link.parts.filter { !partStale(it, meals, recipes) }
        val added = (r.link.added - r.shareBase).coerceAtLeast(0.0)
        if (parts.isEmpty() || added <= 0.0) return null
        return r.link.copy(added = added, parts = parts)
    }

    /**
     * Jeden zlúčený návrh: [Meals.planShopping] nad zoznamom, v ktorom sú už zastarané množstvá znížené, plus
     * riadky [Meals.NeedKind.STALE] pre to, čo plán už nepotrebuje. Rovnaká položka nemá dva protichodné riadky
     * (zníž a zároveň zvýš): vyjde jediný TOPUP s výsledným množstvom.
     */
    fun reconcile(
        from: LocalDate,
        to: LocalDate,
        meals: List<Meals.Meal>,
        recipes: List<Meals.Recipe>,
        pantry: List<Meals.PantryItem>,
        shopItems: List<ShopItem>,
        target: ShopListKind,
        links: List<Link>,
    ): List<Meals.ShopNeed> {
        val live = shopItems.filter { !it.deleted && !it.done }
        val reds = reductions(links, live, meals, recipes)
        val virtual = shopItems.map { s ->
            val r = reds.firstOrNull { it.item.id == s.id } ?: return@map s
            if (r.newText.isEmpty()) s.copy(deleted = true) else s.copy(name = r.newText)
        }
        val needs = ArrayList(Meals.planShopping(from, to, meals, recipes, pantry, virtual, target))
        val staleRows = ArrayList<Meals.ShopNeed>()
        for (r in reds) {
            val parsed = Meals.parseAmount(r.item.name)
            val k = Meals.key(parsed.name)
            val before = Meals.formatShop(parsed.name, parsed.amount, parsed.unit)
            val topIdx = needs.indexOfFirst {
                it.itemId == r.item.id && it.kind == Meals.NeedKind.TOPUP
            }
            val missIdx = needs.indexOfFirst {
                Meals.key(it.name) == k && (it.kind == Meals.NeedKind.MISSING || it.kind == Meals.NeedKind.ASK) && Meals.family(it.unit) == r.link.family
            }
            when {
                topIdx >= 0 -> {
                    val n = needs[topIdx]
                    needs[topIdx] = n.copy(note = "Bolo $before, plán sa zmenil")
                }
                missIdx >= 0 && r.newText.isEmpty() -> {
                    // položka by sa odstránila a hneď znova pridala: radšej jedna zmena množstva
                    val n = needs[missIdx]
                    needs[missIdx] = n.copy(kind = Meals.NeedKind.TOPUP, itemId = r.item.id, newText = n.shopText(), note = "Bolo $before, plán sa zmenil")
                }
                else -> {
                    val shown = if (r.newText.isEmpty()) parsed else Meals.parseAmount(r.newText)
                    staleRows += Meals.ShopNeed(
                        name = parsed.name, amount = shown.amount, unit = shown.unit, kind = Meals.NeedKind.STALE,
                        itemId = r.item.id, newText = r.newText,
                        note = if (r.newText.isEmpty()) "Už netreba · plán sa zmenil" else "Bolo $before · plán sa zmenil",
                    )
                }
            }
        }
        val staleIds = staleRows.map { it.itemId }.toSet()
        return needs.filterNot { it.kind == Meals.NeedKind.ALREADY && it.itemId in staleIds } + staleRows
    }

    // ── ukladanie väzieb (JSON) ────────────────────────────────────────────

    fun toJson(links: List<Link>): String {
        val a = JSONArray()
        for (l in links) {
            a.put(
                JSONObject().put("i", l.itemId).put("k", l.key).put("f", l.family).put("a", l.added)
                    .put("p", JSONArray().also { arr -> l.parts.forEach { arr.put(JSONObject().put("m", it.mealId).put("s", it.sig).put("b", it.base)) } }),
            )
        }
        return a.toString()
    }

    fun fromJson(raw: String?): List<Link> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("i")
                if (id.isEmpty()) return@mapNotNull null
                val p = o.optJSONArray("p") ?: JSONArray()
                val parts = (0 until p.length()).mapNotNull { j ->
                    val x = p.optJSONObject(j) ?: return@mapNotNull null
                    val m = x.optString("m")
                    if (m.isEmpty()) null else Meals.Part(m, x.optString("s"), x.optDouble("b", 0.0))
                }
                Link(id, o.optString("k"), o.optString("f", "ks"), o.optDouble("a", 0.0), parts)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
