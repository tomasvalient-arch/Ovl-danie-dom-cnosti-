package sk.domov.ovladanie

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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

    // Nastavenia › Počítač (od 1.40.0): len správa – spárované PC, pridanie, návod. Ovládanie je na karte Počítač v lište.
    PageScaffold("Počítač – spárované PC", onBack) {
        Text(
            "Spárované PC a párovanie nového. Samotné ovládanie (obraz, myš, klávesnica, zapnutie PC) je na karte Počítač v spodnej lište.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!wifiOk) PcHint(PcNet.NO_WIFI, warning = true)

        pcs.forEach { pc ->
            DomovCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                        Text(pc.name, style = MaterialTheme.typography.titleMedium)
                        PcHint(pc.hosts.firstOrNull()?.let { "Naposledy na adrese $it" } ?: "Adresa sa zistí pri pripojení")
                        PcHint(if (pc.macs.isNotEmpty()) "Zapnutie cez Wake-on-LAN: áno" else "Zapnutie cez Wake-on-LAN: PC neposlal MAC adresu")
                    }
                    var menu by remember { mutableStateOf(false) }
                    androidx.compose.foundation.layout.Box {
                        IconButton(onClick = { menu = true }) { Icon(Sym.moreVert, contentDescription = "Viac") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Zabudnúť tento PC") }, onClick = { menu = false; forget = pc })
                        }
                    }
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
                    runCatching { PcStore.remove(context, f.serverId) } // dočasná chyba Keystore: nič sa nezmaže
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
fun PcAddScreen(onBack: () -> Unit, autoScan: Boolean = false) {
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

    // z karty Počítač („Naskenovať QR kód“) sa skener otvorí hneď, raz
    var scanStarted by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoScan) {
        if (autoScan && !scanStarted) {
            scanStarted = true
            scanner.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Naskenuj QR kód z programu Domov PC")
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
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
                FilledTonalButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Hotovo") }
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
                FilledTonalButton(
                    onClick = { hostText = if (f.port == PcProto.DEFAULT_PORT) f.host else "${f.host}:${f.port}" },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("${f.name.ifBlank { "PC" }}  (${f.host})") }
            }
        }
        if (!searching && found.isEmpty()) {
            PcHint("V sieti sa nenašiel žiadny PC s Domov PC. Je program spustený a Windows firewall ho nechal? Adresu PC môžeš zadať aj ručne.")
            FilledTonalButton(onClick = { search() }, modifier = Modifier.fillMaxWidth()) { Text("Hľadať znova") }
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
        FilledTonalButton(
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

// ------------------------------------------------------------------ karta Počítač v lište (od 1.40.0)

/**
 * Karta Počítač: spárovaný PC = jedno ťuknutie na Ovládať, voľba režimu (touchpad / priamy dotyk), písanie a Wake-on-LAN.
 * Bez spárovaného PC rovno párovanie. Samotné ovládanie je naďalej PcRemoteActivity na celú obrazovku (lišta sa skryje,
 * Späť sa vráti sem). Stav „zapnutý“ sa zámerne nezisťuje – ukazuje sa posledná známa adresa.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun PcTab(wifiOn: Boolean, pagesOpen: Int, onPage: (String) -> Unit) {
    val context = LocalContext.current
    // znova po návrate z párovania / správy PC
    val pcs = remember(pagesOpen) { runCatching { PcStore.list(context) }.getOrDefault(emptyList()) }
    var modeTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    TabPage("Počítač") {
        if (pcs.isEmpty()) {
            PcPairCard(onScan = { onPage("pc-add-qr") }, onManual = { onPage("pc-add") })
            Text(
                "Čo sa nedá: zamknutá obrazovka Windowsu, Ctrl+Alt+Del a okná spustené ako správca. Podrobný návod: Nastavenia › Počítač.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            return@TabPage
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InfoChip(if (wifiOn) Sym.wifi else Sym.wifiOff, if (wifiOn) "Na WiFi" else "Bez WiFi", error = !wifiOn)
            InfoChip(Sym.check, if (pcs.size == 1) "1 spárovaný PC" else "Spárované PC: ${pcs.size}", good = true)
        }
        if (!wifiOn) PcHint(PcNet.NO_WIFI, warning = true)

        pcs.forEach { pc0 ->
            // režim (touchpad / priamy dotyk) sa číta z uloženého PC – po zmene sa karta prekreslí
            val pc = remember(pc0.serverId, modeTick) { runCatching { PcStore.get(context, pc0.serverId) }.getOrNull() ?: pc0 }
            fun open(direct: Boolean? = null, keyboard: Boolean = false, wake: Boolean = false) {
                if (direct != null && direct != pc.direct) {
                    runCatching { PcStore.update(context, pc.serverId) { it.copy(direct = direct) } }
                    modeTick++
                }
                PcRemoteActivity.open(context, pc.serverId, wake = wake, keyboard = keyboard)
            }
            PcCard(
                pc = pc,
                wifiOn = wifiOn,
                onOpen = { open() },
                onMode = { open(direct = it) },
                onManage = { onPage("pc") },
            )
            SectionLabel(if (pcs.size == 1) "Rýchle akcie" else "Rýchle akcie · ${pc.name}")
            DomovCard(padding = 10.dp, spacing = 0.dp) {
                SettingRow(Sym.keyboard, "Písať na PC", caption = "Otvorí ovládanie rovno s klávesnicou", enabled = wifiOn, onClick = { open(keyboard = true) })
                SettingRow(
                    Sym.power, "Zapnúť PC (Wake-on-LAN)",
                    caption = if (pc.macs.isNotEmpty()) "Keď je PC vypnutý alebo uspatý" else "PC neposlal MAC adresu – zapnutie nejde",
                    enabled = wifiOn && pc.macs.isNotEmpty(),
                    onClick = { open(wake = true) },
                )
            }
        }
        Text(
            "Ovládanie sa otvorí na celú obrazovku – spodná lišta sa skryje, Späť ťa vráti sem. Párovanie a správa PC: Nastavenia › Počítač.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

@Composable
private fun PcCard(pc: PcEntry, wifiOn: Boolean, onOpen: () -> Unit, onMode: (Boolean) -> Unit, onManage: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    DomovCard(padding = 16.dp, spacing = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(56.dp).background(scheme.onPrimaryContainer.copy(alpha = 0.9f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Sym.desktopWindows, contentDescription = null, tint = scheme.primaryContainer, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.width(14.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(pc.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                PcHint(pc.hosts.firstOrNull()?.let { "Naposledy na adrese $it" } ?: "Adresa sa zistí pri pripojení")
            }
            var menu by remember { mutableStateOf(false) }
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menu = true }) { Icon(Sym.moreVert, contentDescription = "Viac") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Spárované PC a párovanie") }, onClick = { menu = false; onManage() })
                }
            }
        }
        Button(
            onClick = onOpen,
            enabled = wifiOn,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            Icon(Sym.playArrow, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text("Ovládať", style = MaterialTheme.typography.titleMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeTile(Sym.mouse, "Touchpad", "Prst hýbe kurzorom ako na notebooku", selected = !pc.direct, enabled = wifiOn, modifier = Modifier.weight(1f)) { onMode(false) }
            ModeTile(Sym.touchApp, "Priamy dotyk", "Ťukneš priamo tam, kam chceš kliknúť", selected = pc.direct, enabled = wifiOn, modifier = Modifier.weight(1f)) { onMode(true) }
        }
    }
}

/** Režim ovládania: ťuknutie ho uloží a otvorí ovládanie. Naposledy použitý režim je zvýraznený. */
@Composable
private fun ModeTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, caption: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.alpha(if (enabled) 1f else 0.5f),
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainer,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.surfaceVariant),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = scheme.primaryContainer, modifier = Modifier.size(24.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, minLines = 2)
            if (selected) Text("Naposledy použitý", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
        }
    }
}

/** Karta Počítač bez spárovaného PC: tri kroky a rovno párovanie. */
@Composable
private fun PcPairCard(onScan: () -> Unit, onManual: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    DomovCard(padding = 16.dp, spacing = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(56.dp).background(scheme.onPrimaryContainer.copy(alpha = 0.9f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Sym.desktopWindows, contentDescription = null, tint = scheme.primaryContainer, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.width(14.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text("Ovládaj PC z telefónu", style = MaterialTheme.typography.titleMedium)
                PcHint("Obraz, myš, klávesnica a zapnutie PC – len doma na WiFi, šifrované")
            }
        }
        listOf(
            "Na PC s Windowsom spusti domov-pc.exe (GitHub › Releases) a povoľ ho vo firewalle.",
            "Na stránke správy klikni „Spárovať nový telefón“.",
            "Naskenuj QR kód tu.",
        ).forEachIndexed { i, t ->
            Row {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(22.dp).background(scheme.onPrimaryContainer.copy(alpha = 0.9f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = scheme.primaryContainer) }
                Spacer(Modifier.width(10.dp))
                Text(t, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp)) {
            Icon(Sym.qrCodeScanner, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("Naskenovať QR kód", style = MaterialTheme.typography.titleMedium)
        }
        FilledTonalButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
            Icon(Sym.search, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Hľadať PC v sieti alebo zadať ručne")
        }
    }
}
