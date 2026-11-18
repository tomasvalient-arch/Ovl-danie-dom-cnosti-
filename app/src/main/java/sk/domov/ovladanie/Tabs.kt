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
    AUTOMATION("Automatika", Sym.locationHome, Sym.locationHome),
    HEAT("Kúrenie", Sym.thermostat, Sym.thermostat),
    SETTINGS("Nastavenia", Sym.settings, Sym.settings),
}

/** Plávajúca dolná lišta: zaoblená pilulka, vybraná karta je jantárová. */
@Composable
internal fun HomeBottomBar(selected: HomeTab, onSelect: (HomeTab) -> Unit, automationAlert: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(34.dp),
            color = scheme.surfaceContainerLow,
            border = BorderStroke(1.dp, scheme.surfaceVariant),
            shadowElevation = 6.dp,
        ) {
            Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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
                        // červená bodka na karte Automatika, keď je zapnutá, ale služba nebeží
                        if (t == HomeTab.AUTOMATION && automationAlert) {
                            androidx.compose.material3.BadgedBox(badge = { androidx.compose.material3.Badge() }) {
                                Icon(if (sel) t.selectedIcon else t.icon, contentDescription = "Automatika nebeží", tint = tint, modifier = Modifier.size(22.dp))
                            }
                        } else {
                            Icon(if (sel) t.selectedIcon else t.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        }
                        Text(t.label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Spoločné rozloženie kariet: veľký nadpis ako na Domove a posúvateľný obsah. */
@Composable
private fun TabPage(title: String, content: @Composable () -> Unit) {
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

private fun namesText(names: List<String>): String = when {
    names.isEmpty() -> "Nič"
    names.size <= 2 -> names.joinToString(", ")
    else -> names.take(2).joinToString(", ") + " a ďalšie ${names.size - 2}"
}

// ------------------------------------------------------------------ Automatika

@Composable
internal fun AutomationTab(vm: HomeViewModel, devices: List<Device>, pagesOpen: Int, onPage: (String) -> Unit) {
    val context = LocalContext.current
    val demo by vm.demo.collectAsState()
    val known by vm.known.collectAsState()
    val states by vm.states.collectAsState()
    val scope = rememberCoroutineScope()
    var extra by remember { mutableIntStateOf(0) }
    val tick = rememberPresenceTick() + extra
    val cfg = remember(pagesOpen, tick) { PresenceStore.config(context) }
    val status = remember(pagesOpen, tick) { PresenceStore.status(context) }
    val serviceRunning = remember(pagesOpen, tick) { PresenceService.isRunning }
    val history = remember(pagesOpen, tick) { PresenceStore.history(context) }
    val members = remember(pagesOpen, tick) { HouseholdStore.members(context) }
    LaunchedEffect(status.state, status.since) {
        while (status.state == PresenceState.LEAVING) { delay(20_000); extra++ }
    }
    fun values(d: Device): Map<String, Any> = deviceValues(d, states, known)
    val now = System.currentTimeMillis()
    val on = cfg.enabled || demo
    val running = serviceRunning || demo
    val departIds = AutoDevices.departIds(cfg, devices, ::values)
    val arriveIds = AutoDevices.arriveIds(cfg, devices, ::values)
    val departNames = devices.filter { it.id in departIds }.map { it.name }
    val arriveNames = devices.filter { it.id in arriveIds }.map { it.name }
    val presence = { onPage("presence") }
    val sun by rememberSunInfo(tick, cfg)

    TabPage("Automatika") {
        AutomationCard(
            on = on,
            running = running,
            title = when {
                !on -> "Príchod a odchod je vypnutý"
                !running -> "Automatika nebeží"
                else -> "Automatika beží · " + presenceStateText(status, cfg, now)
            },
            text = when {
                on && !running -> "Je zapnutá, ale služba na pozadí nebeží – príchod ani odchod sa teraz nevyhodnotí."
                on -> "Odchod sa vyhodnotí ${cfg.departDelayMin} min po odpojení od domácej WiFi" +
                    if (members.isNotEmpty()) ", ak nikto neostane doma" else ""
                else -> "Zapni automatické svietenie pri príchode a zhasínanie pri odchode"
            },
            onStart = {
                PresenceService.sync(context)
                scope.launch { delay(1500); extra++ }
            },
            onClick = presence,
        )

        SectionLabel("Príchod a odchod")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(
                Sym.locationOn, "Domáca WiFi",
                caption = cfg.home?.let { h -> (h.ssid.ifBlank { "Uložená" }) + " · odchod po ${cfg.departDelayMin} min" } ?: "Zatiaľ nenastavená",
                onClick = presence,
            )
            SettingRow(Sym.powerSettingsNew, "Pri odchode vypnúť", caption = namesText(departNames), onClick = presence)
            SettingRow(
                Sym.doorOpen, "Pri príchode zapnúť",
                caption = namesText(arriveNames) + if (cfg.onlyWhenDark && arriveNames.isNotEmpty()) " · len keď je tma" else "",
                onClick = presence,
            )
            sun?.let { info ->
                SettingRow(
                    Sym.wbTwilight, "Východ ${info.riseText ?: "–"} · Západ ${info.setText ?: "–"}",
                    caption = info.place.label().replaceFirstChar { it.uppercase() },
                    onClick = presence,
                )
            }
            SettingRow(
                Sym.groups, "Domácnosť – viac telefónov",
                caption = if (members.isEmpty()) "Len tento telefón · pridať člena" else "${members.size + 1} telefóny · vypne sa, keď odídu všetci",
                onClick = { onPage("household") },
            )
            SettingRow(
                Sym.shieldMoon, "Poistka bez cloudu",
                caption = if (cfg.guardEnabled) "Zapnutá · ${Scenes.devicesText(cfg.guardIds.size)}" else "Vypnutá · nepovinné",
                onClick = presence,
            )
        }

        SectionLabel("Scény")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(Sym.bedtime, "Scéna Noc", caption = "Čo vypne a čo nechá zapnuté tlačidlo Noc", onClick = { onPage("night") })
        }

        SectionLabel("Posledné udalosti")
        DomovCard(padding = 14.dp, spacing = 8.dp) {
            if (history.isEmpty()) {
                Text("Zatiaľ nič.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            history.take(3).forEach { e ->
                Row {
                    Text(shortTime(e.time, now), style = MaterialTheme.typography.bodySmall, modifier = Modifier.widthIn(min = 78.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(e.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = presence, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Celá história a nastavenia") }
        }
    }
}

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

@Composable
internal fun SettingsTab(
    vm: HomeViewModel,
    devices: List<Device>,
    wifiOn: Boolean,
    cloudSet: Boolean,
    onPage: (String) -> Unit,
    onSync: () -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onAddManual: () -> Unit,
) {
    val context = LocalContext.current
    val heat by vm.heat.ui.collectAsState()
    val weather by vm.weather.ui.collectAsState()
    val version = remember { appVersion(context) }
    var moonVisible by remember { mutableStateOf(MoonStore.visible(context)) }

    TabPage("Nastavenia") {
        SectionLabel("Zariadenia")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(Sym.sync, "Synchronizovať s Tuya cloudom", caption = if (cloudSet) "Doplní kľúče, názvy a chýbajúce zariadenia" else "Najprv nastav Tuya cloud", onClick = onSync)
            SettingRow(Sym.search, "Hľadať zariadenia v sieti", caption = if (wifiOn) "Nájde zariadenia na tejto WiFi" else "Len na domácej WiFi", enabled = wifiOn, onClick = onScan)
            SettingRow(Sym.download, "Import zariadení", caption = "Zo súboru, iného telefónu alebo tinytuya", onClick = onImport)
            SettingRow(Sym.edit, "Pridať ručne", caption = "Pre pokročilých – IP adresa, Device ID, local key", onClick = onAddManual)
            SettingRow(Sym.upload, "Export zariadení", caption = "Uložiť alebo poslať zoznam zariadení", enabled = devices.isNotEmpty(), onClick = onExport)
        }

        SectionLabel("Domácnosť")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(Sym.groups, "Domácnosť a členovia", caption = "Kto je doma, pridať alebo odstrániť člena", onClick = { onPage("household") })
            SettingRow(Sym.upload, "Zdieľať appku (QR)", caption = "Druhý telefón si stiahne appku a vybrané zariadenia", onClick = { onPage("share") })
            SettingRow(Sym.download, "Pripojiť sa k domácnosti", caption = "Naskenovať QR kód z iného telefónu", onClick = { onPage("join") })
        }

        SectionLabel("Pripojené služby")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(Sym.cloud, "Tuya cloud", caption = if (cloudSet) "Pripojené · vypínanie aj mimo domu" else "Nepovinné · vypínanie aj mimo domu", onClick = { onPage("cloud") })
            SettingRow(Sym.thermostat, "Kúrenie Honeywell", caption = if (heat.configured) "Prihlásený: ${heat.email}" else "Nepovinné · nenastavené", onClick = { onPage("heat-login") })
            SettingRow(
                Sym.partlyCloudyDay, "Počasie",
                caption = weather.location?.name?.ifBlank { null } ?: if (weather.settings.visible) "Miesto nie je nastavené" else "Skryté na Domove",
                onClick = { onPage("weather") },
            )
        }

        SectionLabel("Aplikácia")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(
                Sym.bedtime, "Zobraziť fázu mesiaca",
                caption = "Úzky pás pod počasím na Domove",
                onClick = { moonVisible = !moonVisible; MoonStore.setVisible(context, moonVisible) },
                trailing = { DomovSwitch(checked = moonVisible, onCheckedChange = { moonVisible = it; MoonStore.setVisible(context, it) }) },
            )
            SettingRow(Sym.lock, "Záloha a obnovenie", caption = "Nastavenia a zariadenia v zálohe", onClick = { onPage("backup") })
            SettingRow(Sym.help, "Pomoc", caption = "Ako appka funguje, časté otázky", onClick = { onPage("help") })
            SettingRow(Sym.info, "O aplikácii", caption = "Domov $version")
        }
    }
}

