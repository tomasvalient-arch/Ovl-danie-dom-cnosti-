import sk.domov.ovladanie.Fryer

private var failed = 0
private fun check(name: String, ok: Boolean) {
    if (!ok) { failed++; println("FAIL: $name") }
}

fun main() {
    // hodnoty z reálnej fritézy BH-AF2 (vypnutá): 1 = hlavný vypínač (v appke skrytý), 2 štart/stop, 3 režim, 5 stav, 6 teplota, 9 čas
    val v = mapOf<String, Any>("1" to false, "2" to false, "3" to "Chips", "5" to "standby", "6" to 200, "9" to 0, "10" to 1, "12" to 0)
    val spec = Fryer.detect(v)!!
    check("power dp 1", spec.power == "1")
    check("handled", spec.handled == setOf("1", "2", "3", "5", "6", "9", "10"))
    val st = Fryer.read(spec, v)
    check("read", !st.running && st.mode == "Chips" && st.statusRaw == "standby" && st.tempC == 200 && st.minutes == 0 && st.remainingMin == 1)
    check("summary off", Fryer.summary(spec, v) == "Vypnuté")
    check("summary standby", Fryer.summary(spec, v + ("1" to true)) == "Zapnuté · Pohotovosť")
    check("summary running", Fryer.summary(spec, v + ("1" to true) + ("2" to true) + ("5" to "cooking") + ("10" to 12)) == "Varí · zostáva 12 min")
    check("status unknown", Fryer.statusLabel("preheat") == "preheat")
    check("temp 5-step", Fryer.temperature(spec, 183f) == mapOf<String, Any>("6" to 185))
    check("temp clamp low", Fryer.temperature(spec, 20f) == mapOf<String, Any>("6" to 80))
    check("temp clamp high", Fryer.temperature(spec, 260f) == mapOf<String, Any>("6" to 200))
    check("time", Fryer.cookTime(spec, 14.6f) == mapOf<String, Any>("9" to 15))
    check("time clamp", Fryer.cookTime(spec, 0f) == mapOf<String, Any>("9" to 1) && Fryer.cookTime(spec, 99f) == mapOf<String, Any>("9" to 60))
    check("start", Fryer.start(spec, true) == mapOf<String, Any>("2" to true))
    check("mode", Fryer.mode(spec, "Cake") == mapOf<String, Any>("3" to "Cake"))
    // bez hodnoty 1 (power) a bez 10 (zostáva)
    val s2 = Fryer.detect(v - "1" - "10")!!
    check("no power", s2.power == null && s2.remaining == null)
    check("summary without power dp", Fryer.summary(s2, v - "1") == "Zapnuté · Pohotovosť")
    // nie je fritéza
    check("not fryer: light", Fryer.detect(mapOf("20" to true, "21" to "white", "22" to 500)) == null)
    check("not fryer: dp2 not bool", Fryer.detect(v + ("2" to "x")) == null)
    check("not fryer: missing temp", Fryer.detect(v - "6") == null)
    println(if (failed == 0) "FRYER PASSED" else "FRYER FAILED: $failed")
}
