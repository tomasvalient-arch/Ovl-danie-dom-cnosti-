package sk.domov.ovladanie

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()
    /** Obrazovka, ktorú treba otvoriť (napr. „shopping“ po ťuknutí na názov widgetu Nákup). */
    private val openPage = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // dokončiť prerušené obnovenie zo zálohy skôr, než sa čokoľvek načíta
        BackupManager.recover(this)
        // zvyšky zrušenej funkcie Výdavky (1.41.x) – zmazať, nič iné sa nemení
        runCatching { LegacyCleanup.run(this) }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_RESTORED, false) == true) {
            // po obnovení zo zálohy: automatiku a widgety spustiť nanovo s novými nastaveniami
            runCatching { PresenceService.sync(this) }
            runCatching { DomovWidgetProvider.redrawAll(this) }
            runCatching { ShoppingWidget.redrawAll(this) }
            runCatching { DnesWidget.redrawAll(this) }
            runCatching { BackupManager.schedule(this) }
            runCatching { HouseSyncEngine.schedule(this) }
            Toast.makeText(this, "Obnovené zo zálohy", Toast.LENGTH_LONG).show()
        }
        // ukážkové dáta na snímky obrazovky: adb shell am start -n sk.domov.ovladanie/.MainActivity --ez demo true
        // (len v ladiacom builde – v release appke ho iná appka nemôže spustiť)
        if (BuildConfig.DEBUG && savedInstanceState == null && intent?.getBooleanExtra("demo", false) == true) vm.startDemo()
        if (savedInstanceState == null) openPage.value = pageFrom(intent)
        setContent {
            HomeTheme { HomeScreen(vm, openPage = openPage.value, onPageOpened = { openPage.value = null }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pageFrom(intent)?.let { openPage.value = it }
    }

    /** Povolené sú len známe obrazovky (appku môže spustiť aj iná appka cez spúšťač). */
    private fun pageFrom(intent: Intent?): String? {
        val page = intent?.getStringExtra(EXTRA_PAGE) ?: return null
        if (page in setOf("shopping", "shopping-mine", "shopping-shared", "events", "events-add", "chat")) {
            val thread = intent.getStringExtra(ChatEngine.EXTRA_THREAD)?.takeIf { it.isNotBlank() }
            return if (page == "chat" && thread != null) "chat:$thread" else page
        }
        return null
    }

    override fun onResume() {
        super.onResume()
        // Automatika musí bežať vždy, keď je zapnutá: po aktualizácii appky ju niektoré telefóny (napr. Xiaomi bez
        // „Automatického spúšťania“) nespustia, kým sa appka neotvorí. Na popredí to Android dovolí vždy.
        if (!PresenceService.isRunning) runCatching { PresenceService.sync(this) }
        vm.refreshAll()
        vm.weather.refresh()
        // upozornenia na udalosti: pošle, čo zmeškal budík, a nastaví ďalší
        EventReminders.check(this)
        // ranný prehľad: obnoví budík (napr. po vynútenom zastavení appky) a dobehne zmeškaný
        MorningBriefAlarm.check(this)
        runCatching { HouseSyncEngine.schedule(this) }
        runCatching { HouseSyncEngine.request(this) }
        runCatching { ChatEngine.schedule(this) }
        runCatching { ChatEngine.startListening(this) }
        runCatching { ChatEngine.request(this) }
    }

    companion object {
        const val EXTRA_RESTORED = "restored"
        const val EXTRA_PAGE = "page"
    }
}
