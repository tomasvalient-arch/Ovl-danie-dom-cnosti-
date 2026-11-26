package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs
import kotlin.math.round

/**
 * Jedálniček: recepty, týždenný plán (raňajky, obed a večera), zásoby a doplnenie nákupu.
 * Množstvá v nákupnom zozname sú v názve („Mlieko 2 l“); pri odškrtnutí sa
 * množstvo vyparsujú a pripočíta do zásob bez pýtania. Čistá logika bez Androidu.
 */
object Meals {
    const val PREFS = "domov_meals"
    const val KEY_RECIPES = "recipes"
    const val KEY_PLAN = "plan"
    const val KEY_PANTRY = "pantry"
    const val KEY_HOME = "home"

    val WEEKDAYS = arrayOf("Po", "Ut", "St", "Št", "Pi", "So", "Ne")

    /** Známe základné suroviny — pri doplnení nákupu ich nepridávame, aj keď v zásobách ešte nie sú. */
    private val STAPLES = setOf("sol", "olej", "korenie", "cierne korenie")

    enum class Slot(val code: String, val title: String, val shortTitle: String, val order: Int) {
        BREAKFAST("b", "Raňajky", "Raň.", 0),
        LUNCH("l", "Obed", "Obed", 1),
        DINNER("d", "Večera", "Več.", 2);

        companion object {
            val PLAN = listOf(BREAKFAST, LUNCH, DINNER)

            /** Chýbajúci alebo neznámy kód (dáta z 1.60) je obed. */
            fun of(code: String): Slot = when (code) {
                "b" -> BREAKFAST
                "d" -> DINNER
                else -> LUNCH
            }
        }
    }

    data class Qty(val amount: Double, val unit: String) {
        fun text(): String = formatQty(amount, unit)
    }

    /** Názov položky a množstvo vyčítané z konca reťazca (inak 1 ks). */
    data class Parsed(val name: String, val amount: Double, val unit: String) {
        fun qty(): Qty = Qty(amount, unit)
        fun shopText(): String = formatShop(name, amount, unit)
    }

    data class Ingredient(
        val name: String,
        val amount: Double,
        val unit: String,
    ) {
        fun qty(): Qty = Qty(amount, unit)
        fun shopText(): String = formatShop(name, amount, unit)
    }

    data class Recipe(
        val id: String,
        val name: String,
        val portions: Int = 4,
        val ingredients: List<Ingredient> = emptyList(),
        val updatedAt: Long = 0,
        val deleted: Boolean = false,
        val deviceId: String = "",
    )

    data class Meal(
        val id: String,
        val date: String,
        val slot: Slot,
        val recipeId: String = "",
        val title: String = "",
        val cookIds: List<String> = emptyList(),
        val portions: Int = 0,
        val cooked: Boolean = false,
        val updatedAt: Long = 0,
        val deleted: Boolean = false,
        val deviceId: String = "",
    )

    data class PantryItem(
        val id: String,
        val name: String,
        val amount: Double = 0.0,
        val unit: String = "ks",
        val always: Boolean = false,
        val expires: String? = null,
        val lowAt: Double = 0.0,
        val updatedAt: Long = 0,
        val deleted: Boolean = false,
        val deviceId: String = "",
    )

    enum class NeedKind { MISSING, ALREADY, ASK }

    data class ShopNeed(
        val name: String,
        val amount: Double,
        val unit: String,
        val kind: NeedKind,
        val sources: List<String> = emptyList(),
    ) {
        fun shopText(): String = formatShop(name, amount, unit)
    }

    data class NeededLine(
        val name: String,
        val amount: Double,
        val unit: String,
        val sources: List<String>,
    )

    fun fold(text: String): String = ShoppingHistory.fold(text)

    fun pantryId(name: String): String = "p:" + fold(name)

    fun mealId(date: String, slot: Slot): String = "m:$date:${slot.code}"

    fun newRecipeId(): String = UUID.randomUUID().toString()

