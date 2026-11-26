package sk.domov.ovladanie

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val EMOJI = listOf(
    "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😎",
    "🤔", "😅", "😇", "🙂", "😉", "😭", "😡", "😴",
    "👍", "👎", "👏", "🙏", "💪", "🔥", "✨", "💯",
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🤍", "🖤",
    "🎉", "🏠", "🍳", "🥗", "🍞", "🧀", "🥛", "☕",
    "🛒", "📎", "📅", "💡", "🔑", "☀️", "🌙", "✅",
)

@Composable
internal fun rememberChatTick(): Long {
    val context = LocalContext.current
    var tick by remember { mutableLongStateOf(ChatStore.revision(context)) }
    DisposableEffect(Unit) {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            tick = ChatStore.revision(context)
        }
        ChatStore.listen(context, l)
        onDispose { ChatStore.unlisten(context, l) }
    }
    return tick
}

@Composable
internal fun ChatTab(initialThread: String? = null, onConsumedInitial: () -> Unit = {}) {
    val context = LocalContext.current
    val tick = rememberChatTick()
    val others = remember(tick) { ChatEngine.otherMembers(context) }
    // Písať sa dá hneď, ako je telefón v domácnosti (kľúč sa zdieľal alebo prevzal). Druhý telefón nemusí byť
    // na domácej WiFi: zoznam členov sa plní správami „som doma“ po WiFi, ale chat ide cez internet.
    val ready = remember(tick) { Chat.isReady(others.size, ChatEngine.active(context)) }
    var thread by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(initialThread) {
        if (initialThread != null) {
            thread = initialThread
            onConsumedInitial()
        }
    }
    LaunchedEffect(others.size, ready) {
        if (thread == null && Chat.openSharedDirectly(others.size) && ready) {
            thread = Chat.THREAD_SHARED
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        ChatEngine.startListening(context)
        ChatEngine.schedule(context)
        ChatEngine.request(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val current = thread
    if (current != null) {
        val title = if (Chat.isShared(current)) "Spoločný" else {
            val id = current.removePrefix("dm:").split(":").firstOrNull { it != HouseholdStore.phoneId(context) } ?: current
            ChatEngine.displayName(context, id)
        }
        // kým druhý telefón nepoznáme zo správ „som doma“, mená berieme z prijatých správ chatu
        val heard = remember(tick) {
            if (others.isEmpty()) Chat.peerNames(ChatStore.list(context, Chat.THREAD_SHARED)) else emptyList()
        }
        val subtitleNames = if (Chat.isShared(current)) {
            val peers = if (others.isNotEmpty()) others.map { it.name.ifBlank { it.id } } else heard
            if (peers.isEmpty()) "zatiaľ nikto ďalší" else (listOf(HouseholdStore.name(context)) + peers).joinToString(", ")
        } else title
        ChatThreadScreen(
            thread = current,
            title = title,
            subtitle = "Šifrované · $subtitleNames",
            showBack = !Chat.openSharedDirectly(others.size) || initialThread != null,
            onBack = { thread = null },
        )
    } else if (!ready) {
        ChatEmptyState()
    } else {
        ChatListScreen(others = others, onOpen = { thread = it })
    }
}

@Composable
private fun ChatEmptyState() {
    TabPage("Chat") {
        DomovCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconCircle(Sym.chat, on = false)
                Spacer(Modifier.width(12.dp))
                Text("Zatiaľ si v chate sám", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Súkromný chat je medzi členmi domácnosti a ide šifrovane cez internet, druhý telefón nemusí byť na domácej WiFi. Pozvi ho v Nastavenia › Domácnosť a synchronizácia (Pozvánka textom): keď ju druhý člen vloží, chat sa otvorí. Históriu si tento telefón pamätá natrvalo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListScreen(others: List<Member>, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val tick = rememberChatTick()
    val self = HouseholdStore.phoneId(context)
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chat", style = MaterialTheme.typography.titleLarge)
                        Text("Šifrované", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                ChatListRow(
                    title = "Spoločný",
                    preview = ChatStore.last(context, Chat.THREAD_SHARED)?.let { previewLine(context, it) } ?: "Spoločná miestnosť",
                    whenText = ChatStore.last(context, Chat.THREAD_SHARED)?.let { timeLabel(it.createdAt) } ?: "",
                    unread = ChatStore.unread(context, Chat.THREAD_SHARED).also { tick },
                    leading = { Icon(Sym.groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp)) },
                    onClick = { onOpen(Chat.THREAD_SHARED) },
                )
            }
            items(others, key = { it.id }) { m ->
                val th = Chat.dmThread(self, m.id)
                ChatListRow(
                    title = m.name.ifBlank { "Telefón" },
                    preview = ChatStore.last(context, th)?.let { previewLine(context, it) } ?: "Súkromný rozhovor",
                    whenText = ChatStore.last(context, th)?.let { timeLabel(it.createdAt) } ?: "",
                    unread = ChatStore.unread(context, th),
                    leading = {
                        Text((m.name.ifBlank { "?" }).first().uppercase(), fontWeight = FontWeight.Bold, color = Color(0xFF8BB4E0))
                    },
                    onClick = { onOpen(th) },
                )
            }
        }
    }
}

@Composable
private fun ChatListRow(title: String, preview: String, whenText: String, unread: Int, leading: @Composable () -> Unit, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(scheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { leading() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (whenText.isNotEmpty()) Text(whenText, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (unread > 0) scheme.onSurface else scheme.onSurfaceVariant,
                    fontWeight = if (unread > 0) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.height(18.dp).widthIn(min = 18.dp).clip(RoundedCornerShape(9.dp)).background(scheme.primary).padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (unread > 99) "99+" else unread.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = scheme.onPrimary)
                    }
                }
            }
        }
    }
}

