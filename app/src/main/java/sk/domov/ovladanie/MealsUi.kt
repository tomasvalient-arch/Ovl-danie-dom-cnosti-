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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    var links by remember { mutableStateOf(MealsStore.links(context)) }
    var shopDay by remember { mutableStateOf(MealsStore.shopDay(context)) }
    var promptOn by remember { mutableStateOf(MealsStore.promptEnabled(context)) }
    var eaters by remember { mutableStateOf(MealsStore.usualEaters(context)) }
    var importOpen by remember { mutableStateOf(false) }
    var suggestOpen by remember { mutableStateOf(false) }

    fun reload() {
        recipes = MealsStore.liveRecipes(context)
        meals = MealsStore.liveMeals(context)
        pantry = MealsStore.livePantry(context)
        shop = ShoppingStore.items(context)
        people = MealsStore.people(context)
        syncText = HouseSyncEngine.statusText(context)
        links = MealsStore.links(context)
        shopDay = MealsStore.shopDay(context)
        promptOn = MealsStore.promptEnabled(context)
        eaters = MealsStore.usualEaters(context)
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
    val fillTo = Meals.fillUntil(today, shopDay)
    val plan = remember(meals, recipes, pantry, shop, today, links, shopDay) {
        MealShop.reconcile(fillFrom, fillTo, meals, recipes, pantry, shop, ShopListKind.SHARED, links)
    }
    val missingCount = plan.count { it.kind == Meals.NeedKind.MISSING || it.kind == Meals.NeedKind.TOPUP }
    val staleCount = plan.count { it.kind == Meals.NeedKind.STALE }
    val pending = Meals.pendingCooked(meals, recipes, today, java.time.LocalTime.now())
    val known = remember(recipes, pantry) {
        Meals.knownIngredients(recipes, pantry, runCatching { ShoppingStore.history(context).map { it.name } }.getOrDefault(emptyList()))
    }
    val clipboard = LocalClipboardManager.current
    val portionsFor: (Meals.Recipe) -> Int = { r -> Meals.defaultPortions(r, eaters, people.size) }

    /** Po uložení jedla do plánu: ak niečo chýba, ponúkne pridanie do nákupu (jedno ťuknutie = potvrdenie). */
    fun nudgeShopping(meal: Meals.Meal) {
        val d = runCatching { LocalDate.parse(meal.date) }.getOrNull() ?: return
        if (d.isBefore(today) || meal.recipeId.isEmpty()) return
        val freshMeals = MealsStore.liveMeals(context)
        val needs = MealShop.reconcile(
            fillFrom, if (d.isAfter(fillTo)) d else fillTo, freshMeals, MealsStore.liveRecipes(context),
            MealsStore.livePantry(context), ShoppingStore.items(context), ShopListKind.SHARED, MealsStore.links(context),
        ).filter { n -> (n.kind == Meals.NeedKind.MISSING || n.kind == Meals.NeedKind.TOPUP) && n.parts.any { it.mealId == meal.id } }
        if (needs.isEmpty()) return
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(Meals.needsSummary(needs), actionLabel = "Pridať", withDismissAction = true, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) {
                val n = MealsStore.applyShopping(context, needs, ShopListKind.SHARED)
                reload()
                snackbar.showSnackbar(if (n > 0) "Do nákupu: $n" else "Nákup sa nezmenil")
            }
        }
    }

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
                    known = known,
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
                            // uvarené ostane uvarené: zásoby sa už odrátali a druhýkrát sa neodrátajú
                            MealsStore.saveMeal(context, meal.copy(portions = p))
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
                    onSkip = {
                        val meal = openMeal ?: return@RecipeDetail
                        if (MealsStore.skipMeal(context, meal)) {
                            reload()
                            scope.launch { snackbar.showSnackbar("Jedlo sa nevarilo, do nákupu ho už nerátame") }
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
                    onImport = { importOpen = true },
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
                    staleCount = staleCount,
                    pending = pending,
                    promptOn = promptOn,
                    onPrompt = { MealsStore.setPromptEnabled(context, it); promptOn = it },
                    eaters = eaters,
                    household = people.size,
                    onEaters = { MealsStore.setUsualEaters(context, it); eaters = MealsStore.usualEaters(context) },
                    onAnswer = { meal, yes ->
                        if (yes) {
                            if (MealsStore.markCooked(context, meal)) scope.launch { snackbar.showSnackbar("Odrátané zo zásob") }
                        } else {
                            MealsStore.skipMeal(context, meal)
                        }
                        reload()
                    },
                    onSuggest = { suggestOpen = true },
                    onCopyPrev = {
                        val copy = MealPlanner.copyWeek(monday.minusDays(7), monday, meals, today)
                        if (copy.isEmpty()) {
                            scope.launch { snackbar.showSnackbar("Nie je čo skopírovať: minulý týždeň je prázdny alebo je všetko už obsadené") }
                        } else {
                            MealsStore.saveMeals(context, copy)
                            reload()
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                val r = snackbar.showSnackbar("Skopírované z minulého týždňa: ${copy.size}", actionLabel = "Späť", duration = SnackbarDuration.Long)
                                if (r == SnackbarResult.ActionPerformed) {
                                    copy.forEach { MealsStore.deleteMeal(context, it.id) }
                                    reload()
                                }
                            }
                        }
                    },
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
                    val meal = Meals.Meal(
                        id = Meals.mealId(day.toString(), slot),
                        date = day.toString(),
                        slot = slot,
                        recipeId = recipe.id,
                        title = recipe.name,
                        cookIds = cooks,
                        portions = portions,
                    )
                    MealsStore.saveMeal(context, meal)
                    pickFor = null
                    pickRecipeId = null
                    reload()
                    nudgeShopping(meal)
                },
                defaultPortions = portionsFor,
                onOut = {
                    MealsStore.saveMeal(context, MealPlanner.outMeal(day, slot))
                    pickFor = null
                    pickRecipeId = null
                    reload()
                },
                onClear = currentMeal?.let { m ->
                    {
                        MealsStore.deleteMeal(context, m.id)
                        pickFor = null
                        pickRecipeId = null
                        reload()
                    }
                },
            )
        }
    }

    if (showFill) {
        val focusMeal = fillForMeal
        val needs = if (focusMeal != null) {
            // jedno jedlo: celkové potreby (so všetkými naplánovanými jedlami), ktorých sa toto jedlo týka
            val d = runCatching { LocalDate.parse(focusMeal.date) }.getOrDefault(today)
            MealShop.reconcile(fillFrom, if (d.isAfter(fillTo)) d else fillTo, meals, recipes, pantry, shop, ShopListKind.SHARED, links)
                .filter { n -> n.kind != Meals.NeedKind.STALE && n.parts.any { it.mealId == focusMeal.id } }
        } else {
            plan
        }
        FillShopSheet(
            needs = needs,
            subtitle = if (focusMeal != null) "Čo treba na toto jedlo (so všetkými jedlami v pláne)" else "Súčet chýbajúceho od dnes ${Meals.rangeLabel(fillFrom, fillTo)}",
            shopDay = if (focusMeal != null) null else shopDay,
            onShopDay = { MealsStore.setShopDay(context, it); shopDay = it },
            onDismiss = { showFill = false; fillForMeal = null },
            onAdd = { items, target ->
                val changed = MealsStore.applyShopping(context, items, target)
                showFill = false
                fillForMeal = null
                reload()
                val dest = if (target == ShopListKind.SHARED) "spoločného" else "môjho"
                val adds = items.count { it.kind == Meals.NeedKind.MISSING || it.kind == Meals.NeedKind.ASK }
                val text = if (items.all { it.kind == Meals.NeedKind.MISSING || it.kind == Meals.NeedKind.ASK }) "Pridané do $dest: $changed" else "Nákup upravený: $changed (nových $adds)"
                scope.launch { snackbar.showSnackbar(text) }
            },
        )
    }

    if (suggestOpen) {
        SuggestSheet(
            monday = monday,
            today = today,
            meals = meals,
            recipes = recipes,
            onDismiss = { suggestOpen = false },
            onSave = { list ->
                suggestOpen = false
                val toSave = list.mapNotNull { sg ->
                    val r = recipes.firstOrNull { it.id == sg.recipeId } ?: return@mapNotNull null
                    Meals.Meal(Meals.mealId(sg.date.toString(), sg.slot), sg.date.toString(), sg.slot, recipeId = r.id, title = r.name, portions = portionsFor(r))
                }
                MealsStore.saveMeals(context, toSave)
                reload()
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val first = snackbar.showSnackbar("Do plánu pridané: ${toSave.size}. Chýbajúce sa dá pridať do nákupu tlačidlom dole.", actionLabel = "Späť", duration = SnackbarDuration.Long)
                    if (first == SnackbarResult.ActionPerformed) {
                        toSave.forEach { MealsStore.deleteMeal(context, it.id) }
                        reload()
                    }
                }
            },
        )
    }

    if (importOpen) {
        ImportRecipeDialog(
            known = known,
            clipboardText = { clipboard.getText()?.text.orEmpty() },
            onDismiss = { importOpen = false },
            onParsed = { r ->
                importOpen = false
                editingRecipe = Meals.Recipe(id = Meals.newRecipeId(), name = r.title, portions = r.portions, ingredients = r.ingredients, steps = r.steps)
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
    staleCount: Int,
    pending: List<Meals.Meal>,
    promptOn: Boolean,
    onPrompt: (Boolean) -> Unit,
    eaters: Int,
    household: Int,
    onEaters: (Int) -> Unit,
    onAnswer: (Meals.Meal, Boolean) -> Unit,
    onSuggest: () -> Unit,
    onCopyPrev: () -> Unit,
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
        if (pending.isNotEmpty()) {
            val recipeNames = recipes.associate { it.id to it.name }
            DomovCard(padding = 12.dp, spacing = 4.dp) {
                Text("Varilo sa?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Áno odpočíta suroviny zo zásob, Nie ich nechá a jedlo preskočí.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                pending.take(4).forEach { m ->
                    val d = runCatching { LocalDate.parse(m.date) }.getOrNull()
                    val day = when (d) {
                        today -> "Dnes"
                        today.minusDays(1) -> "Včera"
                        null -> m.date
                        else -> Meals.dayLabel(d)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "$day · ${m.slot.title.lowercase()}: ${m.title.ifBlank { recipeNames[m.recipeId].orEmpty() }}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { onAnswer(m, true) }) { Text("Áno", fontWeight = FontWeight.SemiBold) }
                        TextButton(onClick = { onAnswer(m, false) }) { Text("Nie") }
                    }
                }
                if (pending.size > 4) Text("a ďalšie: ${pending.size - 4}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
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
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            FilterChip(selected = false, onClick = onSuggest, label = { Text("Navrhnúť týždeň") })
            FilterChip(selected = false, onClick = onCopyPrev, label = { Text("Ako minulý týždeň") })
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
        val cta = when {
            missingCount > 0 -> "Doplniť nákup · chýba $missingCount"
            staleCount > 0 -> "Upraviť nákup · plán sa zmenil"
            else -> "Doplniť nákup"
        }
        MealsCta(cta, icon = CartIcon, onClick = onFill)
        CheckRow(
            title = "Večer sa opýtať, či sa varilo",
            caption = "Oznámenie o 21:00 s tlačidlami Áno a Nie; Áno odpočíta zásoby",
            checked = promptOn,
            verticalPadding = 4.dp,
            onChange = onPrompt,
        )
        val shown = Meals.usualEaters(eaters, household)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Nové jedlá plánovať pre", style = MaterialTheme.typography.bodyLarge)
                Text(
                    when {
                        eaters > 0 -> "Nastavené ručne · ${Meals.portionsText(eaters)}"
                        shown > 0 -> "Podľa domácnosti · ${Meals.portionsText(shown)}"
                        else -> "Podľa porcií receptu"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Stepper(shown.toDouble(), "ks", format = { if (it < 1.0) "auto" else it.toInt().toString() }, onChange = { d -> onEaters((if (shown > 0) shown else 1) + d) })
        }
        if (eaters > 0) {
            TextButton(onClick = { onEaters(0) }, modifier = Modifier.padding(horizontal = 0.dp)) { Text("Vrátiť na automatiku") }
        }
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
    val out = meal != null && meal.recipeId.isEmpty() && meal.title.isNotBlank()
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
            if (meal != null && out) {
                Text(
                    meal.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = scheme.onSurfaceVariant,
                )
                Text("bez varenia", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            } else if (meal == null || recipe == null) {
                Text("+ Pridať", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            } else {
                Text(
                    meal.title.ifBlank { recipe.name },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (meal.cooked || meal.skipped) scheme.onSurfaceVariant else scheme.onSurface,
                    textDecoration = if (meal.skipped) TextDecoration.LineThrough else null,
                )
                val who = Meals.cookLabel(meal.cookIds, names)
                if (meal.skipped) {
                    Text("Nevarilo sa", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                } else if (who.isNotBlank()) {
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
    onSkip: () -> Unit,
    onFill: () -> Unit,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val portions = meal?.let { Meals.portionsOf(it, recipe) } ?: recipe.portions
    val scaled = Meals.scaledIngredients(recipe, portions)
    data class Row(val ing: Meals.Ingredient, val have: Boolean, val note: String)
    val fuzzy = scaled.filter { Meals.isFuzzy(it.unit) }
    val rows = scaled.filter { !Meals.isFuzzy(it.unit) }.map { ing ->
        if (Meals.isHomeBasic(ing.name, ing.unit)) return@map Row(ing, true, "vždy doma")
        val stock = pantry.firstOrNull { Meals.key(it.name) == Meals.key(ing.name) }
        when {
            stock?.always == true -> Row(ing, true, "vždy doma")
            stock == null -> Row(ing, false, Meals.formatQty(ing.amount, ing.unit))
            Meals.covers(stock, ing.amount, ing.unit) == true -> Row(ing, true, Meals.formatQty(stock.amount, stock.unit))
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
            if (meal?.skipped == true) append("Nevarilo sa")
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
        if (recipe.steps.isNotEmpty()) {
            SectionLabel("Postup · ${Meals.stepsCountText(recipe.steps.size)}")
            DomovCard(padding = 12.dp, spacing = 10.dp) {
                recipe.steps.forEachIndexed { i, step ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "${i + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.primary,
                            modifier = Modifier.width(26.dp),
                        )
                        Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
                if (portions != recipe.portions) {
                    Text(
                        "Čísla v postupe sú pre ${Meals.portionsText(recipe.portions)}, neprepočítavajú sa. Suroviny hore áno.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (meal != null && !meal.cooked) {
                MealsCta("Uvarené", icon = Sym.skillet, ghost = true, modifier = Modifier.weight(1f), onClick = onCooked)
            }
            if (miss.isNotEmpty() && meal?.skipped != true) {
                MealsCta("Doplniť chýbajúce", icon = CartIcon, modifier = Modifier.weight(1f), onClick = onFill)
            }
        }
        if (meal != null && !meal.cooked && !meal.skipped) {
            TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Nevarilo sa")
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
    known: List<Meals.Known>,
    isNew: Boolean,
    onCancel: () -> Unit,
    onSave: (Meals.Recipe) -> Unit,
    onDelete: (() -> Unit)? = null,
    onRegisterSave: ((() -> Unit)?) -> Unit = {},
) {
    var name by remember { mutableStateOf(initial.name) }
    var portions by remember { mutableIntStateOf(initial.portions.coerceAtLeast(1)) }
    var ings by remember { mutableStateOf(initial.ingredients.ifEmpty { listOf(Meals.Ingredient("", 1.0, "ks")) }) }
    var stepsText by remember { mutableStateOf(Meals.stepsToText(initial.steps)) }
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
        onSave(initial.copy(name = name.trim(), portions = portions, ingredients = cleanIngredients(), steps = Meals.stepsFromText(stepsText)))
    }
    LaunchedEffect(name, portions, ings, stepsText) {
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
                    val hints = if (ing.name.trim().length >= 2) {
                        Meals.suggestIngredients(ing.name, known, 4)
                            .filter { k -> !(k.name == ing.name.trim() && Meals.canonicalUnit(ing.unit) == k.unit) }
                    } else {
                        emptyList()
                    }
                    if (hints.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            hints.forEach { k ->
                                FilterChip(
                                    selected = false,
                                    onClick = { ings = ings.toMutableList().also { it[i] = ing.copy(name = k.name, unit = k.unit, amount = if (ing.amount > 0.0) ing.amount else 1.0) } },
                                    label = { Text("${k.name} · ${k.unit}") },
                                )
                            }
                        }
                    }
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
                        val curSoft = Meals.softLabel(ing.unit)
                        // bežné mäkké miery sú vždy v ponuke, ostatné (konzerva, kocka…) len keď ich recept už používa
                        val chips = listOf("ks", "g", "kg", "ml", "l") + Meals.COMMON_SOFT + listOfNotNull(curSoft?.takeIf { it !in Meals.COMMON_SOFT }) + Meals.FUZZY_UNITS
                        chips.forEach { u ->
                            val selected = when {
                                Meals.isFuzzy(u) -> Meals.fuzzyLabel(ing.unit) == u
                                Meals.isSoft(u) -> curSoft == u
                                else -> !Meals.isFuzzy(ing.unit) && curSoft == null && Meals.canonicalUnit(ing.unit) == u
                            }
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    // pri prechode medzi g/ml, kusmi a mäkkou mierou sa množstvo vráti na 1 (500 g ≠ 500 strúčikov)
                                    val amount = when {
                                        Meals.isFuzzy(u) -> 0.0
                                        ing.amount <= 0.0 -> 1.0
                                        Meals.family(ing.unit) != Meals.family(u) && (Meals.isSoft(u) || Meals.isSoft(ing.unit)) -> 1.0
                                        else -> ing.amount
                                    }
                                    ings = ings.toMutableList().also { it[i] = ing.copy(unit = u, amount = amount) }
                                },
                                label = { Text(u) },
                            )
                        }
                    }
                }
            }
            TextButton(onClick = { ings = ings + Meals.Ingredient("", 1.0, "ks") }) { Text("Pridať surovinu") }
            SectionLabel("Postup")
            DomovTextField(
                value = stepsText,
                onValueChange = { stepsText = it },
                placeholder = { Text("Každý krok na nový riadok, napr.\nRyžu uvaríme podľa návodu.\nHuby orestujeme s cesnakom.") },
                supportingText = {
                    val n = Meals.stepsFromText(stepsText).size
                    Text(if (n == 0) "Číslovanie sa doplní samo." else Meals.stepsCountText(n))
                },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
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
    var pieceG by remember { mutableStateOf(initial?.pieceG?.takeIf { it > 0.0 }?.let { Meals.fmtNum(it) } ?: "") }
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
                if (!always && (unit == "g" || unit == "kg")) {
                    DomovTextField(
                        value = pieceG,
                        onValueChange = { pieceG = it },
                        label = { Text("1 ks váži (g) – nepovinné") },
                        supportingText = { Text("Aby „2 ks cibule“ z receptu sedelo so zásobou v gramoch") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
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
                        pieceG = if (unit == "g" || unit == "kg") Meals.parseNumber(pieceG).coerceAtLeast(0.0) else 0.0,
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
    defaultPortions: (Meals.Recipe) -> Int,
    onOut: () -> Unit,
    onClear: (() -> Unit)?,
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
        val wanted = preselectId ?: current?.recipeId
        val recipe = recipes.firstOrNull { it.id == wanted }
        mutableIntStateOf(
            when {
                // to isté jedlo znova otvorené: ostáva, čo je v pláne (0 = porcie receptu)
                current != null && current.recipeId == wanted -> Meals.portionsOf(current, recipe)
                recipe != null -> defaultPortions(recipe)
                else -> 4
            },
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
                                        if (current?.recipeId != r.id) portions = defaultPortions(r)
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
                TextButton(onClick = { afterHide(onOut) }) { Text("Jeme vonku / bez varenia") }
                if (onClear != null) {
                    TextButton(onClick = { afterHide(onClear) }) { Text("Vymazať z plánu", color = scheme.error) }
                }
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
    onImport: () -> Unit,
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
            Text("Zatiaľ žiadny recept. Pridaj ho tlačidlom Nový, alebo vlož text receptu či odkaz.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            MealsCta("Nový recept", onClick = onNew)
            MealsCta("Vložiť recept z textu alebo odkazu", ghost = true, onClick = onImport)
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
            TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Vložiť recept z textu alebo odkazu") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FillShopSheet(
    needs: List<Meals.ShopNeed>,
    subtitle: String,
    /** null = výber dňa nákupu sa neukazuje (jedno jedlo). */
    shopDay: Int?,
    onShopDay: (Int) -> Unit,
    onDismiss: () -> Unit,
    onAdd: (List<Meals.ShopNeed>, ShopListKind) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var target by remember { mutableStateOf(ShopListKind.SHARED) }
    // všetko okrem „Už v zozname“ je predvolene zaškrtnuté; ťuknutím sa dá odškrtnúť. Pri zmene podkladov (deň nákupu) sa výber obnoví.
    var checked by remember(needs.map { it.uiKey() }) {
        mutableStateOf(needs.filter { it.kind != Meals.NeedKind.ALREADY }.associate { it.uiKey() to true }.toMutableMap())
    }
    val missing = needs.filter { it.kind == Meals.NeedKind.MISSING }
    val topup = needs.filter { it.kind == Meals.NeedKind.TOPUP }
    val stale = needs.filter { it.kind == Meals.NeedKind.STALE }
    val already = needs.filter { it.kind == Meals.NeedKind.ALREADY }
    val ask = needs.filter { it.kind == Meals.NeedKind.ASK }
    val selected = needs.filter { it.kind != Meals.NeedKind.ALREADY && checked[it.uiKey()] == true }
    val dest = if (target == ShopListKind.SHARED) "spoločného" else "môjho"
    val onlyAdds = selected.all { it.kind == Meals.NeedKind.MISSING || it.kind == Meals.NeedKind.ASK }
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
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            if (shopDay != null) {
                Text("Nakupujem v", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    FilterChip(selected = shopDay == 0, onClick = { onShopDay(0) }, label = { Text("Nenastavené") })
                    (1..7).forEach { d ->
                        FilterChip(selected = shopDay == d, onClick = { onShopDay(d) }, label = { Text(Meals.WEEKDAYS[d - 1]) })
                    }
                }
            }
            Segmented(
                options = listOf("Môj" to null, "Spoločný" to null),
                selected = if (target == ShopListKind.MINE) 0 else 1,
                onSelect = { target = if (it == 0) ShopListKind.MINE else ShopListKind.SHARED },
            )
            if (needs.isEmpty()) {
                Text("Nič nechýba — alebo ešte nie je v pláne žiadne jedlo.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            val toggle = { k: String, v: Boolean -> checked = checked.toMutableMap().also { it[k] = v } }
            if (missing.isNotEmpty()) NeedSection("Chýba · ${missing.size}", missing, checked, toggle, toggleable = true)
            if (topup.isNotEmpty()) NeedSection("Zvýšiť množstvo · ${topup.size}", topup, checked, toggle, toggleable = true, caption = { it.note })
            if (stale.isNotEmpty()) NeedSection("Už netreba · ${stale.size}", stale, checked, toggle, toggleable = true, caption = { it.note })
            if (ask.isNotEmpty()) NeedSection("Overiť · ${ask.size}", ask, checked, toggle, toggleable = true, caption = { if (Meals.isSoft(it.unit)) "Nie je v zásobách · odškrtni, ak to máš doma" else "Nie je v zásobách alebo nesedí jednotka · odškrtni, ak to máš doma" })
            if (already.isNotEmpty()) NeedSection("Už v zozname", already, emptyMap(), { _, _ -> }, toggleable = false, caption = { "Už v zozname" })
            MealsCta(
                when {
                    selected.isEmpty() -> "Nič na pridanie"
                    onlyAdds -> "Pridať ${selected.size} do $dest"
                    else -> "Použiť ${selected.size} ${if (selected.size == 1) "zmenu" else if (selected.size < 5) "zmeny" else "zmien"}"
                },
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
            val key = n.uiKey()
            val on = checked[key] == true
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = toggleable) { onCheck(key, !on) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (toggleable) {
                    TickMark(on)
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        n.displayText(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (n.kind == Meals.NeedKind.STALE && n.newText.isEmpty()) TextDecoration.LineThrough else null,
                    )
                    val cap = caption(n)
                    if (cap.isNotBlank()) Text(cap, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (n.kind == Meals.NeedKind.MISSING && n.sources.isNotEmpty() && caption(n) != n.sources.joinToString(" · ")) {
                        Text(n.sources.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Návrh jedálnička na prázdne miesta v týždni: nič sa neuloží, kým užívateľ nepotvrdí tlačidlom dole. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestSheet(
    monday: LocalDate,
    today: LocalDate,
    meals: List<Meals.Meal>,
    recipes: List<Meals.Recipe>,
    onDismiss: () -> Unit,
    onSave: (List<MealPlanner.Suggestion>) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var slots by remember { mutableStateOf(setOf(Meals.Slot.LUNCH, Meals.Slot.DINNER)) }
    var seed by remember { mutableIntStateOf(0) }
    var off by remember { mutableStateOf(setOf<String>()) }
    val days = remember(monday) { (0..6).map { monday.plusDays(it.toLong()) } }
    val empty = remember(days, slots, meals, today) { MealPlanner.emptySlots(days, slots.toList(), meals, today) }
    val suggestions = remember(empty, meals, recipes, seed) { MealPlanner.suggest(empty, meals, recipes, seed) }
    val names = remember(recipes) { recipes.associate { it.id to it.name } }
    val chosen = suggestions.filter { "${it.date}|${it.slot.code}" !in off }
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
            Text("Navrhnúť týždeň", style = MaterialTheme.typography.titleLarge)
            Text(
                "Vyplní len prázdne miesta ${Meals.formatWeekRange(monday)}. Uloží sa až po potvrdení.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Meals.Slot.PLAN.forEach { sl ->
                    FilterChip(
                        selected = sl in slots,
                        onClick = { slots = if (sl in slots) slots - sl else slots + sl },
                        label = { Text(sl.title) },
                    )
                }
            }
            when {
                recipes.isEmpty() -> Text("Najprv pridaj aspoň jeden recept.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                slots.isEmpty() -> Text("Vyber aspoň jeden chod.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                suggestions.isEmpty() -> Text("V tomto týždni nie je čo dopĺňať: všetky vybrané miesta sú obsadené alebo už prešli.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                else -> DomovCard(padding = 4.dp, spacing = 0.dp) {
                    suggestions.forEach { sg ->
                        val key = "${sg.date}|${sg.slot.code}"
                        val on = key !in off
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { off = if (on) off + key else off - key }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TickMark(on)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    names[sg.recipeId].orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = if (on) scheme.onSurface else scheme.onSurfaceVariant,
                                    textDecoration = if (on) null else TextDecoration.LineThrough,
                                )
                                Text(
                                    "${Meals.dayLabel(sg.date)} ${sg.date.dayOfMonth}. ${sg.date.monthValue}. · ${sg.slot.title.lowercase()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (suggestions.isNotEmpty()) {
                MealsCta("Zamiešať", ghost = true, onClick = { seed += 1; off = emptySet() })
            }
            MealsCta(
                if (chosen.isEmpty()) "Nič na pridanie" else "Pridať do plánu: ${chosen.size}",
                enabled = chosen.isNotEmpty(),
                onClick = { onSave(chosen) },
            )
        }
    }
}

/**
 * Import receptu: vložený text (suroviny po riadkoch, aj s množstvom za názvom) alebo odkaz na stránku s receptom.
 * Najprv sa ukáže, čo appka rozpoznala a čomu nerozumela; až potom sa výsledok otvorí v editore, kde sa dá všetko opraviť.
 */
@Composable
private fun ImportRecipeDialog(
    known: List<Meals.Known>,
    clipboardText: () -> String,
    onDismiss: () -> Unit,
    onParsed: (RecipeImport.Result) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<RecipeImport.Result?>(null) }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val url = RecipeImport.asUrl(text)
    fun go() {
        if (busy) return
        error = null
        if (url != null) {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val r = Http.send("GET", url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Android) Domov", "Accept-Language" to "sk,cs,en"))
                        if (r.code in 200..299) RecipeImport.fromHtml(r.body, known) else null
                    }.getOrNull()
                }
                busy = false
                if (result != null && result.ingredients.isNotEmpty()) preview = result
                else error = "Z tejto stránky sa recept nepodarilo načítať (alebo je bez internetu). Skopíruj text receptu a vlož ho sem."
            }
        } else {
            val r = RecipeImport.parse(text, known)
            if (r.ingredients.isEmpty()) error = "Nenašli sa žiadne suroviny. Každá surovina nech je na vlastnom riadku, napr. „500 g múky“, „Múka 500 g“ alebo „2 vajcia“."
            else preview = r
        }
    }
    val shown = preview
    DomovAlert(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (shown == null) "Recept z textu alebo odkazu" else "Rozpoznaný recept") },
        text = {
            if (shown == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Vlož zoznam surovín (jedna na riadok, voliteľne s názvom a počtom porcií) a pod nadpisom Postup kroky prípravy, alebo odkaz na stránku s receptom.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    DomovTextField(
                        value = text,
                        onValueChange = { text = it; error = null },
                        placeholder = { Text("Palacinky\n4 porcie\n250 g múky\n2 vajcia\n500 ml mlieka") },
                        minLines = 5,
                        maxLines = 10,
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { text = clipboardText(); error = null }, enabled = !busy) { Text("Vložiť zo schránky") }
                    if (error != null) Text(error!!, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                    if (busy) Text("Načítavam…", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        (shown.title.ifBlank { "Bez názvu" }) + " · " + Meals.portionsText(shown.portions),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (shown.ingredients.size <= 2) {
                        Text(
                            "Rozpoznal som len ${shown.ingredients.size}. Ak ich je v recepte viac, skontroluj riadky nižšie alebo uprav text.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.error,
                        )
                    }
                    shown.ingredients.forEach { ing ->
                        val qty = if (ing.amount > 0.0) Meals.formatQty(ing.amount, ing.unit) else ing.unit
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(ing.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(qty, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (shown.steps.isNotEmpty()) {
                        Text("Postup · ${Meals.stepsCountText(shown.steps.size)}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        shown.steps.take(2).forEachIndexed { i, st ->
                            Text("${i + 1}. $st", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (shown.steps.size > 2) Text("…", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        if (shown.stepsCut) {
                            Text("Postup bol veľmi dlhý, časť na konci sa vynechala.", style = MaterialTheme.typography.bodySmall, color = scheme.error)
                        }
                    } else {
                        Text("Postup sa nenašiel, môžeš ho doplniť v editore.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    if (shown.skipped.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Nerozpoznané riadky (nepridajú sa):", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        shown.skipped.forEach { sk ->
                            Text(sk, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (shown == null) {
                DialogPrimaryButton(onClick = { go() }, enabled = text.isNotBlank() && !busy) { Text(if (url != null) "Načítať" else "Rozpoznať") }
            } else {
                DialogPrimaryButton(onClick = { onParsed(shown) }) { Text("Otvoriť v editore") }
            }
        },
        dismissButton = {
            if (shown == null) {
                DialogTextButton(onClick = onDismiss, enabled = !busy) { Text("Zrušiť") }
            } else {
                DialogTextButton(onClick = { preview = null }) { Text("Upraviť text") }
            }
        },
    )
}

@Composable
private fun TickMark(on: Boolean) {
    val scheme = MaterialTheme.colorScheme
    if (on) {
        Icon(Sym.check, contentDescription = "Vybrané", tint = scheme.primary, modifier = Modifier.size(20.dp))
    } else {
        Box(Modifier.size(20.dp).border(1.5.dp, scheme.outline, CircleShape).semantics { contentDescription = "Nevybrané" })
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
                                if (meal.skipped) append("Nevarilo sa")
                                if (who.isNotBlank() && !meal.skipped) { if (isNotEmpty()) append(" · "); append("Varí $who") }
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
