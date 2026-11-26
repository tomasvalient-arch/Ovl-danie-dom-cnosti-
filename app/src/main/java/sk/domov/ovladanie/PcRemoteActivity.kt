package sk.domov.ovladanie

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sk.domov.ovladanie.pc.PcKeyboard
import sk.domov.ovladanie.pc.PcProto

/**
 * Obrazovka ovládania PC: na celú obrazovku obraz PC, navrchu panel s tlačidlami (klávesnica, klávesy, režim myši,
 * schránka, monitor, kvalita, napájanie). Spojenie sa drží, kým je obrazovka na popredí.
 */
class PcRemoteActivity : ComponentActivity() {
    private var session: PcSession? = null
    internal var remoteView: RemoteScreenView? = null
    private var firstWake = false
    /** „Písať na PC“ z karty Počítač: klávesnica sa otvorí raz, keď je PC pripojený. */
    internal var keyboardOnConnect = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent?.getStringExtra(EXTRA_PC)
        val entry = if (id != null) PcStore.get(this, id) else null
        if (entry == null) {
            Toast.makeText(this, "Tento PC už nie je spárovaný.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        firstWake = intent?.getBooleanExtra(EXTRA_WAKE, false) == true
        keyboardOnConnect = savedInstanceState == null && intent?.getBooleanExtra(EXTRA_KEYBOARD, false) == true
        val s = PcSession(applicationContext, entry)
        s.toaster = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        session = s
        applyKeepAwake(entry.keepAwake)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        setContent {
            HomeTheme(dark = true) { PcRemoteScreen(s, this) }
        }
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        val s = session ?: return
        if (s.phase == PcSession.Phase.IDLE) {
            s.start(firstWake)
            firstWake = false
        }
    }

    override fun onStop() {
        super.onStop()
        session?.stop()
    }

    override fun onDestroy() {
        session?.toaster = null
        session?.stop()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    internal fun applyKeepAwake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        const val EXTRA_PC = "pc"
        const val EXTRA_WAKE = "wake"
        const val EXTRA_KEYBOARD = "keyboard"

        /** Otvorí ovládanie PC; [wake] = najprv ho zapnúť cez Wake-on-LAN, [keyboard] = po pripojení otvoriť klávesnicu. */
        fun open(context: Context, serverId: String, wake: Boolean = false, keyboard: Boolean = false) {
            context.startActivity(
                Intent(context, PcRemoteActivity::class.java)
                    .putExtra(EXTRA_PC, serverId)
                    .putExtra(EXTRA_WAKE, wake)
                    .putExtra(EXTRA_KEYBOARD, keyboard),
            )
        }
    }
}

private class MenuItem(val label: String, val action: () -> Unit)

private fun mark(label: String, on: Boolean): String = (if (on) "✓  " else "     ") + label

@Composable
private fun PcRemoteScreen(session: PcSession, activity: PcRemoteActivity) {
    var toolbar by remember { mutableStateOf(true) }
    var keysBar by remember { mutableStateOf(false) }
    var powerAsk by remember { mutableIntStateOf(0) }
    BackHandler { activity.finish() }
    LaunchedEffect(session.phase) {
        if (session.phase == PcSession.Phase.ONLINE && activity.keyboardOnConnect) {
            activity.keyboardOnConnect = false
            activity.remoteView?.showKeyboard()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black).imePadding()) {
        AndroidView(
            factory = { ctx ->
                val v = RemoteScreenView(ctx)
                v.attach(session)
                activity.remoteView = v
                v
            },
            modifier = Modifier.fillMaxSize(),
        )

        Column(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().displayCutoutPadding()) {
            if (toolbar) {
                Toolbar(
                    session = session,
                    activity = activity,
                    keysBar = keysBar,
                    onKeysBar = { keysBar = !keysBar },
                    onPower = { powerAsk = it },
                    onHide = { toolbar = false },
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                    BarChip("☰") { toolbar = true }
                }
            }
        }

        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            val banner = session.banner
            if (banner != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(8.dp),
                ) {
                    Text(banner, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (keysBar) KeysBar(session)
        }

        StatusOverlay(session = session, onClose = { activity.finish() })
    }

    if (powerAsk != 0) {
        val action = powerAsk
        val title: String
        val text: String
        val confirm: String
        when (action) {
            PcProto.POWER_LOCK -> { title = "Zamknúť PC?"; text = "PC sa zamkne. Odomkneš ho na PC (zamknutú obrazovku z telefónu ovládať nejde)."; confirm = "Zamknúť" }
            PcProto.POWER_SLEEP -> { title = "Uspať PC?"; text = "PC prejde do spánku. Zobudíš ho cez Wake-on-LAN (ak ho PC podporuje) alebo na PC."; confirm = "Uspať" }
            PcProto.POWER_RESTART -> { title = "Reštartovať PC?"; text = "Neuložená práca na PC sa môže stratiť. Po reštarte sa appka pripojí znova sama."; confirm = "Reštartovať" }
            else -> { title = "Vypnúť PC?"; text = "Neuložená práca na PC sa môže stratiť. Zapneš ho cez Wake-on-LAN (ak ho PC podporuje) alebo na PC."; confirm = "Vypnúť" }
        }
        DomovAlert(
            onDismissRequest = { powerAsk = 0 },
            title = { Text(title) },
            text = { Text(text) },
            danger = action == PcProto.POWER_RESTART || action == PcProto.POWER_SHUTDOWN,
            confirmButton = {
                DialogPrimaryButton(onClick = {
                    session.power(action)
                    powerAsk = 0
                }) { Text(confirm) }
            },
            dismissButton = { DialogTextButton(onClick = { powerAsk = 0 }) { Text("Zrušiť") } },
        )
    }
}

@Composable
private fun Toolbar(
    session: PcSession,
    activity: PcRemoteActivity,
    keysBar: Boolean,
    onKeysBar: () -> Unit,
    onPower: (Int) -> Unit,
    onHide: () -> Unit,
) {
    val entry = session.entry
    val info = session.info
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarChip("Klávesnica") { activity.remoteView?.toggleKeyboard() }
        BarChip("Klávesy", active = keysBar) { onKeysBar() }
        BarChip(if (entry.direct) "Priamy dotyk" else "Touchpad") {
            session.changeEntry { it.copy(direct = !it.direct) }
        }
        MenuChip(
            "Schránka",
            listOf(
                MenuItem("Z PC do telefónu (skopíruje označené)") { session.copyFromPc() },
                MenuItem("Z telefónu do PC (a vloží)") { session.pasteToPc() },
            ),
        )
        if (info != null && info.monitors.size > 1) {
            MenuChip(
                "Monitor",
                info.monitors.map { m ->
                    MenuItem(mark("Monitor ${m.index + 1}${if (m.primary) " (hlavný)" else ""}  ${m.w}×${m.h}", m.index == session.currentMonitor())) {
                        session.selectMonitor(m.index)
                    }
                },
            )
        }
        MenuChip(
            "Obraz a myš",
            listOf(
                MenuItem(mark("Kvalita: automaticky", entry.profile == 0)) { session.setProfile(0) },
                MenuItem(mark("Kvalita: rýchla (menšie rozlíšenie)", entry.profile == 1)) { session.setProfile(1) },
                MenuItem(mark("Kvalita: ostrá (plné rozlíšenie)", entry.profile == 2)) { session.setProfile(2) },
                MenuItem("Zrušiť priblíženie") { activity.remoteView?.resetZoom() },
                MenuItem(mark("Citlivosť myši: nízka", entry.sensitivity == 0)) { session.changeEntry { it.copy(sensitivity = 0) } },
                MenuItem(mark("Citlivosť myši: stredná", entry.sensitivity == 1)) { session.changeEntry { it.copy(sensitivity = 1) } },
                MenuItem(mark("Citlivosť myši: vysoká", entry.sensitivity == 2)) { session.changeEntry { it.copy(sensitivity = 2) } },
                MenuItem(mark("Prirodzené rolovanie", entry.natural)) { session.changeEntry { it.copy(natural = !it.natural) } },
                MenuItem(mark("Nechať obrazovku zapnutú", entry.keepAwake)) {
                    session.changeEntry { it.copy(keepAwake = !it.keepAwake) }
                    activity.applyKeepAwake(!entry.keepAwake)
                },
            ),
        )
        if (info != null && info.canPower()) {
            MenuChip(
                "PC",
                listOf(
                    MenuItem("Zamknúť") { onPower(PcProto.POWER_LOCK) },
                    MenuItem("Uspať") { onPower(PcProto.POWER_SLEEP) },
                    MenuItem("Reštartovať") { onPower(PcProto.POWER_RESTART) },
                    MenuItem("Vypnúť") { onPower(PcProto.POWER_SHUTDOWN) },
                ),
            )
        }
        BarChip("Skryť") { onHide() }
        BarChip("Zavrieť") { activity.finish() }
    }
}

@Composable
private fun BarChip(text: String, active: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (active) scheme.primary else scheme.surfaceContainerHigh.copy(alpha = 0.92f),
        contentColor = if (active) scheme.onPrimary else scheme.onSurface,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Composable
private fun MenuChip(text: String, items: List<MenuItem>) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarChip(text) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (item in items) {
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = {
                        open = false
                        item.action()
                    },
                )
            }
        }
    }
}

