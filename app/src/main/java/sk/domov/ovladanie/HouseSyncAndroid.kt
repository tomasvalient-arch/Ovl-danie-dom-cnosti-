package sk.domov.ovladanie

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Behová synchronizácia kalendára, nákupu a jedálnička cez ntfy.sh.
 * Téma a kľúč AES sú odvodené z kľúča domácnosti; na server ide len šifrovaný text.
 * Pôvodná UDP správa „som doma“ (HouseholdNet) ostáva ako doteraz – toto je samostatný kanál,
 * ktorý funguje aj mimo domácej WiFi (mobilné dáta).
 *
 * Beží pri otvorení appky, po každej zmene zoznamu a pravidelne ([SyncJobService], 15 min).
 * Bez účtu. Zapne sa, keď je domácnosť aktívna (ďalší telefón alebo nedávno zdieľaný kľúč).
 */
object HouseSyncEngine {
    const val PREFS = "domov_sync"
    const val JOB_ID = 4712
    const val BASE = "https://ntfy.sh"
    private const val SNAPSHOT_EVERY_MS = 6L * 60 * 60 * 1000
    private const val WANT_EVERY_MS = 30L * 60 * 1000
    /**
     * Po pripojení do domácnosti (alebo po inštalácii) príde z druhého telefónu celý zoznam naraz. Toto okno ho nechá
     * stiahnuť bez upozornení („pridaných 30 vecí“); skončí hneď po prvej dávke záznamov alebo po tomto čase.
     */
    private const val QUIET_WINDOW_MS = 45L * 60 * 1000

    @Volatile var applying: Boolean = false
        private set

    @Volatile var syncing: Boolean = false
        private set

