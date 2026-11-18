@file:OptIn(ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Ikona košíka (obrys ako v návrhu). */
internal val CartIcon: ImageVector by lazy {
    val line = SolidColor(Color.Black)
    ImageVector.Builder("shopping_cart", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            addPathNodes("M3 4h2.2l2.1 11h10.5l2-8H6.5"),
            stroke = line, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        .addPath(
            addPathNodes("M7.8 19a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0Z"),
            stroke = line, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        .addPath(
            addPathNodes("M15.4 19a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0Z"),
            stroke = line, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        .build()
}

/** Tenký riadok „Nákup“ na Domove: ikona, názov, koľko zostáva kúpiť a šípka. */
@Composable
internal fun ShoppingRow(items: List<ShopItem>, onClick: () -> Unit) {
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
            Icon(CartIcon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text("Nákup", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(ShoppingList.rowText(items), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Icon(Sym.chevronRight, contentDescription = null, tint = scheme.outline, modifier = Modifier.padding(start = 4.dp).size(20.dp))
        }
    }
}

/** Nákupný zoznam: pole hore, pod ním plochý zoznam; ťuknutím na položku sa odškrtne. */
@Composable
internal fun ShoppingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var items by remember { mutableStateOf(ShoppingStore.items(context)) }
    var text by rememberSaveable { mutableStateOf("") }
    val rows = ShoppingList.arrange(items)

    fun submit() {
        if (text.isBlank()) return
        items = ShoppingStore.add(context, text)
        text = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Nákup", style = MaterialTheme.typography.titleLarge)
                        Text(ShoppingList.subtitle(items), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Sym.arrowBack, contentDescription = "Späť") } },
                actions = {
                    if (items.any { it.done }) {
                        TextButton(onClick = { items = ShoppingStore.clearDone(context) }) {
                            Text("Vymazať kúpené", fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShoppingField(text = text, onChange = { text = it }, onSubmit = { submit() })
            if (rows.isEmpty()) {
                Text(
                    "Zatiaľ nič. Napíš hore, čo treba kúpiť (aj s množstvom, napr. „Mlieko 2 l“), a potvrď tlačidlom +.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = scheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, scheme.surfaceVariant),
                ) {
                    Column {
                        rows.forEachIndexed { index, item ->
                            if (index > 0) HorizontalDivider(color = scheme.surfaceVariant)
                            ShoppingItemRow(item = item, onToggle = { items = ShoppingStore.toggle(context, item.id) })
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Zaoblené pole „Čo treba kúpiť?“ s tlačidlom +; po potvrdení ostane klávesnica, aby sa dalo pridať viac vecí za sebou. */
@Composable
private fun ShoppingField(text: String, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val canAdd = text.isNotBlank()
    Surface(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 20.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = text,
                onValueChange = { onChange(it.take(ShoppingList.MAX_NAME)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text("Čo treba kúpiť?", color = scheme.onSurfaceVariant, fontSize = 16.sp)
                        inner()
                    }
                },
            )
            Surface(
                onClick = onSubmit,
                enabled = canAdd,
                modifier = Modifier.size(44.dp).alpha(if (canAdd) 1f else 0.45f),
                shape = CircleShape,
                color = scheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Sym.add, contentDescription = "Pridať do zoznamu", tint = scheme.onPrimaryContainer)
                }
            }
        }
    }
}

/** Jeden riadok zoznamu (min. 56 dp, celý je tlačidlo): krúžok + názov; kúpené je preškrtnuté a zosivené. */
@Composable
private fun ShoppingItemRow(item: ShopItem, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = item.done, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .then(if (item.done) Modifier.background(scheme.primaryContainer) else Modifier.border(2.dp, scheme.onSurfaceVariant, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (item.done) Icon(Sym.check, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            item.name,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = if (item.done) scheme.onSurfaceVariant else scheme.onSurface,
            textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None,
        )
    }
}
