package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject

/**
 * Import receptu z vloženého textu (alebo zo stránky so schémou schema.org/Recipe): názov, počet porcií a suroviny
 * s množstvom. Nič sa neukladá samo – výsledok sa najprv ukáže v náhľade a potom otvorí v editore receptu, kde sa dá
 * všetko opraviť. Čistá logika bez Androidu.
 *
 * Rozpoznáva riadky v tvaroch „500 g múky“, „Múka 500 g“, „Múka – 500 g“, „Múka: 5 dl“, „Múka (500 g)“, odrážky aj emoji na začiatku,
 * číslovanie („1. 2 cibule“), množstvo na samostatnom riadku (pred názvom aj za ním), zoznam v jednom riadku
 * („Suroviny: 500 g múky, 3 vajcia, …“) a nadpisy sekcií („Na cesto“, „Na plnku:“), ktoré preskočí.
 *
 * Jednotky: g, dag, kg, ml, cl, dl, l, ks. Lyžice, strúčiky, hrste, balenia a podobné miery idú ako neurčité množstvo
 * („trocha“): nejdú do nákupu ani sa neodpočítavajú (rovnako ako štipka v recepte).
 */
object RecipeImport {
    /** [skipped] = riadky v časti so surovinami, ktorým appka nerozumela (náhľad ich ukáže, aby sa nič nestratilo potichu). */
    data class Result(
        val title: String,
        val portions: Int,
        val ingredients: List<Meals.Ingredient>,
        val skipped: List<String> = emptyList(),
    )