    private val lock = Any()
    /** Jeden prebiehajúci sync naraz; nie je to [lock], aby [onLocalChange] nečakal na sieť. */
    private val syncMutex = Any()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun listen(c: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(c).registerOnSharedPreferenceChangeListener(l)

    fun unlisten(c: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(c).unregisterOnSharedPreferenceChangeListener(l)

    fun active(c: Context): Boolean = HouseholdStore.active(c)

    fun statusText(c: Context): String {
        val p = prefs(c)
        return HouseSync.statusText(
            active(c),
            syncing,
            p.getLong("ok_at", 0L),
            p.getString("error", "").orEmpty(),
            System.currentTimeMillis(),
        )
    }

    /** Naplánuje (alebo zruší) pravidelnú synchronizáciu. */
    fun schedule(c: Context) {
        runCatching {
            val js = c.getSystemService(JobScheduler::class.java) ?: return
            if (!active(c)) {
                js.cancel(JOB_ID)
                return
            }
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(c, SyncJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60 * 1000L, 5 * 60 * 1000L)
                    .setPersisted(true)
                    .build(),
            )
        }
    }

    fun request(c: Context) {
        if (!active(c.applicationContext)) return
        val app = c.applicationContext
        Thread { runLocked(app) }.start()
    }

    // ------------------------------------------------------------------ živé počúvanie (od 1.68)

    private val listenScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listenLock = Any()
    private var listenJob: Job? = null
    @Volatile private var listenConn: HttpURLConnection? = null
    private val kickPending = AtomicBoolean(false)

    /**
     * Drží otvorený stream na téme ntfy: keď iný telefón domácnosti pošle zmenu (alebo žiadosť o zoznam), spustí sa
     * hneď synchronizácia. Bez toho by sa zmeny zo spoločného nákupu objavili až po otvorení appky alebo pri
     * 15-minútovom behu na pozadí. Stream len „zobudí“ synchronizáciu, samotné stiahnutie, kurzor a zlúčenie robí
     * [syncNow] ako doteraz. Beží, kým žije proces appky (napr. v službe Príchod a odchod).
     */
    fun startListening(c: Context) {
        if (!active(c)) return
        synchronized(listenLock) {
            if (listenJob?.isActive == true) return
            val app = c.applicationContext
            listenJob = listenScope.launch { listenLoop(app) }
        }
    }

    /** Kľúč domácnosti sa zmenil: stream na starej téme zrušiť (zavrie sa spojenie) a začať odznova na novej. */
    fun restartListening(c: Context) {
        synchronized(listenLock) {
            listenJob?.cancel()
            listenJob = null
            runCatching { listenConn?.disconnect() }
        }
        startListening(c)
    }

    /**
     * Správy prichádzajú po dávkach (zoznam sa posiela vo viacerých správach): spojí ich do jednej synchronizácie.
     * Značka sa zhodí pred behom, takže správa, čo príde počas synchronizácie, spustí ďalšiu a nič sa nestratí.
     */
    private fun kick(c: Context) {
        if (!kickPending.compareAndSet(false, true)) return
        val app = c.applicationContext
        Thread {
            try {
                Thread.sleep(600)
            } catch (_: InterruptedException) {
            }
            kickPending.set(false)
            runLocked(app)
        }.start()
    }

    /** Je riadok zo streamu správa od iného telefónu, ktorá si žiada synchronizáciu (zmeny alebo žiadosť o zoznam)? */
    private fun wakesSync(line: String, aes: ByteArray, selfId: String): Boolean {
        if (line.isBlank()) return false
        val msg = try { JSONObject(line) } catch (_: Exception) { return false }
        if (msg.optString("event") != "message") return false
        val token = msg.optString("message")
        if (token.isBlank()) return false
        val plain = HouseSync.decryptText(token, aes) ?: return false
        val env = HouseSync.parseEnvelope(plain) ?: return false
        if (env.optString("f") == selfId) return false
        return env.optString("t") == HouseSync.TYPE_ITEMS || env.optString("t") == HouseSync.TYPE_WANT
    }

    private suspend fun listenLoop(c: Context) {
        var backoff = 2_000L
        var byTime = false
        while (coroutineContext.isActive) {
            if (!active(c)) {
                delay(15_000)
                continue
            }
            var conn: HttpURLConnection? = null
            try {
                val key = HouseholdStore.key(c)
                val topic = HouseSync.topic(key)
                val aes = HouseSync.aesKey(key)
                val self = HouseholdStore.phoneId(c)
                val cursor = prefs(c).getString("cursor", "").orEmpty().trim()
                // od posledne spracovanej správy; bez nej (alebo keď ju ntfy odmietne) od pred 2 minútami
                val since = if (cursor.isNotEmpty() && !byTime) cursor else (System.currentTimeMillis() / 1000 - 120).toString()
                val http = URL("$BASE/$topic/json?since=$since").openConnection() as HttpURLConnection
                http.requestMethod = "GET"
                http.connectTimeout = 15_000
                // ntfy posiela „keepalive“ asi každých 45 s; ticho dlhšie ako toto = mŕtve spojenie (zmena siete, spánok)
                http.readTimeout = 150_000
                http.useCaches = false
                http.setRequestProperty("User-Agent", userAgent())
                conn = http
                listenConn = http
                val code = http.responseCode
                if (code !in 200..299) {
                    if (code == 400) byTime = true
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                    continue
                }
                backoff = 2_000L
                byTime = false
                val reader = http.inputStream.bufferedReader(Charsets.UTF_8)
                while (coroutineContext.isActive && active(c)) {
                    val line = reader.readLine() ?: break
                    if (wakesSync(line, aes, self)) kick(c)
                }
                // server spojenie zavrel: krátka pauza a znova
                delay(1_000)
            } catch (e: CancellationException) {
                throw e
            } catch (_: SocketTimeoutException) {
                delay(1_000)
            } catch (_: Exception) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            } finally {
                runCatching { conn?.disconnect() }
                if (listenConn === conn) listenConn = null
            }
        }
    }

    /** Jedna synchronizácia so zámkom; chybu zapíše aj keď zlyhá JobScheduler. */
    internal fun runLocked(c: Context) {
        synchronized(syncMutex) {
            runCatching { syncNow(c) }.onFailure {
                android.util.Log.w("Domov", "sync", it)
                markError(c, describeError(it))
            }
        }
    }

    fun onLocalChange(c: Context, kind: String, id: String) {
        if (!active(c)) return
        val key = "$kind:$id"
        synchronized(lock) {
            if (applying) return
            val p = prefs(c)
            val q = pending(p)
            if (key !in q) {
                q.add(key)
                p.edit().putString("pending", JSONArray(q).toString()).commit()
            }
        }
        request(c)
    }

    /** Volať po prijatí kľúča domácnosti: požiadať druhý telefón o úplný prehľad. */
    fun requestSnapshot(c: Context) {
        // prvá dávka z novej domácnosti je celý zoznam: bez upozornení na „pridané“ veci
        prefs(c).edit().putLong("want_at", 0L).putLong("snap_at", 0L).putLong("quiet_at", System.currentTimeMillis()).apply()
        request(c)
    }

