package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/*
 * Záloha a obnovenie (bez Androidu – testuje sa v tools/BackupTest.kt).
 *
 * Súbor .domov:  "DOMOVZAL" | verzia (1 B) | KDF (1 B, 1 = PBKDF2-HMAC-SHA256) | iterácie (4 B) | soľ (16 B) | nonce (12 B)
 *                | kontrola hesla (16 B) | AES-256-GCM(gzip(JSON)) s tagom 128 bitov; celá hlavička je AAD.
 * Z hesla sa odvodí 64 B: prvých 32 = kľúč AES, ďalších 32 = kľúč na kontrolu hesla (v hlavičke je len SHA-256 z neho),
 * takže appka vie rozlíšiť nesprávne heslo od poškodeného súboru.
 */

/** Chyba zálohy so slovenskou správou pre používateľa. */
class BackupException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NOT_BACKUP, NEWER, WRONG_PASSWORD, CORRUPTED, INVALID }
}

object BackupCrypto {
    val MAGIC = "DOMOVZAL".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val KDF_PBKDF2 = 1
    const val ITERATIONS = 310_000
    const val MIN_ITERATIONS = 100_000
    const val MAX_ITERATIONS = 5_000_000
    private const val SALT = 16
    private const val NONCE = 12
    private const val CHECK = 16
    val HEADER = MAGIC.size + 1 + 1 + 4 + SALT + NONCE + CHECK
    const val MAX_SIZE = 20 * 1024 * 1024
    private val random = SecureRandom()

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): Pair<ByteArray, ByteArray> {
        val spec = PBEKeySpec(password, salt, iterations, 512)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return raw.copyOfRange(0, 32) to MessageDigest.getInstance("SHA-256").digest(raw.copyOfRange(32, 64)).copyOf(CHECK)
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(plain: ByteArray, password: CharArray, iterations: Int = ITERATIONS): ByteArray {
        require(password.isNotEmpty())
        val salt = ByteArray(SALT).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
        val (key, check) = derive(password, salt, iterations)
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION.toByte()).put(KDF_PBKDF2.toByte()).putInt(iterations)
            .put(salt).put(nonce).put(check).array()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(header)
        return header + c.doFinal(gzip(plain))
    }

    /** Je to súbor zálohy (podľa hlavičky)? */
    fun looksLikeBackup(data: ByteArray): Boolean = data.size >= MAGIC.size && data.copyOf(MAGIC.size).contentEquals(MAGIC)

    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        if (!looksLikeBackup(data)) throw BackupException(BackupException.Kind.NOT_BACKUP, "Toto nie je záloha appky Domov (súbor .domov).")
        if (data.size > MAX_SIZE) throw corrupted()
        if (data.size < MAGIC.size + 1) throw corrupted()
        val version = data[MAGIC.size].toInt() and 0xff
        if (version > VERSION) {
            throw BackupException(BackupException.Kind.NEWER, "Záloha je z novšej verzie appky Domov. Najprv si appku aktualizuj, potom ju obnov.")
        }
        if (version < 1 || data.size < HEADER + 16) throw corrupted()
        val b = ByteBuffer.wrap(data)
        b.position(MAGIC.size + 1)
        val kdf = b.get().toInt() and 0xff
        val iterations = b.int
        if (kdf != KDF_PBKDF2 || iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw corrupted()
        val salt = ByteArray(SALT).also { b.get(it) }
        val nonce = ByteArray(NONCE).also { b.get(it) }
        val check = ByteArray(CHECK).also { b.get(it) }
        val (key, expected) = derive(password, salt, iterations)
        if (!MessageDigest.isEqual(check, expected)) throw BackupException(BackupException.Kind.WRONG_PASSWORD, "Nesprávne heslo zálohy.")
        val plain = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            c.updateAAD(data, 0, HEADER)
            c.doFinal(data, HEADER, data.size - HEADER)
        } catch (e: AEADBadTagException) {
            throw corrupted()
        } catch (e: Exception) {
            throw corrupted()
        }
        return try { gunzip(plain) } catch (e: Exception) { throw corrupted() }
    }

    fun corrupted() = BackupException(BackupException.Kind.CORRUPTED, "Súbor zálohy je poškodený alebo upravený – obnoviť sa nedá.")

    fun gzip(d: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(d) } }.toByteArray()

    fun gunzip(d: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(d)).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_SIZE * 4) throw IllegalStateException("príliš veľké")
            }
        }
        return out.toByteArray()
    }
}

