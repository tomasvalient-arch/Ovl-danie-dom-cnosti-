package sk.domov.ovladanie

import org.json.JSONObject

/**
 * Sledovanie fritézy pre upozornenia (čistá logika bez Androidu, testovaná v tools/FryerWatchTest.kt).
 *
 * Hodnoty podľa štandardu Tuya pre teplovzdušné fritézy (kategória kqzg) a BH-AF2: 2 štart/stop, 5 stav
 * (standby, pre_heating, cooking, pausing, completed, keep_warm…), 10 zostávajúci čas v minútach, 12 porucha (bitmapa,
 * E1/E2 – teplotná sonda). Pripomienku „pretras/otoč jedlo“ BH-AF2 neposiela; ak ju iná fritéza hlási (kód shake / turn
 * v schéme, alebo stav shake/turn_food), appka upozorní aj na ňu.
 */
data class FryerSnapshot(
    val running: Boolean,
    val status: String,
    val remainingMin: Int?,
    val fault: Long,
    val shake: Boolean,
)

/** Uložený stav sledovania jednej fritézy (medzi kontrolami). */
data class FryerWatchState(
    val running: Boolean = false,
    /** Odhad konca varenia (ms), 0 = nevieme. */
    val endsAt: Long = 0L,
    val lastRemainingMin: Int? = null,
    val oneMinuteSent: Boolean = false,
    val shake: Boolean = false,
    val fault: Long = 0L,
    val awayAlerted: Boolean = false,
    val updatedAt: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().put("run", running).put("end", endsAt).put("rem", lastRemainingMin ?: -1)
        .put("one", oneMinuteSent).put("shake", shake).put("fault", fault).put("away", awayAlerted).put("t", updatedAt)

    companion object {
        fun fromJson(text: String?): FryerWatchState = try {
            val o = JSONObject(text ?: "")
            FryerWatchState(o.optBoolean("run"), o.optLong("end"), o.optInt("rem", -1).takeIf { it >= 0 }, o.optBoolean("one"),
                o.optBoolean("shake"), o.optLong("fault"), o.optBoolean("away"), o.optLong("t"))
        } catch (e: Exception) {
            FryerWatchState()
        }
    }
}

/** Nastavenia upozornení jednej fritézy (v detaile fritézy). */
data class FryerAlertSettings(
    val enabled: Boolean = true,
    val oneMinute: Boolean = true,
    val done: Boolean = true,
    val safety: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("on", enabled).put("one", oneMinute).put("done", done).put("safety", safety)

    companion object {
        fun fromJson(text: String?): FryerAlertSettings = try {
            val o = JSONObject(text ?: "")
            FryerAlertSettings(o.optBoolean("on", true), o.optBoolean("one", true), o.optBoolean("done", true), o.optBoolean("safety", true))
        } catch (e: Exception) {
            FryerAlertSettings()
        }
    }
}

sealed class FryerEvent {
    object Started : FryerEvent()
    object OneMinute : FryerEvent()
    object Done : FryerEvent()
    /** Varenie skončilo skôr (Stop z appky / na fritéze). */
    object Stopped : FryerEvent()
    object Shake : FryerEvent()
    data class Fault(val code: Long, val text: String) : FryerEvent()
    /** Fritéza varí a doma nikto nie je. */
    object AwayRunning : FryerEvent()
}

object FryerWatch {
    private val DONE_STATUS = setOf("completed", "complete", "finish", "finished", "done", "time out", "time_out", "cooking_done")
    private val RUN_STATUS = setOf("cooking", "pre_heating", "preheating", "preheat", "run", "running", "work", "working")

