package sk.domov.ovladanie

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Rýchle pridanie do nákupu z widgetu (od 1.44): malé okno „Čo treba kúpiť?“ nad plochou, bez spúšťania celej appky.
 * Rovnaké pole, návrhy z histórie a „Často kupuješ“ ako na obrazovke Nákup. Po pridaní sa pole vyprázdni a ostane
 * otvorené na ďalšiu vec; zavrie sa tlačidlom Hotovo, Späť alebo ťuknutím mimo okna. Widgety sa prekreslia hneď.
 */
class ShoppingQuickAddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HomeTheme { QuickAddSheet(onClose = { finish() }) }
        }
    }
}

@Composable
private fun QuickAddSheet(onClose: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var items by remember { mutableStateOf(ShoppingStore.items(context)) }
    var history by remember { mutableStateOf(ShoppingStore.history(context)) }
    var target by remember { mutableStateOf(ShoppingStore.target(context)) }
    var text by rememberSaveable { mutableStateOf("") }
    // pole sa po každom pridaní vytvorí nanovo (prázdne); IME ozvenu práve pridaného textu ignorujeme
    var added by remember { mutableStateOf(0) }
    var justSubmitted by remember { mutableStateOf<String?>(null) }
    var last by rememberSaveable { mutableStateOf<String?>(null) }
    val targetItems = items.filter { it.list == target }
    val suggestions = ShoppingHistory.suggest(history, text, targetItems)
    val chips = if (text.isBlank()) ShoppingHistory.due(history, targetItems, System.currentTimeMillis(), limit = 6) else emptyList()

    fun addName(name: String) {
        if (name.isBlank()) return
        items = ShoppingStore.add(context, name, target)
        history = ShoppingStore.history(context)
        last = ShoppingList.clean(name)
    }

    fun submit(name: String = text) {
        if (name.isBlank()) return
        justSubmitted = name
        addName(name)
        text = ""
        added++
    }

    fun onFieldChange(incoming: String) {
        if (!ShoppingList.acceptFieldChange(incoming, justSubmitted)) return
        justSubmitted = null
        text = incoming
    }

    val outside = remember { MutableInteractionSource() }
    val inside = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = outside, indication = null, onClick = onClose)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .clickable(interactionSource = inside, indication = null, onClick = {}),
            shape = RoundedCornerShape(28.dp),
            color = scheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, scheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(CartIcon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Pridať do nákupu", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            last?.let { "Pridané: $it · ${ShoppingList.viewSubtitle(ShopView.BOTH, items)}" } ?: ShoppingList.viewSubtitle(ShopView.BOTH, items),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onClose) { Text("Hotovo", fontWeight = FontWeight.SemiBold) }
                }
                key(added) {
                    // otvorí sa rovno s kurzorom v poli a klávesnicou
                    ShoppingField(text = text, onChange = { onFieldChange(it) }, onSubmit = { submit() }, focusAfterAdd = true, placeholder = target.hint)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Pridá sa do", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    ShopListKind.entries.forEach { kind ->
                        val on = target == kind
                        Surface(
                            onClick = { target = kind; ShoppingStore.setTarget(context, kind) },
                            shape = RoundedCornerShape(16.dp),
                            color = if (on) scheme.primaryContainer else scheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, if (on) scheme.primaryContainer else scheme.surfaceVariant),
                        ) {
                            Text(
                                kind.title,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (on) scheme.onPrimaryContainer else scheme.onSurface,
                            )
                        }
                    }
                }
                if (suggestions.isNotEmpty()) {
                    ShoppingSuggestions(suggestions = suggestions, onPick = { submit(it.name) }, onForget = {
                        ShoppingStore.forgetSuggestion(context, it.name)
                        history = ShoppingStore.history(context)
                    })
                }
                if (chips.isNotEmpty()) {
                    ShoppingChips(chips = chips, onPick = { addName(it.name) }, onForget = {
                        ShoppingStore.forgetSuggestion(context, it.name)
                        history = ShoppingStore.history(context)
                    })
                }
            }
        }
    }
}
