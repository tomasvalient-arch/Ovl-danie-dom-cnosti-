import sk.domov.ovladanie.*
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

// Test automatiky príchod/odchod bez Androidu: stavový automat, výpočet východu/západu slnka (porovnanie s knižnicou
// astral 3.2), výber zariadení, poistka a podpis cloud príkazu (porovnanie s tinytuya 1.21 Cloud.sendcommand).

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

data class Ref(val name: String, val lat: Double, val lon: Double, val date: String, val rise: Long, val set: Long)

// astral.sun.sunrise/sunset(Observer(lat, lon), date) – sekundy UTC
val REFS = listOf(
    Ref("Bratislava", 48.1486, 17.1077, "2026-01-15", 1768459045L, 1768490692L),
    Ref("Bratislava", 48.1486, 17.1077, "2026-03-20", 1773982504L, 1774026230L),
    Ref("Bratislava", 48.1486, 17.1077, "2026-06-21", 1782010295L, 1782068110L),
    Ref("Bratislava", 48.1486, 17.1077, "2026-09-23", 1790138379L, 1790182044L),
    Ref("Bratislava", 48.1486, 17.1077, "2026-11-16", 1794808795L, 1794841928L),
    Ref("Bratislava", 48.1486, 17.1077, "2026-12-21", 1797835167L, 1797865186L),
    Ref("Kosice", 48.7164, 21.2611, "2026-01-15", 1768458177L, 1768489567L),
    Ref("Kosice", 48.7164, 21.2611, "2026-06-21", 1782009138L, 1782067273L),
    Ref("Kosice", 48.7164, 21.2611, "2026-11-16", 1794807907L, 1794840823L),
    Ref("Kosice", 48.7164, 21.2611, "2026-12-21", 1797834318L, 1797864041L),
    Ref("Oslo", 59.9139, 10.7522, "2026-01-15", 1768464268L, 1768488530L),
    Ref("Oslo", 59.9139, 10.7522, "2026-06-21", 1782006859L, 1782074596L),
    Ref("Oslo", 59.9139, 10.7522, "2026-12-21", 1797841119L, 1797862286L),
    Ref("NewYork", 40.7128, -74.006, "2026-01-15", 1768479488L, 1768513990L),
    Ref("NewYork", 40.7128, -74.006, "2026-11-16", 1794829459L, 1794865011L),
)

