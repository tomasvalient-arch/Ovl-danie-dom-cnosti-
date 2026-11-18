@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package sk.domov.ovladanie

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Základná karta: tmavý povrch, zaoblenie 24 dp a jemný okraj (ako v návrhu). */
@Composable
fun DomovCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    border: Color = MaterialTheme.colorScheme.surfaceVariant,
    padding: Dp = 16.dp,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = color, border = BorderStroke(1.dp, border), content = body)
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color, border = BorderStroke(1.dp, border), content = body)
    }
}

/** Nadpis sekcie malými písmenami (napr. „ĎALŠIE NASTAVENIA“). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier.padding(start = 4.dp, top = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Krúžok s ikonou zariadenia: jantárový (zapnuté), tlmený (vypnuté), červený (offline). */
@Composable
fun IconCircle(icon: ImageVector, on: Boolean, error: Boolean = false, size: Dp = 40.dp) {
    val c = Domov.colors
    val bg = when {
        error -> MaterialTheme.colorScheme.errorContainer
        on -> c.iconOn
        else -> c.iconOff
    }
    val fg = when {
        error -> MaterialTheme.colorScheme.error
        on -> c.onIconOn
        else -> c.onIconOff
    }
    Box(modifier = Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * Vypínač s fajkou v jazdci (ako v návrhu). [onTile] = vypínač leží na jantárovej dlaždici (zapnuté zariadenie),
 * kde by jantárová dráha splynula s podkladom, preto je dráha tmavá a jazdec jantárový.
 */
@Composable
fun DomovSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true, onTile: Boolean = false) {
    val c = Domov.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = if (onTile && checked) {
            SwitchDefaults.colors(
                checkedThumbColor = c.onIconOn,
                checkedTrackColor = c.iconOn,
                checkedBorderColor = c.iconOn,
                checkedIconColor = c.iconOn,
                disabledCheckedThumbColor = c.onIconOn.copy(alpha = 0.6f),
                disabledCheckedTrackColor = c.iconOn.copy(alpha = 0.5f),
                disabledCheckedBorderColor = c.iconOn.copy(alpha = 0.5f),
                disabledCheckedIconColor = c.iconOn.copy(alpha = 0.6f),
            )
        } else {
            SwitchDefaults.colors()
        },
        thumbContent = if (checked) {
            { Icon(Sym.check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
        } else {
            null
        },
    )
}

/** Riadok nastavenia: ikona, názov, hodnota vpravo a šípka (alebo vlastný obsah vpravo). */
@Composable
fun SettingRow(
    icon: ImageVector?,
    title: String,
    value: String? = null,
    caption: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        } else if (onClick != null) {
            Icon(Sym.chevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 4.dp).size(20.dp))
        }
    }
}

/** Čip s jantárovým zvýraznením vybranej možnosti. */
@Composable
fun DomovChip(label: String, selected: Boolean, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)) } },
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

/** Vodorovne posúvateľný rad čipov. */
@Composable
fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** Prepínač dvoch (alebo viac) možností cez celú šírku, napr. Biela / Farba. */
@Composable
fun Segmented(options: List<Pair<String, ImageVector?>>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp)),
    ) {
        options.forEachIndexed { i, (label, icon) ->
            val sel = i == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(i) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                if (sel) {
                    Icon(Sym.check, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                } else if (icon != null) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
            }
        }
    }
}

/**
 * Široký posuvník s farebnou dráhou (jas, teplota bielej). Počas ťahania volá [onChange] (plynulé ovládanie –
 * obmedzenie rýchlosti rieši HomeViewModel.liveValues), po pustení [onFinished] s presnou hodnotou.
 * Kým prst ťahá, posuvník ignoruje zmeny [value] zvonka (neskáče späť).
 */
@Composable
fun WideSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    track: Brush,
    modifier: Modifier = Modifier,
    fill: Brush? = null,
    steps: Int = 0,
    startIcon: ImageVector? = null,
    endIcon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    onChange: (Float) -> Unit = {},
    onFinished: (Float) -> Unit,
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    var dragging by remember { mutableStateOf(false) }
    var frac by remember(range) { mutableFloatStateOf(((value - range.start) / span).coerceIn(0f, 1f)) }
    LaunchedEffect(value, range) {
        if (!dragging) frac = ((value - range.start) / span).coerceIn(0f, 1f)
    }
    // po pustení: ak zariadenie hodnotu nezmenilo (napr. ju upravilo na svoje minimum), jazdec sa vráti na skutočný stav
    val currentValue by rememberUpdatedState(value)
    var releases by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(releases) {
        if (releases > 0) {
            kotlinx.coroutines.delay(2500)
            if (!dragging) frac = ((currentValue - range.start) / span).coerceIn(0f, 1f)
        }
    }
    val change by rememberUpdatedState(onChange)
    val finish by rememberUpdatedState(onFinished)
    fun snap(f: Float): Float {
        val c = f.coerceIn(0f, 1f)
        return if (steps > 0) (c * (steps + 1)).roundToInt() / (steps + 1).toFloat() else c
    }
    fun real(f: Float) = range.start + f * span
    val shape = RoundedCornerShape(16.dp)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(trackColor)
            .alpha(if (enabled) 1f else 0.45f)
            .pointerInput(enabled, range, steps) {
                if (!enabled) return@pointerInput
                detectTapGestures { pos ->
                    frac = snap(pos.x / size.width)
                    change(real(frac))
                    releases++
                    finish(real(frac))
                }
            }
            .pointerInput(enabled, range, steps) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { pos -> dragging = true; frac = snap(pos.x / size.width); change(real(frac)) },
                    onDragEnd = { dragging = false; releases++; finish(real(frac)) },
                    onDragCancel = { dragging = false; releases++; finish(real(frac)) },
                    onHorizontalDrag = { change2, _ -> frac = snap(change2.position.x / size.width); change(real(frac)) },
                )
            },
    ) {
        val w = maxWidth
        if (fill == null) {
            Box(Modifier.fillMaxWidth().height(48.dp).background(track))
        } else {
            Box(Modifier.width(w * frac).height(48.dp).background(fill))
        }
        if (startIcon != null) {
            Icon(startIcon, null, tint = iconTint.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp).size(20.dp))
        }
        if (endIcon != null) {
            Icon(endIcon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).size(20.dp))
        }
        val thumbW = 6.dp
        val x = ((w - thumbW - 16.dp) * frac + 8.dp)
        Box(
            Modifier
                .offset(x = x)
                .align(Alignment.CenterStart)
                .width(thumbW)
                .height(30.dp)
                .shadow(2.dp, RoundedCornerShape(3.dp))
                .background(Color.White, RoundedCornerShape(3.dp)),
        )
    }
}

