package sk.domov.ovladanie

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Jedno Tuya zariadenie. Hodnoty (DP) sa z neho načítajú automaticky. */
data class Device(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val ip: String,
    val devId: String,
    val localKey: String,
    val version: String = "3.3",
    /** Vlastné názvy hodnôt: číslo DP -> názov (napr. "20" -> "Svetlo"). */
    val labels: Map<String, String> = emptyMap(),
    /** Čísla DP, ktoré sa nemajú zobrazovať. */
    val hidden: Set<String> = emptySet(),
    /** Číslo hodnoty (DP), ktorá je hlavný vypínač. Prázdne = nájde sa automaticky. */
    val powerDp: String = "",
    /** Svetlo s dvoma zdrojmi: číslo hodnoty vypínača hlavného (bieleho) svetla. Prázdne = žiadny. */
    val whiteSwitchDp: String = "",
    /** Svetlo s dvoma zdrojmi: číslo hodnoty vypínača podhľadového (farebného) svetla. */
    val colourSwitchDp: String = "",
    /** Popis hodnôt z Tuya cloudu (mapping z tinytuya wizardu): číslo DP -> kód, typ, jednotka, rozsah. */
    val schema: Map<String, DpSchema> = emptyMap(),
    /** Kategória z Tuya cloudu (napr. „cz“ zásuvka, „wk“ termostat). Prázdne = neznáma. */
    val category: String = "",
    /** Vlastná skupina na hlavnej obrazovke (napr. miestnosť). Prázdne = automaticky podľa typu (Svetlá, Zásuvky…). */
    val group: String = "",
    /** product_id z Tuya cloudu / devices.json (na technické info). */
    val productId: String = "",
    /** Názov produktu / model z Tuya cloudu (product_name / model). */
    val model: String = "",
    /** Ručné nastavenie kanálov svetla (LightChannels.ROLE_*): napr. model=mix, mix=51, back_switch=33. */
    val lightRoles: Map<String, String> = emptyMap(),
    /** Zigbee / Bluetooth podzariadenie: Device ID brány v appke, cez ktorú sa ovláda. Prázdne = samostatné zariadenie. */
    val gatewayId: String = "",
    /** ID podzariadenia za bránou (node_id z Tuya cloudu). Prázdne = samostatné zariadenie. */
    val cid: String = "",
) {
    /** Bez local key v čitateľnej podobe (logy, chybové hlásenia). */
    override fun toString(): String =
        "Device(id=$id, name=$name, ip=$ip, devId=$devId, localKey=${masked(localKey)}, version=$version, category=$category, group=$group, " +
            "gatewayId=$gatewayId, cid=$cid)"


    /** Podzariadenie za bránou: nemá vlastnú IP ani local key, komunikuje cez bránu (pozri Gateways). */
    val isSub: Boolean get() = gatewayId.isNotBlank() && cid.isNotBlank()
}

internal fun rolesToJson(roles: Map<String, String>): JSONObject = JSONObject().apply { roles.forEach { (k, v) -> put(k, v) } }

internal fun rolesFromJson(o: JSONObject?): Map<String, String> {
    if (o == null) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (k in o.keys()) o.optString(k).trim().takeIf { it.isNotEmpty() }?.let { out[k] = it }
    return out
}

sealed interface DeviceState {
    data object Loading : DeviceState

    /** Hodnoty môžu byť Boolean, číslo alebo text. */
    data class Online(val values: Map<String, Any>) : DeviceState

    data class Error(val message: String) : DeviceState
}

private fun readLabels(o: JSONObject): Map<String, String> {
    val labels = mutableMapOf<String, String>()
    val labelsJson = o.optJSONObject("labels")
    if (labelsJson != null) {
        for (k in labelsJson.keys()) {
            labels[k] = labelsJson.optString(k)
        }
    }
    return labels
}

private fun readSchema(o: JSONObject): Map<String, DpSchema> =
    runCatching { Schema.parseMapping(o.optJSONObject("mapping")) }.getOrDefault(emptyMap())

