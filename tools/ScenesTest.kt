import sk.domov.ovladanie.*

// Test skupín na hlavnej obrazovke a scén (Všetko vypnúť, Noc) bez Androidu.

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

fun dev(id: String, name: String, category: String = "", group: String = "") =
    Device(id = id, name = name, ip = "10.0.0.1", devId = "dev$id", localKey = "0123456789abcdef", category = category, group = group)

val LIGHT_ON = mapOf<String, Any>("20" to true, "21" to "white", "22" to 800, "23" to 500, "24" to "000003e803e8")
val LIGHT_OFF = mapOf<String, Any>("20" to false, "21" to "white", "22" to 500, "23" to 300)
val LIGHT_V1 = mapOf<String, Any>("1" to true, "2" to "white", "3" to 200, "4" to 100)
val DUAL = mapOf<String, Any>("20" to true, "22" to 600, "23" to 800, "24" to "00f003e803e8", "51" to true, "52" to true)
val SOCKET = mapOf<String, Any>("1" to true, "9" to 0, "17" to 5, "18" to 300, "19" to 650, "20" to 2300)
val STRIP = mapOf<String, Any>("1" to true, "2" to true, "3" to false)
val FRYER = mapOf<String, Any>("1" to true, "2" to false, "3" to "Chips", "5" to "standby", "6" to 180, "9" to 15)
val COVER = mapOf<String, Any>("1" to "stop", "2" to 40)

