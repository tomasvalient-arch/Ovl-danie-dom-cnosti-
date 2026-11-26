package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * Tuya cloud (OpenAPI) – na stiahnutie zoznamu zariadení, local keys a popisu hodnôt; jediný príkaz cez cloud je
 * vypnutie zariadení pri odchode z domu (automatika príchod/odchod). Bežné ovládanie ide ďalej len lokálne cez WiFi. Postup je rovnaký ako v tinytuya 1.21 (Cloud.py):
 * token cez /v1.0/token?grant_type=1, zariadenia cez /v1.0/iot-01/associated-users/devices
 * (+ /v1.3/iot-03/devices pre chýbajúce local keys), popis hodnôt cez /v1.1/devices/{id}/specifications.
 * Súbor nepoužíva Android, testuje sa v JVM (tools/CloudTest.kt).
 */

/** Dátové centrum Tuya cloudu. Musí byť to isté, v ktorom je Cloud projekt aj účet Tuya/Smart Life appky. */
enum class DataCenter(val code: String, val title: String, val host: String) {
    EU("eu", "Stredná Európa", "openapi.tuyaeu.com"),
    WEU("weu", "Západná Európa", "openapi-weaz.tuyaeu.com"),
    US("us", "Amerika (západ)", "openapi.tuyaus.com"),
    USE("ue", "Amerika (východ)", "openapi-ueaz.tuyaus.com"),
    CN("cn", "Čína", "openapi.tuyacn.com"),
    IN("in", "India", "openapi.tuyain.com"),
    ;

    companion object {
        fun of(code: String?): DataCenter = entries.firstOrNull { it.code == code?.trim()?.lowercase() } ?: EU
    }
}

/** Prístupové údaje k Cloud projektu na iot.tuya.com. */
data class CloudConfig(val accessId: String, val accessSecret: String, val dataCenter: DataCenter = DataCenter.EU) {
    override fun toString(): String = "CloudConfig(accessId=$accessId, accessSecret=${masked(accessSecret)}, dataCenter=$dataCenter)"


    val isComplete: Boolean get() = accessId.isNotBlank() && accessSecret.isNotBlank()
}

/** Zariadenie z Tuya účtu. */
data class CloudDevice(
    val id: String,
    val name: String,
    val localKey: String,
    val category: String = "",
    val productId: String = "",
    val productName: String = "",
    val uid: String = "",
    val online: Boolean = false,
    /** Zariadenie pripojené cez bránu (Zigbee/Bluetooth): ovláda sa cez bránu (cid = nodeId), nie priamo. */
    val sub: Boolean = false,
    /** ID podzariadenia za bránou (node_id z cloudu); prázdne, ak ho cloud neposlal. */
    val nodeId: String = "",
    /** Device ID brány, cez ktorú je podzariadenie pripojené; prázdne, ak sa nezistilo. */
    val parentId: String = "",
    /** Cloud ID (uuid) – záloha, ak node_id chýba. */
    val uuid: String = "",
) {
    /** ID, ktorým sa podzariadenie oslovuje cez bránu (node_id, inak uuid). */
    val cid: String get() = nodeId.ifBlank { uuid }

    override fun toString(): String =
        "CloudDevice(id=$id, name=$name, localKey=${masked(localKey)}, category=$category, online=$online, sub=$sub, cid=$cid, parentId=$parentId)"
}

/** Chyba z Tuya cloudu s kódom (napr. 1004 = zlý podpis). */
class CloudException(val code: Int, val msg: String) : IOException("Tuya cloud $code: $msg")

/** Podpis požiadaviek (HMAC-SHA256), rovnaký ako v tinytuya (new_sign_algorithm). */
object TuyaSign {
    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toHex()

    /**
     * Cesta s parametrami na podpis: parametre zoradené podľa abecedy a **nekódované** (tak to chce Tuya),
     * napr. /v1.0/iot-01/associated-users/devices?last_row_key=xyz&size=50
     */
    fun signPath(path: String, query: Map<String, String>): String =
        if (query.isEmpty()) path else path + "?" + query.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }

    /** stringToSign = metóda \n SHA256(telo) \n podpísané hlavičky \n cesta. */
    fun stringToSign(method: String, body: String, signedHeaders: List<Pair<String, String>>, signPath: String): String =
        method + "\n" + sha256Hex(body) + "\n" + signedHeaders.joinToString("") { "${it.first}:${it.second}\n" } + "\n" + signPath

    /** sign = HMAC-SHA256(secret, client_id + access_token + t + nonce + stringToSign), veľkými písmenami. */
    fun sign(clientId: String, secret: String, token: String?, t: Long, nonce: String, stringToSign: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val payload = clientId + (token ?: "") + t + nonce + stringToSign
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).toHex().uppercase()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** Odpoveď HTTP servera. */
data class HttpResult(val code: Int, val body: String)

