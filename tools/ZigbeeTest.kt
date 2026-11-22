import org.json.JSONArray
import org.json.JSONObject
import sk.domov.ovladanie.*
import java.io.IOException

// Zigbee cez Tuya bránu: smerovanie podzariadení, senzory, uloženie/import, zisťovanie brány z cloudu (bez Androidu).

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

private fun schemaOf(vararg e: Triple<String, String, Pair<DpType, Int>>): Map<String, DpSchema> =
    e.associate { (dp, code, ts) -> dp to DpSchema(code = code, type = ts.first, scale = ts.second, unit = if (code == "va_temperature") "°C" else "") }

private fun gw(ip: String = "192.168.1.9", key: String = "0123456789abcdef", ver: String = "3.4") =
    Device(id = "g", name = "Brána", ip = ip, devId = "gw1", localKey = key, version = ver, category = "wg2")

private fun sub(id: String, cid: String, category: String, schema: Map<String, DpSchema> = emptyMap()) =
    Device(id = id, name = "Zigbee $id", ip = "", devId = "sub-$id", localKey = "", category = category, schema = schema, gatewayId = "gw1", cid = cid)

class FakeTransport(private val reply: (String) -> String) : HttpTransport {
    val urls = mutableListOf<String>()
    override fun execute(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
        urls += url
        return HttpResult(200, reply(url))
    }
}

