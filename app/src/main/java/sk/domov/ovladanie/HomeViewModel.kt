package sk.domov.ovladanie

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(private val app: Application) : AndroidViewModel(app) {
    private val store = DeviceStore(app)
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    private val _devices = MutableStateFlow(store.load())
    val devices: StateFlow<List<Device>> = _devices

    private val _states = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    val states: StateFlow<Map<String, DeviceState>> = _states

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
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        cm.registerNetworkCallback(request, wifiCallback)
    }

    override fun onCleared() {
        cm.unregisterNetworkCallback(wifiCallback)
    }

    // ------------------------------------------------------------ zoznam zariadení

    /** Uloží nové alebo upravené zariadenie. [reload] = hneď znova načítať jeho stav. */
    fun addOrUpdate(device: Device, reload: Boolean = true) {
        val list = _devices.value
        _devices.value = if (list.any { it.id == device.id }) {
            list.map { if (it.id == device.id) device else it }
        } else {
            list + device
        }
        store.save(_devices.value)
        DomovWidgetProvider.redrawAll(app)
        if (reload) refresh(device)
    }

    fun importDevices(imported: List<Device>) {
        var list = _devices.value
        imported.forEach { new ->
            val existing = list.find { it.devId == new.devId }
            list = if (existing == null) {
                list + new
            } else {
                // zachovaj interné id a názov, aktualizuj kľúč, verziu a IP (ak ju import má);
                // názvy hodnôt, skryté hodnoty a vypínač prevezmi z importu, ak ich obsahuje
                list.map {
                    if (it.devId == new.devId) it.copy(
                        localKey = new.localKey,
                        version = new.version,
                        ip = new.ip.ifBlank { it.ip },
                        labels = if (new.labels.isNotEmpty()) new.labels else it.labels,
                        hidden = if (new.hidden.isNotEmpty()) new.hidden else it.hidden,
                        powerDp = new.powerDp.ifBlank { it.powerDp },
                        whiteSwitchDp = new.whiteSwitchDp.ifBlank { it.whiteSwitchDp },
                        colourSwitchDp = new.colourSwitchDp.ifBlank { it.colourSwitchDp },
                    ) else it
                }
            }
        }
        _devices.value = list
        store.save(list)
        refreshAll()
    }

    fun remove(device: Device) {
        _devices.value = _devices.value.filterNot { it.id == device.id }
        _states.value = _states.value - device.id
        store.save(_devices.value)
        StateCache.remove(app, device.id)
        DomovWidgetProvider.redrawAll(app)
    }

    // ------------------------------------------------------------ komunikácia

    fun refreshAll() {
        _devices.value.forEach { refresh(it) }
    }

    /** Zariadenia, ktorých stav sa práve načítava (aby sa dotazy nehromadili). */
    private val refreshing = HashSet<String>()

    /**
     * Načíta stav zariadenia. [quiet] = pravidelné obnovovanie na pozadí: ak zlyhá a zariadenie bolo
     * dovtedy v poriadku, obrazovka sa nemení (nezmizne ovládanie uprostred ťahania posuvníka).
     */
    fun refresh(device: Device, quiet: Boolean = false) {
        val network = _wifi.value ?: return // bez WiFi nemá zmysel pripájať sa
        if (!refreshing.add(device.id)) return
        viewModelScope.launch {
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
        val network = _wifi.value ?: return
        val current = (_states.value[device.id] as? DeviceState.Online)?.values ?: emptyMap()
        apply(device, Result.success(current + values)) // okamžitá odozva v UI
        viewModelScope.launch {
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

    /** Zapíše výsledok do stavu appky, do cache pre widgety a prekreslí widgety. */
    private fun apply(device: Device, result: Result<Map<String, Any>>) {
        result.onSuccess {
            StateCache.save(app, device.id, it)
            Fryer.detect(it)?.let { f -> (it[f.mode] as? String)?.let { m -> ModeMemory.remember(app, device.id, m) } }
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
}