/** Čo všetko záloha obsahuje: súbory nastavení a kľúče v nich (ostatné sú len dočasné – stav, história, cache). */
object BackupSpec {
    const val DEVICES_FILE = "domov"
    private val FILES: Map<String, (String) -> Boolean> = linkedMapOf(
        DEVICES_FILE to { k -> k == "devices" },
        "domov_scenes" to { _ -> true },
        "domov_presence" to { k -> k == "config" },
        "domov_household" to { k -> k == "id" || k == "name" || k == "key" || k == "members" },
        "domov_fryer" to { k -> k.startsWith("set_") },
        "domov_modes" to { k -> k.startsWith("modes_") },
        "domov_recipes" to { k -> k.startsWith("recipes_") }, // od 1.34 (vlastné recepty fritézy)
        "domov_widgets" to { k -> k.startsWith("w_") },
        "domov_ui" to { _ -> true },
        "domov_weather" to { k -> k == "settings" }, // od 1.12 (len nastavenie, nie stiahnuté údaje)
        "domov_moon" to { k -> k == "visible" }, // od 1.26 (zobrazenie pásu fázy mesiaca)
        "domov_shopping" to { k -> k == "items" }, // od 1.30 (nákupný zoznam)
        "domov_events" to { k -> k == "events" || k == "time" }, // od 1.31 (udalosti a čas upozornení)
    )
    val files: Set<String> get() = FILES.keys
    fun included(file: String, key: String): Boolean = FILES[file]?.invoke(key) == true
}