    /** Stav fritézy z hodnôt; null = nie je to fritéza (alebo chýbajú hodnoty). */
    fun snapshot(values: Map<String, Any>, schema: Map<String, DpSchema> = emptyMap()): FryerSnapshot? {
        val spec = Fryer.detect(values) ?: return null
        val status = (values[spec.status] as? String)?.trim()?.lowercase().orEmpty()
        val running = values[spec.start] == true && status !in DONE_STATUS && status != "pausing"
        val faultDp = Schema.dpOf(schema, "fault") ?: "12".takeIf { schema.isEmpty() || schema["12"] == null }
        val fault = when (val f = values[faultDp ?: ""]) {
            is Number -> f.toLong()
            is Boolean -> if (f) 1L else 0L
            else -> 0L
        }
        val shakeDp = schema.entries.firstOrNull { (_, s) -> s.type == DpType.BOOLEAN && Regex("(?i)(shake|turn_food|turn_over|flip)").containsMatchIn(s.code) }?.key
        val shake = (shakeDp != null && values[shakeDp] == true) || status.contains("shake") || status.contains("turn")
        return FryerSnapshot(running, status, spec.remaining?.let { (values[it] as? Number)?.toInt() }, fault, shake)
    }

    /** Popis poruchy (bitmapa E1, E2…). */
    fun faultText(code: Long, schema: Map<String, DpSchema> = emptyMap()): String {
        val labels = schema[Schema.dpOf(schema, "fault") ?: ""]?.range.orEmpty()
        val bits = (0 until 32).filter { code and (1L shl it) != 0L }
        val names = bits.map { labels.getOrNull(it) ?: "E${it + 1}" }
        val known = names.joinToString { n ->
            when (n.uppercase()) {
                "E1" -> "E1 (prerušená teplotná sonda)"
                "E2" -> "E2 (skrat teplotnej sondy)"
                else -> n
            }
        }
        return if (known.isBlank()) "porucha $code" else known
    }

    /** Zostávajúci čas podľa lokálneho odpočtu (ms), null = nevieme. */
    fun remainingMs(s: FryerWatchState, now: Long): Long? = if (s.running && s.endsAt > 0) (s.endsAt - now).coerceAtLeast(0) else null

    /**
     * Jedna kontrola: nový stav a udalosti. [nobodyHome] = podľa domácnosti nie je nikto doma (null = nevieme).
     * Zostávajúci čas zariadenie hlási v celých minútach; lokálny odpočet sa zosynchronizuje, keď sa líši o viac ako minútu.
     */
    fun step(prev: FryerWatchState, snap: FryerSnapshot, now: Long, nobodyHome: Boolean?, settings: FryerAlertSettings): Pair<FryerWatchState, List<FryerEvent>> {
        val ev = mutableListOf<FryerEvent>()
        var s = prev.copy(updatedAt = now)
        if (snap.running) {
            if (!prev.running) ev += FryerEvent.Started
            val rem = snap.remainingMin
            if (rem != null) {
                // zariadenie hlási minúty zaokrúhlené nahor: N = zostáva (N-1, N] min → odhad N×60 s − 30 s
                val estimate = now + rem * 60_000L - 30_000L
                val local = if (prev.running && prev.endsAt > 0) prev.endsAt else 0L
                val resync = local == 0L || kotlin.math.abs(local - estimate) > 60_000L || rem != prev.lastRemainingMin && rem > (prev.lastRemainingMin ?: -1)
                s = s.copy(endsAt = if (resync) estimate.coerceAtLeast(now) else local, lastRemainingMin = rem)
                // nové varenie s dlhším časom → znova povoliť upozornenie minútu pred koncom
                if (prev.oneMinuteSent && rem > 2) s = s.copy(oneMinuteSent = false)
            }
            s = s.copy(running = true)
            val left = remainingMs(s, now)
            if (settings.enabled && settings.oneMinute && !s.oneMinuteSent && ((rem != null && rem <= 1) || (left != null && left <= 65_000L))) {
                ev += FryerEvent.OneMinute
                s = s.copy(oneMinuteSent = true)
            }
            if (nobodyHome == true && !s.awayAlerted && settings.enabled && settings.safety) {
                ev += FryerEvent.AwayRunning
                s = s.copy(awayAlerted = true)
            }
        } else {
            if (prev.running) {
                val left = remainingMs(prev, now)
                val finished = snap.status in DONE_STATUS || (prev.lastRemainingMin ?: 99) <= 1 || (left != null && left <= 90_000L) ||
                    (snap.remainingMin == 0 && snap.status !in RUN_STATUS && snap.status != "pausing" && (prev.lastRemainingMin ?: 99) <= 2)
                ev += if (finished) FryerEvent.Done else FryerEvent.Stopped
            }
            s = s.copy(running = false, endsAt = 0L, oneMinuteSent = false, awayAlerted = false, lastRemainingMin = snap.remainingMin)
        }
        if (snap.shake && !prev.shake) ev += FryerEvent.Shake
        s = s.copy(shake = snap.shake)
        if (snap.fault != 0L && snap.fault != prev.fault) ev += FryerEvent.Fault(snap.fault, faultText(snap.fault))
        s = s.copy(fault = snap.fault)
        // vypnuté upozornenia: udalosti sa nehlásia (bezpečnostné majú vlastný prepínač)
        val out = ev.filter { e ->
            when (e) {
                FryerEvent.OneMinute -> settings.enabled && settings.oneMinute
                FryerEvent.Done -> settings.enabled && settings.done
                FryerEvent.AwayRunning, is FryerEvent.Fault -> settings.enabled && settings.safety
                FryerEvent.Shake -> settings.enabled
                else -> true
            }
        }
        return s to out
    }

