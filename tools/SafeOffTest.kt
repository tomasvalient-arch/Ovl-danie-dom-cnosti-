import sk.domov.ovladanie.*

// Bezpečné hromadné vypínanie: reprodukcia chyby „Všetko vypnúť zaplo fritézu BH-AF2“ a pravidlá SafeOff.

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

fun dev(id: String, name: String, category: String = "", schema: Map<String, DpSchema> = emptyMap(), model: String = "") =
    Device(id = id, name = name, ip = "10.0.0.$id".take(12), devId = "dev$id", localKey = "0123456789abcdef", category = category, schema = schema, model = model)

// BH-AF2: 1 hlavný vypínač, 2 štart/stop, 3 režim, 5 stav, 6 teplota, 9 čas, 10 zostáva, 12 porucha
val AF2_OFF = mapOf<String, Any>("1" to false, "2" to false, "3" to "Chips", "5" to "standby", "6" to 200, "9" to 0, "10" to 1, "12" to 0)
val AF2_STANDBY = AF2_OFF + ("1" to true)
val AF2_RUNNING = AF2_OFF + mapOf("1" to true, "2" to true, "5" to "cooking", "9" to 20, "10" to 20)
val AF2_SCHEMA = mapOf(
    "1" to DpSchema("switch", DpType.BOOLEAN), "2" to DpSchema("start", DpType.BOOLEAN),
    "3" to DpSchema("mode", DpType.ENUM), "5" to DpSchema("status", DpType.ENUM),
    "6" to DpSchema("temp_set", DpType.INTEGER), "9" to DpSchema("cook_time", DpType.INTEGER), "10" to DpSchema("remain_time", DpType.INTEGER),
)
val LIGHT_OFF = mapOf<String, Any>("20" to false, "21" to "white", "22" to 10, "23" to 1000)
val LIGHT_ON = LIGHT_OFF + ("20" to true)
val STRIP = mapOf<String, Any>("1" to true, "2" to "white", "3" to 255, "4" to 100)

/** Žiadny hromadný príkaz nesmie obsahovať zapnutie (true) ani štart fritézy. */
fun noTurnOn(steps: List<OffStep>, exceptDim: Set<String> = emptySet()): Boolean =
    steps.filterIsInstance<OffStep.Send>().filter { it.device.id !in exceptDim }.all { s -> s.cmd.values.none { it == true } }

