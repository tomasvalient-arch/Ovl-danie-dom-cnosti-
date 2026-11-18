package sk.domov.ovladanie

import android.content.Context

/** Uložené scény (zatiaľ Noc). */
class SceneStore(context: Context) {
    private val prefs = context.getSharedPreferences("domov_scenes", Context.MODE_PRIVATE)

    fun night(): NightScene = NightScene.fromJson(prefs.getString("night", null))

    fun saveNight(scene: NightScene) {
        prefs.edit().putString("night", scene.toJson()).apply()
    }
}

/**
 * Ukážkové zariadenia na snímky obrazovky (len v pamäti, nič sa neukladá). Spustí sa iba cez adb:
 * `adb shell am start -n sk.domov.ovladanie/.MainActivity --ez demo true`.
 */
object DemoData {
    private fun light(on: Boolean, mode: String, bright: Int, temp: Int, colour: String = "001e03e803e8", countdown: Int = 0) =
        mapOf<String, Any>("20" to on, "21" to mode, "22" to bright, "23" to temp, "24" to colour, "25" to "000e0d0000000000000000c80000", "26" to countdown)

    fun build(): Pair<List<Device>, Map<String, Map<String, Any>>> {
        fun dev(id: String, name: String, category: String = "", schema: Map<String, DpSchema> = emptyMap()) =
            Device(id = id, name = name, ip = "192.168.1.${20 + id.last().digitToInt()}", devId = "bf0demo000000$id", localKey = "0123456789abcdef", category = category, schema = schema)
        val socketSchema = mapOf(
            "1" to DpSchema("switch_1", DpType.BOOLEAN),
            "9" to DpSchema("countdown_1", DpType.INTEGER, unit = "s", min = 0, max = 86400),
            "17" to DpSchema("add_ele", DpType.INTEGER, unit = "kWh", min = 0, max = 50000, scale = 2),
            "18" to DpSchema("cur_current", DpType.INTEGER, unit = "mA", min = 0, max = 30000),
            "19" to DpSchema("cur_power", DpType.INTEGER, unit = "W", min = 0, max = 50000, scale = 1),
            "20" to DpSchema("cur_voltage", DpType.INTEGER, unit = "V", min = 0, max = 5000, scale = 1),
            "38" to DpSchema("relay_status", DpType.ENUM, range = listOf("power_off", "power_on", "last")),
            "40" to DpSchema("light_mode", DpType.ENUM, range = listOf("relay", "pos", "none")),
            "41" to DpSchema("child_lock", DpType.BOOLEAN),
        )
        // BlitzWolf BW-CLT1: hlavné biele svetlo + farebný podhľad cez mix_rgbcw (DP 51)
        val ceilingSchema = mapOf(
            "20" to DpSchema("switch_led", DpType.BOOLEAN),
            "21" to DpSchema("work_mode", DpType.ENUM, range = listOf("white", "colour", "scene", "music")),
            "22" to DpSchema("bright_value_v2", DpType.INTEGER, min = 10, max = 1000),
            "23" to DpSchema("temp_value_v2", DpType.INTEGER, min = 0, max = 1000),
            "24" to DpSchema("colour_data_v2", DpType.JSON),
            "26" to DpSchema("countdown_1", DpType.INTEGER, unit = "s", min = 0, max = 86400),
            "51" to DpSchema("mix_rgbcw", DpType.RAW),
        )
        val devices = listOf(
            dev("d1", "Obývačka LED", "dj"),
            dev("d2", "Spálňa lampa", "dj"),
            dev("d3", "Kuchyňa", "dj"),
            dev("d4", "Chodba", "dj"),
            dev("d9", "Spálňa strop", "xdd", ceilingSchema).copy(model = "BW-CLT1"),
            dev("d5", "Zásuvka TV", "cz", socketSchema),
            dev("d6", "Predlžovačka", "pc"),
            dev("d7", "Fritéza", "kfj"),
            dev("d8", "Ohrievač kúpeľňa", "qn"),
        )
        val values = mapOf(
            "d9" to light(true, "white", 800, 300, colour = "011803e80320") + ("51" to "AAcBGAPoAyADIAEs"),
            "d1" to light(true, "white", 800, 300),
            "d2" to light(true, "white", 350, 0),
            "d3" to light(false, "white", 1000, 500),
            "d4" to light(false, "white", 600, 200),
            "d5" to mapOf<String, Any>("1" to true, "9" to 2530, "17" to 1284, "18" to 520, "19" to 1187, "20" to 2314, "38" to "last", "40" to "relay", "41" to false),
            "d6" to mapOf<String, Any>("1" to false, "2" to false, "3" to false, "7" to 0),
            "d7" to mapOf<String, Any>("1" to false, "2" to false, "3" to "Chips", "5" to "standby", "6" to 180, "9" to 15, "10" to 0),
        )
        return devices to values
    }

    /** Graf príkonu za posledných ~10 minút pre ukážkovú zásuvku. */
    fun seedPower(devices: List<Device>) {
        val d = devices.find { it.name == "Zásuvka TV" } ?: return
        val now = System.currentTimeMillis()
        val n = 140
        for (i in 0 until n) {
            val t = now - (n - 1 - i) * 4000L
            val base = when {
                i < 40 -> 42f
                i < 52 -> 42f + (i - 40) * 5.5f
                else -> 108f + (i - 52) * 0.12f
            }
            val noise = ((i * 7919) % 13 - 6) * 0.6f
            PowerHistory.add(d.id, if (i == n - 1) 118.7f else base + noise, t)
        }
    }
}
