@file:OptIn(ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.util.Calendar

// ------------------------------------------------------------ pomocné texty

private val DAYS = listOf("ne", "po", "ut", "st", "št", "pi", "so")

/** „do 18:30“, iný deň „do pi 7:00“. */
internal fun heatUntilText(ms: Long, now: Long = System.currentTimeMillis()): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val time = "%d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    val sameDay = c.get(Calendar.YEAR) == today.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) "do $time" else "do ${DAYS[c.get(Calendar.DAY_OF_WEEK) - 1]} $time"
}

internal fun heatZoneModeText(z: EvoZone): String = when (z.mode) {
    EvoZoneMode.FOLLOW -> "podľa programu"
    EvoZoneMode.PERMANENT -> "natrvalo"
    EvoZoneMode.TEMPORARY -> z.until?.let { heatUntilText(it) } ?: "dočasne"
    else -> z.mode
}

private fun ago(ms: Long): String {
    if (ms <= 0) return "zatiaľ nenačítané"
    val min = ((System.currentTimeMillis() - ms) / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "aktualizované teraz"
        min < 60 -> "aktualizované pred $min min"
        else -> "aktualizované pred ${min / 60} h"
    }
}

private fun heating(z: EvoZone) = z.temperature != null && z.setpoint != null && z.setpoint > z.temperature + 0.2

/** Obnovovanie stavu kúrenia, kým je obrazovka viditeľná (samotné volanie dodrží limit 3 min). */
@Composable
internal fun HeatPolling(vm: HomeViewModel) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.heat.poll()
                // poll() sám dodrží limit 3 min; netreba sa budiť každých 30 s
                delay(60_000)
            }
        }
    }
}

// ------------------------------------------------------------ detail: zóna, režim systému, teplá voda

