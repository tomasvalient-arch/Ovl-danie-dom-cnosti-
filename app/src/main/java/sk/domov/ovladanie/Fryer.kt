package sk.domov.ovladanie

import kotlin.math.roundToInt

/**
 * Teplovzdušná fritéza (napr. BlitzHome BH-AF2). Čísla hodnôt vychádzajú zo štandardnej Tuya sady pre fritézy:
 * 1 hlavný vypínač, 2 štart/stop, 3 režim (text, napr. „Chips“), 5 stav (text, napr. „standby“),
 * 6 teplota v °C, 9 nastavený čas v minútach, 10 zostávajúci čas v minútach.
 */
data class FryerSpec(
    val power: String?,
    val start: String,
    val mode: String,
    val status: String,
    val temp: String,
    val time: String,
    val remaining: String?,
) {
    val handled: Set<String> get() = setOfNotNull(power, start, mode, status, temp, time, remaining)
}

data class FryerState(
    val running: Boolean,
    val mode: String,
    val statusRaw: String,
    val tempC: Int,
    val minutes: Int,
    val remainingMin: Int?,
)

object Fryer {
    const val TEMP_MIN = 80
    const val TEMP_MAX = 200
    const val TEMP_STEP = 5
    const val TIME_MIN = 1
    const val TIME_MAX = 60

    fun detect(values: Map<String, Any>): FryerSpec? {
        if (values["2"] !is Boolean) return null
        if (values["3"] !is String || values["5"] !is String) return null
        if (values["6"] !is Number || values["9"] !is Number) return null
        return FryerSpec(
            power = if (values["1"] is Boolean) "1" else null,
            start = "2",
            mode = "3",
            status = "5",
            temp = "6",
            time = "9",
            remaining = if (values["10"] is Number) "10" else null,
        )
    }

    fun read(spec: FryerSpec, values: Map<String, Any>): FryerState = FryerState(
        running = values[spec.start] == true,
        mode = values[spec.mode] as? String ?: "",
        statusRaw = values[spec.status] as? String ?: "",
        tempC = (values[spec.temp] as? Number)?.toInt() ?: TEMP_MAX,
        minutes = (values[spec.time] as? Number)?.toInt() ?: 0,
        remainingMin = spec.remaining?.let { (values[it] as? Number)?.toInt() },
    )

    /** Stav po slovensky; neznáme hodnoty ukáže tak, ako ich zariadenie poslalo. */
    fun statusLabel(raw: String): String = when (raw.trim().lowercase().replace('_', ' ')) {
        "standby", "wait", "wait mode", "set mode" -> "Pohotovosť"
        "cooking", "run", "run mode", "running", "work", "working" -> "Varí"
        "pause", "pause mode", "paused" -> "Pozastavené"
        "stop", "stop mode" -> "Zastavené"
        "finish", "finished", "complete", "completed", "time out" -> "Hotovo"
        "" -> "–"
        else -> raw
    }

    /** Krátky popis pre dlaždicu v zozname. */
    fun summary(spec: FryerSpec, values: Map<String, Any>): String {
        val on = spec.power?.let { values[it] == true } ?: true
        if (!on) return "Vypnuté"
        val st = read(spec, values)
        return if (st.running) {
            val left = st.remainingMin
            if (left != null && left > 0) "Varí · zostáva $left min" else "Varí"
        } else {
            "Zapnuté · ${statusLabel(st.statusRaw)}"
        }
    }

    fun start(spec: FryerSpec, run: Boolean): Map<String, Any> = mapOf(spec.start to run)

    fun mode(spec: FryerSpec, name: String): Map<String, Any> = mapOf(spec.mode to name)

    fun temperature(spec: FryerSpec, celsius: Float): Map<String, Any> {
        val rounded = (celsius / TEMP_STEP).roundToInt() * TEMP_STEP
        return mapOf(spec.temp to rounded.coerceIn(TEMP_MIN, TEMP_MAX))
    }

    fun cookTime(spec: FryerSpec, minutes: Float): Map<String, Any> =
        mapOf(spec.time to minutes.roundToInt().coerceIn(TIME_MIN, TIME_MAX))
}
