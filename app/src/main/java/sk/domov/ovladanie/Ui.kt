@file:OptIn(ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import androidx.compose.foundation.layout.heightIn
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(vm: HomeViewModel) {
    val devices by vm.devices.collectAsState()
    val states by vm.states.collectAsState()
    val wifi by vm.wifi.collectAsState()
    val notice by vm.notice.collectAsState()
    val demo by vm.demo.collectAsState()
    val wifiOn = wifi != null || demo

    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    // karta dolnej lišty (Domov / Automatika / Kúrenie / Nastavenia)
    var tab by rememberSaveable { mutableStateOf(HomeTab.HOME) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var showScan by rememberSaveable { mutableStateOf(false) }
    var addFound by remember { mutableStateOf<FoundDevice?>(null) }
    // zapnutie ohrievača z dlaždice sa potvrdzuje (zariadenie, text, príkaz)
    var pendingPower by remember { mutableStateOf<Triple<Device, String, Map<String, Any>>?>(null) }
    val cloud by vm.cloud.collectAsState()
    val scope = rememberCoroutineScope()
    // otvorené obrazovky Tuya cloud / návod / pomoc (posledná je navrchu)
    val pages = remember { mutableStateListOf<String>() }
    var showSync by rememberSaveable { mutableStateOf(false) }
    var noCloudFor by remember { mutableStateOf<FoundDevice?>(null) }
    var addHint by remember { mutableStateOf<String?>(null) }
    val cloudBusy = remember { mutableStateListOf<String>() }

    /** „Pridať“ pri nájdenom zariadení: s cloudom jedným ťuknutím, bez neho ručné zadanie. */
    fun addFoundDevice(found: FoundDevice) {
        if (cloud == null) {
            noCloudFor = found
            return
        }
        if (found.devId in cloudBusy) return
        cloudBusy += found.devId
        scope.launch(LogOnError) {
            val error = vm.addFromCloud(found)
            cloudBusy -= found.devId
            if (error != null) {
                addHint = "Z Tuya cloudu sa to nepodarilo: $error\n\nIP adresu, Device ID a verziu som doplnil, zadaj názov a local key ručne."
                addFound = found
            }
        }
    }

    val open = devices.find { it.id == openId }
    BackHandler(enabled = open != null) { openId = null }
    BackHandler(enabled = open == null && pages.isNotEmpty()) { pages.removeAt(pages.size - 1) }
    // na inej karte ako Domov sa Späť najprv vráti na Domov, až potom zavrie appku
    BackHandler(enabled = open == null && pages.isEmpty() && tab != HomeTab.HOME) { tab = HomeTab.HOME }

    if (open == null && pages.isNotEmpty()) {
        when (pages.last()) {
            "cloud" -> CloudSettingsScreen(
                vm = vm,
                onBack = { pages.removeAt(pages.size - 1) },
                onGuide = { pages += "guide" },
            )
            "guide" -> CloudGuideScreen(
                onBack = { pages.removeAt(pages.size - 1) },
                onEnterKeys = {
                    // ak sme prišli z nastavení, vrátime sa tam; inak ich otvoríme
                    pages.removeAt(pages.size - 1)
                    if (pages.lastOrNull() != "cloud") pages += "cloud"
                },
            )
            "presence" -> PresenceScreen(
                vm = vm,
                onBack = { pages.removeAt(pages.size - 1) },
                onCloud = { pages += "cloud" },
                onHousehold = { pages += "household" },
            )
            "household" -> HouseholdScreen(
                onBack = { pages.removeAt(pages.size - 1) },
                onShare = { pages += "share" },
                onJoin = { pages += "join" },
            )
            "share" -> ShareAppScreen(vm = vm, onBack = { pages.removeAt(pages.size - 1) })
            "join" -> JoinHouseholdScreen(vm = vm, onBack = { pages.removeAt(pages.size - 1) })
            "night" -> NightSceneScreen(vm = vm, onBack = { pages.removeAt(pages.size - 1) })
            "shopping" -> ShoppingScreen(onBack = { pages.removeAt(pages.size - 1) })
            "backup" -> BackupScreen(onBack = { pages.removeAt(pages.size - 1) })
            "weather" -> WeatherSettingsScreen(vm.weather, onBack = { pages.removeAt(pages.size - 1) })
            "heat-login" -> HeatLoginScreen(
                vm = vm,
                onBack = { pages.removeAt(pages.size - 1) },
                onDone = {
                    // po prihlásení rovno na prehľad kúrenia: karta Kúrenie, alebo stránka kúrenia, ak sme prišli odinakiaľ
                    pages.removeAt(pages.size - 1)
                    if (pages.isEmpty()) tab = HomeTab.HEAT
                    else if (pages.lastOrNull()?.startsWith("heat") != true) pages += "heat"
                },
            )
            "heat" -> HeatScreen(vm = vm, zoneId = null, onBack = { pages.removeAt(pages.size - 1) }, onAccount = { pages += "heat-login" })
            else -> if (pages.last().startsWith("heat:")) {
                HeatScreen(vm = vm, zoneId = pages.last().removePrefix("heat:"), onBack = { pages.removeAt(pages.size - 1) }, onAccount = { pages += "heat-login" })
            } else HelpScreen(onBack = { pages.removeAt(pages.size - 1) }, onGuide = { pages += "guide" })
        }
    } else if (open != null) {
        DeviceScreen(
            device = open,
            state = states[open.id] ?: DeviceState.Loading,
            wifiOn = wifiOn,
            notice = notice,
            vm = vm,
            onBack = { openId = null },
        )
    } else {
        Scaffold(
            bottomBar = {
                // červená bodka na karte Automatika, keď je zapnutá, ale služba nebeží (karta na Domove už nie je)
                val ctx = LocalContext.current
                val autoAlert = remember(tab, pages.size, states) { PresenceStore.config(ctx).enabled && !PresenceService.isRunning && !demo }
                HomeBottomBar(selected = tab, onSelect = { tab = it }, automationAlert = autoAlert)
            },
        ) { tabPadding ->
            Box(Modifier.fillMaxSize().padding(tabPadding).consumeWindowInsets(tabPadding)) {
                when (tab) {
                    HomeTab.HOME ->
                    HomeMain(
                        vm = vm,
                        devices = devices,
                        states = states,
                        wifiOn = wifiOn,
                        notice = notice,
                        cloudSet = cloud != null,
                        pagesOpen = pages.size,
                        onOpen = { openId = it },
                        onPage = { pages += it },
                        onAdd = { showAdd = true },
                        onScan = { showScan = true; vm.startScan() },
                        onImport = { showImport = true },
                        onTogglePower = { device, dp, on ->
                            val values = (states[device.id] as? DeviceState.Online)?.values ?: emptyMap()
                            // svetlo s podhľadom: vypínač na dlaždici zapne hlavné svetlo, vypne všetko
                            val multi = LightChannels.detect(device, values)
                            val cmd: Map<String, Any> = when {
                                multi == null -> mapOf(dp to on)
                                on -> LightChannels.setOn(multi, multi.main, true, values)
                                else -> LightChannels.allOff(multi, values)
                            }
                            val template = Templates.detect(values, device.schema, device.category)
                            val warning = Templates.confirmation(template, dp, cmd)
                            if (warning != null) pendingPower = Triple(device, warning, cmd) else vm.setValues(device, cmd)
                        },
                    )
                    HomeTab.AUTOMATION -> AutomationTab(vm = vm, devices = devices, pagesOpen = pages.size, onPage = { pages += it })
                    HomeTab.HEAT -> HeatTab(vm = vm, onPage = { pages += it })
                    HomeTab.SETTINGS -> SettingsTab(
                        vm = vm,
                        devices = devices,
                        wifiOn = wifiOn,
                        cloudSet = cloud != null,
                        onPage = { pages += it },
                        onSync = { if (cloud == null) pages += "cloud" else { showSync = true; vm.startSync() } },
                        onScan = { showScan = true; vm.startScan() },
                        onImport = { showImport = true },
                        onExport = { showExport = true },
                        onAddManual = { showAdd = true },
                    )
                }
            }
        }
    }

    if (showAdd) {
        DeviceDialog(
            device = null,
            onDismiss = { showAdd = false },
            onSave = { vm.addOrUpdate(it); showAdd = false },
            onDelete = null,
        )
    }
    pendingPower?.let { (device, warning, cmd) ->
        AlertDialog(
            onDismissRequest = { pendingPower = null },
            title = { Text("Zapnúť ${device.name}?") },
            text = { Text(warning) },
            confirmButton = { TextButton(onClick = { pendingPower = null; vm.setValues(device, cmd) }) { Text("Zapnúť") } },
            dismissButton = { TextButton(onClick = { pendingPower = null }) { Text("Zrušiť") } },
        )
    }
    if (showScan) {
        ScanDialog(
            vm = vm,
            devices = devices,
            onAdd = { addFoundDevice(it) },
            busy = cloudBusy.toSet(),
            cloudSet = cloud != null,
            onImport = { showScan = false; vm.clearScan(); showImport = true },
            onDismiss = { showScan = false; vm.clearScan() },
        )
    }
    addFound?.let { found ->
        DeviceDialog(
            device = Device(
                name = "",
                ip = found.ip,
                devId = found.devId,
                localKey = "",
                version = if (found.version in SUPPORTED_VERSIONS) found.version else "3.3",
            ),
            isNew = true,
            hint = addHint ?: ("Zariadenie som našiel v sieti, IP adresu, Device ID a verziu som doplnil. " +
                "Zadaj názov a local key (16 znakov). Local key zistíš cez tinytuya wizard (návod v README), " +
                "alebo naimportuj devices.json cez Nastavenia → Import zariadení."),
            onDismiss = { addFound = null; addHint = null },
            onSave = { vm.addOrUpdate(it); addFound = null; addHint = null },
            onDelete = null,
        )
    }
    noCloudFor?.let { found ->
        NoCloudDialog(
            onGuide = { noCloudFor = null; showScan = false; vm.clearScan(); pages += "guide" },
            onManual = { noCloudFor = null; addHint = null; addFound = found },
            onDismiss = { noCloudFor = null },
        )
    }
    if (showSync) {
        SyncDialog(vm = vm, onDismiss = { showSync = false; vm.clearSync() })
    }
    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            onImport = { vm.importDevices(it); showImport = false },
        )
    }
    if (showExport) {
        ExportDialog(json = exportDevicesJson(devices, HouseholdStore.shareKey(LocalContext.current)), onDismiss = { showExport = false })
    }
    if (!demo) FirstRunNameDialog()
}

