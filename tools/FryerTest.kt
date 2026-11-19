import sk.domov.ovladanie.Fryer
import sk.domov.ovladanie.FryerRecipe
import sk.domov.ovladanie.FryerRecipes

private var failed = 0
private fun check(name: String, ok: Boolean) {
    if (!ok) { failed++; println("FAIL: $name") }
}

fun main() {
    // hodnoty z reálnej fritézy BH-AF2 (vypnutá): 1 = hlavný vypínač (v appke skrytý), 2 štart/stop, 3 režim, 5 stav, 6 teplota, 9 čas
    val v = mapOf<String, Any>("1" to false, "2" to false, "3" to "Chips", "5" to "standby", "6" to 200, "9" to 0, "10" to 1, "12" to 0)
    val spec = Fryer.detect(v)!!
    check("power dp 1", spec.power == "1")
    check("handled", spec.handled == setOf("1", "2", "3", "5", "6", "9", "10"))
    val st = Fryer.read(spec, v)
    check("read", !st.running && st.mode == "Chips" && st.statusRaw == "standby" && st.tempC == 200 && st.minutes == 0 && st.remainingMin == 1)
    check("summary off", Fryer.summary(spec, v) == "Vypnuté")
    check("summary standby", Fryer.summary(spec, v + ("1" to true)) == "Zapnuté · Pohotovosť")
    check("summary running", Fryer.summary(spec, v + ("1" to true) + ("2" to true) + ("5" to "cooking") + ("10" to 12)) == "Varí · zostáva 12 min")
    check("status unknown", Fryer.statusLabel("preheat") == "preheat")
    check("temp 5-step", Fryer.temperature(spec, 183f) == mapOf<String, Any>("6" to 185))
    check("temp clamp low", Fryer.temperature(spec, 20f) == mapOf<String, Any>("6" to 80))
    check("temp clamp high", Fryer.temperature(spec, 260f) == mapOf<String, Any>("6" to 200))
    check("time", Fryer.cookTime(spec, 14.6f) == mapOf<String, Any>("9" to 15))
    check("time clamp", Fryer.cookTime(spec, 0f) == mapOf<String, Any>("9" to 1) && Fryer.cookTime(spec, 99f) == mapOf<String, Any>("9" to 60))
    check("start", Fryer.start(spec, true) == mapOf<String, Any>("2" to true))
    check("mode", Fryer.mode(spec, "Cake") == mapOf<String, Any>("3" to "Cake"))
    // bez hodnoty 1 (power) a bez 10 (zostáva)
    val s2 = Fryer.detect(v - "1" - "10")!!
    check("no power", s2.power == null && s2.remaining == null)
    check("summary without power dp", Fryer.summary(s2, v - "1") == "Zapnuté · Pohotovosť")
    // nie je fritéza
    check("not fryer: light", Fryer.detect(mapOf("20" to true, "21" to "white", "22" to 500)) == null)
    check("not fryer: dp2 not bool", Fryer.detect(v + ("2" to "x")) == null)
    check("not fryer: missing temp", Fryer.detect(v - "6") == null)
    // ---- vlastné recepty
    val cur = Fryer.read(spec, v + ("6" to 183) + ("9" to 18))
    val r1 = FryerRecipes.fromState("a", "  Hranolky   domáce ", cur)
    check("recipe name cleaned", r1.name == "Hranolky domáce")
    check("recipe from state snaps temp", r1.tempC == 185 && r1.minutes == 18 && r1.mode == "Chips")
    check("recipe time 0 -> 1", FryerRecipes.fromState("a", "x", Fryer.read(spec, v + ("9" to 0))).minutes == 1)
    check("recipe temp clamp", FryerRecipes.fromState("a", "x", Fryer.read(spec, v + ("6" to 250))).tempC == 200)
    check("name max", FryerRecipes.cleanName("x".repeat(80)).length == FryerRecipes.NAME_MAX)
    check("describe", FryerRecipes.describe(r1) == "Chips · 18 min · 185 °C")
    check("describe no mode", FryerRecipes.describe(r1.copy(mode = "")) == "18 min · 185 °C")
    val l1 = FryerRecipes.upsert(emptyList(), r1)
    check("upsert add", l1 == listOf(r1))
    val r2 = FryerRecipes.fromState("b", "hranolky DOMÁCE", Fryer.read(spec, v + ("6" to 200) + ("9" to 20)))
    val l2 = FryerRecipes.upsert(l1, r2)
    check("upsert same name overwrites (keeps id and place)", l2.size == 1 && l2[0].id == "a" && l2[0].minutes == 20 && l2[0].tempC == 200)
    val l3 = FryerRecipes.upsert(l2, FryerRecipes.fromState("c", "Kura", cur))
    check("upsert second", l3.size == 2 && l3[1].name == "Kura")
    check("find ignores case", FryerRecipes.find(l3, "KURA")?.id == "c" && FryerRecipes.find(l3, "  ") == null)
    check("rename ok", FryerRecipes.rename(l3, "c", "Kuracie")[1].name == "Kuracie")
    check("rename to clash ignored", FryerRecipes.rename(l3, "c", "hranolky domáce") == l3)
    check("rename to empty ignored", FryerRecipes.rename(l3, "c", "  ") == l3)
    check("rename to itself differing case", FryerRecipes.rename(l3, "c", "KURA")[1].name == "KURA")
    check("remove", FryerRecipes.remove(l3, "a").map { it.id } == listOf("c"))
    val full = (1..FryerRecipes.MAX_RECIPES).fold(emptyList<FryerRecipe>()) { acc, i -> FryerRecipes.upsert(acc, FryerRecipes.fromState("id$i", "R$i", cur)) }
    check("full list size", full.size == FryerRecipes.MAX_RECIPES)
    check("full list rejects new", FryerRecipes.upsert(full, FryerRecipes.fromState("zz", "Nový", cur)) == full && !FryerRecipes.canAdd(full, "Nový"))
    check("full list allows overwrite", FryerRecipes.canAdd(full, "r3"))
    check("overwrite keeps name and id", FryerRecipes.overwrite(l3, "c", Fryer.read(spec, v + ("6" to 150) + ("9" to 5)))[1].let { it.id == "c" && it.name == "Kura" && it.minutes == 5 && it.tempC == 150 })
    // použitie receptu na fritéze: len režim, čas, teplota – nikdy štart (2) ani hlavný vypínač (1)
    val now = Fryer.read(spec, v + ("6" to 150) + ("9" to 5))
    val steps = FryerRecipes.steps(spec, r1.copy(mode = "Cake"), now)
    check("steps order mode,time,temp", steps == listOf(mapOf<String, Any>("3" to "Cake"), mapOf<String, Any>("9" to 18), mapOf<String, Any>("6" to 185)))
    check("steps never start/power", steps.none { it.containsKey("2") || it.containsKey("1") })
    check("steps skip equal values", FryerRecipes.steps(spec, r1.copy(mode = "Chips", minutes = 5), now) == listOf(mapOf<String, Any>("6" to 185)))
    check("steps blank mode keeps mode", FryerRecipes.steps(spec, r1.copy(mode = ""), now).none { it.containsKey("3") })
    check("steps none when equal", FryerRecipes.steps(spec, r1.copy(minutes = 5, tempC = 150), now).isEmpty())
    // úprava a predvyplnenie
    val dr = FryerRecipes.draft(Fryer.read(spec, v))
    check("draft from off fryer (time 0 -> 15)", dr.id == "" && dr.name == "" && dr.mode == "Chips" && dr.minutes == 15 && dr.tempC == 200)
    check("draft keeps valid time", FryerRecipes.draft(Fryer.read(spec, v + ("9" to 22) + ("6" to 183))).let { it.minutes == 22 && it.tempC == 185 })
    val upd = FryerRecipes.update(l3, l3[1].copy(name = " Kuracie  prsia ", mode = " Cake ", minutes = 99, tempC = 51))
    check("update fixes values", upd[1].name == "Kuracie prsia" && upd[1].mode == "Cake" && upd[1].minutes == 60 && upd[1].tempC == 80 && upd[0] == l3[0])
    check("update clash ignored", FryerRecipes.update(l3, l3[1].copy(name = "Hranolky domáce")) == l3)
    check("update empty name ignored", FryerRecipes.update(l3, l3[1].copy(name = "  ")) == l3)
    check("update unknown id ignored", FryerRecipes.update(l3, r1.copy(id = "zzz", name = "Nový")) == l3)
    check("matches", FryerRecipes.matches(r1.copy(minutes = 5, tempC = 150), now) && !FryerRecipes.matches(r1, now))
    check("matches blank mode", FryerRecipes.matches(r1.copy(mode = "", minutes = 5, tempC = 150), now))
    check("matches other mode", !FryerRecipes.matches(r1.copy(mode = "Cake", minutes = 5, tempC = 150), now))
    println(if (failed == 0) "FRYER PASSED" else "FRYER FAILED: $failed")
}
