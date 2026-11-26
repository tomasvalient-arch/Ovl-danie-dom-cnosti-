package sk.domov.ovladanie

import android.content.Context
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Prihlasovacie údaje k Honeywell Home a tokeny – zašifrované kľúčom v Android Keystore (ako pri Tuya cloude),
 * v samostatnom súbore, nikdy nie sú súčasťou exportu. Inštalácia (ID zón) a posledný stav sa ukladajú
 * nezašifrované (neobsahujú heslo, adresu ani e-mail).
 */
class EvohomeStore(context: Context) {
    data class Saved(val email: String, val password: String, val tokens: EvoTokens?) {
        override fun toString(): String = "Saved(email=$email, password=${masked(password)}, tokens=$tokens)"
    }

    private val prefs = context.getSharedPreferences("domov_evohome", Context.MODE_PRIVATE)

    fun load(): Saved? {
        val enc = prefs.getString("cred", null) ?: return null
        return try {
            val o = JSONObject(decrypt(enc))
            val access = o.optString("access")
            Saved(
                o.getString("email"),
                o.getString("password"),
                if (access.isNotBlank() || o.optString("refresh").isNotBlank()) EvoTokens(access, o.optLong("expiresAt"), o.optString("refresh")) else null,
            )
        } catch (e: Exception) {
            // mazať len naozaj nepoužiteľné údaje (kľúč v Keystore sa stratil, poškodený obsah) – treba sa prihlásiť znova;
            // pri dočasnej chybe Keystore nič nemazať
            if (SecretBox.isPermanent(e)) clear() else android.util.Log.w("Domov", "kúrenie: údaje sa teraz nedajú načítať", e)
            null
        }
    }

    @Synchronized
    fun save(email: String, password: String, tokens: EvoTokens?) {
        val json = JSONObject().put("email", email).put("password", password)
            .put("access", tokens?.access ?: "").put("expiresAt", tokens?.expiresAt ?: 0L).put("refresh", tokens?.refresh ?: "")
        prefs.edit().putString("cred", encrypt(json.toString())).apply()
    }

    fun install(): EvoInstall? = EvoInstall.fromJson(prefs.getString("install", null))
    fun saveInstall(i: EvoInstall) = prefs.edit().putString("install", i.toJson()).apply()

    fun lastStatus(): Pair<String, Long>? = prefs.getString("status", null)?.let { it to prefs.getLong("statusAt", 0) }
    fun saveStatus(raw: String, at: Long) = prefs.edit().putString("status", raw).putLong("statusAt", at).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** Uloženie/zmazanie hneď na disk (obnovenie zo zálohy). Iný účet = zabudnúť inštaláciu a stav. */
    fun saveNow(email: String?, password: String?): Boolean {
        val old = load()
        if (email == null || password == null) return prefs.edit().clear().commit()
        if (old != null && old.email == email && old.password == password) return true
        val json = JSONObject().put("email", email).put("password", password).put("access", "").put("expiresAt", 0L).put("refresh", "")
        val e = prefs.edit()
        if (old?.email != email) e.clear()
        return e.putString("cred", encrypt(json.toString())).commit()
    }

    private fun encrypt(text: String): String = SecretBox.encrypt(SecretBox.EVOHOME, text)

    private fun decrypt(stored: String): String = SecretBox.decrypt(SecretBox.EVOHOME, stored)
}

/** Ako dlho platí nová teplota zóny. */
enum class HeatDuration(val label: String) {
    PERMANENT("Natrvalo"),
    HOUR1("1 h"),
    HOUR2("2 h"),
    NEXT_CHANGE("Do zmeny v programe"),
}

data class HeatUi(
    val configured: Boolean = false,
    val email: String = "",
    val system: EvoSystem? = null,
    val updatedAt: Long = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val loggingIn: Boolean = false,
    val loginError: String? = null,
    /** Zóny (a "mode", "dhw"), na ktoré sa práve posiela príkaz. */
    val pending: Set<String> = emptySet(),
)

/**
 * Kúrenie Honeywell evohome: stav, obnovovanie (najviac raz za 3 minúty) a príkazy. Všetka sieťová práca beží
 * na pozadí a každá chyba sa zobrazí ako text – nikdy nezhodí appku. Kúrenie nie je súčasťou automatiky
 * príchod/odchod, Všetko vypnúť ani Noc (tie pracujú len so zoznamom Tuya zariadení).
 */
