package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Spoločná synchronizácia kalendára, nákupu a jedálnička medzi telefónmi domácnosti.
 * Čistá logika (bez Androidu): odvodenie témy ntfy a kľúča AES, šifrovanie AES-GCM,
 * last-writer-wins zlúčenie položiek a balenie správ pod limit ntfy.sh (~4 kB).
 *
 * Téma aj kľúč sa odvodia z kľúča domácnosti, ktorý už telefóny zdieľajú (pozvánka / export).
 * Na verejný ntfy.sh ide len nečitateľný šifrovaný text; server kľúč nepozná.
 */
object HouseSync {
    const val MAGIC = "DMVS1"
    const val VERSION: Byte = 1
    /** Rezerva pod typický limit tela správy ntfy.sh (4096 B); šifrovaný Base64 je dlhší. */
    const val MAX_PLAIN = 2400
    const val KIND_EVENT = "event"
    const val KIND_SHOP = "shop"
    const val KIND_RECIPE = "recipe"
    const val KIND_MEAL = "meal"
    const val KIND_PANTRY = "pantry"

    /**
     * Časť príliš veľkého záznamu (napr. recept s dlhým postupom nad limit správy ntfy). Nie je v [KINDS]: staršia verzia
     * ju bez chyby preskočí, nová ju cez [reassemble] zloží späť na pôvodný záznam.
     */
    const val KIND_PART = "part"
    /** Ako dlho sa držia nekompletné časti, kým nepríde zvyšok (potom sa zahodia; snímka ich pošle znova). */
    const val PART_TTL_MS = 3L * 24 * 60 * 60 * 1000
    private const val PART_MAX_COUNT = 64
    private const val PART_MAX_ENTRIES = 100
    private const val PART_MAX_CHARS = 200_000
    val KINDS = setOf(KIND_EVENT, KIND_SHOP, KIND_RECIPE, KIND_MEAL, KIND_PANTRY)
    const val TYPE_ITEMS = "items"
    const val TYPE_WANT = "want"
    const val TOMBSTONE_MS = 90L * 24 * 60 * 60 * 1000
    /** Predvolené „since“ pri prvom ťahaní z ntfy (ntfy neberie jednotku dní, napr. „3d“ vráti 400). */
    const val SINCE_DEFAULT = "72h"

    /** Verejná téma ntfy: „domov“ + 32 hex znakov zo SHA-256 (neuhádnuteľná bez kľúča domácnosti). */
    fun topic(householdKey: String): String {
        val hex = sha256((householdKey + "domov-ntfy-v1").toByteArray(Charsets.UTF_8))
        return "domov" + hex.take(32)
    }