/** Jantárová výplň posuvníka jasu. */
@Composable
fun amberFill(): Brush = Brush.horizontalGradient(
    listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary),
)

/** Dráha teploty bielej: teplá → studená. */
val TempTrack: Brush = Brush.horizontalGradient(listOf(Color(0xFFFFA347), Color(0xFFFFD6A1), Color(0xFFFFF6EC), Color(0xFFDDE8FF), Color(0xFFB9D2FF)))

/** Malá karta merania: ikona, hodnota s jednotkou a popis (Napätie, Prúd, Spotreba). */
@Composable
fun MetricTile(icon: ImageVector, value: String, unit: String, label: String, modifier: Modifier = Modifier) {
    DomovCard(modifier = modifier, padding = 14.dp, spacing = 6.dp) {
        IconCircle(icon, on = false, size = 32.dp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            if (unit.isNotBlank()) {
                Text(
                    " $unit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Rozdelí „231,4 V“ na číslo a jednotku. */
fun splitUnit(text: String): Pair<String, String> {
    val i = text.lastIndexOf(' ')
    return if (i > 0) text.substring(0, i) to text.substring(i + 1) else text to ""
}

/** „0:42:10“ */
fun clockText(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Odpočet / časovač vypnutia s rýchlymi voľbami. [leftSec] zostávajúci čas, [on] = zariadenie je zapnuté
 * (odpočet ho vypne), inak ho zapne. [onSet] dostane minúty, 0 = zrušiť.
 */
@Composable
fun TimerCard(
    title: String,
    leftSec: Long,
    on: Boolean,
    enabled: Boolean,
    options: List<Int> = listOf(15, 60, 120),
    note: String? = null,
    onSet: (Int) -> Unit,
) {
    var custom by remember { mutableStateOf(false) }
    DomovCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconCircle(Sym.timer, on = leftSec > 0, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when {
                        leftSec <= 0 -> "Nie je nastavený"
                        on -> "Vypne sa o ${clockText(leftSec)}"
                        else -> "Zapne sa o ${clockText(leftSec)}"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { min ->
                DomovChip(label = if (min < 60) "$min min" else "${min / 60} h", selected = false, enabled = enabled) { onSet(min) }
            }
            DomovChip(label = "Vlastný…", selected = false, enabled = enabled) { custom = true }
            if (leftSec > 0) DomovChip(label = "Zrušiť", selected = false, enabled = enabled) { onSet(0) }
        }
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (custom) {
        ValueDialog(
            title = "Odpočet v minútach",
            initial = "30",
            numeric = true,
            onDismiss = { custom = false },
            onConfirm = { text -> custom = false; text.toIntOrNull()?.takeIf { it > 0 }?.let(onSet) },
        )
    }
}

/** Jednoduchá čiara grafu so žiarou pod ňou. */
@Composable
fun LineGraph(samples: List<Float>, modifier: Modifier = Modifier, line: Color = MaterialTheme.colorScheme.primary) {
    val density = LocalDensity.current
    Canvas(modifier = modifier) {
        if (samples.size < 2) return@Canvas
        val maxV = (samples.maxOrNull() ?: 0f).coerceAtLeast(1f) * 1.1f
        val minV = 0f
        val pad = with(density) { 6.dp.toPx() }
        val w = size.width - pad
        val h = size.height
        val path = androidx.compose.ui.graphics.Path()
        val area = androidx.compose.ui.graphics.Path()
        samples.forEachIndexed { i, v ->
            val x = w * i / (samples.size - 1)
            val y = h * (1f - (v - minV) / (maxV - minV))
            if (i == 0) { path.moveTo(x, y); area.moveTo(x, h); area.lineTo(x, y) } else { path.lineTo(x, y); area.lineTo(x, y) }
        }
        area.lineTo(w, h)
        area.close()
        drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), line.copy(alpha = 0f))))
        drawPath(path, line, style = androidx.compose.ui.graphics.drawscope.Stroke(width = with(density) { 2.5.dp.toPx() }))
        val lx = w
        val ly = h * (1f - (samples.last() - minV) / (maxV - minV))
        drawCircle(Color.White, radius = with(density) { 4.dp.toPx() }, center = androidx.compose.ui.geometry.Offset(lx, ly))
    }
}

/** Riadok so zaškrtávacím políčkom, nadpisom a nepovinným popisom; ťuknutie kamkoľvek na riadok prepne políčko. */
@Composable
internal fun CheckRow(
    title: String,
    caption: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    titleStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    verticalPadding: Dp = 0.dp,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = titleStyle)
            if (!caption.isNullOrBlank()) Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
