@file:OptIn(ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Typovo špecifická časť jednotnej stránky nastavení zariadenia.
 * Nový typ sa zaregistruje v [DeviceSettingsRegistry] – nikdy nedostane vlastný priečinok v strome nastavení.
 */
class DeviceTypeSettings(
    val id: String,
    val title: String,
    val hasNotifications: Boolean = false,
    val section: @Composable (DeviceSettingsScope) -> Unit,
)

/** Kontext typovej sekcie: uložené zariadenie a zápis zmien bez straty ostatných polí. */
class DeviceSettingsScope(
    val device: Device,
    val values: Map<String, Any>?,
    val vm: HomeViewModel,
    val update: (Device) -> Unit,
)

fun interface DeviceTypeSettingsProvider {
    fun match(device: Device, values: Map<String, Any>?): DeviceTypeSettings?
}

/**
 * Register typových nastavení. Každé zariadenie ide do Nastavenia › Zariadenia a otvorí tú istú stránku;
 * typ len doplní svoju sekciu. Pravidlo: pri novom type pridaj provider sem, nie novú položku do stromu nastavení.
 */
object DeviceSettingsRegistry {
    private val providers = mutableListOf<DeviceTypeSettingsProvider>()

    init {
        register(FryerSettingsProvider)
        register(LightSettingsProvider)
    }

    fun register(provider: DeviceTypeSettingsProvider) {
        providers += provider
    }

    fun of(device: Device, values: Map<String, Any>?): List<DeviceTypeSettings> =
        providers.mapNotNull { it.match(device, values) }

    fun hasNotifications(device: Device, values: Map<String, Any>?): Boolean =
        of(device, values).any { it.hasNotifications }
}

internal fun isFryerDevice(device: Device, values: Map<String, Any>?): Boolean {
    val v = values ?: emptyMap()
    return AutoDevices.kind(device, v) == AutoKind.FRYER || Fryer.detect(v) != null
}

internal fun isLightDevice(device: Device, values: Map<String, Any>?): Boolean {
    val v = values ?: emptyMap()
    return AutoDevices.kind(device, v) == AutoKind.LIGHT ||
        Light.detect(v) != null ||
        device.lightRoles.isNotEmpty() ||
        device.whiteSwitchDp.isNotBlank() ||
        device.colourSwitchDp.isNotBlank()
}

private val FryerSettingsProvider = DeviceTypeSettingsProvider { device, values ->
    if (!isFryerDevice(device, values)) null
    else DeviceTypeSettings(
        id = "fryer",
        title = "Fritéza",
        hasNotifications = true,
    ) { scope ->
        FryerAlertsCard(scope.device)
        val context = LocalContext.current
        val recipes = remember(scope.device.id) { RecipeStore.list(context, scope.device.id) }
        DomovCard(padding = 14.dp, spacing = 8.dp) {
            Text("Recepty", style = MaterialTheme.typography.titleSmall)
            if (recipes.isEmpty()) {
                Text(
                    "Zatiaľ žiadny vlastný recept.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                recipes.forEach { r ->
                    Text("${r.name} · ${FryerRecipes.describe(r)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            InfoHint("Recepty pridáš a použiješ v detaile fritézy na karte Domov.")
        }
    }
}

private val LightSettingsProvider = DeviceTypeSettingsProvider { device, values ->
    if (!isLightDevice(device, values)) null
    else DeviceTypeSettings(
        id = "light",
        title = "Svetlo",
    ) { scope ->
        var showRoles by remember { mutableStateOf(false) }
        var whiteSw by remember(scope.device.id, scope.device.whiteSwitchDp) { mutableStateOf(scope.device.whiteSwitchDp) }
        var colourSw by remember(scope.device.id, scope.device.colourSwitchDp) { mutableStateOf(scope.device.colourSwitchDp) }
        DomovCard(padding = 14.dp, spacing = 10.dp) {
            SettingRow(
                Sym.tune, "Kanály a zdroje svetla",
                caption = if (scope.device.lightRoles.isEmpty()) "Automaticky, alebo nastav ručne" else "Ručné priradenie kanálov",
                onClick = { showRoles = true },
            )
            DomovTextField(
                value = whiteSw,
                onValueChange = {
                    whiteSw = it
                    scope.update(scope.device.copy(whiteSwitchDp = it.trim()))
                },
                label = { Text("Vypínač bieleho svetla") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Len pre svetlá s dvoma zdrojmi. Inak nechaj prázdne.") },
            )
            DomovTextField(
                value = colourSw,
                onValueChange = {
                    colourSw = it
                    scope.update(scope.device.copy(colourSwitchDp = it.trim()))
                },
                label = { Text("Vypínač farebného podhľadu") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (showRoles) {
            LightRolesDialog(
                device = scope.device,
                values = scope.values,
                roles = scope.device.lightRoles,
                onDismiss = { showRoles = false },
                onApply = { roles -> scope.update(scope.device.copy(lightRoles = roles)); showRoles = false },
            )
        }
    }
}

/** Jednotná stránka nastavení jedného zariadenia (zo zoznamu aj z ozubeného kolieska v detaile). */
@Composable
internal fun DeviceSettingsScreen(
    vm: HomeViewModel,
    deviceId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val devices by vm.devices.collectAsState()
    val states by vm.states.collectAsState()
    val known by vm.known.collectAsState()
    val device = devices.find { it.id == deviceId }
    if (device == null) {
        LaunchedEffect(deviceId) { onBack() }
        return
    }
    val values = deviceValues(device, states, known)
    val kind = AutoDevices.kind(device, values)
    val kindTitle = deviceKindTitle(device, values)
    val extras = remember(device, values) { DeviceSettingsRegistry.of(device, values) }

    var name by remember(device.id, device.name) { mutableStateOf(device.name) }
    var group by remember(device.id, device.group) { mutableStateOf(device.group) }
    var newGroup by remember { mutableStateOf("") }
    var ip by remember(device.id, device.ip) { mutableStateOf(device.ip) }
    var devId by remember(device.id, device.devId) { mutableStateOf(device.devId) }
    var key by remember(device.id, device.localKey) { mutableStateOf(device.localKey) }
    var version by remember(device.id, device.version) { mutableStateOf(device.version) }
    var powerDp by remember(device.id, device.powerDp) { mutableStateOf(device.powerDp) }
    var labels by remember(device.id, device.labels) { mutableStateOf(labelsToText(device.labels)) }
    var hidden by remember(device.id, device.hidden) { mutableStateOf(device.hidden) }
    var hiddenText by remember(device.id, device.hidden) { mutableStateOf(device.hidden.joinToString(", ")) }
    var showKey by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var showTech by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val allDevices = devices
    val cached: (Device) -> Map<String, Any> = { d ->
        if (d.id == device.id) values else deviceValues(d, states, known)
    }
    val cfg = remember(devices) { PresenceStore.config(context) }
    var depart by remember(device.id, cfg.departIds) {
        mutableStateOf(device.id in AutoDevices.departIds(cfg, allDevices, cached))
    }
    var arrive by remember(device.id, cfg.arriveIds) {
        mutableStateOf(device.id in AutoDevices.arriveIds(cfg, allDevices, cached))
    }

    val isSubDev = device.isSub
    val techValid = if (isSubDev) devId.isNotBlank() else ip.isNotBlank() && devId.isNotBlank() && key.trim().length == 16
    val valid = name.isNotBlank() && techValid

    fun built(): Device = device.copy(
        name = name.trim(),
        ip = ip.trim(),
        devId = devId.trim(),
        localKey = key.trim(),
        version = version,
        powerDp = powerDp.trim(),
        labels = parseLabels(labels),
        hidden = hidden,
        group = group.trim().let { if (it in Groups.AUTO) "" else it },
    )

    fun persist(updated: Device) {
        if (updated != device) vm.addOrUpdate(updated)
    }

    fun persistAuto(departOn: Boolean, arriveOn: Boolean) {
        val nowCfg = PresenceStore.config(context)
        val curDepart = AutoDevices.departIds(nowCfg, allDevices, cached)
        val newCfg = nowCfg.copy(
            departIds = if (departOn) curDepart + device.id else curDepart - device.id,
            arriveIds = if (arriveOn) nowCfg.arriveIds + device.id else nowCfg.arriveIds - device.id,
        )
        PresenceStore.saveConfig(context, newCfg)
        PresenceService.sync(context)
    }

    LaunchedEffect(name, group, ip, devId, key, version, powerDp, labels, hidden) {
        if (!valid) return@LaunchedEffect
        val next = built()
        if (next == device) return@LaunchedEffect
        delay(500)
        if (built() == next && valid) persist(next)
    }

    val autoTitle = Groups.autoGroup(kind)
    val custom = remember(allDevices) { Groups.customGroups(allDevices) }
    val dpIds: List<String> = remember(device, values) {
        ((values.keys) + device.schema.keys)
            .sortedWith(compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
    }
    val boolDps = dpIds.filter { dp -> values[dp] is Boolean || device.schema[dp]?.type == DpType.BOOLEAN }
    fun dpName(dp: String): String = dpHumanName(device, values, dp)
    val gateway = if (isSubDev) Gateways.gatewayOf(device) else null

    SettingsSubPage(device.name, onBack, subtitle = kindTitle) {
        SectionLabel("Názov a skupina")
        DomovCard(padding = 14.dp, spacing = 10.dp) {
            DomovTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Názov") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Uloží sa samo, uvidíš ho na karte Domov aj v upozorneniach") },
            )
            Text("Skupina na hlavnej obrazovke", style = MaterialTheme.typography.labelLarge)
            ChipRow {
                DomovChip(label = "Automaticky ($autoTitle)", selected = group.isBlank(), onClick = { group = "" })
                (custom + listOfNotNull(group.takeIf { it.isNotBlank() && it !in custom })).forEach { g ->
                    DomovChip(label = g, selected = group == g, onClick = { group = g })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DomovTextField(
                    value = newGroup, onValueChange = { newGroup = it }, singleLine = true,
                    label = { Text("Nová skupina") }, placeholder = { Text("napr. Kuchyňa") }, modifier = Modifier.weight(1f),
                )
                TextButton(enabled = newGroup.isNotBlank(), onClick = { group = newGroup.trim(); newGroup = "" }) { Text("Pridať") }
            }
        }

        SectionLabel("Ikona a typ")
        DomovCard(padding = 14.dp, spacing = 8.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(kindIcon(device, values, false), contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                    Text(kindTitle, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Ikona sa berie podľa druhu zariadenia. Nový typ ju dostane automaticky, bez vlastného priečinka v nastaveniach.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (AutoDevices.canDepart(kind) || AutoDevices.canArrive(kind)) {
            SectionLabel("Príchod a odchod")
            DomovCard(padding = 8.dp, spacing = 0.dp) {
                if (AutoDevices.canDepart(kind)) {
                    CheckRow("Vypnúť pri odchode", "Keď odídeš z domu (odpojíš sa od domácej WiFi).", depart) {
                        depart = it
                        persistAuto(it, arrive)
                    }
                }
                if (AutoDevices.canArrive(kind)) {
                    CheckRow("Zapnúť pri príchode", "Keď sa pripojíš na domácu WiFi.", arrive) {
                        arrive = it
                        persistAuto(depart, it)
                    }
                }
            }
        }

        extras.forEach { extra ->
            SectionLabel(extra.title)
            extra.section(DeviceSettingsScope(device, values, vm) { persist(it) })
        }

        SectionLabel("Pripojenie")
        DomovCard(padding = 14.dp, spacing = 10.dp) {
            if (isSubDev) {
                Text(
                    "Podzariadenie ide cez bránu" + (gateway?.name?.let { " „$it“" } ?: "") +
                        if (device.cid.isNotBlank()) " · cid ${device.cid}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                InfoHint("IP adresu a local key má brána. Zmeníš ich v nastaveniach brány.")
                DomovTextField(
                    value = devId, onValueChange = { devId = it },
                    label = { Text("Device ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            } else {
                DomovTextField(
                    value = ip, onValueChange = { ip = it },
                    label = { Text("IP adresa") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                DomovTextField(
                    value = devId, onValueChange = { devId = it },
                    label = { Text("Device ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                DomovTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("Local key (16 znakov)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = key.isNotBlank() && key.trim().length != 16,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Skryť" else "Zobraziť") } },
                )
                Text("Verzia protokolu", style = MaterialTheme.typography.labelMedium)
                ChipRow {
                    listOf("3.3", "3.4", "3.5").forEach { v ->
                        DomovChip(label = v, selected = version == v, onClick = { version = v })
                    }
                }
            }
            if (Gateways.isGateway(device)) {
                val n = Gateways.childCount(device)
                Text(
                    if (n == 0) "Toto je brána. Podzariadenia cez ňu prídu po synchronizácii s Tuya cloudom."
                    else "Brána · podzariadení v appke: $n",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingRow(Sym.info, "Technické info", caption = "Všetky hodnoty, cloud, sledovanie zmien", onClick = { showTech = true })
        }

        SectionLabel("Zobrazenie hodnôt")
        DomovCard(padding = 14.dp, spacing = 10.dp) {
            Text(
                if (hidden.isEmpty()) "Na obrazovke zariadenia sú všetky známe hodnoty."
                else "Skryté hodnoty: " + hidden.joinToString(", ") { "${dpName(it)} ($it)" },
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Menej" else "Upraviť skryté hodnoty a vypínač") }
            if (advanced) {
                Text("Hlavný vypínač", style = MaterialTheme.typography.labelMedium)
                if (boolDps.isNotEmpty()) {
                    ChipRow {
                        DomovChip(label = "Automaticky", selected = powerDp.isBlank(), onClick = { powerDp = "" })
                        (boolDps + listOfNotNull(powerDp.takeIf { it.isNotBlank() && it !in boolDps })).forEach { dp ->
                            DomovChip(label = "${dpName(dp)} ($dp)", selected = powerDp == dp, onClick = { powerDp = dp })
                        }
                    }
                } else {
                    DomovTextField(
                        value = powerDp, onValueChange = { powerDp = it },
                        label = { Text("Číslo hodnoty (prázdne = automaticky)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                DomovTextField(
                    value = labels, onValueChange = { labels = it },
                    label = { Text("Vlastné názvy hodnôt") }, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Napr. 20=Svetlo, 22=Jas. Nechaj prázdne, ak sú názvy v poriadku.") },
                )
                Text("Skryté hodnoty", style = MaterialTheme.typography.labelMedium)
                if (dpIds.isNotEmpty()) {
                    ChipRow {
                        (dpIds + hidden.filter { it !in dpIds }).forEach { dp ->
                            DomovChip(
                                label = "${dpName(dp)} ($dp)",
                                selected = dp in hidden,
                                onClick = { hidden = if (dp in hidden) hidden - dp else hidden + dp },
                            )
                        }
                    }
                } else {
                    DomovTextField(
                        value = hiddenText, onValueChange = { hiddenText = it; hidden = parseHidden(it) },
                        label = { Text("Čísla hodnôt, napr. 25, 26") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        TextButton(onClick = { confirmDelete = true }) {
            Text("Odstrániť zariadenie z appky", color = MaterialTheme.colorScheme.error)
        }
        if (!valid) {
            NoticeCard("Doplň názov a údaje pripojenia, aby sa zmeny uložili.", NoticeKind.Warning)
        }
    }

    if (showTech) {
        TechInfoDialog(
            device = device,
            values = values,
            onDismiss = { showTech = false },
            tech = TechActions(
                probe = { vm.techProbe(device) },
                cloud = { vm.techCloud(device) },
                monitor = { sec, onLine -> vm.techMonitor(device, sec, onLine) },
            ),
        )
    }
    if (confirmDelete) {
        DomovAlert(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Sym.delete, null) },
            danger = true,
            title = { Text("Odstrániť ${device.name}?") },
            text = { Text("Zariadenie zmizne z appky. Samotné zariadenie v sieti ostane, len ho prestaneš ovládať z Domova.") },
            confirmButton = {
                DialogPrimaryButton(onClick = {
                    confirmDelete = false
                    vm.remove(device)
                    Toast.makeText(context, "Odstránené", Toast.LENGTH_SHORT).show()
                    onDeleted()
                }) { Text("Odstrániť") }
            },
            dismissButton = { DialogTextButton(onClick = { confirmDelete = false }) { Text("Zrušiť") } },
        )
    }
}
