package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject

/**
 * Import receptu z vloženého textu (alebo zo stránky so schémou schema.org/Recipe): názov, počet porcií a suroviny
 * s množstvom. Nič sa neukladá samo – výsledok sa otvorí v editore receptu, kde sa dá opraviť. Čistá logika bez Androidu.
 *
 * Jednotky: g, dag, kg, ml, cl, dl, l, ks. Lyžice, strúčiky, hrste, balenia a podobné miery idú ako neurčité množstvo
 * („trocha“): nejdú do nákupu ani sa neodpočítavajú (rovnako ako štipka v recepte).
 */
object RecipeImport {
    data class Result(val title: String, val portions: Int, val ingredients: List<Meals.Ingredient>)

    private const val NUM = "(?:\\d+\\s+\\d+/\\d+|\\d+/\\d+|\\d+(?:[.,]\\d+)?)"
    private val QTY = Regex("^($NUM)(?:\\s*[-–—]\\s*($NUM))?\\s*(.*)$")
    private val BULLET = Regex("^[\\s\\-–—•*·▪●◦■□☐✓✔]+")
    private val NUM_PAREN = Regex("^\\d+\\)\\s+")
    private val PORTIONS_A = Regex("(\\d{1,2})\\s*(?:porci|osob|ludi|serving)")
    private val PORTIONS_B = Regex("(?:pocet porcii|porcie|porcii|serves|servings|yield|vynos)\\D{0,14}(\\d{1,2})")

    private val HEADERS_IN = setOf("suroviny", "ingrediencie", "potrebujeme", "budete potrebovat", "budeme potrebovat", "potrebne suroviny", "ingredients")
    private val HEADERS_STOP = setOf("postup", "priprava", "postup pripravy", "sposob pripravy", "instructions", "method", "directions", "preparation")

    private val METRIC = mapOf(
        "g" to ("g" to 1.0), "gr" to ("g" to 1.0), "gram" to ("g" to 1.0), "gramy" to ("g" to 1.0), "gramov" to ("g" to 1.0), "gramu" to ("g" to 1.0),
        "dag" to ("g" to 10.0), "dkg" to ("g" to 10.0),
        "kg" to ("kg" to 1.0), "kilo" to ("kg" to 1.0), "kila" to ("kg" to 1.0), "kilogram" to ("kg" to 1.0), "kilogramov" to ("kg" to 1.0),
        "ml" to ("ml" to 1.0), "cl" to ("ml" to 10.0), "dcl" to ("ml" to 100.0), "dl" to ("ml" to 100.0),
        "l" to ("l" to 1.0), "lt" to ("l" to 1.0), "liter" to ("l" to 1.0), "litre" to ("l" to 1.0), "litra" to ("l" to 1.0), "litrov" to ("l" to 1.0),
        "ks" to ("ks" to 1.0), "kus" to ("ks" to 1.0), "kusy" to ("ks" to 1.0), "kusov" to ("ks" to 1.0), "kusa" to ("ks" to 1.0),
    )

    private val FUZZY_TOKENS = setOf("pl", "kl", "bal", "kocka", "kocky", "kocku", "trocha", "trochu")

    /** Začiatky slov (po skloňovaní): lyžica/lyžice/lyžíc, strúčik, hrsť, štipka, balenie, plechovka, konzerva, pohár, hrnček, šálka, lupienok, kúsok. */
    private val FUZZY_STEMS = listOf(
        "lyzic", "strucik", "struck", "hrst", "stipk", "balen", "plechovk", "konzerv", "pohar", "hrncek", "salk", "lupienok", "kusok", "kuskov",
    )

    private fun isFuzzyToken(f: String) = f in FUZZY_TOKENS || FUZZY_STEMS.any { f.startsWith(it) }

    private val FUZZY_LEAD = setOf("stipka", "stipku", "trocha", "trochu", "podla", "na")

    private fun fold(s: String) = Meals.fold(s)

    private fun parseNum(raw: String): Double {
        val t = raw.trim()
        Regex("^(\\d+)\\s+(\\d+)/(\\d+)$").matchEntire(t)?.let { return it.groupValues[1].toDouble() + it.groupValues[2].toDouble() / it.groupValues[3].toDouble() }
        Regex("^(\\d+)/(\\d+)$").matchEntire(t)?.let { return it.groupValues[1].toDouble() / it.groupValues[2].toDouble() }
        return Meals.parseNumber(t)
    }

