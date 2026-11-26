package sk.domov.ovladanie

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Trvalá história chatu v SQLite (žiadne automatické mazanie) a stiahnuté prílohy v súkromnom úložisku.
 * Nová databáza pri aktualizácii z 1.64 – existujúce nastavenia a zoznamy ostávajú.
 */
object ChatStore {
    const val PREFS = "domov_chat"
    const val DB_NAME = "domov_chat.db"
    const val DIR = "chat"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun listen(c: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(c).registerOnSharedPreferenceChangeListener(l)

    fun unlisten(c: Context, l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(c).unregisterOnSharedPreferenceChangeListener(l)

    fun revision(c: Context): Long = prefs(c).getLong("rev", 0L)

    fun bump(c: Context) {
        val p = prefs(c)
        p.edit().putLong("rev", p.getLong("rev", 0L) + 1L).putLong("changed_at", System.currentTimeMillis()).apply()
    }

    fun dir(c: Context): File = File(c.filesDir, DIR).also { it.mkdirs() }

    fun fileFor(c: Context, id: String, name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "subor" }
        return File(dir(c), "${id}_$safe")
    }

    @Volatile private var helper: Helper? = null

    @Synchronized
    private fun db(c: Context): SQLiteDatabase {
        val h = helper ?: Helper(c.applicationContext).also { helper = it }
        return h.writableDatabase
    }

    private class Helper(c: Context) : SQLiteOpenHelper(c, DB_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE messages (
                    id TEXT PRIMARY KEY,
                    thread TEXT NOT NULL,
                    sender_id TEXT NOT NULL,
                    sender_name TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    text TEXT,
                    file_name TEXT,
                    file_size INTEGER,
                    mime TEXT,
                    local_path TEXT,
                    created_at INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    mine INTEGER NOT NULL,
                    readers TEXT,
                    incoming_read INTEGER NOT NULL
                )""",
            )
            db.execSQL("CREATE INDEX idx_chat_thread_at ON messages(thread, created_at)")
            db.execSQL("CREATE INDEX idx_chat_status ON messages(status)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    private fun read(c: android.database.Cursor): ChatMessage {
        val readers = c.getString(c.getColumnIndexOrThrow("readers")).orEmpty()
        val list = try {
            val a = JSONArray(readers.ifBlank { "[]" })
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
        return ChatMessage(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            thread = c.getString(c.getColumnIndexOrThrow("thread")),
            senderId = c.getString(c.getColumnIndexOrThrow("sender_id")),
            senderName = c.getString(c.getColumnIndexOrThrow("sender_name")),
            kind = c.getString(c.getColumnIndexOrThrow("kind")),
            text = c.getString(c.getColumnIndexOrThrow("text")).orEmpty(),
            fileName = c.getString(c.getColumnIndexOrThrow("file_name")).orEmpty(),
            fileSize = c.getLong(c.getColumnIndexOrThrow("file_size")),
            mime = c.getString(c.getColumnIndexOrThrow("mime")).orEmpty(),
            localPath = c.getString(c.getColumnIndexOrThrow("local_path")).orEmpty(),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            status = c.getString(c.getColumnIndexOrThrow("status")),
            mine = c.getInt(c.getColumnIndexOrThrow("mine")) != 0,
            readers = list,
            incomingRead = c.getInt(c.getColumnIndexOrThrow("incoming_read")) != 0,
        )
    }

    private fun values(m: ChatMessage): ContentValues = ContentValues().apply {
        put("id", m.id)
        put("thread", m.thread)
        put("sender_id", m.senderId)
        put("sender_name", m.senderName)
        put("kind", m.kind)
        put("text", m.text)
        put("file_name", m.fileName)
        put("file_size", m.fileSize)
        put("mime", m.mime)
        put("local_path", m.localPath)
        put("created_at", m.createdAt)
        put("status", m.status)
        put("mine", if (m.mine) 1 else 0)
        put("readers", JSONArray(m.readers).toString())
        put("incoming_read", if (m.incomingRead) 1 else 0)
    }

    @Synchronized
    fun upsert(c: Context, m: ChatMessage, bump: Boolean = true): Boolean {
        val db = db(c)
        val existed = get(c, m.id) != null
        db.insertWithOnConflict("messages", null, values(m), SQLiteDatabase.CONFLICT_REPLACE)
        if (bump) bump(c)
        return !existed
    }

    fun get(c: Context, id: String): ChatMessage? {
        db(c).rawQuery("SELECT * FROM messages WHERE id = ?", arrayOf(id)).use { cur ->
            return if (cur.moveToFirst()) read(cur) else null
        }
    }

    fun list(c: Context, thread: String, limit: Int = 500): List<ChatMessage> {
        db(c).rawQuery(
            "SELECT * FROM messages WHERE thread = ? ORDER BY created_at ASC, id ASC LIMIT ?",
            arrayOf(thread, limit.toString()),
        ).use { cur ->
            val out = ArrayList<ChatMessage>(cur.count)
            while (cur.moveToNext()) out += read(cur)
            return out
        }
    }

    fun last(c: Context, thread: String): ChatMessage? {
        db(c).rawQuery(
            "SELECT * FROM messages WHERE thread = ? ORDER BY created_at DESC, id DESC LIMIT 1",
            arrayOf(thread),
        ).use { cur -> return if (cur.moveToFirst()) read(cur) else null }
    }

    fun pending(c: Context): List<ChatMessage> {
        db(c).rawQuery(
            "SELECT * FROM messages WHERE mine = 1 AND status IN (?, ?) ORDER BY created_at ASC",
            arrayOf(Chat.STATUS_PENDING, Chat.STATUS_FAILED),
        ).use { cur ->
            val out = ArrayList<ChatMessage>()
            while (cur.moveToNext()) out += read(cur)
            return out
        }
    }

    fun all(c: Context): List<ChatMessage> {
        db(c).rawQuery("SELECT * FROM messages ORDER BY created_at ASC", null).use { cur ->
            val out = ArrayList<ChatMessage>(cur.count)
            while (cur.moveToNext()) out += read(cur)
            return out
        }
    }

    fun unread(c: Context, thread: String? = null): Int {
        val sql = if (thread == null)
            "SELECT COUNT(*) FROM messages WHERE mine = 0 AND incoming_read = 0"
        else
            "SELECT COUNT(*) FROM messages WHERE mine = 0 AND incoming_read = 0 AND thread = ?"
        val args = if (thread == null) null else arrayOf(thread)
        db(c).rawQuery(sql, args).use { cur -> return if (cur.moveToFirst()) cur.getInt(0) else 0 }
    }

    fun markSent(c: Context, id: String) {
        db(c).execSQL("UPDATE messages SET status = ? WHERE id = ?", arrayOf(Chat.STATUS_SENT, id))
        bump(c)
    }

    fun markFailed(c: Context, id: String) {
        db(c).execSQL("UPDATE messages SET status = ? WHERE id = ?", arrayOf(Chat.STATUS_FAILED, id))
        bump(c)
    }

    fun setLocalPath(c: Context, id: String, path: String) {
        db(c).execSQL("UPDATE messages SET local_path = ? WHERE id = ?", arrayOf(path, id))
        bump(c)
    }

    fun addReader(c: Context, id: String, readerId: String): Boolean {
        val m = get(c, id) ?: return false
        if (readerId.isBlank() || readerId in m.readers) return false
        upsert(c, m.copy(readers = m.readers + readerId))
        return true
    }

    /** Označí prichádzajúce v threade ako prečítané. Vráti id správ, pre ktoré treba poslať potvrdenie. */
    fun markThreadRead(c: Context, thread: String): List<ChatMessage> {
        val need = ArrayList<ChatMessage>()
        db(c).rawQuery(
            "SELECT * FROM messages WHERE thread = ? AND mine = 0 AND incoming_read = 0",
            arrayOf(thread),
        ).use { cur ->
            while (cur.moveToNext()) need += read(cur)
        }
        if (need.isEmpty()) return emptyList()
        db(c).execSQL("UPDATE messages SET incoming_read = 1 WHERE thread = ? AND mine = 0 AND incoming_read = 0", arrayOf(thread))
        bump(c)
        return need
    }

    fun cursor(c: Context): String = prefs(c).getString("cursor", "").orEmpty()

    fun setCursor(c: Context, id: String) {
        if (id.isNotBlank()) prefs(c).edit().putString("cursor", id).apply()
    }

    /** Len v pamäti: po zabití procesu alebo zamknutí telefónu nesmie ostať „otvorené“ (inak by neprišlo oznámenie). */
    @Volatile private var openThreadMem: String? = null

    fun openThread(c: Context): String? = openThreadMem

    fun setOpenThread(c: Context, thread: String?) {
        openThreadMem = thread
        prefs(c).edit().remove("open_thread").apply()
    }

    fun exportMessages(c: Context): JSONArray = ChatMessage.listToJson(all(c))

    fun exportFiles(c: Context): Pair<JSONObject, List<String>> {
        val items = all(c).asReversed().mapNotNull { m ->
            val path = m.localPath
            if (path.isBlank()) return@mapNotNull null
            val f = File(path)
            if (!f.isFile) return@mapNotNull null
            m.id to f.readBytes()
        }
        val (pick, skip) = Chat.pickBackupFiles(items)
        return Chat.encodeBackupFiles(pick) to skip
    }

    @Synchronized
    fun importBackup(c: Context, messages: JSONArray?, files: JSONObject?, replace: Boolean) {
        val db = db(c)
        if (replace) {
            dir(c).listFiles()?.forEach { it.delete() }
            db.execSQL("DELETE FROM messages")
        }
        val blobs = Chat.decodeBackupFiles(files)
        for (m in ChatMessage.listFromJson(messages)) {
            var local = m.localPath
            val data = blobs[m.id]
            if (data != null) {
                val dest = fileFor(c, m.id, m.fileName.ifBlank { if (m.kind == Chat.KIND_PHOTO) "fotka.jpg" else "subor" })
                dest.writeBytes(data)
                local = dest.absolutePath
            } else if (local.isNotBlank() && !File(local).isFile) {
                local = ""
            }
            val existing = if (replace) null else get(c, m.id)
            if (existing != null) {
                val readers = (existing.readers + m.readers).distinct()
                val path = existing.localPath.ifBlank { local }
                upsert(c, existing.copy(readers = readers, localPath = path, incomingRead = existing.incomingRead || m.incomingRead), bump = false)
            } else {
                upsert(c, m.copy(localPath = local), bump = false)
            }
        }
        bump(c)
    }
}
