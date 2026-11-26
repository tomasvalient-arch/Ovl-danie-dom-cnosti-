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
 * Súkromný chat medzi členmi domácnosti.
 * Čistá logika (bez Androidu): téma ntfy a kľúč AES, šifrovanie AES-256-GCM,
 * typ správy, limit príloh, rozpoznanie videa, náhľad pre notifikáciu, doručenie/prečítanie.
 *
 * Na verejný ntfy.sh ide len nečitateľný šifrovaný text a šifrované bajty prílohy; server kľúč nepozná.
 */
object Chat {
    const val MAGIC = "DMVC1"
    const val VERSION: Byte = 1
    const val TYPE_CHAT = "chat"
    const val TYPE_RECEIPT = "receipt"
    const val KIND_TEXT = "text"
    const val KIND_PHOTO = "photo"
    const val KIND_FILE = "file"
    const val THREAD_SHARED = "spolocny"
    const val STATUS_PENDING = "pending"
    const val STATUS_SENT = "sent"
    const val STATUS_FAILED = "failed"
    /** Verejný ntfy.sh berie prílohu do 15 728 640 B; necháme rezervu na hlavičku šifry. */
    const val MAX_ATTACH = 15_000_000
    const val MAX_META_PLAIN = 2400
    /** Koľko bajtov príloh sa ešte zmestí do zálohy (limit súboru zálohy je 20 MB). */
    const val BACKUP_FILES_BUDGET = 8_000_000
    val VIDEO_EXT = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "3g2", "mpeg", "mpg", "wmv", "flv", "ts")

    fun topic(householdKey: String): String {
        val hex = HouseSync.sha256((householdKey + "domov-chat-ntfy-v1").toByteArray(Charsets.UTF_8))
        return "domovc" + hex.take(32)
    }

    fun aesKey(householdKey: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest((householdKey + "domov-chat-aes-v1").toByteArray(Charsets.UTF_8))

    /** Zašifruje bajty: MAGIC + verzia + 12 B nonce + ciphertext (AES-256-GCM, tag 128 bit). */
    fun encryptBytes(plain: ByteArray, key: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val ct = cipher.doFinal(plain)
        val out = ByteArray(5 + 1 + nonce.size + ct.size)
        MAGIC.toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        out[5] = VERSION
        nonce.copyInto(out, 6)
        ct.copyInto(out, 18)
        return out
    }

    fun decryptBytes(raw: ByteArray, key: ByteArray): ByteArray? {
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

    fun encryptText(plain: String, key: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(encryptBytes(plain.toByteArray(Charsets.UTF_8), key))

    fun decryptText(token: String, key: ByteArray): String? {
        val raw = try {
            Base64.getUrlDecoder().decode(token.trim())
        } catch (_: Exception) {
            return null
        }
        return decryptBytes(raw, key)?.toString(Charsets.UTF_8)
    }

    fun isVideo(mime: String?, name: String?): Boolean {
        val m = mime?.lowercase()?.trim().orEmpty()
        if (m.startsWith("video/")) return true
        val ext = name?.substringAfterLast('.', "")?.lowercase()?.trim().orEmpty()
        return ext in VIDEO_EXT
    }

    fun tooBig(size: Long): Boolean = size > MAX_ATTACH

    fun rejectReason(mime: String?, name: String?, size: Long): String? = when {
        isVideo(mime, name) -> "Videá v chate nie sú podporované."
        tooBig(size) -> "Súbor je príliš veľký (max. 15 MB na ntfy.sh)."
        else -> null
    }

    /** Text na zamknutej obrazovke a v oznámení: meno odosielateľa ide mimo, tu je telo. */
    fun previewBody(kind: String, text: String, fileName: String): String = when (kind) {
        KIND_PHOTO -> "Fotka"
        KIND_FILE -> fileName.ifBlank { "Súbor" }
        else -> text
    }

    fun dmThread(a: String, b: String): String {
        val (x, y) = if (a <= b) a to b else b to a
        return "dm:$x:$y"
    }

    fun isShared(threadId: String): Boolean = threadId == THREAD_SHARED || threadId.isBlank()

    /** Pri dvoch členoch (ja + jeden ďalší) otvor tab rovno spoločný thread. */
    fun openSharedDirectly(otherCount: Int): Boolean = otherCount < 2

    /**
     * Dá sa už písať? Áno, keď poznáme aspoň jedného člena, alebo keď je tento telefón v domácnosti ([householdActive]:
     * kľúč domácnosti sa zdieľal alebo prevzal). Správy idú cez ntfy, takže druhý telefón nemusí byť na domácej WiFi
     * (zoznam členov sa plní len správami „som doma“ po WiFi, a tie môžu prísť až po dňoch).
     */
    fun isReady(otherCount: Int, householdActive: Boolean): Boolean = otherCount > 0 || householdActive

    /**
     * Mená ľudí, od ktorých prišla nejaká správa: jedno meno na odosielateľa (podľa ID), podľa jeho najnovšej správy
     * (premenoval sa), najčerstvejší hovoriaci prvý. Slúži na podnadpis chatu, kým druhý telefón ešte nepoznáme
     * zo správ „som doma“.
     */
    fun peerNames(messages: List<ChatMessage>): List<String> =
        messages.filter { !it.mine && it.senderId.isNotBlank() }
            .sortedByDescending { it.createdAt }
            .distinctBy { it.senderId }
            .map { it.senderName.ifBlank { "Telefón" } }

    fun newId(): String {
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        b[6] = (b[6].toInt() and 0x0f or 0x40).toByte()
        b[8] = (b[8].toInt() and 0x3f or 0x80).toByte()
        val h = b.joinToString("") { "%02x".format(it) }
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-" + h.substring(16, 20) + "-" + h.substring(20)
    }

    data class WireMsg(
        val type: String,
        val fromId: String,
        val fromName: String,
        val id: String,
        val thread: String,
        val kind: String,
        val text: String,
        val fileName: String,
        val fileSize: Long,
        val mime: String,
        val at: Long,
        val receiptFor: String = "",
    )

    fun pack(msg: WireMsg): String {
        val m = JSONObject()
            .put("id", msg.id)
            .put("th", msg.thread.ifBlank { THREAD_SHARED })
            .put("k", msg.kind)
            .put("tx", msg.text)
            .put("fn", msg.fileName)
            .put("sz", msg.fileSize)
            .put("mi", msg.mime)
            .put("at", msg.at)
        if (msg.receiptFor.isNotEmpty()) m.put("rf", msg.receiptFor)
        return JSONObject()
            .put("v", 1)
            .put("t", msg.type)
            .put("f", msg.fromId)
            .put("n", msg.fromName)
            .put("m", m)
            .toString()
    }

    fun parse(plain: String): WireMsg? { return try {
        val o = JSONObject(plain)
        if (o.optInt("v") != 1) return null
        val type = o.optString("t")
        if (type != TYPE_CHAT && type != TYPE_RECEIPT) return null
        val from = o.optString("f")
        if (from.isBlank()) return null
        val m = o.optJSONObject("m") ?: return null
        val id = m.optString("id")
        if (id.isBlank()) return null
        val kind = m.optString("k").ifBlank { KIND_TEXT }
        WireMsg(
            type = type,
            fromId = from,
            fromName = o.optString("n"),
            id = id,
            thread = m.optString("th").ifBlank { THREAD_SHARED },
            kind = kind,
            text = m.optString("tx"),
            fileName = m.optString("fn"),
            fileSize = m.optLong("sz"),
            mime = m.optString("mi"),
            at = m.optLong("at"),
            receiptFor = m.optString("rf"),
        )
    } catch (_: Exception) {
        null
    }
    }

    /**
     * Stav odoslanej správy: jeden sivý check = odoslané, dva jantárové = prečítané
     * (všetci ostatní známi členovia poslali potvrdenie; ak nikoho nepoznáme, stačí jedno).
     */
    enum class Tick { NONE, SENT, READ }

    fun tick(mine: Boolean, status: String, readerIds: Collection<String>, otherMemberIds: Collection<String>): Tick {
        if (!mine) return Tick.NONE
        if (status != STATUS_SENT) return Tick.NONE
        val others = otherMemberIds.filter { it.isNotBlank() }.toSet()
        val readers = readerIds.filter { it.isNotBlank() }.toSet()
        val read = if (others.isEmpty()) readers.isNotEmpty() else others.all { it in readers }
        return if (read) Tick.READ else Tick.SENT
    }

    fun looksLikePlaintextLeak(token: String): Boolean {
        val t = token.trim()
        if (t.isEmpty()) return false
        if (t.startsWith("{") || t.startsWith("[")) return true
        val lower = t.lowercase()
        if ("\"t\":\"chat\"" in lower || "\"tx\":" in lower) return true
        return false
    }

    /** Prílohy do zálohy od najnovších, kým sa zmestia do [budget]. */
    fun pickBackupFiles(items: List<Pair<String, ByteArray>>, budget: Int = BACKUP_FILES_BUDGET): Pair<Map<String, ByteArray>, List<String>> {
        val chosen = LinkedHashMap<String, ByteArray>()
        val skipped = ArrayList<String>()
        var used = 0
        for ((id, data) in items) {
            val need = 8 + (data.size * 4 / 3) // hrubý odhad base64 v JSON
            if (used + need > budget) skipped += id else {
                chosen[id] = data
                used += need
            }
        }
        return chosen to skipped
    }

    fun encodeBackupFiles(files: Map<String, ByteArray>): JSONObject {
        val o = JSONObject()
        for ((id, data) in files) o.put(id, Base64.getEncoder().encodeToString(data))
        return o
    }

    fun decodeBackupFiles(o: JSONObject?): Map<String, ByteArray> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, ByteArray>()
        for (k in o.keys()) {
            val raw = o.optString(k)
            if (raw.isBlank()) continue
            val data = try { Base64.getDecoder().decode(raw) } catch (_: Exception) { continue }
            out[k] = data
        }
        return out
    }
}

/** Riadok v histórii (text, fotka, súbor). Potvrdenia prečítania nie sú samostatné bubliny. */
data class ChatMessage(
    val id: String,
    val thread: String,
    val senderId: String,
    val senderName: String,
    val kind: String,
    val text: String,
    val fileName: String,
    val fileSize: Long,
    val mime: String,
    val localPath: String,
    val createdAt: Long,
    val status: String,
    val mine: Boolean,
    val readers: List<String>,
    val incomingRead: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("th", thread)
        .put("sid", senderId)
        .put("sn", senderName)
        .put("k", kind)
        .put("tx", text)
        .put("fn", fileName)
        .put("sz", fileSize)
        .put("mi", mime)
        .put("lp", localPath)
        .put("at", createdAt)
        .put("st", status)
        .put("mine", mine)
        .put("rd", JSONArray(readers))
        .put("seen", incomingRead)

    companion object {
        fun fromJson(o: JSONObject): ChatMessage? {
            val id = o.optString("id")
            if (id.isBlank()) return null
            val arr = o.optJSONArray("rd")
            val readers = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            return ChatMessage(
                id = id,
                thread = o.optString("th").ifBlank { Chat.THREAD_SHARED },
                senderId = o.optString("sid"),
                senderName = o.optString("sn"),
                kind = o.optString("k").ifBlank { Chat.KIND_TEXT },
                text = o.optString("tx"),
                fileName = o.optString("fn"),
                fileSize = o.optLong("sz"),
                mime = o.optString("mi"),
                localPath = o.optString("lp"),
                createdAt = o.optLong("at"),
                status = o.optString("st").ifBlank { Chat.STATUS_SENT },
                mine = o.optBoolean("mine"),
                readers = readers,
                incomingRead = o.optBoolean("seen"),
            )
        }

        fun listToJson(list: List<ChatMessage>): JSONArray = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }

        fun listFromJson(a: JSONArray?): List<ChatMessage> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { fromJson(a.optJSONObject(it) ?: return@mapNotNull null) }
        }
    }
}
