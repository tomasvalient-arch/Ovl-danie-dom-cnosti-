package sk.domov.ovladanie

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/** Údaje domácnosti v tomto telefóne: náhodné ID, meno telefónu, kľúč domácnosti a ostatní členovia. */
object HouseholdStore {
    private fun prefs(context: Context) = context.getSharedPreferences("domov_household", Context.MODE_PRIVATE)

    /** Rovnaké úložisko ako nastavenia automatiky – aby obrazovka videla zmeny hneď. */
    fun listenPrefs(context: Context) = prefs(context)

    @Synchronized
    fun phoneId(context: Context): String = prefs(context).getString("id", null)
        ?: HouseholdCrypto.newPhoneId().also { prefs(context).edit().putString("id", it).apply() }

    fun name(context: Context): String = prefs(context).getString("name", null)?.takeIf { it.isNotBlank() }
        ?: Build.MODEL.orEmpty().ifBlank { "Telefón" }

    /** Meno nastavené používateľom (null = zatiaľ len názov telefónu). */
    fun customName(context: Context): String? = prefs(context).getString("name", null)?.takeIf { it.isNotBlank() }

    fun setName(context: Context, name: String) {
        prefs(context).edit().putString("name", name.trim().take(40)).putBoolean("name_asked", true).apply()
    }

    /** Pýtala sa už appka na meno pri prvom spustení (uložené alebo „Neskôr“)? */
    fun nameAsked(context: Context): Boolean = prefs(context).getBoolean("name_asked", false)

    fun setNameAsked(context: Context) {
        prefs(context).edit().putBoolean("name_asked", true).apply()
    }

    /** Kľúč domácnosti; prvý telefón si ho vytvorí sám. */
    @Synchronized
    fun key(context: Context): String = prefs(context).getString("key", null)?.takeIf { HouseholdCrypto.isValidKey(it) }
        ?: HouseholdCrypto.newKey().also { prefs(context).edit().putString("key", it).apply() }

    /** Prevezme kľúč z iného telefónu (pozvánka / import). Vráti true, ak sa zmenil. Starý zoznam členov sa zmaže. */
    @Synchronized
    fun adopt(context: Context, key: String): Boolean {
        val k = key.trim()
        if (!HouseholdCrypto.isValidKey(k) || k == key(context)) return false
        prefs(context).edit().putString("key", k).remove("members").putLong("shared_at", System.currentTimeMillis()).apply()
        PresenceStore.log(context, "Telefón sa pridal do domácnosti (nový kľúč domácnosti).")
        PresenceService.sync(context)
        return true
    }

    /**
     * Kľúč domácnosti na zdieľanie (pozvánka, export). Zapamätá si čas, aby služba ďalších [SHARE_WINDOW_MS]
     * počúvala, či sa ozve nový člen, aj keď v zozname ešte nikto nie je.
     */
    fun shareKey(context: Context): String {
        val k = key(context)
        val wasActive = active(context)
        prefs(context).edit().putLong("shared_at", System.currentTimeMillis()).apply()
        // služba (ak beží) začne hneď počúvať, či sa nový člen ozve
        if (!wasActive) PresenceService.sync(context)
        return k
    }

    private const val SHARE_WINDOW_MS = 30L * 24 * 60 * 60_000L

    /**
     * Má zmysel posielať a počúvať správy „som doma“? Áno, ak sú v domácnosti aj iné telefóny, alebo bol kľúč
     * nedávno zdieľaný / prevzatý. Telefón bez domácnosti tak nedrží MulticastLock ani neposiela UDP.
     */
    fun active(context: Context): Boolean {
        val self = phoneId(context)
        if (members(context).any { it.id != self }) return true
        val shared = prefs(context).getLong("shared_at", 0L)
        return shared > 0 && System.currentTimeMillis() - shared < SHARE_WINDOW_MS
    }

    fun members(context: Context): List<Member> = HouseDecision.membersFromJson(prefs(context).getString("members", null))

    @Synchronized
    fun update(context: Context, change: (List<Member>) -> List<Member>) {
        prefs(context).edit().putString("members", HouseDecision.membersToJson(change(members(context)))).apply()
    }

    fun remove(context: Context, id: String) = update(context) { list -> list.filter { it.id != id } }
}

/** Posielanie a príjem UDP správ „som doma“ v domácej WiFi. */
object HouseholdNet {
    /** Pošle správu na broadcast domácej siete (aj 255.255.255.255). Socket je viazaný na WiFi sieť. */
    suspend fun send(context: Context, network: Network, type: MsgType, broadcast: String) = withContext(Dispatchers.IO) {
        val text = HouseholdCrypto.encode(HouseholdStore.key(context), HouseholdStore.phoneId(context), HouseholdStore.name(context),
            System.currentTimeMillis(), type)
        val data = text.toByteArray(Charsets.UTF_8)
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                runCatching { network.bindSocket(s) }
                for (addr in listOf(broadcast, "255.255.255.255").distinct()) {
                    runCatching { s.send(DatagramPacket(data, data.size, InetAddress.getByName(addr), HouseholdCrypto.PORT)) }
                }
            }
        } catch (e: Exception) {
            // sieť práve zmizla – nevadí, ďalšia správa pôjde o chvíľu
        }
    }

    /**
     * Počúva správy, kým beží korutina. Platnú správu od iného člena zapíše do zoznamu a zavolá [onMessage]
     * (napr. aby služba odpovedala na otázku „kto je doma“).
     */
    suspend fun listen(context: Context, network: Network, onMessage: suspend (HouseMsg) -> Unit) = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        // bez MulticastLock niektoré telefóny broadcasty zahadzujú
        val lock = wifi?.createMulticastLock("domov-domacnost")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 2000
                runCatching { network.bindSocket(this) }
                bind(InetSocketAddress(HouseholdCrypto.PORT))
            }
        } catch (e: Exception) {
            runCatching { lock?.release() }
            return@withContext
        }
        val selfId = HouseholdStore.phoneId(context)
        val buf = ByteArray(1024)
        try {
            while (coroutineContext.isActive) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val now = System.currentTimeMillis()
                val members = HouseholdStore.members(context)
                val msg = HouseholdCrypto.decode(HouseholdStore.key(context), text, now) { id -> members.firstOrNull { it.id == id }?.lastSentAt ?: 0L }
                    ?: continue
                if (msg.phoneId == selfId) continue
                HouseholdStore.update(context) { HouseDecision.receive(it, selfId, msg, now) }
                onMessage(msg)
            }
        } catch (e: Exception) {
            // socket zavretý / sieť zmizla
        } finally {
            runCatching { socket.close() }
            runCatching { lock?.release() }
        }
    }
}
