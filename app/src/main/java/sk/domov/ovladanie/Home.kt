@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package sk.domov.ovladanie

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Ikona podľa druhu zariadenia (Material Symbols). */
internal fun kindIcon(d: Device, values: Map<String, Any>, on: Boolean): ImageVector {
    if (Gateways.isGateway(d)) return Sym.devicesOther
    Sensors.kind(d)?.let { return sensorIcon(it) }
    if (Fryer.detect(values) != null) return if (on) Sym.skilletFill else Sym.skillet
    return when (val t = if (Light.detect(values) == null) Templates.detect(values, d.schema, d.category) else null) {
        is ClimateTemplate -> when (t.kind) {
            TemplateKind.HEATER -> Sym.heat
            TemplateKind.THERMOSTAT -> Sym.deviceThermostat
            TemplateKind.FAN -> if (on) Sym.modeFanFill else Sym.modeFan
            else -> Sym.waterDrop
        }
        is CoverTemplate -> Sym.blinds
        is SocketTemplate -> if (t.kind == TemplateKind.STRIP) Sym.outlet else if (on) Sym.powerFill else Sym.power
        null -> when (AutoDevices.kind(d, values)) {
            AutoKind.LIGHT -> if (on) Sym.lightbulbFill else Sym.lightbulb
            AutoKind.SOCKET -> if (on) Sym.powerFill else Sym.power
            AutoKind.HEATER -> Sym.heat
            AutoKind.FRYER -> Sym.skillet
            AutoKind.FAN -> Sym.modeFan
            AutoKind.THERMOSTAT -> Sym.deviceThermostat
            AutoKind.COVER -> Sym.blinds
            else -> Sym.devicesOther
        }
    }
}

/** Ikona senzora podľa druhu (z ikon, ktoré appka má). */
internal fun sensorIcon(kind: SensorKind): ImageVector = when (kind) {
    SensorKind.DOOR -> Sym.doorOpen
    SensorKind.MOTION -> Sym.airwave
    SensorKind.CLIMATE -> Sym.deviceThermostat
    SensorKind.LEAK -> Sym.waterDrop
    SensorKind.SMOKE, SensorKind.CO -> Sym.warning
    SensorKind.GAS -> Sym.air
    SensorKind.BUTTON -> Sym.power
}