/** Pri prvom spustení sa raz opýta na meno (do upozornení „Vitaj doma, …“ a do domácnosti). Dá sa preskočiť. */
@Composable
private fun FirstRunNameDialog() {
    val context = LocalContext.current
    var show by rememberSaveable { mutableStateOf(HouseholdStore.customName(context) == null && !HouseholdStore.nameAsked(context)) }
    if (!show) return
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Ako sa voláš?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Meno sa ukáže v upozornení po príchode domov (napr. „Vitaj doma, Tomáš“) a ostatným telefónom v domácnosti. " +
                    "Zmeníš ho v Automatika › Domácnosť.")
                OutlinedTextField(name, { name = it }, label = { Text("Tvoje meno") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { HouseholdStore.setName(context, name); show = false }) { Text("Uložiť") }
        },
        dismissButton = {
            TextButton(onClick = { HouseholdStore.setNameAsked(context); show = false }) { Text("Neskôr") }
        },
    )
}

@Composable
internal fun WifiBanner(cloudSet: Boolean? = null, onCloud: (() -> Unit)? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cloud = cloudSet ?: remember { CloudStore(context).load()?.isComplete == true }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (cloud) {
                    "Nie si na domácej WiFi. Vypínať sa dá cez Tuya cloud (Všetko vypnúť, odchod), ostatné ovládanie funguje až doma. " +
                        "Prepínače ukazujú posledný známy stav."
                } else {
                    "Nie si na domácej WiFi. Ovládanie funguje len doma – prepínače ukazujú posledný známy stav. " +
                        "S Tuya cloudom by sa dalo vypínať aj mimo domu."
                },
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (!cloud && onCloud != null) {
                TextButton(onClick = onCloud, contentPadding = PaddingValues(0.dp)) { Text("Nastaviť Tuya cloud") }
            }
        }
    }
}

