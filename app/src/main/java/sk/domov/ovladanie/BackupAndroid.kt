package sk.domov.ovladanie

import android.app.Activity
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Calendar

/** SharedPreferences appky: zápis naraz (commit) po súboroch, prihlasovacie údaje cez šifrované úložiská. */
class AndroidPrefsSink(context: Context) : PrefsSink {
    private val app = context.applicationContext

    override fun read(file: String): Map<String, Any> {
        val all = app.getSharedPreferences(file, Context.MODE_PRIVATE).all.filterValues { it != null }.mapValues { it.value!! }
        if (file != BackupSpec.DEVICES_FILE) return all
        // v telefóne sú local keys zašifrované Keystore kľúčom; záloha ich má (ako doteraz) nešifrované vo vnútri šifrovaného súboru
        return all.mapValues { (k, v) -> if (k == "devices" && v is String) DeviceStore.plainJson(v) else v }
    }

    override fun write(file: String, values0: Map<String, Any>) {
        val p = app.getSharedPreferences(file, Context.MODE_PRIVATE)
        val values = if (file != BackupSpec.DEVICES_FILE) values0
        else values0.mapValues { (k, v) -> if (k == "devices" && v is String) DeviceStore.sealedJson(v) else v }
        val e = p.edit()
        // obnovený zoznam zariadení nahrádza aj poškodený zoznam – blokovanie ukladania sa zruší
        if (file == BackupSpec.DEVICES_FILE && values.containsKey("devices")) e.remove(DeviceStore.KEY_BROKEN)
        p.all.keys.filter { BackupSpec.included(file, it) && it !in values }.forEach { e.remove(it) }
        for ((k, v) in values) {
            when (v) {
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Long -> e.putLong(k, v)
                is Int -> e.putInt(k, v)
                is Float -> e.putFloat(k, v)
                is Set<*> -> e.putStringSet(k, v.map { it.toString() }.toSet())
                else -> e.putString(k, v.toString())
            }
        }
        if (!e.commit()) throw IOException("Nastavenia sa nepodarilo zapísať ($file)")
    }

    override fun discard(file: String) {
        val xml = File(File(app.applicationInfo.dataDir, "shared_prefs"), "$file.xml")
        if (!xml.exists()) return
        val p = app.getSharedPreferences(file, Context.MODE_PRIVATE)
        val e = p.edit()
        p.all.keys.filter { BackupSpec.included(file, it) }.forEach { e.remove(it) }
        if (!e.commit()) throw IOException("Nastavenia sa nepodarilo zapísať ($file)")
        if (p.all.isEmpty()) {
            if (!app.deleteSharedPreferences(file) && xml.exists()) xml.delete()
        }
    }

    override fun readSecrets(): Pair<JSONObject?, JSONObject?> {
        val c = runCatching { CloudStore(app).load() }.getOrNull()?.takeIf { it.isComplete }
            ?.let { JSONObject().put("id", it.accessId).put("secret", it.accessSecret).put("dc", it.dataCenter.code) }
        val e = runCatching { EvohomeStore(app).load() }.getOrNull()?.let { JSONObject().put("email", it.email).put("password", it.password) }
        return c to e
    }

    override fun writeSecrets(cloud: JSONObject?, evohome: JSONObject?) {
        val okC = CloudStore(app).saveNow(cloud?.let { CloudConfig(it.getString("id"), it.getString("secret"), DataCenter.of(it.optString("dc"))) })
        val okE = EvohomeStore(app).saveNow(evohome?.optString("email"), evohome?.optString("password"))
        if (!okC || !okE) throw IOException("Prihlasovacie údaje sa nepodarilo zapísať")
    }
}

/** Šifrovanie interných súborov zálohy (poistná záloha, denník, heslo automatickej zálohy) kľúčom v Android Keystore. */
object KeystoreSealer : Sealer {
    override fun seal(data: ByteArray): ByteArray = SecretBox.seal(SecretBox.BACKUP, data)

