@file:OptIn(ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

    var editing by remember { mutableStateOf<Device?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }

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
                DeviceCard(
                    device = device,
                    state = states[device.id] ?: DeviceState.Loading,
                    wifiOn = wifiOn,
                    onToggle = { dp, on -> vm.toggle(device, dp, on) },
                    onEdit = { editing = device },
                )
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
    editing?.let { current ->
        DeviceDialog(
            device = current,
            onDismiss = { editing = null },
            onSave = { vm.addOrUpdate(it); editing = null },
            onDelete = { vm.remove(current); editing = null },
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
private fun WifiBanner() {
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

@Composable
private fun DeviceCard(
    device: Device,
    state: DeviceState,
    wifiOn: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                    val isError = state is DeviceState.Error
                    Text(
                        text = when (state) {
                            is DeviceState.Loading -> "Pripájam sa…"
                            is DeviceState.Online -> "Pripojené"
                            is DeviceState.Error -> state.message
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onEdit) { Text("Upraviť") }
            }
            when (state) {
                is DeviceState.Loading -> LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                is DeviceState.Online -> {
                    if (state.values.isEmpty()) {
                        Text(
                            text = "V odpovedi zariadenia chýbajú vypínače ${device.channels.joinToString(", ")}. Skontroluj DP čísla.",
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    device.channels.forEach { ch ->
                        val value = state.values[ch]
                        if (value != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (device.channels.size > 1) "Kanál $ch" else "Zapnuté",
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = value,
                                    onCheckedChange = { onToggle(ch, it) },
                                    enabled = wifiOn,
                                )
                            }
                        }
                    }
                }
                is DeviceState.Error -> Unit
            }
        }
    }
}

@Composable
private fun DeviceDialog(
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
    var channels by remember { mutableStateOf(device?.channels?.joinToString(",") ?: "1") }

    val channelList = channels.split(",").map { it.trim() }.filter { it.isNotEmpty() && it.all { c -> c.isDigit() } }
    val valid = name.isNotBlank() && ip.isNotBlank() && devId.isNotBlank() &&
        key.trim().length == 16 && channelList.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (device == null) "Nové zariadenie" else "Upraviť zariadenie") },
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
                OutlinedTextField(
                    value = channels, onValueChange = { channels = it },
                    label = { Text("Vypínače – DP čísla (1 alebo 1,2,3)") }, singleLine = true,
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
                            channels = channelList,
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