/** Obsah zálohy. [prefs]: súbor → kľúč → hodnota (String, Boolean, Long, Int, Float, Set<String>). */
data class BackupPayload(
    val prefs: Map<String, Map<String, Any>>,
    /** true = prihlasovacie údaje sú súčasťou zálohy (null = nenastavené → pri „Nahradiť všetko“ sa zmažú). */
    val secretsIncluded: Boolean = false,
    val cloud: JSONObject? = null,
    val evohome: JSONObject? = null,
    val appVersion: String = "",
    val versionCode: Int = 0,
    val created: Long = 0L,
    val format: Int = FORMAT,
) {
    val devices: JSONArray
        get() = (prefs[BackupSpec.DEVICES_FILE]?.get("devices") as? String)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()

    fun toJson(): JSONObject {
        val p = JSONObject()
        for ((file, values) in prefs) {
            val o = JSONObject()
            for ((k, v) in values) o.put(k, encodeValue(v))
            p.put(file, o)
        }
        return JSONObject().put("format", format).put("app", "Domov").put("appVersion", appVersion).put("versionCode", versionCode)
            .put("created", created).put("prefs", p).put("secretsIncluded", secretsIncluded)
            .apply {
                if (secretsIncluded) {
                    put("secrets", JSONObject().apply { cloud?.let { put("tuyaCloud", it) }; evohome?.let { put("evohome", it) } })
                }
            }
    }

    fun toBytes(): ByteArray = toJson().toString().toByteArray(Charsets.UTF_8)

    companion object {
        /** Formát obsahu (JSON). Staršie formáty sa načítajú, novší je chyba. */
        const val FORMAT = 1

        private fun encodeValue(v: Any): JSONObject = when (v) {
            is String -> JSONObject().put("s", v)
            is Boolean -> JSONObject().put("b", v)
            is Long -> JSONObject().put("l", v)
            is Int -> JSONObject().put("i", v)
            is Float -> JSONObject().put("f", v.toDouble())
            is Set<*> -> JSONObject().put("set", JSONArray(v.map { it.toString() }))
            else -> JSONObject().put("s", v.toString())
        }

        private fun decodeValue(o: JSONObject): Any? = when {
            o.has("s") -> o.getString("s")
            o.has("b") -> o.getBoolean("b")
            o.has("l") -> o.getLong("l")
            o.has("i") -> o.getInt("i")
            o.has("f") -> o.getDouble("f").toFloat()
            o.has("set") -> o.getJSONArray("set").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            else -> null
        }

        fun fromBytes(data: ByteArray): BackupPayload {
            val o = try {
                JSONObject(data.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                throw BackupCrypto.corrupted()
            }
            return fromJson(o)
        }

        fun fromJson(o: JSONObject): BackupPayload {
            val format = o.optInt("format", 0)
            if (format > FORMAT) {
                throw BackupException(BackupException.Kind.NEWER, "Záloha je z novšej verzie appky Domov (${o.optString("appVersion")}). Najprv si appku aktualizuj.")
            }
            if (format < 1 || o.optString("app") != "Domov") throw BackupCrypto.corrupted()
            try {
                val prefs = linkedMapOf<String, Map<String, Any>>()
                val p = o.optJSONObject("prefs") ?: JSONObject()
                for (file in p.keys()) {
                    if (file !in BackupSpec.files) continue // neznáme súbory (napr. z budúcich verzií) sa ignorujú
                    val fo = p.getJSONObject(file)
                    val m = linkedMapOf<String, Any>()
                    for (k in fo.keys()) {
                        if (!BackupSpec.included(file, k)) continue
                        decodeValue(fo.getJSONObject(k))?.let { m[k] = it }
                    }
                    prefs[file] = m
                }
                val secrets = o.optJSONObject("secrets")
                return BackupPayload(
                    prefs = prefs,
                    secretsIncluded = o.optBoolean("secretsIncluded", false),
                    cloud = secrets?.optJSONObject("tuyaCloud"),
                    evohome = secrets?.optJSONObject("evohome"),
                    appVersion = o.optString("appVersion"),
                    versionCode = o.optInt("versionCode"),
                    created = o.optLong("created"),
                    format = format,
                )
            } catch (e: BackupException) {
                throw e
            } catch (e: Exception) {
                throw BackupCrypto.corrupted()
            }
        }

        /** Zašifruje zálohu heslom. */
        fun seal(payload: BackupPayload, password: CharArray, iterations: Int = BackupCrypto.ITERATIONS): ByteArray =
            BackupCrypto.encrypt(payload.toBytes(), password, iterations)

        /** Otvorí súbor zálohy (chyby ako [BackupException] so slovenskou správou) a skontroluje obsah. */
        fun open(data: ByteArray, password: CharArray): BackupPayload = fromBytes(BackupCrypto.decrypt(data, password)).also { Backup.validate(it) }
    }
}

/** Zhrnutie zálohy pred obnovením. */
data class BackupSummary(
    val devices: Int,
    val groups: Int,
    val arrive: Int,
    val depart: Int,
    val presenceOn: Boolean,
    val nightSet: Boolean,
    val household: Boolean,
    val members: Int,
    val fryerSettings: Int,
    val cloud: Boolean,
    val evohome: Boolean,
    val appVersion: String,
    val created: Long,
) {
    fun lines(): List<String> = buildList {
        add("${devices} ${plural(devices, "zariadenie", "zariadenia", "zariadení")}, $groups ${plural(groups, "skupina", "skupiny", "skupín")}")
        add(
            if (arrive + depart > 0 || presenceOn) "Príchod a odchod: ${if (presenceOn) "zapnutý" else "vypnutý"}, pri príchode $arrive, pri odchode $depart"
            else "Príchod a odchod: nenastavený",
        )
        add("Scéna Noc: " + if (nightSet) "nastavená" else "predvolená")
        if (household) add("Domácnosť: kľúč domácnosti" + if (members > 0) ", $members ${plural(members, "ďalší telefón", "ďalšie telefóny", "ďalších telefónov")}" else "")
        if (fryerSettings > 0) add("Upozornenia fritézy: $fryerSettings")
        add(
            "Prihlasovacie údaje: " + listOfNotNull("Tuya cloud".takeIf { cloud }, "Honeywell".takeIf { evohome }).ifEmpty { listOf("nie sú v zálohe") }.joinToString(", "),
        )
    }

    companion object {
        fun plural(n: Int, one: String, few: String, many: String) = when (n) {
            1 -> one
            in 2..4 -> few
            else -> many
        }
    }
}

/** Ako obnoviť. */
enum class RestoreMode { REPLACE, MERGE }

/** Konflikt pri zlúčení: zariadenie je v telefóne aj v zálohe, ale s inými údajmi. */
data class DeviceConflict(val backupId: String, val currentId: String, val name: String, val currentName: String, val differences: List<String>)

/** Ukladanie nastavení (Android: SharedPreferences s commit, prihlasovacie údaje šifrované Keystore). */
interface PrefsSink {
    fun read(file: String): Map<String, Any>
    /** Prepíše zálohované kľúče súboru (ostatné nechá) – naraz a synchrónne. */
    fun write(file: String, values: Map<String, Any>)
    fun readSecrets(): Pair<JSONObject?, JSONObject?>
    /** Nastaví prihlasovacie údaje; null = zmazať. */
    fun writeSecrets(cloud: JSONObject?, evohome: JSONObject?)
}

/** Zašifrovanie interných súborov (Android: kľúč v Keystore). */
interface Sealer {
    fun seal(data: ByteArray): ByteArray
    fun open(data: ByteArray): ByteArray
}

object Backup {
    /** Skontroluje obsah pred zápisom – ak niečo nesedí, nezapíše sa nič. */
    fun validate(p: BackupPayload) {
        fun bad(what: String): Nothing = throw BackupException(BackupException.Kind.INVALID, "Záloha má neplatné údaje ($what) – obnoviť sa nedá.")
        val raw = p.prefs[BackupSpec.DEVICES_FILE]?.get("devices")
        if (raw != null) {
            val arr = (raw as? String)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: bad("zariadenia")
            val ids = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: bad("zariadenie ${i + 1}")
                val id = o.optString("id")
                if (id.isBlank() || o.optString("name").isBlank() || !ids.add(id)) bad("zariadenie ${i + 1}")
            }
        }
        (p.prefs["domov_presence"]?.get("config") as? String)?.let { if (runCatching { JSONObject(it) }.isFailure) bad("príchod a odchod") }
        (p.prefs["domov_scenes"]?.get("night") as? String)?.let { if (runCatching { JSONObject(it) }.isFailure) bad("scéna Noc") }
        p.cloud?.let { if (it.optString("id").isBlank() || it.optString("secret").isBlank()) bad("Tuya cloud") }
        p.evohome?.let { if (it.optString("email").isBlank() || it.optString("password").isBlank()) bad("Honeywell") }
    }

    fun summary(p: BackupPayload): BackupSummary {
        val devs = p.devices
        val groups = HashSet<String>()
        for (i in 0 until devs.length()) devs.optJSONObject(i)?.optString("group")?.trim()?.takeIf { it.isNotEmpty() }?.let { groups += it }
        val cfg = (p.prefs["domov_presence"]?.get("config") as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val hh = p.prefs["domov_household"].orEmpty()
        val members = (hh["members"] as? String)?.let { runCatching { JSONArray(it).length() }.getOrNull() } ?: 0
        return BackupSummary(
            devices = devs.length(),
            groups = groups.size,
            arrive = cfg?.optJSONArray("arrive")?.length() ?: 0,
            depart = cfg?.optJSONArray("depart")?.length() ?: 0,
            presenceOn = cfg?.optBoolean("enabled", false) == true,
            nightSet = p.prefs["domov_scenes"]?.containsKey("night") == true,
            household = hh["key"] != null,
            members = members,
            fryerSettings = p.prefs["domov_fryer"]?.keys?.count { it.startsWith("set_") } ?: 0,
            cloud = p.cloud != null,
            evohome = p.evohome != null,
            appVersion = p.appVersion,
            created = p.created,
        )
    }

    /** JSON s kľúčmi v abecednom poradí – na porovnanie, či sú dve zariadenia rovnaké. */
    fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + canonical(v.opt(k)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.opt(it)) }
        is String -> JSONObject.quote(v)
        null, JSONObject.NULL -> "null"
        else -> v.toString()
    }

    private fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun match(cur: List<JSONObject>, b: JSONObject): JSONObject? =
        cur.firstOrNull { it.optString("id") == b.optString("id") }
            ?: b.optString("devId").takeIf { it.isNotBlank() }?.let { dev -> cur.firstOrNull { it.optString("devId") == dev } }

    private val LABELS = linkedMapOf(
        "name" to "názov", "ip" to "IP adresa", "localKey" to "local key", "version" to "verzia", "devId" to "Device ID",
        "group" to "skupina", "labels" to "názvy hodnôt", "hidden" to "skryté hodnoty", "lightRoles" to "zdroje svetla", "mapping" to "popis hodnôt",
    )

    /** Zariadenia, ktoré sú v telefóne aj v zálohe, ale líšia sa (pri zlúčení sa používateľ rozhodne). */
    fun conflicts(current: BackupPayload, backup: BackupPayload): List<DeviceConflict> {
        val cur = objects(current.devices)
        return objects(backup.devices).mapNotNull { b ->
            val c = match(cur, b) ?: return@mapNotNull null
            val bb = JSONObject(b.toString()).put("id", c.optString("id"))
            if (canonical(bb) == canonical(c)) return@mapNotNull null
            val keys = (bb.keys().asSequence() + c.keys().asSequence()).toSet()
            val diff = keys.filter { canonical(bb.opt(it)) != canonical(c.opt(it)) }.map { LABELS[it] ?: "ďalšie nastavenia" }.distinct()
            DeviceConflict(b.optString("id"), c.optString("id"), b.optString("name"), c.optString("name"), diff)
        }
    }

    /**
     * Zlúči zálohu do aktuálneho stavu. Zariadenia podľa ID (alebo Device ID): nové sa pridajú na koniec, rovnaké ostanú,
     * pri konflikte rozhodne [useBackup] (ID zo zálohy → true = použiť zo zálohy; predvolene ostane existujúce).
     * Ostatné nastavenia: existujúce ostanú, chýbajúce sa doplnia zo zálohy; nové zariadenia sa doplnia do Príchodu a Odchodu.
     */
    fun merge(current: BackupPayload, backup: BackupPayload, useBackup: Map<String, Boolean> = emptyMap()): BackupPayload {
        val cur = objects(current.devices)
        val result = cur.map { JSONObject(it.toString()) }.toMutableList()
        val idMap = HashMap<String, String>() // ID v zálohe → ID v telefóne
        val takenFromBackup = HashSet<String>() // ID (v telefóne) zariadení, ktorých nastavenia idú zo zálohy
        val added = HashSet<String>()
        for (b in objects(backup.devices)) {
            val bid = b.optString("id")
            val c = match(cur, b)
            if (c == null) {
                if (result.none { it.optString("id") == bid }) {
                    result += JSONObject(b.toString())
                    idMap[bid] = bid
                    added += bid
                    takenFromBackup += bid
                }
                continue
            }
            val cid = c.optString("id")
            idMap[bid] = cid
            val bb = JSONObject(b.toString()).put("id", cid)
            if (canonical(bb) != canonical(c) && useBackup[bid] == true) {
                val idx = result.indexOfFirst { it.optString("id") == cid }
                if (idx >= 0) result[idx] = bb
                takenFromBackup += cid
            }
        }
        val prefs = linkedMapOf<String, Map<String, Any>>()
        for (file in BackupSpec.files) {
            val c = current.prefs[file].orEmpty()
            val b = backup.prefs[file].orEmpty()
            if (current.prefs[file] == null && backup.prefs[file] == null) continue
            val m = LinkedHashMap(c)
            when (file) {
                BackupSpec.DEVICES_FILE -> m["devices"] = JSONArray(result).toString()
                "domov_presence" -> mergePresence(c["config"] as? String, b["config"] as? String, idMap, added)?.let { m["config"] = it }
                "domov_scenes" -> if (c["night"] == null) (b["night"] as? String)?.let { m["night"] = mapIds(it, idMap) }
                "domov_household" -> for ((k, v) in b) if (k !in m) m[k] = v
                else -> for ((k, v) in b) {
                    val prefix = listOf("set_", "modes_", "recipes_").firstOrNull { k.startsWith(it) }
                    val key = if (prefix != null) prefix + (idMap[k.removePrefix(prefix)] ?: k.removePrefix(prefix)) else k
                    val owner = if (prefix != null) key.removePrefix(prefix) else null
                    if (key !in m || (owner != null && owner in takenFromBackup)) m[key] = v
                }
            }
            prefs[file] = m
        }
        return BackupPayload(
            prefs = prefs,
            // prihlasovacie údaje zo zálohy len tam, kde v telefóne chýbajú; existujúce sa nemenia
            secretsIncluded = false,
            cloud = if (current.cloud == null) backup.cloud else null,
            evohome = if (current.evohome == null) backup.evohome else null,
            appVersion = backup.appVersion,
            versionCode = backup.versionCode,
            created = backup.created,
        )
    }

    private fun mapIds(json: String, idMap: Map<String, String>): String = runCatching {
        val o = JSONObject(json)
        for (k in o.keys().asSequence().toList()) {
            val a = o.optJSONArray(k) ?: continue
            o.put(k, JSONArray((0 until a.length()).map { a.opt(it) }.map { if (it is String) idMap[it] ?: it else it }))
        }
        o.toString()
    }.getOrDefault(json)

    private fun mergePresence(cur: String?, bak: String?, idMap: Map<String, String>, added: Set<String>): String? {
        if (bak == null) return cur
        if (cur == null) return mapIds(bak, idMap)
        return runCatching {
            val c = JSONObject(cur)
            val b = JSONObject(mapIds(bak, idMap))
            for (k in listOf("arrive", "depart")) {
                val ba = b.optJSONArray(k) ?: continue
                val ca = c.optJSONArray(k) ?: if (k == "arrive") JSONArray() else continue // odchod nevybraný = predvolený výber
                val have = (0 until ca.length()).map { ca.optString(it) }.toMutableSet()
                for (i in 0 until ba.length()) {
                    val id = ba.optString(i)
                    if (id in added && have.add(id)) ca.put(id)
                }
                c.put(k, ca)
            }
            c.toString()
        }.getOrDefault(cur)
    }
}

