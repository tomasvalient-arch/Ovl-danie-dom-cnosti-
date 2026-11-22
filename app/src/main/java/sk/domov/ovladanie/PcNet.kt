package sk.domov.ovladanie

import android.content.Context
import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sk.domov.ovladanie.pc.PcClient
import sk.domov.ovladanie.pc.PcCrypto
import sk.domov.ovladanie.pc.PcDiscovery
import sk.domov.ovladanie.pc.PcException
import sk.domov.ovladanie.pc.PcProto
import sk.domov.ovladanie.pc.PcWol
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Sieťové pomôcky pre ovládanie PC: všetko ide len cez WiFi telefónu a len na súkromné (domáce) adresy. */
object PcNet {
    const val NO_WIFI = "Nie si pripojený k WiFi. Ovládanie PC funguje len doma, na rovnakej WiFi ako PC."

    /** Súkromná IPv4 adresa (10.x, 172.16–31.x, 192.168.x, 169.254.x): len také sa dajú pripojiť. */
    fun isPrivateIpv4(host: String): Boolean {
        val parts = host.trim().split(".")
        if (parts.size != 4) return false
        val n = IntArray(4)
        for (i in 0 until 4) {
            val v = parts[i].toIntOrNull() ?: return false
            if (v !in 0..255) return false
            n[i] = v
        }
        return n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168) || (n[0] == 169 && n[1] == 254)
    }

    /** Rozdelí „192.168.1.5“ alebo „192.168.1.5:47810“ na adresu a port (port 0 = neuvedený); null pri nezmysle. */
    fun parseHostPort(text: String): Pair<String, Int>? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val i = t.lastIndexOf(':')
        val host = if (i >= 0) t.substring(0, i).trim() else t
        val port = if (i >= 0) (t.substring(i + 1).trim().toIntOrNull() ?: return null) else 0
        if (port != 0 && port !in 1..65535) return null
        return if (isPrivateIpv4(host)) host to port else null
    }

    fun binder(network: Network): PcDiscovery.Binder = PcDiscovery.Binder { s -> network.bindSocket(s) }

    /** Adresy na hľadanie PC: broadcast tejto siete, všeobecný broadcast a priamo známe adresy PC. */
    fun targets(context: Context, network: Network, known: List<String> = emptyList()): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            wifiAddress(context, network)?.let { out.add(it.broadcast) }
        } catch (e: Exception) {
            // bez broadcastu siete zostanú ostatné adresy
        }
        try {
            out.add(InetAddress.getByName("255.255.255.255"))
        } catch (e: Exception) {
            // nemalo by sa stať
        }
        for (h in known) {
            if (!isPrivateIpv4(h)) continue
            try {
                out.add(InetAddress.getByName(h))
            } catch (e: Exception) {
                // neplatná adresa sa preskočí
            }
        }
        return out
    }

    /** Nájde PC programy v sieti (blokuje [durationMs]); chyba siete = prázdny zoznam. */
    suspend fun discover(context: Context, network: Network, known: List<String>, durationMs: Int): List<PcDiscovery.Found> =
        withContext(Dispatchers.IO) {
            try {
                PcDiscovery.discover(targets(context, network, known), PcProto.DEFAULT_PORT, durationMs, binder(network)).toList()
            } catch (e: Exception) {
                emptyList()
            }
        }

    /** Pošle Wake-on-LAN na všetky známe MAC adresy PC. Vráti počet odoslaných paketov. */
    suspend fun wake(context: Context, network: Network, macs: List<String>, known: List<String>): Int =
        withContext(Dispatchers.IO) { wakeBlocking(context, network, macs, known) }

    fun wakeBlocking(context: Context, network: Network, macs: List<String>, known: List<String>): Int {
        var sent = 0
        val targets = targets(context, network, known)
        for (m in macs) {
            val mac = PcWol.parseMac(m) ?: continue
            sent += try {
                PcWol.send(mac, targets, binder(network))
            } catch (e: Exception) {
                0
            }
        }
        return sent
    }

    // ------------------------------------------------------------------ párovanie

    sealed class PairResult {
        class Ok(val entry: PcEntry) : PairResult()
        class Fail(val message: String) : PairResult()
    }

    private class PairListener : PcClient.Listener() {
        @Volatile var key: ByteArray? = null
        @Volatile var info: PcClient.Info? = null
        val done = CountDownLatch(2)

        override fun onPaired(deviceKey: ByteArray) {
            if (key == null) {
                key = deviceKey
                done.countDown()
            }
        }

        override fun onInfo(info: PcClient.Info) {
            if (this.info == null) {
                this.info = info
                done.countDown()
            }
        }
    }

    /**
     * Spáruje telefón s PC. [hosts] sú adresy na vyskúšanie (z QR kódu alebo zadané ručne), [expectedServerId]
     * (z QR kódu) chráni pred spárovaním s iným PC na tej istej adrese. Kód [code] stačí; ID PC sa zistí pri pripojení.
     */
    suspend fun pair(context: Context, hostsIn: List<String>, port: Int, code: String, expectedServerId: String?): PairResult =
        withContext(Dispatchers.IO) {
            val network = wifiNetwork(context) ?: return@withContext PairResult.Fail(NO_WIFI)
            val clean = PcCrypto.normalizeCode(code)
            if (clean.length != 10) {
                return@withContext PairResult.Fail("Kód má 10 znakov (napr. ABCDE-FGHJK). Skontroluj, či si ho prepísal správne.")
            }
            val hosts = hostsIn.map { it.trim() }.filter { PcNet.isPrivateIpv4(it) }.distinct()
            val expected: ByteArray? = if (expectedServerId != null && expectedServerId.length == 32) PcCrypto.unhex(expectedServerId) else null
            val deviceId = PcStore.deviceId(context)
            val listener = PairListener()
            var client: PcClient? = null
            var usedHost = ""
            var usedPort = port
            var lastNetworkError: String? = null

            fun attempt(host: String, p: Int): PairResult.Fail? {
                try {
                    client = PcClient.connectWithCode(host, p, deviceId, clean, expected, PcStore.phoneName(), network.socketFactory, 3000, listener)
                    usedHost = host
                    usedPort = p
                    return null
                } catch (e: PcException) {
                    // PC odpovedal a rozhodol (zlý kód, párovanie vypnuté...): iná adresa nepomôže
                    if (e.code != 0 && e.code != PcProto.ERR_WRONG_PC) return PairResult.Fail(e.message ?: "Párovanie sa nepodarilo.")
                    lastNetworkError = e.message
                    return null
                } catch (e: Exception) {
                    lastNetworkError = e.message
                    return null
                }
            }

            for (h in hosts) {
                val r = attempt(h, port)
                if (r != null) return@withContext r
                if (client != null) break
            }
            if (client == null && expectedServerId != null) {
                // adresa z QR kódu už nemusí platiť (DHCP): nájdi PC podľa ID
                val found = discover(context, network, hosts, 2500).firstOrNull { it.serverId == expectedServerId }
                if (found != null) {
                    val r = attempt(found.host, found.port)
                    if (r != null) return@withContext r
                }
            }
            val c = client
                ?: return@withContext PairResult.Fail(
                    "PC sa nenašiel na zadanej adrese. Skontroluj, či je na PC spustený program Domov PC, či je telefón na rovnakej WiFi " +
                        "a či Windows nezobrazil okno firewallu (povoľ ho pre súkromné siete)." +
                        (lastNetworkError?.let { "\n($it)" } ?: ""),
                )
            val ok = listener.done.await(8, TimeUnit.SECONDS)
            val info = listener.info
            val key = listener.key
            c.close()
            if (!ok || info == null || key == null) {
                return@withContext PairResult.Fail("PC neposlal údaje o spárovaní. Skús to znova.")
            }
            val serverId = PcCrypto.hex(info.serverId)
            val old = PcStore.get(context, serverId)
            val entry = (old ?: PcEntry(serverId, info.name, key, emptyList(), usedPort, emptyList())).copy(
                name = info.name.ifBlank { usedHost },
                key = key,
                hosts = (listOf(usedHost) + hosts).distinct().take(4),
                port = usedPort,
                macs = info.macs.map { PcWol.formatMac(it) },
            )
            PcStore.save(context, entry)
            PairResult.Ok(entry)
        }
}
