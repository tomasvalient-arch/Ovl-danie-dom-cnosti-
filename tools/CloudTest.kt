import org.json.JSONObject
import sk.domov.ovladanie.*
import java.io.File

// Test Tuya cloud klienta bez siete a bez Androidu.
// Očakávané podpisy vypočítala knižnica tinytuya 1.21 (Cloud._tuyaplatform) pre rovnaké vstupy a rovnaký čas.
// Očakávaný popis hodnôt vytvorila funkcia Cloud._build_mapping z tinytuya 1.21 (tools/testdata/cloud-*.json).

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

data class Sent(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

class FakeTransport(private val reply: (Sent) -> String) : HttpTransport {
    val sent = mutableListOf<Sent>()
    override fun execute(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult {
        val s = Sent(method, url, headers, body)
        sent += s
        return HttpResult(200, reply(s))
    }
}

const val ID = "abcd1234efgh5678ijkl"
const val SECRET = "0123456789abcdef0123456789abcdef"
const val T = 1700000000123L
val TOKEN_OK = """{"success":true,"t":1700000000200,"result":{"access_token":"tok_ABC123","expire_time":7200,"refresh_token":"r","uid":"bay123"}}"""

fun main() {
    val cfg = CloudConfig(ID, SECRET, DataCenter.EU)

    // ---------------------------------------------------------------- podpis (porovnanie s tinytuya)
    val fake = FakeTransport { s -> if (s.url.contains("/token")) TOKEN_OK else """{"success":true,"result":{}}""" }
    val client = TuyaCloudClient(cfg, fake, clock = { T })
    client.connect()
    val tok = fake.sent[0]
    check("token: URL", tok.url == "https://openapi.tuyaeu.com/v1.0/token?grant_type=1")
    check("token: podpis = tinytuya", tok.headers["sign"] == "0C500ABDE054BDA5D3F451E10BE7C3F057FA252D21D53A7FC794F044CC3564E5")
    check("token: hlavičky t, client_id, secret, bez access_token",
        tok.headers["t"] == "1700000000123" && tok.headers["client_id"] == ID && tok.headers["secret"] == SECRET &&
            tok.headers["sign_method"] == "HMAC-SHA256" && !tok.headers.containsKey("access_token"))
    check("token uložený", client.token == "tok_ABC123")

    client.request("GET", "/v1.0/iot-01/associated-users/devices", mapOf("size" to "50", "last_row_key" to "xyz"))
    val a = fake.sent[1]
    check("zoznam: URL so zoradenými parametrami", a.url == "https://openapi.tuyaeu.com/v1.0/iot-01/associated-users/devices?last_row_key=xyz&size=50")
    check("zoznam: podpis = tinytuya", a.headers["sign"] == "DC14218CDB8D935CE74BB9075C27F1CF16AADC7489038DD5CA1668B40405C4D8")
    check("zoznam: access_token, bez secret", a.headers["access_token"] == "tok_ABC123" && !a.headers.containsKey("secret"))

    client.request("GET", "/v1.3/iot-03/devices", mapOf("page_size" to "75", "source_type" to "tuyaUser", "source_id" to "eu1234"))
    check("v1.3 zoznam: podpis = tinytuya", fake.sent[2].headers["sign"] == "6E709114B4F0BD6F108C82C9EF1B470CCCE0E45937910F106E225D85E457470B")
    check("v1.3 zoznam: URL", fake.sent[2].url == "https://openapi.tuyaeu.com/v1.3/iot-03/devices?page_size=75&source_id=eu1234&source_type=tuyaUser")

    client.request("GET", "/v1.1/devices/bf123/specifications")
    check("specifications: podpis = tinytuya", fake.sent[3].headers["sign"] == "D6EE8AAD454CBDAE664467DFDEE1F2C1EBD8FF8AADE07ACC7F55D56CAA5E53FD")

    client.request("POST", "/v1.0/iot-03/devices/bf123/commands", body = """{"commands": [{"code": "switch_1", "value": true}]}""")
    val p = fake.sent[4]
    check("POST: podpis s Content-type = tinytuya", p.headers["sign"] == "6808ED3D94F8CC1597D9D12B61057B1CE21E13E41AF32DABDD97D3A32A0337A3")
    check("POST: Signature-Headers", p.headers["Signature-Headers"] == "Content-type" && p.headers["Content-type"] == "application/json")

    // priame funkcie podpisu
    check("sha256 prázdneho tela", TuyaSign.sha256Hex("") == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    check("signPath zoradí a nekóduje", TuyaSign.signPath("/x", mapOf("b" to "2 3", "a" to "1")) == "/x?a=1&b=2 3")
    val withNonce = TuyaSign.sign(ID, SECRET, null, T, "n1", TuyaSign.stringToSign("GET", "", emptyList(), "/v1.0/token?grant_type=1"))
    check("nonce mení podpis", withNonce != "0C500ABDE054BDA5D3F451E10BE7C3F057FA252D21D53A7FC794F044CC3564E5" && withNonce.length == 64)
    check("dátové centrá", DataCenter.of("weu").host == "openapi-weaz.tuyaeu.com" && DataCenter.of("us").host == "openapi.tuyaus.com" &&
        DataCenter.of("cn").host == "openapi.tuyacn.com" && DataCenter.of("in").host == "openapi.tuyain.com" && DataCenter.of(null) == DataCenter.EU)

    // ---------------------------------------------------------------- popis hodnôt (porovnanie s tinytuya _build_mapping)
    val spec = JSONObject(File("tools/testdata/cloud-specifications.json").readText())
    val expected = JSONObject(File("tools/testdata/cloud-mapping-tinytuya.json").readText())
    val mapping = CloudParse.mapping(spec.getJSONObject("result"))
    check("mapping: rovnaké DP ako tinytuya", mapping.keySet() == expected.keySet())
    check("mapping: obsah zhodný s tinytuya", mapping.similar(expected))
    val schema = Schema.parseMapping(mapping)
    check("mapping -> schéma: cur_power W/10", schema.getValue("19").let { it.code == "cur_power" && it.scale == 1 && it.unit == "W" })
    check("mapping -> schéma: ℃ -> °C", schema.getValue("24").unit == "°C")
    check("mapping: Json má raw_values", mapping.getJSONObject("30").getString("raw_values") == """{"a":1}""")
    check("mapping: jednotka cm ostane cm", CloudParse.mapping(JSONObject("""{"status":[{"code":"x","dp_id":5,"type":"Integer","values":"{\"unit\":\"cm\"}"}]}"""))
        .getJSONObject("5").getJSONObject("values").getString("unit") == "cm")

    // ---------------------------------------------------------------- zoznam zariadení
    check("parse: associated-users (result.devices)", CloudParse.devices(JSONObject(
        """{"success":true,"result":{"devices":[{"id":"bf1","name":"Zásuvka ","local_key":"0123456789abcdef","category":"cz","product_id":"p1","product_name":"Smart plug","uid":"eu1","online":true}],"has_more":false}}"""
    )).single().let { it.id == "bf1" && it.name == "Zásuvka" && it.localKey == "0123456789abcdef" && it.category == "cz" && it.productId == "p1" && it.online })
    check("parse: v1.3 (result.list)", CloudParse.devices(JSONObject("""{"list":[{"id":"bf2","name":"","is_online":true}]}""")).single().let { it.name == "bf2" && it.online })
    check("parse: v1.0 users (pole result)", CloudParse.devices(JSONObject("""{"success":true,"result":[{"id":"bf3","local_key":"k","sub":true},{"name":"bez id"}]}""")).single().sub)

    // celý postup: stránkovanie, doplnenie chýbajúceho local key cez v1.3, popis hodnôt s cache podľa produktu
    var specCalls = 0
    val flow = FakeTransport { s ->
        when {
            s.url.contains("/v1.0/token") -> TOKEN_OK
            s.url.contains("associated-users") && !s.url.contains("last_row_key") ->
                """{"success":true,"result":{"devices":[{"id":"bf1","name":"Zásuvka","local_key":"0123456789abcdef","product_id":"p1","uid":"eu1"}],"has_more":true,"last_row_key":"R2"}}"""
            s.url.contains("associated-users") ->
                """{"success":true,"result":{"devices":[{"id":"bf2","name":"Lampa","local_key":"","product_id":"p1","uid":"eu1","category":"dj"}],"has_more":false,"last_row_key":""}}"""
            s.url.contains("/v1.3/iot-03/devices") ->
                """{"success":true,"result":{"list":[{"id":"bf2","local_key":"fedcba9876543210","product_name":"Lamp"}],"has_more":false}}"""
            s.url.contains("/specifications") -> { specCalls++; spec.toString() }
            else -> """{"success":false,"code":1100,"msg":"param is illegal"}"""
        }
    }
    val c2 = TuyaCloudClient(cfg, flow, clock = { T })
    val list = c2.devices()
    check("stránkovanie: 2 zariadenia", list.map { it.id } == listOf("bf1", "bf2"))
    check("doplnený local key z v1.3", list[1].localKey == "fedcba9876543210" && list[1].productName == "Lamp" && list[1].category == "dj")
    check("mapping pre zariadenie", c2.mapping(list[0])?.has("19") == true)
    c2.mapping(list[1])
    check("mapping cache podľa product_id", specCalls == 1)

    // starý spôsob cez Device ID, keď associated-users nevráti nič
    val old = FakeTransport { s ->
        when {
            s.url.contains("/v1.0/token") -> TOKEN_OK
            s.url.contains("associated-users") -> """{"success":true,"result":{"devices":[],"has_more":false}}"""
            s.url.endsWith("/v1.0/devices/bfKNOWN") -> """{"success":true,"result":{"id":"bfKNOWN","uid":"eu9"}}"""
            s.url.endsWith("/v1.0/users/eu9/devices") -> """{"success":true,"result":[{"id":"bfKNOWN","name":"Termostat","local_key":"0123456789abcdef"}]}"""
            else -> """{"success":false,"code":1100,"msg":"x"}"""
        }
    }
    check("náhradný spôsob cez známe Device ID", TuyaCloudClient(cfg, old, clock = { T }).devices(listOf("bfKNOWN")).single().name == "Termostat")

    // vypršaný token: raz sa znova prihlási
    var tokens = 0
    var first = true
    val expiring = FakeTransport { s ->
        when {
            s.url.contains("/v1.0/token") -> { tokens++; TOKEN_OK }
            first -> { first = false; """{"success":false,"code":1010,"msg":"token invalid"}""" }
            else -> """{"success":true,"result":{"devices":[],"has_more":false}}"""
        }
    }
    TuyaCloudClient(cfg, expiring, clock = { T }).request("GET", "/v1.0/iot-01/associated-users/devices")
    check("vypršaný token sa obnoví", tokens == 2)

    // ---------------------------------------------------------------- chyby
    fun errorOf(body: String): CloudException? = try {
        TuyaCloudClient(cfg, FakeTransport { body }, clock = { T }).connect(); null
    } catch (e: CloudException) { e }
    val e1 = errorOf("""{"success":false,"code":1004,"msg":"sign invalid"}""")
    check("1004 -> zlý Access Secret", e1?.code == 1004 && CloudParse.describe(e1).startsWith("Zlý Access Secret"))
    check("1106 -> neprepojený účet / dátové centrum", CloudParse.describe(1106, "permission deny").contains("Link App"))
    check("28841101 -> chýba služba", CloudParse.describe(28841101, "No permissions. This API is not subscribed.").contains("IoT Core"))
    check("28841105 -> vypršala skúšobná služba", CloudParse.describe(28841105, "No permissions. Your subscription to cloud development plan has expired.").contains("Extend Trial"))
    check("1013 -> čas v telefóne", CloudParse.describe(1013, "request time is invalid").contains("čas"))
    check("neplatná odpoveď servera", errorOf("<html>502</html>")?.code == -200)
    check("bez internetu", CloudParse.describe(java.net.UnknownHostException("x")).contains("internet"))
    // zlé údaje sa neskúšajú obchádzať cez Device ID
    val bad = FakeTransport { s -> if (s.url.contains("/token")) TOKEN_OK else """{"success":false,"code":28841101,"msg":"No permissions. This API is not subscribed."}""" }
    val badErr = try { TuyaCloudClient(cfg, bad, clock = { T }).devices(listOf("bfX")); null } catch (e: CloudException) { e }
    check("chýbajúca služba sa ohlási hneď", badErr?.code == 28841101 && bad.sent.size == 2)

    // ---------------------------------------------------------------- synchronizácia (bez straty nastavení)
    val inApp = Device(
        id = "x1", name = "Moja zásuvka", ip = "192.168.1.20", devId = "bf1", localKey = "staryklucstaryk1", version = "3.4",
        labels = mapOf("19" to "Watty"), hidden = setOf("41"), powerDp = "1",
    )
    val other = Device(name = "Ručné", ip = "192.168.1.50", devId = "bfManual", localKey = "0123456789abcdef")
    val cloudList = listOf(
        CloudDevice("bf1", "Zásuvka (cloud)", "novykluc12345678", category = "cz", productId = "p1"),
        CloudDevice("bf2", "Lampa", "fedcba9876543210", category = "dj"),
    )
    val (merged, changed) = mergeCloud(listOf(inApp, other), cloudList, mapOf("bf1" to schema))
    val m1 = merged[0]
    check("sync: nový local key a popis hodnôt", m1.localKey == "novykluc12345678" && m1.schema == schema && m1.category == "cz")
    check("sync: názov, IP, verzia a vlastné nastavenia zostali",
        m1.id == "x1" && m1.name == "Moja zásuvka" && m1.ip == "192.168.1.20" && m1.version == "3.4" &&
            m1.labels == mapOf("19" to "Watty") && m1.hidden == setOf("41") && m1.powerDp == "1")
    check("sync: ručne pridané zariadenie mimo cloudu sa nezmení", merged[1] == other)
    check("sync: hlásenie zmien", changed == listOf("Moja zásuvka"))
    check("sync: chýbajúce zariadenia sa samé nepridajú", merged.size == 2)
    val (again2, changed2) = mergeCloud(merged, cloudList, mapOf("bf1" to schema))
    check("sync druhýkrát: bez zmien", changed2.isEmpty() && again2 == merged)
    val (keep, _) = mergeCloud(listOf(m1), listOf(CloudDevice("bf1", "x", "")), emptyMap())
    check("sync: bez local key a popisu z cloudu ponechá pôvodné", keep[0].localKey == "novykluc12345678" && keep[0].schema == schema)
    val nd = deviceFromCloud(cloudList[1], emptyMap(), FoundDevice("bf2", "192.168.1.77", "3.5", "pk"))
    check("nové zariadenie z cloudu s IP a verziou z hľadania", nd.name == "Lampa" && nd.ip == "192.168.1.77" && nd.version == "3.5" && nd.localKey == "fedcba9876543210")
    check("nové zariadenie z cloudu bez hľadania", deviceFromCloud(cloudList[1], emptyMap(), null).let { it.ip == "" && it.version == "3.3" })
    // export nikdy neobsahuje údaje cloudu (export pozná len zariadenia)
    check("export bez údajov cloudu", !exportDevicesJson(merged).contains(SECRET) && !exportDevicesJson(merged).contains(ID))

    println("CLOUD PASSED")
}
