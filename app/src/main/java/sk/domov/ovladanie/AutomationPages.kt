@file:OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Podstránky Nastavenia › Automatika (od 1.47). Každá voľba má vlastnú obrazovku. */
object AutoPage {
    const val WIFI = "presence-wifi"
    const val DELAY = "presence-delay"
    const val ARRIVE = "presence-arrive"
    const val DEPART = "presence-depart"
    const val DARK = "presence-dark"
    const val GUARD = "presence-guard"
    const val TRY = "presence-try"
    const val HISTORY = "presence-history"
    const val BRIEF = "brief"
    const val NOTIFY_DONE = "presence-notify-done"
}

private class PresenceEdit(
    val cfg: PresenceConfig,
    val update: (PresenceConfig) -> Unit,
    val devices: List<Device>,
    val kinds: Map<String, AutoKind>,
    val values: (Device) -> Map<String, Any>,
    val members: List<Member>,
    val status: PresenceStatus,
    val history: List<PresenceStore.Entry>,
    val cloudSet: Boolean,
)

@Composable
private fun rememberPresenceEdit(vm: HomeViewModel): PresenceEdit {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        val prefs = PresenceStore.prefs(context)
        val house = HouseholdStore.listenPrefs(context)
        prefs.registerOnSharedPreferenceChangeListener(l)
        house.registerOnSharedPreferenceChangeListener(l)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(l)
            house.unregisterOnSharedPreferenceChangeListener(l)
        }
    }
    val cfg = remember(tick) { PresenceStore.config(context) }
    val devices by vm.devices.collectAsState()
    val states by vm.states.collectAsState()
    val known by vm.known.collectAsState()
    val values: (Device) -> Map<String, Any> = { d -> deviceValues(d, states, known) }
    val kinds = remember(devices, states, known, tick) { devices.associate { it.id to AutoDevices.kind(it, values(it)) } }
    return PresenceEdit(
        cfg = cfg,
        update = { new ->
            PresenceStore.saveConfig(context, new)
            PresenceService.sync(context)
            tick++
        },
        devices = devices,
        kinds = kinds,
        values = values,
        members = remember(tick) { HouseholdStore.members(context) },
        status = remember(tick) { PresenceStore.status(context) },
        history = remember(tick) { PresenceStore.history(context) },
        cloudSet = remember(tick) { CloudStore(context).load() != null },
    )
}

@Composable
private fun Hint(text: String, warning: Boolean = false) {
    if (warning) NoticeCard(text, NoticeKind.Warning) else InfoHint(text)
}

