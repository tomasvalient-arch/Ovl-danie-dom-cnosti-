package sk.domov.ovladanie

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import sk.domov.ovladanie.pc.PcDiscovery
import sk.domov.ovladanie.pc.PcLink
import sk.domov.ovladanie.pc.PcProto

@Composable
private fun PcHint(text: String, warning: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ------------------------------------------------------------------ zoznam spárovaných PC

@Composable
fun PcScreen(onBack: () -> Unit, onAdd: () -> Unit) {
    val context = LocalContext.current
    var pcs by remember { mutableStateOf(PcStore.list(context)) }
    var forget by remember { mutableStateOf<PcEntry?>(null) }
    val wifiOk = wifiNetwork(context) != null

    PageScaffold("Ovládanie PC", onBack) {
        Text(
            "Z telefónu uvidíš obraz PC s Windowsom a budeš ho ovládať: myš, klávesnica, schránka a vypnutie alebo zapnutie PC. " +
                "Všetko beží len doma, na rovnakej WiFi ako PC, a je šifrované.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!wifiOk) PcHint(PcNet.NO_WIFI, warning = true)

        pcs.forEach { pc ->
            DomovCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                        Text(pc.name, style = MaterialTheme.typography.titleMedium)
                        PcHint(pc.hosts.firstOrNull()?.let { "Naposledy na adrese $it" } ?: "Adresa sa zistí pri pripojení")
                    }
                    var menu by remember { mutableStateOf(false) }
                    androidx.compose.foundation.layout.Box {
                        IconButton(onClick = { menu = true }) { Icon(Sym.moreVert, contentDescription = "Viac") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Zabudnúť tento PC") }, onClick = { menu = false; forget = pc })
                        }
                    }
                }
                Button(
                    onClick = { PcRemoteActivity.open(context, pc.serverId) },
                    enabled = wifiOk,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ovládať") }
                if (pc.macs.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { PcRemoteActivity.open(context, pc.serverId, wake = true) },
                        enabled = wifiOk,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Zapnúť PC (Wake-on-LAN)") }
                }
            }
        }

        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Text(if (pcs.isEmpty()) "Pridať PC" else "Pridať ďalší PC")
        }

        DomovCard {
            Text("Ako na to", style = MaterialTheme.typography.titleSmall)
            PcHint(
                "1. Na PC s Windowsom 10/11 spusti program domov-pc.exe (stiahneš ho z GitHubu, v Releases pri appke Domov). " +
                    "Windows sa môže opýtať na firewall: povoľ ho pre súkromné siete.\n" +
                    "2. Na PC sa otvorí stránka správy. Klikni „Spárovať nový telefón“, ukáže sa QR kód a kód.\n" +
                    "3. Tu ťukni na Pridať PC a QR kód naskenuj.\n\n" +
                    "Zapnutie PC cez Wake-on-LAN funguje len, ak je PC pripojený káblom a v BIOSe/Windowse je zapnuté. " +
                    "Pozri README na GitHube.",
            )
            PcHint(
                "Čo sa nedá: zamknutú obrazovku Windowsu a prihlasovanie hesla (tie ovláda len systém), okná spustené ako správca " +
                    "(ak program nebeží ako správca) a Ctrl+Alt+Del. Odomkni PC priamo na ňom.",
            )
        }
    }

    val f = forget
    if (f != null) {
        DomovAlert(
            onDismissRequest = { forget = null },
            title = { Text("Zabudnúť ${f.name}?") },
            text = { Text("Telefón sa od tohto PC odpojí a bude ho treba spárovať znova. Na PC ho môžeš odstrániť aj zo zoznamu telefónov na stránke správy.") },
            danger = true,
            confirmButton = {
                DialogPrimaryButton(onClick = {
                    PcStore.remove(context, f.serverId)
                    pcs = PcStore.list(context)
                    forget = null
                }) { Text("Zabudnúť") }
            },
            dismissButton = { DialogTextButton(onClick = { forget = null }) { Text("Zrušiť") } },
        )
    }
}

// ------------------------------------------------------------------ párovanie

