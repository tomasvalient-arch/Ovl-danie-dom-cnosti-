import sk.domov.ovladanie.*

// Test svetiel s viacerými zdrojmi (hlavné + podhľad) a technického info bez Androidu.

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

fun b(code: String) = DpSchema(code, DpType.BOOLEAN, rawType = "Boolean")
fun e(code: String, vararg r: String) = DpSchema(code, DpType.ENUM, range = r.toList(), rawType = "Enum")
fun i(code: String, min: Long, max: Long) = DpSchema(code, DpType.INTEGER, min = min, max = max, rawType = "Integer")
fun j(code: String) = DpSchema(code, DpType.JSON, rawType = "Json")
fun r(code: String) = DpSchema(code, DpType.RAW, rawType = "Raw")
fun s(code: String) = DpSchema(code, DpType.STRING, rawType = "String")

// BlitzWolf BW-CLT1-like stropné svetlo: CCT hlavné + RGB podhľad, mix_rgbcw na DP 51 (podľa tuya-local
// ledvance_smart_plabl100x25b.yaml / cloudybay_dualzone_ceilinglight.yaml).
val CLT1_SCHEMA = mapOf(
    "20" to b("switch_led"),
    "21" to e("work_mode", "white", "colour", "scene", "music"),
    "22" to i("bright_value_v2", 10, 1000),
    "23" to i("temp_value_v2", 0, 1000),
    "24" to j("colour_data_v2"),
    "25" to s("scene_data_v2"),
    "26" to i("countdown_1", 0, 86400),
    "28" to s("control_data"),
    "34" to b("do_not_disturb"),
    "51" to r("mix_rgbcw"),
)

// bity 0x05 = len hlavné (biele), hue 120, sat 1000, v 1000, biela 1000, teplota 0
val MIX_WHITE_ONLY = "AAUAeAPoA+gD6AAA"
// 0x07 = oba
val MIX_BOTH = "AAcAeAPoA+gALQAA"

val CLT1_VALUES = mapOf<String, Any>(
    "20" to true, "21" to "white", "22" to 1000, "23" to 0, "24" to "007803e803e8", "26" to 0, "34" to false, "51" to MIX_WHITE_ONLY,
)

// bežná RGBCW žiarovka (dj) s DP 20–26
val BULB_SCHEMA = mapOf(
    "20" to b("switch_led"), "21" to e("work_mode", "white", "colour", "scene", "music"),
    "22" to i("bright_value_v2", 10, 1000), "23" to i("temp_value_v2", 0, 1000), "24" to j("colour_data_v2"),
    "25" to s("scene_data_v2"), "26" to i("countdown_1", 0, 86400),
)
val BULB_VALUES = mapOf<String, Any>("20" to true, "21" to "white", "22" to 500, "23" to 300, "24" to "000003e803e8", "26" to 0)

fun dev(name: String, category: String, schema: Map<String, DpSchema>, roles: Map<String, String> = emptyMap(), model: String = "") = Device(
    id = "x", name = name, ip = "192.168.1.77", devId = "bf1234567890abcdefgh", localKey = "SecretKey123456!",
    category = category, schema = schema, lightRoles = roles, productId = "keyabc123", model = model,
)

fun flags(cmd: Map<String, Any>, dp: String = "51") = LightChannels.decodeMix(cmd[dp])!!.flags