/**
 * Obnovenie naraz: pred zápisom poistná záloha aktuálneho stavu (na „Vrátiť späť“) a denník s cieľovým stavom.
 * Ak by appka spadla uprostred zápisu, pri ďalšom spustení [recover] zápis dokončí – nikdy neostane polovičný stav.
 */
class BackupApplier(private val dir: File, private val sink: PrefsSink, private val sealer: Sealer) {
    val safetyFile = File(dir, "pred-obnovou.bin")
    private val journal = File(dir, "obnova-prebieha.bin")

    fun snapshot(includeSecrets: Boolean, appVersion: String = "", versionCode: Int = 0, now: Long = System.currentTimeMillis()): BackupPayload {
        val prefs = linkedMapOf<String, Map<String, Any>>()
        for (file in BackupSpec.files) {
            val m = sink.read(file).filterKeys { BackupSpec.included(file, it) }
            if (m.isNotEmpty()) prefs[file] = m
        }
        val (cloud, evo) = if (includeSecrets) sink.readSecrets() else null to null
        return BackupPayload(prefs, includeSecrets, cloud, evo, appVersion, versionCode, now)
    }

    fun hasSafety(): Boolean = safetyFile.exists()

    fun safetyTime(): Long = if (safetyFile.exists()) safetyFile.lastModified() else 0L