    private const val NUM = "(?:\\d+\\s+\\d+/\\d+|\\d+/\\d+|\\d+(?:[.,]\\d+)?)"
    private const val RANGE = "$NUM(?:\\s*[-–—]\\s*$NUM)?"
    private val QTY = Regex("^($NUM)(?:\\s*[-–—]\\s*($NUM))?\\s*(.*)$")
    private val QTY_PAREN = Regex("^(.+?)\\s*\\(\\s*(?:(?:cca|ca\\.?|asi|približne|okolo)\\s+)?($RANGE)\\s*([\\p{L}]+)?\\.?\\s*\\)\\s*$", RegexOption.IGNORE_CASE)
    private val QTY_LAST = Regex("^(.+?)(?:\\s*([:=\\-–—])\\s*|\\s+)(?:(?:cca|ca\\.?|asi|približne|okolo)\\s+)?($RANGE)\\s*([\\p{L}]+)?\\.?\\s*$", RegexOption.IGNORE_CASE)
    private val LEAD_MOD = Regex("^(?:cca|ca|asi|približne|okolo|zhruba|aspoň|min)\\.?\\s+", RegexOption.IGNORE_CASE)
    private val TIMES = Regex("^(\\s*)[x×]\\s+", RegexOption.IGNORE_CASE)
    private val LEAD_SYMBOLS = Regex("^[^\\p{L}\\p{N}½¼¾⅓⅔]+")
    private val NUM_PAREN = Regex("^\\d{1,2}\\)\\s+")
    private val NUM_DOT_BEFORE_DIGIT = Regex("^\\d{1,2}\\.\\s+(?=[\\d½¼¾⅓⅔])")
    private val NUM_DOT_BEFORE_LETTER = Regex("^\\d{1,2}\\.\\s+(?=\\p{L})")
    private val PORTIONS_A = Regex("(\\d{1,2})\\s*(?:porci|osob|ludi|serving)")
    private val PORTIONS_B = Regex("(?:pocet porcii|porcie|porcii|serves|servings|yield|vynos)\\D{0,14}(\\d{1,2})")
    private val INLINE_STOP = Regex("\\s(?:postup(?:\\s+prípravy)?|príprava|spôsob prípravy)\\s*:", RegexOption.IGNORE_CASE)
    private val HEADER_WITH_CONTENT = Regex("^(suroviny|ingrediencie|potrebujeme|budete potrebovat|budeme potrebovat|potrebne suroviny|ingredients)\\s*[:：]\\s*(.+)$")
    private val LABEL_NA = Regex("^(?:na|do|pre|ku|k)\\s+\\S+(?:\\s+\\S+)?\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)

    private val HEADERS_IN = setOf("suroviny", "ingrediencie", "potrebujeme", "budete potrebovat", "budeme potrebovat", "potrebne suroviny", "ingredients")
    private val HEADERS_STOP = setOf(
        "postup", "priprava", "postup pripravy", "sposob pripravy", "instructions", "method", "directions", "preparation",
        "poznamka", "poznamky", "tip", "tipy", "nutricne hodnoty", "vyzivove hodnoty",
    )

    /** Úvodné údaje stránky (nie suroviny): „Porcie: 4“, „Čas prípravy: 45 min“, „Kalórie 350 kcal“… */
    private val META_PREFIX = listOf(
        "porcie", "porcii", "pocet porcii", "cas", "priprava:", "varenie", "pecenie", "obtiaznost", "narocnost", "kalorie", "energia",
        "bielkoviny", "sacharidy", "tuky", "vlaknina", "hodnotenie", "autor", "kategoria", "pre ", "servings", "serves", "yield", "vynos",
    )

    /** Ovládacie texty stránok, ktoré sa pri kopírovaní dostanú do textu. */
    private val NOISE = setOf(
        "zdielat", "tlacit", "oblubene", "ulozit", "hodnotenie", "reklama", "newsletter", "viac", "zobrazit viac", "skopirovat",
        "pridat do nakupneho zoznamu", "nakupny zoznam", "pridat do oblubenych", "komentare", "tlac", "share", "print", "save",
    )

    private val NOT_INGREDIENT_UNITS = setOf(
        "min", "minut", "minuty", "minutu", "hod", "hodin", "hodiny", "hodinu", "sek", "sekund", "c", "stupnov", "stupne", "stupen",
        "porcii", "porcie", "porciu", "osob", "osoby", "osobu", "kcal", "kj", "krat", "cm", "mm", "dni", "dni.", "den", "dna",
    )

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

    /** Slovo je známa jednotka (metrická, ks alebo neurčitá miera). [raw] kvôli „čl“ (čajová lyžička), ktoré sa po zjednodušení zhoduje s „cl“. */
    private fun isUnitToken(raw: String): Boolean {
        val f = fold(raw.trimEnd('.', ',', ':'))
        if (raw.trimEnd('.').lowercase() == "čl") return true
        return (f in METRIC && !(f == "cl" && raw.lowercase().startsWith("č"))) || isFuzzyToken(f)
    }

    /** Začiatočné frázy bez čísla („štipka soli“, „podľa chuti soľ“, „na ozdobenie petržlenová vňať“) → neurčité množstvo. */
    private val FUZZY_LEAD_PHRASES = listOf(
        "stipka", "stipku", "trocha", "trochu", "podla chuti", "podla potreby", "na ozdobenie", "na posypanie", "na vyprazanie", "na potretie",
        "na dochutenie", "na podavanie", "na vymastenie", "na pomastenie", "na zdobenie", "na dozdobenie",
    )

    /** Koncové frázy bez čísla („Soľ – štipka“, „Olej podľa potreby“). */
    private val FUZZY_TAIL_PHRASES = listOf(
        "stipka", "stipku", "trocha", "trochu", "podla chuti", "podla potreby", "na ozdobenie", "na posypanie", "na vyprazanie", "na potretie",
        "na dochutenie", "na podavanie", "na vymastenie", "na pomastenie", "podla gusta", "kvapka", "kvapky", "niekolko",
    )

    private fun fold(s: String) = Meals.fold(s)

    private fun parseNum(raw: String): Double {
        val t = raw.trim()
        Regex("^(\\d+)\\s+(\\d+)/(\\d+)$").matchEntire(t)?.let { return it.groupValues[1].toDouble() + it.groupValues[2].toDouble() / it.groupValues[3].toDouble() }
        Regex("^(\\d+)/(\\d+)$").matchEntire(t)?.let { return it.groupValues[1].toDouble() / it.groupValues[2].toDouble() }
        return Meals.parseNumber(t)
    }

    private fun parseRange(raw: String): Double {
        val parts = Regex("^($NUM)(?:\\s*[-–—]\\s*($NUM))?$").matchEntire(raw.trim()) ?: return 0.0
        val a = parseNum(parts.groupValues[1])
        val b = parts.groupValues[2].takeIf { it.isNotEmpty() }?.let { parseNum(it) } ?: 0.0
        return maxOf(a, b)
    }

    private fun normalize(line: String): String =
        line.replace('\u00A0', ' ').replace('\t', ' ').replace("½", "1/2").replace("¼", "1/4").replace("¾", "3/4").replace("⅓", "1/3").replace("⅔", "2/3")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("\\s+"), " ").trim()

    // ── slovenské 2. pád → 1. pád názvu („500 g múky“ → Múka), aby sa surovina zhodla so zásobami a nákupom ────────

