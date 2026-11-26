@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package sk.domov.ovladanie

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(onBack: () -> Unit, startAdd: Boolean = false) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val myId = remember { HouseholdStore.phoneId(context) }
    val people = Calendar.people(myId, HouseholdStore.name(context), HouseholdStore.members(context))
    var events by remember { mutableStateOf(EventsStore.visible(context)) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var view by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(startAdd) }
    var editing by remember { mutableStateOf<EventItem?>(null) }
    DisposableEffect(Unit) {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            if (k == null || k == "events") events = EventsStore.visible(context)
        }
        EventsStore.listen(context, l)
        onDispose { EventsStore.unlisten(context, l) }
    }
    if (adding || editing != null) {
        EventFormScreen(
            initial = editing,
            defaultDate = selected,
            myId = myId,
            people = people,
            onBack = { adding = false; editing = null },
            onSaved = {
                events = EventsStore.visible(context)
                adding = false
                editing = null
            },
        )
        return
    }
    val today = LocalDate.now()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Kalendár", style = MaterialTheme.typography.titleLarge)
                        Text(Events.headerSubtitle(events, today), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Sym.arrowBack, contentDescription = "Späť") } },
                actions = {
                    IconButton(onClick = { adding = true }) { Icon(Sym.add, contentDescription = "Pridať udalosť") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
            )
        },
        containerColor = scheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Segmented(
                options = listOf("Mesiac" to null, "Týždeň" to null),
                selected = view,
                onSelect = { view = it },
            )
            SyncStatusRow()
            if (view == 0) {
                MonthBlock(
                    month = month,
                    selected = selected,
                    today = today,
                    events = events,
                    myId = myId,
                    onMonth = {
                        month = it
                        selected = it.atDay(selected.dayOfMonth.coerceAtMost(it.lengthOfMonth()))
                    },
                    onSelect = {
                        selected = it
                        month = YearMonth.from(it)
                    },
                )
            } else {
                WeekBlock(
                    selected = selected,
                    today = today,
                    events = events,
                    myId = myId,
                    onSelect = { selected = it },
                )
            }
            DayList(
                date = selected,
                today = today,
                events = events,
                myId = myId,
                people = people,
                onOpen = { e ->
                    val id = Events.baseId(e.id)
                    editing = events.firstOrNull { it.id == id } ?: e
                },
                onAdd = { adding = true },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MonthBlock(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    events: List<EventItem>,
    myId: String,
    onMonth: (YearMonth) -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onMonth(month.minusMonths(1)) }) {
            Text("‹", style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface)
        }
        Text(
            Calendar.monthTitle(month),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(onClick = { onMonth(month.plusMonths(1)) }) {
            Text("›", style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface)
        }
        if (month != java.time.YearMonth.from(today) || selected != today) {
            TextButton(onClick = { onSelect(today) }) { Text("Dnes") }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Calendar.WEEKDAYS.forEach { d ->
            Text(
                d,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
    val days = Calendar.monthGrid(month)
    days.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth()) {
            week.forEach { cell ->
                MonthCell(
                    cell = cell,
                    selected = cell.date == selected,
                    today = cell.date == today,
                    dots = Calendar.dots(events, cell.date, myId),
                    onClick = { onSelect(cell.date) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MonthCell(
    cell: Calendar.CalDay,
    selected: Boolean,
    today: Boolean,
    dots: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .padding(2.dp)
            .clip(shape)
            .then(
                when {
                    selected && today -> Modifier
                        .background(scheme.primary)
                        .border(2.dp, scheme.onPrimary.copy(alpha = 0.85f), shape)
                    selected -> Modifier.background(scheme.primary)
                    today -> Modifier
                        .background(scheme.primaryContainer)
                        .border(2.dp, scheme.primary, shape)
                    else -> Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val numColor = when {
            selected -> scheme.onPrimary
            !cell.inMonth -> scheme.onSurfaceVariant.copy(alpha = 0.45f)
            else -> scheme.onSurface
        }
        Text(
            cell.date.dayOfMonth.toString(),
            color = numColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (today || selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.height(8.dp)) {
            dots.take(3).forEach { id ->
                val c = Color(Calendar.colorHex(id))
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (selected) scheme.onPrimary else c),
                )
            }
        }
    }
}

@Composable
private fun WeekBlock(
    selected: LocalDate,
    today: LocalDate,
    events: List<EventItem>,
    myId: String,
    onSelect: (LocalDate) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val start = Calendar.weekStart(selected)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onSelect(selected.minusWeeks(1)) }) {
            Text("‹", style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface)
        }
        Text(
            "${start.dayOfMonth}. – ${start.plusDays(6).dayOfMonth}. ${Calendar.MONTHS_GEN[selected.monthValue - 1]}",
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        TextButton(onClick = { onSelect(selected.plusWeeks(1)) }) {
            Text("›", style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface)
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Calendar.weekDays(selected).forEach { d ->
            val sel = d == selected
            val isToday = d == today
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        when {
                            sel && isToday -> Modifier.background(scheme.primary).border(2.dp, scheme.onPrimary.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                            sel -> Modifier.background(scheme.primary)
                            isToday -> Modifier.background(scheme.primaryContainer).border(2.dp, scheme.primary, RoundedCornerShape(12.dp))
                            else -> Modifier
                        },
                    )
                    .clickable { onSelect(d) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    Calendar.WEEKDAYS[d.dayOfWeek.value - 1],
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        sel -> scheme.onPrimary
                        isToday -> scheme.onPrimaryContainer
                        else -> scheme.onSurfaceVariant
                    },
                )
                Text(
                    d.dayOfMonth.toString(),
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        sel -> scheme.onPrimary
                        isToday -> scheme.primary
                        else -> scheme.onSurface
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(8.dp)) {
                    Calendar.dots(events, d, myId).take(3).forEach { id ->
                        Box(Modifier.size(4.dp).clip(CircleShape).background(if (sel) scheme.onPrimary else Color(Calendar.colorHex(id))))
                    }
                }
            }
        }
    }
    Timeline(date = selected, events = events, myId = myId)
}

@Composable
private fun Timeline(date: LocalDate, events: List<EventItem>, myId: String) {
    val scheme = MaterialTheme.colorScheme
    val day = Calendar.onDay(events, date, myId)
    val allDay = day.filter { it.startMin < 0 }
    val timed = day.filter { it.startMin >= 0 }
    if (allDay.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            allDay.forEach { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(Calendar.colorHex(e.color)).copy(alpha = 0.25f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(Calendar.colorHex(e.color))))
                    Spacer(Modifier.width(10.dp))
                    Text(Events.title(e), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
    val fromHour = 7
    val toHour = 21
    val hourH = 44.dp
    Box(Modifier.fillMaxWidth().height(hourH * (toHour - fromHour))) {
        Column {
            for (h in fromHour until toHour) {
                Row(Modifier.fillMaxWidth().height(hourH)) {
                    Text(
                        Events.clockMinutes(h * 60),
                        modifier = Modifier.width(48.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    HorizontalDivider(modifier = Modifier.align(Alignment.Top).weight(1f), color = scheme.surfaceVariant)
                }
            }
        }
        timed.forEach { e ->
            val start = e.startMin.coerceIn(fromHour * 60, toHour * 60)
            val end = (if (e.endMin > e.startMin) e.endMin else e.startMin + 60).coerceIn(start + 15, toHour * 60)
            val top = ((start - fromHour * 60) / 60f) * 44f
            val h = ((end - start) / 60f) * 44f
            Column(
                modifier = Modifier
                    .padding(start = 52.dp, end = 4.dp)
                    .offset(y = top.dp)
                    .fillMaxWidth()
                    .height(h.dp.coerceAtLeast(22.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(Calendar.colorHex(e.color)))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(e.name, color = Color(0xFF2A1700), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                Text(Events.timeRange(e), color = Color(0xFF2A1700).copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun DayList(
    date: LocalDate,
    today: LocalDate,
    events: List<EventItem>,
    myId: String,
    people: List<Calendar.HousePerson>,
    onOpen: (EventItem) -> Unit,
    onAdd: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val items = Calendar.onDay(events, date, myId)
    val meniny = Meniny.zoznam(date)
    DomovCard {
        Text(Calendar.dayTitle(date, today), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty() && meniny.isEmpty()) {
            Text("Nič v tento deň.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        } else {
            items.forEach { e ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpen(e) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(Calendar.colorHex(e.color))))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Events.title(e), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        val sub = buildString {
                            val t = Events.timeRange(e)
                            if (t.isNotEmpty()) append(t)
                            val who = Calendar.audienceLabel(e, people)
                            if (who.isNotEmpty()) {
                                if (isNotEmpty()) append(" · ")
                                append(who)
                            }
                            Events.ageCaption(e, date)?.let {
                                if (isNotEmpty()) append(" · ")
                                append(it)
                            }
                        }
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
            // meniny zo slovenského kalendára, ak nie sú už ako udalosť
            val shown = items.filter { it.isMeniny }.map { Events.displayName(it).lowercase() }.toSet()
            meniny.filter { it.lowercase() !in shown }.forEach { name ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(Calendar.colorHex("peach"))))
                    Spacer(Modifier.width(12.dp))
                    Text("Meniny: $name", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        TextButton(onClick = onAdd, modifier = Modifier.align(Alignment.Start)) {
            Icon(Sym.add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Pridať udalosť")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventFormScreen(
    initial: EventItem?,
    defaultDate: LocalDate,
    myId: String,
    people: List<Calendar.HousePerson>,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val today = LocalDate.now()
    var birthday by remember { mutableStateOf(initial?.birthday == true) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var date by remember {
        mutableStateOf(
            if (initial != null) Events.safeDate(if (initial.yearly) defaultDate.year else maxOf(initial.year, 1), initial.month, initial.day)
            else defaultDate,
        )
    }
    var yearKnown by remember { mutableStateOf((initial?.year ?: 0) > 0 && initial?.birthday == true) }
    var birthYear by remember { mutableStateOf(if ((initial?.year ?: 0) > 0) initial!!.year else today.year - 30) }
    var meninyOn by remember { mutableStateOf(initial != null && Events.hasMeniny(initial)) }
    var meninyDate by remember {
        mutableStateOf(
            if (initial != null && Events.hasMeniny(initial)) Events.safeDate(DatePick.LEAP_YEAR, initial.meninyMonth, initial.meninyDay)
            else defaultDate,
        )
    }
    var allDay by remember { mutableStateOf(initial == null || initial.startMin < 0 || initial.birthday) }
    var startMin by remember { mutableStateOf(if (initial != null && initial.startMin >= 0) initial.startMin else 14 * 60) }
    var endMin by remember { mutableStateOf(if (initial != null && initial.endMin > 0) initial.endMin else 15 * 60) }
    var yearly by remember { mutableStateOf(initial?.yearly == true || initial?.birthday == true) }
    var offsets by remember { mutableStateOf(initial?.offsets ?: listOf(0)) }
    var remindMin by remember { mutableStateOf(if (initial != null) initial.remindMin else 60) }
    val initialSel = remember {
        if (initial == null) Calendar.AudienceSel(all = true, ids = emptySet())
        else Calendar.decodeAudience(initial, people)
    }
    var allAudience by remember { mutableStateOf(initialSel.all) }
    var selectedIds by remember { mutableStateOf(initialSel.ids) }
    var legacyPartner by remember { mutableStateOf(initialSel.legacyPartner) }
    var color by remember { mutableStateOf(initial?.color?.ifBlank { "amber" } ?: "amber") }
    var nameError by remember { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    var pickDate by remember { mutableStateOf(false) }
    var pickMeniny by remember { mutableStateOf(false) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun draft() = Calendar.EventDraft(
        name = name,
        birthday = birthday,
        yearKnown = yearKnown,
        birthYear = birthYear,
        date = date,
        yearly = yearly,
        allDay = allDay,
        startMin = startMin,
        endMin = endMin,
        offsets = offsets,
        remindMin = remindMin,
        meninyOn = meninyOn,
        meninyMonth = meninyDate.monthValue,
        meninyDay = meninyDate.dayOfMonth,
        color = color,
        allAudience = allAudience,
        selectedIds = selectedIds,
        legacyPartner = legacyPartner,
        existingOwnerId = initial?.ownerId,
    )

    fun trySave() {
        val d = draft()
        if (Calendar.formIssue(d) != Calendar.EventFormIssue.NONE) {
            nameError = true
            runCatching { nameFocus.requestFocus() }
            return
        }
        val item = Calendar.buildEvent(
            d, myId, people, System.currentTimeMillis(),
            initial?.id ?: UUID.randomUUID().toString(),
        )
        EventsStore.save(context, item)
        onSaved()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "Nová udalosť" else "Upraviť udalosť") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Sym.arrowBack, contentDescription = "Späť") } },
                actions = {
                    TextButton(onClick = { trySave() }) {
                        Text("Uložiť", fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
            )
        },
        containerColor = scheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (initial?.isMeniny != true) {
                Segmented(
                    options = listOf("Udalosť" to null, "Narodeniny" to null),
                    selected = if (birthday) 1 else 0,
                    onSelect = {
                        birthday = it == 1
                        if (birthday) {
                            allDay = true
                            yearly = true
                        }
                    },
                )
            }
            DomovTextField(
                value = name,
                onValueChange = {
                    name = it.take(Events.MAX_NAME)
                    if (nameError) nameError = false
                },
                modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
                singleLine = true,
                isError = nameError,
                supportingText = if (nameError) {
                    { Text(Calendar.formHint(draft()) ?: "Zadaj názov") }
                } else null,
                label = { Text(if (birthday) "Meno" else "Názov") },
            )
            DialogPickRow(
                icon = Sym.calendarToday,
                caption = if (birthday) "Dátum narodenia" else "Dátum",
                value = Events.dateText(date, weekday = true, year = !yearly || yearKnown),
                onClick = { pickDate = true },
            )
            if (birthday) {
                CheckRow(title = "Rok narodenia je známy", checked = yearKnown, onChange = { yearKnown = it })
            } else {
                CheckRow(title = "Celý deň", checked = allDay, onChange = { allDay = it })
                if (!allDay) {
                    DialogPickRow(icon = Sym.schedule, caption = "Začiatok", value = Events.clockMinutes(startMin), onClick = { pickStart = true })
                    DialogPickRow(icon = Sym.schedule, caption = "Koniec", value = Events.clockMinutes(endMin), onClick = { pickEnd = true })
                }
                CheckRow(title = "Opakovanie", caption = Calendar.repeatLabel(yearly, false), checked = yearly, onChange = { yearly = it })
            }
            if (birthday) {
                CheckRow(title = "Pridať aj meniny", checked = meninyOn, onChange = { on ->
                    meninyOn = on
                    if (on) {
                        val found = Meniny.dniPreMeno(Events.clean(name))
                        found.singleOrNull()?.let { (m, d) ->
                            meninyDate = Events.safeDate(DatePick.LEAP_YEAR, m, d)
                        }
                    }
                })
                if (meninyOn) {
                    DialogPickRow(
                        icon = Sym.calendarToday,
                        caption = "Meniny",
                        value = Events.dateText(meninyDate),
                        onClick = { pickMeniny = true },
                    )
                }
            }
            if (allDay || birthday) {
                Text("Pripomienka", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                val choices = listOf(0, 1, 3, 7, 14)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    choices.forEach { off ->
                        DomovChip(
                            label = Events.offsetLabel(off),
                            selected = off in offsets,
                            onClick = {
                                offsets = if (off in offsets) offsets - off else offsets + off
                            },
                        )
                    }
                }
                if (offsets.isEmpty()) Text("Bez pripomienky", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            } else {
                Text("Pripomienka", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                val choices = listOf(-1 to "Bez pripomienky", 0 to "V čase začiatku", 10 to "10 min. vopred", 60 to "1 hodinu vopred", 1440 to "1 deň vopred")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    choices.forEach { (v, label) ->
                        DomovChip(label = label, selected = remindMin == v, onClick = { remindMin = v })
                    }
                }
            }
            val shown = Calendar.peopleWithAudience(people, selectedIds)
            val showWho = shown.size > 1 || legacyPartner
            if (showWho) {
                Text("Pre koho", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DomovChip(
                        label = "Všetci",
                        selected = allAudience,
                        onClick = {
                            allAudience = true
                            selectedIds = emptySet()
                            legacyPartner = false
                        },
                    )
                    shown.forEach { person ->
                        val on = !allAudience && person.id in selectedIds
                        DomovChip(
                            label = person.chipLabel(),
                            selected = on,
                            onClick = {
                                legacyPartner = false
                                val next = if (person.id in selectedIds) selectedIds - person.id else selectedIds + person.id
                                val allIds = shown.map { it.id }.toSet()
                                if (next.isEmpty() || (allIds.isNotEmpty() && next.containsAll(allIds))) {
                                    allAudience = true
                                    selectedIds = emptySet()
                                } else {
                                    allAudience = false
                                    selectedIds = next
                                }
                            },
                        )
                    }
                    if (legacyPartner && shown.none { !it.self }) {
                        DomovChip(
                            label = "Ostatní členovia",
                            selected = !allAudience && legacyPartner,
                            onClick = {
                                allAudience = false
                                selectedIds = emptySet()
                                legacyPartner = true
                            },
                        )
                    }
                }
            }
            Text("Farba", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Calendar.COLORS.forEach { id ->
                    val c = Color(Calendar.colorHex(id))
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(c)
                            .then(if (color == id) Modifier.border(3.dp, scheme.onSurface, CircleShape) else Modifier)
                            .clickable { color = id },
                    )
                }
            }
            if (initial != null) {
                TextButton(onClick = { confirmDelete = true }) {
                    Icon(Sym.delete, contentDescription = null, tint = scheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("Odstrániť", color = scheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (pickDate) {
        WheelDateDialog(
            title = if (birthday) "Dátum narodenia" else "Dátum",
            withYear = !birthday || yearKnown || !yearly,
            initial = date,
            yearRange = 1900..(LocalDate.now().year + 10),
            minDate = null,
            maxDate = null,
            preview = { Events.dateText(it, weekday = true, year = true) },
            hint = null,
            onConfirm = { date = it; if (birthday && yearKnown) birthYear = it.year; pickDate = false },
            onDismiss = { pickDate = false },
        )
    }
    if (pickMeniny) {
        WheelDateDialog(
            title = "Meniny",
            withYear = false,
            initial = meninyDate,
            yearRange = 1900..(LocalDate.now().year + 10),
            minDate = null,
            maxDate = null,
            preview = { Events.dateText(it) },
            hint = null,
            onConfirm = { meninyDate = it; pickMeniny = false },
            onDismiss = { pickMeniny = false },
        )
    }
    if (pickStart) {
        WheelTimeDialog(
            title = "Začiatok",
            initialMinutes = startMin,
            preview = { Events.clockMinutes(it) },
            onConfirm = {
                startMin = it
                if (endMin <= startMin) endMin = (startMin + 60).coerceAtMost(24 * 60 - 1)
                pickStart = false
            },
            onDismiss = { pickStart = false },
        )
    }
    if (pickEnd) {
        WheelTimeDialog(
            title = "Koniec",
            initialMinutes = endMin,
            preview = { Events.clockMinutes(it) },
            onConfirm = { endMin = it; pickEnd = false },
            onDismiss = { pickEnd = false },
        )
    }
    if (confirmDelete && initial != null) {
        DomovAlert(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Sym.delete, null) },
            title = { Text("Odstrániť udalosť?") },
            text = { Text("„${initial.name}“ sa odstráni z kalendára na oboch telefónoch domácnosti.") },
            confirmButton = {
                DialogPrimaryButton(onClick = {
                    EventsStore.delete(context, initial.id)
                    confirmDelete = false
                    onSaved()
                }) { Text("Odstrániť") }
            },
            dismissButton = { DialogTextButton(onClick = { confirmDelete = false }) { Text("Zrušiť") } },
        )
    }
}
