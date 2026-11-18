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
    println("Všetko v poriadku")
}