/** Domáca WiFi: uloženie aktuálnej siete a overenie zariadením. */
@Composable
internal fun PresenceWifiScreen(vm: HomeViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val edit = rememberPresenceEdit(vm)
    var busy by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    SettingsSubPage("Domáca WiFi", onBack, subtitle = edit.cfg.home?.title ?: "Zatiaľ nenastavená") {
        DomovCard(padding = 16.dp) {
            Text(
                edit.cfg.home?.let { "Uložená sieť: ${it.title}" } ?: "Domáca WiFi ešte nie je nastavená.",
                style = MaterialTheme.typography.bodyLarge,
            )
            FilledTonalButton(
                enabled = busy == null,
                onClick = {
                    val net = wifiNetwork(context)
                    val fp = net?.let { HomeNet.fingerprint(context, it) }
                    if (net == null || fp == null) {
                        info = "Telefón nie je pripojený na WiFi (alebo sieť ešte nemá adresu). Pripoj sa doma na WiFi a skús znova."
                        return@FilledTonalButton
                    }
                    busy = "Overujem, či odpovedajú tvoje zariadenia…"
                    scope.launch(LogOnError) {
                        val answered = HomeNet.verify(context, net)
                        busy = null
                        PresenceStore.saveStatus(context, PresenceStatus(PresenceState.HOME, System.currentTimeMillis()))
                        edit.update(edit.cfg.copy(home = fp))
                        PresenceStore.log(context, "Domáca WiFi nastavená: ${fp.title}.")
                        info = if (answered != null) "Uložené. Odpovedalo zariadenie „${answered.name}“ – sieť je overená."
                        else "Uložené, ale žiadne zariadenie teraz neodpovedalo. Príchod sa vyhodnotí, až keď niektoré zariadenie odpovie – skontroluj IP adresy zariadení."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (edit.cfg.home == null) "Toto je moja domáca WiFi" else "Uložiť aktuálnu WiFi ako domácu") }
            busy?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            info?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        Hint(
            "Na rozpoznanie domácej siete appka nepotrebuje polohu ani povolenie „poloha na pozadí“. Sieť pozná podľa adresy " +
                "routera a rozsahu adries a že je naozaj doma, overí tak, že jej odpovie niektoré tvoje zariadenie s vlastným " +
                "local key (cudzia sieť s rovnakou adresou routera to nedokáže). Názov WiFi (SSID) Android bez polohy appke " +
                "neprezradí, preto sa zobrazuje len adresa routera.",
        )
    }
}

/** Čas, po ktorom sa odchod vyhodnotí. */
@Composable
internal fun PresenceDelayScreen(vm: HomeViewModel, onBack: () -> Unit) {
    val edit = rememberPresenceEdit(vm)
    SettingsSubPage("Odchod po", onBack, subtitle = "${edit.cfg.departDelayMin} min bez domácej WiFi") {
        DomovCard(padding = 16.dp) {
            Text("Vypnúť po ${edit.cfg.departDelayMin} min bez domácej WiFi", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ChipRow {
                listOf(1, 2, 3, 5, 10, 15, 30).forEach { m ->
                    DomovChip(label = "$m min", selected = edit.cfg.departDelayMin == m, onClick = { edit.update(edit.cfg.copy(departDelayMin = m)) })
                }
            }
        }
        Hint("Ak sa telefón do tohto času znova pripojí (krátky výpadok WiFi), nič sa nevypne a ani nezapne.")
    }
}

/** Zariadenia, ktoré sa zapnú pri príchode domov. */
@Composable
internal fun PresenceArriveScreen(vm: HomeViewModel, onBack: () -> Unit) {
    val edit = rememberPresenceEdit(vm)
    val candidates = edit.devices.filter { AutoDevices.canArrive(edit.kinds[it.id] ?: AutoKind.OTHER) }
    val unknown = edit.devices.filter { edit.kinds[it.id] == AutoKind.UNKNOWN }
    SettingsSubPage("Pri príchode zapnúť", onBack, subtitle = namesText(candidates.filter { it.id in edit.cfg.arriveIds }.map { it.name })) {
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            if (candidates.isEmpty()) {
                Text("Žiadne vhodné zariadenie.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            candidates.forEach { d ->
                CheckRow(d.name, kindTitle(edit.kinds[d.id] ?: AutoKind.OTHER), d.id in edit.cfg.arriveIds) { on ->
                    edit.update(edit.cfg.copy(arriveIds = if (on) edit.cfg.arriveIds + d.id else edit.cfg.arriveIds - d.id))
                }
            }
        }
        if (unknown.isNotEmpty()) {
            Hint("Neponúkajú sa zariadenia, o ktorých appka zatiaľ nevie, čo sú (${unknown.joinToString { it.name }}). Otvor ich raz v appke, aby sa načítali ich hodnoty.")
        }
        Hint("Zapína sa hlavný vypínač (svetlo sa zapne s posledným jasom a farbou). Fritézy, ohrievače a termostaty sa pri príchode nikdy nezapínajú a ani sa tu neponúkajú. Pozor na zásuvky – zapne sa všetko, čo je do nich zapojené.")
        SectionLabel("Keď je už niekto doma")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            CheckRow(
                "Zapnúť aj keď je už niekto doma",
                "Predvolene sa pri príchode nič nezapína, ak sa iný telefón domácnosti práve ozval z domu (svetlá si už zapol ten, kto je doma).",
                edit.cfg.arriveIfSomeoneHome,
            ) { edit.update(edit.cfg.copy(arriveIfSomeoneHome = it)) }
        }
    }
}

/** Zariadenia vypínané pri odchode a upozornenie po vykonaní. */
@Composable
internal fun PresenceDepartScreen(vm: HomeViewModel, onBack: () -> Unit, onCloud: () -> Unit) {
    val edit = rememberPresenceEdit(vm)
    val departIds = AutoDevices.departIds(edit.cfg, edit.devices, edit.values)
    val departable = edit.devices.filter { AutoDevices.canDepart(edit.kinds[it.id] ?: AutoKind.OTHER) }
    SettingsSubPage("Pri odchode vypnúť", onBack, subtitle = namesText(edit.devices.filter { it.id in departIds }.map { it.name })) {
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            departable.forEach { d ->
                CheckRow(d.name, kindTitle(edit.kinds[d.id] ?: AutoKind.OTHER), d.id in departIds) { on ->
                    edit.update(edit.cfg.copy(departIds = if (on) departIds + d.id else departIds - d.id))
                }
            }
        }
        Hint("Predvolene všetky svetlá a zásuvky.")
        if (!edit.cloudSet) {
            Hint("Tuya cloud nie je nastavený – mimo domu appka zariadenia nedosiahne, takže ich nevypne a len ťa upozorní „Nepodarilo sa zhasnúť“. Nastav cloud, alebo zapni poistku.", warning = true)
            FilledTonalButton(onClick = onCloud, modifier = Modifier.fillMaxWidth()) { Text("Nastaviť Tuya cloud") }
        } else {
            Hint("Vypína sa cez Tuya cloud (telefón je už mimo WiFi, ide cez mobilné dáta).")
        }
        SectionLabel("Upozornenie")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            CheckRow(
                "Upozornenie po vykonaní",
                "Napríklad „Odišiel si – zhaslo 4 svetlá“.",
                edit.cfg.notifyDone,
            ) { edit.update(edit.cfg.copy(notifyDone = it)) }
        }
    }
}

