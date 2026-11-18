import org.json.JSONArray
import org.json.JSONObject
import sk.domov.ovladanie.*
import java.io.File

// Test popisu hodnôt (mapping z tinytuya) a rozpoznania šablón, bez Androidu.
// tools/testdata/devices.json vytvorila funkcia Cloud._build_mapping z tinytuya 1.21 (rovnaký formát ako wizard).
// Spustenie: pozri README, časť Pre vývojárov.

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: "tools/testdata/devices.json"
    val devices = parseTinytuyaJson(File(path).readText())
    check("import: 8 zariadení", devices.size == 8)
    val byId = devices.associateBy { it.devId }
    val plug = byId.getValue("bf00000000000000plug")

    // ---------------------------------------------------------------- parsovanie schémy
    check("plug kategória cz", plug.category == "cz")
    check("plug 12 hodnôt v schéme", plug.schema.size == 12)
    val power = plug.schema.getValue("19")
    check("cur_power Integer W scale 1", power.code == "cur_power" && power.type == DpType.INTEGER && power.unit == "W" && power.scale == 1)
    val ele = plug.schema.getValue("17")
    check("add_ele kwh -> kWh, scale 3, step 100", ele.unit == "kWh" && ele.scale == 3 && ele.step == 100L)
    check("relay_status Enum s 3 možnosťami", plug.schema.getValue("38").range == listOf("power_off", "power_on", "last"))
    check("String typ (prázdne values)", plug.schema.getValue("42").type == DpType.STRING)
    check("verzia z devices.json", plug.version == "3.4" && plug.ip == "192.168.1.20")
    val therm = byId.getValue("bf0000000000000therm")
    check("℃ -> °C, min/max", therm.schema.getValue("2").let { it.unit == "°C" && it.min == 5L && it.max == 35L })
    check("Bitmap typ", therm.schema.getValue("13").type == DpType.BITMAP)
    val noSchema = byId.getValue("bf00000000000noschem")
    check("bez schémy = prázdna schéma", noSchema.schema.isEmpty() && noSchema.category == "" && noSchema.version == "3.5")
    check("bez verzie = prázdna (pri importe sa ponechá pôvodná)", byId.getValue("bf0000000000000strip").version == "")

    // values ako text (staršie tinytuya / typ String s JSON)
    val m = Schema.parseMapping(JSONObject("""{"5": {"code": "cur_voltage", "type": "Integer", "values": "{\"unit\":\"V\",\"min\":0,\"max\":5000,\"scale\":1,\"step\":1}"}, "x": {"type": "Boolean"}, "6": "zlé"}"""))
    check("values ako text sa načíta, zlé položky preskočí", m.size == 1 && m.getValue("5").scale == 1 && m.getValue("5").unit == "V")
    check("parseMapping(null) = prázdne", Schema.parseMapping(null).isEmpty())

    // ---------------------------------------------------------------- formát hodnôt
    check("format 2304 V/10 = 230,4 V", Schema.format(plug.schema["20"], 2304) == "230,4 V")
    check("format 1234 W/10 = 123,4 W", Schema.format(power, 1234) == "123,4 W")
    check("format add_ele 1500 = 1,500 kWh", Schema.format(ele, 1500) == "1,500 kWh")
    check("format enum preklad", Schema.format(plug.schema["38"], "last") == "Posledný stav")
    check("format Boolean", Schema.format(plug.schema["1"], true) == "Zapnuté")
    check("bez schémy = ako doteraz", Schema.format(null, 2304) == "2304")
    val heaterSet = byId.getValue("bf000000000000heater").schema.getValue("2")
    check("toRaw 21,3 °C scale 1 step 5 -> 215", Schema.toRaw(heaterSet, 21.3) == 215L)
    check("toRaw orezanie na max", Schema.toRaw(heaterSet, 99.0) == 350L)
    check("toRaw orezanie na min", Schema.toRaw(heaterSet, 1.0) == 50L)

    // ---------------------------------------------------------------- názvy
    check("label switch_1", Schema.label("switch_1") == "Vypínač 1")
    check("label switch_3", Schema.label("switch_3") == "Vypínač 3")
    check("label cur_power", Schema.label("cur_power") == "Príkon")
    check("label temp_set", Schema.label("temp_set") == "Nastavená teplota")
    check("label switch_usb1", Schema.label("switch_usb1") == "USB 1")
    check("label countdown_2", Schema.label("countdown_2") == "Odpočet 2")
    check("label neznámy kód čitateľne", Schema.label("light_mode_x2") == "Light mode x2")
    check("enum level_3", Schema.enumLabel("mode", "level_3") == "Stupeň 3")
    check("enum neznáma hodnota", Schema.enumLabel("mode", "Chips") == "Chips")
    check("read-only cur_power", Schema.isReadOnly("cur_power"))
    check("read-only temp_current", Schema.isReadOnly("temp_current"))
    check("read-only add_ele", Schema.isReadOnly("add_ele"))
    check("zapisovateľné relay_status", !Schema.isReadOnly("relay_status"))
    check("zapisovateľné temp_set", !Schema.isReadOnly("temp_set"))

    // ---------------------------------------------------------------- šablóny podľa schémy
    val plugValues = mapOf<String, Any>("1" to true, "9" to 0, "17" to 1500, "18" to 520, "19" to 1187, "20" to 2304, "38" to "last", "41" to false)
    val pt = Templates.detect(plugValues, plug.schema, plug.category)
    check("zásuvka s meraním", pt is SocketTemplate && pt.kind == TemplateKind.SOCKET && pt.switches == listOf("1"))
    pt as SocketTemplate
    check("zásuvka: príkon 118,7 W", pt.power?.text(plugValues) == "118,7 W")
    check("zásuvka: napätie, prúd, spotreba", pt.voltage?.dp == "20" && pt.current?.dp == "18" && pt.energy?.text(plugValues) == "1,500 kWh")
    check("zásuvka: odpočet DP 9", pt.countdown?.dp == "9")
    check("zásuvka: popis dlaždice", Templates.summary(pt, plugValues, "1") == "Zapnuté · 118,7 W")
    check("zásuvka: hlavný vypínač podľa kódu", Schema.powerDp(plug.schema, plugValues) == "1")

    val strip = byId.getValue("bf0000000000000strip")
    val stripValues = mapOf<String, Any>("1" to true, "2" to false, "3" to true, "7" to false, "9" to 0)
    val st = Templates.detect(stripValues, strip.schema, strip.category)
    check("viacnásobná zásuvka: 4 vypínače vrátane USB", st is SocketTemplate && st.kind == TemplateKind.STRIP && st.switches == listOf("1", "2", "3", "7"))
    check("viacnásobná: popis", Templates.summary(st!!, stripValues, "1") == "Zapnuté 2 z 4")

    val thermValues = mapOf<String, Any>("1" to true, "2" to 22, "3" to 20, "4" to "manual", "6" to false, "13" to 0)
    val tt = Templates.detect(thermValues, therm.schema, therm.category)
    check("termostat", tt is ClimateTemplate && tt.kind == TemplateKind.THERMOSTAT)
    tt as ClimateTemplate
    check("termostat: nastavená/aktuálna/režim/poistka", tt.target?.dp == "2" && tt.current?.dp == "3" && tt.mode?.options == listOf("auto", "manual", "eco") && tt.childLock == "6")
    check("termostat: popis", Templates.summary(tt, thermValues, "1") == "Zapnuté · 20 °C → 22 °C")
    check("termostat: zapnutie bez potvrdenia", Templates.confirmation(tt, "1", mapOf("1" to true)) == null)
    check("termostat: príkaz teploty", tt.target!!.command(23.4) == mapOf<String, Any>("2" to 23L))

    val heater = byId.getValue("bf000000000000heater")
    val heaterValues = mapOf<String, Any>("1" to false, "2" to 215, "3" to 198, "4" to "2", "7" to false)
    val ht = Templates.detect(heaterValues, heater.schema, heater.category)
    check("ohrievač (kategória qn)", ht is ClimateTemplate && ht.kind == TemplateKind.HEATER)
    check("ohrievač: zapnutie vyžaduje potvrdenie", Templates.confirmation(ht, "1", mapOf("1" to true)) != null)
    check("ohrievač: vypnutie bez potvrdenia", Templates.confirmation(ht, "1", mapOf("1" to false)) == null)
    check("ohrievač: zmena teploty bez potvrdenia", Templates.confirmation(ht, "1", mapOf("2" to 220)) == null)
    check("ohrievač: teplota 21,5 °C", (ht as ClimateTemplate).target?.text(heaterValues) == "21,5 °C")
    check("ohrievač: stupne ako možnosti", ht.speedEnum?.options == listOf("1", "2", "3"))

    val fan = byId.getValue("bf00000000000000fan1")
    val fanValues = mapOf<String, Any>("1" to true, "2" to "nature", "3" to 3, "5" to true)
    val ft = Templates.detect(fanValues, fan.schema, fan.category)
    check("ventilátor", ft is ClimateTemplate && ft.kind == TemplateKind.FAN && ft.speedNum?.dp == "3" && ft.oscillation == "5")
    check("ventilátor: rýchlosť 1–6", (ft as ClimateTemplate).speedNum?.let { it.min == 1L && it.max == 6L } == true)

    val dehum = byId.getValue("bf000000000000dehum1")
    val dt = Templates.detect(mapOf("1" to true, "2" to 50, "4" to "low", "6" to 64), dehum.schema, dehum.category)
    check("odvlhčovač", dt is ClimateTemplate && dt.kind == TemplateKind.DEHUMIDIFIER && dt.target?.dp == "2" && dt.current?.dp == "6" && dt.speedEnum?.dp == "4")

    val cover = byId.getValue("bf0000000000000cover")
    val coverValues = mapOf<String, Any>("1" to "stop", "2" to 40, "3" to 40, "5" to "forward", "7" to "opening")
    val ct = Templates.detect(coverValues, cover.schema, cover.category)
    check("rolety", ct is CoverTemplate && ct.position?.dp == "2" && ct.positionState?.dp == "3")
    check("rolety: príkazy", Templates.coverCommand(ct as CoverTemplate, "open") == mapOf<String, Any>("1" to "open") &&
        Templates.coverCommand(ct, "close") == mapOf<String, Any>("1" to "close"))
    check("rolety: popis", Templates.summary(ct, coverValues, null) == "Poloha 40 %")

    // ---------------------------------------------------------------- odhad bez schémy
    val gp = Templates.detect(mapOf("1" to true, "9" to 0, "17" to 25, "18" to 410, "19" to 905, "20" to 2311))
    check("odhad: zásuvka s meraním", gp is SocketTemplate && gp.guessed && gp.power?.text(mapOf("19" to 905)) == "90,5 W")
    val gs = Templates.detect(mapOf("1" to true, "2" to false, "3" to true, "9" to 0, "10" to 0))
    check("odhad: 3 vypínače", gs is SocketTemplate && gs.kind == TemplateKind.STRIP && gs.switches.size == 3)
    val gc = Templates.detect(mapOf("1" to "open", "2" to 100, "3" to 80))
    check("odhad: rolety", gc is CoverTemplate && gc.guessed)
    check("odhad: jednoduchý vypínač = nič", Templates.detect(mapOf("1" to true)) == null)
    check("odhad: svetlo nie je šablóna", Templates.detect(mapOf("20" to true, "21" to "white", "22" to 500)) == null)
    // fritéza (1, 2 vypínače) sa rozpozná skôr ako šablóny; Fryer.detect musí stále fungovať
    val fryerValues = mapOf<String, Any>("1" to true, "2" to false, "3" to "Chips", "5" to "standby", "6" to 180, "9" to 15)
    check("fritéza sa stále rozpozná", Fryer.detect(fryerValues) != null)

    // ---------------------------------------------------------------- export / import a opätovný import
    val exported = exportDevicesJson(devices)
    val again = parseTinytuyaJson(exported).associateBy { it.devId }
    check("export -> import zachová schému", again.getValue(plug.devId).schema == plug.schema)
    check("export -> import zachová kategóriu", again.getValue(plug.devId).category == "cz")
    val exportedPlug = JSONArray(exported).getJSONObject(0)
    check("export má mapping vo formáte tinytuya", exportedPlug.getJSONObject("mapping").getJSONObject("19").getString("code") == "cur_power" &&
        exportedPlug.getJSONObject("mapping").getJSONObject("19").getJSONObject("values").getInt("scale") == 1)

    // zariadenie v appke bez schémy, s vlastnými nastaveniami
    val inApp = Device(
        id = "interne-id", name = "Moja zásuvka", ip = "192.168.1.99", devId = plug.devId, localKey = "staryklucstaryk1",
        version = "3.5", labels = mapOf("19" to "Watty"), hidden = setOf("41"), powerDp = "1",
    )
    val strip0 = Device(name = "Lišta", ip = "192.168.1.98", devId = strip.devId, localKey = "0123456789abcdeg", version = "3.4")
    val merged = mergeImported(listOf(inApp, strip0), devices).associateBy { it.devId }
    val mp = merged.getValue(plug.devId)
    check("opätovný import: schéma pribudla", mp.schema == plug.schema && mp.category == "cz")
    check("opätovný import: id, názov, vlastné názvy, skryté a vypínač zostali",
        mp.id == "interne-id" && mp.name == "Moja zásuvka" && mp.labels == mapOf("19" to "Watty") && mp.hidden == setOf("41") && mp.powerDp == "1")
    check("opätovný import: kľúč, IP a verzia z devices.json", mp.localKey == plug.localKey && mp.ip == "192.168.1.20" && mp.version == "3.4")
    val ms = merged.getValue(strip.devId)
    check("opätovný import bez IP a verzie: ponechá pôvodné", ms.ip == "192.168.1.98" && ms.version == "3.4" && ms.schema.size == 5)
    check("nové zariadenie bez verzie dostane 3.3", merged.getValue(byId.getValue("bf0000000000000therm").devId).version == "3.3")
    check("pridané všetky", merged.size == 8)
    // import bez schémy nezmaže uloženú schému
    val keep = mergeImported(listOf(mp), listOf(plug.copy(schema = emptyMap(), category = ""))).single()
    check("import bez mapping ponechá schému", keep.schema == plug.schema && keep.category == "cz")
    // vlastné premenovanie má prednosť
    check("vlastný názov má prednosť pred Tuya názvom", (mp.labels["19"] ?: Schema.label(mp.schema.getValue("19").code)) == "Watty")

    println("SCHEMA PASSED")
}
