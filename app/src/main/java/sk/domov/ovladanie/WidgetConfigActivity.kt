@file:OptIn(ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Otvorí sa pri pridávaní widgetu na plochu: používateľ vyberie zariadenie, ktoré widget ovláda. */
class WidgetConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ak používateľ odíde späť bez výberu, widget sa nepridá.
        setResult(Activity.RESULT_CANCELED)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val devices = DeviceStore(this).load()
        setContent {
            HomeTheme { WidgetPicker(devices) { pick(it) } }
        }
    }

    private fun pick(device: Device) {
        val appContext = applicationContext
        val id = widgetId
        WidgetPrefs.set(appContext, id, device.id)
        DomovWidgetProvider.render(appContext, id)
        Thread { DomovWidgetProvider.refresh(appContext, id) }.start()
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}

@Composable
private fun WidgetPicker(devices: List<Device>, onPick: (Device) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Vyber zariadenie pre widget") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = "Ťuknutím na widget na ploche sa zariadenie zapne alebo vypne. " +
                        "Pri spotrebičoch (napr. fritéza) dávaj pozor, čo sa po zapnutí spustí.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (devices.isEmpty()) {
                item {
                    Text("Najprv pridaj zariadenie v appke Domov.", modifier = Modifier.padding(vertical = 16.dp))
                }
            }
            items(devices, key = { it.id }) { device ->
                Card(onClick = { onPick(device) }, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (device.isSub) "Zigbee (cez bránu)" else device.ip,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
