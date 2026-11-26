package sk.domov.ovladanie

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Karty dolnej lišty. Poradie = poradie v lište. */
enum class HomeTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("Domov", Sym.home, Sym.homeFill),
    CHAT("Chat", Sym.chat, Sym.chat),
    HEAT("Kúrenie", Sym.thermostat, Sym.thermostat),
    PC("Počítač", Sym.desktopWindows, Sym.desktopWindows),
    SETTINGS("Nastavenia", Sym.settings, Sym.settings),
}

/** Plávajúca dolná lišta: zaoblená pilulka, vybraná karta je jantárová. */
@Composable
internal fun HomeBottomBar(selected: HomeTab, onSelect: (HomeTab) -> Unit, automationAlert: Boolean = false, chatUnread: Int = 0) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 12.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(34.dp),
            color = scheme.surfaceContainerLow,
            border = BorderStroke(1.dp, scheme.surfaceVariant),
            shadowElevation = 6.dp,
        ) {
            Row(modifier = Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                HomeTab.entries.forEach { t ->
                    val sel = t == selected
                    val tint = if (sel) scheme.onPrimaryContainer else scheme.onSurfaceVariant
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(if (sel) scheme.primaryContainer else Color.Transparent)
                            .clickable { onSelect(t) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // červená bodka na Nastaveniach (› Automatika), keď je automatika zapnutá, ale služba nebeží
                        if (t == HomeTab.SETTINGS && automationAlert) {
                            androidx.compose.material3.BadgedBox(badge = { androidx.compose.material3.Badge() }) {
                                Icon(if (sel) t.selectedIcon else t.icon, contentDescription = "Automatika nebeží", tint = tint, modifier = Modifier.size(22.dp))
                            }
                        } else if (t == HomeTab.CHAT && chatUnread > 0) {
                            androidx.compose.material3.BadgedBox(
                                badge = {
                                    androidx.compose.material3.Badge(containerColor = scheme.primary, contentColor = scheme.onPrimary) {
                                        Text(if (chatUnread > 99) "99+" else chatUnread.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                },
                            ) {
                                Icon(if (sel) t.selectedIcon else t.icon, contentDescription = "Chat", tint = tint, modifier = Modifier.size(22.dp))
                            }
                        } else {
                            Icon(if (sel) t.selectedIcon else t.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        }
                        Text(t.label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/** Spoločné rozloženie kariet: veľký nadpis ako na Domove a posúvateľný obsah. */
@Composable
internal fun TabPage(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp), contentAlignment = Alignment.CenterStart) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        content()
        Spacer(Modifier.height(16.dp))
    }
}

/** Prepočíta sa po zmene uložených nastavení automatiky/domácnosti, po návrate do appky a každých 5 min. */
@Composable
internal fun rememberPresenceTick(): Int {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            tick++
            while (true) { delay(5 * 60_000L); tick++ }
        }
    }
    DisposableEffect(Unit) {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        val presencePrefs = PresenceStore.prefs(context)
        val housePrefs = HouseholdStore.listenPrefs(context)
        presencePrefs.registerOnSharedPreferenceChangeListener(l)
        housePrefs.registerOnSharedPreferenceChangeListener(l)
        onDispose {
            presencePrefs.unregisterOnSharedPreferenceChangeListener(l)
            housePrefs.unregisterOnSharedPreferenceChangeListener(l)
        }
    }
    return tick
}

/** Aktuálny čas obnovovaný každých [periodMs] – len kým je appka na obrazovke (na pozadí sa telefón nebudí). */
@Composable
internal fun rememberNow(periodMs: Long): Long {
    var now by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, periodMs) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = System.currentTimeMillis(); delay(periodMs) }
        }
    }
    return now
}

internal fun namesText(names: List<String>): String = when {
    names.isEmpty() -> "Nič"
    names.size <= 2 -> names.joinToString(", ")
    else -> names.take(2).joinToString(", ") + " a ďalšie ${names.size - 2}"
}

