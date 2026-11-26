@file:OptIn(ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.repeatOnLifecycle

/** Obnova stavu otvoreného zariadenia (predtým 4 s). */
private const val DEVICE_REFRESH_MS = 12_000L

/** Detail jedného zariadenia: ovládanie podľa typu (svetlo, zásuvka, …) a zoznam všetkých hodnôt. */
@Composable
fun DeviceScreen(
    device: Device,
    state: DeviceState,
    wifiOn: Boolean,
    notice: String?,
    vm: HomeViewModel,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {

    // Kým je detail otvorený a appka na obrazovke, stav sa každých 12 s obnoví (uvidíš zmeny z Tuya appky aj z tlačidla
    // na zariadení). Po návrate do appky sa obnoví hneď; keď je appka na pozadí, nepýta sa vôbec (predtým každé 4 s aj na pozadí).
    val latestDevice by rememberUpdatedState(device)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(device.id, wifiOn, lifecycleOwner) {
        if (!wifiOn) return@LaunchedEffect
        var first = true
        lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            // pri prvom otvorení stav načíta samotná obrazovka; obnoviť hneď len po návrate z pozadia
            if (!first) vm.refresh(latestDevice, quiet = true)
            first = false
            while (true) {
                delay(DEVICE_REFRESH_MS)
                // kým sú otvorené nastavenia (technické info, sledovanie zmien), zariadenie zbytočne nezaťažujeme
                vm.refresh(latestDevice, quiet = true)
            }
        }
    }

    val values = (state as? DeviceState.Online)?.values
    val kindTitle = values?.let { deviceKindTitle(device, it) } ?: "Zariadenie"
    val subtitle = when (state) {
        is DeviceState.Online -> "$kindTitle · online"
        is DeviceState.Loading -> "$kindTitle · pripájam sa…"
        is DeviceState.Error -> "$kindTitle · offline"
    }
    // svetlo sa zapína ťuknutím na kruh; ostatné zariadenia majú vypínač hore (ako v návrhu)
    val power = values?.let { powerDpOf(device, it) }
    val isLight = values != null && Light.isLight(values)
    val on = power != null && values?.get(power) == true
    // potvrdenie nebezpečného zapnutia (ohrievač) aj z vypínača hore
    var pendingTop by remember { mutableStateOf<Pair<String, Map<String, Any>>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(device.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state is DeviceState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Sym.arrowBack, contentDescription = "Späť")
                    }
                },
                actions = {
                    if (state is DeviceState.Error) {
                        IconButton(onClick = { vm.refresh(device) }, enabled = wifiOn) {
                            Icon(Sym.refresh, contentDescription = "Obnoviť")
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Sym.tune, contentDescription = "Nastavenia zariadenia")
                    }
                    if (power != null && !isLight && Fryer.detect(values) == null &&
                        (Templates.detect(values, device.schema, device.category) as? SocketTemplate)?.power != null
                    ) {
                        DomovSwitch(
                            checked = on,
                            enabled = wifiOn,
                            modifier = Modifier.padding(end = 12.dp),
                            onCheckedChange = { target ->
                                val cmd = mapOf<String, Any>(power to target)
                                val template = Templates.detect(values, device.schema, device.category)
                                val warning = Templates.confirmation(template, power, cmd)
                                if (warning != null) pendingTop = warning to cmd else vm.setValues(device, cmd)
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!wifiOn) WifiBanner()
            if (notice != null) NoticeBanner(message = notice, onDismiss = { vm.dismissNotice() })
            when (state) {
                is DeviceState.Loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                is DeviceState.Error -> ErrorBox(message = state.message, onRetry = { vm.refresh(device) })
                is DeviceState.Online -> DeviceContent(device, state.values, wifiOn, vm, onSettings)
            }
        }
    }

    pendingTop?.let { (warning, cmd) ->
        DomovAlert(
            onDismissRequest = { pendingTop = null },
            icon = { Icon(Sym.warning, null) },
            title = { Text("Naozaj zapnúť?") },
            text = { Text(warning) },
            confirmButton = { DialogPrimaryButton(onClick = { pendingTop = null; vm.setValues(device, cmd) }) { Text("Zapnúť") } },
            dismissButton = { DialogTextButton(onClick = { pendingTop = null }) { Text("Zrušiť") } },
        )
    }
}

/** Hodnoty, ktoré už rieši ovládanie podľa typu (nezobrazujú sa medzi ďalšími nastaveniami). */
private fun handledDps(spec: LightSpec?, dual: DualSpec?, fryer: FryerSpec?, template: DeviceTemplate?): Set<String> {
    val out = HashSet<String>()
    spec?.let { out += it.handled }
    dual?.let { out += it.handled }
    fryer?.let { out += it.handled }
    when (template) {
        is SocketTemplate -> {
            out += template.switches
            listOfNotNull(template.countdown, template.power, template.voltage, template.current, template.energy).forEach { out += it.dp }
        }
        is ClimateTemplate -> {
            out += listOfNotNull(template.power, template.oscillation, template.childLock)
            listOfNotNull(template.target, template.current, template.currentTemp, template.speedNum).forEach { out += it.dp }
            listOfNotNull(template.mode, template.speedEnum).forEach { out += it.dp }
        }
        is CoverTemplate -> {
            listOfNotNull(template.position, template.positionState).forEach { out += it.dp }
            template.control?.let { out += it.dp }
        }
        null -> {}
    }
    return out
}

/** Popis druhu zariadenia pod názvom, napr. „Svetlo“, „Zásuvka s meraním“. */
internal fun deviceKindTitle(device: Device, values: Map<String, Any>): String {
    if (Gateways.isGateway(device)) return "Brána"
    Sensors.kind(device)?.let { return it.title }
    LightChannels.detect(device, values)?.let { ml -> return if (ml.channels.size == 2) "Svetlo s dvoma zdrojmi" else "Svetlo s ${ml.channels.size} zdrojmi" }
    if (Light.detect(values) != null) return "Svetlo"
    if (Light.detectDual(values, device.whiteSwitchDp, device.colourSwitchDp) != null) return "Svetlo s dvoma zdrojmi"
    if (Fryer.detect(values) != null) return "Fritéza"
    return when (val t = Templates.detect(values, device.schema, device.category)) {
        is SocketTemplate -> if (t.kind == TemplateKind.SOCKET && t.hasMetering) "Zásuvka s meraním" else t.kind.title
        null -> when (AutoDevices.kind(device, values)) {
            AutoKind.LIGHT -> "Svetlo"
            AutoKind.SOCKET -> "Zásuvka"
            else -> "Zariadenie"
        }
        else -> t.kind.title
    }
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    DomovCard(color = MaterialTheme.colorScheme.errorContainer, border = MaterialTheme.colorScheme.errorContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Sym.cloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.size(12.dp))
            Text("Zariadenie neodpovedá", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
        Text(
            text = "Skontroluj IP adresu, local key a verziu protokolu v nastaveniach (ikona vpravo hore).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Button(onClick = onRetry) { Text("Skúsiť znova") }
    }
}

@Composable
private fun DeviceContent(device: Device, values: Map<String, Any>, wifiOn: Boolean, vm: HomeViewModel, onSettings: () -> Unit) {
    // plynulé posuvníky: priebežné príkazy počas ťahania (nebezpečné príkazy s potvrdením len po pustení)
    val live: (Map<String, Any>) -> Unit = { cmd ->
        val template = Templates.detect(values, device.schema, device.category)
        if (wifiOn && Templates.confirmation(template, powerDpOf(device, values), cmd) == null) vm.liveValues(device, cmd)
    }
    CompositionLocalProvider(LocalLiveCommand provides live) {
        DeviceContentInner(device, values, wifiOn, vm, onSettings)
    }
}

@Composable
private fun DeviceContentInner(device: Device, values: Map<String, Any>, wifiOn: Boolean, vm: HomeViewModel, onSettings: () -> Unit) {
    val power = powerDpOf(device, values)
    val on = power != null && values[power] == true
    // svetlo s viacerými zdrojmi (hlavné + podhľad) má prednosť pred obyčajným svetlom
    val multi = LightChannels.detect(device, values)
    val spec = if (multi == null) Light.detect(values, device.schema) else null
    val dual = if (multi == null && spec == null) Light.detectDual(values, device.whiteSwitchDp, device.colourSwitchDp) else null
    val fryer = if (multi == null && spec == null && dual == null) Fryer.detect(values) else null
    val template = if (multi == null && spec == null && dual == null && fryer == null) {
        Templates.detect(values, device.schema, device.category)
    } else {
        null
    }
    val hasControls = multi != null || spec != null || dual != null || fryer != null || template != null

    // Príkazy, ktoré môžu byť nebezpečné (zapnutie ohrievača), sa najprv potvrdia.
    var pending by remember { mutableStateOf<Pair<String, Map<String, Any>>?>(null) }
    val send: (Map<String, Any>) -> Unit = { cmd ->
        val warning = Templates.confirmation(template, power, cmd)
        if (warning != null) pending = warning to cmd else vm.setValues(device, cmd)
    }

    val sensor = Sensors.read(device, values)
    val gateway = Gateways.isGateway(device)

    when {
        gateway -> GatewayCard(device, vm, wifiOn)
        sensor != null -> SensorCard(sensor)
        multi != null -> MultiLightOrb(multi, values, enabled = wifiOn) { vm.setValues(device, it) }
        spec != null -> LightOrbFor(spec, values, enabled = wifiOn) { vm.setValues(device, it) }
        dual != null && power != null -> {
            val st = Light.readDual(dual, values)
            LightOrb(on = on, percent = st.whitePercent, glow = null, enabled = wifiOn) { send(mapOf(power to !on)) }
        }
        template is SocketTemplate && template.power != null -> {} // veľké číslo príkonu a vypínač hore (SocketControls)
        power != null -> PowerCard(
            icon = kindIcon(device, values, on),
            on = on,
            summary = statusText(device, DeviceState.Online(values))
                .removePrefix(if (on) "Zapnuté" else "Vypnuté").removePrefix(" · ").trim()
                .ifBlank { if (on) "Ťukni pre vypnutie" else "Ťukni pre zapnutie" },
            enabled = wifiOn,
        ) { send(mapOf(power to !on)) }
    }

    if (template != null) {
        TemplateControls(
            device = device,
            template = template,
            values = values,
            wifiOn = wifiOn,
            powerOn = power == null || on,
            onCommand = send,
        )
    }

    if (multi != null) {
        MultiLightControls(ml = multi, values = values, enabled = wifiOn, onCommand = { vm.setValues(device, it) })
    }

    // obyčajné svetlo, ktoré vie aj zmiešaný režim (DP 51): ponuka samostatného ovládania hlavného svetla a podhľadu
    if (spec != null && LightChannels.suggestMix(device, values)) {
        val reported = LightChannels.hasMixCapability(device, values)
        DomovCard {
            Text("Má toto svetlo aj podhľad?", style = MaterialTheme.typography.titleSmall)
            Text(
                if (reported) {
                    "Svetlo hlási zmiešaný režim (mix_rgbcw). Ak má hlavné svetlo a samostatné farebné podsvietenie, " +
                        "dajú sa ovládať nezávisle – oba naraz."
                } else {
                    "Stropné svetlá s farebným podsvietením (napr. BlitzWolf BW-CLT1) ovládajú hlavné svetlo a podhľad " +
                        "zvyčajne cez skrytú hodnotu DP 51, ktorú Tuya cloud v popise neuvádza. Skús samostatné ovládanie – " +
                        "ak svetlo nereaguje, vráť to tu: nastavenia → Kanály svetla → Jedno svetlo."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = { vm.addOrUpdate(device.copy(lightRoles = device.lightRoles + (LightChannels.ROLE_MODEL to "mix")), reload = false) }) {
                    Text("Ovládať samostatne")
                }
                TextButton(onClick = { vm.addOrUpdate(device.copy(lightRoles = device.lightRoles + (LightChannels.ROLE_MODEL to "single")), reload = false) }) {
                    Text("Nie, je to jedno svetlo")
                }
            }
        }
    }

    if (spec != null) {
        LightControls(
            spec = spec,
            values = values,
            enabled = wifiOn && on,
            onCommand = { vm.setValues(device, it) },
        )
    }

    if (dual != null) {
        DualLightControls(
            spec = dual,
            values = values,
            enabled = wifiOn && on,
            onCommand = { vm.setValues(device, it) },
        )
    }

    if (fryer != null) {
        FryerControls(
            deviceId = device.id,
            spec = fryer,
            values = values,
            // bez hodnoty hlavného vypínača (1) ovládanie nevypíname
            enabled = wifiOn && (fryer.power == null || on),
            onCommand = { vm.setValues(device, it) },
        )
        val alerts = FryerStore.settings(LocalContext.current, device.id)
        SettingRow(
            Sym.notifications,
            "Upozornenia",
            caption = if (alerts.enabled) "Zapnuté · minútu pred koncom, keď dovarí, bezpečnosť" else "Vypnuté · nastavíš v nastaveniach zariadenia",
            onClick = onSettings,
        )
    }

    // časovač vypnutia pre svetlá a ďalšie zariadenia s odpočtom (zásuvka ho má vo vlastnom ovládaní)
    if (template !is SocketTemplate || template.countdown == null) {
        AutoDevices.countdownDp(device, values)?.let { (dp, minutes) ->
            if (values[dp] is Number) {
                val raw = (values[dp] as Number).toLong()
                TimerCard(
                    title = if (multi != null || spec != null || dual != null) "Časovač vypnutia" else "Odpočet",
                    leftSec = if (minutes) raw * 60 else raw,
                    on = on,
                    enabled = wifiOn,
                    onSet = { min -> send(mapOf(dp to if (minutes) min else min * 60)) },
                )
            }
        }
    }

    // Zoznam všetkých hodnôt s ich číslami (pri ovládaní zbalený), aby bolo vidieť, čo zariadenie hlási.
    val rows = values.filterKeys { dp -> dp !in device.hidden }
    // Svetlo s dvoma zdrojmi bez nastavených vypínačov: zoznam hneď rozbalíme, aby sa dali vypínače priradiť.
    var showAll by remember { mutableStateOf(dual != null && dual.whiteSwitch == null && dual.colourSwitch == null) }
    val expanded = !hasControls || showAll

    // nastavenia z popisu hodnôt (napr. stav po výpadku prúdu, kontrolka, detská poistka) – vždy viditeľné
    val handled = handledDps(spec, dual, fryer, template) + setOfNotNull(power) + (multi?.handled ?: emptySet()) +
        setOfNotNull(AutoDevices.countdownDp(device, values)?.first)
    val settings = if (!hasControls) emptyList() else rows.keys
        .filter { it !in handled }
        .mapNotNull { dp -> device.schema[dp]?.let { dp to it } }
        .filter { (dp, sc) ->
            !Schema.isReadOnly(sc.code) &&
                ((sc.type == DpType.ENUM && sc.range.isNotEmpty() && values[dp] is String) || (sc.type == DpType.BOOLEAN && values[dp] is Boolean))
        }
        .sortedBy { it.first.toIntOrNull() ?: Int.MAX_VALUE }
    var enumFor by remember { mutableStateOf<Pair<String, DpSchema>?>(null) }

    if (rows.isNotEmpty()) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(if (hasControls) "Ďalšie nastavenia" else "Hodnoty zariadenia", modifier = Modifier.weight(1f))
            if (hasControls) {
                TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "Skryť hodnoty" else "Všetky hodnoty (${rows.size})")
                }
            }
        }
        if (settings.isNotEmpty() && !showAll) {
            DomovCard(padding = 10.dp, spacing = 0.dp) {
                settings.forEach { (dp, sc) ->
                    val icon = when {
                        sc.code.startsWith("relay_status") -> Sym.power
                        sc.code.contains("light") || sc.code.contains("backlight") -> Sym.lightbulb
                        sc.code.contains("lock") -> Sym.lock
                        else -> Sym.tune
                    }
                    val v = values[dp]
                    if (v is Boolean) {
                        SettingRow(icon = icon, title = dpLabel(device, dp)) {
                            DomovSwitch(checked = v, onCheckedChange = { send(mapOf(dp to it)) }, enabled = wifiOn)
                        }
                    } else {
                        SettingRow(icon = icon, title = dpLabel(device, dp), value = Schema.enumLabel(sc.code, v.toString()), enabled = wifiOn, onClick = { enumFor = dp to sc })
                    }
                }
            }
        }
        if (expanded) {
            DomovCard(padding = 12.dp, spacing = 0.dp) {
                rows.forEach { (dp, value) ->
                    val role = when {
                        dp == device.whiteSwitchDp.trim() -> " · vypínač hlavného svetla"
                        dp == device.colourSwitchDp.trim() -> " · vypínač podhľadového svetla"
                        else -> ""
                    }
                    val schema = device.schema[dp]
                    DpRow(
                        label = dpLabel(device, dp) + role,
                        value = value,
                        enabled = wifiOn,
                        schema = schema,
                        caption = if (schema != null) "č. $dp · ${schema.code}" else "č. $dp",
                        forceReadOnly = sensor != null,
                        onSet = { send(mapOf(dp to it)) },
                        onRename = { name ->
                            vm.addOrUpdate(device.copy(labels = device.labels + (dp to name)), reload = false)
                        },
                        onHide = { vm.addOrUpdate(device.copy(hidden = device.hidden + dp), reload = false) },
                        onMakePower = if (value is Boolean && sensor == null) {
                            { vm.addOrUpdate(device.copy(powerDp = dp), reload = false) }
                        } else {
                            null
                        },
                        onMakeWhiteSwitch = if (value is Boolean && dual != null) {
                            { vm.addOrUpdate(device.copy(whiteSwitchDp = dp), reload = false) }
                        } else {
                            null
                        },
                        onMakeColourSwitch = if (value is Boolean && dual != null) {
                            { vm.addOrUpdate(device.copy(colourSwitchDp = dp), reload = false) }
                        } else {
                            null
                        },
                    )
                }
            }
            if (dual != null && (dual.whiteSwitch == null || dual.colourSwitch == null)) {
                Text(
                    text = "Ako priradiť vypínače svetiel: v Tuya appke prepni Biela alebo Farba a pozri sa tu, ktorý " +
                        "vypínač sa zmenil (obnovuje sa každé 4 s). Potom ťukni na jeho ⋮ a vyber, ktoré svetlo ovláda. " +
                        "Vypínač môžeš vyskúšať aj ťuknutím priamo tu.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = "Pri každej hodnote môžeš cez ⋮ zmeniť jej názov, skryť ju alebo ju nastaviť ako hlavný vypínač " +
                    "(ten ovláda aj widget na ploche).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else if (!hasControls && power == null && sensor == null && !gateway) {
        Text(
            text = "Zariadenie nevrátilo žiadne hodnoty na zobrazenie.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    ChangeLog(device = device, values = values)

    enumFor?.let { (dp, sc) ->
        EnumDialog(
            title = dpLabel(device, dp),
            schema = sc,
            current = values[dp]?.toString().orEmpty(),
            onDismiss = { enumFor = null },
            onConfirm = { enumFor = null; send(mapOf(dp to it)) },
        )
    }

    pending?.let { (warning, cmd) ->
        DomovAlert(
            onDismissRequest = { pending = null },
            icon = { Icon(Sym.warning, null) },
            title = { Text("Naozaj zapnúť?") },
            text = { Text(warning) },
            confirmButton = {
                DialogPrimaryButton(onClick = { pending = null; vm.setValues(device, cmd) }) { Text("Zapnúť") }
            },
            dismissButton = { DialogTextButton(onClick = { pending = null }) { Text("Zrušiť") } },
        )
    }
}

/**
 * Diagnostika: zapisuje, ktoré hodnoty sa zmenili, kým je detail otvorený. Stačí prepnúť niečo v Tuya appke
 * (alebo na zariadení) a hneď je vidieť, ktoré číslo hodnoty sa tým mení.
 */
@Composable
private fun ChangeLog(device: Device, values: Map<String, Any>) {
    val deviceId = device.id
    val entries = remember(deviceId) { mutableStateListOf<String>() }
    var previous by remember(deviceId) { mutableStateOf<Map<String, Any>?>(null) }
    var open by remember(deviceId) { mutableStateOf(false) }

    LaunchedEffect(values) {
        val old = previous
        if (old != null && old != values) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val keys = (old.keys + values.keys).sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
            val line = keys.filter { old[it] != values[it] }
                .joinToString("; ") { k ->
                    val name = dpLabel(device, k).let { if (it == "Hodnota $k") it else "$it ($k)" }
                    "$name: ${old[k] ?: "–"} → ${values[k] ?: "–"}"
                }
            entries.add(0, "$time  $line")
            while (entries.size > 30) entries.removeAt(entries.size - 1)
        }
        previous = values
    }

    TextButton(onClick = { open = !open }) {
        Text(if (open) "Skryť zmeny hodnôt" else "Zmeny hodnôt (${entries.size})")
    }
    if (open) {
        DomovCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entries.isEmpty()) {
                    Text(
                        text = "Zatiaľ sa nič nezmenilo. Prepni niečo v Tuya appke alebo na zariadení a o pár " +
                            "sekúnd sa tu ukáže, ktoré hodnoty sa zmenili.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    entries.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

/** Veľká karta vypínača pre zariadenia bez vlastného „hero“ (termostat, fritéza, bežný vypínač…). */
@Composable
private fun PowerCard(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, summary: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Domov.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (on) Color.Transparent else c.tileOff,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (on) c.tileOnBorder else c.tileOffBorder),
    ) {
        Row(
            modifier = Modifier
                .then(if (on) Modifier.background(c.tileOn) else Modifier)
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircle(icon, on = on, size = 64.dp)
            Spacer(Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(if (on) "Zapnuté" else "Vypnuté", style = MaterialTheme.typography.headlineSmall, color = if (on) c.onTileOn else MaterialTheme.colorScheme.onSurface)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = if (on) c.onTileOnMuted else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Sym.powerSettingsNew, contentDescription = if (on) "Vypnúť" else "Zapnúť", tint = if (on) c.onTileOn else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Veľká karta senzora: hlavná hodnota (Zatvorené, Pohyb, 21,4 °C), batéria a upozornenia. Nič sa nedá ovládať. */
@Composable
private fun SensorCard(r: SensorReading) {
    val c = Domov.colors
    val on = r.alarm
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (on) Color.Transparent else c.tileOff,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (on) c.tileOnBorder else c.tileOffBorder),
    ) {
        Column(
            modifier = Modifier.then(if (on) Modifier.background(c.tileOn) else Modifier).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconCircle(sensorIcon(r.kind), on = on, size = 64.dp)
                Spacer(Modifier.size(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (r.known) r.title else "Čakám na hodnoty",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (on) c.onTileOn else MaterialTheme.colorScheme.onSurface,
                    )
                    if (r.detail.isNotEmpty()) {
                        Text(r.detail, style = MaterialTheme.typography.bodyMedium, color = if (on) c.onTileOnMuted else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (r.batteryText.isNotEmpty()) {
                Text(
                    r.batteryText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (r.batteryLow) MaterialTheme.colorScheme.error else if (on) c.onTileOnMuted else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (r.tamper) {
                Text("Zariadenie hlási otvorenie krytu.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Text(
                "Senzor appka len zobrazuje. Batériové senzory posielajú hodnoty brána; ak sa dlho nezmenia, " +
                    "stav je posledný, ktorý brána pozná.",
                style = MaterialTheme.typography.bodySmall,
                color = if (on) c.onTileOnMuted else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Brána: stav, počet podzariadení v appke a diagnostika (surová odpoveď brány na dotaz na podzariadenia). */
@Composable
private fun GatewayCard(device: Device, vm: HomeViewModel, wifiOn: Boolean) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val n = Gateways.childCount(device)
    DomovCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconCircle(Sym.devicesOther, on = false, size = 48.dp)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Brána", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (n == 0) "V appke zatiaľ nemá žiadne zariadenia" else "V appke má " + Scenes.devicesText(n),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "Zigbee zariadenia (senzory, zásuvky, žiarovky) sa pridávajú cez Synchronizovať s Tuya cloudom. " +
                "Ovládajú sa lokálne cez túto bránu, ktorá musí byť v domácej sieti.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            enabled = wifiOn && !busy,
            onClick = {
                busy = true
                scope.launch {
                    info = vm.gatewayInfo(device).fold(
                        onSuccess = { list -> if (list.isEmpty()) "Brána odpovedala, ale zoznam podzariadení neposlala." else list.joinToString("\n\n") },
                        onFailure = { it.message ?: "Brána neodpovedá" },
                    )
                    busy = false
                }
            },
        ) { Text(if (busy) "Pýtam sa brány…" else "Zistiť podzariadenia brány") }
        info?.let {
            Text(
                it.take(1500),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Jeden riadok = jedna hodnota zariadenia. Boolean je vypínač, číslo a text sa menia cez dialóg. */
@Composable
private fun DpRow(
    label: String,
    value: Any,
    enabled: Boolean,
    onSet: (Any) -> Unit,
    /** Popis z Tuya cloudu (jednotka, rozsah, možnosti). Null = zobrazí sa ako doteraz. */
    schema: DpSchema? = null,
    caption: String? = null,
    onRename: (String) -> Unit,
    onHide: () -> Unit,
    onMakePower: (() -> Unit)?,
    onMakeWhiteSwitch: (() -> Unit)? = null,
    onMakeColourSwitch: (() -> Unit)? = null,
    /** Hodnoty senzora sa nikdy neposielajú späť (aj bez popisu z cloudu). */
    forceReadOnly: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val raw = value.toString()
    val text = Schema.format(schema, value)
    val readOnly = forceReadOnly || (schema != null && Schema.isReadOnly(schema.code))
    val numberSchema = schema?.takeIf { it.type == DpType.INTEGER && value is Number }
    val enumSchema = schema?.takeIf { it.type == DpType.ENUM && it.range.isNotEmpty() }
    val editable = !readOnly && (value is Number || (value is String && raw.length <= 40))

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            if (value !is Boolean || readOnly) {
                Text(
                    text = if (text.length > 40) text.take(40) + "…" else text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (caption != null) {
                Text(
                    text = caption + if (readOnly) " · len na čítanie" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (value is Boolean && !readOnly) {
            DomovSwitch(checked = value, onCheckedChange = { onSet(it) }, enabled = enabled)
        } else if (editable) {
            TextButton(onClick = { editing = true }, enabled = enabled) { Text("Zmeniť") }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Sym.moreVert, contentDescription = "Viac")
            }
            DomovMenu(expanded = menu, onDismiss = { menu = false }) {
                DomovMenuItem("Premenovať", Sym.edit, onClick = { menu = false; renaming = true })
                DomovMenuItem("Skryť", Sym.close, onClick = { menu = false; onHide() })
                if (onMakePower != null || onMakeWhiteSwitch != null || onMakeColourSwitch != null) {
                    DomovMenuLabel("Použiť ako")
                }
                if (onMakePower != null) {
                    DomovMenuItem("Hlavný vypínač", Sym.bolt, onClick = { menu = false; onMakePower() })
                }
                if (onMakeWhiteSwitch != null) {
                    DomovMenuItem("Vypínač hlavného svetla (biela)", Sym.lightbulb, onClick = { menu = false; onMakeWhiteSwitch() })
                }
                if (onMakeColourSwitch != null) {
                    DomovMenuItem("Vypínač podhľadového svetla (farba)", Sym.palette, onClick = { menu = false; onMakeColourSwitch() })
                }
            }
        }
    }

    if (renaming) {
        ValueDialog(
            title = "Názov hodnoty",
            initial = label,
            numeric = false,
            confirmText = "Uložiť",
            onDismiss = { renaming = false },
            onConfirm = { input ->
                if (input.isNotBlank()) onRename(input)
                renaming = false
            },
        )
    }
    if (editing && numberSchema != null) {
        NumberDialog(
            title = label,
            schema = numberSchema,
            raw = (value as Number).toLong(),
            onDismiss = { editing = false },
            onConfirm = { editing = false; onSet(it) },
        )
    } else if (editing && enumSchema != null) {
        EnumDialog(
            title = label,
            schema = enumSchema,
            current = raw,
            onDismiss = { editing = false },
            onConfirm = { editing = false; onSet(it) },
        )
    } else if (editing) {
        ValueDialog(
            title = label,
            initial = raw,
            numeric = value is Number,
            onDismiss = { editing = false },
            onConfirm = { input ->
                val parsed: Any? = when (value) {
                    is Double, is Float -> input.toDoubleOrNull()
                    is Number -> input.toLongOrNull()
                    else -> input
                }
                if (parsed != null) onSet(parsed)
                editing = false
            },
        )
    }
}

/** Zmena čísla podľa Tuya popisu: posuvník v rozsahu min–max s krokom a tlačidlá − / +. */
@Composable
private fun NumberDialog(title: String, schema: DpSchema, raw: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var current by rememberSaveable { androidx.compose.runtime.mutableLongStateOf(raw) }
    var typed by rememberSaveable { mutableStateOf<String?>(null) }
    val min = schema.min
    val max = schema.max
    fun clamp(v: Long): Long = Schema.toRaw(schema, Schema.scaled(v, schema.scale))
    val count = if (min != null && max != null && max > min) (max - min) / schema.step else 0L

    DomovAlert(
        onDismissRequest = onDismiss,
        icon = { Icon(Sym.tune, null) },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    Schema.format(schema, current),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (min != null && max != null) {
                    Text(
                        "Rozsah ${Schema.format(schema, min)} – ${Schema.format(schema, max)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilledTonalIconButton(onClick = { current = clamp(current - schema.step); typed = null }, modifier = Modifier.size(48.dp)) {
                        Icon(Sym.remove, contentDescription = "Menej")
                    }
                    if (count in 1..1000) {
                        WideSlider(
                            value = current.coerceIn(min!!, max!!).toFloat(),
                            range = min.toFloat()..max.toFloat(),
                            enabled = true,
                            track = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.weight(1f),
                            fill = amberFill(),
                            steps = if (count in 2..200) (count - 1).toInt() else 0,
                            onChange = { current = clamp(it.toLong()); typed = null },
                            onFinished = {},
                        )
                    } else {
                        DomovTextField(
                            value = typed ?: Schema.formatNumber(Schema.scaled(current, schema.scale), schema.scale),
                            onValueChange = { input ->
                                typed = input
                                input.replace(',', '.').toDoubleOrNull()?.let { current = Schema.toRaw(schema, it) }
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    FilledTonalIconButton(onClick = { current = clamp(current + schema.step); typed = null }, modifier = Modifier.size(48.dp)) {
                        Icon(Sym.add, contentDescription = "Viac")
                    }
                }
            }
        },
        confirmButton = { DialogPrimaryButton(onClick = { onConfirm(current) }) { Text("Nastaviť") } },
        dismissButton = { DialogTextButton(onClick = onDismiss) { Text("Zrušiť") } },
    )
}

/** Výber jednej z možností podľa Tuya popisu (s prekladom bežných hodnôt). */
@Composable
private fun EnumDialog(title: String, schema: DpSchema, current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var selected by remember { mutableStateOf(current) }
    DomovAlert(
        onDismissRequest = onDismiss,
        icon = { Icon(Sym.tune, null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                schema.range.forEach { option ->
                    val label = Schema.enumLabel(schema.code, option)
                    DialogOption(
                        title = label,
                        trailing = if (label == option) null else option,
                        selected = selected == option,
                        onClick = { selected = option },
                    )
                }
            }
        },
        confirmButton = { DialogPrimaryButton(onClick = { onConfirm(selected) }) { Text("Nastaviť") } },
        dismissButton = { DialogTextButton(onClick = onDismiss) { Text("Zrušiť") } },
    )
}
