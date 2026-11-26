@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package sk.domov.ovladanie

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

fun weatherIcon(i: WeatherIcon): ImageVector = when (i) {
    WeatherIcon.CLEAR_DAY -> Sym.sunny
    WeatherIcon.CLEAR_NIGHT -> Sym.clearNight
    WeatherIcon.PARTLY_DAY -> Sym.partlyCloudyDay
    WeatherIcon.PARTLY_NIGHT -> Sym.partlyCloudyNight
    WeatherIcon.CLOUDY, WeatherIcon.UNKNOWN -> Sym.cloud
    WeatherIcon.FOG -> Sym.foggy
    WeatherIcon.DRIZZLE -> Sym.rainyLight
    WeatherIcon.RAIN -> Sym.rainy
    WeatherIcon.HEAVY_RAIN -> Sym.rainyHeavy
    WeatherIcon.FREEZING -> Sym.weatherMix
    WeatherIcon.SNOW -> Sym.weatherSnowy
    WeatherIcon.STORM -> Sym.thunderstorm
}

/** Kompaktná karta počasia na hlavnej obrazovke. Ťuknutie = hodiny, podržanie = nastavenie. */
@Composable
fun WeatherCard(weather: WeatherController, onSettings: () -> Unit, moon: MoonInfo? = null) {
    val ui by weather.ui.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    // text „aktualizované pred X min“: obnoviť pri návrate do appky a potom raz za minútu, len kým je appka na obrazovke
    val now = rememberNow(60_000)
    var locError by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            locating = true
            scope.launch(LogOnError) { locError = weather.usePhoneLocation(); locating = false }
        } else {
            locError = "Bez povolenia na polohu – vyber mesto ručne."
        }
    }
    if (!ui.settings.visible) return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    val f = ui.forecast
    if (ui.location == null) {
        DomovCard(padding = 14.dp, spacing = 8.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Sym.partlyCloudyDay, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Počasie", style = MaterialTheme.typography.titleMedium)
                    Text("Vyber miesto a počasie sa ukáže tu.", style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    locError = null
                    if (WeatherController.hasLocationPermission(context)) {
                        locating = true
                        scope.launch(LogOnError) { locError = weather.usePhoneLocation(); locating = false }
                    } else {
                        askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                }, enabled = !locating, modifier = Modifier.weight(1f)) { Text(if (locating) "Zisťujem…" else "Poloha telefónu", maxLines = 1) }
                FilledTonalButton(onClick = onSettings, modifier = Modifier.weight(1f)) { Text("Vybrať mesto", maxLines = 1) }
            }
            locError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            moon?.let { MoonLine(it, compact = true) }
            TextButton(onClick = { weather.setVisible(false) }) { Text("Skryť počasie") }
        }
        return
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).combinedClickable(onClick = { expanded = !expanded }, onLongClick = onSettings),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (f == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ui.loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Sym.cloudOff, null, tint = muted)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ui.location?.name ?: "Počasie", style = MaterialTheme.typography.titleSmall)
                        Text(if (ui.loading) "Načítavam počasie…" else ui.error ?: "Počasie zatiaľ nie je načítané.", style = MaterialTheme.typography.bodySmall,
                            color = if (ui.error != null && !ui.loading) MaterialTheme.colorScheme.error else muted)
                    }
                    if (!ui.loading) TextButton(onClick = { weather.refresh(force = true) }) { Text("Skúsiť znova") }
                }
                moon?.let { MoonLine(it, compact = true) }
                return@Column
            }
            val kind = Wmo.of(f.now.code, f.now.isDay)
            val today = WeatherPolicy.today(f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(weatherIcon(kind.icon), contentDescription = kind.text, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(10.dp))
                Text(WeatherPolicy.temp(f.now.temp), style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Medium))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(kind.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (today != null) Text("Max ${WeatherPolicy.temp(today.max)} · Min ${WeatherPolicy.temp(today.min)}", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Sym.locationOn, null, tint = muted, modifier = Modifier.size(14.dp))
                        Text(ui.location?.name ?: "", style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (ui.loading) CircularProgressIndicator(modifier = Modifier.padding(top = 4.dp).size(14.dp), strokeWidth = 2.dp)
                }
            }
            val extra = listOfNotNull(
                f.now.feels?.let { "Pocitovo ${WeatherPolicy.temp(it)}" },
                f.now.wind?.let { "Vietor ${it.roundToInt()} km/h" },
                today?.precip?.let { "Zrážky $it %" },
            )
            if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
            moon?.let { MoonLine(it, compact = true) }

            val days = WeatherPolicy.nextDays(f)
            if (days.isNotEmpty()) {
                Row(Modifier.fillMaxWidth()) {
                    days.forEach { d ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(WeatherPolicy.dayName(d.date, f.now.time.take(10)), style = MaterialTheme.typography.labelMedium, color = muted)
                            val k = Wmo.of(d.code)
                            Icon(weatherIcon(k.icon), contentDescription = k.text, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                            Text(WeatherPolicy.temp(d.max), style = MaterialTheme.typography.labelLarge)
                            Text(WeatherPolicy.temp(d.min), style = MaterialTheme.typography.labelMedium, color = muted)
                        }
                    }
                }
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    WeatherPolicy.nextHours(f).forEach { h ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(h.time.drop(11).take(5), style = MaterialTheme.typography.labelSmall, color = muted)
                            val k = Wmo.of(h.code, h.isDay)
                            Icon(weatherIcon(k.icon), contentDescription = k.text, modifier = Modifier.size(20.dp))
                            Text(WeatherPolicy.temp(h.temp), style = MaterialTheme.typography.labelLarge)
                            Text(h.precip?.takeIf { it > 0 }?.let { "$it %" } ?: " ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val stale = WeatherPolicy.isStale(ui.fetchedAt, now)
                Text(
                    WeatherPolicy.ageText(ui.fetchedAt, now) + if (stale) " (staré údaje)" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (stale) MaterialTheme.colorScheme.error else muted,
                    modifier = Modifier.weight(1f),
                )
                Text(if (expanded) "menej" else "po hodinách", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            ui.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}


/** Nastavenie počasia: miesto (poloha telefónu alebo mesto), zobrazenie karty. */
@Composable
fun WeatherSettingsScreen(weather: WeatherController, onBack: () -> Unit) {
    val ui by weather.ui.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }

    fun phone() {
        locating = true
        error = null
        scope.launch(LogOnError) { error = weather.usePhoneLocation(); locating = false }
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) phone() else error = "Bez povolenia na polohu – vyber mesto ručne."
    }
    fun search() {
        if (searching) return
        searching = true
        error = null
        scope.launch(LogOnError) {
            weather.search(query).onSuccess { results = it; if (it.isEmpty()) error = "Mesto „${query.trim()}“ sa nenašlo." }.onFailure { error = it.message; results = null }
            searching = false
        }
    }

    SettingsSubPage("Počasie", onBack, subtitle = "Miesto na karte Domov") {
            DomovCard {
                SettingRow(
                    icon = Sym.partlyCloudyDay,
                    title = "Zobraziť počasie na hlavnej obrazovke",
                    trailing = { DomovSwitch(checked = ui.settings.visible, onCheckedChange = { weather.setVisible(it) }) },
                )
                Text(
                    "Miesto: " + when {
                        ui.location == null -> "nevybraté"
                        ui.settings.source == WeatherSource.NONE -> "Domov (poloha zo západu slnka v Automatike)"
                        else -> ui.location!!.name
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Teplota v °C, vietor v km/h.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionLabel("Poloha telefónu")
            DomovCard {
                InfoHint("Appka si raz zistí približnú polohu (na ~1 km). Polohu na pozadí nesleduje.")
                FilledTonalButton(onClick = {
                    if (WeatherController.hasLocationPermission(context)) phone() else askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }, enabled = !locating, modifier = Modifier.fillMaxWidth()) {
                    Icon(Sym.myLocation, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (locating) "Zisťujem polohu…" else "Použiť polohu telefónu")
                }
            }
            SectionLabel("Mesto")
            DomovCard {
                DomovTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Názov mesta alebo obce") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(onClick = { search() }, enabled = !searching && query.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (searching) "Hľadám…" else "Hľadať")
                }
                results?.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { weather.setPlace(p); results = null; query = ""; onBack() }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Sym.locationOn, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(p.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            InfoHint("Údaje o počasí: Open-Meteo.com (CC BY 4.0), bez registrácie. Obnovujú sa najviac raz za 30 minút; bez internetu sa ukáže posledné uložené počasie.")
    }
}
