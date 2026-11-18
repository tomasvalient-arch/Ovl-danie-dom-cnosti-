package sk.domov.ovladanie

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Denník príkazov, ktoré appka (aj automatika, widget a služba na pozadí) poslala zariadeniam,
 * a zmien stavu, ktoré appka videla. Len v pamäti (posledných ~400 záznamov), zobrazuje sa v Technickom info zariadenia.
 * Slúži na zistenie, kto a kedy zariadeniu poslal príkaz (napr. keď sa svetlo po zapnutí samo vypne).
 */
object CommandLog {
    private class Entry(val time: Long, val devId: String, val text: String)

    private const val MAX = 400
    private val entries = ArrayDeque<Entry>()
    private val lastKey = HashMap<String, Long>()

    @Synchronized
    fun add(devId: String, text: String) {
        entries.addLast(Entry(System.currentTimeMillis(), devId, text))
        while (entries.size > MAX) entries.removeFirst()
    }

    /** Ako [add], ale rovnaký [key] zariadenia sa zapíše najviac raz za [ms] (aby posuvníky a opakované chyby denník nezaplavili). */
    @Synchronized
    fun addThrottled(devId: String, key: String, text: String, ms: Long) {
        val now = System.currentTimeMillis()
        val k = "$devId|$key"
        val last = lastKey[k]
        if (last != null && now - last < ms) return
        lastKey[k] = now
        add(devId, text)
    }

    /** Posledné záznamy zariadenia, od najstaršieho po najnovší, každý s časom „HH:mm:ss.SSS“. */
    @Synchronized
    fun lines(devId: String, max: Int = 60): List<String> {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return entries.filter { it.devId == devId }.takeLast(max).map { fmt.format(Date(it.time)) + "  " + it.text }
    }
}
