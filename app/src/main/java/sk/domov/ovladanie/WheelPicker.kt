@file:OptIn(ExperimentalFoundationApi::class)

package sk.domov.ovladanie

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import kotlin.math.abs

private val ItemHeight = 44.dp
private const val VISIBLE_ITEMS = 5
private const val WRAP_REPEATS = 200

private val MONTH_NAMES = listOf(
    "január", "február", "marec", "apríl", "máj", "jún",
    "júl", "august", "september", "október", "november", "december",
)

/** O koľko položiek sa treba posunúť z [from] na [to], keď sa bubon zalamuje po [count] hodnotách (najkratšou cestou). */
private fun shortestDelta(from: Int, to: Int, count: Int): Int {
    var d = to - from
    if (d > count / 2) d -= count
    if (d < -count / 2) d += count
    return d
}

/**
 * Výber dátumu na bubnoch ako spodný panel: deň a mesiac sa zalamujú, rok nie (a nemusí byť vôbec – [withYear] = false pre
 * meniny a každoročné udalosti). Po zastavení sa dátum opraví cez [DatePick.clamp], hodnoty mimo rozsahu sú zašedené.
 * [preview] vypíše pod bubnami, čo sa vybralo; „Hotovo“ vráti vybraný dátum.
 */
@Composable
internal fun WheelDateDialog(
    title: String,
    withYear: Boolean,
    initial: LocalDate,
    yearRange: IntRange,
    minDate: LocalDate?,
    maxDate: LocalDate?,
    preview: (LocalDate) -> String,
    hint: String?,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val start = remember {
        DatePick.clamp(initial.year.coerceIn(yearRange.first, yearRange.last), initial.monthValue, initial.dayOfMonth, minDate, maxDate)
    }
    var year by remember { mutableIntStateOf(start.year) }
    var month by remember { mutableIntStateOf(start.monthValue) }
    var day by remember { mutableIntStateOf(start.dayOfMonth) }
    // zvýši sa pri každom zastavení bubna: bubon, ktorý ostal inde ako opravená hodnota, sa vráti na ňu
    var sync by remember { mutableIntStateOf(0) }

    fun settle(y: Int, m: Int, d: Int) {
        val c = DatePick.clamp(y, m, d, minDate, maxDate)
        year = c.year
        month = c.monthValue
        day = c.dayOfMonth
        sync += 1
    }

    val chosen = DatePick.clamp(year, month, day, minDate, maxDate)
    val noClick = remember { MutableInteractionSource() }
    val outsideClick = remember { MutableInteractionSource() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier.fillMaxSize().clickable(interactionSource = outsideClick, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(interactionSource = noClick, indication = null, onClick = {}),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = scheme.surfaceContainerLow,
            ) {
                Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp)) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(scheme.outlineVariant),
                    )
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                    val dayWeight = if (withYear) 0.24f else 0.4f
                    val monthWeight = if (withYear) 0.46f else 0.6f
                    Row(modifier = Modifier.fillMaxWidth()) {
                        WheelLabel("Deň", Modifier.weight(dayWeight))
                        WheelLabel("Mesiac", Modifier.weight(monthWeight))
                        if (withYear) WheelLabel("Rok", Modifier.weight(0.3f))
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(ItemHeight * VISIBLE_ITEMS)) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .height(ItemHeight)
                                .clip(RoundedCornerShape(14.dp))
                                .background(scheme.surfaceVariant.copy(alpha = 0.6f)),
                        )
                        Row(modifier = Modifier.fillMaxSize()) {
                            Wheel(
                                count = 31, selected = day - 1, wrap = true, sync = sync,
                                label = { (it + 1).toString() },
                                valid = { DatePick.isValid(year, month, it + 1, minDate, maxDate) },
                                onSettled = { settle(year, month, it + 1) },
                                modifier = Modifier.weight(dayWeight),
                            )
                            Wheel(
                                count = 12, selected = month - 1, wrap = true, sync = sync,
                                label = { MONTH_NAMES[it] },
                                valid = { DatePick.monthHasValid(year, it + 1, minDate, maxDate) },
                                onSettled = { settle(year, it + 1, day) },
                                modifier = Modifier.weight(monthWeight),
                            )
                            if (withYear) {
                                Wheel(
                                    count = yearRange.last - yearRange.first + 1, selected = year - yearRange.first, wrap = false, sync = sync,
                                    label = { (yearRange.first + it).toString() },
                                    valid = { true },
                                    onSettled = { settle(yearRange.first + it, month, day) },
                                    modifier = Modifier.weight(0.3f),
                                )
                            }
                        }
                    }
                    Text(
                        preview(chosen),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    if (hint != null) {
                        Text(
                            hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) { Text("Zrušiť") }
                        Button(onClick = { onConfirm(chosen) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Hotovo") }
                    }
                }
            }
        }
    }
}