private fun readHidden(o: JSONObject): Set<String> {
    val hiddenArr = o.optJSONArray("hidden") ?: return emptySet()
    return (0 until hiddenArr.length()).map { hiddenArr.getString(it) }.toSet()
}

/**
 * Ktorá hodnota je hlavný vypínač: ručne nastavená, inak vypínač svetla,
 * inak prvá hodnota typu zapnuté/vypnuté. Vráti null, ak zariadenie žiadnu takú nemá.
 */
fun powerDpOf(device: Device, values: Map<String, Any>): String? {
    // senzory (dvere, pohyb, teplota…) a brány vypínač nemajú; ich „zapnuté/vypnuté“ hodnota nesmie byť vypínač
    if (Sensors.isSensor(device) || Gateways.isGateway(device)) return null
    if (device.powerDp.isNotBlank() && values[device.powerDp] is Boolean) return device.powerDp
    Light.detect(values)?.let { return it.power }
    // fritéza: hlavný vypínač je len hodnota 1; štart/stop (2) nesmie byť hlavný vypínač, inak by widget spúšťal ohrev
    Fryer.detect(values)?.let { return it.power }
    // podľa popisu z Tuya cloudu (kód switch, switch_1…)
    Schema.powerDp(device.schema, values)?.let { return it }
    // rolety nemajú vypínač; ich hodnoty zapnuté/vypnuté (napr. opačný smer motora) nie sú hlavný vypínač
    if (device.schema.isNotEmpty() && Templates.detect(values, device.schema, device.category) is CoverTemplate) return null
    // vypínače sekcií svetla s dvoma zdrojmi nie sú hlavný vypínač
    val sectionSwitches = setOf(device.whiteSwitchDp.trim(), device.colourSwitchDp.trim())
    return values.entries.firstOrNull { it.value is Boolean && it.key !in sectionSwitches }?.key
}

/** "20=Svetlo, 22=Jas" -> {20: Svetlo, 22: Jas} */
fun parseLabels(text: String): Map<String, String> =
    text.split(",").mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) {
            null
        } else {
            val key = part.substring(0, i).trim()
            val value = part.substring(i + 1).trim()
            if (key.isEmpty() || value.isEmpty()) null else key to value
        }
    }.toMap()

fun labelsToText(labels: Map<String, String>): String =
    labels.entries.joinToString(", ") { "${it.key}=${it.value}" }

/** "25, 26" -> {25, 26} */
fun parseHidden(text: String): Set<String> =
    text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/**
 * Export všetkých zariadení do JSON. Formát je rovnaký ako devices.json z tinytuya
 * (name, id, key, ip, ver, category, mapping), plus voliteľné labels, hidden a powerDp. Importuje sa naspäť cez Import.
 */
fun exportDevicesJson(devices: List<Device>, householdKey: String? = null): String {
    val arr = JSONArray()
    devices.forEach { d ->
        val o = JSONObject()
            .put("name", d.name)
            .put("id", d.devId)
            .put("key", d.localKey)
            .put("ip", d.ip)
            .put("ver", d.version)
        if (d.labels.isNotEmpty()) {
            val labels = JSONObject()
            d.labels.forEach { (k, v) -> labels.put(k, v) }
            o.put("labels", labels)
        }
        if (d.hidden.isNotEmpty()) o.put("hidden", JSONArray(d.hidden.toList()))
        if (d.powerDp.isNotBlank()) o.put("powerDp", d.powerDp)
        if (d.whiteSwitchDp.isNotBlank()) o.put("whiteSwitchDp", d.whiteSwitchDp)
        if (d.colourSwitchDp.isNotBlank()) o.put("colourSwitchDp", d.colourSwitchDp)
        if (d.category.isNotBlank()) o.put("category", d.category)
        if (d.group.isNotBlank()) o.put("group", d.group)
        if (d.productId.isNotBlank()) o.put("product_id", d.productId)
        if (d.model.isNotBlank()) o.put("product_name", d.model)
        if (d.lightRoles.isNotEmpty()) o.put("lightRoles", rolesToJson(d.lightRoles))
        if (d.isSub) o.put("sub", true).put("parent", d.gatewayId).put("node_id", d.cid)
        if (d.schema.isNotEmpty()) o.put("mapping", Schema.toJson(d.schema))
        arr.put(o)
    }
    // kľúč domácnosti (automatika pre viac telefónov); prvok nemá „id“, import zariadení ho preskočí
    if (!householdKey.isNullOrBlank()) arr.put(HouseholdCrypto.exportEntry(householdKey))
    return arr.toString(2)
}

