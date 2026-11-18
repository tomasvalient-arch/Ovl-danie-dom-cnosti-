import sk.domov.ovladanie.*

// Jeden prepočet jasu (hodnota zariadenia <-> %) pre kruh, posuvník, dlaždicu, widget a scény.

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

fun main() {
    // 10–1000 (V2): presne hodnota / 10, minimum = 1 % ako Tuya appka
    check("10–1000: 10 = 1 %", Bright.percent(10, 10, 1000) == 1)
    check("10–1000: 500 = 50 %, 1000 = 100 %, 100 = 10 %", Bright.percent(500, 10, 1000) == 50 && Bright.percent(1000, 10, 1000) == 100 && Bright.percent(100, 10, 1000) == 10)
    check("10–1000: % -> hodnota = 10 × %", (1..100).all { Bright.raw(it, 10, 1000) == it * 10 })
    // 25–255 (V1)
    check("25–255: 25 = 1 % (nie 10 %)", Bright.percent(25, 25, 255) == 1)
    check("25–255: 255 = 100 %", Bright.percent(255, 25, 255) == 100)
    check("25–255: 1 % -> 25, 100 % -> 255", Bright.raw(1, 25, 255) == 25 && Bright.raw(100, 25, 255) == 255)
    // 0–1000 (DP 51 / iné)
    check("0–1000: 0 = 1 %, 1000 = 100 %, 500 = 50 %", Bright.percent(0, 0, 1000) == 1 && Bright.percent(1000, 0, 1000) == 100 && Bright.percent(500, 0, 1000) == 50)
    for ((min, max) in listOf(10 to 1000, 25 to 255, 0 to 1000, 0 to 255, 1 to 100)) {
        check("$min–$max: tam a späť presne pre 1–100 %", (1..100).all { Bright.percent(Bright.raw(it, min, max), min, max) == it })
        check("$min–$max: mimo rozsahu sa oreže", Bright.percent(max * 2, min, max) == 100 && Bright.percent(-5, min, max) == 1 && Bright.raw(0, min, max) == Bright.raw(1, min, max) && Bright.raw(500, min, max) == max)
    }
    check("bez hodnoty -> 100 %", Bright.percent(null, 10, 1000) == 100)

    // svetlo s rozsahom zo schémy: LED pas s DP 1–5 (formát V1), ale bright_value 10–1000
    val v = mapOf<String, Any>("1" to true, "2" to "white", "3" to 10, "4" to 0)
    val schema = mapOf("3" to DpSchema("bright_value", DpType.INTEGER, min = 10, max = 1000), "4" to DpSchema("temp_value", DpType.INTEGER, min = 0, max = 1000))
    val noSchema = Light.detect(v)!!
    val withSchema = Light.detect(v, schema)!!
    check("LT11: rozsah zo schémy 10–1000", withSchema.brightMin == 10 && withSchema.brightMax == 1000 && withSchema.tempMax == 1000)
    check("LT11: hodnota 10 = 1 % (kruh, posuvník aj dlaždica rovnako)", Light.read(withSchema, v).whitePercent == 1 && Light.summary(withSchema, v) == "Zapnuté · Biela 1 %")
    check("LT11: 1 % -> 10, 50 % -> 500", Light.whiteBrightness(withSchema, 1)["3"] == 10 && Light.whiteBrightness(withSchema, 50)["3"] == 500)
    check("V1 bez schémy: 25–255, 25 = 1 %", noSchema.brightMin == 25 && Light.read(noSchema, v + ("3" to 25)).whitePercent == 1 && Light.whiteBrightness(noSchema, 1)["3"] == 25)
    val v2 = mapOf<String, Any>("20" to true, "21" to "white", "22" to 10, "23" to 1000)
    check("V2 bez schémy: 10 = 1 %", Light.read(Light.detect(v2)!!, v2).whitePercent == 1)
    check("V2: Noc 10 % -> 100", Scenes.dimCommand(Device(id = "a", name = "A", ip = "1", devId = "d", localKey = "k"), v2, 10)?.get("22") == 100)
    // svetlo s dvoma zdrojmi (22, 23, 24)
    val dual = Light.detectDual(mapOf("22" to 10, "23" to 0, "24" to "000003e803e8"), "", "")!!
    check("dvojité svetlo: 10 = 1 %, 1 % -> 10", Light.readDual(dual, mapOf("22" to 10, "23" to 0, "24" to "000003e803e8")).whitePercent == 1 && Light.dualWhiteBrightness(dual, 1)["22"] == 10)
    println("Bright: všetko OK")
}
