import sk.domov.ovladanie.*
import java.util.Collections

// Test plynulých posuvníkov: obmedzenie rýchlosti, zlučovanie bez frontu, konečná hodnota a ochrana pred starým hlásením.

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

fun waitIdle(t: LiveThrottle<*>) {
    val end = System.currentTimeMillis() + 3000
    while (t.busy && System.currentTimeMillis() < end) Thread.sleep(5)
}

fun main() {
    // 1) rýchly prst (hodnota každých 5 ms 1,3 s), zariadenie odpovedá za 20 ms
    run {
        val sent = Collections.synchronizedList(ArrayList<Pair<Long, Int>>())
        val t = LiveThrottle<Int>(intervalMs = 130) { v -> sent += System.currentTimeMillis() to v; Thread.sleep(20) }
        val t0 = System.currentTimeMillis()
        var i = 0
        while (System.currentTimeMillis() - t0 < 1300) { t.offer(i++); Thread.sleep(5) }
        var doneErr: Throwable? = RuntimeException("nezavolané")
        t.finish(9999) { doneErr = it }
        waitIdle(t)
        val elapsed = (sent.last().first - sent.first().first).coerceAtLeast(1)
        val rate = (sent.size - 1) * 1000.0 / elapsed
        println("     odoslané ${sent.size} z $i hodnôt, ${"%.1f".format(rate)} za sekundu")
        check("rýchlosť: najviac ~1 za 130 ms (≤ 8,5/s)", rate <= 8.5)
        check("rýchlosť: priebežne sa posiela (≥ 7 odoslaní)", sent.size >= 7)
        val gaps = sent.zipWithNext { a, b -> b.first - a.first }
        check("medzery medzi odoslaniami ≥ 120 ms", gaps.all { it >= 120 })
        check("posledná odoslaná = konečná hodnota", sent.last().second == 9999)
        check("konečná hodnota potvrdená bez chyby", doneErr == null)
        check("priebežne idú najnovšie hodnoty (rastú)", sent.map { it.second }.zipWithNext().all { (a, b) -> b > a })
        check("po skončení nič nebeží", !t.busy)
    }
    // 2) pomalé zariadenie (300 ms na príkaz): žiadny front, len najnovšia hodnota
    run {
        val sent = Collections.synchronizedList(ArrayList<Int>())
        val t = LiveThrottle<Int>(intervalMs = 130) { v -> sent += v; Thread.sleep(300) }
        for (v in 1..100) { t.offer(v); Thread.sleep(10) }
        t.finish(100)
        val finishAt = System.currentTimeMillis()
        waitIdle(t)
        println("     pomalé zariadenie: odoslané $sent")
        check("bez frontu: zo 100 hodnôt najviac 6 odoslaní", sent.size <= 6)
        check("bez frontu: posledná je konečná", sent.last() == 100)
        check("konečná hodnota najneskôr po jednom bežiacom príkaze", System.currentTimeMillis() - finishAt < 900)
    }
    // 3) zlučovanie príkazov (mapy): novšie DP prepíšu staršie, ostatné ostanú
    run {
        val sent = Collections.synchronizedList(ArrayList<Map<String, Any>>())
        val gate = Object()
        val t = LiveThrottle<Map<String, Any>>(intervalMs = 50, merge = { o, n -> o + n }) { v -> sent += v; Thread.sleep(100) }
        t.offer(mapOf("22" to 100))
        Thread.sleep(20)
        t.offer(mapOf("21" to "colour", "24" to "a"))
        t.offer(mapOf("24" to "b"))
        t.finish(mapOf("24" to "c"))
        waitIdle(t)
        check("zlúčenie: 2 odoslania", sent.size == 2)
        check("zlúčenie: druhé obsahuje režim aj poslednú farbu", sent[1] == mapOf("21" to "colour", "24" to "c"))
    }
    // 4) chyba pri odoslaní: dostane ju konečná hodnota, obmedzovač ďalej funguje
    run {
        var fail = true
        val sent = Collections.synchronizedList(ArrayList<Int>())
        val t = LiveThrottle<Int>(intervalMs = 10) { v -> if (fail) throw java.io.IOException("spadlo") else sent += v }
        var err: Throwable? = null
        t.finish(1) { err = it }
        waitIdle(t)
        check("chyba sa odovzdá, nespadne", err is java.io.IOException && !t.busy)
        fail = false
        t.finish(2)
        waitIdle(t)
        check("po chybe sa ďalej posiela", sent == listOf(2))
    }
    // 5) simulované hodiny: obmedzovač bez skutočného spánku
    run {
        var now = 0L
        val sent = ArrayList<Pair<Long, Int>>()
        val tasks = ArrayList<Runnable>()
        val t = LiveThrottle<Int>(intervalMs = 130, clock = { now }, sleeper = { now += it }, runner = { tasks += it }) { v -> sent += now to v; now += 15 }
        t.offer(1); t.offer(2); t.offer(3)
        tasks.removeAt(0).run()
        check("simulácia: prvá dávka = len najnovšia hodnota 3", sent == listOf(0L to 3))
        t.offer(4); t.offer(5)
        tasks.removeAt(0).run()
        check("simulácia: ďalšia až po 130 ms a len 5", sent.last() == (130L to 5) && sent.size == 2)
    }

    // --- ochrana pred starým hlásením
    var now = 1000L
    val g = DragGuard(holdMs = 1500, clock = { now })
    g.drag("a", mapOf("22" to 500))
    check("ťahanie: zariadenie je chránené", g.active("a") && !g.active("b"))
    val f1 = g.filter("a", mapOf("22" to 500, "20" to true), mapOf("22" to 300, "20" to false))
    check("počas ťahania sa stará hodnota DP 22 ignoruje, iné DP prejdú", f1 == mapOf("22" to 500, "20" to false))
    check("iné zariadenie bez zmeny", g.filter("b", null, mapOf("22" to 1)) == mapOf("22" to 1))
    g.drag("a", mapOf("22" to 520))
    check("počas ťahania ani zhodná hodnota ochranu nezruší", g.filter("a", mapOf("22" to 520), mapOf("22" to 520))["22"] == 520 && g.active("a"))
    g.release("a", mapOf("22" to 530))
    now += 1000
    check("1 s po pustení: stará hodnota ignorovaná", g.filter("a", mapOf("22" to 530), mapOf("22" to 520))["22"] == 530)
    check("1 s po pustení: zhodná hodnota (Long vs Int) prijatá", g.filter("a", mapOf("22" to 530), mapOf("22" to 530L))["22"] == 530L)
    check("po zhode ochrana skončí: nová hodnota zo zariadenia prejde", g.filter("a", mapOf("22" to 530), mapOf("22" to 410))["22"] == 410 && !g.active("a"))
    g.release("a", mapOf("51" to "AAcBGQJpA+gAeAPo"))
    now += 1600
    check("po 1,5 s ochrana vyprší", g.filter("a", mapOf("51" to "AAcBGQJpA+gAeAPo"), mapOf("51" to "AAUBGQJpA+gAeAPo"))["51"] == "AAUBGQJpA+gAeAPo" && !g.active("a"))
    g.release("a", mapOf("24" to "0119026903E8"))
    check("text sa porovnáva bez ohľadu na veľkosť písmen", g.filter("a", null, mapOf("24" to "0119026903e8"))["24"] == "0119026903e8" && !g.active("a"))
    println("Live: všetko OK")
}
