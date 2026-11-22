package sk.domov.ovladanie

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import sk.domov.ovladanie.pc.PcClient
import sk.domov.ovladanie.pc.PcCrypto
import sk.domov.ovladanie.pc.PcDiscovery
import sk.domov.ovladanie.pc.PcException
import sk.domov.ovladanie.pc.PcKeyboard
import sk.domov.ovladanie.pc.PcProto
import sk.domov.ovladanie.pc.PcWol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Jedno ovládanie PC: spojenie s PC (s opätovným pripájaním), obraz, vstupy a stav pre obrazovku ovládania.
 * Žije, kým je otvorená obrazovka ovládania; po odchode z appky sa spojenie zatvorí a po návrate obnoví.
 */
class PcSession(context: Context, initial: PcEntry) : PcKeyboard.Sink {
    enum class Phase { IDLE, CONNECTING, ONLINE, FAILED }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // --- stav pre obrazovku (čítajú ho Compose aj View) ---
    var entry by mutableStateOf(initial)
        private set
    var phase by mutableStateOf(Phase.IDLE)
        private set
    var status by mutableStateOf("")
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var info by mutableStateOf<PcClient.Info?>(null)
        private set
    var deskLocked by mutableStateOf(false)
        private set
    var banner by mutableStateOf<String?>(null)
        private set
    var modVersion by mutableIntStateOf(0)
        private set

    /** Obraz PC; píše do neho vlákno prijímania, kreslí ho View (oboje pod [frameLock]). */
    val frameLock = Any()
    @Volatile var frame: Bitmap? = null
        private set
    @Volatile private var screenEpoch: Long = -1
    @Volatile var cursorX16 = 0
        private set
    @Volatile var cursorY16 = 0
        private set
    @Volatile var cursorVisible = false
        private set
    /** Zvyšuje sa pri každej zmene polohy kurzora; View podľa toho vie, že má sledovať kurzor. */
    @Volatile var cursorVersion = 0
        private set

    /** Zavolá sa (z ľubovoľného vlákna) po zmene obrazu alebo kurzora. */
    @Volatile var onFrame: (() -> Unit)? = null
    /** Zavolá sa na hlavnom vlákne s krátkou správou pre používateľa. */
    @Volatile var toaster: ((String) -> Unit)? = null

    val keyboard = PcKeyboard(this)

    private val generation = AtomicInteger(0)
    @Volatile private var running = false
    @Volatile private var wakeFirst = false
    @Volatile private var client: PcClient? = null
    @Volatile private var active: Conn? = null
    @Volatile private var powerAction = 0
    @Volatile private var powerAt = 0L
    private var supervisor: Thread? = null
    private var wifiLock: WifiManager.WifiLock? = null

    init {
        keyboard.setListener(object : PcKeyboard.Listener {
            override fun modifiersChanged() {
                main.post { modVersion = modVersion + 1 }
            }
        })
    }

    private fun say(msg: String) {
        main.post { toaster?.invoke(msg) }
    }

    // ------------------------------------------------------------------ vstupy (neblokujúce; bez spojenia nič nerobia)

    override fun key(vk: Int, down: Boolean) {
        client?.key(vk, down)
    }

    override fun text(s: String) {
        client?.text(s)
    }

    fun moveRel(dx: Int, dy: Int) { client?.moveRel(dx, dy) }

    fun moveAbs(x16: Int, y16: Int) { client?.moveAbs(x16, y16) }

    fun button(button: Int, down: Boolean) { client?.button(button, down) }

    fun scroll(dx: Int, dy: Int) { client?.wheel(dx, dy) }

    // ------------------------------------------------------------------ nastavenia

    fun changeEntry(change: (PcEntry) -> PcEntry) {
        val next = PcStore.update(app, entry.serverId, change) ?: return
        entry = next
    }

    private fun profileConfig(p: Int): IntArray = when (p) {
        1 -> intArrayOf(15, 50, 2)  // rýchly: polovičné rozlíšenie
        2 -> intArrayOf(12, 88, 1)  // ostrý: plné rozlíšenie
        else -> intArrayOf(15, 70, 0) // automaticky
    }

    fun applyConfig() {
        val c = profileConfig(entry.profile)
        client?.config(c[0], c[1], c[2], entry.monitor)
    }

    fun setProfile(p: Int) {
        changeEntry { it.copy(profile = p) }
        applyConfig()
    }

