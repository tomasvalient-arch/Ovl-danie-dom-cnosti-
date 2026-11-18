// Test evohome klienta proti lokálnemu HTTP serveru, ktorý prehráva skutočné (anonymizované) odpovede
// Honeywell TCC v2 API z knižnice evohome-async (tools/testdata/evohome).
import com.sun.net.httpserver.HttpServer
import sk.domov.ovladanie.*
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.LocalDateTime

private var failures = 0
private fun check(name: String, ok: Boolean) {
    println((if (ok) "OK   " else "CHYBA ") + name)
    if (!ok) failures++
}

private val DATA = File("tools/testdata/evohome")
private fun fixture(name: String) = File(DATA, name).readText()

data class Req(val method: String, val path: String, val headers: Map<String, String>, val body: String)

/** Falošný Honeywell server: tokeny, userAccount, installationInfo, status, schedule a príkazy (PUT). */
class MockTcc {
    val requests = mutableListOf<Req>()
    var password = "spravne-heslo"
    var tokenCounter = 0
    var expiresIn = 1800
    var validAccess = mutableSetOf<String>()
    var validRefresh = mutableSetOf<String>()
    /** Nasledujúce odpovede na API (napr. 429, 500) – vrátia sa namiesto bežnej odpovede. */
    val forced = ArrayDeque<Pair<Int, String>>()
    var forcedRetryAfter: String? = null
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { ex ->
            val body = ex.requestBody.bufferedReader().readText()
            val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString() }
            val path = ex.requestURI.toString()
            requests += Req(ex.requestMethod, path, headers, body)
            var code = 200
            var out: String
            val forcedNext = forced.removeFirstOrNull()
            if (forcedNext != null) {
                code = forcedNext.first; out = forcedNext.second
                forcedRetryAfter?.let { ex.responseHeaders.add("Retry-After", it) }
            } else if (path == "/Auth/OAuth/Token") {
                val form = body.split("&").associate { p -> p.substringBefore("=") to URLDecoder.decode(p.substringAfter("="), "UTF-8") }
                val basicOk = headers["authorization"] == "Basic " + EvohomeClient.APPLICATION_ID
                val grantOk = when (form["grant_type"]) {
                    "password" -> form["Username"] == "tomas@example.com" && form["Password"] == password
                    "refresh_token" -> form["refresh_token"] in validRefresh
                    else -> false
                }
                if (basicOk && grantOk && form["scope"] == EvohomeClient.SCOPE) {
                    tokenCounter++
                    val access = "access-$tokenCounter"; val refresh = "refresh-$tokenCounter"
                    validAccess += access; validRefresh.clear(); validRefresh += refresh
                    out = """{"access_token":"$access","token_type":"bearer","expires_in":$expiresIn,"refresh_token":"$refresh","scope":"EMEA-V1-Basic EMEA-V1-Anonymous"}"""
                } else {
                    code = 400; out = """{"error":"invalid_grant"}"""
                }
            } else {
                val token = headers["authorization"]?.removePrefix("bearer ")
                if (token !in validAccess) {
                    code = 401; out = """[{"code":"Unauthorized","message":"Unauthorized"}]"""
                } else {
                    val p = path.removePrefix("/WebAPI/emea/api/v1/")
                    out = when {
                        ex.requestMethod == "GET" && p == "userAccount" -> fixture("user_account.json")
                        ex.requestMethod == "GET" && p == "location/installationInfo?userId=2512649&includeTemperatureControlSystems=True" -> fixture("user_locations.json")
                        ex.requestMethod == "GET" && p == "location/2738909/status?includeTemperatureControlSystems=True" -> fixture("status_2738909.json")
                        ex.requestMethod == "GET" && p.matches(Regex("temperatureZone/\\d+/schedule")) -> fixture("schedule_zone.json")
                        ex.requestMethod == "PUT" -> """{"id":"840367013"}"""
                        else -> { code = 404; """[{"code":"NotFound","message":"$p"}]""" }
                    }
                }
            }
            val bytes = out.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    fun stop() = server.stop(0)
    fun last() = requests.last()
}