/** „Len po zotmení“: podľa slnka alebo ručný čas. */
@Composable
internal fun PresenceDarkScreen(vm: HomeViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val edit = rememberPresenceEdit(vm)
    val sun by rememberSunInfo(edit.cfg)
    var pickFrom by remember { mutableStateOf(false) }
    var pickTo by remember { mutableStateOf(false) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(context, "Bez polohy zadaj súradnice ručne alebo použi čas od – do.", Toast.LENGTH_LONG).show()
        } else {
            lastLocation(context) { loc ->
                if (loc == null) Toast.makeText(context, "Poloha zatiaľ nie je známa. Zapni polohu v telefóne a skús znova, alebo zadaj súradnice ručne.", Toast.LENGTH_LONG).show()
                else edit.update(edit.cfg.copy(lat = (loc.latitude * 100).roundToInt() / 100.0, lon = (loc.longitude * 100).roundToInt() / 100.0))
            }
        }
    }
    val caption = when {
        !edit.cfg.onlyWhenDark -> "Vypnuté · príchod zapína vždy"
        edit.cfg.darkMode == DarkMode.MANUAL -> "Ručne · od ${Darkness.hhmm(edit.cfg.darkFrom)} do ${Darkness.hhmm(edit.cfg.darkTo)}"
        else -> "Podľa slnka" + (sun?.let { i -> if (i.setText != null && i.riseText != null) " · dnes od ${i.setText} do ${i.riseText}" else "" } ?: "")
    }
    SettingsSubPage("Len keď je tma", onBack, subtitle = caption) {
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            CheckRow(
                "Len po zotmení",
                "Keď je vypnuté, príchod zapína vybrané svetlá vždy.",
                edit.cfg.onlyWhenDark,
            ) { edit.update(edit.cfg.copy(onlyWhenDark = it)) }
        }
        if (!edit.cfg.onlyWhenDark) {
            sun?.let { Hint("Dnes západ slnka o ${it.setText ?: "–"} (${it.place.label()}).") }
        }
        if (edit.cfg.onlyWhenDark) {
            SectionLabel("Ako sa určí tma")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogOption(
                    title = "Podľa západu a východu slnka",
                    selected = edit.cfg.darkMode == DarkMode.SUN,
                    onClick = { edit.update(edit.cfg.copy(darkMode = DarkMode.SUN)) },
                )
                DialogOption(
                    title = "Ručne zadaný čas od – do",
                    selected = edit.cfg.darkMode == DarkMode.MANUAL,
                    onClick = { edit.update(edit.cfg.copy(darkMode = DarkMode.MANUAL)) },
                )
            }
            if (edit.cfg.darkMode == DarkMode.SUN) {
                ModernSunSettings(edit.cfg, edit.update) {
                    locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
            }
            if (edit.cfg.darkMode == DarkMode.MANUAL) {
                DomovCard(padding = 10.dp, spacing = 0.dp) {
                    DialogPickRow(Sym.schedule, "Tma od", Darkness.hhmm(edit.cfg.darkFrom)) { pickFrom = true }
                    DialogPickRow(Sym.schedule, "Tma do", Darkness.hhmm(edit.cfg.darkTo)) { pickTo = true }
                }
                Hint("Napr. 17:00 – 6:30 (cez polnoc). Pri príchode v tomto čase sa svetlá zapnú, inokedy nie.")
            }
        }
    }
    if (pickFrom) {
        WheelTimeDialog(
            title = "Tma od",
            initialMinutes = edit.cfg.darkFrom,
            preview = { m -> "Od ${Darkness.hhmm(m)}" },
            onConfirm = { m -> edit.update(edit.cfg.copy(darkFrom = m)); pickFrom = false },
            onDismiss = { pickFrom = false },
        )
    }
    if (pickTo) {
        WheelTimeDialog(
            title = "Tma do",
            initialMinutes = edit.cfg.darkTo,
            preview = { m -> "Do ${Darkness.hhmm(m)}" },
            onConfirm = { m -> edit.update(edit.cfg.copy(darkTo = m)); pickTo = false },
            onDismiss = { pickTo = false },
        )
    }
}

