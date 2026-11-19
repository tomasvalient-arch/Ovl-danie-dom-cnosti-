@file:OptIn(ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

private fun notificationsOn(c: Context): Boolean = NotificationManagerCompat.from(c).areNotificationsEnabled()

private fun clockText(minutes: Int): String = String.format(java.util.Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60)

/** Pilulka s odpočtom: „Dnes“ je zvýraznená, ostatné („Zajtra“, „4 dni“) sú tlmené. */
@Composable
private fun CountdownPill(days: Int) {
    val scheme = MaterialTheme.colorScheme
    val today = days <= 0
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (today) scheme.primary else scheme.surfaceContainerHighest,
        contentColor = if (today) scheme.onPrimary else scheme.onSurface,
    ) {
        Text(
            Events.pillText(days),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 62.dp).padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** Jedna pripomienka v okne upozornení: odpočet, názov a pri narodeninách vek. */
@Composable
private fun EventSoonLine(e: EventItem, today: LocalDate) {
    val scheme = MaterialTheme.colorScheme
    val days = Events.daysUntil(e, today) ?: 0
    val age = Events.ageCaption(e, today)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CountdownPill(days)
        Spacer(Modifier.width(12.dp))
        Text(
            Events.title(e),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (age != null) {
            Text(age, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/**
 * „Udalosti“ na Domove. Kým sa nič nechystá, je to tenký riadok; inak karta, v ktorej je pod sebou každá udalosť
 * a meniny, ktorých okno upozornení už začalo (napr. týždeň vopred) – všetky, bez zbaľovania. Po dni udalosti riadok zmizne.
 */
@Composable
internal fun EventsRow(events: List<EventItem>, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val today = LocalDate.now()
    val soon = Events.groups(events, today).soon
    val slim = soon.isEmpty()
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (slim) 26.dp else 24.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.surfaceVariant),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().height(if (slim) 52.dp else 50.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Sym.calendarToday, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text("Udalosti", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (slim) Events.slimText(events) else "${soon.size} čoskoro",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                Icon(Sym.chevronRight, contentDescription = null, tint = scheme.outline, modifier = Modifier.padding(start = 4.dp).size(20.dp))
            }
            soon.forEach { e ->
                HorizontalDivider(color = scheme.surfaceVariant)
                EventSoonLine(e, today)
            }
        }
    }
}

/** Zoznam udalostí a narodenín (Čoskoro / Neskôr / Prešlo); ťuknutím na riadok sa upravia, + hore pridá novú. */
@Composable
internal fun EventsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var events by remember { mutableStateOf(EventsStore.events(context)) }
    var timeMin by remember { mutableIntStateOf(EventsStore.time(context)) }
    var editing by remember { mutableStateOf<EventItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(notificationsOn(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = notificationsOn(context) }
    val today = LocalDate.now()
    val groups = Events.groups(events, today)

    fun saved(e: EventItem) {
        events = EventsStore.save(context, e)
        adding = false
        editing = null
        // o povolenie upozornení sa appka pýta po uložení prvej udalosti
        if (!allowed && Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // meniny sú odvodená položka: upravuje sa uložený záznam osoby
    fun openEvent(item: EventItem) {
        editing = events.firstOrNull { it.id == Events.baseId(item.id) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Udalosti", style = MaterialTheme.typography.titleLarge)
                        Text(Events.subtitle(events, today), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Sym.arrowBack, contentDescription = "Späť") } },
                actions = { IconButton(onClick = { adding = true }) { Icon(Sym.add, contentDescription = "Pridať udalosť") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!allowed) {
                DomovCard {
                    Text("Upozornenia sú v telefóne vypnuté, udalosti ťa preto neupozornia.", style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Zapnúť upozornenia") }
                }
            }
            if (events.isEmpty()) {
                DomovCard {
                    Text(
                        "Zatiaľ nič. Pridaj narodeniny (aj s meninami) alebo udalosť a appka ťa upozorní vopred, napríklad týždeň a v ten deň.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Pridať") }
                }
            } else {
                EventGroup("Čoskoro", "ukazuje sa na Domove", groups.soon, 0, today) { openEvent(it) }
                EventGroup("Neskôr", "zatiaľ bez pripomienky", groups.later, 1, today) { openEvent(it) }
                EventGroup("Prešlo", "jednorazové", groups.past, 2, today) { openEvent(it) }
            }
            DomovCard(padding = 10.dp, spacing = 0.dp) {
                SettingRow(
                    Sym.schedule, "Čas upozornení",
                    caption = "Kedy ráno ti upozornenie príde",
                    value = clockText(timeMin),
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, h, m ->
                                timeMin = h * 60 + m
                                EventsStore.setTime(context, h * 60 + m)
                            },
                            timeMin / 60, timeMin % 60, true,
                        ).show()
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (adding) {
        EventDialog(initial = null, onSave = { saved(it) }, onDelete = null, onDismiss = { adding = false })
    }
    editing?.let { e ->
        EventDialog(
            initial = e,
            onSave = { saved(it) },
            onDelete = {
                events = EventsStore.delete(context, e.id)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** Skupina v zozname: nadpis a karta s riadkami; prázdna skupina sa nezobrazí. [mode]: 0 čoskoro, 1 neskôr, 2 prešlo. */
@Composable
private fun EventGroup(title: String, hint: String, items: List<EventItem>, mode: Int, today: LocalDate, onOpen: (EventItem) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    if (items.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = scheme.surfaceContainerLow,
                border = BorderStroke(1.dp, scheme.surfaceVariant),
            ) {
                Column {
                    items.forEachIndexed { index, e ->
                        if (index > 0) HorizontalDivider(color = scheme.surfaceVariant)
                        EventListRow(e = e, today = today, mode = mode, onClick = { onOpen(e) })
                    }
                }
            }
        }
    }
}

/** Riadok zoznamu: vľavo odpočet (čoskoro), dátum (neskôr) alebo „Prešlo“, vpravo názov, dátum a kedy upozorní. */
@Composable
private fun EventListRow(e: EventItem, today: LocalDate, mode: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val occ = Events.occurrence(e, today)
    val days = Events.daysUntil(e, today)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (mode == 0 && days != null) {
                val (big, small) = Events.countdown(days)
                Text(big, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = scheme.primary)
                if (small.isNotEmpty()) Text(small, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            } else if (mode == 1 && occ != null) {
                Text(Events.monthShort(occ).uppercase(), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Text("${occ.dayOfMonth}.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = scheme.onSurfaceVariant)
            } else {
                Text("Prešlo", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                Events.title(e),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (mode == 2) scheme.onSurfaceVariant else scheme.onSurface,
            )
            Text(Events.dateLine(e, today), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Text(Events.offsetsText(e.offsets), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

/**
 * Pridanie a úprava: druh, názov, dátum (na bubnoch), meniny pri narodeninách, opakovanie a kedy vopred upozorniť.
 * Dátum sa skladá zo dňa a mesiaca ([month], [day]) a roku, ktorý má význam len keď sa používa: rok jednorazovej udalosti
 * ([eventYear]) alebo rok narodenia ([birthYear], len keď je zaškrtnuté „Poznám rok narodenia“). Appka nikdy nevymyslí
 * rok narodenia za teba: kým ho nevyberieš, uložiť sa nedá (a zrušený výber hneď po zaškrtnutí zaškrtnutie vráti).
 * Meniny ([meninyOn]) sú druhý dátum tej istej osoby s rovnakými upozorneniami; ak je meno v kalendári mien na jednom dni,
 * dátum sa predvyplní, inak sa otvoria bubny. Pod dátumami je vidno, čo sa naozaj stane (ktoré upozornenia prídu a ktoré už prešli).
 */
@Composable
private fun EventDialog(initial: EventItem?, onSave: (EventItem) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val today = remember { LocalDate.now() }
    val timeOfDay = remember { EventsStore.timeOfDay(context) }
    var birthday by remember { mutableStateOf(initial?.birthday ?: false) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var month by remember { mutableIntStateOf(initial?.month ?: today.monthValue) }
    var day by remember { mutableIntStateOf(initial?.day ?: today.dayOfMonth) }
    var eventYear by remember {
        mutableIntStateOf(if (initial != null && !initial.birthday && !initial.yearly && initial.year > 0) initial.year else today.year)
    }
    var birthYear by remember { mutableStateOf(initial?.takeIf { it.birthday && it.year > 0 }?.year) }
    var knowsYear by remember { mutableStateOf(initial != null && initial.birthday && initial.year > 0) }
    var yearly by remember { mutableStateOf(initial?.yearly ?: false) }
    var meninyOn by remember { mutableStateOf(initial != null && Events.hasMeniny(initial)) }
    var meninyMonth by remember { mutableIntStateOf(if (initial != null && Events.hasMeniny(initial)) initial.meninyMonth else 0) }
    var meninyDay by remember { mutableIntStateOf(if (initial != null && Events.hasMeniny(initial)) initial.meninyDay else 0) }
    var offsets by remember { mutableStateOf(initial?.offsets?.filter { it in Events.PRESETS }?.toSet() ?: setOf(7, 0)) }
    var custom by remember { mutableStateOf(initial?.offsets?.firstOrNull { it !in Events.PRESETS }?.toString() ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    // ktorý výber dátumu je otvorený: 0 žiadny, 1 hlavný dátum, 2 meniny
    var picking by remember { mutableIntStateOf(0) }

    val oneTime = !birthday && !yearly
    val birthWithYear = birthday && knowsYear
    val needsBirthYear = birthWithYear && birthYear == null
    val needsMeninyDate = birthday && meninyOn && meninyMonth == 0

    fun build(): EventItem {
        val extra = custom.toIntOrNull()?.takeIf { it in 0..Events.MAX_OFFSET }
        val withMeniny = birthday && meninyOn && meninyMonth in 1..12 && meninyDay in 1..31
        return EventItem(
            id = initial?.id ?: UUID.randomUUID().toString(),
            name = Events.clean(name),
            birthday = birthday,
            year = when {
                birthday -> if (knowsYear) (birthYear ?: 0) else 0
                yearly -> 0
                else -> eventYear
            },
            month = month,
            day = day,
            yearly = birthday || yearly,
            offsets = (offsets + listOfNotNull(extra)).distinct().sortedDescending(),
            meninyMonth = if (withMeniny) meninyMonth else 0,
            meninyDay = if (withMeniny) meninyDay else 0,
        )
    }

    /** Udalosť s dátumom, ktorý sa práve vyberá na bubnoch (pre živý náhľad pod bubnami). */
    fun candidate(d: LocalDate): EventItem =
        build().copy(month = d.monthValue, day = d.dayOfMonth, year = if (birthWithYear || oneTime) d.year else 0)

    fun toggleKnowsYear(on: Boolean) {
        knowsYear = on
        // rok sa vyberá hneď, aby sa nikdy neuložil vymyslený
        if (on && birthYear == null) picking = 1
    }

    fun toggleMeniny(on: Boolean) {
        meninyOn = on
        if (on && meninyMonth == 0) {
            val hit = Meniny.dniPreMeno(name).singleOrNull()
            if (hit != null) {
                meninyMonth = hit.first
                meninyDay = hit.second
            } else {
                picking = 2
            }
        }
    }

    fun cancelPick() {
        // zrušený výber hneď po zaškrtnutí vráti zaškrtnutie, aby nezostal prázdny údaj
        if (picking == 1 && birthWithYear && birthYear == null) knowsYear = false
        if (picking == 2 && meninyMonth == 0) meninyOn = false
        picking = 0
    }

    val built = build()
    val shown = Events.safeDate(if (birthWithYear) (birthYear ?: today.year) else if (oneTime) eventYear else DatePick.LEAP_YEAR, month, day)
    val pastOneTime = oneTime && shown.isBefore(today)
    val dateTitle = when {
        birthWithYear -> "Dátum narodenia"
        birthday -> "Narodeniny (deň a mesiac)"
        oneTime -> "Dátum"
        else -> "Dátum (deň a mesiac)"
    }
    val dateValue = when {
        needsBirthYear -> "Vyber rok"
        birthWithYear || oneTime -> Events.dateText(shown, year = true)
        else -> Events.dateText(shown)
    }
    val canSave = name.isNotBlank() && !needsBirthYear && !needsMeninyDate && !pastOneTime

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nová udalosť" else "Upraviť udalosť") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChipRow {
                    DomovChip("Udalosť", selected = !birthday) { birthday = false }
                    DomovChip("Narodeniny", selected = birthday) { birthday = true }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(Events.MAX_NAME) },
                    label = { Text(if (birthday) "Meno" else "Názov") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (birthday) {
                    CheckRow(title = "Poznám rok narodenia", caption = "Upozornenie potom napíše, koľko rokov bude mať", checked = knowsYear) { toggleKnowsYear(it) }
                } else {
                    CheckRow(title = "Opakovať každý rok", checked = yearly) { yearly = it }
                }
                SettingRow(Sym.calendarToday, dateTitle, value = dateValue, onClick = { picking = 1 })
                if (birthday) {
                    CheckRow(title = "Pridať aj meniny", caption = "Upozornenie príde aj v deň menín", checked = meninyOn) { toggleMeniny(it) }
                    if (meninyOn && meninyMonth > 0) {
                        SettingRow(
                            Sym.calendarToday, "Meniny",
                            value = Events.dateText(Events.safeDate(DatePick.LEAP_YEAR, meninyMonth, meninyDay)),
                            onClick = { picking = 2 },
                        )
                    }
                }
                when {
                    needsBirthYear -> Text("Ťukni na dátum a vyber rok narodenia.", style = MaterialTheme.typography.bodySmall, color = scheme.error)
                    pastOneTime -> Text("Tento dátum už prešiel, upozornenie by nepríšlo. Vyber budúci dátum.", style = MaterialTheme.typography.bodySmall, color = scheme.error)
                    else -> {
                        val ps = Events.parts(built)
                        ps.forEach { p ->
                            val prefix = if (ps.size > 1) Events.partLabel(p) + " · " else ""
                            Text(prefix + Events.dateLine(p, today), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                }
                Text("Upozorniť vopred", style = MaterialTheme.typography.titleSmall)
                ChipRow {
                    Events.PRESETS.forEach { off ->
                        DomovChip(Events.offsetLabel(off), selected = off in offsets) {
                            offsets = if (off in offsets) offsets - off else offsets + off
                        }
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.filter { ch -> ch.isDigit() }.take(3) },
                    label = { Text("Iný počet dní vopred (nepovinné)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!needsBirthYear && !needsMeninyDate && !pastOneTime) {
                    Events.planLinesFor(built, LocalDateTime.now(), timeOfDay).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                        Text(if (confirmDelete) "Naozaj zmazať?" else "Zmazať udalosť", color = scheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = canSave, onClick = { onSave(build()) }) { Text("Uložiť") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušiť") } },
    )

    if (picking == 1) {
        WheelDateDialog(
            title = dateTitle,
            withYear = birthWithYear || oneTime,
            initial = Events.safeDate(
                if (birthWithYear) (birthYear ?: (today.year - 30)) else if (oneTime) eventYear else DatePick.LEAP_YEAR,
                month, day,
            ),
            yearRange = if (birthWithYear) 1900..today.year else if (oneTime) today.year..(today.year + 10) else DatePick.LEAP_YEAR..DatePick.LEAP_YEAR,
            // jednorazová udalosť nie je v minulosti, narodený nie je v budúcnosti
            minDate = if (oneTime) today else null,
            maxDate = if (birthWithYear) today else null,
            preview = { d -> Events.dateLine(candidate(d), today) },
            hint = if (oneTime) "Minulé dni sú zašedené a nedajú sa vybrať" else null,
            onConfirm = { d ->
                month = d.monthValue
                day = d.dayOfMonth
                if (birthWithYear) birthYear = d.year
                if (oneTime) eventYear = d.year
                picking = 0
            },
            onDismiss = { cancelPick() },
        )
    }
    if (picking == 2) {
        WheelDateDialog(
            title = "Dátum menín (deň a mesiac)",
            withYear = false,
            initial = Events.safeDate(DatePick.LEAP_YEAR, if (meninyMonth > 0) meninyMonth else month, if (meninyMonth > 0) meninyDay else day),
            yearRange = DatePick.LEAP_YEAR..DatePick.LEAP_YEAR,
            minDate = null,
            maxDate = null,
            preview = { d -> Events.dateLine(Events.parts(candidate(d).copy(birthday = true, meninyMonth = d.monthValue, meninyDay = d.dayOfMonth)).last(), today) },
            hint = null,
            onConfirm = { d ->
                meninyMonth = d.monthValue
                meninyDay = d.dayOfMonth
                picking = 0
            },
            onDismiss = { cancelPick() },
        )
    }
}