/**
 * Import zo súboru devices.json, ktorý vytvorí `python -m tinytuya wizard`, alebo z exportu tejto appky.
 * Očakáva pole objektov s poľami name, id, key a voliteľne ip, ver/version, labels, hidden, powerDp,
 * category a mapping (popis hodnôt z Tuya cloudu, tinytuya ho uloží, keď vo wizarde odpovieš áno na „Download DP Name mappings?“).
 */
fun parseTinytuyaJson(text: String): List<Device> {
    val arr = JSONArray(text.trim())
    val result = mutableListOf<Device>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.optString("id")
        val key = o.optString("key")
        // podzariadenie za bránou: parent (Device ID brány) a node_id (cid); vlastný local key nepotrebuje
        val parent = o.optString("parent", o.optString("gatewayId", o.optString("gateway_id", ""))).trim()
        val cid = o.optString("node_id", o.optString("cid", "")).trim()
        val sub = parent.isNotEmpty() && cid.isNotEmpty() && cid.matches(Regex("[A-Za-z0-9_.:-]+"))
        if (id.isBlank() || (!sub && key.length != 16)) continue
        // prázdna verzia = devices.json ju nemá; pri importe sa potom ponechá pôvodná (alebo 3.3 pri novom zariadení)
        val ver = o.optString("ver", o.optString("version", "")).trim()
        result += Device(
            name = o.optString("name", id).ifBlank { id },
            ip = o.optString("ip", ""),
            devId = id,
            localKey = if (sub) "" else key,
            version = if (ver in listOf("3.3", "3.4", "3.5")) ver else "",
            labels = readLabels(o),
            hidden = readHidden(o),
            powerDp = o.optString("powerDp", ""),
            whiteSwitchDp = o.optString("whiteSwitchDp", ""),
            colourSwitchDp = o.optString("colourSwitchDp", ""),
            schema = readSchema(o),
            category = o.optString("category", ""),
            group = o.optString("group", "").trim(),
            productId = o.optString("product_id", o.optString("productId", "")).trim(),
            model = o.optString("product_name", o.optString("model", "")).trim(),
            lightRoles = rolesFromJson(o.optJSONObject("lightRoles")),
            gatewayId = if (sub) parent else "",
            cid = if (sub) cid else "",
        )
    }
    return result
}

/**
 * Zlúči importované zariadenia so zoznamom v appke. Zariadenie s rovnakým Device ID sa aktualizuje: zostane jeho
 * interné id a názov; local key, verzia, IP, popis hodnôt (mapping) a kategória sa prevezmú z importu, ak ich má.
 * Vlastné názvy hodnôt, skryté hodnoty a vypínače zostanú, ak ich import neobsahuje.
 */
