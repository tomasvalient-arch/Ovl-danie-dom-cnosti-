package sk.domov.ovladanie

/*
 * Plynulé ovládanie posuvníkov (od 1.9.2): počas ťahania sa posiela najnovšia hodnota najviac raz za [LIVE_INTERVAL_MS],
 * bez frontu (kým beží odoslanie, čaká len jedna – najnovšia – hodnota), po pustení vždy presná posledná hodnota.
 * Hlásenia zo zariadenia so starou hodnotou počas ťahania a krátko po ňom sa ignorujú (posuvník neskáče späť).
 * Čistý Kotlin bez Androidu (test: tools/LiveTest.kt).
 */

/** Najviac jedno odoslanie za tento čas počas ťahania. */
const val LIVE_INTERVAL_MS = 130L

/** Ako dlho po pustení sa ignorujú hlásenia so starou hodnotou. */
const val LIVE_HOLD_MS = 1500L

/**
 * Obmedzovač odosielania pre jedno zariadenie. [offer] počas ťahania, [finish] po pustení (hodnota sa pošle vždy).
 * Odosiela sa na vlastnom vlákne ([runner]); [sender] môže blokovať a vyhodiť výnimku (nikdy nespadne appka).
 * Nové hodnoty sa zlučujú s čakajúcou cez [merge] (pri príkazoch: novšie DP prepíšu staršie).
 */
class LiveThrottle<T : Any>(
    private val intervalMs: Long = LIVE_INTERVAL_MS,
    private val merge: (old: T, new: T) -> T = { _, n -> n },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val runner: (Runnable) -> Unit = { r -> Thread(r, "domov-live").apply { isDaemon = true }.start() },
    private val sender: (T) -> Unit,
) {
    private val lock = Any()
    private var pending: T? = null
    private var running = false
    private var lastSend = Long.MIN_VALUE / 4
    private val callbacks = ArrayList<(Throwable?) -> Unit>()

    /** Čas posledného offer/finish (na zistenie, či ťahanie ešte prebieha). */
    @Volatile var lastActivity: Long = 0L
        private set

    /** Beží odosielanie alebo čaká hodnota. */
    val busy: Boolean get() = synchronized(lock) { running || pending != null }

    /** Hodnota počas ťahania – môže byť nahradená novšou skôr, než sa odošle. */
    fun offer(value: T) = submit(value, null)

    /** Konečná hodnota po pustení – odošle sa vždy (po prípadnom práve bežiacom odoslaní); [done] dostane chybu alebo null. */
    fun finish(value: T, done: ((Throwable?) -> Unit)? = null) = submit(value, done)

    private fun submit(value: T, done: ((Throwable?) -> Unit)?) {
        val start: Boolean
        synchronized(lock) {
            pending = pending?.let { merge(it, value) } ?: value
            if (done != null) callbacks += done
            lastActivity = clock()
            start = !running
            if (start) running = true
        }
        if (start) {
            try {
                runner(Runnable { loop() })
            } catch (e: Throwable) {
                synchronized(lock) { running = false }
            }
        }
    }

    private fun loop() {
        try {
            while (true) {
                val wait = synchronized(lock) { lastSend + intervalMs - clock() }
                if (wait > 0) {
                    try {
                        sleeper(wait)
                    } catch (e: InterruptedException) {
                        // pokračujeme – konečná hodnota sa musí odoslať
                    }
                }
                val value: T
                val cbs: List<(Throwable?) -> Unit>
                synchronized(lock) {
                    val p = pending
                    if (p == null) {
                        running = false
                        return
                    }
                    value = p
                    pending = null
                    cbs = callbacks.toList()
                    callbacks.clear()
                    lastSend = clock()
                }
                val err = try {
                    sender(value)
                    null
                } catch (e: Throwable) {
                    e
                }
                cbs.forEach { runCatching { it(err) } }
            }
        } catch (e: Throwable) {
            synchronized(lock) { running = false }
        }
    }
}

/**
 * Ochrana pred „skákaním späť“: kým sa DP ťahá a [holdMs] po pustení, hlásenie zariadenia s inou hodnotou sa nahradí
 * aktuálnou hodnotou v appke. Keď zariadenie nahlási očakávanú hodnotu, ochrana pre DP po pustení skončí.
 */
class DragGuard(private val holdMs: Long = LIVE_HOLD_MS, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private class Entry(val dragging: Boolean, val expected: Any, val until: Long)

    private val map = HashMap<String, HashMap<String, Entry>>()

    /** Ťahanie: tieto DP sa chránia, kým nepríde [release]. */
    @Synchronized
    fun drag(deviceId: String, values: Map<String, Any>) {
        val m = map.getOrPut(deviceId) { HashMap() }
        values.forEach { (dp, v) -> m[dp] = Entry(true, v, Long.MAX_VALUE) }
    }

    /** Pustenie s konečnými hodnotami: chránia sa ešte [holdMs]. */
    @Synchronized
    fun release(deviceId: String, values: Map<String, Any>) {
        val m = map.getOrPut(deviceId) { HashMap() }
        val until = clock() + holdMs
        values.forEach { (dp, v) -> m[dp] = Entry(false, v, until) }
    }

    /** Prebieha ťahanie alebo ochrana po ňom (vtedy sa zariadenie pravidelne neobnovuje). */
    @Synchronized
    fun active(deviceId: String): Boolean {
        val m = map[deviceId] ?: return false
        val now = clock()
        m.entries.removeAll { !it.value.dragging && now > it.value.until }
        if (m.isEmpty()) map.remove(deviceId)
        return m.isNotEmpty()
    }

    /** Hlásenie zo zariadenia [fresh] bez starých hodnôt chránených DP (nahradí ich hodnotou z [current]). */
    @Synchronized
    fun filter(deviceId: String, current: Map<String, Any>?, fresh: Map<String, Any>): Map<String, Any> {
        val m = map[deviceId] ?: return fresh
        val now = clock()
        var out: MutableMap<String, Any>? = null
        val it = m.entries.iterator()
        while (it.hasNext()) {
            val (dp, e) = it.next()
            if (!e.dragging && now > e.until) {
                it.remove()
                continue
            }
            val incoming = fresh[dp] ?: continue
            if (same(incoming, e.expected)) {
                if (!e.dragging) it.remove()
                continue
            }
            if (out == null) out = LinkedHashMap(fresh)
            out[dp] = current?.get(dp) ?: e.expected
        }
        if (m.isEmpty()) map.remove(deviceId)
        return out ?: fresh
    }

    companion object {
        fun same(a: Any, b: Any): Boolean = when {
            a is Number && b is Number -> a.toDouble() == b.toDouble()
            a is String && b is String -> a.equals(b, ignoreCase = true)
            else -> a == b
        }
    }
}
