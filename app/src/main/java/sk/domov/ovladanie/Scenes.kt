package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject

/**
 * Skupiny na hlavnej obrazovke a rýchle scény (Všetko vypnúť, Noc).
 * Čistý Kotlin bez Androidu, aby sa dal otestovať aj mimo telefónu.
 */
object Groups {
    const val LIGHTS = "Svetlá"
    const val SOCKETS = "Zásuvky"
    const val APPLIANCES = "Spotrebiče"
    const val OTHER = "Ostatné"

    /** Automatické skupiny v poradí, v akom sa zobrazujú. */
    val AUTO = listOf(LIGHTS, SOCKETS, APPLIANCES, OTHER)

    fun autoGroup(kind: AutoKind): String = when (kind) {
        AutoKind.LIGHT -> LIGHTS
        AutoKind.SOCKET -> SOCKETS
        AutoKind.FAN, AutoKind.FRYER, AutoKind.HEATER, AutoKind.THERMOSTAT -> APPLIANCES
        AutoKind.COVER, AutoKind.OTHER, AutoKind.UNKNOWN -> OTHER
    }

    /** Skupina zariadenia: vlastná (napr. miestnosť), inak automatická podľa druhu. */
    fun groupOf(d: Device, values: Map<String, Any>): String =
        d.group.trim().ifBlank { autoGroup(AutoDevices.kind(d, values)) }

    /**
     * Rozdelí zariadenia do skupín. Vlastné skupiny (miestnosti) idú prvé v poradí, v akom sa v zozname objavia,
     * potom automatické Svetlá, Zásuvky, Spotrebiče, Ostatné. V skupine platí poradie zo zoznamu zariadení.
     * Prázdne skupiny sa vynechajú.
     */
    fun arrange(devices: List<Device>, values: (Device) -> Map<String, Any>): List<Pair<String, List<Device>>> {
        val byGroup = LinkedHashMap<String, MutableList<Device>>()
        devices.forEach { d -> byGroup.getOrPut(groupOf(d, values(d))) { mutableListOf() } += d }
        val custom = byGroup.keys.filter { it !in AUTO }
        return (custom + AUTO.filter { it in byGroup }).map { it to byGroup.getValue(it).toList() }
    }

    /** Názvy vlastných skupín (na výber pri presune). */
    fun customGroups(devices: List<Device>): List<String> =
        devices.map { it.group.trim() }.filter { it.isNotEmpty() && it !in AUTO }.distinct()

    /**
     * Posunie zariadenie v rámci jeho skupiny o [delta] (-1 = dopredu, +1 = dozadu): vymení sa so susedom
     * z tej istej skupiny. Ostatné zariadenia ostanú na mieste. Ak sa nedá posunúť, vráti pôvodný zoznam.
     */
    fun move(devices: List<Device>, id: String, delta: Int, values: (Device) -> Map<String, Any>): List<Device> {
        val i = devices.indexOfFirst { it.id == id }
        if (i < 0 || delta == 0) return devices
        val group = groupOf(devices[i], values(devices[i]))
        val same = devices.indices.filter { groupOf(devices[it], values(devices[it])) == group }
        val pos = same.indexOf(i)
        val target = pos + if (delta < 0) -1 else 1
        if (target !in same.indices) return devices
        val j = same[target]
        val list = devices.toMutableList()
        list[i] = devices[j]
        list[j] = devices[i]
        return list
    }

    /** Presunie zariadenie do skupiny ("" = späť do automatickej) a zaradí ho na koniec. */
    fun setGroup(devices: List<Device>, id: String, group: String): List<Device> {
        val d = devices.find { it.id == id } ?: return devices
        val g = group.trim().let { if (it in AUTO) "" else it }
        return devices.filterNot { it.id == id } + d.copy(group = g)
    }

    /** „2 z 3 zapnuté“ */
    fun onText(on: Int, total: Int): String = if (total == 0) "" else "$on z $total zapnuté"
}