@Composable
private fun WheelLabel(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(bottom = 4.dp),
    )
}

/**
 * Jeden bubon: zoznam, ktorý sa po pustení vždy zastaví s jednou hodnotou v strede. Hodnoty sú 0 until [count];
 * [valid] určuje, ktoré sú zašedené. Keď sa bubon zastaví na inej hodnote, než je [selected], zavolá [onSettled]
 * a rodič vráti opravenú hodnotu cez [selected] ([sync] zabezpečí, že sa bubon vráti aj vtedy, keď sa hodnota nezmenila).
 */
@Composable
private fun Wheel(
    count: Int,
    selected: Int,
    wrap: Boolean,
    sync: Int,
    label: (Int) -> String,
    valid: (Int) -> Boolean,
    onSettled: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repeats = if (wrap) WRAP_REPEATS else 1
    val total = repeats * count
    val base = if (wrap) (repeats / 2) * count else 0
    val state = rememberLazyListState(initialFirstVisibleItemIndex = base + selected.coerceIn(0, count - 1))
    val fling = rememberSnapFlingBehavior(state)
    val latestSelected by rememberUpdatedState(selected)
    val latestSettled by rememberUpdatedState(onSettled)

    // index položky v strede bubna (null, kým sa zoznam nezmeria)
    fun centered(): Int? {
        val info = state.layoutInfo
        val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
        return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index
    }

    // bubon sa zastavil: ak je v strede iná hodnota, než je vybraná, oznám ju
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val c = centered()
                if (c != null) {
                    val v = if (wrap) c % count else c
                    if (v != latestSelected) latestSettled(v)
                }
            }
        }
    }

    // vybraná hodnota sa zmenila zvonku (oprava, iný bubon): posuň bubon na ňu najkratšou cestou
    LaunchedEffect(selected, sync) {
        val c = centered()
        if (c != null && !state.isScrollInProgress) {
            val cur = if (wrap) c % count else c
            if (cur != selected) {
                val delta = if (wrap) shortestDelta(cur, selected, count) else selected - cur
                state.animateScrollToItem((c + delta).coerceIn(0, total - 1))
            }
        }
    }

    LazyColumn(
        state = state,
        flingBehavior = fling,
        contentPadding = PaddingValues(vertical = ItemHeight * 2),
        modifier = modifier.fillMaxHeight(),
    ) {
        items(total) { i ->
            val v = i % count
            val ok = valid(v)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ItemHeight)
                    .graphicsLayer {
                        // čím ďalej od stredu, tým menšia a bledšia položka (dojem valca)
                        val info = state.layoutInfo
                        val item = info.visibleItemsInfo.firstOrNull { it.index == i }
                        val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                        val dist = if (item == null) 3f else (abs(item.offset + item.size / 2f - mid) / item.size).coerceAtMost(3f)
                        val s = 1f - 0.07f * dist
                        scaleX = s
                        scaleY = s
                        alpha = (1f - 0.3f * dist).coerceAtLeast(0.2f) * (if (ok) 1f else 0.4f)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(label(v), style = MaterialTheme.typography.titleLarge, maxLines = 1)
            }
        }
    }
}
