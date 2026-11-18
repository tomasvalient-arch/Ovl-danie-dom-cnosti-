@file:OptIn(ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun rgbColor(h: Float, s: Float, v: Float): Color {
    val c = Light.hsvToRgb(h, s, v)
    return Color(c[0], c[1], c[2])
}

/** 8 rýchlych farieb: červená, oranžová, žltá, zelená, tyrkysová, modrá, fialová, ružová. */
private val QUICK_HUES = listOf(0f, 30f, 50f, 130f, 195f, 230f, 275f, 330f)

/** Teplota 0 (teplá) – 1 (studená) ako približné Kelviny, napr. „3 000 K · teplá“. */
internal fun tempText(t: Float): String {
    val k = ((2700 + t.coerceIn(0f, 1f) * 3800) / 100).roundToInt() * 100
    val name = if (t < 0.34f) "teplá" else if (t < 0.67f) "neutrálna" else "studená"
    return "${k / 1000}\u00A0${"%03d".format(k % 1000)} K · $name"
}

/**
 * Veľký svietiaci kruh s jasom. Ťuknutie = zapnúť / vypnúť. [glow] = farba žiary (farebný režim), inak jantárová.
 */
@Composable
fun LightOrb(on: Boolean, percent: Int, glow: Color?, enabled: Boolean, onToggle: () -> Unit) {
    val c = Domov.colors
    val colors = when {
        !on -> c.orbOff
        glow != null -> listOf(Color.White.copy(alpha = 0.95f), glow, glow.copy(alpha = 0.85f))
        else -> c.orbGradient
    }
    val halo = glow ?: c.glow
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(250.dp), contentAlignment = Alignment.Center) {
            if (on) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Brush.radialGradient(listOf(halo.copy(alpha = 0.45f), halo.copy(alpha = 0.12f), Color.Transparent)))
                    drawCircle(halo.copy(alpha = 0.10f), radius = size.minDimension * 0.44f)
                }
            }
            Box(
                modifier = Modifier
                    .size(184.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(colors))
                    .then(if (!on) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape) else Modifier)
                    .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                val fg = if (on) Color(0xFF3F2400) else MaterialTheme.colorScheme.onSurfaceVariant
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (on) Sym.lightbulbFill else Sym.lightbulb, contentDescription = null, tint = fg, modifier = Modifier.size(36.dp))
                    Text(if (on) "$percent %" else "Vypnuté", color = fg, fontSize = if (on) 40.sp else 26.sp, fontWeight = FontWeight.Bold)
                    Text(if (on) "ZAPNUTÉ" else "", color = fg.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(
            if (on) "Ťukni na kruh pre vypnutie" else "Ťukni na kruh pre zapnutie",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Priebežné príkazy počas ťahania posuvníka (plynulé ovládanie). Dodá ho obrazovka zariadenia
 * (HomeViewModel.liveValues); keď chýba (napr. nastavenia scény), posuvník posiela len po pustení.
 */
val LocalLiveCommand = staticCompositionLocalOf<((Map<String, Any>) -> Unit)?> { null }

/** Priebežný príkaz z hodnoty posuvníka, alebo null, ak plynulé ovládanie nie je k dispozícii. */
@Composable
internal fun <T> liveOf(build: (T) -> Map<String, Any>): ((T) -> Unit)? {
    val live = LocalLiveCommand.current ?: return null
    return { v -> runCatching { build(v) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { runCatching { live(it) } } }
}

/** Ako [liveOf] pre farebné koliesko (odtieň, sýtosť). */
@Composable
internal fun liveOf2(build: (Float, Float) -> Map<String, Any>): ((Float, Float) -> Unit)? {
    val live = LocalLiveCommand.current ?: return null
    return { h, s -> runCatching { build(h, s) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { runCatching { live(it) } } }
}

/** Kruh svetla pre detail: jas a farba podľa aktuálneho stavu. */
@Composable
fun LightOrbFor(spec: LightSpec, values: Map<String, Any>, enabled: Boolean, onCommand: (Map<String, Any>) -> Unit) {
    val st = Light.read(spec, values)
    val colour = spec.color != null && st.isColour
    LightOrb(
        on = st.on,
        percent = if (colour) (st.color.v * 100).roundToInt().coerceIn(1, 100) else st.whitePercent,
        glow = if (colour) rgbColor(st.color.h, max(st.color.s, 0.3f), 1f) else null,
        enabled = enabled,
        onToggle = { onCommand(Light.power(spec, !st.on)) },
    )
}

/**
 * Ovládanie svetla: režim Biela/Farba, široké posuvníky jasu a teploty, farebné koliesko a 8 rýchlych farieb.
 * [enabled] = false znamená, že svetlo je vypnuté (ovládanie je stlmené).
 */
@Composable
fun LightControls(
    spec: LightSpec,
    values: Map<String, Any>,
    enabled: Boolean,
    onCommand: (Map<String, Any>) -> Unit,
) {
    val state = Light.read(spec, values)
    val canColour = spec.color != null
    val colourMode = canColour && state.isColour

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (canColour) {
            Segmented(
                options = listOf("Biela" to Sym.lightbulb, "Farba" to Sym.palette),
                selected = if (colourMode) 1 else 0,
                enabled = enabled,
            ) { i -> onCommand(Light.mode(spec, if (i == 0) "white" else "colour")) }
        }
        if (!enabled) Hint("Zapni svetlo, aby si mohol meniť jas a farby.")
        DomovCard(modifier = Modifier.alpha(if (enabled) 1f else 0.6f)) {
            if (colourMode) {
                val c = state.color
                BrightnessSlider(
                    percent = (c.v * 100).roundToInt().coerceIn(1, 100),
                    enabled = enabled,
                    onFinished = { onCommand(Light.colour(spec, Hsv(c.h, c.s, it / 100f))) },
                    onLive = liveOf { p: Int -> Light.colour(spec, Hsv(c.h, c.s, p / 100f)) },
                )
            } else {
                BrightnessSlider(
                    percent = state.whitePercent,
                    enabled = enabled,
                    onFinished = { onCommand(Light.whiteBrightness(spec, it)) },
                    onLive = liveOf { p: Int -> Light.whiteBrightness(spec, p) },
                )
                if (spec.temp != null) {
                    TempSlider(temp = state.temp, enabled = enabled, onFinished = { onCommand(Light.whiteTemp(spec, it)) }, onLive = liveOf { t: Float -> Light.whiteTemp(spec, t) })
                }
            }
        }
        if (canColour) {
            val c = state.color
            val v = max(c.v, 0.1f)
            ColourCard(
                hue = c.h,
                sat = c.s,
                selected = colourMode,
                enabled = enabled,
                onPick = { h, s -> onCommand(Light.colour(spec, Hsv(h, s, v))) },
                onLive = liveOf2 { h, s -> Light.colour(spec, Hsv(h, s, v)) },
            )
        }
    }
}

/**
 * Svetlo s dvoma zdrojmi: sekcia Hlavné svetlo (jas a teplota bielej) a sekcia Podhľadové svetlo (farba a jas).
 * Ak sú nastavené vypínače sekcií, každá má vlastný vypínač.
 */
@Composable
fun DualLightControls(
    spec: DualSpec,
    values: Map<String, Any>,
    enabled: Boolean,
    onCommand: (Map<String, Any>) -> Unit,
) {
    val st = Light.readDual(spec, values)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!enabled) Hint("Zapni svetlo, aby si mohol meniť jas a farby.")

        val whiteEnabled = enabled && st.whiteOn != false
        LightSection(
            title = "Hlavné svetlo (biela)",
            icon = Sym.light,
            switchOn = st.whiteOn,
            enabled = enabled,
            onSwitch = { on -> spec.whiteSwitch?.let { onCommand(mapOf(it to on)) } },
        ) {
            BrightnessSlider(percent = st.whitePercent, enabled = whiteEnabled, onFinished = { onCommand(Light.dualWhiteBrightness(spec, it)) }, onLive = liveOf { p: Int -> Light.dualWhiteBrightness(spec, p) })
            TempSlider(temp = st.temp, enabled = whiteEnabled, onFinished = { onCommand(Light.dualTemp(spec, it)) }, onLive = liveOf { t: Float -> Light.dualTemp(spec, t) })
        }

        val colourEnabled = enabled && st.colourOn != false
        LightSection(
            title = "Podhľadové svetlo (farba)",
            icon = Sym.palette,
            switchOn = st.colourOn,
            enabled = enabled,
            onSwitch = { on -> spec.colourSwitch?.let { onCommand(mapOf(it to on)) } },
        ) {
            val c = st.colour
            val v = max(c.v, 0.1f)
            BrightnessSlider(
                percent = (c.v * 100).roundToInt().coerceIn(1, 100),
                enabled = colourEnabled,
                onFinished = { onCommand(Light.dualColour(spec, Hsv(c.h, c.s, it / 100f))) },
                onLive = liveOf { p: Int -> Light.dualColour(spec, Hsv(c.h, c.s, p / 100f)) },
            )
            WheelAndDots(hue = c.h, sat = c.s, enabled = colourEnabled, onLive = liveOf2 { h, s -> Light.dualColour(spec, Hsv(h, s, v)) }) { h, s -> onCommand(Light.dualColour(spec, Hsv(h, s, v))) }
        }

        if (spec.whiteSwitch == null && spec.colourSwitch == null) {
            Hint("Samostatné vypínače hlavného a podhľadového svetla zatiaľ nie sú nastavené (ozubené koliesko → Pokročilé).")
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
}

/** Jedna sekcia svetla: nadpis s voliteľným vypínačom a pod ním ovládanie. */
@Composable
private fun LightSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    switchOn: Boolean?,
    enabled: Boolean,
    onSwitch: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    DomovCard(modifier = Modifier.alpha(if (enabled) 1f else 0.6f)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(icon, on = switchOn != false && enabled, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (switchOn != null) DomovSwitch(checked = switchOn, onCheckedChange = onSwitch, enabled = enabled)
        }
        content()
    }
}

@Composable
private fun BrightnessSlider(percent: Int, enabled: Boolean, onFinished: (Int) -> Unit, onLive: ((Int) -> Unit)? = null) {
    var shown by remember(percent) { androidx.compose.runtime.mutableIntStateOf(percent) }
    // po pustení: ak zariadenie hodnotu nezmenilo (napr. ju upravilo na svoje minimum), popis sa vráti na skutočný stav
    val current by rememberUpdatedState(percent)
    var released by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(released) {
        if (released > 0) {
            kotlinx.coroutines.delay(2500)
            shown = current
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Jas", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("$shown %", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        WideSlider(
            value = percent.toFloat(),
            range = 1f..100f,
            enabled = enabled,
            track = amberFill(),
            fill = amberFill(),
            startIcon = Sym.brightnessLow,
            endIcon = Sym.brightnessHigh,
            onChange = { shown = it.roundToInt(); onLive?.invoke(it.roundToInt().coerceIn(1, 100)) },
            onFinished = { released++; onFinished(it.roundToInt().coerceIn(1, 100)) },
        )
    }
}

@Composable
private fun TempSlider(temp: Float, enabled: Boolean, onFinished: (Float) -> Unit, onLive: ((Float) -> Unit)? = null) {
    var shown by remember(temp) { mutableFloatStateOf(temp) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Teplota bielej", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(tempText(shown), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        WideSlider(
            value = temp,
            range = 0f..1f,
            enabled = enabled,
            track = TempTrack,
            onChange = { shown = it; onLive?.invoke(it) },
            onFinished = onFinished,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Teplá", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("Studená", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ColourCard(hue: Float, sat: Float, selected: Boolean, enabled: Boolean, onPick: (Float, Float) -> Unit, onLive: ((Float, Float) -> Unit)? = null) {
    DomovCard(modifier = Modifier.alpha(if (enabled) 1f else 0.6f)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Farba", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (!selected) Text("prepne na režim Farba", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        WheelAndDots(hue = hue, sat = sat, enabled = enabled, showSelection = selected, onLive = onLive, onPick = onPick)
    }
}

/** Farebné koliesko vľavo a 8 rýchlych farieb vpravo. */
@Composable
private fun WheelAndDots(
    hue: Float,
    sat: Float,
    enabled: Boolean,
    showSelection: Boolean = true,
    onLive: ((Float, Float) -> Unit)? = null,
    onPick: (Float, Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ColorWheel(hue = hue, sat = sat, enabled = enabled, showMarker = showSelection, onPick = onPick, onLive = onLive)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            QUICK_HUES.chunked(4).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { h ->
                        val sel = showSelection && sat > 0.6f && hueDistance(h, hue) < 12f
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .then(if (sel) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .padding(if (sel) 4.dp else 2.dp)
                                .clip(CircleShape)
                                .background(rgbColor(h, 0.75f, 1f))
                                .clickable(enabled = enabled) { onPick(h, 1f) },
                        )
                    }
                }
            }
        }
    }
}