// Automatika: od 1.40.0 v Nastavenia › Automatika (SettingsUi.kt), stav pri odchode / zastavenej službe na Domove.

// ------------------------------------------------------------------ Kúrenie

@Composable
internal fun HeatTab(vm: HomeViewModel, onPage: (String) -> Unit) {
    val heat by vm.heat.ui.collectAsState()
    if (heat.configured) {
        HeatScreen(vm = vm, zoneId = null, onBack = null, onAccount = { onPage("heat-login") })
        return
    }
    TabPage("Kúrenie") {
        DomovCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconCircle(Sym.thermostat, on = false)
                Spacer(Modifier.width(12.dp))
                Text("Kúrenie zatiaľ nie je nastavené", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Ak máš kúrenie Honeywell evohome (appka Honeywell Home), uvidíš tu teplotu v každej miestnosti a môžeš ju meniť " +
                    "aj mimo domu. Stačí sa raz prihlásiť účtom Honeywell. Je to nepovinné – bez neho appka funguje normálne.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { onPage("heat-login") }, modifier = Modifier.fillMaxWidth()) { Text("Nastaviť kúrenie") }
        }
    }
}

// ------------------------------------------------------------------ Nastavenia

// SettingsTab a podstránky nastavení sú v SettingsUi.kt (od 1.39.0)

/**
 * Uložený zoznam zariadení sa nedal prečítať: ukladanie je zablokované, kým sa používateľ nerozhodne.
 * Možnosti: skúsiť obnoviť odloženú kópiu, poslať pôvodné dáta (na kontrolu / ručnú opravu), alebo začať odznova.
 */
@Composable
internal fun BrokenDevicesCard(vm: HomeViewModel) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    SectionLabel("Pozor – zoznam zariadení")
    DomovCard(padding = 10.dp, spacing = 0.dp) {
        SettingRow(
            Sym.warning, "Zoznam zariadení sa nepodarilo načítať",
            caption = "Pôvodné dáta sú odložené a zmeny zariadení sa zatiaľ neukladajú, aby sa neprepísali.",
        )
        SettingRow(Sym.sync, "Skúsiť obnoviť zoznam", caption = "Z odloženej kópie pôvodných dát", onClick = { result = vm.rescueDevices() })
        SettingRow(Sym.upload, "Poslať pôvodné dáta", caption = "Na kontrolu alebo ručnú opravu (obsahuje local keys)", onClick = {
            val raw = vm.brokenDevicesRaw() ?: return@SettingRow
            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_SUBJECT, "Domov – poškodený zoznam zariadení")
                .putExtra(android.content.Intent.EXTRA_TEXT, raw)
            context.startActivity(android.content.Intent.createChooser(send, "Poslať pôvodné dáta"))
        })
        SettingRow(Sym.delete, "Začať s prázdnym zoznamom", caption = "Pôvodné dáta ostanú odložené", onClick = { confirmDiscard = true })
    }
    result?.let { msg ->
        DomovAlert(
            onDismissRequest = { result = null },
            title = { Text("Obnovenie zoznamu") },
            text = { Text(msg) },
            confirmButton = { DialogPrimaryButton(onClick = { result = null }) { Text("OK") } },
        )
    }
    if (confirmDiscard) {
        DomovAlert(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Začať s prázdnym zoznamom?") },
            text = { Text("Appka začne ukladať nový zoznam zariadení. Pôvodné dáta ostanú odložené a obnoviť ich pôjde, kým sa zoznam znova nepoškodí.") },
            danger = true,
            confirmButton = { DialogPrimaryButton(onClick = { vm.discardBrokenDevices(); confirmDiscard = false }) { Text("Začať odznova") } },
            dismissButton = { DialogTextButton(onClick = { confirmDiscard = false }) { Text("Zrušiť") } },
        )
    }
}
