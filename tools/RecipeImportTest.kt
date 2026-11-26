import sk.domov.ovladanie.*

// Import receptu z vloženého textu: reálne tvary riadkov zo stránok, sociálnych sietí a z poznámok.

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

fun eq(a: Double, b: Double) = kotlin.math.abs(a - b) < 1e-6

fun RecipeImport.Result.has(name: String, amount: Double, unit: String) =
    ingredients.any { it.name == name && eq(it.amount, amount) && it.unit == unit }

fun RecipeImport.Result.fuzzy(name: String) = ingredients.any { it.name == name && eq(it.amount, 0.0) && Meals.isFuzzy(it.unit) || it.name == name && it.unit == "podľa chuti" }

fun main() {
    // množstvo za názvom (veľmi bežné na stránkach)
    val a = RecipeImport.parse("Bryndzové halušky\nPorcie: 4\nSuroviny\nZemiaky 500 g\nHladká múka 200 g\nVajce 1 ks\nBryndza 250 g\nSlanina 100 g\nSoľ\nPostup\nZemiaky nastrúhame.")
    check("názov pred množstvom: 5 surovín + soľ", a.ingredients.size == 6 && a.title == "Bryndzové halušky" && a.portions == 4)
    check("názov pred množstvom: Zemiaky 500 g", a.has("Zemiaky", 500.0, "g") && a.has("Hladká múka", 200.0, "g") && a.has("Vajce", 1.0, "ks"))
    check("postup sa neberie, nič nezostane nerozpoznané", a.skipped.isEmpty())

    val b = RecipeImport.parse("Palacinky\nSuroviny:\nMúka - 250 g\nMlieko: 5 dl\nVajcia – 2 ks\nCukor – 1 PL\nSoľ – štipka\nOlej – podľa potreby")
    check("pomlčka a dvojbodka", b.has("Múka", 250.0, "g") && b.has("Mlieko", 500.0, "ml") && b.has("Vajcia", 2.0, "ks"))
    check("1 PL a štipka a podľa potreby sú neurčité množstvo", b.fuzzy("Cukor") && b.fuzzy("Soľ") && b.fuzzy("Olej") && b.ingredients.size == 6)

    val c = RecipeImport.parse("Cestoviny s paradajkami 🍝\n🍅 400 g paradajok\n🧄 2 strúčiky cesnaku\n🍝 250 g špagiet\n🧂 soľ, korenie\n🫒 3 PL olivového oleja")
    check("emoji odrážky a názov bez emoji", c.title == "Cestoviny s paradajkami" && c.has("Paradajky", 400.0, "g") && c.has("Špagety", 250.0, "g"))
    check("emoji: „soľ, korenie“ sú dve suroviny", c.fuzzy("Soľ") && c.fuzzy("Korenie"))
    check("emoji: olivový olej a cesnak", c.fuzzy("Olivový olej") && c.fuzzy("Cesnak") && c.ingredients.size == 6)

    val d = RecipeImport.parse("Suroviny\nMúka\n500 g\nVajcia\n3 ks\nMlieko\n2 dl\nSoľ\n1 štipka")
    check("názov a množstvo na dvoch riadkoch (názov hore)", d.has("Múka", 500.0, "g") && d.has("Vajcia", 3.0, "ks") && d.has("Mlieko", 200.0, "ml") && d.fuzzy("Soľ") && d.ingredients.size == 4)

    val e = RecipeImport.parse("Suroviny\n500 g\nmúka\n3 ks\nvajcia\n2 dl\nmlieko")
    check("názov a množstvo na dvoch riadkoch (množstvo hore)", e.has("Múka", 500.0, "g") && e.has("Vajcia", 3.0, "ks") && e.has("Mlieko", 200.0, "ml") && e.ingredients.size == 3)

    val f = RecipeImport.parse("Suroviny: 500 g múky, 3 vajcia, 2 dl mlieka, štipka soli, 1 PL oleja. Postup: Zmiešame všetko.")
    check("zoznam v jednom riadku", f.has("Múka", 500.0, "g") && f.has("Vajcia", 3.0, "ks") && f.has("Mlieko", 200.0, "ml") && f.fuzzy("Soľ") && f.fuzzy("Olej") && f.ingredients.size == 5)

    val g = RecipeImport.parse("Guláš\nSuroviny:\n1. 500 g hovädzieho mäsa\n2. 2 cibule\n3. 1 PL sladkej papriky\n4. 2 strúčiky cesnaku")
    check("číslovanie riadkov a 2. pád → 1. pád", g.has("Hovädzie mäso", 500.0, "g") && g.has("Cibuľa", 2.0, "ks") && g.fuzzy("Sladká paprika") && g.fuzzy("Cesnak") && g.ingredients.size == 4)

    val h = RecipeImport.parse("Suroviny\nmúka\t500 g\nvajcia\t3 ks\nmlieko\t2 dl")
    check("tabulátor medzi názvom a množstvom", h.has("Múka", 500.0, "g") && h.has("Vajcia", 3.0, "ks") && h.has("Mlieko", 200.0, "ml"))

    val i = RecipeImport.parse("Suroviny\n2x vajce\ncca 500 g múky\nasi 2 dl mlieka\nokolo 100 g masla\n1 balenie droždia")
    check("2x, cca, asi, okolo", i.has("Vajce", 2.0, "ks") && i.has("Múka", 500.0, "g") && i.has("Mlieko", 200.0, "ml") && i.has("Maslo", 100.0, "g") && i.fuzzy("Droždie"))

    val j = RecipeImport.parse("Suroviny\nMúka (500 g)\nVajcia (3 ks)\nMlieko (2 dl)")
    check("množstvo v zátvorke", j.has("Múka", 500.0, "g") && j.has("Vajcia", 3.0, "ks") && j.has("Mlieko", 200.0, "ml"))

    val k = RecipeImport.parse("Koláč\nNa cesto\n250 g múky\n100 g masla\nNa plnku\n500 g tvarohu\n2 vajcia\nPostup\nCesto vymiesime.")
    check("nadpisy sekcií „Na cesto“ sa nepovažujú za suroviny", k.ingredients.size == 4 && k.ingredients.none { it.name.startsWith("Na ") } && k.has("Tvaroh", 500.0, "g"))
    val k2 = RecipeImport.parse("Koláč\nSuroviny\nNa cesto:\n250 g múky\nNa posypanie: škorica\n100 g masla")
    check("„Na posypanie: škorica“ → škorica", k2.fuzzy("Škorica") && k2.ingredients.size == 3)

    val l = RecipeImport.parse("Suroviny\n500g múky\n250ml mlieka\n1,5 kg zemiakov\n1 l vody\n2 hrnčeky ryže\n3 lyžice oleja\n1 lyžička soli")
    check("zlepené jednotky 500g, 250ml", l.has("Múka", 500.0, "g") && l.has("Mlieko", 250.0, "ml") && l.has("Zemiaky", 1.5, "kg") && l.has("Voda", 1.0, "l") && l.ingredients.size == 7)

    val m = RecipeImport.parse("Rýchla polievka\n- 1 l vývaru\n- 200 g rezancov\n- 1 mrkva\n- petržlenová vňať")
    check("bez nadpisu Suroviny, s pomlčkami", m.title == "Rýchla polievka" && m.has("Vývar", 1.0, "l") && m.has("Rezance", 200.0, "g") && m.has("Mrkva", 1.0, "ks") && m.fuzzy("Petržlenová vňať"))

    val n = RecipeImport.parse("Domov / Recepty / Hlavné jedlá\nKurací perkelt\nČas prípravy: 45 min\nPorcie: 4\nObľúbené Tlačiť Zdieľať\nSuroviny\n600 g kuracie prsia\n1 ks cibuľa\n2 ks paprika\n3 PL olej\n1 PL sladká paprika\nPostup prípravy\n1. Cibuľu orestujeme.")
    check("stránka: názov nie je omrvinková navigácia a čas/porcie nie sú suroviny", n.title == "Kurací perkelt" && n.portions == 4 && n.ingredients.size == 5)

    val q = RecipeImport.parse("Suroviny\nZemiaky - 500 g (varené)\nMlieko 1 l, polotučné\n250 g – múka\nCukor, 100 g")
    check("poznámka za množstvom a vedúca pomlčka", q.has("Zemiaky", 500.0, "g") && q.has("Mlieko", 1.0, "l") && q.has("Múka", 250.0, "g") && q.has("Cukor", 100.0, "g") && q.ingredients.size == 4)

    // čo nesmie byť surovina
    val o = RecipeImport.parse("Rezne\nPečieme 20 minút pri 180 °C.\n45 min\n2 hodiny\nPorcie 4")
    check("kroky, čas a teplota nie sú suroviny", o.ingredients.isEmpty() && o.title == "Rezne")
    check("veta so slovesom a množstvom na konci", RecipeImport.parseAll("Zemiaky nastrúhame na 2 cm hrubé plátky.").isEmpty())
    check("samotné množstvo bez názvu", RecipeImport.parseAll("500 g").isEmpty())
    check("prázdny text", RecipeImport.parse("").ingredients.isEmpty() && RecipeImport.parse("   \n  ").skipped.isEmpty())

    // nerozpoznané riadky sa hlásia (nestratia sa potichu)
    val p = RecipeImport.parse("Suroviny\n500 g múky\n3 vajcia\nčo sa dá nájsť doma a pritom je to veľmi dlhý riadok bez zmyslu v surovinách tohto receptu")
    check("nerozpoznaný dlhý riadok ide do skipped", p.ingredients.size == 2 && p.skipped.size == 1)

    // 2. pád → 1. pád
    check("2. pád → základný tvar", RecipeImport.parseLine("500 g zemiakov")!!.name == "Zemiaky" && RecipeImport.parseLine("1 kg cukru")!!.name == "Cukor" && RecipeImport.parseLine("2 dl smotany")!!.name == "Smotana")
    check("kyslá smotana, hladká múka, kuracie mäso", RecipeImport.parseLine("2 dl kyslej smotany")!!.name == "Kyslá smotana" && RecipeImport.parseLine("300 g hladkej múky")!!.name == "Hladká múka" && RecipeImport.parseLine("400 g kuracieho mäsa")!!.name == "Kuracie mäso")
    check("známy názov má prednosť", RecipeImport.parseLine("500 g zemiakov", listOf(Meals.Known("Zemiaky nové", "kg", 2))) !!.name == "Zemiaky")
    check("vajec → vajcia", RecipeImport.parseLine("10 vajec")!!.name == "Vajcia")

    // zhoda so zásobami: názvy z importu sa spoja s tým, čo už appka pozná
    check("import sa zhoduje so zásobou cez key", Meals.key(RecipeImport.parseLine("500 g zemiakov")!!.name) == Meals.key("Zemiaky") && Meals.key(RecipeImport.parseLine("1 kg cukru")!!.name) == Meals.key("Cukor"))

    println("RecipeImportTest OK")
}
