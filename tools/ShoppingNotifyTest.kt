import sk.domov.ovladanie.*

// Test upozornení na zmeny spoločného nákupu (1.68): čo je zmena, zbieranie neprečítaných zmien, texty a uloženie.

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

private const val ME = "phone-me"
private const val KATKA = "phone-katka"

private fun item(
    id: String = "i1",
    name: String = "Mlieko",
    done: Boolean = false,
    addedAt: Long = 1000,
    by: String = KATKA,
    byName: String = "Katka",
    updatedAt: Long = 1000,
    deleted: Boolean = false,
    device: String = KATKA,
    list: ShopListKind = ShopListKind.SHARED,
) = ShopItem(id, name, done, addedAt, if (done) updatedAt else 0L, by, byName, updatedAt, deleted, device, list)

fun main() {
    // ---- change(): čo je zmena
    var c = ShoppingChanges.change(null, item(), ME)
    check("nová vec od druhého telefónu = pridané", c?.kind == ShopChangeKind.ADDED && c.name == "Mlieko")
    check("meno z položky, keď ju pridal ten istý telefón", c?.actorName == "Katka" && c.actorId == KATKA)

    check("zmena z tohto telefónu sa nehlási", ShoppingChanges.change(null, item(device = ME, by = ME), ME) == null)
    check("súkromná položka sa nehlási", ShoppingChanges.change(null, item(list = ShopListKind.MINE), ME) == null)

    val live = item()
    c = ShoppingChanges.change(live, live.copy(done = true, doneAt = 2000, updatedAt = 2000, deviceId = KATKA), ME)
    check("odškrtnutie = kúpené", c?.kind == ShopChangeKind.DONE && c.name == "Mlieko")

    val bought = live.copy(done = true, doneAt = 2000, updatedAt = 2000)
    c = ShoppingChanges.change(bought, bought.copy(done = false, doneAt = 0, updatedAt = 3000), ME)
    check("vrátenie medzi nekúpené = vrátené", c?.kind == ShopChangeKind.UNDONE)
    c = ShoppingChanges.change(bought, bought.copy(done = false, doneAt = 0, addedAt = 3000, updatedAt = 3000), ME)
    check("kúpená vec pridaná znova (nový čas pridania) = pridané", c?.kind == ShopChangeKind.ADDED)

    c = ShoppingChanges.change(live, live.copy(name = "Mlieko 2 l", updatedAt = 2000), ME)
    check("premenovanie", c?.kind == ShopChangeKind.RENAMED && c.name == "Mlieko 2 l" && c.oldName == "Mlieko")

    c = ShoppingChanges.change(live, live.copy(deleted = true, updatedAt = 2000), ME)
    check("odstránenie nekúpenej veci", c?.kind == ShopChangeKind.REMOVED && c.name == "Mlieko")
    check("odstránenie už kúpenej veci je upratovanie (Vymazať kúpené)", ShoppingChanges.change(bought, bought.copy(deleted = true, updatedAt = 4000), ME) == null)
    check("tombstone veci, ktorú tu nikdy nebolo", ShoppingChanges.change(null, item(deleted = true), ME) == null)
    check("tombstone tombstonu", ShoppingChanges.change(live.copy(deleted = true), live.copy(deleted = true, updatedAt = 5000), ME) == null)

    check("zmena len času a autora nie je zmena", ShoppingChanges.change(live, live.copy(updatedAt = 2000, addedByName = "K."), ME) == null)

    c = ShoppingChanges.change(live.copy(deleted = true), live.copy(deleted = false, updatedAt = 3000), ME)
    check("obnovená vec (Späť, zmazaná a pridaná znova) = pridané", c?.kind == ShopChangeKind.ADDED)
    c = ShoppingChanges.change(null, bought, ME)
    check("vec, ktorá k nám príde už kúpená = kúpené", c?.kind == ShopChangeKind.DONE)

    c = ShoppingChanges.change(live, live.copy(done = true, doneAt = 2000, updatedAt = 2000, deviceId = "phone-x", addedByName = "Katka"), ME)
    check("meno sa nepridá, keď zmenu urobil iný telefón ako ten, čo vec pridal", c?.actorName == "")

    // ---- accumulate(): zbieranie neprečítaných
    val now = 1_800_000_000_000L
    fun ch(kind: ShopChangeKind, name: String, who: String = "Katka", at: Long = now, old: String = "") =
        ShopChange(kind, name, "id-$who", who, old, at)

    var u = ShoppingChanges.accumulate(emptyList(), listOf(ch(ShopChangeKind.ADDED, "Mlieko")), now)
    u = ShoppingChanges.accumulate(u, listOf(ch(ShopChangeKind.ADDED, "Chlieb")), now)
    check("zmeny sa zbierajú v poradí", u.map { it.name } == listOf("Mlieko", "Chlieb"))

    u = ShoppingChanges.accumulate(u, listOf(ch(ShopChangeKind.REMOVED, "mlieko")), now)
    check("pridané a hneď odstránené sa vzájomne zruší (aj bez ohľadu na veľkosť písmen)", u.map { it.name } == listOf("Chlieb"))

    u = ShoppingChanges.accumulate(u, listOf(ch(ShopChangeKind.DONE, "Chlieb")), now)
    u = ShoppingChanges.accumulate(u, listOf(ch(ShopChangeKind.UNDONE, "Chlieb")), now)
    check("kúpené a hneď vrátené sa zruší", u.map { it.kind to it.name } == listOf(ShopChangeKind.ADDED to "Chlieb"))

    u = ShoppingChanges.accumulate(u, listOf(ch(ShopChangeKind.ADDED, "Chlieb")), now + 5)
    check("rovnaká zmena dvakrát ostane raz, s novším časom", u.size == 1 && u[0].at == now + 5)
    u = ShoppingChanges.accumulate(emptyList(), listOf(ch(ShopChangeKind.ADDED, "Syr", at = 3)), now)
    check("čas zmeny je čas prijatia, nie hodiny odosielateľa", u.size == 1 && u[0].at == now)

    val oldOnes = listOf(ch(ShopChangeKind.ADDED, "Staré", at = now - ShoppingChanges.MAX_AGE_MS - 1))
    u = ShoppingChanges.accumulate(oldOnes, listOf(ch(ShopChangeKind.ADDED, "Nové")), now)
    check("staré zmeny (nad 24 h) sa zahodia", u.map { it.name } == listOf("Nové"))

    val many = (1..50).map { ch(ShopChangeKind.ADDED, "Vec $it") }
    u = ShoppingChanges.accumulate(emptyList(), many, now)
    check("najviac ${ShoppingChanges.MAX_UNSEEN} neprečítaných, ostanú najnovšie", u.size == ShoppingChanges.MAX_UNSEEN && u.last().name == "Vec 50" && u.first().name == "Vec 21")

    // všetko sa zrušilo -> prázdne
    u = ShoppingChanges.accumulate(
        ShoppingChanges.accumulate(emptyList(), listOf(ch(ShopChangeKind.ADDED, "Syr")), now),
        listOf(ch(ShopChangeKind.REMOVED, "Syr")), now,
    )
    check("po zrušení je zoznam prázdny", u.isEmpty())

    // ---- texty
    check("riadok: pridané", ShoppingChanges.line(ch(ShopChangeKind.ADDED, "Mlieko")) == "Pridané: Mlieko")
    check("riadok: kúpené", ShoppingChanges.line(ch(ShopChangeKind.DONE, "Mlieko")) == "Kúpené: Mlieko")
    check("riadok: odstránené", ShoppingChanges.line(ch(ShopChangeKind.REMOVED, "Mlieko")) == "Odstránené: Mlieko")
    check("riadok: vrátené", ShoppingChanges.line(ch(ShopChangeKind.UNDONE, "Mlieko")) == "Vrátené do zoznamu: Mlieko")
    check("riadok: premenované", ShoppingChanges.line(ch(ShopChangeKind.RENAMED, "Mlieko 2 l", old = "Mlieko")) == "Premenované: Mlieko → Mlieko 2 l")
    check("riadok: premenované bez starého názvu", ShoppingChanges.line(ch(ShopChangeKind.RENAMED, "Mlieko 2 l")) == "Premenované: Mlieko 2 l")

    check("nadpis bez mena", ShoppingChanges.title(listOf(ch(ShopChangeKind.ADDED, "A", who = ""))) == "Spoločný nákup")
    check("nadpis s jedným menom", ShoppingChanges.title(listOf(ch(ShopChangeKind.ADDED, "A"))) == "Nákup · Katka")
    check("nadpis s dvoma menami", ShoppingChanges.title(listOf(ch(ShopChangeKind.ADDED, "A"), ch(ShopChangeKind.ADDED, "B", who = "Tomáš"))) == "Nákup · Katka, Tomáš")
    check(
        "nadpis s tromi menami",
        ShoppingChanges.title(listOf(ch(ShopChangeKind.ADDED, "A"), ch(ShopChangeKind.ADDED, "B", who = "Tomáš"), ch(ShopChangeKind.ADDED, "C", who = "Eva"))) == "Nákup · Katka, Tomáš a ďalší",
    )
    check("meno sa neopakuje", ShoppingChanges.title(listOf(ch(ShopChangeKind.ADDED, "A"), ch(ShopChangeKind.DONE, "B", who = "katka"))) == "Nákup · Katka")

    check("súhrn jednej zmeny", ShoppingChanges.summary(listOf(ch(ShopChangeKind.ADDED, "Mlieko"))) == "Pridané: Mlieko")
    check(
        "súhrn: zoskupené podľa druhu",
        ShoppingChanges.summary(listOf(ch(ShopChangeKind.ADDED, "Mlieko"), ch(ShopChangeKind.DONE, "Maslo"), ch(ShopChangeKind.ADDED, "Chlieb"))) == "Pridané: Mlieko, Chlieb · Kúpené: Maslo",
    )
    check(
        "súhrn: viac ako 3 veci sa skráti",
        ShoppingChanges.summary((1..5).map { ch(ShopChangeKind.ADDED, "V$it") }) == "Pridané: V1, V2, V3 +2",
    )
    check("súhrn prázdneho", ShoppingChanges.summary(emptyList()) == "")

    var (lines, more) = ShoppingChanges.lines(listOf(ch(ShopChangeKind.ADDED, "Mlieko"), ch(ShopChangeKind.DONE, "Maslo")))
    check("riadky bez mena pri jednom človeku", lines == listOf("Pridané: Mlieko", "Kúpené: Maslo") && more == 0)
    val l2 = ShoppingChanges.lines(listOf(ch(ShopChangeKind.ADDED, "Mlieko"), ch(ShopChangeKind.DONE, "Maslo", who = "Tomáš")))
    check("riadky s menom, keď zmeny robilo viac ľudí", l2.first == listOf("Pridané: Mlieko (Katka)", "Kúpené: Maslo (Tomáš)"))
    val l3 = ShoppingChanges.lines((1..9).map { ch(ShopChangeKind.ADDED, "V$it") })
    check("viac riadkov ako sa zmestí: posledné a počet zvyšku", l3.first.size == ShoppingChanges.MAX_LINES && l3.first.last() == "Pridané: V9" && l3.second == 3)

    // ---- uloženie
    val saved = listOf(
        ch(ShopChangeKind.ADDED, "Mlieko"),
        ch(ShopChangeKind.RENAMED, "Mlieko 2 l", who = "Tomáš", old = "Mlieko", at = 77),
    )
    check("uloženie a načítanie", ShoppingChanges.fromJson(ShoppingChanges.toJson(saved)) == saved)
    check("poškodený záznam = prázdny zoznam", ShoppingChanges.fromJson("{ nie je json").isEmpty() && ShoppingChanges.fromJson(null).isEmpty() && ShoppingChanges.fromJson("").isEmpty())
    check("neznámy druh a prázdny názov sa preskočia", ShoppingChanges.fromJson("""[{"k":"?","n":"x"},{"k":"a","n":""},{"k":"a","n":"OK"}]""").map { it.name } == listOf("OK"))

    println("Všetko v poriadku.")
}