/** Hlavná obrazovka: hlavička, stav automatiky, rýchle akcie a dlaždice v skupinách. */
@Composable
internal fun HomeMain(
    vm: HomeViewModel,
    devices: List<Device>,
    states: Map<String, DeviceState>,
    wifiOn: Boolean,
    notice: String?,
    cloudSet: Boolean,
    pagesOpen: Int,
    onOpen: (String) -> Unit,
    onPage: (String) -> Unit,
    onAdd: () -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onTogglePower: (Device, String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val known by vm.known.collectAsState()
    val demo by vm.demo.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    // stav automatiky a domácnosti sa prečíta znova po návrate z nastavení, po návrate do appky, pri zmene uložených
    // údajov (služba zapíše nový stav) a pre istotu každých 5 min – len kým je appka na obrazovke
    val tick = rememberPresenceTick()
    // čas a zdroj posledného stavu zariadení (dlaždice bez WiFi) – obnoví sa pri každom zápise do cache
    var cacheTick by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> cacheTick++ }
        val cachePrefs = StateCache.prefs(context)
        cachePrefs.registerOnSharedPreferenceChangeListener(l)
        onDispose { cachePrefs.unregisterOnSharedPreferenceChangeListener(l) }
    }
    val metas = remember(devices, cacheTick, known) { devices.associate { it.id to StateCache.meta(context, it.id) } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val cfg = remember(pagesOpen, tick) { PresenceStore.config(context) }
    val status = remember(pagesOpen, tick) { PresenceStore.status(context) }
    val members = remember(pagesOpen, tick) { HouseholdStore.members(context) }
    val selfName = remember(pagesOpen) { HouseholdStore.customName(context) ?: "ty" }
    // nákupný zoznam: znova sa načíta po návrate z obrazovky Nákup
    val shopItems = remember(pagesOpen, tick) { ShoppingStore.items(context) }
    // udalosti a narodeniny: znova sa načítajú po návrate z obrazovky Udalosti
    val eventItems = remember(pagesOpen, tick) { EventsStore.events(context) }

    fun values(d: Device): Map<String, Any> = deviceValues(d, states, known)

    val now = System.currentTimeMillis()
    val homeNames: String? = if (demo) "Tomáš, Priateľka" else {
        val others = members.filter { HouseDecision.isHome(it, now, cfg.memberWindowMin * 60_000L) }.sortedBy { it.name }.map { it.name }
        val me = if (cfg.enabled && status.state == PresenceState.HOME) listOf(selfName) else emptyList()
        (me + others).joinToString(", ").ifBlank { null }
    }
    // východ a západ slnka: jeden zdroj pre celú appku (SunRepository – výpočet NOAA z najpresnejšej známej polohy)
    val weatherUi by vm.weather.ui.collectAsState()
    val sun by rememberSunInfo(tick, cfg, weatherUi.location)
    val sunrise = sun?.riseText
    val sunset = sun?.setText
    val moonVisible = MoonStore.visible(context)
    val moon by rememberMoonPhase(tick)

    var confirmAllOff by remember { mutableStateOf(false) }
    var confirmNight by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<Device?>(null) }

    val groups = Groups.arrange(devices, ::values)
    // „Pridať“ (tlačidlo + v hlavičke) = rovno hľadanie v sieti; Tuya cloud, import a ručné zadanie sú v Nastaveniach › Zariadenia
    val addAction: () -> Unit = {
        if (wifiOn) onScan() else scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar("Zariadenia sa v sieti hľadajú len na domácej WiFi. Mimo domu ich pridáš v Nastaveniach › Zariadenia (Tuya cloud alebo import).")
        }
    }
    val firstName = remember(pagesOpen) { HouseholdStore.customName(context) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        var pulling by remember { mutableStateOf(false) }
        LaunchedEffect(pulling) { if (pulling) { delay(1200); pulling = false } }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = pulling || weatherUi.loading,
            onRefresh = { pulling = true; if (wifiOn) vm.refreshAll(); vm.weather.refresh(force = true) },
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
        LazyVerticalGrid(
            // telefón (aj 360 dp) = 2 stĺpce, tablet / na šírku viac
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            greetingText(firstName),
                            style = MaterialTheme.typography.headlineMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Surface(
                            onClick = addAction,
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Box(contentAlignment = Alignment.Center) { Icon(Sym.add, contentDescription = "Pridať zariadenie") }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (homeNames != null) InfoChip(Sym.homeFill, "Doma: $homeNames", good = true)
                        if (sunrise != null || sunset != null) {
                            InfoChip(Sym.wbTwilight, listOfNotNull(sunrise?.let { "Východ $it" }, sunset?.let { "Západ $it" }).joinToString(" · "))
                        }
                        if (!wifiOn) InfoChip(Sym.wifiOff, "Bez WiFi", error = true)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "weather") { WeatherCard(vm.weather, onSettings = { onPage("weather") }) }
            item(span = { GridItemSpan(maxLineSpan) }, key = "meniny") { MeninyStrip(tick) }
            if (moonVisible) {
                moon?.let { info ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "moon") { MoonStrip(info) }
                }
            }
            // Mimo domácej WiFi ukazuje len malý chip „Bez WiFi“ v hlavičke; veľké červené upozornenie je na Domove zrušené (od 1.34.5)
            notice?.let { msg -> item(span = { GridItemSpan(maxLineSpan) }, key = "notice") { NoticeBanner(message = msg, onDismiss = { vm.dismissNotice() }) } }

            item(span = { GridItemSpan(maxLineSpan) }, key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    ScenePill(Sym.doorOpen, "Príchod", highlighted = true) { vm.arriveNow() }
                    ScenePill(Sym.bedtime, "Noc", highlighted = false) { confirmNight = true }
                    ScenePill(Sym.powerSettingsNew, "Všetko vypnúť", highlighted = false) { confirmAllOff = true }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "shop") {
                ShoppingRow(items = shopItems, onClick = { onPage("shopping") })
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "events") {
                EventsRow(events = eventItems, onClick = { onPage("events") })
            }

            if (devices.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    EmptyHome(wifiOn = wifiOn, cloudSet = cloudSet, onScan = onScan, onAdd = onAdd, onImport = onImport, onGuide = { onPage("guide") }, onHelp = { onPage("help") }, onRestore = { onPage("backup") }, onJoin = { onPage("join") })
                }
            }

            groups.forEach { (name, list) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "g_$name") {
                    val onCount = list.count { d -> powerDpOf(d, values(d))?.let { values(d)[it] == true } == true }
                    val hasPower = list.count { d -> powerDpOf(d, values(d)) != null }
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(
                            if (hasPower == list.size) Groups.onText(onCount, list.size) else Scenes.devicesText(list.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(list, key = { it.id }) { device ->
                    Tile(
                        device = device,
                        state = states[device.id] ?: DeviceState.Loading,
                        known = values(device),
                        wifiOn = wifiOn,
                        meta = metas[device.id],
                        onOpen = { onOpen(device.id) },
                        onLongPress = { moving = device },
                        onTogglePower = { dp, on -> onTogglePower(device, dp, on) },
                        onChannel = { ch, on ->
                            // svetlo s podhľadom: každý zdroj sa zapína samostatne
                            val v = values(device)
                            LightChannels.detect(device, v)?.let { ml -> vm.setValues(device, LightChannels.setOn(ml, ch, on, v)) }
                        },
                        onBlockedTap = {
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar(
                                    "Nie si na domácej WiFi – ${device.name} sa dá prepnúť len doma. Zobrazený je posledný známy stav." +
                                        if (cloudSet) " Vypnúť všetko môžeš cez Tuya cloud tlačidlom Všetko vypnúť." else "",
                                )
                            }
                        },
                    )
                }
            }
        }
        }
    }

    if (confirmAllOff) {
        val plan = Scenes.allOffIds(cfg, devices, ::values)
        val names = devices.filter { it.id in plan }.map { it.name }
        DomovAlert(
            onDismissRequest = { confirmAllOff = false },
            icon = { Icon(Sym.powerSettingsNew, null) },
            title = { Text("Všetko vypnúť?") },
            text = {
                Text(
                    if (names.isEmpty()) "Nie je vybraté žiadne zariadenie. Výber je rovnaký ako pri odchode (Príchod a odchod → Pri odchode vypnúť)."
                    else "Vypne sa ${Scenes.devicesText(names.size)}: ${names.joinToString()}.\n\nVýber je rovnaký ako pri odchode – predvolene svetlá a zásuvky, spotrebiče len ak ich tam vyberieš." +
                        (if (devices.any { it.id in plan && SafeOff.risky(it, values(it)) }) "\n\nSpotrebiče sa nikdy nezapnú: fritéza sa len zastaví, ak práve varí; keď appka nevie zistiť stav, preskočí ju." else "") +
                        if (!wifiOn) "\n\nNie si na WiFi – pôjde to cez Tuya cloud (ak je nastavený)." else "",
                )
            },
            confirmButton = { DialogPrimaryButton(onClick = { confirmAllOff = false; vm.allOff() }, enabled = names.isNotEmpty()) { Text("Vypnúť") } },
            dismissButton = { DialogTextButton(onClick = { confirmAllOff = false; onPage("presence") }) { Text("Zmeniť výber") } },
        )
    }
    if (confirmNight) {
        val scene by vm.night.collectAsState()
        val dim = devices.filter { it.id in scene.dimIds }.map { it.name }
        val off = Scenes.nightOffIds(scene, devices, ::values).let { ids -> devices.filter { it.id in ids }.map { it.name } }
        DomovAlert(
            onDismissRequest = { confirmNight = false },
            icon = { Icon(Sym.bedtime, null) },
            title = { Text("Scéna Noc") },
            text = {
                Text(
                    buildString {
                        if (dim.isNotEmpty()) append("Stlmí na ${scene.dimPercent} % (teplá biela): ${dim.joinToString()}.\n\n")
                        else append("Zatiaľ nie je vybraté svetlo, ktoré ostane stlmené (nastavíš cez Upraviť).\n\n")
                        append(if (off.isEmpty()) "Nič sa nevypne." else "Vypne: ${off.joinToString()}.")
                        if (!wifiOn) append("\n\nNoc funguje len na domácej WiFi.")
                    },
                )
            },
            confirmButton = { DialogPrimaryButton(onClick = { confirmNight = false; vm.runNight() }, enabled = wifiOn) { Text("Spustiť") } },
            dismissButton = { DialogTextButton(onClick = { confirmNight = false; onPage("night") }) { Text("Upraviť") } },
        )
    }
    moving?.let { d ->
        MoveDialog(
            device = d,
            current = Groups.groupOf(d, values(d)),
            auto = Groups.autoGroup(AutoDevices.kind(d, values(d))),
            custom = Groups.customGroups(devices),
            onMove = { delta -> vm.moveDevice(d.id, delta) },
            onGroup = { g -> vm.setGroup(d.id, g); moving = null },
            onDismiss = { moving = null },
        )
    }
}