/** Odoslanie HTTP požiadavky (v testoch sa nahradí). */
fun interface HttpTransport {
    fun execute(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult
}

/** Bežné HTTPS cez HttpURLConnection. */
object UrlConnectionTransport : HttpTransport {
    override fun execute(method: String, url: String, headers: Map<String, String>, body: String?): HttpResult =
        Http.send(method, url, headers, body).let { HttpResult(it.code, it.body) }
}

class TuyaCloudClient(
    private val config: CloudConfig,
    private val transport: HttpTransport = UrlConnectionTransport,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val nonce: () -> String = { "" },
) {
    var token: String? = null
        private set

    /** Popis hodnôt podľa product_id (rovnaké produkty majú rovnaký popis). */
    private val mappingCache = HashMap<String, JSONObject>()

    /** Získa prístupový token. Pri zlých údajoch vyhodí CloudException. */
    fun connect() {
        token = null
        val res = call("GET", "/v1.0/token", mapOf("grant_type" to "1"), null)
        token = res.optJSONObject("result")?.optString("access_token")?.takeIf { it.isNotBlank() }
            ?: throw CloudException(-1, "Odpoveď bez tokenu")
    }

    /** Požiadavka s tokenom; pri vypršanom tokene sa raz znova prihlási. Vráti celú odpoveď (success = true). */
    fun request(method: String, path: String, query: Map<String, String> = emptyMap(), body: String? = null): JSONObject {
        if (token == null) connect()
        return try {
            call(method, path, query, body)
        } catch (e: CloudException) {
            if (e.code != 1010 && e.code != 1011 && !e.msg.contains("token invalid", true)) throw e
            connect()
            call(method, path, query, body)
        }
    }

    private fun call(method: String, path: String, query: Map<String, String>, body: String?): JSONObject {
        val t = clock()
        val n = nonce()
        val signHeaders = mutableListOf<Pair<String, String>>()
        val headers = LinkedHashMap<String, String>()
        if (body != null) {
            headers["Content-type"] = "application/json"
            headers["Signature-Headers"] = "Content-type"
            signHeaders += "Content-type" to "application/json"
        }
        val sts = TuyaSign.stringToSign(method, body ?: "", signHeaders, TuyaSign.signPath(path, query))
        if (token == null) headers["secret"] = config.accessSecret
        headers["client_id"] = config.accessId
        headers["sign"] = TuyaSign.sign(config.accessId, config.accessSecret, token, t, n, sts)
        headers["t"] = t.toString()
        if (n.isNotEmpty()) headers["nonce"] = n
        headers["sign_method"] = "HMAC-SHA256"
        token?.let { headers["access_token"] = it }

        val url = "https://${config.dataCenter.host}$path" + if (query.isEmpty()) "" else
            "?" + query.toSortedMap().entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val res = transport.execute(method, url, headers, body)
        val json = try {
            JSONObject(res.body)
        } catch (e: Exception) {
            throw CloudException(-res.code, "Neočakávaná odpoveď servera (HTTP ${res.code})")
        }
        if (!json.optBoolean("success", false)) {
            throw CloudException(json.optInt("code", -1), json.optString("msg", "neznáma chyba"))
        }
        return json
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * Všetky zariadenia prepojených účtov appky (ako tinytuya bez apiDeviceID). Ak zoznam chýba alebo je prázdny
     * a poznáme Device ID niektorého zariadenia ([knownDevIds]), skúsi starší spôsob cez používateľa tohto zariadenia.
     */
    fun devices(knownDevIds: List<String> = emptyList()): List<CloudDevice> {
        val all = LinkedHashMap<String, CloudDevice>()
        var firstError: CloudException? = null
        try {
            val query = linkedMapOf("size" to "50")
            var guard = 0
            do {
                val res = request("GET", "/v1.0/iot-01/associated-users/devices", query).optJSONObject("result") ?: break
                CloudParse.devices(res).forEach { all[it.id] = it }
                val more = res.optBoolean("has_more", false)
                val last = res.optString("last_row_key", "")
                if (last.isNotBlank()) query["last_row_key"] = last
            } while (more && last.isNotBlank() && ++guard < 40)
        } catch (e: CloudException) {
            if (e.code in AUTH_ERRORS) throw e
            firstError = e
        }

        if (all.isEmpty()) {
            for (devId in knownDevIds.distinct().take(3)) {
                try {
                    val uid = request("GET", "/v1.0/devices/$devId").optJSONObject("result")?.optString("uid").orEmpty()
                    if (uid.isBlank()) continue
                    CloudParse.devices(request("GET", "/v1.0/users/$uid/devices")).forEach { all[it.id] = it }
                    if (all.isNotEmpty()) break
                } catch (e: CloudException) {
                    if (e.code in AUTH_ERRORS) throw e
                    if (firstError == null) firstError = e
                }
            }
        }
        if (all.isEmpty() && firstError != null) throw firstError

        // local key niekedy v prvom zozname chýba; doplní ho zoznam podľa používateľa (ako tinytuya)
        val missing = all.values.filter { it.localKey.isBlank() && it.uid.isNotBlank() }.map { it.uid }.distinct()
        for (uid in missing) {
            try {
                val query = linkedMapOf("page_size" to "75", "source_type" to "tuyaUser", "source_id" to uid)
                var guard = 0
                do {
                    val res = request("GET", "/v1.3/iot-03/devices", query).optJSONObject("result") ?: break
                    CloudParse.devices(res).forEach { d ->
                        val old = all[d.id]
                        all[d.id] = if (old == null) d else old.copy(
                            localKey = old.localKey.ifBlank { d.localKey },
                            category = old.category.ifBlank { d.category },
                            productId = old.productId.ifBlank { d.productId },
                            productName = old.productName.ifBlank { d.productName },
                            sub = old.sub || d.sub,
                            nodeId = old.nodeId.ifBlank { d.nodeId },
                            parentId = old.parentId.ifBlank { d.parentId },
                            uuid = old.uuid.ifBlank { d.uuid },
                        )
                    }
                    val more = res.optBoolean("has_more", false)
                    val last = res.optString("last_row_key", "")
                    if (last.isNotBlank()) query["last_row_key"] = last
                } while (more && last.isNotBlank() && ++guard < 40)
            } catch (e: CloudException) {
                if (e.code in AUTH_ERRORS) throw e
            }
        }
        runCatching { resolveSubDevices(all) }.onFailure { if (it is CloudException && it.code in AUTH_ERRORS) throw it }
        return all.values.toList()
    }

    /**
     * Zistí pri podzariadeniach (Zigbee) ich bránu a node_id. Najprv sa opýta brán na ich podzariadenia
     * (/v1.0/devices/{brána}/sub-devices). Čo ostane nezistené, doplní sa podľa jedinej brány v účte; node_id sa
     * ešte skúsi z detailu zariadenia. Chyby jednotlivých dotazov sa ignorujú (zariadenie ostane bez brány a appka
     * ho ponúkne až keď sa dá ovládať).
     */
    private fun resolveSubDevices(all: MutableMap<String, CloudDevice>) {
        val subs = all.values.filter { it.sub }
        if (subs.isEmpty()) return
        val byCategory = all.values.filter { !it.sub && it.category.trim().lowercase() in Gateways.CATEGORIES }.map { it.id }
        val referenced = subs.map { it.parentId }.filter { it.isNotBlank() }
        var gateways = (byCategory + referenced).distinct()
        if (gateways.isEmpty()) {
            // brána má v cloude neznámu kategóriu: skúsime pár online zariadení bez podzariadení
            gateways = all.values.filter { !it.sub && it.online }.map { it.id }.take(12)
        }
        for (gw in gateways) {
            if (all.values.none { it.sub && (it.parentId.isBlank() || it.nodeId.isBlank()) }) break
            for (path in listOf("/v1.0/devices/$gw/sub-devices", "/v1.3/iot-03/devices/$gw/sub-devices")) {
                val found = try {
                    CloudParse.subDevices(request("GET", path).opt("result"))
                } catch (e: CloudException) {
                    if (e.code in AUTH_ERRORS) throw e
                    emptyList<Pair<String, String>>()
                }
                if (found.isEmpty()) continue
                found.forEach { (id, cid) ->
                    val d = all[id] ?: return@forEach
                    all[id] = d.copy(nodeId = d.nodeId.ifBlank { cid }, parentId = gw, sub = true)
                }
                break
            }
        }
        // jediná brána v účte: podzariadenia bez zistenej brány patria jej
        val onlyGateway = byCategory.singleOrNull()
        if (onlyGateway != null) {
            all.values.filter { it.sub && it.parentId.isBlank() }.forEach { d -> all[d.id] = d.copy(parentId = onlyGateway) }
        }
        // node_id z detailu zariadenia
        all.values.filter { it.sub && it.nodeId.isBlank() }.take(20).forEach { d ->
            try {
                val r = request("GET", "/v1.0/devices/${d.id}").optJSONObject("result")
                val node = if (r == null) "" else CloudParse.firstText(r, "node_id", "nodeId")
                if (node.isNotBlank()) all[d.id] = d.copy(nodeId = node)
            } catch (e: CloudException) {
                if (e.code in AUTH_ERRORS) throw e
            }
        }
    }

    /** Popis hodnôt zariadenia vo formáte tinytuya „mapping“. Pri chybe vráti null (zariadenie sa pridá aj bez neho). */
    fun mapping(device: CloudDevice): JSONObject? {
        device.productId.takeIf { it.isNotBlank() }?.let { pid -> mappingCache[pid]?.let { return it } }
        val res = try {
            request("GET", "/v1.1/devices/${device.id}/specifications")
        } catch (e: CloudException) {
            if (e.code in AUTH_ERRORS) throw e
            return null
        }
        val mapping = CloudParse.mapping(res.optJSONObject("result") ?: return null)
        // /v1.1 vracia len štandardné funkcie kategórie; skryté DP (napr. 51 mix_rgbcw) doplní model vecí
        runCatching { thingModel(device.id) }.getOrNull()?.let { CloudParse.addThingProps(mapping, it) }
        if (device.productId.isNotBlank()) mappingCache[device.productId] = mapping
        return mapping
    }

    /** Úplný model vecí zariadenia (/v2.0/cloud/thing/{id}/model) – všetky DP vrátane neštandardných. */
    fun thingModel(devId: String): List<ThingProp> =
        CloudParse.thingProps(request("GET", "/v2.0/cloud/thing/$devId/model").optJSONObject("result")?.optString("model").orEmpty())

    /** Posledné hodnoty všetkých vlastností v cloude (/v2.0/cloud/thing/{id}/shadow/properties). */
    fun shadow(devId: String): List<ShadowProp> =
        CloudParse.shadow(request("GET", "/v2.0/cloud/thing/$devId/shadow/properties").optJSONObject("result"))

    /**
     * Pošle zariadeniu príkaz cez cloud (ako tinytuya Cloud.sendcommand): POST /v1.0/iot-03/devices/{id}/commands
     * s telom {"commands": [{"code": "switch_led", "value": false}]}. Pri chybe vyhodí CloudException.
     */
    fun sendCommands(devId: String, commands: List<Pair<String, Any>>) {
        CommandLog.add(devId, "→ " + commands.joinToString(", ") { "${it.first}=${it.second}" } + "  [cloud]")
        request("POST", "/v1.0/iot-03/devices/$devId/commands", body = AutoDevices.commandBody(commands))
    }

    companion object {
        /** Chyby, pri ktorých nemá zmysel skúšať iné spôsoby (zlé údaje, služby, čas). */
        val AUTH_ERRORS = setOf(1004, 1005, 1013, 28841101, 28841105, 28841002, 28841104)
    }
}

/** Spracovanie odpovedí Tuya cloudu (bez siete, testuje sa samostatne). */
object CloudParse {
    /**
     * Zariadenia z odpovede: result.devices (associated-users), result.list (v1.3 iot-03) alebo pole result (v1.0 users).
     * Akceptuje celú odpoveď aj len jej časť „result“.
     */
    fun devices(json: JSONObject): List<CloudDevice> {
        val arr: JSONArray = json.optJSONArray("devices")
            ?: json.optJSONArray("list")
            ?: json.optJSONArray("result")
            ?: json.optJSONObject("result")?.let { r -> r.optJSONArray("devices") ?: r.optJSONArray("list") }
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim()
            if (id.isEmpty()) return@mapNotNull null
            CloudDevice(
                id = id,
                name = o.optString("name").trim().ifBlank { id },
                localKey = o.optString("local_key").trim(),
                category = o.optString("category").trim(),
                productId = o.optString("product_id").trim(),
                productName = o.optString("product_name").trim(),
                uid = o.optString("uid").trim(),
                online = o.optBoolean("online", false) || o.optBoolean("is_online", false),
                sub = o.optBoolean("sub", false),
                nodeId = firstText(o, "node_id", "nodeId", "cid"),
                parentId = firstText(o, "parent_id", "parentId", "gateway_id", "gatewayId", "parent"),
                uuid = o.optString("uuid").trim(),
            )
        }
    }

    /** Prvý neprázdny textový údaj z viacerých možných názvov poľa (Tuya ich v rôznych odpovediach pomenúva rôzne). */
    internal fun firstText(o: JSONObject, vararg names: String): String {
        for (n in names) {
            val v = o.opt(n)
            if (v is String && v.isNotBlank()) return v.trim()
        }
        return ""
    }

    /**
     * Podzariadenia brány z odpovede /v1.0/devices/{gateway}/sub-devices: pole v result, alebo result.devices / list.
     * Vráti dvojice (Device ID podzariadenia, node_id). Tolerantné: položka bez ID sa preskočí, node_id sa hľadá pod
     * viacerými názvami a nakoniec sa použije uuid.
     */
    fun subDevices(result: Any?): List<Pair<String, String>> {
        val arr: JSONArray = when (result) {
            is JSONArray -> result
            is JSONObject -> result.optJSONArray("devices") ?: result.optJSONArray("list") ?: result.optJSONArray("sub_devices") ?: return emptyList()
            else -> return emptyList()
        }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = firstText(o, "id", "device_id", "devId")
            val cid = firstText(o, "node_id", "nodeId", "cid", "uuid")
            if (id.isEmpty() || cid.isEmpty()) null else id to cid
        }
    }

    /**
     * Popis hodnôt zo /v1.1/devices/{id}/specifications: zlúči status a functions do formátu tinytuya
     * {"1": {"code": "switch_1", "type": "Boolean", "values": {}}, …} – rovnako ako Cloud._build_mapping.
     */
    fun mapping(spec: JSONObject): JSONObject {
        val out = JSONObject()
        for (key in listOf("status", "functions")) {
            val arr = spec.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val code = item.optString("code")
                val type = item.optString("type")
                if (code.isBlank() || type.isBlank()) continue
                val dp = item.opt("dp_id")?.toString() ?: code
                if (out.has(dp)) continue
                val rawValues = item.opt("values")
                val values: Any = if (type.equals("string", true)) {
                    rawValues ?: ""
                } else {
                    when (rawValues) {
                        is String -> runCatching { JSONObject(rawValues) }.getOrElse {
                            runCatching { JSONArray(rawValues) }.getOrElse { rawValues }
                        }
                        null -> JSONObject.NULL
                        else -> rawValues
                    }
                }
                if (values is JSONObject && values.has("unit")) {
                    val unit = values.optString("unit")
                    if (unit.isNotEmpty()) {
                        // ako tinytuya, ale „c“/„f“ len ako celá jednotka (tinytuya by z „cm“ urobil „°Cm“)
                        val fixed = when (unit) {
                            "c" -> "°C"
                            "f" -> "°F"
                            else -> unit.replace("℃", "°C").replace("℉", "°F").replace("秒", "s")
                        }
                        values.put("unit", fixed)
                    }
                }
                val data = JSONObject().put("code", code).put("type", type)
                if (type.equals("json", true)) data.put("raw_values", rawValues)
                data.put("values", values)
                out.put(dp, data)
            }
        }
        return out
    }

    /** Vlastnosti z modelu vecí (pole „model“ je JSON v texte). Pri chybe prázdny zoznam. */
    fun thingProps(modelText: String): List<ThingProp> {
        val model = runCatching { JSONObject(modelText) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ThingProp>()
        val services = model.optJSONArray("services") ?: return emptyList()
        for (i in 0 until services.length()) {
            val props = services.optJSONObject(i)?.optJSONArray("properties") ?: continue
            for (j in 0 until props.length()) {
                val p = props.optJSONObject(j) ?: continue
                val id = p.optInt("abilityId", -1)
                val code = p.optString("code")
                if (id < 0 || code.isBlank()) continue
                val spec = p.optJSONObject("typeSpec") ?: JSONObject()
                val shown = JSONObject(spec.toString()).apply { remove("type") }
                out += ThingProp(id, code, p.optString("name"), spec.optString("type"), p.optString("accessMode"), if (shown.length() == 0) "" else shown.toString())
            }
        }
        return out
    }

    /** Posledné hodnoty z /shadow/properties: result.properties[{code, dp_id, type, value}]. */
    fun shadow(result: JSONObject?): List<ShadowProp> {
        val arr = result?.optJSONArray("properties") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val dp = p.optInt("dp_id", -1).takeIf { it >= 0 } ?: return@mapNotNull null
            val v = p.opt("value")
            ShadowProp(dp, p.optString("code"), p.optString("type"), if (v == JSONObject.NULL) null else v)
        }
    }

    /**
     * Doplní do popisu hodnôt (formát tinytuya) DP z modelu vecí, ktoré v ňom chýbajú. Existujúce sa nemenia.
     * Typy modelu vecí: bool, value, enum, string, raw, bitmap → Boolean, Integer, Enum, String, Raw, Bitmap.
     */
    fun addThingProps(mapping: JSONObject, props: List<ThingProp>) {
        for (p in props) {
            val key = p.dp.toString()
            if (mapping.has(key)) continue
            val type = when (p.type.lowercase()) {
                "bool" -> "Boolean"
                "value" -> "Integer"
                "enum" -> "Enum"
                "string" -> "String"
                "raw" -> "Raw"
                "bitmap" -> "Bitmap"
                else -> continue
            }
            val values = runCatching { JSONObject(p.spec.ifBlank { "{}" }) }.getOrElse { JSONObject() }
            mapping.put(key, JSONObject().put("code", p.code).put("type", type).put("values", values))
        }
    }

    /** Zrozumiteľná slovenská správa k chybe z Tuya cloudu. */
    fun describe(t: Throwable): String = when (t) {
        is CloudException -> describe(t.code, t.msg)
        is java.net.UnknownHostException -> "Server Tuya cloudu sa nenašiel. Skontroluj pripojenie na internet."
        is java.net.SocketTimeoutException -> "Tuya cloud neodpovedá. Skontroluj pripojenie na internet a skús znova."
        is javax.net.ssl.SSLException -> "Zabezpečené spojenie s Tuya cloudom zlyhalo. Skontroluj dátum a čas v telefóne."
        is IOException -> "Chyba spojenia s Tuya cloudom: ${t.message ?: t.javaClass.simpleName}"
        else -> t.message ?: t.javaClass.simpleName
    }

    fun describe(code: Int, msg: String): String {
        val m = msg.lowercase()
        val text = when {
            code == 1004 || m.contains("sign invalid") ->
                "Zlý Access Secret (podpis nesedí). Skopíruj ho znova celý z Overview projektu."
            code == 1005 || m.contains("clientid is invalid") || m.contains("client_id") ->
                "Neznáme Access ID, alebo je projekt v inom dátovom centre. Skontroluj Access ID a dátové centrum."
            code == 1013 || m.contains("request time") ->
                "Čas v telefóne nesedí so serverom. Zapni automatický dátum a čas."
            code == 28841105 || code == 28841002 || code == 28841104 || m.contains("expired") ->
                "Skúšobná služba IoT Core vypršala. Predĺž ju zadarmo na iot.tuya.com: Cloud → Cloud Services → IoT Core → " +
                    "View Details → Extend Trial Period (pozri Pomoc)."
            code == 28841101 || m.contains("not subscribed") || m.contains("no permissions") ->
                "Projekt nemá pridanú potrebnú službu. V projekte na iot.tuya.com pridaj v Service API služby IoT Core " +
                    "a Authorization Token Management."
            code == 1106 || m.contains("permission deny") ->
                "Prístup zamietnutý. Účet Tuya / Smart Life appky asi nie je prepojený s projektom (Devices → Link App " +
                    "Account), alebo je vybraté iné dátové centrum, než v ktorom je tvoj účet."
            code == 1010 || code == 1011 || m.contains("token") ->
                "Prihlásenie do Tuya cloudu vypršalo. Skús to znova."
            code == 1100 || code == 1101 || m.contains("param") ->
                "Tuya cloud odmietol požiadavku (zlé parametre)."
            code == 2009 || m.contains("not support") ->
                "Tuya cloud toto zariadenie nepodporuje."
            else -> "Chyba Tuya cloudu."
        }
        return "$text (kód $code: $msg)"
    }
}
