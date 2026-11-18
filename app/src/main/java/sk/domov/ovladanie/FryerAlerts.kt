package sk.domov.ovladanie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Network
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Nastavenia a stav sledovania fritéz (SharedPreferences „domov_fryer“). */
object FryerStore {
    private fun prefs(context: Context) = context.getSharedPreferences("domov_fryer", Context.MODE_PRIVATE)

    fun settings(context: Context, id: String): FryerAlertSettings = FryerAlertSettings.fromJson(prefs(context).getString("set_$id", null))

    fun saveSettings(context: Context, id: String, s: FryerAlertSettings) {
        prefs(context).edit().putString("set_$id", s.toJson().toString()).apply()
    }

    fun watch(context: Context, id: String): FryerWatchState = FryerWatchState.fromJson(prefs(context).getString("watch_$id", null))

    fun saveWatch(context: Context, id: String, s: FryerWatchState) {
        prefs(context).edit().putString("watch_$id", s.toJson().toString()).apply()
    }

    /** O povolenie notifikácií sa appka pýta len raz. */
    fun permissionAsked(context: Context): Boolean = prefs(context).getBoolean("perm_asked", false)

    fun setPermissionAsked(context: Context) {
        prefs(context).edit().putBoolean("perm_asked", true).apply()
    }

    fun lastCloudPoll(context: Context, id: String): Long = prefs(context).getLong("cloud_$id", 0L)

    fun setLastCloudPoll(context: Context, id: String, t: Long) {
        prefs(context).edit().putLong("cloud_$id", t).apply()
    }
}

/** Notifikácie fritézy: priebežná (zostávajúci čas, Stop), upozornenia a bezpečnostné upozornenia. */
object FryerNotify {
    const val CH_ONGOING = "fryer_ongoing"
    const val CH_ALERTS = "fryer_alerts"
    const val CH_SAFETY = "fryer_safety"

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_ONGOING, "Fritéza – varenie", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Zostávajúci čas, kým fritéza varí (tichá)."
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Fritéza – upozornenia", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Minútu pred koncom, keď dovarí, pretrasenie jedla (so zvukom a vibráciou)."
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SAFETY, "Fritéza – bezpečnosť", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Fritéza varí a nikto nie je doma, porucha fritézy."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
            },
        )
    }

    private fun base(id: String) = 5000 + (id.hashCode() and 0x3FF) * 4
    fun ongoingId(id: String) = base(id)
    fun alertId(id: String) = base(id) + 1
    fun safetyId(id: String) = base(id) + 2

    private fun openApp(context: Context, id: String): PendingIntent = PendingIntent.getActivity(
        context, base(id), Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun stopIntent(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context, base(id) + 3,
        Intent(context, FryerReceiver::class.java).setAction(FryerReceiver.ACTION_STOP).putExtra(FryerReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun post(context: Context, nid: Int, n: Notification) {
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(nid, n)
        } catch (e: SecurityException) {
            // bez povolenia notifikácií
        } catch (e: Exception) {
        }
    }

    fun cancel(context: Context, nid: Int) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(nid) }
    }

    /** Priebežná notifikácia: odpočet beží v telefóne (chronometer), appka ho pri kontrole zosynchronizuje. */
    fun ongoing(context: Context, d: Device, s: FryerWatchState, now: Long, extra: String? = null) {
        channels(context)
        val b = NotificationCompat.Builder(context, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_bulb)
            .setContentTitle("${d.name} varí")
            .setContentText(listOfNotNull(FryerWatch.remainingText(s, now).replaceFirstChar { it.uppercase() }, extra).joinToString(" · "))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context, d.id))
            .addAction(0, "Stop", stopIntent(context, d.id))
        val left = FryerWatch.remainingMs(s, now)
        if (left != null) {
            b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(now + left).setShowWhen(true)
        }
        post(context, ongoingId(d.id), b.build())
    }

    fun alert(context: Context, d: Device, title: String, text: String, stop: Boolean = false) {
        channels(context)
        val b = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_bulb)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, d.id))
        if (stop) b.addAction(0, "Stop", stopIntent(context, d.id))
        post(context, alertId(d.id), b.build())
    }

    fun safety(context: Context, d: Device, title: String, text: String, stop: Boolean) {
        channels(context)
        val b = NotificationCompat.Builder(context, CH_SAFETY)
            .setSmallIcon(R.drawable.ic_bulb)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, d.id))
        if (stop) b.addAction(0, "Stop", stopIntent(context, d.id))
        post(context, safetyId(d.id), b.build())
    }
}

/**
 * Sledovanie fritéz: spracuje stav (z obrazovky appky aj zo služby na pozadí), zobrazí notifikácie a vie fritézu
 * bezpečne zastaviť (len Stop, len keď naozaj varí – pravidlá ako pri Všetko vypnúť).
 */
object FryerMonitor {
    private val lock = Any()

