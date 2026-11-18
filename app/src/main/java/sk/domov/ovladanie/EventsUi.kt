@file:OptIn(ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import android.Manifest
import android.app.DatePickerDialog
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
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

private fun notificationsOn(c: Context): Boolean = NotificationManagerCompat.from(c).areNotificationsEnabled()

private fun startOfDayMillis(d: LocalDate): Long = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun clockText(minutes: Int): String = String.format(java.util.Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60)

/** Tenký riadok „Udalosti“ na Domove: najbližšia udalosť a za koľko dní. */
@Composable
internal fun EventsRow(events: List<EventItem>, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(26.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Sym.calendarToday, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text("Udalosti", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                Events.rowText(events, LocalDate.now()),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            Icon(Sym.chevronRight, contentDescription = null, tint = scheme.outline, modifier = Modifier.padding(start = 4.dp).size(20.dp))
        }
    }
}

/** Zoznam udalostí a narodenín; ťuknutím na riadok sa upravia, + hore pridá novú. */
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
    val rows = Events.arrange(events, today)

    fun saved(e: EventItem) {
        events = EventsStore.save(context, e)
        adding = false
        editing = null
        // o povolenie upozornení sa appka pýta po uložení prvej udalosti
        if (!allowed && Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            if (rows.isEmpty()) {
                DomovCard {
                    Text(
                        "Zatiaľ nič. Pridaj narodeniny alebo udalosť a appka ťa upozorní vopred, napríklad týždeň a v ten deň.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Pridať") }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = scheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, scheme.surfaceVariant),
                ) {
                    Column {
                        rows.forEachIndexed { index, e ->
                            if (index > 0) HorizontalDivider(color = scheme.surfaceVariant)
                            EventListRow(e = e, today = today, onClick = { editing = e })
                        }
                    }
                }
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

/** Riadok zoznamu: vľavo za koľko dní, vpravo názov, dátum a kedy upozorní. */
@Composable
private fun EventListRow(e: EventItem, today: LocalDate, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val occ = Events.occurrence(e, today)
    val days = occ?.let { ChronoUnit.DAYS.between(today, it).toInt() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (days == null) {
                Text("Prešlo", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            } else {
                val (big, small) = Events.countdown(days)
                Text(
                    big,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (days <= 7) scheme.primary else scheme.onSurface,
                )
                if (small.isNotEmpty()) Text(small, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                Events.title(e),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (days == null) scheme.onSurfaceVariant else scheme.onSurface,
            )
            Text(Events.dateLine(e, today), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Text(Events.offsetsText(e.offsets), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

/**
 * Pridanie a úprava: druh, názov, dátum, opakovanie a kedy vopred upozorniť.
 * Dátum sa skladá zo dňa a mesiaca ([month], [day]) a roku, ktorý má význam len keď sa používa: rok jednorazovej udalosti
 * ([eventYear]) alebo rok narodenia ([birthYear], len keď je zaškrtnuté „Poznám rok narodenia“). Appka nikdy nevymyslí
 * rok narodenia za teba: kým ho nevyberieš, uložiť sa nedá. Pod dátumom a voľbami vidno, čo sa naozaj stane.
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
    var offsets by remember { mutableStateOf(initial?.offsets?.filter { it in Events.PRESETS }?.toSet() ?: setOf(7, 0)) }
    var custom by remember { mutableStateOf(initial?.offsets?.firstOrNull { it !in Events.PRESETS }?.toString() ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    val oneTime = !birthday && !yearly
    val needsBirthYear = birthday && knowsYear && birthYear == null

    fun build(): EventItem {
        val extra = custom.toIntOrNull()?.takeIf { it in 0..Events.MAX_OFFSET }
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
        )
    }

    /** Rok, ktorý sa pri dátume zobrazí a s ktorým sa otvorí výber. */
    fun shownYear(): Int = when {
        birthday && knowsYear -> birthYear ?: (today.year - 30)
        oneTime -> eventYear
        else -> today.year
    }

    fun applyPicked(y: Int, m: Int, d: Int) {
        month = m
        day = d
        if (birthday && knowsYear) birthYear = y
        if (oneTime) eventYear = y
    }

    fun pickDate() {
        val start = Events.safeDate(shownYear(), month, day)
        val dlg = DatePickerDialog(
            context,
            { _, y, m, d -> applyPicked(y, m + 1, d) },
            start.year, start.monthValue - 1, start.dayOfMonth,
        )
        // narodený sa môže človek najneskôr dnes; jednorazová udalosť nemôže byť v minulosti
        if (birthday && knowsYear) dlg.datePicker.maxDate = startOfDayMillis(today)
        if (oneTime) dlg.datePicker.minDate = startOfDayMillis(today)
        dlg.show()
    }

    val built = build()
    val shown = Events.safeDate(shownYear(), month, day)
    val pastOneTime = oneTime && shown.isBefore(today)
    val dateTitle = when {
        birthday && knowsYear -> "Dátum narodenia"
        birthday -> "Narodeniny (deň a mesiac)"
        else -> "Dátum"
    }
    val dateValue = when {
        needsBirthYear -> "Vyber rok"
        birthday && knowsYear -> Events.dateText(shown, year = true)
        oneTime -> Events.dateText(shown, year = true)
        else -> Events.dateText(shown)
    }
    val canSave = name.isNotBlank() && !needsBirthYear && !pastOneTime

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
                    CheckRow(title = "Poznám rok narodenia", caption = "Upozornenie potom napíše, koľko rokov bude mať", checked = knowsYear) {
                        knowsYear = it
                        // rok sa vyberá hneď, aby sa nikdy neuložil vymyslený
                        if (it && birthYear == null) pickDate()
                    }
                } else {
                    CheckRow(title = "Opakovať každý rok", checked = yearly) { yearly = it }
                }
                SettingRow(Sym.calendarToday, dateTitle, value = dateValue, onClick = { pickDate() })
                when {
                    needsBirthYear -> Text("Ťukni na dátum a vyber rok narodenia.", style = MaterialTheme.typography.bodySmall, color = scheme.error)
                    pastOneTime -> Text("Tento dátum už prešiel, upozornenie by nepríšlo. Vyber budúci dátum.", style = MaterialTheme.typography.bodySmall, color = scheme.error)
                    else -> Text(Events.dateLine(built, today), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
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
                if (!needsBirthYear && !pastOneTime) {
                    Events.planLines(Events.plan(built, LocalDateTime.now(), timeOfDay), timeOfDay).forEach {
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
}
