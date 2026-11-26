package sk.domov.ovladanie

import android.content.Context

/** Úložisko jedálnička (recepty, týždenný plán, zásoby) v súbore [Meals.PREFS]. */
object MealsStore {
    private fun prefs(c: Context) = c.getSharedPreferences(Meals.PREFS, Context.MODE_PRIVATE)

    fun listen(c: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(c).registerOnSharedPreferenceChangeListener(l)

    fun unlisten(c: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(c).unregisterOnSharedPreferenceChangeListener(l)

    fun homeVisible(c: Context): Boolean = prefs(c).getBoolean(Meals.KEY_HOME, true)

    fun setHomeVisible(c: Context, visible: Boolean) {
        prefs(c).edit().putBoolean(Meals.KEY_HOME, visible).apply()
    }

    fun recipes(c: Context): List<Meals.Recipe> =
        pruneRecipes(Meals.recipesFromJson(prefs(c).getString(Meals.KEY_RECIPES, null).orEmpty()))

    fun meals(c: Context): List<Meals.Meal> =
        pruneMeals(Meals.mealsFromJson(prefs(c).getString(Meals.KEY_PLAN, null).orEmpty()))

    fun pantry(c: Context): List<Meals.PantryItem> {
        ensureDefaults(c)
        return prunePantry(Meals.pantryFromJson(prefs(c).getString(Meals.KEY_PANTRY, null).orEmpty()))
    }

    fun liveRecipes(c: Context): List<Meals.Recipe> = recipes(c).filter { !it.deleted }.sortedBy { Meals.fold(it.name) }

    fun liveMeals(c: Context): List<Meals.Meal> = meals(c).filter { !it.deleted }

    fun livePantry(c: Context): List<Meals.PantryItem> =
        pantry(c).filter { !it.deleted }.sortedWith(compareBy({ it.always }, { Meals.fold(it.name) }))

    fun people(c: Context): List<Calendar.HousePerson> =
        Calendar.people(HouseholdStore.phoneId(c), HouseholdStore.name(c), HouseholdStore.members(c))

    fun namesById(c: Context): Map<String, String> = people(c).associate { it.id to it.name }

    @Synchronized
    fun ensureDefaults(c: Context) {
        if (prefs(c).contains(Meals.KEY_PANTRY)) return
        val now = System.currentTimeMillis()
        val me = HouseholdStore.phoneId(c)
        writePantry(c, Meals.defaultStaples(now, me), emptyList())
    }

    @Synchronized
    fun saveRecipe(c: Context, recipe: Meals.Recipe): List<Meals.Recipe> {
        val before = recipes(c)
        val now = System.currentTimeMillis()
        val me = HouseholdStore.phoneId(c)
        val item = recipe.copy(updatedAt = now, deviceId = me, deleted = false)
        val next = if (before.any { it.id == item.id }) before.map { if (it.id == item.id) item else it } else before + item
        writeRecipes(c, next, before)
        return next
    }

    @Synchronized
    fun deleteRecipe(c: Context, id: String): List<Meals.Recipe> {
        val before = recipes(c)
        val now = System.currentTimeMillis()
        val me = HouseholdStore.phoneId(c)
        val next = before.map { if (it.id == id) it.copy(deleted = true, updatedAt = now, deviceId = me) else it }
        writeRecipes(c, next, before)
        return next
    }

    @Synchronized
    fun saveMeal(c: Context, meal: Meals.Meal): List<Meals.Meal> {
        val before = meals(c)
        val now = System.currentTimeMillis()
        val me = HouseholdStore.phoneId(c)
        val item = meal.copy(id = Meals.mealId(meal.date, meal.slot), updatedAt = now, deviceId = me, deleted = false)
        val next = if (before.any { it.id == item.id }) before.map { if (it.id == item.id) item else it } else before + item
        writeMeals(c, next, before)
        return next
    }

    @Synchronized
    fun deleteMeal(c: Context, id: String): List<Meals.Meal> {
        val before = meals(c)
        val now = System.currentTimeMillis()
        val me = HouseholdStore.phoneId(c)
        val next = before.map { if (it.id == id) it.copy(deleted = true, updatedAt = now, deviceId = me) else it }
        writeMeals(c, next, before)
        return next
    }

    /** Označí jedlo ako uvarené a odráta suroviny zo zásob (okrem „vždy doma“). */
    @Synchronized
    fun markCooked(c: Context, meal: Meals.Meal): Boolean {
        val recipe = liveRecipes(c).firstOrNull { it.id == meal.recipeId } ?: return false
        val now = System.currentTimeMillis()
        val me = HouseholdStore.phoneId(c)
        val beforeMeals = meals(c)
        val cooked = meal.copy(cooked = true, updatedAt = now, deviceId = me, deleted = false)
        val nextMeals = if (beforeMeals.any { it.id == cooked.id }) beforeMeals.map { if (it.id == cooked.id) cooked else it } else beforeMeals + cooked
        writeMeals(c, nextMeals, beforeMeals)
        val beforePantry = pantry(c)
        writePantry(c, Meals.deduct(beforePantry, recipe, Meals.portionsOf(meal, recipe), now, me), beforePantry)
        return true
    }

    @Synchronized
    fun savePantry(c: Context, item: Meals.PantryItem): List<Meals.PantryItem> {
        val before = pantry(c)
        val now = System.currentTimeMillis()
        val me = HouseholdStore.phoneId(c)
        val id = item.id.ifBlank { Meals.pantryId(item.name) }
        val stored = item.copy(id = id, updatedAt = now, deviceId = me, deleted = false)
        val next = if (before.any { it.id == id }) before.map { if (it.id == id) stored else it } else before + stored
        writePantry(c, next, before)
        return next
    }

    @Synchronized
    fun deletePantry(c: Context, id: String): List<Meals.PantryItem> {
        val before = pantry(c)
        val now = System.currentTimeMillis()
        val me = HouseholdStore.phoneId(c)
        val next = before.map { if (it.id == id) it.copy(deleted = true, updatedAt = now, deviceId = me) else it }
        writePantry(c, next, before)
        return next
    }

    @Synchronized
    fun bumpPantry(c: Context, id: String, dir: Int): List<Meals.PantryItem> {
        val before = pantry(c)
        val now = System.currentTimeMillis()
        val me = HouseholdStore.phoneId(c)
        val next = before.map {
            if (it.id != id || it.deleted) it
            else Meals.bump(it, dir).copy(updatedAt = now, deviceId = me)
        }
        writePantry(c, next, before)
        return next
    }

    /**
     * Po miestnom odškrtnutí nákupu: vyparsuje množstvo z názvu a pripočíta ho do zásob bez pýtania.
     * Pri príchode z iného telefónu sa nevolá — zásoby prídu samostatne synchronizáciou.
     */
    @Synchronized
    fun addPurchased(c: Context, rawName: String) {
        if (rawName.isBlank()) return
        val before = pantry(c)
        val now = System.currentTimeMillis()
        val me = HouseholdStore.phoneId(c)
        writePantry(c, Meals.addPurchased(before, rawName, now, me), before)
    }

    @Synchronized
    fun mergeIncomingRecipes(c: Context, incoming: List<Meals.Recipe>): Boolean =
        mergeList(incoming, recipes(c), { it.id }, { it.updatedAt }, { it.deviceId }) { writeRecipes(c, it, recipes(c)) }

    @Synchronized
    fun mergeIncomingMeals(c: Context, incoming: List<Meals.Meal>): Boolean =
        mergeList(incoming, meals(c), { it.id }, { it.updatedAt }, { it.deviceId }) { writeMeals(c, it, meals(c)) }

    @Synchronized
    fun mergeIncomingPantry(c: Context, incoming: List<Meals.PantryItem>): Boolean =
        mergeList(incoming, pantry(c), { it.id }, { it.updatedAt }, { it.deviceId }) { writePantry(c, it, pantry(c)) }

    private fun <T> mergeList(
        incoming: List<T>,
        current: List<T>,
        idOf: (T) -> String,
        atOf: (T) -> Long,
        devOf: (T) -> String,
        write: (List<T>) -> Unit,
    ): Boolean {
        if (incoming.isEmpty()) return false
        val keep = current.associateBy { idOf(it) }.toMutableMap()
        var changed = false
        for (item in incoming) {
            val id = idOf(item)
            if (id.isBlank()) continue
            val existing = keep[id]
            if (existing == null || HouseSync.incomingWins(atOf(existing), devOf(existing), atOf(item), devOf(item))) {
                if (existing != item) {
                    keep[id] = item
                    changed = true
                }
            }
        }
        if (!changed) return false
        write(keep.values.toList())
        return true
    }

    private fun writeRecipes(c: Context, next: List<Meals.Recipe>, before: List<Meals.Recipe>) {
        prefs(c).edit().putString(Meals.KEY_RECIPES, Meals.recipesToJson(pruneRecipes(next))).apply()
        notify(c, HouseSync.KIND_RECIPE, before, pruneRecipes(next), { it.id }, { it })
    }

    private fun writeMeals(c: Context, next: List<Meals.Meal>, before: List<Meals.Meal>) {
        prefs(c).edit().putString(Meals.KEY_PLAN, Meals.mealsToJson(pruneMeals(next))).apply()
        notify(c, HouseSync.KIND_MEAL, before, pruneMeals(next), { it.id }, { it })
    }

    private fun writePantry(c: Context, next: List<Meals.PantryItem>, before: List<Meals.PantryItem>) {
        prefs(c).edit().putString(Meals.KEY_PANTRY, Meals.pantryToJson(prunePantry(next))).apply()
        notify(c, HouseSync.KIND_PANTRY, before, prunePantry(next), { it.id }, { it })
    }

    private fun <T> notify(
        c: Context,
        kind: String,
        before: List<T>,
        next: List<T>,
        idOf: (T) -> String,
        ident: (T) -> T,
    ) {
        if (HouseSyncEngine.applying) return
        val ids = next.filter { n -> before.find { idOf(it) == idOf(n) } != ident(n) }.map { idOf(it) } +
            before.filter { b -> next.none { idOf(it) == idOf(b) } }.map { idOf(it) }
        for (id in ids.distinct()) HouseSyncEngine.onLocalChange(c, kind, id)
    }

    private fun pruneRecipes(list: List<Meals.Recipe>, now: Long = System.currentTimeMillis()) =
        list.filter { !it.deleted || now - it.updatedAt < HouseSync.TOMBSTONE_MS }

    private fun pruneMeals(list: List<Meals.Meal>, now: Long = System.currentTimeMillis()) =
        list.filter { !it.deleted || now - it.updatedAt < HouseSync.TOMBSTONE_MS }

    private fun prunePantry(list: List<Meals.PantryItem>, now: Long = System.currentTimeMillis()) =
        list.filter { !it.deleted || now - it.updatedAt < HouseSync.TOMBSTONE_MS }
}
