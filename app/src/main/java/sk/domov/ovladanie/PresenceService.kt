package sk.domov.ovladanie

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ================================================================== uložené nastavenia, stav a história

object PresenceStore {
    private const val MAX_HISTORY = 30

    fun prefs(context: Context) = context.getSharedPreferences("domov_presence", Context.MODE_PRIVATE)

    fun config(context: Context): PresenceConfig = PresenceConfig.fromJson(prefs(context).getString("config", null))

    fun saveConfig(context: Context, cfg: PresenceConfig) {
        prefs(context).edit().putString("config", cfg.toJson().toString()).apply()
    }

    fun status(context: Context): PresenceStatus = PresenceStatus.fromJson(prefs(context).getString("status", null))

    fun saveStatus(context: Context, s: PresenceStatus) {
        prefs(context).edit().putString("status", s.toJson().toString()).apply()
    }

    data class Entry(val time: Long, val text: String)

    fun history(context: Context): List<Entry> = try {
        val a = JSONArray(prefs(context).getString("history", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Entry(it.optLong("t"), it.optString("m")) }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun log(context: Context, text: String) {
        val list = listOf(Entry(System.currentTimeMillis(), text)) + history(context)
        val a = JSONArray()
        list.take(MAX_HISTORY).forEach { a.put(JSONObject().put("t", it.time).put("m", it.text)) }
        prefs(context).edit().putString("history", a.toString()).apply()
    }

    fun clearHistory(context: Context) {
        prefs(context).edit().remove("history").apply()
    }

    /** Kedy appka naposledy nastavila zariadeniu odpočet poistky (0 = nenastavený). */
    fun guardSet(context: Context, deviceId: String): Long = prefs(context).getLong("guard_$deviceId", 0L)

    fun setGuardSet(context: Context, deviceId: String, time: Long) {
        prefs(context).edit().apply { if (time == 0L) remove("guard_$deviceId") else putLong("guard_$deviceId", time) }.apply()
    }

    fun armedGuards(context: Context): Set<String> =
        prefs(context).all.keys.filter { it.startsWith("guard_") }.map { it.removePrefix("guard_") }.toSet()
}

/** Chyba v korutine na pozadí appku nezhodí, len sa zapíše do logu. */
val LogOnError = kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.w("Domov", "chyba v pozadí", e) }

fun timeText(t: Long): String = SimpleDateFormat("d.M. H:mm", Locale.forLanguageTag("sk")).format(Date(t))

// ================================================================== rozpoznanie domácej siete

object HomeNet {
    /** Odtlačok WiFi siete (router, rozsah, SSID ak ho Android prezradí), alebo null kým sieť nemá adresu. */
    fun fingerprint(context: Context, network: Network): HomeNetwork? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp: LinkProperties = cm.getLinkProperties(network) ?: return null
        val addr = wifiAddress(network, lp) ?: return null
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress ?: return null
        val dhcp = if (Build.VERSION.SDK_INT >= 30) lp.dhcpServerAddress?.hostAddress.orEmpty() else ""
        return HomeNetwork(
            gateway = gateway,
            subnet = addr.subnet,
            ssid = ssid(context),
            dhcpServer = dhcp,
        )
    }

