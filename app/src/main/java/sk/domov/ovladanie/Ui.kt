@file:OptIn(ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun HomeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun HomeScreen(vm: HomeViewModel) {
    val devices by vm.devices.collectAsState()
    val states by vm.states.collectAsState()
    val wifi by vm.wifi.collectAsState()
    val wifiOn = wifi != null

    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

    val open = devices.find { it.id == openId }
    BackHandler(enabled = open != null) { openId = null }

    if (open != null) {
        DeviceScreen(
            device = open,
            state = states[open.id] ?: DeviceState.Loading,
            wifiOn = wifiOn,
            vm = vm,
            onBack = { openId = null },
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Domov") },
                    actions = {
                        TextButton(onClick = { showImport = true }) { Text("Import") }
                        IconButton(onClick = { vm.refreshAll() }, enabled = wifiOn) {
                            Icon(Icons.Default.Refresh, contentDescription = "Obnoviť")
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Pridať zariadenie")
                }
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!wifiOn) {
                    item { WifiBanner() }
                }
                if (devices.isEmpty()) {
                    item { EmptyHint() }
                }
                items(devices, key = { it.id }) { device ->
                    DeviceTile(
                        device = device,
                        state = states[device.id] ?: DeviceState.Loading,
                        wifiOn = wifiOn,
                        onOpen = { openId = device.id },
                        onTogglePower = { dp, on -> vm.setValues(device, mapOf(dp to on)) },
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
    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            onImport = { vm.importDevices(it); showImport = false },
        )
    }
}

@Composable
internal fun WifiBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = "Nie si pripojený na WiFi. Ovládanie funguje len v domácej sieti.",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun EmptyHint() {
    Text(
        text = "Zatiaľ tu nie je žiadne zariadenie. Pridaj ho tlačidlom + alebo naimportuj devices.json cez Import.",
        modifier = Modifier.padding(vertical = 24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Krátky popis stavu zariadenia, napr. "Zapnuté · Biela 80 %". */
internal fun statusText(device: Device, state: DeviceState): String = when (state) {
    is DeviceState.Loading -> "Pripájam sa…"
    is DeviceState.Error -> "Nedostupné – ťukni pre podrobnosti"
    is DeviceState.Online -> {
        val spec = Light.detect(state.values)
        val power = powerDpOf(device, state.values)
        when {
            spec != null -> Light.summary(spec, state.values)
            power == null -> "Pripojené"
            power != null && state.values[power] == true -> "Zapnuté"
            else -> "Vypnuté"
        }
    }
}

/** Kruhová ikona zariadenia: žiarovka pre svetlá, inak symbol vypínača. */
@Composable
internal fun DeviceIcon(isLight: Boolean, on: Boolean) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (isLight) R.drawable.ic_bulb else R.drawable.ic_power),
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Jedna dlaždica na domovskej obrazovke: ikona, názov, stav a hlavný vypínač. */
@Composable
private fun DeviceTile(
    device: Device,
    state: DeviceState,
    wifiOn: Boolean,
    onOpen: () -> Unit,
    onTogglePower: (String, Boolean) -> Unit,
) {
    val values = (state as? DeviceState.Online)?.values
    val power = if (values != null) powerDpOf(device, values) else null
    val on = power != null && values?.get(power) == true
    val isLight = values != null && Light.detect(values) != null
    val container = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant

    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            DeviceIcon(isLight = isLight, on = on)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = statusText(device, state),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is DeviceState.Error) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (power != null) {
                Switch(checked = on, onCheckedChange = { onTogglePower(power, it) }, enabled = wifiOn)
            }
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

@Composable
internal fun DeviceDialog(
    device: Device?,
    onDismiss: () -> Unit,
    onSave: (Device) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(device?.name ?: "") }
    var ip by remember { mutableStateOf(device?.ip ?: "") }
    var devId by remember { mutableStateOf(device?.devId ?: "") }
    var key by remember { mutableStateOf(device?.localKey ?: "") }
    var version by remember { mutableStateOf(device?.version ?: "3.3") }
    var powerDp by remember { mutableStateOf(device?.powerDp ?: "") }
    var labels by remember { mutableStateOf(labelsToText(device?.labels ?: emptyMap())) }
    var hidden by remember { mutableStateOf(device?.hidden?.joinToString(", ") ?: "") }

    val valid = name.isNotBlank() && ip.isNotBlank() && devId.isNotBlank() && key.trim().length == 16

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (device == null) "Nové zariadenie" else "Nastavenia zariadenia") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Názov") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ip, onValueChange = { ip = it },
                    label = { Text("IP adresa (napr. 192.168.1.50)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = devId, onValueChange = { devId = it },
                    label = { Text("Device ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("Local key (16 znakov)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Verzia protokolu", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("3.3", "3.4", "3.5").forEach { v ->
                        FilterChip(selected = version == v, onClick = { version = v }, label = { Text(v) })
                    }
                }
                Text("Pokročilé", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = powerDp, onValueChange = { powerDp = it },
                    label = { Text("Hlavný vypínač: číslo hodnoty (nepovinné)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = labels, onValueChange = { labels = it },
                    label = { Text("Názvy hodnôt (napr. 20=Svetlo, 22=Jas)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = hidden, onValueChange = { hidden = it },
                    label = { Text("Skryté hodnoty (čísla, napr. 25, 26)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val base = device ?: Device(name = "", ip = "", devId = "", localKey = "")
                    onSave(
                        base.copy(
                            name = name.trim(),
                            ip = ip.trim(),
                            devId = devId.trim(),
                            localKey = key.trim(),
                            version = version,
                            powerDp = powerDp.trim(),
                            labels = parseLabels(labels),
                            hidden = parseHidden(hidden),
                        )
                    )
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
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (List<Device>) -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import zariadení") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Vlož obsah súboru devices.json, ktorý vytvorí „python -m tinytuya wizard“.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    label = { Text("devices.json") },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    try {
                        val list = parseTinytuyaJson(text)
                        if (list.isEmpty()) error = "Nenašiel som žiadne zariadenie s local key" else onImport(list)
                    } catch (e: Exception) {
                        error = "Neplatný JSON"
                    }
                },
            ) { Text("Importovať") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušiť") } },
    )
}