@Composable
fun PcAddScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hostText by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<PcEntry?>(null) }
    var found by remember { mutableStateOf<List<PcDiscovery.Found>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    fun runPair(hosts: List<String>, port: Int, pairCode: String, serverId: String?) {
        busy = true
        error = null
        scope.launch {
            when (val r = PcNet.pair(context, hosts, port, pairCode, serverId)) {
                is PcNet.PairResult.Ok -> done = r.entry
                is PcNet.PairResult.Fail -> error = r.message
            }
            busy = false
        }
    }

    fun search() {
        val net = wifiNetwork(context)
        if (net == null) {
            error = PcNet.NO_WIFI
            return
        }
        searching = true
        scope.launch {
            found = PcNet.discover(context, net, emptyList(), 3000)
            searching = false
        }
    }

    LaunchedEffect(Unit) { search() }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { r ->
        val text = r.contents
        if (text != null) {
            val link = PcLink.parse(text)
            if (link == null) {
                error = "Toto nie je QR kód z programu Domov PC. Na PC klikni „Spárovať nový telefón“ a naskenuj ten QR kód."
            } else {
                runPair(link.hosts, link.port, link.code, link.serverId)
            }
        }
    }

    PageScaffold("Pridať PC", onBack) {
        val ok = done
        if (ok != null) {
            DomovCard {
                Text("Spárované", style = MaterialTheme.typography.titleMedium, color = Domov.colors.good)
                Text("Telefón je spárovaný s PC „${ok.name}“.", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = {
                        PcRemoteActivity.open(context, ok.serverId)
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ovládať PC") }
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Hotovo") }
            }
            return@PageScaffold
        }

        Text(
            "Na PC spusti Domov PC a v otvorenej stránke klikni „Spárovať nový telefón“. Ukáže sa QR kód a kód na prepísanie. " +
                "Telefón a PC musia byť na rovnakej WiFi.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            enabled = !busy,
            onClick = {
                error = null
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Naskenuj QR kód z programu Domov PC")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Naskenovať QR kód") }

        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            PcHint("Párujem… (môže to trvať pár sekúnd)")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        Text("Alebo ručne", style = MaterialTheme.typography.titleSmall)
        if (searching) PcHint("Hľadám PC v sieti…")
        if (!searching && found.isNotEmpty()) {
            PcHint("Nájdené v sieti (ťukni, čím vyplníš adresu):")
            found.forEach { f ->
                OutlinedButton(
                    onClick = { hostText = if (f.port == PcProto.DEFAULT_PORT) f.host else "${f.host}:${f.port}" },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("${f.name.ifBlank { "PC" }}  (${f.host})") }
            }
        }
        if (!searching && found.isEmpty()) {
            PcHint("V sieti sa nenašiel žiadny PC s Domov PC. Je program spustený a Windows firewall ho nechal? Adresu PC môžeš zadať aj ručne.")
            OutlinedButton(onClick = { search() }, modifier = Modifier.fillMaxWidth()) { Text("Hľadať znova") }
        }
        DomovTextField(
            value = hostText,
            onValueChange = { hostText = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Adresa PC") },
            placeholder = { Text("napr. 192.168.1.20") },
        )
        DomovTextField(
            value = code,
            onValueChange = { code = it.uppercase().take(14); error = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Párovací kód") },
            placeholder = { Text("ABCDE-FGHJK") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        )
        OutlinedButton(
            enabled = !busy && hostText.isNotBlank() && code.isNotBlank(),
            onClick = {
                val hp = PcNet.parseHostPort(hostText)
                if (hp == null) {
                    error = "Zadaj adresu PC z domácej siete, napr. 192.168.1.20 (ukáže ju stránka správy programu Domov PC)."
                } else {
                    runPair(listOf(hp.first), if (hp.second == 0) PcProto.DEFAULT_PORT else hp.second, code, null)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Spárovať") }
        PcHint("Kód platí len pár minút po kliknutí na „Spárovať nový telefón“ a po úspešnom spárovaní prestane platiť.")
    }
}