@Composable
private fun ModernSunSettings(cfg: PresenceConfig, update: (PresenceConfig) -> Unit, onLocate: () -> Unit) {
    var lat by remember(cfg.lat) { mutableStateOf(cfg.lat?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "") }
    var lon by remember(cfg.lon) { mutableStateOf(cfg.lon?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "") }
    Hint("Východ a západ slnka sa počítajú priamo v telefóne (NOAA) z najpresnejšej známej polohy: ak má appka povolenie na polohu, z poslednej známej polohy telefónu (GPS sa kvôli tomu nezapína), inak zo súradníc domu nižšie, inak z miesta počasia.")
    FilledTonalButton(onClick = onLocate, modifier = Modifier.fillMaxWidth()) { Text("Zistiť polohu z telefónu (raz)") }
    DomovCard(padding = 12.dp, spacing = 10.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DomovTextField(value = lat, onValueChange = { lat = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Šírka") })
            DomovTextField(value = lon, onValueChange = { lon = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Dĺžka") })
        }
        val la = lat.replace(',', '.').toDoubleOrNull()
        val lo = lon.replace(',', '.').toDoubleOrNull()
        val valid = la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0
        FilledTonalButton(
            enabled = valid && (la != cfg.lat || lo != cfg.lon),
            onClick = { update(cfg.copy(lat = la, lon = lo)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Uložiť súradnice") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { update(cfg.copy(lat = 48.15, lon = 17.11)) }) { Text("Bratislava") }
            TextButton(onClick = { update(cfg.copy(lat = 48.72, lon = 21.26)) }) { Text("Košice") }
        }
        SunToday(cfg)
    }
}

/** Poistka odpočtom na zariadení, keď nie je cloud. */
@Composable
internal fun PresenceGuardScreen(vm: HomeViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val edit = rememberPresenceEdit(vm)
    val guardable = edit.devices.filter { AutoDevices.countdownDp(it, edit.values(it)) != null }
    SettingsSubPage("Poistka bez cloudu", onBack, subtitle = if (edit.cfg.guardEnabled) "Zapnutá · ${Scenes.devicesText(edit.cfg.guardIds.size)}" else "Vypnutá · nepovinné") {
        Hint(
            "Kým si doma, appka zapnutým zariadeniam každých ${edit.cfg.guardRefreshMin} min nastaví odpočet " +
                "${edit.cfg.guardSeconds / 60} min. Keď odídeš, odpočet už nikto neobnoví a zariadenie sa samo vypne – aj bez cloudu " +
                "a bez internetu. Vypnutým zariadeniam appka odpočet zruší.",
        )
        Hint(
            "Pozor: ak telefón doma zaspí, vybije sa alebo sa odpojí od WiFi (aj v noci), svetlá po odpočte zhasnú, hoci si doma. " +
                "Zariadenia zapnuté inak (vypínač, Tuya appka) appka tiež „poistí“.",
            warning = true,
        )
        if (edit.members.isNotEmpty()) {
            Hint(
                "Viac telefónov: poistku zapni na všetkých telefónoch pre tie isté zariadenia. Odpočet obnovuje každý " +
                    "telefón, ktorý je doma, takže keď jeden odíde, druhý ho ďalej obnovuje. Ak by ju mal zapnutú len jeden telefón, " +
                    "po jeho odchode by svetlá zhasli aj tomu, kto je doma.",
                warning = true,
            )
        }
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            CheckRow(
                "Zapnúť poistku",
                if (guardable.isEmpty()) "Žiadne zariadenie nemá odpočet (countdown). Otvor zariadenia raz v appke, aby sa načítali ich hodnoty." else null,
                edit.cfg.guardEnabled,
                enabled = guardable.isNotEmpty(),
            ) { on ->
                if (!on) scope.launch(LogOnError) { PresenceActions.guardClearAll(context) }
                edit.update(edit.cfg.copy(guardEnabled = on))
            }
        }
        if (edit.cfg.guardEnabled) {
            SectionLabel("Zariadenia")
            DomovCard(padding = 10.dp, spacing = 0.dp) {
                guardable.forEach { d ->
                    CheckRow(d.name, kindTitle(edit.kinds[d.id] ?: AutoKind.OTHER), d.id in edit.cfg.guardIds) { on ->
                        if (!on) scope.launch(LogOnError) {
                            val dp = AutoDevices.countdownDp(d, edit.values(d))?.first
                            val net = wifiNetwork(context)
                            if (dp != null && net != null && PresenceStore.guardSet(context, d.id) > 0) {
                                withContext(Dispatchers.IO) { runCatching { DeviceClient.setValues(net, d, mapOf(dp to 0), "zrušenie poistky") } }
                                PresenceStore.setGuardSet(context, d.id, 0L)
                            }
                        }
                        edit.update(edit.cfg.copy(guardIds = if (on) edit.cfg.guardIds + d.id else edit.cfg.guardIds - d.id))
                    }
                }
            }
            SectionLabel("Dĺžka odpočtu")
            DomovCard(padding = 16.dp) {
                ChipRow {
                    listOf(10, 15, 20, 30, 60).forEach { m ->
                        DomovChip(label = "$m min", selected = edit.cfg.guardSeconds / 60 == m, onClick = { edit.update(edit.cfg.copy(guardMinutes = m)) })
                    }
                }
            }
            SectionLabel("Obnovovať každých")
            DomovCard(padding = 16.dp) {
                ChipRow {
                    listOf(2, 3, 5, 10).forEach { m ->
                        DomovChip(label = "$m min", selected = edit.cfg.guardRefreshMin == m, onClick = { edit.update(edit.cfg.copy(guardRefreshMin = m)) })
                    }
                }
            }
            Hint("Odpočet je vždy aspoň o 5 minút dlhší než interval obnovy.")
        }
    }
}

/** Ručný test príchodu a odchodu. */
@Composable
internal fun PresenceTryScreen(vm: HomeViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    SettingsSubPage("Vyskúšať príchod / odchod", onBack, subtitle = "Spustí akciu hneď, bez čakania") {
        Hint("Spustí akciu hneď, bez čakania a bez ohľadu na „len po zotmení“. Stav doma / mimo domu sa nemení.")
        FilledTonalButton(
            enabled = busy == null,
            onClick = {
                busy = "Zapínam…"
                scope.launch(LogOnError) { result = PresenceActions.arrive(context, null, manual = true); busy = null }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Vyskúšať príchod") }
        FilledTonalButton(
            enabled = busy == null,
            onClick = {
                busy = "Vypínam cez Tuya cloud…"
                scope.launch(LogOnError) { result = PresenceActions.depart(context, manual = true); busy = null }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Vyskúšať odchod") }
        busy?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** História automatiky. */
@Composable
internal fun PresenceHistoryScreen(vm: HomeViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val edit = rememberPresenceEdit(vm)
    SettingsSubPage("História", onBack, subtitle = if (edit.history.isEmpty()) "Zatiaľ nič" else "${edit.history.size} záznamov") {
        DomovCard(padding = 14.dp, spacing = 10.dp) {
            if (edit.history.isEmpty()) {
                Text("Zatiaľ nič.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            edit.history.take(15).forEach { e ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(timeText(e.time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 78.dp))
                    Text(e.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            if (edit.history.isNotEmpty()) {
                TextButton(onClick = { PresenceStore.clearHistory(context) }) { Text("Vymazať históriu") }
            }
        }
    }
}

/** Ranný prehľad ako samostatná obrazovka (rovnaké nastavenia ako v Upozorneniach). */
@Composable
internal fun MorningBriefScreen(onBack: () -> Unit) {
    SettingsSubPage("Ranný prehľad", onBack, subtitle = "Jedna notifikácia každé ráno, len v tomto telefóne") {
        MorningBriefSection()
    }
}

/** Len upozornenie po vykonaní príchodu/odchodu (nie výber zariadení). */
@Composable
internal fun PresenceNotifyDoneScreen(vm: HomeViewModel, onBack: () -> Unit, onDepart: () -> Unit) {
    val edit = rememberPresenceEdit(vm)
    SettingsSubPage(
        "Upozornenie po príchode a odchode",
        onBack,
        subtitle = if (edit.cfg.notifyDone) "Zapnuté" else "Vypnuté",
    ) {
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            CheckRow(
                "Upozornenie po vykonaní",
                "Napríklad „Odišiel si – zhaslo 4 svetlá“ alebo že sa svetlá pri príchode zapli.",
                edit.cfg.notifyDone,
            ) { edit.update(edit.cfg.copy(notifyDone = it)) }
        }
        Hint("Čo sa pri odchode vypne a pri príchode zapne, nastavíš v Automatike – nie tu.")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(
                Sym.powerSettingsNew,
                "Pri odchode vypnúť",
                caption = "Nastavuje sa v Automatike",
                onClick = onDepart,
            )
        }
    }
}
