package sk.domov.ovladanie

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

/** Jedno spoločné HTTP(S) volanie cez HttpURLConnection (Tuya cloud, Honeywell, počasie). */
object Http {
    class Reply(val code: Int, val body: String, val retryAfter: String? = null)

    /**
     * Odošle požiadavku a vráti kód a telo (pri chybe 4xx/5xx telo chybovej odpovede).
     * [open] umožní otvoriť spojenie cez konkrétnu sieť (počasie cez mobilné dáta).
     */
    fun send(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        connectMs: Int = 10_000,
        readMs: Int = 15_000,
        open: (URL) -> URLConnection = { it.openConnection() },
    ): Reply {
        val c = open(URL(url)) as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.useCaches = false
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return Reply(code, text, c.getHeaderField("Retry-After"))
        } finally {
            c.disconnect()
        }
    }
}