fun main() {
    val devices = listOf(
        dev("a", "Obývačka", "dj"),
        dev("b", "Zásuvka TV", "cz"),
        dev("c", "Spálňa", "dj"),
        dev("d", "Fritéza", "kfj"),
        dev("e", "Ohrievač", "qn"),
        dev("f", "Rolety"),
        dev("g", "Neznáme"),
        dev("h", "Lampa", "dj", group = "Obývačka"),
        dev("i", "Predlžovačka", "pc"),
    )
    val vals = mapOf("a" to LIGHT_ON, "b" to SOCKET, "c" to LIGHT_OFF, "d" to FRYER, "f" to COVER, "h" to LIGHT_V1, "i" to STRIP)
    val v: (Device) -> Map<String, Any> = { vals[it.id] ?: emptyMap() }

    // ---------------------------------------------------------------- skupiny
    check("auto skupina: svetlo", Groups.groupOf(devices[0], LIGHT_ON) == Groups.LIGHTS)
    check("auto skupina: zásuvka", Groups.groupOf(devices[1], SOCKET) == Groups.SOCKETS)
    check("auto skupina: fritéza a ohrievač sú spotrebiče", Groups.groupOf(devices[3], FRYER) == Groups.APPLIANCES && Groups.groupOf(devices[4], emptyMap()) == Groups.APPLIANCES)
    check("auto skupina: rolety a neznáme -> Ostatné", Groups.groupOf(devices[5], COVER) == Groups.OTHER && Groups.groupOf(devices[6], emptyMap()) == Groups.OTHER)
    check("vlastná skupina má prednosť", Groups.groupOf(devices[7], LIGHT_V1) == "Obývačka")
    val arranged = Groups.arrange(devices, v)
    check("poradie skupín: vlastné, Svetlá, Zásuvky, Spotrebiče, Ostatné",
        arranged.map { it.first } == listOf("Obývačka", Groups.LIGHTS, Groups.SOCKETS, Groups.APPLIANCES, Groups.OTHER))
    check("v skupine platí poradie zoznamu", arranged.first { it.first == Groups.LIGHTS }.second.map { it.id } == listOf("a", "c"))
    check("zásuvky: aj predlžovačka", arranged.first { it.first == Groups.SOCKETS }.second.map { it.id } == listOf("b", "i"))
    check("prázdne skupiny sa vynechajú", Groups.arrange(listOf(devices[0]), v).map { it.first } == listOf(Groups.LIGHTS))

    val moved = Groups.move(devices, "c", -1, v)
    check("posun dopredu vymení so susedom v skupine (preskočí iné skupiny)", moved.map { it.id } == listOf("c", "b", "a", "d", "e", "f", "g", "h", "i"))
    check("posun prvého dopredu nič nemení", Groups.move(devices, "a", -1, v) == devices)
    check("posun posledného dozadu nič nemení", Groups.move(devices, "c", 1, v) == devices)
    check("neznáme id nič nemení", Groups.move(devices, "zz", 1, v) == devices)
    check("posun späť vráti pôvodné poradie", Groups.move(moved, "c", 1, v).map { it.id } == devices.map { it.id })

    val regrouped = Groups.setGroup(devices, "a", "Obývačka")
    check("presun do skupiny: uloží názov a dá na koniec", regrouped.last().id == "a" && regrouped.last().group == "Obývačka")
    check("presun do skupiny Svetlá = späť na automatickú", Groups.setGroup(regrouped, "a", Groups.LIGHTS).last().group == "")
    check("vlastné skupiny bez automatických a duplicít",
        Groups.customGroups(regrouped + dev("x", "X", group = "Svetlá") + dev("y", "Y", group = " Obývačka ")) == listOf("Obývačka"))
    check("text skupiny", Groups.onText(2, 3) == "2 z 3 zapnuté")

    // export / import skupiny
    val json = exportDevicesJson(listOf(devices[7]))
    val back = parseTinytuyaJson(json)
    check("skupina sa exportuje a importuje", json.contains("\"group\"") && back.single().group == "Obývačka")
    check("export bez skupiny nemá pole group", !exportDevicesJson(listOf(devices[0])).contains("\"group\""))
    val merged = mergeImported(listOf(devices[7]), listOf(back.single().copy(group = "")))
    check("import bez skupiny ponechá pôvodnú", merged.single().group == "Obývačka")
    check("devices.json bez skupiny -> automatická", parseTinytuyaJson("[{\"name\":\"A\",\"id\":\"x1\",\"key\":\"0123456789abcdef\"}]").single().group == "")

    // ---------------------------------------------------------------- Všetko vypnúť
    val cfg = PresenceConfig()
    val allOff = Scenes.allOffIds(cfg, devices, v)
    check("Všetko vypnúť predvolene: svetlá a zásuvky", allOff == setOf("a", "b", "c", "h", "i"))
    check("Všetko vypnúť: spotrebiče len ak sú vybrané pri odchode",
        Scenes.allOffIds(cfg.copy(departIds = setOf("a", "d", "f")), devices, v) == setOf("a", "d"))
    val plan = Scenes.allOffPlan(cfg, devices, v).associate { it.first.id to it.second }
    check("príkaz svetla V2: 20=false", plan["a"] == mapOf("20" to false))
    check("príkaz svetla V1: 1=false", plan["h"] == mapOf("1" to false))
    check("viacnásobná zásuvka: všetky vypínače", plan["i"] == mapOf("1" to false, "2" to false, "3" to false))
    check("zásuvka s meraním: len vypínač", plan["b"] == mapOf("1" to false))
    check("rolety sa nevypínajú", Scenes.offCommand(devices[5], COVER) == null)
    check("svetlo s dvoma zdrojmi: aj vypínače sekcií",
        Scenes.offCommand(dev("z", "Strop").copy(whiteSwitchDp = "51", colourSwitchDp = "52"), DUAL) == mapOf("20" to false, "51" to false, "52" to false))
    check("bez známeho stavu nie je čo poslať", Scenes.offCommand(dev("q", "Q", "dj"), emptyMap()) == null)

    // ---------------------------------------------------------------- Noc
    val scene = NightScene(dimIds = setOf("c"), dimPercent = 10)
    val nightOff = Scenes.nightOffIds(scene, devices, v)
    check("Noc predvolene vypne svetlá a zásuvky okrem stlmených", nightOff == setOf("a", "b", "h", "i"))
    val night = Scenes.nightPlan(scene, devices, v)
    check("Noc: stlmené svetlo je prvé", night.first().first.id == "c")
    check("Noc: stlmenie = zapnúť, biela, 10 %, teplá (V2)",
        night.first().second == mapOf("20" to true, "21" to "white", "22" to 100, "23" to 0))
    check("Noc: stlmenie V1 (jas 25–255, 10 % = 46)", Scenes.dimCommand(devices[7], LIGHT_V1, 10) == mapOf("1" to true, "2" to "white", "3" to 46, "4" to 0))
    check("Noc: svetlo s dvoma zdrojmi -> biela sekcia zapnutá, farebná vypnutá",
        Scenes.dimCommand(dev("z", "Strop").copy(whiteSwitchDp = "51", colourSwitchDp = "52"), DUAL, 20) ==
            mapOf("20" to true, "51" to true, "52" to false, "22" to 200, "23" to 0))
    check("Noc: zásuvku stlmiť nejde", Scenes.dimCommand(devices[1], SOCKET, 10) == null)
    check("Noc: vlastný výber vypínaných (aj spotrebič)",
        Scenes.nightOffIds(NightScene(offIds = setOf("a", "d", "c"), dimIds = setOf("c")), devices, v) == setOf("a", "d"))
    val sj = NightScene(offIds = setOf("a"), dimIds = setOf("c", "h"), dimPercent = 15)
    check("Noc: uloženie a načítanie", NightScene.fromJson(sj.toJson()) == sj)
    check("Noc: predvolené (null) sa zachová", NightScene.fromJson(NightScene(dimIds = setOf("c")).toJson()).offIds == null)
    check("Noc: poškodené dáta -> predvolené", NightScene.fromJson("{xx") == NightScene() && NightScene.fromJson(null) == NightScene())
    check("slovenské tvary", Scenes.devicesText(1) == "1 zariadenie" && Scenes.devicesText(3) == "3 zariadenia" && Scenes.devicesText(7) == "7 zariadení")

    println("Všetky testy skupín a scén prešli.")
}