    fun fryers(context: Context): List<Device> = runCatching {
        DeviceStore(context).load().filter { d -> SafeOff.looksLikeFryer(d, StateCache.load(context, d.id) ?: emptyMap()) }
    }.getOrDefault(emptyList())

    /** Nikto doma? (null = nevieme, napr. príchod a odchod je vypnutý). */
    fun nobodyHome(context: Context): Boolean? = runCatching {
        val cfg = PresenceStore.config(context)
        val st = PresenceStore.status(context)
        val others = if (st.state == PresenceState.AWAY) {
            HouseDecision.stayingHome(HouseholdStore.members(context), HouseholdStore.phoneId(context), st.since, cfg.memberWindowMs)
        } else {
            emptyList()
        }
        FryerWatch.nobodyHome(st.state, others, cfg.enabled && cfg.home != null)
    }.getOrNull()

    /** Spracuje čerstvé hodnoty fritézy a zobrazí notifikácie. Bezpečné volať často (aj z obrazovky). */
    fun onValues(context: Context, d: Device, values: Map<String, Any>, viaCloud: Boolean = false) {
        try {
            val settings = FryerStore.settings(context, d.id)
            val snap = FryerWatch.snapshot(values, d.schema) ?: return
            val now = System.currentTimeMillis()
            val (state, events) = synchronized(lock) {
                val prev = FryerStore.watch(context, d.id)
                val r = FryerWatch.step(prev, snap, now, nobodyHome(context), settings)
                FryerStore.saveWatch(context, d.id, r.first)
                r
            }
            show(context, d, state, events, now, viaCloud, settings)
            // služba beží, ale fritézy zatiaľ nesledovala (napr. fritéza pribudla) – nech začne
            if (settings.enabled && PresenceService.isRunning && !PresenceService.watchesFryers && PresenceService.fryerWanted(context)) {
                PresenceService.sync(context)
            }
        } catch (e: Exception) {
            // upozornenia nesmú zhodiť appku
        }
    }

    private fun show(context: Context, d: Device, s: FryerWatchState, events: List<FryerEvent>, now: Long, viaCloud: Boolean, settings: FryerAlertSettings) {
        if (settings.enabled && s.running) FryerNotify.ongoing(context, d, s, now, if (viaCloud) "cez cloud" else null)
        if (!s.running) {
            FryerNotify.cancel(context, FryerNotify.ongoingId(d.id))
            // už nevarí: upozornenie „varí a nikto nie je doma“ je neaktuálne (poruchu nechať)
            if (s.fault == 0L) FryerNotify.cancel(context, FryerNotify.safetyId(d.id))
        }
        for (e in events) {
            when (e) {
                FryerEvent.OneMinute -> FryerNotify.alert(context, d, "${d.name}: posledná minúta", "Fritéza o chvíľu dovarí.", stop = true)
                FryerEvent.Done -> {
                    FryerNotify.alert(context, d, "Fritéza dovarila", "${d.name}: jedlo je hotové.")
                    PresenceStore.log(context, "${d.name}: fritéza dovarila.")
                }
                FryerEvent.Shake -> FryerNotify.alert(context, d, "${d.name}: pretras jedlo", "Fritéza pripomína pretrasenie alebo otočenie jedla.")
                is FryerEvent.Fault -> {
                    FryerNotify.safety(context, d, "Porucha fritézy", "${d.name}: ${e.text}. Skontroluj ju.", stop = s.running)
                    PresenceStore.log(context, "${d.name}: porucha ${e.text}.")
                }
                FryerEvent.AwayRunning -> {
                    val how = if (CloudStore(context).load()?.isComplete == true) "Stop ju zastaví cez Tuya cloud." else "Stop funguje len cez domácu WiFi (Tuya cloud nie je nastavený)."
                    FryerNotify.safety(context, d, "POZOR: fritéza varí a nikto nie je doma", "${d.name} práve varí. $how", stop = true)
                    PresenceStore.log(context, "${d.name}: fritéza varí, kým nikto nie je doma – upozornenie.")
                }
                FryerEvent.Started, FryerEvent.Stopped -> {}
            }
        }
    }