    /**
     * Je to prvé stiahnutie zoznamu (nová inštalácia alebo nová domácnosť)? Vtedy sa o zmenách nákupu neupozorňuje.
     * Telefón, ktorý sa už raz úspešne synchronizoval (aj pred touto verziou), je hneď „zabehnutý“.
     */
    private fun isQuiet(p: SharedPreferences, now: Long): Boolean {
        if (!p.contains("quiet_at")) {
            val fresh = p.getLong("ok_at", 0L) == 0L
            p.edit().putLong("quiet_at", if (fresh) now else -1L).apply()
            return fresh
        }
        val at = p.getLong("quiet_at", -1L)
        return at > 0 && now - at < QUIET_WINDOW_MS
    }

    private fun endQuiet(p: SharedPreferences) {
        if (p.getLong("quiet_at", -1L) != -1L) p.edit().putLong("quiet_at", -1L).apply()
    }

    internal fun syncNow(c: Context) {
        if (!active(c)) return
        syncing = true
        touch(c)
        try {
            val key = HouseholdStore.key(c)
            val topic = HouseSync.topic(key)
            val aes = HouseSync.aesKey(key)
            val from = HouseholdStore.phoneId(c)
            val p = prefs(c)
            val now = System.currentTimeMillis()

            val quiet = isQuiet(p, now)
            val cursor = p.getString("cursor", "").orEmpty()
            val since = HouseSync.sinceParam(cursor)
            var get = poll(topic, since)
            // neplatné alebo už vypršanú kurzor (vrátane historického „3d“) skúsiť znova od predvoleného intervalu
            if (get.code == 400 && cursor.isNotBlank()) {
                p.edit().remove("cursor").apply()
                get = poll(topic, HouseSync.SINCE_DEFAULT)
            }
            if (get.code !in 200..299) {
                markError(c, describeHttp(get.code, get.body))
                return
            }

            var lastId = cursor
            var want = false
            val incoming = ArrayList<JSONObject>()
            for (line in get.body.lineSequence()) {
                if (line.isBlank()) continue
                val msg = try { JSONObject(line) } catch (_: Exception) { continue }
                if (msg.optString("event") !in listOf("", "message")) continue
                msg.optString("id").trim().takeIf { it.isNotEmpty() }?.let { lastId = it }
                val token = msg.optString("message")
                if (token.isBlank()) continue
                val plain = HouseSync.decryptText(token, aes) ?: continue
                val env = HouseSync.parseEnvelope(plain) ?: continue
                if (env.optString("f") == from) continue
                when (env.optString("t")) {
                    HouseSync.TYPE_WANT -> want = true
                    HouseSync.TYPE_ITEMS -> incoming += HouseSync.envelopeItems(env)
                }
            }

            if (incoming.isNotEmpty()) {
                try {
                    // veľké záznamy (recept s dlhým postupom) prichádzajú po častiach: zložia sa, až keď je kompletný celok
                    val parts = try { JSONObject(p.getString("parts", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
                    val (partsNext, whole) = HouseSync.reassemble(parts, incoming, now)
                    if (partsNext.toString() != parts.toString()) p.edit().putString("parts", partsNext.toString()).commit()
                    if (whole.isNotEmpty()) {
                        applyIncoming(c, whole, quiet)
                        endQuiet(p)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("Domov", "sync apply", e)
                    markError(c, "spracovanie správy: ${describeError(e)}")
                    return
                }
            }

            val lastSnap = p.getLong("snap_at", 0L)
            val lastWant = p.getLong("want_at", 0L)
            val lastOk = p.getLong("ok_at", 0L)
            val needSnap = want || lastSnap == 0L || now - lastSnap >= SNAPSHOT_EVERY_MS
            // keď ešte nemáme žiadny úspech, požiadame druhý telefón o snímku (max raz za 30 min)
            if (lastOk == 0L && now - lastWant >= WANT_EVERY_MS) {
                if (!publish(c, topic, aes, HouseSync.pack(from, HouseSync.TYPE_WANT, emptyList()))) return
                p.edit().putLong("want_at", now).apply()
            }

            val pendingKeys: List<String>
            val records: List<JSONObject>
            synchronized(lock) {
                pendingKeys = pending(p)
                records = if (needSnap) allRecords(c) else pendingRecords(c, pendingKeys)
            }
            val keysSent = records.map { HouseSync.keyOf(it) }
            if (records.isNotEmpty()) {
                if (!publish(c, topic, aes, HouseSync.pack(from, HouseSync.TYPE_ITEMS, records))) return
            }
            synchronized(lock) {
                val ed = p.edit()
                    .putString("pending", JSONArray(HouseSync.remainingPending(pending(p), keysSent)).toString())
                    .putString("error", "")
                    .putLong("ok_at", now)
                if (lastId.isNotBlank()) ed.putString("cursor", lastId)
                if (needSnap && records.isNotEmpty()) ed.putLong("snap_at", now)
                ed.commit()
            }
        } finally {
            syncing = false
            touch(c)
        }
    }

    private fun userAgent(): String = "Domov/${BuildConfig.VERSION_NAME}"

    private fun poll(topic: String, since: String) = Http.send(
        "GET",
        "$BASE/$topic/json?poll=1&since=$since",
        headers = mapOf("User-Agent" to userAgent()),
        connectMs = 12_000,
        readMs = 20_000,
    )

    private fun describeHttp(code: Int, body: String): String {
        val err = try { JSONObject(body).optString("error").trim() } catch (_: Exception) { "" }
        return if (err.isNotEmpty()) "http $code: $err" else "http $code"
    }

    private fun describeError(t: Throwable): String = when (t) {
        is java.net.UnknownHostException, is java.net.NoRouteToHostException -> "nie je pripojenie"
        is java.net.SocketTimeoutException -> "čas vypršal"
        is javax.net.ssl.SSLException -> "zlyhalo zabezpečené spojenie"
        is java.io.IOException -> "sieť: ${(t.message ?: t.javaClass.simpleName).trim().take(60)}"
        else -> (t.message ?: t.javaClass.simpleName).trim().take(80)
    }

    private fun publish(c: Context, topic: String, aes: ByteArray, plains: List<String>): Boolean {
        for (plain in plains) {
            val token = HouseSync.encryptText(plain, aes)
            val r = Http.send(
                "POST",
                "$BASE/$topic",
                headers = mapOf(
                    "User-Agent" to userAgent(),
                    "Content-Type" to "text/plain; charset=utf-8",
                    "Cache" to "yes",
                    "TTL" to "24h",
                ),
                body = token,
                connectMs = 12_000,
                readMs = 20_000,
            )
            if (r.code !in 200..299) {
                markError(c, describeHttp(r.code, r.body))
                return false
            }
        }
        return true
    }

    private fun applyIncoming(c: Context, incoming: List<JSONObject>, quiet: Boolean = false) {
        val now = System.currentTimeMillis()
        val prunedIn = HouseSync.pruneTombstones(incoming, now)
        val evIn = ArrayList<EventItem>()
        val shIn = ArrayList<ShopItem>()
        val recIn = ArrayList<Meals.Recipe>()
        val mealIn = ArrayList<Meals.Meal>()
        val panIn = ArrayList<Meals.PantryItem>()
        for (rec in prunedIn) {
            when (rec.optString("k")) {
                HouseSync.KIND_EVENT -> eventFrom(rec)?.let { evIn += it }
                HouseSync.KIND_SHOP -> shopFrom(rec)?.let { shIn += it }
                HouseSync.KIND_RECIPE -> recipeFrom(rec)?.let { recIn += it }
                HouseSync.KIND_MEAL -> mealFrom(rec)?.let { mealIn += it }
                HouseSync.KIND_PANTRY -> pantryFrom(rec)?.let { panIn += it }
            }
        }
        if (evIn.isEmpty() && shIn.isEmpty() && recIn.isEmpty() && mealIn.isEmpty() && panIn.isEmpty()) return
        var shopChanges: List<ShopChange> = emptyList()
        synchronized(lock) { applying = true }
        try {
            // zlúčenie prebehne atomicky v úložisku (aktuálny stav + LWW v jednom zámku)
            if (evIn.isNotEmpty()) EventsStore.mergeIncoming(c, evIn)
            if (shIn.isNotEmpty()) shopChanges = ShoppingStore.mergeIncomingChanges(c, shIn)
            if (recIn.isNotEmpty()) MealsStore.mergeIncomingRecipes(c, recIn)
            if (mealIn.isNotEmpty()) MealsStore.mergeIncomingMeals(c, mealIn)
            if (panIn.isNotEmpty()) MealsStore.mergeIncomingPantry(c, panIn)
        } finally {
            synchronized(lock) { applying = false }
        }
        // upozornenie až po zlúčení a mimo zámku; chyba upozornenia nesmie pokaziť synchronizáciu
        if (!quiet && shopChanges.isNotEmpty()) runCatching { ShoppingNotify.onRemoteChanges(c, shopChanges) }
    }

    private fun allRecords(c: Context): List<JSONObject> {
        val now = System.currentTimeMillis()
        val ev = EventsStore.all(c).map { eventRecord(it) }
        val sh = ShoppingList.forSync(ShoppingStore.all(c)).map { shopRecord(it) }
        val rec = MealsStore.recipes(c).map { recipeRecord(it) }
        val meal = MealsStore.meals(c).map { mealRecord(it) }
        val pan = MealsStore.pantry(c).map { pantryRecord(it) }
        return HouseSync.pruneTombstones(ev + sh + rec + meal + pan, now)
    }

    private fun pendingRecords(c: Context, keys: List<String>): List<JSONObject> {
        if (keys.isEmpty()) return emptyList()
        val set = keys.toSet()
        return allRecords(c).filter { HouseSync.keyOf(it) in set }
    }

    private fun eventRecord(e: EventItem): JSONObject {
        val arr = JSONArray(Events.toJson(listOf(e)))
        return HouseSync.record(HouseSync.KIND_EVENT, e.id, e.updatedAt, e.deviceId, e.deleted, arr.getJSONObject(0))
    }

    private fun shopRecord(s: ShopItem): JSONObject {
        val arr = JSONArray(ShoppingList.toJson(listOf(s)))
        return HouseSync.record(HouseSync.KIND_SHOP, s.id, s.updatedAt, s.deviceId, s.deleted, arr.getJSONObject(0))
    }

    private fun eventFrom(rec: JSONObject): EventItem? {
        val payload = rec.optJSONObject("p") ?: return null
        return Events.fromJson(JSONArray().put(payload).toString()).firstOrNull()?.copy(
            updatedAt = rec.optLong("u"),
            deleted = rec.optBoolean("x"),
            deviceId = rec.optString("d"),
        )
    }

    private fun shopFrom(rec: JSONObject): ShopItem? {
        val payload = rec.optJSONObject("p") ?: return null
        return ShoppingList.fromJson(JSONArray().put(payload).toString()).firstOrNull()?.copy(
            updatedAt = rec.optLong("u"),
            deleted = rec.optBoolean("x"),
            deviceId = rec.optString("d"),
        )
    }

    private fun recipeRecord(r: Meals.Recipe): JSONObject =
        HouseSync.record(HouseSync.KIND_RECIPE, r.id, r.updatedAt, r.deviceId, r.deleted, Meals.recipeObj(r))

    private fun mealRecord(m: Meals.Meal): JSONObject =
        HouseSync.record(HouseSync.KIND_MEAL, m.id, m.updatedAt, m.deviceId, m.deleted, Meals.mealObj(m))

    private fun pantryRecord(p: Meals.PantryItem): JSONObject =
        HouseSync.record(HouseSync.KIND_PANTRY, p.id, p.updatedAt, p.deviceId, p.deleted, Meals.pantryObj(p))

    private fun recipeFrom(rec: JSONObject): Meals.Recipe? {
        val payload = rec.optJSONObject("p") ?: return null
        return Meals.recipeFrom(payload)?.copy(
            updatedAt = rec.optLong("u"),
            deleted = rec.optBoolean("x"),
            deviceId = rec.optString("d"),
        )
    }

    private fun mealFrom(rec: JSONObject): Meals.Meal? {
        val payload = rec.optJSONObject("p") ?: return null
        return Meals.mealFrom(payload)?.copy(
            updatedAt = rec.optLong("u"),
            deleted = rec.optBoolean("x"),
            deviceId = rec.optString("d"),
        )
    }

    private fun pantryFrom(rec: JSONObject): Meals.PantryItem? {
        val payload = rec.optJSONObject("p") ?: return null
        return Meals.pantryFrom(payload)?.copy(
            updatedAt = rec.optLong("u"),
            deleted = rec.optBoolean("x"),
            deviceId = rec.optString("d"),
        )
    }

    private fun pending(p: SharedPreferences): MutableList<String> {
        val raw = p.getString("pending", "[]").orEmpty()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun markError(c: Context, msg: String) {
        prefs(c).edit().putString("error", msg.take(80)).putLong("try_at", System.currentTimeMillis()).apply()
    }

    private fun touch(c: Context) {
        prefs(c).edit().putLong("try_at", System.currentTimeMillis()).apply()
    }
}

/** Pravidelná synchronizácia (JobScheduler, každé 15 min, akákoľvek sieť vrátane mobilných dát). */
class SyncJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            try {
                if (HouseSyncEngine.active(app)) HouseSyncEngine.runLocked(app)
            } catch (e: Exception) {
                android.util.Log.w("Domov", "sync job", e)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