/** Tlačidlo klávesy; pri [repeat] sa po podržaní opakuje (šípky, Backspace). */
@Composable
private fun KeyChip(text: String, repeat: Boolean = false, onKey: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val current by rememberUpdatedState(onKey)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceContainerHigh)
            .pointerInput(repeat) {
                detectTapGestures(
                    onPress = {
                        current()
                        if (repeat) {
                            coroutineScope {
                                val job = launch {
                                    delay(400)
                                    while (true) {
                                        current()
                                        delay(60)
                                    }
                                }
                                tryAwaitRelease()
                                job.cancel()
                            }
                        } else {
                            tryAwaitRelease()
                        }
                    },
                )
            }
            .padding(horizontal = 13.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun ModChip(text: String, state: Int, onClick: () -> Unit) {
    val label = if (state == PcKeyboard.LOCKED) "$text 🔒" else text
    BarChip(label, active = state != PcKeyboard.OFF, onClick = onClick)
}

@Composable
private fun KeysBar(session: PcSession) {
    @Suppress("UNUSED_VARIABLE")
    val version = session.modVersion // čítanie stavu: panel sa prekreslí, keď sa zmení stav Ctrl/Alt/Shift/Win
    val kb = session.keyboard
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xE6101010))
            .horizontalScroll(rememberScrollState())
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyChip("Esc") { kb.press(PcKeyboard.VK_ESCAPE) }
        KeyChip("Tab", repeat = true) { kb.press(PcKeyboard.VK_TAB) }
        ModChip("Ctrl", kb.state(PcKeyboard.VK_CONTROL)) { kb.toggle(PcKeyboard.VK_CONTROL) }
        ModChip("Alt", kb.state(PcKeyboard.VK_MENU)) { kb.toggle(PcKeyboard.VK_MENU) }
        ModChip("Shift", kb.state(PcKeyboard.VK_SHIFT)) { kb.toggle(PcKeyboard.VK_SHIFT) }
        ModChip("Win", kb.state(PcKeyboard.VK_LWIN)) { kb.toggle(PcKeyboard.VK_LWIN) }
        KeyChip("←", repeat = true) { kb.press(PcKeyboard.VK_LEFT) }
        KeyChip("↑", repeat = true) { kb.press(PcKeyboard.VK_UP) }
        KeyChip("↓", repeat = true) { kb.press(PcKeyboard.VK_DOWN) }
        KeyChip("→", repeat = true) { kb.press(PcKeyboard.VK_RIGHT) }
        KeyChip("Enter", repeat = true) { kb.press(PcKeyboard.VK_RETURN) }
        KeyChip("⌫", repeat = true) { kb.press(PcKeyboard.VK_BACK) }
        KeyChip("Del", repeat = true) { kb.press(PcKeyboard.VK_DELETE) }
        KeyChip("Home") { kb.press(PcKeyboard.VK_HOME) }
        KeyChip("End") { kb.press(PcKeyboard.VK_END) }
        KeyChip("PgUp", repeat = true) { kb.press(PcKeyboard.VK_PRIOR) }
        KeyChip("PgDn", repeat = true) { kb.press(PcKeyboard.VK_NEXT) }
        KeyChip("Alt+Tab") { kb.chord(intArrayOf(PcKeyboard.VK_MENU), PcKeyboard.VK_TAB) }
        KeyChip("Win+D") { kb.chord(intArrayOf(PcKeyboard.VK_LWIN), PcKeyboard.VK_D) }
        KeyChip("Správca úloh") { kb.chord(intArrayOf(PcKeyboard.VK_CONTROL, PcKeyboard.VK_SHIFT), PcKeyboard.VK_ESCAPE) }
        for (i in 0 until 12) {
            KeyChip("F${i + 1}") { kb.press(PcKeyboard.VK_F1 + i) }
        }
    }
}

/** Zakrýva obraz, kým sa pripájame alebo keď pripojenie zlyhalo. */
@Composable
private fun StatusOverlay(session: PcSession, onClose: () -> Unit) {
    val phase = session.phase
    if (phase == PcSession.Phase.ONLINE) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD9000000))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (phase == PcSession.Phase.FAILED) {
                Text(
                    session.failure ?: "Pripojenie zlyhalo.",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = { session.retry() }) { Text("Pripojiť znova") }
                if (session.entry.macs.isNotEmpty()) {
                    FilledTonalButton(onClick = { session.wake() }) { Text("Zapnúť PC (Wake-on-LAN)") }
                }
                TextButton(onClick = onClose) { Text("Zavrieť") }
            } else {
                CircularProgressIndicator()
                Text(
                    session.status.ifBlank { "Pripájam sa…" },
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
                TextButton(onClick = onClose) { Text("Zrušiť") }
            }
        }
    }
}