private fun hueDistance(a: Float, b: Float): Float {
    val d = kotlin.math.abs(a - b) % 360f
    return min(d, 360f - d)
}

/**
 * Farebné koliesko: uhol = odtieň, vzdialenosť od stredu = sýtosť. Počas ťahania volá [onLive] (plynulé ovládanie),
 * po pustení prsta [onPick]. Kým prst ťahá, ignoruje zmeny zvonka.
 */
@Composable
private fun ColorWheel(hue: Float, sat: Float, enabled: Boolean, showMarker: Boolean, onPick: (Float, Float) -> Unit, onLive: ((Float, Float) -> Unit)? = null) {
    var h by remember { mutableFloatStateOf(hue) }
    var s by remember { mutableFloatStateOf(sat) }
    var moved by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(hue, sat) {
        if (!dragging) {
            h = hue
            s = sat
            moved = false
        }
    }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val sweep = remember { (0..6).map { rgbColor(it * 60f, 1f, 1f) } }

    fun pick(pos: Offset): Pair<Float, Float> {
        val r = min(box.width, box.height) / 2f
        if (r <= 0f) return h to s
        val dx = pos.x - box.width / 2f
        val dy = pos.y - box.height / 2f
        var ang = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (ang < 0) ang += 360f
        return ang to (hypot(dx, dy) / r).coerceIn(0f, 1f)
    }

    Box(
        modifier = Modifier
            .size(124.dp)
            .onSizeChanged { box = it }
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { pos -> val p = pick(pos); h = p.first; s = p.second; moved = true; onPick(p.first, p.second) }
            }
            .pointerInput(enabled) {
                if (enabled) {
                    detectDragGestures(
                        onDragStart = { pos -> dragging = true; val p = pick(pos); h = p.first; s = p.second; moved = true; onLive?.invoke(h, s) },
                        onDragEnd = { dragging = false; onPick(h, s) },
                        onDragCancel = { dragging = false; onPick(h, s) },
                        onDrag = { change, _ -> val p = pick(change.position); h = p.first; s = p.second; onLive?.invoke(h, s) },
                    )
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(Brush.sweepGradient(sweep))
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f))))
            if (showMarker || moved) {
                val r = size.minDimension / 2f
                val rad = Math.toRadians(h.toDouble())
                val center = Offset(size.width / 2f + (cos(rad) * s * r).toFloat(), size.height / 2f + (sin(rad) * s * r).toFloat())
                drawCircle(Color.Black.copy(alpha = 0.45f), radius = 10.dp.toPx(), center = center, style = Stroke(width = 5.dp.toPx()))
                drawCircle(Color.White, radius = 10.dp.toPx(), center = center, style = Stroke(width = 3.dp.toPx()))
            }
        }
    }
}

