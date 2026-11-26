package sk.domov.ovladanie

import android.content.Context
import java.io.File
import java.security.KeyStore

/**
 * 1.42.0: funkcia „Výdavky z bankových notifikácií“ (1.41.0) a tlačidlo Shizuku (1.41.1) boli zrušené.
 * Po aktualizácii z 1.41.x sa zmažú ich zvyšky: uložené platby a pravidlá (domov_expenses), zachytené notifikácie
 * (domov_expenses_log) a kľúč v Android Keystore, ktorým boli šifrované. Ostatné údaje sa nedotknú.
 * Beží pri každom štarte, ale keď nič z toho neexistuje, len skontroluje dva súbory.
 */
object LegacyCleanup {
    private val PREFS = listOf("domov_expenses", "domov_expenses_log")
    private const val KEY_ALIAS = "domov_expenses"

    fun run(c: Context) {
        val app = c.applicationContext
        val dir = File(app.applicationInfo.dataDir, "shared_prefs")
        var found = false
        for (name in PREFS) {
            if (File(dir, "$name.xml").exists()) {
                found = true
                runCatching { app.deleteSharedPreferences(name) }
            }
        }
        if (found) {
            runCatching {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
            }
        }
    }
}
