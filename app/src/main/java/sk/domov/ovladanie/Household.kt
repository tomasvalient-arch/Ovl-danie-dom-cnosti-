package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * Viac telefónov v domácnosti – bez servera a bez cloudu. Telefóny na domácej WiFi si posielajú UDP správy
 * „som doma“, podpísané spoločným kľúčom domácnosti (HMAC-SHA256). Pri odchode telefón podľa poslednej známej
 * informácie zistí, či je doma ešte niekto iný, a vtedy nezhasína. Súbor nepoužíva Android (tools/HouseholdTest.kt).
 */

/** Člen domácnosti (iný telefón), ako ho vidí tento telefón. [lastSeen] = čas prijatia poslednej správy (hodiny tohto telefónu). */
data class Member(val id: String, val name: String, val lastSeen: Long, val lastSentAt: Long = 0L) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("seen", lastSeen).put("sent", lastSentAt)

    companion object {
        fun fromJson(o: JSONObject): Member = Member(o.optString("id"), o.optString("name"), o.optLong("seen"), o.optLong("sent"))
    }
}

/** Druh správy: B = som doma (pravidelne), Q = som doma a pýtam sa, kto je ešte doma (pri príchode – ostatní hneď odpovedia). */
enum class MsgType(val code: String) { BEAT("B"), QUERY("Q") }

data class HouseMsg(val household: String, val phoneId: String, val name: String, val sentAt: Long, val type: MsgType)

object HouseholdCrypto {
    const val PORT = 41766
    private const val MAGIC = "DOMOV1"
    /** Max. rozdiel hodín medzi telefónmi (oba majú automatický čas). Staršie správy sa zahodia (ochrana proti zopakovaniu). */
    const val MAX_SKEW_MS = 10 * 60_000L
    const val INVITE_PREFIX = "DOMOV-POZVANKA:"

