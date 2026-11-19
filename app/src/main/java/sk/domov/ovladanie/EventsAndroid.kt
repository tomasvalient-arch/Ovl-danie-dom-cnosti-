package sk.domov.ovladanie

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Uloženie udalostí: len v tomto telefóne (SharedPreferences „domov_events“), udalosti a čas sú súčasť zálohy. */
object EventsStore {
    const val PREFS = "domov_events"
    private const val KEY_EVENTS = "events"
    private const val KEY_TIME = "time"
    private const val KEY_FIRED = "fired"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun events(c: Context): List<EventItem> = Events.fromJson(prefs(c).getString(KEY_EVENTS, null))

    /** Čas denného upozornenia v minútach od polnoci (predvolene 8:00). */
    fun time(c: Context): Int = prefs(c).getInt(KEY_TIME, Events.DEFAULT_TIME_MIN).coerceIn(0, 24 * 60 - 1)

    fun timeOfDay(c: Context): LocalTime = time(c).let { LocalTime.of(it / 60, it % 60) }

    fun fired(c: Context): Set<String> = Events.firedFromJson(prefs(c).getString(KEY_FIRED, null))

    fun saveFired(c: Context, fired: Set<String>) {
        prefs(c).edit().putString(KEY_FIRED, Events.firedToJson(fired)).apply()
    }

    /** Pridá alebo upraví udalosť (podľa id) a nastaví budík. Vráti aktuálny zoznam. */
    @Synchronized
    fun save(c: Context, e: EventItem): List<EventItem> {
        val list = events(c)
        val next = when {
            list.any { it.id == e.id } -> list.map { if (it.id == e.id) e else it }
            list.size >= Events.MAX_EVENTS -> list
            else -> list + e
        }
        prefs(c).edit()
            .putString(KEY_EVENTS, Events.toJson(next))
            .putString(KEY_FIRED, Events.firedToJson(Events.afterSave(fired(c), e, LocalDateTime.now(), timeOfDay(c))))
            .apply()
        EventReminders.schedule(c)
        return next
    }

    @Synchronized
    fun delete(c: Context, id: String): List<EventItem> {
        val next = events(c).filter { it.id != id }
        prefs(c).edit()
            .putString(KEY_EVENTS, Events.toJson(next))
            .putString(KEY_FIRED, Events.firedToJson(Events.dropKeys(fired(c), id)))
            .apply()
        EventReminders.schedule(c)
        return next
    }

    fun setTime(c: Context, minutes: Int) {
        prefs(c).edit().putInt(KEY_TIME, minutes.coerceIn(0, 24 * 60 - 1)).apply()
        EventReminders.schedule(c)
    }
}

/**
 * Upozornenia na udalosti: budík sa spustí raz denne v nastavený čas, pošle upozornenia, ktoré sú na rade, a nastaví
 * sa na zajtra. Kontrola beží aj po štarte telefónu, po aktualizácii appky a vždy keď appku otvoríš – takže zmeškané
 * upozornenie (vypnutý telefón, šetrič batérie) príde najneskôr vtedy.
 */
object EventReminders {
    const val ACTION_CHECK = "sk.domov.ovladanie.EVENTS_CHECK"
    const val CHANNEL = "domov_events"

    fun check(c: Context) {
        runCatching { sendDue(c) }
        runCatching { schedule(c) }
    }

    private fun sendDue(c: Context) {
        val events = EventsStore.events(c)
        if (events.isEmpty()) return
        val now = LocalDateTime.now()
        val fired = EventsStore.fired(c)
        val due = Events.due(events, fired, now, EventsStore.timeOfDay(c))
        // bez povolených upozornení sa nič nevybaví: keď ich zapneš, prídu zmeškané pri najbližšej kontrole
        val canNotify = NotificationManagerCompat.from(c).areNotificationsEnabled()
        val next = fired.toMutableSet()
        if (canNotify) for (d in due) {
            notify(c, d, now.toLocalDate())
            next += d.keys
        }
        val pruned = Events.prune(next, now.toLocalDate())
        if (pruned != fired) EventsStore.saveFired(c, pruned)
    }

    private fun notify(c: Context, d: EventDue, today: LocalDate) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Udalosti a narodeniny", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Upozornenia na udalosti a narodeniny vopred."
            },
        )
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = Events.notificationBody(d.event, d.occurrence, today)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_event)
            .setContentTitle(Events.title(d.event))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            // jedna notifikácia na udalosť: „v ten deň“ nahradí „týždeň vopred“
            nm.notify(("ev|" + d.event.id).hashCode(), n)
        } catch (e: SecurityException) {
            // bez povolenia notifikácií
        } catch (e: Exception) {
        }
    }

    private fun pending(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 7310, Intent(c, EventsReceiver::class.java).setAction(ACTION_CHECK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Nastaví (alebo zruší, ak nie sú udalosti) budík na najbližšiu dennú kontrolu. */
    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(c)
        if (EventsStore.events(c).isEmpty()) {
            am.cancel(pi)
            return
        }
        val at = Events.nextCheck(LocalDateTime.now(), EventsStore.timeOfDay(c))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c))
    }
}

class EventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EventReminders.check(context)
    }
}
