package sk.domov.ovladanie

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * IPv4 adresa telefónu vo WiFi sieti – jediné miesto, kde sa zisťuje (hľadanie zariadení, rozpoznanie domácej
 * siete, zdieľanie appky). [prefixLength] je dĺžka masky siete (napr. 24 pre 255.255.255.0).
 */
data class WifiAddress(val network: Network, val address: Inet4Address, val prefixLength: Int) {
    /** Adresa ako text, napr. „192.168.1.37“. */
    val ip: String get() = address.hostAddress.orEmpty()

    /** Rozsah siete, napr. „192.168.1.0/24“ (prázdne pri neplatnom prefixe). */
    val subnet: String get() = HomeNetwork.subnetOf(ip, prefixLength)

    /** Broadcast adresa siete, napr. 192.168.1.255 (pri neplatnom prefixe 255.255.255.255). */
    val broadcast: InetAddress get() = InetAddress.getByName(HomeNetwork.broadcastOf(subnet))
}

/**
 * IPv4 adresa telefónu v danej sieti [network], alebo null, kým ju sieť nemá (napr. DHCP ešte nedobehol).
 * Prednosť má bežná adresa pred link-local (169.254.x.x); loopback sa nikdy nevráti.
 */
fun wifiAddress(context: Context, network: Network): WifiAddress? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val lp = cm.getLinkProperties(network) ?: return null
    return wifiAddress(network, lp)
}

/** To isté pre už načítané [LinkProperties] (aby ich volajúci nemusel čítať dvakrát). */
fun wifiAddress(network: Network, lp: LinkProperties): WifiAddress? {
    val v4 = lp.linkAddresses.mapNotNull { la ->
        val a = la.address as? Inet4Address ?: return@mapNotNull null
        if (a.isLoopbackAddress || a.isAnyLocalAddress) null else a to la.prefixLength
    }
    // Záloha: niektoré ROM-ky nedajú IPv4 do LinkProperties hneď – adresu vezmeme priamo z rozhrania (wlan0).
    val candidates = v4.ifEmpty { interfaceAddresses(lp.interfaceName) }
    val (addr, prefix) = candidates.firstOrNull { !it.first.isLinkLocalAddress } ?: candidates.firstOrNull() ?: return null
    return WifiAddress(network, addr, prefix)
}

/**
 * WiFi siete telefónu – jediné miesto, kde sa hľadá WiFi sieť. Aktívna sieť (ak je to WiFi) je prvá, za ňou
 * ostatné WiFi siete v poradí systému. Prázdne, ak telefón nie je na WiFi.
 */
@Suppress("DEPRECATION")
fun wifiNetworks(context: Context): List<Network> {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    fun isWifi(n: Network): Boolean =
        cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    val active = cm.activeNetwork
    return (listOfNotNull(active) + cm.allNetworks.filter { it != active }).filter { isWifi(it) }
}

/** WiFi sieť telefónu (prednostne aktívna), alebo null ak telefón nie je na WiFi. Sockety k zariadeniam sa viažu na ňu. */
fun wifiNetwork(context: Context): Network? = wifiNetworks(context).firstOrNull()

/**
 * IPv4 adresa telefónu vo WiFi (bez ohľadu na to, ktorá sieť je práve hlavná), alebo null, ak telefón nie je na WiFi.
 * Prvá WiFi sieť z [wifiNetworks], ktorá už má adresu.
 */
fun wifiAddress(context: Context): WifiAddress? =
    wifiNetworks(context).firstNotNullOfOrNull { wifiAddress(context, it) }

private fun interfaceAddresses(name: String?): List<Pair<Inet4Address, Int>> {
    if (name.isNullOrBlank()) return emptyList()
    return try {
        val nif = NetworkInterface.getByName(name) ?: return emptyList()
        if (!nif.isUp || nif.isLoopback) return emptyList()
        nif.interfaceAddresses.mapNotNull { ia ->
            val a = ia.address as? Inet4Address ?: return@mapNotNull null
            if (a.isLoopbackAddress || a.isAnyLocalAddress) null else a to ia.networkPrefixLength.toInt()
        }
    } catch (e: Exception) {
        emptyList()
    }
}
