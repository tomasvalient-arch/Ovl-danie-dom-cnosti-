package sk.domov.ovladanie

import kotlin.math.roundToInt

/**
 * Teplovzdušná fritéza (napr. BlitzHome BH-AF2). Čísla hodnôt vychádzajú zo štandardnej Tuya sady pre fritézy:
 * 1 hlavný vypínač, 2 štart/stop, 3 režim (text, napr. „Chips“), 5 stav (text, napr. „standby“),
 * 6 teplota v °C, 9 nastavený čas v minútach, 10 zostávajúci čas v minútach.
 */
data class FryerSpec(
    val power: String?,
    val start: String,
    val mode: String,
    val status: String,
    val temp: String,
    val time: String,
    val remaining: String?,
) {
    val handled: Set<String> get() = setOfNotNull(power, start, mode, status, temp, time, remaining)
}

data class FryerState(
    val running: Boolean,
    val mode: String,
    val statusRaw: String,
    val tempC: Int,
    val minutes: Int,
    val remainingMin: Int?,
)

object Fryer {
    const val TEMP_MIN = 80
    const val TEMP_MAX = 200
    const val TEMP_STEP = 5
    const val TIME_MIN = 1
    const val TIME_MAX = 60

    fun detect(values: Map<String, Any>): FryerSpec? {
        if (values["2"] !is Boolean) return null
        if (values["3"] !is String || values["5"] !is String) return null
        if (values["6"] !is Number || values["9"] !is Number) return null
        return FryerSpec(
            power = if (values["1"] is Boolean) "1" else null,
            start = "2",
            mode = "3",
            status = "5",
            temp = "6",
            time = "9",
            remaining = if (values["10"] is Number) "10" else null,
        )
    }

    fun read(spec: FryerSpec, values: Map<String, Any>): FryerState = FryerState(
        running = values[spec.start] == true,
        mode = values[spec.mode] as? String ?: "",
        statusRaw = values[spec.status] as? String ?: "",
        tempC = (values[spec.temp] as? Number)?.toInt() ?: TEMP_MAX,
        minutes = (values[spec.time] as? Number)?.toInt() ?: 0,
        remainingMin = spec.remaining?.let { (values[it] as? Number)?.toInt() },
    )

    /** Stav po slovensky; neznáme hodnoty ukáže tak, ako ich zariadenie poslalo. */
    fun statusLabel(raw: String): String = when (raw.trim().lowercase().replace('_', ' ')) {
        "standby", "wait", "wait mode", "set mode" -> "Pohotovosť"
        "cooking", "run", "run mode", "running", "work", "working" -> "Varí"
        "pause", "pause mode", "paused" -> "Pozastavené"
        "stop", "stop mode" -> "Zastavené"
        "finish", "finished", "complete", "completed", "time out" -> "Hotovo"
        "" -> "–"
        else -> raw
    }

    /** Krátky popis pre dlaždicu v zozname. */
    fun summary(spec: FryerSpec, values: Map<String, Any>): String {
        val on = spec.power?.let { values[it] == true } ?: true
        if (!on) return "Vypnuté"
        val st = read(spec, values)
        return if (st.running) {
            val left = st.remainingMin
            if (left != null && left > 0) "Varí · zostáva $left min" else "Varí"
        } else {
            "Zapnuté · ${statusLabel(st.statusRaw)}"
        }
    }

    fun start(spec: FryerSpec, run: Boolean): Map<String, Any> = mapOf(spec.start to run)

    fun mode(spec: FryerSpec, name: String): Map<String, Any> = mapOf(spec.mode to name)

    fun temperature(spec: FryerSpec, celsius: Float): Map<String, Any> {
        val rounded = (celsius / TEMP_STEP).roundToInt() * TEMP_STEP
        return mapOf(spec.temp to rounded.coerceIn(TEMP_MIN, TEMP_MAX))
    }

    fun cookTime(spec: FryerSpec, minutes: Float): Map<String, Any> =
        mapOf(spec.time to minutes.roundToInt().coerceIn(TIME_MIN, TIME_MAX))
}

/**
 * Vlastný recept fritézy: názov + režim + čas + teplota (štart sa v recepte nikdy neukladá ani nespúšťa).
 * [mode] je prázdny, ak zariadenie režim nemá (potom sa režim pri nastavení nemení).
 */
data class FryerRecipe(
    val id: String,
    val name: String,
    val mode: String,
    val minutes: Int,
    val tempC: Int,
)