    /** 2. pád (alebo iný tvar po čísle) → základný tvar a rod: M, F, N, P = množné číslo. */
    private val NOMINATIVE: Map<String, Pair<String, Char>> by lazy {
        val raw = listOf(
            // ženský rod
            "múky" to ("múka" to 'F'), "soli" to ("soľ" to 'F'), "smotany" to ("smotana" to 'F'), "mrkvy" to ("mrkva" to 'F'),
            "papriky" to ("paprika" to 'F'), "ryže" to ("ryža" to 'F'), "šunky" to ("šunka" to 'F'), "bryndze" to ("bryndza" to 'F'),
            "slaniny" to ("slanina" to 'F'), "strúhanky" to ("strúhanka" to 'F'), "vody" to ("voda" to 'F'), "kapusty" to ("kapusta" to 'F'),
            "repy" to ("repa" to 'F'), "cibule" to ("cibuľa" to 'F'), "tekvice" to ("tekvica" to 'F'), "uhorky" to ("uhorka" to 'F'),
            "šošovice" to ("šošovica" to 'F'), "bazalky" to ("bazalka" to 'F'), "majonézy" to ("majonéza" to 'F'), "horčice" to ("horčica" to 'F'),
            "kávy" to ("káva" to 'F'), "čokolády" to ("čokoláda" to 'F'), "cukety" to ("cuketa" to 'F'), "mäty" to ("mäta" to 'F'),
            "kukurice" to ("kukurica" to 'F'), "fazule" to ("fazuľa" to 'F'), "bylinky" to ("bylinka" to 'F'), "šťavy" to ("šťava" to 'F'),
            "vanilky" to ("vanilka" to 'F'), "škorice" to ("škorica" to 'F'), "brokolice" to ("brokolica" to 'F'), "vňate" to ("vňať" to 'F'),
            "kapary" to ("kapary" to 'P'), "chuti" to ("chuť" to 'F'), "mozzarelly" to ("mozzarella" to 'F'), "slížky" to ("slížka" to 'F'),
            "pizze" to ("pizza" to 'F'), "tortilly" to ("tortilla" to 'F'), "polievky" to ("polievka" to 'F'), "omáčky" to ("omáčka" to 'F'),
            "pomarančovej" to ("pomarančová" to 'F'), "petržlenovej" to ("petržlenová" to 'F'), "zeleniny" to ("zelenina" to 'F'),
            "bravčovej" to ("bravčová" to 'F'), "hovädzej" to ("hovädzia" to 'F'), "klobásy" to ("klobása" to 'F'), "kuracej" to ("kuracia" to 'F'),
            "tresky" to ("treska" to 'F'), "ryby" to ("ryba" to 'F'), "múčky" to ("múčka" to 'F'), "celeru" to ("zeler" to 'M'),
            // mužský rod
            "cukru" to ("cukor" to 'M'), "oleja" to ("olej" to 'M'), "octu" to ("ocot" to 'M'), "syra" to ("syr" to 'M'), "kečupu" to ("kečup" to 'M'),
            "pretlaku" to ("pretlak" to 'M'), "vývaru" to ("vývar" to 'M'), "jogurtu" to ("jogurt" to 'M'), "tvarohu" to ("tvaroh" to 'M'),
            "prášku" to ("prášok" to 'M'), "škrobu" to ("škrob" to 'M'), "medu" to ("med" to 'M'), "rumu" to ("rum" to 'M'), "cesnaku" to ("cesnak" to 'M'),
            "hrášku" to ("hrášok" to 'M'), "špenátu" to ("špenát" to 'M'), "zelera" to ("zeler" to 'M'), "kôpru" to ("kôpor" to 'M'),
            "zázvoru" to ("zázvor" to 'M'), "kvasu" to ("kvas" to 'M'), "kakaového" to ("kakaový" to 'M'), "olivového" to ("olivový" to 'M'),
            "rastlinného" to ("rastlinný" to 'M'), "slnečnicového" to ("slnečnicový" to 'M'), "bieleho" to ("biely" to 'M'), "čierneho" to ("čierny" to 'M'),
            "červeného" to ("červený" to 'M'), "sladkého" to ("sladký" to 'M'), "grilovacieho" to ("grilovacie" to 'N'), "kyslého" to ("kyslý" to 'M'),
            "bazalkového" to ("bazalkový" to 'M'), "citrónovej" to ("citrónová" to 'F'), "vlašského" to ("vlašský" to 'M'),
            "reďkovky" to ("reďkovka" to 'F'), "mixu" to ("mix" to 'M'), "lekváru" to ("lekvár" to 'M'), "džemu" to ("džem" to 'M'), "kuskusu" to ("kuskus" to 'M'),
            "bulguru" to ("bulgur" to 'M'), "chleba" to ("chlieb" to 'M'), "rožku" to ("rožok" to 'M'), "rožkov" to ("rožky" to 'P'), "čaju" to ("čaj" to 'M'),
            // stredný rod
            "droždia" to ("droždie" to 'N'), "mlieka" to ("mlieko" to 'N'), "masla" to ("maslo" to 'N'), "mäsa" to ("mäso" to 'N'), "korenia" to ("korenie" to 'N'),
            "kakaa" to ("kakao" to 'N'), "vína" to ("víno" to 'N'), "piva" to ("pivo" to 'N'), "kuracieho" to ("kuracie" to 'N'), "bravčového" to ("bravčové" to 'N'),
            "hovädzieho" to ("hovädzie" to 'N'), "mletého" to ("mleté" to 'N'), "údeného" to ("údené" to 'N'), "sladkého" to ("sladké" to 'N'),
            "oleja" to ("olej" to 'M'), "rajčiakov" to ("rajčiaky" to 'P'), "zelenej" to ("zelená" to 'F'), "červenej" to ("červená" to 'F'),
            "morskej" to ("morská" to 'F'), "hladkej" to ("hladká" to 'F'), "polohrubej" to ("polohrubá" to 'F'), "hrubej" to ("hrubá" to 'F'),
            "kyslej" to ("kyslá" to 'F'), "sladkej" to ("sladká" to 'F'), "čerstvej" to ("čerstvá" to 'F'), "cukrovej" to ("cukrová" to 'F'),
            // množné číslo (2. pád po čísle a jednotke, napr. „500 g zemiakov“)
            "vajec" to ("vajcia" to 'P'), "zemiakov" to ("zemiaky" to 'P'), "paradajok" to ("paradajky" to 'P'), "cestovín" to ("cestoviny" to 'P'),
            "húb" to ("huby" to 'P'), "jabĺk" to ("jablká" to 'P'), "mrkiev" to ("mrkvy" to 'P'), "rezancov" to ("rezance" to 'P'), "špagiet" to ("špagety" to 'P'),
            "halušiek" to ("halušky" to 'P'), "párkov" to ("párky" to 'P'), "klobások" to ("klobásy" to 'P'), "jahôd" to ("jahody" to 'P'), "malín" to ("maliny" to 'P'),
            "čučoriedok" to ("čučoriedky" to 'P'), "banánov" to ("banány" to 'P'), "citrónov" to ("citróny" to 'P'), "pomarančov" to ("pomaranče" to 'P'),
            "olív" to ("olivy" to 'P'), "orechov" to ("orechy" to 'P'), "mandlí" to ("mandle" to 'P'), "hrozienok" to ("hrozienka" to 'P'), "makaróny" to ("makaróny" to 'P'),
            "fliačikov" to ("fliačiky" to 'P'), "rajčín" to ("rajčiny" to 'P'), "paprík" to ("papriky" to 'P'), "uhoriek" to ("uhorky" to 'P'), "cibúľ" to ("cibule" to 'P'),
            "strukov" to ("struky" to 'P'), "gnocchi" to ("gnocchi" to 'P'), "tortíl" to ("tortilly" to 'P'), "žemlí" to ("žemle" to 'P'), "bagiet" to ("bagety" to 'P'),
            "kúskov" to ("kúsky" to 'P'), "plátkov" to ("plátky" to 'P'), "stehien" to ("stehná" to 'P'), "prsia" to ("prsia" to 'P'), "krídel" to ("krídla" to 'P'),
            "fazúľ" to ("fazule" to 'P'), "šošoviek" to ("šošovice" to 'P'), "datlí" to ("datle" to 'P'), "sliviek" to ("slivky" to 'P'), "hrušiek" to ("hrušky" to 'P'),
            "broskýň" to ("broskyne" to 'P'), "marhúľ" to ("marhule" to 'P'), "čerešní" to ("čerešne" to 'P'), "višní" to ("višne" to 'P'), "ríbezlí" to ("ríbezle" to 'P'),
            "ružičiek" to ("ružičky" to 'P'), "tekvíc" to ("tekvice" to 'P'),
        )
        raw.associate { (gen, nom) -> fold(gen) to nom }
    }

