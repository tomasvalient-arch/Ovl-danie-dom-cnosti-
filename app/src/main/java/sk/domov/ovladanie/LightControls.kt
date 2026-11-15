@file:OptIn(ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

private fun rgbColor(h: Float, s: Float, v: Float): Color {
    val c = Light.hsvToRgb(h, s, v)
    return Color(c[0], c[1], c[2])
}

private val WHITE_SWATCHES = listOf(
    0f to Color(0xFFFFB46B),
    0.25f to Color(0xFFFFD9A8),
    0.5f to Color(0xFFFFF4E5),
    0.75f to Color(0xFFE6F0FF),
    1f to Color(0xFFBFD7FF),
)

private val COLOUR_SWATCH_HUES = listOf(0f, 30f, 60f, 120f, 180f, 240f, 280f)

/**
 * Ovládanie svetla ako v Tuya appke: režim Biela/Farba, jas, teplota bielej a výber farby.
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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp).alpha(if (enabled) 1f else 0.45f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!enabled) {
                Text(
                    text = "Zapni svetlo, aby si mohol meniť jas a farby.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (canColour) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.isWhite,
                        onClick = { if (enabled) onCommand(Light.mode(spec, "white")) },
                        label = { Text("Biela") },
                    )
                    FilterChip(
                        selected = state.isColour,
                        onClick = { if (enabled) onCommand(Light.mode(spec, "colour")) },
                        label = { Text("Farba") },
                    )
                }
            }
            if (canColour && state.isColour) {
                ColourControls(spec, state, enabled, onCommand)
            } else {
                WhiteControls(spec, state, enabled, onCommand)
            }
        }
    }
}

@Composable
private fun WhiteControls(
    spec: LightSpec,
    state: LightState,
    enabled: Boolean,
    onCommand: (Map<String, Any>) -> Unit,
) {
    LabeledSlider(
        title = "Jas",
        value = state.whitePercent.toFloat(),
        range = 1f..100f,
        enabled = enabled,
        valueText = { "${it.roundToInt()} %" },
        onFinished = { onCommand(Light.whiteBrightness(spec, it.roundToInt())) },
    )
    if (spec.temp != null) {
        LabeledSlider(
            title = "Teplota svetla",
            value = state.temp,
            range = 0f..1f,
            enabled = enabled,
            valueText = { if (it < 0.34f) "Teplá" else if (it < 0.67f) "Neutrálna" else "Studená" },
            onFinished = { onCommand(Light.whiteTemp(spec, it)) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WHITE_SWATCHES.forEach { (temp, color) ->
                Swatch(color = color, enabled = enabled) { onCommand(Light.whiteTemp(spec, temp)) }
            }
        }
    }
}

@Composable
private fun ColourControls(
    spec: LightSpec,
    state: LightState,
    enabled: Boolean,
    onCommand: (Map<String, Any>) -> Unit,
) {
    val c = state.color
    val v = max(c.v, 0.1f)
    HsPicker(
        hue = c.h,
        sat = c.s,
        enabled = enabled,
        onPick = { h, s -> onCommand(Light.colour(spec, Hsv(h, s, v))) },
    )
    LabeledSlider(
        title = "Jas",
        value = c.v.coerceIn(0.01f, 1f),
        range = 0.01f..1f,
        enabled = enabled,
        valueText = { "${(it * 100).roundToInt()} %" },
        onFinished = { onCommand(Light.colour(spec, Hsv(c.h, c.s, it))) },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        COLOUR_SWATCH_HUES.forEach { hue ->
            Swatch(color = rgbColor(hue, 1f, 1f), enabled = enabled) {
                onCommand(Light.colour(spec, Hsv(hue, 1f, v)))
            }
        }
    }
}

/** Posuvník, ktorý príkaz pošle až po pustení (nezahltí zariadenie počas ťahania). */
@Composable
private fun LabeledSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    valueText: (Float) -> String,
    onFinished: (Float) -> Unit,
) {
    var drag by remember(value) { mutableStateOf(value) }
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title)
            Text(valueText(drag), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = drag,
            onValueChange = { drag = it },
            onValueChangeFinished = { onFinished(drag) },
            valueRange = range,
            enabled = enabled,
        )
    }
}

@Composable
private fun Swatch(color: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

/**
 * Plocha na výber farby: vodorovne odtieň, zvisle sýtosť (hore sýta farba, dole biela).
 * Príkaz sa pošle po pustení prsta.
 */
@Composable
private fun HsPicker(hue: Float, sat: Float, enabled: Boolean, onPick: (Float, Float) -> Unit) {
    var h by remember(hue, sat) { mutableStateOf(hue) }
    var s by remember(hue, sat) { mutableStateOf(sat) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val hueColors = remember { (0..6).map { rgbColor(it * 60f, 1f, 1f) } }

    fun pick(pos: Offset): Pair<Float, Float> {
        val w = max(1, boxSize.width).toFloat()
        val hgt = max(1, boxSize.height).toFloat()
        val newHue = (pos.x / w).coerceIn(0f, 1f) * 360f
        val newSat = 1f - (pos.y / hgt).coerceIn(0f, 1f)
        return Pair(newHue, newSat)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(16.dp))
            .onSizeChanged { boxSize = it }
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures { pos ->
                        val p = pick(pos)
                        h = p.first
                        s = p.second
                        onPick(p.first, p.second)
                    }
                }
            }
            .pointerInput(enabled) {
                if (enabled) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val p = pick(pos)
                            h = p.first
                            s = p.second
                        },
                        onDragEnd = { onPick(h, s) },
                        onDrag = { change, _ ->
                            val p = pick(change.position)
                            h = p.first
                            s = p.second
                        },
                    )
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(brush = Brush.horizontalGradient(hueColors))
            drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.White)))
            val center = Offset(h / 360f * size.width, (1f - s) * size.height)
            drawCircle(
                color = Color.Black.copy(alpha = 0.45f),
                radius = 13.dp.toPx(),
                center = center,
                style = Stroke(width = 5.dp.toPx()),
            )
            drawCircle(
                color = Color.White,
                radius = 13.dp.toPx(),
                center = center,
                style = Stroke(width = 3.dp.toPx()),
            )
        }
    }
}
