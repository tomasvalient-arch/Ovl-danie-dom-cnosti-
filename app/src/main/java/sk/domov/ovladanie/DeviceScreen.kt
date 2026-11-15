@file:OptIn(ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Detail jedného zariadenia: veľký vypínač, ovládanie podľa typu a zoznam všetkých hodnôt. */
@Composable
fun DeviceScreen(
    device: Device,
    state: DeviceState,
    wifiOn: Boolean,
    notice: String?,
    vm: HomeViewModel,
    onBack: () -> Unit,
) {
    var showEdit by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Späť")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh(device) }, enabled = wifiOn) {
                        Icon(Icons.Default.Refresh, contentDescription = "Obnoviť")
                    }
                    IconButton(onClick = { showEdit = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Nastavenia zariadenia")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!wifiOn) WifiBanner()
            if (notice != null) NoticeBanner(message = notice, onDismiss = { vm.dismissNotice() })
            when (state) {
                is DeviceState.Loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                is DeviceState.Error -> ErrorBox(message = state.message, onRetry = { vm.refresh(device) })
                is DeviceState.Online -> DeviceContent(device, state.values, wifiOn, vm)
            }
        }
    }

    if (showEdit) {
        DeviceDialog(
            device = device,
            onDismiss = { showEdit = false },
            onSave = { vm.addOrUpdate(it); showEdit = false },
            onDelete = { vm.remove(device); showEdit = false },
        )
    }
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                text = "Skontroluj IP adresu, local key a verziu protokolu v nastaveniach (ozubené koliesko hore).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(onClick = onRetry) { Text("Skúsiť znova") }
        }
    }
}

@Composable
private fun DeviceContent(device: Device, values: Map<String, Any>, wifiOn: Boolean, vm: HomeViewModel) {
    val power = powerDpOf(device, values)
    val on = power != null && values[power] == true
    val spec = Light.detect(values)

    if (power != null) {
        PowerButton(on = on, enabled = wifiOn, isLight = spec != null) {
            vm.setValues(device, mapOf(power to !on))
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

    // Ostatné hodnoty: pri svetle len pokročilé, pri ostatných zariadeniach všetky (okrem vypínača).
    val rows = values.filterKeys { dp ->
        dp !in device.hidden && dp != power && (spec == null || dp !in spec.handled)
    }
    var showAll by remember { mutableStateOf(false) }
    val expanded = spec == null || showAll

    if (rows.isNotEmpty()) {
        if (spec != null) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Skryť ďalšie hodnoty" else "Ďalšie hodnoty (${rows.size})")
            }
        } else {
            Text("Hodnoty zariadenia", style = MaterialTheme.typography.titleMedium)
        }
        if (expanded) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    rows.forEach { (dp, value) ->
                        DpRow(
                            label = device.labels[dp] ?: "Hodnota $dp",
                            value = value,
                            enabled = wifiOn,
                            onSet = { vm.setValue(device, dp, it) },
                            onRename = { name ->
                                vm.addOrUpdate(device.copy(labels = device.labels + (dp to name)), reload = false)
                            },
                            onHide = { vm.addOrUpdate(device.copy(hidden = device.hidden + dp), reload = false) },
                            onMakePower = if (value is Boolean) {
                                { vm.addOrUpdate(device.copy(powerDp = dp), reload = false) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            Text(
                text = "Pri každej hodnote môžeš cez ⋮ zmeniť jej názov, skryť ju alebo ju nastaviť ako hlavný vypínač " +
                    "(ten ovláda aj widget na ploche).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else if (spec == null && power == null) {
        Text(
            text = "Zariadenie nevrátilo žiadne hodnoty na zobrazenie.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PowerButton(on: Boolean, enabled: Boolean, isLight: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (isLight) R.drawable.ic_bulb else R.drawable.ic_power),
                contentDescription = if (on) "Vypnúť" else "Zapnúť",
                modifier = Modifier.size(48.dp),
                tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(if (on) "Zapnuté" else "Vypnuté", style = MaterialTheme.typography.titleMedium)
    }
}

/** Jeden riadok = jedna hodnota zariadenia. Boolean je vypínač, číslo a text sa menia cez dialóg. */
@Composable
private fun DpRow(
    label: String,
    value: Any,
    enabled: Boolean,
    onSet: (Any) -> Unit,
    onRename: (String) -> Unit,
    onHide: () -> Unit,
    onMakePower: (() -> Unit)?,
) {
    var editing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val text = value.toString()
    val editable = value is Number || (value is String && text.length <= 40)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label)
            if (value !is Boolean) {
                Text(
                    text = if (text.length > 40) text.take(40) + "…" else text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (value is Boolean) {
            Switch(checked = value, onCheckedChange = { onSet(it) }, enabled = enabled)
        } else if (editable) {
            TextButton(onClick = { editing = true }, enabled = enabled) { Text("Zmeniť") }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Viac")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Premenovať") },
                    onClick = { menu = false; renaming = true },
                )
                DropdownMenuItem(
                    text = { Text("Skryť") },
                    onClick = { menu = false; onHide() },
                )
                if (onMakePower != null) {
                    DropdownMenuItem(
                        text = { Text("Nastaviť ako hlavný vypínač") },
                        onClick = { menu = false; onMakePower() },
                    )
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
    if (editing) {
        ValueDialog(
            title = label,
            initial = text,
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