    /** Prídavné meno v 2. páde → 1. pád podľa rodu podstatného mena, ktoré nasleduje („hladkej“ + F → „hladká“). */
    private fun adjective(word: String, gender: Char): String {
        val w = word.lowercase()
        return when (gender) {
            'F' -> if (w.endsWith("ej") && w.length > 4) w.dropLast(2) + "á" else w
            'M' -> if (w.endsWith("ého") && w.length > 5) w.dropLast(3) + "ý" else w
            'N' -> when {
                w.endsWith("ieho") && w.length > 6 -> w.dropLast(4) + "ie"
                w.endsWith("ého") && w.length > 5 -> w.dropLast(3) + "é"
                else -> w
            }
            'P' -> if (w.endsWith("ých") && w.length > 5) w.dropLast(3) + "é" else if (w.endsWith("ích") && w.length > 5) w.dropLast(3) + "ie" else w
            else -> w
        }
    }

    /** „hladkej múky“ → „hladká múka“; ak posledné slovo nepoznáme, nechá sa názov ako je. */
    private fun nominative(name: String): String {
        val words = name.trim().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return name
        val last = words.last()
        val hit = NOMINATIVE[fold(last)] ?: return name
        // iba skutočné podstatné mená ako posledné slovo (tvary prídavných mien v tabuľke slúžia na samostatné použitie)
        val noun = hit.first
        val adjs = words.dropLast(1).map { adjective(it, hit.second) }
        val tail = if (last.first().isUpperCase()) noun.replaceFirstChar { it.uppercase() } else noun
        return (adjs + tail).joinToString(" ")
    }

