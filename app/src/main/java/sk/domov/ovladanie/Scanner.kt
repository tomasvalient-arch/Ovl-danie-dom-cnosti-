package sk.domov.ovladanie

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.domov.ovladanie.tuya.TuyaDiscovery
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException


/**
 * Hľadanie Tuya zariadení v sieti. Zariadenia asi každých 5 sekúnd vysielajú UDP správu na porty
 * 6666 (3.1) a 6667 (3.3–3.5) a niektoré 3.5 sa ozvú až na požiadavku na port 7000 (ako to robí Tuya appka).
 */
object Scanner {
    private const val RECEIVE_TIMEOUT_MS = 500
    private const val REQUEST_EVERY_MS = 5000L

    /**
     * Počúva [durationMs] milisekúnd a každé nájdené zariadenie pošle do [onFound] (aj opakovane,
     * zariadenie vysiela stále dookola). Blokuje, preto volaj z korutiny.
     */
    suspend fun scan(context: Context, network: Network?, durationMs: Long, onFound: (FoundDevice) -> Unit) =
        withContext(Dispatchers.IO) {
            // Bez MulticastLock niektoré telefóny broadcasty z WiFi zahadzujú (úspora batérie).
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            val lock = wifi?.createMulticastLock("domov-hladanie")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            val sockets = listOf(TuyaDiscovery.PORT_PLAIN, TuyaDiscovery.PORT_ENCRYPTED, TuyaDiscovery.PORT_APP)
                .mapNotNull { port -> openListener(port) }
            if (sockets.isEmpty()) {
                lock?.release()
                throw java.io.IOException("Nepodarilo sa otvoriť UDP porty 6666/6667")
            }
            val deadline = System.currentTimeMillis() + durationMs
            try {
                coroutineScope {
                    sockets.forEach { socket ->
                        launch(Dispatchers.IO) { receiveLoop(socket, deadline, onFound) }
                    }
                    launch(Dispatchers.IO) { requestLoop(context, network, deadline) }
                }
            } finally {
                sockets.forEach { runCatching { it.close() } }
                runCatching { lock?.release() }
            }
        }

    private fun openListener(port: Int): DatagramSocket? = try {
        DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = RECEIVE_TIMEOUT_MS
            bind(InetSocketAddress(port))
        }
    } catch (e: Exception) {
        null // port môže byť obsadený inou appkou; ostatné porty stačia
    }

    private suspend fun receiveLoop(socket: DatagramSocket, deadline: Long, onFound: (FoundDevice) -> Unit) =
        coroutineScope {
            val buf = ByteArray(4096)
            while (isActive && System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    break
                }
                val f = TuyaDiscovery.parse(packet.data, packet.length, packet.address?.hostAddress) ?: continue
                onFound(FoundDevice(f.devId, f.ip, f.version, f.productKey))
            }
        }

    /** Každých 5 s pošle do siete požiadavku „pošlite mi info“ (pre zariadenia 3.5). */
    private suspend fun requestLoop(context: Context, network: Network?, deadline: Long) = coroutineScope {
        val (myIp, targets) = addresses(context, network) ?: return@coroutineScope
        val socket = try {
            DatagramSocket().apply { broadcast = true }
        } catch (e: Exception) {
            return@coroutineScope
        }
        try {
            runCatching { network?.bindSocket(socket) }
            val payload = TuyaDiscovery.discoveryRequest(myIp)
            while (isActive && System.currentTimeMillis() < deadline) {
                targets.forEach { target ->
                    runCatching { socket.send(DatagramPacket(payload, payload.size, target, TuyaDiscovery.PORT_APP)) }
                }
                kotlinx.coroutines.delay(REQUEST_EVERY_MS)
            }
        } finally {
            socket.close()
        }
    }

    /** IP telefónu vo WiFi a broadcast adresy siete (napr. 192.168.1.255 a 255.255.255.255). */
    private fun addresses(context: Context, network: Network?): Pair<String, List<InetAddress>>? {
        val wifi = wifiAddress(context, network ?: return null) ?: return null
        val all = InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))
        return wifi.ip to listOf(wifi.broadcast, all).distinct()
    }
}
