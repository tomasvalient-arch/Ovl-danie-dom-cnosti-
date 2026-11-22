@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package sk.domov.ovladanie

import androidx.compose.foundation.layout.widthIn
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

internal fun isIgnoringBattery(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

@SuppressLint("BatteryLife")
internal fun openBatterySettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e2: Exception) {
            Toast.makeText(context, "Otvor Nastavenia → Aplikácie → Domov → Batéria → Bez obmedzení.", Toast.LENGTH_LONG).show()
        }
    }
}

/** Posledná známa poloha (len hrubá, na výpočet západu slnka). */
@SuppressLint("MissingPermission")
private fun lastLocation(context: Context, onResult: (Location?) -> Unit) {
    val lm = context.getSystemService(LocationManager::class.java)
    if (lm == null) { onResult(null); return }
    val known = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    if (known != null) { onResult(known); return }
    if (Build.VERSION.SDK_INT >= 30) {
        val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
        try {
            lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(context)) { onResult(it) }
        } catch (e: Exception) {
            onResult(null)
        }
    } else {
        onResult(null)
    }
}

private fun Double.short(): String = String.format(Locale.US, "%.2f", this)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Note(text: String, warning: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SwitchLine(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

private fun kindTitle(k: AutoKind): String = when (k) {
    AutoKind.LIGHT -> "svetlo"
    AutoKind.SOCKET -> "zásuvka / vypínač"
    AutoKind.FAN -> "ventilátor / zvlhčovač"
    AutoKind.FRYER -> "fritéza"
    AutoKind.HEATER -> "ohrievač"
    AutoKind.THERMOSTAT -> "termostat"
    AutoKind.COVER -> "rolety"
    AutoKind.SENSOR -> "senzor"
    AutoKind.GATEWAY -> "brána"
    AutoKind.OTHER -> "iné"
    AutoKind.UNKNOWN -> "neznámy typ"
}

@Composable
fun PresenceScreen(vm: HomeViewModel, onBack: () -> Unit, onCloud: () -> Unit, onHousehold: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf(PresenceStore.config(context)) }
    var tick by remember { mutableIntStateOf(0) } // zmena stavu / histórie v službe
    var battery by remember { mutableStateOf(isIgnoringBattery(context)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var netInfo by remember { mutableStateOf<String?>(null) }
    val devices = remember { DeviceStore(context).load() }
    val cloud = remember { CloudStore(context).load() }

    DisposableEffect(Unit) {
        val prefs = PresenceStore.prefs(context)
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        val house = HouseholdStore.listenPrefs(context)
        prefs.registerOnSharedPreferenceChangeListener(l)
        house.registerOnSharedPreferenceChangeListener(l)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(l)
            house.unregisterOnSharedPreferenceChangeListener(l)
        }
    }
    @Suppress("DEPRECATION")
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { battery = isIgnoringBattery(context); tick++ } }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    fun update(new: PresenceConfig) {
        cfg = new
        PresenceStore.saveConfig(context, new)
        PresenceService.sync(context)
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(context, "Bez polohy zadaj súradnice ručne alebo použi čas od – do.", Toast.LENGTH_LONG).show()
        } else {
            lastLocation(context) { loc ->
                if (loc == null) Toast.makeText(context, "Poloha zatiaľ nie je známa. Zapni polohu v telefóne a skús znova, alebo zadaj súradnice ručne.", Toast.LENGTH_LONG).show()
                else update(cfg.copy(lat = (loc.latitude * 100).roundToInt() / 100.0, lon = (loc.longitude * 100).roundToInt() / 100.0))
            }
        }
    }

    val values: (Device) -> Map<String, Any> = { d ->
        (vm.states.value[d.id] as? DeviceState.Online)?.values ?: StateCache.load(context, d.id) ?: emptyMap()
    }
    val kinds = devices.associate { it.id to AutoDevices.kind(it, values(it)) }
    val status = remember(tick) { PresenceStore.status(context) }
    val history = remember(tick) { PresenceStore.history(context) }
    val members = remember(tick) { HouseholdStore.members(context) }

    PageScaffold("Príchod a odchod", onBack) {
        Text(
            "Keď sa telefón pripojí na domácu WiFi, appka zapne vybrané zariadenia. Keď sa od nej odpojí a do pár minút sa " +
                "nevráti, vybrané zariadenia vypne cez Tuya cloud. Funguje aj na pozadí a po reštarte telefónu.",
            style = MaterialTheme.typography.bodyMedium,
        )

        // ------------------------------------------------ zapnutie
        Section("Automatika") {
            SwitchLine("Zapnúť príchod a odchod", cfg.enabled, enabled = cfg.home != null) { on ->
                if (on && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                if (!on) {
                    scope.launch(LogOnError) { PresenceActions.guardClearAll(context) }
                    PresenceStore.saveStatus(context, PresenceStatus())
                }
                PresenceStore.log(context, if (on) "Automatika zapnutá." else "Automatika vypnutá.")
                update(cfg.copy(enabled = on))
            }
            if (cfg.home == null) Note("Najprv nižšie nastav domácu WiFi.")
            if (cfg.enabled) {
                Text("Stav: ${status.state.title}" + if (status.since > 0) " (od ${timeText(status.since)})" else "", fontWeight = FontWeight.Medium)
                if (!PresenceService.isRunning) Note("Služba sa spúšťa… Ak tu tento text ostane, otvor appku znova.")
            }
            // výnimku z optimalizácie batérie pýtať len vtedy, keď automatika naozaj beží
            if (!battery && cfg.enabled) {
                HorizontalDivider()
                Note("Optimalizácia batérie je zapnutá – Android môže automatiku na pozadí uspať alebo jej vypnúť internet, " +
                    "potom sa odchod vyhodnotí neskoro alebo vôbec.", warning = true)
                OutlinedButton(onClick = { openBatterySettings(context) }) { Text("Vypnúť optimalizáciu batérie") }
            }
        }

        // ------------------------------------------------ domáca WiFi
        Section("Domáca WiFi") {
            Text(cfg.home?.let { "Uložená: ${it.title}" } ?: "Zatiaľ nenastavená.")
            Button(
                enabled = busy == null,
                onClick = {
                    val net = wifiNetwork(context)
                    val fp = net?.let { HomeNet.fingerprint(context, it) }
                    if (net == null || fp == null) {
                        netInfo = "Telefón nie je pripojený na WiFi (alebo sieť ešte nemá adresu). Pripoj sa doma na WiFi a skús znova."
                        return@Button
                    }
                    busy = "Overujem, či odpovedajú tvoje zariadenia…"
                    scope.launch(LogOnError) {
                        val answered = HomeNet.verify(context, net)
                        busy = null
                        PresenceStore.saveStatus(context, PresenceStatus(PresenceState.HOME, System.currentTimeMillis()))
                        update(cfg.copy(home = fp))
                        PresenceStore.log(context, "Domáca WiFi nastavená: ${fp.title}.")
                        netInfo = if (answered != null) "Uložené. Odpovedalo zariadenie „${answered.name}“ – sieť je overená."
                        else "Uložené, ale žiadne zariadenie teraz neodpovedalo. Príchod sa vyhodnotí, až keď niektoré zariadenie odpovie – skontroluj IP adresy zariadení."
                    }
                },
            ) { Text(if (cfg.home == null) "Toto je moja domáca WiFi" else "Uložiť aktuálnu WiFi ako domácu") }
            busy?.let { Text(it); LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            netInfo?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Note(
                "Na rozpoznanie domácej siete appka nepotrebuje polohu ani povolenie „poloha na pozadí“. Sieť pozná podľa adresy " +
                    "routera a rozsahu adries a že je naozaj doma, overí tak, že jej odpovie niektoré tvoje zariadenie s vlastným " +
                    "local key (cudzia sieť s rovnakou adresou routera to nedokáže). Názov WiFi (SSID) Android bez polohy appke " +
                    "neprezradí, preto sa zobrazuje len adresa routera.",
            )
        }

        // ------------------------------------------------ domácnosť (vlastná obrazovka: členovia, pridať člena, pripojiť sa)
        if (onHousehold != null) {
            Section("Domácnosť – viac telefónov") {
                Note(
                    if (members.isEmpty()) "Zatiaľ len tento telefón. Ďalšieho člena pridáš cez Domácnosť → Pridať člena."
                    else "V domácnosti: ${HouseholdStore.name(context)} a " + members.joinToString(", ") { it.name } + ".",
                )
                OutlinedButton(onClick = onHousehold) { Text("Domácnosť a členovia") }
            }
        } else {
            HouseholdSection(cfg, status, members, ::update)
        }

        // ------------------------------------------------ príchod
        Section("Pri príchode zapnúť") {
            val candidates = devices.filter { AutoDevices.canArrive(kinds[it.id] ?: AutoKind.OTHER) }
            if (candidates.isEmpty()) Note("Žiadne vhodné zariadenie.")
            candidates.forEach { d ->
                CheckRow(d.name, kindTitle(kinds[d.id] ?: AutoKind.OTHER), d.id in cfg.arriveIds) { on ->
                    update(cfg.copy(arriveIds = if (on) cfg.arriveIds + d.id else cfg.arriveIds - d.id))
                }
            }
            val unknown = devices.filter { kinds[it.id] == AutoKind.UNKNOWN }
            if (unknown.isNotEmpty()) Note("Neponúkajú sa zariadenia, o ktorých appka zatiaľ nevie, čo sú (${unknown.joinToString { it.name }}). " +
                "Otvor ich raz v appke, aby sa načítali ich hodnoty.")
            Note("Zapína sa hlavný vypínač (svetlo sa zapne s posledným jasom a farbou). Fritézy, ohrievače a termostaty sa " +
                "pri príchode nikdy nezapínajú a ani sa tu neponúkajú. Pozor na zásuvky – zapne sa všetko, čo je do nich zapojené.")
            HorizontalDivider()
            SwitchLine("Zapnúť aj keď je už niekto doma", cfg.arriveIfSomeoneHome) { update(cfg.copy(arriveIfSomeoneHome = it)) }
            Note("Predvolene sa pri príchode nič nezapína, ak sa iný telefón domácnosti práve ozval z domu (svetlá si už zapol ten, kto je doma).")
            HorizontalDivider()
            SwitchLine("Len po zotmení", cfg.onlyWhenDark) { update(cfg.copy(onlyWhenDark = it)) }
            if (!cfg.onlyWhenDark) {
                val sun by rememberSunInfo(cfg)
                sun?.let { Note("Dnes západ slnka o ${it.setText ?: "–"} (${it.place.label()}).") }
            }
            if (cfg.onlyWhenDark) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { update(cfg.copy(darkMode = DarkMode.SUN)) }) {
                    RadioButton(selected = cfg.darkMode == DarkMode.SUN, onClick = { update(cfg.copy(darkMode = DarkMode.SUN)) })
                    Text("Podľa západu a východu slnka")
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { update(cfg.copy(darkMode = DarkMode.MANUAL)) }) {
                    RadioButton(selected = cfg.darkMode == DarkMode.MANUAL, onClick = { update(cfg.copy(darkMode = DarkMode.MANUAL)) })
                    Text("Ručne zadaný čas od – do")
                }
                if (cfg.darkMode == DarkMode.SUN) SunSettings(cfg, ::update) {
                    locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                if (cfg.darkMode == DarkMode.MANUAL) ManualDark(cfg, ::update)
            }
        }

        // ------------------------------------------------ odchod
        Section("Pri odchode vypnúť") {
            val departIds = AutoDevices.departIds(cfg, devices, values)
            devices.filter { AutoDevices.canDepart(kinds[it.id] ?: AutoKind.OTHER) }.forEach { d ->
                CheckRow(d.name, kindTitle(kinds[d.id] ?: AutoKind.OTHER), d.id in departIds) { on ->
                    update(cfg.copy(departIds = if (on) departIds + d.id else departIds - d.id))
                }
            }
            Note("Predvolene všetky svetlá a zásuvky.")
            HorizontalDivider()
            Text("Vypnúť po ${cfg.departDelayMin} min bez domácej WiFi")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3, 5, 10, 15, 30).forEach { m ->
                    FilterChip(selected = cfg.departDelayMin == m, onClick = { update(cfg.copy(departDelayMin = m)) }, label = { Text("$m min") })
                }
            }
            Note("Ak sa telefón do tohto času znova pripojí (krátky výpadok WiFi), nič sa nevypne a ani nezapne.")
            if (cloud == null) {
                Note("Tuya cloud nie je nastavený – mimo domu appka zariadenia nedosiahne, takže ich nevypne a len ťa upozorní " +
                    "„Nepodarilo sa zhasnúť“. Nastav cloud, alebo zapni poistku nižšie.", warning = true)
                OutlinedButton(onClick = onCloud) { Text("Nastaviť Tuya cloud") }
            } else {
                Note("Vypína sa cez Tuya cloud (telefón je už mimo WiFi, ide cez mobilné dáta).")
            }
            SwitchLine("Upozornenie po vykonaní („Odišiel si – zhaslo 4 svetlá“)", cfg.notifyDone) { update(cfg.copy(notifyDone = it)) }
        }

        // ------------------------------------------------ poistka
        Section("Poistka bez cloudu (nepovinné)") {
            val guardable = devices.filter { AutoDevices.countdownDp(it, values(it)) != null }
            Note(
                "Kým si doma, appka zapnutým zariadeniam každých ${cfg.guardRefreshMin} min nastaví odpočet " +
                    "${cfg.guardSeconds / 60} min. Keď odídeš, odpočet už nikto neobnoví a zariadenie sa samo vypne – aj bez cloudu " +
                    "a bez internetu. Vypnutým zariadeniam appka odpočet zruší.",
            )
            Note(
                "Pozor: ak telefón doma zaspí, vybije sa alebo sa odpojí od WiFi (aj v noci), svetlá po odpočte zhasnú, hoci si doma. " +
                    "Zariadenia zapnuté inak (vypínač, Tuya appka) appka tiež „poistí“.",
                warning = true,
            )
            if (members.isNotEmpty()) Note("Viac telefónov: poistku zapni na všetkých telefónoch pre tie isté zariadenia. Odpočet obnovuje každý " +
                "telefón, ktorý je doma, takže keď jeden odíde, druhý ho ďalej obnovuje. Ak by ju mal zapnutú len jeden telefón, " +
                "po jeho odchode by svetlá zhasli aj tomu, kto je doma.", warning = true)
            SwitchLine("Zapnúť poistku", cfg.guardEnabled, enabled = guardable.isNotEmpty()) { on ->
                if (!on) scope.launch(LogOnError) { PresenceActions.guardClearAll(context) }
                update(cfg.copy(guardEnabled = on))
            }
            if (guardable.isEmpty()) Note("Žiadne zariadenie nemá odpočet (countdown). Otvor zariadenia raz v appke, aby sa načítali ich hodnoty.")
            if (cfg.guardEnabled) {
                guardable.forEach { d ->
                    CheckRow(d.name, kindTitle(kinds[d.id] ?: AutoKind.OTHER), d.id in cfg.guardIds) { on ->
                        if (!on) scope.launch(LogOnError) {
                            val dp = AutoDevices.countdownDp(d, values(d))?.first
                            val net = wifiNetwork(context)
                            if (dp != null && net != null && PresenceStore.guardSet(context, d.id) > 0) {
                                withContext(Dispatchers.IO) { runCatching { DeviceClient.setValues(net, d, mapOf(dp to 0), "zrušenie poistky") } }
                                PresenceStore.setGuardSet(context, d.id, 0L)
                            }
                        }
                        update(cfg.copy(guardIds = if (on) cfg.guardIds + d.id else cfg.guardIds - d.id))
                    }
                }
                Text("Dĺžka odpočtu")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 15, 20, 30, 60).forEach { m ->
                        FilterChip(selected = cfg.guardSeconds / 60 == m, onClick = { update(cfg.copy(guardMinutes = m)) }, label = { Text("$m min") })
                    }
                }
                Text("Obnovovať každých")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 3, 5, 10).forEach { m ->
                        FilterChip(selected = cfg.guardRefreshMin == m, onClick = { update(cfg.copy(guardRefreshMin = m)) }, label = { Text("$m min") })
                    }
                }
                Note("Odpočet je vždy aspoň o 5 minút dlhší než interval obnovy.")
            }
        }

        // ------------------------------------------------ test
        Section("Vyskúšať") {
            Note("Spustí akciu hneď, bez čakania a bez ohľadu na „len po zotmení“. Stav doma / mimo domu sa nemení.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = busy == null, onClick = {
                    busy = "Zapínam…"
                    scope.launch(LogOnError) { result = PresenceActions.arrive(context, null, manual = true); busy = null }
                }) { Text("Vyskúšať príchod") }
                OutlinedButton(enabled = busy == null, onClick = {
                    busy = "Vypínam cez Tuya cloud…"
                    scope.launch(LogOnError) { result = PresenceActions.depart(context, manual = true); busy = null }
                }) { Text("Vyskúšať odchod") }
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }

        // ------------------------------------------------ história
        Section("Posledné udalosti") {
            if (history.isEmpty()) Note("Zatiaľ nič.")
            history.take(15).forEach { e ->
                Row {
                    Text(timeText(e.time), style = MaterialTheme.typography.bodySmall, modifier = Modifier.widthIn(min = 78.dp))
                    Text(e.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
            if (history.isNotEmpty()) TextButton(onClick = { PresenceStore.clearHistory(context) }) { Text("Vymazať históriu") }
        }
    }
}

@Composable
private fun SunSettings(cfg: PresenceConfig, update: (PresenceConfig) -> Unit, onLocate: () -> Unit) {
    var lat by remember(cfg.lat) { mutableStateOf(cfg.lat?.short() ?: "") }
    var lon by remember(cfg.lon) { mutableStateOf(cfg.lon?.short() ?: "") }
    Note("Východ a západ slnka sa počítajú priamo v telefóne (NOAA) z najpresnejšej známej polohy: ak má appka povolenie " +
        "na polohu, z poslednej známej polohy telefónu (GPS sa kvôli tomu nezapína), inak zo súradníc domu nižšie, inak z miesta počasia.")
    OutlinedButton(onClick = onLocate) { Text("Zistiť polohu z telefónu (raz)") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(lat, { lat = it }, label = { Text("Šírka") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(lon, { lon = it }, label = { Text("Dĺžka") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    val la = lat.replace(',', '.').toDoubleOrNull()
    val lo = lon.replace(',', '.').toDoubleOrNull()
    val valid = la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = valid && (la != cfg.lat || lo != cfg.lon), onClick = { update(cfg.copy(lat = la, lon = lo)) }) { Text("Uložiť súradnice") }
        TextButton(onClick = { update(cfg.copy(lat = 48.15, lon = 17.11)) }) { Text("Bratislava") }
        TextButton(onClick = { update(cfg.copy(lat = 48.72, lon = 21.26)) }) { Text("Košice") }
    }
    SunToday(cfg)
}

@Composable
private fun ManualDark(cfg: PresenceConfig, update: (PresenceConfig) -> Unit) {
    var from by remember(cfg.darkFrom) { mutableStateOf(Darkness.hhmm(cfg.darkFrom)) }
    var to by remember(cfg.darkTo) { mutableStateOf(Darkness.hhmm(cfg.darkTo)) }
    val f = Darkness.parseHhmm(from)
    val t = Darkness.parseHhmm(to)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(from, { from = it }, label = { Text("Tma od") }, singleLine = true, isError = f == null, modifier = Modifier.weight(1f))
        OutlinedTextField(to, { to = it }, label = { Text("do") }, singleLine = true, isError = t == null, modifier = Modifier.weight(1f))
    }
    TextButton(enabled = f != null && t != null && (f != cfg.darkFrom || t != cfg.darkTo), onClick = { update(cfg.copy(darkFrom = f!!, darkTo = t!!)) }) {
        Text("Uložiť čas")
    }
    Note("Napr. 17:00 – 6:30 (cez polnoc). Pri príchode v tomto čase sa svetlá zapnú, inokedy nie.")
}


/** „Dnes západ o 16:12“ – počíta sa pre dnešný dátum, takže sa mení s ročným obdobím. Pre porovnanie aj iné dni. */
@Composable
internal fun SunToday(cfg: PresenceConfig) {
    val now = System.currentTimeMillis()
    val tz = TimeZone.getDefault()
    val info by rememberSunInfo(cfg)
    val sun = info ?: return
    fun pair(t: Long) = SunTimes.day(sun.place, t, tz)
    val rise = sun.sunrise
    val set = sun.sunset
    val fmt = java.text.SimpleDateFormat("d. M.", Locale.forLanguageTag("sk"))
    Text(
        "Dnes (${fmt.format(java.util.Date(now))}) západ o ${set?.let { Darkness.hhmm(it) } ?: "–"}",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        "Východ o ${rise?.let { Darkness.hhmm(it) } ?: "–"}" +
            if (SunTimes.isDark(sun.place, now)) " · teraz je tma" else " · teraz je svetlo",
        style = MaterialTheme.typography.bodyMedium,
    )
    val later = listOf(30, 90).map { d -> now + d * 86_400_000L }
    Note(
        "Čas sa každý deň prepočíta podľa dátumu: " + later.joinToString(", ") { t ->
            "${fmt.format(java.util.Date(t))} západ o ${pair(t).second?.let { Darkness.hhmm(it) } ?: "–"}"
        } + ".",
    )
    Text(
        "Vypočítané ${sun.place.label()} (${sun.place.lat.short()}, ${sun.place.lon.short()}).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun agoText(t: Long, now: Long): String {
    val m = ((now - t) / 60_000).coerceAtLeast(0)
    return when {
        m < 1 -> "práve teraz"
        m < 60 -> "pred $m min"
        m < 24 * 60 -> "pred ${m / 60} h"
        else -> timeText(t)
    }
}

/** Domácnosť: členovia, pridať člena (zdieľanie appky cez QR), pripojiť sa k domácnosti, pozvánka. */
@Composable
internal fun HouseholdScreen(onBack: () -> Unit, onShare: () -> Unit, onJoin: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var cfg by remember { mutableStateOf(PresenceStore.config(context)) }
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
    val status = remember(tick) { PresenceStore.status(context) }
    val members = remember(tick) { HouseholdStore.members(context) }
    fun update(new: PresenceConfig) {
        cfg = new
        PresenceStore.saveConfig(context, new)
        PresenceService.sync(context)
    }
    PageScaffold("Domácnosť", onBack) {
        Section("Pridať člena") {
            Note("Druhý telefón naskenuje QR kód, stiahne si appku priamo z tohto telefónu cez WiFi a dostane len zariadenia, ktoré vyberieš.")
            Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) { Text("Pridať člena – zdieľať appku (QR)") }
            Note("Tento telefón sa chce pridať k inému?")
            OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) { Text("Pripojiť sa k domácnosti – naskenovať QR") }
        }
        HouseholdSection(cfg, status, members, ::update)
    }
}

@Composable
private fun HouseholdSection(cfg: PresenceConfig, status: PresenceStatus, members: List<Member>, update: (PresenceConfig) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(HouseholdStore.name(context)) }
    var showInvite by remember { mutableStateOf(false) }
    var joinText by remember { mutableStateOf("") }
    var joinInfo by remember { mutableStateOf<String?>(null) }
    val now = rememberNow(30_000)
    Section("Domácnosť – viac telefónov") {
        Note("Ak má appku aj ďalší člen domácnosti, telefóny si v domácej WiFi posielajú správu „som doma“ (bez servera a bez " +
            "cloudu, podpísanú kľúčom domácnosti). Keď odíde jeden a druhý je doma, svetlá sa nezhasnú.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, label = { Text("Tvoje meno (aj v upozorneniach)") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(enabled = name.isNotBlank() && name.trim() != HouseholdStore.name(context), onClick = {
                HouseholdStore.setName(context, name)
                Toast.makeText(context, "Uložené", Toast.LENGTH_SHORT).show()
            }) { Text("Uložiť") }
        }
        Text("Kto je doma", fontWeight = FontWeight.Medium)
        Text("• ${HouseholdStore.name(context)} (tento telefón) – " +
            if (!cfg.enabled) "automatika vypnutá" else status.state.title)
        if (members.isEmpty()) Note("Zatiaľ sa neozval žiadny iný telefón. Pridaj ho tlačidlom Pridať člena vyššie.")
        members.sortedByDescending { it.lastSeen }.forEach { m ->
            val home = HouseDecision.isHome(m, now, cfg.memberWindowMs)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "• ${m.name} – " + (if (home) "doma" else "preč alebo telefón spí") + " (naposledy ${agoText(m.lastSeen, now)})",
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { HouseholdStore.remove(context, m.id) }) { Text("Odstrániť") }
            }
        }
        HorizontalDivider()
        Text("Pri odchode nezhasínať, ak sa iný telefón ozval z domu za posledných ${cfg.memberWindowMin} min")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3, 5, 10, 15, 20, 30).forEach { m ->
                FilterChip(selected = cfg.memberWindowMin == m, onClick = { update(cfg.copy(memberWindowMin = m)) }, label = { Text("$m min") })
            }
        }
        Note("Telefón, ktorý leží doma a spí, sa ozýva len približne každých 5–10 minút, preto odporúčam 10 min. Kratší čas = " +
            "rýchlejšie zhasnutie, ale väčšie riziko, že zhasne niekomu, kto je doma.")
        Note("Keď odídete obaja naraz (do ${cfg.memberWindowMin} min od seba), každý telefón si myslí, že ten druhý je ešte doma, a " +
            "svetlá ostanú svietiť. Príde notifikácia s tlačidlom Aj tak zhasnúť.", warning = true)
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showInvite = true }) { Text("Pozvánka textom (len automatika)") }
        }
        Text("Pridať sa do domácnosti", fontWeight = FontWeight.Medium)
        Note("Ručne bez QR: na druhom telefóne vlož pozvánku (DOMOV-POZVANKA:…) alebo naimportuj export zariadení z prvého telefónu (Nastavenia → Import zariadení).")
        OutlinedTextField(joinText, { joinText = it; joinInfo = null }, label = { Text("Pozvánka") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                val clip = context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
                joinText = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString().trim() else ""
            }) { Text("Vložiť zo schránky") }
            Button(enabled = joinText.isNotBlank(), onClick = {
                val key = HouseholdCrypto.parseInvite(joinText)
                joinInfo = when {
                    key == null -> "Toto nie je platná pozvánka. Skopíruj celý text začínajúci DOMOV-POZVANKA:"
                    HouseholdStore.adopt(context, key) -> { joinText = ""; "Hotovo – tento telefón je v domácnosti. Nastav meno telefónu a domácu WiFi a zapni automatiku." }
                    else -> "Tento telefón už v tejto domácnosti je."
                }
            }) { Text("Pridať sa") }
        }
        joinInfo?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
    if (showInvite) InviteDialog(onDismiss = { showInvite = false })
}

