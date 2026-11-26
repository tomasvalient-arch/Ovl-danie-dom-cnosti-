package sk.domov.ovladanie

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Nastavenie ranného prehľadu: len v tomto telefóne (SharedPreferences „domov_brief“). Nie je v zálohe ani
 * v zdieľaní appky (tie berú len vymenované súbory), predvolene vypnuté.
 */
object MorningBriefStore {
    const val PREFS = "domov_brief"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TIME = "time"
    private const val KEY_SENT = "sent"
    private const val KEY_SENDING = "sending_at"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ENABLED, false)

    /** Čas v minútach od polnoci (predvolene 7:00). */
    fun time(c: Context): Int = prefs(c).getInt(KEY_TIME, MorningBrief.DEFAULT_TIME_MIN).coerceIn(0, 24 * 60 - 1)

    fun timeOfDay(c: Context): LocalTime = time(c).let { LocalTime.of(it / 60, it % 60) }

    fun lastSent(c: Context): LocalDate? = prefs(c).getString(KEY_SENT, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun markSent(c: Context, d: LocalDate) {
        prefs(c).edit().putString(KEY_SENT, d.toString()).apply()
    }

    fun sendingAt(c: Context): Long = prefs(c).getLong(KEY_SENDING, 0L)

    fun markSending(c: Context, at: Long) {
        prefs(c).edit().putLong(KEY_SENDING, at).apply()
    }

    fun clearSending(c: Context) {
        prefs(c).edit().remove(KEY_SENDING).apply()
    }

    fun setEnabled(c: Context, on: Boolean) {
        val e = prefs(c).edit().putBoolean(KEY_ENABLED, on)
        // po zapnutí sa dnešný prehľad nedobieha (prišiel by hneď, aj keď je 7:00 dávno preč); prvý príde v nastavený čas
        if (on) e.putString(KEY_SENT, LocalDate.now().let { if (LocalTime.now().isBefore(timeOfDay(c))) it.minusDays(1) else it }.toString())
        e.apply()
        MorningBriefAlarm.schedule(c)
    }

    fun setTime(c: Context, minutes: Int) {
        prefs(c).edit().putInt(KEY_TIME, minutes.coerceIn(0, 24 * 60 - 1)).apply()
        MorningBriefAlarm.schedule(c)
    }
}

/**
 * Ranný prehľad: budík (AlarmManager.setAndAllowWhileIdle, ako upozornenia na udalosti) raz denne v nastavený čas
 * pozbiera počasie, udalosti, meniny a zapnuté zariadenia a pošle jednu notifikáciu. Každý zdroj má vlastný časový
 * limit; keď zlyhá, prehľad príde bez neho. Budík sa obnoví po štarte telefónu, aktualizácii appky, zmene času
 * a pri každom otvorení appky.
 */
object MorningBriefAlarm {
    const val ACTION_ALARM = "sk.domov.ovladanie.MORNING_BRIEF"
    const val CHANNEL = "domov_brief"
    private const val NOTIFICATION_ID = 7400
    private const val TAG = "Domov"

    /** Celkový čas na zber údajov: počasie aj zariadenia sa zisťujú naraz (BroadcastReceiver smie bežať ~10 s). */
    private const val COLLECT_MS = 7_500L

    /** Zber beží mimo budíka: zariadenie, ktoré visí na sieťovom volaní, nezdrží notifikáciu (výsledok sa zahodí). */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun pending(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 7400, Intent(c, MorningBriefReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val sendLock = Any()

    /** Nastaví (alebo pri vypnutom prehľade zruší) budík na [at], inak na najbližší čas prehľadu. */
    fun schedule(c: Context, at: LocalDateTime? = null) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(c)
        if (!MorningBriefStore.enabled(c)) {
            am.cancel(pi)
            return
        }
        val whenAt = (at ?: MorningBrief.nextAlarm(LocalDateTime.now(), MorningBriefStore.timeOfDay(c)))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt, pi)
    }

    /** Ďalší pokus o [MorningBrief.RETRY_MIN] minút, kým sme v catch-up okne; inak bežný ranný budík. */
    private fun scheduleRetry(c: Context) {
        val retry = MorningBrief.nextRetry(LocalDateTime.now(), MorningBriefStore.timeOfDay(c), MorningBriefStore.lastSent(c))
        if (retry != null) schedule(c, retry) else schedule(c)
    }