/** Posuvník s názvom a hodnotou; príkaz pošle až po pustení (nezahltí zariadenie počas ťahania). */
@Composable
internal fun LabeledSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    valueText: (Float) -> String,
    onFinished: (Float) -> Unit,
    steps: Int = 0,
    onLive: ((Float) -> Unit)? = null,
) {
    var drag by remember(value) { mutableFloatStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(valueText(drag), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        WideSlider(
            value = value,
            range = range,
            enabled = enabled,
            track = amberFill(),
            fill = amberFill(),
            steps = steps,
            onChange = { drag = it; onLive?.invoke(it) },
            onFinished = onFinished,
        )
    }
}

/** Kruh svetla s viacerými zdrojmi: svieti, keď svieti hociktorý zdroj; ťuknutie vypne všetko alebo zapne hlavné svetlo. */
@Composable
fun MultiLightOrb(ml: MultiLight, values: Map<String, Any>, enabled: Boolean, onCommand: (Map<String, Any>) -> Unit) {
    val main = ml.main
    val st = LightChannels.read(ml, main, values)
    val back = ml.channels.firstOrNull { !it.main && LightChannels.read(ml, it, values).on }
    val backSt = back?.let { LightChannels.read(ml, it, values) }
    LightOrb(
        on = st.on || backSt != null,
        percent = if (st.on) st.percent else backSt?.percent ?: st.percent,
        glow = if (!st.on && backSt != null && back.hasColour) rgbColor(backSt.colour.h, max(backSt.colour.s, 0.3f), 1f) else null,
        enabled = enabled,
        // ako vypínač na dlaždici: keď svieti hocičo, vypne všetko; inak zapne hlavné svetlo
        onToggle = { onCommand(if (st.on || backSt != null) LightChannels.allOff(ml, values) else LightChannels.setOn(ml, main, true, values)) },
    )
}

/**
 * Svetlo s viacerými zdrojmi (napr. hlavné biele + farebný podhľad): každý zdroj má vlastnú kartu s vypínačom,
 * jasom a teplotou/farbou. Zdroje sa dajú zapnúť nezávisle od seba.
 */
@Composable
fun MultiLightControls(
    ml: MultiLight,
    values: Map<String, Any>,
    enabled: Boolean,
    onCommand: (Map<String, Any>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ml.channels.forEach { ch -> ChannelCard(ml, ch, values, enabled, onCommand) }
        if (ml.backend == LightBackend.MIX && ml.mixDp != null && values[ml.mixDp] == null) {
            Hint(
                "Svetlo zatiaľ neposlalo zmiešanú hodnotu (DP ${ml.mixDp}) – stav zdrojov je odhadnutý. Ak svetlo na prepínače " +
                    "nereaguje, vráť to v nastaveniach → Kanály svetla → Jedno svetlo.",
            )
        }
    }
}

@Composable
private fun ChannelCard(ml: MultiLight, ch: LightChannel, values: Map<String, Any>, enabled: Boolean, onCommand: (Map<String, Any>) -> Unit) {
    val st = LightChannels.read(ml, ch, values)
    val on = enabled && st.on
    LightSection(
        title = ch.name,
        icon = if (ch.main) Sym.light else if (ch.hasColour) Sym.palette else Sym.lightbulb,
        switchOn = st.on,
        enabled = enabled,
        onSwitch = { onCommand(LightChannels.setOn(ml, ch, it, values)) },
    ) {
        if (!st.on) Hint("Vypnuté – zapni vypínačom vpravo.")
        if (ch.hasWhite && ch.hasColour && ch.modeDp != null) {
            Segmented(
                options = listOf("Biela" to Sym.lightbulb, "Farba" to Sym.palette),
                selected = if (st.colourMode) 1 else 0,
                enabled = on,
            ) { i -> onCommand(LightChannels.mode(ch, i == 1)) }
        }
        if (ch.hasBrightness) {
            BrightnessSlider(percent = st.percent, enabled = on, onFinished = { onCommand(LightChannels.brightness(ml, ch, it, values)) }, onLive = liveOf { p: Int -> LightChannels.brightness(ml, ch, p, values) })
        }
        if (ch.hasTemp && !st.colourMode) {
            TempSlider(temp = st.temp, enabled = on, onFinished = { onCommand(LightChannels.temp(ml, ch, it, values)) }, onLive = liveOf { t: Float -> LightChannels.temp(ml, ch, t, values) })
        }
        if (ch.hasColour && (st.colourMode || ch.modeDp == null)) {
            val c = st.colour
            val v = max(c.v, 0.1f)
            WheelAndDots(hue = c.h, sat = c.s, enabled = on, onLive = liveOf2 { h, s -> LightChannels.colour(ml, ch, Hsv(h, s, v), values) }) { h, s -> onCommand(LightChannels.colour(ml, ch, Hsv(h, s, v), values)) }
        }
    }
}