    /** Názov WiFi – Android ho od verzie 8.1/10 prezradí len s povolením polohy; inak prázdne. */
    @Suppress("DEPRECATION")
    private fun ssid(context: Context): String {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return ""
        return try {
            HomeNetwork.cleanSsid(context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Overí, že je telefón naozaj doma: aspoň jedno zariadenie odpovie s vlastným local key (cudzia sieť s rovnakou
     * adresou routera to nedokáže). Skúsi naraz najviac 4 zariadenia. Vráti zariadenie, ktoré odpovedalo, alebo null.
     */
    suspend fun verify(context: Context, network: Network): Device? = withContext(Dispatchers.IO) {
        val devices = DeviceStore(context).load().filter { it.ip.isNotBlank() && it.localKey.isNotBlank() }
        if (devices.isEmpty()) return@withContext null
        // najprv zariadenia, ktoré naposledy odpovedali
        val ordered = devices.sortedByDescending { StateCache.load(context, it.id) != null }.take(4)
        withTimeoutOrNull(15_000) {
            ordered.map { d ->
                async {
                    try {
                        val v = DeviceClient.readState(network, d)
                        StateCache.save(context, d.id, v)
                        d
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll().firstOrNull { it != null }
        }
    }
}

// ================================================================== akcie príchod / odchod / poistka

object PresenceActions {
    private val lock = Mutex()

    private fun values(context: Context, network: Network?, d: Device): Map<String, Any> {
        if (network != null) {
            try {
                return DeviceClient.readState(network, d).also { StateCache.save(context, d.id, it) }
            } catch (e: Exception) {
                // použije sa posledný známy stav
            }
        }
        return StateCache.load(context, d.id) ?: emptyMap()
    }

    private fun cached(context: Context): (Device) -> Map<String, Any> = { StateCache.load(context, it.id) ?: emptyMap() }

    private fun isDarkNow(context: Context, cfg: PresenceConfig): Boolean {
        val c = Calendar.getInstance()
        val now = System.currentTimeMillis()
        return Darkness.isDark(cfg, now, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), SunRepository.place(context, now))
    }

    /** Príchod: zapne vybrané zariadenia lokálne cez WiFi. [manual] = test z nastavení (ignoruje „len po zotmení“). */
    suspend fun arrive(context: Context, network: Network?, manual: Boolean, prefix: String? = null): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val cfg = PresenceStore.config(context)
            val prefix = prefix ?: if (manual) "Test príchodu" else "Príchod domov"
            val all = DeviceStore(context).load()
            val ids = AutoDevices.arriveIds(cfg, all, cached(context))
            if (ids.isEmpty()) {
                return@withLock "$prefix – nie je vybraté žiadne zariadenie na zapnutie.".also { PresenceStore.log(context, it) }
            }
            if (!manual && cfg.onlyWhenDark && !isDarkNow(context, cfg)) {
                return@withLock "$prefix – ešte je svetlo, nič som nezapol.".also { PresenceStore.log(context, it) }
            }
            val net = network ?: wifiNetwork(context)
            if (net == null) {
                val msg = "$prefix – telefón nie je na WiFi, nedá sa nič zapnúť."
                PresenceStore.log(context, msg)
                return@withLock msg
            }
            val ok = mutableListOf<Device>()
            val failed = mutableListOf<String>()
            for (d in all.filter { it.id in ids }) {
                var error: String? = "nemá vypínač"
                for (attempt in 1..2) {
                    val v = values(context, net, d)
                    val cmd = AutoDevices.arriveCommand(d, v) ?: break
                    try {
                        DeviceClient.setValues(net, d, cmd)
                        StateCache.save(context, d.id, v + cmd, if (manual) StateCache.SRC_MANUAL_WIFI else StateCache.SRC_AUTO_WIFI)
                        error = null
                        break
                    } catch (e: Exception) {
                        error = DeviceClient.shortError(e).lowercase()
                        if (attempt == 1) delay(3000)
                    }
                }
                if (error == null) ok += d else failed += "${d.name} ($error)"
            }
            val lights = ok.all { AutoDevices.kind(it, StateCache.load(context, it.id) ?: emptyMap()) == AutoKind.LIGHT }
            val msg = buildString {
                append(prefix)
                if (ok.isNotEmpty()) append(" – zaplo sa ${countText(ok.size, lights)}: ${ok.joinToString { it.name }}")
                if (failed.isNotEmpty()) append(if (ok.isEmpty()) " – nepodarilo sa zapnúť: " else ". Nepodarilo sa: ").append(failed.joinToString())
                append('.')
            }
            PresenceStore.log(context, msg)
            if (!manual && (cfg.notifyDone || failed.isNotEmpty())) PresenceNotify.event(context, PresenceNotify.arriveTitle(context), msg,
                kind = if (failed.isNotEmpty()) PresenceNotify.Kind.FAILED else PresenceNotify.Kind.ROUTINE)
            msg
        }.also { guardTick(context, network, force = true) }
    }

    /**
     * Odchod: vypne vybrané zariadenia cez Tuya cloud (telefón už nie je na domácej WiFi, ide cez mobilné dáta).
     * Pri výpadku internetu to skúsi ešte dvakrát.
     */
    suspend fun depart(context: Context, manual: Boolean, prefix: String? = null): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val cfg = PresenceStore.config(context)
            val prefix = prefix ?: if (manual) "Test odchodu" else "Odišiel si"
            val all = DeviceStore(context).load()
            val ids = AutoDevices.departIds(cfg, all, cached(context))
            val targets = all.filter { it.id in ids }
            if (targets.isEmpty()) {
                return@withLock "$prefix – nie je vybraté žiadne zariadenie na vypnutie.".also { PresenceStore.log(context, it) }
            }
            val guarded = if (cfg.guardEnabled) targets.filter { it.id in cfg.guardIds }.map { it.name } else emptyList()
            val cloud = CloudStore(context).load()
            if (cloud == null || !cloud.isComplete) {
                val names = targets.map { it.name }.filter { it !in guarded }
                val msg = buildString {
                    append("$prefix – Tuya cloud nie je nastavený.")
                    if (names.isNotEmpty()) append(" Nepodarilo sa zhasnúť: ${names.joinToString()}.")
                    if (guarded.isNotEmpty()) append(" Poistka vypne do ${cfg.guardSeconds / 60} min: ${guarded.joinToString()}.")
                }
                PresenceStore.log(context, msg)
                if (names.isNotEmpty()) PresenceNotify.event(context, "Nepodarilo sa zhasnúť", msg, kind = PresenceNotify.Kind.FAILED)
                else if (!manual && cfg.notifyDone) PresenceNotify.event(context, PresenceNotify.departTitle(context, prefix), msg)
                return@withLock msg
            }
            val client = TuyaCloudClient(cloud)
            val pending = targets.toMutableList()
            val ok = mutableListOf<Device>()
            val failed = LinkedHashMap<String, String>()
            val skipped = LinkedHashMap<String, String>()
            val checks = mutableListOf<CloudCheck>()
            for (round in 1..3) {
                val next = mutableListOf<Device>()
                for (d in pending) {
                    try {
                        when (val r = turnOff(client, context, d, if (manual) StateCache.SRC_MANUAL_CLOUD else StateCache.SRC_AUTO_CLOUD)) {
                            is CloudOffResult.Sent -> { ok += d; r.check?.let { checks += it } }
                            is CloudOffResult.Idle -> { r.check?.let { checks += it } }
                            is CloudOffResult.Skipped -> skipped[d.name] = r.reason
                        }
                        failed.remove(d.name)
                    } catch (e: CloudException) {
                        failed[d.name] = CloudParse.describe(e)
                        if (e.code in TuyaCloudClient.AUTH_ERRORS) break
                    } catch (e: IOException) {
                        // bez internetu (WiFi už nie je, mobilné dáta ešte nenabehli) – skúsiť neskôr
                        failed[d.name] = "bez internetu"
                        next += d
                    }
                }
                if (next.isEmpty() || round == 3) break
                pending.clear(); pending += next
                delay(if (manual) 3_000 else 45_000)
            }
            // overenie rizikových zariadení (fritéza, ohrievač…): ak sa niečo zaplo, hneď Stop / vypnúť a varovať
            val warnings = verifyCloud(client, checks)
            val lights = ok.isNotEmpty() && ok.all { AutoDevices.kind(it, StateCache.load(context, it.id) ?: emptyMap()) == AutoKind.LIGHT }
            val msg = buildString {
                append(prefix)
                if (ok.isNotEmpty()) append(if (lights) " – zhaslo ${countText(ok.size, true)}" else " – vypnuté ${countText(ok.size, false)}")
                    .append(": ").append(ok.joinToString { it.name })
                if (failed.isNotEmpty()) append(if (ok.isEmpty()) " – nepodarilo sa zhasnúť: " else ". Nepodarilo sa zhasnúť: ")
                    .append(failed.entries.joinToString { "${it.key} (${it.value})" })
                if (ok.isEmpty() && failed.isEmpty()) append(" – nebolo čo vypnúť")
                if (skipped.isNotEmpty()) append(". Preskočené: ").append(skipped.entries.joinToString { "${it.key} (${it.value})" })
                append('.')
                warnings.forEach { append(" POZOR: ").append(it) }
            }
            PresenceStore.log(context, msg)
            if (warnings.isNotEmpty()) PresenceNotify.event(context, "Pozor – skontroluj spotrebič", msg, kind = PresenceNotify.Kind.WARNING)
            else if (failed.isNotEmpty()) PresenceNotify.event(context, "Nepodarilo sa zhasnúť", msg, kind = PresenceNotify.Kind.FAILED)
            else if (!manual && cfg.notifyDone) PresenceNotify.event(context, PresenceNotify.departTitle(context, prefix), msg)
            msg
        }
    }

    /** Rizikové zariadenie na overenie po odchode: čo sme poslali (podľa DP) a stav pred tým. */
    private class CloudCheck(val device: Device, val sent: Map<String, Any>, val before: Map<String, Any>)

    private sealed class CloudOffResult {
        class Sent(val check: CloudCheck?) : CloudOffResult()
        class Idle(val check: CloudCheck?) : CloudOffResult()
        class Skipped(val reason: String) : CloudOffResult()
    }

    /**
     * Vypne zariadenie cez cloud. Svetlá a zásuvky: kód zo schémy, inak bežné kódy. Fritéza, ohrievač a neznáme
     * zariadenia: podľa čerstvého stavu z cloudu (SafeOff) – fritéza len Stop, keď varí; inak preskočiť.
     */
    private fun turnOff(client: TuyaCloudClient, context: Context, device: Device, source: String): CloudOffResult {
        var d = device
        val cached = StateCache.load(context, d.id) ?: emptyMap()
        if (d.schema.isEmpty()) {
            client.mapping(CloudDevice(d.devId, d.name, ""))?.let { m ->
                val schema = Schema.parseMapping(m)
                if (schema.isNotEmpty()) d = d.copy(schema = schema)
            }
        }
        val risky = SafeOff.risky(d, cached)
        // čerstvý stav z cloudu (pri rizikových povinný)
        val live = try {
            SafeOff.shadowValues(client.shadow(d.devId))
        } catch (e: CloudException) {
            if (e.code in TuyaCloudClient.AUTH_ERRORS) throw e
            emptyMap()
        } catch (e: IOException) {
            if (risky) throw e
            emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
        val fresh = live.isNotEmpty()
        val values = if (fresh) live else cached
        val plan = SafeOff.cloudOff(d, values, fresh)
        val isRisky = risky || SafeOff.risky(d, values)
        val sentDps = if (isRisky) (SafeOff.offStep(d, values, fresh) as? OffStep.Send)?.cmd ?: emptyMap() else emptyMap()
        val check = if (isRisky && fresh) CloudCheck(d, sentDps, values) else null
        plan.skip?.let { return CloudOffResult.Skipped(it) }
        if (plan.options.isEmpty()) return CloudOffResult.Idle(check)
        var last: CloudException? = null
        for (cmds in plan.options) {
            try {
                client.sendCommands(d.devId, cmds)
                // zapamätať nový stav (vypnuté), aby appka a widget neukazovali zapnuté, kým telefón nie je na WiFi
                runCatching { cacheAfterCloudOff(context, d, values, cmds, source) }
                return CloudOffResult.Sent(check)
            } catch (e: CloudException) {
                if (e.code in TuyaCloudClient.AUTH_ERRORS) throw e
                last = e
            }
        }
        throw last ?: CloudException(-1, "neznáma chyba")
    }

    /** Uloží do StateCache hodnoty po vypnutí cez cloud (kódy → DP podľa schémy; bez schémy aspoň hlavný vypínač = vypnuté). */
    private fun cacheAfterCloudOff(context: Context, d: Device, base: Map<String, Any>, cmds: List<Pair<String, Any>>, source: String) {
        val dps = cmds.mapNotNull { (code, v) -> d.schema.entries.firstOrNull { it.value.code == code }?.key?.let { it to v } }.toMap()
        val updated = when {
            dps.isNotEmpty() -> base + dps
            else -> powerDpOf(d, base)?.let { base + (it to false) } ?: return
        }
        StateCache.save(context, d.id, updated, source)
    }

    /** Po odchode znova prečíta stav rizikových zariadení; ak sa zaplo, pošle Stop / vypnutie. Vráti varovania. */
    private suspend fun verifyCloud(client: TuyaCloudClient, checks: List<CloudCheck>): List<String> {
        if (checks.isEmpty()) return emptyList()
        delay(3_000)
        val warnings = mutableListOf<String>()
        for (c in checks) {
            try {
                val after = SafeOff.shadowValues(client.shadow(c.device.devId))
                if (after.isEmpty()) continue
                val rescue = SafeOff.verify(c.device, c.sent, c.before, after) ?: continue
                val codes = rescue.cmd.mapNotNull { (dp, v) -> c.device.schema[dp]?.code?.let { it to v } }
                val sent = codes.isNotEmpty() && runCatching { client.sendCommands(c.device.devId, codes) }.isSuccess
                warnings += if (sent) rescue.warning else "${c.device.name}: zariadenie je zapnuté a nepodarilo sa ho zastaviť – vypni ho ručne!"
            } catch (e: Exception) {
                // overenie je len poistka; chyba nesmie zhodiť odchod
            }
        }
        return warnings
    }

    /** Kontrola poistky: zapnutým zariadeniam obnoví odpočet, vypnutým zruší náš odpočet. */
    suspend fun guardTick(context: Context, network: Network?, force: Boolean = false) = withContext(Dispatchers.IO) {
        val cfg = PresenceStore.config(context)
        if (!cfg.enabled || !cfg.guardEnabled || cfg.guardIds.isEmpty()) return@withContext
        if (PresenceStore.status(context).state != PresenceState.HOME) return@withContext
        val net = network ?: wifiNetwork(context) ?: return@withContext
        val now = System.currentTimeMillis()
        for (d in DeviceStore(context).load().filter { it.id in cfg.guardIds }) {
            val v = try {
                DeviceClient.readState(net, d).also { StateCache.save(context, d.id, it) }
            } catch (e: Exception) {
                continue
            }
            val (dp, minutes) = AutoDevices.countdownDp(d, v) ?: continue
            val isOn = powerDpOf(d, v)?.let { v[it] as? Boolean }
            val last = PresenceStore.guardSet(context, d.id)
            val refresh = if (force) 0L else cfg.guardRefreshMin * 60_000L
            when (Guard.decide(isOn, last > 0, last, now, refresh)) {
                GuardStep.SET -> runCatching { DeviceClient.setValues(net, d, Guard.command(dp, minutes, cfg.guardSeconds)) }
                    .onSuccess { PresenceStore.setGuardSet(context, d.id, now) }
                GuardStep.CLEAR -> runCatching { DeviceClient.setValues(net, d, mapOf(dp to 0)) }
                    .onSuccess { PresenceStore.setGuardSet(context, d.id, 0L) }
                GuardStep.NONE -> {}
            }
        }
    }

    /** Zruší všetky odpočty poistky (pri vypnutí poistky alebo automatiky). Vráti počet zrušených. */
    suspend fun guardClearAll(context: Context): Int = withContext(Dispatchers.IO) {
        val armed = PresenceStore.armedGuards(context)
        if (armed.isEmpty()) return@withContext 0
        val net = wifiNetwork(context) ?: return@withContext 0
        var n = 0
        for (d in DeviceStore(context).load().filter { it.id in armed }) {
            val v = StateCache.load(context, d.id) ?: emptyMap()
            val cd = AutoDevices.countdownDp(d, v)
            if (cd == null) {
                PresenceStore.setGuardSet(context, d.id, 0L)
                continue
            }
            val dp = cd.first
            if (runCatching { DeviceClient.setValues(net, d, mapOf(dp to 0)) }.isSuccess) {
                PresenceStore.setGuardSet(context, d.id, 0L)
                n++
            }
        }
        if (n > 0) PresenceStore.log(context, "Poistka vypnutá – zrušené odpočty: $n.")
        n
    }
}

// ================================================================== notifikácie

object PresenceNotify {
    const val CH_SERVICE = "presence_service"
    const val CH_EVENTS = "presence_events"
    const val ID_SERVICE = 4101
    const val ID_STAY = 4199
    /** Bežné „Vitaj doma“ / „Odišiel si“ – vždy len posledné (nové nahradí staré, nehromadia sa). */
    const val ID_ROUTINE = 4200
    /** „Nepodarilo sa zhasnúť“ – posledné zlyhanie (oddelené, aby ho bežná správa neprepísala). */
    const val ID_FAILED = 4201
    private var nextId = 4300

    /** Druh udalosti: ROUTINE a FAILED sa nahrádzajú, WARNING (spotrebič) má vždy vlastnú notifikáciu. */
    enum class Kind { ROUTINE, FAILED, WARNING }

    /** Posledná zobrazená udalosť (text a čas) – rovnaká správa sa do 10 min nezobrazí znova. */
    private var lastText: String? = null
    private var lastAt = 0L
    private const val SAME_TEXT_MS = 10 * 60_000L

    /** Posledný text trvalej notifikácie služby; pri zhode ju neprekresľujeme (Android 14+ by ju znova ukázal). */
    @Volatile private var lastServiceText: String? = null

    fun shouldUpdateService(text: String): Boolean = shouldPostServiceNotification(lastServiceText, text)

    fun clearServiceText() { lastServiceText = null }

    /** Nadpis pri príchode: „Vitaj doma, Tomáš“ (meno z Automatika › Domácnosť), bez mena „Vitaj doma“. */
    fun arriveTitle(context: Context): String =
        HouseholdStore.customName(context)?.let { "Vitaj doma, $it" } ?: "Vitaj doma"

    /** Nadpis pri automatickom odchode: „Dovidenia, Tomáš“, bez mena pôvodný text ([fallback]). */
    fun departTitle(context: Context, fallback: String): String =
        HouseholdStore.customName(context)?.let { "Dovidenia, $it" } ?: fallback

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Príchod a odchod (beží na pozadí)", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Tichá trvalá notifikácia, bez nej Android automatiku na pozadí zastaví."
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENTS, "Príchod a odchod – udalosti", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Čo automatika zapla alebo vypla a čo sa nepodarilo."
            },
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun service(context: Context, text: String): Notification {
        lastServiceText = text
        return NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_bulb)
            .setContentTitle("Príchod a odchod")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setLocalOnly(true)
            .setContentIntent(openApp(context))
            .build()
    }