fun main() {
    // ---------------------------------------------------------------- smerovanie cez bránu
    val g = gw()
    val door = sub("door", "node-door", "mcs")
    Gateways.update(listOf(g, door))
    val r = Gateways.route(door)
    check("podzariadenie sa pripája na IP, kľúč, ID a verziu brány", r.ip == "192.168.1.9" && r.devId == "gw1" && r.localKey == "0123456789abcdef" && r.version == "3.4")
    check("podzariadenie posiela svoje cid", r.cid == "node-door")
    check("samostatné zariadenie ide priamo", Gateways.route(g).let { it.devId == "gw1" && it.cid == "" && it.ip == "192.168.1.9" })
    check("zámok je spoločný pre bránu aj jej podzariadenia", Gateways.lockKey(door) == Gateways.lockKey(g) && Gateways.lockKey(g) == "gw1")
    check("brána sa pozná podľa podzariadení aj kategórie", Gateways.isGateway(g) && !Gateways.isGateway(door) &&
        Gateways.isGateway(g.copy(category = "xx").also { Gateways.update(listOf(it, door)) }))
    check("počet podzariadení brány", Gateways.childCount(g) == 1)
    Gateways.update(listOf(g, door))

    Gateways.update(listOf(door))
    check("bez brány v appke vyhodí zrozumiteľnú chybu", runCatching { Gateways.route(door) }.exceptionOrNull().let { it is IOException && it.message!!.contains("Brána") })
    Gateways.update(listOf(gw(ip = ""), door))
    check("brána bez IP: chyba s návodom", runCatching { Gateways.route(door) }.exceptionOrNull()?.message?.contains("IP") == true)
    Gateways.update(listOf(gw(key = ""), door))
    check("brána bez local key: chyba s návodom", runCatching { Gateways.route(door) }.exceptionOrNull()?.message?.contains("local key") == true)
    Gateways.update(listOf(g, door))

    // ---------------------------------------------------------------- senzory
    val doorSchema = schemaOf(
        Triple("1", "doorcontact_state", DpType.BOOLEAN to 0),
        Triple("3", "battery_percentage", DpType.INTEGER to 0),
        Triple("4", "temper_alarm", DpType.BOOLEAN to 0),
    )
    val d1 = sub("d1", "c1", "mcs", doorSchema)
    val open = Sensors.read(d1, mapOf("1" to true, "3" to 87, "4" to false))!!
    check("dvere: otvorené = poplach, batéria 87 %", open.kind == SensorKind.DOOR && open.title == "Otvorené" && open.alarm && open.battery == 87 && !open.batteryLow && !open.tamper)
    val closed = Sensors.read(d1, mapOf("1" to false, "3" to 12, "4" to true))!!
    check("dvere: zatvorené, slabá batéria, otvorený kryt", closed.title == "Zatvorené" && !closed.alarm && closed.batteryLow && closed.tamper)
    check("text na dlaždici pri slabej batérii", Sensors.tileText(closed) == "Zatvorené · slabá batéria")
    check("sumár so batériou", Sensors.summary(open) == "Otvorené · Batéria 87 %")
    check("senzor bez hodnôt čaká", Sensors.read(d1, emptyMap())!!.let { !it.known && Sensors.tileText(it) == "Čakám na hodnoty" })
    check("dvere bez popisu z cloudu: podľa kategórie, DP 1", Sensors.read(sub("d2", "c2", "mcs"), mapOf("1" to false))!!.title == "Zatvorené")

    val pirSchema = schemaOf(Triple("1", "pir", DpType.ENUM to 0), Triple("4", "battery_percentage", DpType.INTEGER to 0))
    val pir = sub("p1", "c3", "pir", pirSchema)
    check("pohyb zachytený", Sensors.read(pir, mapOf("1" to "pir", "4" to 100))!!.let { it.title == "Pohyb" && it.alarm })
    check("bez pohybu", Sensors.read(pir, mapOf("1" to "none", "4" to 100))!!.let { it.title == "Bez pohybu" && !it.alarm })

    val climateSchema = schemaOf(
        Triple("1", "va_temperature", DpType.INTEGER to 1),
        Triple("2", "va_humidity", DpType.INTEGER to 0),
        Triple("4", "battery_percentage", DpType.INTEGER to 0),
    )
    val cl = sub("t1", "c4", "wsdcg", climateSchema)
    val c1 = Sensors.read(cl, mapOf("1" to 214, "2" to 48, "4" to 100))!!
    check("teplota 21,4 °C a vlhkosť 48 %", c1.title == "21,4 °C" && c1.detail == "Vlhkosť 48 %" && !c1.alarm && Sensors.tileText(c1) == "21,4 °C · 48 %")
    check("záporná teplota", Sensors.read(cl, mapOf("1" to -35, "2" to 70))!!.title == "-3,5 °C")
    check("teplota bez popisu z cloudu sa berie ×10", Sensors.read(sub("t2", "c5", "wsdcg"), mapOf("1" to 225, "2" to 50))!!.title == "22,5 °C")
    check("len vlhkosť", Sensors.read(cl, mapOf("2" to 55))!!.title == "55 %")

    val battState = schemaOf(Triple("1", "doorcontact_state", DpType.BOOLEAN to 0), Triple("3", "battery_state", DpType.ENUM to 0))
    check("batéria podľa stavu low", Sensors.read(sub("b1", "c6", "mcs", battState), mapOf("1" to false, "3" to "low"))!!.let { it.batteryLow && it.battery == null && it.batteryText == "Batéria slabá" })

    val leak = sub("w1", "c7", "sj", schemaOf(Triple("1", "watersensor_state", DpType.ENUM to 0)))
    check("voda: poplach a pokoj", Sensors.read(leak, mapOf("1" to "alarm"))!!.let { it.alarm && it.title == "Únik vody" } &&
        Sensors.read(leak, mapOf("1" to "normal"))!!.let { !it.alarm && it.title == "Sucho" })
    val smoke = sub("s1", "c8", "ywbj", schemaOf(Triple("1", "smoke_sensor_status", DpType.ENUM to 0)))
    check("dym", Sensors.read(smoke, mapOf("1" to "alarm"))!!.title == "Dym")

    val multi = sub("m1", "c9", "pir", schemaOf(Triple("1", "pir", DpType.ENUM to 0), Triple("2", "va_temperature", DpType.INTEGER to 1)))
    check("pri pohybe aj teplote má prednosť pohyb", Sensors.kind(multi) == SensorKind.MOTION)

    val btn = sub("k1", "c10", "wxkg", schemaOf(Triple("1", "switch_mode1", DpType.ENUM to 0)))
    check("tlačidlo je senzor bez ovládania", Sensors.kind(btn) == SensorKind.BUTTON && Sensors.read(btn, emptyMap())!!.known)

    val socket = sub("z1", "c11", "cz", schemaOf(Triple("1", "switch_1", DpType.BOOLEAN to 0)))
    check("zásuvka nie je senzor", Sensors.kind(socket) == null && Sensors.read(socket, mapOf("1" to true)) == null)
    check("zásuvka má normálny vypínač", powerDpOf(socket, mapOf("1" to true)) == "1")
    check("senzor nikdy nemá vypínač (aj keď má hodnotu zapnuté/vypnuté)", powerDpOf(d1, mapOf("1" to true, "4" to false)) == null)
    check("brána nemá vypínač", powerDpOf(g, mapOf("1" to true)) == null)

    // ---------------------------------------------------------------- automatika a skupiny
    check("druh: senzor a brána", AutoDevices.kind(d1, emptyMap()) == AutoKind.SENSOR && AutoDevices.kind(g, emptyMap()) == AutoKind.GATEWAY)
    check("senzor a brána sa nikdy nevypínajú ani nezapínajú",
        !AutoDevices.canDepart(AutoKind.SENSOR) && !AutoDevices.canDepart(AutoKind.GATEWAY) &&
            !AutoDevices.canArrive(AutoKind.SENSOR) && !AutoDevices.canArrive(AutoKind.GATEWAY) &&
            !AutoDevices.defaultDepart(AutoKind.SENSOR) && !AutoDevices.defaultDepart(AutoKind.GATEWAY))
    check("zásuvka cez bránu je zásuvka", AutoDevices.kind(socket, mapOf("1" to true)) == AutoKind.SOCKET)
    check("skupina Senzory", Groups.groupOf(d1, emptyMap()) == Groups.SENSORS && Groups.groupOf(g, emptyMap()) == Groups.SENSORS)
    val arranged = Groups.arrange(listOf(d1, socket, g)) { emptyMap() }.map { it.first }
    check("poradie skupín: zásuvky pred senzormi", arranged == listOf(Groups.SOCKETS, Groups.SENSORS))
    check("senzor ani brána nie sú rizikové zariadenia (netreba ich čítať pri hromadnom vypnutí)", !SafeOff.risky(d1, emptyMap()) && !SafeOff.risky(g, emptyMap()))

    // ---------------------------------------------------------------- export, import, uloženie
    val list = listOf(g, d1, socket)
    val exported = exportDevicesJson(list)
    val back = parseTinytuyaJson(exported)
    check("export a import zachovajú podzariadenia", back.size == 3 && back[1].isSub && back[1].gatewayId == "gw1" && back[1].cid == "c1" && back[1].localKey == "")
    check("brána po importe ostáva samostatná", !back[0].isSub && back[0].localKey == "0123456789abcdef")
    check("import preskočí zariadenie so zlým kľúčom (ak nie je podzariadenie)", parseTinytuyaJson("""[{"id":"x","name":"Zlé","key":"krátky"}]""").isEmpty())
    check("import podzariadenia podľa tinytuya (parent + node_id)",
        parseTinytuyaJson("""[{"id":"s9","name":"Dvere","key":"0123456789abcdef","sub":true,"parent":"gw1","node_id":"abc123"}]""").single().let { it.isSub && it.cid == "abc123" && it.localKey == "" })
    val merged = mergeImported(list, listOf(back[1].copy(name = "Iný")))
    check("opakovaný import podzariadenie neduplikuje", merged.size == 3 && merged[1].isSub)

    val stored = JSONArray().put(JSONObject().put("id", "i1").put("name", "Dvere").put("devId", "sub-d1").put("gatewayId", "gw1").put("cid", "c1").put("category", "mcs")).toString()
    val parsed = DeviceList.parseStored(stored).single()
    check("uložené podzariadenie sa načíta", parsed.isSub && parsed.gatewayId == "gw1" && parsed.cid == "c1" && parsed.ip == "" && parsed.localKey == "")
    check("staré uložené zariadenia sú samostatné", !DeviceList.parseStored("""[{"id":"a","name":"x","ip":"1.2.3.4","devId":"d","localKey":"0123456789abcdef"}]""").single().isSub)

    // ---------------------------------------------------------------- Tuya cloud
    val cloud = CloudParse.devices(JSONObject("""{"result":{"devices":[
        {"id":"gw1","name":"Brána","local_key":"0123456789abcdef","category":"wg2","sub":false},
        {"id":"z1","name":"Dvere","local_key":"zzzzzzzzzzzzzzzz","category":"mcs","sub":true,"node_id":"n1","uuid":"u1","parent_id":"gw1"},
        {"id":"z2","name":"Pohyb","category":"pir","sub":true,"uuid":"u2"}]}}"""))
    check("cloud: node_id, brána a uuid", cloud[1].nodeId == "n1" && cloud[1].parentId == "gw1" && cloud[1].cid == "n1" && cloud[2].cid == "u2" && cloud[2].parentId == "")
    check("cloud: sub-devices ako pole alebo objekt",
        CloudParse.subDevices(JSONArray("""[{"id":"z1","node_id":"n1"},{"id":"z2","uuid":"u2"},{"name":"bez id"}]""")) == listOf("z1" to "n1", "z2" to "u2") &&
            CloudParse.subDevices(JSONObject("""{"devices":[{"id":"z3","node_id":"n3"}]}""")) == listOf("z3" to "n3") &&
            CloudParse.subDevices(JSONObject("{}")).isEmpty() && CloudParse.subDevices(null).isEmpty())

    val upd = mergeCloud(listOf(d1.copy(cid = "stare")), listOf(CloudDevice(id = "sub-d1", name = "Dvere", localKey = "zzzzzzzzzzzzzzzz", sub = true, nodeId = "nove", parentId = "gw1")), emptyMap())
    check("synchronizácia nekopíruje kľúč podzariadeniu, ale opraví cid", upd.first.single().let { it.localKey == "" && it.cid == "nove" })

    // zisťovanie brány: podzariadenie bez parent_id v zozname, brána ho vráti cez sub-devices
    val tokenOk = """{"success":true,"result":{"access_token":"tok","expire_time":7200}}"""
    val listJson = """{"success":true,"result":{"has_more":false,"devices":[
        {"id":"gw1","name":"Brána","local_key":"0123456789abcdef","category":"wg2","sub":false,"online":true},
        {"id":"z1","name":"Dvere","category":"mcs","sub":true,"uuid":"u1"},
        {"id":"z2","name":"Teplota","category":"wsdcg","sub":true,"uuid":"u2"}]}}"""
    val fake = FakeTransport { url ->
        when {
            url.contains("/token") -> tokenOk
            url.contains("/associated-users/devices") -> listJson
            url.contains("/devices/gw1/sub-devices") -> """{"success":true,"result":[{"id":"z1","node_id":"nodeA"},{"id":"z2","node_id":"nodeB"}]}"""
            else -> """{"success":true,"result":{}}"""
        }
    }
    val found = TuyaCloudClient(CloudConfig("id", "secret"), fake, clock = { 1700000000123L }).devices()
    check("cloud: brána a node_id podzariadení sa zistia", found.first { it.id == "z1" }.let { it.parentId == "gw1" && it.nodeId == "nodeA" } &&
        found.first { it.id == "z2" }.nodeId == "nodeB")
    check("cloud: brána ostáva samostatné zariadenie", found.first { it.id == "gw1" }.let { !it.sub && it.parentId == "" })

    // keď sub-devices nič nevráti: jediná brána v účte, node_id sa zoberie z uuid
    val fake2 = FakeTransport { url ->
        when {
            url.contains("/token") -> tokenOk
            url.contains("/associated-users/devices") -> listJson
            else -> """{"success":true,"result":{}}"""
        }
    }
    val found2 = TuyaCloudClient(CloudConfig("id", "secret"), fake2, clock = { 1700000000123L }).devices()
    check("cloud: jediná brána v účte dostane podzariadenia, cid z uuid", found2.first { it.id == "z1" }.let { it.parentId == "gw1" && it.cid == "u1" })

    println("ZigbeeTest: všetko OK")
}