/** Pozdrav podľa času a (ak je zadané) mena: „Dobrý večer, Tomáš“. */
private fun greetingText(name: String?): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val greeting = when {
        hour in 5..9 -> "Dobré ráno"
        hour in 10..17 -> "Dobrý deň"
        else -> "Dobrý večer"
    }
    return if (name.isNullOrBlank()) greeting else "$greeting, $name"
}

@Composable
private fun InfoChip(icon: ImageVector, text: String, error: Boolean = false, good: Boolean = false) {
    val c = Domov.colors
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        error -> scheme.errorContainer
        good -> c.goodContainer
        else -> scheme.surfaceContainerLow
    }
    val accent = when {
        error -> scheme.error
        good -> c.good
        else -> scheme.primary
    }
    Row(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .background(bg, RoundedCornerShape(16.dp))
            .then(if (error || good) Modifier else Modifier.border(1.dp, scheme.surfaceVariant, RoundedCornerShape(16.dp)))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (good) c.good else scheme.onSurface)
    }
}

@Composable
internal fun AutomationCard(on: Boolean, running: Boolean, title: String, text: String, onStart: () -> Unit, onClick: () -> Unit) {
    val c = Domov.colors
    val broken = on && !running
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp).background(
                    when {
                        broken -> MaterialTheme.colorScheme.error
                        on -> c.goodContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    RoundedCornerShape(12.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Sym.locationHome, null,
                    tint = when {
                        broken -> MaterialTheme.colorScheme.onError
                        on -> c.good
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    color = if (broken) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text, style = MaterialTheme.typography.bodySmall,
                    color = if (broken) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (broken) {
                androidx.compose.material3.Button(
                    onClick = onStart,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Spustiť") }
            } else {
                Icon(Sym.chevronRight, null, tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
    if (broken) {
        DomovCard(onClick = onClick, padding = 14.dp, color = MaterialTheme.colorScheme.errorContainer, border = MaterialTheme.colorScheme.error) { content() }
    } else {
        DomovCard(onClick = onClick, padding = 14.dp) { content() }
    }
}

/** „HH:MM“ dnes, inak „d.M. H:mm“. */
internal fun shortTime(t: Long, now: Long = System.currentTimeMillis()): String {
    val a = java.util.Calendar.getInstance().apply { timeInMillis = t }
    val b = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val today = a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    return java.text.SimpleDateFormat(if (today) "H:mm" else "d.M. H:mm", java.util.Locale.forLanguageTag("sk")).format(java.util.Date(t))
}

/** Aktuálny stav automatiky s časom: „Si doma od 21:52“, „Odchádzaš – vypnem o 2 min“… */
internal fun presenceStateText(s: PresenceStatus, cfg: PresenceConfig, now: Long): String = when (s.state) {
    PresenceState.HOME -> if (s.since > 0) "Si doma od ${shortTime(s.since, now)}" else "Si doma"
    PresenceState.LEAVING -> {
        val left = s.since + cfg.departDelayMs - now
        if (left <= 0) "Odchádzaš – vypínam…" else "Odchádzaš – vypnem o ${((left + 59_999) / 60_000).coerceAtLeast(1)} min"
    }
    PresenceState.AWAY -> if (s.since > 0) "Mimo domu od ${shortTime(s.since, now)}" else "Mimo domu"
    PresenceState.UNKNOWN -> "Zisťujem, či si doma…"
}

/** Scéna ako malá pilulka (Príchod, Noc, Všetko vypnúť). Zvýraznená = jantárová. */
@Composable
private fun ScenePill(icon: ImageVector, label: String, highlighted: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.height(44.dp),
        shape = RoundedCornerShape(22.dp),
        color = if (highlighted) scheme.primaryContainer else scheme.surfaceContainerLow,
        contentColor = if (highlighted) scheme.onPrimaryContainer else scheme.onSurface,
        border = BorderStroke(1.dp, if (highlighted) scheme.primaryContainer else scheme.surfaceVariant),
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
    }
}

/** Dlaždica zariadenia: zapnuté = jantárová, vypnuté = tmavá, offline = červená ikona. Dlhé podržanie = presun. */
@Composable
private fun Tile(
    device: Device,
    state: DeviceState,
    known: Map<String, Any>,
    wifiOn: Boolean,
    meta: StateCache.Meta?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onTogglePower: (String, Boolean) -> Unit,
    onChannel: (LightChannel, Boolean) -> Unit,
    onBlockedTap: () -> Unit,
) {
    val c = Domov.colors
    // bez WiFi: posledný známy stav (z obrazovky alebo z automatiky), aj keď sa zariadenie teraz nedá načítať
    val values = (state as? DeviceState.Online)?.values ?: if (!wifiOn) known.takeIf { it.isNotEmpty() } else null
    val stale = !wifiOn && values != null
    val power = if (values != null) powerDpOf(device, values) else null
    val multi = values?.let { LightChannels.detect(device, it) }
    val sensor = if (values != null) Sensors.read(device, values) else null
    // senzor svieti, keď hlási poplach (otvorené dvere, pohyb, únik); svetlo s podhľadom, keď svieti hociktorý zdroj
    val on = if (sensor != null) sensor.alarm else if (multi != null) LightChannels.anyOn(multi, values) else power != null && values?.get(power) == true
    val offline = state is DeviceState.Error && !stale
    // jas svetla ako pásik v dlaždici (len keď svieti a nie je to svetlo s podhľadom)
    val lightSpec = if (values != null && multi == null) Light.detect(values) else null
    val lightPercent: Int? = if (lightSpec != null && values != null && on) {
        val st = Light.read(lightSpec, values)
        (if (st.isColour) (st.color.v * 100).roundToInt() else st.whitePercent).coerceIn(0, 100)
    } else null
    val minH = if (multi != null || lightSpec != null) 186.dp else 132.dp
    val shape = MaterialTheme.shapes.large
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minH)
            .then(if (on) Modifier.background(c.tileOn, shape) else Modifier.background(c.tileOff, shape))
            .border(1.dp, if (on) c.tileOnBorder else c.tileOffBorder, shape)
            .clip(shape)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(14.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(min = minH - 28.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Top) {
                IconCircle(kindIcon(device, values ?: known, on), on = on, error = offline, size = 40.dp)
                Spacer(Modifier.weight(1f))
                when {
                    offline -> Icon(Sym.cloudOff, contentDescription = "Offline", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    // dva zdroje: namiesto vypínača sú dole tlačidlá jednotlivých zdrojov
                    multi != null -> Unit
                    power != null && stale -> Box {
                        // tlmený prepínač s posledným známym stavom; ťuknutie vysvetlí, prečo sa nedá prepnúť
                        DomovSwitch(checked = on, onCheckedChange = null, enabled = false, onTile = on)
                        Box(Modifier.matchParentSize().clickable(onClickLabel = "Prečo sa nedá prepnúť") { onBlockedTap() })
                    }
                    power != null -> DomovSwitch(checked = on, onCheckedChange = { onTogglePower(power, it) }, enabled = wifiOn, onTile = on)
                }
            }
            Column {
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (on) c.onTileOn else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        offline -> "Offline"
                        stale -> lastKnownText(device, values!!, power != null || multi != null, on, meta)
                        else -> tileStatus(device, state)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        offline -> MaterialTheme.colorScheme.error
                        on -> c.onTileOnMuted
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = if (stale) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (lightPercent != null) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.onTileOn.copy(alpha = 0.18f))) {
                        Box(Modifier.fillMaxWidth(lightPercent / 100f).fillMaxHeight().background(c.onTileOn))
                    }
                }
            }
            if (multi != null && values != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    multi.channels.forEach { ch ->
                        val chOn = LightChannels.read(multi, ch, values).on
                        ChannelButton(ch = ch, chOn = chOn, tileOn = on, enabled = wifiOn && !stale && !offline, onClick = { onChannel(ch, !chOn) })
                    }
                }
            }
        }
    }
}

