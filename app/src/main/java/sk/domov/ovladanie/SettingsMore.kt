@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package sk.domov.ovladanie

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDate

/** Kalendár, nákup a ich synchronizácia medzi telefónmi domácnosti. */
@Composable
internal fun SettingsCalendarPage(onBack: () -> Unit, onPage: (String) -> Unit) {
    val context = LocalContext.current
    var eventsTime by remember { mutableIntStateOf(EventsStore.time(context)) }
    var pickEventsTime by remember { mutableStateOf(false) }
    val shopItems = remember { ShoppingStore.items(context) }
    val events = remember { EventsStore.visible(context) }
    var syncText by remember { mutableStateOf(HouseSyncEngine.statusText(context)) }
    val syncActive = remember { HouseSyncEngine.active(context) }
    val soon = Events.groups(events, LocalDate.now()).soon.size
    DisposableEffect(Unit) {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            syncText = HouseSyncEngine.statusText(context)
        }
        HouseSyncEngine.listen(context, l)
        onDispose { HouseSyncEngine.unlisten(context, l) }
    }

    SettingsSubPage("Kalendár a nákup", onBack, subtitle = "Spoločné medzi telefónmi domácnosti") {
        SectionLabel("Kalendár")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(
                Sym.calendarToday, "Kalendár",
                caption = if (soon > 0) "$soon čoskoro · otvorí mesačný a týždenný prehľad" else "Udalosti, narodeniny a pripomienky",
                onClick = { onPage("events") },
            )
            SettingRow(
                Sym.schedule, "Denné upozornenie",
                caption = "Ráno pripomenie dnešné a blízke udalosti (okrem časovaných pripomienok v kalendári)",
                value = clockMinutes(eventsTime),
                onClick = { pickEventsTime = true },
            )
        }

        SectionLabel("Nákup")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(
                CartIcon, "Nákupný zoznam",
                caption = ShoppingList.rowText(shopItems) + " · otvorí zoznam",
                onClick = { onPage("shopping") },
            )
        }

        SectionLabel("Jedálniček")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(
                Sym.skillet, "Jedálniček",
                caption = "Raňajky, obed a večera, zásoby a doplnenie nákupu",
                onClick = { onPage("meals") },
            )
        }

        SectionLabel("Synchronizácia")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(
                Sym.sync, "Medzi telefónmi domácnosti",
                caption = when {
                    syncActive && syncText.isNotBlank() -> syncText
                    syncActive -> "Kalendár a nákup sa zdieľajú zašifrovane, bez účtu"
                    else -> "Zapne sa samo, keď je v domácnosti viac telefónov"
                } + " · členov spravuješ v Nastavenia › Domácnosť a synchronizácia",
            )
        }
        Hint("Zmeny kalendára, nákupu a jedálnička sa medzi členmi domácnosti posielajú zašifrované (bez účtu). Stav „som doma“ ide priamo po domácej WiFi. Členov spravuješ v Nastavenia › Domácnosť a synchronizácia.")
    }

    if (pickEventsTime) {
        WheelTimeDialog(
            title = "Denné upozornenie",
            initialMinutes = eventsTime,
            preview = { m -> "Upozornenie príde ráno o %d:%02d".format(m / 60, m % 60) },
            onConfirm = { m ->
                eventsTime = m
                EventsStore.setTime(context, m)
                pickEventsTime = false
            },
            onDismiss = { pickEventsTime = false },
        )
    }
}

