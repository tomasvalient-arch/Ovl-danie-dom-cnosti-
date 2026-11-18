package sk.domov.ovladanie

/** Zariadenie nájdené v sieti (z UDP správy, ktorú zariadenie samo vysiela). */
data class FoundDevice(
    val devId: String,
    val ip: String,
    /** Verzia protokolu, ktorú zariadenie hlási (napr. "3.3"). Môže byť prázdna. */
    val version: String,
    val productKey: String,
)

/** Verzie protokolu, ktoré appka vie ovládať. */
val SUPPORTED_VERSIONS = listOf("3.3", "3.4", "3.5")
