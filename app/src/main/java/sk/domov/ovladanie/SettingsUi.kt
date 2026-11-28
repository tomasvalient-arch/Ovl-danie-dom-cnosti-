@file:OptIn(ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/**
 * Nastavenia: prehľad podľa tém (zariadenia, automatika, domácnosť, upozornenia, kalendár a nákup, vzhľad, záloha, pomoc).
 * Podstránky idú do zásobníka HomeScreen, systémové Späť sa vráti sem. Kúrenie a Počítač otvoria existujúce obrazovky.
 */
object SettingsPage {
    const val DEVICES = "settings-devices"
    const val AUTOMATION = "settings-automation"
    const val HOUSEHOLD = "settings-household"
    const val SCREEN = "settings-screen"
    const val NOTIFY = "settings-notify"
    const val CALENDAR = "settings-calendar"
    const val DATA = "settings-data"
    const val HELP = "settings-help"
    const val ABOUT = "settings-about"
    const val CONNECTIONS = "settings-connections"
    /** Zoznam zariadení, ktoré majú typové upozornenia (nie vlastný priečinok typu). */
    const val DEVICES_NOTIFY = "settings-devices-notify"
    const val DEVICE_SET_PREFIX = "device-set:"

    fun deviceSettings(id: String): String = DEVICE_SET_PREFIX + id
    fun deviceSettingsId(page: String): String? = page.takeIf { it.startsWith(DEVICE_SET_PREFIX) }?.removePrefix(DEVICE_SET_PREFIX)

    fun phonesText(n: Int): String = when (n) {
        1 -> "Len tento telefón"
        2, 3, 4 -> "$n telefóny"
        else -> "$n telefónov"
    }
}

@Composable
internal fun SettingsTab(vm: HomeViewModel, devices: List<Device>, cloudSet: Boolean, onPage: (String) -> Unit) {
    val context = LocalContext.current
    val heat by vm.heat.ui.collectAsState()
    val weather by vm.weather.ui.collectAsState()
    val broken by vm.devicesBroken.collectAsState()
    // stavy sa prečítajú pri každom návrate na zoznam (podstránka zoznam odstráni z kompozície)
    val version = remember { appVersion(context) }
    val phones = remember { HouseholdStore.members(context).size + 1 }
    val pcs = remember { runCatching { PcStore.list(context).size }.getOrDefault(0) }
    val moon = remember { MoonStore.visible(context) }
    val brief = remember { MorningBriefStore.enabled(context) to MorningBriefStore.time(context) }
    val eventsTime = remember { EventsStore.time(context) }
    val auto = remember { runCatching { BackupManager.auto(context) }.getOrNull() }
    val autoTick = rememberPresenceTick()
    val autoCfg = remember(autoTick) { PresenceStore.config(context) }
    val autoStatus = remember(autoTick) { PresenceStore.status(context) }
    val autoRunning = remember(autoTick) { PresenceService.isRunning || vm.demo.value }

    TabPage("Nastavenia") {
        if (broken) BrokenDevicesCard(vm)

        DomovCard(padding = 10.dp, spacing = 0.dp) {
            CategoryRow(
                Sym.lightbulb, "Zariadenia",
                "${Scenes.devicesText(devices.size)} · všetky rovnako, typové voľby v detaile zariadenia",
            ) { onPage(SettingsPage.DEVICES) }
        }

        SectionLabel("Prepojenia a účty")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            CategoryRow(
                Sym.cloud, "Tuya cloud",
                if (cloudSet) "Pripojené · vypínanie aj mimo domu" else "Nepovinné · vypínanie aj mimo domu",
            ) { onPage("cloud") }
            CategoryRow(
                Sym.thermostat, "Kúrenie Honeywell",
                if (heat.configured) "Prihlásený · ${heat.email}" else "Nepovinné · nenastavené",
            ) { onPage("heat-login") }
            CategoryRow(
                Sym.devicesOther, "Počítač",
                if (pcs > 0) "Spárované: $pcs · párovanie a správa PC" else "Zatiaľ žiadny PC · párovanie a správa",
            ) { onPage("pc") }
        }

        DomovCard(padding = 10.dp, spacing = 0.dp) {
            CategoryRow(
                Sym.locationHome, "Automatika",
                when {
                    !autoCfg.enabled -> "Vypnutá · príchod a odchod podľa domácej WiFi"
                    !autoRunning -> "Zapnutá, ale nebeží – otvor a spusti"
                    else -> "Beží · " + presenceStateText(autoStatus, autoCfg, System.currentTimeMillis()).replaceFirstChar { it.lowercase() } +
                        " · odchod po ${autoCfg.departDelayMin} min"
                },
                iconOn = autoCfg.enabled && autoRunning,
                error = autoCfg.enabled && !autoRunning,
            ) { onPage(SettingsPage.AUTOMATION) }
            CategoryRow(
                Sym.groups, "Domácnosť a synchronizácia",
                "${SettingsPage.phonesText(phones)} · kto je doma, zdieľanie, kalendár, nákup a jedálniček",
            ) { onPage("household") }
            CategoryRow(
                Sym.notifications, "Upozornenia",
                (if (brief.first) "Ranný prehľad o ${clockMinutes(brief.second)}" else "Ranný prehľad vypnutý") +
                    " · príchod, odchod a zariadenia",
            ) { onPage(SettingsPage.NOTIFY) }
            CategoryRow(
                Sym.calendarToday, "Kalendár a nákup",
                "Spoločný kalendár, nákup, jedálniček a denné upozornenie o ${clockMinutes(eventsTime)}",
            ) { onPage(SettingsPage.CALENDAR) }
            val place = weather.location?.name?.ifBlank { null }
            CategoryRow(
                Sym.dashboardCustomize, "Čo je na karte Domov",
                when {
                    !weather.settings.visible -> "Počasie skryté"
                    place != null -> "Počasie: $place"
                    else -> "Počasie: miesto nenastavené"
                } + (if (moon) " · fáza mesiaca" else ""),
            ) { onPage(SettingsPage.SCREEN) }
        }

        DomovCard(padding = 10.dp, spacing = 0.dp) {
            CategoryRow(
                Sym.backup, "Záloha",
                if (auto?.enabled == true) "Automatická záloha zapnutá · export zariadení" else "Záloha, obnovenie a export zariadení",
            ) { onPage(SettingsPage.DATA) }
            CategoryRow(Sym.help, "Pomoc a o aplikácii", "Časté otázky · Domov $version") { onPage(SettingsPage.HELP) }
        }
    }
}

