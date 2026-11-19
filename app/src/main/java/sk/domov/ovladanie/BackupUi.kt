@file:OptIn(ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import android.app.Activity
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun dateText(ms: Long): String = if (ms <= 0) "" else SimpleDateFormat("d. M. yyyy HH:mm", Locale.getDefault()).format(Date(ms))

private const val MIN_PASSWORD = 8

private fun passwordProblem(a: String, b: String): String? = when {
    a.length < MIN_PASSWORD -> "Heslo musí mať aspoň $MIN_PASSWORD znakov."
    a != b -> "Heslá sa nezhodujú."
    else -> null
}

private fun errorText(e: Throwable): String = when (e) {
    is BackupException -> e.message ?: "Záloha sa nedá obnoviť."
    is SecurityException -> "K súboru nie je prístup."
    else -> "Nepodarilo sa to (${e.message ?: e.javaClass.simpleName})."
}

/** Záloha a obnovenie všetkých nastavení appky. */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }

    // vytvorenie
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var withSecrets by remember { mutableStateOf(false) }
    var pwError by remember { mutableStateOf<String?>(null) }

    // obnovenie
    var fileData by remember { mutableStateOf<ByteArray?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    var restorePw by remember { mutableStateOf("") }
    var restoreError by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<BackupPayload?>(null) }
    var mode by remember { mutableStateOf(RestoreMode.REPLACE) }
    var conflicts by remember { mutableStateOf<List<DeviceConflict>?>(null) }
    val useBackup = remember { mutableStateMapOf<String, Boolean>() }
    var confirmUndo by remember { mutableStateOf(false) }
    var hasSafety by remember { mutableStateOf(runCatching { BackupManager.applier(context).hasSafety() }.getOrDefault(false)) }
    val safetyAt = remember(hasSafety) { runCatching { BackupManager.applier(context).safetyTime() }.getOrDefault(0L) }

    // automatická záloha
    var auto by remember { mutableStateOf(BackupManager.auto(context)) }
    var autoSetup by remember { mutableStateOf(false) }
    var autoFolder by remember { mutableStateOf<Uri?>(null) }
    var autoPw1 by remember { mutableStateOf("") }
    var autoPw2 by remember { mutableStateOf("") }
    var autoSecrets by remember { mutableStateOf(false) }
    var autoError by remember { mutableStateOf<String?>(null) }

    fun work(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        scope.launch(LogOnError) {
            try {
                block()
            } finally {
                busy = null
            }
        }
    }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) work("Šifrujem zálohu…") {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    val data = BackupManager.create(context, pw1.toCharArray(), withSecrets)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) } ?: error("Súbor sa nedá zapísať")
                }.exceptionOrNull()
            }
            if (err == null) {
                message = "Záloha je uložená" to "Súbor ${displayName(context, uri)} je zašifrovaný tvojím heslom. Bez hesla sa nedá obnoviť – zapamätaj si ho."
                pw1 = ""; pw2 = ""
            } else {
                message = "Zálohu sa nepodarilo uložiť" to errorText(err)
            }
        }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work("Načítavam súbor…") {
            val r = withContext(Dispatchers.IO) { runCatching { BackupManager.read(context, uri) } }
            r.onSuccess { data ->
                if (!BackupCrypto.looksLikeBackup(data)) {
                    message = "Toto nie je záloha" to "Vyber súbor zálohy appky Domov (domov-zaloha-….domov). Súbor zariadení .json sa načítava cez Import zariadení."
                } else {
                    fileData = data; restorePw = ""; restoreError = null; askPassword = true
                }
            }.onFailure { message = "Súbor sa nedá otvoriť" to errorText(it) }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) autoFolder = uri }

    fun doRestore() {
        val p = preview ?: return
        val choices = useBackup.toMap()
        preview = null; conflicts = null
        work("Obnovujem…") {
            val err = withContext(Dispatchers.IO) { runCatching { BackupManager.restore(context, p, mode, choices) }.exceptionOrNull() }
            if (err == null) {
                (context as? Activity)?.let { BackupManager.restart(it) }
            } else {
                message = "Obnovenie sa nepodarilo" to errorText(err) + "\n\nNastavenia ostali bez zmeny alebo sa dokončia pri ďalšom spustení appky."
                hasSafety = runCatching { BackupManager.applier(context).hasSafety() }.getOrDefault(false)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Záloha a obnovenie") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Sym.arrowBack, contentDescription = "Späť") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Jeden zašifrovaný súbor so všetkým: zariadenia (aj local keys, popis hodnôt, názvy, skryté hodnoty, zdroje svetla, skupiny a poradie), " +
                    "scéna Noc, príchod a odchod, domácnosť a jej kľúč, upozornenia fritézy a nastavenia appky.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionLabel("Vytvoriť zálohu")
            DomovCard {
                PasswordField("Heslo zálohy", pw1) { pw1 = it; pwError = null }
                PasswordField("Heslo znova", pw2) { pw2 = it; pwError = null }
                Text(
                    "Heslo sa nikam neukladá. Bez neho zálohu neobnovíš ani ty.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CheckRow("Pridať aj prihlasovacie údaje Tuya cloud a Honeywell", checked = withSecrets, titleStyle = MaterialTheme.typography.bodyMedium) { withSecrets = it }
                if (withSecrets) {
                    Text(
                        "Pozor: kto pozná heslo zálohy, dostane aj prístup k tvojmu Tuya cloudu a kúreniu Honeywell. Zvoľ silné heslo a súbor nikomu neposielaj.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                pwError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = {
                        pwError = passwordProblem(pw1, pw2)
                        if (pwError == null) saveFile.launch(BackupManager.todayName())
                    },
                    enabled = busy == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Uložiť do súboru") }
                OutlinedButton(
                    onClick = {
                        pwError = passwordProblem(pw1, pw2)
                        if (pwError == null) work("Šifrujem zálohu…") {
                            val r = withContext(Dispatchers.IO) { runCatching { BackupManager.shareIntent(context, BackupManager.create(context, pw1.toCharArray(), withSecrets)) } }
                            r.onSuccess { runCatching { context.startActivity(it) } }.onFailure { message = "Zálohu sa nepodarilo vytvoriť" to errorText(it) }
                        }
                    },
                    enabled = busy == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Zdieľať") }
            }

            SectionLabel("Obnoviť zo zálohy")
            DomovCard {
                Text(
                    "Vyber súbor .domov a zadaj heslo. Pred obnovením appka ukáže, čo záloha obsahuje, a sama si uloží terajší stav, aby sa dal vrátiť.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { openFile.launch(arrayOf("*/*")) }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) { Text("Vybrať súbor zálohy") }
                if (hasSafety) {
                    Text("Stav pred posledným obnovením: ${dateText(safetyAt)}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { confirmUndo = true }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) { Text("Vrátiť späť") }
                }
            }

            SectionLabel("Automatická záloha")
            DomovCard {
                SettingRow(
                    icon = Sym.folder,
                    title = "Raz týždenne do priečinka",
                    caption = if (auto.enabled) "Priečinok: ${auto.folderName}. Ponechá posledné 4 zálohy." else "Vypnutá",
                    trailing = {
                        DomovSwitch(checked = auto.enabled, onCheckedChange = { on ->
                            if (on) {
                                autoFolder = null; autoPw1 = ""; autoPw2 = ""; autoSecrets = false; autoError = null; autoSetup = true
                            } else {
                                BackupManager.disableAuto(context); auto = BackupManager.auto(context)
                            }
                        })
                    },
                )
                if (auto.enabled) {
                    Text(
                        if (auto.last > 0) "Posledná: ${dateText(auto.last)} (${auto.lastFile})" else "Zatiaľ žiadna – prvá sa uloží do týždňa (alebo hneď tlačidlom).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (auto.error.isNotBlank()) Text(auto.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = {
                        work("Zálohujem…") {
                            val err = withContext(Dispatchers.IO) { BackupManager.runAuto(context) }
                            auto = BackupManager.auto(context)
                            Toast.makeText(context, err ?: "Záloha uložená", Toast.LENGTH_LONG).show()
                        }
                    }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) { Text("Zálohovať teraz") }
                }
            }
            Text(
                "Import a export zariadení (.json) ostávajú v menu ako doteraz.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    busy?.let { label ->
        DomovDialog(onDismiss = {}, title = null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator()
                Text(label)
            }
        }
    }

    message?.let { (title, text) ->
        DomovAlert(
            onDismissRequest = { message = null },
            icon = { Icon(Sym.info, null) },
            title = { Text(title) },
            text = { Column() { Text(text) } },
            confirmButton = { DialogPrimaryButton(onClick = { message = null }) { Text("OK") } },
        )
    }

    if (askPassword && busy == null) {
        DomovAlert(
            onDismissRequest = { askPassword = false; fileData = null },
            icon = { Icon(Sym.lock, null) },
            title = { Text("Heslo zálohy") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PasswordField("Heslo", restorePw) { restorePw = it; restoreError = null }
                    restoreError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                DialogPrimaryButton(enabled = restorePw.isNotEmpty(), onClick = {
                    val data = fileData ?: return@DialogPrimaryButton
                    work("Overujem heslo…") {
                        val r = withContext(Dispatchers.IO) { runCatching { BackupPayload.open(data, restorePw.toCharArray()) } }
                        r.onSuccess { p ->
                            askPassword = false; fileData = null; restorePw = ""
                            mode = RestoreMode.REPLACE; useBackup.clear(); preview = p
                        }.onFailure { e ->
                            if (e is BackupException && e.kind == BackupException.Kind.WRONG_PASSWORD) {
                                restoreError = e.message
                            } else {
                                askPassword = false; fileData = null
                                message = "Zálohu nejde obnoviť" to errorText(e)
                            }
                        }
                    }
                }) { Text("Otvoriť") }
            },
            dismissButton = { DialogTextButton(onClick = { askPassword = false; fileData = null }) { Text("Zrušiť") } },
        )
    }

    preview?.let { p ->
        if (conflicts == null && busy == null) {
            val s = remember(p) { Backup.summary(p) }
            DomovAlert(
                onDismissRequest = { preview = null },
                icon = { Icon(Sym.refresh, null) },
                title = { Text("Obnoviť zálohu?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Vytvorená ${dateText(s.created)}" + if (s.appVersion.isNotBlank()) " vo verzii ${s.appVersion}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        s.lines().forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                        ModeRow("Nahradiť všetko", "Nastavenia v telefóne nahradí zálohou.", mode == RestoreMode.REPLACE) { mode = RestoreMode.REPLACE }
                        ModeRow("Zlúčiť", "Pridá zariadenia zo zálohy, existujúce nechá. Pri rozdieloch sa spýta.", mode == RestoreMode.MERGE) { mode = RestoreMode.MERGE }
                        Text(
                            "Terajší stav sa uloží, obnovenie pôjde vrátiť (Vrátiť späť). Appka sa potom reštartuje.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    DialogPrimaryButton(onClick = {
                        if (mode == RestoreMode.MERGE) {
                            val c = runCatching {
                                val cur = BackupManager.applier(context).snapshot(includeSecrets = false)
                                Backup.conflicts(cur, p)
                            }.getOrDefault(emptyList())
                            if (c.isEmpty()) doRestore() else conflicts = c
                        } else {
                            doRestore()
                        }
                    }) { Text("Obnoviť") }
                },
                dismissButton = { DialogTextButton(onClick = { preview = null }) { Text("Zrušiť") } },
            )
        }
    }

    conflicts?.let { list ->
        if (busy == null) {
            DomovAlert(
                onDismissRequest = { conflicts = null },
                icon = { Icon(Sym.warning, null) },
                title = { Text("Rozdielne zariadenia") },
                text = {
                    Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Tieto zariadenia sú v telefóne aj v zálohe, ale líšia sa. Zaškrtni tie, pri ktorých chceš údaje zo zálohy; ostatné ostanú, ako sú.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        list.forEach { c ->
                            val on = useBackup[c.backupId] == true
                            CheckRow(
                                title = c.currentName + if (c.name != c.currentName) " (v zálohe „${c.name}“)" else "",
                                caption = "Iné: " + c.differences.joinToString(", "),
                                checked = on,
                                titleStyle = MaterialTheme.typography.bodyMedium,
                            ) { useBackup[c.backupId] = it }
                        }
                    }
                },
                confirmButton = { DialogPrimaryButton(onClick = { doRestore() }) { Text("Zlúčiť") } },
                dismissButton = { DialogTextButton(onClick = { conflicts = null }) { Text("Späť") } },
            )
        }
    }

    if (confirmUndo) {
        DomovAlert(
            onDismissRequest = { confirmUndo = false },
            icon = { Icon(Sym.arrowBack, null) },
            title = { Text("Vrátiť späť?") },
            text = { Column() { Text("Nastavenia sa vrátia do stavu pred posledným obnovením zo zálohy (${dateText(safetyAt)}). Appka sa potom reštartuje.") } },
            confirmButton = {
                DialogPrimaryButton(onClick = {
                    confirmUndo = false
                    work("Vraciam späť…") {
                        val r = withContext(Dispatchers.IO) { runCatching { BackupManager.undo(context) } }
                        if (r.getOrDefault(false)) (context as? Activity)?.let { BackupManager.restart(it) }
                        else message = "Vrátiť späť sa nepodarilo" to (r.exceptionOrNull()?.let { errorText(it) } ?: "Uložený stav sa nenašiel.")
                    }
                }) { Text("Vrátiť späť") }
            },
            dismissButton = { DialogTextButton(onClick = { confirmUndo = false }) { Text("Zrušiť") } },
        )
    }

    if (autoSetup) {
        DomovAlert(
            onDismissRequest = { autoSetup = false },
            icon = { Icon(Sym.schedule, null) },
            title = { Text("Automatická záloha") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Raz týždenne uloží zálohu do vybraného priečinka (napr. Stiahnuté alebo Google Disk) a nechá posledné 4.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text(autoFolder?.let { "Priečinok: " + folderName(it) } ?: "Vybrať priečinok")
                    }
                    PasswordField("Heslo zálohy", autoPw1) { autoPw1 = it; autoError = null }
                    PasswordField("Heslo znova", autoPw2) { autoPw2 = it; autoError = null }
                    Text("Heslo sa uloží v telefóne zašifrované (Android Keystore), aby sa záloha dala vytvoriť bez teba.", style = MaterialTheme.typography.bodySmall)
                    CheckRow("Aj prihlasovacie údaje Tuya cloud a Honeywell", checked = autoSecrets, titleStyle = MaterialTheme.typography.bodyMedium) { autoSecrets = it }
                    if (autoSecrets) Text("Pozor: kto má súbor a heslo, dostane prístup k cloudu a kúreniu.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    autoError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                DialogPrimaryButton(onClick = {
                    val folder = autoFolder
                    autoError = if (folder == null) "Vyber priečinok." else passwordProblem(autoPw1, autoPw2)
                    if (autoError == null && folder != null) {
                        runCatching { BackupManager.enableAuto(context, folder, folderName(folder), autoPw1.toCharArray(), autoSecrets) }
                            .onFailure { autoError = errorText(it) }
                        if (autoError == null) {
                            autoSetup = false; autoPw1 = ""; autoPw2 = ""
                            auto = BackupManager.auto(context)
                        }
                    }
                }) { Text("Zapnúť") }
            },
            dismissButton = { DialogTextButton(onClick = { autoSetup = false }) { Text("Zrušiť") } },
        )
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: BackupManager.todayName()

private fun folderName(uri: Uri): String = runCatching {
    DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifBlank { "hlavný priečinok" }
}.getOrDefault(uri.lastPathSegment ?: "")

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    if (LocalInDomovDialog.current) {
        DomovTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ModeRow(title: String, caption: String, selected: Boolean, onClick: () -> Unit) {
    DialogOption(title = title, caption = caption, selected = selected, onClick = onClick)
}
