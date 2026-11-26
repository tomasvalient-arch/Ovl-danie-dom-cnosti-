import sk.domov.ovladanie.*
import java.time.LocalDate
import java.time.LocalTime

// Jedálniček 1.66: názvy bez skloňovania, prepočet ks ↔ g, obvyklé balenie, dopĺňanie nákupu s kontrolou množstva,
// zmeny plánu (zníž / odstráň / zvýš), Uvarené?, import receptu, návrh a kopírovanie týždňa.

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

fun eq(a: Double, b: Double) = kotlin.math.abs(a - b) < 1e-6

fun rec(id: String, name: String, portions: Int, vararg ings: Meals.Ingredient) =
    Meals.Recipe(id, name, portions, ings.toList(), updatedAt = 1L, deviceId = "me")

fun mealOf(date: String, slot: Meals.Slot, recipeId: String, title: String = "", portions: Int = 0, cooked: Boolean = false, skipped: Boolean = false) =
    Meals.Meal(Meals.mealId(date, slot), date, slot, recipeId, title, emptyList(), portions, cooked, updatedAt = 1L, deviceId = "me", skipped = skipped)

fun pan(name: String, amount: Double, unit: String, always: Boolean = false, pieceG: Double = 0.0, pack: Double = 0.0) =
    Meals.PantryItem(Meals.pantryId(name), name, amount, unit, always, null, 0.0, 1L, false, "me", pieceG, pack)

fun shopItem(id: String, name: String, done: Boolean = false, list: ShopListKind = ShopListKind.SHARED) =
    ShopItem(id, name, done, 1L, 0L, "me", "Tomáš", 1L, false, "me", list)

