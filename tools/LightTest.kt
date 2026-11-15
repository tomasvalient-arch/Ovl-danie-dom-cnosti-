import sk.domov.ovladanie.*

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

fun main() {
    // V2 svetlo
    val v2 = mapOf<String, Any>("20" to true, "21" to "white", "22" to 500, "23" to 250, "24" to "00e803e803e8", "25" to "x", "26" to 0)
    val s2 = Light.detect(v2)!!
    check("detect V2", s2.format == LightFormat.V2 && s2.power == "20" && s2.color == "24" && s2.temp == "23")
    val st2 = Light.read(s2, v2)
    check("V2 read on/mode", st2.on && st2.isWhite)
    check("V2 bright 50 %", st2.whitePercent == 50)
    check("V2 temp 0.25", Math.abs(st2.temp - 0.25f) < 0.001f)
    check("V2 colour parse", Math.abs(st2.color.h - 232f) < 0.01f && st2.color.s == 1f && st2.color.v == 1f)
    check("V2 summary", Light.summary(s2, v2) == "Zapnuté · Biela 50 %")
    check("V2 summary off", Light.summary(s2, v2 + ("20" to false)) == "Vypnuté")
    check("V2 summary colour", Light.summary(s2, v2 + ("21" to "colour")) == "Zapnuté · Farba 100 %")

    // príkazy V2
    check("V2 power cmd", Light.power(s2, false) == mapOf<String, Any>("20" to false))
    check("V2 white bright 80", Light.whiteBrightness(s2, 80) == mapOf<String, Any>("21" to "white", "22" to 800))
    check("V2 white bright 1 -> min 10", Light.whiteBrightness(s2, 1)["22"] == 10)
    check("V2 temp 1.0", Light.whiteTemp(s2, 1f)["23"] == 1000)
    val red = Light.colour(s2, Hsv(0f, 1f, 1f))
    check("V2 red hex", red == mapOf<String, Any>("21" to "colour", "24" to "000003e803e8"))
    val blue = Light.colour(s2, Hsv(240f, 1f, 0.5f))
    check("V2 blue half hex", blue["24"] == "00f003e801f4")
    // okrúhlenie hex -> parse -> encode
    val rt = Light.encodeColor(LightFormat.V2, Light.parseColor(LightFormat.V2, "00f003e801f4"))
    check("V2 round trip", rt == "00f003e801f4")

    // V1 svetlo
    val v1 = mapOf<String, Any>("1" to true, "2" to "colour", "3" to 255, "4" to 0, "5" to "ff00000000ffff")
    val s1 = Light.detect(v1)!!
    check("detect V1", s1.format == LightFormat.V1 && s1.power == "1" && s1.color == "5")
    val st1 = Light.read(s1, v1)
    check("V1 colour mode", st1.isColour && st1.whitePercent == 100)
    check("V1 colour parse", st1.color.h == 0f && st1.color.s == 1f && st1.color.v == 1f)
    check("V1 red hex", Light.encodeColor(LightFormat.V1, Hsv(0f, 1f, 1f)) == "ff00000000ffff")
    check("V1 green hex", Light.encodeColor(LightFormat.V1, Hsv(120f, 1f, 1f)) == "00ff00" + "0078" + "ffff")
    check("V1 white bright 50", Light.whiteBrightness(s1, 50) == mapOf<String, Any>("2" to "white", "3" to 128))
    check("V1 white bright 1 -> min 25", Light.whiteBrightness(s1, 1)["3"] == 25)

    // nesvetlo
    check("not a light", Light.detect(mapOf("1" to true, "2" to 5, "3" to 10)) == null)
    check("fryer-like not a light", Light.detect(mapOf("1" to true, "2" to false, "3" to 200)) == null)

    // HSV -> RGB
    check("rgb red", Light.hsvToRgb(0f, 1f, 1f).toList() == listOf(255, 0, 0))
    check("rgb cyan", Light.hsvToRgb(180f, 1f, 1f).toList() == listOf(0, 255, 255))
    check("rgb white", Light.hsvToRgb(0f, 0f, 1f).toList() == listOf(255, 255, 255))
    check("rgb 360 = red", Light.hsvToRgb(360f, 1f, 1f).toList() == listOf(255, 0, 0))
    println("ALL PASSED")
}
