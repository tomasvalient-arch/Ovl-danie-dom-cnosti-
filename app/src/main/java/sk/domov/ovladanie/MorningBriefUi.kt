package sk.domov.ovladanie

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

internal fun appNotificationsEnabled(c: Context): Boolean = NotificationManagerCompat.from(c).areNotificationsEnabled()

internal fun clockMinutes(minutes: Int): String = String.format(java.util.Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60)

/** Systémové nastavenia upozornení appky (fallback, keď sa povolenie už nedá vypýtať dialógom). */
internal fun openNotificationSettings(c: Context) {
    runCatching {
        c.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Stav povolenia upozornení, prepočíta sa pri návrate do appky (napr. zo systémových nastavení). */
@Composable
internal fun rememberNotificationsAllowed(): androidx.compose.runtime.MutableState<Boolean> {
    val context = LocalContext.current
    val allowed = remember { mutableStateOf(appNotificationsEnabled(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) allowed.value = appNotificationsEnabled(context) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    return allowed
}

/**
 * Nastavenia › Upozornenia a ranný prehľad › Ranný prehľad: zapnutie (predvolene vypnuté), čas, skúšobné odoslanie
 * a ukážka z dnešných údajov. Len pre tento telefón.
 */
@Composable
internal fun MorningBriefSection() {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(MorningBriefStore.enabled(context)) }
    var timeMin by remember { mutableIntStateOf(MorningBriefStore.time(context)) }
    var allowed by rememberNotificationsAllowed()
    var pickTime by remember { mutableStateOf(false) }
    var testState by remember { mutableStateOf<String?>(null) }
    val preview = remember { runCatching { MorningBriefAlarm.preview(context) }.getOrNull() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = appNotificationsEnabled(context) }

    fun needsPermission() = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun toggle(on: Boolean) {
        enabled = on
        MorningBriefStore.setEnabled(context, on)
        if (on && needsPermission()) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    SectionLabel("Ranný prehľad")
    DomovCard(padding = 10.dp, spacing = 0.dp) {
        SettingRow(
            Sym.wbTwilight, "Ranný prehľad",
            caption = if (enabled) "Každé ráno o ${clockMinutes(timeMin)}: počasie, udalosti, meniny, nákup a čo ostalo zapnuté"
            else "Vypnutý · jedna notifikácia ráno s počasím, udalosťami, meninami a zapnutými zariadeniami",
            onClick = { toggle(!enabled) },
            trailing = { DomovSwitch(checked = enabled, onCheckedChange = { toggle(it) }) },
        )
        if (enabled) {
            SettingRow(Sym.schedule, "Čas prehľadu", caption = "Len v tomto telefóne", value = clockMinutes(timeMin), onClick = { pickTime = true })
        }
        if (enabled && !allowed) {
            SettingRow(
                Sym.warning, "Upozornenia sú v telefóne vypnuté",
                caption = "Bez nich prehľad nepríde. Ťukni a zapni ich.",
                onClick = { if (needsPermission()) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings(context) },
            )
        }
        FilledTonalButton(
            onClick = {
                if (!appNotificationsEnabled(context)) {
                    if (needsPermission()) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    testState = "Najprv povoľ upozornenia pre appku Domov."
                    return@FilledTonalButton
                }
                testState = SENDING
                scope.launch {
                    val ok = runCatching { MorningBriefAlarm.sendNow(context) }.getOrDefault(false)
                    testState = if (ok) "Odoslané – pozri notifikácie." else "Nepodarilo sa – upozornenia sú vypnuté?"
                }
            },
            enabled = testState != SENDING,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        ) {
            Icon(Sym.sunny, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Poslať skúšobne teraz")
        }
        testState?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }

    // ukážka, ako bude notifikácia vyzerať (z dnešných údajov v telefóne, bez siete a bez stavu zariadení)
    preview?.let { p ->
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = scheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, scheme.outlineVariant),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Sym.wbTwilight, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ukážka · Ranný prehľad · ${clockMinutes(timeMin)}", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                Text(p.title, style = MaterialTheme.typography.titleSmall)
                Text(p.big, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface.copy(alpha = 0.85f))
                Text("Zapnuté zariadenia sa zisťujú až pri odoslaní.", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }

    if (pickTime) {
        WheelTimeDialog(
            title = "Čas ranného prehľadu",
            initialMinutes = timeMin,
            preview = { m -> "Prehľad príde o %d:%02d".format(m / 60, m % 60) },
            onConfirm = { m ->
                timeMin = m
                MorningBriefStore.setTime(context, m)
                pickTime = false
            },
            onDismiss = { pickTime = false },
        )
    }
}

private const val SENDING = "Pripravujem prehľad…"