fun mergeImported(current: List<Device>, imported: List<Device>): List<Device> {
    var list = current
    imported.forEach { new ->
        val existing = list.find { it.devId == new.devId }
        list = if (existing == null) {
            list + new.copy(version = new.version.ifBlank { "3.3" })
        } else {
            list.map {
                if (it.devId == new.devId) it.copy(
                    localKey = if (new.isSub) it.localKey else new.localKey,
                    gatewayId = if (new.isSub) new.gatewayId else it.gatewayId,
                    cid = if (new.isSub) new.cid else it.cid,
                    version = new.version.ifBlank { it.version },
                    ip = new.ip.ifBlank { it.ip },
                    labels = if (new.labels.isNotEmpty()) new.labels else it.labels,
                    hidden = if (new.hidden.isNotEmpty()) new.hidden else it.hidden,
                    powerDp = new.powerDp.ifBlank { it.powerDp },
                    whiteSwitchDp = new.whiteSwitchDp.ifBlank { it.whiteSwitchDp },
                    colourSwitchDp = new.colourSwitchDp.ifBlank { it.colourSwitchDp },
                    schema = if (new.schema.isNotEmpty()) new.schema else it.schema,
                    category = new.category.ifBlank { it.category },
                    group = new.group.ifBlank { it.group },
                    productId = new.productId.ifBlank { it.productId },
                    model = new.model.ifBlank { it.model },
                    lightRoles = if (new.lightRoles.isNotEmpty()) new.lightRoles else it.lightRoles,
                ) else it
            }
        }
    }
    return list
}

/**
 * Aktualizácia zariadení podľa Tuya cloudu (Synchronizovať): podľa Device ID doplní nový local key (napr. po
 * opätovnom spárovaní), popis hodnôt a kategóriu. Názov, IP, verzia, vlastné názvy hodnôt, skryté hodnoty a
 * vypínače zostanú. Vráti nový zoznam a názvy zariadení, ktoré sa zmenili.
 */
fun mergeCloud(
    current: List<Device>,
    cloud: List<CloudDevice>,
    mappings: Map<String, Map<String, DpSchema>>,
): Pair<List<Device>, List<String>> {
    val byId = cloud.associateBy { it.id }
    val changed = mutableListOf<String>()
    val list = current.map { d ->
        val c = byId[d.devId] ?: return@map d
        val schema = mappings[d.devId]?.takeIf { it.isNotEmpty() } ?: d.schema
        val updated = d.copy(
            // podzariadenie komunikuje cez bránu (jej kľúč), vlastný local key sa nepoužíva
            localKey = if (d.isSub) d.localKey else (c.localKey.takeIf { it.length == 16 } ?: d.localKey),
            gatewayId = if (d.isSub && c.parentId.isNotBlank() && c.nodeId.isNotBlank()) c.parentId else d.gatewayId,
            cid = if (d.isSub && c.parentId.isNotBlank() && c.nodeId.isNotBlank()) c.nodeId else d.cid,
            schema = schema,
            category = c.category.ifBlank { d.category },
            productId = c.productId.ifBlank { d.productId },
            model = c.productName.ifBlank { d.model },
        )
        if (updated != d) changed += d.name
        updated
    }
    return list to changed
}

/** Nové zariadenie z Tuya cloudu; IP a verzia z hľadania v sieti, ak sa našlo. */
fun deviceFromCloud(c: CloudDevice, schema: Map<String, DpSchema>, found: FoundDevice?): Device = Device(
    name = c.name,
    ip = found?.ip.orEmpty(),
    devId = c.id,
    localKey = c.localKey,
    version = found?.version?.takeIf { it in SUPPORTED_VERSIONS } ?: "3.3",
    schema = schema,
    category = c.category,
    productId = c.productId,
    model = c.productName,
)

/**
 * Nové podzariadenie (Zigbee) z Tuya cloudu. Nemá IP ani local key, ovláda sa cez bránu [gateway] (jej Device ID).
 * [cid] je ID podzariadenia za bránou (node_id).
 */
fun deviceFromCloudSub(c: CloudDevice, schema: Map<String, DpSchema>, gatewayDevId: String, cid: String): Device = Device(
    name = c.name,
    ip = "",
    devId = c.id,
    localKey = "",
    version = "3.3",
    schema = schema,
    category = c.category,
    productId = c.productId,
    model = c.productName,
    gatewayId = gatewayDevId,
    cid = cid,
)
