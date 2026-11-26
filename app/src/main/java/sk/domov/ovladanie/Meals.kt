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
        /** „Nevarilo sa“ (odpovedané v Uvarené? alebo zrušené): nejde do nákupu ani sa nepýta znova. */
        val skipped: Boolean = false,
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
        /** Koľko váži 1 ks v gramoch (0 = neznáme; vtedy sa použije tabuľka bežných surovín). */
        val pieceG: Double = 0.0,
        /** Obvyklé balenie v základnej jednotke (g / ml / ks): naučí sa z posledného nákupu s množstvom. */
        val pack: Double = 0.0,
    )

    /**
     * MISSING = chýba, pridať; ALREADY = už je v zozname; ASK = nejasné (pridá sa po zaškrtnutí);
     * TOPUP = v zozname je menej, než treba (zvýšiť množstvo); STALE = v zozname je viac, než plán potrebuje (znížiť / odstrániť).
     */
    enum class NeedKind { MISSING, ALREADY, ASK, TOPUP, STALE }

    /** Časť potreby z jedného jedla v pláne (základná jednotka g / ml / ks); [sig] = recept a porcie, z ktorých sa počítalo. */
    data class Part(val mealId: String, val sig: String, val base: Double)

    data class ShopNeed(
        val name: String,
        val amount: Double,
        val unit: String,
        val kind: NeedKind,
        val sources: List<String> = emptyList(),
        /** TOPUP / STALE / ALREADY: položka v zozname, ktorej sa to týka. */
        val itemId: String = "",
        /** TOPUP / STALE: nový text položky; pri STALE prázdny = odstrániť. */
        val newText: String = "",
        /** Krátka poznámka pod riadkom (napr. „Na zozname je 1 l“). */
        val note: String = "",
        val parts: List<Part> = emptyList(),
    ) {
        fun shopText(): String = formatShop(name, amount, unit)

        /** Jednoznačný kľúč riadku v dialógu. */
        fun uiKey(): String = "${kind.name}:$itemId:${shopText()}"
    }

    data class NeededLine(
        val name: String,
        val amount: Double,
        val unit: String,
        val sources: List<String>,
        val parts: List<Part> = emptyList(),
    )

    fun fold(text: String): String = ShoppingHistory.fold(text)

    fun pantryId(name: String): String = "p:" + fold(name)

    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /** Zjednodušenie slova na základ: koncové samohlásky preč („paradajka/paradajky“, „vajce/vajcia“ → rovnaký základ). */
    private fun stem(word: String): String {
        var s = word
        while (s.length > 3 && s.last() in "aeiouy") s = s.dropLast(1)
        return s
    }

    /**
     * Kľúč na porovnávanie názvov surovín: bez diakritiky, veľkosti písmen, interpunkcie a koncoviek
     * (množné číslo, 2. pád). „Paradajky“, „paradajka“ aj „Paradajka“ sú to isté; „Mlieko“ a „mlieka“ tiež.
     * Id zásoby ([pantryId]) sa tým nemení – kľúč slúži len na hľadanie zhody.
     */
    fun key(name: String): String =
        fold(name).replace(NON_ALNUM, " ").trim().split(' ').filter { it.isNotEmpty() }.joinToString(" ") { stem(it) }

    private val STAPLE_KEYS: Set<String> by lazy { STAPLES.map { key(it) }.toSet() }

    /** Približná hmotnosť 1 ks bežných surovín (g) – na prepočet „2 ks cibule“ ↔ „300 g“. */
    private val PIECE_RAW = mapOf(
        "cibuľa" to 150.0, "mrkva" to 100.0, "zemiak" to 120.0, "paradajka" to 150.0, "rajčina" to 150.0,
        "paprika" to 150.0, "uhorka" to 300.0, "cuketa" to 250.0, "baklažán" to 300.0, "kaleráb" to 300.0,
        "brokolica" to 400.0, "karfiol" to 800.0, "zeler" to 400.0, "petržlen" to 80.0, "šalát" to 300.0,
        "jablko" to 180.0, "hruška" to 180.0, "banán" to 120.0, "citrón" to 100.0, "pomaranč" to 200.0,
        "avokádo" to 200.0, "mozzarella" to 125.0,
    )

    private val PIECE_TABLE: Map<String, Double> by lazy { PIECE_RAW.mapKeys { key(it.key) } }

    /** Hmotnosť 1 ks v g: z položky v zásobách, inak z tabuľky (aj podľa posledného slova: „červená cibuľa“); 0 = neznáme. */
    fun pieceWeight(name: String, item: PantryItem? = null): Double {
        if (item != null && item.pieceG > 0.0) return item.pieceG
        val k = key(name)
        PIECE_TABLE[k]?.let { return it }
        return PIECE_TABLE[k.substringAfterLast(' ')] ?: 0.0
    }

    fun mealId(date: String, slot: Slot): String = "m:$date:${slot.code}"

    fun newRecipeId(): String = UUID.randomUUID().toString()

    fun weekMonday(day: LocalDate): LocalDate {
        val shift = (day.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
        return day.minusDays(shift.toLong())
    }

    fun weekSunday(day: LocalDate): LocalDate = weekMonday(day).plusDays(6)

    /** Doplnenie nákupu sčíta od [from] (dnes) do nedele toho istého týždňa. */
    fun fillUntil(from: LocalDate): LocalDate = weekSunday(from)

    /**
     * Doplnenie nákupu s nastaveným dňom nákupu [shopDay] (1 = pondelok … 7 = nedeľa; 0 = nenastavené → do nedele).
     * Pokrýva čas až do dňa pred nákupom po najbližšom nákupe (najbližší nákup + 6 dní; ak je dnes deň nákupu, dnes + 6 dní).
     */
    fun fillUntil(from: LocalDate, shopDay: Int): LocalDate {
        if (shopDay !in 1..7) return weekSunday(from)
        val shift = (shopDay - from.dayOfWeek.value + 7) % 7
        return from.plusDays(shift.toLong() + 6)
    }

    /** Text pre „Súčet chýbajúceho od dnes do …“. */
    fun rangeLabel(from: LocalDate, to: LocalDate): String =
        if (to == weekSunday(from)) "do nedele" else "do ${dayLabel(to).lowercase()} ${formatDate(to)}"

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

    fun parseAmount(raw: String): Parsed = parseInternal(raw).first

    /** Je množstvo v texte napísané výslovne („Mlieko 2 l“), alebo sa len predpokladá 1 ks („Mlieko“)? */
    fun hasQuantity(raw: String): Boolean = parseInternal(raw).second

    private fun parseInternal(raw: String): Pair<Parsed, Boolean> {
        val text = raw.trim()
        if (text.isEmpty()) return Parsed("", 1.0, "ks") to false
        TAIL_UNIT.matchEntire(text)?.let { m ->
            val name = m.groupValues[1].trim().trimEnd(',', '.', ';', '-')
            val amount = parseNumber(m.groupValues[2])
            val rawUnit = m.groupValues[3].lowercase()
            val unit = UNIT_ALIASES[rawUnit] ?: "ks"
            val scaled = amount * (UNIT_SCALE[rawUnit] ?: 1.0)
            if (name.isEmpty()) return Parsed(text, 1.0, "ks") to false
            if (amount <= 0.0) return Parsed(name, 1.0, "ks") to false
            return Parsed(name, scaled, unit) to true
        }
        TAIL_NUMBER.matchEntire(text)?.let { m ->
            val name = m.groupValues[1].trim().trimEnd(',', '.', ';', '-')
            val amount = parseNumber(m.groupValues[2])
            if (name.isNotEmpty() && amount > 0.0) return Parsed(name, amount, "ks") to true
        }
        return Parsed(text, 1.0, "ks") to false
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
            .map { key(it) }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        return parts.all { it in STAPLE_KEYS }
    }

    /** Množstvo v základnej jednotke rodiny [target] (g / ml / ks); ks ↔ g cez hmotnosť kusu, inak null. */
    private fun baseIn(amount: Double, unit: String, target: String, grams: Double): Double? {
        val f = family(unit)
        if (f == target) return toBase(amount, unit)
        if (grams <= 0.0) return null
        return when {
            f == "ks" && target == "g" -> amount * grams
            f == "g" && target == "ks" -> toBase(amount, unit) / grams
            else -> null
        }
    }

    /** Zásoba vyjadrená v základnej jednotke potreby (g / ml / ks); null = nedá sa prepočítať (napr. g ↔ ks bez hmotnosti kusu). */
    private fun stockBaseFor(item: PantryItem, needUnit: String): Double? =
        baseIn(item.amount, item.unit, family(needUnit), pieceWeight(item.name, item))

    /**
     * Odškrtnutý nákup do zásob. Množstvo z názvu („Mlieko 2 l“) sa pripočíta (ks ↔ g cez hmotnosť kusu, ak je známa)
     * a zapamätá sa ako obvyklé balenie. Bez množstva v názve („Mlieko“) sa pripočíta obvyklé balenie z minula;
     * ak ho nepoznáme a zásoba nie je v kusoch, zásoby sa nemenia (veľkosť balenia nevieme).
     */
    fun addPurchased(pantry: List<PantryItem>, rawName: String, now: Long, deviceId: String): List<PantryItem> {
        val (parsed, explicit) = parseInternal(rawName)
        val name = parsed.name.ifBlank { rawName.trim() }
        if (name.isEmpty()) return pantry
        val k = key(name)
        val live = pantry.filter { !it.deleted }
        val existing = live.firstOrNull { key(it.name) == k }
            ?: pantry.firstOrNull { it.id == pantryId(name) }
        fun fresh() = PantryItem(
            id = pantryId(name),
            name = name,
            amount = parsed.amount,
            unit = parsed.unit,
            pack = if (explicit) toBase(parsed.amount, parsed.unit) else 0.0,
            updatedAt = now,
            deviceId = deviceId,
        )
        if (existing == null) return pantry + fresh()
        if (existing.deleted) return pantry.map { if (it.id == existing.id) fresh().copy(id = existing.id, always = existing.always, pieceG = existing.pieceG) else it }
        val hf = family(existing.unit)
        val grams = pieceWeight(existing.name, existing)
        val addBase: Double? = when {
            explicit -> baseIn(parsed.amount, parsed.unit, hf, grams)
            existing.pack > 0.0 -> existing.pack
            hf == "ks" -> 1.0
            else -> null
        }
        if (addBase == null) {
            // nezlučiteľné jednotky: nový nákup nahradí zásobu (ako doteraz); bez množstva zásoby nemeníme
            if (!explicit) return pantry
            return pantry.map {
                if (it.id != existing.id) it
                else it.copy(name = name, amount = parsed.amount, unit = parsed.unit, pack = toBase(parsed.amount, parsed.unit), updatedAt = now, deviceId = deviceId)
            }
        }
        val total = pretty(toBase(existing.amount, existing.unit) + addBase, hf)
        return pantry.map {
            if (it.id != existing.id) it
            else it.copy(
                name = name,
                amount = total.amount,
                unit = total.unit,
                pack = if (explicit) addBase else existing.pack,
                deleted = false,
                updatedAt = now,
                deviceId = deviceId,
            )
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
            val k = key(ing.name)
            val hit = next.firstOrNull { !it.deleted && key(it.name) == k } ?: continue
            if (hit.always) continue
            val have = stockBaseFor(hit, ing.unit) ?: continue
            val leftNeed = (have - toBase(ing.amount, ing.unit)).coerceAtLeast(0.0)
            val hf = family(hit.unit)
            val grams = pieceWeight(hit.name, hit)
            val left = when {
                hf == family(ing.unit) -> leftNeed
                hf == "ks" -> leftNeed / grams
                hf == "g" -> leftNeed * grams
                else -> continue
            }
            val pretty = pretty(left, hf)
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
            val k = key(item.name)
            val i = out.indexOfFirst { key(it.name) == k && canMerge(it.unit, item.unit) }
            if (i < 0) out += item
            else {
                val sum = sumQty(out[i].amount, out[i].unit, item.amount, item.unit)
                out[i] = out[i].copy(amount = sum.amount, unit = sum.unit)
            }
        }
        return out
    }

    /** Odtlačok jedla pre sledovanie zmien plánu: recept a počet porcií, z ktorých sa počítal nákup. */
    fun sig(meal: Meal, recipe: Recipe?): String = "${meal.recipeId}|${portionsOf(meal, recipe)}"

    fun neededForRange(
        from: LocalDate,
        to: LocalDate,
        meals: List<Meal>,
        recipes: List<Recipe>,
    ): List<NeededLine> {
        val byId = recipes.filter { !it.deleted }.associateBy { it.id }
        data class Acc(var amount: Double, var unit: String, val sources: MutableList<String>, val name: String, val parts: MutableList<Part>)
        val acc = LinkedHashMap<String, Acc>()
        val mealsIn = meals.filter { !it.deleted && !it.cooked && !it.skipped && it.recipeId.isNotEmpty() }
            .mapNotNull { m ->
                val d = runCatching { LocalDate.parse(m.date) }.getOrNull() ?: return@mapNotNull null
                if (d.isBefore(from) || d.isAfter(to)) return@mapNotNull null
                m to d
            }
            .sortedWith(compareBy({ it.second }, { it.first.slot.order }))
        for ((meal, day) in mealsIn) {
            val recipe = byId[meal.recipeId] ?: continue
            val source = "${dayLabel(day)} · ${meal.title.ifBlank { recipe.name }}"
            val sig = sig(meal, recipe)
            for (ing in scaledIngredients(recipe, portionsOf(meal, recipe))) {
                if (ing.name.isBlank() || isFuzzy(ing.unit)) continue
                val k = key(ing.name) + ":" + family(ing.unit)
                val part = Part(meal.id, sig, toBase(ing.amount, ing.unit))
                val cur = acc[k]
                if (cur == null) {
                    acc[k] = Acc(ing.amount, ing.unit, mutableListOf(source), ing.name, mutableListOf(part))
                } else if (canMerge(cur.unit, ing.unit)) {
                    val sum = sumQty(cur.amount, cur.unit, ing.amount, ing.unit)
                    cur.amount = sum.amount
                    cur.unit = sum.unit
                    if (source !in cur.sources) cur.sources += source
                    cur.parts += part
                }
            }
        }
        return acc.values.map { NeededLine(it.name, it.amount, it.unit, it.sources, it.parts) }
    }

    private data class OnList(val item: ShopItem, val parsed: Parsed, val explicit: Boolean)

    /** 1500 ml → 1,5 l, 1200 g → 1,2 kg (len známe jednotky hmotnosti a objemu). */
    private fun nice(q: Qty): Qty {
        if (!isKnownUnit(q.unit)) return q
        val f = family(q.unit)
        return if (f == "g" || f == "ml") pretty(toBase(q.amount, q.unit), f) else q
    }

    /**
     * Chýbajúce suroviny od [from] do [to] (vrátane).
     * Vždy doma a známe základné suroviny sa vynechajú; názvy sa porovnávajú cez [key] (paradajka = paradajky).
     * Od zásoby sa odráta to, čo je na sklade (zlučiteľné jednotky; ks ↔ g cez hmotnosť kusu, ak je známa).
     * Surovina bez záznamu v zásobách so známou jednotkou ide do [NeedKind.MISSING], s neznámou alebo
     * neprepočítateľnou do [NeedKind.ASK] (Overiť).
     * Položka, ktorá už je v cieľovom zozname (nekúpená): ak má dosť (alebo množstvo nie je napísané) → [NeedKind.ALREADY],
     * ak má v zozname menej, než treba → [NeedKind.TOPUP] (zvýšiť na potrebné množstvo).
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
        val onList = LinkedHashMap<String, OnList>()
        for (it in shopItems.filter { !it.deleted && !it.done && it.list == target }) {
            val (parsed, explicit) = parseInternal(it.name)
            if (parsed.name.isBlank()) continue
            onList.putIfAbsent(key(parsed.name), OnList(it, parsed, explicit))
        }
        val out = ArrayList<ShopNeed>()
        for (line in neededForRange(from, to, meals, recipes)) {
            if (isStaple(line.name)) continue
            val k = key(line.name)
            val item = stock.firstOrNull { key(it.name) == k }
            if (item?.always == true) continue
            var kind: NeedKind
            var qty = nice(Qty(line.amount, line.unit))
            if (item == null) {
                kind = if (isKnownUnit(line.unit)) NeedKind.MISSING else NeedKind.ASK
            } else {
                val have = stockBaseFor(item, line.unit)
                if (have == null) {
                    kind = NeedKind.ASK
                } else {
                    val needBase = toBase(line.amount, line.unit)
                    if (have + 1e-6 >= needBase) continue
                    val missBase = needBase - have
                    val fam = family(line.unit)
                    qty = if (fam == "ks") Qty(if (family(item.unit) != fam) roundKsUp(missBase) else missBase, "ks") else pretty(missBase, fam)
                    if (qty.amount <= 1e-6) continue
                    kind = NeedKind.MISSING
                }
            }
            val listed = onList[k]
            if (listed != null) {
                val comparable = listed.explicit && isKnownUnit(qty.unit) && canMerge(listed.parsed.unit, qty.unit)
                if (comparable && !enough(listed.parsed.amount, listed.parsed.unit, qty.amount, qty.unit)) {
                    out += ShopNeed(
                        listed.parsed.name, qty.amount, qty.unit, NeedKind.TOPUP, line.sources,
                        itemId = listed.item.id,
                        newText = formatShop(listed.parsed.name, qty.amount, qty.unit),
                        note = "Na zozname je ${formatQty(listed.parsed.amount, listed.parsed.unit)}",
                        parts = line.parts,
                    )
                } else {
                    out += ShopNeed(line.name, qty.amount, qty.unit, NeedKind.ALREADY, line.sources, itemId = listed.item.id, parts = line.parts)
                }
                continue
            }
            out += ShopNeed(line.name, qty.amount, qty.unit, kind, line.sources, parts = line.parts)
        }
        return out
    }

    // ── návrhy surovín pri písaní receptu ───────────────────────────────────

    /** Známa surovina: obvyklé písanie názvu a jednotka, v akej sa používa. */
    data class Known(val name: String, val unit: String, val uses: Int)

    /**
     * Suroviny, ktoré už appka pozná: z receptov (najčastejšia jednotka), zásob a histórie nákupov. Zhoda cez [key],
     * takže „paradajky“ a „paradajka“ sú jedna surovina. Najpoužívanejšie hore.
     */
    fun knownIngredients(recipes: List<Recipe>, pantry: List<PantryItem>, shopNames: List<String>): List<Known> {
        class Acc(var name: String, val units: MutableMap<String, Int>, var uses: Int)
        val byKey = LinkedHashMap<String, Acc>()
        fun add(name: String, unit: String, weight: Int) {
            val n = name.trim()
            if (n.isEmpty() || isFuzzy(unit)) return
            val k = key(n)
            if (k.isEmpty()) return
            val u = if (unit.trim().lowercase() in UNIT_ALIASES) canonicalUnit(unit) else "ks"
            val acc = byKey.getOrPut(k) { Acc(n, LinkedHashMap(), 0) }
            acc.units[u] = (acc.units[u] ?: 0) + weight
            acc.uses += weight
        }
        for (r in recipes.filter { !it.deleted }) for (i in r.ingredients) add(i.name, i.unit, 3)
        for (p in pantry.filter { !it.deleted && !it.always }) add(p.name, p.unit, 2)
        for (raw in shopNames) {
            val (parsed, explicit) = parseInternal(raw)
            if (parsed.name.isNotBlank()) add(parsed.name, if (explicit) parsed.unit else "ks", 1)
        }
        return byKey.values
            .map { a -> Known(a.name, a.units.maxByOrNull { it.value }?.key ?: "ks", a.uses) }
            .sortedWith(compareByDescending<Known> { it.uses }.thenBy { fold(it.name) })
    }

    /** Návrhy podľa napísaného začiatku názvu (alebo začiatku niektorého slova), najviac [limit]. */
    fun suggestIngredients(query: String, known: List<Known>, limit: Int = 4): List<Known> {
        val q = fold(query).trim()
        if (q.length < 2) return emptyList()
        val qk = key(query)
        val hits = known.filter { k ->
            val f = fold(k.name)
            f.startsWith(q) || f.split(' ').any { it.startsWith(q) } || (qk.isNotEmpty() && key(k.name).startsWith(qk))
        }
        return hits.sortedWith(compareByDescending<Known> { fold(it.name).startsWith(q) }.thenByDescending { it.uses }).take(limit)
    }

    /** Stačí zásoba [item] na [amount] [unit]? null = nedá sa porovnať (g ↔ ks bez známej hmotnosti kusu). */
    fun covers(item: PantryItem, amount: Double, unit: String): Boolean? {
        val have = stockBaseFor(item, unit) ?: return null
        return have + 1e-6 >= toBase(amount, unit)
    }

    /** Krátky text do oznámenia: „Chýba: múka 500 g, mlieko 1 l (+2)“. */
    fun needsSummary(needs: List<ShopNeed>): String {
        val names = needs.map { it.shopText() }
        return when {
            names.isEmpty() -> ""
            names.size <= 2 -> "Chýba: " + names.joinToString(", ")
            else -> "Chýba: ${names[0]}, ${names[1]} (+${names.size - 2})"
        }
    }

    // ── Uvarené? ────────────────────────────────────────────────────────────

    /** Dokedy sa jedlo v danom čase dňa „stihlo“ uvariť (potom sa pýtame, či sa varilo). */
    fun slotEnd(slot: Slot): java.time.LocalTime = when (slot) {
        Slot.BREAKFAST -> java.time.LocalTime.of(10, 0)
        Slot.LUNCH -> java.time.LocalTime.of(15, 0)
        Slot.DINNER -> java.time.LocalTime.of(21, 0)
    }

    /** Kolko dní spätne sa pýtame „Uvarené?“; staršie jedlá už nikto nepotvrdí. */
    const val ASK_BACK_DAYS = 3L

    /**
     * Jedlá, o ktorých treba zistiť, či sa uvarili: s receptom, neuvarené a neodpovedané, ktorých čas už prešiel
     * (včera a skôr; dnes podľa [slotEnd]), najviac [ASK_BACK_DAYS] dní dozadu. Od najstaršieho.
     */
    fun pendingCooked(meals: List<Meal>, recipes: List<Recipe>, today: LocalDate, now: java.time.LocalTime): List<Meal> {
        val ids = recipes.filter { !it.deleted }.map { it.id }.toSet()
        return meals.filter { !it.deleted && !it.cooked && !it.skipped && it.recipeId.isNotEmpty() && it.recipeId in ids }
            .mapNotNull { m ->
                val d = runCatching { LocalDate.parse(m.date) }.getOrNull() ?: return@mapNotNull null
                val due = d.isBefore(today) || (d == today && !now.isBefore(slotEnd(m.slot)))
                if (!due || d.isBefore(today.minusDays(ASK_BACK_DAYS))) null else m to d
            }
            .sortedWith(compareBy({ it.second }, { it.first.slot.order }))
            .map { it.first }
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
        .put("sk", m.skipped)
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
        .put("pg", p.pieceG)
        .put("pk", p.pack)
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
            skipped = o.optBoolean("sk"),
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
            pieceG = o.optDouble("pg", 0.0),
            pack = o.optDouble("pk", 0.0),
            updatedAt = o.optLong("ts"),
            deleted = o.optBoolean("x"),
            deviceId = o.optString("dv"),
        )
    }
}
