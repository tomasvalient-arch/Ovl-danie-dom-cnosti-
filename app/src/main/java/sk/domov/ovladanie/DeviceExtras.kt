package sk.domov.ovladanie

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" }.getOrDefault("")

/** Čo aplikácia v zariadení rozpoznala (do technického info). */
internal fun detectedText(device: Device, values: Map<String, Any>?): String = runCatching {
    if (values.isNullOrEmpty()) return@runCatching "nevie sa (zariadenie neodpovedá)"
    val ml = LightChannels.detect(device, values)
    if (ml != null) {
        "svetlo s viacerými zdrojmi – " + ml.description + "; kanály: " + ml.channels.joinToString { it.name }
    } else {
        deviceKindTitle(device, values) + if (LightChannels.hasMixCapability(device, values)) " (hlási aj mix_rgbcw, nepoužité)" else ""
    }
}.getOrDefault("chyba pri rozpoznávaní")

/** Akcie technického info, ktoré potrebujú sieť (dodá ich obrazovka zariadenia cez HomeViewModel). */
class TechActions(
    val probe: suspend () -> Result<Map<String, Any>>,
    val cloud: suspend () -> CloudTech?,
    val monitor: suspend (seconds: Int, onLine: (String) -> Unit) -> Unit,
)

private const val MONITOR_SECONDS = 120

/**
 * Technické info s tlačidlami Poslať (Android zdieľanie) a Kopírovať. Bez local key a IP, Device ID skryté.
 * Pri otvorení vyžiada zo zariadenia všetky DP a (ak je nastavený) načíta model z Tuya cloudu.
 * „Sledovať zmeny“ drží spojenie 2 minúty a zapisuje každú zmenu (napr. z Tuya appky).
 */