fun main() {
    // ---------------------------------------------------------------- stavový automat
    val d = 3 * 60_000L
    var s = PresenceStatus()
    var r = PresenceMachine.step(s, PresenceEvent.HomeSeen(1000), d)
    check("prvé spustenie doma: bez akcie", r.status.state == PresenceState.HOME && r.action == null)
    s = r.status
    r = PresenceMachine.step(s, PresenceEvent.HomeSeen(2000), d)
    check("doma + doma: nič", r.status == s && r.action == null)
    r = PresenceMachine.step(s, PresenceEvent.HomeLost(10_000), d); s = r.status
    check("odpojenie: čakanie, bez akcie", s.state == PresenceState.LEAVING && s.since == 10_000L && r.action == null)
    check("termín kontroly = odpojenie + 3 min", PresenceMachine.dueAt(s, d) == 10_000L + d)
    r = PresenceMachine.step(s, PresenceEvent.Tick(10_000 + d - 1), d)
    check("pred uplynutím času: stále čaká", r.status.state == PresenceState.LEAVING && r.action == null)
    r = PresenceMachine.step(s, PresenceEvent.HomeLost(20_000), d)
    check("ďalšia strata siete neposúva čas", r.status.since == 10_000L)
    // krátky výpadok
    val back = PresenceMachine.step(s, PresenceEvent.HomeSeen(70_000), d)
    check("krátky výpadok: späť doma BEZ príchodu", back.status.state == PresenceState.HOME && back.action == null)
    // odchod
    r = PresenceMachine.step(s, PresenceEvent.Tick(10_000 + d), d)
    check("po 3 min: mimo domu + ODCHOD", r.status.state == PresenceState.AWAY && r.action == PresenceAction.DEPART)
    s = r.status
    check("mimo domu: žiadna kontrola", PresenceMachine.dueAt(s, d) == null)
    check("mimo domu + tick: nič", PresenceMachine.step(s, PresenceEvent.Tick(10_000_000), d).action == null)
    check("mimo domu + strata: nič", PresenceMachine.step(s, PresenceEvent.HomeLost(10_000_000), d).let { it.action == null && it.status == s })
    r = PresenceMachine.step(s, PresenceEvent.HomeSeen(20_000_000), d)
    check("návrat: doma + PRÍCHOD", r.status.state == PresenceState.HOME && r.action == PresenceAction.ARRIVE)
    check("neznámy stav + mimo siete: mimo domu bez akcie",
        PresenceMachine.step(PresenceStatus(), PresenceEvent.HomeLost(5), d).let { it.status.state == PresenceState.AWAY && it.action == null })
    check("neznámy stav + tick: nič", PresenceMachine.step(PresenceStatus(), PresenceEvent.Tick(5), d).action == null)
    // celý deň: doma, výpadok 1 min, odchod, návrat
    var st = PresenceStatus(); val acts = mutableListOf<PresenceAction>()
    fun ev(e: PresenceEvent) { val x = PresenceMachine.step(st, e, d); st = x.status; x.action?.let { acts += it } }
    ev(PresenceEvent.HomeSeen(0)); ev(PresenceEvent.HomeLost(100_000)); ev(PresenceEvent.Tick(130_000)); ev(PresenceEvent.HomeSeen(160_000))
    ev(PresenceEvent.Tick(500_000)); ev(PresenceEvent.HomeLost(1_000_000)); ev(PresenceEvent.Tick(1_100_000)); ev(PresenceEvent.Tick(1_000_000 + d))
    ev(PresenceEvent.HomeLost(2_000_000)); ev(PresenceEvent.HomeSeen(9_000_000)); ev(PresenceEvent.HomeSeen(9_100_000))
    check("scenár dňa: len jeden odchod a jeden príchod", acts == listOf(PresenceAction.DEPART, PresenceAction.ARRIVE))
    check("stav sa uloží a načíta", PresenceStatus.fromJson(PresenceStatus(PresenceState.LEAVING, 123).toJson().toString()) == PresenceStatus(PresenceState.LEAVING, 123))
    check("poškodený stav = neznámy", PresenceStatus.fromJson("blbost").state == PresenceState.UNKNOWN)

    // ---------------------------------------------------------------- domáca sieť
    val home = HomeNetwork("192.168.1.1", "192.168.1.0/24")
    check("rozsah siete", HomeNetwork.subnetOf("192.168.1.37", 24) == "192.168.1.0/24" && HomeNetwork.subnetOf("10.0.5.200", 16) == "10.0.0.0/16")
    check("rovnaký router a rozsah = domov", home.matches(HomeNetwork("192.168.1.1", "192.168.1.0/24")))
    check("iný router ≠ domov", !home.matches(HomeNetwork("192.168.0.1", "192.168.0.0/24")))
    check("iný rozsah ≠ domov", !home.matches(HomeNetwork("192.168.1.1", "192.168.0.0/16")))
    val homeSsid = home.copy(ssid = "Doma")
    check("iné SSID ≠ domov", !homeSsid.matches(HomeNetwork("192.168.1.1", "192.168.1.0/24", ssid = "Kaviaren")))
    check("neznáme SSID (bez polohy) nevadí", homeSsid.matches(HomeNetwork("192.168.1.1", "192.168.1.0/24")))
    // DHCP server (Android 11+): porovná sa len keď je známy na oboch stranách
    val homeDhcp = HomeNetwork("192.168.1.1", "192.168.1.0/24", dhcpServer = "192.168.1.1")
    check("rovnaký DHCP server = domov", homeDhcp.matches(HomeNetwork("192.168.1.1", "192.168.1.0/24", dhcpServer = "192.168.1.1")))
    check("iný DHCP server ≠ domov", !homeDhcp.matches(HomeNetwork("192.168.1.1", "192.168.1.0/24", dhcpServer = "192.168.1.254")))
    check("neznámy DHCP server (Android 10) nevadí", homeDhcp.matches(HomeNetwork("192.168.1.1", "192.168.1.0/24")))
    check("starý odtlačok bez DHCP nevadí", home.matches(HomeNetwork("192.168.1.1", "192.168.1.0/24", dhcpServer = "192.168.1.1")))
    check("SSID bez úvodzoviek / <unknown ssid>", HomeNetwork.cleanSsid("\"Doma\"") == "Doma" && HomeNetwork.cleanSsid("<unknown ssid>") == "")

    // ---------------------------------------------------------------- nastavenia
    val cfg = PresenceConfig(enabled = true, home = homeSsid, arriveIds = setOf("a"), departIds = setOf("a", "b"), departDelayMin = 5,
        onlyWhenDark = true, darkMode = DarkMode.MANUAL, lat = 48.15, lon = 17.11, darkFrom = 18 * 60, darkTo = 7 * 60,
        guardEnabled = true, guardIds = setOf("b"), guardMinutes = 20, guardRefreshMin = 4)
    check("nastavenia sa uložia a načítajú", PresenceConfig.fromJson(cfg.toJson().toString()) == cfg)
    check("predvolené: vypnuté, poistka vypnutá, 3 min", PresenceConfig.fromJson(null).let { !it.enabled && !it.guardEnabled && it.departDelayMin == 3 && it.departIds == null })
    check("odpočet poistky aspoň interval + 5 min", PresenceConfig(guardMinutes = 5, guardRefreshMin = 5).guardSeconds == 600)

    // ---------------------------------------------------------------- tma
    check("okno cez polnoc", Darkness.inWindow(23 * 60, 17 * 60, 390) && Darkness.inWindow(60, 17 * 60, 390) && !Darkness.inWindow(12 * 60, 17 * 60, 390))
    check("okno cez deň", Darkness.inWindow(10 * 60, 9 * 60, 11 * 60) && !Darkness.inWindow(11 * 60, 9 * 60, 11 * 60))
    check("čas hh:mm", Darkness.parseHhmm("17:30") == 1050 && Darkness.parseHhmm("7.05") == 425 && Darkness.parseHhmm("7") == 420 &&
        Darkness.parseHhmm("25:00") == null && Darkness.hhmm(425) == "7:05")
    for (ref in REFS) {
        val day = LocalDate.parse(ref.date).atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1000
        val rise = Sun.event(ref.lat, ref.lon, day, true)!! / 1000
        val set = Sun.event(ref.lat, ref.lon, day, false)!! / 1000
        // astral vracia udalosti v rámci UTC dňa; pri západných dĺžkach môže byť západ o deň posunutý
        fun diff(a: Long, b: Long): Long { val x = abs(a - b) % 86400; return minOf(x, 86400 - x) }
        val tol = if (ref.lon < 0) 180 else 60
        check("východ ${ref.name} ${ref.date} (±${tol}s, rozdiel ${diff(rise, ref.rise)}s)", diff(rise, ref.rise) <= tol)
        check("západ ${ref.name} ${ref.date} (±${tol}s, rozdiel ${diff(set, ref.set)}s)", diff(set, ref.set) <= tol)
        if (ref.lon > 0) {
            check("tma 5 min po západe, svetlo 5 min pred (${ref.name} ${ref.date})",
                Sun.isDark(ref.lat, ref.lon, (ref.set + 300) * 1000) && !Sun.isDark(ref.lat, ref.lon, (ref.set - 300) * 1000) &&
                    Sun.isDark(ref.lat, ref.lon, (ref.rise - 300) * 1000) && !Sun.isDark(ref.lat, ref.lon, (ref.rise + 300) * 1000))
        }
    }
    check("polárna noc v Tromsø: žiadny východ, celý deň tma",
        Sun.event(69.65, 18.96, LocalDate.parse("2026-12-21").atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1000, true) == null &&
            Sun.isDark(69.65, 18.96, 1797854400000L))
    val noon = 1794830400000L // 2026-11-16 11:00 UTC
    val dusk = 1794843000000L // 2026-11-16 14:30 UTC = 15:30 SEČ, ešte svetlo; 16:30 tma
    val cfgSun = PresenceConfig(onlyWhenDark = true, darkMode = DarkMode.SUN, lat = 48.1486, lon = 17.1077)
    check("Darkness podľa slnka: poludnie svetlo, 17:00 SEČ tma",
        !Darkness.isDark(cfgSun, noon, 12 * 60) && Darkness.isDark(cfgSun, dusk + 3_600_000L, 17 * 60))
    check("Darkness podľa slnka bez polohy = stred Slovenska (nie ručný čas)",
        !Darkness.isDark(cfgSun.copy(lat = null, lon = null), noon, 18 * 60) && Darkness.isDark(cfgSun.copy(lat = null, lon = null), dusk + 3_600_000L, 12 * 60))
    // západ sa mení s dátumom: Bratislava 16.11. ~16:12 SEČ, 21.12. ~15:59, 21.6. ~20:55 SELČ
    val ba = PresenceConfig(lat = 48.1486, lon = 17.1077)
    val baPlace = Darkness.place(ba)
    val bt = java.util.TimeZone.getTimeZone("Europe/Bratislava")
    val nov = SunTimes.day(baPlace, 1794830400000L, bt).second!!
    val dec = SunTimes.day(baPlace, 1797854400000L, bt).second!!
    val jun = SunTimes.day(baPlace, 1782043200000L, bt).second!!
    check("dnešný západ podľa dátumu: 16.11. ${Darkness.hhmm(nov)}, 21.12. ${Darkness.hhmm(dec)}, 21.6. ${Darkness.hhmm(jun)}",
        nov in (16 * 60 + 8)..(16 * 60 + 16) && dec in (15 * 60 + 55)..(16 * 60 + 3) && jun in (20 * 60 + 51)..(20 * 60 + 59))

    // ---------------------------------------------------------------- zariadenia
    val lightV = mapOf<String, Any>("20" to true, "21" to "white", "22" to 500, "26" to 0)
    val light = Device(id = "L", name = "Lampa", ip = "1", devId = "dl", localKey = "k")
    val sock = Device(id = "S", name = "Zásuvka", ip = "2", devId = "ds", localKey = "k", category = "cz",
        schema = mapOf("1" to DpSchema("switch_1", DpType.BOOLEAN), "9" to DpSchema("countdown_1", DpType.INTEGER, unit = "s", min = 0, max = 86400),
            "19" to DpSchema("cur_power", DpType.INTEGER, unit = "W", scale = 1)))
    val sockV = mapOf<String, Any>("1" to false, "9" to 0, "19" to 0)
    val fryerV = mapOf<String, Any>("1" to true, "2" to false, "3" to "chips", "5" to "standby", "6" to 180, "9" to 15)
    val fryer = Device(id = "F", name = "Fritéza", ip = "3", devId = "df", localKey = "k")
    val heater = Device(id = "H", name = "Ohrievač", ip = "4", devId = "dh", localKey = "k", category = "qn",
        schema = mapOf("1" to DpSchema("switch", DpType.BOOLEAN), "2" to DpSchema("temp_set", DpType.INTEGER, min = 5, max = 35)))
    val heaterV = mapOf<String, Any>("1" to false, "2" to 20)
    val strip = Device(id = "P", name = "Predlžovačka", ip = "5", devId = "dp", localKey = "k", category = "pc",
        schema = mapOf("1" to DpSchema("switch_1", DpType.BOOLEAN), "2" to DpSchema("switch_2", DpType.BOOLEAN), "3" to DpSchema("switch_usb1", DpType.BOOLEAN)))
    val stripV = mapOf<String, Any>("1" to true, "2" to true, "3" to false)
    val vals = mapOf("L" to lightV, "S" to sockV, "F" to fryerV, "H" to heaterV, "P" to stripV)
    val all = listOf(light, sock, fryer, heater, strip)
    val v: (Device) -> Map<String, Any> = { vals[it.id] ?: emptyMap() }

    check("druhy zariadení", AutoDevices.kind(light, lightV) == AutoKind.LIGHT && AutoDevices.kind(sock, sockV) == AutoKind.SOCKET &&
        AutoDevices.kind(fryer, fryerV) == AutoKind.FRYER && AutoDevices.kind(heater, heaterV) == AutoKind.HEATER &&
        AutoDevices.kind(strip, stripV) == AutoKind.SOCKET)
    check("príchod: fritéza ani ohrievač sa neponúkajú", !AutoDevices.canArrive(AutoKind.FRYER) && !AutoDevices.canArrive(AutoKind.HEATER) &&
        AutoDevices.canArrive(AutoKind.LIGHT) && AutoDevices.canArrive(AutoKind.SOCKET))
    check("príchod: uložená fritéza/ohrievač sa aj tak nezapne",
        AutoDevices.arriveIds(PresenceConfig(arriveIds = setOf("L", "F", "H")), all, v) == setOf("L") &&
            AutoDevices.arriveCommand(fryer, fryerV) == null && AutoDevices.arriveCommand(heater, heaterV) == null)
    check("príchod: svetlo sa zapne hlavným vypínačom", AutoDevices.arriveCommand(light, lightV) == mapOf("20" to true))
    check("odchod predvolene: svetlá a zásuvky", AutoDevices.departIds(PresenceConfig(), all, v) == setOf("L", "S", "P"))
    check("odchod: vlastný výber", AutoDevices.departIds(PresenceConfig(departIds = setOf("H")), all, v) == setOf("H"))
    check("vypnutie zásuvky: kód zo schémy", AutoDevices.offCommands(sock, sockV).first() == listOf("switch_1" to false))
    check("vypnutie predlžovačky: všetky vypínače",
        AutoDevices.offCommands(strip, stripV).first() == listOf("switch_1" to false, "switch_2" to false, "switch_usb1" to false))
    check("vypnutie ohrievača: switch", AutoDevices.offCommands(heater, heaterV).first() == listOf("switch" to false))
    check("vypnutie svetla bez schémy: odhad switch_led", AutoDevices.offCommands(light, lightV).first() == listOf("switch_led" to false))
    check("countdown: zásuvka zo schémy DP 9, svetlo DP 26, fritéza nie",
        AutoDevices.countdownDp(sock, sockV) == ("9" to false) && AutoDevices.countdownDp(light, lightV) == ("26" to false) &&
            AutoDevices.countdownDp(fryer, fryerV) == null)
    check("countdown: zásuvka bez schémy DP 9",
        AutoDevices.countdownDp(Device(name = "z", ip = "", devId = "x", localKey = ""), mapOf("1" to true, "9" to 0)) == ("9" to false))
    check("countdown v minútach", AutoDevices.countdownDp(sock.copy(schema = sock.schema + ("9" to DpSchema("countdown_1", DpType.INTEGER, unit = "min"))), sockV) == ("9" to true))
    val unknownDev = Device(id = "U", name = "Neznáme", ip = "6", devId = "du", localKey = "k")
    check("neznáme zariadenie sa pri príchode neponúka, pri odchode áno",
        AutoDevices.kind(unknownDev, emptyMap()) == AutoKind.UNKNOWN && !AutoDevices.canArrive(AutoKind.UNKNOWN) && AutoDevices.canDepart(AutoKind.UNKNOWN) &&
            AutoDevices.arriveIds(PresenceConfig(arriveIds = setOf("U")), listOf(unknownDev)) { emptyMap() }.isEmpty())
    check("text počtu", countText(1, true) == "1 svetlo" && countText(4, true) == "4 svetlá" && countText(5, true) == "5 svetiel" && countText(3, false) == "3 zariadenia")

    // ---------------------------------------------------------------- poistka
    val m5 = 5 * 60_000L
    check("poistka: zapnuté a nenastavené -> nastaviť", Guard.decide(true, false, 0, 1000, m5) == GuardStep.SET)
    check("poistka: zapnuté, nedávno nastavené -> nič", Guard.decide(true, true, 1000, 1000 + m5 - 1, m5) == GuardStep.NONE)
    check("poistka: zapnuté, po intervale -> obnoviť", Guard.decide(true, true, 1000, 1000 + m5, m5) == GuardStep.SET)
    check("poistka: vypnuté s odpočtom -> zrušiť", Guard.decide(false, true, 1000, 2000, m5) == GuardStep.CLEAR)
    check("poistka: vypnuté bez odpočtu -> nič", Guard.decide(false, false, 0, 2000, m5) == GuardStep.NONE)
    check("poistka: neznámy stav -> nič", Guard.decide(null, true, 0, 2000, m5) == GuardStep.NONE)
    check("poistka: príkaz v sekundách / minútach", Guard.command("26", false, 900) == mapOf("26" to 900) && Guard.command("9", true, 900) == mapOf("9" to 15))

    // ---------------------------------------------------------------- cloud príkaz (tinytuya Cloud.sendcommand)
    check("telo príkazu = json.dumps", AutoDevices.commandBody(listOf("switch_led" to false)) == """{"commands": [{"code": "switch_led", "value": false}]}""")
    data class Sent(val url: String, val headers: Map<String, String>, val body: String?)
    val sent = mutableListOf<Sent>()
    val client = TuyaCloudClient(CloudConfig("abcd1234efgh5678ijkl", "0123456789abcdef0123456789abcdef"), { _, url, h, body ->
        sent += Sent(url, h, body)
        HttpResult(200, if (url.contains("/token")) """{"success":true,"result":{"access_token":"tok_ABC123","expire_time":7200}}""" else """{"success":true,"result":true}""")
    }, clock = { 1700000000123L })
    client.sendCommands("bf123", listOf("switch_led" to false))
    client.sendCommands("bf123", listOf("switch_1" to false, "switch_2" to false))
    client.sendCommands("bf123", listOf("countdown_1" to 900))
    check("príkaz: URL", sent[1].url == "https://openapi.tuyaeu.com/v1.0/iot-03/devices/bf123/commands")
    check("príkaz switch_led: telo a podpis = tinytuya", sent[1].body == """{"commands": [{"code": "switch_led", "value": false}]}""" &&
        sent[1].headers["sign"] == "588DB915897969BA1038F8A430EE1AD2C0790E5A61BB3650A24788CD819FBD1C")
    check("príkaz 2 vypínače: podpis = tinytuya", sent[2].body == """{"commands": [{"code": "switch_1", "value": false}, {"code": "switch_2", "value": false}]}""" &&
        sent[2].headers["sign"] == "10BFC51AD652B1F013F1D6D02AB9E47F001C3697FCF2D8B54485DEE81A04FEA2")
    check("príkaz odpočet: podpis = tinytuya", sent[3].headers["sign"] == "46CBF2BA77BC9D0F9673D830F2148518D96DD12889D3CE5F044BBD43E5847147")
    val failing = TuyaCloudClient(CloudConfig("a", "b"), { _, url, _, _ ->
        HttpResult(200, if (url.contains("/token")) """{"success":true,"result":{"access_token":"t"}}""" else """{"success":false,"code":2008,"msg":"command or value not support"}""")
    })
    val err = runCatching { failing.sendCommands("x", listOf("switch" to false)) }.exceptionOrNull()
    check("zlý kód príkazu -> CloudException", err is CloudException && err.code == 2008)

    // ---------------------------------------------------------------- tichá notifikácia služby (1.27)
    check("text služby: doma s poistkou", presenceServiceText(PresenceState.HOME, true) == "Si doma · poistka zapnutá")
    check("text služby: doma bez poistky", presenceServiceText(PresenceState.HOME, false) == "Si doma")
    check("text služby: odchod", presenceServiceText(PresenceState.LEAVING, true) == "Odpojený od domácej WiFi – čakám, či sa vrátiš")
    check("text služby: mimo domu", presenceServiceText(PresenceState.AWAY, false) == "Mimo domu – pri návrate zapnem vybrané")
    check("text služby: neznáme", presenceServiceText(PresenceState.UNKNOWN, false) == "Zisťujem, či si doma…")
    check("notifikáciu služby neprekresliť pri rovnakom texte", !shouldPostServiceNotification("Si doma · poistka zapnutá", "Si doma · poistka zapnutá"))
    check("notifikáciu služby prekresliť pri zmene textu", shouldPostServiceNotification("Si doma", "Si doma · poistka zapnutá"))
    check("notifikáciu služby zobraziť prvýkrát", shouldPostServiceNotification(null, "Si doma"))

    println("PresenceTest: všetko OK")
}