    /** Monitor, ktorý sa práve zobrazuje (podľa PC; kým PC nepošle údaje, podľa uloženej voľby). */
    fun currentMonitor(): Int {
        val i = info
        val chosen = entry.monitor
        if (i == null) return if (chosen == 255) 0 else chosen
        // PC pošle zoznam monitorov len pri zmene zostavy, takže aktívny je ten, ktorý sme si vybrali (ak ešte existuje)
        if (chosen != 255 && i.monitors.any { it.index == chosen }) return chosen
        return i.activeMonitor
    }

    fun selectMonitor(index: Int) {
        changeEntry { it.copy(monitor = index) }
        applyConfig()
    }

    // ------------------------------------------------------------------ napájanie a schránka

    fun power(action: Int) {
        if (action == PcProto.POWER_SLEEP || action == PcProto.POWER_SHUTDOWN || action == PcProto.POWER_RESTART) {
            powerAction = action
            powerAt = System.currentTimeMillis()
        }
        client?.power(action)
    }

    /** Skopíruje označené na PC (Ctrl+C) a pošle text do schránky telefónu. */
    fun copyFromPc() {
        val c = client ?: return
        keyboard.chord(intArrayOf(PcKeyboard.VK_CONTROL), PcKeyboard.VK_C)
        main.postDelayed({ c.clipGet() }, 300)
    }

    /** Pošle schránku telefónu do schránky PC a vloží ju (Ctrl+V). */
    fun pasteToPc() {
        val c = client ?: return
        val cm = app.getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(app)?.toString().orEmpty() else ""
        if (text.isEmpty()) {
            say("Schránka telefónu je prázdna.")
            return
        }
        if (text.toByteArray(Charsets.UTF_8).size > 256 * 1024) {
            say("Text v schránke je príliš dlhý (najviac 256 KB).")
            return
        }
        c.clipSet(text)
        keyboard.chord(intArrayOf(PcKeyboard.VK_CONTROL), PcKeyboard.VK_V)
    }

