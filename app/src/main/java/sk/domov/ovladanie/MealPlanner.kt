package sk.domov.ovladanie

import java.time.LocalDate
import java.util.Random
import kotlin.math.abs
import kotlin.math.min

/**
 * Plánovanie jedálnička bez písania: návrh na prázdne miesta v týždni a skopírovanie minulého týždňa.
 * Čistá logika bez Androidu; nič sa neukladá samo – výsledok sa najprv ukáže a uloží sa po potvrdení.
 */
object MealPlanner {
    /** Značka pre jedlo bez receptu, ktoré nejde do nákupu („Jeme vonku“). */
    const val OUT_TITLE = "Jeme vonku"

    data class Suggestion(val date: LocalDate, val slot: Meals.Slot, val recipeId: String)

    /** Dni, v ktorých je ešte voľné miesto (bez živého jedla) pre dané chody. Minulosť sa nenavrhuje. */
    fun emptySlots(days: List<LocalDate>, slots: List<Meals.Slot>, meals: List<Meals.Meal>, today: LocalDate): List<Pair<LocalDate, Meals.Slot>> =
        days.filter { !it.isBefore(today) }.flatMap { d ->
            slots.sortedBy { it.order }.filter { s -> Meals.mealOn(meals, d, s) == null }.map { d to it }
        }

    /**
     * Návrh receptov na prázdne miesta [empty]: čo sa dlho nevarilo a obľúbené (často varené) hore, tá istá vec sa neopakuje
     * 3 dni pred ani po (ak sa dá), recept sa radšej dáva k chodu, v ktorom sa už varil. [seed] strieda návrhy („Zamiešať“);
     * rovnaké vstupy dajú rovnaký výsledok.
     */
    fun suggest(
        empty: List<Pair<LocalDate, Meals.Slot>>,
        meals: List<Meals.Meal>,
        recipes: List<Meals.Recipe>,
        seed: Int,
    ): List<Suggestion> {
        val live = recipes.filter { !it.deleted && it.ingredients.isNotEmpty() }.ifEmpty { recipes.filter { !it.deleted } }
        if (live.isEmpty()) return emptyList()
        val dates = HashMap<String, MutableList<LocalDate>>()
        val slotsUsed = HashMap<String, MutableSet<Meals.Slot>>()
        val uses = HashMap<String, Int>()
        for (m in meals.filter { !it.deleted && !it.skipped && it.recipeId.isNotEmpty() }) {
            val d = runCatching { LocalDate.parse(m.date) }.getOrNull() ?: continue
            dates.getOrPut(m.recipeId) { mutableListOf() } += d
            slotsUsed.getOrPut(m.recipeId) { mutableSetOf() } += m.slot
            uses[m.recipeId] = (uses[m.recipeId] ?: 0) + 1
        }
        val out = ArrayList<Suggestion>()
        for ((date, slot) in empty.sortedWith(compareBy({ it.first }, { it.second.order }))) {
            var best: Meals.Recipe? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (r in live) {
                val used = dates[r.id].orEmpty()
                val nearest = used.minOfOrNull { abs(it.toEpochDay() - date.toEpochDay()) }
                val sinceLast = used.filter { !it.isAfter(date) }.maxOrNull()?.let { date.toEpochDay() - it.toEpochDay() }
                var score = min(sinceLast ?: 60L, 60L).toDouble()
                score += min(uses[r.id] ?: 0, 5) * 0.8
                if (nearest != null && nearest <= 3) score -= 100.0
                val slots = slotsUsed[r.id]
                if (slots != null) score += if (slot in slots) 6.0 else -6.0
                score += Random(seed * 31L + r.id.hashCode() * 17L + date.toEpochDay()).nextDouble() * 4.0
                if (score > bestScore) {
                    bestScore = score
                    best = r
                }
            }
            val pick = best ?: continue
            out += Suggestion(date, slot, pick.id)
            dates.getOrPut(pick.id) { mutableListOf() } += date
            slotsUsed.getOrPut(pick.id) { mutableSetOf() } += slot
            uses[pick.id] = (uses[pick.id] ?: 0) + 1
        }
        return out
    }

    /**
     * Jedlá minulého týždňa ([fromMonday]) na rovnaké dni a chody týždňa [toMonday], len na voľné miesta od [today].
     * Kopírujú sa aj „Jeme vonku“; uvarené a preskočené sa vrátia ako nové (neuvarené).
     */
    fun copyWeek(fromMonday: LocalDate, toMonday: LocalDate, meals: List<Meals.Meal>, today: LocalDate): List<Meals.Meal> {
        val shift = toMonday.toEpochDay() - fromMonday.toEpochDay()
        val out = ArrayList<Meals.Meal>()
        for (m in meals.filter { !it.deleted }) {
            val d = runCatching { LocalDate.parse(m.date) }.getOrNull() ?: continue
            if (d.isBefore(fromMonday) || d.isAfter(fromMonday.plusDays(6))) continue
            if (m.recipeId.isEmpty() && m.title.isBlank()) continue
            val target = d.plusDays(shift)
            if (target.isBefore(today)) continue
            if (Meals.mealOn(meals, target, m.slot) != null) continue
            out += m.copy(
                id = Meals.mealId(target.toString(), m.slot),
                date = target.toString(),
                cooked = false,
                skipped = false,
                updatedAt = 0,
                deleted = false,
            )
        }
        return out.sortedWith(compareBy({ it.date }, { it.slot.order }))
    }

    /** Jedlo bez receptu („Jeme vonku“): nejde do nákupu ani sa nepýta „Uvarené?“. */
    fun outMeal(date: LocalDate, slot: Meals.Slot, title: String = OUT_TITLE): Meals.Meal =
        Meals.Meal(Meals.mealId(date.toString(), slot), date.toString(), slot, recipeId = "", title = title.ifBlank { OUT_TITLE })
}