    /**
     * Jedna kontrola všetkých fritéz (zo služby na pozadí). Doma lokálne, inak cez cloud (obmedzene).
     * Vráti, o koľko ms skontrolovať znova.
     */
    suspend fun tick(context: Context, homeNet: Network?, background: Boolean = false): Long = withContext(Dispatchers.IO) {
        var next = 300_000L
        for (d in fryers(context)) {
            val settings = FryerStore.settings(context, d.id)
            if (!settings.enabled) continue
            try {
                val now = System.currentTimeMillis()
                val prev = FryerStore.watch(context, d.id)
                val net = homeNet ?: wifiNetwork(context)
                var local = false
                var values: Map<String, Any>? = null
                if (net != null) {
                    values = runCatching { DeviceClient.readState(net, d) }.getOrNull()
                    local = values != null
                }
                if (values == null) {
                    val cloud = CloudStore(context).load()
                    val wait = FryerWatch.nextPollMs(prev, now, local = false)
                    if (cloud != null && cloud.isComplete && now - FryerStore.lastCloudPoll(context, d.id) >= wait - 2_000L) {
                        FryerStore.setLastCloudPoll(context, d.id, now)
                        values = runCatching { SafeOff.shadowValues(TuyaCloudClient(cloud).shadow(d.devId)) }.getOrNull()?.takeIf { it.isNotEmpty() }
                    }
                }
                if (values != null) {
                    if (local) StateCache.save(context, d.id, values)
                    onValues(context, d, values, viaCloud = !local)
                } else if (FryerWatch.localOneMinuteDue(prev, now, settings)) {
                    // zariadenie teraz neodpovedá, ale podľa odpočtu zostáva minúta
                    FryerStore.saveWatch(context, d.id, prev.copy(oneMinuteSent = true))
                    FryerNotify.alert(context, d, "${d.name}: posledná minúta", "Fritéza o chvíľu dovarí (podľa odpočtu).", stop = true)
                }
                next = minOf(next, FryerWatch.nextPollMs(FryerStore.watch(context, d.id), System.currentTimeMillis(), local || net != null, background))
            } catch (e: Exception) {
                next = minOf(next, 60_000L)
            }
        }
        next
    }

    /**
     * Stop z notifikácie: prečíta čerstvý stav (lokálne, inak cloud) a pošle len Stop, len keď fritéza naozaj varí.
     * Po odoslaní stav overí. Vráti text výsledku.
     */
    suspend fun stop(context: Context, id: String): String = withContext(Dispatchers.IO) {
        val d = DeviceStore(context).load().firstOrNull { it.id == id } ?: return@withContext "Fritéza sa nenašla."
        try {
            val net = wifiNetwork(context)
            val before = net?.let { n -> runCatching { DeviceClient.readState(n, d) }.getOrNull() }
            if (net != null && before != null) {
                when (val step = SafeOff.fryerStep(d, before, fresh = true)) {
                    is OffStep.Idle -> { onValues(context, d, before); return@withContext "${d.name} nevarí – nič som neposlal." }
                    is OffStep.Skip -> return@withContext "${d.name}: preskočené (${step.reason})."
                    is OffStep.Send -> {
                        DeviceClient.setValues(net, d, step.cmd, "fritéza")
                        delay(1500)
                        val after = runCatching { DeviceClient.readState(net, d) }.getOrNull()
                        if (after != null) {
                            StateCache.save(context, d.id, after)
                            onValues(context, d, after)
                            if (SafeOff.fryerRunning(after)) return@withContext "${d.name} stále varí – vypni ju ručne!"
                        }
                        return@withContext "${d.name}: zastavené."
                    }
                }
            }
            // mimo domu: cez Tuya cloud
            val cloud = CloudStore(context).load()
            if (cloud == null || !cloud.isComplete) return@withContext "${d.name}: lokálne nedostupná a Tuya cloud nie je nastavený."
            val client = TuyaCloudClient(cloud)
            var dev = d
            if (dev.schema.isEmpty()) {
                client.mapping(CloudDevice(d.devId, d.name, ""))?.let { m -> Schema.parseMapping(m).takeIf { it.isNotEmpty() }?.let { dev = dev.copy(schema = it) } }
            }
            val live = SafeOff.shadowValues(client.shadow(dev.devId))
            val plan = SafeOff.cloudOff(dev, live, fresh = live.isNotEmpty())
            plan.skip?.let { return@withContext "${d.name}: preskočené ($it)." }
            if (plan.options.isEmpty()) { onValues(context, dev, live, viaCloud = true); return@withContext "${d.name} nevarí – nič som neposlal." }
            client.sendCommands(dev.devId, plan.options.first())
            delay(3000)
            val after = runCatching { SafeOff.shadowValues(client.shadow(dev.devId)) }.getOrNull()
            if (after != null && after.isNotEmpty()) {
                onValues(context, dev, after, viaCloud = true)
                if (SafeOff.fryerRunning(after)) return@withContext "${d.name} podľa cloudu stále varí – skontroluj ju!"
            }
            "${d.name}: zastavené cez Tuya cloud."
        } catch (e: Exception) {
            "${d.name}: Stop sa nepodaril (${DeviceClient.shortError(e).lowercase()})."
        }
    }
}

/** Tlačidlo Stop v notifikácii fritézy. */
class FryerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO + LogOnError).launch {
            try {
                val msg = FryerMonitor.stop(context, id)
                PresenceStore.log(context, msg)
                val d = DeviceStore(context).load().firstOrNull { it.id == id }
                if (d != null) {
                    FryerNotify.cancel(context, FryerNotify.safetyId(id))
                    FryerNotify.alert(context, d, "Fritéza", msg)
                }
            } catch (e: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_STOP = "sk.domov.ovladanie.FRYER_STOP"
        const val EXTRA_ID = "id"
    }
}