fun main() {
    // --- kodek
    val m = LightChannels.decodeMix(MIX_BOTH)!!
    check("mix: dekódovanie príznakov 7", m.flags == 7)
    check("mix: hue 120, sat 1000, v 1000", m.h == 120 && m.s == 1000 && m.v == 1000)
    check("mix: biela 45, teplota 0", m.wb == 45 && m.wc == 0)
    check("mix: kódovanie späť rovnaké", LightChannels.encodeMix(m) == MIX_BOTH)
    val hex = LightChannels.decodeMix("000700780" + "3e803e8002d0000")!!
    check("mix: hex variant", hex.flags == 7 && hex.hex && LightChannels.encodeMix(hex) == "0007007803e803e8002d0000")
    check("mix: nezmysel = null", LightChannels.decodeMix("abc") == null && LightChannels.decodeMix(true) == null && LightChannels.decodeMix(null) == null)

    // --- BW-CLT1
    val clt = dev("Spálňa strop", "xdd", CLT1_SCHEMA, model = "BW-CLT1")
    val ml = LightChannels.detect(clt, CLT1_VALUES)
    check("CLT1: rozpoznané ako zmiešané svetlo", ml != null && ml.backend == LightBackend.MIX && ml.mixDp == "51" && ml.powerDp == "20")
    ml!!
    check("CLT1: dva kanály Hlavné svetlo + Podhľad", ml.channels.map { it.name } == listOf("Hlavné svetlo", "Podhľad"))
    check("CLT1: rieši DP 20–24 a 51", ml.handled.containsAll(listOf("20", "21", "22", "23", "24", "51")))
    val main = ml.main
    val back = ml.channels[1]
    check("CLT1: hlavné svieti, podhľad nie", LightChannels.read(ml, main, CLT1_VALUES).on && !LightChannels.read(ml, back, CLT1_VALUES).on)
    check("CLT1: súhrn", LightChannels.summary(ml, CLT1_VALUES) == "Zapnuté · Hlavné 100 %")

    val backOn = LightChannels.setOn(ml, back, true, CLT1_VALUES)
    check("CLT1: zapnutie podhľadu nechá hlavné svetlo (príznaky 7)", flags(backOn) == 7 && "21" !in backOn && "20" !in backOn)
    val both = CLT1_VALUES + ("51" to MIX_BOTH)
    check("CLT1: obe svietia", LightChannels.read(ml, main, both).on && LightChannels.read(ml, back, both).on)
    check("CLT1: súhrn oboch", LightChannels.summary(ml, both).contains("Podhľad"))
    val mainOff = LightChannels.setOn(ml, main, false, both)
    check("CLT1: vypnutie hlavného nechá podhľad (príznaky 6)", flags(mainOff) == 6 && "20" !in mainOff)
    val lastOff = LightChannels.setOn(ml, main, false, CLT1_VALUES)
    check("CLT1: vypnutie posledného zdroja vypne aj DP 20", lastOff["20"] == false && flags(lastOff) and 3 == 0)
    val bright = LightChannels.brightness(ml, back, 30, both)
    check("CLT1: jas podhľadu mení len farebný jas", LightChannels.decodeMix(bright["51"])!!.let { it.v == 300 && it.wb == 45 && it.flags == 7 })
    val temp = LightChannels.temp(ml, main, 1f, both)
    check("CLT1: teplota hlavného", LightChannels.decodeMix(temp["51"])!!.let { it.wc == 1000 && it.flags == 7 })
    val col = LightChannels.colour(ml, back, Hsv(240f, 1f, 0.5f), CLT1_VALUES)
    check("CLT1: farba podhľadu ho zapne a hlavné nechá", LightChannels.decodeMix(col["51"])!!.let { it.h == 240 && it.v == 500 && it.flags == 7 })

    // celé svetlo vypnuté (DP 20 = false), príznaky si pamätajú oba
    val masterOff = both + ("20" to false)
    check("CLT1: pri vypnutom DP 20 nesvieti nič", !LightChannels.anyOn(ml, masterOff))
    val onlyBack = LightChannels.setOn(ml, back, true, masterOff)
    check("CLT1: zapnutie podhľadu pri vypnutom svetle: DP 20 zap., len podhľad (6)", onlyBack["20"] == true && flags(onlyBack) == 6)

    check("CLT1: Všetko vypnúť = DP 20 vypnúť", LightChannels.allOff(ml, both) == mapOf("20" to false))
    val arrive = LightChannels.arrive(ml, masterOff)
    check("CLT1: príchod zapne len hlavné", arrive["20"] == true && flags(arrive) == 5)
    val arriveAll = LightChannels.arrive(ml.copy(arriveAll = true), masterOff)
    check("CLT1: príchod so všetkými zapne oba", flags(arriveAll) == 7)
    val dim = LightChannels.dim(ml, both, 10)
    check("CLT1: Noc = len hlavné, teplá biela 10 %", LightChannels.decodeMix(dim["51"])!!.let { it.flags == 5 && it.wb == 100 && it.wc == 0 })
    check("CLT1: cloud kód na vypnutie", LightChannels.offCodes(ml, CLT1_SCHEMA) == listOf("switch_led"))

    // integrácia so scénami a príchodom
    check("Scény: Všetko vypnúť vypne DP 20", Scenes.offCommand(clt, both) == mapOf<String, Any>("20" to false))
    check("Príchod: zapne hlavné cez DP 51", AutoDevices.arriveCommand(clt, masterOff)?.let { flags(it) == 5 } == true)
    check("Scény: Noc stlmí len hlavné", Scenes.dimCommand(clt, both, 10)?.let { flags(it) == 5 } == true)

    // DP 51 chýba v hlásení: odvodí sa z 20/21
    val noMix = CLT1_VALUES - "51"
    check("CLT1 bez DP 51 v hlásení: rozpoznané podľa schémy", LightChannels.detect(clt, noMix) != null)
    check("CLT1 bez DP 51: hlavné svieti podľa work_mode", LightChannels.read(ml, main, noMix).on && !LightChannels.read(ml, back, noMix).on)
    check("CLT1 bez DP 51: zapnutie podhľadu pošle 7", flags(LightChannels.setOn(ml, back, true, noMix)) == 7)

    // bez cloudu (bez schémy), ale s názvom: hodnota DP 51 sa dá dekódovať
    val noSchema = dev("BW-CLT1", "", emptyMap())
    check("bez schémy + názov BW-CLT1: rozpoznané z DP 51", LightChannels.detect(noSchema, CLT1_VALUES)?.backend == LightBackend.MIX)

    // --- bežná žiarovka ostáva jedno svetlo
    check("RGBCW žiarovka: nie je viackanálová", LightChannels.detect(dev("Obývačka", "dj", BULB_SCHEMA), BULB_VALUES) == null)
    check("RGBCW žiarovka bez schémy: nie je viackanálová", LightChannels.detect(dev("Lampa", "", emptyMap()), BULB_VALUES) == null)
    check("RGBCW žiarovka: Všetko vypnúť ako doteraz", Scenes.offCommand(dev("Obývačka", "dj", BULB_SCHEMA), BULB_VALUES) == mapOf<String, Any>("20" to false))
    // žiarovka s mix_rgbcw (biela + farba naraz v jednej žiarovke) sa automaticky nedelí
    val mixBulb = dev("Lampa", "dj", BULB_SCHEMA + ("51" to r("mix_rgbcw")))
    val mixBulbVals = BULB_VALUES + ("51" to MIX_WHITE_ONLY)
    check("žiarovka s mix_rgbcw: automaticky jedno svetlo", LightChannels.detect(mixBulb, mixBulbVals) == null)
    check("žiarovka s mix_rgbcw: ponúkne sa samostatné ovládanie", LightChannels.hasMixCapability(mixBulb, mixBulbVals))
    check("žiarovka s mix_rgbcw + ručne model=mix: dva kanály", LightChannels.detect(mixBulb.copy(lightRoles = mapOf("model" to "mix")), mixBulbVals)?.channels?.size == 2)
    // staré svetlo s 2 zdrojmi (DP 51/52 ako vypínače) sa nemení
    val oldDual = mapOf<String, Any>("20" to true, "22" to 600, "23" to 800, "24" to "00f003e803e8", "51" to true, "52" to true)
    check("staré svetlo s DP 51 = vypínač: nie zmiešané", LightChannels.detect(dev("Strop", "xdd", emptyMap()), oldDual) == null)

    // --- samostatné vypínače
    val nightSchema = mapOf("20" to b("switch_led"), "21" to e("work_mode", "white", "colour"), "22" to i("bright_value_v2", 10, 1000),
        "23" to i("temp_value_v2", 0, 1000), "33" to b("switch_night_light"))
    val nightVals = mapOf<String, Any>("20" to true, "21" to "white", "22" to 700, "23" to 400, "33" to true)
    val nl = LightChannels.detect(dev("Strop", "xdd", nightSchema), nightVals)
    check("switch_night_light: dva kanály", nl?.backend == LightBackend.SWITCHES && nl.channels.map { it.switchDp } == listOf("20", "33"))
    nl!!
    check("switch_night_light: názov Nočné svetlo", nl.channels[1].name == "Nočné svetlo")
    check("switch_night_light: zapnutie hlavného pošle len DP 20", LightChannels.setOn(nl, nl.main, false, nightVals) == mapOf<String, Any>("20" to false))
    check("switch_night_light: Všetko vypnúť oba", LightChannels.allOff(nl, nightVals) == mapOf<String, Any>("20" to false, "33" to false))
    check("switch_night_light: príchod len hlavné", LightChannels.arrive(nl, nightVals) == mapOf<String, Any>("20" to true))
    check("switch_night_light: jas hlavného", LightChannels.brightness(nl, nl.main, 50, nightVals) == mapOf<String, Any>("21" to "white", "22" to 500))
    check("switch_night_light: cloud kódy", LightChannels.offCodes(nl, nightSchema) == listOf("switch_led", "switch_night_light"))

    val idxSchema = mapOf("1" to b("switch_led_1"), "2" to i("bright_value_1", 10, 1000), "7" to b("switch_led_2"), "8" to i("bright_value_2", 10, 1000))
    val idxVals = mapOf<String, Any>("1" to true, "2" to 500, "7" to false, "8" to 1000)
    val idx = LightChannels.detect(dev("Stmievač", "tgq", idxSchema), idxVals)
    check("switch_led_1/2: dva kanály", idx?.channels?.map { it.switchDp } == listOf("1", "7"))
    check("switch_led_1/2: jas druhého", LightChannels.brightness(idx!!, idx.channels[1], 20, idxVals) == mapOf<String, Any>("8" to 200))

    // --- ručné nastavenie
    check("ručne single: CLT1 ako jedno svetlo", LightChannels.detect(clt.copy(lightRoles = mapOf("model" to "single")), CLT1_VALUES) == null)
    val manualBack = LightChannels.detect(dev("Svetlo", "dj", BULB_SCHEMA + ("40" to b("switch_x"))), BULB_VALUES + ("40" to false)) 
    check("bez ručného nastavenia neznámy vypínač nič nemení", manualBack == null)
    val manual = LightChannels.detect(dev("Svetlo", "dj", BULB_SCHEMA + ("40" to b("switch_x")), mapOf("back_switch" to "40")), BULB_VALUES + ("40" to false))
    check("ručne back_switch=40: dva kanály", manual?.channels?.map { it.switchDp } == listOf("20", "40"))
    check("ručne mix=60: zmiešaná hodnota na inom DP", LightChannels.detect(clt.copy(lightRoles = mapOf("mix" to "60")), CLT1_VALUES + ("60" to MIX_BOTH))?.mixDp == "60")
    check("ručne arrive_all", LightChannels.detect(clt.copy(lightRoles = mapOf("arrive_all" to "1")), CLT1_VALUES)?.arriveAll == true)
    check("chybné hodnoty nespadnú", LightChannels.detect(clt, mapOf("20" to "x", "51" to 5)) != null || true)

    // --- technické info
    val info = TechInfo.build(clt, both, "test", "1.9")
    check("tech info: bez local key", !info.contains("SecretKey123456!"))
    check("tech info: bez IP", !info.contains("192.168.1.77"))
    check("tech info: Device ID skryté", !info.contains("bf1234567890abcdefgh") && info.contains("bf12…efgh"))
    check("tech info: obsahuje schému a hodnoty", info.contains("DP 51  mix_rgbcw") && info.contains("DP 22  bright_value_v2") && info.contains("rozsah 10–1000"))
    check("tech info: kategória, produkt, model", info.contains("xdd") && info.contains("keyabc123") && info.contains("BW-CLT1"))
    check("tech info: rozbalená mix hodnota", info.contains("hlavné") || info.contains("príznaky") || info.contains("flags"))

    // --- uloženie / export
    val json = exportDevicesJson(listOf(clt.copy(lightRoles = mapOf("model" to "mix"))))
    check("export: product_id v devices.json", json.contains("product_id"))
    val back2 = parseTinytuyaJson(json).first()
    check("export/import: product_id, model, lightRoles", back2.productId == "keyabc123" && back2.model == "BW-CLT1" && back2.lightRoles == mapOf("model" to "mix"))

    // --- skutočné BW-CLT1 od užívateľa (1.9 tech info): štandardné kódy, DP 51 v popise chýba
    val userSchema = mapOf(
        "20" to b("switch_led"), "21" to e("work_mode", "white", "colour", "scene", "music"),
        "22" to i("bright_value", 10, 1000), "23" to i("temp_value", 0, 1000), "24" to j("colour_data"),
        "26" to i("countdown", 0, 86400), "27" to s("music_data"), "28" to s("control_data"),
    )
    val userVals = mapOf<String, Any>("20" to true, "21" to "white", "22" to 10, "23" to 1000, "24" to "0119026903e8", "26" to 0)
    val userDev = dev("BW-CLT1", "xdd", userSchema)
    check("BW-CLT1 bez DP 51: jedno svetlo (nie rozbité dvojité)", LightChannels.detect(userDev, userVals) == null)
    check("BW-CLT1 bez DP 51: ponúkne samostatné ovládanie", LightChannels.suggestMix(userDev, userVals))
    check("BW-CLT1: pri čítaní stavu sa vyžiada DP 51", LightChannels.hiddenDps(userDev) == listOf("51"))
    // hodnota zodpovedajúca Tuya appke: farba zap. 100 %, biela zap. 12 %
    val userMix = "AAcBGQJpA+gAeAPo"
    val withMix = userVals + ("51" to userMix)
    val um = LightChannels.detect(userDev, withMix)
    check("BW-CLT1 + DP 51 z UPDATEDPS: zmiešané svetlo", um?.backend == LightBackend.MIX && um.mixDp == "51" && um.powerDp == "20")
    check("BW-CLT1 + DP 51: biela 12 %, farba 100 %, obe zapnuté",
        LightChannels.read(um!!, um.main, withMix).let { it.on && it.percent == 12 } && LightChannels.read(um, um.channels[1], withMix).let { it.on && it.percent == 100 })
    check("BW-CLT1 + DP 51: vypnutie farby nechá bielu", flags(LightChannels.setOn(um, um.channels[1], false, withMix)) == 5)
    check("BW-CLT1 ručne model=mix bez DP 51: dva kanály", LightChannels.detect(userDev.copy(lightRoles = mapOf("model" to "mix")), userVals)?.channels?.size == 2)
    check("BW-CLT1 ručne single: nepýta sa na DP 51", LightChannels.hiddenDps(userDev.copy(lightRoles = mapOf("model" to "single"))).isEmpty())
    check("BW-CLT1 po vybratí jedného svetla sa karta neponúka", !LightChannels.suggestMix(userDev.copy(lightRoles = mapOf("model" to "single")), userVals))
    check("bežná žiarovka: nepýta sa na DP 51", LightChannels.hiddenDps(dev("Obývačka", "dj", BULB_SCHEMA)).isEmpty())
    check("bežná žiarovka: karta sa neponúka", !LightChannels.suggestMix(dev("Obývačka", "dj", BULB_SCHEMA), BULB_VALUES))
    check("zásuvka s názvom strop: nepýta sa", LightChannels.hiddenDps(dev("Strop zásuvka", "cz", mapOf("1" to b("switch_1")))).isEmpty())
    // DP 51 chýba v ďalšom čítaní: ostane z predchádzajúceho
    val kept = LightChannels.keepHidden(userDev, withMix, userVals)
    check("skrytý DP 51 ostane, keď ho zariadenie neposlalo", kept["51"] == userMix && kept["22"] == 10)
    check("nový DP 51 prepíše starý", LightChannels.keepHidden(userDev, withMix, userVals + ("51" to MIX_WHITE_ONLY))["51"] == MIX_WHITE_ONLY)
    check("pri žiarovke sa nič neponecháva", LightChannels.keepHidden(dev("L", "dj", BULB_SCHEMA), BULB_VALUES + ("51" to "x"), BULB_VALUES)["51"] == null)

    // --- lokálne hlásenia
    val push33 = "{\"devId\":\"bf1234567890abcdefgh\",\"dps\":{\"51\":\"$userMix\"},\"t\":1700000000}"
    val push34 = "{\"protocol\":4,\"t\":1,\"data\":{\"dps\":{\"20\":false}}}"
    check("hlásenie 3.3: dps bez devId", LocalDps.parse(push33) == mapOf<String, Any>("51" to userMix))
    check("hlásenie 3.4: data.dps", LocalDps.parse(push34) == mapOf<String, Any>("20" to false))
    check("nezmysel: null", LocalDps.parse("abc") == null && LocalDps.parse("") == null)
    val merged = LocalDps.merge(listOf("{\"dps\":{\"20\":true,\"22\":10}}", push33, push34))
    check("zlúčenie: neskoršie prepíše, zoradené", merged.keys.toList() == listOf("20", "22", "51") && merged["20"] == false)
    check("skupiny vyžiadania pokrývajú 1–255", LocalDps.PROBE_GROUPS.flatten() == (1..255).toList())
    val line = LocalDps.logLine("16:52:03.120", mapOf("51" to userMix), userSchema)
    check("riadok záznamu: čas, DP a rozbalený mix", line.startsWith("16:52:03.120  DP 51 = ") && line.contains("biela jas 120") && !line.contains("bf12"))

    // --- Tuya cloud: model vecí (ako Annaror 5LRGB v tuya-local #3883) a shadow
    val model = org.json.JSONObject().put("modelId", "x").put("services", org.json.JSONArray().put(org.json.JSONObject().put("code", "").put("properties", org.json.JSONArray()
        .put(org.json.JSONObject("{\"abilityId\":20,\"accessMode\":\"rw\",\"code\":\"switch_led\",\"name\":\"开关\",\"typeSpec\":{\"type\":\"bool\"}}"))
        .put(org.json.JSONObject("{\"abilityId\":22,\"accessMode\":\"rw\",\"code\":\"bright_value\",\"name\":\"白光亮度\",\"typeSpec\":{\"type\":\"value\",\"max\":1000,\"min\":10,\"scale\":0,\"step\":1}}"))
        .put(org.json.JSONObject("{\"abilityId\":51,\"accessMode\":\"rw\",\"code\":\"mix_rgbcw\",\"name\":\"混光\",\"typeSpec\":{\"type\":\"raw\",\"maxlen\":255}}"))
        .put(org.json.JSONObject("{\"abilityId\":101,\"accessMode\":\"rw\",\"code\":\"device_status\",\"name\":\"设备状态上报\",\"typeSpec\":{\"type\":\"string\",\"maxlen\":255}}"))
    ))).toString()
    val props = CloudParse.thingProps(model)
    check("model vecí: 4 vlastnosti, DP 51 mix_rgbcw raw", props.size == 4 && props.any { it.dp == 51 && it.code == "mix_rgbcw" && it.type == "raw" })
    check("model vecí: rozsah jasu", props.first { it.dp == 22 }.spec.contains("\"max\":1000"))
    check("model vecí: zlý text = prázdne", CloudParse.thingProps("nie json").isEmpty())
    val mapping = org.json.JSONObject().put("20", org.json.JSONObject().put("code", "switch_led").put("type", "Boolean").put("values", org.json.JSONObject()))
    CloudParse.addThingProps(mapping, props)
    val sch = Schema.parseMapping(mapping)
    check("doplnenie: DP 51 mix_rgbcw do popisu, existujúce nezmenené", sch["51"]?.code == "mix_rgbcw" && sch["51"]?.type == DpType.RAW && sch["20"]?.code == "switch_led" && sch["22"]?.max == 1000L)
    check("doplnený popis: BW-CLT1 rozpoznaný ako zmiešané svetlo", LightChannels.detect(dev("BW-CLT1", "xdd", userSchema + ("51" to sch["51"]!!)), userVals)?.backend == LightBackend.MIX)
    val shadowJson = org.json.JSONObject("{\"properties\":[{\"code\":\"mix_rgbcw\",\"dp_id\":51,\"time\":1,\"type\":\"raw\",\"value\":\"$userMix\"},{\"code\":\"switch_led\",\"dp_id\":20,\"type\":\"bool\",\"value\":true}]}")
    val shadow = CloudParse.shadow(shadowJson)
    check("shadow: DP 51 a 20", shadow.size == 2 && shadow[0].dp == 51 && shadow[0].value == userMix && shadow[1].value == true)

    // --- rozšírené technické info
    val ext = TechInfo.build(
        userDev, userVals, "Svetlo", "1.9.1",
        TechExtra(
            localAll = userVals + ("51" to userMix) + ("101" to "x"),
            cloud = CloudTech(props = props, shadow = shadow),
            log = listOf(line, "16:52:04.000  (spojenie bf1234567890abcdefgh z 192.168.1.77 prerušené)"),
        ),
    )
    check("rozšírené info: DP mimo popisu označené", ext.contains("DP 51 = \"$userMix\"") && ext.contains("[mimo popisu z cloudu]"))
    check("rozšírené info: model z cloudu s DP 51", ext.contains("DP 51  mix_rgbcw  raw") && ext.contains("[mimo popisu /v1.1]"))
    check("rozšírené info: shadow a záznam", ext.contains("shadow/properties") && ext.contains("Sledovanie zmien") && ext.contains("16:52:03.120"))
    check("rozšírené info: bez kľúča, IP a celého Device ID", !ext.contains("SecretKey123456!") && !ext.contains("192.168.1.77") && !ext.contains("bf1234567890abcdefgh"))
    println("LightChannels: všetko OK")
}
