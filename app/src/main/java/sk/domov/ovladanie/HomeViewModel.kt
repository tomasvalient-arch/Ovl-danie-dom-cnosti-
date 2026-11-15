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
import org.json.JSONObject
import sk.domov.ovladanie.tuya.TuyaDevice
import java.io.IOException

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val store = DeviceStore(app)
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    private val _devices = MutableStateFlow(store.load())
    val devices: StateFlow<List<Device>> = _devices

    private val _states = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    val states: StateFlow<Map<String, DeviceState>> = _states

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

    fun addOrUpdate(device: Device) {
        val list = _devices.value
        _devices.value = if (list.any { it.id == device.id }) {
            list.map { if (it.id == device.id) device else it }
        } else {
            list + device
        }
        store.save(_devices.value)
        refresh(device)
    }

    fun importDevices(imported: List<Device>) {
        var list = _devices.value
        imported.forEach { new ->
            val existing = list.find { it.devId == new.devId }
            list = if (existing == null) {
                list + new
            } else {
                // zachovaj interné id, názov a kanály, aktualizuj kľúč, verziu a IP (ak ju import má)
                list.map {
                    if (it.devId == new.devId) it.copy(
                        localKey = new.localKey,
                        version = new.version,
                        ip = new.ip.ifBlank { it.ip },
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
    }

    // ------------------------------------------------------------ komunikácia

    fun refreshAll() {
        _devices.value.forEach { refresh(it) }
    }

    fun refresh(device: Device) {
        if (_wifi.value == null) return // bez WiFi nemá zmysel pripájať sa
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { readState(device) } }
            setState(device.id, result.toState())
        }
    }

    /** Nastaví jednu hodnotu (Boolean, číslo alebo text) a potom znova načíta stav zariadenia. */
    fun setValue(device: Device, dp: String, value: Any) {
        val current = (_states.value[device.id] as? DeviceState.Online)?.values ?: emptyMap()
        setState(device.id, DeviceState.Online(current + (dp to value))) // okamžitá odozva v UI
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    client(device).setDps(JSONObject().put(dp, value).toString())
                    delay(400) // zariadenie potrebuje chvíľu, kým zmenu premietne do stavu
                    readState(device)
                }
            }
            setState(device.id, result.toState())
        }
    }

    private fun Result<Map<String, Any>>.toState(): DeviceState =
        fold(
            onSuccess = { DeviceState.Online(it) },
            onFailure = { DeviceState.Error(describe(it)) },
        )

    private fun client(d: Device): TuyaDevice {
        val network = _wifi.value ?: throw IOException("Nie si pripojený na WiFi")
        // Sockety viažeme priamo na WiFi sieť, aby sa nepoužilo mobilné dáta ani keď WiFi nemá internet.
        return TuyaDevice(d.ip, d.devId, d.localKey, d.version, network.socketFactory)
    }

    /** Načíta všetky hodnoty (DP) zariadenia, zoradené podľa čísla DP. */
    private fun readState(d: Device): Map<String, Any> {
        if (d.ip.isBlank()) throw IOException("Zariadenie nemá nastavenú IP adresu")
        val text = client(d).query()
        if (text.isBlank()) throw IOException("Zariadenie neposlalo žiadne dáta")
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException("Neočakávaná odpoveď zariadenia: $text")
        }
        val dps = json.optJSONObject("dps")
            ?: json.optJSONObject("data")?.optJSONObject("dps")
            ?: throw IOException("Neočakávaná odpoveď zariadenia: $text")
        val out = HashMap<String, Any>()
        for (key in dps.keys()) {
            val v = dps.get(key)
            if (v is Boolean || v is Number || v is String) out[key] = v
        }
        return out.toSortedMap(compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
    }

    private fun setState(id: String, state: DeviceState) {
        _states.value = _states.value + (id to state)
    }

    private fun describe(t: Throwable): String = when (t) {
        is java.net.SocketTimeoutException -> "Zariadenie neodpovedá (skontroluj IP adresu a či je zapnuté)"
        is java.net.ConnectException -> "Nedá sa pripojiť (skontroluj IP adresu)"
        is IllegalArgumentException -> t.message ?: "Neplatné nastavenia zariadenia"
        else -> t.message ?: t.javaClass.simpleName
    }
}