/** Upozornenie na neúspešný príkaz s tlačidlom OK. */
@Composable
internal fun NoticeBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

/** Aktuálne hodnoty zariadenia: živý stav, inak posledný známy (cache), inak prázdne. */
internal fun deviceValues(d: Device, states: Map<String, DeviceState>, known: Map<String, Map<String, Any>>): Map<String, Any> =
    (states[d.id] as? DeviceState.Online)?.values ?: known[d.id] ?: emptyMap()

/** Krátky popis stavu zariadenia, napr. "Zapnuté · Biela 80 %". */
internal fun statusText(device: Device, state: DeviceState): String = when (state) {
    is DeviceState.Loading -> "Pripájam sa…"
    is DeviceState.Error -> "Nedostupné – ťukni pre podrobnosti"
    is DeviceState.Online -> LightChannels.detect(device, state.values)?.let { LightChannels.summary(it, state.values) } ?: run {
        val spec = Light.detect(state.values, device.schema)
        val fryer = Fryer.detect(state.values)
        val power = powerDpOf(device, state.values)
        val template = if (spec == null && fryer == null) Templates.detect(state.values, device.schema, device.category) else null
        val templateText = template?.let { Templates.summary(it, state.values, power) }
        when {
            spec != null -> Light.summary(spec, state.values)
            fryer != null -> Fryer.summary(fryer, state.values)
            template != null && templateText != null -> templateText
            power == null -> "Pripojené"
            state.values[power] == true -> "Zapnuté"
            else -> "Vypnuté"
        }
    }
}

