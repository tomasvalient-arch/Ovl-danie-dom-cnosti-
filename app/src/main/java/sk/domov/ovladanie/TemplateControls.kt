@file:OptIn(ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Názov hodnoty: vlastný názov má prednosť, potom názov z Tuya popisu, inak „Hodnota 20“. */
internal fun dpLabel(device: Device, dp: String): String =
    device.labels[dp] ?: device.schema[dp]?.let { Schema.label(it.code) } ?: "Hodnota $dp"

/**
 * Ovládanie podľa šablóny (zásuvka, termostat, ventilátor, zvlhčovač, rolety…).
 * [onCommand] už rieši potvrdenie nebezpečných príkazov (zapnutie ohrievača).
 */
@Composable
fun TemplateControls(
    device: Device,
    template: DeviceTemplate,
    values: Map<String, Any>,
    wifiOn: Boolean,
    powerOn: Boolean,
    onCommand: (Map<String, Any>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (template) {
            is SocketTemplate -> SocketControls(device, template, values, wifiOn, onCommand)
            is ClimateTemplate -> ClimateControls(device, template, values, wifiOn, powerOn, onCommand)
            is CoverTemplate -> CoverControls(template, values, wifiOn, onCommand)
        }
        if (template.guessed) {
            Text(
                text = "Typ zariadenia appka odhadla podľa čísel hodnôt. Presnejšie názvy a rozsahy získaš " +
                    "importom devices.json s popisom hodnôt (README, časť 3).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

// ------------------------------------------------------------ zásuvka

/** Vzorky príkonu za posledné minúty (kým appka beží), aby graf nezačínal od nuly pri každom otvorení detailu. */
object PowerHistory {
    data class Sample(val time: Long, val watts: Float)

    private val map = HashMap<String, androidx.compose.runtime.snapshots.SnapshotStateList<Sample>>()

    fun of(deviceId: String) = map.getOrPut(deviceId) { mutableStateListOf() }

    fun add(deviceId: String, watts: Float, time: Long = System.currentTimeMillis()) {
        val list = of(deviceId)
        // pri obnovovaní každé 4 s stačí jedna vzorka za 3 s
        if (list.isNotEmpty() && time - list.last().time < 3000) return
        list.add(Sample(time, watts))
        while (list.size > 150 || (list.size > 2 && time - list.first().time > 10 * 60_000L)) list.removeAt(0)
    }
}

@Composable
private fun SocketControls(
    device: Device,
    t: SocketTemplate,
    values: Map<String, Any>,
    wifiOn: Boolean,
    onCommand: (Map<String, Any>) -> Unit,
) {
    val power = powerDpOf(device, values)
    val on = power != null && values[power] == true

    if (t.power != null) {
        PowerHero(deviceId = device.id, watts = t.power.real(values), unit = t.power.unit.ifBlank { "W" }, on = on, scale = t.power.scale, values = values)
    }

    if (t.voltage != null || t.current != null || t.energy != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            t.voltage?.text(values)?.let { val (v, u) = splitUnit(it); MetricTile(Sym.bolt, v, u, "Napätie", Modifier.weight(1f)) }
            t.current?.let { c ->
                val raw = c.real(values)
                if (raw != null) {
                    val (v, u) = if (c.unit == "mA") Schema.formatNumber(raw / 1000.0, 2) to "A" else splitUnit(c.text(values) ?: "")
                    MetricTile(Sym.airwave, v, u, "Prúd", Modifier.weight(1f))
                }
            }
            t.energy?.text(values)?.let { val (v, u) = splitUnit(it); MetricTile(Sym.energyProgramSaving, v, u, "Spotreba spolu", Modifier.weight(1f)) }
        }
    }

    t.countdown?.let { cd ->
        val minutesUnit = cd.unit.equals("min", ignoreCase = true)
        val raw = (values[cd.dp] as? Number)?.toLong() ?: 0L
        TimerCard(
            title = "Odpočet",
            leftSec = if (minutesUnit) raw * 60 else raw,
            on = on,
            enabled = wifiOn,
            onSet = { min -> onCommand(if (min == 0) mapOf(cd.dp to 0) else cd.command(if (minutesUnit) min.toDouble() else min * 60.0)) },
        )
    }

    if (t.switches.size >= 2) {
        SectionLabel("Vypínače")
        DomovCard(padding = 12.dp, spacing = 0.dp) {
            t.switches.forEach { dp ->
                SettingRow(icon = Sym.power, title = dpLabel(device, dp)) {
                    DomovSwitch(checked = values[dp] == true, onCheckedChange = { onCommand(mapOf(dp to it)) }, enabled = wifiOn)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { onCommand(t.switches.associateWith { true }) }, enabled = wifiOn) { Text("Zapnúť všetky") }
                OutlinedButton(onClick = { onCommand(t.switches.associateWith { false }) }, enabled = wifiOn) { Text("Vypnúť všetky") }
            }
        }
    }
}

/** Veľké číslo príkonu s grafom za posledné minúty. */
@Composable
private fun PowerHero(deviceId: String, watts: Double?, unit: String, on: Boolean, scale: Int, values: Map<String, Any>) {
    val samples = PowerHistory.of(deviceId)
    LaunchedEffect(deviceId, values) {
        if (watts != null) PowerHistory.add(deviceId, watts.toFloat())
    }
    val c = Domov.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (on) Color.Transparent else c.tileOff,
        border = BorderStroke(1.dp, if (on) c.tileOnBorder else c.tileOffBorder),
    ) {
        Column(
            modifier = Modifier.then(if (on) Modifier.background(c.tileOn) else Modifier).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val fg = if (on) c.onTileOn else MaterialTheme.colorScheme.onSurface
            val muted = if (on) c.onTileOnMuted else MaterialTheme.colorScheme.onSurfaceVariant
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Sym.boltFill, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Príkon teraz", style = MaterialTheme.typography.labelMedium, color = fg)
                }
                Spacer(Modifier.weight(1f))
                if (samples.size >= 2) {
                    val min = ((samples.last().time - samples.first().time) / 60_000L).coerceAtLeast(1)
                    Text("posledných $min min", style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    watts?.let { Schema.formatNumber(it, scale) } ?: "–",
                    fontSize = 64.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-2).sp,
                    color = fg,
                )
                Text(" $unit", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 10.dp))
            }
            if (samples.size >= 2) {
                LineGraph(samples.map { it.watts }, modifier = Modifier.fillMaxWidth().height(96.dp))
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                val maxW = samples.maxOf { it.watts }
                val minW = samples.minOf { it.watts }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(fmt.format(Date(samples.first().time)), style = MaterialTheme.typography.labelSmall, color = muted)
                    Text(
                        "max ${maxW.roundToInt()} $unit · min ${minW.roundToInt()} $unit",
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    Text("teraz", style = MaterialTheme.typography.labelSmall, color = muted)
                }
            } else {
                Text("Graf príkonu sa začne kresliť o pár sekúnd.", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

// ------------------------------------------------------------ termostat, ohrievač, ventilátor, zvlhčovač

@Composable
private fun ClimateControls(
    device: Device,
    t: ClimateTemplate,
    values: Map<String, Any>,
    wifiOn: Boolean,
    powerOn: Boolean,
    onCommand: (Map<String, Any>) -> Unit,
) {
    // bez hlavného vypínača ovládanie nevypíname
    val enabled = wifiOn && (t.power == null || powerOn)
    Column(
        modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!enabled && wifiOn) {
            Text("Zapni zariadenie, aby si mohol meniť nastavenia.", style = MaterialTheme.typography.bodySmall)
        }
        val isHumidity = t.kind == TemplateKind.HUMIDIFIER || t.kind == TemplateKind.DEHUMIDIFIER
        val metrics = listOfNotNull(
            t.current?.text(values)?.let { Triple(if (isHumidity) Sym.waterDrop else Sym.deviceThermostat, it, if (isHumidity) "Vlhkosť teraz" else "Teplota teraz") },
            t.currentTemp?.text(values)?.let { Triple(Sym.deviceThermostat, it, "Teplota") },
            t.target?.text(values)?.let { Triple(Sym.tune, it, "Nastavené") },
        )
        if (metrics.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                metrics.forEach { (icon, text, label) -> val (v, u) = splitUnit(text); MetricTile(icon, v, u, label, Modifier.weight(1f)) }
            }
        }
        DomovCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                t.target?.let { target ->
                    NumSlider(
                        title = if (isHumidity) "Nastavená vlhkosť" else "Nastavená teplota",
                        n = target,
                        values = values,
                        enabled = enabled,
                        onCommand = onCommand,
                    )
                }
                t.mode?.let { EnumChips(dpLabel(device, it.dp), it, values, enabled, onCommand) }
                t.speedEnum?.let { EnumChips(dpLabel(device, it.dp), it, values, enabled, onCommand) }
                t.speedNum?.let { NumSlider(dpLabel(device, it.dp), it, values, enabled, onCommand) }
                t.oscillation?.let { dp -> SwitchRow(dpLabel(device, dp), dp, values, enabled, onCommand) }
                t.childLock?.let { dp -> SwitchRow(dpLabel(device, dp), dp, values, wifiOn, onCommand) }
            }
        }
    }
}

@Composable
private fun NumSlider(title: String, n: NumDp, values: Map<String, Any>, enabled: Boolean, onCommand: (Map<String, Any>) -> Unit) {
    val min = n.realMin ?: return
    val max = n.realMax ?: return
    if (max <= min) return
    val step = n.realStep
    val count = ((max - min) / step).roundToInt()
    val current = (n.real(values) ?: min).coerceIn(min, max)
    LabeledSlider(
        title = title,
        value = current.toFloat(),
        range = min.toFloat()..max.toFloat(),
        enabled = enabled,
        valueText = { v ->
            val snapped = Schema.scaled(Schema.toRaw(n.schema, v.toDouble()), n.scale)
            Schema.formatNumber(snapped, n.scale) + if (n.unit.isBlank()) "" else " ${n.unit}"
        },
        onFinished = { onCommand(n.command(it.toDouble())) },
        onLive = liveOf { v: Float -> n.command(v.toDouble()) },
        steps = if (count in 2..200) count - 1 else 0,
    )
}

@Composable
private fun EnumChips(title: String, e: EnumDp, values: Map<String, Any>, enabled: Boolean, onCommand: (Map<String, Any>) -> Unit) {
    val current = values[e.dp] as? String
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            e.options.forEach { option ->
                DomovChip(
                    label = e.label(option),
                    selected = option == current,
                    enabled = enabled,
                    onClick = { if (option != current) onCommand(mapOf(e.dp to option)) },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, dp: String, values: Map<String, Any>, enabled: Boolean, onCommand: (Map<String, Any>) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        DomovSwitch(checked = values[dp] == true, onCheckedChange = { onCommand(mapOf(dp to it)) }, enabled = enabled)
    }
}

// ------------------------------------------------------------ žalúzie / rolety

@Composable
private fun CoverControls(t: CoverTemplate, values: Map<String, Any>, wifiOn: Boolean, onCommand: (Map<String, Any>) -> Unit) {
    DomovCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            (t.positionState ?: t.position)?.text(values)?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconCircle(Sym.blinds, on = true, size = 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("POLOHA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(it, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
            if (t.control != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("open" to "Otvoriť", "stop" to "Zastaviť", "close" to "Zatvoriť").forEach { (action, title) ->
                        val cmd = Templates.coverCommand(t, action)
                        if (cmd != null) {
                            Button(onClick = { onCommand(cmd) }, enabled = wifiOn) { Text(title) }
                        }
                    }
                }
            }
            t.position?.let { NumSlider("Nastaviť polohu", it, values, wifiOn, onCommand) }
        }
    }
}