/** Tlačidlo jedného zdroja svetla na dlaždici (Hlavné / Podhľad). */
@Composable
private fun ChannelButton(ch: LightChannel, chOn: Boolean, tileOn: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Domov.colors
    val scheme = MaterialTheme.colorScheme
    val label = when {
        ch.main -> "Hlavné"
        ch.name.contains("odhľad", ignoreCase = true) -> "Podhľad"
        else -> ch.name
    }
    val fg = if (tileOn) c.onTileOn else scheme.onSurfaceVariant
    val ring = if (tileOn) c.onTileOn.copy(alpha = 0.5f) else scheme.outline
    Column(
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .then(if (chOn) Modifier.background(c.iconOn) else Modifier.border(1.5.dp, ring, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (ch.main) Sym.lightbulb else Sym.palette, contentDescription = null, tint = if (chOn) c.onIconOn else fg, modifier = Modifier.size(22.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

/** „Naposledy: vypnuté · 22:31 (automatika cez cloud)“ – stav, keď telefón nie je na WiFi. */
private fun lastKnownText(device: Device, values: Map<String, Any>, hasPower: Boolean, on: Boolean, meta: StateCache.Meta?): String {
    val what = if (hasPower) (if (on) "zapnuté" else "vypnuté") else tileStatus(device, DeviceState.Online(values))
    return "Naposledy: $what" + (meta?.let { " · ${shortTime(it.time)} (${it.source})" } ?: "")
}

/** Krátky stav na dlaždicu (napr. „Zapnuté · 80 %“, „118,7 W“). */
private fun tileStatus(device: Device, state: DeviceState): String {
    if (state is DeviceState.Online) Sensors.read(device, state.values)?.let { return Sensors.tileText(it) }
    val text = statusText(device, state)
    return text.replace("Biela ", "").replace(" · teplota ", " · ")
}

/** Presun dlaždice: poradie v skupine a výber skupiny (napr. miestnosť). */
@Composable
private fun MoveDialog(
    device: Device,
    current: String,
    auto: String,
    custom: List<String>,
    onMove: (Int) -> Unit,
    onGroup: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    DomovAlert(
        onDismissRequest = onDismiss,
        icon = { Icon(Sym.tune, null) },
        title = { Text(device.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Poradie v skupine", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = { onMove(-1) }) {
                        Icon(Sym.arrowUpward, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Dopredu")
                    }
                    androidx.compose.material3.OutlinedButton(onClick = { onMove(1) }) {
                        Icon(Sym.arrowDownward, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Dozadu")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Skupina", style = MaterialTheme.typography.titleSmall)
                GroupOption("$auto (automaticky)", selected = device.group.isBlank()) { onGroup("") }
                custom.forEach { g -> GroupOption(g, selected = current == g) { onGroup(g) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DomovTextField(
                        value = newName,
                        onValueChange = { newName = it.take(30) },
                        label = { Text("Nová skupina, napr. Obývačka") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { if (newName.isNotBlank()) onGroup(newName.trim()) }, enabled = newName.isNotBlank()) {
                        Icon(Sym.check, contentDescription = "Presunúť")
                    }
                }
                Text(
                    "Tip: dlaždicu podrž, keď ju chceš presunúť. Skupina sa uloží aj do exportu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { DialogPrimaryButton(onClick = onDismiss) { Text("Hotovo") } },
    )
}

@Composable
private fun GroupOption(title: String, selected: Boolean, onClick: () -> Unit) {
    DialogOption(title = title, selected = selected, onClick = onClick)
}

/** Prázdny zoznam (aj prvé spustenie): ponúkne všetky spôsoby pridania zariadení, cloud je len jeden z nich. */
@Composable
private fun EmptyHome(
    wifiOn: Boolean,
    cloudSet: Boolean,
    onScan: () -> Unit,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onGuide: () -> Unit,
    onHelp: () -> Unit,
    onRestore: () -> Unit = {},
    onJoin: () -> Unit = {},
) {
    DomovCard(modifier = Modifier.padding(top = 8.dp)) {
        Text("Vitaj v appke Domov", style = MaterialTheme.typography.titleMedium)
        Text(
            "Zatiaľ tu nie je žiadne zariadenie. Na každé potrebuješ IP adresu, Device ID, local key a verziu protokolu. " +
                "Vyber si, ako ich pridáš:",
            style = MaterialTheme.typography.bodyMedium,
        )
        androidx.compose.material3.Button(onClick = onJoin, modifier = Modifier.fillMaxWidth()) { Text("Pripojiť sa k domácnosti (QR z iného telefónu)") }
        androidx.compose.material3.OutlinedButton(onClick = onScan, enabled = wifiOn, modifier = Modifier.fillMaxWidth()) { Text("Hľadať zariadenia v sieti") }
        androidx.compose.material3.OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import devices.json / zálohy") }
        androidx.compose.material3.OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text("Obnoviť celú zálohu (.domov)") }
        androidx.compose.material3.OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Pridať ručne") }
        if (!cloudSet) {
            androidx.compose.material3.OutlinedButton(onClick = onGuide, modifier = Modifier.fillMaxWidth()) { Text("Sprievodca: local keys z Tuya cloudu") }
            Text(
                "Nepovinné: s Tuya cloudom appka dotiahne local keys a názvy sama. Bez neho funguje všetko rovnako.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onHelp) { Text("Pomoc") }
    }
}

/** Nastavenie scény Noc: ktoré svetlá ostanú stlmené a čo sa vypne. */
@Composable
internal fun NightSceneScreen(vm: HomeViewModel, onBack: () -> Unit) {
    val devices by vm.devices.collectAsState()
    val scene by vm.night.collectAsState()
    val known by vm.known.collectAsState()
    val states by vm.states.collectAsState()
    fun values(d: Device): Map<String, Any> = deviceValues(d, states, known)
    val offIds = Scenes.nightOffIds(scene.copy(dimIds = emptySet()), devices, ::values)
    var percent by remember(scene.dimPercent) { androidx.compose.runtime.mutableFloatStateOf(scene.dimPercent.toFloat()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scéna Noc") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Sym.arrowBack, contentDescription = "Späť") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Tlačidlo Noc na hlavnej obrazovke vypne vybrané zariadenia a vybrané svetlá nechá stlmené na teplú bielu " +
                    "(napr. lampa pri posteli alebo chodba). Funguje na domácej WiFi.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionLabel("Nechať svietiť stlmené")
            DomovCard(padding = 8.dp, spacing = 0.dp) {
                val lights = devices.filter { Scenes.canDim(it, values(it)) || AutoDevices.kind(it, values(it)) == AutoKind.LIGHT }
                if (lights.isEmpty()) Text("Zatiaľ žiadne svetlo s jasom.", modifier = Modifier.padding(8.dp))
                lights.forEach { d ->
                    val checked = d.id in scene.dimIds
                    CheckRow(verticalPadding = 2.dp, title = d.name, caption = if (Scenes.canDim(d, values(d))) null else "stav zatiaľ neznámy – zapne sa, keď odpovie", checked = checked) {
                        vm.saveNight(scene.copy(dimIds = if (checked) scene.dimIds - d.id else scene.dimIds + d.id))
                    }
                }
            }
            DomovCard {
                LabeledSlider(
                    title = "Jas stlmených svetiel",
                    value = percent,
                    range = 1f..50f,
                    enabled = true,
                    valueText = { "${it.roundToInt()} %" },
                    onFinished = { percent = it; vm.saveNight(scene.copy(dimPercent = it.roundToInt().coerceIn(1, 100))) },
                )
            }
            SectionLabel("Vypnúť")
            DomovCard(padding = 8.dp, spacing = 0.dp) {
                devices.filter { AutoDevices.canDepart(AutoDevices.kind(it, values(it))) }.forEach { d ->
                    val dimmed = d.id in scene.dimIds
                    val checked = d.id in offIds && !dimmed
                    CheckRow(verticalPadding = 2.dp, title = d.name, caption = if (dimmed) "ostane stlmené" else kindLabel(AutoDevices.kind(d, values(d))), checked = checked, enabled = !dimmed) {
                        val base = offIds.toMutableSet()
                        if (checked) base -= d.id else base += d.id
                        vm.saveNight(scene.copy(offIds = base))
                    }
                }
            }
            TextButton(onClick = { vm.saveNight(NightScene(dimIds = scene.dimIds, dimPercent = scene.dimPercent)) }) {
                Text("Predvolený výber (všetky svetlá a zásuvky)")
            }
        }
    }
}

private fun kindLabel(k: AutoKind) = when (k) {
    AutoKind.LIGHT -> "svetlo"
    AutoKind.SOCKET -> "zásuvka"
    AutoKind.FAN -> "ventilátor"
    AutoKind.FRYER -> "fritéza"
    AutoKind.HEATER -> "ohrievač"
    AutoKind.THERMOSTAT -> "termostat"
    AutoKind.COVER -> "rolety"
    AutoKind.SENSOR -> "senzor"
    AutoKind.GATEWAY -> "brána"
    AutoKind.OTHER -> "zariadenie"
    AutoKind.UNKNOWN -> "neznáme zariadenie"
}

