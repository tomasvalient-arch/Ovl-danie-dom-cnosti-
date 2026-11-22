package sk.domov.ovladanie

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Zobrazenie pásu fázy mesiaca na Domove. Predvolene zapnuté. Súbor SharedPreferences: [PREFS]. */
object MoonStore {
    const val PREFS = "domov_moon"
    private const val KEY = "visible"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun visible(c: Context): Boolean = prefs(c).getBoolean(KEY, true)

    fun setVisible(c: Context, visible: Boolean) {
        prefs(c).edit().putBoolean(KEY, visible).apply()
    }
}

/** Aktuálna fáza pre UI; prepočíta sa pri zmene [keys] (mimo hlavného vlákna). */
@SuppressLint("ProduceStateDoesNotAssignValue") // lint nevidí priradenie cez withContext (falošné hlásenie)
@Composable
internal fun rememberMoonPhase(vararg keys: Any?): State<MoonInfo?> {
    return produceState<MoonInfo?>(null, *keys) {
        value = withContext(Dispatchers.Default) {
            runCatching { Moon.phase(System.currentTimeMillis()) }.getOrNull()
        }
    }
}

@Composable
internal fun MoonPhaseIcon(
    illumination: Double,
    lightOnRight: Boolean,
    modifier: Modifier = Modifier,
    craters: Boolean = true,
) {
    val dark = Domov.colors.dark
    val unlit = if (dark) Color(0xFF2A241F) else Color(0xFFC9B29A)
    val earthshine = if (dark) Color(0x1AFFC88C) else Color(0x1A855400)
    val lit0 = if (dark) Color(0xFFFFF6E0) else Color(0xFFFFF6E4)
    val lit1 = if (dark) Color(0xFFFFD28A) else Color(0xFFFFD089)
    val lit2 = if (dark) Color(0xFFE59A3A) else Color(0xFFE08A28)
    val rim = if (dark) Color(0x47FFC88C) else Color(0x38855600)
    val glow = if (dark) Color(0x57FFB95C) else Color(0x52F0A032)
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxR = min(cx, cy)
        val r = maxR * 0.70f
        val glowR = min(r * 1.42f, maxR - 1f)
        if (glowR > r * 1.02f) {
            drawCircle(
                brush = Brush.radialGradient(
                    0.00f to Color.Transparent,
                    0.58f to Color.Transparent,
                    0.78f to glow,
                    1.00f to Color.Transparent,
                    center = Offset(cx, cy),
                    radius = glowR,
                ),
                radius = glowR,
                center = Offset(cx, cy),
            )
        }
        drawCircle(unlit, r, Offset(cx, cy))
        drawCircle(earthshine, r, Offset(cx, cy))
        val lit = moonLitPath(cx, cy, r, illumination.toFloat(), lightOnRight)
        clipPath(lit) {
            val lx = cx + (if (lightOnRight) 1f else -1f) * r * 0.32f
            val ly = cy - r * 0.28f
            drawCircle(
                brush = Brush.radialGradient(
                    0f to lit0,
                    0.45f to lit1,
                    1f to lit2,
                    center = Offset(lx, ly),
                    radius = r * 1.15f,
                ),
                radius = r,
                center = Offset(cx, cy),
            )
            if (craters && illumination > 0.15) {
                val spots = arrayOf(
                    Triple(0.22f, -0.18f, 0.13f), Triple(-0.28f, 0.12f, 0.09f), Triple(0.08f, 0.32f, 0.07f),
                    Triple(-0.12f, -0.38f, 0.06f), Triple(0.35f, 0.22f, 0.05f), Triple(-0.38f, -0.20f, 0.08f),
                )
                for ((dx, dy, rr) in spots) {
                    drawCircle(Color(0x248A5A18), rr * r, Offset(cx + dx * r, cy + dy * r))
                }
            }
        }
        drawCircle(color = rim, radius = r, center = Offset(cx, cy), style = Stroke(width = maxOf(0.8f, r * 0.035f)))
    }
}

/** Úzky pás pod počasím: ikona, názov a %, vpravo najbližšia hlavná fáza. */
@Composable
internal fun MoonStrip(info: MoonInfo, modifier: Modifier = Modifier) {
    val tz = TimeZone.getDefault()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MoonPhaseIcon(info.illumination, info.lightOnRight, Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                info.text,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            info.next?.let { next ->
                Text(
                    next.label(tz),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun moonLitPath(cx: Float, cy: Float, r: Float, k: Float, lightOnRight: Boolean): Path {
    val dir = if (lightOnRight) 1f else -1f
    val a = (1f - 2f * k) * dir
    val path = Path()
    val oval = Rect(cx - r, cy - r, cx + r, cy + r)
    // Compose: 0° = východ (3. hodina), kladný uhol = v smere hodinových ručičiek (os y smeruje dole).
    // Z severu (−90°) na juh (90°): pri svetle sprava +180° (cez východ), pri svetle zľava −180° (cez západ).
    // Zmena smeru obráti osvetlenie: ikona by ukazovala tmavú časť namiesto svetlej (chyba do 1.34.3).
    path.arcTo(oval, -90f, if (lightOnRight) 180f else -180f, true)
    val steps = 64
    for (i in 0..steps) {
        val t = (PI / 2.0 - PI * i / steps).toFloat()
        path.lineTo(cx + a * r * cos(t), cy + r * sin(t))
    }
    path.close()
    return path
}
