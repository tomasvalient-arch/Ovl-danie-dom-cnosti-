import org.json.JSONArray
import org.json.JSONObject
import sk.domov.ovladanie.*
import java.io.File
import java.nio.file.Files

// Záloha a obnovenie: všetky typy údajov tam a späť, heslo, poškodený súbor, novšia verzia, zlúčenie, atomickosť, starší formát.

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

inline fun <reified T : Throwable> fails(block: () -> Unit): T? = try { block(); null } catch (e: Throwable) { e as? T ?: throw e }

/** SharedPreferences v pamäti; vie „spadnúť“ pri n-tom zápise. */
class MemSink : PrefsSink {
    val files = HashMap<String, MutableMap<String, Any>>()
    var cloud: JSONObject? = null
    var evo: JSONObject? = null
    var failAfter = -1
    var writes = 0
    override fun read(file: String): Map<String, Any> = files[file]?.toMap() ?: emptyMap()
    override fun write(file: String, values: Map<String, Any>) {
        if (failAfter >= 0 && writes >= failAfter) throw RuntimeException("telefón vypnutý počas zápisu")
        writes++
        val m = files.getOrPut(file) { HashMap() }
        m.keys.filter { BackupSpec.included(file, it) && it !in values }.forEach { m.remove(it) }
        m.putAll(values)
    }
    override fun discard(file: String) {
        val m = files[file] ?: return
        m.keys.filter { BackupSpec.included(file, it) }.forEach { m.remove(it) }
        if (m.isEmpty()) files.remove(file)
    }
    override fun readSecrets() = cloud to evo
    override fun writeSecrets(cloud: JSONObject?, evohome: JSONObject?) { this.cloud = cloud; this.evo = evohome }
    fun dump(): String = files.toSortedMap().map { (f, m) -> f + "=" + m.toSortedMap().map { (k, v) -> "$k:${if (v is Set<*>) v.map { it.toString() }.sorted() else v}" } }.joinToString("|") +
        "|cloud=" + (cloud?.let { Backup.canonical(it) }) + "|evo=" + (evo?.let { Backup.canonical(it) })
}

object TestSealer : Sealer {
    override fun seal(data: ByteArray) = data.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    override fun open(data: ByteArray) = seal(data)
}

fun dev(id: String, name: String, devId: String = "dev$id", key: String = "key$id", group: String = "", extra: JSONObject.() -> Unit = {}) =
    JSONObject().put("id", id).put("name", name).put("ip", "192.168.1.${id.hashCode() and 0x7f}").put("devId", devId).put("localKey", key)
        .put("version", "3.3").put("labels", JSONObject()).put("hidden", JSONArray()).put("group", group).apply(extra)

fun phone(): MemSink = MemSink().apply {
    files["domov"] = hashMapOf(
        "devices" to JSONArray(listOf(
            dev("a", "Obývačka svetlo", group = "Obývačka") {
                put("labels", JSONObject().put("20", "Svetlo")); put("hidden", JSONArray(listOf("101")))
                put("lightRoles", JSONObject().put("20", "main").put("51", "mix"))
                put("mapping", JSONObject().put("22", JSONObject().put("code", "bright_value_v2").put("type", "Integer").put("values", JSONObject().put("min", 10).put("max", 1000))))
            },
            dev("b", "fritéza BH-AF2", group = "Kuchyňa"),
            dev("c", "Ohrievač", group = "Kúpeľňa"),
        )).toString(),
        "devices_backup" to "nezálohuje sa",
    )
    files["domov_scenes"] = hashMapOf("night" to JSONObject().put("offIds", JSONArray(listOf("b", "c"))).put("dimIds", JSONArray(listOf("a"))).put("dimPercent", 7).toString())
    files["domov_presence"] = hashMapOf(
        "config" to JSONObject().put("enabled", true).put("home", JSONObject().put("gateway", "192.168.1.1").put("subnet", "192.168.1.0/24"))
            .put("arrive", JSONArray(listOf("a"))).put("depart", JSONArray(listOf("a", "c"))).put("delay", 5).put("onlyDark", true).put("darkMode", "SUN").toString(),
        "status" to "{\"state\":\"HOME\"}", "history" to "[]", "guard_c" to 123L,
    )
    files["domov_household"] = hashMapOf("id" to "phone-1", "name" to "Tomáš", "key" to "HHKEY-123", "members" to JSONArray(listOf(JSONObject().put("id", "p2").put("name", "Priateľka"))).toString())
    files["domov_fryer"] = hashMapOf("set_b" to "{\"enabled\":true,\"oneMinute\":false}", "watch_b" to "{}", "perm_asked" to true, "cloud_b" to 5L)
    files["domov_modes"] = hashMapOf("modes_b" to "Chips,Steak")
    files["domov_widgets"] = hashMapOf("w_17" to "a")
    files["domov_ui"] = hashMapOf("theme" to "dark", "compact" to true, "scale" to 1.25f, "count" to 3, "tabs" to setOf("x", "y"))
    files["domov_cache"] = hashMapOf("values_a" to "{}")
    files["domov_weather"] = hashMapOf("settings" to "{\"visible\":false,\"source\":\"CITY\",\"name\":\"Žilina\",\"lat\":49.22,\"lon\":18.74}", "cache" to "{\"raw\":\"...\"}", "attempt" to 5L)
    files["domov_moon"] = hashMapOf("visible" to false, "tmp" to "x")
    cloud = JSONObject().put("id", "cloudid").put("secret", "cloudsecret").put("dc", "eu")
    evo = JSONObject().put("email", "t@example.sk").put("password", "heslo")
}