fun main() {
    val mock = MockTcc()
    var now = 1_700_000_000_000L
    var savedTokens: EvoTokens? = null
    fun client(password: String = "spravne-heslo", tokens: EvoTokens? = null) =
        EvohomeClient("tomas@example.com", password, tokens = tokens, host = mock.url, clock = { now }, onTokens = { savedTokens = it })

    // --- prihlásenie
    run {
        val bad = client(password = "zle")
        val e = runCatching { bad.login() }.exceptionOrNull() as? EvoException
        check("zlé heslo → WRONG_PASSWORD", e?.kind == EvoErrorKind.WRONG_PASSWORD)
        check("zlé heslo → slovenská správa", evoErrorText(e!!).contains("Nesprávny e-mail alebo heslo"))
    }
    val c = client()
    val t = c.login()
    val tokenReq = mock.last()
    check("token POST na /Auth/OAuth/Token s Basic ID aplikácie", tokenReq.method == "POST" && tokenReq.headers["authorization"] == "Basic NGEyMzEwODktZDJiNi00MWJkLWE1ZWItMTZhMGE0MjJiOTk5OjFhMTVjZGI4LTQyZGUtNDA3Yi1hZGQwLTA1OWY5MmM1MzBjYg==")
    check("token POST je form-urlencoded s grant_type=password", tokenReq.headers["content-type"]?.startsWith("application/x-www-form-urlencoded") == true && tokenReq.body.contains("grant_type=password") && tokenReq.body.contains("Username=tomas%40example.com"))
    check("token uložený (access, refresh, expirácia)", t.access == "access-1" && t.refresh == "refresh-1" && t.expiresAt == now + 1800_000 && savedTokens == t)

    // --- inštalácia a stav
    val install = c.installation()
    check("API volania s hlavičkou bearer", mock.last().headers["authorization"] == "bearer access-1")
    check("inštalácia: lokalita, systém, 9 zón, teplá voda", install.locationId == "2738909" && install.systemId == "3432522" && install.zones.size == 9 && install.dhwId == "3933910")
    check("inštalácia: povolené režimy obsahujú Auto, Eco, Preč, Vypnuté", EvoMode.offered.all { it in install.allowedModes })
    check("inštalácia: rozsah a krok zóny", install.zones[0].min == 5.0 && install.zones[0].max == 35.0 && install.zones[0].step == 0.5)
    check("inštalácia sa dá uložiť a načítať (bez adresy a e-mailu)", EvoInstall.fromJson(install.toJson()) == install && !install.toJson().contains("@") && !install.toJson().contains("Crescent"))
    val requestsBefore = mock.requests.size
    val st = c.status(install)
    check("stav: jedna požiadavka", mock.requests.size == requestsBefore + 1)
    check("stav: režim systému AutoWithEco natrvalo", st.mode == EvoMode.ECO && st.permanent)
    check("stav: 9 zón v poradí inštalácie", st.zones.size == 9 && st.zones.map { it.id } == install.zones.map { it.id })
    val dead = st.zones.first { it.name == "Dead Zone" }
    val main = st.zones.first { it.name == "Main Room" }
    check("stav: zóna bez čidla má teplotu null, porucha sa načíta", dead.temperature == null && dead.setpoint == 5.0 && dead.faults.isNotEmpty())
    check("stav: Main Room 19,0 °C, cieľ 17 °C podľa programu", main.temperature == 19.0 && main.setpoint == 17.0 && main.mode == EvoZoneMode.FOLLOW)
    check("stav: teplá voda 23 °C, vypnutá", st.dhw?.temperature == 23.0 && st.dhw?.state == "Off")
    check("text teploty", evoTemp(19.0) == "19,0 °C" && evoTemp(null) == "—")

    // --- príkazy
    c.setZone("3432576", 21.5, null)
    var r = mock.last()
    check("natrvalo: PUT temperatureZone/{id}/heatSetpoint", r.method == "PUT" && r.path == "/WebAPI/emea/api/v1/temperatureZone/3432576/heatSetpoint")
    check("natrvalo: telo PermanentOverride 21.5", JSONObject(r.body).let { it.getString("setpointMode") == "PermanentOverride" && it.getDouble("heatSetpointValue") == 21.5 && !it.has("timeUntil") } && r.headers["content-type"]?.startsWith("application/json") == true)
    c.setZone("3432576", 22.0, 1_700_003_600_500L)
    r = mock.last()
    check("na 1 h: TemporaryOverride s timeUntil v UTC", JSONObject(r.body).let { it.getString("setpointMode") == "TemporaryOverride" && it.getString("timeUntil") == "2023-11-14T23:13:20Z" })
    c.followSchedule("3432576")
    check("podľa programu: FollowSchedule bez teploty", JSONObject(mock.last().body).let { it.getString("setpointMode") == "FollowSchedule" && it.length() == 1 })
    c.setMode("3432522", EvoMode.AWAY)
    r = mock.last()
    check("režim: PUT temperatureControlSystem/{id}/mode {systemMode, permanent}", r.path.endsWith("/temperatureControlSystem/3432522/mode") && JSONObject(r.body).let { it.getString("systemMode") == "Away" && it.getBoolean("permanent") })
    c.setDhw("3933910", true)
    r = mock.last()
    check("teplá voda: PUT domesticHotWater/{id}/state On natrvalo", r.path.endsWith("/domesticHotWater/3933910/state") && JSONObject(r.body).let { it.getString("mode") == "PermanentOverride" && it.getString("state") == "On" })
    c.setDhw("3933910", null)
    check("teplá voda: podľa programu", JSONObject(mock.last().body).getString("mode") == "FollowSchedule")

    // --- obnovenie tokenu
    now += 1800_000
    c.status(install)
    val refreshReq = mock.requests.last { it.path == "/Auth/OAuth/Token" }
    check("po expirácii sa token obnoví refresh tokenom (nie heslom)", refreshReq.body.contains("grant_type=refresh_token") && refreshReq.body.contains("refresh_token=refresh-1") && !refreshReq.body.contains("Password"))
    check("nový token uložený", savedTokens?.access == "access-2" && mock.last().headers["authorization"] == "bearer access-2")

    // server zneplatní access token pred expiráciou → 401 → obnoví a zopakuje
    mock.validAccess.clear()
    val before401 = mock.requests.size
    c.status(install)
    val seq = mock.requests.drop(before401).map { it.path.substringBefore("?") }
    check("401 → obnovenie tokenu a jeden opakovaný pokus", seq == listOf("/WebAPI/emea/api/v1/location/2738909/status", "/Auth/OAuth/Token", "/WebAPI/emea/api/v1/location/2738909/status"))

    // neplatný refresh token → prihlásenie heslom
    mock.validRefresh.clear(); mock.validAccess.clear()
    now += 1800_000
    c.status(install)
    val lastTwoTokens = mock.requests.filter { it.path == "/Auth/OAuth/Token" }.takeLast(2)
    check("neplatný refresh token → nové prihlásenie heslom", lastTwoTokens[0].body.contains("refresh_token") && lastTwoTokens[1].body.contains("grant_type=password"))

    // uložené tokeny po reštarte appky: žiadne prihlásenie
    val restored = client(tokens = savedTokens)
    val beforeRestore = mock.requests.size
    restored.status(install)
    check("uložený token sa použije po reštarte (bez prihlásenia)", mock.requests.drop(beforeRestore).none { it.path == "/Auth/OAuth/Token" })

    // --- chyby
    mock.forced += 429 to """{"message":"Too many requests"}"""
    var e = runCatching { c.status(install) }.exceptionOrNull() as? EvoException
    check("429 → RATE_LIMITED, čaká 15 min", e?.kind == EvoErrorKind.RATE_LIMITED && e.retryAfterSec == 900L)
    check("429 → správa po slovensky", evoErrorText(e!!).contains("dočasne obmedzil") && evoErrorText(e).contains("15 min"))
    val beforeBlocked = mock.requests.size
    e = runCatching { c.status(install) }.exceptionOrNull() as? EvoException
    check("počas obmedzenia sa na server vôbec nevolá", e?.kind == EvoErrorKind.RATE_LIMITED && mock.requests.size == beforeBlocked)
    now += 901_000
    mock.forcedRetryAfter = "120"
    mock.forced += 429 to "{}"
    e = runCatching { c.status(install) }.exceptionOrNull() as? EvoException
    check("Retry-After hlavička sa rešpektuje", e?.retryAfterSec == 120L)
    mock.forcedRetryAfter = null
    now += 121_000
    mock.forced += 500 to "oops"
    e = runCatching { c.status(install) }.exceptionOrNull() as? EvoException
    check("500 → SERVER", e?.kind == EvoErrorKind.SERVER)
    mock.forced += 400 to """[{"code":"InvalidInput","message":"bad","parameterName":"heatSetpointValue"}]"""
    e = runCatching { c.setZone("3432576", 99.0, null) }.exceptionOrNull() as? EvoException
    check("400 → REJECTED s kódom servera", e?.kind == EvoErrorKind.REJECTED && e.message == "InvalidInput")
    val offline = EvohomeClient("a@b.c", "x", host = "http://127.0.0.1:1", clock = { now })
    e = runCatching { offline.login() }.exceptionOrNull() as? EvoException
    check("bez spojenia → NO_INTERNET", e?.kind == EvoErrorKind.NO_INTERNET && evoErrorText(e!!).contains("internet"))

    // --- nečakané dáta nesmú spadnúť inak než EvoException
    val junk = listOf("", "null", "[]", "{}", """{"gateways":[{"temperatureControlSystems":[{"systemId":"1","zones":[null,{"zoneId":"5"},{"zoneId":"6","temperatureStatus":{"isAvailable":true,"temperature":"x"}}]}]}]}""")
    val results = junk.map { j -> runCatching { EvoParse.status(j, install) } }
    check("pokazený stav → len EvoException", results.all { it.isSuccess || it.exceptionOrNull() is EvoException })
    val partial = results.last().getOrNull()
    check("neúplná zóna sa načíta s predvolenými hodnotami", partial?.zones?.size == 2 && partial.zones.all { it.temperature == null && it.setpoint == null })
    check("pokazená inštalácia → EvoException", runCatching { EvoParse.installation("{}", "1") }.exceptionOrNull() is EvoException && runCatching { EvoParse.installation("[]", "1") }.exceptionOrNull() is EvoException)
    check("pokazená uložená inštalácia → null", EvoInstall.fromJson("{blbost") == null && EvoInstall.fromJson(null) == null)
    check("čas so zónou aj bez nej", EvoParse.parseTime("2025-02-16T10:30:00Z") == 1739701800000L && EvoParse.parseTime("2024-12-17T17:10:57") != null && EvoParse.parseTime("x") == null)

    // --- ďalšia zmena v programe
    val sched = c.schedule("3432576")
    check("program: 7 dní po 4 zmeny", sched.size == 28)
    check("pondelok 7:30 → pondelok 8:00", EvoParse.nextSwitch(sched, LocalDateTime.of(2026, 11, 16, 7, 30)) == LocalDateTime.of(2026, 11, 16, 8, 0))
    check("pondelok 23:30 → utorok 7:00", EvoParse.nextSwitch(sched, LocalDateTime.of(2026, 11, 16, 23, 30)) == LocalDateTime.of(2026, 11, 17, 7, 0))
    check("piatok 23:00 presne → sobota 7:00", EvoParse.nextSwitch(sched, LocalDateTime.of(2026, 11, 20, 23, 0)) == LocalDateTime.of(2026, 11, 21, 7, 0))
    check("prázdny program → null", EvoParse.nextSwitch(emptyList(), LocalDateTime.of(2026, 11, 16, 7, 30)) == null)
    val src = CloudEvoSource(c, install, clock = { now })
    val nextMs = src.nextSwitch("3432576", now)
    check("CloudEvoSource: ďalšia zmena je v budúcnosti a najviac o deň", nextMs != null && nextMs > now && nextMs - now <= 24 * 3600_000L)

    // --- krok a rozsah
    val z = main
    check("zaokrúhlenie na 0,5 a rozsah 5–35", evoClamp(21.26, z) == 21.5 && evoClamp(2.0, z) == 5.0 && evoClamp(40.0, z) == 35.0)

    // --- ukážkový zdroj
    val demo = DemoEvoSource { now }
    demo.setZone("z1", 22.5, null)
    demo.setMode(EvoMode.AWAY)
    demo.setDhw(false)
    val ds = demo.status()
    check("ukážka: príkazy sa prejavia v stave", ds.zones[0].setpoint == 22.5 && ds.zones[0].mode == EvoZoneMode.PERMANENT && ds.mode == EvoMode.AWAY && ds.dhw?.state == "Off")

    mock.stop()
    if (failures > 0) {
        println("EvohomeTest: $failures chýb"); kotlin.system.exitProcess(1)
    }
    println("EvohomeTest: všetko OK")
}