    private fun normalize(line: String): String =
        line.replace('\u00A0', ' ').replace("½", "1/2").replace("¼", "1/4").replace("¾", "3/4").replace("⅓", "1/3").replace("⅔", "2/3")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("\\s+"), " ").trim()

    private fun cleanName(raw: String, known: List<Meals.Known>): String {
        var n = raw.trim()
        n = n.replace(Regex("\\([^)]*\\)"), " ").substringBefore(',').substringBefore(';').trim()
        n = n.replace(Regex("^(?:z|zo|of)\\s+", RegexOption.IGNORE_CASE), "").trim().trimEnd('.', ':', '-')
        n = n.replace(Regex("\\s+"), " ")
        if (n.isEmpty()) return ""
        n = n.replaceFirstChar { it.uppercase() }
        val k = Meals.key(n)
        return known.firstOrNull { Meals.key(it.name) == k }?.name ?: n
    }

    /** Jeden riadok so surovinou; null, ak nevyzerá ako surovina. [lenient] = povolí aj riadok bez čísla („Soľ“). */
    fun parseLine(line: String, known: List<Meals.Known> = emptyList(), lenient: Boolean = false): Meals.Ingredient? {
        var t = normalize(line)
        t = t.replace(BULLET, "").replace(NUM_PAREN, "").trim()
        if (t.isEmpty()) return null
        val m = QTY.matchEntire(t)
        if (m == null) {
            val firstFold = fold(t.substringBefore(' '))
            val fuzzyLead = firstFold in FUZZY_LEAD && (firstFold != "na" || t.length < 40)
            if (!lenient && !fuzzyLead) return null
            if (t.length > 60 || t.trimEnd().endsWith(".")) return null
            var rest = t
            if (firstFold == "stipka" || firstFold == "stipku" || firstFold == "trocha" || firstFold == "trochu") rest = t.substringAfter(' ', "")
            val name = cleanName(rest.replace(Regex("(?i)\\bpodľa chuti\\b"), ""), known)
            if (name.isEmpty() || name.split(' ').size > 4) return null
            return Meals.Ingredient(name, 0.0, "podľa chuti")
        }
        val a = parseNum(m.groupValues[1])
        val b = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { parseNum(it) } ?: 0.0
        val amount = maxOf(a, b)
        var rest = m.groupValues[3].trim()
        if (amount <= 0.0 || rest.isEmpty()) return null
        val firstRaw = rest.substringBefore(' ').trimEnd('.', ',', ':')
        val firstFold = fold(firstRaw)
        val after = rest.substringAfter(' ', "").trim()
        val metric = METRIC[firstFold]?.takeUnless { firstFold == "cl" && firstRaw.lowercase().startsWith("č") }
        return when {
            firstRaw.lowercase() == "čl" || isFuzzyToken(firstFold) -> {
                val name = cleanName(after, known)
                if (name.isEmpty()) null else Meals.Ingredient(name, 0.0, "trocha")
            }
            metric != null && after.isNotEmpty() -> {
                val name = cleanName(after, known)
                if (name.isEmpty()) null else Meals.Ingredient(name, amount * metric.second, metric.first)
            }
            metric != null -> null
            else -> {
                val name = cleanName(rest, known)
                if (name.isEmpty()) null else Meals.Ingredient(name, amount, "ks")
            }
        }
    }

    /** Text receptu: názov, počet porcií (4, ak nie je uvedený) a suroviny (zastaví sa pri sekcii Postup). */
    fun parse(text: String, known: List<Meals.Known> = emptyList()): Result {
        val lines = text.lines().map { normalize(it) }
        var title = ""
        var portions = 0
        val out = ArrayList<Meals.Ingredient>()
        var inIngredients = false
        var sawHeader = false
        for ((i, line) in lines.withIndex()) {
            if (line.isEmpty()) continue
            val f = fold(line).trimEnd(':', ' ')
            if (f in HEADERS_IN) { inIngredients = true; sawHeader = true; continue }
            if (f in HEADERS_STOP && (sawHeader || out.isNotEmpty())) break
            if (portions == 0) {
                val fl = fold(line)
                (PORTIONS_A.find(fl) ?: PORTIONS_B.find(fl))?.let { portions = it.groupValues[1].toInt().coerceIn(1, 24) }
            }
            val ing = parseLine(line, known, lenient = inIngredients)
            if (ing != null) { out += ing; continue }
            if (line.endsWith(":") && line.none { it.isDigit() }) continue
            if (title.isEmpty() && out.isEmpty() && !inIngredients && i < 5 && line.length in 2..80 && PORTIONS_A.find(fold(line)) == null) {
                title = line.trimEnd('.', ':').replaceFirstChar { it.uppercase() }
            }
        }
        return Result(title, if (portions > 0) portions else 4, out)
    }

    /** Ak je celý text jedna adresa (http/https), vráti ju. */
    fun asUrl(text: String): String? {
        val t = text.trim()
        if (t.contains('\n') || t.contains(' ')) return null
        return if (t.startsWith("http://") || t.startsWith("https://")) t else null
    }

    /** Recept zo stránky: JSON-LD blok schema.org/Recipe (názov, porcie, suroviny); null, ak ho stránka nemá. */
    fun fromHtml(html: String, known: List<Meals.Known> = emptyList()): Result? {
        val blocks = Regex("<script[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html).map { it.groupValues[1].trim() }.toList()
        for (raw in blocks) {
            val root: Any = try {
                if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw)
            } catch (_: Exception) {
                continue
            }
            val r = findRecipe(root) ?: continue
            val ings = r.optJSONArray("recipeIngredient") ?: r.optJSONArray("ingredients") ?: continue
            val list = (0 until ings.length()).mapNotNull { parseLine(ings.optString(it), known, lenient = true) }
            if (list.isEmpty()) continue
            val yieldRaw = r.opt("recipeYield")?.let { if (it is JSONArray) it.optString(0) else it.toString() }.orEmpty()
            val portions = Regex("\\d{1,2}").find(yieldRaw)?.value?.toIntOrNull()?.coerceIn(1, 24) ?: 4
            val title = normalize(r.optString("name")).replaceFirstChar { it.uppercase() }
            return Result(title, portions, list)
        }
        return null
    }

    private fun isRecipe(o: JSONObject): Boolean {
        val t = o.opt("@type")
        return when (t) {
            is String -> t.equals("Recipe", ignoreCase = true)
            is JSONArray -> (0 until t.length()).any { t.optString(it).equals("Recipe", ignoreCase = true) }
            else -> false
        }
    }

    private fun findRecipe(node: Any?): JSONObject? {
        when (node) {
            is JSONObject -> {
                if (isRecipe(node)) return node
                node.optJSONArray("@graph")?.let { g -> findRecipe(g)?.let { return it } }
            }
            is JSONArray -> for (i in 0 until node.length()) findRecipe(node.opt(i))?.let { return it }
        }
        return null
    }
}
