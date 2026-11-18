package sk.domov.ovladanie

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Udalosti servera pre obrazovku (volajú sa z vlákna servera). */
sealed class ShareEvent {
    data object PageOpened : ShareEvent()
    data object ApkDownloaded : ShareEvent()
    data class Joined(val name: String) : ShareEvent()
}

/**
 * Dočasný HTTP server (len ServerSocket, bez knižnice) na adrese telefónu v domácej WiFi.
 * `/d` stránka s návodom, `/domov.apk` nainštalovaná appka (applicationInfo.sourceDir), `/share` zašifrované zariadenia (raz).
 * Všetko len s platným tokenom a do vypršania; zastaví sa [stop] (pri odchode z obrazovky).
 */
class AppShareServer(
    private val apk: File,
    private val offer: ShareOffer,
    private val encryptedPayload: String,
    private val ownerName: String,
    private val onEvent: (ShareEvent) -> Unit,
) {
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    private val shared = AtomicBoolean(false)
    val downloads = AtomicInteger(0)

    /** Spustí server, vráti port. */
    fun start(address: InetAddress): Int {
        var s: ServerSocket? = null
        for (p in PORTS) {
            s = try { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(address, p), 16) } } catch (e: IOException) { null }
            if (s != null) break
        }
        val ss = s ?: ServerSocket().apply { bind(InetSocketAddress(address, 0), 16) }
        server = ss
        thread(name = "domov-share", isDaemon = true) {
            while (!ss.isClosed) {
                val c = try { ss.accept() } catch (e: IOException) { break }
                try { pool.execute { handle(c) } } catch (e: Exception) { runCatching { c.close() } }
            }
        }
        return ss.localPort
    }

    fun stop() {
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 15_000
            c.use { sock ->
                val input = BufferedInputStream(sock.getInputStream())
                val head = readHead(input) ?: return
                val line = head.lineSequence().firstOrNull().orEmpty().split(' ')
                if (line.size < 2 || (line[0] != "GET" && line[0] != "HEAD")) return respond(sock.getOutputStream(), 405, "text/plain", "Method not allowed")
                val target = line[1]
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "")
                val params = query.split('&').filter { '=' in it }.associate {
                    it.substringBefore('=') to runCatching { URLDecoder.decode(it.substringAfter('='), "UTF-8") }.getOrDefault("")
                }
                val out = sock.getOutputStream()
                val now = System.currentTimeMillis()
                val ok = offer.valid(now) && offer.tokenMatches(params["t"])
                when (path) {
                    "/", "/d" -> if (ok) {
                        onEvent(ShareEvent.PageOpened)
                        respond(out, 200, "text/html; charset=utf-8", page())
                    } else respond(out, 410, "text/html; charset=utf-8", expiredPage())
                    "/domov.apk", "/Domov.apk" -> if (ok) {
                        val len = apk.length()
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Length: $len\r\n" +
                            "Content-Disposition: attachment; filename=\"Domov.apk\"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                        if (line[0] == "GET") {
                            apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                            out.flush()
                            downloads.incrementAndGet()
                            onEvent(ShareEvent.ApkDownloaded)
                        }
                    } else respond(out, 410, "text/html; charset=utf-8", expiredPage())
                    "/share" -> when {
                        !ok -> respond(out, 410, "text/plain", "expired")
                        !shared.compareAndSet(false, true) -> respond(out, 410, "text/plain", "used")
                        else -> {
                            respond(out, 200, "text/plain; charset=utf-8", encryptedPayload)
                            onEvent(ShareEvent.Joined(params["name"].orEmpty().take(40).ifBlank { "Nový telefón" }))
                        }
                    }
                    else -> respond(out, 404, "text/plain", "Not found")
                }
            }
        } catch (e: Exception) {
            runCatching { c.close() }
        }
    }

    private fun readHead(input: InputStream): String? {
        val buf = StringBuilder()
        while (buf.length < 8192) {
            val b = input.read()
            if (b < 0) return null
            buf.append(b.toChar())
            if (buf.endsWith("\r\n\r\n") || buf.endsWith("\n\n")) return buf.toString()
        }
        return null
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Gone" }
        out.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(): String {
        val mb = String.format(java.util.Locale.US, "%.1f", apk.length() / 1_048_576.0).replace('.', ',')
        val until = java.text.SimpleDateFormat("H:mm", java.util.Locale.forLanguageTag("sk")).format(java.util.Date(offer.expiresAt))
        val owner = esc(ownerName)
        return """<!doctype html><html lang="sk"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Domov – inštalácia</title><style>
body{font-family:system-ui,sans-serif;background:#131110;color:#EDE0D6;margin:0;padding:24px;line-height:1.5}
h1{font-size:26px;margin:0 0 4px}.m{color:#C4B5A8;font-size:14px}
a.b{display:block;text-align:center;background:#FFB95C;color:#3F2400;font-weight:700;text-decoration:none;padding:16px;border-radius:16px;margin:22px 0;font-size:18px}
ol{padding-left:20px}li{margin:10px 0}.c{background:#1C1917;border:1px solid #2B2622;border-radius:16px;padding:14px 16px;margin-top:16px}
</style></head><body>
<h1>Domov</h1><div class="m">Ovládanie domácnosti · od telefónu „$owner“ · odkaz platí do $until</div>
<a class="b" href="/domov.apk?t=${offer.token}">Stiahnuť Domov ($mb MB)</a>
<ol>
<li>Ťukni na <b>Stiahnuť Domov</b>. Ak prehliadač upozorní na súbor alebo nezabezpečené sťahovanie, vyber <b>Stiahnuť aj tak / Ponechať</b>.</li>
<li>Otvor stiahnutý súbor <b>Domov.apk</b> (v upozornení alebo v Stiahnutých súboroch).</li>
<li>Telefón sa spýta, či povoliť <b>inštaláciu neznámych aplikácií</b> pre prehliadač – povoľ to a vráť sa späť. Ak upozorní Play Protect, vyber <b>Inštalovať aj tak</b> (appka nie je z Obchodu Play).</li>
<li>Otvor appku <b>Domov</b> → <b>Pripojiť sa k domácnosti</b> a naskenuj <b>ten istý QR kód</b> na telefóne „$owner“. Appka si stiahne zariadenia, ktoré ti boli zdieľané.</li>
</ol>
<div class="c m">Oba telefóny musia byť na rovnakej WiFi. Ak už appku Domov máš, krok 1–3 preskoč.</div>
</body></html>"""
    }

    private fun expiredPage(): String = """<!doctype html><html lang="sk"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Domov</title></head><body style="font-family:system-ui,sans-serif;background:#131110;color:#EDE0D6;padding:24px">
<h1>Odkaz už neplatí</h1><p>Na telefóne s appkou Domov otvor znova <b>Zdieľať appku</b> a naskenuj nový QR kód.</p></body></html>"""

    companion object {
        val PORTS = listOf(8765, 8766, 8767, 8768)
    }
}