    /** Lokálne (bez kontroly zariadenia): treba už upozorniť minútu pred koncom? */
    fun localOneMinuteDue(s: FryerWatchState, now: Long, settings: FryerAlertSettings): Boolean {
        val left = remainingMs(s, now) ?: return false
        return settings.enabled && settings.oneMinute && !s.oneMinuteSent && left <= 65_000L
    }

    /**
     * Ako často kontrolovať: doma (lokálne) 20 s počas varenia, 10 s v poslednej minúte, 90 s keď fritéza nevarí;
     * mimo domu cez cloud 60 s počas varenia a 10 min keď nevarí (obmedzenie počtu volaní cloudu).
     * [background] = služba na pozadí (šetrí batériu): doma 60 s počas varenia, 30 s v poslednej minúte, 3 min keď nevarí;
     * na „minútu pred koncom“ sa aj tak zobudí presne.
     */
    fun nextPollMs(s: FryerWatchState, now: Long, local: Boolean, background: Boolean = false): Long {
        val bgLocal = local && background
        if (!s.running) return if (bgLocal) 180_000L else if (local) 90_000L else 600_000L
        val base = if (local && !background) 20_000L else 60_000L
        val left = remainingMs(s, now) ?: return base
        if (left <= 75_000L) return if (local && !background) 10_000L else 30_000L
        // zobudiť sa presne na „minútu pred koncom“
        val toOneMinute = left - 60_000L
        return minOf(base, maxOf(5_000L, toOneMinute))
    }

    /** „zostáva 12 min“ / „posledná minúta“. */
    fun remainingText(s: FryerWatchState, now: Long): String {
        val left = remainingMs(s, now) ?: return s.lastRemainingMin?.let { "zostáva $it min" } ?: "varí"
        val min = ((left + 59_999L) / 60_000L).toInt()
        return if (min <= 1) "posledná minúta" else "zostáva $min min"
    }

    /** Je podľa domácnosti doma niekto? false = nikto (telefón mimo domu a nikto neostal), null = nevieme. */
    fun nobodyHome(state: PresenceState, others: List<Member>, presenceOn: Boolean): Boolean? {
        if (!presenceOn) return null
        return when (state) {
            PresenceState.AWAY -> others.isEmpty()
            PresenceState.HOME -> false
            else -> null
        }
    }
}
