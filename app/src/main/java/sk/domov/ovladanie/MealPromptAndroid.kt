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

/**
 * Večerné oznámenie „Varili ste?“: raz denne o 21:00 (budík AlarmManager.setAndAllowWhileIdle, ako ranný prehľad),
 * ak sú v pláne jedlá, o ktorých ešte nevieme, či sa uvarili. Tlačidlo „Áno“ ich označí ako uvarené a odráta suroviny
 * zo zásob, „Nie“ ich označí ako nevarené (ostanú v pláne, ale nejdú do nákupu). Ťuknutím sa otvorí Jedálniček,
 * kde sa dá odpovedať po jednom. Zapína sa v Jedálničku (predvolene zapnuté), nastavenie je len v tomto telefóne.
 */
object MealPromptAlarm {
    const val ACTION_ALARM = "sk.domov.ovladanie.MEAL_PROMPT"
    const val ACTION_YES = "sk.domov.ovladanie.MEAL_PROMPT_YES"
    const val ACTION_NO = "sk.domov.ovladanie.MEAL_PROMPT_NO"
    const val CHANNEL = "domov_meal_prompt"
    private const val NOTIFICATION_ID = 7410
    private val AT = LocalTime.of(21, 2)

    /** Najbližší čas oznámenia po [now]. */
    fun nextAt(now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate().atTime(AT)
        return if (now.isBefore(today)) today else today.plusDays(1)
    }

    private fun alarmIntent(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 7410, Intent(c, MealPromptReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Nastaví (alebo pri vypnutom oznámení zruší) budík na najbližších 21:02. */
    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(c)
        if (!MealsStore.promptEnabled(c)) {
            am.cancel(pi)
            return
        }
        val at = nextAt(LocalDateTime.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** Budík: nastaví ďalší a pošle oznámenie, ak je na čo odpovedať. */
    fun fire(c: Context) {
        runCatching { schedule(c) }
        if (!MealsStore.promptEnabled(c)) return
        val pending = runCatching { MealsStore.pendingCooked(c) }.getOrDefault(emptyList())
        if (pending.isEmpty()) return
        runCatching { notify(c, pending) }
    }

    /** Odpoveď z tlačidla v oznámení pre všetky jedlá, ktoré teraz čakajú. */
    fun answer(c: Context, yes: Boolean) {
        val pending = runCatching { MealsStore.pendingCooked(c) }.getOrDefault(emptyList())
        for (m in pending) {
            runCatching { if (yes) MealsStore.markCooked(c, m) else MealsStore.skipMeal(c, m) }
        }
        NotificationManagerCompat.from(c).cancel(NOTIFICATION_ID)
    }

    private fun dayWord(date: String, today: LocalDate): String {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return ""
        return when (d) {
            today -> "Dnes"
            today.minusDays(1) -> "Včera"
            else -> Meals.dayLabel(d)
        }
    }

    private fun notify(c: Context, pending: List<Meals.Meal>) {
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Varili ste?", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Večer sa opýta, či sa uvarilo to, čo bolo v jedálničku, a podľa toho odpočíta zásoby."
            },
        )
        val recipes = MealsStore.liveRecipes(c).associateBy { it.id }
        val today = LocalDate.now()
        val lines = pending.take(6).map { m ->
            "${dayWord(m.date, today)} · ${m.slot.title.lowercase()}: ${m.title.ifBlank { recipes[m.recipeId]?.name.orEmpty() }}"
        }
        val title = if (pending.size == 1) "Varili ste ${lines.first().substringAfter(": ")}?" else "Varili ste podľa jedálnička?"
        val open = PendingIntent.getActivity(
            c, 7411, Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_PAGE, "meals"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(code: Int, act: String): PendingIntent = PendingIntent.getBroadcast(
            c, code, Intent(c, MealPromptReceiver::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_meal)
            .setContentTitle(title)
            .setContentText(lines.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, if (pending.size == 1) "Áno" else "Áno, všetko", action(7412, ACTION_YES))
            .addAction(0, if (pending.size == 1) "Nie" else "Nie, nič", action(7413, ACTION_NO))
            .build()
        try {
            nm.notify(NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
        }
    }
}

/** Budík „Varili ste?“, odpovede z oznámenia a prepočet budíka po zmene času alebo pásma. */
class MealPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MealPromptAlarm.ACTION_ALARM -> MealPromptAlarm.fire(context)
            MealPromptAlarm.ACTION_YES -> MealPromptAlarm.answer(context, true)
            MealPromptAlarm.ACTION_NO -> MealPromptAlarm.answer(context, false)
            else -> runCatching { MealPromptAlarm.schedule(context) }
        }
    }
}