fun main() {
    val tmp = Files.createTempDirectory("zaloha").toFile()
    val pw = "Moje heslo 123".toCharArray()

    // ---- tam a späť
    val src = phone()
    val snap = BackupApplier(File(tmp, "src"), src, TestSealer).snapshot(includeSecrets = false, appVersion = "1.11", versionCode = 17, now = 1_000L)
    val file = BackupPayload.seal(snap, pw)
    check("hlavička: DOMOVZAL, verzia 1, PBKDF2 310 000 iterácií", String(file, 0, 8) == "DOMOVZAL" && file[8].toInt() == 1 && file[9].toInt() == 1 &&
        java.nio.ByteBuffer.wrap(file, 10, 4).int == 310_000)
    check("obsah je zašifrovaný (local key ani kľúč domácnosti v súbore nie sú)", !String(file, Charsets.ISO_8859_1).contains("keya") && !String(file, Charsets.ISO_8859_1).contains("HHKEY"))
    val file2 = BackupPayload.seal(snap, pw)
    check("náhodná soľ a nonce (dve zálohy sa líšia)", !file.contentEquals(file2) && !file.copyOfRange(14, 42).contentEquals(file2.copyOfRange(14, 42)))
    val opened = BackupPayload.open(file, pw)
    check("bez prihlasovacích údajov (predvolene)", !opened.secretsIncluded && opened.cloud == null && opened.evohome == null)
    val fresh = MemSink()
    BackupApplier(File(tmp, "fresh"), fresh, TestSealer).restore(opened, RestoreMode.REPLACE)
    val expected = phone().apply {
        files["domov"]!!.remove("devices_backup"); files.remove("domov_cache")
        files["domov_presence"]!!.keys.retainAll(setOf("config")); files["domov_fryer"]!!.keys.retainAll(setOf("set_b")); files["domov_weather"]!!.keys.retainAll(setOf("settings")); files["domov_moon"]!!.keys.retainAll(setOf("visible"))
        cloud = null; evo = null
    }
    check("tam a späť: zariadenia (kľúče, popis hodnôt, zdroje svetla, názvy, skryté, skupiny, poradie), Noc, automatika, domácnosť, fritéza, režimy, widgety, UI (String/Boolean/Long/Int/Float/Set)",
        fresh.dump() == expected.dump())
    check("dočasné údaje sa nezálohujú (stav, história, cache, poistky)", fresh.files["domov_presence"]!!.keys == setOf("config") && "domov_cache" !in fresh.files)
    val s = Backup.summary(opened)
    check("zhrnutie: 3 zariadenia, 3 skupiny, príchod 1, odchod 2", s.devices == 3 && s.groups == 3 && s.arrive == 1 && s.depart == 2 && s.presenceOn)
    check("zhrnutie: text", s.lines().first() == "3 zariadenia, 3 skupiny" && s.lines().any { it == "Domácnosť: kľúč domácnosti, 1 ďalší telefón" } &&
        s.lines().last() == "Prihlasovacie údaje: nie sú v zálohe")

    // ---- s prihlasovacími údajmi
    val withSecrets = BackupPayload.open(BackupPayload.seal(BackupApplier(File(tmp, "s2"), phone(), TestSealer).snapshot(true, "1.11", 17), pw), pw)
    check("s prihlasovacími údajmi: Tuya cloud aj Honeywell", withSecrets.cloud?.optString("secret") == "cloudsecret" && withSecrets.evohome?.optString("password") == "heslo" &&
        Backup.summary(withSecrets).lines().last() == "Prihlasovacie údaje: Tuya cloud, Honeywell")
    val p3 = MemSink().apply { cloud = JSONObject().put("id", "iné").put("secret", "iné") }
    BackupApplier(File(tmp, "p3"), p3, TestSealer).restore(opened, RestoreMode.REPLACE)
    check("záloha bez údajov: existujúci Tuya cloud ostane", p3.cloud?.optString("id") == "iné")
    BackupApplier(File(tmp, "p3"), p3, TestSealer).restore(withSecrets, RestoreMode.REPLACE)
    check("záloha s údajmi: Tuya cloud prepísaný", p3.cloud?.optString("id") == "cloudid" && p3.evo?.optString("email") == "t@example.sk")

    // ---- heslo a poškodenie
    check("nesprávne heslo", fails<BackupException> { BackupPayload.open(file, "zlé heslo".toCharArray()) }?.kind == BackupException.Kind.WRONG_PASSWORD)
    check("nesprávne heslo: slovenská správa", fails<BackupException> { BackupPayload.open(file, "x".toCharArray()) }?.message == "Nesprávne heslo zálohy.")
    val tampered = file.copyOf().also { it[it.size - 40] = (it[it.size - 40].toInt() xor 1).toByte() }
    check("upravený obsah -> poškodený", fails<BackupException> { BackupPayload.open(tampered, pw) }?.kind == BackupException.Kind.CORRUPTED)
    val tagged = file.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
    check("upravený overovací tag -> poškodený", fails<BackupException> { BackupPayload.open(tagged, pw) }?.kind == BackupException.Kind.CORRUPTED)
    check("skrátený súbor -> poškodený", fails<BackupException> { BackupPayload.open(file.copyOf(file.size / 2), pw) }?.kind == BackupException.Kind.CORRUPTED)
    check("len hlavička -> poškodený", fails<BackupException> { BackupPayload.open(file.copyOf(20), pw) }?.kind == BackupException.Kind.CORRUPTED)
    val iters = file.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(10, 999_999_999) }
    check("nezmyselný počet iterácií -> poškodený (bez dlhého výpočtu)", fails<BackupException> { BackupPayload.open(iters, pw) }?.kind == BackupException.Kind.CORRUPTED)
    check("iný súbor (devices.json) -> nie je záloha", fails<BackupException> { BackupPayload.open("[{\"name\":\"x\"}]".toByteArray(), pw) }?.kind == BackupException.Kind.NOT_BACKUP)
    check("prázdny súbor -> nie je záloha", fails<BackupException> { BackupPayload.open(ByteArray(0), pw) }?.kind == BackupException.Kind.NOT_BACKUP)
    val newer = file.copyOf().also { it[8] = 2 }
    check("novšia verzia súboru", fails<BackupException> { BackupPayload.open(newer, pw) }?.kind == BackupException.Kind.NEWER)
    val newerFormat = BackupCrypto.encrypt(snap.toJson().put("format", 2).put("appVersion", "2.0").toString().toByteArray(), pw)
    check("novšia verzia obsahu", fails<BackupException> { BackupPayload.open(newerFormat, pw) }?.let { it.kind == BackupException.Kind.NEWER && it.message!!.contains("2.0") } == true)
    val badData = BackupCrypto.encrypt(snap.toJson().put("prefs", JSONObject().put("domov", JSONObject().put("devices", JSONObject().put("s", "[{\"id\":\"\"}]")))).toString().toByteArray(), pw)
    check("neplatné zariadenia -> chyba pred zápisom", fails<BackupException> { BackupPayload.open(badData, pw) }?.kind == BackupException.Kind.INVALID)

    // ---- starší formát: menej iterácií, bez niektorých častí (fritéza, widgety, UI ešte neboli)
    val old = JSONObject().put("format", 1).put("app", "Domov").put("appVersion", "1.11").put("created", 5L).put("prefs", JSONObject()
        .put("domov", JSONObject().put("devices", JSONObject().put("s", JSONArray(listOf(dev("z", "Stará lampa"))).toString())))
        .put("domov_neznamy", JSONObject().put("x", JSONObject().put("s", "y")))
        .put("domov_presence", JSONObject().put("config", JSONObject().put("s", "{\"enabled\":false}")).put("status", JSONObject().put("s", "x"))))
    val oldFile = BackupCrypto.encrypt(old.toString().toByteArray(), pw, 200_000)
    val oldP = BackupPayload.open(oldFile, pw)
    check("starší formát (200 000 iterácií) sa otvorí", oldP.devices.length() == 1 && oldP.prefs["domov_presence"]!!.keys == setOf("config") && "domov_neznamy" !in oldP.prefs)
    val o1 = phone()
    BackupApplier(File(tmp, "old"), o1, TestSealer).restore(oldP, RestoreMode.REPLACE)
    check("starší formát, Nahradiť: zariadenia a automatika zo zálohy, časti, ktoré záloha nemá, ostanú",
        JSONArray(o1.files["domov"]!!["devices"] as String).length() == 1 && o1.files["domov_fryer"]!!["set_b"] != null && o1.files["domov_household"]!!["key"] == "HHKEY-123" && (o1.files["domov_weather"]!!["settings"] as String).contains("Žilina") &&
            o1.files["domov_presence"]!!["status"] == "{\"state\":\"HOME\"}" && o1.files["domov_moon"]!!["visible"] == false)

    check("počasie (1.12): nastavenie v zálohe, stiahnuté údaje nie", fresh.files["domov_weather"]!!.keys == setOf("settings"))
    check("fáza mesiaca (1.26): zobrazenie v zálohe, ostatné kľúče nie", fresh.files["domov_moon"]!!.keys == setOf("visible") && fresh.files["domov_moon"]!!["visible"] == false)
    check("jedálniček (1.60): recepty, plán, zásoby a karta Domov v zálohe",
        BackupSpec.included("domov_meals", "recipes") && BackupSpec.included("domov_meals", "plan") &&
            BackupSpec.included("domov_meals", "pantry") && BackupSpec.included("domov_meals", "home") &&
            !BackupSpec.included("domov_meals", "tmp"))
    check("fáza mesiaca (1.26): stará záloha bez kľúča existujúce nastavenie nechá", o1.files["domov_moon"]!!["visible"] == false)

    // ---- zlúčenie
    val cur = MemSink().apply {
        files["domov"] = hashMapOf("devices" to JSONArray(listOf(
            dev("a", "Obývačka svetlo", group = "Obývačka") { // zmenená IP a local key -> konflikt
                put("ip", "10.0.0.5"); put("localKey", "novy")
                put("labels", JSONObject().put("20", "Svetlo")); put("hidden", JSONArray(listOf("101")))
                put("lightRoles", JSONObject().put("20", "main").put("51", "mix"))
                put("mapping", JSONObject().put("22", JSONObject().put("code", "bright_value_v2").put("type", "Integer").put("values", JSONObject().put("min", 10).put("max", 1000))))
            },
            dev("x9", "Ohrievač", devId = "devc", key = "keyc", group = "Kúpeľňa") { put("ip", "192.168.1.${"c".hashCode() and 0x7f}") }, // rovnaké zariadenie, iné ID v appke
            dev("n", "Nová lampa"),
        )).toString())
        files["domov_presence"] = hashMapOf("config" to JSONObject().put("enabled", true).put("arrive", JSONArray(listOf("n"))).put("depart", JSONArray(listOf("n"))).toString())
        files["domov_household"] = hashMapOf("id" to "phone-NEW", "key" to "HHKEY-NEW")
        files["domov_fryer"] = hashMapOf()
        cloud = JSONObject().put("id", "moje").put("secret", "moje")
    }
    val curSnap = BackupApplier(File(tmp, "m0"), cur, TestSealer).snapshot(true)
    val conf = Backup.conflicts(curSnap, withSecrets)
    if (conf.size != 1) println(conf)
    check("konflikt: len Obývačka svetlo (IP, local key)", conf.size == 1 && conf[0].backupId == "a" && conf[0].differences == listOf("IP adresa", "local key"))
    val m1 = MemSink().apply { files.putAll(cur.files.mapValues { HashMap(it.value) }); cloud = cur.cloud }
    BackupApplier(File(tmp, "m1"), m1, TestSealer).restore(withSecrets, RestoreMode.MERGE)
    val md = JSONArray(m1.files["domov"]!!["devices"] as String)
    val names = (0 until md.length()).map { md.getJSONObject(it).getString("name") }
    check("zlúčenie: poradie existujúcich ostane, nové sa pridajú na koniec", names == listOf("Obývačka svetlo", "Ohrievač", "Nová lampa", "fritéza BH-AF2"))
    check("zlúčenie, konflikt predvolene: ostane existujúce", md.getJSONObject(0).getString("localKey") == "novy")
    check("zlúčenie podľa Device ID: ohrievač sa nezdvojí, ID v appke ostane", md.getJSONObject(1).getString("id") == "x9")
    val cfg = JSONObject(m1.files["domov_presence"]!!["config"] as String)
    check("zlúčenie: nové zariadenia doplnené do príchodu/odchodu, existujúci výber ostane",
        cfg.getJSONArray("arrive").toString() == "[\"n\"]" && cfg.getJSONArray("depart").toString() == "[\"n\"]")
    check("zlúčenie: domácnosť – ID a kľúč telefónu ostanú, doplní sa meno a členovia",
        m1.files["domov_household"]!!["id"] == "phone-NEW" && m1.files["domov_household"]!!["key"] == "HHKEY-NEW" && m1.files["domov_household"]!!["name"] == "Tomáš")
    check("zlúčenie: nastavenia fritézy pre pridanú fritézu", m1.files["domov_fryer"]!!["set_b"] != null)
    check("zlúčenie: Noc chýbala -> zo zálohy, ID ohrievača preložené", JSONObject(m1.files["domov_scenes"]!!["night"] as String).getJSONArray("offIds").toString() == "[\"b\",\"x9\"]")
    check("zlúčenie: vlastný Tuya cloud ostane, Honeywell doplnený", m1.cloud?.optString("id") == "moje" && m1.evo?.optString("email") == "t@example.sk")
    val m2 = MemSink().apply { files.putAll(cur.files.mapValues { HashMap(it.value) }) }
    BackupApplier(File(tmp, "m2"), m2, TestSealer).restore(withSecrets, RestoreMode.MERGE, mapOf("a" to true))
    check("zlúčenie, konflikt „použiť zo zálohy“", JSONArray(m2.files["domov"]!!["devices"] as String).getJSONObject(0).getString("localKey") == "keya")
    val again = MemSink().apply { files.putAll(m2.files.mapValues { HashMap(it.value) }) }
    val before = again.dump()
    BackupApplier(File(tmp, "m3"), again, TestSealer).restore(withSecrets, RestoreMode.MERGE, mapOf("a" to true))
    check("zlúčenie dvakrát nič nezdvojí", JSONArray(again.files["domov"]!!["devices"] as String).length() == 4 && again.dump().substringBefore("|cloud") == before.substringBefore("|cloud"))

    // ---- atomickosť a Vrátiť späť
    val at = phone()
    val orig = at.dump()
    val ap = BackupApplier(File(tmp, "atom"), at, TestSealer)
    check("chyba v obsahu: nič sa nezapíše, žiadna poistka", fails<BackupException> {
        ap.restore(oldP.copy(prefs = mapOf("domov" to mapOf("devices" to "nie je json"))), RestoreMode.REPLACE)
    } != null && at.dump() == orig && !ap.hasSafety() && at.writes == 0)
    at.failAfter = 1
    check("výpadok počas zápisu", fails<RuntimeException> { ap.restore(oldP, RestoreMode.REPLACE) } != null && at.dump() != orig)
    at.failAfter = -1
    check("po reštarte sa obnova dokončí", ap.recover() && JSONArray(at.files["domov"]!!["devices"] as String).getJSONObject(0).getString("name") == "Stará lampa")
    check("denník po dokončení zmizne", !ap.recover())
    check("poistná záloha existuje", ap.hasSafety())
    check("Vrátiť späť: presne pôvodný stav (vrátane prihlasovacích údajov)", ap.undo() && at.dump() == orig && !ap.hasSafety())
    val e1 = MemSink()
    val ea = BackupApplier(File(tmp, "empty"), e1, TestSealer)
    ea.restore(withSecrets, RestoreMode.REPLACE)
    check("Vrátiť späť na prázdnu appku: všetko zmizne, aj prihlasovacie údaje", ea.undo() && e1.files.values.all { it.isEmpty() } && e1.cloud == null && e1.evo == null)

    // ---- automatická záloha: ponechať posledné 4
    val list = listOf("domov-zaloha-2026-10-01.domov", "domov-zaloha-2026-10-08.domov", "domov-zaloha-2026-10-15.domov", "domov-zaloha-2026-10-22.domov",
        "domov-zaloha-2026-10-29.domov", "domov-zaloha-2026-11-05.domov", "fotka.jpg", "domov-zaloha-2026-11-05 (1).domov")
    check("ponechať posledné 4", BackupApplier.toDelete(list) == listOf("domov-zaloha-2026-10-15.domov", "domov-zaloha-2026-10-08.domov", "domov-zaloha-2026-10-01.domov"))
    check("názov súboru", BackupApplier.fileName(2026, 11, 16) == "domov-zaloha-2026-11-16.domov")
    tmp.deleteRecursively()
    println("Backup: všetko OK")
}
