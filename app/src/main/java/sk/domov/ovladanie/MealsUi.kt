@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package sk.domov.ovladanie

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val WeekShape = RoundedCornerShape(16.dp)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MealsScreen(onBack: () -> Unit, startPantry: Boolean = false) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var recipes by remember { mutableStateOf(MealsStore.liveRecipes(context)) }
    var meals by remember { mutableStateOf(MealsStore.liveMeals(context)) }
    var pantry by remember { mutableStateOf(MealsStore.livePantry(context)) }
    var shop by remember { mutableStateOf(ShoppingStore.items(context)) }
    var people by remember { mutableStateOf(MealsStore.people(context)) }
    var syncText by remember { mutableStateOf(HouseSyncEngine.statusText(context)) }
    var pantryOpen by remember { mutableStateOf(startPantry) }
    var monday by remember { mutableStateOf(Meals.weekMonday(LocalDate.now())) }
    var openMeal by remember { mutableStateOf<Meals.Meal?>(null) }
    var openRecipe by remember { mutableStateOf<Meals.Recipe?>(null) }
    var editingRecipe by remember { mutableStateOf<Meals.Recipe?>(null) }
    var pickFor by remember { mutableStateOf<Pair<LocalDate, Meals.Slot>?>(null) }
    var showFill by remember { mutableStateOf(false) }
    var fillForMeal by remember { mutableStateOf<Meals.Meal?>(null) }
    var recipesOpen by remember { mutableStateOf(false) }
    var saveEditor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pickRecipeId by remember { mutableStateOf<String?>(null) }

    fun reload() {
        recipes = MealsStore.liveRecipes(context)
        meals = MealsStore.liveMeals(context)
        pantry = MealsStore.livePantry(context)
        shop = ShoppingStore.items(context)
        people = MealsStore.people(context)
        syncText = HouseSyncEngine.statusText(context)
        openMeal = openMeal?.let { id -> meals.firstOrNull { it.id == id.id } }
        openRecipe = openRecipe?.let { r -> recipes.firstOrNull { it.id == r.id } }
    }

    DisposableEffect(Unit) {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reload() }
        MealsStore.listen(context, l)
        ShoppingStore.listen(context, l)
        HouseSyncEngine.listen(context, l)
        onDispose {
            MealsStore.unlisten(context, l)
            ShoppingStore.unlisten(context, l)
            HouseSyncEngine.unlisten(context, l)
        }
    }

    val names = people.associate { it.id to it.name }
    val today = LocalDate.now()
    val fillFrom = today
    val fillTo = Meals.fillUntil(today)
    val plan = remember(meals, recipes, pantry, shop, today) {
        Meals.planShopping(fillFrom, fillTo, meals, recipes, pantry, shop, ShopListKind.SHARED)
    }
    val missingCount = plan.count { it.kind == Meals.NeedKind.MISSING }

    fun goBack() {
        when {
            editingRecipe != null -> editingRecipe = null
            openRecipe != null -> { openRecipe = null; openMeal = null }
            pantryOpen && !startPantry -> pantryOpen = false
            recipesOpen -> recipesOpen = false
            else -> onBack()
        }
    }

    fun startNewRecipe() {
        editingRecipe = Meals.Recipe(id = Meals.newRecipeId(), name = "", portions = 4)
    }

    val title = when {
        editingRecipe != null -> if (editingRecipe!!.id.isEmpty() || recipes.none { it.id == editingRecipe!!.id }) "Nový recept" else "Upraviť recept"
        openRecipe != null -> "Recept"
        pantryOpen -> "Zásoby"
        recipesOpen -> "Recepty"
        else -> "Jedálniček"
    }
    val subtitle = when {
        openRecipe != null && openMeal != null -> {
            val d = runCatching { LocalDate.parse(openMeal!!.date) }.getOrNull()
            val day = d?.let { it.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("sk")).replaceFirstChar { c -> c.titlecase(Locale("sk")) } } ?: openMeal!!.date
            "$day · ${openMeal!!.slot.title.lowercase()}"
        }
        pantryOpen -> "Čo máte doma"
        recipesOpen -> "Všetky uložené recepty"
        editingRecipe != null -> null
        else -> {
            val who = people.joinToString(", ") { it.name }
            if (who.isBlank()) "Plán raňajok, obedov a večerí" else "Spoločný · $who"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!subtitle.isNullOrBlank()) {
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = { goBack() }) { Icon(Sym.arrowBack, contentDescription = "Späť") } },
                actions = {
                    if (!pantryOpen && openRecipe == null && editingRecipe == null && !recipesOpen) {
                        TextButton(onClick = { recipesOpen = true }) {
                            Text("Recepty", fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(onClick = { pantryOpen = true }) {
                            Text("Zásoby", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (recipesOpen && openRecipe == null && editingRecipe == null) {
                        TextButton(onClick = { startNewRecipe() }) {
                            Text("Nový", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (openRecipe != null && editingRecipe == null) {
                        TextButton(onClick = { editingRecipe = openRecipe }) {
                            Text("Upraviť", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (editingRecipe != null) {
                        TextButton(onClick = { saveEditor?.invoke() }) {
                            Text("Uložiť", fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                editingRecipe != null -> RecipeEditor(
                    initial = editingRecipe!!,
                    isNew = recipes.none { it.id == editingRecipe!!.id },
                    onCancel = { editingRecipe = null },
                    onRegisterSave = { saveEditor = it },
                    onSave = { rec ->
                        val list = MealsStore.saveRecipe(context, rec)
                        val saved = list.firstOrNull { it.id == rec.id } ?: rec
                        editingRecipe = null
                        if (pickFor != null) {
                            // späť do hárku s práve uloženým receptom; do plánu ho dá Uložiť do plánu
                            pickRecipeId = saved.id
                            openRecipe = null
                        } else {
                            openRecipe = saved
                        }
                        reload()
                    },
                    onDelete = {
                        val id = editingRecipe!!.id
                        MealsStore.deleteRecipe(context, id)
                        if (openRecipe?.id == id) openRecipe = null
                        if (pickRecipeId == id) pickRecipeId = null
                        editingRecipe = null
                        reload()
                    },
                )
                openRecipe != null -> RecipeDetail(
                    recipe = openRecipe!!,
                    meal = openMeal,
                    pantry = pantry,
                    names = names,
                    onPortions = { p ->
                        val meal = openMeal
                        if (meal != null) {
                            MealsStore.saveMeal(context, meal.copy(portions = p, cooked = false))
                        } else {
                            MealsStore.saveRecipe(context, openRecipe!!.copy(portions = p))
                        }
                        reload()
                    },
                    onCooked = {
                        val meal = openMeal ?: return@RecipeDetail
                        if (MealsStore.markCooked(context, meal)) {
                            reload()
                            scope.launch { snackbar.showSnackbar("Odrátané zo zásob") }
                        }
                    },
                    onFill = {
                        fillForMeal = openMeal
                        showFill = true
                    },
                    onRemove = {
                        openMeal?.let { MealsStore.deleteMeal(context, it.id) }
                        openMeal = null
                        openRecipe = null
                        reload()
                    },
                )
                recipesOpen -> RecipesPage(
                    recipes = recipes,
                    onOpen = { openMeal = null; openRecipe = it },
                    onEdit = { editingRecipe = it },
                    onNew = { startNewRecipe() },
                )
                pantryOpen -> PantryPage(
                    items = pantry,
                    today = today,
                    onBump = { id, dir -> MealsStore.bumpPantry(context, id, dir); reload() },
                    onSave = { MealsStore.savePantry(context, it); reload() },
                    onDelete = { MealsStore.deletePantry(context, it); reload() },
                )
                else -> WeekPage(
                    monday = monday,
                    today = today,
                    meals = meals,
                    recipes = recipes,
                    names = names,
                    syncText = syncText,
                    missingCount = missingCount,
                    onPrev = { monday = monday.minusDays(7) },
                    onNext = { monday = monday.plusDays(7) },
                    onOpen = { meal, recipe -> openMeal = meal; openRecipe = recipe },
                    onPick = { day, slot -> pickFor = day to slot },
                    onFill = { fillForMeal = null; showFill = true },
                )
            }
        }
    }

    pickFor?.let { (day, slot) ->
        if (editingRecipe == null) {
            val currentMeal = Meals.mealOn(meals, day, slot)
            MealPickerSheet(
                day = day,
                slot = slot,
                recipes = recipes,
                people = people,
                current = currentMeal,
                preselectId = pickRecipeId ?: currentMeal?.recipeId,
                onDismiss = { pickFor = null; pickRecipeId = null },
                onNew = { startNewRecipe() },
                onEdit = { editingRecipe = it },
                onPick = { recipe, cooks, portions ->
                    MealsStore.saveMeal(
                        context,
                        Meals.Meal(
                            id = Meals.mealId(day.toString(), slot),
                            date = day.toString(),
                            slot = slot,
                            recipeId = recipe.id,
                            title = recipe.name,
                            cookIds = cooks,
                            portions = portions,
                        ),
                    )
                    pickFor = null
                    pickRecipeId = null
                    reload()
                },
            )
        }
    }

    if (showFill) {
        val focusMeal = fillForMeal
        val needs = if (focusMeal != null) {
            val d = runCatching { LocalDate.parse(focusMeal.date) }.getOrDefault(today)
            Meals.planShopping(d, d, listOf(focusMeal), recipes, pantry, shop, ShopListKind.SHARED)
        } else {
            Meals.planShopping(fillFrom, fillTo, meals, recipes, pantry, shop, ShopListKind.SHARED)
        }
        FillShopSheet(
            needs = needs,
            onDismiss = { showFill = false; fillForMeal = null },
            onAdd = { items, target ->
                for (n in items) ShoppingStore.add(context, n.shopText(), target)
                showFill = false
                fillForMeal = null
                reload()
                val dest = if (target == ShopListKind.SHARED) "spoločného" else "môjho"
                scope.launch { snackbar.showSnackbar("Pridané do $dest: ${items.size}") }
            },
        )
    }
}

@Composable
private fun WeekPage(
    monday: LocalDate,
    today: LocalDate,
    meals: List<Meals.Meal>,
    recipes: List<Meals.Recipe>,
    names: Map<String, String>,
    syncText: String,
    missingCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpen: (Meals.Meal, Meals.Recipe) -> Unit,
    onPick: (LocalDate, Meals.Slot) -> Unit,
    onFill: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val rel = when (monday) {
        Meals.weekMonday(today) -> "Tento týždeň"
        Meals.weekMonday(today).minusDays(7) -> "Minulý týždeň"
        Meals.weekMonday(today).plusDays(7) -> "Budúci týždeň"
        else -> null
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev) { Icon(Sym.arrowBack, contentDescription = "Predošlý týždeň") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Meals.formatWeekRange(monday), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (rel != null) Text(rel, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            IconButton(onClick = onNext) { Icon(Sym.chevronRight, contentDescription = "Ďalší týždeň") }
        }
        if (syncText.isNotBlank()) {
            Text(syncText, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 2.dp, bottom = 0.dp)) {
            Meals.Slot.PLAN.forEach { slot ->
                Text(slot.shortTitle, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
        (0..6).forEach { i ->
            val day = monday.plusDays(i.toLong())
            DayRow(
                day = day,
                today = today,
                meals = meals,
                recipes = recipes,
                names = names,
                onOpen = onOpen,
                onPick = onPick,
            )
        }
        val cta = if (missingCount > 0) "Doplniť nákup · chýba $missingCount" else "Doplniť nákup"
        MealsCta(cta, icon = CartIcon, onClick = onFill)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DayRow(
    day: LocalDate,
    today: LocalDate,
    meals: List<Meals.Meal>,
    recipes: List<Meals.Recipe>,
    names: Map<String, String>,
    onOpen: (Meals.Meal, Meals.Recipe) -> Unit,
    onPick: (LocalDate, Meals.Slot) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val isToday = day == today
    Row(
        Modifier
            .fillMaxWidth()
            .clip(WeekShape)
            .then(if (isToday) Modifier.border(1.dp, scheme.primary, WeekShape).background(scheme.surfaceContainerHigh) else Modifier)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.width(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Meals.dayLabel(day), style = MaterialTheme.typography.labelSmall, color = if (isToday) scheme.primary else scheme.onSurfaceVariant)
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isToday) scheme.primary else scheme.onSurface,
            )
        }
        Meals.Slot.PLAN.forEach { slot ->
            MealCell(Modifier.weight(1f), Meals.mealOn(meals, day, slot), recipes, names, { onPick(day, slot) }, onOpen)
        }
    }
}

@Composable
private fun MealCell(
    modifier: Modifier,
    meal: Meals.Meal?,
    recipes: List<Meals.Recipe>,
    names: Map<String, String>,
    onPick: () -> Unit,
    onOpen: (Meals.Meal, Meals.Recipe) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val recipe = meal?.let { m -> recipes.firstOrNull { it.id == m.recipeId } }
    Surface(
        onClick = {
            if (meal != null && recipe != null) onOpen(meal, recipe)
            else onPick()
        },
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 5.dp, vertical = 5.dp), verticalArrangement = Arrangement.Center) {
            if (meal == null || recipe == null) {
                Text("+ Pridať", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            } else {
                Text(
                    meal.title.ifBlank { recipe.name },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (meal.cooked) scheme.onSurfaceVariant else scheme.onSurface,
                )
                val who = Meals.cookLabel(meal.cookIds, names)
                if (who.isNotBlank()) {
                    Text(if (meal.cooked) "Uvarené · $who" else who, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else if (meal.cooked) {
                    Text("Uvarené", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RecipeDetail(
    recipe: Meals.Recipe,
    meal: Meals.Meal?,
    pantry: List<Meals.PantryItem>,
    names: Map<String, String>,
    onPortions: (Int) -> Unit,
    onCooked: () -> Unit,
    onFill: () -> Unit,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val portions = meal?.let { Meals.portionsOf(it, recipe) } ?: recipe.portions
    val scaled = Meals.scaledIngredients(recipe, portions)
    data class Row(val ing: Meals.Ingredient, val have: Boolean, val note: String)
    val fuzzy = scaled.filter { Meals.isFuzzy(it.unit) }
    val rows = scaled.filter { !Meals.isFuzzy(it.unit) }.map { ing ->
        if (Meals.isStaple(ing.name)) return@map Row(ing, true, "vždy doma")
        val stock = pantry.firstOrNull { Meals.fold(it.name) == Meals.fold(ing.name) }
        when {
            stock?.always == true -> Row(ing, true, "vždy doma")
            stock == null -> Row(ing, false, Meals.formatQty(ing.amount, ing.unit))
            !Meals.canMerge(stock.unit, ing.unit) -> Row(ing, false, Meals.formatQty(ing.amount, ing.unit))
            Meals.enough(stock.amount, stock.unit, ing.amount, ing.unit) ->
                Row(ing, true, Meals.formatQty(stock.amount, stock.unit))
            else -> Row(ing, false, Meals.formatQty(ing.amount, ing.unit))
        }
    }
    val cooked = meal?.cooked == true
    val have = if (cooked) emptyList() else rows.filter { it.have }
    val miss = if (cooked) emptyList() else rows.filter { !it.have }
    val used = if (cooked) rows else emptyList()
    val who = meal?.let { Meals.cookLabel(it.cookIds, names) }.orEmpty()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(recipe.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        val sub = buildString {
            if (cooked) append("Uvarené")
            if (who.isNotBlank()) { if (isNotEmpty()) append(" · "); append("Varí $who") }
            if (meal != null) { if (isNotEmpty()) append(" · "); append("z jedálnička") }
        }
        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        DomovCard(padding = 12.dp, spacing = 8.dp) {
            if (meal != null) {
                Text(
                    "Recept je pre ${Meals.portionsText(recipe.portions)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Varím pre:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Stepper(portions, onChange = { onPortions((portions + it).coerceIn(1, 24)) })
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recept je pre", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Stepper(portions, onChange = { onPortions((portions + it).coerceIn(1, 24)) })
                }
            }
        }
        if (used.isNotEmpty()) {
            SectionLabel("Použité zo zásob · ${used.size}")
            DomovCard(padding = 4.dp, spacing = 0.dp) {
                used.forEach { IngLine(it.ing.name, Meals.formatQty(it.ing.amount, it.ing.unit), have = true) }
            }
        }
        if (have.isNotEmpty()) {
            SectionLabel("Máme doma · ${have.size}")
            DomovCard(padding = 4.dp, spacing = 0.dp) {
                have.forEach { IngLine(it.ing.name, it.note, have = true) }
            }
        }
        if (miss.isNotEmpty()) {
            SectionLabel("Chýba · ${miss.size}")
            DomovCard(padding = 4.dp, spacing = 0.dp) {
                miss.forEach { IngLine(it.ing.name, it.note, have = false) }
            }
        }
        if (fuzzy.isNotEmpty()) {
            SectionLabel("Dochucenie")
            DomovCard(padding = 4.dp, spacing = 0.dp) {
                fuzzy.forEach { IngLine(it.name, Meals.formatQty(it.amount, it.unit), have = true) }
            }
        }
        if (rows.isEmpty() && fuzzy.isEmpty()) {
            Text("V recepte zatiaľ nie sú suroviny. Ťukni na Upraviť.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (meal != null && !meal.cooked) {
                MealsCta("Uvarené", icon = Sym.skillet, ghost = true, modifier = Modifier.weight(1f), onClick = onCooked)
            }
            if (miss.isNotEmpty()) {
                MealsCta("Doplniť chýbajúce", icon = CartIcon, modifier = Modifier.weight(1f), onClick = onFill)
            }
        }
        if (meal != null) {
            TextButton(onClick = onRemove, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Odstrániť z plánu", color = scheme.error)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun IngLine(name: String, note: String, have: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (have) Sym.check else Sym.close,
            contentDescription = null,
            tint = if (have) scheme.primary else scheme.error,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = if (have) scheme.onSurfaceVariant else scheme.primary)
    }
}

@Composable
private fun RecipeEditor(
    initial: Meals.Recipe,
    isNew: Boolean,
    onCancel: () -> Unit,
    onSave: (Meals.Recipe) -> Unit,
    onDelete: (() -> Unit)? = null,
    onRegisterSave: ((() -> Unit)?) -> Unit = {},
) {
    var name by remember { mutableStateOf(initial.name) }
    var portions by remember { mutableIntStateOf(initial.portions.coerceAtLeast(1)) }
    var ings by remember { mutableStateOf(initial.ingredients.ifEmpty { listOf(Meals.Ingredient("", 1.0, "ks")) }) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    fun cleanIngredients(): List<Meals.Ingredient> = ings.mapNotNull {
        val n = it.name.trim()
        if (n.isEmpty()) null
        else if (Meals.isFuzzy(it.unit)) it.copy(name = n, amount = 0.0, unit = Meals.fuzzyLabel(it.unit) ?: it.unit)
        else it.copy(name = n, amount = if (it.amount <= 0.0) 1.0 else it.amount, unit = Meals.canonicalUnit(it.unit))
    }
    fun commit() {
        if (name.isBlank()) return
        onSave(initial.copy(name = name.trim(), portions = portions, ingredients = cleanIngredients()))
    }
    LaunchedEffect(name, portions, ings) {
        onRegisterSave { commit() }
    }
    DisposableEffect(Unit) {
        onDispose { onRegisterSave(null) }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DomovTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Názov") },
                singleLine = true,
                supportingText = if (name.isBlank()) ({ Text("Zadaj názov receptu") }) else null,
                modifier = Modifier.fillMaxWidth(),
            )
            DomovCard(padding = 12.dp, spacing = 0.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recept je pre", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Stepper(portions, onChange = { portions = (portions + it).coerceIn(1, 24) })
                }
            }
            SectionLabel("Suroviny")
            ings.forEachIndexed { i, ing ->
                DomovCard(padding = 10.dp, spacing = 8.dp) {
                    DomovTextField(value = ing.name, onValueChange = { v -> ings = ings.toMutableList().also { it[i] = ing.copy(name = v) } }, label = { Text("Surovina") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (!Meals.isFuzzy(ing.unit)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DomovTextField(
                                value = Meals.fmtNum(ing.amount),
                                onValueChange = { v ->
                                    val n = Meals.parseNumber(v)
                                    if (n > 0.0 || v.isBlank()) ings = ings.toMutableList().also { it[i] = ing.copy(amount = n.coerceAtLeast(0.0).let { a -> if (a == 0.0 && v.isNotBlank()) ing.amount else if (v.isBlank()) 0.0 else a }) }
                                },
                                label = { Text("Množstvo") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { ings = ings.toMutableList().also { it.removeAt(i) }.ifEmpty { listOf(Meals.Ingredient("", 1.0, "ks")) } }) {
                                Text("Odstrániť", color = scheme.error)
                            }
                        }
                    } else {
                        TextButton(onClick = { ings = ings.toMutableList().also { it.removeAt(i) }.ifEmpty { listOf(Meals.Ingredient("", 1.0, "ks")) } }) {
                            Text("Odstrániť", color = scheme.error)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        (listOf("ks", "g", "kg", "ml", "l") + Meals.FUZZY_UNITS).forEach { u ->
                            val selected = if (Meals.isFuzzy(u)) Meals.fuzzyLabel(ing.unit) == u else !Meals.isFuzzy(ing.unit) && Meals.canonicalUnit(ing.unit) == u
                            FilterChip(selected = selected, onClick = { ings = ings.toMutableList().also { it[i] = ing.copy(unit = u, amount = if (Meals.isFuzzy(u)) 0.0 else if (ing.amount > 0.0) ing.amount else 1.0) } }, label = { Text(u) })
                        }
                    }
                }
            }
            TextButton(onClick = { ings = ings + Meals.Ingredient("", 1.0, "ks") }) { Text("Pridať surovinu") }
            if (!isNew && onDelete != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Odstrániť recept", color = scheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmDelete && onDelete != null) {
        DomovAlert(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Sym.delete, null) },
            title = { Text("Odstrániť recept?") },
            text = {
                Text(
                    if (initial.name.isBlank()) "Recept sa odstráni zo zoznamu. Jedlá v pláne, ktoré ho používajú, ostanú bez receptu."
                    else "„${initial.name}“ sa odstráni zo zoznamu. Jedlá v pláne, ktoré ho používajú, ostanú bez receptu.",
                )
            },
            confirmButton = {
                DialogPrimaryButton(onClick = { confirmDelete = false; onDelete() }) { Text("Odstrániť") }
            },
            dismissButton = { DialogTextButton(onClick = { confirmDelete = false }) { Text("Zrušiť") } },
        )
    }
}

@Composable
private fun PantryPage(
    items: List<Meals.PantryItem>,
    today: LocalDate,
    onBump: (String, Int) -> Unit,
    onSave: (Meals.PantryItem) -> Unit,
    onDelete: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var filter by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Meals.PantryItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    val labels = listOf("Všetko", "Málo", "Končí", "Vždy doma")
    val shown = items.filter { item ->
        when (filter) {
            1 -> Meals.isLow(item)
            2 -> Meals.isExpiring(item, today)
            3 -> item.always
            else -> true
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            labels.forEachIndexed { i, lab ->
                FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(lab) })
            }
        }
        if (shown.isEmpty()) {
            Text(
                when (filter) {
                    1 -> "Nič nie je označené ako málo."
                    2 -> "Nič čoskoro nekončí."
                    3 -> "Žiadna surovina nie je označená ako vždy doma."
                    else -> "Zásoby sú prázdne. Odškrtnuté položky z nákupu sa sem pridajú samy, alebo ich pridaj ručne."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            DomovCard(padding = 4.dp, spacing = 0.dp) {
                shown.forEach { item ->
                    PantryLine(item, today, onBump = { onBump(item.id, it) }, onClick = { editing = item })
                }
            }
        }
        MealsCta("Pridať do zásob", icon = Sym.add, ghost = true, onClick = { adding = true })
        Spacer(Modifier.height(16.dp))
    }
    if (editing != null || adding) {
        PantryEditDialog(
            initial = editing,
            onDismiss = { editing = null; adding = false },
            onSave = { onSave(it); editing = null; adding = false },
            onDelete = editing?.let { item -> { onDelete(item.id); editing = null } },
        )
    }
}

@Composable
private fun PantryLine(item: Meals.PantryItem, today: LocalDate, onBump: (Int) -> Unit, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.always) Flag("vždy doma", scheme.tertiary)
                if (Meals.isLow(item)) Flag("málo", scheme.error)
                if (Meals.isExpiring(item, today)) {
                    val exp = item.expires?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    Flag(exp?.let { "do ${Meals.formatDate(it)}" } ?: "končí", scheme.primary)
                }
            }
        }
        if (!item.always) {
            Stepper(item.amount, item.unit, onChange = onBump)
        }
    }
}

@Composable
private fun Flag(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun PantryEditDialog(
    initial: Meals.PantryItem?,
    onDismiss: () -> Unit,
    onSave: (Meals.PantryItem) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var amount by remember { mutableStateOf(initial?.let { Meals.fmtNum(it.amount) } ?: "1") }
    var unit by remember { mutableStateOf(initial?.unit ?: "ks") }
    var always by remember { mutableStateOf(initial?.always == true) }
    var expires by remember { mutableStateOf(initial?.expires) }
    var pickExp by remember { mutableStateOf(false) }
    DomovAlert(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nová zásoba" else "Zásoba") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DomovTextField(value = name, onValueChange = { name = it }, label = { Text("Názov") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!always) {
                    DomovTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("Množstvo") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("ks", "g", "kg", "ml", "l").forEach { u ->
                            FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u) })
                        }
                    }
                }
                CheckRow("Vždy doma", "Do nákupu sa nepridáva", always) { always = it }
                TextButton(onClick = { pickExp = true }) {
                    Text(expires?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull()?.let { "Spotreba do ${Meals.formatDateYear(it)}" } } ?: "Pridať dátum spotreby")
                }
                if (expires != null) TextButton(onClick = { expires = null }) { Text("Zrušiť dátum spotreby") }
            }
        },
        confirmButton = {
            DialogPrimaryButton(onClick = {
                if (name.isBlank()) return@DialogPrimaryButton
                val parsed = Meals.parseNumber(amount).let { if (it <= 0.0) 1.0 else it }
                onSave(
                    (initial ?: Meals.PantryItem(id = "", name = name.trim())).copy(
                        name = name.trim(),
                        amount = if (always) initial?.amount ?: 0.0 else parsed,
                        unit = unit,
                        always = always,
                        expires = expires,
                    ),
                )
            }, enabled = name.isNotBlank()) { Text("Uložiť") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) DialogTextButton(onClick = onDelete) { Text("Odstrániť") }
                DialogTextButton(onClick = onDismiss) { Text("Zrušiť") }
            }
        },
    )
    if (pickExp) {
        val start = expires?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now().plusDays(3)
        WheelDateDialog(
            title = "Dátum spotreby",
            withYear = true,
            initial = start,
            yearRange = (LocalDate.now().year)..(LocalDate.now().year + 3),
            minDate = LocalDate.now(),
            maxDate = LocalDate.now().plusYears(3),
            preview = { Meals.formatDateYear(it) },
            hint = null,
            onConfirm = { expires = it.toString(); pickExp = false },
            onDismiss = { pickExp = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MealPickerSheet(
    day: LocalDate,
    slot: Meals.Slot,
    recipes: List<Meals.Recipe>,
    people: List<Calendar.HousePerson>,
    current: Meals.Meal?,
    preselectId: String? = null,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onEdit: (Meals.Recipe) -> Unit,
    onPick: (Meals.Recipe, List<String>, Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun afterHide(block: () -> Unit) {
        scope.launch {
            runCatching { sheetState.hide() }
            block()
        }
    }
    var selected by remember { mutableStateOf(preselectId ?: current?.recipeId) }
    var cooks by remember { mutableStateOf(current?.cookIds ?: emptyList()) }
    var portions by remember {
        mutableIntStateOf(
            current?.portions?.takeIf { it > 0 && current.recipeId == (preselectId ?: current.recipeId) }
                ?: recipes.firstOrNull { it.id == (preselectId ?: current?.recipeId) }?.portions
                ?: 4,
        )
    }
    val rec = recipes.firstOrNull { it.id == selected }
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxH)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("${Meals.dayLabel(day)} ${Meals.formatDate(day)} · ${slot.title}", style = MaterialTheme.typography.titleLarge)
                if (people.isNotEmpty()) {
                    Text("Kto varí", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        people.forEach { p ->
                            val on = p.id in cooks
                            FilterChip(selected = on, onClick = { cooks = if (on) cooks - p.id else cooks + p.id }, label = { Text(p.name) })
                        }
                    }
                }
                Text("Recept", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                if (recipes.isEmpty()) {
                    Text("Zatiaľ žiadny recept.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                } else {
                    recipes.forEach { r ->
                        val on = r.id == selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (on) scheme.primaryContainer else scheme.surfaceContainerLow)
                                .combinedClickable(
                                    onClick = {
                                        selected = r.id
                                        if (current?.recipeId != r.id) portions = r.portions
                                    },
                                    onLongClick = { afterHide { onEdit(r) } },
                                )
                                .padding(start = 14.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                r.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                            )
                            IconButton(onClick = { afterHide { onEdit(r) } }) {
                                Icon(Sym.edit, contentDescription = "Upraviť recept", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                TextButton(onClick = { afterHide(onNew) }) { Text("Nový recept") }
                if (rec != null) {
                    Text(
                        "Recept je pre ${Meals.portionsText(rec.portions)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Varím pre:", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Stepper(portions, onChange = { portions = (portions + it).coerceIn(1, 24) })
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            if (rec != null) {
                MealsCta(
                    "Uložiť do plánu",
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp),
                    onClick = { afterHide { onPick(rec, cooks, portions) } },
                )
            } else {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun RecipesPage(
    recipes: List<Meals.Recipe>,
    onOpen: (Meals.Recipe) -> Unit,
    onEdit: (Meals.Recipe) -> Unit,
    onNew: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (recipes.isEmpty()) {
            Text("Zatiaľ žiadny recept. Pridaj ho tlačidlom Nový.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            MealsCta("Nový recept", onClick = onNew)
        } else {
            recipes.forEach { r ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .combinedClickable(onClick = { onOpen(r) }, onLongClick = { onEdit(r) }),
                    shape = RoundedCornerShape(16.dp),
                    color = scheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, scheme.surfaceVariant),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            val n = r.ingredients.size
                            val cap = buildString {
                                append("Recept je pre ${Meals.portionsText(r.portions)}")
                                if (n == 0) append(" · bez surovín")
                                else append(" · $n " + if (n == 1) "surovina" else if (n < 5) "suroviny" else "surovín")
                            }
                            Text(cap, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onEdit(r) }) {
                            Icon(Sym.edit, contentDescription = "Upraviť recept")
                        }
                    }
                }
            }
            TextButton(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("Nový recept") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FillShopSheet(
    needs: List<Meals.ShopNeed>,
    onDismiss: () -> Unit,
    onAdd: (List<Meals.ShopNeed>, ShopListKind) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var target by remember { mutableStateOf(ShopListKind.SHARED) }
    var checked by remember {
        mutableStateOf(needs.filter { it.kind != Meals.NeedKind.ALREADY }.associate { it.shopText() to (it.kind == Meals.NeedKind.MISSING) }.toMutableMap())
    }
    val missing = needs.filter { it.kind == Meals.NeedKind.MISSING }
    val already = needs.filter { it.kind == Meals.NeedKind.ALREADY }
    val ask = needs.filter { it.kind == Meals.NeedKind.ASK }
    val selected = needs.filter { it.kind != Meals.NeedKind.ALREADY && checked[it.shopText()] == true }
    val dest = if (target == ShopListKind.SHARED) "spoločného" else "môjho"
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Pridať do nákupu", style = MaterialTheme.typography.titleLarge)
            Text("Súčet chýbajúceho od dnes do nedele", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Segmented(
                options = listOf("Môj" to null, "Spoločný" to null),
                selected = if (target == ShopListKind.MINE) 0 else 1,
                onSelect = { target = if (it == 0) ShopListKind.MINE else ShopListKind.SHARED },
            )
            if (missing.isEmpty() && ask.isEmpty() && already.isEmpty()) {
                Text("Na tento týždeň nič nechýba — alebo ešte nie je v pláne žiadne jedlo.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            if (missing.isNotEmpty()) NeedSection("Chýba · ${missing.size}", missing, checked, { k, v -> checked = checked.toMutableMap().also { it[k] = v } }, toggleable = true)
            if (already.isNotEmpty()) NeedSection("Už v zozname", already, emptyMap(), { _, _ -> }, toggleable = false, caption = { "Už v ${if (target == ShopListKind.SHARED) "spoločnom" else "mojom"} zozname" })
            if (ask.isNotEmpty()) NeedSection("Overiť", ask, checked, { k, v -> checked = checked.toMutableMap().also { it[k] = v } }, toggleable = true, caption = { "Nie je v zásobách · overiť" })
            MealsCta(
                if (selected.isEmpty()) "Nič na pridanie" else "Pridať ${selected.size} do $dest",
                icon = CartIcon,
                enabled = selected.isNotEmpty(),
                onClick = { onAdd(selected, target) },
            )
        }
    }
}

@Composable
private fun NeedSection(
    title: String,
    items: List<Meals.ShopNeed>,
    checked: Map<String, Boolean>,
    onCheck: (String, Boolean) -> Unit,
    toggleable: Boolean,
    caption: (Meals.ShopNeed) -> String = { it.sources.joinToString(" · ") },
) {
    SectionLabel(title)
    DomovCard(padding = 4.dp, spacing = 0.dp) {
        items.forEach { n ->
            val key = n.shopText()
            val on = checked[key] == true
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = toggleable) { onCheck(key, !on) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (toggleable) {
                    Icon(
                        if (on) Sym.check else Sym.add,
                        contentDescription = null,
                        tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(n.shopText(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    val cap = caption(n)
                    if (cap.isNotBlank()) Text(cap, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Stepper(value: Int, onChange: (Int) -> Unit) {
    Stepper(value.toDouble(), "ks", format = { it.toInt().toString() }, onChange = onChange)
}

@Composable
private fun Stepper(amount: Double, unit: String, format: (Double) -> String = { Meals.formatQty(it, unit) }, onChange: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = { onChange(-1) }, modifier = Modifier.size(32.dp)) {
            Icon(Sym.remove, contentDescription = "Menej", modifier = Modifier.size(16.dp))
        }
        Text(format(amount), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.widthInStepper())
        IconButton(onClick = { onChange(1) }, modifier = Modifier.size(32.dp)) {
            Icon(Sym.add, contentDescription = "Viac", modifier = Modifier.size(16.dp))
        }
    }
}

private fun Modifier.widthInStepper() = width(64.dp)

@Composable
private fun MealsCta(
    label: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    ghost: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(16.dp),
        color = when {
            !enabled -> scheme.surfaceVariant
            ghost -> scheme.surfaceContainerLow
            else -> scheme.primary
        },
        border = if (ghost) BorderStroke(1.dp, scheme.outlineVariant) else null,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = if (!enabled) scheme.onSurfaceVariant else if (ghost) scheme.primary else scheme.onPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (!enabled) scheme.onSurfaceVariant else if (ghost) scheme.primary else scheme.onPrimary,
            )
        }
    }
}

@Composable
internal fun MealsHomeCard(meals: List<Meals.Meal>, recipes: List<Meals.Recipe>, pantry: List<Meals.PantryItem>, shop: List<ShopItem>, names: Map<String, String>, today: LocalDate, onClick: () -> Unit, onFill: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val todayMeals = Meals.mealsOn(meals, today)
    val plan = Meals.planShopping(today, today, todayMeals, recipes, pantry, shop, ShopListKind.SHARED)
        .filter { it.kind == Meals.NeedKind.MISSING }
    val missNames = plan.map { it.name.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale("sk")) else c.toString() } }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.surfaceVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Sym.skillet, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text("Dnes varíme", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Sym.chevronRight, contentDescription = null, tint = scheme.outline, modifier = Modifier.size(20.dp))
            }
            if (todayMeals.isEmpty()) {
                Text("Zatiaľ nič v pláne", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, start = 34.dp))
            } else {
                todayMeals.forEach { meal ->
                    val recipe = recipes.firstOrNull { it.id == meal.recipeId }
                    val who = Meals.cookLabel(meal.cookIds, names)
                    Row(Modifier.padding(top = 8.dp, start = 34.dp), verticalAlignment = Alignment.Top) {
                        Text(meal.slot.title, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.width(72.dp).padding(top = 2.dp))
                        Column(Modifier.weight(1f)) {
                            Text(meal.title.ifBlank { recipe?.name ?: "Jedlo" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            val cap = buildString {
                                if (meal.cooked) append("Uvarené")
                                if (who.isNotBlank()) { if (isNotEmpty()) append(" · "); append("Varí $who") }
                            }
                            if (cap.isNotBlank()) Text(cap, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                }
                if (missNames.isNotEmpty()) {
                    val list = when {
                        missNames.size == 1 -> missNames[0].lowercase(Locale("sk"))
                        missNames.size == 2 -> missNames[0].lowercase(Locale("sk")) + " a " + missNames[1].lowercase(Locale("sk"))
                        else -> missNames.take(2).joinToString(", ") { it.lowercase(Locale("sk")) } + " a " + missNames[2].lowercase(Locale("sk"))
                    }
                    Text(
                        "Chýba $list · doplniť",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.primary,
                        modifier = Modifier
                            .padding(top = 8.dp, start = 34.dp)
                            .clickable(onClick = onFill),
                    )
                }
            }
        }
    }
}
