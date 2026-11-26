import org.json.JSONObject
import sk.domov.ovladanie.Events
import sk.domov.ovladanie.EventItem
import sk.domov.ovladanie.HouseSync
import sk.domov.ovladanie.ShoppingList
import sk.domov.ovladanie.ShopItem


fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

fun main() {
    val key = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef" // 32 znakov, tvar kľúča nie je tu dôležitý
    val topic = HouseSync.topic(key)
    check("téma začína domov a má 37 znakov", topic.startsWith("domov") && topic.length == 37)
    check("téma je hex za prefixom", topic.drop(5).all { it in "0123456789abcdef" })
    check("rovnaký kľúč → rovnaká téma", HouseSync.topic(key) == topic)
    check("iný kľúč → iná téma", HouseSync.topic(key + "x") != topic)

    val aes = HouseSync.aesKey(key)
    check("AES kľúč 32 B", aes.size == 32)
    check("téma a AES kľúč sa líšia", !HouseSync.sha256(aes).startsWith(topic.drop(5)))

    val plain = "ahoj, toto je tajný zoznam nákupu 🛒".toByteArray(Charsets.UTF_8)
    val token = HouseSync.encrypt(plain, aes)
    check("token je URL-safe", token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    check("dešifrovanie vráti pôvodný text", HouseSync.decrypt(token, aes)!!.toString(Charsets.UTF_8) == "ahoj, toto je tajný zoznam nákupu 🛒")
    check("zlý kľúč sa nedešifruje", HouseSync.decrypt(token, HouseSync.aesKey("iny-kluc-nie-je-ten-isty-xxxxx")) == null)
    check("poškodený token je null", HouseSync.decrypt("toto-nie-je-token", aes) == null)
    check("dva šifrované texty sú rôzne (náhodný nonce)", HouseSync.encrypt(plain, aes) != token)

    val a = HouseSync.record("event", "id1", 100, "aa", false, JSONObject().put("n", "Zubár"))
    val b = HouseSync.record("event", "id1", 200, "bb", false, JSONObject().put("n", "Zubár 2"))
    val older = HouseSync.record("event", "id1", 50, "cc", true, JSONObject().put("n", "Zubár"))
    check("novší záznam vyhrá", HouseSync.merge(a, b).optLong("u") == 200L)
    check("starší príchod nevyhrá", HouseSync.merge(b, older).optLong("u") == 200L)
    val tie1 = HouseSync.record("event", "id1", 200, "aa", false, JSONObject().put("n", "A"))
    val tie2 = HouseSync.record("event", "id1", 200, "zz", true, JSONObject().put("n", "Z"))
    check("pri remíze vyhrá vyššie id telefónu", HouseSync.merge(tie1, tie2).optString("d") == "zz" && tie2.optBoolean("x"))

    val local = mapOf(HouseSync.keyOf(a) to a)
    val (merged, changed) = HouseSync.applyIncoming(local, listOf(b, older))
    check("aplikuje sa len novší", changed == listOf("event:id1") && merged["event:id1"]!!.optLong("u") == 200L)
    val (_, none) = HouseSync.applyIncoming(merged, listOf(b))
    check("rovnaký príchod sa znova nezapisuje", none.isEmpty())

    val tomb = HouseSync.record("shop", "s1", 1L, "aa", true, JSONObject().put("n", "Chlieb"))
    val fresh = HouseSync.record("shop", "s2", System.currentTimeMillis(), "aa", true, JSONObject().put("n", "Maslo"))
    val live = HouseSync.record("shop", "s3", 1L, "aa", false, JSONObject().put("n", "Mlieko"))
    val pruned = HouseSync.pruneTombstones(listOf(tomb, fresh, live), System.currentTimeMillis())
    check("starý tombstone zmizne, nový a živá ostanú", pruned.map { it.optString("i") }.toSet() == setOf("s2", "s3"))

    val items = (1..40).map { HouseSync.record("shop", "i$it", it.toLong(), "aa", false, JSONObject().put("n", "x".repeat(80))) }
    val packs = HouseSync.pack("phone1", HouseSync.TYPE_ITEMS, items)
    check("balenie rozdelí veľký zoznam", packs.size >= 2)
    check("každá obálka sa zmestí", packs.all { it.length <= HouseSync.MAX_PLAIN + 200 })
    val want = HouseSync.pack("phone1", HouseSync.TYPE_WANT, emptyList())
    check("want je jedna obálka", want.size == 1 && HouseSync.parseEnvelope(want[0])!!.optString("t") == "want")

    val ev = EventItem("e1", "Zubár", false, 2026, 11, 24, false, listOf(0), startMin = 14 * 60, endMin = 15 * 60, updatedAt = 9L, deviceId = "me")
    val back = Events.fromJson(Events.toJson(listOf(ev)))[0]
    check("udalosť s časom ide do JSON a späť", back.startMin == 14 * 60 && back.endMin == 15 * 60 && back.updatedAt == 9L)
    val oldEv = Events.fromJson("""[{"i":"s","n":"Ok","b":true,"y":1990,"m":3,"d":4,"r":true,"o":[7,0]}]""")[0]
    check("starý záznam má predvolené nové polia", oldEv.startMin == -1 && !oldEv.deleted && oldEv.audience == "both")

    val shop = ShopItem("s", "Mlieko", false, 5, 0, "id1", "Tomáš", 10, false, "id1")
    val shopBack = ShoppingList.fromJson(ShoppingList.toJson(listOf(shop)))[0]
    check("nákup s autorom ide do JSON a späť", shopBack.addedByName == "Tomáš" && shopBack.updatedAt == 10L)
    val oldShop = ShoppingList.fromJson("""[{"i":"a","n":"Maslo","d":false,"a":1,"t":0}]""")[0]
    check("starý nákup sa načíta", oldShop.name == "Maslo" && oldShop.addedByName.isEmpty() && !oldShop.deleted)

    check("stav: pred chvíľou", HouseSync.statusText(true, false, System.currentTimeMillis() - 10_000, "", System.currentTimeMillis()) == "Synchronizované pred chvíľou")
    check("stav: neaktívna domácnosť je prázdny", HouseSync.statusText(false, false, 0, "", 1L).isEmpty())
    check("stav: synchronizuje sa", HouseSync.statusText(true, true, 0, "", 1L) == "Synchronizuje sa…")
    check("stav: prvá chyba je pravdivá, nie Čaká na sieť",
        HouseSync.statusText(true, false, 0, "http 400: invalid since parameter", 1L) == "Synchronizácia zlyhala: http 400: invalid since parameter")
    check("stav: ešte bez pokusu", HouseSync.statusText(true, false, 0, "", 1L) == "Zatiaľ nesynchronizované")
    check("stav: neskoršia chyba ukáže aj posledný úspech",
        HouseSync.statusText(true, false, 1L, "čas vypršal", 1L + 3 * 60_000).contains("čas vypršal") &&
            HouseSync.statusText(true, false, 1L, "čas vypršal", 1L + 3 * 60_000).contains("pred"))
    check("since: prázdny a staré 3d idú na 72h", HouseSync.sinceParam("") == "72h" && HouseSync.sinceParam("3d") == "72h" && HouseSync.sinceParam("3D") == "72h")
    check("since: platné id správy ostane", HouseSync.sinceParam("9SoOY7su9HUQ") == "9SoOY7su9HUQ")

    check("pending: po odoslaní A,B ostane C pridané počas prenosu",
        HouseSync.remainingPending(listOf("e:A", "e:B", "e:C"), listOf("e:A", "e:B")) == listOf("e:C"))
    check("pending: úplný snímok neodstráni kľúč pridaný počas odosielania",
        HouseSync.remainingPending(listOf("e:A", "e:B", "e:C"), listOf("e:A", "e:B")) == listOf("e:C"))
    check("pending: prázdne odoslanie frontu nemení",
        HouseSync.remainingPending(listOf("e:A"), emptyList()) == listOf("e:A"))
    check("pending: vyprázdnenie len odoslaných",
        HouseSync.remainingPending(listOf("e:A"), listOf("e:A")) == emptyList<String>())
    check("LWW: novší updatedAt vyhrá", HouseSync.incomingWins(10L, "z", 20L, "a"))
    check("LWW: starší príchod prehrá", !HouseSync.incomingWins(20L, "a", 10L, "z"))
    check("LWW: pri rovnosti vyšší deviceId vyhrá", HouseSync.incomingWins(10L, "phone1", 10L, "phone2"))
    check("LWW: pri rovnosti nižší deviceId prehrá", !HouseSync.incomingWins(10L, "phone2", 10L, "phone1"))
    val pantryNew = HouseSync.record(HouseSync.KIND_PANTRY, "p1", 20, "bb", false, JSONObject().put("n", "Mlieko"))
    val pantryOld = HouseSync.record(HouseSync.KIND_PANTRY, "p1", 10, "aa", false, JSONObject().put("n", "Mlieko"))
    val (pantryMerged, pantryChanged) = HouseSync.applyIncoming(mapOf(HouseSync.keyOf(pantryOld) to pantryOld), listOf(pantryNew))
    check("zásoby (pantry) sa prijmú a novší vyhrá", pantryChanged == listOf("pantry:p1") && pantryMerged["pantry:p1"]!!.optLong("u") == 20L)
    val mealRec = HouseSync.record(HouseSync.KIND_MEAL, "m1", 5, "aa", false, JSONObject().put("t", "Kurča"))
    val recipeRec = HouseSync.record(HouseSync.KIND_RECIPE, "r1", 5, "aa", false, JSONObject().put("n", "Kurča"))
    val (mealIn, mealCh) = HouseSync.applyIncoming(emptyMap(), listOf(mealRec, recipeRec))
    check("plán a recept sa prijmú", mealCh.toSet() == setOf("meal:m1", "recipe:r1") && "meal:m1" in mealIn && "recipe:r1" in mealIn)
    val typoKind = HouseSync.record("recept", "r-x", 1, "aa", false, JSONObject().put("n", "X"))
    val otherKind = HouseSync.record("fryer", "f1", 1, "aa", false, JSONObject().put("n", "X"))
    val (skipMap, skipCh) = HouseSync.applyIncoming(emptyMap(), listOf(typoKind, otherKind))
    check("neznámy typ (preklep aj iný) sa preskočí ako doteraz", skipMap.isEmpty() && skipCh.isEmpty())
    val pantryTomb = HouseSync.record(HouseSync.KIND_PANTRY, "oldp", 1L, "aa", true, JSONObject().put("n", "X"))
    val pantryLive = HouseSync.record(HouseSync.KIND_RECIPE, "liver", System.currentTimeMillis(), "aa", false, JSONObject().put("n", "Y"))
    val prunedMeals = HouseSync.pruneTombstones(listOf(pantryTomb, pantryLive), System.currentTimeMillis())
    check("starý tombstone zásob zmizne, živý recept ostane", prunedMeals.map { it.optString("i") } == listOf("liver"))

    println("HouseSyncTest OK")
}