    fun event(context: Context, title: String, text: String, forceOffAction: Boolean = false, kind: Kind = Kind.ROUTINE) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            val key = "$kind|$title|$text"
            if (key == lastText && now - lastAt < SAME_TEXT_MS) return
            lastText = key
            lastAt = now
        }
        channels(context)
        val b = NotificationCompat.Builder(context, CH_EVENTS)
            .setSmallIcon(R.drawable.ic_bulb)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
        // bežná správa, ktorá nahrádza predchádzajúcu ešte nezavretú: bez ďalšieho zvuku
        if (kind == Kind.ROUTINE && !forceOffAction) b.setOnlyAlertOnce(true)
        if (forceOffAction) {
            val pi = PendingIntent.getBroadcast(context, 77, Intent(context, PresenceReceiver::class.java).setAction(PresenceService.ACTION_FORCE_OFF),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(0, "Aj tak zhasnúť", pi)
        }
        val n = b.build()
        try {
            val id = when {
                forceOffAction -> ID_STAY
                kind == Kind.ROUTINE -> ID_ROUTINE
                kind == Kind.FAILED -> ID_FAILED
                else -> nextId++
            }
            context.getSystemService(NotificationManager::class.java)?.notify(id, n)
        } catch (e: SecurityException) {
            // bez povolenia notifikácií – udalosť je aspoň v histórii
        }
    }

    fun cancel(context: Context, id: Int) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(id) }
    }
}