    /** 256-bit kľúč AES, odvodený z kľúča domácnosti (iný reťazec ako téma). */
    fun aesKey(householdKey: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest((householdKey + "domov-sync-aes-v1").toByteArray(Charsets.UTF_8))

    fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /**
     * Zašifruje bajty: MAGIC + verzia + 12 B nonce + ciphertext (AES-256-GCM, tag 128 bit).
     * Výsledok je URL-safe Base64 bez paddingu, vhodné ako telo ntfy.
     */
    fun encrypt(plain: ByteArray, key: ByteArray): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val ct = cipher.doFinal(plain)
        val out = ByteArray(5 + 1 + nonce.size + ct.size)
        MAGIC.toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        out[5] = VERSION
        nonce.copyInto(out, 6)
        ct.copyInto(out, 18)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out)
    }

    /** Dešifruje token; pri zlom tvare, inej verzii alebo zlom kľúči vráti null (správu preskočíme). */
    fun decrypt(token: String, key: ByteArray): ByteArray? {
        val raw = try {
            Base64.getUrlDecoder().decode(token.trim())
        } catch (_: Exception) {
            return null
        }
        if (raw.size < 18 + 16) return null
        if (String(raw, 0, 5, Charsets.US_ASCII) != MAGIC) return null
        if (raw[5] != VERSION) return null
        val nonce = raw.copyOfRange(6, 18)
        val ct = raw.copyOfRange(18, raw.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.doFinal(ct)
        } catch (_: AEADBadTagException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    fun encryptText(plain: String, key: ByteArray): String = encrypt(plain.toByteArray(Charsets.UTF_8), key)

    fun decryptText(token: String, key: ByteArray): String? =
        decrypt(token, key)?.toString(Charsets.UTF_8)

    /** Jeden záznam v obálke: druh, id, čas zmeny, id telefónu (remíza), tombstone a telo položky. */
    fun record(kind: String, id: String, updatedAt: Long, deviceId: String, deleted: Boolean, payload: JSONObject): JSONObject =
        JSONObject()
            .put("k", kind)
            .put("i", id)
            .put("u", updatedAt)
            .put("d", deviceId)
            .put("x", deleted)
            .put("p", payload)

    fun keyOf(rec: JSONObject): String = rec.optString("k") + ":" + rec.optString("i")

    /**
     * Last-writer-wins: novší [u] vyhrá; pri rovnosti vyšší reťazec [d] (id telefónu).
     * Tombstone je bežný záznam s [x]=true – vymazanie sa tak prenesie aj na druhý telefón.
     */
    fun merge(local: JSONObject?, incoming: JSONObject): JSONObject {
        if (local == null) return incoming
        val lu = local.optLong("u")
        val iu = incoming.optLong("u")
        if (iu > lu) return incoming
        if (iu < lu) return local
        return if (incoming.optString("d") > local.optString("d")) incoming else local
    }

    /** Či príchodzí záznam v LWW vyhrá nad miestnym (novší [updatedAt], pri rovnosti vyšší [deviceId]). */
    fun incomingWins(localUpdatedAt: Long, localDeviceId: String, incomingUpdatedAt: Long, incomingDeviceId: String): Boolean {
        if (incomingUpdatedAt != localUpdatedAt) return incomingUpdatedAt > localUpdatedAt
        return incomingDeviceId > localDeviceId
    }

    /**
     * Po úspešnom odoslaní: z aktuálnej fronty odstráň len kľúče, ktoré sa naozaj poslali.
     * Kľúče pridané počas odosielania ostanú.
     */
    fun remainingPending(current: List<String>, sent: Collection<String>): List<String> {
        if (sent.isEmpty()) return current
        val drop = sent.toSet()
        return current.filter { it !in drop }
    }

    /**
     * Zlúči prichádzajúce záznamy do mapy. Vrátia sa aj kľúče, pri ktorých vyhral príchod
     * (tie treba zapísať do úložiska).
     */
    fun applyIncoming(local: Map<String, JSONObject>, incoming: List<JSONObject>): Pair<Map<String, JSONObject>, List<String>> {
        val next = HashMap(local)
        val changed = ArrayList<String>()
        for (inc in incoming) {
            val id = inc.optString("i")
            val kind = inc.optString("k")
            if (id.isEmpty() || kind !in KINDS) continue
            val key = keyOf(inc)
            val merged = merge(next[key], inc)
            if (next[key] !== merged && next[key]?.toString() != merged.toString()) {
                next[key] = merged
                changed += key
            } else if (key !in next) {
                next[key] = merged
                changed += key
            }
        }
        return next to changed
    }

    fun envelope(from: String, type: String, items: List<JSONObject> = emptyList()): JSONObject {
        val arr = JSONArray()
        for (o in items) arr.put(o)
        return JSONObject()
            .put("v", 1)
            .put("t", type)
            .put("f", from)
            .put("i", arr)
    }

    fun parseEnvelope(plain: String): JSONObject? = try {
        val o = JSONObject(plain)
        if (o.optInt("v") != 1) null else o
    } catch (_: Exception) {
        null
    }

    fun envelopeItems(env: JSONObject): List<JSONObject> {
        val arr = env.optJSONArray("i") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
    }

    /**
     * Rozdelí záznamy do obálok, aby otvorený text každej ostal pod [MAX_PLAIN].
     * Prázdny zoznam -> žiadna obálka (okrem type=want, tá ide vždy jedna).
     */
    fun pack(from: String, type: String, items: List<JSONObject>): List<String> {
        if (type == TYPE_WANT) return listOf(envelope(from, TYPE_WANT).toString())
        if (items.isEmpty()) return emptyList()
        return packAll(from, type, items.flatMap { item ->
            if (item.optString("k") != KIND_PART && envelope(from, type, listOf(item)).toString().length > MAX_PLAIN) splitRecord(from, type, item)
            else listOf(item)
        })
    }

    private fun utf8Len(s: String) = s.toByteArray(Charsets.UTF_8).size

    /** Rozdelí text na kúsky najviac [size] znakov tak, aby sa nepretrhol náhradný pár (emoji). */
    private fun chunkText(text: String, size: Int): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            var end = minOf(text.length, i + size)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            out += text.substring(i, end)
            i = end
        }
        return out
    }

    /**
     * Rozdelí príliš veľký záznam na časti [KIND_PART], z ktorých každá sa zmestí do jednej správy pod [MAX_PLAIN] bajtov.
     * Časť nesie kľúč pôvodného záznamu, jeho čas zmeny a telefón, poradie a počet častí.
     */
    fun splitRecord(from: String, type: String, rec: JSONObject): List<JSONObject> {
        val text = rec.toString()
        val key = keyOf(rec)
        fun partsOf(size: Int): List<JSONObject> {
            val chunks = chunkText(text, size)
            return chunks.mapIndexed { n, c ->
                JSONObject().put("k", KIND_PART).put("i", key).put("u", rec.optLong("u")).put("d", rec.optString("d"))
                    .put("n", n).put("of", chunks.size).put("s", c)
            }
        }
        var size = 1300
        while (size > 100) {
            val parts = partsOf(size)
            if (parts.all { utf8Len(envelope(from, type, listOf(it)).toString()) <= MAX_PLAIN }) return parts
            size -= 150
        }
        return partsOf(100)
    }

    /**
     * Zloží časti veľkých záznamov späť. [buffer] = rozpracované časti z minulých synchronizácií (ukladá sa medzi nimi),
     * [incoming] = záznamy z novej dávky v poradí. Bežné záznamy prejdú bezo zmeny, časti sa zbierajú, a keď je záznam
     * kompletný, vráti sa ako jeden celok. Opakovaná časť nič nepokazí a zlý alebo neúplný záznam sa zahodí.
     */
    fun reassemble(buffer: JSONObject, incoming: List<JSONObject>, now: Long, maxAge: Long = PART_TTL_MS): Pair<JSONObject, List<JSONObject>> {
        val buf = try { JSONObject(buffer.toString()) } catch (_: Exception) { JSONObject() }
        for (k in buf.keys().asSequence().toList()) {
            val e = buf.optJSONObject(k)
            if (e == null || now - e.optLong("t") > maxAge) buf.remove(k)
        }
        val done = ArrayList<JSONObject>()
        for (rec in incoming) {
            if (rec.optString("k") != KIND_PART) { done += rec; continue }
            val key = rec.optString("i")
            val of = rec.optInt("of", 0)
            val n = rec.optInt("n", -1)
            val chunk = rec.optString("s", "")
            if (key.isEmpty() || of !in 1..PART_MAX_COUNT || n !in 0 until of || chunk.isEmpty()) continue
            val bk = "$key|${rec.optLong("u")}|${rec.optString("d")}"
            var e = buf.optJSONObject(bk)
            if (e == null || e.optInt("of") != of) {
                e = JSONObject().put("of", of).put("t", now).put("s", JSONObject())
                buf.put(bk, e)
            }
            val sObj = e.getJSONObject("s")
            sObj.put(n.toString(), chunk)
            if (sObj.length() < of) continue
            val whole = StringBuilder()
            var complete = true
            for (i in 0 until of) {
                val c = sObj.optString(i.toString(), "")
                if (c.isEmpty()) { complete = false; break }
                whole.append(c)
            }
            if (!complete) continue
            buf.remove(bk)
            val original = try { JSONObject(whole.toString()) } catch (_: Exception) { null } ?: continue
            if (original.optString("k") == KIND_PART || keyOf(original) != key) continue
            // novší záznam vyhral: staršie nedokončené časti toho istého kľúča už nie sú potrebné
            for (other in buf.keys().asSequence().toList()) {
                val parts = other.split('|')
                if (parts.size == 3 && parts[0] == key && (parts[1].toLongOrNull() ?: 0L) <= rec.optLong("u")) buf.remove(other)
            }
            done += original
        }
        // strop veľkosti: pri nadmernom množstve sa zahodia najstaršie rozpracované časti
        fun total(): Int = buf.keys().asSequence().sumOf { buf.optJSONObject(it)?.toString()?.length ?: 0 }
        while ((buf.length() > PART_MAX_ENTRIES || total() > PART_MAX_CHARS) && buf.length() > 0) {
            val oldest = buf.keys().asSequence().minByOrNull { buf.optJSONObject(it)?.optLong("t") ?: 0L } ?: break
            buf.remove(oldest)
        }
        return buf to done
    }

    private fun packAll(from: String, type: String, items: List<JSONObject>): List<String> {
        val out = ArrayList<String>()
        var batch = ArrayList<JSONObject>()
        fun flush() {
            if (batch.isEmpty()) return
            out += envelope(from, type, batch).toString()
            batch = ArrayList()
        }
        for (item in items) {
            val trial = ArrayList(batch).also { it += item }
            val text = envelope(from, type, trial).toString()
            if (text.length > MAX_PLAIN && batch.isNotEmpty()) {
                flush()
                val single = envelope(from, type, listOf(item)).toString()
                if (single.length > MAX_PLAIN) {
                    // príliš veľká položka – aj tak ju pošleme (vzácne); príjemca s limitom ju môže zahodiť
                    out += single
                } else {
                    batch += item
                }
            } else {
                batch += item
            }
        }
        flush()
        return out
    }

    /** Zahodí tombstone staršie ako [TOMBSTONE_MS] (živé položky ostávajú). */
    fun pruneTombstones(items: List<JSONObject>, now: Long, maxAge: Long = TOMBSTONE_MS): List<JSONObject> =
        items.filter { !it.optBoolean("x") || now - it.optLong("u") < maxAge }

    /**
     * Parameter `since` pre ntfy poll: prázdny alebo staré „3d“ (ktoré ntfy odmietne) ide na [SINCE_DEFAULT],
     * inak sa použije uložené id poslednej správy.
     */
    fun sinceParam(cursor: String): String {
        val s = cursor.trim()
        if (s.isEmpty() || s.equals("3d", ignoreCase = true)) return SINCE_DEFAULT
        return s
    }

    fun shortError(lastError: String): String = lastError.trim().take(80)

    fun statusText(active: Boolean, syncing: Boolean, lastOkAt: Long, lastError: String, now: Long): String {
        if (!active) return ""
        if (syncing) return "Synchronizuje sa…"
        val err = shortError(lastError)
        if (lastOkAt <= 0L) {
            return if (err.isNotEmpty()) "Synchronizácia zlyhala: $err" else "Zatiaľ nesynchronizované"
        }
        val ago = now - lastOkAt
        val whenOk = when {
            ago < 2L * 60_000 -> "pred chvíľou"
            ago < 60L * 60_000 -> "pred ${(ago / 60_000).toInt()} min."
            ago < 24L * 60 * 60_000 -> "pred ${(ago / 3_600_000).toInt()} h"
            else -> "pred ${(ago / (24 * 3_600_000)).toInt()} d."
        }
        if (err.isNotEmpty()) return "Posledná synchronizácia zlyhala: $err (naposledy v poriadku $whenOk)"
        return "Synchronizované $whenOk"
    }
}
