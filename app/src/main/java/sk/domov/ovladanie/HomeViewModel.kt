package sk.domov.ovladanie

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HomeViewModel(private val app: Application) : AndroidViewModel(app) {
    private val store = DeviceStore(app)
    private val cm: ConnectivityManager? = app.getSystemService(ConnectivityManager::class.java)
    private var wifiCallbackRegistered = false

    private val _devices = MutableStateFlow(store.load())
    val devices: StateFlow<List<Device>> = _devices

    private val _states = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    val states: StateFlow<Map<String, DeviceState>> = _states

    /** Posledný známy stav každého zariadenia (aj keď je práve offline) – podľa neho sa triedia skupiny. */
    private val _known = MutableStateFlow(_devices.value.associate { it.id to (StateCache.load(app, it.id) ?: emptyMap()) })
    val known: StateFlow<Map<String, Map<String, Any>>> = _known

    /** Krátka informácia o výsledku (Všetko vypnúť, Noc, Príchod) – zobrazí sa dole ako snackbar. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun clearMessage() {
        _message.value = null
    }

    /** Ukážkový režim pre snímky obrazovky (spustí sa len cez adb, nič neukladá). */
    private val _demo = MutableStateFlow(false)
    val demo: StateFlow<Boolean> = _demo

    /** Zapisuje zoznam do úložiska: nikdy rekurzívne a rovnaký zoznam len raz (v ukážkovom režime nič). */
    private val saver = DeviceSaver { store.save(it) }.also { it.markSaved(_devices.value) }

    private fun saveDevices(list: List<Device>) {
        if (_demo.value) return
        if (!saver.save(list)) _notice.value = "Zariadenia sa nepodarilo uložiť."
    }

    /** Chyba v pozadí (sieť, cloud, nečakané dáta) nesmie zhodiť appku – zobrazí sa ako upozornenie. */
    private val safe = CoroutineExceptionHandler { _, e ->
        android.util.Log.w("Domov", "chyba v pozadí", e)
        _notice.value = "Niečo sa nepodarilo: " + (e.message ?: e.javaClass.simpleName)
    }

    /** Krátka správa o neúspešnom príkaze, ktorú UI ukáže ako upozornenie (null = žiadna). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    fun dismissNotice() {
        _notice.value = null
    }

    /** Aktuálna WiFi sieť, alebo null ak telefón nie je pripojený na WiFi. */
    private val _wifi = MutableStateFlow<Network?>(null)
    val wifi: StateFlow<Network?> = _wifi

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val first = _wifi.value == null
            _wifi.value = network
            if (first) refreshAll()
        }

        override fun onLost(network: Network) {
            if (_wifi.value == network) _wifi.value = null
        }
    }

    init {
        // kým je appka otvorená a služba Príchod a odchod nebeží: fritézy obnovovať podľa stavu varenia
        // (počas varenia každých 20 s, pred koncom častejšie, inak 90 s), aby notifikácie sedeli
        viewModelScope.launch(Dispatchers.IO + LogOnError) {
            delay(10_000)
            while (true) {
                var next = 90_000L
                try {
                    if (!_demo.value && !PresenceService.isRunning && _wifi.value != null) {
                        val now = System.currentTimeMillis()
                        for (d in _devices.value) {
                            val v = _known.value[d.id] ?: continue
                            if (Fryer.detect(v) == null) continue
                            withContext(Dispatchers.Main) { refresh(d, quiet = true) }
                            next = minOf(next, FryerWatch.nextPollMs(FryerStore.watch(app, d.id), now, true))
                        }
                    }
                } catch (e: Exception) {
                    next = 60_000L
                }
                delay(next.coerceIn(5_000L, 90_000L))
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        // Callback na hlavnom vlákne: spustí sa až po dokončení konštruktora (inak by mohol siahnuť na ešte
        // nevytvorené polia, napr. zoznam práve obnovovaných zariadení) a nebije sa s UI.
        // Android môže registráciu odmietnuť (napr. TooManyRequestsException) – appka sa potom len správa ako bez WiFi, nespadne.
        wifiCallbackRegistered = runCatching {
            cm?.registerNetworkCallback(request, wifiCallback, android.os.Handler(android.os.Looper.getMainLooper()))
        }.isSuccess && cm != null
    }

    /**
     * Stav zapísaný mimo obrazovky (automatika príchod/odchod, poistka, fritéza, test odchodu) sa hneď premietne
     * do appky. Predtým appka ukazovala starý stav: po odchode cez cloud zapnuté, po príchode vypnuté (o krok pozadu).
     */
    private val cacheListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (ownCacheWrite) return@OnSharedPreferenceChangeListener
        val id = key?.removePrefix("values_")?.takeIf { key.startsWith("values_") } ?: return@OnSharedPreferenceChangeListener
        if (_demo.value || _devices.value.none { it.id == id }) return@OnSharedPreferenceChangeListener
        if (dragGuard.active(id) || liveThrottles[id]?.busy == true) return@OnSharedPreferenceChangeListener
        val values = StateCache.load(app, id) ?: return@OnSharedPreferenceChangeListener
        if (_known.value[id] == values && (_states.value[id] as? DeviceState.Online)?.values == values) return@OnSharedPreferenceChangeListener
        _known.value = _known.value + (id to values)
        // aj keď je zariadenie offline / telefón bez WiFi: ukázať posledný známy stav z automatiky
        setState(id, DeviceState.Online(values))
    }
    /** Zápis do cache z tejto obrazovky (apply) – listener ho nemá spracovať druhýkrát. Volá sa na hlavnom vlákne. */
    private var ownCacheWrite = false
    private val cachePrefs = StateCache.prefs(app)

    init {
        cachePrefs.registerOnSharedPreferenceChangeListener(cacheListener)
    }

    override fun onCleared() {
        cachePrefs.unregisterOnSharedPreferenceChangeListener(cacheListener)
        if (wifiCallbackRegistered) runCatching { cm?.unregisterNetworkCallback(wifiCallback) }
        weather.close()
    }

    // ------------------------------------------------------------ zoznam zariadení

    /** Uloží nové alebo upravené zariadenie. [reload] = hneď znova načítať jeho stav. */
    fun addOrUpdate(device: Device, reload: Boolean = true) {
        _devices.value = DeviceList.upsert(_devices.value, device)
        saveDevices(_devices.value)
        DomovWidgetProvider.redrawAll(app)
        if (reload) refresh(device)
    }

    fun importDevices(imported: List<Device>) {
        val list = mergeImported(_devices.value, imported)
        _devices.value = list
        saveDevices(list)
        refreshAll()
    }

    fun remove(device: Device) {
        _devices.value = _devices.value.filterNot { it.id == device.id }
        _states.value = _states.value - device.id
        saveDevices(_devices.value)
        StateCache.remove(app, device.id)
        DomovWidgetProvider.redrawAll(app)
    }

    // ------------------------------------------------------------ komunikácia

    fun refreshAll() {
        _devices.value.forEach { refresh(it) }
    }

    // ------------------------------------------------------------ skupiny a scény

    /** Posledné známe hodnoty zariadenia (aktuálny stav, inak uložený). */
    fun valuesOf(d: Device): Map<String, Any> =
        (_states.value[d.id] as? DeviceState.Online)?.values ?: _known.value[d.id] ?: emptyMap()

    /** Posunie zariadenie v jeho skupine (-1 dopredu, +1 dozadu). */
    fun moveDevice(id: String, delta: Int) {
        val list = Groups.move(_devices.value, id, delta, ::valuesOf)
        if (list == _devices.value) return
        _devices.value = list
        saveDevices(list)
    }

    /** Presunie zariadenie do skupiny ("" = automatická podľa typu). */
    fun setGroup(id: String, group: String) {
        val list = Groups.setGroup(_devices.value, id, group)
        _devices.value = list
        saveDevices(list)
    }

    private val sceneStore = SceneStore(app)
    private val _night = MutableStateFlow(sceneStore.night())
    val night: StateFlow<NightScene> = _night

    fun saveNight(scene: NightScene) {
        _night.value = scene
        sceneStore.saveNight(scene)
    }

    /**
     * Všetko vypnúť: na WiFi lokálne (rovnaký výber ako pri odchode), bez WiFi cez Tuya cloud, ak je nastavený.
     */
    fun allOff() {
        if (_wifi.value == null && !_demo.value) {
            viewModelScope.launch(safe) { _message.value = PresenceActions.depart(app, manual = true, prefix = "Všetko vypnúť (cez cloud)") }
            return
        }
        runPlan("Všetko vypnúť") { devices, values, fresh -> Scenes.allOffSteps(PresenceStore.config(app), devices, values, fresh) }
    }

    /** Scéna Noc (len na WiFi): stlmí vybrané svetlá na teplú bielu a ostatné vypne. */
    fun runNight() {
        val scene = _night.value
        runPlan("Noc") { devices, values, fresh -> Scenes.nightSteps(scene, devices, values, fresh) }
    }

    /** Príchod ručne: zapne zariadenia vybrané pre príchod (bez ohľadu na zotmenie). */
    fun arriveNow() {
        if (_demo.value) {
            _message.value = "Príchod – zaplo sa 2 svetlá."
            return
        }
        viewModelScope.launch(safe) { _message.value = PresenceActions.arrive(app, _wifi.value, manual = true, prefix = "Príchod") }
    }

    /**
     * Hromadná akcia (Všetko vypnúť, Noc) podľa bezpečných pravidiel SafeOff: rizikové zariadenia (fritéza, ohrievač,
     * neznáme) sa pred akciou čerstvo prečítajú; bez čerstvého stavu sa preskočia. Po odoslaní sa ich stav overí a ak
     * sa niečo zaplo (fritéza varí), hneď sa pošle Stop a zobrazí varovanie.
     */
    private fun runPlan(
        title: String,
        build: (List<Device>, (Device) -> Map<String, Any>, (Device) -> Boolean) -> List<OffStep>,
    ) {
        val network = if (_demo.value) null else _wifi.value
        if (network == null && !_demo.value) {
            _message.value = "$title – nie si na WiFi, ovládanie funguje len v domácej sieti."
            return
        }
        viewModelScope.launch(safe) {
            val devices = _devices.value
            val values = HashMap<String, Map<String, Any>>()
            val fresh = HashSet<String>()
            for (d in devices) {
                val cur = (_states.value[d.id] as? DeviceState.Online)?.values
                val known = cur ?: valuesOf(d)
                // rizikové zariadenia vždy čerstvo zo zariadenia; ostatné z obrazovky (ak chýba, opýtať sa)
                val mustRead = network != null && (SafeOff.risky(d, known) || cur == null)
                if (mustRead) {
                    val read = withContext(Dispatchers.IO) { runCatching { DeviceClient.readState(network!!, d) }.getOrNull() }
                    if (read != null) {
                        values[d.id] = read
                        fresh += d.id
                        continue
                    }
                }
                values[d.id] = known
                if (_demo.value && cur != null) fresh += d.id
            }
            val steps = build(devices, { values[it.id] ?: emptyMap() }, { it.id in fresh })
            val sends = steps.filterIsInstance<OffStep.Send>()
            val skippedText = SafeOff.skippedText(steps)
            if (sends.isEmpty()) {
                _message.value = "$title – nie je čo vypnúť ani stlmiť" + (skippedText?.let { "; $it" } ?: "") + "."
                if (!_demo.value && skippedText != null) PresenceStore.log(app, _message.value ?: "")
                return@launch
            }
            val ok = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (step in sends) {
                val d = step.device
                val cmd = step.cmd
                if (network == null) {
                    setState(d.id, DeviceState.Online((values[d.id] ?: emptyMap()) + cmd)); ok += d.name; continue
                }
                val sent = withContext(Dispatchers.IO) { runCatching { DeviceClient.setValues(network, d, cmd, "hromadná akcia") } }
                if (sent.isSuccess) {
                    ok += d.name
                    apply(d, Result.success((values[d.id] ?: emptyMap()) + cmd))
                } else {
                    failed += "${d.name} (${DeviceClient.shortError(sent.exceptionOrNull() ?: Exception()).lowercase()})"
                }
            }
            // overenie rizikových zariadení (aj tých, ktorým sme nič neposlali)
            val warnings = mutableListOf<String>()
            if (network != null) {
                val toCheck = steps.filter { it !is OffStep.Skip && SafeOff.risky(it.device, values[it.device.id] ?: emptyMap()) && it.device.id in fresh }
                if (toCheck.isNotEmpty()) {
                    delay(1500)
                    for (step in toCheck) {
                        val d = step.device
                        val after = withContext(Dispatchers.IO) { runCatching { DeviceClient.readState(network, d) }.getOrNull() } ?: continue
                        val sentCmd = (step as? OffStep.Send)?.cmd ?: emptyMap()
                        val rescue = SafeOff.verify(d, sentCmd, values[d.id] ?: emptyMap(), after)
                        if (rescue == null) {
                            apply(d, Result.success(after))
                            continue
                        }
                        val r = withContext(Dispatchers.IO) { runCatching { DeviceClient.setValues(network, d, rescue.cmd, "náprava po akcii") } }
                        warnings += if (r.isSuccess) rescue.warning else "${d.name}: zariadenie je zapnuté a nepodarilo sa ho zastaviť – vypni ho ručne!"
                        refresh(d, quiet = true)
                    }
                }
            }
            val msg = buildString {
                append(title)
                if (ok.isNotEmpty()) append(" – hotovo: ").append(ok.joinToString())
                if (failed.isNotEmpty()) append(if (ok.isEmpty()) " – nepodarilo sa: " else ". Nepodarilo sa: ").append(failed.joinToString())
                skippedText?.let { append(". ").append(it.replaceFirstChar { c -> c.uppercase() }) }
                append('.')
                warnings.forEach { append(" POZOR: ").append(it) }
            }
            if (!_demo.value) PresenceStore.log(app, msg)
            _message.value = msg
            if (network != null) {
                delay(800)
                sends.forEach { refresh(it.device, quiet = true) }
            }
        }
    }

    /** Ukážkové zariadenia a stavy (len v pamäti) – na snímky obrazovky cez `adb shell am start … --ez demo true`. */
    fun startDemo() {
        val (devices, values) = DemoData.build()
        _demo.value = true
        _devices.value = devices
        _known.value = values
        _states.value = devices.associate { d -> d.id to (values[d.id]?.let { DeviceState.Online(it) } ?: DeviceState.Error("Zariadenie neodpovedá (časový limit).")) }
        DemoData.seedPower(devices)
        heat.startDemo()
    }

    /** Zariadenia, ktorých stav sa práve načítava (aby sa dotazy nehromadili). */
    private val refreshing = HashSet<String>()

    /**
     * Načíta stav zariadenia. [quiet] = pravidelné obnovovanie na pozadí: ak zlyhá a zariadenie bolo
     * dovtedy v poriadku, obrazovka sa nemení (nezmizne ovládanie uprostred ťahania posuvníka).
     */
    fun refresh(device: Device, quiet: Boolean = false) {
        if (_demo.value) return
        // počas ťahania posuvníka a chvíľu po ňom sa pravidelne neobnovuje (staré hlásenie by posuvník vrátilo späť)
        if (quiet && (dragGuard.active(device.id) || liveThrottles[device.id]?.busy == true)) return
        val network = _wifi.value ?: return // bez WiFi nemá zmysel pripájať sa
        if (!refreshing.add(device.id)) return
        viewModelScope.launch(safe) {
            try {
                val result = withContext(Dispatchers.IO) { runCatching { DeviceClient.readState(network, device) } }
                val wasOnline = _states.value[device.id] is DeviceState.Online
                if (quiet && result.isFailure && wasOnline) return@launch
                apply(device, result)
            } finally {
                refreshing.remove(device.id)
            }
        }
    }

    /** Nastaví jednu hodnotu (Boolean, číslo alebo text) a potom znova načíta stav zariadenia. */
    fun setValue(device: Device, dp: String, value: Any) = setValues(device, mapOf(dp to value))

    /**
     * Nastaví viac hodnôt naraz (napr. režim aj farbu svetla) a potom znova načíta stav zariadenia.
     * Ak príkaz zlyhá, zariadenie zostane zobrazené s jeho skutočným stavom a ukáže sa upozornenie.
     */
    fun setValues(device: Device, values: Map<String, Any>) {
        if (values.isEmpty()) return
        if (_demo.value) {
            val cur = (_states.value[device.id] as? DeviceState.Online)?.values ?: emptyMap()
            setState(device.id, DeviceState.Online(cur + values))
            return
        }
        val network = _wifi.value ?: return
        // koniec ťahania posuvníka: konečná hodnota ide cez ten istý obmedzovač (vždy po poslednej priebežnej)
        val throttle = liveThrottles[device.id]
        if (throttle != null && (throttle.busy || System.currentTimeMillis() - throttle.lastActivity < 3000)) {
            finishLive(device, network, throttle, values)
            return
        }
        val current = (_states.value[device.id] as? DeviceState.Online)?.values ?: emptyMap()
        apply(device, Result.success(current + values)) // okamžitá odozva v UI
        viewModelScope.launch(safe) {
            val sent = withContext(Dispatchers.IO) {
                runCatching { DeviceClient.setValues(network, device, values) }
            }
            sent.onFailure { _notice.value = "Príkaz sa nepodaril. " + DeviceClient.describe(it) }
            delay(400) // zariadenie potrebuje chvíľu, kým zmenu premietne do stavu
            val result = withContext(Dispatchers.IO) {
                runCatching { DeviceClient.readState(network, device) }
            }
            apply(device, result)
        }
    }

    // ------------------------------------------------------------ plynulé posuvníky

    private val liveThrottles = java.util.concurrent.ConcurrentHashMap<String, LiveThrottle<Map<String, Any>>>()
    private val dragGuard = DragGuard()

    private fun throttleFor(device: Device, network: android.net.Network): LiveThrottle<Map<String, Any>> =
        liveThrottles.getOrPut(device.id) {
            LiveThrottle(merge = { old, new -> old + new }) { cmd ->
                // vždy aktuálne nastavenia zariadenia (IP sa mohla zmeniť)
                val d = _devices.value.find { it.id == device.id } ?: device
                DeviceClient.liveSend(_wifi.value ?: network, d, cmd)
            }
        }

    /**
     * Priebežná hodnota počas ťahania posuvníka (jas, teplota, farba, číselná hodnota). Pošle sa najviac raz za
     * ~130 ms a stále len najnovšia. Mimo domácej WiFi (cloud) sa nič neposiela – len konečná hodnota po pustení.
     */
    fun liveValues(device: Device, values: Map<String, Any>) {
        if (values.isEmpty()) return
        runCatching {
            val cur = (_states.value[device.id] as? DeviceState.Online)?.values
            if (_demo.value) {
                setState(device.id, DeviceState.Online((cur ?: emptyMap()) + values))
                return
            }
            val network = _wifi.value ?: return
            if (cur == null) return
            dragGuard.drag(device.id, values)
            setState(device.id, DeviceState.Online(cur + values)) // obrazovka hneď ukazuje novú hodnotu
            throttleFor(device, network).offer(values)
        }
    }

    private fun finishLive(device: Device, network: android.net.Network, throttle: LiveThrottle<Map<String, Any>>, values: Map<String, Any>) {
        dragGuard.release(device.id, values)
        val current = (_states.value[device.id] as? DeviceState.Online)?.values ?: emptyMap()
        apply(device, Result.success(current + values))
        throttle.finish(values) { err ->
            viewModelScope.launch(safe) {
                if (err != null) _notice.value = "Príkaz sa nepodaril. " + DeviceClient.describe(err)
                delay(400)
                if (throttle.busy) return@launch // medzitým začalo ďalšie ťahanie
                val result = withContext(Dispatchers.IO) { runCatching { DeviceClient.readState(network, device) } }
                apply(device, result)
            }
        }
    }

    // ------------------------------------------------------------ technické info

    /** Vyžiada zo zariadenia všetky DP (1–255) a pridá ich do stavu. Vráti výsledok alebo chybu po slovensky. */
    suspend fun techProbe(device: Device): Result<Map<String, Any>> {
        if (_demo.value) {
            val cur = (_states.value[device.id] as? DeviceState.Online)?.values ?: emptyMap()
            return Result.success(cur)
        }
        val network = _wifi.value ?: return Result.failure(java.io.IOException("Telefón nie je na WiFi"))
        val res = withContext(Dispatchers.IO) { runCatching { DeviceClient.probeAll(network, device) } }
        res.onSuccess { all ->
            val cur = (_states.value[device.id] as? DeviceState.Online)?.values ?: emptyMap()
            if (all.isNotEmpty()) setState(device.id, DeviceState.Online((cur + all).toSortedMap(compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))))
        }
        return res.recoverCatching { throw java.io.IOException(DeviceClient.describe(it)) }
    }

    /** Model vecí a posledné hodnoty z Tuya cloudu; null = cloud nie je nastavený. */
    suspend fun techCloud(device: Device): CloudTech? {
        val config = _cloud.value ?: return null
        return withContext(Dispatchers.IO) {
            val client = cloudClient ?: TuyaCloudClient(config).also { cloudClient = it }
            val model = runCatching { client.thingModel(device.devId) }
            val shadow = runCatching { client.shadow(device.devId) }
            CloudTech(
                props = model.getOrDefault(emptyList()),
                shadow = shadow.getOrDefault(emptyList()),
                modelError = model.exceptionOrNull()?.let { CloudParse.describe(it) },
                shadowError = shadow.exceptionOrNull()?.let { CloudParse.describe(it) },
            )
        }
    }

    /**
     * Sledovanie zmien: [seconds] drží spojenie so zariadením a každú zmenu pošle do [onLine] (riadok so
     * zmenenými DP). Zmeny sa premietnu aj do stavu na obrazovke. Skončí aj pri zrušení korutiny.
     */
    suspend fun techMonitor(device: Device, seconds: Int, onLine: (String) -> Unit) {
        val network = _wifi.value
        if (network == null || _demo.value) {
            onLine(if (_demo.value) "ukážkový režim – sledovanie nie je k dispozícii" else "telefón nie je na WiFi")
            return
        }
        val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
        val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
        withContext(Dispatchers.IO) {
            DeviceClient.monitor(
                network, device, seconds * 1000L,
                cancelled = { job?.isActive == false },
                onDps = { dps ->
                    val line = LocalDps.logLine(fmt.format(java.util.Date()), dps, device.schema)
                    viewModelScope.launch(safe) {
                        onLine(line)
                        val cur = (_states.value[device.id] as? DeviceState.Online)?.values
                        if (cur != null) setState(device.id, DeviceState.Online(cur + dps))
                    }
                },
                onInfo = { msg ->
                    val line = fmt.format(java.util.Date()) + "  (" + msg + ")"
                    viewModelScope.launch(safe) { onLine(line) }
                },
            )
        }
    }

    // ------------------------------------------------------------ hľadanie zariadení v sieti

    /** Stav hľadania zariadení v sieti (menu ⋮ → Hľadať zariadenia v sieti). */
    data class ScanState(
        val running: Boolean = false,
        val found: List<FoundDevice> = emptyList(),
        /** devId zariadení, ktorým appka pri hľadaní sama opravila IP adresu. */
        val ipUpdated: Set<String> = emptySet(),
        val error: String? = null,
    )

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan

    /** Naraz môže počúvať len jedno hľadanie (porty 6666/6667 sa nedajú otvoriť dvakrát). */
    private val scanMutex = Mutex()
    private var lastAutoScan = 0L

    /** Ručné hľadanie: počúva 12 sekúnd a nájdené zariadenia ukazuje priebežne. */
    fun startScan() {
        if (_scan.value.running) return
        val network = _wifi.value
        if (network == null) {
            _scan.value = ScanState(error = "Nie si pripojený na WiFi.")
            return
        }
        _scan.value = ScanState(running = true)
        viewModelScope.launch(safe) {
            val result = runCatching {
                scanMutex.withLock {
                    Scanner.scan(app, network, SCAN_MS) { found ->
                        viewModelScope.launch(safe) { onFound(found, manual = true) }
                    }
                }
            }
            _scan.value = _scan.value.copy(
                running = false,
                error = result.exceptionOrNull()?.let { it.message ?: "Hľadanie zlyhalo" },
            )
        }
    }

    /** Zapíše nájdené zariadenie; známemu zariadeniu so zmenenou IP adresou ju hneď opraví. */
    private fun onFound(found: FoundDevice, manual: Boolean) {
        if (manual) {
            val list = _scan.value.found
            val updated = if (list.any { it.devId == found.devId }) {
                list.map { if (it.devId == found.devId) found else it }
            } else {
                list + found
            }
            _scan.value = _scan.value.copy(found = updated)
        }
        val fixed = DeviceList.ipFix(_devices.value, found) ?: return
        run {
            addOrUpdate(fixed)
            if (manual) _scan.value = _scan.value.copy(ipUpdated = _scan.value.ipUpdated + found.devId)
        }
    }

    /** Známemu zariadeniu nastaví verziu protokolu a IP adresu podľa toho, čo hlási v sieti. */
    fun applyFound(found: FoundDevice) {
        val known = _devices.value.find { it.devId == found.devId } ?: return
        addOrUpdate(
            known.copy(
                ip = found.ip.ifBlank { known.ip },
                version = if (found.version in SUPPORTED_VERSIONS) found.version else known.version,
            )
        )
    }

    fun clearScan() {
        if (!_scan.value.running) _scan.value = ScanState()
    }

    /**
     * Keď zariadenie neodpovedá (napr. router mu pridelil inú IP adresu), appka potichu na 8 sekúnd
     * počúva v sieti a ak zariadenie nájde na novej adrese, IP opraví a znova sa pripojí. Najviac raz za minútu.
     */
    private fun autoFixIp() {
        val network = _wifi.value ?: return
        val now = System.currentTimeMillis()
        if (now - lastAutoScan < 60_000 || scanMutex.isLocked) return
        lastAutoScan = now
        viewModelScope.launch(safe) {
            runCatching {
                scanMutex.withLock {
                    Scanner.scan(app, network, AUTO_SCAN_MS) { found ->
                        viewModelScope.launch(safe) { onFound(found, manual = false) }
                    }
                }
            }
        }
    }

    private fun isUnreachable(t: Throwable?): Boolean =
        t is java.net.SocketTimeoutException || t is java.net.ConnectException ||
            t is java.net.NoRouteToHostException || t is java.net.UnknownHostException ||
            (t is java.io.IOException && t.message == "Zariadenie nemá nastavenú IP adresu")

    /** Zapíše výsledok do stavu appky, do cache pre widgety a prekreslí widgety. */
    private fun apply(device: Device, rawResult: Result<Map<String, Any>>) {
        if (isUnreachable(rawResult.exceptionOrNull())) autoFixIp()
        // skryté DP (51 mix_rgbcw), ktoré zariadenie tentoraz neposlalo, ostanú z predchádzajúceho stavu
        val previous = (_states.value[device.id] as? DeviceState.Online)?.values
        val result = rawResult.map { fresh ->
            val kept = runCatching { LightChannels.keepHidden(device, previous, fresh) }.getOrDefault(fresh)
            // počas ťahania a krátko po ňom: hlásenie so starou hodnotou posuvníka ignorovať
            runCatching { dragGuard.filter(device.id, previous, kept) }.getOrDefault(kept)
        }
        result.onSuccess {
            // denník: zmena zapnuté/vypnuté, ktorú appka vidí (po príkaze alebo pri čítaní stavu)
            runCatching {
                val dp = powerDpOf(device, it)
                val now = dp?.let { k -> it[k] as? Boolean }
                val was = previous?.let { p -> dp?.let { k -> p[k] as? Boolean } }
                if (now != null && now != was) CommandLog.add(device.devId, "stav v appke: " + if (now) "ZAPNUTÉ" else "VYPNUTÉ")
            }
            _known.value = _known.value + (device.id to it)
            ownCacheWrite = true
            try { StateCache.save(app, device.id, it) } finally { ownCacheWrite = false }
            Fryer.detect(it)?.let { f -> (it[f.mode] as? String)?.let { m -> ModeMemory.remember(app, device.id, m) } }
            // upozornenia fritézy (aj keď služba na pozadí nebeží, kým je appka otvorená)
            if (!_demo.value && Fryer.detect(it) != null) {
                val values = it
                viewModelScope.launch(Dispatchers.IO + LogOnError) { FryerMonitor.onValues(app, device, values) }
            }
            DomovWidgetProvider.redrawAll(app)
        }
        setState(
            device.id,
            result.fold(
                onSuccess = { DeviceState.Online(it) },
                onFailure = { DeviceState.Error(DeviceClient.describe(it)) },
            ),
        )
    }

    private fun setState(id: String, state: DeviceState) {
        _states.value = _states.value + (id to state)
    }

    // ------------------------------------------------------------ Tuya cloud (voliteľný)

    private val cloudStore = CloudStore(app)
    private val _cloud = MutableStateFlow(cloudStore.load())

    /** Uložené prístupové údaje k Tuya cloudu, alebo null (cloud nie je nastavený – appka funguje aj tak). */
    val cloud: StateFlow<CloudConfig?> = _cloud

    sealed interface CloudTest {
        data object Idle : CloudTest
        data object Running : CloudTest
        data class Done(val ok: Boolean, val message: String) : CloudTest
    }

    private val _cloudTest = MutableStateFlow<CloudTest>(CloudTest.Idle)
    val cloudTest: StateFlow<CloudTest> = _cloudTest

    /** Klient a naposledy stiahnutý zoznam (aby sa pri pridávaní viacerých zariadení nesťahoval stále znova). */
    private var cloudClient: TuyaCloudClient? = null
    private var cloudList: Pair<Long, List<CloudDevice>>? = null
    private val cloudMutex = Mutex()

    fun saveCloud(config: CloudConfig) {
        val clean = config.copy(accessId = config.accessId.trim(), accessSecret = config.accessSecret.trim())
        cloudStore.save(clean)
        _cloud.value = clean
        resetCloudCache()
    }

    fun clearCloud() {
        cloudStore.clear()
        _cloud.value = null
        _cloudTest.value = CloudTest.Idle
        resetCloudCache()
    }

    fun clearCloudTest() {
        if (_cloudTest.value !is CloudTest.Running) _cloudTest.value = CloudTest.Idle
    }

    private fun resetCloudCache() {
        cloudClient = null
        cloudList = null
    }

    /** Otestuje údaje (ešte neuložené): prihlásenie a stiahnutie zoznamu zariadení. */
    fun testCloud(config: CloudConfig) {
        if (_cloudTest.value is CloudTest.Running) return
        _cloudTest.value = CloudTest.Running
        viewModelScope.launch(safe) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = TuyaCloudClient(config.copy(accessId = config.accessId.trim(), accessSecret = config.accessSecret.trim()))
                    client.connect()
                    client.devices(_devices.value.map { it.devId })
                }
            }
            _cloudTest.value = result.fold(
                onSuccess = { list ->
                    if (list.isEmpty()) {
                        CloudTest.Done(
                            false,
                            "Prihlásenie funguje, ale v projekte nie sú žiadne zariadenia. Prepoj účet Tuya / Smart Life " +
                                "appky: na iot.tuya.com v projekte Devices → Link App Account (pozri návod). Skontroluj aj " +
                                "dátové centrum – musí byť to, v ktorom je tvoj účet.",
                        )
                    } else {
                        val local = list.count { !it.sub }
                        CloudTest.Done(true, "Pripojenie funguje. V účte je ${list.size} zariadení" +
                            (if (local < list.size) " (z toho ${list.size - local} cez bránu, tie appka neovláda)." else "."))
                    }
                },
                onFailure = { CloudTest.Done(false, CloudParse.describe(it)) },
            )
        }
    }

    /** Zoznam zariadení z cloudu (najviac 5 minút starý). Blokuje – volaj na Dispatchers.IO. */
    private suspend fun cloudDevices(force: Boolean = false): Pair<TuyaCloudClient, List<CloudDevice>> = cloudMutex.withLock {
        val config = _cloud.value ?: throw IllegalStateException("Tuya cloud nie je nastavený")
        val client = cloudClient ?: TuyaCloudClient(config).also { cloudClient = it }
        val cached = cloudList
        if (!force && cached != null && System.currentTimeMillis() - cached.first < 5 * 60_000) return client to cached.second
        val list = client.devices(_devices.value.map { it.devId })
        cloudList = System.currentTimeMillis() to list
        client to list
    }

    /**
     * Pridanie nájdeného zariadenia jedným ťuknutím: z cloudu dotiahne local key, názov a popis hodnôt.
     * Vráti null pri úspechu, inak text chyby (UI potom ponúkne ručné zadanie).
     */
    suspend fun addFromCloud(found: FoundDevice): String? = withContext(Dispatchers.IO) {
        try {
            var (client, list) = cloudDevices()
            var c = list.find { it.id == found.devId }
            if (c == null) {
                // zariadenie mohlo pribudnúť do účtu len teraz
                val fresh = cloudDevices(force = true)
                client = fresh.first
                c = fresh.second.find { it.id == found.devId }
            }
            if (c == null) return@withContext "Zariadenie s Device ID ${found.devId} nie je v Tuya účte prepojenom s projektom."
            if (c.localKey.length != 16) return@withContext "Tuya cloud neposlal local key pre toto zariadenie."
            val schema = client.mapping(c)?.let { Schema.parseMapping(it) } ?: emptyMap()
            withContext(Dispatchers.Main) {
                addOrUpdate(deviceFromCloud(c, schema, found))
            }
            null
        } catch (e: Exception) {
            CloudParse.describe(e)
        }
    }

    data class SyncState(
        val running: Boolean = false,
        val error: String? = null,
        /** Názvy zariadení, ktorým sa aktualizoval local key alebo popis hodnôt. */
        val updated: List<String> = emptyList(),
        /** Zariadenia z účtu, ktoré v appke chýbajú (len tie, ktoré sa dajú ovládať lokálne). */
        val missing: List<CloudDevice> = emptyList(),
        /** Zariadenia cez bránu (Zigbee/Bluetooth), ktoré appka neovláda. */
        val subCount: Int = 0,
        val done: Boolean = false,
    )

    private val _sync = MutableStateFlow(SyncState())
    val sync: StateFlow<SyncState> = _sync

    /** Popisy hodnôt a IP z poslednej synchronizácie (na pridanie chýbajúcich zariadení). */
    private val syncMappings = HashMap<String, Map<String, DpSchema>>()
    private val syncFound = HashMap<String, FoundDevice>()

    /**
     * Synchronizácia s Tuya cloudom: aktualizuje local keys a popisy existujúcich zariadení (bez straty vlastných
     * nastavení) a nájde zariadenia z účtu, ktoré v appke chýbajú. Zároveň krátko hľadá v sieti, aby mali IP adresu.
     */
    fun startSync() {
        if (_sync.value.running || _cloud.value == null) return
        _sync.value = SyncState(running = true)
        syncFound.clear()
        _scan.value.found.forEach { syncFound[it.devId] = it }
        val network = _wifi.value
        viewModelScope.launch(safe) {
            // hľadanie v sieti beží súčasne so sťahovaním z cloudu
            val scanJob = if (network != null) launch {
                runCatching {
                    if (scanMutex.tryLock()) {
                        try {
                            Scanner.scan(app, network, AUTO_SCAN_MS) { found ->
                                viewModelScope.launch(safe) { syncFound[found.devId] = found; onFound(found, manual = false) }
                            }
                        } finally {
                            scanMutex.unlock()
                        }
                    }
                }
            } else null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val (client, list) = cloudDevices(force = true)
                    val mine = _devices.value.map { it.devId }.toSet()
                    val usable = list.filter { !it.sub }
                    usable.filter { it.id in mine || it.localKey.length == 16 }.forEach { c ->
                        client.mapping(c)?.let { syncMappings[c.id] = Schema.parseMapping(it) }
                    }
                    list
                }
            }
            scanJob?.join()
            result.onSuccess { list ->
                val (merged, changed) = mergeCloud(_devices.value, list, syncMappings)
                if (changed.isNotEmpty()) {
                    _devices.value = merged
                    saveDevices(merged)
                    DomovWidgetProvider.redrawAll(app)
                    refreshAll()
                }
                val mine = merged.map { it.devId }.toSet()
                _sync.value = SyncState(
                    updated = changed,
                    missing = list.filter { !it.sub && it.id !in mine && it.localKey.length == 16 },
                    subCount = list.count { it.sub && it.id !in mine },
                    done = true,
                )
            }.onFailure {
                _sync.value = SyncState(error = CloudParse.describe(it), done = true)
            }
        }
    }

    /** Pridá zariadenie z poslednej synchronizácie (s IP z hľadania v sieti, ak sa našlo). */
    fun addFromSync(c: CloudDevice) {
        addOrUpdate(deviceFromCloud(c, syncMappings[c.id] ?: emptyMap(), syncFound[c.id]))
        _sync.value = _sync.value.copy(missing = _sync.value.missing.filterNot { it.id == c.id })
    }

    /** IP adresa zariadenia z posledného hľadania v sieti (na zobrazenie v zozname chýbajúcich). */
    fun syncIp(devId: String): String? = syncFound[devId]?.ip

    fun clearSync() {
        if (!_sync.value.running) _sync.value = SyncState()
    }

    private companion object {
        const val SCAN_MS = 12_000L
        const val AUTO_SCAN_MS = 8_000L
    }

    // ------------------------------------------------------------ kúrenie Honeywell evohome (nepovinné)

    /**
     * Kúrenie cez Honeywell cloud – úplne oddelené od zoznamu Tuya zariadení, takže ho automatika príchod/odchod,
     * Všetko vypnúť ani Noc nikdy nezmenia. Deklarované na konci triedy (po všetkých ostatných poliach).
     */
    val heat = HeatController(app, viewModelScope)
    val weather = WeatherController(app, viewModelScope)
}