/** QR kód z textu (zxing), ako bitmapa. */
internal fun qrBitmap(text: String, size: Int = 640): android.graphics.Bitmap {
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp
}

@Composable
private fun InviteDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val invite = remember { HouseholdCrypto.invite(HouseholdStore.shareKey(context)) }
    val qr = remember(invite) { runCatching { qrBitmap(invite) }.getOrNull() }
    DomovAlert(
        onDismissRequest = onDismiss,
        icon = { Icon(Sym.groups, null) },
        title = { Text("Pozvať do domácnosti") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Note("Pozvánka obsahuje kľúč domácnosti. Pošli ju len členom domácnosti. Na druhom telefóne: Domácnosť → " +
                    "Pridať sa do domácnosti → vlož pozvánku. QR kód naskenuj fotoaparátom druhého telefónu a text skopíruj.")
                qr?.let {
                    androidx.compose.foundation.Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "QR kód pozvánky",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(invite, style = MaterialTheme.typography.bodySmall)
                Note("Zariadenia (IP, local keys) pozvánka neobsahuje – tie prenesieš cez Nastavenia → Export zariadení. Export obsahuje aj kľúč " +
                    "domácnosti, takže stačí len on.")
            }
        },
        confirmButton = {
            DialogPrimaryButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, invite)
                context.startActivity(Intent.createChooser(send, "Poslať pozvánku"))
            }) { Text("Zdieľať") }
        },
        dismissButton = {
            Row {
                DialogTextButton(onClick = onDismiss) { Text("Zavrieť") }
                DialogTextButton(onClick = {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("Domov – pozvánka", invite))
                    Toast.makeText(context, "Skopírované", Toast.LENGTH_SHORT).show()
                }) { Text("Kopírovať") }
            }
        },
    )
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier =
    this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))
