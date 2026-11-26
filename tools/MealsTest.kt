import org.json.JSONObject
import sk.domov.ovladanie.*
import java.time.LocalDate

// Jedálniček: parsovanie množstiev z názvu, súčet surovín, doplnenie nákupu, odpočet po uvarení, LWW synchronizácia.

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

fun qtyEq(a: Double, b: Double) = kotlin.math.abs(a - b) < 1e-6

fun recipe(id: String, name: String, portions: Int, vararg ings: Meals.Ingredient, at: Long = 1L) =
    Meals.Recipe(id, name, portions, ings.toList(), updatedAt = at, deviceId = "me")

fun meal(date: String, slot: Meals.Slot, recipeId: String, title: String, portions: Int = 0, cooked: Boolean = false, cooks: List<String> = emptyList(), at: Long = 1L) =
    Meals.Meal(Meals.mealId(date, slot), date, slot, recipeId, title, cooks, portions, cooked, updatedAt = at, deviceId = "me")

fun pantry(name: String, amount: Double, unit: String, always: Boolean = false, expires: String? = null, at: Long = 1L) =
    Meals.PantryItem(Meals.pantryId(name), name, amount, unit, always, expires, updatedAt = at, deviceId = "me")

fun shop(name: String, list: ShopListKind = ShopListKind.SHARED, done: Boolean = false) =
    ShopItem("s-" + name, name, done, 1L, 0L, "me", "Tomáš", 1L, false, "me", list)