/**
 * Scéna Noc: vybrané svetlá ostanú stlmené na [dimPercent] % teplej bielej, ostatné vybrané zariadenia sa vypnú.
 * [offIds] null = predvolene všetky svetlá a zásuvky okrem stlmených.
 */
data class NightScene(
    val offIds: Set<String>? = null,
    val dimIds: Set<String> = emptySet(),
    val dimPercent: Int = 10,
) {
    fun toJson(): String = JSONObject()
        .put("dim", JSONArray(dimIds.toList()))
        .put("percent", dimPercent)
        .apply { offIds?.let { put("off", JSONArray(it.toList())) } }
        .toString()

    companion object {
        fun fromJson(text: String?): NightScene {
            if (text.isNullOrBlank()) return NightScene()
            return runCatching {
                val o = JSONObject(text)
                fun set(name: String): Set<String>? = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                NightScene(
                    offIds = set("off"),
                    dimIds = set("dim") ?: emptySet(),
                    dimPercent = o.optInt("percent", 10).coerceIn(1, 100),
                )
            }.getOrDefault(NightScene())
        }
    }
}

object Scenes {
    /** Všetko vypnúť: rovnaký výber ako pri odchode (predvolene svetlá a zásuvky, spotrebiče len ak sú vybrané). */
    fun allOffIds(cfg: PresenceConfig, devices: List<Device>, values: (Device) -> Map<String, Any>): Set<String> {
        val ids = AutoDevices.departIds(cfg, devices, values)
        return devices.filter { it.id in ids && AutoDevices.canDepart(AutoDevices.kind(it, values(it))) }.map { it.id }.toSet()
    }

    /**
     * Lokálny príkaz na vypnutie: hlavný vypínač, pri viacnásobnej zásuvke všetky vypínače, pri svetle s dvoma zdrojmi
     * aj vypínače sekcií. Null = zariadenie nemá čo vypnúť (alebo sa nevypína, napr. rolety).
     */
    fun offCommand(d: Device, values: Map<String, Any>): Map<String, Any>? {
        val kind = AutoDevices.kind(d, values)
        if (!AutoDevices.canDepart(kind)) return null
        // fritéza: hlavný vypínač sa hromadne nikdy neposiela (BH-AF2 zápis berie ako tlačidlo a spustí program) – pozri SafeOff
        if (SafeOff.looksLikeFryer(d, values)) return null
        val cmd = LinkedHashMap<String, Any>()
        // svetlo s viacerými zdrojmi: vypnúť všetky (hlavné aj podhľad)
        LightChannels.detect(d, values)?.let { ml -> return LightChannels.allOff(ml, values).ifEmpty { null } }
        // len istý vypínač; odhad „prvá zapnutá/vypnutá hodnota“ len pri svetlách a zásuvkách
        (SafeOff.certainPower(d, values) ?: powerDpOf(d, values)?.takeIf { kind == AutoKind.LIGHT || kind == AutoKind.SOCKET })
            ?.let { cmd[it] = false }
        val t = if (Light.detect(values) == null) Templates.detect(values, d.schema, d.category) else null
        if (t is SocketTemplate) t.switches.filter { values[it] is Boolean }.forEach { cmd[it] = false }
        listOf(d.whiteSwitchDp.trim(), d.colourSwitchDp.trim()).filter { values[it] is Boolean }.forEach { cmd[it] = false }
        return cmd.ifEmpty { null }
    }

    /** Dá sa svetlo stlmiť na teplú bielu (jednoduché svetlo alebo svetlo s dvoma zdrojmi)? */
    fun canDim(d: Device, values: Map<String, Any>): Boolean =
        LightChannels.detect(d, values) != null || Light.detect(values) != null || Light.detectDual(values, d.whiteSwitchDp, d.colourSwitchDp) != null