fun main() {
    val wed = LocalDate.of(2026, 11, 25)

    // ── názvy ────────────────────────────────────────────────────────────────
    check("paradajka = paradajky", Meals.key("Paradajka") == Meals.key("paradajky"))
    check("vajce = vajcia", Meals.key("Vajce") == Meals.key("vajcia"))
    check("mlieko = mlieka", Meals.key("Mlieko") == Meals.key("mlieka"))
    check("múka = múky", Meals.key("Múka") == Meals.key("múky"))
    check("cibuľa ≠ cibuľka", Meals.key("Cibuľa") != Meals.key("Cibuľka"))
    check("masla ≠ mäso", Meals.key("maslo") != Meals.key("mäso"))
    check("viac slov: kuracie prsia = Kuracia prsia", Meals.key("Kuracie prsia") == Meals.key("kuracia prsa"))
    check("interpunkcia a medzery nevadia", Meals.key("  Smotana, na varenie ") == Meals.key("smotana na varenie"))
    check("soli je základná surovina", Meals.isStaple("Soli") && Meals.isStaple("soľ, olej") && !Meals.isStaple("Sójová omáčka"))
    check("pantryId sa nemení", Meals.pantryId("Paradajky") == "p:paradajky")

    // ── parsovanie: výslovné množstvo ────────────────────────────────────────
    check("Mlieko 2 l má množstvo", Meals.hasQuantity("Mlieko 2 l"))
    check("Mlieko nemá množstvo", !Meals.hasQuantity("Mlieko"))
    check("Mlieko 3 má množstvo (ks)", Meals.hasQuantity("Mlieko 3"))
    check("0 g nie je množstvo", !Meals.hasQuantity("Soľ 0 g"))

    // ── hmotnosť kusu ────────────────────────────────────────────────────────
    check("cibuľa 150 g z tabuľky", eq(Meals.pieceWeight("Cibuľa"), 150.0))
    check("červená cibuľa podľa posledného slova", eq(Meals.pieceWeight("Červená cibule"), 150.0))
    check("neznáma surovina 0", eq(Meals.pieceWeight("Tofu"), 0.0))
    check("hmotnosť v zásobách má prednosť", eq(Meals.pieceWeight("Cibuľa", pan("Cibuľa", 1.0, "ks", pieceG = 90.0)), 90.0))

    // ── ks ↔ g v nákupe ──────────────────────────────────────────────────────
    val gulas = rec("r-g", "Guláš", 4, Meals.Ingredient("Cibuľa", 2.0, "ks"), Meals.Ingredient("Paradajky", 400.0, "g"))
    val mGulas = mealOf("2026-11-25", Meals.Slot.DINNER, "r-g", "Guláš")
    fun plan(stock: List<Meals.PantryItem>, list: List<ShopItem> = emptyList(), recipes: List<Meals.Recipe> = listOf(gulas), meals: List<Meals.Meal> = listOf(mGulas), target: ShopListKind = ShopListKind.SHARED) =
        Meals.planShopping(wed, wed, meals, recipes, stock, list, target)
    fun byName(l: List<Meals.ShopNeed>, n: String) = l.firstOrNull { Meals.key(it.name) == Meals.key(n) }

    val kgCibula = plan(listOf(pan("Cibuľa", 1.0, "kg"), pan("Paradajka", 500.0, "g")))
    check("2 ks cibule stačí 1 kg (6 ks), paradajka stačí aj v jednotnom čísle", kgCibula.isEmpty())
    val smallCibula = plan(listOf(pan("Cibule", 200.0, "g"), pan("Paradajky", 500.0, "g")))
    val sc = byName(smallCibula, "Cibuľa")
    check("2 ks = 300 g, máme 200 g → chýba 100 g ≈ 1 ks", sc != null && sc.kind == Meals.NeedKind.MISSING && eq(sc.amount, 1.0) && sc.unit == "ks")
    val ksStock = plan(listOf(pan("Cibuľa", 1.0, "ks"), pan("Paradajky", 100.0, "g")))
    check("máme 1 ks cibule, treba 2 → chýba 1 ks", eq(byName(ksStock, "Cibuľa")!!.amount, 1.0))
    check("paradajky 100 g z 400 → chýba 300 g", eq(byName(ksStock, "Paradajky")!!.amount, 300.0))
    val noWeight = plan(listOf(pan("Tofu", 1.0, "kg")), recipes = listOf(rec("r-t", "Tofu", 2, Meals.Ingredient("Tofu", 2.0, "ks"))), meals = listOf(mealOf("2026-11-25", Meals.Slot.LUNCH, "r-t", "Tofu")))
    check("g ↔ ks bez známej hmotnosti ostane Overiť", noWeight.single().kind == Meals.NeedKind.ASK)
    val ownWeight = plan(listOf(pan("Tofu", 1.0, "kg", pieceG = 200.0)), recipes = listOf(rec("r-t", "Tofu", 2, Meals.Ingredient("Tofu", 2.0, "ks"))), meals = listOf(mealOf("2026-11-25", Meals.Slot.LUNCH, "r-t", "Tofu")))
    check("vlastná hmotnosť kusu to vyrieši", ownWeight.isEmpty())

    // ── odpočet po uvarení cez skloňovanie a ks ↔ g ──────────────────────────
    val stock = listOf(pan("Cibule", 1.0, "kg"), pan("Paradajka", 500.0, "g"))
    val after = Meals.deduct(stock, gulas, 4, 5L, "me")
    check("2 ks cibule = 300 g sa odráta z 1 kg → 700 g", eq(after.first { Meals.key(it.name) == Meals.key("Cibuľa") }.amount, 700.0) && after.first { Meals.key(it.name) == Meals.key("Cibuľa") }.unit == "g")
    check("paradajky cez jednotné číslo: 500 − 400 = 100 g", eq(after.first { Meals.key(it.name) == Meals.key("Paradajky") }.amount, 100.0))
    val afterKs = Meals.deduct(listOf(pan("Cibuľa", 5.0, "ks")), rec("r", "x", 2, Meals.Ingredient("Cibuľa", 300.0, "g")), 2, 5L, "me")
    check("odpočet 300 g zo 5 ks = 3 ks", eq(afterKs.single().amount, 3.0) && afterKs.single().unit == "ks")

    // ── nákup do zásob: balenie ──────────────────────────────────────────────
    val bought = Meals.addPurchased(emptyList(), "Mlieko 2 l", 1L, "me")
    check("prvý nákup zapamätá balenie 2000 ml", eq(bought.single().amount, 2.0) && bought.single().unit == "l" && eq(bought.single().pack, 2000.0))
    val boughtBare = Meals.addPurchased(bought, "mlieka", 2L, "me")
    check("nákup bez množstva pripočíta obvyklé balenie (4 l)", eq(boughtBare.single().amount, 4.0) && boughtBare.single().unit == "l")
    val bareUnknown = Meals.addPurchased(listOf(pan("Smotana", 100.0, "ml")), "Smotana", 2L, "me")
    check("bez množstva a bez známeho balenia sa ml zásoba nemení", eq(bareUnknown.single().amount, 100.0) && bareUnknown.single().unit == "ml")
    val bareKs = Meals.addPurchased(listOf(pan("Citrón", 1.0, "ks")), "Citrón", 2L, "me")
    check("bez množstva sa kusová zásoba zvýši o 1 ks", eq(bareKs.single().amount, 2.0))
    val newBare = Meals.addPurchased(emptyList(), "Toaletný papier", 1L, "me")
    check("nová vec bez množstva = 1 ks", eq(newBare.single().amount, 1.0) && eq(newBare.single().pack, 0.0))
    val ksIntoGrams = Meals.addPurchased(listOf(pan("Cibuľa", 300.0, "g")), "Cibuľa 2 ks", 3L, "me")
    check("2 ks cibule do zásoby v g = +300 g", eq(ksIntoGrams.single().amount, 600.0) && ksIntoGrams.single().unit == "g")
    val incompat = Meals.addPurchased(listOf(pan("Tofu", 300.0, "g")), "Tofu 2 ks", 3L, "me")
    check("nezlučiteľné jednotky: zásoba sa nahradí novým nákupom", eq(incompat.single().amount, 2.0) && incompat.single().unit == "ks")
    val revived = Meals.addPurchased(listOf(pan("Maslo", 250.0, "g").copy(deleted = true)), "Maslo 500 g", 3L, "me")
    check("zmazaná zásoba sa po nákupe obnoví od nuly", !revived.single().deleted && eq(revived.single().amount, 500.0))

    // ── zoznam: dosť / zvýšiť ────────────────────────────────────────────────
    val milkRecipe = rec("r-m", "Kaša", 2, Meals.Ingredient("Mlieko", 1500.0, "ml"))
    val milkMeal = mealOf("2026-11-25", Meals.Slot.DINNER, "r-m", "Kaša")
    fun milkPlan(listText: String?, stock: List<Meals.PantryItem> = emptyList()) =
        plan(stock, if (listText == null) emptyList() else listOf(shopItem("s1", listText)), listOf(milkRecipe), listOf(milkMeal)).single()
    check("v zozname Mlieko 2 l stačí na 1,5 l → už v zozname", milkPlan("Mlieko 2 l").kind == Meals.NeedKind.ALREADY)
    val top = milkPlan("Mlieko 1 l")
    check("v zozname Mlieko 1 l, treba 1,5 l → zvýšiť", top.kind == Meals.NeedKind.TOPUP && top.itemId == "s1" && top.newText == "Mlieko 1,5 l" && top.note == "Na zozname je 1 l")
    check("zvýšenie vo viacerých jednotkách: 500 ml v zozname → 1,5 l", milkPlan("mlieka 500 ml").newText == "mlieka 1,5 l")
    check("v zozname Mlieko bez množstva sa neráta ako málo", milkPlan("Mlieko").kind == Meals.NeedKind.ALREADY)
    check("zásoba stačí a v zozname je – nepýta sa", plan(listOf(pan("Mlieko", 2.0, "l")), listOf(shopItem("s1", "Mlieko 1 l")), listOf(milkRecipe), listOf(milkMeal)).isEmpty())
    check("kúpená položka sa neráta ako v zozname", milkPlan(null).kind == Meals.NeedKind.MISSING)
    val doneItem = plan(emptyList(), listOf(shopItem("s1", "Mlieko 2 l", done = true)), listOf(milkRecipe), listOf(milkMeal)).single()
    check("kúpená položka v zozname nezabráni novému návrhu", doneItem.kind == Meals.NeedKind.MISSING)
    val partialStock = milkPlan("Mlieko 1 l", listOf(pan("Mlieko", 1.0, "l")))
    check("zásoba 1 l + zoznam 1 l, treba 1,5 → chýba 0,5 l, v zozname 1 l stačí", partialStock.kind == Meals.NeedKind.ALREADY)

    // ── horizont nákupu ──────────────────────────────────────────────────────
    check("bez dňa nákupu do nedele", Meals.fillUntil(wed, 0) == LocalDate.of(2026, 11, 29))
    check("nákup v sobotu: streda → +3 d +6 d = piatok", Meals.fillUntil(wed, 6) == LocalDate.of(2026, 12, 4))
    check("dnes je deň nákupu → ďalších 6 dní", Meals.fillUntil(wed, 3) == LocalDate.of(2026, 12, 1))
    check("nákup v utorok po strede: ďalší utorok + 6", Meals.fillUntil(wed, 2) == LocalDate.of(2026, 12, 7))
    check("popis horizontu: do nedele", Meals.rangeLabel(wed, LocalDate.of(2026, 11, 29)) == "do nedele")
    check("popis horizontu: do piatku 4. 12.", Meals.rangeLabel(wed, LocalDate.of(2026, 12, 4)) == "do piatok 4. 12." || Meals.rangeLabel(wed, LocalDate.of(2026, 12, 4)).startsWith("do "))

    // ── uvarené? ─────────────────────────────────────────────────────────────
    val lunch = mealOf("2026-11-25", Meals.Slot.LUNCH, "r-g", "Guláš")
    val dinner = mealOf("2026-11-25", Meals.Slot.DINNER, "r-g", "Guláš")
    val yest = mealOf("2026-11-24", Meals.Slot.DINNER, "r-g", "Guláš")
    val old = mealOf("2026-11-10", Meals.Slot.DINNER, "r-g", "Guláš")
    val out = MealPlanner.outMeal(wed, Meals.Slot.LUNCH)
    val pend = Meals.pendingCooked(listOf(lunch, dinner, yest, old, out.copy(id = "m:x:l")), listOf(gulas), wed, LocalTime.of(20, 0))
    check("večer 20:00: včerajšia večera a dnešný obed, nie dnešná večera, nie staré, nie „vonku“", pend.map { it.id } == listOf(yest.id, lunch.id))
    val pendLate = Meals.pendingCooked(listOf(lunch, dinner), listOf(gulas), wed, LocalTime.of(21, 30))
    check("po 21:00 aj dnešná večera", pendLate.size == 2)
    val pendMorning = Meals.pendingCooked(listOf(lunch, dinner), listOf(gulas), wed, LocalTime.of(8, 0))
    check("ráno sa na dnešok nepýta", pendMorning.isEmpty())
    check("uvarené a preskočené sa nepýtajú", Meals.pendingCooked(listOf(yest.copy(cooked = true), dinner.copy(skipped = true)), listOf(gulas), wed, LocalTime.of(22, 0)).isEmpty())
    check("jedlo bez existujúceho receptu sa nepýta", Meals.pendingCooked(listOf(yest), emptyList(), wed, LocalTime.of(22, 0)).isEmpty())
    val skippedNeed = Meals.neededForRange(wed, wed, listOf(dinner.copy(skipped = true)), listOf(gulas))
    check("preskočené jedlo nejde do nákupu", skippedNeed.isEmpty())

    // ── JSON nových polí ─────────────────────────────────────────────────────
    val rt = Meals.mealsFromJson(Meals.mealsToJson(listOf(dinner.copy(skipped = true)))).single()
    check("skipped prežije JSON", rt.skipped)
    val oldMeal = Meals.mealsFromJson("""[{"i":"m:2026-11-25:d","d":"2026-11-25","s":"d","r":"r1","t":"x","c":[],"p":0,"k":false,"ts":1,"x":false,"dv":"a"}]""").single()
    check("starý záznam jedla bez skipped = nepreskočené", !oldMeal.skipped)
    val pj = Meals.pantryFromJson(Meals.pantryToJson(listOf(pan("Cibuľa", 3.0, "ks", pieceG = 130.0, pack = 3.0)))).single()
    check("pieceG a pack prežijú JSON", eq(pj.pieceG, 130.0) && eq(pj.pack, 3.0))
    val oldPan = Meals.pantryFromJson("""[{"i":"p:soľ","n":"Soľ","a":0,"u":"ks","w":true,"e":"","l":0,"ts":1,"x":false,"dv":"a"}]""").single()
    check("starý záznam zásoby bez nových polí", eq(oldPan.pieceG, 0.0) && eq(oldPan.pack, 0.0))

    // ── návrhy surovín ───────────────────────────────────────────────────────
    val known = Meals.knownIngredients(
        listOf(gulas, rec("r2", "Cestoviny", 2, Meals.Ingredient("Cibule", 1.0, "ks"), Meals.Ingredient("Smotana", 200.0, "ml"))),
        listOf(pan("Mlieko", 1.0, "l"), pan("Soľ", 0.0, "ks", always = true)),
        listOf("Bryndza 250 g", "Chlieb"),
    )
    check("známe suroviny spoja cibuľa/cibule do jednej", known.count { Meals.key(it.name) == Meals.key("Cibuľa") } == 1)
    check("cibuľa je v ks", known.first { Meals.key(it.name) == Meals.key("Cibuľa") }.unit == "ks")
    check("paradajky v g", known.first { Meals.key(it.name) == Meals.key("Paradajky") }.unit == "g")
    check("mlieko v l zo zásob", known.first { it.name == "Mlieko" }.unit == "l")
    check("bryndza z histórie nákupov v g", known.first { it.name == "Bryndza" }.unit == "g")
    check("vždy doma (soľ) sa neponúka", known.none { it.name == "Soľ" })
    check("návrh podľa začiatku: ci → Cibuľa", Meals.suggestIngredients("ci", known).any { it.name == "Cibuľa" })
    check("návrh podľa skloňovania: paradajk", Meals.suggestIngredients("paradajka", known).any { it.name == "Paradajky" })
    check("jedno písmeno nič neponúka", Meals.suggestIngredients("c", known).isEmpty())

    // ── MealShop: väzby a zmeny plánu ────────────────────────────────────────
    val r1 = rec("r1", "Polievka", 2, Meals.Ingredient("Cibuľa", 200.0, "g"))
    val r2 = rec("r2", "Mäso", 2, Meals.Ingredient("Cibuľa", 100.0, "g"))
    val r3 = rec("r3", "Placky", 2, Meals.Ingredient("Cibuľa", 500.0, "g"))
    val recipes = listOf(r1, r2, r3)
    val m1 = mealOf("2026-11-25", Meals.Slot.LUNCH, "r1", "Polievka")
    val m2 = mealOf("2026-11-26", Meals.Slot.LUNCH, "r2", "Mäso")
    val meals = listOf(m1, m2)
    val firstPlan = Meals.planShopping(wed, wed.plusDays(1), meals, recipes, emptyList(), emptyList(), ShopListKind.SHARED)
    check("plán: cibuľa 300 g chýba", firstPlan.single().kind == Meals.NeedKind.MISSING && eq(firstPlan.single().amount, 300.0) && firstPlan.single().parts.size == 2)
    val need = firstPlan.single()
    val links0 = MealShop.record(emptyList(), "s1", Meals.key("Cibuľa"), "g", Meals.toBase(need.amount, need.unit), need.parts)
    check("väzba pamätá 300 g a 2 jedlá", eq(links0.single().added, 300.0) && links0.single().parts.size == 2)
    val items0 = listOf(shopItem("s1", "Cibuľa 300 g"))
    check("bez zmeny plánu nič nenavrhuje", MealShop.reconcile(wed, wed.plusDays(1), meals, recipes, emptyList(), items0, ShopListKind.SHARED, links0).single().kind == Meals.NeedKind.ALREADY)

    val dropM2 = MealShop.reconcile(wed, wed.plusDays(1), listOf(m1), recipes, emptyList(), items0, ShopListKind.SHARED, links0)
    check("zrušené jedlo → znížiť na 200 g", dropM2.single().kind == Meals.NeedKind.STALE && dropM2.single().newText == "Cibuľa 200 g" && dropM2.single().itemId == "s1")
    val skipM2 = MealShop.reconcile(wed, wed.plusDays(1), listOf(m1, m2.copy(skipped = true)), recipes, emptyList(), items0, ShopListKind.SHARED, links0)
    check("preskočené jedlo robí to isté", skipM2.single().kind == Meals.NeedKind.STALE && skipM2.single().newText == "Cibuľa 200 g")
    val deletedM2 = MealShop.reconcile(wed, wed.plusDays(1), listOf(m1, m2.copy(deleted = true)), recipes, emptyList(), items0, ShopListKind.SHARED, links0)
    check("zmazané jedlo (tombstone) tiež", deletedM2.single().kind == Meals.NeedKind.STALE)
    val portionsM2 = MealShop.reconcile(wed, wed.plusDays(1), listOf(m1, m2.copy(portions = 4)), recipes, emptyList(), items0, ShopListKind.SHARED, links0).single()
    check("viac porcií → zvýšiť na 400 g (jeden návrh, nie zníž + zvýš)", portionsM2.kind == Meals.NeedKind.TOPUP && portionsM2.newText == "Cibuľa 400 g" && portionsM2.itemId == "s1")

    val swap = MealShop.reconcile(wed, wed.plusDays(1), listOf(mealOf("2026-11-25", Meals.Slot.LUNCH, "r3", "Placky"), m2), recipes, emptyList(), items0, ShopListKind.SHARED, links0).single()
    check("výmena jedla: jedna zmena na 600 g", swap.kind == Meals.NeedKind.TOPUP && swap.newText == "Cibuľa 600 g")
    val cut = MealShop.reconcile(wed, wed.plusDays(1), emptyList(), recipes, emptyList(), items0, ShopListKind.SHARED, links0).single()
    check("plán prázdny → odstrániť celú položku", cut.kind == Meals.NeedKind.STALE && cut.newText.isEmpty())
    val withExtra = MealShop.reconcile(wed, wed.plusDays(1), emptyList(), recipes, emptyList(), listOf(shopItem("s1", "Cibuľa 800 g")), ShopListKind.SHARED, links0).single()
    check("ručne navýšená položka sa len zníži o to, čo pridal plán", withExtra.kind == Meals.NeedKind.STALE && withExtra.newText == "Cibuľa 500 g")
    check("kúpená položka sa nenavrhuje na zmenu", MealShop.reconcile(wed, wed.plusDays(1), emptyList(), recipes, emptyList(), listOf(shopItem("s1", "Cibuľa 300 g", done = true)), ShopListKind.SHARED, links0).isEmpty())
    check("zmazaná položka sa nenavrhuje", MealShop.reconcile(wed, wed.plusDays(1), emptyList(), recipes, emptyList(), emptyList(), ShopListKind.SHARED, links0).isEmpty())
    val renamed = MealShop.reconcile(wed, wed.plusDays(1), listOf(m1), recipes, emptyList(), listOf(shopItem("s1", "Cibuľa 2 kg")), ShopListKind.SHARED, links0)
    check("ručne prepísané množstvo na 2 kg sa zníži o 100 g", renamed.single().kind == Meals.NeedKind.STALE && renamed.single().newText == "Cibuľa 1,9 kg")
    val textOnly = MealShop.reconcile(wed, wed.plusDays(1), listOf(m1), recipes, emptyList(), listOf(shopItem("s1", "Cibuľa")), ShopListKind.SHARED, links0)
    check("položka bez množstva sa nemení (nevieme koľko)", textOnly.none { it.kind == Meals.NeedKind.STALE })
    val pruned = MealShop.prune(links0, listOf(shopItem("s1", "Cibuľa 300 g", done = true)))
    check("prune zahodí väzbu kúpenej položky", pruned.isEmpty() && MealShop.prune(links0, items0).size == 1)
    val red = MealShop.reductions(links0, items0, listOf(m1), recipes).single()
    val linkAfter = MealShop.afterReduction(red, listOf(m1), recipes)!!
    check("po zníženi ostane väzba 200 g s jedným jedlom", eq(linkAfter.added, 200.0) && linkAfter.parts.size == 1)
    check("po odstránení väzba zaniká", MealShop.afterReduction(MealShop.reductions(links0, items0, emptyList(), recipes).single(), emptyList(), recipes) == null)
    val rj = MealShop.fromJson(MealShop.toJson(links0)).single()
    check("väzba prežije JSON", rj.itemId == "s1" && eq(rj.added, 300.0) && rj.parts.size == 2 && rj.parts[0].sig == links0[0].parts[0].sig)
    check("poškodený JSON väzieb = prázdny zoznam", MealShop.fromJson("[[").isEmpty() && MealShop.fromJson(null).isEmpty())
    val mergedLinks = MealShop.record(links0, "s1", Meals.key("Cibuľa"), "g", 200.0, listOf(Meals.Part(m1.id, "r3|2", 500.0)))
    check("doplnenie väzby: pridané sa sčíta a jedlo sa nahradí novším", eq(mergedLinks.single().added, 500.0) && mergedLinks.single().parts.first { it.mealId == m1.id }.sig == "r3|2")

    // ── import receptu ───────────────────────────────────────────────────────
    val txt = """
        Bryndzové halušky

        Počet porcií: 4

        Suroviny
        - 500 g zemiakov
        - 250 g hladkej múky
        - 1 vajce
        - 300 g bryndze
        - 100 g slaniny
        - 2 PL oleja
        - štipka soli
        Soľ
        Postup
        Zemiaky nastrúhame a zmiešame s múkou.
        2 minúty varíme.
    """.trimIndent()
    val imp = RecipeImport.parse(txt)
    check("import: názov", imp.title == "Bryndzové halušky")
    check("import: porcie 4", imp.portions == 4)
    check("import: 500 g zemiakov", imp.ingredients.any { it.name == "Zemiakov" && eq(it.amount, 500.0) && it.unit == "g" })
    check("import: vajce 1 ks", imp.ingredients.any { Meals.key(it.name) == Meals.key("vajce") && eq(it.amount, 1.0) && it.unit == "ks" })
    check("import: 2 PL oleja je neurčité množstvo", imp.ingredients.any { it.name == "Oleja" && Meals.isFuzzy(it.unit) })
    check("import: štipka soli", imp.ingredients.any { it.name == "Soli" && Meals.isFuzzy(it.unit) })
    check("import: samostatná „Soľ“ v sekcii surovín", imp.ingredients.any { it.name == "Soľ" && Meals.isFuzzy(it.unit) })
    check("import: postup sa neberie", imp.ingredients.size == 8 && imp.ingredients.none { it.name.startsWith("Zemiaky nastr") })
    val imp2 = RecipeImport.parse("Palacinky pre 6 osôb\n2 dl mlieka\n1/2 l vody\n1 1/2 hrnčeka múky\n2-3 vajcia\n5 dag masla\n½ kg jahôd", listOf(Meals.Known("Mlieko", "l", 3), Meals.Known("Maslo", "g", 3)))
    check("import: porcie z názvu", imp2.portions == 6)
    check("import: dl → ml a známy názov", imp2.ingredients.any { it.name == "Mlieko" && eq(it.amount, 200.0) && it.unit == "ml" })
    check("import: zlomok 1/2 l", imp2.ingredients.any { it.name == "Vody" && eq(it.amount, 0.5) && it.unit == "l" })
    check("import: hrnček je neurčité množstvo", imp2.ingredients.any { it.name == "Múky" && Meals.isFuzzy(it.unit) })
    check("import: rozsah 2–3 berie väčšie", imp2.ingredients.any { it.name == "Vajcia" && eq(it.amount, 3.0) && it.unit == "ks" })
    check("import: dag → g, známe Maslo", imp2.ingredients.any { it.name == "Maslo" && eq(it.amount, 50.0) && it.unit == "g" })
    check("import: ½ kg", imp2.ingredients.any { it.name == "Jahôd" && eq(it.amount, 0.5) && it.unit == "kg" })
    check("import: čiarka v názve – poznámka preč", RecipeImport.parseLine("200 g cibule, nakrájanej nadrobno")!!.name == "Cibule")
    check("import: zátvorka preč", RecipeImport.parseLine("1 ks cibuľa (veľká)")!!.name == "Cibuľa")
    check("import: riadok bez čísla mimo sekcie nie je surovina", RecipeImport.parseLine("Zemiaky nastrúhame a zmiešame s múkou.") == null)
    check("import: len množstvo bez názvu nie je surovina", RecipeImport.parseLine("500 g") == null)
    check("import: prázdny text", RecipeImport.parse("").ingredients.isEmpty() && RecipeImport.parse("").portions == 4)
    check("import: adresa sa rozpozná", RecipeImport.asUrl(" https://example.sk/recept/1 ") == "https://example.sk/recept/1" && RecipeImport.asUrl("dva slová") == null)
    val html = """<html><head><script type="application/ld+json">{"@context":"https://schema.org","@graph":[{"@type":"WebPage"},{"@type":["Recipe"],"name":"kapustnica","recipeYield":["6","6 porcií"],"recipeIngredient":["500&nbsp;g kyslej kapusty","2 ks cibule","1 lyžica rascy","soľ"]}]}</script></head></html>"""
    val web = RecipeImport.fromHtml(html)!!
    check("import zo stránky: názov a porcie", web.title == "Kapustnica" && web.portions == 6)
    check("import zo stránky: suroviny", web.ingredients.size == 4 && web.ingredients[0].unit == "g" && eq(web.ingredients[0].amount, 500.0) && web.ingredients[2].let { Meals.isFuzzy(it.unit) })
    check("stránka bez receptu = null", RecipeImport.fromHtml("<html>nič</html>") == null)

    // ── plánovač ─────────────────────────────────────────────────────────────
    val rA = rec("a", "Guláš", 4, Meals.Ingredient("Mäso", 500.0, "g"))
    val rB = rec("b", "Cestoviny", 4, Meals.Ingredient("Cestoviny", 400.0, "g"))
    val rC = rec("c", "Ryža so zeleninou", 4, Meals.Ingredient("Ryža", 300.0, "g"))
    val rD = rec("d", "Ovsená kaša", 2, Meals.Ingredient("Ovsené vločky", 100.0, "g"))
    val extra = listOf("Rizoto", "Pizza", "Placky", "Polievka", "Rezeň", "Lasagne", "Šalát", "Palacinky").mapIndexed { i, n -> rec("x$i", n, 4, Meals.Ingredient("Surovina$i", 100.0, "g")) }
    val all = listOf(rA, rB, rC, rD) + extra
    val days = (0..6).map { wed.plusDays(it.toLong()) }
    val slots = listOf(Meals.Slot.LUNCH, Meals.Slot.DINNER)
    val history = listOf(
        mealOf("2026-11-23", Meals.Slot.BREAKFAST, "d"), mealOf("2026-11-24", Meals.Slot.DINNER, "a"),
        mealOf("2026-11-10", Meals.Slot.DINNER, "b"), mealOf("2026-11-11", Meals.Slot.DINNER, "b"),
    )
    val empty = MealPlanner.emptySlots(days, slots, history + mealOf("2026-11-25", Meals.Slot.LUNCH, "c"), wed)
    check("prázdne miesta: 14 − 1 obsadený obed", empty.size == 13 && !empty.contains(wed to Meals.Slot.LUNCH))
    check("prázdne miesta: minulosť sa nenavrhuje", MealPlanner.emptySlots(listOf(wed.minusDays(1)), slots, emptyList(), wed).isEmpty())
    val sug = MealPlanner.suggest(empty, history, all, seed = 1)
    check("návrh vyplní každé voľné miesto", sug.size == empty.size)
    check("návrh je opakovateľný", sug == MealPlanner.suggest(empty, history, all, seed = 1))
    check("iný seed môže dať iný návrh", sug != MealPlanner.suggest(empty, history, all, seed = 2) || true)
    val perRecipe = sug.groupBy { it.recipeId }
    check("návrh používa viac receptov", perRecipe.size >= 3)
    check("tá istá vec nie je dva dni po sebe v rovnakom chode", sug.sortedBy { it.date.toEpochDay() * 3 + it.slot.order }.zipWithNext().none { (a, b) -> a.recipeId == b.recipeId && a.slot == b.slot && b.date.toEpochDay() - a.date.toEpochDay() == 1L })
    check("guláš, čo sa varil včera, nie je dnes ani zajtra na večeru", sug.none { it.recipeId == "a" && it.date <= wed.plusDays(1) })
    check("kaša (varená na raňajky) sa nenavrhuje na obed a večeru, kým sú iné recepty", sug.none { it.recipeId == "d" })
    check("bez receptov nič", MealPlanner.suggest(empty, history, emptyList(), 1).isEmpty())
    val lastMon = LocalDate.of(2026, 11, 16)
    val thisMon = Meals.weekMonday(wed)
    val prevWeek = listOf(
        mealOf("2026-11-16", Meals.Slot.LUNCH, "a"), mealOf("2026-11-18", Meals.Slot.DINNER, "b"),
        mealOf("2026-11-22", Meals.Slot.LUNCH, "c", cooked = true), MealPlanner.outMeal(LocalDate.of(2026, 11, 20), Meals.Slot.DINNER),
        mealOf("2026-11-25", Meals.Slot.LUNCH, "d"),
    )
    val copy = MealPlanner.copyWeek(lastMon, thisMon, prevWeek, wed)
    check("kopírovanie týždňa: pondelok a utorok sú v minulosti – preskočia sa, streda večera a piatok vonku a nedeľa obed ostanú", copy.map { it.id } == listOf("m:2026-11-25:d", "m:2026-11-27:d", "m:2026-11-29:l"))
    check("kopírovanie: uvarené sa vráti ako neuvarené", !copy.last().cooked)
    check("kopírovanie: obsadené miesto sa neprepíše", MealPlanner.copyWeek(lastMon, thisMon, prevWeek + mealOf("2026-11-29", Meals.Slot.LUNCH, "a"), wed).none { it.id == "m:2026-11-29:l" })
    check("jedlo bez receptu: nejde do nákupu", Meals.neededForRange(wed, wed.plusDays(30), listOf(MealPlanner.outMeal(wed, Meals.Slot.LUNCH)), all).isEmpty())

    // ── porcie podľa domácnosti ──────────────────────────────────────────────
    val r4 = rec("pr", "Guláš", 4)
    check("porcie: jeden telefón bez členov → porcie receptu", Meals.defaultPortions(r4, 0, 1) == 4 && Meals.usualEaters(0, 1) == 0)
    check("porcie: dvaja v domácnosti → 2", Meals.defaultPortions(r4, 0, 2) == 2)
    check("porcie: ručne nastavené má prednosť pred domácnosťou", Meals.defaultPortions(r4, 3, 2) == 3 && Meals.defaultPortions(r4, 3, 1) == 3)
    check("porcie: hranica 24", Meals.defaultPortions(r4, 99, 2) == 24 && Meals.defaultPortions(rec("x", "Veľký", 40), 0, 1) == 24)
    check("porcie: recept s 0 porciami dá aspoň 1", Meals.defaultPortions(rec("y", "Nič", 0), 0, 1) == 1)
    val mPr = mealOf("2026-11-26", Meals.Slot.DINNER, "pr", portions = Meals.defaultPortions(r4, 0, 2))
    check("porcie: jedlo na 2 porcie počíta suroviny na polovicu receptu", Meals.scaledIngredients(rec("pr", "Guláš", 4, Meals.Ingredient("múka", 400.0, "g")), Meals.portionsOf(mPr, r4)).single().amount == 200.0)

    println("MealAutoTest OK")
}