fun main() {
    // ── parsovanie množstva z názvu ─────────────────────────────────────────
    val mlieko = Meals.parseAmount("Mlieko 2 l")
    check("mlieko 2 l: názov", mlieko.name == "Mlieko")
    check("mlieko 2 l: množstvo", qtyEq(mlieko.amount, 2.0) && mlieko.unit == "l")
    check("mlieko 2 l: text do zoznamu", mlieko.shopText() == "Mlieko 2 l")

    val jogurt = Meals.parseAmount("Jogurt biely 4 ks")
    check("jogurt 4 ks", jogurt.name == "Jogurt biely" && qtyEq(jogurt.amount, 4.0) && jogurt.unit == "ks")

    val noSpace = Meals.parseAmount("Mlieko 2l")
    check("bez medzery pred jednotkou", noSpace.name == "Mlieko" && qtyEq(noSpace.amount, 2.0) && noSpace.unit == "l")

    val comma = Meals.parseAmount("Smotana 0,2 l")
    check("desatinná čiarka", comma.name == "Smotana" && qtyEq(comma.amount, 0.2) && comma.unit == "l")

    val grams = Meals.parseAmount("Bryndza 250g")
    check("gramy bez medzery", grams.name == "Bryndza" && qtyEq(grams.amount, 250.0) && grams.unit == "g")

    val dcl = Meals.parseAmount("Mlieko 2 dcl")
    check("dcl ide na ml", dcl.name == "Mlieko" && qtyEq(dcl.amount, 200.0) && dcl.unit == "ml")

    val kusov = Meals.parseAmount("Paradajky 3 kusy")
    check("alias kusy → ks", kusov.unit == "ks" && qtyEq(kusov.amount, 3.0) && kusov.name == "Paradajky")

    val bareNum = Meals.parseAmount("Cestoviny 3")
    check("číslo bez jednotky je ks", bareNum.name == "Cestoviny" && qtyEq(bareNum.amount, 3.0) && bareNum.unit == "ks")

    val plain = Meals.parseAmount("Toaletný papier")
    check("bez množstva → 1 ks", plain.name == "Toaletný papier" && qtyEq(plain.amount, 1.0) && plain.unit == "ks")

    val trim = Meals.parseAmount("  šalát  ")
    check("bez čísla ostane celý názov a 1 ks", trim.name == "šalát" && qtyEq(trim.amount, 1.0))

    val empty = Meals.parseAmount("   ")
    check("prázdny text → 1 ks", empty.name.isEmpty() && qtyEq(empty.amount, 1.0) && empty.unit == "ks")

    val onlyQty = Meals.parseAmount("2 l")
    check("len množstvo bez názvu sa neberie ako nákup", onlyQty.name == "2 l" && qtyEq(onlyQty.amount, 1.0) && onlyQty.unit == "ks")

    val kg = Meals.parseAmount("Múka 1,5 kg")
    check("kg s čiarkou", kg.name == "Múka" && qtyEq(kg.amount, 1.5) && kg.unit == "kg")

    // ── formát a súčet jednotiek ────────────────────────────────────────────
    check("formát celé číslo", Meals.formatQty(2.0, "l") == "2 l")
    check("formát desatinné čiarkou", Meals.formatQty(1.5, "l") == "1,5 l")
    check("formát názov + množstvo", Meals.formatShop("Smotana", 200.0, "ml") == "Smotana 200 ml")

    val sumMass = Meals.sumQty(500.0, "g", 1.0, "kg")
    check("súčet 500 g + 1 kg = 1,5 kg", qtyEq(sumMass.amount, 1.5) && sumMass.unit == "kg")
    val sumVol = Meals.sumQty(500.0, "ml", 200.0, "ml")
    check("súčet 500+200 ml ostane ml", qtyEq(sumVol.amount, 700.0) && sumVol.unit == "ml")
    val sumLit = Meals.sumQty(800.0, "ml", 300.0, "ml")
    check("súčet 1100 ml → 1,1 l", qtyEq(sumLit.amount, 1.1) && sumLit.unit == "l")
    val sub = Meals.subQty(150.0, "g", 250.0, "g")
    check("odpočet 250−150 g = 100 g", qtyEq(sub.amount, 100.0) && sub.unit == "g")
    check("dosť zásob", Meals.enough(1.0, "l", 200.0, "ml"))
    check("málo zásob", !Meals.enough(150.0, "g", 250.0, "g"))
    check("zlúčiteľné g a kg", Meals.canMerge("g", "kg") && !Meals.canMerge("g", "ks"))

    val merged = Meals.mergeIngredients(listOf(
        Meals.Ingredient("Bryndza", 150.0, "g"),
        Meals.Ingredient("bryndza", 100.0, "g"),
        Meals.Ingredient("Mlieko", 200.0, "ml"),
        Meals.Ingredient("Mlieko", 0.8, "l"),
    ))
    val bry = merged.first { Meals.fold(it.name) == Meals.fold("Bryndza") }
    val mlk = merged.first { Meals.fold(it.name) == Meals.fold("Mlieko") }
    check("zlúčenie rovnakého názvu bez diakritiky", qtyEq(bry.amount, 250.0) && bry.unit == "g")
    check("zlúčenie mlieka ml + l", qtyEq(mlk.amount, 1.0) && mlk.unit == "l")
    check("zlúčenie má dva riadky", merged.size == 2)

    // ── týždeň: pondelok až nedeľa, doplnenie od dnes ───────────────────────
    val wed = LocalDate.of(2026, 11, 25)
    check("streda 25. 11. 2026", wed.dayOfWeek == java.time.DayOfWeek.WEDNESDAY)
    check("pondelok týždňa", Meals.weekMonday(wed) == LocalDate.of(2026, 11, 23))
    check("nedeľa týždňa", Meals.weekSunday(wed) == LocalDate.of(2026, 11, 29))
    check("doplniť do nedele", Meals.fillUntil(wed) == LocalDate.of(2026, 11, 29))
    check("nedeľa ostane nedeľou", Meals.fillUntil(LocalDate.of(2026, 11, 29)) == LocalDate.of(2026, 11, 29))
    check("popis dňa", Meals.dayLabel(wed) == "St")
    check("rozsah týždňa v rovnakom mesiaci", Meals.formatWeekRange(LocalDate.of(2026, 11, 23)) == "23.–29. 11. 2026")

    check("kto varí: mená členov", Meals.cookLabel(listOf("t", "j"), mapOf("t" to "Tomáš", "j" to "Jana")) == "Tomáš, Jana")
    check("kto varí: prázdne", Meals.cookLabel(emptyList(), mapOf("t" to "Tomáš")).isEmpty())
    check("kto varí: neznáme id vynechá", Meals.cookLabel(listOf("x", "t"), mapOf("t" to "Tomáš")) == "Tomáš")

    // ── plán nákupu: odpočet zásob, vždy doma, už v zozname, overiť ────────
    val kurca = recipe(
        "r-kurca", "Kurča na paprike", 4,
        Meals.Ingredient("Kura", 1.0, "ks"),
        Meals.Ingredient("Paprika", 2.0, "ks"),
        Meals.Ingredient("Cibuľa", 1.0, "ks"),
        Meals.Ingredient("Olej", 2.0, "pl"),
        Meals.Ingredient("Soľ", 1.0, "ks"),
        Meals.Ingredient("Smotana", 200.0, "ml"),
        Meals.Ingredient("Vývar", 500.0, "ml"),
        Meals.Ingredient("Cesnak", 1.0, "hlavička"),
    )
    val salat = recipe(
        "r-salat", "Šalát s fetou", 2,
        Meals.Ingredient("Feta", 200.0, "g"),
        Meals.Ingredient("Šalát", 1.0, "ks"),
    )
    val halusky = recipe(
        "r-halusky", "Halušky s bryndzou", 4,
        Meals.Ingredient("Bryndza", 250.0, "g"),
        Meals.Ingredient("Zemiaky", 800.0, "g"),
    )
    val pizza = recipe(
        "r-pizza", "Pizza z rúry", 4,
        Meals.Ingredient("Mozzarella", 250.0, "g"),
        Meals.Ingredient("Paradajky", 3.0, "ks"),
    )
    val pecene = recipe(
        "r-pecene", "Pečené kura", 4,
        Meals.Ingredient("Kura", 1.0, "ks"),
    )
    val risotto = recipe(
        "r-risotto", "Risotto s hubami", 4,
        Meals.Ingredient("Huby", 300.0, "g"),
        Meals.Ingredient("Ryža", 300.0, "g"),
    )
    val monMeal = meal("2026-11-23", Meals.Slot.LUNCH, "r-kurca", "Cestoviny s tuniakom") // pondelok — pred dneškom
    val wedLunch = meal("2026-11-25", Meals.Slot.LUNCH, "r-kurca", "Kurča na paprike", cooks = listOf("t"))
    val wedDinner = meal("2026-11-25", Meals.Slot.DINNER, "r-salat", "Šalát s fetou", cooks = listOf("j"))
    val thuLunch = meal("2026-11-26", Meals.Slot.LUNCH, "r-halusky", "Halušky s bryndzou", cooks = listOf("j"))
    val friDinner = meal("2026-11-27", Meals.Slot.DINNER, "r-pizza", "Pizza z rúry", cooks = listOf("t", "j"))
    val satLunch = meal("2026-11-28", Meals.Slot.LUNCH, "r-pecene", "Pečené kura", cooks = listOf("t"))
    val sunLunch = meal("2026-11-29", Meals.Slot.LUNCH, "r-risotto", "Risotto s hubami", cooks = listOf("j"))
    val allMeals = listOf(monMeal, wedLunch, wedDinner, thuLunch, friDinner, satLunch, sunLunch)
    val allRecipes = listOf(kurca, salat, halusky, pizza, pecene, risotto)
    val stock = listOf(
        pantry("Kura", 1.0, "ks"),
        pantry("Paprika", 3.0, "ks"),
        pantry("Cibuľa", 2.0, "ks"),
        pantry("Olej", 0.0, "ks", always = true),
        pantry("Soľ", 0.0, "ks", always = true),
        pantry("Bryndza", 150.0, "g", expires = "2026-11-27"),
        pantry("Paradajky", 2.0, "ks"),
        pantry("Mlieko", 1.0, "l"),
        pantry("Šalát", 1.0, "ks"),
        pantry("Zemiaky", 1.0, "kg"),
        pantry("Ryža", 500.0, "g"),
    )
    val list = listOf(shop("Mlieko 2 l"))

    val needed = Meals.neededForRange(wed, Meals.fillUntil(wed), allMeals, allRecipes)
    check("súčty neberú pondelok (pred dnes)", needed.none { it.sources.any { s -> s.startsWith("Po ") } })
    val kuraNeed = needed.first { Meals.fold(it.name) == Meals.fold("Kura") }
    check("kura: dnešný obed + sobota = 2 ks", qtyEq(kuraNeed.amount, 2.0) && kuraNeed.unit == "ks")

    val plan = Meals.planShopping(wed, Meals.fillUntil(wed), allMeals, allRecipes, stock, list, ShopListKind.SHARED)
    val missing = plan.filter { it.kind == Meals.NeedKind.MISSING }
    val already = plan.filter { it.kind == Meals.NeedKind.ALREADY }
    val ask = plan.filter { it.kind == Meals.NeedKind.ASK }
    fun hit(name: String) = plan.firstOrNull { Meals.fold(it.name) == Meals.fold(name) }

    check("olej a soľ sa preskočia (vždy doma / základ)", plan.none { Meals.isStaple(it.name) || Meals.fold(it.name) in setOf(Meals.fold("Olej"), Meals.fold("Soľ")) })
    check("paprika a cibuľa stačia", hit("Paprika") == null && hit("Cibuľa") == null)
    check("šalát a zemiaky a ryža stačia", hit("Šalát") == null && hit("Zemiaky") == null && hit("Ryža") == null)
    val smotana = hit("Smotana")!!
    check("smotana celá chýba", smotana.kind == Meals.NeedKind.MISSING && qtyEq(smotana.amount, 200.0) && smotana.unit == "ml")
    val vyvar = hit("Vývar")!!
    check("vývar celý chýba", vyvar.kind == Meals.NeedKind.MISSING && qtyEq(vyvar.amount, 500.0) && vyvar.unit == "ml")
    val feta = hit("Feta")!!
    check("feta bez zásoby, známa jednotka ide do Chýba", feta.kind == Meals.NeedKind.MISSING && qtyEq(feta.amount, 200.0) && feta.unit == "g")
    val cesnak = hit("Cesnak")!!
    check("cesnak v neznámej jednotke (hlavička) je Overiť", cesnak.kind == Meals.NeedKind.ASK && qtyEq(cesnak.amount, 1.0))
    val bryndza = hit("Bryndza")!!
    check("bryndza: treba 250, máme 150, chýba 100 g", bryndza.kind == Meals.NeedKind.MISSING && qtyEq(bryndza.amount, 100.0) && bryndza.unit == "g")
    val mozz = hit("Mozzarella")!!
    check("mozarella bez zásoby ide do Chýba", mozz.kind == Meals.NeedKind.MISSING && qtyEq(mozz.amount, 250.0))
    val huby = hit("Huby")!!
    check("huby bez zásoby idú do Chýba", huby.kind == Meals.NeedKind.MISSING && qtyEq(huby.amount, 300.0))
    val kuraMiss = hit("Kura")!!
    check("kura: máme 1, treba 2, chýba 1 ks", kuraMiss.kind == Meals.NeedKind.MISSING && qtyEq(kuraMiss.amount, 1.0))
    val parad = hit("Paradajky")!!
    check("paradajky: máme 2, treba 3, chýba 1 ks", parad.kind == Meals.NeedKind.MISSING && qtyEq(parad.amount, 1.0))
    check("mlieko nie je v recepte, v zozname ostáva bokom", hit("Mlieko") == null)
    check("už v zozname je prázdne, keď surovina nie je v pláne", already.isEmpty())

    // Mlieko ako surovina + už v zozname
    val withMilk = allRecipes + recipe("r-kasa", "Kaša", 2, Meals.Ingredient("Mlieko", 1500.0, "ml"))
    val milkMeal = meal("2026-11-25", Meals.Slot.DINNER, "r-kasa", "Kaša")
    val planMilk = Meals.planShopping(wed, wed, listOf(milkMeal), withMilk, stock, list, ShopListKind.SHARED)
    check("mlieko už v spoločnom zozname ide do Už v zozname", planMilk.single { Meals.fold(it.name) == Meals.fold("Mlieko") }.kind == Meals.NeedKind.ALREADY)
    val planMine = Meals.planShopping(wed, wed, listOf(milkMeal), withMilk, stock, list, ShopListKind.MINE)
    val mineMilk = planMine.single { Meals.fold(it.name) == Meals.fold("Mlieko") }
    check("v mojom zozname mlieko nie je, takže sa pridá (máme 1 l, treba 1,5 l → chýba 0,5 l)", mineMilk.kind == Meals.NeedKind.MISSING && qtyEq(mineMilk.amount, 500.0) && mineMilk.unit == "ml")
    val enoughStock = Meals.planShopping(wed, wed, listOf(milkMeal.copy(portions = 1)), withMilk.map { if (it.id == "r-kasa") it.copy(ingredients = listOf(Meals.Ingredient("Mlieko", 500.0, "ml"))) else it }, stock, list, ShopListKind.SHARED)
    check("zásoba stačí → nič sa neponúka, ani Už v zozname", enoughStock.none { Meals.fold(it.name) == Meals.fold("Mlieko") })

    val cookedWed = wedLunch.copy(cooked = true)
    val afterCookPlan = Meals.planShopping(wed, Meals.fillUntil(wed), listOf(cookedWed, satLunch), allRecipes, stock, emptyList())
    check("po uvarení dnešného kurčaťa (ešte pred odpočtom) zásoba 1 ks pokryje sobotu", afterCookPlan.none { Meals.fold(it.name) == Meals.fold("Kura") })

    // ── uvarené odpočíta suroviny, vždy doma nechá ──────────────────────────
    val deducted = Meals.deduct(stock, kurca, 4, 50L, "me")
    fun stockOf(n: String) = deducted.first { Meals.fold(it.name) == Meals.fold(n) }
    check("po uvarení ubudne 1 kura", qtyEq(stockOf("Kura").amount, 0.0))
    check("po uvarení paprika 3−2=1", qtyEq(stockOf("Paprika").amount, 1.0))
    check("po uvarení cibuľa 2−1=1", qtyEq(stockOf("Cibuľa").amount, 1.0))
    check("olej vždy doma sa nemení", stockOf("Olej").always && qtyEq(stockOf("Olej").amount, 0.0))
    check("soľ vždy doma sa nemení", stockOf("Soľ").always)
    check("updatedAt po odpočte", stockOf("Kura").updatedAt == 50L)

    val afterToday = Meals.planShopping(wed, Meals.fillUntil(wed), listOf(cookedWed, satLunch), allRecipes, deducted, emptyList())
    val kuraThen = afterToday.firstOrNull { Meals.fold(it.name) == Meals.fold("Kura") }
    check("po odpočte dnešného kurčaťa chýba sobotné kura", kuraThen != null && kuraThen.kind == Meals.NeedKind.MISSING && qtyEq(kuraThen.amount, 1.0))

    // menšie porcie: recept na 4, varíme 2 → polovičné množstvá
    val half = Meals.scaledIngredients(kurca, 2)
    check("polovičná smotana", qtyEq(half.first { it.name == "Smotana" }.amount, 100.0))

    // ── odškrtnutie nákupu ide do zásob ─────────────────────────────────────
    val added = Meals.addPurchased(stock, "Smotana 200 ml", 80L, "me")
    val smo = added.first { Meals.fold(it.name) == Meals.fold("Smotana") }
    check("nová zásoba zo zoznamu", qtyEq(smo.amount, 200.0) && smo.unit == "ml" && smo.id == Meals.pantryId("Smotana"))
    val addedAgain = Meals.addPurchased(added, "smotana 100 ml", 90L, "me")
    val smo2 = addedAgain.first { Meals.fold(it.name) == Meals.fold("Smotana") }
    check("ďalší nákup sa pripočíta", qtyEq(smo2.amount, 300.0) && smo2.unit == "ml" && smo2.updatedAt == 90L)

    val paper = Meals.addPurchased(emptyList(), "Toaletný papier", 1L, "me")
    check("neparsovateľné ide ako 1 ks", paper.single().name == "Toaletný papier" && qtyEq(paper.single().amount, 1.0) && paper.single().unit == "ks")

    val milkAdd = Meals.addPurchased(stock, "Mlieko 0,5 l", 1L, "me")
    check("pripočítať 0,5 l k 1 l", qtyEq(milkAdd.first { Meals.fold(it.name) == Meals.fold("Mlieko") }.amount, 1.5))

    // ── málo / končí ────────────────────────────────────────────────────────
    check("paradajky 2 ks sú málo", Meals.isLow(stock.first { it.name == "Paradajky" }))
    check("mlieko 1 l nie je málo", !Meals.isLow(stock.first { it.name == "Mlieko" }))
    check("soľ vždy doma nie je málo", !Meals.isLow(stock.first { it.name == "Soľ" }))
    check("bryndza do 27. 11. končí (dnes 25.)", Meals.isExpiring(stock.first { it.name == "Bryndza" }, wed, 3))
    check("bryndza nie je končiaca o 0 dní", !Meals.isExpiring(stock.first { it.name == "Bryndza" }, wed, 0))
    val bumped = Meals.bump(stock.first { it.name == "Paradajky" }, 1)
    check("+1 ks paradajky", qtyEq(bumped.amount, 3.0))
    check("− nejde pod nulu", qtyEq(Meals.bump(pantry("X", 0.0, "ks"), -1).amount, 0.0))

    // ── JSON tam a späť ─────────────────────────────────────────────────────
    val rJson = Meals.recipesFromJson(Meals.recipesToJson(allRecipes))
    check("recepty JSON", rJson.size == allRecipes.size && rJson[0].name == "Kurča na paprike" && rJson[0].ingredients.any { it.name == "Smotana" && qtyEq(it.amount, 200.0) })
    val mJson = Meals.mealsFromJson(Meals.mealsToJson(allMeals))
    check("plán JSON", mJson.size == allMeals.size && mJson[1].slot == Meals.Slot.LUNCH && mJson[1].cookIds == listOf("t"))
    val pJson = Meals.pantryFromJson(Meals.pantryToJson(stock))
    check("zásoby JSON", pJson.size == stock.size && pJson.first { it.name == "Bryndza" }.expires == "2026-11-27" && pJson.first { it.name == "Olej" }.always)

    // ── synchronizácia LWW + tombstones + typ ───────────────────────────────
    val recA = HouseSync.record(HouseSync.KIND_PANTRY, "p:mlieko", 100, "aa", false, Meals.pantryObj(pantry("Mlieko", 1.0, "l", at = 100)))
    val recB = HouseSync.record(HouseSync.KIND_PANTRY, "p:mlieko", 200, "bb", false, Meals.pantryObj(pantry("Mlieko", 2.0, "l", at = 200)))
    check("zásoby: novší záznam vyhrá", HouseSync.merge(recA, recB).optLong("u") == 200L)
    val (pMerged, pChanged) = HouseSync.applyIncoming(mapOf(HouseSync.keyOf(recA) to recA), listOf(recB))
    check("zásoby applyIncoming zmení kľúč", pChanged == listOf("pantry:p:mlieko") && pMerged["pantry:p:mlieko"]!!.optLong("u") == 200L)
    val mealA = HouseSync.record(HouseSync.KIND_MEAL, "m:2026-11-25:l", 10, "aa", false, Meals.mealObj(wedLunch.copy(updatedAt = 10)))
    val mealB = HouseSync.record(HouseSync.KIND_MEAL, "m:2026-11-25:l", 20, "bb", false, Meals.mealObj(wedLunch.copy(cooked = true, updatedAt = 20, deviceId = "bb")))
    val (mMerged, mChanged) = HouseSync.applyIncoming(mapOf(HouseSync.keyOf(mealA) to mealA), listOf(mealB))
    check("plán: novší uvarené vyhrá", mChanged == listOf("meal:m:2026-11-25:l") && mMerged["meal:m:2026-11-25:l"]!!.optJSONObject("p")!!.optBoolean("k"))
    val recipeA = HouseSync.record(HouseSync.KIND_RECIPE, "r-kurca", 5, "aa", false, Meals.recipeObj(kurca.copy(updatedAt = 5)))
    val recipeDel = HouseSync.record(HouseSync.KIND_RECIPE, "r-kurca", 9, "zz", true, Meals.recipeObj(kurca.copy(updatedAt = 9, deleted = true, deviceId = "zz")))
    val (rMerged, rChanged) = HouseSync.applyIncoming(mapOf(HouseSync.keyOf(recipeA) to recipeA), listOf(recipeDel))
    check("recept: novší tombstone vyhrá", rChanged == listOf("recipe:r-kurca") && rMerged["recipe:r-kurca"]!!.optBoolean("x"))

    val typo = HouseSync.record("recept", "r-x", 1, "aa", false, JSONObject().put("n", "X"))
    val unknown = HouseSync.record("fryer", "f1", 1, "aa", false, JSONObject().put("n", "X"))
    val (skipped, chSkip) = HouseSync.applyIncoming(emptyMap(), listOf(typo, unknown))
    check("neznámy typ (preklep aj iný) sa preskočí", skipped.isEmpty() && chSkip.isEmpty())

    val oldTomb = HouseSync.record(HouseSync.KIND_PANTRY, "p:x", 1L, "aa", true, JSONObject().put("n", "X"))
    val liveP = HouseSync.record(HouseSync.KIND_PANTRY, "p:y", 1L, "aa", false, JSONObject().put("n", "Y"))
    val freshTomb = HouseSync.record(HouseSync.KIND_MEAL, "m:z", System.currentTimeMillis(), "aa", true, JSONObject())
    val pruned = HouseSync.pruneTombstones(listOf(oldTomb, liveP, freshTomb), System.currentTimeMillis())
    check("starý tombstone zásob zmizne, živá a nový tombstone ostanú", pruned.map { it.optString("i") }.toSet() == setOf("p:y", "m:z"))

    val (_, noneAgain) = HouseSync.applyIncoming(pMerged, listOf(recB))
    check("rovnaký príchod zásob sa nezapisuje znova", noneAgain.isEmpty())

    // ── 1.61: raňajky, neurčité množstvá, prepočet porcií ───────────────────
    check("slot: b je raňajky", Meals.Slot.of("b") == Meals.Slot.BREAKFAST)
    check("slot: l a prázdny (1.60) je obed", Meals.Slot.of("l") == Meals.Slot.LUNCH && Meals.Slot.of("") == Meals.Slot.LUNCH && Meals.Slot.of("x") == Meals.Slot.LUNCH)
    check("slot: d je večera", Meals.Slot.of("d") == Meals.Slot.DINNER)
    val breakfast = meal("2026-11-25", Meals.Slot.BREAKFAST, "r-salat", "Jogurt", portions = 2)
    check("id raňajok", breakfast.id == "m:2026-11-25:b")
    val dayMeals = Meals.mealsOn(listOf(wedDinner, breakfast, wedLunch), wed)
    check("poradie na deň: raňajky, obed, večera", dayMeals.map { it.slot } == listOf(Meals.Slot.BREAKFAST, Meals.Slot.LUNCH, Meals.Slot.DINNER))
    val bJson = Meals.mealsFromJson(Meals.mealsToJson(listOf(breakfast))).single()
    check("raňajky JSON tam a späť", bJson.slot == Meals.Slot.BREAKFAST && bJson.portions == 2 && bJson.id == breakfast.id)
    val oldLunch = Meals.mealsFromJson("""[{"i":"m:2026-11-25:l","d":"2026-11-25","s":"l","r":"r-kurca","t":"Kurča","c":[],"p":4,"k":false,"ts":1,"x":false,"dv":"me"}]""").single()
    check("starý obed z 1.60 ostane obedom", oldLunch.slot == Meals.Slot.LUNCH && oldLunch.portions == 4)

    val base2 = recipe("r-omacka", "Omáčka", 2, Meals.Ingredient("Smotana", 200.0, "ml"), Meals.Ingredient("Bryndza", 200.0, "g"), Meals.Ingredient("Kura", 1.0, "ks"), Meals.Ingredient("Soľ", 0.0, "štipka"))
    val for10 = Meals.scaledIngredients(base2, 10)
    fun sc(name: String) = for10.first { it.name == name }
    check("200 g na 2 porcie → 1 kg na 10", qtyEq(Meals.toBase(sc("Bryndza").amount, sc("Bryndza").unit), 1000.0))
    check("200 ml na 2 porcie → 1 l na 10", qtyEq(Meals.toBase(sc("Smotana").amount, sc("Smotana").unit), 1000.0))
    check("1 ks na 2 porcie → 5 ks na 10", qtyEq(sc("Kura").amount, 5.0) && sc("Kura").unit == "ks")
    check("štipka sa pri 10 porciách nemení", Meals.isFuzzy(sc("Soľ").unit) && Meals.formatQty(sc("Soľ").amount, sc("Soľ").unit) == "štipka")
    val same = Meals.scaledIngredients(base2, 2)
    check("pri rovnakých porciách ostane 200 ml (nie 0,2 l)", qtyEq(same.first { it.name == "Smotana" }.amount, 200.0) && same.first { it.name == "Smotana" }.unit == "ml")

    check("ks: 1 na 2, varím 3 → 1,5", qtyEq(Meals.scaleQty(1.0, "ks", 3, 2).amount, 1.5))
    check("ks: 1 na 2, varím 4 → 2", qtyEq(Meals.scaleQty(1.0, "ks", 4, 2).amount, 2.0))
    check("ks: 1 na 4, varím 5 → 1,5 (nahor na polovicu)", qtyEq(Meals.scaleQty(1.0, "ks", 5, 4).amount, 1.5))
    check("ks: 1 na 10, varím 1 → 0,5 (nie nula)", qtyEq(Meals.scaleQty(1.0, "ks", 1, 10).amount, 0.5))
    check("ks nahor: 2,1 → 2,5; 2 ostane 2", qtyEq(Meals.roundKsUp(2.1), 2.5) && qtyEq(Meals.roundKsUp(2.0), 2.0))
    check("g: 33,3 g → 33 g", qtyEq(Meals.roundMeas(33.333), 33.0))
    check("g: 1000 ostane 1000 (pôjde na 1 kg)", qtyEq(Meals.roundMeas(1000.0), 1000.0))

    check("formát štipka bez čísla", Meals.formatQty(1.0, "štipka") == "štipka")
    check("formát podľa chuti", Meals.formatQty(0.0, "podľa chuti") == "podľa chuti")
    check("fuzzy aj bez diakritiky", Meals.isFuzzy("stipka") && Meals.isFuzzy("podla chuti") && Meals.fuzzyLabel("PODUŠKA") == "poduška")
    check("hlavička nie je neurčité množstvo", !Meals.isFuzzy("hlavička"))

    val fuzzyMeal = meal("2026-11-25", Meals.Slot.BREAKFAST, "r-omacka", "Omáčka", portions = 10)
    val fuzzPlan = Meals.planShopping(wed, wed, listOf(fuzzyMeal), listOf(base2), emptyList(), emptyList())
    check("štipka nejde do nákupu", fuzzPlan.none { Meals.fold(it.name) == Meals.fold("Soľ") })
    check("prepočítaná smotana ide do nákupu (1 l)", fuzzPlan.any { Meals.fold(it.name) == Meals.fold("Smotana") && qtyEq(Meals.toBase(it.amount, it.unit), 1000.0) })

    val pantrySalt = listOf(pantry("Soľ", 1.0, "ks"), pantry("Smotana", 1.0, "l"))
    val afterDeduct = Meals.deduct(pantrySalt, base2, 10, 99L, "me")
    check("štipka sa z zásob neodpočíta", qtyEq(afterDeduct.first { Meals.fold(it.name) == Meals.fold("Soľ") }.amount, 1.0))
    check("uvarené pre 10 odráta 1 l smotany", qtyEq(afterDeduct.first { Meals.fold(it.name) == Meals.fold("Smotana") }.amount, 0.0))

    val bfastRec = recipe("r-jogurt", "Jogurt s ovocím", 1, Meals.Ingredient("Jogurt", 200.0, "g"))
    val bfastMeal = meal("2026-11-25", Meals.Slot.BREAKFAST, "r-jogurt", "Jogurt s ovocím", portions = 2)
    val bfastNeed = Meals.neededForRange(wed, wed, listOf(bfastMeal, monMeal), listOf(bfastRec, kurca))
    check("doplniť nákup berie aj raňajky", bfastNeed.any { Meals.fold(it.name) == Meals.fold("Jogurt") && qtyEq(it.amount, 400.0) })
    check("pondelok pred dnes sa neberie ani pri raňajkách", bfastNeed.none { it.sources.any { s -> s.startsWith("Po ") } })

    check("text porcií: 1 porciu, 2 porcie, 10 porcií", Meals.portionsText(1) == "1 porciu" && Meals.portionsText(2) == "2 porcie" && Meals.portionsText(10) == "10 porcií")

    // ── mäkké miery: strúčik, PL, ČL, hrsť… ─────────────────────────────────────
    check("soft: rozpoznanie tvarov", Meals.softLabel("strúčiky") == "strúčik" && Meals.softLabel("strucikov") == "strúčik" && Meals.softLabel("PL") == "PL" &&
        Meals.softLabel("ČL") == "ČL" && Meals.softLabel("hrste") == "hrsť" && Meals.softLabel("hrnčeka") == "hrnček")
    check("soft: cl je centilitre, ks a g nie sú mäkké", Meals.softLabel("cl") == null && Meals.softLabel("ks") == null && Meals.softLabel("g") == null && Meals.canonicalUnit("cl") == "ml")
    check("soft: neurčité miery ostávajú neurčité", !Meals.isSoft("štipka") && !Meals.isSoft("trocha") && !Meals.isSoft("podľa chuti") && Meals.isFuzzy("štipka"))
    check("soft: kanonická jednotka", Meals.canonicalUnit("strúčiky") == "strúčik" && Meals.canonicalUnit("PL") == "PL")
    check("soft: skloňovanie", Meals.formatQty(1.0, "strúčik") == "1 strúčik" && Meals.formatQty(2.0, "strúčik") == "2 strúčiky" && Meals.formatQty(5.0, "strúčik") == "5 strúčikov" &&
        Meals.formatQty(1.5, "strúčik") == "1½ strúčika" && Meals.formatQty(0.5, "hrnček") == "½ hrnčeka" && Meals.formatQty(2.0, "hrnček") == "2 hrnčeky")
    check("soft: skratky PL/ČL sa neskloňujú, zlomky", Meals.formatQty(1.0, "PL") == "1 PL" && Meals.formatQty(3.0, "PL") == "3 PL" && Meals.formatQty(0.5, "ČL") == "½ ČL" && Meals.formatQty(0.25, "ČL") == "¼ ČL" && Meals.formatQty(2.5, "PL") == "2½ PL")
    check("soft: desatinné číslo mimo zlomkov", Meals.formatQty(1.3, "strúčik") == "1,3 strúčika")
    check("soft: do zoznamu ide len názov", Meals.formatShop("Cesnak", 2.0, "strúčik") == "Cesnak" && Meals.Ingredient("Cesnak", 2.0, "strúčik").shopText() == "Cesnak" && Meals.formatShop("Múka", 500.0, "g") == "Múka 500 g")

    check("soft: nezlučiteľné s ks ani s inou mäkkou mierou", !Meals.canMerge("strúčik", "ks") && !Meals.canMerge("PL", "ČL") && Meals.canMerge("strúčik", "strúčiky") && !Meals.canMerge("strúčik", "g"))
    val sumSoft = Meals.sumQty(2.0, "strúčik", 3.0, "strúčiky")
    check("soft: súčet zachová jednotku", qtyEq(sumSoft.amount, 5.0) && sumSoft.unit == "strúčik")
    val mergedSoft = Meals.mergeIngredients(listOf(Meals.Ingredient("Cesnak", 2.0, "strúčik"), Meals.Ingredient("Cesnak", 1.0, "strúčik"), Meals.Ingredient("Cesnak", 1.0, "ks"), Meals.Ingredient("Soľ", 0.0, "štipka"), Meals.Ingredient("Soľ", 0.0, "štipka")))
    check("soft: mergeIngredients – 3 strúčiky a 1 ks osobitne, štipky sa nestratia", mergedSoft.size == 3 && mergedSoft.any { it.unit == "strúčik" && qtyEq(it.amount, 3.0) } && mergedSoft.any { it.unit == "ks" } && mergedSoft.any { Meals.formatQty(it.amount, it.unit) == "štipka" })

    check("soft: škálovanie 2 strúčiky z 2 na 4 porcie = 4", qtyEq(Meals.scaleQty(2.0, "strúčik", 4, 2).amount, 4.0) && Meals.scaleQty(2.0, "strúčik", 4, 2).unit == "strúčik")
    check("soft: 1 strúčik zo 4 na 6 porcií = 1,5", qtyEq(Meals.scaleQty(1.0, "strúčik", 6, 4).amount, 1.5))
    check("soft: ½ ČL zo 4 na 1 porciu = ¼ (nie nula)", qtyEq(Meals.scaleQty(0.5, "ČL", 1, 4).amount, 0.25))
    check("soft: 1 PL zo 4 na 10 porcií = 2,5; 2 z 2 na 24 = 24", qtyEq(Meals.scaleQty(1.0, "PL", 10, 4).amount, 2.5) && qtyEq(Meals.scaleQty(2.0, "strúčik", 24, 2).amount, 24.0))
    check("soft: rovnaké porcie nemenia množstvo", qtyEq(Meals.scaleQty(0.5, "ČL", 4, 4).amount, 0.5) && Meals.scaleQty(0.5, "ČL", 4, 4).unit == "ČL")

    val rizoto = recipe("r-rizoto", "Ryža s hubami", 2,
        Meals.Ingredient("Ryža", 150.0, "g"), Meals.Ingredient("Cesnak", 2.0, "strúčik"), Meals.Ingredient("Vňať koriandrová", 1.0, "hrsť"),
        Meals.Ingredient("Olej olivový", 1.0, "ČL"), Meals.Ingredient("Soľ", 0.5, "ČL"))
    val rizotoMeal = meal("2026-11-25", Meals.Slot.LUNCH, "r-rizoto", "Ryža s hubami", portions = 4)
    val sc4 = Meals.scaledIngredients(rizoto, 4)
    check("soft: scaledIngredients pre 4 porcie = 4 strúčiky, 2 hrste, 2 ČL oleja", sc4.any { it.name == "Cesnak" && qtyEq(it.amount, 4.0) && it.unit == "strúčik" } && sc4.any { it.name.startsWith("Vňať") && qtyEq(it.amount, 2.0) && it.unit == "hrsť" } && sc4.any { it.name == "Olej olivový" && qtyEq(it.amount, 2.0) && it.unit == "ČL" })

    val planSoft = Meals.planShopping(wed, wed, listOf(rizotoMeal), listOf(rizoto), emptyList(), emptyList())
    val garlic = planSoft.firstOrNull { it.name == "Cesnak" }
    check("soft: cesnak bez zásoby ide do Overiť s množstvom z receptu", garlic != null && garlic.kind == Meals.NeedKind.ASK && qtyEq(garlic.amount, 4.0) && garlic.unit == "strúčik" && garlic.displayText() == "Cesnak 4 strúčiky")
    check("soft: do zoznamu sa pridá len „Cesnak“", garlic!!.shopText() == "Cesnak")
    check("soft: hrsť koriandra sa pýta, olej a soľ nie (vždy doma)", planSoft.any { it.name.startsWith("Vňať") && it.kind == Meals.NeedKind.ASK } && planSoft.none { it.name.startsWith("Olej") || it.name == "Soľ" })
    check("soft: ryža sa dopočíta normálne", planSoft.any { it.name == "Ryža" && it.kind == Meals.NeedKind.MISSING && qtyEq(it.amount, 300.0) && it.unit == "g" })

    val planHave = Meals.planShopping(wed, wed, listOf(rizotoMeal), listOf(rizoto), listOf(pantry("Cesnak", 1.0, "ks"), pantry("Vňať koriandrová", 1.0, "ks")), emptyList())
    check("soft: cesnak v zásobách (aj 1 ks) stačí, nepýta sa", planHave.none { it.name == "Cesnak" || it.name.startsWith("Vňať") })
    val planEmptyStock = Meals.planShopping(wed, wed, listOf(rizotoMeal), listOf(rizoto), listOf(pantry("Cesnak", 0.0, "ks")), emptyList())
    check("soft: minutý cesnak (0 ks) sa pýta", planEmptyStock.any { it.name == "Cesnak" && it.kind == Meals.NeedKind.ASK })
    val planAlways = Meals.planShopping(wed, wed, listOf(rizotoMeal), listOf(rizoto), listOf(pantry("Cesnak", 0.0, "ks", always = true)), emptyList())
    check("soft: „vždy doma“ cesnak sa nepýta", planAlways.none { it.name == "Cesnak" })
    val planListed = Meals.planShopping(wed, wed, listOf(rizotoMeal), listOf(rizoto), emptyList(), listOf(shop("Cesnak")))
    check("soft: cesnak už na zozname = už v zozname", planListed.any { it.name == "Cesnak" && it.kind == Meals.NeedKind.ALREADY })

    val gulas2 = recipe("r-gulas2", "Guláš", 4, Meals.Ingredient("Cesnak", 1.0, "strúčik"), Meals.Ingredient("Cesnak", 1.0, "hrsť"))
    val gulasMeal = meal("2026-11-26", Meals.Slot.LUNCH, "r-gulas2", "Guláš", portions = 4)
    val twoDays = Meals.planShopping(wed, wed.plusDays(1), listOf(rizotoMeal, gulasMeal), listOf(rizoto, gulas2), emptyList(), emptyList())
    check("soft: cesnak z dvoch receptov je jeden riadok (4 + 1 strúčik), obe jedlá v zdrojoch", twoDays.count { it.name == "Cesnak" } == 1 && twoDays.first { it.name == "Cesnak" }.let { qtyEq(it.amount, 5.0) && it.unit == "strúčik" && it.sources.size == 2 })

    val pantryGarlic = listOf(pantry("Cesnak", 1.0, "ks"), pantry("Soľ", 1.0, "ks"))
    val afterSoft = Meals.deduct(pantryGarlic, rizoto, 4, 99L, "me")
    check("soft: strúčiky sa z cesnaku v kusoch neodpočítavajú", qtyEq(afterSoft.first { it.name == "Cesnak" }.amount, 1.0) && afterSoft.first { it.name == "Cesnak" }.unit == "ks")
    val afterSoft2 = Meals.deduct(listOf(pantry("Cesnak", 10.0, "strúčik")), rizoto, 4, 99L, "me")
    check("soft: zásoba v rovnakej jednotke sa odpočíta (10 − 4 = 6)", qtyEq(afterSoft2.first { it.name == "Cesnak" }.amount, 6.0) && afterSoft2.first { it.name == "Cesnak" }.unit == "strúčik")
    check("soft: covers – je v zásobách / minuté / vždy doma", Meals.covers(pantry("Cesnak", 1.0, "ks"), 2.0, "strúčik") == true && Meals.covers(pantry("Cesnak", 0.0, "ks"), 2.0, "strúčik") == false &&
        Meals.covers(pantry("Cesnak", 0.0, "ks", always = true), 2.0, "strúčik") == true && Meals.covers(pantry("Cesnak", 1.0, "strúčik"), 2.0, "strúčik") == false)

    val knownSoft = Meals.knownIngredients(listOf(recipe("r-k", "K", 2, Meals.Ingredient("Cesnak", 2.0, "strúčik"), Meals.Ingredient("Cesnak", 1.0, "strúčik"))), emptyList(), emptyList())
    check("soft: návrh suroviny pri písaní ponúkne strúčik", knownSoft.any { it.name == "Cesnak" && it.unit == "strúčik" })

    val rtRecipe = Meals.recipeFrom(Meals.recipeObj(rizoto))!!
    check("soft: recept so strúčikmi prežije uloženie a synchronizáciu (JSON)", rtRecipe.ingredients.any { it.name == "Cesnak" && qtyEq(it.amount, 2.0) && it.unit == "strúčik" } && rtRecipe.ingredients.any { it.name == "Soľ" && qtyEq(it.amount, 0.5) && it.unit == "ČL" })
    check("soft: lyžica olivového oleja či korenia je vždy doma, fľaša oleja nie", Meals.isHomeBasic("Olej olivový", "ČL") && Meals.isHomeBasic("Korenie na cestoviny", "PL") && Meals.isHomeBasic("Morská soľ", "hrsť") && !Meals.isHomeBasic("Olej olivový", "ml") && !Meals.isHomeBasic("Med", "PL"))
    println("MealsTest OK")
}

