import sk.domov.ovladanie.*

// Upozornenia fritézy: varí -> posledná minúta -> dovarila, Stop, varí keď nikto nie je doma, porucha, intervaly.

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

val IDLE = mapOf<String, Any>("1" to true, "2" to false, "3" to "Chips", "5" to "standby", "6" to 200, "9" to 0, "10" to 1, "12" to 0)
fun cooking(rem: Int) = IDLE + mapOf("2" to true, "5" to "cooking", "9" to 3, "10" to rem)

fun main() {
    val on = FryerAlertSettings()
    var s = FryerWatchState()
    var t = 1_000_000L
    val all = mutableListOf<Pair<Long, FryerEvent>>()
    fun feed(v: Map<String, Any>, nobody: Boolean? = false, settings: FryerAlertSettings = on): List<FryerEvent> {
        val (n, ev) = FryerWatch.step(s, FryerWatch.snapshot(v)!!, t, nobody, settings)
        s = n
        ev.forEach { all += t to it }
        return ev
    }

    // ---- varenie 3 min, kontrola každých 20 s; zariadenie hlási minúty zaokrúhlené nahor
    check("štart -> Started", feed(cooking(3)) == listOf(FryerEvent.Started))
    check("odhad konca ~2,5 min", FryerWatch.remainingMs(s, t) in 140_000L..160_000L)
    check("priebežný text", FryerWatch.remainingText(s, t) == "zostáva 3 min")
    val startT = t
    var oneMinuteAt = -1L
    var doneAt = -1L
    for (i in 1..20) {
        t += 20_000L
        val elapsed = t - startT
        val remSec = 150_000L - elapsed
        val ev = if (remSec > 0) feed(cooking(((remSec + 59_999) / 60_000).toInt())) else feed(IDLE + ("10" to 0))
        if (FryerEvent.OneMinute in ev) oneMinuteAt = remSec
        if (FryerEvent.Done in ev) doneAt = elapsed
    }
    check("posledná minúta: práve raz", all.count { it.second == FryerEvent.OneMinute } == 1)
    check("posledná minúta: keď zostáva ≤ 65 s (zostávalo ${oneMinuteAt / 1000} s)", oneMinuteAt in 1..65_000L)
    check("dovarila: práve raz, po skončení", all.count { it.second == FryerEvent.Done } == 1 && doneAt in 150_000L..175_000L)
    check("po dovarení nebeží", !s.running && s.endsAt == 0L)

    // ---- skorý Stop (zostáva veľa) -> Stopped, nie Dovarila
    s = FryerWatchState(); all.clear()
    feed(cooking(15)); t += 20_000; feed(cooking(15)); t += 20_000
    check("Stop počas varenia -> Stopped (nie Dovarila)", feed(IDLE + ("10" to 15)) == listOf(FryerEvent.Stopped))
    // stav „completed“ hneď znamená dovarila
    s = FryerWatchState(); feed(cooking(10)); t += 20_000
    check("stav completed -> Dovarila", feed(IDLE + mapOf("5" to "completed", "10" to 0)) == listOf(FryerEvent.Done))

    // ---- nikto doma
    s = FryerWatchState()
    check("varí a nikto doma -> Started + AwayRunning", feed(cooking(20), nobody = true) == listOf(FryerEvent.Started, FryerEvent.AwayRunning))
    t += 60_000
    check("AwayRunning len raz", feed(cooking(19), nobody = true).isEmpty())
    t += 60_000; feed(IDLE + ("10" to 18)); t += 60_000
    check("nové varenie, nikto doma -> znova", FryerEvent.AwayRunning in feed(cooking(10), nobody = true))
    s = FryerWatchState()
    check("doma / nevieme -> bez bezpečnostného upozornenia", feed(cooking(20), nobody = false) == listOf(FryerEvent.Started) &&
        run { s = FryerWatchState(); feed(cooking(20), nobody = null) == listOf(FryerEvent.Started) })
    s = FryerWatchState()
    check("vypnuté bezpečnostné upozornenie", feed(cooking(20), nobody = true, settings = on.copy(safety = false)) == listOf(FryerEvent.Started))

    // ---- nastavenia
    s = FryerWatchState(); feed(cooking(2), settings = on.copy(oneMinute = false)); t += 40_000
    check("bez „Minútu pred koncom“", FryerEvent.OneMinute !in feed(cooking(1), settings = on.copy(oneMinute = false)))
    t += 60_000
    check("bez „Keď dovarí“", feed(IDLE + ("10" to 0), settings = on.copy(done = false)).isEmpty())
    s = FryerWatchState()
    check("upozornenia vypnuté: žiadne upozornenia", feed(cooking(1), nobody = true, settings = on.copy(enabled = false)) == listOf(FryerEvent.Started))

    // ---- porucha a pretrasenie
    s = FryerWatchState()
    val f = feed(IDLE + ("12" to 1))
    check("porucha E1", f.size == 1 && (f[0] as FryerEvent.Fault).text.startsWith("E1"))
    check("porucha sa neopakuje", feed(IDLE + ("12" to 1)).isEmpty())
    check("porucha E2", FryerWatch.faultText(2) == "E2 (skrat teplotnej sondy)")
    val shakeSchema = mapOf("104" to DpSchema("shake_remind", DpType.BOOLEAN))
    val snap = FryerWatch.snapshot(cooking(5) + ("104" to true), shakeSchema)!!
    check("pretrasenie podľa schémy", snap.shake && FryerWatch.step(FryerWatchState(running = true), snap, t, false, on).second == listOf(FryerEvent.Shake))
    check("pretrasenie podľa stavu", FryerWatch.snapshot(cooking(5) + ("5" to "shake"))!!.shake)
    check("pozastavené = nevarí", !FryerWatch.snapshot(cooking(5) + ("5" to "pausing"))!!.running)

    // ---- intervaly
    val run = FryerWatchState(running = true, endsAt = t + 10 * 60_000L)
    check("doma počas varenia 20 s", FryerWatch.nextPollMs(run, t, true) == 20_000L)
    check("mimo domu počas varenia 60 s", FryerWatch.nextPollMs(run, t, false) == 60_000L)
    check("na pozadí doma: varenie 60 s, nevarí 3 min, posledná minúta 30 s",
        FryerWatch.nextPollMs(run, t, true, background = true) == 60_000L &&
            FryerWatch.nextPollMs(FryerWatchState(), t, true, background = true) == 180_000L &&
            FryerWatch.nextPollMs(run.copy(endsAt = t + 70_000L), t, true, background = true) == 30_000L)
    check("na pozadí: zobudiť sa presne na poslednú minútu", FryerWatch.nextPollMs(run.copy(endsAt = t + 100_000L), t, true, background = true) == 40_000L)
    check("nevarí: doma 90 s, cloud 10 min", FryerWatch.nextPollMs(FryerWatchState(), t, true) == 90_000L && FryerWatch.nextPollMs(FryerWatchState(), t, false) == 600_000L)
    check("zobudiť sa na poslednú minútu", FryerWatch.nextPollMs(run.copy(endsAt = t + 70_000L), t, true) == 10_000L &&
        FryerWatch.nextPollMs(run.copy(endsAt = t + 75_000L + 8_000L), t, true) == 23_000L.coerceAtMost(20_000L))
    check("lokálne: posledná minúta podľa odpočtu", FryerWatch.localOneMinuteDue(run.copy(endsAt = t + 50_000L), t, on) && !FryerWatch.localOneMinuteDue(run, t, on))
    check("uloženie a načítanie stavu", FryerWatchState.fromJson(run.copy(lastRemainingMin = 7, awayAlerted = true).toJson().toString()) == run.copy(lastRemainingMin = 7, awayAlerted = true))
    check("nastavenia: predvolene všetko zapnuté", FryerAlertSettings.fromJson(null) == FryerAlertSettings(true, true, true, true) &&
        FryerAlertSettings.fromJson(on.copy(done = false).toJson().toString()).done == false)
    check("nikto doma: mimo domu a nikto neostal", FryerWatch.nobodyHome(PresenceState.AWAY, emptyList(), true) == true)
    check("nikto doma: ostala priateľka -> nie", FryerWatch.nobodyHome(PresenceState.AWAY, listOf(Member("x", "Priateľka", 1L)), true) == false)
    check("nikto doma: automatika vypnutá -> nevieme", FryerWatch.nobodyHome(PresenceState.AWAY, emptyList(), false) == null)
    check("fritéza podľa kategórie kqzg", SafeOff.looksLikeFryer(Device(id = "k", name = "Kuchyňa", ip = "1", devId = "d", localKey = "k", category = "kqzg"), emptyMap()))
    println("FryerWatch: všetko OK")
}