    /**
     * Budík, štart telefónu alebo zmena času: ak je dnešný prehľad na rade (a ešte neodišiel), pošle ho.
     * „Odišiel“ sa zapíše až po úspešnej notifikácii; pri zlyhaní sa skúsi znova. [done] na konci (goAsync).
     * Widget Dnes sa pri kontrole prekreslí (aj po polnoci / zmene dátumu).
     */
    fun check(c: Context, done: () -> Unit = {}) {
        val app = c.applicationContext
        runCatching { DnesWidget.redrawAll(app) }
        val due = MorningBriefStore.enabled(app) &&
            MorningBrief.isDue(LocalDateTime.now(), MorningBriefStore.timeOfDay(app), MorningBriefStore.lastSent(app))
        if (!due) {
            runCatching { schedule(app) }
            done()
            return
        }
        val started = synchronized(sendLock) {
            if (!MorningBrief.canStartSend(System.currentTimeMillis(), MorningBriefStore.sendingAt(app))) false
            else {
                MorningBriefStore.markSending(app, System.currentTimeMillis())
                true
            }
        }
        if (!started) {
            done()
            return
        }
        // keby proces padol počas zberu, lastSent ešte nie je zapísaný — o 15 minút (v okne) sa skúsi znova
        runCatching { scheduleRetry(app) }
        work.launch {
            try {
                if (send(app)) {
                    MorningBriefStore.markSent(app, LocalDate.now())
                    runCatching { schedule(app) }
                } else {
                    runCatching { scheduleRetry(app) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ranný prehľad zlyhal", e)
                runCatching { scheduleRetry(app) }
            } finally {
                MorningBriefStore.clearSending(app)
                done()
            }
        }
    }

    /** Ukážka v Nastaveniach: dnešné meniny, udalosti a uložené počasie (bez siete a bez stavu zariadení). */
    fun preview(c: Context): BriefText {
        val today = LocalDate.now()
        return MorningBrief.compose(
            BriefContent(
                date = today,
                weather = runCatching { cachedWeather(c, today) }.getOrNull(),
                events = runCatching { EventsStore.visible(c) }.getOrDefault(emptyList()),
                shopLeft = runCatching {
                    val all = ShoppingStore.items(c)
                    ShoppingList.left(all)
                }.getOrNull(),
                shopMine = runCatching { ShoppingList.left(ShoppingStore.items(c), ShopListKind.MINE) }.getOrNull(),
                shopShared = runCatching { ShoppingList.left(ShoppingStore.items(c), ShopListKind.SHARED) }.getOrNull(),
            ),
        )
    }

    /** „Poslať skúšobne teraz“: pošle prehľad hneď, bez ohľadu na nastavenie a bez zápisu „dnes odišiel“. */
    suspend fun sendNow(c: Context): Boolean = send(c.applicationContext)

    /** Pozbiera údaje a pošle notifikáciu. Vráti false, ak sa notifikácia nedala zobraziť (vypnuté upozornenia). */
    private suspend fun send(c: Context): Boolean {
        val today = LocalDate.now()
        val weather = work.async { runCatching { todayWeather(c, today) }.getOrNull() }
        val devices = work.async { runCatching { deviceStates(c) }.getOrNull() }
        val events = runCatching { EventsStore.visible(c) }.getOrDefault(emptyList())
        val deadline = System.currentTimeMillis() + COLLECT_MS
        fun left() = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
        val w = awaitOrNull(weather, left())
        val content = BriefContent(
            date = today,
            weather = w ?: runCatching { cachedWeather(c, today) }.getOrNull(),
            events = events,
            devices = awaitOrNull(devices, left()),
            shopLeft = runCatching {
                    val all = ShoppingStore.items(c)
                    ShoppingList.left(all)
                }.getOrNull(),
                shopMine = runCatching { ShoppingList.left(ShoppingStore.items(c), ShopListKind.MINE) }.getOrNull(),
                shopShared = runCatching { ShoppingList.left(ShoppingStore.items(c), ShopListKind.SHARED) }.getOrNull(),
        )
        return notify(c, MorningBrief.compose(content))
    }

    private suspend fun <T> awaitOrNull(d: Deferred<T?>, ms: Long): T? = withTimeoutOrNull(ms) { d.await() }

    // ------------------------------------------------------------------ počasie

    private fun weatherLocation(c: Context): WeatherLocation? {
        val (lat, lon) = runCatching { PresenceStore.config(c).let { it.lat to it.lon } }.getOrDefault(null to null)
        return WeatherPolicy.location(WeatherStore.settings(c), lat, lon)
    }

    /** Dnešok z čerstvej predpovede (open-meteo); úspešné stiahnutie uloží aj do pamäte počasia appky. */
    private fun todayWeather(c: Context, today: LocalDate): WeatherDay? {
        val loc = weatherLocation(c) ?: return null
        val r = Http.send("GET", WeatherApi.forecastUrl(loc.lat, loc.lon), mapOf("User-Agent" to "Domov-Android"), connectMs = 5_000, readMs = 6_000)
        if (r.code != 200) return null
        val f = WeatherApi.parseForecast(r.body)
        WeatherStore.saveCache(c, WeatherCache(r.body, System.currentTimeMillis(), loc.lat, loc.lon))
        return f.days.firstOrNull { it.date == today.toString() }
    }

    /** Záloha bez internetu: uložená predpoveď, ak obsahuje dnešok pre to isté miesto. */
    private fun cachedWeather(c: Context, today: LocalDate): WeatherDay? {
        val loc = weatherLocation(c) ?: return null
        val cache = WeatherStore.cache(c)?.takeIf { WeatherPolicy.samePlace(it, loc) } ?: return null
        return WeatherApi.parseForecast(cache.raw).days.firstOrNull { it.date == today.toString() }
    }

    // ------------------------------------------------------------------ zariadenia

    /**
     * Čerstvý stav svetiel, zásuviek, klímy…: doma cez WiFi (lokálne), inak / čo neodpovie cez Tuya cloud (ak je
     * nastavený). Starý uložený stav sa nepoužíva – radšej zariadenie vynechať ako hlásiť nepravdu. null = nič na zistenie.
     */
    private suspend fun deviceStates(c: Context): BriefDevices? {
        val all = runCatching { DeviceStore(c).load() }.getOrDefault(emptyList())
        val targets = all.filter { MorningBrief.candidate(it, StateCache.load(c, it.id) ?: emptyMap()) }
        if (targets.isEmpty()) return null
        val fresh = java.util.concurrent.ConcurrentHashMap<String, Map<String, Any>>()
        val net = runCatching { wifiNetwork(c) }.getOrNull()
        if (net != null) {
            val jobs = targets.filter { d -> runCatching { Gateways.route(d).let { it.ip.isNotBlank() && it.localKey.isNotBlank() } }.getOrDefault(false) }.map { d ->
                work.async {
                    runCatching { DeviceClient.readState(net, d) }.getOrNull()?.let {
                        StateCache.save(c, d.id, it)
                        fresh[d.id] = it
                    }
                }
            }
            withTimeoutOrNull(4_000) { jobs.forEach { it.await() } }
        }
        val rest = targets.filter { !fresh.containsKey(it.id) && it.devId.isNotBlank() }
        val cloud = if (rest.isNotEmpty()) runCatching { CloudStore(c).load() }.getOrNull()?.takeIf { it.isComplete } else null
        if (cloud != null) {
            val client = TuyaCloudClient(cloud)
            val job = work.async {
                for (d in rest) {
                    val v = runCatching { SafeOff.shadowValues(client.shadow(d.devId)) }.getOrNull() ?: continue
                    if (v.isNotEmpty()) fresh[d.id] = v
                }
            }
            withTimeoutOrNull(2_500) { job.await() }
        }
        val on = ArrayList<String>()
        var unknown = 0
        for (d in targets) {
            when (fresh[d.id]?.let { MorningBrief.isOn(d, it) }) {
                true -> on += d.name
                false -> {}
                null -> unknown++
            }
        }
        return BriefDevices(on, targets.size, unknown)
    }

    // ------------------------------------------------------------------ notifikácia

    private fun notify(c: Context, t: BriefText): Boolean {
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return false
        val nm = c.getSystemService(NotificationManager::class.java) ?: return false
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ranný prehľad", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Jedna notifikácia ráno: počasie, udalosti, meniny a čo ostalo zapnuté."
            },
        )
        val open = PendingIntent.getActivity(
            c, 7401, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_morning)
            .setContentTitle(t.title)
            .setContentText(t.short)
            .setStyle(NotificationCompat.BigTextStyle().bigText(t.big))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        return try {
            nm.notify(NOTIFICATION_ID, n)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}

/** Budík ranného prehľadu, zmena času / dátumu / pásma a polnočný prekres widgetu Dnes (štart telefónu rieši PresenceReceiver). */
class MorningBriefReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED,
            DnesWidget.ACTION_MIDNIGHT -> {
                // budíky sú v absolútnom čase: po zmene času ich treba prepočítať (aj upozornenia na udalosti)
                runCatching { EventReminders.schedule(context) }
                runCatching { DnesWidget.redrawAll(context) }
                val pending = goAsync()
                MorningBriefAlarm.check(context) { pending.finish() }
            }
            else -> {
                val pending = goAsync()
                MorningBriefAlarm.check(context) { pending.finish() }
            }
        }
    }
}
