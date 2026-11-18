package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/*
 * Honeywell evohome (RFG100) cez cloud Total Connect Comfort International – ten istý, ktorý používa
 * appka „Honeywell Home“ v Európe. Rozhranie je to isté ako v knižniciach evohome-client / evohome-async
 * a v integrácii „evohome“ v Home Assistant (TCC v2 API, server tccna.resideo.com).
 *
 * Tento súbor je čistý Kotlin (bez Androidu), aby sa dal testovať na počítači (tools/EvohomeTest.kt).
 * Všetky funkcie sú blokujúce – volajú sa z vlákna na pozadí.
 */

/** Druh chyby – podľa neho sa ukáže zrozumiteľná slovenská správa. */
enum class EvoErrorKind { WRONG_PASSWORD, NO_INTERNET, RATE_LIMITED, SERVER, REJECTED, BAD_DATA, NOT_LOGGED_IN }

class EvoException(val kind: EvoErrorKind, message: String, val retryAfterSec: Long = 0) : Exception(message)

/** Krátka slovenská správa pre používateľa. */
fun evoErrorText(e: Throwable): String = when ((e as? EvoException)?.kind) {
    EvoErrorKind.WRONG_PASSWORD -> "Nesprávny e-mail alebo heslo k Honeywell Home."
    EvoErrorKind.NO_INTERNET -> "Bez spojenia s Honeywell serverom – skontroluj internet (kúrenie sa ovláda cez cloud, nie cez WiFi doma)."
    EvoErrorKind.RATE_LIMITED -> "Honeywell server dočasne obmedzil prístup (príliš veľa požiadaviek). Skús to o ${((e as EvoException).retryAfterSec + 59) / 60} min."
    EvoErrorKind.SERVER -> "Honeywell server práve neodpovedá správne. Skús to o chvíľu."
    EvoErrorKind.REJECTED -> "Honeywell server príkaz odmietol: ${e.message}"
    EvoErrorKind.BAD_DATA -> "Honeywell server poslal nečakané údaje: ${e.message}"
    EvoErrorKind.NOT_LOGGED_IN -> "Prihlásenie do Honeywell Home vypršalo – prihlás sa znova."
    null -> "Kúrenie: " + (e.message ?: e.javaClass.simpleName)
}

data class EvoTokens(val access: String, val expiresAt: Long, val refresh: String)

/** Režimy celého systému (systemMode). */
object EvoMode {
    const val AUTO = "Auto"
    const val ECO = "AutoWithEco"
    const val AWAY = "Away"
    const val OFF = "HeatingOff"
    const val DAY_OFF = "DayOff"
    const val CUSTOM = "Custom"
    const val RESET = "AutoWithReset"

    /** Ponúkané v appke (v tomto poradí), ak ich systém podporuje. */
    val offered = listOf(AUTO, ECO, AWAY, OFF)

    fun label(mode: String): String = when (mode) {
        AUTO -> "Auto"
        ECO -> "Eco"
        AWAY -> "Preč"
        OFF -> "Vypnuté"
        DAY_OFF -> "Voľný deň"
        CUSTOM -> "Vlastný"
        RESET -> "Auto (reset)"
        else -> mode
    }
}

/** Režimy zóny (setpointMode). */
object EvoZoneMode {
    const val FOLLOW = "FollowSchedule"
    const val PERMANENT = "PermanentOverride"
    const val TEMPORARY = "TemporaryOverride"
}

data class EvoZone(
    val id: String,
    val name: String,
    val temperature: Double?,
    val setpoint: Double?,
    val mode: String,
    val until: Long? = null,
    val min: Double = 5.0,
    val max: Double = 35.0,
    val step: Double = 0.5,
    val faults: List<String> = emptyList(),
)

data class EvoDhw(val id: String, val temperature: Double?, val state: String?, val mode: String, val until: Long? = null)

data class EvoSystem(
    val systemId: String,
    val locationName: String,
    val mode: String,
    val permanent: Boolean,
    val modeUntil: Long?,
    val allowedModes: List<String>,
    val zones: List<EvoZone>,
    val dhw: EvoDhw?,
    val faults: List<String> = emptyList(),
)

data class EvoZoneInfo(val id: String, val name: String, val min: Double, val max: Double, val step: Double)