// ================================================================== služba na pozadí

/**
 * Služba v popredí (tichá trvalá notifikácia), ktorá sleduje WiFi cez ConnectivityManager.NetworkCallback.
 * Typ connectedDevice: appka ovláda zariadenia v domácej sieti (Android 14+ to vyžaduje uviesť).
 */
class PresenceService : Service() {
    /** Chyba v korutine služby appku nezhodí; zapíše sa do logu aj do histórie (aby bolo vidieť, prečo automatika nereagovala). */
    private val serviceErrors = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        android.util.Log.w("Domov", "chyba v automatike", e)
        runCatching { PresenceStore.log(this, "Chyba automatiky: ${e.javaClass.simpleName}${e.message?.let { ": " + it.take(120) } ?: ""}") }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + serviceErrors)
    private val mutex = Mutex()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val checks = HashMap<Network, Job>()
    private val fingerprints = HashMap<Network, HomeNetwork>()
    /** Siete, ktoré vyzerajú ako domáca (sedí router a rozsah), aj keď ešte neodpovedalo žiadne zariadenie. */
    private val homeLike = HashSet<Network>()
    @Volatile private var homeNet: Network? = null
    private var tickJob: Job? = null
    private var guardJob: Job? = null
    private var startupJob: Job? = null
    private var fryerJob: Job? = null
    // domácnosť (viac telefónov)
    @Volatile private var houseNet: Network? = null
    @Volatile private var houseBroadcast: String = "255.255.255.255"
    private var listenJob: Job? = null
    private var beatJob: Job? = null
    @Volatile private var lastReply = 0L
    /** Domáca sieť, na ktorej by mohla bežať domácnosť (aj keď je domácnosť práve vypnutá, lebo nemá ďalších členov). */
    @Volatile private var houseCandidate: Pair<Network, String>? = null
    /** Kedy naposledy prebehla kontrola poistky (aby korutina a záložný budík nečítali zariadenia dvakrát). */
    @Volatile private var lastGuardRun = 0L
    /** Služba je v popredí (po úspešnom startForeground). */
    @Volatile private var foregroundStarted = false
    /** Práve sa zastavuje – sync() ju nesmie považovať za bežiacu a musí ju spustiť znova cez startForegroundService. */
    @Volatile internal var stopping = false
        private set
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // človek práve použil telefón – hneď oznámiť „som doma“ (telefón v spánku posiela zriedka)
            sendBeat(MsgType.BEAT)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        PresenceNotify.channels(this)
        runCatching { registerReceiver(screenOn, android.content.IntentFilter(Intent.ACTION_SCREEN_ON)) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Po startForegroundService musí startForeground prebehnúť v tomto onStartCommand (Android 8+ / 14 FGS timeout).
        stopping = false
        val notification = PresenceNotify.service(this, statusText(PresenceStore.status(this)))
        try {
            ServiceCompat.startForeground(this, PresenceNotify.ID_SERVICE, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            foregroundStarted = true
        } catch (e: Exception) {
            PresenceStore.log(this, "Android nedovolil spustiť automatiku na pozadí (${e.javaClass.simpleName}). Otvor appku.")
            stopSelf()
            return START_NOT_STICKY
        }
        val cfg = PresenceStore.config(this)
        if (!cfg.enabled || cfg.home == null) {
            stopping = true
            foregroundStarted = false
            PresenceNotify.clearServiceText()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (callback == null) {
            PresenceStore.log(this, when (intent?.action) {
                null -> "Automatika znova spustená (Android ju reštartoval)."
                ACTION_RELOAD -> "Automatika spustená."
                else -> "Automatika spustená budíkom."
            })
            try {
                register()
            } catch (e: Exception) {
                PresenceStore.log(this, "Sledovanie WiFi sa nepodarilo spustiť (${e.javaClass.simpleName}). Otvor appku.")
            }
        }
        runCatching { ensureFryerJob() }
        when (intent?.action) {
            ACTION_TICK -> scope.launch { onEvent(PresenceEvent.Tick(System.currentTimeMillis())) }
            ACTION_GUARD -> scope.launch { runGuardFromAlarm() }
            ACTION_BEAT -> { sendBeat(MsgType.BEAT); scheduleBeatAlarm() }
            ACTION_RELOAD -> scope.launch {
                ensureHousehold()
                mutex.withLock { reschedule(PresenceStore.status(this@PresenceService)) }
            }
        }
        return START_STICKY
    }

    /**
     * Sledovanie fritéz (upozornenia): doma lokálne, mimo domu cez cloud; interval podľa stavu varenia.
     * Beží len vtedy, keď je nejaká fritéza so zapnutými upozorneniami a appka smie zobrazovať notifikácie.
     * Na pozadí sa pýta najviac raz za minútu (rýchlejšie obnovuje len otvorená obrazovka zariadenia).
     */
    private fun ensureFryerJob() {
        if (fryerJob?.isActive == true) return
        if (!fryerWanted(this)) { watchesFryers = false; return }
        watchesFryers = true
        fryerJob = scope.launch {
            delay(5_000)
            try {
                while (isActive) {
                    if (!fryerWanted(this@PresenceService)) break
                    val next = try { FryerMonitor.tick(this@PresenceService, homeNet, background = true) } catch (e: Exception) { 60_000L }
                    delay(next.coerceIn(5_000L, 600_000L))
                }
            } finally {
                watchesFryers = false
            }
        }
    }

    override fun onDestroy() {
        stopping = true
        foregroundStarted = false
        instance = null
        PresenceNotify.clearServiceText()
        watchesFryers = false
        runCatching { unregisterReceiver(screenOn) }
        stopHousehold()
        cancelAlarm(ACTION_BEAT)
        callback?.let { runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
        callback = null
        cancelAlarm(ACTION_TICK)
        cancelAlarm(ACTION_GUARD)
        scope.cancel()
        super.onDestroy()
    }

    private fun register() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = check(network)

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                // adresa / router sa zmenili (napr. DHCP dobehol neskôr) – skontrolovať znova
                val fp = HomeNet.fingerprint(this@PresenceService, network)
                synchronized(checks) {
                    if (fp != null && fingerprints[network]?.let { it.gateway == fp.gateway && it.subnet == fp.subnet } != true) check(network)
                }
            }

            override fun onLost(network: Network) {
                val wasHomeLike = synchronized(checks) {
                    checks.remove(network)?.cancel()
                    fingerprints.remove(network)
                    homeLike.remove(network)
                }
                if (houseCandidate?.first == network) houseCandidate = null
                if (network == houseNet) stopHousehold()
                if (network == homeNet || (homeNet == null && wasHomeLike)) {
                    homeNet = null
                    scope.launch { onEvent(PresenceEvent.HomeLost(System.currentTimeMillis())) }
                }
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(request, cb)
        callback = cb
        // po štarte (napr. po reštarte telefónu): ak sa do 40 s domáca sieť neobjaví, telefón nie je doma
        startupJob = scope.launch {
            delay(40_000)
            val busy = synchronized(checks) { checks.values.any { it.isActive } || homeLike.isNotEmpty() }
            if (homeNet == null && !busy) onEvent(PresenceEvent.HomeLost(System.currentTimeMillis()))
        }
    }

    /** Skontroluje WiFi sieť: sedí odtlačok domácej siete a odpovedá niektoré zariadenie? */
    private fun check(network: Network) {
        synchronized(checks) {
            checks.remove(network)?.cancel()
            checks[network] = scope.launch {
                val home = PresenceStore.config(this@PresenceService).home ?: return@launch
                // adresa a router nemusia byť hneď po pripojení
                var fp: HomeNetwork? = null
                for (i in 0 until 10) {
                    fp = HomeNet.fingerprint(this@PresenceService, network)
                    if (fp != null) break
                    delay(1000)
                }
                if (fp == null) return@launch
                synchronized(checks) { fingerprints[network] = fp }
                if (!home.matches(fp)) return@launch
                synchronized(checks) { homeLike += network }
                houseCandidate = network to fp.broadcast
                ensureHousehold()
                for (wait in listOf(0L, 10_000L, 30_000L, 60_000L, 120_000L)) {
                    delay(wait)
                    val answered = HomeNet.verify(this@PresenceService, network)
                    if (answered != null) {
                        homeNet = network
                        onEvent(PresenceEvent.HomeSeen(System.currentTimeMillis()))
                        return@launch
                    }
                }
                // žiadne zariadenie neodpovedá; ak Android prezradil zhodné SSID, stačí to
                if (home.ssid.isNotBlank() && home.ssid == fp.ssid) {
                    homeNet = network
                    onEvent(PresenceEvent.HomeSeen(System.currentTimeMillis()))
                } else if (PresenceStore.status(this@PresenceService).state != PresenceState.HOME) {
                    PresenceStore.log(this@PresenceService, "Sieť vyzerá ako domáca (${fp.gateway}), ale neodpovedalo žiadne zariadenie – príchod sa nevyhodnotil.")
                }
            }
        }
    }

    private suspend fun onEvent(e: PresenceEvent) {
        val ctx = this
        var before = PresenceStatus()
        val action = mutex.withLock {
            val cfg = PresenceStore.config(ctx)
            val old = PresenceStore.status(ctx)
            before = old
            val step = PresenceMachine.step(old, e, cfg.departDelayMs)
            if (step.status != old) {
                PresenceStore.saveStatus(ctx, step.status)
                when {
                    old.state == PresenceState.LEAVING && step.status.state == PresenceState.HOME ->
                        PresenceStore.log(ctx, "Krátky výpadok domácej WiFi (${(e.time - old.since) / 1000} s) – nič sa nerobí.")
                    step.status.state == PresenceState.LEAVING ->
                        PresenceStore.log(ctx, "Odpojený od domácej WiFi – ak sa do ${cfg.departDelayMin} min nevrátiš, vypnem vybrané zariadenia.")
                    old.state == PresenceState.UNKNOWN ->
                        PresenceStore.log(ctx, "Automatika beží – " + if (step.status.state == PresenceState.HOME) "si doma." else "nie si doma.")
                }
            }
            reschedule(step.status)
            step.action
        }
        // Stav je už uložený (napr. AWAY) – príchod / odchod sa musí dokončiť, aj keby volajúcu korutinu niekto zrušil.
        withContext(NonCancellable) { runAction(ctx, e, before, action) }
    }

    private suspend fun runAction(ctx: PresenceService, e: PresenceEvent, before: PresenceStatus, action: PresenceAction?) {
        val now = PresenceStore.status(ctx)
        if (e is PresenceEvent.HomeSeen && before.state != PresenceState.HOME && now.state == PresenceState.HOME) {
            // som doma (znova): „Aj tak zhasnúť“ z odchodu už neplatí (zhasol by doma), zmazať ho
            PresenceNotify.cancel(ctx, PresenceNotify.ID_STAY)
            // oznámiť ostatným a opýtať sa, kto je doma
            houseNet?.let { HouseholdNet.send(ctx, it, MsgType.QUERY, houseBroadcast) }
        }
        when (action) {
            PresenceAction.ARRIVE -> {
                val cfg = PresenceStore.config(ctx)
                // počkať na odpovede ostatných telefónov; počítajú sa len správy prijaté po návrate
                if (houseNet != null) delay(4000)
                val others = HouseDecision.alreadyHome(HouseholdStore.members(ctx), HouseholdStore.phoneId(ctx), before.since)
                if (others.isNotEmpty() && !cfg.arriveIfSomeoneHome) {
                    PresenceStore.log(ctx, "Príchod domov – doma už " + (if (others.size == 1) "je " else "sú ") +
                        HouseDecision.names(others) + ", nič nezapínam.")
                    PresenceActions.guardTick(ctx, homeNet, force = true)
                } else {
                    PresenceActions.arrive(ctx, homeNet, manual = false)
                }
            }
            PresenceAction.DEPART -> {
                val cfg = PresenceStore.config(ctx)
                // odchod: rozhoduje posledná známa informácia spred odpojenia (pri odchode už telefón nič nepošle ani neprijme)
                val stay = HouseDecision.stayingHome(HouseholdStore.members(ctx), HouseholdStore.phoneId(ctx), before.since, cfg.memberWindowMs)
                if (stay.isNotEmpty()) {
                    val msg = HouseDecision.stayText(stay) + " – nezhasínam."
                    PresenceStore.log(ctx, msg)
                    if (cfg.notifyDone) PresenceNotify.event(ctx, PresenceNotify.departTitle(ctx, "Odišiel si"), "$msg Ak už doma nikto nie je, ťukni na Aj tak zhasnúť.", forceOffAction = true)
                } else {
                    PresenceActions.depart(ctx, manual = false)
                }
            }
            null -> {}
        }
        updateNotification()
    }

    /** Naplánuje kontrolu odchodu a poistku podľa stavu. */
    private fun reschedule(s: PresenceStatus) {
        val cfg = PresenceStore.config(this)
        tickJob?.cancel()
        val due = PresenceMachine.dueAt(s, cfg.departDelayMs)
        if (due != null) {
            tickJob = scope.launch {
                delay((due - System.currentTimeMillis()).coerceAtLeast(0) + 500)
                // Tick v samostatnej korutine: onEvent → reschedule() volá tickJob?.cancel(), čo by inak zrušilo
                // práve bežiaci Tick – stav by sa prepol na „mimo domu“, ale vypnutie (depart) by sa už nespustilo.
                scope.launch { onEvent(PresenceEvent.Tick(System.currentTimeMillis())) }
            }
            // záloha, keby telefón zaspal (Doze) – Android ju spustí najneskôr o pár minút
            setAlarm(ACTION_TICK, due + 1000)
        } else {
            cancelAlarm(ACTION_TICK)
        }
        guardJob?.cancel()
        if (s.state == PresenceState.HOME && cfg.guardEnabled && cfg.guardIds.isNotEmpty()) {
            // Poistka: kontrola raz za guardRefreshMin (predtým každú minútu, hoci príkazy idú až po tomto intervale).
            // Korutina je hlavný časovač, kým telefón nespí; budík ACTION_GUARD je len záloha pre Doze a po každej
            // kontrole sa posunie, takže sa zariadenia nečítajú dvakrát.
            guardJob = scope.launch {
                runGuard(skipIfWithinMs = 60_000L)
                while (isActive) {
                    delay(guardIntervalMs(cfg))
                    runGuard(skipIfWithinMs = 0L)
                }
            }
            scheduleGuardAlarm()
        } else {
            cancelAlarm(ACTION_GUARD)
        }
        if (s.state == PresenceState.HOME && houseNet != null) scheduleBeatAlarm() else cancelAlarm(ACTION_BEAT)
        updateNotification()
    }

    private fun guardIntervalMs(cfg: PresenceConfig): Long = cfg.guardRefreshMin.coerceAtLeast(1) * 60_000L

    /** Jedna kontrola poistky; preskočí sa, ak posledná prebehla pred menej ako [skipIfWithinMs]. */
    private suspend fun runGuard(skipIfWithinMs: Long) {
        val now = System.currentTimeMillis()
        if (skipIfWithinMs > 0 && now - lastGuardRun < skipIfWithinMs) return
        lastGuardRun = now
        PresenceActions.guardTick(this@PresenceService, homeNet)
        scheduleGuardAlarm()
    }

    /** Záložný budík poistky: kontrola len vtedy, ak ju korutina medzitým nespravila (telefón spal). */
    private suspend fun runGuardFromAlarm() {
        val cfg = PresenceStore.config(this)
        if (System.currentTimeMillis() - lastGuardRun < guardIntervalMs(cfg) - 20_000L) {
            scheduleGuardAlarm()
            return
        }
        runGuard(skipIfWithinMs = 0L)
    }

    private fun scheduleGuardAlarm() {
        val cfg = PresenceStore.config(this)
        if (cfg.guardEnabled && PresenceStore.status(this).state == PresenceState.HOME) {
            setAlarm(ACTION_GUARD, System.currentTimeMillis() + cfg.guardRefreshMin * 60_000L)
        }
    }

    private fun scheduleBeatAlarm() {
        if (houseNet == null) { cancelAlarm(ACTION_BEAT); return }
        if (PresenceStore.status(this).state == PresenceState.HOME) {
            setAlarm(ACTION_BEAT, System.currentTimeMillis() + beatAlarmMs(PresenceStore.config(this)))
        }
    }

    /**
     * Domácnosť (UDP „som doma“ + MulticastLock) beží len vtedy, keď má zmysel: telefón má ďalších členov
     * alebo nedávno zdieľal / prevzal kľúč domácnosti (aby sa nový člen mohol ohlásiť). Inak sa nič neposiela
     * ani nepočúva a WiFi môže šetriť energiu.
     */
    private fun ensureHousehold() {
        val cand = houseCandidate
        if (cand != null && HouseholdStore.active(this)) {
            startHousehold(cand.first, cand.second)
        } else if (houseNet != null) {
            stopHousehold()
            cancelAlarm(ACTION_BEAT)
        }
    }

    // ------------------------------------------------ domácnosť

    private fun startHousehold(network: Network, broadcast: String) {
        if (houseNet == network && listenJob?.isActive == true) return
        stopHousehold()
        houseNet = network
        houseBroadcast = broadcast
        listenJob = scope.launch {
            HouseholdNet.listen(this@PresenceService, network) { msg ->
                if (msg.type == MsgType.QUERY) {
                    val now = System.currentTimeMillis()
                    if (now - lastReply > 3000) {
                        lastReply = now
                        sendBeat(MsgType.BEAT)
                    }
                }
            }
        }
        beatJob = scope.launch {
            while (isActive) {
                delay(beatMs(PresenceStore.config(this@PresenceService)))
                sendBeat(MsgType.BEAT)
            }
        }
        scheduleBeatAlarm()
    }

    private fun stopHousehold() {
        listenJob?.cancel(); listenJob = null
        beatJob?.cancel(); beatJob = null
        houseNet = null
    }

    /** Pošle „som doma“, ak je telefón doma (na overenej domácej WiFi). */
    private fun sendBeat(type: MsgType) {
        val net = houseNet ?: return
        if (homeNet == null || PresenceStore.status(this).state != PresenceState.HOME) return
        scope.launch { HouseholdNet.send(this@PresenceService, net, type, houseBroadcast) }
    }

    private fun alarmIntent(action: String): PendingIntent = PendingIntent.getBroadcast(
        this, action.hashCode(), Intent(this, PresenceReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun setAlarm(action: String, at: Long) {
        val am = getSystemService(AlarmManager::class.java) ?: return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(action))
    }

    private fun cancelAlarm(action: String) {
        getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(action))
    }

    private fun statusText(s: PresenceStatus): String =
        presenceServiceText(s.state, PresenceStore.config(this).guardEnabled)

    private fun updateNotification() {
        val text = statusText(PresenceStore.status(this))
        // rovnaký text: nevolať notify() – na Androide 14+ by sa odsunutá notifikácia znova objavila
        if (!PresenceNotify.shouldUpdateService(text)) return
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(PresenceNotify.ID_SERVICE, PresenceNotify.service(this, text))
        } catch (e: SecurityException) {
        }
    }

    /** Obnova v už bežiacej službe bez startForegroundService (to by znova vyvesilo odsunutú notifikáciu). */
    internal fun reloadWhileRunning() {
        runCatching { ensureFryerJob() }
        scope.launch {
            ensureHousehold()
            mutex.withLock { reschedule(PresenceStore.status(this@PresenceService)) }
        }
    }

    internal fun markStopping() { stopping = true }

    internal fun onAlarm(action: String) {
        runCatching { ensureFryerJob() }
        when (action) {
            ACTION_TICK -> scope.launch { onEvent(PresenceEvent.Tick(System.currentTimeMillis())) }
            ACTION_GUARD -> scope.launch { runGuardFromAlarm() }
            ACTION_BEAT -> { sendBeat(MsgType.BEAT); scheduleBeatAlarm() }
            ACTION_FORCE_OFF -> scope.launch { PresenceActions.depart(this@PresenceService, manual = false) }
        }
    }

    companion object {
        const val ACTION_TICK = "sk.domov.ovladanie.PRESENCE_TICK"
        const val ACTION_GUARD = "sk.domov.ovladanie.PRESENCE_GUARD"
        const val ACTION_RELOAD = "sk.domov.ovladanie.PRESENCE_RELOAD"
        const val ACTION_BEAT = "sk.domov.ovladanie.PRESENCE_BEAT"
        const val ACTION_FORCE_OFF = "sk.domov.ovladanie.PRESENCE_FORCE_OFF"
        /**
         * „Som doma“: kým telefón nespí, najviac raz za 4 min (predtým 45 s), ale vždy aspoň 3× za okno
         * „ozval sa za posledných X min“, aby ostatné telefóny nepovažovali tento telefón za preč.
         */
        const val BEAT_MS = 4 * 60_000L
        private const val BEAT_MIN_MS = 45_000L

        /**
         * Záložný budík (Doze): predtým každých 5 min. Teraz o minútu kratší ako okno člena (predvolene 10 min → 9 min),
         * najviac 15 min. Pri okne 20 min a viac sa telefón budí len raz za 15 min.
         */
        fun beatAlarmMs(cfg: PresenceConfig): Long = (cfg.memberWindowMs - 60_000L).coerceIn(5 * 60_000L, 15 * 60_000L)

        fun beatMs(cfg: PresenceConfig): Long = minOf(BEAT_MS, cfg.memberWindowMs / 3).coerceAtLeast(BEAT_MIN_MS)

        @Volatile internal var instance: PresenceService? = null

        /** Sleduje služba práve fritézy? (inak ich počas otvorenej appky obnovuje HomeViewModel) */
        @Volatile var watchesFryers: Boolean = false
            private set

        fun fryerWanted(context: Context): Boolean =
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                FryerMonitor.fryers(context).any { FryerStore.settings(context, it.id).enabled }

        val isRunning: Boolean get() = instance != null

        /** Spustí alebo zastaví službu podľa nastavení. Volá sa po zmene nastavení, po reštarte a po aktualizácii appky. */
        fun sync(context: Context) {
            val cfg = PresenceStore.config(context)
            val intent = Intent(context, PresenceService::class.java)
            if (cfg.enabled && cfg.home != null) {
                val running = instance
                // už beží v popredí: nespúšťať znova cez startForegroundService (onStartCommand by musel
                // znova volať startForeground a odsunutá notifikácia by sa vrátila). Pri zastavovaní naopak
                // musí ísť klasický štart, inak by sa služba po vypnutí a rýchlom zapnutí nespustila.
                if (running != null && running.foregroundStarted && !running.stopping) {
                    running.reloadWhileRunning()
                    return
                }
                try {
                    ContextCompat.startForegroundService(context, intent.setAction(ACTION_RELOAD))
                } catch (e: Exception) {
                    PresenceStore.log(context, "Automatiku sa nepodarilo spustiť (${e.javaClass.simpleName}). Otvor appku.")
                }
            } else {
                instance?.markStopping()
                context.stopService(intent)
            }
        }
    }
}

