@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package sk.domov.ovladanie

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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

/** Karta „Nákup“ na Domove: dve polovice so súčtami, ťuknutie otvorí príslušný zoznam. */
@Composable
internal fun ShoppingRow(items: List<ShopItem>, onMine: () -> Unit, onShared: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val mine = ShoppingList.left(items, ShopListKind.MINE)
    val shared = ShoppingList.left(items, ShopListKind.SHARED)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CartIcon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Nákup", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShopHomeHalf(
                    modifier = Modifier.weight(1f),
                    icon = Sym.lock,
                    label = ShopListKind.MINE.title,
                    count = mine,
                    onClick = onMine,
                )
                ShopHomeHalf(
                    modifier = Modifier.weight(1f),
                    icon = Sym.groups,
                    label = ShopListKind.SHARED.title,
                    count = shared,
                    onClick = onShared,
                )
            }
        }
    }
}

@Composable
private fun ShopHomeHalf(modifier: Modifier, icon: ImageVector, label: String, count: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerHighest,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            }
            Text("$count", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Nákupný zoznam: pilulka Môj | Oba | Spoločný, pole pridáva do zvoleného cieľa,
 * v pohľade Oba sú dva stĺpce. Ťuknutím sa položka odškrtne, podržaním sa otvorí menu
 * (premenovať / presunúť / odstrániť), v celošírkovom zozname aj potiahnutím do strany.
 */
@Composable
internal fun ShoppingScreen(onBack: () -> Unit, initialView: ShopView? = null) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var items by remember { mutableStateOf(ShoppingStore.items(context)) }
    var history by remember { mutableStateOf(ShoppingStore.history(context)) }
    var view by remember { mutableStateOf(initialView ?: ShoppingStore.view(context)) }
    var target by remember { mutableStateOf(initialView?.kind ?: ShoppingStore.target(context)) }
    var text by rememberSaveable { mutableStateOf("") }
    var added by remember { mutableStateOf(0) }
    var justSubmitted by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ShopItem?>(null) }
    var menuFor by remember { mutableStateOf<ShopItem?>(null) }
    var forgetting by remember { mutableStateOf<String?>(null) }

    fun applyView(v: ShopView, alsoTarget: Boolean = true) {
        view = v
        ShoppingStore.setView(context, v)
        if (alsoTarget) {
            v.kind?.let {
                target = it
                ShoppingStore.setTarget(context, it)
            }
        }
    }
    fun applyTarget(k: ShopListKind) {
        target = k
        ShoppingStore.setTarget(context, k)
    }

    LaunchedEffect(initialView) {
        if (initialView != null) applyView(initialView)
    }

    DisposableEffect(Unit) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            items = ShoppingStore.items(context)
            history = ShoppingStore.history(context)
        }
        ShoppingStore.listen(context, listener)
        onDispose { ShoppingStore.unlisten(context, listener) }
    }

    // spoločný zoznam je na očiach: zmeny od druhého telefónu vidno hneď, notifikácia netreba (a staré zmeny sa zrušia)
    val sharedVisible = view != ShopView.MINE
    DisposableEffect(sharedVisible) {
        ShoppingNotify.screenOpen = sharedVisible
        if (sharedVisible) ShoppingNotify.markSeen(context)
        onDispose { ShoppingNotify.screenOpen = false }
    }

    val visibleKind = view.kind
    val visible = if (visibleKind == null) items else items.filter { it.list == visibleKind }
    val targetItems = items.filter { it.list == target }
    val rows = ShoppingList.arrange(visible)
    val suggestions = ShoppingHistory.suggest(history, text, targetItems)
    val chips = if (text.isBlank()) ShoppingHistory.due(history, targetItems, System.currentTimeMillis()) else emptyList()
    val canClear = visible.any { it.done }

    fun addName(name: String) {
        items = ShoppingStore.add(context, name, target)
        history = ShoppingStore.history(context)
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
    fun delete(item: ShopItem) {
        items = ShoppingStore.remove(context, item)
        history = ShoppingStore.history(context)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar("Odstránené: ${item.name}", actionLabel = "Späť", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) {
                items = ShoppingStore.restore(context, item)
                history = ShoppingStore.history(context)
            }
        }
    }
    fun move(item: ShopItem) {
        items = ShoppingStore.move(context, item)
        snackbar.currentSnackbarData?.dismiss()
        scope.launch { snackbar.showSnackbar("Presunuté do ${item.list.other.title.lowercase()}: ${item.name}", duration = SnackbarDuration.Short) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Nákup", style = MaterialTheme.typography.titleLarge)
                        Text(ShoppingList.viewSubtitle(view, items), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Sym.arrowBack, contentDescription = "Späť") } },
                actions = {
                    if (canClear) {
                        TextButton(onClick = { items = ShoppingStore.clearDone(context, visibleKind) }) {
                            Text("Vymazať kúpené", fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
            )
        },
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(16.dp),
                    containerColor = scheme.surfaceContainerHighest,
                    contentColor = scheme.onSurface,
                    actionColor = scheme.primary,
                )
            }
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (view == ShopView.MINE) {
                Surface(shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainerLow, border = BorderStroke(1.dp, scheme.surfaceVariant)) {
                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Sym.lock, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Text("Môj zoznam · nesynchronizuje sa", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    }
                }
            } else {
                SyncStatusRow()
            }
            Segmented(
                options = listOf("Môj" to null, "Oba" to null, "Spoločný" to null),
                selected = view.ordinal,
                onSelect = { applyView(ShopView.entries[it]) },
            )
            key(added) {
                ShoppingField(text = text, onChange = { onFieldChange(it) }, onSubmit = { submit() }, focusAfterAdd = added > 0, placeholder = target.hint)
            }
            ShopTargetRow(target = target, onSelect = { applyTarget(it) })
            if (suggestions.isNotEmpty()) {
                ShoppingSuggestions(suggestions = suggestions, onPick = { submit(it.name) }, onForget = { forgetting = it.name })
            }
            if (chips.isNotEmpty()) {
                ShoppingChips(chips = chips, onPick = { addName(it.name) }, onForget = { forgetting = it.name })
            }
            if (view == ShopView.BOTH) {
                var drag by remember { mutableStateOf(0f) }
                ShopColumns(
                    items = items,
                    target = target,
                    onOpen = { applyView(ShopView.of(it)) },
                    onToggle = { items = ShoppingStore.toggle(context, it.id) },
                    onMenu = { menuFor = it },
                    modifier = Modifier.pointerInput(view) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                when {
                                    drag < -64f -> applyView(ShopView.SHARED)
                                    drag > 64f -> applyView(ShopView.MINE)
                                }
                                drag = 0f
                            },
                            onHorizontalDrag = { _, a -> drag += a },
                        )
                    },
                )
                Text(
                    "Ťukni na názov zoznamu alebo ho potiahni do strany – otvorí sa sám na celú šírku.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else if (rows.isEmpty()) {
                Text(
                    if (view == ShopView.MINE) "Zatiaľ nič len pre teba. Napíš hore, čo treba kúpiť, a potvrď tlačidlom +."
                    else "Zatiaľ nič v spoločnom. Napíš hore, čo treba kúpiť (aj s množstvom, napr. „Mlieko 2 l“), a potvrď tlačidlom +.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            } else {
                val live = rows.filter { !it.done }
                val done = rows.filter { it.done }
                if (live.isNotEmpty()) {
                    ShopCard(live, showAuthor = view == ShopView.SHARED, showAvatar = view == ShopView.SHARED, onToggle = { items = ShoppingStore.toggle(context, it.id) }, onMenu = { menuFor = it }, onDelete = { delete(it) })
                }
                if (done.isNotEmpty()) {
                    ShopCard(done, showAuthor = view == ShopView.SHARED, showAvatar = view == ShopView.SHARED, onToggle = { items = ShoppingStore.toggle(context, it.id) }, onMenu = { menuFor = it }, onDelete = { delete(it) })
                }
                Text(
                    if (view == ShopView.MINE) "Tieto veci vidíš len ty. Podrž položku, ak ju chceš presunúť do spoločného."
                    else "Potiahnutím do strany položku odstrániš, podržaním ju presunieš alebo premenuješ.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.outline,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    menuFor?.let { item ->
        ShopItemMenu(
            item = item,
            onDismiss = { menuFor = null },
            onRename = { menuFor = null; editing = item },
            onMove = { menuFor = null; move(item) },
            onDelete = { menuFor = null; delete(item) },
        )
    }
    editing?.let { item ->
        RenameShopItemDialog(
            item = item,
            onDismiss = { editing = null },
            onSave = { name ->
                items = ShoppingStore.rename(context, item, name)
                history = ShoppingStore.history(context)
                editing = null
            },
        )
    }
    forgetting?.let { name ->
        DomovAlert(
            onDismissRequest = { forgetting = null },
            icon = { Icon(Sym.history, null) },
            title = { Text("Odstrániť z návrhov?") },
            text = { Text("„$name“ sa prestane ponúkať pri písaní aj medzi rýchlymi tlačidlami. Nákupný zoznam sa nezmení.") },
            confirmButton = {
                DialogPrimaryButton(onClick = {
                    ShoppingStore.forgetSuggestion(context, name)
                    history = ShoppingStore.history(context)
                    forgetting = null
                }) { Text("Odstrániť") }
            },
            dismissButton = { DialogTextButton(onClick = { forgetting = null }) { Text("Zrušiť") } },
        )
    }
}

@Composable
private fun ShopTargetRow(target: ShopListKind, onSelect: (ShopListKind) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 2.dp)) {
        Text("Pridá sa do", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        ShopListKind.entries.forEach { kind ->
            val on = target == kind
            Surface(
                onClick = { onSelect(kind) },
                shape = RoundedCornerShape(16.dp),
                color = if (on) scheme.primaryContainer else scheme.surfaceContainerLow,
                border = BorderStroke(1.dp, if (on) scheme.primaryContainer else scheme.surfaceVariant),
            ) {
                Row(modifier = Modifier.height(32.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(if (kind == ShopListKind.MINE) Sym.lock else Sym.groups, null, tint = if (on) scheme.onPrimaryContainer else scheme.onSurface, modifier = Modifier.size(14.dp))
                    Text(kind.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (on) scheme.onPrimaryContainer else scheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun ShopColumns(
    items: List<ShopItem>,
    target: ShopListKind,
    onOpen: (ShopListKind) -> Unit,
    onToggle: (ShopItem) -> Unit,
    onMenu: (ShopItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShopListKind.entries.forEach { kind ->
            val rows = ShoppingList.arrange(items.filter { it.list == kind })
            val left = ShoppingList.left(items, kind)
            val active = target == kind
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(22.dp),
                color = scheme.surfaceContainerLow,
                border = BorderStroke(if (active) 1.5.dp else 1.dp, if (active) scheme.primary else scheme.surfaceVariant),
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(kind) }
                            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(if (kind == ShopListKind.MINE) Sym.lock else Sym.groups, null, tint = scheme.onSurface, modifier = Modifier.size(16.dp))
                        Text(kind.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Surface(shape = RoundedCornerShape(10.dp), color = if (active) scheme.primaryContainer else scheme.surfaceContainerHighest) {
                            Text("$left", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                        }
                        Icon(Sym.chevronRight, contentDescription = "Otvoriť ${kind.title}", tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                    if (rows.isEmpty()) {
                        Text("Prázdny", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp))
                    } else {
                        rows.forEachIndexed { i, item ->
                            if (i > 0) HorizontalDivider(color = scheme.surfaceVariant)
                            ShoppingItemRow(
                                item = item,
                                onToggle = { onToggle(item) },
                                onEdit = { onMenu(item) },
                                showAuthor = kind == ShopListKind.SHARED,
                                showAvatar = false,
                                compact = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShopCard(
    rows: List<ShopItem>,
    showAuthor: Boolean,
    showAvatar: Boolean,
    onToggle: (ShopItem) -> Unit,
    onMenu: (ShopItem) -> Unit,
    onDelete: (ShopItem) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.surfaceVariant),
    ) {
        Column {
            rows.forEachIndexed { index, item ->
                key(item.id) {
                    if (index > 0) HorizontalDivider(color = scheme.surfaceVariant)
                    SwipeToDelete(onDelete = { onDelete(item) }) {
                        ShoppingItemRow(
                            item = item,
                            onToggle = { onToggle(item) },
                            onEdit = { onMenu(item) },
                            showAuthor = showAuthor,
                            showAvatar = showAvatar,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShopItemMenu(
    item: ShopItem,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = scheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Text(item.name, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        ShopMenuRow(icon = Sym.edit, label = "Premenovať", onClick = onRename)
        ShopMenuRow(icon = Sym.swapHoriz, label = item.list.moveLabel, onClick = onMove)
        ShopMenuRow(icon = Sym.delete, label = "Odstrániť", onClick = onDelete, danger = true)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ShopMenuRow(icon: ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (danger) scheme.error else scheme.onSurface)
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = if (danger) scheme.error else scheme.onSurface)
    }
}


/** Zaoblené pole „Čo treba kúpiť?“ s tlačidlom +; po potvrdení ostane klávesnica, aby sa dalo pridať viac vecí za sebou. */
@Composable
internal fun ShoppingField(text: String, onChange: (String) -> Unit, onSubmit: () -> Unit, focusAfterAdd: Boolean = false, placeholder: String = "Čo treba kúpiť?") {
    val scheme = MaterialTheme.colorScheme
    val canAdd = text.isNotBlank()
    // po pridaní položky ostane kurzor v (už prázdnom) poli, aby sa dalo hneď písať ďalšiu vec
    val focus = remember { FocusRequester() }
    if (focusAfterAdd) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
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
                modifier = Modifier.weight(1f).focusRequester(focus),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }, onDone = { onSubmit() }, onSend = { onSubmit() }, onSearch = { onSubmit() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text(placeholder, color = scheme.onSurfaceVariant, fontSize = 16.sp)
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

/** Návrhy pod poľom pri písaní (z histórie): ťuknutím sa vec hneď pridá, podržaním sa odstráni z návrhov. */
@Composable
internal fun ShoppingSuggestions(
    suggestions: List<ShopHistoryEntry>,
    onPick: (ShopHistoryEntry) -> Unit,
    onForget: (ShopHistoryEntry) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.surfaceVariant),
    ) {
        Column {
            suggestions.forEachIndexed { index, s ->
                if (index > 0) HorizontalDivider(color = scheme.surfaceVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .combinedClickable(
                            onClickLabel = "Pridať do zoznamu",
                            onLongClickLabel = "Odstrániť z návrhov",
                            onLongClick = { onForget(s) },
                            onClick = { onPick(s) },
                        )
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Sym.history, contentDescription = null, tint = scheme.outline, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(s.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp, color = scheme.onSurface)
                    Icon(Sym.add, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * „Často kupuješ“ (keď je pole prázdne): veci, ktoré treba podľa doterajších nákupov kúpiť znova ([ShoppingHistory.due]).
 * Ťuknutím sa vec pridá, podržaním sa odstráni z návrhov. Sekcia sa dá zbaliť ťuknutím na nadpis (pamätá sa).
 */
@Composable
internal fun ShoppingChips(chips: List<ShopHistoryEntry>, onPick: (ShopHistoryEntry) -> Unit, onForget: (ShopHistoryEntry) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var collapsed by remember { mutableStateOf(ShoppingStore.chipsCollapsed(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = if (collapsed) "Rozbaliť" else "Zbaliť") {
                    collapsed = !collapsed
                    ShoppingStore.setChipsCollapsed(context, collapsed)
                }
                .heightIn(min = 36.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Často kupuješ · ${chips.size}",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Sym.chevronRight,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).rotate(if (collapsed) 0f else 90f),
            )
        }
        if (!collapsed) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { c ->
                Row(
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(scheme.surfaceContainerLow)
                        .border(1.dp, scheme.surfaceVariant, RoundedCornerShape(20.dp))
                        .combinedClickable(
                            onClickLabel = "Pridať do zoznamu",
                            onLongClickLabel = "Odstrániť z návrhov",
                            onLongClick = { onForget(c) },
                            onClick = { onPick(c) },
                        )
                        .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Sym.add, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(c.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onSurface)
                }
            }
        }
    }
}

/** Potiahnutie riadku do ktorejkoľvek strany ho odstráni; pod riadkom sa ukáže červený pás s košom. */
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // poistka: potvrdenie sa môže zavolať aj viackrát, položka sa odstráni (a odpočíta z histórie) len raz
    var gone by remember { mutableStateOf(false) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                if (!gone) {
                    gone = true
                    onDelete()
                }
                true
            } else {
                false
            }
        },
        positionalThreshold = { total -> total * 0.4f },
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val active = state.targetValue != SwipeToDismissBoxValue.Settled
            val bg by animateColorAsState(if (active) scheme.errorContainer else scheme.surfaceContainerHigh, label = "swipe")
            val toStart = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                modifier = Modifier.fillMaxSize().background(bg).padding(horizontal = 22.dp),
                contentAlignment = if (toStart) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Icon(Sym.delete, contentDescription = null, tint = if (active) scheme.onErrorContainer else scheme.onSurfaceVariant)
            }
        },
    ) {
        // riadok musí mať vlastné pozadie, inak by pás s košom presvital
        Box(Modifier.background(scheme.surfaceContainerLow)) { content() }
    }
}

/** Jeden riadok zoznamu (min. 56 dp, celý je tlačidlo): krúžok + názov; kúpené je preškrtnuté a zosivené. Podržaním premenovanie. */
@Composable
private fun ShoppingItemRow(item: ShopItem, onToggle: () -> Unit, onEdit: () -> Unit, showAuthor: Boolean = true, showAvatar: Boolean = false, compact: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .combinedClickable(
                role = Role.Checkbox,
                onClickLabel = if (item.done) "Vrátiť medzi nekúpené" else "Označiť ako kúpené",
                onLongClickLabel = "Premenovať",
                onLongClick = onEdit,
                onClick = onToggle,
            )
            .semantics { stateDescription = if (item.done) "Kúpené" else "Nekúpené" }
            .padding(horizontal = if (compact) 10.dp else 18.dp, vertical = if (compact) 8.dp else 8.dp),
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
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = if (compact) 13.sp else 17.sp,
                fontWeight = FontWeight.Medium,
                color = if (item.done) scheme.onSurfaceVariant else scheme.onSurface,
                textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None,
            )
            if (showAuthor) {
                val by = ShoppingList.addedByText(item)
                if (by.isNotEmpty()) {
                    Text(by, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, fontSize = if (compact) 10.sp else 12.sp)
                }
            }
        }
        if (showAvatar) {
            val letter = item.addedByName.trim().firstOrNull()?.uppercase()
            if (letter != null) {
                val pal = listOf(Color(0xFF5C4033), Color(0xFF3A4A6B), Color(0xFF3D4F3A))
                val col = pal[(item.addedById.ifBlank { item.addedByName }.hashCode() and 0x7fffffff) % pal.size]
                Surface(shape = CircleShape, color = col, modifier = Modifier.size(22.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(letter, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Okno „Upraviť položku“ (podržaním riadku): nový názov, Uložiť / Zrušiť. */
@Composable
private fun RenameShopItemDialog(item: ShopItem, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(item.id) { mutableStateOf(item.name) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val ok = ShoppingList.clean(name).isNotEmpty()
    DomovDialog(
        onDismiss = onDismiss,
        title = { Text("Upraviť položku") },
        icon = { Icon(Sym.edit, null) },
        confirmButton = { DialogPrimaryButton(onClick = { onSave(name) }, enabled = ok) { Text("Uložiť") } },
        dismissButton = { DialogTextButton(onClick = onDismiss) { Text("Zrušiť") } },
    ) {
        DomovTextField(
            value = name,
            onValueChange = { name = it.take(ShoppingList.MAX_NAME) },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            label = { Text("Názov (aj s množstvom)") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (ok) onSave(name) }),
        )
    }
}