/** Inštalácia (nemenné údaje): ID lokality, systému, zóny a ich rozsahy. Neobsahuje adresu ani e-mail. */
data class EvoInstall(
    val userId: String,
    val locationId: String,
    val locationName: String,
    val systemId: String,
    val allowedModes: List<String>,
    val zones: List<EvoZoneInfo>,
    val dhwId: String?,
    /** Aktuálny posun časového pásma lokality v minútach (na výpočet ďalšej zmeny v programe). */
    val offsetMinutes: Int?,
    val fetchedAt: Long = 0,
) {
    fun toJson(): String = JSONObject()
        .put("userId", userId).put("locationId", locationId).put("locationName", locationName).put("systemId", systemId)
        .put("allowedModes", JSONArray(allowedModes))
        .put("zones", JSONArray(zones.map { JSONObject().put("id", it.id).put("name", it.name).put("min", it.min).put("max", it.max).put("step", it.step) }))
        .put("dhwId", dhwId ?: JSONObject.NULL)
        .put("offset", offsetMinutes ?: JSONObject.NULL)
        .put("fetchedAt", fetchedAt)
        .toString()

    companion object {
        fun fromJson(raw: String?): EvoInstall? = try {
            if (raw.isNullOrBlank()) null else {
                val o = JSONObject(raw)
                val z = o.optJSONArray("zones") ?: JSONArray()
                EvoInstall(
                    userId = o.optString("userId"),
                    locationId = o.getString("locationId"),
                    locationName = o.optString("locationName"),
                    systemId = o.getString("systemId"),
                    allowedModes = o.optJSONArray("allowedModes")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: EvoMode.offered,
                    zones = (0 until z.length()).mapNotNull { i -> z.optJSONObject(i) }.map {
                        EvoZoneInfo(it.optString("id"), it.optString("name"), it.optDouble("min", 5.0), it.optDouble("max", 35.0), it.optDouble("step", 0.5))
                    },
                    dhwId = o.optString("dhwId").takeIf { it.isNotBlank() && it != "null" },
                    offsetMinutes = if (o.isNull("offset")) null else o.optInt("offset"),
                    fetchedAt = o.optLong("fetchedAt"),
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** Čítanie odpovedí servera – tolerantné k chýbajúcim poliam (nič tu nesmie zhodiť appku). */
object EvoParse {
    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
    private fun JSONObject.num(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    fun userId(raw: String): String = try {
        JSONObject(raw).str("userId") ?: throw EvoException(EvoErrorKind.BAD_DATA, "chýba userId")
    } catch (e: org.json.JSONException) {
        throw EvoException(EvoErrorKind.BAD_DATA, "userAccount nie je JSON")
    }

    /** Z installationInfo vyberie prvú lokalitu, ktorá má systém kúrenia (evohome má zvyčajne jednu). */
    fun installation(raw: String, userId: String, now: Long = 0): EvoInstall {
        val locations = try {
            JSONArray(raw).objects()
        } catch (e: org.json.JSONException) {
            throw EvoException(EvoErrorKind.BAD_DATA, "installationInfo nie je zoznam")
        }
        for (loc in locations) {
            val info = loc.optJSONObject("locationInfo") ?: continue
            val locationId = info.str("locationId") ?: continue
            for (gw in loc.optJSONArray("gateways").objects()) {
                for (tcs in gw.optJSONArray("temperatureControlSystems").objects()) {
                    val systemId = tcs.str("systemId") ?: continue
                    val modes = tcs.optJSONArray("allowedSystemModes").objects().mapNotNull { it.str("systemMode") }
                    val zones = tcs.optJSONArray("zones").objects().mapNotNull { z ->
                        val id = z.str("zoneId") ?: return@mapNotNull null
                        val caps = z.optJSONObject("setpointCapabilities")
                        EvoZoneInfo(
                            id = id,
                            name = z.str("name") ?: "Zóna $id",
                            min = caps?.num("minHeatSetpoint") ?: 5.0,
                            max = caps?.num("maxHeatSetpoint") ?: 35.0,
                            step = (caps?.num("valueResolution") ?: 0.5).takeIf { it > 0 } ?: 0.5,
                        )
                    }
                    val dhwId = tcs.optJSONObject("dhw")?.str("dhwId")
                    val tz = info.optJSONObject("timeZone")
                    return EvoInstall(
                        userId = userId,
                        locationId = locationId,
                        locationName = info.str("name") ?: "Domov",
                        systemId = systemId,
                        allowedModes = modes.ifEmpty { EvoMode.offered },
                        zones = zones,
                        dhwId = dhwId,
                        offsetMinutes = tz?.let { if (it.has("currentOffsetMinutes")) it.optInt("currentOffsetMinutes") else null },
                        fetchedAt = now,
                    )
                }
            }
        }
        throw EvoException(EvoErrorKind.BAD_DATA, "v účte nie je žiadny systém evohome")
    }

    /** Stav lokality (location/{id}/status) → systém, zóny a teplá voda. */
    fun status(raw: String, install: EvoInstall): EvoSystem {
        val root = try {
            JSONObject(raw)
        } catch (e: org.json.JSONException) {
            throw EvoException(EvoErrorKind.BAD_DATA, "stav nie je JSON")
        }
        val systems = root.optJSONArray("gateways").objects().flatMap { it.optJSONArray("temperatureControlSystems").objects() }
        val tcs = systems.find { it.str("systemId") == install.systemId } ?: systems.firstOrNull()
            ?: throw EvoException(EvoErrorKind.BAD_DATA, "v stave chýba systém kúrenia")
        val modeStatus = tcs.optJSONObject("systemModeStatus")
        val caps = install.zones.associateBy { it.id }
        val zones = tcs.optJSONArray("zones").objects().mapNotNull { z ->
            val id = z.str("zoneId") ?: return@mapNotNull null
            val t = z.optJSONObject("temperatureStatus")
            val sp = z.optJSONObject("setpointStatus")
            val cap = caps[id]
            EvoZone(
                id = id,
                name = z.str("name") ?: cap?.name ?: "Zóna $id",
                temperature = if (t?.optBoolean("isAvailable", false) == true) t.num("temperature") else null,
                setpoint = sp?.num("targetHeatTemperature"),
                mode = sp?.str("setpointMode") ?: EvoZoneMode.FOLLOW,
                until = sp?.str("until")?.let(::parseTime),
                min = cap?.min ?: 5.0,
                max = cap?.max ?: 35.0,
                step = cap?.step ?: 0.5,
                faults = z.optJSONArray("activeFaults").objects().mapNotNull { it.str("faultType") },
            )
        }
        // poradie zón ako v inštalácii (rovnaké ako v appke Honeywell)
        val order = install.zones.mapIndexed { i, z -> z.id to i }.toMap()
        val dhw = tcs.optJSONObject("dhw")?.let { d ->
            val id = d.str("dhwId") ?: install.dhwId ?: return@let null
            val t = d.optJSONObject("temperatureStatus")
            val st = d.optJSONObject("stateStatus")
            EvoDhw(
                id = id,
                temperature = if (t?.optBoolean("isAvailable", false) == true) t.num("temperature") else null,
                state = st?.str("state"),
                mode = st?.str("mode") ?: EvoZoneMode.FOLLOW,
                until = st?.str("until")?.let(::parseTime),
            )
        }
        return EvoSystem(
            systemId = tcs.str("systemId") ?: install.systemId,
            locationName = install.locationName,
            mode = modeStatus?.str("mode") ?: EvoMode.AUTO,
            permanent = modeStatus?.optBoolean("isPermanent", true) ?: true,
            modeUntil = modeStatus?.str("timeUntil")?.let(::parseTime),
            allowedModes = install.allowedModes,
            zones = zones.sortedBy { order[it.id] ?: Int.MAX_VALUE },
            dhw = dhw,
            faults = tcs.optJSONArray("activeFaults").objects().mapNotNull { it.str("faultType") },
        )
    }

    /** „2025-02-16T10:30:00Z“ → epoch ms (bez zóny = UTC). */
    fun parseTime(s: String): Long? = try {
        val t = s.trim()
        if (t.endsWith("Z") || t.contains('+') || t.lastIndexOf('-') > 9) java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli()
        else LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (e: Exception) {
        null
    }

    data class Switchpoint(val day: DayOfWeek, val time: LocalTime, val setpoint: Double?)

    fun schedule(raw: String): List<Switchpoint> = try {
        JSONObject(raw).optJSONArray("dailySchedules").objects().flatMap { d ->
            val day = runCatching { DayOfWeek.valueOf(d.optString("dayOfWeek").uppercase()) }.getOrNull() ?: return@flatMap emptyList()
            d.optJSONArray("switchpoints").objects().mapNotNull { s ->
                val time = runCatching { LocalTime.parse(s.optString("timeOfDay")) }.getOrNull() ?: return@mapNotNull null
                Switchpoint(day, time, s.num("heatSetpoint"))
            }
        }
    } catch (e: org.json.JSONException) {
        throw EvoException(EvoErrorKind.BAD_DATA, "program nie je JSON")
    }

    /** Najbližšia zmena v programe po [now] (miestny čas lokality), najviac týždeň dopredu. */
    fun nextSwitch(schedule: List<Switchpoint>, now: LocalDateTime): LocalDateTime? {
        if (schedule.isEmpty()) return null
        for (offset in 0..7) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            val next = schedule.filter { it.day == date.dayOfWeek }.map { date.atTime(it.time) }.filter { it.isAfter(now) }.minOrNull()
            if (next != null) return next
        }
        return null
    }
}

/** Telá príkazov (PUT) presne ako ich posiela evohome-async. */
object EvoBody {
    private val UTC_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun utc(epochMs: Long): String = UTC_FORMAT.format(Instant.ofEpochMilli(epochMs / 1000 * 1000))

    fun zoneSetpoint(value: Double, untilMs: Long?): String = JSONObject()
        .put("setpointMode", if (untilMs == null) EvoZoneMode.PERMANENT else EvoZoneMode.TEMPORARY)
        .put("heatSetpointValue", value)
        .apply { if (untilMs != null) put("timeUntil", utc(untilMs)) }
        .toString()

    fun zoneFollow(): String = JSONObject().put("setpointMode", EvoZoneMode.FOLLOW).toString()

    fun systemMode(mode: String, untilMs: Long? = null): String = JSONObject()
        .put("systemMode", mode)
        .put("permanent", untilMs == null)
        .apply { if (untilMs != null) put("timeUntil", utc(untilMs)) }
        .toString()

    /** [on] null = podľa programu. */
    fun dhw(on: Boolean?): String = if (on == null) JSONObject().put("mode", EvoZoneMode.FOLLOW).toString()
    else JSONObject().put("mode", EvoZoneMode.PERMANENT).put("state", if (on) "On" else "Off").toString()
}

/** HTTP vrstva (v testoch sa dá nahradiť). */
fun interface EvoHttp {
    data class Response(val code: Int, val body: String, val retryAfter: String? = null)

    fun send(method: String, url: String, headers: Map<String, String>, body: String?, contentType: String?): Response

    companion object {
        val Default = EvoHttp { method, url, headers, body, contentType ->
            val all = if (body != null && contentType != null) headers + ("Content-Type" to contentType) else headers
            Http.send(method, url, all, body, connectMs = 15_000, readMs = 20_000).let { Response(it.code, it.body, it.retryAfter) }
        }
    }
}

/**
 * Klient TCC v2 API. Prihlásenie heslom len raz, potom sa používa access token (platí ~30 min) a obnovuje sa
 * refresh tokenom – server prihlásenia obmedzuje, preto sa tokeny ukladajú ([onTokens]).
 */
class EvohomeClient(
    private val email: String,
    private val password: String,
    var tokens: EvoTokens? = null,
    private val host: String = HOST,
    private val http: EvoHttp = EvoHttp.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onTokens: (EvoTokens?) -> Unit = {},
) {
    /** Do kedy server odmieta požiadavky (po 429). */
    @Volatile
    var blockedUntil: Long = 0
        private set

    private fun checkBlocked() {
        val left = blockedUntil - clock()
        if (left > 0) throw EvoException(EvoErrorKind.RATE_LIMITED, "obmedzené", (left + 999) / 1000)
    }

    private fun call(method: String, url: String, headers: Map<String, String>, body: String?, contentType: String?): EvoHttp.Response {
        checkBlocked()
        val r = try {
            http.send(method, url, headers, body, contentType)
        } catch (e: IOException) {
            throw EvoException(EvoErrorKind.NO_INTERNET, e.message ?: e.javaClass.simpleName)
        } catch (e: SecurityException) {
            throw EvoException(EvoErrorKind.NO_INTERNET, e.message ?: "bez oprávnenia na internet")
        }
        if (r.code == 429) {
            val sec = r.retryAfter?.trim()?.toLongOrNull()?.coerceIn(30, 3600) ?: DEFAULT_RETRY_AFTER_SEC
            blockedUntil = clock() + sec * 1000
            throw EvoException(EvoErrorKind.RATE_LIMITED, "429", sec)
        }
        if (r.code >= 500) throw EvoException(EvoErrorKind.SERVER, "HTTP ${r.code}")
        return r
    }

    private fun form(map: Map<String, String>) = map.entries.joinToString("&") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }

    private fun tokenRequest(fields: Map<String, String>): EvoHttp.Response = call(
        "POST",
        "$host/Auth/OAuth/Token",
        mapOf(
            "Accept" to "application/json",
            "Cache-Control" to "no-cache, no-store",
            "Pragma" to "no-cache",
            "Authorization" to "Basic $APPLICATION_ID",
        ),
        form(fields + ("scope" to SCOPE)),
        "application/x-www-form-urlencoded; charset=utf-8",
    )

    private fun saveTokens(r: EvoHttp.Response): EvoTokens {
        val o = try {
            JSONObject(r.body)
        } catch (e: org.json.JSONException) {
            throw EvoException(EvoErrorKind.BAD_DATA, "odpoveď prihlásenia nie je JSON")
        }
        val access = o.optString("access_token")
        if (access.isBlank()) throw EvoException(EvoErrorKind.BAD_DATA, "chýba access_token")
        val t = EvoTokens(access, clock() + o.optLong("expires_in", 1800) * 1000, o.optString("refresh_token"))
        tokens = t
        onTokens(t)
        return t
    }

    /** Prihlásenie heslom (overí e-mail a heslo). */
    @Synchronized
    fun login(): EvoTokens {
        val r = tokenRequest(mapOf("grant_type" to "password", "Username" to email, "Password" to password))
        if (r.code == 400 || r.code == 401) {
            val err = runCatching { JSONObject(r.body).optString("error") }.getOrDefault("")
            throw EvoException(
                if (err == "invalid_grant" || r.code == 401) EvoErrorKind.WRONG_PASSWORD else EvoErrorKind.REJECTED,
                err.ifBlank { "HTTP ${r.code}" },
            )
        }
        if (r.code !in 200..299) throw EvoException(EvoErrorKind.REJECTED, "HTTP ${r.code}")
        return saveTokens(r)
    }

    /** Platný access token: uložený, inak obnovený refresh tokenom, inak nové prihlásenie heslom. */
    @Synchronized
    fun accessToken(): String {
        val t = tokens
        if (t != null && t.access.isNotBlank() && t.expiresAt - 60_000 > clock()) return t.access
        if (t != null && t.refresh.isNotBlank()) {
            val r = tokenRequest(mapOf("grant_type" to "refresh_token", "refresh_token" to t.refresh))
            if (r.code in 200..299) return saveTokens(r).access
            // neplatný refresh token (400/401) → prihlásime sa znova heslom
            tokens = null
            onTokens(null)
        }
        return login().access
    }

    @Synchronized
    private fun dropAccessToken() {
        tokens = tokens?.copy(access = "", expiresAt = 0)
    }

    /** Požiadavka na /WebAPI/emea/api/v1/…; pri 401 raz obnoví token a skúsi znova. */
    fun api(method: String, path: String, body: String? = null): String {
        repeat(2) { attempt ->
            val r = call(
                method,
                "$host/WebAPI/emea/api/v1/$path",
                mapOf("Accept" to "application/json", "Authorization" to "bearer " + accessToken()),
                body,
                if (body != null) "application/json" else null,
            )
            when {
                r.code in 200..299 -> return r.body
                r.code == 401 && attempt == 0 -> dropAccessToken()
                r.code == 401 -> throw EvoException(EvoErrorKind.NOT_LOGGED_IN, "HTTP 401")
                else -> throw EvoException(EvoErrorKind.REJECTED, vendorError(r))
            }
        }
        throw EvoException(EvoErrorKind.NOT_LOGGED_IN, "HTTP 401")
    }

    private fun vendorError(r: EvoHttp.Response): String = try {
        val a = JSONArray(r.body)
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("code")?.takeIf { c -> c.isNotBlank() } }.joinToString().ifBlank { "HTTP ${r.code}" }
    } catch (e: Exception) {
        "HTTP ${r.code}"
    }

    fun installation(): EvoInstall {
        val userId = EvoParse.userId(api("GET", "userAccount"))
        return EvoParse.installation(api("GET", "location/installationInfo?userId=$userId&includeTemperatureControlSystems=True"), userId, clock())
    }

    fun status(install: EvoInstall): EvoSystem =
        EvoParse.status(api("GET", "location/${install.locationId}/status?includeTemperatureControlSystems=True"), install)

    fun setZone(zoneId: String, value: Double, untilMs: Long?) {
        api("PUT", "temperatureZone/$zoneId/heatSetpoint", EvoBody.zoneSetpoint(value, untilMs))
    }

    fun followSchedule(zoneId: String) {
        api("PUT", "temperatureZone/$zoneId/heatSetpoint", EvoBody.zoneFollow())
    }

    fun setMode(systemId: String, mode: String) {
        api("PUT", "temperatureControlSystem/$systemId/mode", EvoBody.systemMode(mode))
    }

    fun setDhw(dhwId: String, on: Boolean?) {
        api("PUT", "domesticHotWater/$dhwId/state", EvoBody.dhw(on))
    }

    fun schedule(zoneId: String): List<EvoParse.Switchpoint> = EvoParse.schedule(api("GET", "temperatureZone/$zoneId/schedule"))

    companion object {
        const val HOST = "https://tccna.resideo.com"
        const val SCOPE = "EMEA-V1-Basic EMEA-V1-Anonymous"
        const val DEFAULT_RETRY_AFTER_SEC = 15 * 60L

        /** Verejné ID aplikácie (client_id:secret) – rovnaké ako v appke Honeywell a v evohome-async. */
        val APPLICATION_ID: String = Base64.getEncoder().encodeToString(
            "4a231089-d2b6-41bd-a5eb-16a0a422b999:1a15cdb8-42de-407b-add0-059f92c530cb".toByteArray(),
        )
    }
}

/** Zdroj údajov o kúrení: skutočný cloud alebo ukážka (na snímky obrazovky). */
interface EvoSource {
    fun status(): EvoSystem
    fun setZone(zoneId: String, value: Double, untilMs: Long?)
    fun followSchedule(zoneId: String)
    fun setMode(mode: String)
    fun setDhw(on: Boolean?)

    /** Čas ďalšej zmeny v programe zóny (epoch ms), null = program nie je známy. */
    fun nextSwitch(zoneId: String, now: Long): Long?
}

/** Skutočný evohome cez cloud. Inštalácia sa načíta raz (a potom raz za deň). */
class CloudEvoSource(
    private val client: EvohomeClient,
    var install: EvoInstall?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onInstall: (EvoInstall) -> Unit = {},
) : EvoSource {
    private val schedules = HashMap<String, Pair<Long, List<EvoParse.Switchpoint>>>()

    @Synchronized
    fun ensureInstall(): EvoInstall {
        val cur = install
        if (cur != null && clock() - cur.fetchedAt < 24 * 3600_000L) return cur
        return try {
            client.installation().also { install = it; onInstall(it) }
        } catch (e: EvoException) {
            // keď server nejde, použijeme staršiu inštaláciu (zóny sa menia len zriedka)
            if (cur != null && e.kind != EvoErrorKind.WRONG_PASSWORD) cur else throw e
        }
    }

    /** Posledná surová odpoveď stavu (appka si ju uloží, aby po spustení hneď ukázala posledné hodnoty). */
    @Volatile
    var lastRaw: String? = null

    override fun status(): EvoSystem {
        val inst = ensureInstall()
        val raw = client.api("GET", "location/${inst.locationId}/status?includeTemperatureControlSystems=True")
        return EvoParse.status(raw, inst).also { lastRaw = raw }
    }
    override fun setZone(zoneId: String, value: Double, untilMs: Long?) = client.setZone(zoneId, value, untilMs)
    override fun followSchedule(zoneId: String) = client.followSchedule(zoneId)
    override fun setMode(mode: String) = client.setMode(ensureInstall().systemId, mode)
    override fun setDhw(on: Boolean?) {
        val id = ensureInstall().dhwId ?: throw EvoException(EvoErrorKind.REJECTED, "systém nemá ohrev vody")
        client.setDhw(id, on)
    }

    override fun nextSwitch(zoneId: String, now: Long): Long? {
        val cached = schedules[zoneId]
        val sched = if (cached != null && now - cached.first < 6 * 3600_000L) cached.second
        else client.schedule(zoneId).also { schedules[zoneId] = now to it }
        val zone: ZoneId = ensureInstall().offsetMinutes?.let { ZoneOffset.ofTotalSeconds(it * 60) } ?: ZoneId.systemDefault()
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        return EvoParse.nextSwitch(sched, local)?.atZone(zone)?.toInstant()?.toEpochMilli()
    }
}

/** Ukážkový evohome (len v pamäti) – pre snímky obrazovky a testy. */
class DemoEvoSource(private val clock: () -> Long = System::currentTimeMillis) : EvoSource {
    private var mode = EvoMode.AUTO
    private var dhw = EvoDhw("demo-dhw", 52.0, "On", EvoZoneMode.FOLLOW)
    private val zones = mutableListOf(
        EvoZone("z1", "Obývačka", 21.4, 21.0, EvoZoneMode.FOLLOW),
        EvoZone("z2", "Spálňa", 19.2, 19.0, EvoZoneMode.FOLLOW),
        EvoZone("z3", "Kúpeľňa", 22.6, 23.0, EvoZoneMode.TEMPORARY, until = null),
        EvoZone("z4", "Detská izba", 20.1, 20.5, EvoZoneMode.PERMANENT),
    )

    init {
        zones[2] = zones[2].copy(until = nextHour(2))
    }

    private fun nextHour(h: Int): Long = (clock() / 3600_000L + h) * 3600_000L

    @Synchronized
    override fun status() = EvoSystem(
        systemId = "demo", locationName = "Domov", mode = mode, permanent = true, modeUntil = null,
        allowedModes = listOf(EvoMode.AUTO, EvoMode.ECO, EvoMode.AWAY, EvoMode.OFF, EvoMode.DAY_OFF, EvoMode.CUSTOM),
        zones = zones.map {
            if (mode == EvoMode.OFF) it.copy(setpoint = 5.0) else if (mode == EvoMode.ECO && it.mode == EvoZoneMode.FOLLOW) it.copy(setpoint = (it.setpoint ?: 20.0) - 3) else it
        },
        dhw = dhw,
    )

    @Synchronized
    override fun setZone(zoneId: String, value: Double, untilMs: Long?) {
        val i = zones.indexOfFirst { it.id == zoneId }.takeIf { it >= 0 } ?: throw EvoException(EvoErrorKind.REJECTED, "neznáma zóna")
        zones[i] = zones[i].copy(setpoint = value, mode = if (untilMs == null) EvoZoneMode.PERMANENT else EvoZoneMode.TEMPORARY, until = untilMs)
    }

    @Synchronized
    override fun followSchedule(zoneId: String) {
        val i = zones.indexOfFirst { it.id == zoneId }.takeIf { it >= 0 } ?: return
        zones[i] = zones[i].copy(setpoint = 20.0, mode = EvoZoneMode.FOLLOW, until = null)
    }

    @Synchronized
    override fun setMode(mode: String) {
        this.mode = mode
    }

    @Synchronized
    override fun setDhw(on: Boolean?) {
        dhw = dhw.copy(state = if (on == null) "On" else if (on) "On" else "Off", mode = if (on == null) EvoZoneMode.FOLLOW else EvoZoneMode.PERMANENT)
    }

    override fun nextSwitch(zoneId: String, now: Long): Long = nextHour(3)
}

/** Text teploty po slovensky: „21,5 °C“, bez hodnoty „—“. */
fun evoTemp(v: Double?): String = if (v == null) "—" else String.format(java.util.Locale.ROOT, "%.1f", v).replace('.', ',') + " °C"

/** Zaokrúhli na krok zóny a obmedzí na povolený rozsah. */
fun evoClamp(value: Double, zone: EvoZone): Double {
    val step = if (zone.step > 0) zone.step else 0.5
    val r = Math.round(value / step) * step
    return r.coerceIn(zone.min, zone.max)
}