/** Spustenie po reštarte telefónu / aktualizácii appky a budíky (záloha časovačov, keď telefón spí). */
class PresenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val cfg = runCatching { PresenceStore.config(context) }.getOrNull()
                if (cfg != null && cfg.enabled && cfg.home != null) {
                    PresenceStore.log(context, if (intent.action == Intent.ACTION_BOOT_COMPLETED) "Telefón sa zapol – spúšťam automatiku."
                        else "Appka sa aktualizovala – spúšťam automatiku.")
                }
                BackupManager.recover(context)
                PresenceService.sync(context)
                BackupManager.schedule(context)
            }
            PresenceService.ACTION_TICK, PresenceService.ACTION_GUARD, PresenceService.ACTION_BEAT -> {
                val svc = PresenceService.instance
                if (svc != null) svc.onAlarm(intent.action!!) else PresenceService.sync(context)
            }
            PresenceService.ACTION_FORCE_OFF -> {
                context.getSystemService(NotificationManager::class.java)?.cancel(PresenceNotify.ID_STAY)
                val svc = PresenceService.instance
                if (svc != null) svc.onAlarm(PresenceService.ACTION_FORCE_OFF) else {
                    val pending = goAsync()
                    CoroutineScope(Dispatchers.IO + LogOnError).launch {
                        try { PresenceActions.depart(context, manual = false) } finally { pending.finish() }
                    }
                }
            }
        }
    }
}