    fun newKey(random: SecureRandom = SecureRandom()): String {
        val b = ByteArray(32)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    fun newPhoneId(random: SecureRandom = SecureRandom()): String {
        val b = ByteArray(6)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun isValidKey(key: String): Boolean = try {
        Base64.getUrlDecoder().decode(key.trim()).size == 32
    } catch (e: Exception) {
        false
    }

    private fun keyBytes(key: String): ByteArray = Base64.getUrlDecoder().decode(key.trim())

    /** Krátke verejné ID domácnosti (z kľúča), aby sa správy dvoch domácností v jednej sieti nemiešali. Kľúč sa z neho nedá zistiť. */
    fun householdId(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(("domov-household:" + key.trim()).toByteArray()).take(4).joinToString("") { "%02x".format(it) }

    private fun mac(key: String, text: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(keyBytes(key), "HmacSHA256"))
        return m.doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Správa: DOMOV1 <domácnosť> <id telefónu> <čas ms> <typ> <meno base64> <hmac>. */
    fun encode(key: String, phoneId: String, name: String, sentAt: Long, type: MsgType): String {
        val nameB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(name.take(40).toByteArray(Charsets.UTF_8))
        val body = listOf(MAGIC, householdId(key), phoneId, sentAt.toString(), type.code, nameB64).joinToString(" ")
        return body + " " + mac(key, body)
    }

    /**
     * Overí a rozloží správu. Vráti null, ak nie je z tejto domácnosti, podpis nesedí, je príliš stará / z budúcnosti
     * (rozdiel hodín nad [MAX_SKEW_MS]) alebo nie je novšia než posledná prijatá od toho istého telefónu ([lastSentAt]).
     */
    fun decode(key: String, text: String, now: Long, lastSentAt: (String) -> Long = { 0L }): HouseMsg? {
        val p = text.trim().split(' ')
        if (p.size != 7 || p[0] != MAGIC) return null
        if (p[1] != householdId(key)) return null
        val body = p.take(6).joinToString(" ")
        val expected = mac(key, body)
        if (!MessageDigest.isEqual(expected.toByteArray(), p[6].lowercase().toByteArray())) return null
        val sentAt = p[3].toLongOrNull() ?: return null
        if (kotlin.math.abs(now - sentAt) > MAX_SKEW_MS) return null
        if (sentAt <= lastSentAt(p[2])) return null
        val type = MsgType.entries.firstOrNull { it.code == p[4] } ?: return null
        val name = try {
            String(Base64.getUrlDecoder().decode(p[5]), Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
        if (!p[2].matches(Regex("[0-9a-f]{6,32}"))) return null
        return HouseMsg(p[1], p[2], name, sentAt, type)
    }

    fun invite(key: String): String = INVITE_PREFIX + key.trim()

    /** Kľúč z pozvánky (aj keď je vložená s okolitým textom, napr. zo správy). */
    fun parseInvite(text: String): String? {
        val i = text.indexOf(INVITE_PREFIX)
        if (i < 0) return null
        val key = text.substring(i + INVITE_PREFIX.length).trim().takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        return key.takeIf { isValidKey(it) }
    }

    /** Kľúč domácnosti z exportu zariadení (prvok poľa s „domov_household“), alebo z pozvánky. */
    fun parseExport(text: String): String? {
        parseInvite(text)?.let { return it }
        return try {
            val arr = JSONArray(text.trim())
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("domov_household")?.takeIf { k -> isValidKey(k) } }.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /** Prvok, ktorý sa pridá do exportu zariadení (nemá „id“, takže ho import zariadení aj tinytuya preskočí). */
    fun exportEntry(key: String): JSONObject =
        JSONObject().put("name", "Domácnosť (Domov – kľúč pre ďalšie telefóny)").put("domov_household", key.trim())
}

/** Rozhodovanie pri odchode a príchode podľa ostatných členov domácnosti. */
object HouseDecision {
    /** Prijme správu: aktualizuje (alebo pridá) člena. Vlastné správy (rovnaké ID) ignoruje. */
    fun receive(members: List<Member>, selfId: String, msg: HouseMsg, now: Long): List<Member> {
        if (msg.phoneId == selfId) return members
        val updated = Member(msg.phoneId, msg.name.ifBlank { msg.phoneId }, now, msg.sentAt)
        return members.filter { it.id != msg.phoneId } + updated
    }

    /**
     * Odchod: ktorí iní členovia boli doma v čase, keď sa tento telefón odpojil ([leftAt])? Počíta sa člen, od ktorého
     * prišla správa najviac [windowMs] pred odpojením (telefón v spánku posiela zriedkavejšie). Neprázdny výsledok = nezhasínať.
     */
    fun stayingHome(members: List<Member>, selfId: String, leftAt: Long, windowMs: Long): List<Member> =
        members.filter { it.id != selfId && it.lastSeen >= leftAt - windowMs }

    /**
     * Príchod: je už niekto doma? Počítajú sa len správy prijaté po návrate ([arrivedAt], napr. odpovede na otázku Q),
     * staré informácie spred odchodu nie.
     */
    fun alreadyHome(members: List<Member>, selfId: String, arrivedAt: Long): List<Member> =
        members.filter { it.id != selfId && it.lastSeen >= arrivedAt }

    /** Je člen „doma“ podľa poslednej správy (pre zoznam Kto je doma)? */
    fun isHome(m: Member, now: Long, windowMs: Long): Boolean = now - m.lastSeen <= windowMs

    fun names(list: List<Member>): String = list.sortedBy { it.name }.joinToString(", ") { it.name }

    fun membersToJson(list: List<Member>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

    fun membersFromJson(text: String?): List<Member> = try {
        val a = JSONArray(text ?: "[]")
        (0 until a.length()).map { Member.fromJson(a.getJSONObject(it)) }.filter { it.id.isNotBlank() }
    } catch (e: Exception) {
        emptyList()
    }

    /** „Doma je ešte Priateľka“ / „Doma sú ešte Jano, Priateľka“. */
    fun stayText(list: List<Member>): String = if (list.size == 1) "Doma je ešte ${list[0].name}" else "Doma sú ešte ${names(list)}"
}
