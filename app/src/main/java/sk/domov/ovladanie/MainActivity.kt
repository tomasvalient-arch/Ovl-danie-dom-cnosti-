package sk.domov.ovladanie

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // dokončiť prerušené obnovenie zo zálohy skôr, než sa čokoľvek načíta
        BackupManager.recover(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_RESTORED, false) == true) {
            // po obnovení zo zálohy: automatiku a widgety spustiť nanovo s novými nastaveniami
            runCatching { PresenceService.sync(this) }
            runCatching { DomovWidgetProvider.redrawAll(this) }
            runCatching { BackupManager.schedule(this) }
            Toast.makeText(this, "Obnovené zo zálohy", Toast.LENGTH_LONG).show()
        }
        // ukážkové dáta na snímky obrazovky: adb shell am start -n sk.domov.ovladanie/.MainActivity --ez demo true
        if (savedInstanceState == null && intent?.getBooleanExtra("demo", false) == true) vm.startDemo()
        setContent {
            HomeTheme { HomeScreen(vm) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Automatika musí bežať vždy, keď je zapnutá: po aktualizácii appky ju niektoré telefóny (napr. Xiaomi bez
        // „Automatického spúšťania“) nespustia, kým sa appka neotvorí. Na popredí to Android dovolí vždy.
        if (!PresenceService.isRunning) runCatching { PresenceService.sync(this) }
        vm.refreshAll()
        vm.weather.refresh()
    }

    companion object {
        const val EXTRA_RESTORED = "restored"
    }
}
