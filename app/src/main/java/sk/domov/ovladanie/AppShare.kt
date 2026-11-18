package sk.domov.ovladanie

import org.json.JSONObject
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Zdieľanie appky cez QR kód (bez cloudu): prvý telefón spustí dočasný server v domácej WiFi a ukáže QR s odkazom
 * `http://IP:PORT/d?t=TOKEN#k=KĽÚČ`.
 *  - Prehliadač druhého telefónu otvorí stránku a stiahne APK. Časť za „#“ (kľúč) prehliadač na server nikdy neposiela.
 *  - Appka Domov na druhom telefóne naskenuje ten istý QR, stiahne `/share?t=TOKEN` a obsah rozšifruje kľúčom z QR
 *    (AES-256-GCM). Bez QR kódu sa local keys z premávky v sieti nedajú prečítať.
 * Token je jednorazový (zariadenia sa vydajú len raz) a platí [ShareOffer.VALID_MS].
 */
data class ShareLink(val host: String, val port: Int, val token: String, val key: String) {
    fun url(): String = "http://${hostPart(host)}:$port/d?t=$token#k=$key"

    companion object {
        private fun hostPart(h: String) = if (h.contains(':')) "[$h]" else h

        /** Odkaz z QR kódu (alebo vložený text). Neplatný → null. */
        fun parse(text: String): ShareLink? {
            val t = text.trim()
            val m = Regex("^https?://(\\[[0-9a-fA-F:]+]|[A-Za-z0-9.\\-]+):(\\d{1,5})/[^?#]*\\?([^#]*)#(.*)$").find(t) ?: return null
            val host = m.groupValues[1].removePrefix("[").removeSuffix("]")
            val port = m.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            fun param(s: String, name: String): String? = s.split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i > 0 && it.substring(0, i) == name) URLDecoder.decode(it.substring(i + 1), "UTF-8") else null
            }.firstOrNull()
            val token = param(m.groupValues[3], "t")?.takeIf { AppShareCrypto.validId(it, 16) } ?: return null
            val key = param(m.groupValues[4], "k")?.takeIf { AppShareCrypto.validId(it, 32) } ?: return null
            return ShareLink(host, port, token, key)
        }
    }
}

/** Jedna ponuka zdieľania: token, kľúč a čas vypršania. */
data class ShareOffer(val token: String, val key: String, val createdAt: Long) {
    val expiresAt: Long get() = createdAt + VALID_MS
    fun valid(now: Long): Boolean = now in createdAt until expiresAt
    fun tokenMatches(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(candidate.toByteArray(), token.toByteArray())

    companion object {
        const val VALID_MS = 10 * 60_000L
        fun create(now: Long): ShareOffer = ShareOffer(AppShareCrypto.randomId(16), AppShareCrypto.randomId(32), now)
    }
}

/** Čo dostane druhý telefón. [devicesJson] = export zariadení (ako Export zariadení, voliteľne s kľúčom domácnosti). */
data class SharePayload(
    val from: String,
    val devicesJson: String,
    val cloud: CloudConfig? = null,
    val heatEmail: String? = null,
    val heatPassword: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("v", 1)
        put("from", from)
        put("devices", devicesJson)
        cloud?.let { put("cloud", JSONObject().put("id", it.accessId).put("secret", it.accessSecret).put("dc", it.dataCenter.name)) }
        if (!heatEmail.isNullOrBlank() && !heatPassword.isNullOrBlank()) put("heat", JSONObject().put("email", heatEmail).put("password", heatPassword))
    }.toString()

    companion object {
        fun fromJson(text: String): SharePayload {
            val o = JSONObject(text)
            require(o.optInt("v") == 1) { "Nepodporovaná verzia zdieľania – aktualizuj appku na oboch telefónoch." }
            val c = o.optJSONObject("cloud")?.let { c ->
                val dc = runCatching { DataCenter.valueOf(c.optString("dc")) }.getOrDefault(DataCenter.EU)
                CloudConfig(c.optString("id"), c.optString("secret"), dc).takeIf { it.isComplete }
            }
            val h = o.optJSONObject("heat")
            return SharePayload(
                from = o.optString("from"),
                devicesJson = o.optString("devices", "[]"),
                cloud = c,
                heatEmail = h?.optString("email")?.takeIf { it.isNotBlank() },
                heatPassword = h?.optString("password")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

object AppShareCrypto {
    private val rnd = SecureRandom()
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    fun randomId(bytes: Int): String = ByteArray(bytes).also { rnd.nextBytes(it) }.let { enc.encodeToString(it) }

    /** Platný base64url reťazec s presne [bytes] bajtmi. */
    fun validId(s: String, bytes: Int): Boolean = Regex("^[A-Za-z0-9_-]+$").matches(s) && runCatching { dec.decode(s).size == bytes }.getOrDefault(false)

    /** AES-256-GCM, token ako doplnkové dáta (obsah sa nedá podstrčiť k inému tokenu). Výsledok: base64url(iv ‖ šifra). */
    fun encrypt(plain: String, key: String, token: String): String {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dec.decode(key), "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(token.toByteArray())
        return enc.encodeToString(iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** Rozšifruje; zlý kľúč alebo poškodené dáta → výnimka. */
    fun decrypt(data: String, key: String, token: String): String {
        val raw = dec.decode(data.trim())
        require(raw.size > 12 + 16) { "Poškodené dáta" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(dec.decode(key), "AES"), GCMParameterSpec(128, raw, 0, 12))
        c.updateAAD(token.toByteArray())
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }
}