    /**
     * Obnoví [backup]. REPLACE: zálohované súbory sa prepíšu (súbory, ktoré záloha nemá, ostanú); MERGE: zlúčenie,
     * [useBackup] rozhoduje konflikty. Pri chybe pred zápisom sa nezmení nič.
     */
    fun restore(backup: BackupPayload, mode: RestoreMode, useBackup: Map<String, Boolean> = emptyMap(), makeSafety: Boolean = true) {
        Backup.validate(backup)
        val current = snapshot(includeSecrets = true)
        val target = if (mode == RestoreMode.MERGE) Backup.merge(current, backup, useBackup) else backup
        Backup.validate(target)
        dir.mkdirs()
        if (makeSafety) writeAtomic(safetyFile, sealer.seal(current.toBytes()))
        writeAtomic(journal, sealer.seal(target.toBytes()))
        applyNow(target)
        journal.delete()
    }

    /** Vráti stav spred poslednej obnovy. */
    fun undo(): Boolean {
        if (!safetyFile.exists()) return false
        val p = BackupPayload.fromBytes(sealer.open(safetyFile.readBytes()))
        // aby išlo vrátiť všetko: súbory, ktoré pred obnovou neexistovali, sa vyprázdnia
        val full = p.copy(prefs = BackupSpec.files.associateWith { p.prefs[it].orEmpty() })
        restore(full, RestoreMode.REPLACE, makeSafety = false)
        safetyFile.delete()
        return true
    }