class HeatController(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val store = EvohomeStore(app)
    private val mutex = Mutex()
    private val handler = CoroutineExceptionHandler { _, e ->
        android.util.Log.w("Domov", "kúrenie: chyba v pozadí", e)
        _ui.value = _ui.value.copy(loading = false, loggingIn = false, pending = emptySet(), error = evoErrorText(e))
    }

    @Volatile
    private var source: EvoSource? = null
    private var demo = false
    private var lastFetch = 0L
    private var followUp: Job? = null

    private val _ui = MutableStateFlow(HeatUi())
    val ui: StateFlow<HeatUi> = _ui

    init {
        try {
            val saved = store.load()
            if (saved != null) {
                source = cloudSource(saved.email, saved.password, saved.tokens, store.install())
                val cached = store.lastStatus()
                val install = store.install()
                val system = if (cached != null && install != null) runCatching { EvoParse.status(cached.first, install) }.getOrNull() else null
                lastFetch = if (system != null) cached!!.second else 0
                _ui.value = HeatUi(configured = true, email = saved.email, system = system, updatedAt = if (system != null) cached!!.second else 0)
            }
        } catch (e: Exception) {
            android.util.Log.w("Domov", "kúrenie: načítanie nastavení", e)
        }
    }

    private fun cloudSource(email: String, password: String, tokens: EvoTokens?, install: EvoInstall?): CloudEvoSource {
        val client = EvohomeClient(email, password, tokens = tokens, onTokens = { t ->
            if (!demo && _ui.value.configured) runCatching { store.save(email, password, t) }
        })
        return CloudEvoSource(client, install, onInstall = { i -> runCatching { store.saveInstall(i) } })
    }

    /** Ukážka na snímky obrazovky (nič sa neukladá ani neposiela). */
    fun startDemo() {
        demo = true
        source = DemoEvoSource()
        lastFetch = 0
        _ui.value = HeatUi(configured = true, email = "ukazka@example.com")
        poll(force = true)
    }

    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    fun clearLoginError() {
        _ui.value = _ui.value.copy(loginError = null)
    }

    /** Prihlásenie: overí heslo, načíta inštaláciu a stav; uloží sa len pri úspechu. */
    fun login(email: String, password: String) {
        if (_ui.value.loggingIn) return
        val mail = email.trim()
        if (mail.isBlank() || password.isBlank()) {
            _ui.value = _ui.value.copy(loginError = "Zadaj e-mail aj heslo.")
            return
        }
        _ui.value = _ui.value.copy(loggingIn = true, loginError = null)
        scope.launch(handler) {
            val result = withContext(Dispatchers.IO) {
                mutex.withLock {
                    runCatching {
                        var tokens: EvoTokens? = null
                        val client = EvohomeClient(mail, password, onTokens = { tokens = it })
                        client.login()
                        val install = client.installation()
                        val src = CloudEvoSource(client, install)
                        val system = src.status()
                        Triple(install, system, Pair(src.lastRaw, tokens))
                    }
                }
            }
            result.fold(
                onSuccess = { (install, system, rest) ->
                    val now = System.currentTimeMillis()
                    runCatching {
                        store.save(mail, password, rest.second)
                        store.saveInstall(install)
                        rest.first?.let { store.saveStatus(it, now) }
                    }
                    source = cloudSource(mail, password, rest.second, install)
                    lastFetch = now
                    _ui.value = HeatUi(configured = true, email = mail, system = system, updatedAt = now)
                },
                onFailure = { e -> _ui.value = _ui.value.copy(loggingIn = false, loginError = evoErrorText(e)) },
            )
        }
    }

    fun logout() {
        followUp?.cancel()
        source = null
        demo = false
        runCatching { store.clear() }
        lastFetch = 0
        _ui.value = HeatUi()
    }

    /**
     * Obnoví stav, ak od posledného uplynuli aspoň 3 minúty ([force] = po príkaze alebo ťuknutí na Obnoviť,
     * aj vtedy najmenej 30 s od posledného dotazu). Server Honeywell pri častých dotazoch dočasne blokuje.
     */
    fun poll(force: Boolean = false) {
        val src = source ?: return
        val now = System.currentTimeMillis()
        val minGap = if (force) MIN_FORCED_MS else POLL_MS
        if (_ui.value.loading || now - lastFetch < minGap) return
        lastFetch = now
        _ui.value = _ui.value.copy(loading = true)
        scope.launch(handler) {
            val result = withContext(Dispatchers.IO) { mutex.withLock { runCatching { src.status() to (src as? CloudEvoSource)?.lastRaw } } }
            if (src !== source) return@launch // medzitým odhlásenie
            result.fold(
                onSuccess = { (system, raw) ->
                    val at = System.currentTimeMillis()
                    if (raw != null && !demo) runCatching { store.saveStatus(raw, at) }
                    _ui.value = _ui.value.copy(system = system, updatedAt = at, loading = false, error = null)
                },
                onFailure = { e -> _ui.value = _ui.value.copy(loading = false, error = evoErrorText(e)) },
            )
        }
    }

    /**
     * Pošle príkaz ([action] beží na pozadí a vráti, ako upraviť zobrazenie), hneď upraví zobrazenie
     * a o chvíľu načíta skutočný stav zo servera.
     */
    private fun command(key: String, action: (EvoSource) -> (EvoSystem) -> EvoSystem) {
        val src = source ?: return
        if (key in _ui.value.pending) return
        _ui.value = _ui.value.copy(pending = _ui.value.pending + key, error = null)
        scope.launch(handler) {
            val result = withContext(Dispatchers.IO) { mutex.withLock { runCatching { action(src) } } }
            if (src !== source) return@launch
            val cur = _ui.value
            result.fold(
                onSuccess = { update -> _ui.value = cur.copy(pending = cur.pending - key, system = cur.system?.let { runCatching { update(it) }.getOrDefault(it) }) },
                onFailure = { e -> _ui.value = cur.copy(pending = cur.pending - key, error = evoErrorText(e)) },
            )
            // brána RFG100 príkaz vykoná do pár sekúnd, potom sa stav overí na serveri
            followUp?.cancel()
            followUp = scope.launch(handler) {
                delay(if (demo) 500 else FOLLOW_UP_MS)
                lastFetch = minOf(lastFetch, System.currentTimeMillis() - MIN_FORCED_MS)
                poll(force = true)
            }
        }
    }

    private fun EvoSystem.withZone(id: String, change: (EvoZone) -> EvoZone) = copy(zones = zones.map { if (it.id == id) change(it) else it })

    fun setZone(zone: EvoZone, value: Double, duration: HeatDuration) {
        val target = evoClamp(value, zone)
        command(zone.id) { src ->
            val now = System.currentTimeMillis()
            val until = when (duration) {
                HeatDuration.PERMANENT -> null
                HeatDuration.HOUR1 -> now + 3600_000L
                HeatDuration.HOUR2 -> 2 * 3600_000L + now
                HeatDuration.NEXT_CHANGE -> src.nextSwitch(zone.id, now)
                    ?: throw EvoException(EvoErrorKind.REJECTED, "program zóny nie je známy, vyber Natrvalo alebo 1 h")
            }
            src.setZone(zone.id, target, until)
            val update: (EvoSystem) -> EvoSystem = { s ->
                s.withZone(zone.id) { it.copy(setpoint = target, mode = if (until == null) EvoZoneMode.PERMANENT else EvoZoneMode.TEMPORARY, until = until) }
            }
            update
        }
    }

    fun followSchedule(zone: EvoZone) {
        command(zone.id) { src ->
            src.followSchedule(zone.id)
            val update: (EvoSystem) -> EvoSystem = { s -> s.withZone(zone.id) { it.copy(mode = EvoZoneMode.FOLLOW, until = null) } }
            update
        }
    }

    fun setMode(mode: String) {
        command("mode") { src ->
            src.setMode(mode)
            val update: (EvoSystem) -> EvoSystem = { s -> s.copy(mode = mode, permanent = true, modeUntil = null) }
            update
        }
    }

    fun setDhw(on: Boolean?) {
        command("dhw") { src ->
            src.setDhw(on)
            val update: (EvoSystem) -> EvoSystem = { s ->
                s.copy(dhw = s.dhw?.copy(state = if (on == null) s.dhw.state else if (on) "On" else "Off", mode = if (on == null) EvoZoneMode.FOLLOW else EvoZoneMode.PERMANENT, until = null))
            }
            update
        }
    }

    private companion object {
        const val POLL_MS = 3 * 60_000L
        const val MIN_FORCED_MS = 30_000L
        const val FOLLOW_UP_MS = 15_000L
    }
}