    private fun cleanName(raw: String, known: List<Meals.Known>): String {
        var n = raw.trim().replace(Regex("^[^\\p{L}\\p{N}]+"), "")
        n = n.replace(Regex("\\([^)]*\\)"), " ").substringBefore(',').substringBefore(';').trim()
        n = n.replace(Regex("^(?:z|zo|of)\\s+", RegexOption.IGNORE_CASE), "").trim().trimEnd('.', ':', '-', '–', '—')
        n = n.replace(Regex("\\s+"), " ")
        if (n.isEmpty() || n.none { it.isLetter() }) return ""
        n = nominative(n)
        n = n.replaceFirstChar { it.uppercase() }
        val k = Meals.key(n)
        return known.firstOrNull { Meals.key(it.name) == k }?.name ?: n
    }

    private fun build(amount: Double, unitRaw: String, nameRaw: String, known: List<Meals.Known>): Meals.Ingredient? {
        if (amount <= 0.0) return null
        val firstFold = fold(unitRaw.trimEnd('.', ',', ':'))
        val metric = METRIC[firstFold]?.takeUnless { firstFold == "cl" && unitRaw.lowercase().startsWith("č") }
        val fuzzy = unitRaw.trimEnd('.').lowercase() == "čl" || isFuzzyToken(firstFold)
        val name = cleanName(nameRaw, known)
        if (name.isEmpty()) return null
        return when {
            fuzzy -> Meals.Ingredient(name, 0.0, "trocha")
            metric != null -> Meals.Ingredient(name, amount * metric.second, metric.first)
            else -> null
        }
    }

    /** Riadok začína množstvom: „500 g múky“, „2 cibule“, „2x vajce“, „cca 3 dl mlieka“. */
    private fun qtyFirst(t0: String, known: List<Meals.Known>): Meals.Ingredient? {
        val t = t0.replace(LEAD_MOD, "")
        val m = QTY.matchEntire(t) ?: return null
        val amount = maxOf(parseNum(m.groupValues[1]), m.groupValues[2].takeIf { it.isNotEmpty() }?.let { parseNum(it) } ?: 0.0)
        var rest = m.groupValues[3].trim()
        if (amount <= 0.0 || rest.isEmpty()) return null
        rest = rest.replace(TIMES, "").trim().replace(Regex("^(?:cca|ca|asi)\\.?\\s+", RegexOption.IGNORE_CASE), "")
        if (rest.isEmpty()) return null
        val firstRaw = rest.substringBefore(' ').trimEnd('.', ',', ':')
        val firstFold = fold(firstRaw)
        if (firstFold in NOT_INGREDIENT_UNITS || firstRaw.startsWith("°") || firstRaw.startsWith("%")) return null
        val after = rest.substringAfter(' ', "").trim()
        return when {
            isUnitToken(firstRaw) -> if (after.isEmpty()) null else build(amount, firstRaw, after, known)
            else -> {
                val name = cleanName(rest, known)
                if (name.isEmpty()) null else Meals.Ingredient(name, amount, "ks")
            }
        }
    }

    /** Množstvo je za názvom: „Zemiaky 500 g“, „Múka – 250 g“, „Mlieko: 5 dl“, „Vajcia (3 ks)“, „Vajcia: 3“. */
    private fun qtyLast(t: String, known: List<Meals.Known>, lenient: Boolean): Meals.Ingredient? {
        QTY_PAREN.matchEntire(t)?.let { m ->
            val unit = m.groupValues[3]
            if (unit.isNotEmpty() && !isUnitToken(unit)) return@let
            val amount = parseRange(m.groupValues[2])
            val name = m.groupValues[1]
            if (name.length <= 50 && name.split(' ').size <= 5) {
                if (unit.isEmpty()) {
                    cleanName(name, known).takeIf { it.isNotEmpty() && amount > 0.0 }?.let { return Meals.Ingredient(it, amount, "ks") }
                } else {
                    build(amount, unit, name, known)?.let { return it }
                }
            }
        }
        val m = QTY_LAST.matchEntire(t) ?: return null
        val name = m.groupValues[1].trim().trimEnd(':', '-', '–', '—', '=').trim()
        val sep = m.groupValues[2]
        val unit = m.groupValues[4]
        val amount = parseRange(m.groupValues[3])
        if (name.isEmpty() || name.length > 50 || name.split(' ').size > 5 || amount <= 0.0) return null
        if (name.none { it.isLetter() } || name.last().isDigit()) return null
        if (fold(name.substringBefore(' ')) in META_WORDS) return null
        return if (unit.isNotEmpty()) {
            if (!isUnitToken(unit)) null else build(amount, unit, name, known)
        } else if (sep.isNotEmpty() || lenient) {
            // „Vajcia: 3“ alebo „Vajcia 3“ v časti surovín
            cleanName(name, known).takeIf { it.isNotEmpty() }?.let { Meals.Ingredient(it, amount, "ks") }
        } else {
            null
        }
    }