@Composable
internal fun TechInfoDialog(device: Device, values: Map<String, Any>?, onDismiss: () -> Unit, tech: TechActions? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var localAll by remember { mutableStateOf<Map<String, Any>?>(null) }
    var localNote by remember { mutableStateOf<String?>(if (tech != null) "Načítavam všetky DP zo zariadenia…" else null) }
    var cloud by remember { mutableStateOf<CloudTech?>(null) }
    var cloudNote by remember { mutableStateOf<String?>(if (tech != null) "Načítavam z Tuya cloudu…" else null) }
    val log = remember { mutableStateListOf<String>() }
    var monitorJob by remember { mutableStateOf<Job?>(null) }
    var left by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (tech == null) return@LaunchedEffect
        launch {
            val r = runCatching { tech.probe() }.getOrElse { Result.failure(it) }
            r.onSuccess { localAll = it; localNote = null }
                .onFailure { localNote = "Nepodarilo sa načítať: ${it.message ?: it.javaClass.simpleName}" }
        }
        launch {
            val c = runCatching { tech.cloud() }
            c.onSuccess { r ->
                cloud = r
                cloudNote = if (r == null) "Tuya cloud nie je nastavený (nepovinné) – úplný model sa nenačítal." else null
            }.onFailure { cloudNote = "Nepodarilo sa načítať: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    val text = runCatching {
        TechInfo.build(
            device, values, detectedText(device, values), appVersion(context),
            TechExtra(localAll = localAll, localNote = localNote, cloud = cloud, cloudNote = cloudNote, log = log.toList(), commands = CommandLog.lines(device.devId)),
        )
    }.getOrElse { "Technické info sa nepodarilo pripraviť: ${it.message}" }
    val monitoring = monitorJob?.isActive == true

    DomovAlert(
        onDismissRequest = { monitorJob?.cancel(); onDismiss() },
        icon = { Icon(Sym.info, null) },
        title = { Text("Technické info") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Popis zariadenia na poslanie (napr. vývojárovi). Neobsahuje local key ani IP adresu, Device ID je skryté.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (tech != null) {
                    OutlinedButton(
                        onClick = {
                            if (monitoring) {
                                monitorJob?.cancel()
                            } else {
                                log.add("— začiatok sledovania (${MONITOR_SECONDS / 60} min) —")
                                monitorJob = scope.launch {
                                    val timer = launch {
                                        left = MONITOR_SECONDS
                                        while (left > 0) { delay(1000); left-- }
                                    }
                                    try {
                                        tech.monitor(MONITOR_SECONDS) { line -> log.add(line) }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        log.add("(chyba: ${e.message ?: e.javaClass.simpleName})")
                                    } finally {
                                        timer.cancel()
                                        left = 0
                                        log.add("— koniec sledovania —")
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (monitoring) "Zastaviť sledovanie ($left s)" else "Sledovať zmeny (live, 2 min)") }
                    if (monitoring) {
                        Text(
                            "Teraz v Tuya appke prepínaj Farba / Biela (vypínače, jas). Každá zmena, ktorú svetlo pošle, sa zapíše. Potom ťukni Poslať.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (log.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState(), reverseScrolling = true),
                        ) {
                            log.takeLast(40).forEach { Text(TechInfo.redact(it, device), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp) }
                        }
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = if (log.isEmpty()) 400.dp else 260.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        },
        confirmButton = {
            DialogPrimaryButton(onClick = {
                runCatching {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Domov – popis zariadenia ${device.name}")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(send, "Poslať popis zariadenia").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { Toast.makeText(context, "Zdieľanie sa nepodarilo otvoriť", Toast.LENGTH_SHORT).show() }
            }) { Text("Poslať") }
        },
        dismissButton = {
            Row {
                DialogTextButton(onClick = {
                    runCatching {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Domov – popis zariadenia", text))
                        Toast.makeText(context, "Skopírované", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Kopírovať") }
                DialogTextButton(onClick = { monitorJob?.cancel(); onDismiss() }) { Text("Zavrieť") }
            }
        },
    )
}

/**
 * Ručné nastavenie kanálov svetla: spôsob ovládania a čísla hodnôt (DP) pre hlavné svetlo a podhľad.
 * Prázdne pole = automaticky.
 */
@Composable
internal fun LightRolesDialog(
    device: Device,
    values: Map<String, Any>?,
    roles: Map<String, String>,
    onDismiss: () -> Unit,
    onApply: (Map<String, String>) -> Unit,
) {
    var model by remember { mutableStateOf(roles[LightChannels.ROLE_MODEL] ?: "auto") }
    var mix by remember { mutableStateOf(roles[LightChannels.ROLE_MIX] ?: "") }
    var power by remember { mutableStateOf(roles[LightChannels.ROLE_POWER] ?: "") }
    var mainSw by remember { mutableStateOf(roles[LightChannels.ROLE_MAIN_SWITCH] ?: "") }
    var backSw by remember { mutableStateOf(roles[LightChannels.ROLE_BACK_SWITCH] ?: "") }
    var backBright by remember { mutableStateOf(roles[LightChannels.ROLE_BACK_BRIGHT] ?: "") }
    var backColour by remember { mutableStateOf(roles[LightChannels.ROLE_BACK_COLOUR] ?: "") }
    var arriveAll by remember { mutableStateOf(roles[LightChannels.ROLE_ARRIVE_ALL] == "1") }

    fun build(): Map<String, String> = buildMap {
        if (model != "auto") put(LightChannels.ROLE_MODEL, model)
        fun num(k: String, v: String) { v.trim().filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.let { put(k, it) } }
        num(LightChannels.ROLE_MIX, mix)
        num(LightChannels.ROLE_POWER, power)
        num(LightChannels.ROLE_MAIN_SWITCH, mainSw)
        num(LightChannels.ROLE_BACK_SWITCH, backSw)
        num(LightChannels.ROLE_BACK_BRIGHT, backBright)
        num(LightChannels.ROLE_BACK_COLOUR, backColour)
        if (arriveAll) put(LightChannels.ROLE_ARRIVE_ALL, "1")
    }

    val preview = remember(model, mix, power, mainSw, backSw, backBright, backColour, arriveAll, values) {
        if (values.isNullOrEmpty()) "Zariadenie neodpovedá – výsledok sa ukáže po pripojení."
        else detectedText(device.copy(lightRoles = build()), values)
    }

    DomovAlert(
        onDismissRequest = onDismiss,
        icon = { Icon(Sym.lightbulb, null) },
        title = { Text("Kanály svetla") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Ak svetlo má hlavné svetlo a podhľad, ale aplikácia ich neovláda samostatne, nastav to tu. Prázdne = automaticky.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Spôsob ovládania", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("auto" to "Automaticky", "single" to "Jedno svetlo", "mix" to "Zmiešané (DP 51)", "switches" to "Samostatné vypínače").forEach { (k, t) ->
                        FilterChip(selected = model == k, onClick = { model = k }, label = { Text(t) })
                    }
                }
                if (model == "mix" || model == "auto") {
                    NumField(mix, "Zmiešaná hodnota mix_rgbcw (zvyčajne 51)") { mix = it }
                    NumField(power, "Vypínač celého svetla (zvyčajne 20)") { power = it }
                }
                if (model == "switches" || model == "auto") {
                    NumField(mainSw, "Vypínač hlavného svetla") { mainSw = it }
                    NumField(backSw, "Vypínač podhľadu") { backSw = it }
                    NumField(backBright, "Jas podhľadu (nepovinné)") { backBright = it }
                    NumField(backColour, "Farba podhľadu (nepovinné)") { backColour = it }
                }
                CheckRow("Pri príchode zapnúť aj podhľad", checked = arriveAll, titleStyle = MaterialTheme.typography.bodyMedium) { arriveAll = it }
                Text("Rozpoznané: $preview", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { DialogPrimaryButton(onClick = { onApply(build()) }) { Text("Použiť") } },
        dismissButton = {
            Row {
                DialogTextButton(onClick = { onApply(emptyMap()) }) { Text("Automaticky") }
                DialogTextButton(onClick = onDismiss) { Text("Zrušiť") }
            }
        },
    )
}

@Composable
private fun NumField(value: String, label: String, onChange: (String) -> Unit) {
    DomovTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() }.take(4)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
