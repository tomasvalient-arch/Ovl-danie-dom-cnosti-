import sk.domov.ovladanie.*

// Regresný test pádu vo verzii 1.7 (StackOverflowError): hľadanie v sieti -> onFound -> addOrUpdate -> saveDevices.
// Simuluje rovnakú cestu ako HomeViewModel s uloženými zariadeniami, bez Androidu.

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

/** Rovnaká logika ako HomeViewModel: zoznam v pamäti, ukladanie cez DeviceSaver, oprava IP z hľadania v sieti. */
class FakeVm(stored: String) {
    var storage: String? = stored
    var devices = DeviceList.parseStored(stored)
    var refreshed = 0
    val saver = DeviceSaver { list -> storage = exportStored(list) }.also { it.markSaved(devices) }

    fun addOrUpdate(d: Device) {
        devices = DeviceList.upsert(devices, d)
        saver.save(devices)
        refreshed++ // vo VM refresh(device) -> pri nedostupnosti autoFixIp (najviac raz za minútu)
    }

    fun onFound(found: FoundDevice) {
        val fixed = DeviceList.ipFix(devices, found) ?: return
        addOrUpdate(fixed)
    }
}

/** Formát rovnaký ako DeviceStore.save (pole objektov s id, name, ip, devId, localKey…). */
fun exportStored(list: List<Device>): String = org.json.JSONArray().apply {
    list.forEach { d ->
        put(org.json.JSONObject().put("id", d.id).put("name", d.name).put("ip", d.ip).put("devId", d.devId)
            .put("localKey", d.localKey).put("version", d.version).put("group", d.group).put("category", d.category))
    }
}.toString()

// uložené zariadenia z verzie 1.6 (bez poľa group) – presne tak, ako ich ukladala appka
const val STORED_16 = """[
 {"id":"11111111-aaaa","name":"Obývačka LED","ip":"192.168.1.20","devId":"bf01","localKey":"0123456789abcdef","version":"3.3",
  "labels":{"20":"Svetlo"},"hidden":["25"],"powerDp":"","whiteSwitchDp":"","colourSwitchDp":"","category":"dj"},
 {"id":"22222222-bbbb","name":"Zásuvka TV","ip":"192.168.1.21","devId":"bf02","localKey":"abcdef0123456789","version":"3.4",
  "labels":{},"hidden":[],"powerDp":"1","whiteSwitchDp":"","colourSwitchDp":"","category":"cz",
  "mapping":{"1":{"code":"switch_1","type":"Boolean","values":"{}"}}}
]"""

fun main() {
    // ---------------------------------------------------------------- načítanie uložených dát
    val vm = FakeVm(STORED_16)
    check("načíta uložené zariadenia z 1.6 bez straty", vm.devices.size == 2 && vm.devices[0].labels["20"] == "Svetlo" &&
        vm.devices[0].hidden == setOf("25") && vm.devices[1].powerDp == "1" && vm.devices[1].schema["1"]?.code == "switch_1")
    check("staré dáta bez skupiny -> automatická", vm.devices.all { it.group == "" })

    // ---------------------------------------------------------------- cesta, ktorá padala
    vm.onFound(FoundDevice("bf02", "192.168.1.50", "3.4", ""))
    check("nová IP z hľadania v sieti sa uloží (bez StackOverflowError)", vm.devices[1].ip == "192.168.1.50" && vm.saver.writes == 1)
    check("uložené dáta obsahujú novú IP", DeviceList.parseStored(vm.storage).first { it.devId == "bf02" }.ip == "192.168.1.50")
    repeat(100) { vm.onFound(FoundDevice("bf02", "192.168.1.50", "3.4", "")) }
    check("opakované správy z tej istej adresy nič neukladajú ani neobnovujú", vm.saver.writes == 1 && vm.refreshed == 1)
    vm.onFound(FoundDevice("cudzie", "192.168.1.99", "3.3", ""))
    vm.onFound(FoundDevice("bf01", "", "3.3", ""))
    check("neznáme zariadenie a prázdna IP nič nemenia", vm.saver.writes == 1 && vm.devices[0].ip == "192.168.1.20")

    // ---------------------------------------------------------------- ukladanie nie je rekurzívne a je idempotentné
    var depth = 0
    var maxDepth = 0
    lateinit var reentrant: DeviceSaver
    var calls = 0
    reentrant = DeviceSaver { list ->
        depth++; maxDepth = maxOf(maxDepth, depth); calls++
        // napr. pozorovateľ, ktorý pri zápise znova volá ukladanie
        if (calls < 1000) reentrant.save(list + Device(name = "x$calls", ip = "", devId = "d$calls", localKey = ""))
        depth--
    }
    reentrant.save(vm.devices)
    check("zápis, ktorý znova volá ukladanie, nevedie k rekurzii", maxDepth == 1)
    check("opakované ukladanie má strop (nezacyklí sa)", calls <= 10)
    val s2 = DeviceSaver { }
    s2.save(vm.devices); s2.save(vm.devices); s2.save(vm.devices.toList())
    check("rovnaký zoznam sa zapíše len raz", s2.writes == 1)
    s2.save(vm.devices + vm.devices[0].copy(id = "novy"))
    check("zmenený zoznam sa zapíše", s2.writes == 2)
    val failing = DeviceSaver { throw IllegalStateException("disk") }
    check("chyba zápisu appku nezhodí", !failing.save(vm.devices))

    // ---------------------------------------------------------------- odolnosť voči reálnym dátam
    val messy = """[{"id":"a","name":"A","ip":"1.1.1.1","devId":"x1","localKey":"k"},
        {"id":"a","name":"Duplicitné id","devId":"x2"},
        {"name":"Bez id","devId":"x3"},
        {"name":"Bez devId"},
        42, null,
        {"id":"b","devId":"x4","mapping":"pokazené","labels":"zlé","hidden":"zlé"}]"""
    val parsed = DeviceList.parseStored(messy)
    check("poškodené položky sa preskočia, ostatné ostanú", parsed.map { it.devId } == listOf("x1", "x2", "x3", "x4"))
    check("duplicitné a chýbajúce id dostanú nové jedinečné", parsed.map { it.id }.toSet().size == 4 && parsed[0].id == "a")
    check("chýbajúce polia majú rozumné predvolené hodnoty", parsed[1].name == "Duplicitné id" && parsed[1].ip == "" && parsed[1].version == "3.3" && parsed[2].name == "Bez id")
    check("prázdne úložisko", DeviceList.parseStored(null).isEmpty() && DeviceList.parseStored("").isEmpty())
    check("import devices.json preskočí nezmyselné prvky",
        parseTinytuyaJson("""[1, null, {"name":"A","id":"x1","key":"0123456789abcdef"}]""").single().devId == "x1")

    println("DeviceListTest: všetko OK")
}
