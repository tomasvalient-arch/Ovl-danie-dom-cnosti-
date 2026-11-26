package sk.domov.ovladanie

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.ZoneId

/**
 * Widget „Dnes“ na plochu (od 1.46): dnešný program (udalosti a meniny), počet položiek v nákupe
 * a rýchle pridanie udalosti. Ťuknutím na hlavičku sa otvorí kalendár, na nákup sa otvorí zoznam.
 */
class DnesWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) DnesWidget.render(context, appWidgetManager, id)
        DnesWidget.scheduleMidnight(context)
    }
}

object DnesWidget {
    const val ACTION_MIDNIGHT = "sk.domov.ovladanie.DNES_MIDNIGHT"
    private const val MIDNIGHT_REQ = 7410
    private val LINE_IDS = intArrayOf(R.id.dnes_line1, R.id.dnes_line2, R.id.dnes_line3, R.id.dnes_line4)

    fun redrawAll(context: Context) {
        runCatching {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app) ?: return
            for (id in manager.getAppWidgetIds(ComponentName(app, DnesWidgetProvider::class.java))) {
                render(app, manager, id)
            }
        }.onFailure { android.util.Log.w("Domov", "dnes widget", it) }
        scheduleMidnight(context)
    }

    /** Budík tesne o polnoci, aby widget ukázal nový deň aj bez otvorenia appky (inexaktný, ako ostatné budíky). */
    fun scheduleMidnight(context: Context) {
        runCatching {
            val app = context.applicationContext
            val am = app.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                app, MIDNIGHT_REQ,
                Intent(app, MorningBriefReceiver::class.java).setAction(ACTION_MIDNIGHT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val at = LocalDate.now().plusDays(1).atStartOfDay()
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure { android.util.Log.w("Domov", "dnes midnight", it) }
    }

    fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_dnes)
        val today = LocalDate.now()
        views.setTextViewText(R.id.dnes_date, Calendar.dayTitle(today, today).removePrefix("Dnes · "))

        val myId = runCatching { HouseholdStore.phoneId(context) }.getOrDefault("")
        val events = runCatching { EventsStore.events(context) }.getOrDefault(emptyList())
        val lines = ArrayList<String>()
        for (e in Calendar.onDay(events, today, myId)) {
            val time = Events.timeRange(e)
            lines += if (time.isEmpty()) Events.title(e) else "${Events.title(e)}  $time"
        }
        val shownMeniny = Calendar.onDay(events, today, myId).filter { it.isMeniny }.map { Events.displayName(it).lowercase() }.toSet()
        for (n in Meniny.zoznam(today)) {
            if (n.lowercase() !in shownMeniny) lines += "Meniny: $n"
        }
        if (lines.isEmpty()) lines += "Dnes nič v kalendári"
        for (i in LINE_IDS.indices) {
            if (i < lines.size) {
                views.setViewVisibility(LINE_IDS[i], View.VISIBLE)
                views.setTextViewText(LINE_IDS[i], lines[i])
            } else {
                views.setViewVisibility(LINE_IDS[i], View.GONE)
            }
        }

        val shop = runCatching { ShoppingStore.items(context) }.getOrDefault(emptyList())
        val mine = ShoppingList.left(shop, ShopListKind.MINE)
        val shared = ShoppingList.left(shop, ShopListKind.SHARED)
        views.setTextViewText(
            R.id.dnes_shop,
            MorningBrief.shopLine(mine, shared) ?: "Nákup: nič nezostáva",
        )

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        views.setOnClickPendingIntent(
            R.id.dnes_header,
            PendingIntent.getActivity(
                context, 7401,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PAGE, "events")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags,
            ),
        )
        views.setOnClickPendingIntent(
            R.id.dnes_add,
            PendingIntent.getActivity(
                context, 7402,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PAGE, "events-add")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags,
            ),
        )
        views.setOnClickPendingIntent(
            R.id.dnes_shop,
            PendingIntent.getActivity(
                context, 7403,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PAGE, "shopping")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags,
            ),
        )
        manager.updateAppWidget(widgetId, views)
    }
}
