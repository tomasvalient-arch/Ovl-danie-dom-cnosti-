package sk.domov.ovladanie

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Nastavenie (zálohuje sa) a posledná odpoveď (nezálohuje sa). */
object WeatherStore {
    private fun prefs(c: Context) = c.getSharedPreferences("domov_weather", Context.MODE_PRIVATE)
    fun settings(c: Context) = WeatherSettings.fromJson(prefs(c).getString("settings", null))
    fun saveSettings(c: Context, s: WeatherSettings) = prefs(c).edit().putString("settings", s.toJson()).apply()
    fun cache(c: Context) = WeatherCache.fromJson(prefs(c).getString("cache", null))
    fun saveCache(c: Context, w: WeatherCache) = prefs(c).edit().putString("cache", w.toJson()).apply()
    fun lastAttempt(c: Context) = prefs(c).getLong("attempt", 0L)
    fun setLastAttempt(c: Context, t: Long) = prefs(c).edit().putLong("attempt", t).apply()
}

data class WeatherUi(
    val settings: WeatherSettings = WeatherSettings(),
    val location: WeatherLocation? = null,
    val forecast: Forecast? = null,
    val fetchedAt: Long = 0L,
    val loading: Boolean = false,
    val error: String? = null,
)

/** Počasie na hlavnej obrazovke: cache, obnovenie najviac raz za 30 min, chyby ako text – nikdy nezhodí appku. */
class WeatherController(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private val handler = CoroutineExceptionHandler { _, e ->
        android.util.Log.w("Domov", "počasie: chyba", e)
        _ui.value = _ui.value.copy(loading = false, error = errorText(e, _ui.value.forecast != null))
    }
    private val _ui = MutableStateFlow(WeatherUi())
    val ui: StateFlow<WeatherUi> = _ui

    init {
        scope.launch(Dispatchers.IO + handler) { load() }
    }

    /**
     * Zmena siete (napr. WiFi → mobilné dáta): ak posledné načítanie zlyhalo, skúsiť to hneď znova na novej sieti
     * (predtým chyba ostala zobrazená, kým údaje nezostarli na 30 min).
     */
    private val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
    private val netCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            if (_ui.value.error != null) scope.launch(Dispatchers.IO + handler) { delay(1500); refresh(force = true, afterError = true) }
        }
    }

    init {
        runCatching { cm?.registerDefaultNetworkCallback(netCallback) }
    }

    fun close() {
        runCatching { cm?.unregisterNetworkCallback(netCallback) }
    }

    /**
     * HTTPS cez aktuálnu predvolenú sieť (WiFi alebo mobilné dáta – nikdy nie natvrdo domácu WiFi). Každá sieť má
     * vlastné spojenia, takže sa nepoužije staré spojenie z WiFi, ktorá už zmizla. Pri chybe spojenia jeden nový pokus.
     */
    private suspend fun fetch(url: String): String {
        try {
            return get(url, cm?.activeNetwork)
        } catch (e: HttpError) {
            throw e
        } catch (e: java.io.IOException) {
            delay(2000)
            return get(url, cm?.activeNetwork, fresh = true)
        }
    }

    private fun homeLocation(): Pair<Double?, Double?> = runCatching { PresenceStore.config(app).let { it.lat to it.lon } }.getOrDefault(null to null)

    /** Načíta nastavenie a uložené údaje (bez siete). */
    private fun load() {
        val s = WeatherStore.settings(app)
        val (hl, ho) = homeLocation()
        val loc = WeatherPolicy.location(s, hl, ho)
        val cache = WeatherStore.cache(app)?.takeIf { loc != null && WeatherPolicy.samePlace(it, loc) }
        val f = cache?.let { runCatching { WeatherApi.parseForecast(it.raw) }.getOrNull() }
        _ui.value = _ui.value.copy(settings = s, location = loc, forecast = f, fetchedAt = if (f != null) cache.fetchedAt else 0L)
    }

    /** Obnoví počasie, ak treba (otvorenie appky, návrat, potiahnutie nadol = force). */
    fun refresh(force: Boolean = false, afterError: Boolean = false) {
        scope.launch(Dispatchers.IO + handler) {
            mutex.withLock {
                load()
                val st = _ui.value
                if (!st.settings.visible) return@withLock
                val loc = st.location ?: return@withLock
                val now = System.currentTimeMillis()
                val cache = WeatherStore.cache(app)
                // po chybe a zmene siete: znova hneď (len ak chyba stále trvá)
                if (afterError && st.error == null) return@withLock
                val due = WeatherPolicy.shouldFetch(cache, loc, now, force, WeatherStore.lastAttempt(app)) ||
                    (afterError && now - WeatherStore.lastAttempt(app) >= 10_000L)
                if (!due) return@withLock
                _ui.value = st.copy(loading = true, error = null)
                WeatherStore.setLastAttempt(app, now)
                try {
                    val raw = fetch(WeatherApi.forecastUrl(loc.lat, loc.lon))
                    val f = WeatherApi.parseForecast(raw)
                    val at = System.currentTimeMillis()
                    WeatherStore.saveCache(app, WeatherCache(raw, at, loc.lat, loc.lon))
                    _ui.value = _ui.value.copy(forecast = f, fetchedAt = at, loading = false, error = null)
                } catch (e: Exception) {
                    _ui.value = _ui.value.copy(loading = false, error = errorText(e, _ui.value.forecast != null))
                }
            }
        }
    }

    fun setVisible(visible: Boolean) {
        WeatherStore.saveSettings(app, WeatherStore.settings(app).copy(visible = visible))
        refresh()
    }

    fun setPlace(p: Place) {
        WeatherStore.saveSettings(app, WeatherStore.settings(app).copy(source = WeatherSource.CITY, name = p.name, lat = round(p.lat), lon = round(p.lon), visible = true))
        refresh()
    }

    /** Hľadanie mesta. Vráti zoznam alebo chybu (slovenský text). */
    suspend fun search(query: String): Result<List<Place>> = withContext(Dispatchers.IO) {
        runCatching {
            if (query.trim().length < 2) throw IllegalArgumentException("Napíš aspoň 2 písmená názvu mesta.")
            WeatherApi.parsePlaces(fetch(WeatherApi.searchUrl(query)))
        }.recoverCatching { e -> throw IllegalArgumentException(errorText(e, false, search = true)) }
    }

    /** Poloha telefónu (len približná, povolenie pýta UI). Vráti chybu alebo null. */
    suspend fun usePhoneLocation(): String? = withContext(Dispatchers.IO) {
        try {
            val l = phoneLocation(app) ?: return@withContext "Polohu sa nepodarilo zistiť. Zapni v telefóne polohu alebo vyber mesto."
            WeatherStore.saveSettings(app, WeatherStore.settings(app).copy(source = WeatherSource.PHONE, name = "Poloha telefónu", lat = round(l.latitude), lon = round(l.longitude), visible = true))
            refresh()
            null
        } catch (e: SecurityException) {
            "Appka nemá povolenie na polohu. Povoľ ho alebo vyber mesto."
        } catch (e: Exception) {
            "Polohu sa nepodarilo zistiť (${e.message ?: e.javaClass.simpleName})."
        }
    }

    companion object {
        /** Na ~1 km (stačí na počasie a neukladá presnú polohu). */
        fun round(v: Double) = (v * 100).roundToInt() / 100.0

        fun errorText(e: Throwable, haveCache: Boolean, search: Boolean = false): String {
            val base = when (e) {
                is UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException -> "Bez internetu"
                is SocketTimeoutException -> "Server počasia neodpovedá"
                is HttpError -> "Server počasia hlási chybu ${e.code}"
                is IllegalArgumentException -> return e.message ?: "Chyba počasia"
                // presný druh chyby pomôže zistiť príčinu (napr. SSLHandshakeException, SocketException)
                is IOException -> "Chyba spojenia (${e.javaClass.simpleName})"
                else -> "Počasie sa nepodarilo načítať"
            }
            return when {
                search -> "$base – mesto sa nedá vyhľadať."
                haveCache -> "$base – zobrazujem uložené počasie."
                else -> "$base – počasie sa nedá načítať."
            }
        }

        class HttpError(val code: Int) : IOException("HTTP $code")

        fun get(url: String, network: android.net.Network? = null, fresh: Boolean = false): String {
            val headers = if (fresh) mapOf("User-Agent" to "Domov-Android", "Connection" to "close") else mapOf("User-Agent" to "Domov-Android")
            val r = Http.send("GET", url, headers) { u -> network?.let { runCatching { it.openConnection(u) }.getOrNull() } ?: u.openConnection() }
            if (r.code != 200) throw HttpError(r.code)
            return r.body
        }

        fun hasLocationPermission(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Posledná známa (alebo jedna čerstvá) približná poloha. */
        @Suppress("MissingPermission")
        suspend fun phoneLocation(ctx: Context): Location? {
            if (!hasLocationPermission(ctx)) throw SecurityException("bez povolenia")
            val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
            val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
            val last = providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
            if (last != null && System.currentTimeMillis() - last.time < 24 * 3600_000L) return last
            if (Build.VERSION.SDK_INT >= 30) {
                // všetci dostupní poskytovatelia naraz – vyhrá prvá platná poloha (na niektorých telefónoch
                // „fused“ pri približnej polohe nevráti nič, kým GPS/sieť áno)
                val wanted = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { it in providers }
                if (wanted.isNotEmpty()) {
                    val signals = mutableListOf<android.os.CancellationSignal>()
                    val fresh = withTimeoutOrNull(20_000) {
                        suspendCancellableCoroutine<Location?> { cont ->
                            cont.invokeOnCancellation { signals.forEach { runCatching { it.cancel() } } }
                            var pending = wanted.size
                            val main = ContextCompat.getMainExecutor(ctx)
                            for (provider in wanted) {
                                val signal = android.os.CancellationSignal().also { signals += it }
                                try {
                                    lm.getCurrentLocation(provider, signal, main) { l ->
                                        pending--
                                        if (cont.isActive && (l != null || pending <= 0)) cont.resume(l)
                                    }
                                } catch (e: Exception) {
                                    pending--
                                    if (cont.isActive && pending <= 0) cont.resume(null)
                                }
                            }
                        }
                    }
                    signals.forEach { runCatching { it.cancel() } }
                    if (fresh != null) return fresh
                }
            }
            return last
        }
    }
}