@Composable
internal fun ValueDialog(
    title: String,
    initial: String,
    numeric: Boolean,
    confirmText: String = "Nastaviť",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušiť") } },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun DeviceDialog(
    device: Device?,
    /** true = nové zariadenie (aj keď je [device] predvyplnené, napr. z hľadania v sieti). */
    isNew: Boolean = device == null,
    /** Voliteľný text navrchu dialógu. */
    hint: String? = null,
    onDismiss: () -> Unit,
    onSave: (Device) -> Unit,
    onDelete: (() -> Unit)?,
    /** Aktuálne hodnoty zo zariadenia (technické info, kanály svetla). */
    values: Map<String, Any>? = null,
    /** Sieťové akcie technického info (všetky DP, Tuya cloud, sledovanie zmien). */
    tech: TechActions? = null,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(device?.name ?: "") }
    var group by remember { mutableStateOf(device?.group?.trim() ?: "") }
    var newGroup by remember { mutableStateOf("") }
    var roles by remember { mutableStateOf(device?.lightRoles ?: emptyMap()) }
    var showTech by remember { mutableStateOf(false) }
    var showRoles by remember { mutableStateOf(false) }
    var ip by remember { mutableStateOf(device?.ip ?: "") }
    var devId by remember { mutableStateOf(device?.devId ?: "") }
    var key by remember { mutableStateOf(device?.localKey ?: "") }
    var version by remember { mutableStateOf(device?.version ?: "3.3") }
    var powerDp by remember { mutableStateOf(device?.powerDp ?: "") }
    var whiteSw by remember { mutableStateOf(device?.whiteSwitchDp ?: "") }
    var colourSw by remember { mutableStateOf(device?.colourSwitchDp ?: "") }
    var labels by remember { mutableStateOf(labelsToText(device?.labels ?: emptyMap())) }
    var hidden by remember { mutableStateOf(device?.hidden ?: emptySet()) }
    var hiddenText by remember { mutableStateOf(device?.hidden?.joinToString(", ") ?: "") }

    val techValid = ip.isNotBlank() && devId.isNotBlank() && key.trim().length == 16
    val valid = name.isNotBlank() && techValid
    // technické údaje sú zbalené; rozbalia sa samé, keď ich treba doplniť (ručné pridanie)
    var advanced by remember { mutableStateOf(!techValid) }

    // ------------------------------------------------ automatika pre toto zariadenie (len uložené zariadenie)
    val allDevices = remember { DeviceStore(context).load() }
    val cached: (Device) -> Map<String, Any> = { d -> if (d.id == device?.id && values != null) values else StateCache.load(context, d.id) ?: emptyMap() }
    val cfg0 = remember { PresenceStore.config(context) }
    val kind = remember(device) { device?.let { AutoDevices.kind(it, values ?: StateCache.load(context, it.id) ?: emptyMap()) } }
    val inStore = device != null && !isNew && allDevices.any { it.id == device.id }
    val departNow = remember { if (inStore) device!!.id in AutoDevices.departIds(cfg0, allDevices, cached) else false }
    val arriveNow = remember { if (inStore) device!!.id in AutoDevices.arriveIds(cfg0, allDevices, cached) else false }
    var depart by remember { mutableStateOf(departNow) }
    var arrive by remember { mutableStateOf(arriveNow) }

    // posledné známe hodnoty (aj keď sa zariadenie práve nepripojilo, napr. mimo domu)
    val known: Map<String, Any>? = remember(device, values) { values ?: device?.let { StateCache.load(context, it.id) } }
    // názvy hodnôt (DP) pre ľudí: „Jas (22)“ namiesto čísla
    val dpIds: List<String> = remember(device, known) {
        ((known?.keys ?: emptySet()) + (device?.schema?.keys ?: emptySet()))
            .sortedWith(compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
    }
    val boolDps = dpIds.filter { dp -> known?.get(dp) is Boolean || device?.schema?.get(dp)?.type == DpType.BOOLEAN }
    fun dpName(dp: String): String = device?.let { dpHumanName(it, known, dp) } ?: "Hodnota $dp"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Nové zariadenie" else "Nastavenia zariadenia") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Názov") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Ako sa zariadenie volá v appke, napr. „LED pas kuchyňa“.") },
                )

                // ------------------------------------------------ miestnosť / skupina
                Text("Skupina na hlavnej obrazovke", style = MaterialTheme.typography.labelLarge)
                val autoTitle = kind?.let { Groups.autoGroup(it) } ?: "podľa druhu"
                val custom = remember { Groups.customGroups(allDevices) }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = group.isBlank(), onClick = { group = "" }, label = { Text("Automaticky ($autoTitle)") })
                    (custom + listOfNotNull(group.takeIf { it.isNotBlank() && it !in custom })).forEach { g ->
                        FilterChip(selected = group == g, onClick = { group = g }, label = { Text(g) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newGroup, onValueChange = { newGroup = it }, singleLine = true,
                        label = { Text("Nová skupina") }, placeholder = { Text("napr. Kuchyňa") }, modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = newGroup.isNotBlank(), onClick = { group = newGroup.trim(); newGroup = "" }) { Text("Pridať") }
                }

                // ------------------------------------------------ automatika
                if (inStore && kind != null && (AutoDevices.canDepart(kind) || AutoDevices.canArrive(kind))) {
                    Text("Príchod a odchod", style = MaterialTheme.typography.labelLarge)
                    if (AutoDevices.canDepart(kind)) {
                        CheckRow(
                            "Vypnúť pri odchode",
                            "Keď odídeš z domu (odpojíš sa od domácej WiFi).",
                            depart,
                            titleStyle = MaterialTheme.typography.bodyMedium,
                        ) { depart = it }
                    }
                    if (AutoDevices.canArrive(kind)) {
                        CheckRow(
                            "Zapnúť pri príchode",
                            "Keď sa vrátiš domov" + if (cfg0.onlyWhenDark) " a je tma." else ".",
                            arrive,
                            titleStyle = MaterialTheme.typography.bodyMedium,
                        ) { arrive = it }
                    }
                    if (!cfg0.enabled) Text("Automatika Príchod a odchod je teraz vypnutá – zapneš ju na karte Automatika.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (!techValid) {
                    Text(
                        "Na pripojenie chýbajú technické údaje (IP adresa, Device ID alebo local key). Najľahšie ich doplníš cez " +
                            "Hľadať zariadenia v sieti alebo Tuya cloud; ručne v časti Rozšírené nižšie.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // ------------------------------------------------ rozšírené (technické)
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Rozšírené (technické)", style = MaterialTheme.typography.titleSmall)
                        Text("Netreba meniť – vyplní sa samo pri hľadaní v sieti alebo z Tuya cloudu.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (advanced) "▲" else "▼", style = MaterialTheme.typography.titleMedium)
                }
                if (advanced) {
                    val lightish = device != null && (roles.isNotEmpty() || device.schema.values.any { it.code.startsWith("switch_led") || it.code == "mix_rgbcw" } ||
                        (known != null && runCatching { Light.isLight(known) || LightChannels.detect(device, known) != null }.getOrDefault(false)) ||
                        whiteSw.isNotBlank() || colourSw.isNotBlank() || (kind == AutoKind.LIGHT))
                    if (device != null && !isNew) {
                        OutlinedButton(onClick = { showTech = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Technické info / Poslať popis zariadenia")
                        }
                        if (lightish) {
                            OutlinedButton(onClick = { showRoles = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (roles.isEmpty()) "Kanály svetla (ručne)" else "Kanály svetla (ručne – nastavené)")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = ip, onValueChange = { ip = it },
                        label = { Text("IP adresa") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Adresa zariadenia v domácej WiFi, napr. 192.168.1.50. Keď sa zmení, appka ju väčšinou opraví sama.") },
                    )
                    OutlinedTextField(
                        value = devId, onValueChange = { devId = it },
                        label = { Text("Device ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Jedinečné označenie zariadenia v Tuya (z Tuya cloudu alebo hľadania v sieti).") },
                    )
                    OutlinedTextField(
                        value = key, onValueChange = { key = it },
                        label = { Text("Local key (16 znakov)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        isError = key.isNotBlank() && key.trim().length != 16,
                        supportingText = { Text("Tajný kľúč na šifrované ovládanie cez WiFi. Mení sa len po novom spárovaní v Tuya appke.") },
                    )
                    Text("Verzia protokolu", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("3.3", "3.4", "3.5").forEach { v ->
                            FilterChip(selected = version == v, onClick = { version = v }, label = { Text(v) })
                        }
                    }
                    Text("Spôsob šifrovania, ktorý zariadenie používa. Ak zariadenie neodpovedá, skús inú verziu.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    // hlavný vypínač: výber z hodnôt s ľudskými názvami
                    Text("Hlavný vypínač", style = MaterialTheme.typography.labelMedium)
                    Text("Ktorá hodnota zapína a vypína zariadenie (prepínač na dlaždici). Automaticky to väčšinou sedí.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (boolDps.isNotEmpty()) {
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = powerDp.isBlank(), onClick = { powerDp = "" }, label = { Text("Automaticky") })
                            (boolDps + listOfNotNull(powerDp.takeIf { it.isNotBlank() && it !in boolDps })).forEach { dp ->
                                FilterChip(selected = powerDp == dp, onClick = { powerDp = dp }, label = { Text("${dpName(dp)} ($dp)") })
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = powerDp, onValueChange = { powerDp = it },
                            label = { Text("Číslo hodnoty (prázdne = automaticky)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (lightish || device == null) OutlinedTextField(
                        value = whiteSw, onValueChange = { whiteSw = it },
                        label = { Text("Svetlo s 2 zdrojmi: vypínač bieleho svetla") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Číslo hodnoty" + (whiteSw.trim().takeIf { it.isNotEmpty() }?.let { " – ${dpName(it)}" } ?: "") + ". Len pre svetlá s hlavným a podhľadovým svetlom, inak nechaj prázdne.") },
                    )
                    if (lightish || device == null) OutlinedTextField(
                        value = colourSw, onValueChange = { colourSw = it },
                        label = { Text("Svetlo s 2 zdrojmi: vypínač farebného podhľadu") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Číslo hodnoty" + (colourSw.trim().takeIf { it.isNotEmpty() }?.let { " – ${dpName(it)}" } ?: "") + ". Inak nechaj prázdne.") },
                    )
                    OutlinedTextField(
                        value = labels, onValueChange = { labels = it },
                        label = { Text("Vlastné názvy hodnôt") }, modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text("Napr. 20=Svetlo, 22=Jas. Nechaj prázdne, ak sú názvy v poriadku." +
                                if (dpIds.isNotEmpty()) " Teraz: " + dpIds.take(8).joinToString(", ") { "$it = ${dpName(it)}" } else "")
                        },
                    )
                    Text("Skryté hodnoty", style = MaterialTheme.typography.labelMedium)
                    Text("Čo sa nemá zobrazovať na obrazovke zariadenia (napr. nepotrebné časovače).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (dpIds.isNotEmpty()) {
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (dpIds + hidden.filter { it !in dpIds }).forEach { dp ->
                                FilterChip(
                                    selected = dp in hidden,
                                    onClick = { hidden = if (dp in hidden) hidden - dp else hidden + dp },
                                    label = { Text("${dpName(dp)} ($dp)") },
                                )
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = hiddenText, onValueChange = { hiddenText = it; hidden = parseHidden(it) },
                            label = { Text("Čísla hodnôt, napr. 25, 26") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val base = device ?: Device(name = "", ip = "", devId = "", localKey = "")
                    val saved = base.copy(
                        name = name.trim(),
                        ip = ip.trim(),
                        devId = devId.trim(),
                        localKey = key.trim(),
                        version = version,
                        powerDp = powerDp.trim(),
                        whiteSwitchDp = whiteSw.trim(),
                        colourSwitchDp = colourSw.trim(),
                        labels = parseLabels(labels),
                        hidden = hidden,
                        lightRoles = roles,
                        group = group.trim().let { if (it in Groups.AUTO) "" else it },
                    )
                    // automatika: uložiť len to, čo sa naozaj zmenilo (výber ostáva rovnaký ako v Príchod a odchod)
                    if (inStore && (depart != departNow || arrive != arriveNow)) {
                        val cfg = PresenceStore.config(context)
                        val curDepart = AutoDevices.departIds(cfg, allDevices, cached)
                        val newCfg = cfg.copy(
                            departIds = if (depart != departNow) (if (depart) curDepart + saved.id else curDepart - saved.id) else cfg.departIds,
                            arriveIds = if (arrive != arriveNow) (if (arrive) cfg.arriveIds + saved.id else cfg.arriveIds - saved.id) else cfg.arriveIds,
                        )
                        PresenceStore.saveConfig(context, newCfg)
                        PresenceService.sync(context)
                    }
                    onSave(saved)
                },
            ) { Text("Uložiť") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Zmazať") }
                }
                TextButton(onClick = onDismiss) { Text("Zrušiť") }
            }
        },
    )
    if (showTech && device != null) {
        // technické info ukazuje uložené nastavenie a ručné kanály z tohto dialógu (bez kľúča a IP)
        TechInfoDialog(device = device.copy(lightRoles = roles), values = values, onDismiss = { showTech = false }, tech = tech)
    }
    if (showRoles && device != null) {
        LightRolesDialog(
            device = device,
            values = values,
            roles = roles,
            onDismiss = { showRoles = false },
            onApply = { roles = it; showRoles = false },
        )
    }
}

/** Názov hodnoty (DP) pre ľudí: vlastný názov, názov z Tuya popisu, pri svetle bez popisu podľa bežných DP, inak „Hodnota 22“. */
internal fun dpHumanName(device: Device, values: Map<String, Any>?, dp: String): String {
    device.labels[dp]?.let { return it }
    device.schema[dp]?.let { return Schema.label(it.code) }
    val l = values?.let { v -> runCatching { Light.detect(v) }.getOrNull() }
    if (l != null) {
        when (dp) {
            l.power -> return "Vypínač svetla"
            l.mode -> return "Režim svetla"
            l.bright -> return "Jas"
            l.temp -> return "Teplota farby"
            l.color -> return "Farba"
        }
    }
    return "Hodnota $dp"
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (List<Device>) -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                if (content.isNullOrBlank()) {
                    error = "Súbor je prázdny"
                } else {
                    text = content
                    error = null
                }
            } catch (e: Exception) {
                error = "Súbor sa nepodarilo prečítať"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import zariadení") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Vlož JSON z exportu tejto appky alebo súbor devices.json, ktorý vytvorí „python -m tinytuya wizard“.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickFile.launch(arrayOf("*/*")) }) { Text("Vybrať súbor .json") }
                }
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val clip = clipboard?.primaryClip
                        val pasted = if (clip != null && clip.itemCount > 0) {
                            clip.getItemAt(0).coerceToText(context).toString()
                        } else {
                            ""
                        }
                        text = pasted
                        error = if (pasted.isBlank()) "Schránka je prázdna" else null
                    },
                ) { Text("Vložiť zo schránky") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 180.dp),
                    label = { Text("JSON") },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    try {
                        // kľúč domácnosti (z exportu iného telefónu alebo pozvánka) – automatika pre viac telefónov
                        val household = HouseholdCrypto.parseExport(text)
                        val joined = household != null && HouseholdStore.adopt(context, household)
                        if (joined) Toast.makeText(context, "Telefón sa pridal do domácnosti (kľúč z exportu).", Toast.LENGTH_LONG).show()
                        val list = if (HouseholdCrypto.parseInvite(text) != null) emptyList() else parseTinytuyaJson(text)
                        when {
                            list.isNotEmpty() -> onImport(list)
                            household != null -> onDismiss()
                            else -> error = "Nenašiel som žiadne zariadenie s local key"
                        }
                    } catch (e: Exception) {
                        error = "Neplatný JSON"
                    }
                },
            ) { Text("Importovať") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušiť") } },
    )
}

/** Zobrazí export zariadení ako JSON a dá ho skopírovať do schránky alebo poslať cez Zdieľať. */
@Composable
private fun ExportDialog(json: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var copied by rememberSaveable { mutableStateOf(false) }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                Toast.makeText(context, "Súbor je uložený", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Súbor sa nepodarilo uložiť", Toast.LENGTH_LONG).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export zariadení") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Obsahuje aj local keys a kľúč domácnosti, preto ho ulož na bezpečné miesto a pošli ho len členom domácnosti " +
                        "(napr. na druhý telefón). Po novej inštalácii alebo na druhom telefóne ho načítaš cez Import zariadení.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { saveFile.launch("domov-zariadenia.json") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Uložiť do súboru .json") }
                OutlinedTextField(
                    value = json,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 200.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Domov – zariadenia", json))
                            copied = true
                        },
                    ) { Text("Kopírovať") }
                    Button(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND)
                            send.type = "text/plain"
                            send.putExtra(Intent.EXTRA_SUBJECT, "Domov – zariadenia")
                            send.putExtra(Intent.EXTRA_TEXT, json)
                            context.startActivity(Intent.createChooser(send, "Exportovať zariadenia"))
                        },
                    ) { Text("Zdieľať") }
                }
                if (copied) {
                    Text("Skopírované do schránky.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zavrieť") } },
    )
}

/**
 * Hľadanie zariadení v sieti: priebežne ukazuje nájdené zariadenia. Známym zariadeniam appka IP adresu
 * opraví sama a ponúkne zmenu verzie protokolu, nové sa dajú pridať s predvyplnenými údajmi.
 */
@Composable
private fun ScanDialog(
    vm: HomeViewModel,
    devices: List<Device>,
    onAdd: (FoundDevice) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
    busy: Set<String> = emptySet(),
    cloudSet: Boolean = false,
) {
    val scan by vm.scan.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hľadať zariadenia v sieti") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (scan.running) {
                    Text("Počúvam v sieti (asi 12 sekúnd)…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                scan.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!scan.running && scan.error == null && scan.found.isEmpty()) {
                    Text(
                        "Nenašiel som žiadne zariadenie. Skontroluj, či je telefón v tej istej WiFi sieti ako " +
                            "zariadenia a či router neblokuje komunikáciu medzi zariadeniami (izolácia klientov, sieť pre hostí).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                scan.found.forEach { found ->
                    FoundCard(
                        found = found,
                        known = devices.find { it.devId == found.devId },
                        ipUpdated = found.devId in scan.ipUpdated,
                        onApply = { vm.applyFound(found) },
                        onAdd = { onAdd(found) },
                        busy = found.devId in busy,
                        cloudSet = cloudSet,
                    )
                }
                if (scan.found.any { f -> devices.none { it.devId == f.devId } }) {
                    TextButton(onClick = onImport) { Text("Import zariadení (local keys)") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zavrieť") } },
        dismissButton = {
            TextButton(onClick = { vm.startScan() }, enabled = !scan.running) { Text("Hľadať znova") }
        },
    )
}

@Composable
private fun FoundCard(
    found: FoundDevice,
    known: Device?,
    ipUpdated: Boolean,
    onApply: () -> Unit,
    onAdd: () -> Unit,
    busy: Boolean = false,
    cloudSet: Boolean = false,
) {
    val supported = found.version in SUPPORTED_VERSIONS
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (known != null) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(known?.name ?: "Nové zariadenie", style = MaterialTheme.typography.titleSmall)
            Text(
                "IP ${found.ip.ifBlank { "?" }} · verzia ${found.version.ifBlank { "?" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Device ID ${found.devId}", style = MaterialTheme.typography.bodySmall)
            if (!supported && found.version.isNotBlank()) {
                Text(
                    "Verziu ${found.version} appka nepodporuje (len 3.3, 3.4 a 3.5).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (known != null) {
                val versionDiffers = supported && known.version != found.version
                val ipDiffers = found.ip.isNotBlank() && known.ip != found.ip
                when {
                    ipUpdated -> Text("IP adresa bola zmenená, appka ju opravila.", style = MaterialTheme.typography.bodySmall)
                    !versionDiffers && !ipDiffers -> Text("Uložené údaje sedia.", style = MaterialTheme.typography.bodySmall)
                }
                if (versionDiffers || ipDiffers) {
                    if (versionDiffers) {
                        Text(
                            "V appke je verzia ${known.version}, zariadenie hlási ${found.version}.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = onApply) {
                        Text(if (versionDiffers) "Použiť verziu ${found.version}" else "Aktualizovať IP")
                    }
                }
            } else {
                Button(onClick = onAdd, enabled = !busy) {
                    Text(if (busy) "Sťahujem z Tuya cloudu…" else "Pridať")
                }
                if (cloudSet && !busy) {
                    Text(
                        "Local key, názov a popis hodnôt sa stiahnu z Tuya cloudu.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
