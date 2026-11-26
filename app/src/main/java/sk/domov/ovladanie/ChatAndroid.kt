package sk.domov.ovladanie

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Odosielanie a príjem šifrovaného chatu cez ntfy.sh (samostatná téma od synchronizácie zoznamov).
 * Text aj prílohy idú ako AES-256-GCM; na server nikdy neputuje otvorený text ani názov súboru.
 */
object ChatEngine {
    const val BASE = "https://ntfy.sh"
    const val JOB_ID = 4715
    const val CHANNEL = "Chat"
    private const val NOTIFY_ID = 7601

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()
    private val listenLock = Any()
    private var listenJob: Job? = null

    @Volatile var syncing: Boolean = false
        private set

    fun active(c: Context): Boolean = HouseholdStore.active(c)

    fun startListening(c: Context) {
        if (!active(c)) return
        synchronized(listenLock) {
            if (listenJob?.isActive == true) return
            val app = c.applicationContext
            listenJob = scope.launch { listenLoop(app) }
        }
    }

    /**
     * Kľúč domácnosti sa zmenil (telefón prevzal pozvánku): otvorený stream beží na starej téme a zo svojho čakania by
     * vyšiel až po ďalšom riadku zo servera, preto ho zrušiť a začať odznova na novej téme.
     */
    fun restartListening(c: Context) {
        synchronized(listenLock) {
            listenJob?.cancel()
            listenJob = null
        }
        startListening(c)
    }

