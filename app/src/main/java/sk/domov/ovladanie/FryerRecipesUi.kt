@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package sk.domov.ovladanie

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Čo sa práve robí s receptom (určuje, ktoré okno je otvorené). */
private sealed interface RecipeAction {
    data object New : RecipeAction
    data class Edit(val recipe: FryerRecipe) : RecipeAction
    data class Overwrite(val recipe: FryerRecipe) : RecipeAction
    data class Delete(val recipe: FryerRecipe) : RecipeAction
    data class ApplyWhileRunning(val recipe: FryerRecipe) : RecipeAction
}

/**
 * Karta „Recepty“ v detaile fritézy. Recept = názov + režim + čas + teplota. Dá sa vytvoriť a upraviť kedykoľvek (aj pri
 * vypnutej fritéze – nezávisle od jej stavu); nastaviť na fritéze sa dá, keď je zapnutá ([enabled]). Recept fritézu
 * nikdy nespustí – štart je vždy zvlášť (a s potvrdením).
 */
@Composable
fun FryerRecipesCard(
    deviceId: String,
    spec: FryerSpec,
    st: FryerState,
    modes: List<String>,
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
        if (!enabled) return
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
                TextButton(onClick = { action = RecipeAction.New }) {
                    Icon(Sym.add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Nový recept")
                }
            }
            if (recipes.isEmpty()) {
                Text(
                    "Ulož si obľúbenú kombináciu režimu, času a teploty (napr. hranolky, 18 min, 200 °C) " +
                        "a nastav ju jedným ťuknutím. Recept fritézu nespustí, Štart ostáva zvlášť.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!enabled) {
                Text(
                    "Recepty môžeš vytvárať a upravovať aj teraz. Na fritéze sa dajú nastaviť, keď je zapnutá " +
                        "(a si na domácej WiFi).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            recipes.forEach { r ->
                RecipeRow(
                    recipe = r,
                    selected = FryerRecipes.matches(r, st),
                    enabled = enabled,
                    onApply = { if (st.running) action = RecipeAction.ApplyWhileRunning(r) else applyRecipe(r) },
                    onEdit = { action = RecipeAction.Edit(r) },
                    onOverwrite = { action = RecipeAction.Overwrite(r) },
                    onDelete = { action = RecipeAction.Delete(r) },
                )
            }
        }
    }

    when (val a = action) {
        null -> {}
        RecipeAction.New -> RecipeEditDialog(
            editing = null,
            draft = FryerRecipes.draft(st),
            modes = modes,
            existing = recipes,
            onDismiss = { action = null },
            onConfirm = { r ->
                commit(FryerRecipes.upsert(recipes, r))
                action = null
            },
        )
        is RecipeAction.Edit -> RecipeEditDialog(
            editing = a.recipe,
            draft = a.recipe,
            modes = modes,
            existing = recipes,
            onDismiss = { action = null },
            onConfirm = { r ->
                commit(FryerRecipes.update(recipes, r))
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
    onEdit: () -> Unit,
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
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp).alpha(if (enabled) 1f else 0.6f)) {
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
                DomovMenuItem("Upraviť", Sym.edit, onClick = { menu = false; onEdit() })
                DomovMenuItem("Prepísať aktuálnym nastavením", Sym.refresh, enabled = enabled, onClick = { menu = false; onOverwrite() })
                DomovMenuItem("Zmazať", Sym.delete, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/**
 * Okno na vytvorenie (nový) alebo úpravu ([editing]) receptu: názov, režim, čas a teplota. Pri novom receptu sú hodnoty
 * predvyplnené z fritézy ([draft]), dajú sa ale zmeniť, takže funguje aj pri vypnutej fritéze. Nový recept s existujúcim
 * názvom ten starý prepíše.
 */
@Composable
private fun RecipeEditDialog(
    editing: FryerRecipe?,
    draft: FryerRecipe,
    modes: List<String>,
    existing: List<FryerRecipe>,
    onDismiss: () -> Unit,
    onConfirm: (FryerRecipe) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(draft.name) }
    var mode by remember { mutableStateOf(draft.mode) }
    var minutes by remember { mutableIntStateOf(draft.minutes.coerceIn(Fryer.TIME_MIN, Fryer.TIME_MAX)) }
    var temp by remember { mutableIntStateOf(FryerRecipes.snapTemp(draft.tempC)) }

    val clean = FryerRecipes.cleanName(name)
    val clash = FryerRecipes.find(existing, clean)?.takeIf { it.id != editing?.id }
    val full = editing == null && clash == null && !FryerRecipes.canAdd(existing, clean)
    val error = when {
        editing != null && clash != null -> "Recept s týmto názvom už máš."
        full -> "Máš už ${FryerRecipes.MAX_RECIPES} receptov. Niektorý najprv zmaž."
        else -> null
    }
    val note = if (editing == null && clash != null) "Recept s týmto názvom už je, prepíše sa." else null
    val hint = error ?: note
    val ok = clean.isNotEmpty() && error == null

    DomovDialog(
        onDismiss = onDismiss,
        title = { Text(if (editing == null) "Nový recept" else "Upraviť recept") },
        icon = { Icon(Sym.skillet, null) },
        confirmButton = {
            DialogPrimaryButton(
                onClick = {
                    onConfirm(
                        FryerRecipe(
                            id = editing?.id ?: RecipeStore.newId(),
                            name = clean,
                            mode = mode.trim(),
                            minutes = minutes,
                            tempC = temp,
                        ),
                    )
                },
                enabled = ok,
            ) { Text(if (editing == null && clash != null) "Prepísať" else "Uložiť") }
        },
        dismissButton = { DialogTextButton(onClick = onDismiss) { Text("Zrušiť") } },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            DomovTextField(
                value = mode,
                onValueChange = { mode = it.take(40) },
                label = { Text("Režim (voliteľné)") },
                singleLine = true,
                supportingText = {
                    Text(
                        if (modes.isEmpty()) "Názov presne tak, ako ho fritéza hlási (napr. Chips). Prázdne = režim sa nemení."
                        else "Ťukni na režim, ktorý appka už videla. Prázdne = režim sa nemení.",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (modes.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEach { m -> DomovChip(m, selected = m == mode.trim()) { mode = m } }
                }
            }
            EditorSlider(
                title = "Čas varenia",
                valueText = "$minutes min",
                value = minutes.toFloat(),
                range = Fryer.TIME_MIN.toFloat()..Fryer.TIME_MAX.toFloat(),
                steps = Fryer.TIME_MAX - Fryer.TIME_MIN - 1,
                onChange = { minutes = it.roundToInt().coerceIn(Fryer.TIME_MIN, Fryer.TIME_MAX) },
            )
            EditorSlider(
                title = "Teplota",
                valueText = "$temp °C",
                value = temp.toFloat(),
                range = Fryer.TEMP_MIN.toFloat()..Fryer.TEMP_MAX.toFloat(),
                steps = (Fryer.TEMP_MAX - Fryer.TEMP_MIN) / Fryer.TEMP_STEP - 1,
                onChange = { temp = FryerRecipes.snapTemp(it.roundToInt()) },
            )
            Text(
                "Recept fritézu nikdy nespustí.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditorSlider(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
        }
        WideSlider(
            value = value,
            range = range,
            enabled = true,
            track = SolidColor(scheme.surfaceVariant),
            fill = amberFill(),
            steps = steps,
            onChange = onChange,
            onFinished = {},
        )
    }
}