    private val META_WORDS = setOf("porcie", "porcii", "cas", "priprava", "varenie", "pecenie", "obtiaznost", "kalorie", "energia", "hodnotenie", "krok", "kroky", "strana", "recept")

    /** Zloží zoznam z textu bez čísla: „soľ, korenie“, „soľ a korenie“ → viac surovín; jedno slovné spojenie → jedna. */
    private fun bareNames(t: String, known: List<Meals.Known>): List<Meals.Ingredient> {
        var s = t.replace(Regex("(?i)\\bpodľa (?:chuti|potreby|gusta)\\b"), "").trim().trimEnd(',', ';', '.', ':', '-', '–', '—').trim()
        if (s.isEmpty()) return emptyList()
        val parts = s.split(Regex("\\s*[,;/]\\s*|\\s+(?:a|i|alebo)\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = if (parts.size in 2..4 && parts.all { it.split(' ').size <= 3 }) parts else listOf(s)
        return chunks.mapNotNull { c ->
            if (c.split(' ').size > 4 || c.length > 50) return@mapNotNull null
            val name = cleanName(c, known)
            if (name.isEmpty()) null else Meals.Ingredient(name, 0.0, "podľa chuti")
        }
    }

    private fun stripFuzzyLead(t: String): Pair<String, Boolean> {
        val words = t.split(' ').filter { it.isNotEmpty() }
        for (p in FUZZY_LEAD_PHRASES) {
            val n = p.count { it == ' ' } + 1
            if (words.size >= n && fold(words.take(n).joinToString(" ")) == p) return words.drop(n).joinToString(" ") to true
        }
        return t to false
    }

    private fun stripFuzzyTail(t: String): Pair<String, Boolean> {
        val words = t.split(' ').filter { it.isNotEmpty() }
        for (p in FUZZY_TAIL_PHRASES) {
            val n = p.count { it == ' ' } + 1
            if (words.size > n && fold(words.takeLast(n).joinToString(" ")) == p) {
                return words.dropLast(n).joinToString(" ").trimEnd(':', '-', '–', '—', ',', ' ') to true
            }
        }
        return t to false
    }

    /** Čistí riadok od odrážok, emoji a číslovania. */
    private fun clean(line: String, lenient: Boolean): String {
        var t = normalize(line)
        t = t.replace(LEAD_SYMBOLS, "").trim()
        t = t.replace(NUM_PAREN, "")
        t = t.replace(NUM_DOT_BEFORE_DIGIT, "")
        if (lenient) t = t.replace(NUM_DOT_BEFORE_LETTER, "")
        return t.trim()
    }

    /** Nadpis sekcie (nie surovina): „Na cesto“, „Na plnku:“, „Cesto:“, „CESTO“. */
    private fun isSectionHeader(t: String): Boolean {
        if (t.isEmpty() || t.any { it.isDigit() }) return false
        val words = t.trimEnd(':').split(' ').filter { it.isNotEmpty() }
        if (t.endsWith(":") && words.size <= 4) return true
        val f = fold(t)
        val lead = f.substringBefore(' ')
        if (lead in setOf("na", "do", "pre", "ku") && words.size in 2..3 && FUZZY_LEAD_PHRASES.none { f.startsWith(it) }) return true
        if (t.length in 3..24 && t.any { it.isLetter() } && t == t.uppercase() && words.size <= 3) return true
        return false
    }

    /** Riadky bez množstva sa zoberú ako názov suroviny, len ak vyzerajú ako názov (nie veta). */
    private fun looksLikeName(t: String): Boolean =
        t.isNotEmpty() && t.length <= 50 && !t.endsWith(".") && !t.endsWith("!") && !t.endsWith("?") && t.split(' ').size <= 4 && t.any { it.isLetter() }

    /** Jeden riadok → zoznam surovín (zvyčajne jedna; „soľ, korenie“ dá dve). Prázdny = nepoznané. */
    fun parseAll(line: String, known: List<Meals.Known> = emptyList(), lenient: Boolean = false): List<Meals.Ingredient> {
        var t = clean(line, lenient)
        if (t.isEmpty() || t.length > 200) return emptyList()
        // poznámka na konci („Zemiaky 500 g (varené)“, „Zemiaky 500 g, varené“) sa pri hľadaní množstva neráta
        t = t.replace(Regex("\\s*\\((?![^)]*\\d)[^)]*\\)\\s*$"), "").trim()
        if (t.contains(", ")) {
            val head = t.substringBefore(", ").trim()
            if (head.any { it.isDigit() } && head.length >= 3 && (qtyFirst(head, known) != null || qtyLast(head, known, lenient) != null)) t = head
        }
        // „Na ozdobenie: petržlenová vňať“ → obsah za dvojbodkou
        var forced = false
        LABEL_NA.matchEntire(t)?.let { t = it.groupValues[1].trim(); forced = true }
        qtyFirst(t, known)?.let { return listOf(it) }
        qtyLast(t, known, lenient)?.let { return listOf(it) }
        if (QTY.matchEntire(t) != null && !forced) {
            // začína číslom, ale nevyšlo to (čas, teplota, veta) – nie je to surovina
            return emptyList()
        }
        val (tailless, tailFuzzy) = stripFuzzyTail(t)
        val (body, leadFuzzy) = stripFuzzyLead(tailless)
        val fuzzy = tailFuzzy || leadFuzzy
        if (!lenient && !fuzzy && !forced) return emptyList()
        if (body.isEmpty()) return emptyList()
        if (!looksLikeName(body) && !(fuzzy && body.length <= 50 && body.split(' ').size <= 5)) return emptyList()
        return bareNames(body, known)
    }

    /** Jeden riadok so surovinou; null, ak nevyzerá ako surovina. [lenient] = povolí aj riadok bez čísla („Soľ“). */
    fun parseLine(line: String, known: List<Meals.Known> = emptyList(), lenient: Boolean = false): Meals.Ingredient? =
        parseAll(line, known, lenient).firstOrNull()

    // ── celý text ─────────────────────────────────────────────────────────────────────────────────────────

    private fun isPureQty(raw: String): Boolean {
        val t = clean(raw, true)
        if (t.isEmpty()) return false
        val f = fold(t)
        if (f in setOf("stipka", "trocha", "stipku", "trochu")) return true
        val m = Regex("^(?:(?:cca|ca\\.?|asi)\\s+)?($RANGE)\\s*([\\p{L}]+)?\\.?$", RegexOption.IGNORE_CASE).matchEntire(t) ?: return false
        val unit = m.groupValues[2]
        return unit.isNotEmpty() && isUnitToken(unit)
    }

    private fun isNameOnly(raw: String): Boolean {
        val t = clean(raw, true)
        if (t.isEmpty() || t.any { it.isDigit() } || isSectionHeader(t)) return false
        val f = fold(t).trimEnd(':', ' ')
        if (f in HEADERS_IN || f in HEADERS_STOP || f in NOISE) return false
        return looksLikeName(t)
    }

    /** Spojí „názov / množstvo“ na dvoch riadkoch do jedného („Múka“ + „500 g“ → „500 g Múka“); smer sa určí podľa toho, čo dá viac dvojíc. */
    private fun pairLines(lines: List<String>, from: Int, to: Int): List<String> {
        val q = (from until to).filter { isPureQty(lines[it]) }
        if (q.size < 2) return lines
        fun pairs(nameFirst: Boolean): List<Pair<Int, Int>> {
            val used = HashSet<Int>()
            val out = ArrayList<Pair<Int, Int>>()
            for (i in q) {
                if (i in used) continue
                val j = if (nameFirst) prevNonEmpty(lines, i, from) else nextNonEmpty(lines, i, to)
                if (j < 0 || j in used || !isNameOnly(lines[j])) continue
                used += i; used += j
                out += i to j
            }
            return out
        }
        val nameFirst = pairs(true)
        val qtyFirst = pairs(false)
        val chosen = if (nameFirst.size >= qtyFirst.size) nameFirst else qtyFirst
        if (chosen.size < 2) return lines
        val res = lines.toMutableList()
        for ((qi, ni) in chosen) {
            res[ni] = clean(lines[qi], true) + " " + clean(lines[ni], true)
            res[qi] = ""
        }
        return res
    }

    private fun prevNonEmpty(lines: List<String>, i: Int, from: Int): Int {
        var k = i - 1
        while (k >= from && lines[k].isBlank()) k--
        return if (k >= from) k else -1
    }

    private fun nextNonEmpty(lines: List<String>, i: Int, to: Int): Int {
        var k = i + 1
        while (k < to && lines[k].isBlank()) k++
        return if (k < to) k else -1
    }

    /** „Suroviny: a, b, c Postup: …“ v jednom riadku → nadpis a jednotlivé položky; odrážky uprostred riadka sa tiež rozdelia. */
    private fun expand(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        for (raw in lines) {
            var line = raw
            val h = HEADER_WITH_CONTENT.matchEntire(fold(line))
            if (h != null) {
                val idx = line.indexOfFirst { it == ':' || it == '：' }
                var content = line.substring(idx + 1).trim()
                INLINE_STOP.find(content)?.let { content = content.substring(0, it.range.first) }
                out += "Suroviny"
                out += content.split(Regex(";|,(?=\\s)|\\s[•·▪●◦■□☐✓✔]\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
                continue
            }
            val bullets = Regex("\\s[•·▪●◦■□☐✓✔]\\s*")
            if (bullets.findAll(line).count() >= 2) {
                out += line.split(bullets).map { it.trim() }.filter { it.isNotEmpty() }
                continue
            }
            out += line
        }
        return out
    }

    private fun isMeta(line: String): Boolean {
        val f = fold(line).trimEnd(':', ' ')
        if (f.isEmpty()) return false
        if (META_PREFIX.any { p -> f.startsWith(p) && (p.endsWith(":") || p.endsWith(" ") || f.length == p.length || f.getOrNull(p.length) in setOf(':', ' ')) }) {
            // „pre 4 osoby“, „Porcie: 4“, „Čas prípravy: 45 min“, ale nie „Pre cesto“ (to rieši nadpis sekcie)
            return f.any { it.isDigit() } || f.length <= 14
        }
        return false
    }

    private fun isBreadcrumb(line: String): Boolean = line.contains(" / ") || line.contains(" > ") || line.contains('›') || line.contains('»') || line.contains(" | ")

    /** Text receptu: názov, počet porcií (4, ak nie je uvedený) a suroviny (zastaví sa pri sekcii Postup). */
    fun parse(text: String, known: List<Meals.Known> = emptyList()): Result {
        val first = expand(text.replace("\r", "").lines().map { normalize(it) })
        // hranice časti so surovinami pre spájanie „názov / množstvo“: od nadpisu Suroviny po Postup
        val headerIdx = first.indexOfFirst { fold(it).trimEnd(':', ' ') in HEADERS_IN }
        val stopIdx = first.indexOfFirst { fold(it).trimEnd(':', ' ') in HEADERS_STOP }.let { s -> if (s < 0 || s <= headerIdx) first.size else s }
        val lines = pairLines(first, if (headerIdx >= 0) headerIdx + 1 else 0, stopIdx)
        var title = ""
        var portions = 0
        val out = ArrayList<Meals.Ingredient>()
        val skipped = ArrayList<String>()
        var inIngredients = false
        var sawHeader = false
        var seen = 0
        for (rawLine in lines) {
            val line = clean(rawLine, inIngredients)
            if (line.isEmpty()) continue
            seen++
            val f = fold(line).trimEnd(':', ' ')
            if (f in HEADERS_IN) { inIngredients = true; sawHeader = true; continue }
            if (f in HEADERS_STOP && (sawHeader || out.isNotEmpty())) break
            if (f in NOISE) continue
            if (portions == 0) {
                val fl = fold(line)
                (PORTIONS_A.find(fl) ?: PORTIONS_B.find(fl))?.let { portions = it.groupValues[1].toInt().coerceIn(1, 24) }
            }
            if (isMeta(line)) continue
            if (isSectionHeader(line) && !line.any { it.isDigit() }) {
                // „Suroviny na cesto“ a podobné nadpisy začínajú časť surovín
                if (fold(line).trimEnd(':', ' ').startsWith("suroviny")) { inIngredients = true; sawHeader = true }
                continue
            }
            // po dvoch rozpoznaných surovinách sa krátke riadky bez čísla („soľ, korenie“) berú ako ďalšie suroviny aj bez nadpisu
            val ings = parseAll(line, known, lenient = inIngredients || out.size >= 2)
            if (ings.isNotEmpty()) { out += ings; continue }
            if (title.isEmpty() && out.isEmpty() && !inIngredients && seen <= 8 && !isBreadcrumb(line) &&
                line.length in 2..80 && PORTIONS_A.find(fold(line)) == null && line.none { it.isDigit() }
            ) {
                title = line.trim().trimEnd { !it.isLetterOrDigit() && it != ')' }.replaceFirstChar { it.uppercase() }
                continue
            }
            if ((inIngredients || out.isNotEmpty()) && line.length <= 90) skipped += line
        }
        return Result(title, if (portions > 0) portions else 4, out, skipped.take(12))
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
            val list = ArrayList<Meals.Ingredient>()
            val skipped = ArrayList<String>()
            for (i in 0 until ings.length()) {
                val s = ings.optString(i)
                val parsed = parseAll(s, known, lenient = true)
                if (parsed.isEmpty()) { if (s.isNotBlank()) skipped += normalize(s) } else list += parsed
            }
            if (list.isEmpty()) continue
            val yieldRaw = r.opt("recipeYield")?.let { if (it is JSONArray) it.optString(0) else it.toString() }.orEmpty()
            val portions = Regex("\\d{1,2}").find(yieldRaw)?.value?.toIntOrNull()?.coerceIn(1, 24) ?: 4
            val title = normalize(r.optString("name")).replaceFirstChar { it.uppercase() }
            return Result(title, portions, list, skipped.take(12))
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