    override fun open(data: ByteArray): ByteArray = SecretBox.open(SecretBox.BACKUP, data)
}

/** Nastavenia automatickej zálohy (samy nie sú súčasťou zálohy). */
data class AutoBackup(
    val enabled: Boolean = false,
    val folder: String = "",
    val folderName: String = "",
    val includeSecrets: Boolean = false,
    val last: Long = 0L,
    val lastFile: String = "",
    val error: String = "",
)

object BackupManager {
    private fun prefs(context: Context) = context.getSharedPreferences("domov_backup_auto", Context.MODE_PRIVATE)

    fun applier(context: Context) = BackupApplier(
        File(context.noBackupFilesDir, "zaloha"),
        AndroidPrefsSink(context),
        KeystoreSealer,
        extras = object : BackupExtras {
            override fun snapshot() = ChatEngine.extrasSnapshot(context)
            override fun apply(chat: org.json.JSONArray?, files: org.json.JSONObject?, replace: Boolean) =
                ChatEngine.extrasApply(context, chat, files, replace)
        },
    )

    private fun version(context: Context): Pair<String, Int> = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        (pi.versionName ?: "") to (if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode)
    }.getOrDefault("" to 0)

    fun todayName(): String {
        val c = Calendar.getInstance()
        return BackupApplier.fileName(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Vytvorí zašifrovaný obsah súboru zálohy (trvá chvíľu – volať mimo hlavného vlákna). */
    fun create(context: Context, password: CharArray, includeSecrets: Boolean): ByteArray {
        val (name, code) = version(context)
        return BackupPayload.seal(applier(context).snapshot(includeSecrets, name, code), password)
    }

    /** Súbor do priečinka cache na zdieľanie (FileProvider). */
    fun shareIntent(context: Context, data: ByteArray): Intent {
        val dir = File(context.cacheDir, "zaloha").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, todayName())
        BackupApplier.writeAtomic(f, data)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Domov – záloha").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Zdieľať zálohu")
    }

    fun read(context: Context, uri: Uri): ByteArray {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > BackupCrypto.MAX_SIZE) throw BackupCrypto.corrupted()
            }
            return out.toByteArray()
        }
        throw IOException("Súbor sa nedá otvoriť")
    }

    /** Obnoví zálohu: zastaví automatiku, zapíše všetko naraz (s poistnou zálohou). Appku treba potom reštartovať [restart]. */
    fun restore(context: Context, payload: BackupPayload, mode: RestoreMode, useBackup: Map<String, Boolean>) {
        runCatching { context.stopService(Intent(context, PresenceService::class.java)) }
        applier(context).restore(payload, mode, useBackup)
        PresenceStore.log(context, if (mode == RestoreMode.REPLACE) "Obnovené zo zálohy (nahradiť všetko)." else "Obnovené zo zálohy (zlúčené).")
    }

    fun undo(context: Context): Boolean {
        runCatching { context.stopService(Intent(context, PresenceService::class.java)) }
        return applier(context).undo().also { if (it) PresenceStore.log(context, "Obnovenie zo zálohy vrátené späť.") }
    }

    /** Dokončí prerušené obnovenie – volá sa pri štarte appky, nikdy nespadne. */
    fun recover(context: Context) {
        try {
            if (applier(context).recover()) PresenceStore.log(context, "Prerušené obnovenie zo zálohy bolo dokončené.")
        } catch (e: Exception) {
            // nič – obnova sa skúsi znova pri ďalšom štarte
        }
    }

    /** Čistý reštart appky (nový proces), aby sa všetko načítalo z obnovených nastavení. */
    fun restart(activity: Activity) {
        val i = Intent(activity, RestartActivity::class.java).putExtra(RestartActivity.EXTRA_PID, Process.myPid())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(i)
        activity.finishAffinity()
    }

    // ---- automatická záloha raz týždenne

    fun auto(context: Context): AutoBackup {
        val p = prefs(context)
        return AutoBackup(
            enabled = p.getBoolean("enabled", false),
            folder = p.getString("folder", "") ?: "",
            folderName = p.getString("folderName", "") ?: "",
            includeSecrets = p.getBoolean("secrets", false),
            last = p.getLong("last", 0L),
            lastFile = p.getString("lastFile", "") ?: "",
            error = p.getString("error", "") ?: "",
        )
    }

    fun enableAuto(context: Context, folder: Uri, folderName: String, password: CharArray, includeSecrets: Boolean) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val sealed = Base64.encodeToString(KeystoreSealer.seal(String(password).toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs(context).edit().putBoolean("enabled", true).putString("folder", folder.toString()).putString("folderName", folderName)
            .putString("pw", sealed).putBoolean("secrets", includeSecrets).putString("error", "").apply()
        schedule(context)
    }

    fun disableAuto(context: Context) {
        val a = auto(context)
        prefs(context).edit().putBoolean("enabled", false).remove("pw").apply()
        runCatching { context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
        if (a.folder.isNotBlank()) runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(a.folder), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    const val JOB_ID = 4711

    fun schedule(context: Context) {
        if (!auto(context).enabled) return
        runCatching {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, BackupJobService::class.java))
                    .setPeriodic(7L * 24 * 3600 * 1000, 24L * 3600 * 1000)
                    .setRequiresBatteryNotLow(true)
                    .setPersisted(true)
                    .build(),
            )
        }
    }

    /** Uloží zálohu do vybraného priečinka a nechá posledné 4. Vráti chybu (null = OK). */
    fun runAuto(context: Context): String? {
        val a = auto(context)
        if (!a.enabled || a.folder.isBlank()) return "Automatická záloha je vypnutá."
        val err = try {
            val pw = String(KeystoreSealer.open(Base64.decode(prefs(context).getString("pw", "") ?: "", Base64.NO_WRAP)), Charsets.UTF_8)
            val data = create(context, pw.toCharArray(), a.includeSecrets)
            val tree = Uri.parse(a.folder)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val cr = context.contentResolver
            val name = todayName()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            fun list(): Map<String, String> {
                val found = HashMap<String, String>()
                cr.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    while (c.moveToNext()) found[c.getString(1) ?: continue] = c.getString(0)
                }
                return found
            }
            // jedna záloha za deň: dnešný súbor sa prepíše, inak sa vytvorí nový
            val existing = list()[name]?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
            val doc = existing ?: DocumentsContract.createDocument(cr, parent, "application/octet-stream", name) ?: throw IOException("Súbor sa nepodarilo vytvoriť")
            try {
                cr.openOutputStream(doc, "wt")?.use { it.write(data) } ?: throw IOException("Súbor sa nepodarilo zapísať")
            } catch (e: Exception) {
                if (existing == null) runCatching { DocumentsContract.deleteDocument(cr, doc) }
                throw e
            }
            // ponechať posledné 4 zálohy
            val found = list()
            for (old in BackupApplier.toDelete(found.keys.toList(), 4)) {
                runCatching { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, found[old])) }
            }
            prefs(context).edit().putLong("last", System.currentTimeMillis()).putString("lastFile", name).putString("error", "").apply()
            null
        } catch (e: SecurityException) {
            "Appka už nemá prístup k priečinku – vyber ho znova."
        } catch (e: Exception) {
            "Zálohu sa nepodarilo uložiť (${e.message ?: e.javaClass.simpleName})."
        }
        if (err != null) prefs(context).edit().putString("error", err).apply()
        return err
    }
}

/** Týždenná automatická záloha (JobScheduler – bez ďalších knižníc). */
class BackupJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        scope.launch {
            val err = runCatching { BackupManager.runAuto(applicationContext) }.getOrElse { it.message }
            if (err != null) runCatching { PresenceStore.log(applicationContext, "Automatická záloha: $err") }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Pomocná obrazovka v samostatnom procese: ukončí starý proces appky a spustí ju nanovo. */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_RESTORED, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
        Process.killProcess(Process.myPid())
    }

    companion object {
        const val EXTRA_PID = "pid"
    }
}
