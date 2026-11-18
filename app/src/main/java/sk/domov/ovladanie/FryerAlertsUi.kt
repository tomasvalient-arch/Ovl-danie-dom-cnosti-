package sk.domov.ovladanie

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private fun notificationsAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private val isXiaomi: Boolean
    get() = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Nastavenia upozornení fritézy (v detaile fritézy). */
@Composable
fun FryerAlertsCard(device: Device) {
    val context = LocalContext.current
    var s by remember(device.id) { mutableStateOf(FryerStore.settings(context, device.id)) }
    var allowed by remember { mutableStateOf(notificationsAllowed(context)) }
    var explain by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }

    fun save(n: FryerAlertSettings) {
        s = n
        FryerStore.saveSettings(context, device.id, n)
        // služba na pozadí podľa toho spustí / ukončí sledovanie fritéz
        if (PresenceService.isRunning) PresenceService.sync(context)
    }

    // o povolenie sa appka pýta len raz, so slovenským vysvetlením
    LaunchedEffect(device.id) {
        if (s.enabled && !allowed && !FryerStore.permissionAsked(context)) explain = true
    }

    DomovCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Upozornenia", style = MaterialTheme.typography.titleMedium)
            Line("Upozornenia", "Kým varí: zostávajúci čas a tlačidlo Stop", s.enabled) { save(s.copy(enabled = it)) }
            Line("Minútu pred koncom", null, s.oneMinute, s.enabled) { save(s.copy(oneMinute = it)) }
            Line("Keď dovarí", null, s.done, s.enabled) { save(s.copy(done = it)) }
            Line("Bezpečnostné upozornenie", "Fritéza varí a nikto nie je doma, porucha", s.safety, s.enabled) { save(s.copy(safety = it)) }
            if (s.enabled && !allowed) {
                Text(
                    "Notifikácie sú v telefóne pre Domov vypnuté – upozornenia sa nezobrazia.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && !FryerStore.permissionAsked(context)) {
                        FryerStore.setPermissionAsked(context)
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        openAppSettings(context)
                    }
                }) { Text("Povoliť notifikácie") }
            }
            if (s.enabled) {
                val bg = PresenceService.isRunning
                Text(
                    if (bg) "Na pozadí sleduje fritézu služba Príchod a odchod (doma každú minútu počas varenia, mimo domu cez Tuya cloud)."
                    else "Na pozadí upozorní, len keď je zapnutý Príchod a odchod. Inak len kým je appka otvorená.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if ((bg && !isIgnoringBattery(context)) || isXiaomi) {
                    Text(
                        if (isXiaomi) "Xiaomi / HyperOS môže appku na pozadí zastaviť. Povoľ: Nastavenia → Aplikácie → Domov → Automatické spúšťanie (zapnúť) " +
                            "a Šetrič batérie → Bez obmedzení. V zozname nedávnych aplikácií môžeš Domov aj zamknúť (podržať → zámok)."
                        else "Aby Android sledovanie na pozadí nezastavil, povoľ appke Domov batériu bez obmedzení.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(onClick = { openBatterySettings(context) }) { Text("Batéria bez obmedzení") }
                        if (isXiaomi) TextButton(onClick = { openAppSettings(context) }) { Text("Automatické spúšťanie") }
                    }
                }
            }
        }
    }

    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false; FryerStore.setPermissionAsked(context) },
            title = { Text("Upozornenia fritézy") },
            text = {
                Text(
                    "Domov ti môže dať vedieť, koľko ešte fritéza varí, minútu pred koncom a keď dovarí. Upozorní aj vtedy, keď " +
                        "fritéza varí a nikto nie je doma. Na to potrebuje povolenie zobrazovať notifikácie.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    FryerStore.setPermissionAsked(context)
                    if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Povoliť") }
            },
            dismissButton = { TextButton(onClick = { explain = false; FryerStore.setPermissionAsked(context) }) { Text("Teraz nie") } },
        )
    }
}

@Composable
private fun Line(title: String, sub: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
