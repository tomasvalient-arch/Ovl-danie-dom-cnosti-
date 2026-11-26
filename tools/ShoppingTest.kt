import sk.domov.ovladanie.*

// Test nákupného zoznamu: pridanie, čistenie názvu, duplicity, odškrtnutie, poradie, texty a uloženie.

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

fun main() {
    var n = 0
    val id = { "id" + (n++) }

    // čistenie názvu
    check("medzery preč, prvé písmeno veľké", ShoppingList.clean("  mlieko   2 l ") == "Mlieko 2 l")
    check("diakritika na začiatku", ShoppingList.clean("šalát") == "Šalát")
    check("prázdny text", ShoppingList.clean("   ") == "")
    check("dlhý názov sa skráti", ShoppingList.clean("a".repeat(200)).length == ShoppingList.MAX_NAME)

    // pridanie
    var l = emptyList<ShopItem>()
    l = ShoppingList.add(l, "mlieko 2 l", 1000, id)
    l = ShoppingList.add(l, "Chlieb", 2000, id)
    l = ShoppingList.add(l, "   ", 3000, id)
    check("prázdny text nepridá nič", l.size == 2)
    check("nové je hore", ShoppingList.arrange(l).map { it.name } == listOf("Chlieb", "Mlieko 2 l"))

    // duplicita nekúpenej
    l = ShoppingList.add(l, "chlieb", 4000, id)
    check("nekúpená sa nezduplikuje", l.size == 2)

    // odškrtnutie a poradie
    val milk = l.first { it.name == "Mlieko 2 l" }.id
    val bread = l.first { it.name == "Chlieb" }.id
    l = ShoppingList.toggle(l, milk, 5000)
    check("kúpené ide na koniec", ShoppingList.arrange(l).map { it.name } == listOf("Chlieb", "Mlieko 2 l"))
    check("zostáva 1", ShoppingList.left(l) == 1)
    l = ShoppingList.toggle(l, bread, 6000)
    check("naposledy kúpené je hore", ShoppingList.arrange(l).map { it.name } == listOf("Chlieb", "Mlieko 2 l"))
    l = ShoppingList.toggle(l, bread, 7000)
    check("vrátené medzi nekúpené", ShoppingList.left(l) == 1 && !l.first { it.id == bread }.done)

    // kúpená položka napísaná znova sa vráti hore
    l = ShoppingList.add(l, "MLIEKO 2 L", 8000, id)
    check("kúpená sa vráti medzi nekúpené", l.size == 2 && ShoppingList.left(l) == 2)
    check("a ide hore", ShoppingList.arrange(l).first().name == "Mlieko 2 l")

    // vymazať kúpené
    l = ShoppingList.toggle(l, bread, 9000)
    l = ShoppingList.clearDone(l)
    check("vymazané kúpené", l.size == 1 && l[0].name == "Mlieko 2 l")

    // limit
    var big = emptyList<ShopItem>()
    for (i in 0 until ShoppingList.MAX_ITEMS + 5) big = ShoppingList.add(big, "Vec $i", i.toLong(), id)
    check("najviac ${ShoppingList.MAX_ITEMS} položiek", big.size == ShoppingList.MAX_ITEMS)

    // texty
    check("1 položka", ShoppingList.itemsText(1) == "1 položka")
    check("3 položky", ShoppingList.itemsText(3) == "3 položky")
    check("7 položiek", ShoppingList.itemsText(7) == "7 položiek")
    check("riadok: prázdny", ShoppingList.rowText(emptyList()) == "Prázdny")
    val allDone = listOf(ShopItem("a", "X", true, 1, 2))
    check("riadok: všetko kúpené", ShoppingList.rowText(allDone) == "Všetko kúpené")
    check("riadok: počet", ShoppingList.rowText(listOf(ShopItem("a", "X", false, 1))) == "1 položka")
    check("podnadpis", ShoppingList.subtitle(big) == "Zostáva: ${ShoppingList.MAX_ITEMS}")

    // uloženie
    val back = ShoppingList.fromJson(ShoppingList.toJson(allDone + listOf(ShopItem("b", "Šalát", false, 5))))
    check("JSON tam a späť", back.size == 2 && back[0].done && back[0].doneAt == 2L && back[1].name == "Šalát" && back[1].addedAt == 5L)
    check("poškodený JSON = prázdny zoznam", ShoppingList.fromJson("{nie je pole").isEmpty())
    check("null = prázdny zoznam", ShoppingList.fromJson(null).isEmpty())
    check("položka bez názvu sa preskočí", ShoppingList.fromJson("""[{"i":"a","n":""},{"i":"b","n":"Maslo"}]""").map { it.name } == listOf("Maslo"))

    // 1.43: odstránenie, späť, premenovanie
    var r = emptyList<ShopItem>()
    r = ShoppingList.add(r, "Syr", 1, id)
    r = ShoppingList.add(r, "Salama", 2, id)
    val syr = r.first { it.name == "Syr" }
    r = ShoppingList.remove(r, syr.id)
    check("odstránená položka", r.map { it.name } == listOf("Salama"))
    r = ShoppingList.restore(r, syr)
    check("späť vráti tú istú položku", r.any { it.id == syr.id && it.addedAt == 1L })
    check("späť dvakrát nezduplikuje", ShoppingList.restore(r, syr).size == 2)
    val sal = r.first { it.name == "Salama" }.id
    r = ShoppingList.rename(r, sal, "  saláma 20 dkg ")
    check("premenovanie vyčistí názov", r.first { it.id == sal }.name == "Saláma 20 dkg")
    check("prázdny názov nič nezmení", ShoppingList.rename(r, sal, "  ") == r)
    r = ShoppingList.rename(r, sal, "SYR")
    check("premenovanie na nekúpenú duplicitu zlúči", r.size == 1 && r[0].id == syr.id)
    r = ShoppingList.add(r, "Maslo", 3, id)
    r = ShoppingList.toggle(r, syr.id, 4)
    val maslo = r.first { it.name == "Maslo" }.id
    r = ShoppingList.rename(r, maslo, "syr")
    check("premenovanie na kúpenú duplicitu odstráni kúpenú", r.size == 1 && r[0].id == maslo && r[0].name == "Syr" && !r[0].done)

    // 1.43: história, návrhy a rýchle tlačidlá
    check("fold bez diakritiky", ShoppingHistory.fold("  Šalát  Ľadový ") == "salat ladovy")
    var h = emptyList<ShopHistoryEntry>()
    h = ShoppingHistory.record(h, "mlieko 2 l", 10)
    h = ShoppingHistory.record(h, "Mlieko 2 L", 20)
    h = ShoppingHistory.record(h, "Šalát", 30)
    h = ShoppingHistory.record(h, "Salama", 40)
    h = ShoppingHistory.record(h, "Chlieb", 50)
    h = ShoppingHistory.record(h, "Chlieb", 60)
    h = ShoppingHistory.record(h, "Chlieb", 70)
    h = ShoppingHistory.record(h, "   ", 80)
    check("história bez duplicít", h.size == 4)
    check("počet sa zvyšuje", h.first { it.name.startsWith("Mlieko") }.count == 2)
    check("návrh bez diakritiky a veľkosti", ShoppingHistory.suggest(h, "SAL", emptyList()).map { it.name } == listOf("Salama", "Šalát"))
    check("návrh podľa slova v názve", ShoppingHistory.suggest(h, "2 l", emptyList()).isEmpty() && ShoppingHistory.suggest(h, "l", emptyList()).map { it.name }.contains("Mlieko 2 L"))
    check("prázdny text = bez návrhov", ShoppingHistory.suggest(h, " ", emptyList()).isEmpty())
    check("presne napísané sa nenavrhuje", ShoppingHistory.suggest(h, "chlieb", emptyList()).isEmpty())
    val onListNow = listOf(ShopItem("x", "Šalát", false, 1), ShopItem("y", "Chlieb", true, 1, 2))
    check("nekúpené v zozname sa nenavrhuje", ShoppingHistory.suggest(h, "sa", onListNow).map { it.name } == listOf("Salama"))
    h = ShoppingHistory.forget(h, "chlieb")
    check("odstránenie nekúpenej odpočíta", h.first { it.name == "Chlieb" }.count == 2)
    check("pri nule zmizne", ShoppingHistory.forget(h, "salama").none { it.name == "Salama" })
    check("odstrániť z návrhov", ShoppingHistory.delete(h, "CHLIEB").none { it.name == "Chlieb" })
    val hr = ShoppingHistory.rename(h, "Mlieko 2 L", "chlieb", 99)
    check("premenovanie zlúči históriu", hr.none { it.name.startsWith("Mlieko") } && hr.first { it.name == "Chlieb" }.count == 4)
    check("premenovanie na nové meno", ShoppingHistory.rename(h, "Salama", "Saláma", 99).let { x -> x.any { it.name == "Saláma" && it.count == 1 } && x.none { it.name == "Salama" } })
    var full = emptyList<ShopHistoryEntry>()
    for (i in 0 until ShoppingHistory.MAX + 3) full = ShoppingHistory.record(full, "Vec $i", i.toLong())
    check("história má limit", full.size == ShoppingHistory.MAX)
    val hb = ShoppingHistory.fromJson(ShoppingHistory.toJson(h))
    check("história JSON tam a späť", hb == h)
    check("poškodená história = prázdna", ShoppingHistory.fromJson("x").isEmpty() && ShoppingHistory.fromJson(null).isEmpty())
    check("naplnenie z terajšieho zoznamu", ShoppingHistory.seed(onListNow).map { it.name to it.count } == listOf("Šalát" to 1, "Chlieb" to 1))

    // 1.45: „Často kupuješ“ len pre veci, ktoré treba kúpiť znova
    val D = ShoppingHistory.DAY_MS
    var g = emptyList<ShopHistoryEntry>()
    g = ShoppingHistory.record(g, "Mlieko", 0)
    g = ShoppingHistory.bought(g, "mlieko", 1 * D)
    g = ShoppingHistory.bought(g, "Mlieko", 3 * D)
    g = ShoppingHistory.bought(g, "Mlieko", 5 * D)
    g = ShoppingHistory.bought(g, "Mlieko", 5 * D) // rovnaký čas sa nezduplikuje
    val milkE = g.first { it.name == "Mlieko" }
    check("nákupy sa zapisujú", milkE.bought == listOf(1 * D, 3 * D, 5 * D))
    check("interval = medián medzier (2 dni)", ShoppingHistory.interval(milkE) == 2 * D)
    check("menej ako 2 nákupy = 7 dní", ShoppingHistory.interval(ShopHistoryEntry("X", 1, 0, listOf(D))) == 7 * D)
    check("interval aspoň 1 deň", ShoppingHistory.interval(ShopHistoryEntry("X", 1, 0, listOf(D, D + 1000, D + 2000))) == D)
    check("párny počet medzier: priemer dvoch stredných", ShoppingHistory.interval(ShopHistoryEntry("X", 1, 0, listOf(0, 2 * D, 6 * D))) == 3 * D)
    check("hneď po kúpe sa neponúka", ShoppingHistory.due(g, emptyList(), 6 * D).isEmpty())
    check("po obvyklom čase sa ponúka", ShoppingHistory.due(g, emptyList(), 7 * D).map { it.name } == listOf("Mlieko"))
    check("kúpené (nevymazané) v zozname sa neponúka", ShoppingHistory.due(g, listOf(ShopItem("m", "Mlieko", true, 1, 5 * D)), 30 * D).isEmpty())
    check("nekúpené v zozname sa neponúka", ShoppingHistory.due(g, listOf(ShopItem("m", "mlieko", false, 1)), 30 * D).isEmpty())
    g = ShoppingHistory.unbought(g, "Mlieko", 5 * D)
    check("zrušené odškrtnutie zabudne nákup", g.first { it.name == "Mlieko" }.bought == listOf(1 * D, 3 * D))
    g = ShoppingHistory.bought(g, "Mlieko", 5 * D)
    // Chlieb: raz kúpený pred 10 dňami (týždenný interval → 10/7 po termíne), Maslo: kupuje sa každé 2 dni, posledný pred 6 dňami (3× po termíne)
    g = ShoppingHistory.bought(g, "Chlieb", 0)
    g = ShoppingHistory.record(g, "Maslo", 0)
    g = ShoppingHistory.bought(g, "Maslo", 0)
    g = ShoppingHistory.bought(g, "Maslo", 2 * D)
    g = ShoppingHistory.bought(g, "Maslo", 4 * D)
    check("nová vec pri kúpe sa pridá do histórie", g.any { it.name == "Chlieb" && it.bought == listOf(0L) })
    check("poradie podľa toho, ako veľmi je po termíne", ShoppingHistory.due(g, emptyList(), 10 * D).map { it.name } == listOf("Maslo", "Mlieko", "Chlieb"))
    // rovnako po termíne → rozhodne počet pridaní
    var tie = emptyList<ShopHistoryEntry>()
    tie = ShoppingHistory.record(tie, "A", 0); tie = ShoppingHistory.bought(tie, "A", 0)
    tie = ShoppingHistory.record(tie, "B", 0); tie = ShoppingHistory.record(tie, "B", 0); tie = ShoppingHistory.bought(tie, "B", 0)
    check("pri zhode rozhodne počet", ShoppingHistory.due(tie, emptyList(), 8 * D).map { it.name } == listOf("B", "A"))
    check("limit tlačidiel", ShoppingHistory.due((1..30).fold(emptyList<ShopHistoryEntry>()) { acc, i -> ShoppingHistory.bought(acc, "Vec $i", 0) }, emptyList(), 30 * D).size == ShoppingHistory.CHIPS)
    var cap = emptyList<ShopHistoryEntry>()
    for (i in 1..15) cap = ShoppingHistory.bought(cap, "Káva", i * D)
    check("pamätá si len posledných ${ShoppingHistory.MAX_BOUGHT} nákupov", cap[0].bought.size == ShoppingHistory.MAX_BOUGHT && cap[0].bought.first() == 6 * D)
    check("pridanie nezmaže nákupy", ShoppingHistory.record(cap, "káva", 99).first().bought.size == ShoppingHistory.MAX_BOUGHT)
    check("nákupy v JSON tam a späť", ShoppingHistory.fromJson(ShoppingHistory.toJson(g)) == g)
    val oldJson = """[{"n":"Syr","c":3,"t":${2 * D}}]"""
    val migrated = ShoppingHistory.fromJson(oldJson)
    check("stará história (bez nákupov) sa načíta", migrated.size == 1 && migrated[0].bought.isEmpty() && migrated[0].count == 3)
    check("stará história: termín od posledného pridania", ShoppingHistory.due(migrated, emptyList(), 8 * D).isEmpty() && ShoppingHistory.due(migrated, emptyList(), 9 * D).size == 1)
    check("premenovanie prenesie nákupy", ShoppingHistory.rename(g, "Maslo", "Maslo 250 g", 11 * D).first { it.name == "Maslo 250 g" }.bought == listOf(0L, 2 * D, 4 * D))
    check("naplnenie zapíše kúpené položky ako nákup", ShoppingHistory.seed(listOf(ShopItem("x", "Džem", true, 1, 5 * D))).first().bought == listOf(5 * D))
    check("ozvena IME po Enter sa ignoruje (rovnaký text)", !ShoppingList.acceptFieldChange("Víno", "Víno"))
    check("ozvena IME bez ohľadu na veľkosť písmen", !ShoppingList.acceptFieldChange("víno", "Víno"))
    check("ozvena po úprave veľkého písmena (clean)", !ShoppingList.acceptFieldChange("Víno", "víno"))
    check("medzery pri ozvene", !ShoppingList.acceptFieldChange("  Víno  ", "Víno"))
    check("prázdne pole po vyčistení sa prijme", ShoppingList.acceptFieldChange("", "Víno"))
    check("nové písanie po pridaní sa prijme", ShoppingList.acceptFieldChange("M", "Víno"))
    check("ďalšia vec s iným názvom sa prijme", ShoppingList.acceptFieldChange("Mlieko", "Víno"))
    check("bez predchádzajúceho pridania sa text prijme", ShoppingList.acceptFieldChange("Víno", null))
    check("tlačidlo + aj Enter používajú tú istú úpravu poľa", ShoppingList.acceptFieldChange("Víno", "Víno") == ShoppingList.acceptFieldChange("Víno", "víno"))

    // --- dva zoznamy (1.52): Môj ostáva miestny, Spoločný je pôvodný zoznam ---
    val id2 = { java.util.UUID.randomUUID().toString() }
    var two = emptyList<ShopItem>()
    two = ShoppingList.add(two, "Cestoviny", 1, id2, list = ShopListKind.MINE)
    two = ShoppingList.add(two, "Mlieko 2 l", 2, id2, list = ShopListKind.SHARED)
    two = ShoppingList.add(two, "Cestoviny", 3, id2, list = ShopListKind.SHARED)
    check("rovnaký názov môže byť v oboch zoznamoch", two.filter { !it.deleted }.size == 3)
    check("zostáva v Môj 1", ShoppingList.left(two, ShopListKind.MINE) == 1)
    check("zostáva v Spoločný 2", ShoppingList.left(two, ShopListKind.SHARED) == 2)
    check("súčet bez filtra sú oba", ShoppingList.left(two) == 3)
    two = ShoppingList.add(two, "cestoviny", 4, id2, list = ShopListKind.MINE)
    check("v tom istom zozname sa nekúpená nezduplikuje", ShoppingList.of(two, ShopListKind.MINE).filter { !it.deleted }.size == 1)

    val legacyItems = ShoppingList.fromJson("""[{"i":"a","n":"Maslo","d":false,"a":1,"t":0}]""")
    check("starý JSON bez poľa l je spoločný (nič sa nestratí)", legacyItems.size == 1 && legacyItems[0].list == ShopListKind.SHARED && legacyItems[0].name == "Maslo")
    val mineJson = ShoppingList.fromJson(ShoppingList.toJson(listOf(ShopItem("p", "Sponky", false, 1, list = ShopListKind.MINE))))
    check("Môj ide do JSON a späť", mineJson[0].list == ShopListKind.MINE && mineJson[0].name == "Sponky")
    check("v spoločnom JSON nie je zbytočné l", !ShoppingList.toJson(listOf(ShopItem("s", "Chlieb", false, 1))).contains("\"l\""))

    var mv = emptyList<ShopItem>()
    mv = ShoppingList.add(mv, "Darček pre mamu", 10, { "priv" }, list = ShopListKind.MINE)
    mv = ShoppingList.move(mv, "priv", 20, { "unused" }, "me")
    val afterToShared = mv.first { it.id == "priv" }
    check("presun Môj → Spoločný nechá to isté id", afterToShared.list == ShopListKind.SHARED && !afterToShared.deleted)
    check("presun Môj → Spoločný je v synchronizácii", ShoppingList.forSync(mv).any { it.id == "priv" && !it.deleted })

    mv = ShoppingList.move(mv, "priv", 30, { "copy" }, "me")
    val tomb = mv.first { it.id == "priv" }
    val copy = mv.first { it.id == "copy" }
    check("presun Spoločný → Môj pošle tombstone pôvodného id", tomb.deleted && tomb.list == ShopListKind.SHARED)
    check("presun Spoločný → Môj urobí novú súkromnú kópiu", copy.list == ShopListKind.MINE && !copy.deleted && copy.name == "Darček pre mamu")
    check("tombstone spoločnej sa synchronizuje, kópia nie", ShoppingList.forSync(mv).let { s -> s.any { it.id == "priv" && it.deleted } && s.none { it.id == "copy" } })

    var clash = emptyList<ShopItem>()
    clash = ShoppingList.add(clash, "Mlieko", 1, { "m1" }, list = ShopListKind.MINE)
    clash = ShoppingList.add(clash, "Mlieko", 2, { "s1" }, list = ShopListKind.SHARED)
    clash = ShoppingList.move(clash, "m1", 3, { "x" }, "me")
    check("presun do zoznamu, ktorý už má rovnaký názov, nezduplikuje", clash.count { !it.deleted && it.name == "Mlieko" } == 1)
    check("zdroj súkromnej pri zlúčení zmizne", clash.first { it.id == "m1" }.deleted && clash.first { it.id == "s1" }.list == ShopListKind.SHARED && !clash.first { it.id == "s1" }.deleted)

    var done = emptyList<ShopItem>()
    done = ShoppingList.add(done, "Chlieb", 1, { "c1" }, list = ShopListKind.SHARED)
    done = ShoppingList.add(done, "Sponky", 2, { "c2" }, list = ShopListKind.MINE)
    done = ShoppingList.toggle(done, "c1", 3)
    done = ShoppingList.toggle(done, "c2", 4)
    done = ShoppingList.tombstoneDone(done, 5, "me", ShopListKind.SHARED)
    check("Vymazať kúpené len v aktuálnom zozname", done.first { it.id == "c1" }.deleted && !done.first { it.id == "c2" }.deleted)

    val mixed = listOf(
        ShopItem("a", "A", false, 1, list = ShopListKind.MINE),
        ShopItem("b", "B", false, 1, list = ShopListKind.SHARED),
        ShopItem("c", "C", false, 1, deleted = true, list = ShopListKind.MINE),
        ShopItem("d", "D", false, 1, deleted = true, list = ShopListKind.SHARED),
    )
    check("do synchronizácie ide len spoločný (aj tombstone)", ShoppingList.forSync(mixed).map { it.id } == listOf("b", "d"))
    check("podnadpis Oba ukáže oba počty", ShoppingList.viewSubtitle(ShopView.BOTH, two).contains("Môj") && ShoppingList.viewSubtitle(ShopView.BOTH, two).contains("Spoločný"))
    check("ShopView zo skratky", ShopView.from("m") == ShopView.MINE && ShopView.from("s") == ShopView.SHARED && ShopView.from(null) == ShopView.BOTH)

    println("Všetko v poriadku")
}

