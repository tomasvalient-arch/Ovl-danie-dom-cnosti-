package sk.domov.ovladanie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Upozornenie, keď iný člen domácnosti upraví spoločný nákupný zoznam (od 1.68.0): pridá, odškrtne, premenuje
 * alebo odstráni vec. Notifikácia je viditeľná aj na zamknutej obrazovke (celý text, nie „skrytý obsah“).
 *
 * Zmeny sa zbierajú, kým si ich človek neotvorí (alebo notifikáciu neodsunie): keď partner pridá päť vecí po jednej,
 * v notifikácii je všetkých päť, nie len posledná. Nič sa nehlási, keď má človek práve otvorený spoločný nákup,
 * ani pri prvom stiahnutí zoznamu po pripojení do domácnosti (to by bolo desiatky „pridaných“ vecí naraz).
 */
object ShoppingNotify {
    const val CHANNEL = "shopping_changes"
    const val ACTION_DISMISS = "sk.domov.ovladanie.SHOP_NOTIFY_DISMISS"
    private const val NOTIFY_ID = 7620
    private const val REQ_OPEN = 7621
    private const val REQ_DISMISS = 7622
    private const val PREFS = "domov_shop_notify"
    /** Ďalšia zmena do tohto času od predošlého upozornenia len potichu doplní text (bez nového zvuku a bannera). */
    private const val QUIET_REPEAT_MS = 25_000L

    /** Je appka práve na popredí (nastavuje MainActivity)? */
    @Volatile var appForeground: Boolean = false

    /** Je práve otvorená obrazovka Nákup (nastavuje ShoppingScreen)? Spolu s [appForeground] = človek zoznam vidí. */
    @Volatile var screenOpen: Boolean = false

    private val lock = Any()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Zapnuté predvolene; vypnúť sa dá v Nastavenia › Upozornenia. */
    fun enabled(c: Context): Boolean = prefs(c).getBoolean("enabled", true)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("enabled", on).apply()
        if (!on) markSeen(c)
    }

    /** Neprečítané zmeny (len na zobrazenie a testy). */
    fun unseen(c: Context): List<ShopChange> = ShoppingChanges.fromJson(prefs(c).getString("unseen", null))

    /** Zmeny z druhého telefónu už človek videl (otvoril Nákup alebo notifikáciu odsunul): vyčistí zoznam a zruší notifikáciu. */
    fun markSeen(c: Context) {
        synchronized(lock) { prefs(c).edit().remove("unseen").apply() }
        runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(NOTIFY_ID) }
    }

    /** Len vyčistí zoznam (notifikácia je už preč – odsunutá). */
    internal fun clearUnseen(c: Context) {
        synchronized(lock) { prefs(c).edit().remove("unseen").apply() }
    }

    /**
     * Volá sa po zlúčení zmien z iného telefónu (z vlákna synchronizácie). Dopĺňa mená, pridá zmeny k neprečítaným
     * a ukáže alebo obnoví notifikáciu.
     */
    fun onRemoteChanges(c: Context, changes: List<ShopChange>) {
        if (changes.isEmpty() || !enabled(c)) return
        // človek zoznam práve pozerá: obrazovka sa obnoví sama, notifikácia by len rušila
        if (appForeground && screenOpen) return
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return
        val named = changes.map { withName(c, it) }
        val now = System.currentTimeMillis()
        val all: List<ShopChange>
        synchronized(lock) {
            all = ShoppingChanges.accumulate(unseen(c), named, now)
            prefs(c).edit().putString("unseen", ShoppingChanges.toJson(all)).apply()
        }
        if (all.isEmpty()) {
            // zmeny sa navzájom zrušili (pridané a hneď odstránené): nič nové na ukázanie
            runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(NOTIFY_ID) }
            return
        }
        post(c, all, now)
    }

    /** Meno člena: najprv zo zoznamu členov domácnosti, inak meno, pod ktorým položku pridal. */
    private fun withName(c: Context, ch: ShopChange): ShopChange {
        val member = HouseholdStore.members(c).firstOrNull { it.id == ch.actorId }?.name?.trim().orEmpty()
        return if (member.isNotEmpty()) ch.copy(actorName = member) else ch
    }

    private fun ensureChannel(nm: NotificationManager) {
        val ch = NotificationChannel(CHANNEL, "Zmeny v spoločnom nákupe", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Keď člen domácnosti upraví spoločný nákupný zoznam. Na zamknutej obrazovke je viditeľný text."
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }
        nm.createNotificationChannel(ch)
    }

    private fun post(c: Context, all: List<ShopChange>, now: Long) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        try {
            ensureChannel(nm)
            val p = prefs(c)
            val showing = runCatching { nm.activeNotifications.any { it.id == NOTIFY_ID } }.getOrDefault(false)
            val lastAlert = p.getLong("alert_at", 0L)
            // rýchle zmeny po sebe nechcú pípať a vyskakovať zakaždým; text sa len obnoví
            val quietUpdate = showing && now - lastAlert < QUIET_REPEAT_MS
            if (!quietUpdate) p.edit().putLong("alert_at", now).apply()

            val open = PendingIntent.getActivity(
                c, REQ_OPEN,
                Intent(c, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PAGE, "shopping-shared")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val dismiss = PendingIntent.getBroadcast(
                c, REQ_DISMISS,
                Intent(c, ShoppingNotifyReceiver::class.java).setAction(ACTION_DISMISS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val title = ShoppingChanges.title(all)
            val summary = ShoppingChanges.summary(all)
            val (lines, more) = ShoppingChanges.lines(all)
            val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
            for (l in lines) style.addLine(l)
            if (more > 0) style.setSummaryText("+$more ďalších zmien")

            val n = NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_shop_notify)
                .setColor(ContextCompat.getColor(c, R.color.shop_widget_accent))
                .setContentTitle(title)
                .setContentText(summary)
                .setStyle(style)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                // celý text aj na zamknutej obrazovke (nie „obsah skrytý“)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(quietUpdate)
                .setShowWhen(true)
                .setWhen(all.maxOf { it.at }.takeIf { it > 0 } ?: now)
                .setAutoCancel(true)
                .setContentIntent(open)
                .setDeleteIntent(dismiss)
                .build()
            nm.notify(NOTIFY_ID, n)
        } catch (_: SecurityException) {
            // bez povolenia notifikácií
        } catch (_: Exception) {
        }
    }
}

/** Odsunutie notifikácie o zmenách nákupu: zmeny sa považujú za videné, ďalšia notifikácia začne odznova. */
class ShoppingNotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ShoppingNotify.ACTION_DISMISS) ShoppingNotify.clearUnseen(context)
    }
}
