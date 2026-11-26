package sk.domov.ovladanie

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.RemoteViews
import androidx.core.widget.RemoteViewsCompat

/**
 * Widget Nákup na plochu (od 1.44) v dvoch veľkostiach: malý 2×2 a zoznam 4×3.
 * Malý 2×2 ukazuje súčty Môj / Spoločný a +; veľký 4×3 dva stĺpce (ťuk na stĺpec otvorí ten zoznam).
 * Oranžové + otvorí [ShoppingQuickAddActivity] s voľbou cieľa. Ťuknutím na riadok vo veľkom widgete sa položka odškrtne.
 * Zoznam je v telefóne ([ShoppingStore]); každá zmena prekreslí všetky widgety.
 */
abstract class ShoppingWidgetProvider : AppWidgetProvider() {
    protected abstract val compact: Boolean

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) ShoppingWidget.render(context, appWidgetManager, id, compact)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ShoppingWidget.ACTION_TOGGLE -> {
                val id = intent.getStringExtra(ShoppingWidget.EXTRA_ITEM) ?: return
                ShoppingStore.toggle(context.applicationContext, id) // prekreslí widgety
            }
            ShoppingWidget.ACTION_CLEAR -> ShoppingStore.clearDone(context.applicationContext)
            else -> super.onReceive(context, intent)
        }
    }
}

/** Malý widget 2×2. */
class ShoppingWidgetSmall : ShoppingWidgetProvider() {
    override val compact = true
}

/** Väčší widget 4×3 s celým zoznamom. */
class ShoppingWidgetLarge : ShoppingWidgetProvider() {
    override val compact = false
}

object ShoppingWidget {
    const val ACTION_TOGGLE = "sk.domov.ovladanie.SHOP_TOGGLE"
    const val ACTION_CLEAR = "sk.domov.ovladanie.SHOP_CLEAR"
    const val EXTRA_ITEM = "item"

    /** Prekreslí všetky widgety Nákup (volá sa po každej zmene zoznamu). Chyba widgetu nesmie zhodiť appku. */
    fun redrawAll(context: Context) {
        runCatching {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app) ?: return
            for (id in manager.getAppWidgetIds(ComponentName(app, ShoppingWidgetSmall::class.java))) render(app, manager, id, true)
            for (id in manager.getAppWidgetIds(ComponentName(app, ShoppingWidgetLarge::class.java))) render(app, manager, id, false)
        }.onFailure { android.util.Log.w("Domov", "shopping widget", it) }
    }

    private fun providerClass(compact: Boolean) = if (compact) ShoppingWidgetSmall::class.java else ShoppingWidgetLarge::class.java

    fun render(context: Context, manager: AppWidgetManager, widgetId: Int, compact: Boolean) {
        val items = ShoppingStore.items(context)
        val mineLeft = ShoppingList.left(items, ShopListKind.MINE)
        val sharedLeft = ShoppingList.left(items, ShopListKind.SHARED)
        val views = RemoteViews(context.packageName, if (compact) R.layout.widget_shop_small else R.layout.widget_shop_large)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun openPage(page: String, code: Int): PendingIntent {
            val i = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PAGE, page)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, code, i, flags)
        }
        val add = Intent(context, ShoppingQuickAddActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        views.setOnClickPendingIntent(
            R.id.shop_add,
            PendingIntent.getActivity(context, 7102 + widgetId * 10, add, flags),
        )
        views.setOnClickPendingIntent(R.id.shop_header, openPage("shopping", 7101 + widgetId * 10))

        if (compact) {
            views.setTextViewText(R.id.shop_mine_count, "$mineLeft")
            views.setTextViewText(R.id.shop_shared_count, "$sharedLeft")
            views.setOnClickPendingIntent(R.id.shop_mine_col, openPage("shopping-mine", 7103 + widgetId * 10))
            views.setOnClickPendingIntent(R.id.shop_shared_col, openPage("shopping-shared", 7104 + widgetId * 10))
            views.setContentDescription(R.id.shop_header, "Nákup, môj $mineLeft, spoločný $sharedLeft")
        } else {
            views.setTextViewText(R.id.shop_subtitle, "${mineLeft + sharedLeft} zostáva")
            views.setTextViewText(R.id.shop_mine_header, "Môj · $mineLeft")
            views.setTextViewText(R.id.shop_shared_header, "Spoločný · $sharedLeft")
            views.setOnClickPendingIntent(R.id.shop_mine_header, openPage("shopping-mine", 7103 + widgetId * 10))
            views.setOnClickPendingIntent(R.id.shop_shared_header, openPage("shopping-shared", 7104 + widgetId * 10))
            views.setContentDescription(R.id.shop_header, "Nákup, ${mineLeft + sharedLeft} zostáva, ťukni na otvorenie")

            val toggle = Intent(context, providerClass(false)).setAction(ACTION_TOGGLE)
            val template = PendingIntent.getBroadcast(
                context,
                7106 + widgetId * 10,
                toggle,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
            )
            views.setPendingIntentTemplate(R.id.shop_list_mine, template)
            views.setPendingIntentTemplate(R.id.shop_list_shared, template)
            fun bindList(listId: Int, emptyId: Int, kind: ShopListKind) {
                val rows = ShoppingList.arrange(items.filter { it.list == kind && !it.done })
                val builder = RemoteViewsCompat.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
                rows.forEach { item -> builder.addItem(stableId(item.id), itemView(context, item, false)) }
                RemoteViewsCompat.setRemoteAdapter(context, views, widgetId, listId, builder.build())
                views.setEmptyView(listId, emptyId)
                views.setViewVisibility(emptyId, if (rows.isEmpty()) View.VISIBLE else View.GONE)
            }
            bindList(R.id.shop_list_mine, R.id.shop_empty_mine, ShopListKind.MINE)
            bindList(R.id.shop_list_shared, R.id.shop_empty_shared, ShopListKind.SHARED)
        }

        manager.updateAppWidget(widgetId, views)
    }

    private fun itemView(context: Context, item: ShopItem, compact: Boolean): RemoteViews {
        val v = RemoteViews(context.packageName, if (compact) R.layout.widget_shop_item_small else R.layout.widget_shop_item_large)
        // farby sú v rozložení (launcher ich vyberie podľa tmavého / svetlého režimu), tu sa len prepína viditeľnosť
        if (item.done) {
            val s = SpannableString(item.name)
            s.setSpan(StrikethroughSpan(), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            v.setTextViewText(R.id.shop_item_done, s)
            v.setViewVisibility(R.id.shop_item_done, View.VISIBLE)
            v.setViewVisibility(R.id.shop_item_name, View.GONE)
            v.setImageViewResource(R.id.shop_item_check, R.drawable.ic_shop_checked)
        } else {
            v.setTextViewText(R.id.shop_item_name, item.name)
            v.setImageViewResource(R.id.shop_item_check, R.drawable.ic_shop_ring)
        }
        v.setOnClickFillInIntent(R.id.shop_item, Intent().putExtra(EXTRA_ITEM, item.id))
        v.setContentDescription(R.id.shop_item, item.name + if (item.done) ", kúpené, ťukni na vrátenie" else ", ťukni na odškrtnutie")
        return v
    }

    /** Stabilné číslo riadku z id položky (UUID). */
    private fun stableId(id: String): Long {
        var h = 1125899906842597L
        for (c in id) h = 31 * h + c.code
        return h
    }
}