@Composable
/** Prehľad kúrenia; [onBack] = null na karte Kúrenie (bez šípky späť). */
internal fun HeatScreen(vm: HomeViewModel, zoneId: String?, onBack: (() -> Unit)?, onAccount: () -> Unit) {
    val heat by vm.heat.ui.collectAsState()
    HeatPolling(vm)
    val sys = heat.system
    var selectedId by rememberSaveable { mutableStateOf(zoneId) }
    val zone = sys?.zones?.find { it.id == selectedId } ?: sys?.zones?.firstOrNull()
    var confirmOff by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kúrenie") },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Sym.arrowBack, contentDescription = "Späť") } },
                actions = {
                    IconButton(onClick = { vm.heat.poll(force = true) }, enabled = !heat.loading) { Icon(Sym.refresh, contentDescription = "Obnoviť") }
                    IconButton(onClick = onAccount) { Icon(Sym.settings, contentDescription = "Účet Honeywell") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (heat.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            heat.error?.let { NoticeBanner(message = it, onDismiss = { vm.heat.clearError() }) }
            if (sys == null) {
                DomovCard {
                    Text(if (heat.loading) "Načítavam stav z Honeywell cloudu…" else "Stav kúrenia sa zatiaľ nepodarilo načítať.")
                    OutlinedButton(onClick = { vm.heat.poll(force = true) }, enabled = !heat.loading) { Text("Skúsiť znova") }
                }
                return@Column
            }
            if (sys.zones.size > 1) {
                ChipRow {
                    sys.zones.forEach { z -> DomovChip(z.name, selected = z.id == zone?.id) { selectedId = z.id } }
                }
            }
            if (zone != null) ZoneControl(zone, busy = zone.id in heat.pending, onSet = { v, d -> vm.heat.setZone(zone, v, d) }, onFollow = { vm.heat.followSchedule(zone) })

            SectionLabel("Režim systému")
            DomovCard(padding = 14.dp) {
                val modes = EvoMode.offered.filter { it in sys.allowedModes }
                val idx = modes.indexOf(sys.mode)
                Segmented(modes.map { EvoMode.label(it) to null }, selected = idx, enabled = "mode" !in heat.pending) { i ->
                    val m = modes[i]
                    if (m == sys.mode) return@Segmented
                    if (m == EvoMode.OFF) confirmOff = true else vm.heat.setMode(m)
                }
                Text(
                    when {
                        "mode" in heat.pending -> "Mení sa režim…"
                        idx < 0 -> "Teraz: ${EvoMode.label(sys.mode)}" + (sys.modeUntil?.let { " " + heatUntilText(it) } ?: "")
                        else -> modeHint(sys.mode)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            sys.dhw?.let { d ->
                SectionLabel("Teplá voda")
                DomovCard(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconCircle(Sym.waterDrop, on = d.state == "On", size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(evoTemp(d.temperature), style = MaterialTheme.typography.titleMedium)
                            Text(
                                (if (d.state == "On") "Ohrieva sa" else "Vypnutá") + " · " + (if (d.mode == EvoZoneMode.FOLLOW) "podľa programu" else "ručne"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    val sel = when {
                        d.mode == EvoZoneMode.FOLLOW -> 2
                        d.state == "On" -> 0
                        else -> 1
                    }
                    Segmented(listOf("Zapnúť" to null, "Vypnúť" to null, "Program" to null), selected = sel, enabled = "dhw" !in heat.pending) { i ->
                        if (i != sel) vm.heat.setDhw(if (i == 2) null else i == 0)
                    }
                }
            }

            Text(
                "${ago(heat.updatedAt)} · cez Honeywell cloud, stav sa obnovuje najviac raz za 3 min",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            icon = { Icon(Sym.powerSettingsNew, null) },
            title = { Text("Vypnúť kúrenie?") },
            text = { Text("Celý systém evohome prestane kúriť (ochrana proti zamrznutiu ostáva). Zapneš ho späť režimom Auto.") },
            confirmButton = { TextButton(onClick = { confirmOff = false; vm.heat.setMode(EvoMode.OFF) }) { Text("Vypnúť") } },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Zrušiť") } },
        )
    }
}

private fun modeHint(mode: String) = when (mode) {
    EvoMode.AUTO -> "Všetky zóny kúria podľa programu."
    EvoMode.ECO -> "Program znížený o 3 °C."
    EvoMode.AWAY -> "Útlmová teplota, kým nie si doma."
    EvoMode.OFF -> "Kúrenie je vypnuté (len ochrana proti zamrznutiu)."
    else -> ""
}

@Composable
private fun ZoneControl(zone: EvoZone, busy: Boolean, onSet: (Double, HeatDuration) -> Unit, onFollow: () -> Unit) {
    var target by remember(zone.id, zone.setpoint) { mutableDoubleStateOf(zone.setpoint ?: 20.0) }
    var duration by rememberSaveable(zone.id) { mutableStateOf(HeatDuration.PERMANENT) }
    val c = Domov.colors
    DomovCard(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(zone.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Teraz ${evoTemp(zone.temperature)} · ${heatZoneModeText(zone)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (zone.faults.isNotEmpty()) {
                    Text("Porucha: " + zone.faults.joinToString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            IconCircle(if (heating(zone)) Sym.heat else Sym.thermostat, on = heating(zone), size = 40.dp)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            FilledTonalIconButton(onClick = { target = evoClamp(target - zone.step, zone) }, enabled = !busy && target > zone.min, modifier = Modifier.size(56.dp)) {
                Icon(Sym.remove, contentDescription = "Znížiť o ${zone.step}")
            }
            Box(
                modifier = Modifier.padding(horizontal = 20.dp).size(140.dp).clip(CircleShape).background(c.tileOn),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(evoTemp(target).removeSuffix(" °C"), style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, fontWeight = FontWeight.Medium), color = c.onTileOn)
                    Text("°C cieľ", style = MaterialTheme.typography.labelMedium, color = c.onTileOnMuted)
                }
            }
            FilledTonalIconButton(onClick = { target = evoClamp(target + zone.step, zone) }, enabled = !busy && target < zone.max, modifier = Modifier.size(56.dp)) {
                Icon(Sym.add, contentDescription = "Zvýšiť o ${zone.step}")
            }
        }
        Text("Ako dlho", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow {
            HeatDuration.entries.forEach { d -> DomovChip(d.label, selected = duration == d) { duration = d } }
        }
        Button(onClick = { onSet(target, duration) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Nastavuje sa…" else "Nastaviť ${evoTemp(target)}" + if (duration == HeatDuration.PERMANENT) " natrvalo" else if (duration == HeatDuration.NEXT_CHANGE) " do zmeny" else " na ${duration.label}")
        }
        OutlinedButton(onClick = onFollow, enabled = !busy && zone.mode != EvoZoneMode.FOLLOW, modifier = Modifier.fillMaxWidth()) {
            Icon(Sym.schedule, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Podľa programu")
        }
    }
}

// ------------------------------------------------------------ prihlásenie

@Composable
internal fun HeatLoginScreen(vm: HomeViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val heat by vm.heat.ui.collectAsState()
    var email by rememberSaveable { mutableStateOf(heat.email) }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var wasLoggingIn by remember { mutableStateOf(false) }
    LaunchedEffect(heat.loggingIn, heat.configured, heat.loginError) {
        if (wasLoggingIn && !heat.loggingIn && heat.configured && heat.loginError == null) onDone()
        wasLoggingIn = heat.loggingIn
    }

    PageScaffold("Kúrenie Honeywell", onBack) {
        DomovCard {
            Text("Honeywell evohome (nepovinné)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Domov ukáže teplotu v každej zóne a nastaví kúrenie cez ten istý cloud ako appka Honeywell Home (brána RFG100).\n\n" +
                    "1. Zadaj e-mail a heslo, ktorými sa prihlasuješ do appky Honeywell Home.\n" +
                    "2. Údaje sa uložia zašifrované len v tomto telefóne a nikdy nie sú v exporte.\n" +
                    "3. Kúrenie ide cez internet (funguje aj mimo domu). Stav sa obnovuje najviac raz za 3 minúty – Honeywell pri častých dotazoch dočasne blokuje.\n\n" +
                    "Kúrenie sa nikdy nemení automaticky – príchod a odchod, Všetko vypnúť ani Noc naň nesiahajú.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (heat.configured) {
            DomovCard {
                Text("Prihlásený ako ${heat.email}", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth()) { Text("Odhlásiť a zabudnúť heslo") }
            }
            Text("Prihlásiť iný účet:", style = MaterialTheme.typography.titleSmall)
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; vm.heat.clearLoginError() },
            label = { Text("E-mail (Honeywell Home)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; vm.heat.clearLoginError() },
            label = { Text("Heslo") },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Skryť" else "Zobraziť") } },
            modifier = Modifier.fillMaxWidth(),
        )
        heat.loginError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (heat.loggingIn) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { vm.heat.login(email, password) },
            enabled = !heat.loggingIn && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (heat.loggingIn) "Prihlasujem…" else "Prihlásiť") }
        Text(
            "Ak si zabudol heslo, obnov ho v appke Honeywell Home. Po viacerých neúspešných pokusoch Honeywell prihlásenie na chvíľu zablokuje.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Odhlásiť Honeywell?") },
            text = { Text("Zmaže sa uložený e-mail, heslo a stav kúrenia z tohto telefónu. Kúrenie samotné sa nezmení.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; vm.heat.logout(); onBack() }) { Text("Odhlásiť") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Zrušiť") } },
        )
    }
}