/** Riadok kategórie: ikona v krúžku, názov, stav a šípka. */
@Composable
internal fun CategoryRow(icon: ImageVector, title: String, caption: String, iconOn: Boolean = false, error: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconCircle(icon, on = iconOn, error = error)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Sym.chevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 4.dp).size(20.dp))
    }
}

/** Spoločné rozloženie podstránky nastavení: horná lišta so Späť, nadpis (a stav) a posúvateľný obsah. */
@Composable
internal fun SettingsSubPage(title: String, onBack: () -> Unit, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Sym.arrowBack, contentDescription = "Späť") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ------------------------------------------------------------------ podstránky

@Composable
internal fun SettingsDevicesPage(
    vm: HomeViewModel,
    wifiOn: Boolean,
    cloudSet: Boolean,
    onBack: () -> Unit,
    onPage: (String) -> Unit,
    onSync: () -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onAddManual: () -> Unit,
    notifyOnly: Boolean = false,
) {
    val devices by vm.devices.collectAsState()
    val states by vm.states.collectAsState()
    val known by vm.known.collectAsState()
    fun valuesOf(d: Device) = deviceValues(d, states, known)
    val listed = if (notifyOnly) devices.filter { DeviceSettingsRegistry.hasNotifications(it, valuesOf(it)) } else devices
    val online = listed.count { states[it.id] is DeviceState.Online }
    val title = if (notifyOnly) "Upozornenia zariadení" else "Zariadenia"
    val subtitle = if (notifyOnly) {
        if (listed.isEmpty()) "Žiadne zariadenie s vlastnými upozorneniami"
        else Scenes.devicesText(listed.size)
    } else Scenes.devicesText(devices.size) + if (wifiOn && devices.isNotEmpty()) " · odpovedá na WiFi: $online" else ""
    SettingsSubPage(title, onBack, subtitle = subtitle) {
        if (notifyOnly) {
            InfoHint("Upozornenia konkrétneho zariadenia (napr. fritézy) nastavíš v jeho stránke. Nový typ zariadenia sa tu objaví sám, bez vlastného priečinka.")
        }
        if (listed.isEmpty() && notifyOnly) {
            DomovCard(padding = 16.dp) {
                Text(
                    "V appke zatiaľ nie je zariadenie s vlastnými upozorneniami. Keď pridáš napríklad fritézu, objaví sa v Nastavenia › Zariadenia a tu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (listed.isNotEmpty()) {
            SectionLabel(if (notifyOnly) "Zariadenia s upozorneniami" else "Tvoje zariadenia")
            Groups.arrange(listed, ::valuesOf).forEach { (group, items) ->
                Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DomovCard(padding = 10.dp, spacing = 0.dp) {
                    items.forEach { d ->
                        val v = valuesOf(d)
                        val st = states[d.id]
                        val cap = buildString {
                            append(deviceKindTitle(d, v))
                            when (st) {
                                is DeviceState.Online -> append(" · online")
                                is DeviceState.Error -> append(" · offline")
                                else -> {}
                            }
                            if (notifyOnly) append(" · upozornenia v nastaveniach zariadenia")
                        }
                        SettingRow(
                            kindIcon(d, v, st is DeviceState.Online),
                            d.name,
                            caption = cap,
                            onClick = { onPage(SettingsPage.deviceSettings(d.id)) },
                        )
                    }
                }
            }
        } else if (!notifyOnly) {
            DomovCard(padding = 16.dp) {
                Text(
                    "Zatiaľ žiadne zariadenie. Pridaj ho hľadaním v sieti, importom alebo ručne.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!notifyOnly) {
        SectionLabel("Pridať zariadenia")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(Sym.search, "Hľadať v sieti", caption = if (wifiOn) "Nájde zariadenia na tejto WiFi" else "Len na domácej WiFi", enabled = wifiOn, onClick = onScan)
            SettingRow(Sym.sync, "Synchronizovať s Tuya cloudom", caption = if (cloudSet) "Doplní kľúče, názvy a chýbajúce zariadenia" else "Najprv nastav Tuya cloud v Prepojenia a účty", onClick = onSync)
            SettingRow(Sym.uploadFile, "Import zariadení", caption = "Zo súboru, iného telefónu alebo tinytuya", onClick = onImport)
        }
        SectionLabel("Pokročilé")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(Sym.edit, "Pridať ručne", caption = "IP adresa, Device ID, local key", onClick = onAddManual)
        }
        InfoHint("Export zoznamu zariadení je v Nastavenia › Záloha. Účty Tuya, Honeywell a Počítač sú v Nastavenia › Prepojenia a účty.")
        }
    }
}

@Composable
internal fun SettingsScreenPage(vm: HomeViewModel, onBack: () -> Unit, onPage: (String) -> Unit) {
    val context = LocalContext.current
    val weather by vm.weather.ui.collectAsState()
    var moonVisible by remember { mutableStateOf(MoonStore.visible(context)) }
    SettingsSubPage("Čo je na karte Domov", onBack, subtitle = "Počasie a fáza mesiaca") {
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(
                Sym.partlyCloudyDay, "Počasie",
                caption = weather.location?.name?.ifBlank { null } ?: if (weather.settings.visible) "Miesto nie je nastavené" else "Skryté na Domove",
                onClick = { onPage("weather") },
            )
            SettingRow(
                Sym.darkMode, "Fáza mesiaca",
                caption = "Úzky pás pod počasím na karte Domov",
                onClick = { moonVisible = !moonVisible; MoonStore.setVisible(context, moonVisible) },
                trailing = { DomovSwitch(checked = moonVisible, onCheckedChange = { moonVisible = it; MoonStore.setVisible(context, it) }) },
            )
        }
    }
}

@Composable
internal fun SettingsNotifyPage(onBack: () -> Unit, onPage: (String) -> Unit) {
    val context = LocalContext.current
    val eventsTime = remember { EventsStore.time(context) }
    val allowed by rememberNotificationsAllowed()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val brief = remember { MorningBriefStore.enabled(context) to MorningBriefStore.time(context) }
    val notifyTick = rememberPresenceTick()
    val notifyDoneNow = remember(notifyTick) { runCatching { PresenceStore.config(context).notifyDone }.getOrDefault(false) }
    var shopNotify by remember { mutableStateOf(ShoppingNotify.enabled(context)) }

    fun setShopNotify(on: Boolean) {
        shopNotify = on
        ShoppingNotify.setEnabled(context, on)
        if (on && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    SettingsSubPage("Upozornenia", onBack, subtitle = "Čo ti appka posiela") {
        SectionLabel("Ranný prehľad")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(
                Sym.sunny, "Ranný prehľad",
                caption = if (brief.first) "Zapnutý · každé ráno o ${clockMinutes(brief.second)} · počasie, udalosti, nákup"
                else "Vypnutý · jedna notifikácia ráno, len v tomto telefóne",
                onClick = { onPage(AutoPage.BRIEF) },
            )
        }

        SectionLabel("Spoločný nákup")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(
                CartIcon, "Zmeny v spoločnom nákupe",
                caption = if (shopNotify) "Zapnuté · keď člen domácnosti upraví spoločný zoznam, aj na zamknutej obrazovke"
                else "Vypnuté · zoznam sa obnoví až po otvorení appky",
                onClick = { setShopNotify(!shopNotify) },
                trailing = { DomovSwitch(checked = shopNotify, onCheckedChange = { setShopNotify(it) }) },
            )
        }

        SectionLabel("Ďalšie upozornenia")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(
                Sym.locationHome, "Upozornenie po príchode a odchode",
                caption = if (notifyDoneNow) "Zapnuté · po vykonaní akcie" else "Vypnuté · po vykonaní akcie",
                onClick = { onPage(AutoPage.NOTIFY_DONE) },
            )
            SettingRow(
                Sym.notifications, "Upozornenia v telefóne",
                caption = if (allowed) "Povolené · systémové nastavenia" else "Vypnuté – ťukni a povoľ ich",
                onClick = {
                    if (!allowed && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else openNotificationSettings(context)
                },
            )
        }
        InfoHint("Denné upozornenie kalendára sa nastavuje v Nastavenia › Kalendár a nákup (teraz o ${clockMinutes(eventsTime)}). Upozornenia fritézy a ďalších typov nastavíš v nastaveniach konkrétneho zariadenia (Nastavenia › Zariadenia).")
    }
}

@Composable
internal fun SettingsDataPage(hasDevices: Boolean, onBack: () -> Unit, onPage: (String) -> Unit, onExport: () -> Unit) {
    SettingsSubPage("Záloha", onBack, subtitle = "Záloha, obnovenie a export zariadení") {
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(Sym.backup, "Záloha a obnovenie", caption = "Nastavenia a zariadenia v zálohe", onClick = { onPage("backup") })
            SettingRow(Sym.upload, "Export zariadení", caption = "Uložiť alebo poslať zoznam zariadení", enabled = hasDevices, onClick = onExport)
        }
        Text(
            "Import zariadení je v Nastavenia › Zariadenia, zdieľanie appky cez QR v Nastavenia › Domácnosť a synchronizácia.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

@Composable
internal fun SettingsHelpPage(onBack: () -> Unit, onPage: (String) -> Unit) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    SettingsSubPage("Pomoc a o aplikácii", onBack) {
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(Sym.help, "Pomoc", caption = "Ako appka funguje, časté otázky", onClick = { onPage("help") })
            SettingRow(Sym.info, "O aplikácii", caption = "Domov $version", onClick = { onPage(SettingsPage.ABOUT) })
        }
    }
}

// ------------------------------------------------------------------ Automatika (od 1.40.0, predtým karta v lište)

/**
 * Nastavenia › Automatika: prehľad a odkazy. Každá voľba otvára vlastnú obrazovku (od 1.47).
 * Členovia domácnosti sú v Nastavenia › Domácnosť (tu je na ne odkaz).
 */
@Composable
internal fun SettingsAutomationPage(vm: HomeViewModel, onBack: () -> Unit, onPage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val devices by vm.devices.collectAsState()
    val states by vm.states.collectAsState()
    val known by vm.known.collectAsState()
    val demo by vm.demo.collectAsState()
    var extra by remember { mutableIntStateOf(0) }
    val tick = rememberPresenceTick() + extra
    val cfg = remember(tick) { PresenceStore.config(context) }
    val status = remember(tick) { PresenceStore.status(context) }
    val running = remember(tick) { PresenceService.isRunning } || demo
    val history = remember(tick) { PresenceStore.history(context) }
    val members = remember(tick) { HouseholdStore.members(context) }
    val sun by rememberSunInfo(tick, cfg)
    androidx.compose.runtime.LaunchedEffect(status.state, status.since) {
        while (status.state == PresenceState.LEAVING) { kotlinx.coroutines.delay(20_000); extra++ }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun values(d: Device): Map<String, Any> = deviceValues(d, states, known)
    val departNames = AutoDevices.departIds(cfg, devices, ::values).let { ids -> devices.filter { it.id in ids }.map { it.name } }
    val arriveNames = AutoDevices.arriveIds(cfg, devices, ::values).let { ids -> devices.filter { it.id in ids }.map { it.name } }
    val goWifi = { onPage(AutoPage.WIFI) }
    val now = System.currentTimeMillis()
    val on = cfg.enabled || demo

    fun toggle(enable: Boolean) {
        if (cfg.home == null) { goWifi(); return }   // bez domácej WiFi sa zapnúť nedá – najprv Domáca WiFi
        if (enable && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (!enable) {
            scope.launch(LogOnError) { PresenceActions.guardClearAll(context) }
            PresenceStore.saveStatus(context, PresenceStatus())
        }
        PresenceStore.log(context, if (enable) "Automatika zapnutá." else "Automatika vypnutá.")
        PresenceStore.saveConfig(context, cfg.copy(enabled = enable))
        PresenceService.sync(context)
        extra++
    }

    SettingsSubPage("Automatika", onBack, subtitle = "Príchod a odchod podľa domácej WiFi") {
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(
                Sym.locationHome, "Príchod a odchod",
                caption = when {
                    cfg.home == null -> "Najprv nastav domácu WiFi"
                    !on -> "Vypnuté · zapne svetlá pri príchode a vypne pri odchode"
                    !running -> "Zapnuté, ale služba na pozadí nebeží"
                    else -> "Beží · " + presenceStateText(status, cfg, now)
                },
                onClick = { toggle(!cfg.enabled) },
                trailing = { DomovSwitch(checked = cfg.enabled, onCheckedChange = { toggle(it) }) },
            )
            if (cfg.enabled && !running) {
                SettingRow(
                    Sym.warning, "Automatika nebeží",
                    caption = "Príchod ani odchod sa teraz nevyhodnotí.",
                    trailing = {
                        androidx.compose.material3.Button(
                            onClick = { PresenceService.sync(context); scope.launch { kotlinx.coroutines.delay(1500); extra++ } },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) { Text("Spustiť") }
                    },
                )
            }
        }

        SectionLabel("Kedy")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(Sym.locationOn, "Domáca WiFi", caption = cfg.home?.let { h -> h.ssid.ifBlank { "Uložená" } } ?: "Zatiaľ nenastavená", onClick = goWifi)
            SettingRow(Sym.timer, "Odchod po", caption = "Čas po odpojení od domácej WiFi", value = "${cfg.departDelayMin} min", onClick = { onPage(AutoPage.DELAY) })
            SettingRow(
                Sym.groups, "Kto sa počíta",
                caption = (if (members.isEmpty()) "Len tento telefón" else "${SettingsPage.phonesText(members.size + 1)} · vypne sa, keď odídu všetci") +
                    " · členov spravuješ v Nastavenia › Domácnosť a synchronizácia",
            )
        }

        SectionLabel("Čo sa stane")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(Sym.powerSettingsNew, "Pri odchode vypnúť", caption = namesText(departNames), onClick = { onPage(AutoPage.DEPART) })
            SettingRow(Sym.doorOpen, "Pri príchode zapnúť", caption = namesText(arriveNames), onClick = { onPage(AutoPage.ARRIVE) })
            SettingRow(
                Sym.wbTwilight, "Len keď je tma",
                caption = when {
                    !cfg.onlyWhenDark -> "Vypnuté · príchod zapína vždy"
                    cfg.darkMode == DarkMode.MANUAL -> "Ručne · od ${Darkness.hhmm(cfg.darkFrom)} do ${Darkness.hhmm(cfg.darkTo)}"
                    else -> "Podľa slnka" + (sun?.let { i -> if (i.setText != null && i.riseText != null) " · dnes od ${i.setText} do ${i.riseText}" else "" } ?: "")
                },
                onClick = { onPage(AutoPage.DARK) },
            )
            SettingRow(Sym.bedtime, "Scéna Noc", caption = "Čo vypne a čo nechá zapnuté scéna Noc", onClick = { onPage("night") })
        }

        SectionLabel("Pokročilé")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(
                Sym.shieldMoon, "Poistka bez cloudu",
                caption = if (cfg.guardEnabled) "Zapnutá · ${Scenes.devicesText(cfg.guardIds.size)}" else "Vypnutá · nepovinné",
                onClick = { onPage(AutoPage.GUARD) },
            )
            SettingRow(Sym.playArrow, "Vyskúšať príchod / odchod", caption = "Spustí akciu hneď, bez čakania", onClick = { onPage(AutoPage.TRY) })
            SettingRow(
                Sym.history, "História",
                caption = history.firstOrNull()?.let { e -> "${shortTime(e.time, now)} · ${e.text}" } ?: "Zatiaľ nič",
                onClick = { onPage(AutoPage.HISTORY) },
            )
        }
        val batteryOk = remember(tick) { isIgnoringBattery(context) }
        if (cfg.enabled && !batteryOk) {
            SectionLabel("Pozadie")
            DomovCard(padding = 10.dp, spacing = 0.dp) {
                SettingRow(
                    Sym.warning, "Optimalizácia batérie je zapnutá",
                    caption = "Android môže automatiku na pozadí uspať. Odporúčam ju pre Domov vypnúť.",
                    onClick = { openBatterySettings(context) },
                )
            }
        }
        Text(
            "Telefóny domácnosti spravuješ v Nastavenia › Domácnosť a synchronizácia. Ranný prehľad je v Nastavenia › Upozornenia.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}
