package sk.domov.ovladanie

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.io.IOException

/**
 * Widget na plochu: jedna dlaždica = jedno zariadenie. Ťuknutím sa zapne alebo vypne
 * jeho hlavný vypínač (zariadenie sa najprv opýta, aký má stav, a prepne ho na opačný).
 */
class DomovWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Hneď ukáž naposledy známy stav, potom ho v pozadí obnov zo zariadenia.
        for (id in appWidgetIds) render(context, id)
        val pending = goAsync()
        Thread {
            try {
                for (id in appWidgetIds) refresh(context.applicationContext, id)
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            val id = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
            val pending = goAsync()
            Thread {
                try {
                    toggle(context.applicationContext, id)
                } finally {
                    pending.finish()
                }
            }.start()
        } else {
            super.onReceive(context, intent)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) WidgetPrefs.remove(context, id)
    }

    companion object {
        const val ACTION_TOGGLE = "sk.domov.ovladanie.WIDGET_TOGGLE"
        const val EXTRA_WIDGET_ID = "widgetId"

        private const val ON_BG = 0xFFFFC857.toInt()
        private const val ON_FG = 0xFF1B1B1F.toInt()
        private const val OFF_FG = 0xFFE3E2E6.toInt()
        private const val OFF_FG_DIM = 0xFFA9A8AE.toInt()
        private const val ERROR_FG = 0xFFFFB4AB.toInt()

        private fun findDevice(context: Context, widgetId: Int): Device? {
            val deviceId = WidgetPrefs.deviceId(context, widgetId) ?: return null
            return DeviceStore(context).load().find { it.id == deviceId }
        }

        /**
         * Nakreslí widget z naposledy známeho stavu.
         * [status] prepíše text pod názvom (napr. "…" počas prepínania), [failed] ho zobrazí ako chybu.
         */
        fun render(context: Context, widgetId: Int, status: String? = null, failed: Boolean = false) {
            val manager = AppWidgetManager.getInstance(context)
            val device = findDevice(context, widgetId)
            val values = if (device != null) StateCache.load(context, device.id) else null
            val power = if (device != null && values != null) powerDpOf(device, values) else null
            val on = !failed && power != null && values?.get(power) == true
            val isLight = values != null && Light.detect(values) != null

            val text = status ?: when {
                device == null -> "Zariadenie chýba"
                values == null || power == null -> "Ťukni pre prepnutie"
                on -> "Zapnuté"
                else -> "Vypnuté"
            }

            val views = RemoteViews(context.packageName, R.layout.widget_tile)
            views.setTextViewText(R.id.widget_title, device?.name ?: "Domov")
            views.setTextViewText(R.id.widget_status, text)
            views.setImageViewResource(R.id.widget_icon, if (isLight) R.drawable.ic_bulb else R.drawable.ic_power)
            views.setInt(R.id.widget_root, "setBackgroundResource", if (on) R.drawable.widget_bg_on else R.drawable.widget_bg_off)
            val titleColor = if (on) ON_FG else OFF_FG
            val statusColor = when {
                failed -> ERROR_FG
                on -> ON_FG
                else -> OFF_FG_DIM
            }
            views.setTextColor(R.id.widget_title, titleColor)
            views.setTextColor(R.id.widget_status, statusColor)
            views.setInt(R.id.widget_icon, "setColorFilter", titleColor)

            val intent = Intent(context, DomovWidgetProvider::class.java)
                .setAction(ACTION_TOGGLE)
                .putExtra(EXTRA_WIDGET_ID, widgetId)
            val pending = PendingIntent.getBroadcast(
                context,
                widgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            manager.updateAppWidget(widgetId, views)
        }

        /** Znova nakreslí všetky widgety z uloženého stavu (bez siete). */
        fun redrawAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, DomovWidgetProvider::class.java))
            for (id in ids) render(context, id)
        }

        /** Opýta sa zariadenia na aktuálny stav a prekreslí widget. Volaj mimo hlavného vlákna. */
        fun refresh(context: Context, widgetId: Int) {
            val device = findDevice(context, widgetId) ?: return
            val network = DeviceClient.wifiNetwork(context)
            if (network == null) {
                render(context, widgetId, "Bez WiFi", failed = true)
                return
            }
            try {
                val values = DeviceClient.readState(network, device)
                StateCache.save(context, device.id, values)
                redrawAll(context)
            } catch (e: Exception) {
                render(context, widgetId, DeviceClient.shortError(e), failed = true)
            }
        }

        /** Prepne hlavný vypínač zariadenia na opačný stav. Volaj mimo hlavného vlákna. */
        fun toggle(context: Context, widgetId: Int) {
            val device = findDevice(context, widgetId)
            if (device == null) {
                render(context, widgetId, "Zariadenie chýba", failed = true)
                return
            }
            val network = DeviceClient.wifiNetwork(context)
            if (network == null) {
                render(context, widgetId, "Bez WiFi", failed = true)
                return
            }
            render(context, widgetId, "…")
            try {
                val before = DeviceClient.readState(network, device)
                val power = powerDpOf(device, before) ?: throw IOException("Zariadenie nemá vypínač")
                val target = before[power] != true
                DeviceClient.setValues(network, device, mapOf(power to target))
                Thread.sleep(350)
                // Skontroluj, či zariadenie zmenu naozaj prijalo; ak sa nedá overiť, verím príkazu.
                val after: Map<String, Any> = try {
                    DeviceClient.readState(network, device)
                } catch (e: Exception) {
                    before + (power to target)
                }
                StateCache.save(context, device.id, after)
                redrawAll(context)
            } catch (e: Exception) {
                render(context, widgetId, DeviceClient.shortError(e), failed = true)
            }
        }
    }
}
