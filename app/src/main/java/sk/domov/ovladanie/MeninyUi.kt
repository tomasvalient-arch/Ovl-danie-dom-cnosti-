package sk.domov.ovladanie

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

/**
 * Úzky pás medzi počasím a fázou mesiaca: dnešný deň, dátum a meniny; pod tým zajtrajšie meniny s dátumom.
 * Vzhľad rovnaký ako [MoonStrip]. [tick] sa mení pri návrate do appky a každých 5 min (rememberPresenceTick),
 * a navyše sa dátum prepíše presne o polnoci, aj keď appka beží.
 */
@Composable
internal fun MeninyStrip(tick: Int, modifier: Modifier = Modifier) {
    var dnes by remember { mutableStateOf(LocalDate.now()) }
    // pri návrate do appky / každých 5 min (tick) a presne o polnoci sa dátum prepočíta
    LaunchedEffect(tick) {
        dnes = LocalDate.now()
        while (true) {
            val zajtra = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            delay((zajtra - System.currentTimeMillis()).coerceAtLeast(1_000L) + 500L)
            dnes = LocalDate.now()
        }
    }
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
            Icon(
                Sym.calendarToday,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    Meniny.dnes(dnes),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    Meniny.zajtra(dnes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
