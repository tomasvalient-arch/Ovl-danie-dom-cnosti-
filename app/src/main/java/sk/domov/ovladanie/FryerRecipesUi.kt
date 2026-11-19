package sk.domov.ovladanie

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Čo sa práve robí s receptom (určuje, ktoré okno je otvorené). */
private sealed interface RecipeAction {
    data object SaveNew : RecipeAction
    data class Rename(val recipe: FryerRecipe) : RecipeAction
    data class Overwrite(val recipe: FryerRecipe) : RecipeAction
    data class Delete(val recipe: FryerRecipe) : RecipeAction
    data class ApplyWhileRunning(val recipe: FryerRecipe) : RecipeAction
}

/**
 * Karta „Recepty“ v detaile fritézy: uloží aktuálne nastavenie (režim, čas, teplota) pod názvom a jedným ťuknutím ho
 * znova nastaví. Recept fritézu nikdy nespustí – štart je vždy zvlášť (a s potvrdením).
 */
@Composable
fun FryerRecipesCard(
    deviceId: String,
    spec: FryerSpec,
    st: FryerState,
    enabled: Boolean,
    onCommand: (Map<String, Any>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var recipes by remember(deviceId) { mutableStateOf(RecipeStore.list(context, deviceId)) }
    var action by remember { mutableStateOf<RecipeAction?>(null) }

    fun commit(list: List<FryerRecipe>) {
        recipes = list
        RecipeStore.save(context, deviceId, list)
    }

    fun applyRecipe(r: FryerRecipe) {
        val steps = FryerRecipes.steps(spec, r, st)
        if (steps.isEmpty()) {
            Toast.makeText(context, "Už je nastavené: ${r.name}", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, "Nastavujem: ${r.name}", Toast.LENGTH_SHORT).show()
        // po jednom zápise (režim môže prepísať čas a teplotu, preto ide prvý); zariadenie potrebuje chvíľu medzi príkazmi
        scope.launch {
            steps.forEachIndexed { i, step ->
                if (i > 0) delay(700)
                onCommand(step)
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Recepty", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { action = RecipeAction.SaveNew }, enabled = enabled) {
                    Icon(Sym.add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Uložiť aktuálne")
                }
            }
            if (recipes.isEmpty()) {
                Text(
                    "Ulož si obľúbenú kombináciu režimu, času a teploty (napr. hranolky, 18 min, 200 °C) " +
                        "a nastav ju jedným ťuknutím. Recept fritézu nespustí, Štart ostáva zvlášť.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                recipes.forEach { r ->
                    RecipeRow(
                        recipe = r,
                        selected = FryerRecipes.matches(r, st),
                        enabled = enabled,
                        onApply = { if (st.running) action = RecipeAction.ApplyWhileRunning(r) else applyRecipe(r) },
                        onRename = { action = RecipeAction.Rename(r) },
                        onOverwrite = { action = RecipeAction.Overwrite(r) },
                        onDelete = { action = RecipeAction.Delete(r) },
                    )
                }
            }
        }
    }

    when (val a = action) {
        null -> {}
        RecipeAction.SaveNew -> {
            val current = FryerRecipes.fromState("", "x", st)
            RecipeNameDialog(
                title = "Nový recept",
                confirmText = "Uložiť",
                initial = "",
                summary = FryerRecipes.describe(current),
                existing = recipes,
                renaming = null,
                onDismiss = { action = null },
                onConfirm = { name ->
                    commit(FryerRecipes.upsert(recipes, FryerRecipes.fromState(RecipeStore.newId(), name, st)))
                    action = null
                },
            )
        }
        is RecipeAction.Rename -> RecipeNameDialog(
            title = "Premenovať recept",
            confirmText = "Uložiť",
            initial = a.recipe.name,
            summary = FryerRecipes.describe(a.recipe),
            existing = recipes,
            renaming = a.recipe,
            onDismiss = { action = null },
            onConfirm = { name ->
                commit(FryerRecipes.rename(recipes, a.recipe.id, name))
                action = null
            },
        )
        is RecipeAction.Overwrite -> {
            val now = FryerRecipes.fromState(a.recipe.id, a.recipe.name, st)
            DomovAlert(
                onDismissRequest = { action = null },
                icon = { Icon(Sym.refresh, null) },
                title = { Text("Prepísať recept?") },
                text = {
                    Text(
                        "„${a.recipe.name}“ bude mať namiesto ${FryerRecipes.describe(a.recipe)} nové nastavenie: " +
                            FryerRecipes.describe(now) + ".",
                    )
                },
                confirmButton = {
                    DialogPrimaryButton(onClick = {
                        commit(FryerRecipes.overwrite(recipes, a.recipe.id, st))
                        action = null
                    }) { Text("Prepísať") }
                },
                dismissButton = { DialogTextButton(onClick = { action = null }) { Text("Zrušiť") } },
            )
        }
        is RecipeAction.Delete -> DomovAlert(
            onDismissRequest = { action = null },
            icon = { Icon(Sym.delete, null) },
            danger = true,
            title = { Text("Zmazať recept?") },
            text = { Text("„${a.recipe.name}“ (${FryerRecipes.describe(a.recipe)}) sa zmaže. Fritéza ostane, ako je.") },
            confirmButton = {
                DialogPrimaryButton(onClick = {
                    commit(FryerRecipes.remove(recipes, a.recipe.id))
                    action = null
                }) { Text("Zmazať") }
            },
            dismissButton = { DialogTextButton(onClick = { action = null }) { Text("Zrušiť") } },
        )
        is RecipeAction.ApplyWhileRunning -> DomovAlert(
            onDismissRequest = { action = null },
            icon = { Icon(Sym.skillet, null) },
            title = { Text("Fritéza práve varí") },
            text = {
                Text(
                    "Zmeniť jej nastavenie na „${a.recipe.name}“ (${FryerRecipes.describe(a.recipe)})? " +
                        "Varenie sa tým nezastaví ani nespustí nanovo, mení sa len režim, čas a teplota.",
                )
            },
            confirmButton = {
                DialogPrimaryButton(onClick = {
                    action = null
                    applyRecipe(a.recipe)
                }) { Text("Zmeniť") }
            },
            dismissButton = { DialogTextButton(onClick = { action = null }) { Text("Nechať") } },
        )
    }
}

@Composable
private fun RecipeRow(
    recipe: FryerRecipe,
    selected: Boolean,
    enabled: Boolean,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onOverwrite: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    var menu by remember(recipe.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) scheme.primary.copy(alpha = 0.14f) else scheme.surface.copy(alpha = 0.45f))
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.7f),
                shape = shape,
            )
            .clickable(enabled = enabled, onClick = onApply)
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(recipe.name, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
            Text(
                FryerRecipes.describe(recipe),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Box(
                modifier = Modifier.size(22.dp).clip(CircleShape).background(scheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Sym.check, contentDescription = "Nastavené", tint = scheme.onPrimary, modifier = Modifier.size(14.dp))
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Sym.moreVert, contentDescription = "Viac", tint = scheme.onSurfaceVariant)
            }
            DomovMenu(expanded = menu, onDismiss = { menu = false }) {
                DomovMenuItem("Premenovať", Sym.edit, onClick = { menu = false; onRename() })
                DomovMenuItem("Prepísať aktuálnym nastavením", Sym.refresh, enabled = enabled, onClick = { menu = false; onOverwrite() })
                DomovMenuItem("Zmazať", Sym.delete, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/** Okno s názvom receptu (nový alebo premenovanie). Nový recept s existujúcim názvom ten starý prepíše. */
@Composable
private fun RecipeNameDialog(
    title: String,
    confirmText: String,
    initial: String,
    summary: String,
    existing: List<FryerRecipe>,
    renaming: FryerRecipe?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val clean = FryerRecipes.cleanName(name)
    val clash = FryerRecipes.find(existing, clean)?.takeIf { it.id != renaming?.id }
    val full = renaming == null && clash == null && !FryerRecipes.canAdd(existing, clean)
    val error = when {
        renaming != null && clash != null -> "Recept s týmto názvom už máš."
        full -> "Máš už ${FryerRecipes.MAX_RECIPES} receptov. Niektorý najprv zmaž."
        else -> null
    }
    val note = if (renaming == null && clash != null) "Recept s týmto názvom už je, prepíše sa." else null
    val ok = clean.isNotEmpty() && error == null
    val hint = error ?: note

    DomovDialog(
        onDismiss = onDismiss,
        title = { Text(title) },
        icon = { Icon(Sym.skillet, null) },
        confirmButton = {
            DialogPrimaryButton(onClick = { onConfirm(clean) }, enabled = ok) {
                Text(if (renaming == null && clash != null) "Prepísať" else confirmText)
            }
        },
        dismissButton = { DialogTextButton(onClick = onDismiss) { Text("Zrušiť") } },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DomovTextField(
                value = name,
                onValueChange = { name = it.take(FryerRecipes.NAME_MAX) },
                label = { Text("Názov receptu") },
                singleLine = true,
                isError = error != null,
                supportingText = if (hint != null) {
                    { Text(hint) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) onConfirm(clean) }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