    private fun putOnPhoneClipboard(text: String) {
        if (text.isEmpty()) {
            say("Na PC nie je nič skopírované.")
            return
        }
        val cm = app.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("PC", text))
        // od Androidu 13 systém sám ukáže „skopírované“
        if (Build.VERSION.SDK_INT < 33) say("Skopírované z PC (${text.length} znakov)")
    }

    // ------------------------------------------------------------------ spojenie

    private fun alive(gen: Int) = running && generation.get() == gen

    /** Spustí pripájanie. [wake] = najprv zapnúť PC (Wake-on-LAN) a čakať, kým naštartuje. */
    @Synchronized
    fun start(wake: Boolean = false) {
        if (running) return
        running = true
        wakeFirst = wake
        val gen = generation.incrementAndGet()
        phase = Phase.CONNECTING
        status = if (wake) "Zapínam PC…" else "Pripájam sa…"
        failure = null
        val t = Thread({ supervise(gen) }, "pc-session")
        t.isDaemon = true
        supervisor = t
        t.start()
    }

    /** Zatvorí spojenie (pri odchode z obrazovky). Držané klávesy PC pustí samo pri odpojení. */
    fun stop() {
        synchronized(this) {
            running = false
            generation.incrementAndGet()
        }
        val c = client
        client = null
        active = null
        keyboard.resetWithoutSending()
        supervisor?.interrupt()
        if (c != null) Thread({ c.close() }, "pc-close").start()
        releaseWifiLock()
        phase = Phase.IDLE
        deskLocked = false
        banner = null
    }

    /** Po zlyhaní: skúsiť znova. */
    fun retry() {
        stopSupervisorIfFailed()
        start(false)
    }

    /** Po zlyhaní: zapnúť PC cez Wake-on-LAN a čakať na pripojenie. */
    fun wake() {
        stopSupervisorIfFailed()
        start(true)
    }

    private fun stopSupervisorIfFailed() {
        if (phase == Phase.FAILED) {
            synchronized(this) {
                running = false
                generation.incrementAndGet()
            }
        }
    }

    private class Attempt(val client: PcClient?, val fatal: String?, val message: String?)

    private inner class Conn : PcClient.Listener() {
        @Volatile var client: PcClient? = null
        val closed = CountDownLatch(1)
        @Volatile var reason: String = ""
        @Volatile var replaced = false
        private var gotInfo = false

        private fun current() = active === this

        override fun onAttached(client: PcClient) {
            this.client = client
        }

        override fun onInfo(info: PcClient.Info) {
            if (!current()) return
            main.post { this@PcSession.info = info }
            if (!gotInfo) {
                gotInfo = true
                // zapamätať názov PC a MAC adresy (na Wake-on-LAN)
                val macs = info.macs.map { PcWol.formatMac(it) }
                PcStore.update(app, entry.serverId) { it.copy(name = info.name.ifBlank { it.name }, macs = if (macs.isEmpty()) it.macs else macs) }
                    ?.let { next -> main.post { entry = next } }
            }
        }

        override fun onScreen(screen: PcClient.Screen) {
            if (!current()) return
            synchronized(frameLock) {
                val bmp = Bitmap.createBitmap(screen.outW, screen.outH, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.BLACK)
                frame = bmp
                screenEpoch = screen.epoch
            }
            onFrame?.invoke()
        }

        override fun onRects(epoch: Long, seq: Long, rects: List<PcClient.Rect>) {
            if (!current()) return
            if (epoch == screenEpoch) {
                synchronized(frameLock) {
                    val bmp = frame
                    if (bmp != null) {
                        val canvas = Canvas(bmp)
                        for (r in rects) {
                            val band = BitmapFactory.decodeByteArray(r.jpeg, 0, r.jpeg.size) ?: continue
                            if (band.width == bmp.width && r.y >= 0 && r.y + band.height <= bmp.height) {
                                canvas.drawBitmap(band, 0f, r.y.toFloat(), null)
                            }
                            band.recycle()
                        }
                    }
                }
                onFrame?.invoke()
            }
            // potvrdiť vždy (aj zastaraný obraz), inak by PC prestalo posielať
            client?.ack(seq)
        }

        override fun onCursor(x16: Int, y16: Int, visible: Boolean) {
            if (!current()) return
            cursorX16 = x16
            cursorY16 = y16
            cursorVisible = visible
            cursorVersion += 1
            onFrame?.invoke()
        }

        override fun onClip(text: String) {
            main.post { putOnPhoneClipboard(text) }
        }

        override fun onNotice(code: Int, message: String) {
            if (!current()) return
            when (code) {
                PcProto.NOTICE_DESKTOP_LOCKED -> main.post { deskLocked = true; banner = message }
                PcProto.NOTICE_DESKTOP_OK -> main.post { deskLocked = false; banner = null }
                PcProto.NOTICE_CAPTURE_ERROR -> main.post { banner = message }
                PcProto.NOTICE_REPLACED -> replaced = true
                PcProto.NOTICE_CLIPBOARD_ERROR -> say(message)
                PcProto.NOTICE_POWER_ACCEPTED -> say("PC prijal príkaz.")
                else -> if (message.isNotBlank()) say(message)
            }
        }

        override fun onClosed(reason: String) {
            this.reason = reason
            closed.countDown()
        }
    }

    private fun connectHost(gen: Int, conn: Conn, network: android.net.Network, host: String, port: Int): Attempt {
        val e = entry
        return try {
            val c = PcClient.connectPaired(
                host, port, PcStore.deviceId(app), e.key, PcCrypto.unhex(e.serverId), PcStore.phoneName(),
                network.socketFactory, 2500, conn,
            )
            Attempt(c, null, null)
        } catch (ex: PcException) {
            when (ex.code) {
                PcProto.ERR_UNKNOWN_DEVICE, PcProto.ERR_BAD_PROOF, PcProto.ERR_PROTOCOL, PcProto.ERR_LOCKED_OUT ->
                    Attempt(null, ex.message ?: "PC odmietol pripojenie.", null)
                else -> Attempt(null, null, ex.message)
            }
        } catch (ex: Exception) {
            Attempt(null, null, ex.message ?: "Pripojenie zlyhalo.")
        }
    }

    /** Jeden pokus o pripojenie: známe adresy, potom hľadanie PC v sieti podľa ID. */
    private fun tryConnect(gen: Int, conn: Conn): Attempt {
        val network = wifiNetwork(app) ?: return Attempt(null, null, PcNet.NO_WIFI)
        val e = entry
        val hosts = e.hosts.filter { PcNet.isPrivateIpv4(it) }
        for (h in hosts) {
            if (!alive(gen)) return Attempt(null, null, "Zrušené.")
            val r = connectHost(gen, conn, network, h, e.port)
            if (r.client != null || r.fatal != null) return r
        }
        if (!alive(gen)) return Attempt(null, null, "Zrušené.")
        val found = try {
            PcDiscovery.discover(PcNet.targets(app, network, hosts), e.port, 1500, PcNet.binder(network))
        } catch (ex: Exception) {
            emptyList<PcDiscovery.Found>()
        }
        val match = found.firstOrNull { it.serverId == e.serverId }
        if (match != null && alive(gen)) {
            val r = connectHost(gen, conn, network, match.host, match.port)
            if (r.client != null) {
                // PC má novú adresu (DHCP): zapamätať
                val updated = (listOf(match.host) + e.hosts).distinct().take(4)
                PcStore.update(app, e.serverId) { it.copy(hosts = updated, port = match.port) }
                    ?.let { next -> main.post { entry = next } }
            }
            if (r.client != null || r.fatal != null) return r
        }
        return Attempt(null, null, "PC sa nenašiel v sieti. Je zapnutý a beží na ňom program Domov PC?")
    }

    private fun fail(gen: Int, message: String) {
        if (generation.get() != gen) return
        running = false
        releaseWifiLock()
        main.post {
            if (generation.get() == gen) {
                failure = message
                phase = Phase.FAILED
            }
        }
    }

    private fun supervise(gen: Int) {
        try {
            var everConnected = false
            val startedAt = System.currentTimeMillis()
            var deadline = startedAt + (if (wakeFirst) 240_000L else 0L)
            var lastWake = 0L
            var attempt = 0
            while (alive(gen)) {
                // zapnutie PC: signál hneď a potom každých 12 s, kým sa PC neozve
                if (wakeFirst && System.currentTimeMillis() - lastWake > 12_000L) {
                    lastWake = System.currentTimeMillis()
                    val net = wifiNetwork(app)
                    if (net != null) {
                        val e = entry
                        PcNet.wakeBlocking(app, net, e.macs, e.hosts)
                    }
                }
                val conn = Conn()
                active = conn
                val r = tryConnect(gen, conn)
                val c = r.client
                if (c != null) {
                    everConnected = true
                    attempt = 0
                    wakeFirst = false
                    client = c
                    acquireWifiLock()
                    main.post {
                        if (generation.get() == gen) {
                            phase = Phase.ONLINE
                            status = ""
                            failure = null
                            deskLocked = false
                            banner = null
                        }
                    }
                    applyConfig()
                    // čakáme, kým sa spojenie neskončí (alebo kým nás niekto nezastaví)
                    while (alive(gen) && !conn.closed.await(500, TimeUnit.MILLISECONDS)) {
                        // nič
                    }
                    if (!alive(gen)) return
                    client = null
                    conn.client?.close()
                    keyboard.resetWithoutSending()
                    val recentPower = if (System.currentTimeMillis() - powerAt < 30_000L) powerAction else 0
                    powerAction = 0
                    if (conn.replaced) {
                        fail(gen, "Pripojil sa iný telefón, ktorý ťa vytlačil. Ťuknutím na „Pripojiť znova“ ovládanie prevezmeš späť.")
                        return
                    }
                    if (recentPower == PcProto.POWER_SLEEP || recentPower == PcProto.POWER_SHUTDOWN) {
                        fail(gen, if (recentPower == PcProto.POWER_SLEEP) "PC spí." else "PC sa vypol.")
                        return
                    }
                    deadline = System.currentTimeMillis() + (if (recentPower == PcProto.POWER_RESTART) 240_000L else 60_000L)
                    main.post {
                        if (generation.get() == gen) {
                            phase = Phase.CONNECTING
                            status = if (recentPower == PcProto.POWER_RESTART) "PC sa reštartuje, čakám na neho…" else "Spojenie sa prerušilo. Pripájam znova…"
                        }
                    }
                    Thread.sleep(800)
                    continue
                }
                if (r.fatal != null) {
                    fail(gen, r.fatal)
                    return
                }
                attempt++
                val msg = r.message ?: "Pripojenie zlyhalo."
                // prvý pokus zlyhal: hneď to povedať; po prerušení spojenia alebo pri zapínaní PC skúšame ďalej
                if ((!everConnected && !wakeFirst) || System.currentTimeMillis() > deadline) {
                    fail(gen, msg)
                    return
                }
                if (msg == PcNet.NO_WIFI) {
                    main.post { if (generation.get() == gen) status = "Čakám na WiFi…" }
                } else {
                    val waited = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                    main.post { if (generation.get() == gen) status = (if (wakeFirst) "Čakám, kým PC naštartuje… " else "Hľadám PC… ") + "(${waited} s)" }
                }
                Thread.sleep(2000)
            }
        } catch (e: InterruptedException) {
            // zastavené
        } catch (e: Exception) {
            fail(gen, "Neočakávaná chyba: ${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ WiFi zámok (nižšia odozva počas ovládania)

    private fun acquireWifiLock() {
        try {
            if (wifiLock == null) {
                val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "domov-pc").apply { setReferenceCounted(false) }
            }
            wifiLock?.acquire()
        } catch (e: Exception) {
            // zámok je len zlepšenie; bez neho ovládanie funguje tiež
        }
    }

    private fun releaseWifiLock() {
        try {
            val l = wifiLock
            if (l != null && l.isHeld) l.release()
        } catch (e: Exception) {
            // nič
        }
    }
}