fun main() {
    val fryer = dev("1", "fritéza BH-AF2")
    val fryerCloud = dev("1", "fritéza BH-AF2", schema = AF2_SCHEMA)
    val strip = dev("2", "LED pas kuchyňa -LT11", "dd")
    val light = dev("3", "BW-CLT1", "xdd")
    val devices = listOf(fryer, strip, light)
    // používateľ má pri odchode vybraté aj spotrebiče (fritézu)
    val cfg = PresenceConfig(departIds = setOf("1", "2", "3"))

    // ---- reprodukcia chyby: predtým offCommand pre vypnutú fritézu poslal {"1": false} (BH-AF2 to berie ako tlačidlo a spustí Chips 20 min)
    check("chyba: fritéza už nedostane hlavný vypínač (offCommand = null)", Scenes.offCommand(fryer, AF2_OFF) == null && Scenes.offCommand(fryer, AF2_STANDBY) == null)

    for ((label, fv) in listOf("vypnutá" to AF2_OFF, "pohotovosť" to AF2_STANDBY)) {
        val vals = mapOf("1" to fv, "2" to STRIP + ("1" to false), "3" to LIGHT_OFF)
        val steps = Scenes.allOffSteps(cfg, devices, { vals[it.id] ?: emptyMap() }) { true }
        val f = steps.first { it.device.id == "1" }
        check("Všetko vypnúť, fritéza $label: nič sa jej nepošle", f is OffStep.Idle)
        check("Všetko vypnúť, fritéza $label: žiadny príkaz na fritézu", Scenes.sends(steps).none { it.first.id == "1" })
        check("Všetko vypnúť, fritéza $label: nič sa nezapína", noTurnOn(steps))
        check("Všetko vypnúť, svetlá už zhasnuté: nič sa neposiela", Scenes.sends(steps).isEmpty())
    }
    run {
        val vals = mapOf("1" to AF2_RUNNING, "2" to STRIP, "3" to LIGHT_ON)
        val steps = Scenes.allOffSteps(cfg, devices, { vals[it.id] ?: emptyMap() }) { true }
        val plan = Scenes.sends(steps).associate { it.first.id to it.second }
        check("Všetko vypnúť, fritéza varí: len Stop {2:false}", plan["1"] == mapOf<String, Any>("2" to false))
        check("Všetko vypnúť: svetlá sa vypnú", plan["2"] == mapOf<String, Any>("1" to false) && plan["3"] == mapOf<String, Any>("20" to false))
        check("Všetko vypnúť, fritéza varí: nič sa nezapína", noTurnOn(steps))
    }
    run {
        val vals = mapOf("1" to AF2_RUNNING, "2" to STRIP, "3" to LIGHT_ON)
        val steps = Scenes.allOffSteps(cfg, devices, { vals[it.id] ?: emptyMap() }) { it.id != "1" }
        val f = steps.first { it.device.id == "1" }
        check("Všetko vypnúť, stav fritézy neznámy (nečerstvý): preskočené", f is OffStep.Skip && (f as OffStep.Skip).reason == SafeOff.SKIP_UNKNOWN)
        check("text: preskočené: fritéza BH-AF2 (stav neznámy)", SafeOff.skippedText(steps) == "preskočené: fritéza BH-AF2 (stav neznámy)")
        val partial = mapOf<String, Any>("1" to false)
        check("fritéza, ktorá poslala len DP 1: podľa názvu je to fritéza", AutoDevices.kind(fryer, partial) == AutoKind.FRYER)
        val s2 = SafeOff.offStep(fryer, partial, fresh = true)
        check("fritéza bez istého Stop DP: preskočené", s2 is OffStep.Skip && (s2 as OffStep.Skip).reason == SafeOff.SKIP_NO_STOP)
        check("fritéza bez hodnôt: preskočené", SafeOff.offStep(fryer, emptyMap(), true) is OffStep.Skip)
        check("fritéza podľa modelu (BH-AF2) aj s iným názvom", SafeOff.looksLikeFryer(dev("9", "Kuchyňa", model = "BH-AF2"), emptyMap()))
        check("starý plán z pamäte: fritéza sa vynechá", Scenes.allOffPlan(cfg, devices) { vals[it.id] ?: emptyMap() }.none { it.first.id == "1" })
    }

    // ---- Noc
    run {
        val scene = NightScene(offIds = setOf("1", "2"), dimIds = setOf("3"), dimPercent = 10)
        for ((label, fv, fresh, expect) in listOf(
            Quad("vypnutá", AF2_OFF, true, "idle"), Quad("varí", AF2_RUNNING, true, "stop"), Quad("neznámy stav", AF2_RUNNING, false, "skip"),
        )) {
            val vals = mapOf("1" to fv, "2" to STRIP, "3" to LIGHT_ON)
            val steps = Scenes.nightSteps(scene, devices, { vals[it.id] ?: emptyMap() }) { fresh || it.id != "1" }
            val f = steps.filter { it.device.id == "1" }
            check("Noc, fritéza $label: nikdy sa nestlmuje/nezapína", f.size == 1 && noTurnOn(steps, exceptDim = setOf("3")))
            val ok = when (expect) {
                "idle" -> f.single() is OffStep.Idle
                "stop" -> (f.single() as? OffStep.Send)?.cmd == mapOf<String, Any>("2" to false)
                else -> f.single() is OffStep.Skip
            }
            check("Noc, fritéza $label: $expect", ok)
        }
    }

    // ---- odchod (cloud)
    run {
        check("odchod: offCommands fritézy je prázdny (žiadne odhady switch/switch_1)", AutoDevices.offCommands(fryerCloud, AF2_OFF).isEmpty() && AutoDevices.offCommands(fryer, emptyMap()).isEmpty())
        val idle = SafeOff.cloudOff(fryerCloud, AF2_OFF, fresh = true)
        check("odchod, fritéza vypnutá: nič sa neposiela, nie je preskočená", idle.options.isEmpty() && idle.skip == null)
        val standby = SafeOff.cloudOff(fryerCloud, AF2_STANDBY, fresh = true)
        check("odchod, fritéza v pohotovosti: nič sa neposiela", standby.options.isEmpty() && standby.skip == null)
        val run = SafeOff.cloudOff(fryerCloud, AF2_RUNNING, fresh = true)
        check("odchod, fritéza varí: len start=false", run.options == listOf(listOf("start" to (false as Any))))
        val unk = SafeOff.cloudOff(fryerCloud, AF2_RUNNING, fresh = false)
        check("odchod, stav z cloudu sa nenačítal: preskočené", unk.options.isEmpty() && unk.skip == SafeOff.SKIP_UNKNOWN)
        val noCode = SafeOff.cloudOff(fryer, AF2_RUNNING, fresh = true)
        check("odchod, bez kódu Stop v schéme: preskočené", noCode.options.isEmpty() && noCode.skip == SafeOff.SKIP_NO_STOP)
        val shadow = SafeOff.shadowValues(listOf(ShadowProp(1, "switch", "bool", true), ShadowProp(2, "start", "bool", true), ShadowProp(3, "mode", "enum", "Chips"),
            ShadowProp(5, "status", "enum", "cooking"), ShadowProp(6, "temp_set", "value", 200), ShadowProp(9, "cook_time", "value", 20), ShadowProp(12, "fault", "bitmap", null)))
        check("shadow -> hodnoty podľa DP", shadow["2"] == true && shadow["5"] == "cooking" && "12" !in shadow && Fryer.detect(shadow) != null)
        val stripCloud = dev("2", "LED pas", "dd", schema = mapOf("20" to DpSchema("switch_led", DpType.BOOLEAN)))
        check("odchod, svetlo: ako doteraz switch_led=false", SafeOff.cloudOff(stripCloud, mapOf("20" to true, "21" to "white", "22" to 500), true).options.first() == listOf("switch_led" to (false as Any)))
        check("odchod, svetlo bez stavu: aj tak vypnúť (bezpečné)", SafeOff.cloudOff(stripCloud, emptyMap(), false).options.isNotEmpty())
    }

    // ---- ohrievač a neznáme zariadenia
    run {
        val heater = dev("5", "Ohrievač", "qn", schema = mapOf("1" to DpSchema("switch", DpType.BOOLEAN), "2" to DpSchema("temp_set", DpType.INTEGER)))
        check("ohrievač zapnutý (čerstvé): vypnúť 1=false", (SafeOff.offStep(heater, mapOf("1" to true, "2" to 20), true) as? OffStep.Send)?.cmd == mapOf<String, Any>("1" to false))
        check("ohrievač vypnutý: nič", SafeOff.offStep(heater, mapOf("1" to false, "2" to 20), true) is OffStep.Idle)
        check("ohrievač bez čerstvého stavu: preskočené", SafeOff.offStep(heater, mapOf("1" to true), false) is OffStep.Skip)
        val heaterNoSchema = dev("6", "Radiátor", "qn")
        check("ohrievač bez schémy (vypínač len odhad): preskočené", SafeOff.offStep(heaterNoSchema, mapOf("1" to true, "4" to true), true) is OffStep.Skip)
        val unknown = dev("7", "Niečo")
        check("neznáme zariadenie (len odhad vypínača): preskočené", SafeOff.offStep(unknown, mapOf("3" to true, "7" to false), true) is OffStep.Skip)
        check("neznáme zariadenie: odchod bez odhadov kódov", AutoDevices.offCommands(unknown, mapOf("3" to true)).isEmpty())
        val manual = dev("8", "Zvlhčovač").copy(powerDp = "1")
        check("neznáme s ručne nastaveným vypínačom: vypne sa, ak je zapnuté", (SafeOff.offStep(manual, mapOf("1" to true, "5" to "auto"), true) as? OffStep.Send)?.cmd == mapOf<String, Any>("1" to false))
        check("príchod: neznáme zariadenie bez istého vypínača sa nezapne", AutoDevices.arriveCommand(unknown, mapOf("3" to false, "7" to false)) == null)
        check("príchod: fritéza nikdy", AutoDevices.arriveCommand(fryer, AF2_OFF) == null && AutoDevices.arriveCommand(fryer, mapOf("1" to false)) == null)
        check("príchod: svetlo ako doteraz", AutoDevices.arriveCommand(light, LIGHT_OFF) == mapOf<String, Any>("20" to true))
        check("Noc: spotrebič sa nestlmí ani keby bol vybratý", Scenes.nightSteps(NightScene(offIds = emptySet(), dimIds = setOf("5")), listOf(heater), { mapOf("1" to false) }) { true }.isEmpty())
    }

    // ---- overenie po odoslaní
    run {
        val r = SafeOff.verify(fryer, emptyMap(), AF2_OFF, AF2_RUNNING)
        check("overenie: fritéza sa rozbehla -> Stop a varovanie", r?.cmd == mapOf<String, Any>("2" to false) && r.warning.contains("Stop"))
        check("overenie: fritéza ostala vypnutá -> v poriadku", SafeOff.verify(fryer, emptyMap(), AF2_OFF, AF2_STANDBY) == null)
        check("overenie: Stop prešiel -> v poriadku", SafeOff.verify(fryer, mapOf("2" to false), AF2_RUNNING, AF2_STANDBY) == null)
        check("overenie: Stop neprešiel -> znova Stop", SafeOff.verify(fryer, mapOf("2" to false), AF2_RUNNING, AF2_RUNNING)?.cmd == mapOf<String, Any>("2" to false))
        check("overenie: fritéza varila už pred akciou a bola preskočená -> nesiahať", SafeOff.verify(fryer, emptyMap(), AF2_RUNNING, AF2_RUNNING) == null)
        val heater = dev("5", "Ohrievač", "qn", schema = mapOf("1" to DpSchema("switch", DpType.BOOLEAN)))
        check("overenie: ohrievač po vypnutí stále zapnutý -> znova vypnúť", SafeOff.verify(heater, mapOf("1" to false), mapOf("1" to true), mapOf("1" to true))?.cmd == mapOf<String, Any>("1" to false))
        check("overenie: ohrievač vypnutý -> v poriadku", SafeOff.verify(heater, mapOf("1" to false), mapOf("1" to true), mapOf("1" to false)) == null)
    }
    println("SafeOff: všetko OK")
}

data class Quad(val a: String, val b: Map<String, Any>, val c: Boolean, val d: String)
