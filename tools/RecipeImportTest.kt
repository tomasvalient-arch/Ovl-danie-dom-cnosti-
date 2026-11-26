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
    check("1 PL ostane 1 PL, štipka a podľa potreby sú neurčité množstvo", b.has("Cukor", 1.0, "PL") && b.fuzzy("Soľ") && b.fuzzy("Olej") && b.ingredients.size == 6)

    val c = RecipeImport.parse("Cestoviny s paradajkami 🍝\n🍅 400 g paradajok\n🧄 2 strúčiky cesnaku\n🍝 250 g špagiet\n🧂 soľ, korenie\n🫒 3 PL olivového oleja")
    check("emoji odrážky a názov bez emoji", c.title == "Cestoviny s paradajkami" && c.has("Paradajky", 400.0, "g") && c.has("Špagety", 250.0, "g"))
    check("emoji: „soľ, korenie“ sú dve suroviny", c.fuzzy("Soľ") && c.fuzzy("Korenie"))
    check("emoji: 3 PL oleja a 2 strúčiky cesnaku sa zachovajú", c.has("Olivový olej", 3.0, "PL") && c.has("Cesnak", 2.0, "strúčik") && c.ingredients.size == 6)

    val d = RecipeImport.parse("Suroviny\nMúka\n500 g\nVajcia\n3 ks\nMlieko\n2 dl\nSoľ\n1 štipka")
    check("názov a množstvo na dvoch riadkoch (názov hore)", d.has("Múka", 500.0, "g") && d.has("Vajcia", 3.0, "ks") && d.has("Mlieko", 200.0, "ml") && d.fuzzy("Soľ") && d.ingredients.size == 4)

    val e = RecipeImport.parse("Suroviny\n500 g\nmúka\n3 ks\nvajcia\n2 dl\nmlieko")
    check("názov a množstvo na dvoch riadkoch (množstvo hore)", e.has("Múka", 500.0, "g") && e.has("Vajcia", 3.0, "ks") && e.has("Mlieko", 200.0, "ml") && e.ingredients.size == 3)

    val f = RecipeImport.parse("Suroviny: 500 g múky, 3 vajcia, 2 dl mlieka, štipka soli, 1 PL oleja. Postup: Zmiešame všetko.")
    check("zoznam v jednom riadku", f.has("Múka", 500.0, "g") && f.has("Vajcia", 3.0, "ks") && f.has("Mlieko", 200.0, "ml") && f.fuzzy("Soľ") && f.has("Olej", 1.0, "PL") && f.ingredients.size == 5)

    val g = RecipeImport.parse("Guláš\nSuroviny:\n1. 500 g hovädzieho mäsa\n2. 2 cibule\n3. 1 PL sladkej papriky\n4. 2 strúčiky cesnaku")
    check("číslovanie riadkov a 2. pád → 1. pád", g.has("Hovädzie mäso", 500.0, "g") && g.has("Cibuľa", 2.0, "ks") && g.has("Sladká paprika", 1.0, "PL") && g.has("Cesnak", 2.0, "strúčik") && g.ingredients.size == 4)

    val h = RecipeImport.parse("Suroviny\nmúka\t500 g\nvajcia\t3 ks\nmlieko\t2 dl")
    check("tabulátor medzi názvom a množstvom", h.has("Múka", 500.0, "g") && h.has("Vajcia", 3.0, "ks") && h.has("Mlieko", 200.0, "ml"))

    val i = RecipeImport.parse("Suroviny\n2x vajce\ncca 500 g múky\nasi 2 dl mlieka\nokolo 100 g masla\n1 balenie droždia")
    check("2x, cca, asi, okolo", i.has("Vajce", 2.0, "ks") && i.has("Múka", 500.0, "g") && i.has("Mlieko", 200.0, "ml") && i.has("Maslo", 100.0, "g") && i.has("Droždie", 1.0, "balenie"))

    val j = RecipeImport.parse("Suroviny\nMúka (500 g)\nVajcia (3 ks)\nMlieko (2 dl)")
    check("množstvo v zátvorke", j.has("Múka", 500.0, "g") && j.has("Vajcia", 3.0, "ks") && j.has("Mlieko", 200.0, "ml"))

    val k = RecipeImport.parse("Koláč\nNa cesto\n250 g múky\n100 g masla\nNa plnku\n500 g tvarohu\n2 vajcia\nPostup\nCesto vymiesime.")
    check("nadpisy sekcií „Na cesto“ sa nepovažujú za suroviny", k.ingredients.size == 4 && k.ingredients.none { it.name.startsWith("Na ") } && k.has("Tvaroh", 500.0, "g"))
    val k2 = RecipeImport.parse("Koláč\nSuroviny\nNa cesto:\n250 g múky\nNa posypanie: škorica\n100 g masla")
    check("„Na posypanie: škorica“ → škorica", k2.fuzzy("Škorica") && k2.ingredients.size == 3)

    val l = RecipeImport.parse("Suroviny\n500g múky\n250ml mlieka\n1,5 kg zemiakov\n1 l vody\n2 hrnčeky ryže\n3 lyžice oleja\n1 lyžička soli")
    check("zlepené jednotky 500g, 250ml", l.has("Múka", 500.0, "g") && l.has("Mlieko", 250.0, "ml") && l.has("Zemiaky", 1.5, "kg") && l.has("Voda", 1.0, "l") && l.ingredients.size == 7)
    check("hrnčeky, lyžice a lyžička sa zachovajú ako mäkké miery", l.has("Ryža", 2.0, "hrnček") && l.has("Olej", 3.0, "PL") && l.has("Soľ", 1.0, "ČL"))

    val m = RecipeImport.parse("Rýchla polievka\n- 1 l vývaru\n- 200 g rezancov\n- 1 mrkva\n- petržlenová vňať")
    check("bez nadpisu Suroviny, s pomlčkami", m.title == "Rýchla polievka" && m.has("Vývar", 1.0, "l") && m.has("Rezance", 200.0, "g") && m.has("Mrkva", 1.0, "ks") && m.fuzzy("Petržlenová vňať"))

    val n = RecipeImport.parse("Domov / Recepty / Hlavné jedlá\nKurací perkelt\nČas prípravy: 45 min\nPorcie: 4\nObľúbené Tlačiť Zdieľať\nSuroviny\n600 g kuracie prsia\n1 ks cibuľa\n2 ks paprika\n3 PL olej\n1 PL sladká paprika\nPostup prípravy\n1. Cibuľu orestujeme.")
    check("stránka: názov nie je omrvinková navigácia a čas/porcie nie sú suroviny", n.title == "Kurací perkelt" && n.portions == 4 && n.ingredients.size == 5)

    val q = RecipeImport.parse("Suroviny\nZemiaky - 500 g (varené)\nMlieko 1 l, polotučné\n250 g – múka\nCukor, 100 g")
    check("poznámka za množstvom a vedúca pomlčka", q.has("Zemiaky", 500.0, "g") && q.has("Mlieko", 1.0, "l") && q.has("Múka", 250.0, "g") && q.has("Cukor", 100.0, "g") && q.ingredients.size == 4)

    // presný text zo skutočnej stránky: množstvo (s tabulátorom) na jednom riadku, názov na ďalšom, prívlastok pri množstve, miera bez čísla
    val real = RecipeImport.parse("150 g\t\nryža SOS Jasmine Brown\n250 g\t\nšampiňóny hnedé\n2 strúčiky\t\ncesnak\n30 g strúhaný\t\nparmezán\n hrsť\t\nvňať koriandrová\n1 ČL\t\nolej olivový\n1 ČL\t\nkorenie na cestoviny\n1/2 ČL\t\nsoľ")
    check("reálny text: presne 8 surovín, nič navyše", real.ingredients.size == 8 && real.skipped.isEmpty())
    check("reálny text: ryža 150 g, šampiňóny 250 g", real.has("Ryža SOS Jasmine Brown", 150.0, "g") && real.has("Šampiňóny hnedé", 250.0, "g"))
    check("reálny text: parmezán strúhaný 30 g (prívlastok sa pripojí k názvu)", real.has("Parmezán strúhaný", 30.0, "g"))
    check("reálny text: hrsť + vňať koriandrová = 1 hrsť", real.has("Vňať koriandrová", 1.0, "hrsť") && real.ingredients.none { it.name == "Hrsť" })
    check("reálny text: 2 strúčiky cesnaku, 1 ČL oleja, 1 ČL korenia, 1/2 ČL soli", real.has("Cesnak", 2.0, "strúčik") && real.has("Olej olivový", 1.0, "ČL") && real.has("Korenie na cestoviny", 1.0, "ČL") && real.has("Soľ", 0.5, "ČL"))
    // rovnaký recept s hlavičkou Suroviny a nadpisom
    val real2 = RecipeImport.parse("Ryža s hubami\nSuroviny\n150 g\nryža\n30 g strúhaný\nparmezán\nhrsť\nbazalka\nPostup\nUvaríme.")
    check("qualifier a hrsť aj s nadpisom", real2.title == "Ryža s hubami" && real2.has("Parmezán strúhaný", 30.0, "g") && real2.has("Bazalka", 1.0, "hrsť") && real2.has("Ryža", 150.0, "g") && real2.ingredients.size == 3)
    // kompletný riadok sa nepáruje s ďalším: „30 g strúhaný parmezán“ + ďalšia surovina
    val full = RecipeImport.parse("Suroviny\n30 g strúhaný parmezán\n200 g ryže\n2 vajcia")
    check("kompletné riadky ostanú kompletné", full.has("Strúhaný parmezán", 30.0, "g") && full.has("Ryža", 200.0, "g") && full.has("Vajcia", 2.0, "ks") && full.ingredients.size == 3)

    val bare = RecipeImport.parse("Suroviny\n2\nvajcia\n500 g\nmúka\n1\ncibuľa\nPostup\n1\nCibuľu nakrájame.")
    check("samotné číslo pred názvom = počet kusov", bare.has("Vajcia", 2.0, "ks") && bare.has("Múka", 500.0, "g") && bare.has("Cibuľa", 1.0, "ks") && bare.ingredients.size == 3)
    val steps = RecipeImport.parse("Rezne\n1\nNaklepeme mäso.\n2\nObalíme.")
    check("čísla krokov bez nadpisu Suroviny nie sú suroviny", steps.ingredients.isEmpty())

    // odkaz na stránku: JSON-LD s položkami „názov , množstvo“ (varecha.pravda.sk)
    val ld = """<html><head><script type="application/ld+json">{"@context":"https://schema.org","@type":"Recipe","name":"Ryža s hubami, parmezánom a koriandrom","recipeYield":"2 porcie","recipeIngredient":["ryža SOS Jasmine Brown , 150 g","šampiňóny hnedé , 250 g","cesnak , 2 strúčiky","parmezán , 30 g strúhaný","vňať koriandrová , hrsť","olej olivový , 1 ČL","korenie na cestoviny , 1 ČL","soľ , 1/2 ČL"]}</script></head><body></body></html>"""
    val web = RecipeImport.fromHtml(ld)!!
    check("stránka: názov a 2 porcie", web.title == "Ryža s hubami, parmezánom a koriandrom" && web.portions == 2)
    check("stránka: presne 8 surovín, nič nerozpoznané navyše", web.ingredients.size == 8 && web.skipped.isEmpty())
    check("stránka: parmezán strúhaný 30 g", web.has("Parmezán strúhaný", 30.0, "g") && web.has("Ryža SOS Jasmine Brown", 150.0, "g") && web.has("Šampiňóny hnedé", 250.0, "g"))
    check("stránka: hrsť je množstvo, nie surovina", web.has("Vňať koriandrová", 1.0, "hrsť") && web.ingredients.none { it.name == "Hrsť" })
    check("stránka: 2 strúčiky cesnaku, 1 ČL oleja aj 1/2 ČL soli", web.has("Cesnak", 2.0, "strúčik") && web.has("Olej olivový", 1.0, "ČL") && web.has("Soľ", 0.5, "ČL"))
    val ld2 = """<script type="application/ld+json">{"@type":"Recipe","name":"X","recipeIngredient":["150 g","ryža","30 g strúhaný","parmezán","2","vajcia"]}</script>"""
    val web2 = RecipeImport.fromHtml(ld2)!!
    check("stránka: položky „množstvo“ a „názov“ zvlášť sa spoja", web2.has("Ryža", 150.0, "g") && web2.has("Parmezán strúhaný", 30.0, "g") && web2.has("Vajcia", 2.0, "ks"))
    val micro = """<html><h1>Palacinky</h1><span itemprop="recipeYield">4 porcie</span><ul><li itemprop="recipeIngredient">250 g múky</li><li itemprop="recipeIngredient">2 vajcia</li><li itemprop="ingredients">5 dl mlieka</li></ul></html>"""
    val web3 = RecipeImport.fromHtml(micro)!!
    check("stránka bez JSON-LD: itemprop suroviny", web3.title == "Palacinky" && web3.portions == 4 && web3.has("Múka", 250.0, "g") && web3.has("Mlieko", 500.0, "ml") && web3.ingredients.size == 3)
    check("stránka bez receptu = null", RecipeImport.fromHtml("<html>nič</html>") == null)
    // „názov , množstvo“ aj v texte
    check("riadok „názov , množstvo“", RecipeImport.parseLine("parmezán , 30 g strúhaný")!!.let { it.name == "Parmezán strúhaný" && eq(it.amount, 30.0) && it.unit == "g" } && RecipeImport.parseLine("cibuľa, 2 ks")!!.let { it.name == "Cibuľa" && eq(it.amount, 2.0) })
    check("riadok „množstvo , názov“", RecipeImport.parseLine("30 g strúhaný , parmezán")!!.let { it.name == "Parmezán strúhaný" && eq(it.amount, 30.0) } && RecipeImport.parseLine("hrsť , vňať koriandrová")!!.let { it.name == "Vňať koriandrová" && eq(it.amount, 1.0) && it.unit == "hrsť" } && RecipeImport.parseLine("150 g , ryža")!!.name == "Ryža" && RecipeImport.parseAll("hrsť , vňať koriandrová").size == 1)
    check("desatinná čiarka a poznámka za čiarkou sa nepoplietli", RecipeImport.parseLine("Mlieko 1,5 l")!!.let { it.name == "Mlieko" && eq(it.amount, 1.5) } && RecipeImport.parseLine("200 g cibule, nakrájanej")!!.name == "Cibuľa")

    // mäkké miery: lyžice s prívlastkom, skratky, kocky, konzervy, kúsky – množstvo sa zachová
    val soft = RecipeImport.parse("Suroviny\n2 polievkové lyžice soli\n1 čajová lyžička škorice\n1 lyžica medu\n2 lyžičky kakaa\n1 kocka zeleninového bujónu\n1 konzerva kukurice\n1 kúsok zázvoru\n1 hlávka cesnaku\n2 cl rumu\n250 g kuskusu\n1 hlávkový šalát\n3 strúčiky cesnaku\nPostup\nUvaríme.")
    check("mäkké miery: polievková lyžica = PL, čajová lyžička = ČL", soft.has("Soľ", 2.0, "PL") && soft.has("Škorica", 1.0, "ČL") && soft.has("Med", 1.0, "PL") && soft.has("Kakao", 2.0, "ČL"))
    check("mäkké miery: kocka, konzerva, kúsok, hlávka", soft.has("Zeleninový bujón", 1.0, "kocka") && soft.has("Kukurica", 1.0, "konzerva") && soft.has("Zázvor", 1.0, "kúsok") && soft.has("Cesnak", 1.0, "hlávka"))
    check("mäkké miery: 2 cl rumu sú centilitre (20 ml), nie čajová lyžička", soft.has("Rum", 20.0, "ml"))
    check("mäkké miery: kuskus a hlávkový šalát nie sú jednotky", soft.has("Kuskus", 250.0, "g") && soft.ingredients.any { it.name.startsWith("Hlávkový šalát") && eq(it.amount, 1.0) && it.unit == "ks" })
    check("mäkké miery: 3 strúčiky cesnaku", soft.has("Cesnak", 3.0, "strúčik") && soft.skipped.isEmpty())
    check("štipka a trocha ostávajú neurčité", RecipeImport.parseLine("štipka soli")!!.let { Meals.isFuzzy(it.unit) && eq(it.amount, 0.0) } && RecipeImport.parseLine("trochu čili")!!.let { Meals.isFuzzy(it.unit) })
    check("2 strúčiky cesnaku v jednom riadku", RecipeImport.parseLine("2 strúčiky cesnaku")!!.let { it.name == "Cesnak" && eq(it.amount, 2.0) && it.unit == "strúčik" } && RecipeImport.parseLine("cesnak – 2 strúčiky")!!.let { it.name == "Cesnak" && eq(it.amount, 2.0) && it.unit == "strúčik" })
    check("zobrazenie: 2 strúčiky, ½ ČL, 1 hrsť", Meals.formatQty(2.0, "strúčik") == "2 strúčiky" && Meals.formatQty(0.5, "ČL") == "½ ČL" && Meals.formatQty(1.0, "hrsť") == "1 hrsť")

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