    fun weekMonday(day: LocalDate): LocalDate {
        val shift = (day.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
        return day.minusDays(shift.toLong())
    }

    fun weekSunday(day: LocalDate): LocalDate = weekMonday(day).plusDays(6)

    /** Doplnenie nákupu sčíta od [from] (dnes) do nedele toho istého týždňa. */
    fun fillUntil(from: LocalDate): LocalDate = weekSunday(from)

    fun dayLabel(day: LocalDate): String = WEEKDAYS[day.dayOfWeek.value - 1]

    fun formatDate(day: LocalDate): String = "${day.dayOfMonth}. ${day.monthValue}."

    fun formatDateYear(day: LocalDate): String = "${day.dayOfMonth}. ${day.monthValue}. ${day.year}"

    fun formatWeekRange(monday: LocalDate): String {
        val sunday = weekSunday(monday)
        return if (monday.month == sunday.month) {
            "${monday.dayOfMonth}.–${sunday.dayOfMonth}. ${sunday.monthValue}. ${sunday.year}"
        } else {
            "${monday.dayOfMonth}. ${monday.monthValue}.–${sunday.dayOfMonth}. ${sunday.monthValue}. ${sunday.year}"
        }
    }

    fun cookLabel(ids: List<String>, namesById: Map<String, String>): String {
        if (ids.isEmpty()) return ""
        return ids.mapNotNull { namesById[it]?.trim()?.ifBlank { null } }.joinToString(", ")
    }

    fun portionsOf(meal: Meal, recipe: Recipe?): Int {
        val p = if (meal.portions > 0) meal.portions else recipe?.portions ?: 0
        return p.coerceAtLeast(1)
    }

    /** „2 porcie“, „1 porciu“, „10 porcií“ — pre text „Recept je pre…“. */
    fun portionsText(n: Int): String {
        val count = n.coerceAtLeast(1)
        val word = when (count) {
            1 -> "porciu"
            2, 3, 4 -> "porcie"
            else -> "porcií"
        }
        return "$count $word"
    }

    // ── množstvá ────────────────────────────────────────────────────────────

    private val UNIT_ALIASES = mapOf(
        "ks" to "ks", "kus" to "ks", "kusy" to "ks", "kusov" to "ks",
        "g" to "g", "gr" to "g", "gram" to "g", "gramy" to "g", "gramov" to "g",
        "kg" to "kg",
        "ml" to "ml",
        "dcl" to "ml", "cl" to "ml",
        "l" to "l", "lt" to "l", "liter" to "l", "litre" to "l", "litrov" to "l",
    )

    private val UNIT_SCALE = mapOf(
        "dcl" to 100.0,
        "cl" to 10.0,
    )

    /** Neurčité množstvá: zobrazia sa ako sú, neskálujú sa, nejdú do nákupu ani sa neodpočítavajú. */
    val FUZZY_UNITS = listOf("štipka", "poduška", "trocha", "podľa chuti")

    private val FUZZY_FOLD = FUZZY_UNITS.associateBy { fold(it) }

    private val UNIT_RE = UNIT_ALIASES.keys.joinToString("|") { Regex.escape(it) }

    private val TAIL_UNIT = Regex(
        """^(.*?)(?:\s+)?(\d+(?:[.,]\d+)?)\s*($UNIT_RE)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private val TAIL_NUMBER = Regex("""^(.*?)\s+(\d+(?:[.,]\d+)?)\s*$""")

    fun parseAmount(raw: String): Parsed {
        val text = raw.trim()
        if (text.isEmpty()) return Parsed("", 1.0, "ks")
        TAIL_UNIT.matchEntire(text)?.let { m ->
            val name = m.groupValues[1].trim().trimEnd(',', '.', ';', '-')
            val amount = parseNumber(m.groupValues[2])
            val rawUnit = m.groupValues[3].lowercase()
            val unit = UNIT_ALIASES[rawUnit] ?: "ks"
            val scaled = amount * (UNIT_SCALE[rawUnit] ?: 1.0)
            if (name.isEmpty()) return Parsed(text, 1.0, "ks")
            if (amount <= 0.0) return Parsed(name, 1.0, "ks")
            return Parsed(name, scaled, unit)
        }
        TAIL_NUMBER.matchEntire(text)?.let { m ->
            val name = m.groupValues[1].trim().trimEnd(',', '.', ';', '-')
            val amount = parseNumber(m.groupValues[2])
            if (name.isNotEmpty() && amount > 0.0) return Parsed(name, amount, "ks")
        }
        return Parsed(text, 1.0, "ks")
    }

    fun parseNumber(raw: String): Double =
        raw.trim().replace(',', '.').toDoubleOrNull() ?: 0.0

    fun formatQty(amount: Double, unit: String): String {
        fuzzyLabel(unit)?.let { return it }
        val u = canonicalUnit(unit)
        return "${fmtNum(amount)} $u"
    }

    fun formatShop(name: String, amount: Double, unit: String): String {
        val n = name.trim()
        if (n.isEmpty()) return formatQty(amount, unit)
        return "$n ${formatQty(amount, unit)}"
    }

    fun fmtNum(amount: Double): String {
        val r = round(amount)
        if (abs(amount - r) < 1e-6) return r.toLong().toString()
        val s = String.format(java.util.Locale.US, "%.2f", amount).trimEnd('0').trimEnd('.')
        return s.replace('.', ',')
    }

    fun canonicalUnit(unit: String): String = UNIT_ALIASES[unit.trim().lowercase()] ?: unit.trim().ifBlank { "ks" }

    /** ks, g, kg, ml, l — pri chýbajúcej zásobe idú rovno do nákupu; iné jednotky (hlavička, lyžica) do Overiť. */
    fun isKnownUnit(unit: String): Boolean = !isFuzzy(unit) && unit.trim().lowercase() in UNIT_ALIASES

    fun fuzzyLabel(unit: String): String? = FUZZY_FOLD[fold(unit)]

    fun isFuzzy(unit: String): Boolean = fuzzyLabel(unit) != null

    fun family(unit: String): String {
        fuzzyLabel(unit)?.let { return "fuzzy:$it" }
        return when (canonicalUnit(unit)) {
            "g", "kg" -> "g"
            "ml", "l" -> "ml"
            else -> "ks"
        }
    }

    fun toBase(amount: Double, unit: String): Double = when (canonicalUnit(unit)) {
        "kg" -> amount * 1000.0
        "l" -> amount * 1000.0
        else -> amount
    }

    fun fromBase(base: Double, unit: String): Double = when (canonicalUnit(unit)) {
        "kg" -> base / 1000.0
        "l" -> base / 1000.0
        else -> base
    }

    fun canMerge(u1: String, u2: String): Boolean = family(u1) == family(u2)

    /** Súčet v „krajšej“ jednotke (kg/l od 1000 g/ml). */
    fun sumQty(a1: Double, u1: String, a2: Double, u2: String): Qty {
        require(canMerge(u1, u2)) { "nezlučiteľné jednotky $u1 a $u2" }
        val base = toBase(a1, u1) + toBase(a2, u2)
        return pretty(base, family(u1))
    }

    fun subQty(haveA: Double, haveU: String, needA: Double, needU: String): Qty {
        require(canMerge(haveU, needU)) { "nezlučiteľné jednotky $haveU a $needU" }
        val left = (toBase(needA, needU) - toBase(haveA, haveU)).coerceAtLeast(0.0)
        return pretty(left, family(needU))
    }

    fun enough(haveA: Double, haveU: String, needA: Double, needU: String): Boolean {
        if (!canMerge(haveU, needU)) return false
        return toBase(haveA, haveU) + 1e-6 >= toBase(needA, needU)
    }

    fun pretty(base: Double, family: String): Qty = when (family) {
        "g" -> if (base >= 1000.0 - 1e-6) Qty(base / 1000.0, "kg") else Qty(base, "g")
        "ml" -> if (base >= 1000.0 - 1e-6) Qty(base / 1000.0, "l") else Qty(base, "ml")
        else -> Qty(base, "ks")
    }

    /** Kusy nahor na celé alebo polovice (1,25 → 1,5; 0,1 → 0,5). */
    fun roundKsUp(amount: Double): Double {
        if (amount <= 0.0) return 0.0
        return kotlin.math.ceil(amount * 2.0 - 1e-9) / 2.0
    }

    /**
     * Zaokrúhlenie odvážených/odmeraných množstiev v základnej jednotke (g / ml):
     * od 100 na 5, od 10 na 1, od 1 na 0,5, menšie na 0,1 (najmenej 0,1).
     */
    fun roundMeas(base: Double): Double {
        if (base <= 0.0) return 0.0
        return when {
            base >= 100.0 - 1e-9 -> round(base / 5.0) * 5.0
            base >= 10.0 - 1e-9 -> round(base)
            base >= 1.0 - 1e-9 -> round(base * 2.0) / 2.0
            else -> (round(base * 10.0) / 10.0).coerceAtLeast(0.1)
        }
    }

    fun scaleQty(amount: Double, unit: String, usedPortions: Int, basePortions: Int): Qty {
        if (isFuzzy(unit)) return Qty(amount, fuzzyLabel(unit) ?: unit)
        val used = usedPortions.coerceAtLeast(1)
        val base = basePortions.coerceAtLeast(1)
        if (used == base) return Qty(amount, if (unit.trim().lowercase() in UNIT_ALIASES) canonicalUnit(unit) else unit)
        val raw = amount * used.toDouble() / base
        return roundScaled(raw, unit)
    }

    fun roundScaled(amount: Double, unit: String): Qty {
        if (isFuzzy(unit)) return Qty(amount, fuzzyLabel(unit) ?: unit)
        val fam = family(unit)
        if (fam.startsWith("fuzzy:")) return Qty(amount, unit)
        if (fam != "g" && fam != "ml") {
            val u = if (unit.trim().lowercase() in UNIT_ALIASES) "ks" else unit
            return Qty(roundKsUp(amount), u)
        }
        return pretty(roundMeas(toBase(amount, unit)), fam)
    }

    fun stepFor(unit: String): Double = when (canonicalUnit(unit)) {
        "kg", "l" -> 0.1
        "g", "ml" -> 50.0
        else -> 1.0
    }

    fun bump(item: PantryItem, dir: Int): PantryItem {
        val step = stepFor(item.unit) * if (dir >= 0) 1 else -1
        val next = (item.amount + step).coerceAtLeast(0.0)
        return item.copy(amount = next)
    }

    // ── zásoby ──────────────────────────────────────────────────────────────

    fun isLow(item: PantryItem): Boolean {
        if (item.deleted || item.always) return false
        if (item.amount <= 0.0) return true
        if (item.lowAt > 0.0 && item.amount <= item.lowAt) return true
        return canonicalUnit(item.unit) == "ks" && item.amount <= 2.0
    }

    fun isExpiring(item: PantryItem, today: LocalDate, withinDays: Long = 3): Boolean {
        if (item.deleted || item.always) return false
        val exp = item.expires?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        return !exp.isAfter(today.plusDays(withinDays))
    }

    fun isStaple(name: String): Boolean {
        val parts = name.split(',', ';', '+', '/')
            .map { fold(it) }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        return parts.all { it in STAPLES }
    }

    fun addPurchased(pantry: List<PantryItem>, rawName: String, now: Long, deviceId: String): List<PantryItem> {
        val parsed = parseAmount(rawName)
        val name = parsed.name.ifBlank { rawName.trim() }
        if (name.isEmpty()) return pantry
        val key = fold(name)
        val live = pantry.filter { !it.deleted }
        val existing = live.firstOrNull { fold(it.name) == key }
            ?: pantry.firstOrNull { it.id == pantryId(name) }
        return if (existing == null) {
            pantry + PantryItem(
                id = pantryId(name),
                name = name,
                amount = parsed.amount,
                unit = parsed.unit,
                updatedAt = now,
                deviceId = deviceId,
            )
        } else {
            val added = if (canMerge(existing.unit, parsed.unit)) {
                sumQty(existing.amount, existing.unit, parsed.amount, parsed.unit)
            } else {
                Qty(parsed.amount, parsed.unit)
            }
            pantry.map {
                if (it.id != existing.id) it
                else it.copy(
                    name = name,
                    amount = added.amount,
                    unit = added.unit,
                    deleted = false,
                    updatedAt = now,
                    deviceId = deviceId,
                )
            }
        }
    }

    fun deduct(
        pantry: List<PantryItem>,
        recipe: Recipe,
        portions: Int,
        now: Long,
        deviceId: String,
    ): List<PantryItem> {
        var next = pantry
        for (ing in scaledIngredients(recipe, portions)) {
            if (isFuzzy(ing.unit) || isStaple(ing.name)) continue
            val need = ing.qty()
            val key = fold(ing.name)
            val hit = next.firstOrNull { !it.deleted && fold(it.name) == key } ?: continue
            if (hit.always) continue
            if (!canMerge(hit.unit, need.unit)) continue
            val left = (toBase(hit.amount, hit.unit) - toBase(need.amount, need.unit)).coerceAtLeast(0.0)
            val pretty = pretty(left, family(hit.unit))
            next = next.map {
                if (it.id != hit.id) it
                else it.copy(amount = pretty.amount, unit = pretty.unit, updatedAt = now, deviceId = deviceId)
            }
        }
        return next
    }

    fun defaultStaples(now: Long, deviceId: String): List<PantryItem> = listOf(
        PantryItem(id = pantryId("Soľ"), name = "Soľ", always = true, amount = 0.0, unit = "ks", updatedAt = now, deviceId = deviceId),
        PantryItem(id = pantryId("Olej"), name = "Olej", always = true, amount = 0.0, unit = "ks", updatedAt = now, deviceId = deviceId),
        PantryItem(id = pantryId("Korenie"), name = "Korenie", always = true, amount = 0.0, unit = "ks", updatedAt = now, deviceId = deviceId),
    )

    // ── súčet surovín a doplnenie nákupu ────────────────────────────────────

    fun scaledIngredients(recipe: Recipe, portions: Int): List<Ingredient> {
        val used = portions.coerceAtLeast(1)
        val base = recipe.portions.coerceAtLeast(1)
        return recipe.ingredients.map { ing ->
            val q = scaleQty(ing.amount, ing.unit, used, base)
            ing.copy(amount = q.amount, unit = q.unit)
        }
    }

    /** Zlúči rovnaké suroviny (zhoda názvu bez diakritiky a zlučiteľná jednotka). */
    fun mergeIngredients(items: List<Ingredient>): List<Ingredient> {
        val out = ArrayList<Ingredient>()
        for (item in items) {
            val key = fold(item.name)
            val i = out.indexOfFirst { fold(it.name) == key && canMerge(it.unit, item.unit) }
            if (i < 0) out += item
            else {
                val sum = sumQty(out[i].amount, out[i].unit, item.amount, item.unit)
                out[i] = out[i].copy(amount = sum.amount, unit = sum.unit)
            }
        }
        return out
    }

    fun neededForRange(
        from: LocalDate,
        to: LocalDate,
        meals: List<Meal>,
        recipes: List<Recipe>,
    ): List<NeededLine> {
        val byId = recipes.filter { !it.deleted }.associateBy { it.id }
        data class Acc(var amount: Double, var unit: String, val sources: MutableList<String>, val name: String)
        val acc = LinkedHashMap<String, Acc>()
        val mealsIn = meals.filter { !it.deleted && !it.cooked && it.recipeId.isNotEmpty() }
            .mapNotNull { m ->
                val d = runCatching { LocalDate.parse(m.date) }.getOrNull() ?: return@mapNotNull null
                if (d.isBefore(from) || d.isAfter(to)) return@mapNotNull null
                m to d
            }
            .sortedWith(compareBy({ it.second }, { it.first.slot.order }))
        for ((meal, day) in mealsIn) {
            val recipe = byId[meal.recipeId] ?: continue
            val source = "${dayLabel(day)} · ${meal.title.ifBlank { recipe.name }}"
            for (ing in scaledIngredients(recipe, portionsOf(meal, recipe))) {
                if (ing.name.isBlank() || isFuzzy(ing.unit)) continue
                val key = fold(ing.name) + ":" + family(ing.unit)
                val cur = acc[key]
                if (cur == null) {
                    acc[key] = Acc(ing.amount, ing.unit, mutableListOf(source), ing.name)
                } else if (canMerge(cur.unit, ing.unit)) {
                    val sum = sumQty(cur.amount, cur.unit, ing.amount, ing.unit)
                    cur.amount = sum.amount
                    cur.unit = sum.unit
                    if (source !in cur.sources) cur.sources += source
                }
            }
        }
        return acc.values.map { NeededLine(it.name, it.amount, it.unit, it.sources) }
    }

    /**
     * Chýbajúce suroviny od [from] do [to] (vrátane).
     * Vždy doma a známe základné suroviny sa vynechajú.
     * Položky, ktoré už sú v cieľovom zozname (nekúpené), idú do [NeedKind.ALREADY].
     * Surovina bez záznamu v zásobách ide do [NeedKind.ASK] (Overiť) — nikdy sa nepridá potichu.
     * Od zásoby sa odráta to, čo je na sklade (zlučiteľné jednotky).
     */
    fun planShopping(
        from: LocalDate,
        to: LocalDate,
        meals: List<Meal>,
        recipes: List<Recipe>,
        pantry: List<PantryItem>,
        shopItems: List<ShopItem>,
        target: ShopListKind = ShopListKind.SHARED,
    ): List<ShopNeed> {
        val stock = pantry.filter { !it.deleted }
        val alreadyNames = shopItems
            .filter { !it.deleted && !it.done && it.list == target }
            .map { parseAmount(it.name) }
            .filter { it.name.isNotBlank() }
            .associateBy { fold(it.name) }
        val out = ArrayList<ShopNeed>()
        for (line in neededForRange(from, to, meals, recipes)) {
            if (isStaple(line.name)) continue
            val key = fold(line.name)
            val item = stock.firstOrNull { fold(it.name) == key }
            if (item?.always == true) continue
            if (alreadyNames.containsKey(key)) {
                out += ShopNeed(line.name, line.amount, line.unit, NeedKind.ALREADY, line.sources)
                continue
            }
            if (item == null) {
                val kind = if (isKnownUnit(line.unit)) NeedKind.MISSING else NeedKind.ASK
                out += ShopNeed(line.name, line.amount, line.unit, kind, line.sources)
                continue
            }
            if (!canMerge(item.unit, line.unit)) {
                out += ShopNeed(line.name, line.amount, line.unit, NeedKind.ASK, line.sources)
                continue
            }
            if (enough(item.amount, item.unit, line.amount, line.unit)) continue
            val miss = subQty(item.amount, item.unit, line.amount, line.unit)
            if (miss.amount <= 1e-6) continue
            out += ShopNeed(line.name, miss.amount, miss.unit, NeedKind.MISSING, line.sources)
        }
        return out
    }

    fun mealOn(meals: List<Meal>, date: LocalDate, slot: Slot): Meal? =
        meals.firstOrNull { !it.deleted && it.date == date.toString() && it.slot == slot }

    fun mealsOn(meals: List<Meal>, date: LocalDate): List<Meal> =
        meals.filter { !it.deleted && it.date == date.toString() }.sortedBy { it.slot.order }

    // ── JSON ────────────────────────────────────────────────────────────────

    fun recipesToJson(list: List<Recipe>): String {
        val a = JSONArray()
        for (r in list) a.put(recipeObj(r))
        return a.toString()
    }

    fun recipesFromJson(raw: String): List<Recipe> {
        if (raw.isBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i -> recipeFrom(a.optJSONObject(i) ?: return@mapNotNull null) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun mealsToJson(list: List<Meal>): String {
        val a = JSONArray()
        for (m in list) a.put(mealObj(m))
        return a.toString()
    }

    fun mealsFromJson(raw: String): List<Meal> {
        if (raw.isBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i -> mealFrom(a.optJSONObject(i) ?: return@mapNotNull null) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun pantryToJson(list: List<PantryItem>): String {
        val a = JSONArray()
        for (p in list) a.put(pantryObj(p))
        return a.toString()
    }

    fun pantryFromJson(raw: String): List<PantryItem> {
        if (raw.isBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i -> pantryFrom(a.optJSONObject(i) ?: return@mapNotNull null) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun recipeObj(r: Recipe): JSONObject = JSONObject()
        .put("i", r.id)
        .put("n", r.name)
        .put("p", r.portions)
        .put("g", JSONArray().also { a ->
            for (ing in r.ingredients) {
                a.put(JSONObject().put("n", ing.name).put("a", ing.amount).put("u", ing.unit))
            }
        })
        .put("ts", r.updatedAt)
        .put("x", r.deleted)
        .put("dv", r.deviceId)

    fun mealObj(m: Meal): JSONObject = JSONObject()
        .put("i", m.id)
        .put("d", m.date)
        .put("s", m.slot.code)
        .put("r", m.recipeId)
        .put("t", m.title)
        .put("c", JSONArray(m.cookIds))
        .put("p", m.portions)
        .put("k", m.cooked)
        .put("ts", m.updatedAt)
        .put("x", m.deleted)
        .put("dv", m.deviceId)

    fun pantryObj(p: PantryItem): JSONObject = JSONObject()
        .put("i", p.id)
        .put("n", p.name)
        .put("a", p.amount)
        .put("u", p.unit)
        .put("w", p.always)
        .put("e", p.expires ?: "")
        .put("l", p.lowAt)
        .put("ts", p.updatedAt)
        .put("x", p.deleted)
        .put("dv", p.deviceId)

    fun recipeFrom(o: JSONObject): Recipe? {
        val id = o.optString("i")
        if (id.isEmpty()) return null
        val g = o.optJSONArray("g") ?: JSONArray()
        val ings = (0 until g.length()).mapNotNull { i ->
            val x = g.optJSONObject(i) ?: return@mapNotNull null
            val n = x.optString("n").trim()
            if (n.isEmpty()) return@mapNotNull null
            Ingredient(n, x.optDouble("a", 0.0), x.optString("u").ifBlank { "ks" })
        }
        return Recipe(
            id = id,
            name = o.optString("n"),
            portions = o.optInt("p", 4).coerceAtLeast(1),
            ingredients = ings,
            updatedAt = o.optLong("ts"),
            deleted = o.optBoolean("x"),
            deviceId = o.optString("dv"),
        )
    }

    fun mealFrom(o: JSONObject): Meal? {
        val id = o.optString("i")
        val date = o.optString("d")
        if (id.isEmpty() || date.isEmpty()) return null
        val c = o.optJSONArray("c") ?: JSONArray()
        val cooks = (0 until c.length()).map { c.optString(it) }.filter { it.isNotEmpty() }
        return Meal(
            id = id,
            date = date,
            slot = Slot.of(o.optString("s")),
            recipeId = o.optString("r"),
            title = o.optString("t"),
            cookIds = cooks,
            portions = o.optInt("p"),
            cooked = o.optBoolean("k"),
            updatedAt = o.optLong("ts"),
            deleted = o.optBoolean("x"),
            deviceId = o.optString("dv"),
        )
    }

    fun pantryFrom(o: JSONObject): PantryItem? {
        val id = o.optString("i")
        if (id.isEmpty()) return null
        val exp = o.optString("e").ifBlank { null }
        return PantryItem(
            id = id,
            name = o.optString("n"),
            amount = o.optDouble("a", 0.0),
            unit = o.optString("u").ifBlank { "ks" },
            always = o.optBoolean("w"),
            expires = exp,
            lowAt = o.optDouble("l", 0.0),
            updatedAt = o.optLong("ts"),
            deleted = o.optBoolean("x"),
            deviceId = o.optString("dv"),
        )
    }
}