/** Čistá logika receptov (bez Androidu): úprava zoznamu, porovnanie so stavom a príkazy na nastavenie. */
object FryerRecipes {
    const val NAME_MAX = 30
    const val MAX_RECIPES = 20

    /** Názov bez okrajových a dvojitých medzier, najviac [NAME_MAX] znakov. */
    fun cleanName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(NAME_MAX).trim()

    /** Recept s rovnakým názvom (bez ohľadu na veľkosť písmen). */
    fun find(list: List<FryerRecipe>, name: String): FryerRecipe? {
        val key = cleanName(name).lowercase()
        if (key.isEmpty()) return null
        return list.firstOrNull { it.name.lowercase() == key }
    }

    /** Recept z aktuálneho nastavenia fritézy; čas a teplota sa dajú do rozsahu, ktorý appka vie nastaviť. */
    fun fromState(id: String, name: String, st: FryerState): FryerRecipe = FryerRecipe(
        id = id,
        name = cleanName(name),
        mode = st.mode.trim(),
        minutes = st.minutes.coerceIn(Fryer.TIME_MIN, Fryer.TIME_MAX),
        tempC = snapTemp(st.tempC),
    )

    fun snapTemp(celsius: Int): Int =
        ((celsius.toFloat() / Fryer.TEMP_STEP).roundToInt() * Fryer.TEMP_STEP).coerceIn(Fryer.TEMP_MIN, Fryer.TEMP_MAX)

    /** Nový recept s názvom, ktorý už existuje, prepíše ten starý (zostane na svojom mieste a s pôvodným id). Plný zoznam nový nepridá. */
    fun upsert(list: List<FryerRecipe>, recipe: FryerRecipe): List<FryerRecipe> {
        val same = find(list, recipe.name)
        return if (same != null) {
            list.map { if (it.id == same.id) recipe.copy(id = same.id) else it }
        } else if (list.size >= MAX_RECIPES) {
            list
        } else {
            list + recipe
        }
    }

    fun canAdd(list: List<FryerRecipe>, name: String): Boolean = find(list, name) != null || list.size < MAX_RECIPES

    fun remove(list: List<FryerRecipe>, id: String): List<FryerRecipe> = list.filterNot { it.id == id }

    /** Premenovanie; ak je názov prázdny alebo ho už má iný recept, zoznam sa nezmení. */
    fun rename(list: List<FryerRecipe>, id: String, name: String): List<FryerRecipe> {
        val clean = cleanName(name)
        if (clean.isEmpty()) return list
        val clash = find(list, clean)
        if (clash != null && clash.id != id) return list
        return list.map { if (it.id == id) it.copy(name = clean) else it }
    }

    /** Nahradí nastavenie receptu aktuálnym (názov a id zostanú). */
    fun overwrite(list: List<FryerRecipe>, id: String, st: FryerState): List<FryerRecipe> =
        list.map { if (it.id == id) fromState(it.id, it.name, st).copy(name = it.name) else it }

    /** Je recept práve nastavený na fritéze? */
    fun matches(r: FryerRecipe, st: FryerState): Boolean =
        (r.mode.isBlank() || r.mode == st.mode) && r.minutes == st.minutes && r.tempC == st.tempC

    /**
     * Príkazy na nastavenie receptu, jeden po druhom: režim, čas, teplota (rovnaké jednotlivé zápisy ako posuvníky,
     * lebo režim môže na zariadení prepísať čas a teplotu, preto ide prvý). Hodnoty, ktoré už sedia, sa nepošlú.
     * Nikdy nie je medzi nimi štart ani hlavný vypínač.
     */
    fun steps(spec: FryerSpec, r: FryerRecipe, st: FryerState): List<Map<String, Any>> = buildList {
        if (r.mode.isNotBlank() && r.mode != st.mode) add(Fryer.mode(spec, r.mode))
        if (r.minutes != st.minutes) add(Fryer.cookTime(spec, r.minutes.toFloat()))
        if (r.tempC != st.tempC) add(Fryer.temperature(spec, r.tempC.toFloat()))
    }

    /** Krátky popis: „Chips · 18 min · 200 °C“. */
    fun describe(r: FryerRecipe): String =
        listOf(r.mode.takeIf { it.isNotBlank() }, "${r.minutes} min", "${r.tempC} °C").filterNotNull().joinToString(" · ")
}