private fun previewLine(c: android.content.Context, m: ChatMessage): String {
    val who = if (m.mine) "Ty: " else ""
    return who + Chat.previewBody(m.kind, m.text, m.fileName)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatThreadScreen(thread: String, title: String, subtitle: String, showBack: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick = rememberChatTick()
    val messages = remember(tick, thread) { ChatStore.list(context, thread) }
    val others = remember(tick) { ChatEngine.otherMembers(context).map { it.id } }
    var draft by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    var viewPhoto by remember { mutableStateOf<ChatMessage?>(null) }
    val listState = rememberLazyListState()
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    LaunchedEffect(thread) { ChatEngine.onThreadVisible(context, thread) }
    DisposableEffect(thread) { onDispose { ChatEngine.onThreadHidden(context, thread) } }
    LaunchedEffect(owner, thread) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            ChatEngine.onThreadVisible(context, thread)
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                // Appka v pozadí / zamknutá obrazovka: nové správy majú prísť ako oznámenie, nie ako prečítané.
                ChatEngine.onThreadHidden(context, thread)
            }
        }
    }
    if (showBack) BackHandler { onBack() }

    val photoDir = remember { File(context.cacheDir, "chat-capture").also { it.mkdirs() } }
    var captureFile by remember { mutableStateOf<File?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = captureFile
        if (ok && f != null && f.isFile) {
            sending = true
            scope.launch(LogOnError) {
                try {
                    hint = withContext(Dispatchers.IO) { ChatEngine.sendPhoto(context, thread, Uri.fromFile(f)) }
                } finally {
                    sending = false
                    f.delete()
                }
            }
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            sending = true
            scope.launch(LogOnError) {
                try {
                    hint = withContext(Dispatchers.IO) { ChatEngine.sendPhoto(context, thread, uri) }
                } finally {
                    sending = false
                }
            }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            sending = true
            scope.launch(LogOnError) {
                try {
                    hint = withContext(Dispatchers.IO) { ChatEngine.sendFile(context, thread, uri) }
                } finally {
                    sending = false
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                IconButton(onClick = onBack) { Icon(Sym.arrowBack, contentDescription = "Späť") }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Sym.lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val rows = remember(messages) { withSeparators(messages) }
        // Po novej správe aj po otvorení klávesnice skoč na posledný riadok (riadky obsahujú aj oddeľovače dní).
        val imeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
        LaunchedEffect(rows.size, imeOpen) {
            if (rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is ChatRow.Sep -> DateSep(row.label)
                    is ChatRow.Msg -> MessageBubble(
                        m = row.msg,
                        tick = Chat.tick(row.msg.mine, row.msg.status, row.msg.readers, others),
                        onPhoto = { viewPhoto = row.msg },
                        onFile = { ChatEngine.openFile(context, row.msg) },
                    )
                }
            }
        }
        if (hint != null) {
            Text(
                hint!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clickable { hint = null },
            )
        }
        ComposerBar(
            text = draft,
            onText = { draft = it },
            onEmoji = { showEmoji = !showEmoji },
            onAttach = { showAttach = true },
            sending = sending,
            onSend = {
                val t = draft
                draft = ""
                showEmoji = false
                sending = true
                scope.launch(LogOnError) {
                    try {
                        hint = withContext(Dispatchers.IO) { ChatEngine.sendText(context, thread, t) }
                    } finally {
                        sending = false
                    }
                }
            },
        )
        if (showEmoji) EmojiPanel(onPick = { draft += it })
    }

    if (showAttach) {
        ModalBottomSheet(onDismissRequest = { showAttach = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Text("Priložiť", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            AttachRow(Sym.photo, "Fotka", "Z galérie v telefóne") {
                showAttach = false
                pickImage.launch("image/*")
            }
            AttachRow(Sym.photoCamera, "Fotoaparát", "Odfotiť teraz") {
                showAttach = false
                val f = File(photoDir, "cap-${System.currentTimeMillis()}.jpg")
                captureFile = f
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                takePicture.launch(uri)
            }
            AttachRow(Sym.draft, "Súbor", "PDF, dokument alebo iný súbor") {
                showAttach = false
                pickFile.launch(arrayOf("*/*"))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    viewPhoto?.let { p ->
        PhotoViewer(m = p, onClose = { viewPhoto = null })
    }
}

private sealed class ChatRow(val key: String) {
    class Sep(val label: String) : ChatRow("sep-$label")
    class Msg(val msg: ChatMessage) : ChatRow(msg.id)
}

private fun withSeparators(messages: List<ChatMessage>): List<ChatRow> {
    val out = ArrayList<ChatRow>()
    var lastDay = ""
    val fmt = SimpleDateFormat("d. MMMM", Locale("sk"))
    val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    val cal = Calendar.getInstance()
    val today = dayFmt.format(cal.time)
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = dayFmt.format(cal.time)
    for (m in messages) {
        val day = dayFmt.format(Date(m.createdAt))
        if (day != lastDay) {
            lastDay = day
            val label = when (day) {
                today -> "Dnes"
                yesterday -> "Včera"
                else -> fmt.format(Date(m.createdAt))
            }
            out += ChatRow.Sep(label)
        }
        out += ChatRow.Msg(m)
    }
    return out
}

@Composable
private fun DateSep(label: String) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surfaceContainer)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(m: ChatMessage, tick: Chat.Tick, onPhoto: () -> Unit, onFile: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val mine = m.mine
    val bg = if (mine) Color(0xFF3A2A18) else scheme.surfaceContainerHighest
    val border = if (mine) Color(0xFF7A4E16) else scheme.outlineVariant
    val shape = RoundedCornerShape(
        topStart = 18.dp, topEnd = 18.dp,
        bottomStart = if (mine) 18.dp else 6.dp,
        bottomEnd = if (mine) 6.dp else 18.dp,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 280.dp)
                .clip(shape)
                .background(bg)
                .border(1.dp, border, shape)
                .then(
                    when (m.kind) {
                        Chat.KIND_PHOTO -> Modifier.clickable(onClick = onPhoto).padding(4.dp)
                        Chat.KIND_FILE -> Modifier.clickable(onClick = onFile).padding(8.dp)
                        else -> Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)
                    },
                ),
        ) {
            when (m.kind) {
                Chat.KIND_PHOTO -> {
                    val bmp = remember(m.localPath) { loadThumb(m.localPath) }
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(),
                            contentDescription = "Fotka",
                            modifier = Modifier.fillMaxWidth().height(156.dp).clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                            Text("Fotka sa sťahuje…", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Chat.KIND_FILE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A1700)),
                            contentAlignment = Alignment.Center,
                        ) {
                            val ext = m.fileName.substringAfterLast('.', "SÚBOR").take(4).uppercase()
                            Text(ext.ifBlank { "SÚBOR" }, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = scheme.primary)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(m.fileName.ifBlank { "Súbor" }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(sizeLabel(m.fileSize), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        }
                    }
                }
                else -> {
                    Text(m.text, style = MaterialTheme.typography.bodyLarge, lineHeight = 21.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp, end = if (m.kind == Chat.KIND_PHOTO) 6.dp else 0.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(clock(m.createdAt), fontSize = 10.5.sp, fontWeight = FontWeight.Medium, color = scheme.onSurfaceVariant)
                if (tick != Chat.Tick.NONE) {
                    Spacer(Modifier.width(4.dp))
                    TickMarks(tick)
                }
            }
        }
    }
}

@Composable
private fun TickMarks(tick: Chat.Tick) {
    val color = if (tick == Chat.Tick.READ) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val text = if (tick == Chat.Tick.READ) "✓✓" else "✓"
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun ComposerBar(text: String, onText: (String) -> Unit, onEmoji: () -> Unit, onAttach: () -> Unit, sending: Boolean, onSend: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onAttach, modifier = Modifier.size(40.dp).clip(CircleShape).background(scheme.surfaceContainerLow).border(1.dp, scheme.outlineVariant, CircleShape)) {
            Icon(Sym.add, contentDescription = "Priložiť")
        }
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(scheme.surfaceContainerLow).border(1.dp, scheme.outlineVariant, RoundedCornerShape(22.dp)).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (text.isEmpty()) Text("Správa", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(
                    value = text,
                    onValueChange = onText,
                    textStyle = TextStyle(color = scheme.onSurface, fontSize = 15.sp),
                    cursorBrush = SolidColor(scheme.primary),
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            IconButton(onClick = onEmoji) { Icon(Sym.mood, contentDescription = "Emoji", tint = scheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(8.dp))
        val can = text.isNotBlank() && !sending
        IconButton(
            onClick = onSend,
            enabled = can,
            modifier = Modifier.size(40.dp).clip(CircleShape).background(if (can) scheme.primaryContainer else scheme.surfaceContainerLow),
        ) {
            Icon(Sym.send, contentDescription = "Odoslať", tint = if (can) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmojiPanel(onPick: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(Color(0xFF1A1816))) {
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(8),
            modifier = Modifier.fillMaxWidth().height(220.dp),
            contentPadding = PaddingValues(8.dp),
        ) {
            items(EMOJI.size) { i ->
                Box(Modifier.size(36.dp).clickable { onPick(EMOJI[i]) }, contentAlignment = Alignment.Center) {
                    Text(EMOJI[i], fontSize = 22.sp)
                }
            }
        }
    }
}

@Composable
private fun AttachRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, cap: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(cap, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PhotoViewer(m: ChatMessage, onClose: () -> Unit) {
    val bmp = remember(m.localPath) { loadFull(m.localPath) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose)) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = "Fotka", modifier = Modifier.fillMaxSize().align(Alignment.Center), contentScale = ContentScale.Fit)
            }
            Column(Modifier.padding(start = 8.dp, top = 28.dp, end = 16.dp)) {
                IconButton(onClick = onClose) { Icon(Sym.close, contentDescription = "Zavrieť", tint = Color.White) }
                Text(m.senderName, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(timeLabel(m.createdAt), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun loadThumb(path: String): android.graphics.Bitmap? {
    if (path.isBlank() || !File(path).isFile) return null
    return try {
        val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, b)
        val s = maxOf(1, maxOf(b.outWidth, b.outHeight) / 600)
        android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
    } catch (_: Exception) { null }
}

private fun loadFull(path: String): android.graphics.Bitmap? {
    if (path.isBlank() || !File(path).isFile) return null
    return try { android.graphics.BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
}

private fun clock(at: Long): String = SimpleDateFormat("HH:mm", Locale("sk")).format(Date(at))

private fun timeLabel(at: Long): String {
    val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    val cal = Calendar.getInstance()
    val today = dayFmt.format(cal.time)
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = dayFmt.format(cal.time)
    val day = dayFmt.format(Date(at))
    val t = clock(at)
    return when (day) {
        today -> t
        yesterday -> "včera"
        else -> SimpleDateFormat("d. M.", Locale("sk")).format(Date(at))
    }
}

private fun sizeLabel(size: Long): String = when {
    size <= 0L -> "Súbor"
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${(size + 512) / 1024} kB"
    else -> String.format(Locale("sk"), "%.1f MB", size / (1024.0 * 1024.0))
}