    /** Dokončí prerušené obnovenie (volá sa pri štarte appky). Vráti true, ak niečo dokončil. */
    fun recover(): Boolean {
        if (!journal.exists()) return false
        val target = try {
            BackupPayload.fromBytes(sealer.open(journal.readBytes()))
        } catch (e: Exception) {
            journal.delete() // denník sa zapisuje naraz (premenovaním) – nečitateľný znamená, že sa nič nezačalo
            return false
        }
        applyNow(target)
        journal.delete()
        return true
    }

    private fun applyNow(target: BackupPayload) {
        for ((file, values) in target.prefs) if (file in BackupSpec.files) sink.write(file, values)
        if (target.secretsIncluded) {
            sink.writeSecrets(target.cloud, target.evohome)
        } else if (target.cloud != null || target.evohome != null) {
            val (c, e) = sink.readSecrets()
            sink.writeSecrets(target.cloud ?: c, target.evohome ?: e)
        }
    }

    companion object {
        fun writeAtomic(file: File, data: ByteArray) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { it.write(data); it.fd.sync() }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw java.io.IOException("Súbor sa nepodarilo uložiť")
            }
        }

        /** Názov súboru zálohy. */
        fun fileName(year: Int, month: Int, day: Int) = "domov-zaloha-%04d-%02d-%02d.domov".format(year, month, day)

        /** Ktoré automatické zálohy zmazať (ponechať posledné [keep]). */
        fun toDelete(names: List<String>, keep: Int = 4): List<String> =
            names.filter { it.matches(Regex("domov-zaloha-\\d{4}-\\d{2}-\\d{2}( \\(\\d+\\))?\\.domov")) }.sortedDescending().drop(keep)
    }
}
