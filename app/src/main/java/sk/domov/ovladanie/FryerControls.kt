@file:OptIn(ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Ovládanie fritézy ako v Tuya appke: stav, režim, čas, teplota a tlačidlo Štart/Stop.
 * [enabled] je pravda, keď je fritéza zapnutá a máme spojenie.
 */
@Composable
fun FryerControls(
    deviceId: String,
    spec: FryerSpec,
    values: Map<String, Any>,
    enabled: Boolean,
    onCommand: (Map<String, Any>) -> Unit,
) {
    val context = LocalContext.current
    val st = Fryer.read(spec, values)
    var confirmStart by remember { mutableStateOf(false) }
    var customMode by remember { mutableStateOf(false) }

    // Režimy, ktoré appka už videla (zariadenie ich neposiela ako zoznam).
    val modes = (ModeMemory.list(context, deviceId) + st.mode).filter { it.isNotBlank() }.distinct()

    // Stlmená (zamknutá) je len priama obsluha; recepty sa dajú vytvárať a upravovať aj pri vypnutej fritéze
    val dim = Modifier.alpha(if (enabled) 1f else 0.45f)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(modifier = dim, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!enabled) {
                Text(
                    text = "Zapni fritézu, aby si mohol nastavovať režim, čas a teplotu.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Stav", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = Fryer.statusLabel(st.statusRaw) +
                                (st.remainingMin?.takeIf { st.running && it > 0 }?.let { " · zostáva $it min" } ?: ""),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Režim", style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            modes.forEach { name ->
                                FilterChip(
                                    selected = name == st.mode,
                                    onClick = { if (enabled && name != st.mode) onCommand(Fryer.mode(spec, name)) },
                                    label = { Text(name) },
                                    enabled = enabled,
                                )
                            }
                            FilterChip(
                                selected = false,
                                onClick = { customMode = true },
                                label = { Text("Iný…") },
                                enabled = enabled,
                            )
                        }
                        Text(
                            text = "Zoznam režimov sa dopĺňa sám: appka si zapamätá každý režim, ktorý sa raz objaví " +
                                "(napr. keď ho vyberieš v Tuya appke). Iný režim môžeš zadať aj ručne cez Iný….",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    LabeledSlider(
                        title = "Čas varenia",
                        value = st.minutes.toFloat().coerceIn(Fryer.TIME_MIN.toFloat(), Fryer.TIME_MAX.toFloat()),
                        range = Fryer.TIME_MIN.toFloat()..Fryer.TIME_MAX.toFloat(),
                        enabled = enabled,
                        valueText = { "${it.roundToInt()} min" },
                        onFinished = { onCommand(Fryer.cookTime(spec, it)) },
                        onLive = liveOf { m: Float -> Fryer.cookTime(spec, m) },
                        steps = Fryer.TIME_MAX - Fryer.TIME_MIN - 1,
                    )
                    LabeledSlider(
                        title = "Teplota",
                        value = st.tempC.toFloat().coerceIn(Fryer.TEMP_MIN.toFloat(), Fryer.TEMP_MAX.toFloat()),
                        range = Fryer.TEMP_MIN.toFloat()..Fryer.TEMP_MAX.toFloat(),
                        enabled = enabled,
                        valueText = { "${((it / Fryer.TEMP_STEP).roundToInt() * Fryer.TEMP_STEP)} °C" },
                        onFinished = { onCommand(Fryer.temperature(spec, it)) },
                        onLive = liveOf { t: Float -> Fryer.temperature(spec, t) },
                        steps = (Fryer.TEMP_MAX - Fryer.TEMP_MIN) / Fryer.TEMP_STEP - 1,
                    )
                }
            }

        }

        FryerRecipesCard(deviceId = deviceId, spec = spec, st = st, modes = modes, enabled = enabled, onCommand = onCommand)

        Column(modifier = dim) {
            if (st.running) {
                Button(
                    onClick = { onCommand(Fryer.start(spec, false)) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Stop") }
            } else {
                Button(
                    onClick = { confirmStart = true },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Štart") }
            }
        }
    }

    if (confirmStart) {
        DomovAlert(
            onDismissRequest = { confirmStart = false },
            icon = { Icon(Sym.skillet, null) },
            title = { Text("Spustiť varenie?") },
            text = {
                Text(
                    "Fritéza sa začne ohrievať: ${st.mode.ifBlank { "režim" }}, ${st.minutes} min, ${st.tempC} °C. " +
                        "Skontroluj, že je v nej jedlo a že je na to vhodný čas."
                )
            },
            confirmButton = {
                DialogPrimaryButton(onClick = { confirmStart = false; onCommand(Fryer.start(spec, true)) }) { Text("Spustiť") }
            },
            dismissButton = { DialogTextButton(onClick = { confirmStart = false }) { Text("Zrušiť") } },
        )
    }
    if (customMode) {
        ValueDialog(
            title = "Názov režimu",
            initial = st.mode,
            numeric = false,
            onDismiss = { customMode = false },
            onConfirm = { text ->
                customMode = false
                if (text.isNotBlank()) onCommand(Fryer.mode(spec, text))
            },
        )
    }
}