/** Krátke info o aplikácii – pred 1.48 bol riadok bez ťuknutia. */
@Composable
internal fun SettingsAboutPage(onBack: () -> Unit, onPage: (String) -> Unit) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    SettingsSubPage("O aplikácii", onBack, subtitle = "Domov $version") {
        DomovCard(padding = 16.dp, spacing = 10.dp) {
            Text("Domov", style = MaterialTheme.typography.titleMedium)
            Text("Verzia $version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Ovládanie Tuya zariadení priamo cez domácu WiFi, bez nutnosti Tuya appky. Spoločný kalendár, nákup a automatika príchodu a odchodu pre domácnosť.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Domácnosť: meno, kto je doma, okno „niekto je doma“, pridanie člena a synchronizácia.
 * Nahrádza starú obrazovku aj medzistupeň „Domácnosť a členovia / QR / pripojiť sa“.
 */
@Composable
internal fun HouseholdSettingsScreen(onBack: () -> Unit, onShare: () -> Unit, onJoin: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        val prefs = PresenceStore.prefs(context)
        val house = HouseholdStore.listenPrefs(context)
        prefs.registerOnSharedPreferenceChangeListener(l)
        house.registerOnSharedPreferenceChangeListener(l)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(l)
            house.unregisterOnSharedPreferenceChangeListener(l)
        }
    }
    var cfg by remember(tick) { mutableStateOf(PresenceStore.config(context)) }
    val status = remember(tick) { PresenceStore.status(context) }
    val members = remember(tick) { HouseholdStore.members(context) }
    var name by remember(tick) { mutableStateOf(HouseholdStore.name(context)) }
    var showInvite by remember { mutableStateOf(false) }
    var joinText by remember { mutableStateOf("") }
    var joinInfo by remember { mutableStateOf<String?>(null) }
    val now = rememberNow(30_000)
    var syncText by remember { mutableStateOf(HouseSyncEngine.statusText(context)) }
    val syncActive = remember(tick) { HouseSyncEngine.active(context) }
    DisposableEffect(Unit) {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            syncText = HouseSyncEngine.statusText(context)
        }
        HouseSyncEngine.listen(context, l)
        onDispose { HouseSyncEngine.unlisten(context, l) }
    }

    fun update(new: PresenceConfig) {
        cfg = new
        PresenceStore.saveConfig(context, new)
        PresenceService.sync(context)
        tick++
    }

    SettingsSubPage("Domácnosť a synchronizácia", onBack, subtitle = "Meno, členovia a zdieľanie") {
        val selfName = HouseholdStore.name(context)
        LaunchedEffect(name) {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || trimmed == HouseholdStore.name(context)) return@LaunchedEffect
            delay(500)
            if (name.trim() == trimmed) {
                HouseholdStore.setName(context, trimmed)
            }
        }
        val selfStatus = when {
            !cfg.enabled -> "Automatika vypnutá"
            status.state == PresenceState.HOME -> "Doma"
            status.state == PresenceState.AWAY -> "Mimo domu"
            status.state == PresenceState.LEAVING -> "Odchádza"
            else -> "Neznáme"
        }

        SectionLabel("Tento telefón")
        DomovCard(padding = 14.dp, spacing = 8.dp) {
            DomovTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Tvoje meno") },
                supportingText = { Text("Uloží sa samo, uvidíš ho aj v upozorneniach") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionLabel("Kto je doma")
        DomovCard(padding = 14.dp, spacing = 4.dp) {
            PersonRow(
                name = selfName,
                status = selfStatus,
                statusGood = cfg.enabled && status.state == PresenceState.HOME,
                self = true,
            )
            if (members.isEmpty()) {
                Text(
                    "Zatiaľ žiadny ďalší telefón.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            members.sortedByDescending { it.lastSeen }.forEach { m ->
                val home = HouseDecision.isHome(m, now, cfg.memberWindowMs)
                PersonRow(
                    name = m.name,
                    caption = "Naposledy ${agoText(m.lastSeen, now)}",
                    status = if (home) "Doma" else "Mimo domu",
                    statusGood = home,
                    onRemove = { HouseholdStore.remove(context, m.id); tick++ },
                )
            }
        }
        InfoHint("Telefóny si v domácej WiFi posielajú správu „som doma“ (bez cloudu, podpísanú kľúčom domácnosti). Keď odíde jeden a druhý je doma, svetlá sa nezhasnú.")

        SectionLabel("Okno prítomnosti")
        DomovCard(padding = 14.dp, spacing = 10.dp) {
            Text(
                "Počítať, že je niekto doma, ešte ${cfg.memberWindowMin} min po poslednej správe",
                style = MaterialTheme.typography.bodyMedium,
            )
            ChipRow {
                listOf(3, 5, 10, 15, 20, 30).forEach { m ->
                    DomovChip(label = "$m min", selected = cfg.memberWindowMin == m, onClick = { update(cfg.copy(memberWindowMin = m)) })
                }
            }
        }
        InfoHint("Telefón, ktorý leží doma a spí, sa ozýva približne každých 5–10 minút. Odporúčam 10 min.")
        NoticeCard(
            "Keď odíde naraz viac členov do ${cfg.memberWindowMin} min, svetlá môžu ostať svietiť. Príde notifikácia s tlačidlom Aj tak zhasnúť.",
            NoticeKind.Warning,
        )

        SectionLabel("Pridať člena")
        DomovCard(padding = 10.dp, spacing = 0.dp) {
            SettingRow(Sym.qrCode, "Zdieľať appku (QR)", caption = "Druhý telefón si stiahne appku a vybrané zariadenia", onClick = onShare)
            SettingRow(Sym.personAdd, "Pripojiť sa k domácnosti", caption = "Naskenovať QR kód z iného telefónu", onClick = onJoin)
            SettingRow(Sym.groups, "Pozvánka textom", caption = "Len kľúč domácnosti, bez zariadení", onClick = { showInvite = true })
        }

        SectionLabel("Pozvánka textom")
        DomovCard(padding = 14.dp, spacing = 10.dp) {
            DomovTextField(
                value = joinText,
                onValueChange = { joinText = it; joinInfo = null },
                singleLine = true,
                label = { Text("DOMOV-POZVANKA:…") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val clip = context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
                    joinText = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString().trim() else ""
                }) { Text("Vložiť zo schránky") }
                FilledTonalButton(
                    enabled = joinText.isNotBlank(),
                    onClick = {
                        val key = HouseholdCrypto.parseInvite(joinText)
                        joinInfo = when {
                            key == null -> "Toto nie je platná pozvánka. Skopíruj celý text začínajúci DOMOV-POZVANKA:"
                            HouseholdStore.adopt(context, key) -> { joinText = ""; tick++; "Hotovo – tento telefón je v domácnosti. Nastav meno a domácu WiFi a zapni automatiku." }
                            else -> "Tento telefón už v tejto domácnosti je."
                        }
                    },
                ) { Text("Pridať sa") }
            }
            joinInfo?.let { msg ->
                NoticeCard(msg, if (msg.startsWith("Hotovo")) NoticeKind.Good else NoticeKind.Warning)
            }
        }

        SectionLabel("Synchronizácia kalendára a nákupu")
        DomovCard(padding = 14.dp, spacing = 8.dp) {
            Text(
                when {
                    syncActive && syncText.isNotBlank() -> syncText
                    syncActive -> "Kalendár a nákup sa medzi telefónmi zdieľajú zašifrovane, bez účtu."
                    else -> "Keď bude v domácnosti viac telefónov, kalendár a nákup sa začnú synchronizovať samy."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (syncActive) {
                TextButton(onClick = {
                    HouseSyncEngine.request(context)
                    syncText = HouseSyncEngine.statusText(context)
                }) { Text("Synchronizovať teraz") }
            }
        }
        InfoHint("Stav „som doma“ ide po domácej WiFi. Kalendár a nákup idú zašifrované cez sieť, kľúč ostáva v telefónoch.")
    }

    if (showInvite) InviteDialog(onDismiss = { showInvite = false })
}


@Composable
private fun Hint(text: String, warning: Boolean = false) {
    if (warning) NoticeCard(text, NoticeKind.Warning) else InfoHint(text)
}