/** Druhý telefón: stiahne zašifrované zariadenia z [link] (cez WiFi) a rozšifruje ich kľúčom z QR. */
object AppShareClient {
    class ShareException(message: String) : Exception(message)

    fun fetch(context: Context, link: ShareLink, myName: String): SharePayload {
        val wifi = wifiAddress(context)
        val socket = (wifi?.network?.socketFactory?.createSocket() ?: Socket())
        val body: String
        try {
            socket.soTimeout = 15_000
            try {
                socket.connect(InetSocketAddress(link.host, link.port), 8_000)
            } catch (e: IOException) {
                throw ShareException("Druhý telefón neodpovedá. Ste obaja na rovnakej WiFi a je na ňom ešte otvorený QR kód?")
            }
            val path = "/share?t=${link.token}&name=${URLEncoder.encode(myName, "UTF-8")}"
            socket.getOutputStream().apply {
                write("GET $path HTTP/1.1\r\nHost: ${link.host}:${link.port}\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val raw = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val status = raw.substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull()
            body = raw.substringAfter("\r\n\r\n", "")
            when {
                status == 410 && body.trim() == "used" -> throw ShareException("Tento QR kód už bol použitý. Na druhom telefóne vytvor nový (Zdieľať appku).")
                status == 410 -> throw ShareException("QR kód vypršal. Na druhom telefóne vytvor nový (Zdieľať appku).")
                status != 200 -> throw ShareException("Neočakávaná odpoveď druhého telefónu ($status).")
            }
        } finally {
            runCatching { socket.close() }
        }
        val plain = try {
            AppShareCrypto.decrypt(body, link.key, link.token)
        } catch (e: Exception) {
            throw ShareException("Údaje sa nepodarilo rozšifrovať – naskenuj QR kód znova.")
        }
        return try { SharePayload.fromJson(plain) } catch (e: IllegalArgumentException) { throw ShareException(e.message ?: "Neplatné údaje") }
    }
}