    /** Príkaz: zapnúť, teplá biela a jas [percent] %. Null = nie je to svetlo, ktoré vieme stlmiť. */
    fun dimCommand(d: Device, values: Map<String, Any>, percent: Int): Map<String, Any>? {
        LightChannels.detect(d, values)?.let { ml -> return LightChannels.dim(ml, values, percent) }
        Light.detect(values, d.schema)?.let { spec ->
            return LinkedHashMap<String, Any>().apply {
                put(spec.power, true)
                putAll(Light.whiteBrightness(spec, percent))
                putAll(Light.whiteTemp(spec, 0f))
            }
        }
        Light.detectDual(values, d.whiteSwitchDp, d.colourSwitchDp)?.let { spec ->
            return LinkedHashMap<String, Any>().apply {
                powerDpOf(d, values)?.let { put(it, true) }
                spec.whiteSwitch?.let { put(it, true) }
                spec.colourSwitch?.let { put(it, false) }
                putAll(Light.dualWhiteBrightness(spec, percent))
                putAll(Light.dualTemp(spec, 0f))
            }
        }
        return null
    }

    /** Zariadenia, ktoré scéna Noc vypne. */
    fun nightOffIds(scene: NightScene, devices: List<Device>, values: (Device) -> Map<String, Any>): Set<String> {
        val base = scene.offIds ?: devices.filter { AutoDevices.defaultDepart(AutoDevices.kind(it, values(it))) }.map { it.id }.toSet()
        return devices.filter { it.id in base && it.id !in scene.dimIds }.map { it.id }.toSet()
    }

    /** Plán scény Noc: (zariadenie, príkaz). Stlmené svetlá prvé, potom vypnutie. Hodnoty z pamäte (rizikové sa vynechajú). */
    fun nightPlan(scene: NightScene, devices: List<Device>, values: (Device) -> Map<String, Any>): List<Pair<Device, Map<String, Any>>> =
        sends(nightSteps(scene, devices, values) { false })

    /** Plán Všetko vypnúť (hodnoty z pamäte, rizikové sa vynechajú). */
    fun allOffPlan(cfg: PresenceConfig, devices: List<Device>, values: (Device) -> Map<String, Any>): List<Pair<Device, Map<String, Any>>> =
        sends(allOffSteps(cfg, devices, values) { false })

    /**
     * Kroky scény Noc. [fresh] = hodnoty zariadenia sú práve prečítané. Stlmenie len pri svetlách (nikdy pri spotrebiči).
     */
    fun nightSteps(scene: NightScene, devices: List<Device>, values: (Device) -> Map<String, Any>, fresh: (Device) -> Boolean): List<OffStep> {
        val dim = devices.filter { it.id in scene.dimIds && !SafeOff.risky(it, values(it)) }
            .mapNotNull { d -> dimCommand(d, values(d), scene.dimPercent)?.let { OffStep.Send(d, it) } }
        val offIds = nightOffIds(scene, devices, values)
        val off = devices.filter { it.id in offIds }.map { d -> SafeOff.offStep(d, values(d), fresh(d)) }
        return dim + off
    }

    /** Kroky Všetko vypnúť. */
    fun allOffSteps(cfg: PresenceConfig, devices: List<Device>, values: (Device) -> Map<String, Any>, fresh: (Device) -> Boolean): List<OffStep> {
        val ids = allOffIds(cfg, devices, values)
        return devices.filter { it.id in ids }.map { d -> SafeOff.offStep(d, values(d), fresh(d)) }
    }

    fun sends(steps: List<OffStep>): List<Pair<Device, Map<String, Any>>> =
        steps.filterIsInstance<OffStep.Send>().map { it.device to it.cmd }

    /** Rovnaká hodnota (čísla bez ohľadu na Int/Long, text bez ohľadu na veľkosť písmen). */
    fun sameValue(a: Any?, b: Any?): Boolean = when {
        a == null || b == null -> false
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is String && b is String -> a.equals(b, ignoreCase = true)
        else -> a == b
    }

    /** „vypne 4 zariadenia“ – slovenské tvary. */
    fun devicesText(n: Int): String = when (n) {
        1 -> "1 zariadenie"
        2, 3, 4 -> "$n zariadenia"
        else -> "$n zariadení"
    }
}