    fun schedule(c: Context) {
        runCatching {
            val js = c.getSystemService(JobScheduler::class.java) ?: return
            if (!active(c)) {
                js.cancel(JOB_ID)
                return
            }
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(c, ChatJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60 * 1000L, 5 * 60 * 1000L)
                    .setPersisted(true)
                    .build(),
            )
        }
    }

    fun request(c: Context) {
        if (!active(c.applicationContext)) return
        val app = c.applicationContext
        scope.launch {
            runCatching { flush(app) }.onFailure { Log.w("Domov", "chat", it) }
        }
    }

    fun otherMembers(c: Context): List<Member> {
        val self = HouseholdStore.phoneId(c)
        return HouseholdStore.members(c).filter { it.id != self && it.id.isNotBlank() }
            .distinctBy { it.id }
            .sortedBy { it.name.ifBlank { it.id } }
    }

    fun displayName(c: Context, id: String, fallback: String = ""): String {
        val self = HouseholdStore.phoneId(c)
        if (id == self) return HouseholdStore.name(c)
        val m = HouseholdStore.members(c).firstOrNull { it.id == id }
        return m?.name?.ifBlank { null } ?: fallback.ifBlank { "Telefón" }
    }

    suspend fun sendText(c: Context, thread: String, text: String): String? {
        val body = text.trim()
        if (body.isEmpty()) return "Správa je prázdna."
        val msg = outgoing(c, thread, Chat.KIND_TEXT, body, "", 0, "", "")
        ChatStore.upsert(c, msg)
        return finishSend(c, msg.id)
    }

    suspend fun sendPhoto(c: Context, thread: String, uri: Uri): String? {
        val compressed = try {
            compressPhoto(c, uri)
        } catch (e: Exception) {
            return "Fotku sa nepodarilo pripraviť."
        }
        return sendBytes(c, thread, Chat.KIND_PHOTO, "fotka.jpg", "image/jpeg", compressed)
    }

    suspend fun sendFile(c: Context, thread: String, uri: Uri): String? {
        val cr = c.contentResolver
        val name = queryName(c, uri) ?: "súbor"
        val mime = cr.getType(uri)
        val tmp = File(c.cacheDir, "chat-out-${System.currentTimeMillis()}")
        return try {
            cr.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: return "Súbor sa nedá otvoriť."
            val reason = Chat.rejectReason(mime, name, tmp.length())
            if (reason != null) {
                tmp.delete()
                return reason
            }
            sendBytes(c, thread, Chat.KIND_FILE, name, mime.orEmpty(), tmp.readBytes()).also { tmp.delete() }
        } catch (e: Exception) {
            tmp.delete()
            "Súbor sa nepodarilo pripraviť."
        }
    }

    private suspend fun sendBytes(c: Context, thread: String, kind: String, name: String, mime: String, data: ByteArray): String? {
        val reason = Chat.rejectReason(mime, name, data.size.toLong())
        if (reason != null) return reason
        val id = Chat.newId()
        val dest = ChatStore.fileFor(c, id, name)
        dest.writeBytes(data)
        val msg = outgoing(c, thread, kind, "", name, data.size.toLong(), mime, dest.absolutePath).copy(id = id)
        ChatStore.upsert(c, msg)
        return finishSend(c, msg.id)
    }

    /** Po uložení do poradia: sieťová chyba nesmie zhodiť appku, správa ostane na neskorší pokus. */
    private suspend fun finishSend(c: Context, id: String): String? {
        return try {
            flush(c)
            when (ChatStore.get(c, id)?.status) {
                Chat.STATUS_FAILED -> "Správu sa nepodarilo odoslať."
                Chat.STATUS_PENDING -> "Správu sa teraz nepodarilo odoslať. Ostane v poradí a skúsim to znova, keď bude spojenie."
                else -> null
            }
        } catch (e: Exception) {
            Log.w("Domov", "chat: odoslanie zlyhalo", e)
            "Správu sa teraz nepodarilo odoslať. Ostane v poradí a skúsim to znova, keď bude spojenie."
        }
    }

    private fun outgoing(c: Context, thread: String, kind: String, text: String, name: String, size: Long, mime: String, path: String): ChatMessage {
        val self = HouseholdStore.phoneId(c)
        HouseholdStore.noteChatActivity(c)
        return ChatMessage(
            id = Chat.newId(),
            thread = thread.ifBlank { Chat.THREAD_SHARED },
            senderId = self,
            senderName = HouseholdStore.name(c),
            kind = kind,
            text = text,
            fileName = name,
            fileSize = size,
            mime = mime,
            localPath = path,
            createdAt = System.currentTimeMillis(),
            status = Chat.STATUS_PENDING,
            mine = true,
            readers = emptyList(),
            incomingRead = true,
        )
    }

    suspend fun flush(c: Context) {
        sendMutex.withLock { pollAndFlush(c) }
    }

    suspend fun pollAndFlush(c: Context) {
        if (!active(c)) return
        syncing = true
        try {
            val key = HouseholdStore.key(c)
            val topic = Chat.topic(key)
            val aes = Chat.aesKey(key)
            try {
                pull(c, topic, aes)
            } catch (e: Exception) {
                Log.w("Domov", "chat: stiahnutie z ntfy zlyhalo", e)
            }
            for (m in ChatStore.pending(c)) {
                val ok = try {
                    publish(c, topic, aes, m)
                } catch (e: Exception) {
                    Log.w("Domov", "chat: odoslanie zlyhalo, ostáva v poradí", e)
                    return
                }
                if (!ok) {
                    ChatStore.markFailed(c, m.id)
                    return
                }
                ChatStore.markSent(c, m.id)
            }
        } finally {
            syncing = false
        }
    }

    private suspend fun listenLoop(c: Context) {
        var backoff = 2_000L
        while (coroutineContext.isActive) {
            if (!active(c)) {
                delay(15_000)
                continue
            }
            try {
                sendMutex.withLock { pollAndFlush(c) }
                val key = HouseholdStore.key(c)
                val topic = Chat.topic(key)
                val aes = Chat.aesKey(key)
                val since = ChatStore.cursor(c).ifBlank { "0" }
                val url = "$BASE/$topic/json?since=$since"
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 0
                    conn.useCaches = false
                    conn.setRequestProperty("User-Agent", userAgent())
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(60_000L)
                        continue
                    }
                    backoff = 2_000L
                    val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
                    while (coroutineContext.isActive && active(c)) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        handleNtfyLine(c, aes, line)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun pull(c: Context, topic: String, aes: ByteArray) {
        val since = HouseSync.sinceParam(ChatStore.cursor(c))
        val r = Http.send("GET", "$BASE/$topic/json?poll=1&since=$since", headers = mapOf("User-Agent" to userAgent()), connectMs = 12_000, readMs = 20_000)
        if (r.code == 400 && ChatStore.cursor(c).isNotBlank()) {
            ChatStore.setCursor(c, "")
            val r2 = Http.send("GET", "$BASE/$topic/json?poll=1&since=${HouseSync.SINCE_DEFAULT}", headers = mapOf("User-Agent" to userAgent()), connectMs = 12_000, readMs = 20_000)
            if (r2.code in 200..299) r2.body.lineSequence().forEach { handleNtfyLine(c, aes, it) }
            return
        }
        if (r.code in 200..299) r.body.lineSequence().forEach { handleNtfyLine(c, aes, it) }
    }

    private val handleLock = Any()

    /** Stream aj poll môžu dostať tú istú udalosť naraz; zámok bráni dvojitému uloženiu/oznámeniu. */
    private fun handleNtfyLine(c: Context, aes: ByteArray, line: String) = synchronized(handleLock) {
        handleNtfyLineLocked(c, aes, line)
    }

    private fun handleNtfyLineLocked(c: Context, aes: ByteArray, line: String) {
        if (line.isBlank()) return
        val msg = try { JSONObject(line) } catch (_: Exception) { return }
        if (msg.optString("event") !in listOf("", "message")) return
        msg.optString("id").trim().takeIf { it.isNotEmpty() }?.let { ChatStore.setCursor(c, it) }
        val token = msg.optString("message")
        if (token.isBlank()) return
        if (Chat.looksLikePlaintextLeak(token)) {
            Log.w("Domov", "chat: správu bez šifry som zahodil")
            return
        }
        val plain = Chat.decryptText(token, aes) ?: return
        val wire = Chat.parse(plain) ?: return
        val self = HouseholdStore.phoneId(c)
        if (wire.fromId == self) return
        // platná správa zašifrovaná naším kľúčom od iného telefónu: domácnosť žije, aj keď sa po WiFi nikdy nestretli
        HouseholdStore.noteChatActivity(c)
        if (wire.type == Chat.TYPE_RECEIPT) {
            if (wire.receiptFor.isNotBlank()) ChatStore.addReader(c, wire.receiptFor, wire.fromId)
            return
        }
        val existed = ChatStore.get(c, wire.id)
        if (existed != null) return
        var local = ""
        val attach = msg.optJSONObject("attachment")?.optString("url").orEmpty()
        if (attach.isNotBlank() && wire.kind in setOf(Chat.KIND_PHOTO, Chat.KIND_FILE)) {
            local = downloadAttach(c, attach, aes, wire.id, wire.fileName, wire.kind)
        }
        val stored = ChatMessage(
            id = wire.id,
            thread = wire.thread.ifBlank { Chat.THREAD_SHARED },
            senderId = wire.fromId,
            senderName = wire.fromName.ifBlank { displayName(c, wire.fromId) },
            kind = wire.kind,
            text = wire.text,
            fileName = wire.fileName,
            fileSize = wire.fileSize,
            mime = wire.mime,
            localPath = local,
            createdAt = if (wire.at > 0) wire.at else System.currentTimeMillis(),
            status = Chat.STATUS_SENT,
            mine = false,
            readers = emptyList(),
            incomingRead = false,
        )
        ChatStore.upsert(c, stored)
        val open = ChatStore.openThread(c)
        if (open == stored.thread) {
            val unread = ChatStore.markThreadRead(c, stored.thread)
            unread.forEach { sendReceipt(c, it) }
        } else {
            notifyIncoming(c, stored)
        }
    }

    private fun downloadAttach(c: Context, url: String, aes: ByteArray, id: String, name: String, kind: String): String {
        val enc = File(c.cacheDir, "chat-att-$id.enc")
        return try {
            val code = Http.downloadTo(url, enc, headers = mapOf("User-Agent" to userAgent()))
            if (code !in 200..299) return ""
            val raw = enc.readBytes()
            val plain = Chat.decryptBytes(raw, aes) ?: return ""
            val dest = ChatStore.fileFor(c, id, name.ifBlank { if (kind == Chat.KIND_PHOTO) "fotka.jpg" else "subor" })
            dest.writeBytes(plain)
            dest.absolutePath
        } catch (_: Exception) {
            ""
        } finally {
            enc.delete()
        }
    }

    private fun publish(c: Context, topic: String, aes: ByteArray, m: ChatMessage): Boolean {
        val wire = Chat.WireMsg(
            type = Chat.TYPE_CHAT,
            fromId = m.senderId,
            fromName = m.senderName,
            id = m.id,
            thread = m.thread,
            kind = m.kind,
            text = m.text,
            fileName = m.fileName,
            fileSize = m.fileSize,
            mime = m.mime,
            at = m.createdAt,
        )
        val plain = Chat.pack(wire)
        if (plain.length > Chat.MAX_META_PLAIN) return false
        val token = Chat.encryptText(plain, aes)
        if (Chat.looksLikePlaintextLeak(token)) return false
        val headers = mutableMapOf(
            "User-Agent" to userAgent(),
            "Cache" to "yes",
            "TTL" to "24h",
        )
        if (m.kind in setOf(Chat.KIND_PHOTO, Chat.KIND_FILE) && m.localPath.isNotBlank()) {
            val data = File(m.localPath).readBytes()
            val reason = Chat.rejectReason(m.mime, m.fileName, data.size.toLong())
            if (reason != null) return false
            val enc = Chat.encryptBytes(data, aes)
            if (enc.size > Chat.MAX_ATTACH + 64) return false
            headers["Filename"] = "att.bin"
            headers["Message"] = token
            headers["Content-Type"] = "application/octet-stream"
            val r = Http.sendBytes("POST", "$BASE/$topic", headers = headers, body = enc, connectMs = 15_000, readMs = 60_000)
            return r.code in 200..299
        }
        headers["Content-Type"] = "text/plain; charset=utf-8"
        val r = Http.send("POST", "$BASE/$topic", headers = headers, body = token, connectMs = 12_000, readMs = 20_000)
        return r.code in 200..299
    }

    fun sendReceipt(c: Context, of: ChatMessage) {
        if (of.mine) return
        scope.launch {
            runCatching {
                val key = HouseholdStore.key(c)
                val topic = Chat.topic(key)
                val aes = Chat.aesKey(key)
                val self = HouseholdStore.phoneId(c)
                val wire = Chat.WireMsg(
                    type = Chat.TYPE_RECEIPT,
                    fromId = self,
                    fromName = HouseholdStore.name(c),
                    id = "r-" + of.id + "-" + self,
                    thread = of.thread,
                    kind = Chat.KIND_TEXT,
                    text = "",
                    fileName = "",
                    fileSize = 0,
                    mime = "",
                    at = System.currentTimeMillis(),
                    receiptFor = of.id,
                )
                val token = Chat.encryptText(Chat.pack(wire), aes)
                Http.send(
                    "POST", "$BASE/$topic",
                    headers = mapOf("User-Agent" to userAgent(), "Content-Type" to "text/plain; charset=utf-8", "Cache" to "yes", "TTL" to "24h"),
                    body = token,
                )
            }
        }
    }

    fun onThreadVisible(c: Context, thread: String) {
        ChatStore.setOpenThread(c, thread)
        val unread = ChatStore.markThreadRead(c, thread)
        unread.forEach { sendReceipt(c, it) }
        c.getSystemService(NotificationManager::class.java)?.cancel(NOTIFY_ID)
    }

    fun onThreadHidden(c: Context, thread: String) {
        if (ChatStore.openThread(c) == thread) ChatStore.setOpenThread(c, null)
    }

    fun openFile(c: Context, m: ChatMessage): Boolean {
        val path = m.localPath
        if (path.isBlank()) return false
        val f = File(path)
        if (!f.isFile) return false
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)
        val mime = m.mime.ifBlank { c.contentResolver.getType(uri) } ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            c.startActivity(Intent.createChooser(intent, m.fileName.ifBlank { "Súbor" }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun compressPhoto(c: Context, uri: Uri): ByteArray {
        val cr = c.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth.coerceAtLeast(1)
        val h = bounds.outHeight.coerceAtLeast(1)
        val max = 1600
        var sample = 1
        while (w / sample > max || h / sample > max) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("decode")
        val scaled = if (bmp.width > max || bmp.height > max) {
            val ratio = minOf(max.toFloat() / bmp.width, max.toFloat() / bmp.height)
            val out = ThumbnailUtils.extractThumbnail(bmp, (bmp.width * ratio).toInt().coerceAtLeast(1), (bmp.height * ratio).toInt().coerceAtLeast(1))
            if (out !== bmp) bmp.recycle()
            out
        } else bmp
        val out = java.io.ByteArrayOutputStream()
        var q = 82
        do {
            out.reset()
            scaled.compress(Bitmap.CompressFormat.JPEG, q, out)
            q -= 8
        } while (out.size() > Chat.MAX_ATTACH && q >= 40)
        if (scaled !== bmp) scaled.recycle() else bmp.recycle()
        val bytes = out.toByteArray()
        if (bytes.size > Chat.MAX_ATTACH) throw IllegalStateException("too big")
        return bytes
    }

    private fun queryName(c: Context, uri: Uri): String? {
        val cursor = c.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null) ?: return uri.lastPathSegment
        cursor.use {
            if (!it.moveToFirst()) return uri.lastPathSegment
            val i = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            return if (i >= 0) it.getString(i) else uri.lastPathSegment
        }
    }

    private fun notifyIncoming(c: Context, m: ChatMessage) {
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL, "Chat", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Správy medzi členmi domácnosti. Na zamknutej obrazovke je viditeľný text."
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
        val open = PendingIntent.getActivity(
            c, 0,
            Intent(c, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_PAGE, "chat")
                .putExtra(EXTRA_THREAD, m.thread)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = m.senderName.ifBlank { displayName(c, m.senderId) }
        val body = Chat.previewBody(m.kind, m.text, m.fileName)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPublicVersion(
                NotificationCompat.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_chat)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build(),
            )
            .build()
        try {
            nm.notify(NOTIFY_ID, n)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun userAgent(): String = "Domov/${BuildConfig.VERSION_NAME}"

    const val EXTRA_THREAD = "chatThread"

    fun extrasSnapshot(c: Context): Pair<org.json.JSONArray?, org.json.JSONObject?> {
        val msgs = ChatStore.exportMessages(c)
        val (files, _) = ChatStore.exportFiles(c)
        if (msgs.length() == 0) return null to null
        return msgs to files
    }

    fun extrasApply(c: Context, chat: org.json.JSONArray?, files: org.json.JSONObject?, replace: Boolean) {
        if (chat == null && files == null) {
            if (replace) ChatStore.importBackup(c, org.json.JSONArray(), org.json.JSONObject(), true)
            return
        }
        ChatStore.importBackup(c, chat, files, replace)
    }
}

class ChatJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            try {
                if (ChatEngine.active(app)) {
                    kotlinx.coroutines.runBlocking { ChatEngine.flush(app) }
                }
            } catch (e: Exception) {
                Log.w("Domov", "chat job", e)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
