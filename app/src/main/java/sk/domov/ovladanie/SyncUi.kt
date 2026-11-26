package sk.domov.ovladanie

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Stav synchronizácie domácnosti (skryté, keď domácnosť nie je aktívna). */
@Composable
fun SyncStatusRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(HouseSyncEngine.statusText(context)) }
    DisposableEffect(Unit) {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            text = HouseSyncEngine.statusText(context)
        }
        HouseSyncEngine.listen(context, l)
        onDispose { HouseSyncEngine.unlisten(context, l) }
    }
    if (text.isBlank()) return
    val scheme = MaterialTheme.colorScheme
    val failed = text.contains("zlyhala", ignoreCase = true)
    Surface(
        modifier = modifier.clickable {
            HouseSyncEngine.request(context)
            text = HouseSyncEngine.statusText(context)
        },
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        Text(
            if (failed && !text.contains("znova", ignoreCase = true)) "$text · ťukni a skús znova" else text,
            style = MaterialTheme.typography.labelMedium,
            color = if (failed) scheme.error else scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
