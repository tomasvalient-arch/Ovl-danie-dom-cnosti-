package sk.domov.ovladanie

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
private fun Hint(text: String, warning: Boolean = false) {
    if (warning) NoticeCard(text, NoticeKind.Warning) else InfoHint(text)
}

/** Pošle nainštalovanú appku ako súbor cez systémové Zdieľať (Quick Share / Nearby Share, Bluetooth, e-mail…). */
private suspend fun shareApkFile(context: android.content.Context) {
    val uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "apk").apply { mkdirs() }
        val out = File(dir, "Domov.apk")
        File(context.applicationInfo.sourceDir).inputStream().use { i -> out.outputStream().use { i.copyTo(it) } }
        FileProvider.getUriForFile(context, context.packageName + ".files", out)
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/vnd.android.package-archive")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Poslať appku Domov"))
}

// ------------------------------------------------------------------ zdieľanie (prvý telefón)

@Composable
internal fun ShareAppScreen(vm: HomeViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices by vm.devices.collectAsState()
    val heat by vm.heat.ui.collectAsState()
    val cloud by vm.cloud.collectAsState()
    var selected by remember { mutableStateOf(devices.map { it.id }.toSet()) }
    var household by remember { mutableStateOf(true) }
    var shareHeat by remember { mutableStateOf(false) }
    var shareCloud by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }

    if (showQr) {
        ShareQrScreen(
            vm = vm,
            chosen = devices.filter { it.id in selected },
            household = household,
            shareHeat = shareHeat && heat.configured,
            shareCloud = shareCloud && cloud != null,
            onBack = { showQr = false },
            onDone = onBack,
        )
        return
    }

    PageScaffold("Zdieľať appku", onBack) {
        Text(
            "Druhý telefón naskenuje QR kód fotoaparátom, stiahne si appku priamo z tohto telefónu cez WiFi a potom si v nej " +
                "stiahne len to, čo tu vyberieš.",
            style = MaterialTheme.typography.bodyMedium,
        )
        DomovCard(padding = 12.dp, spacing = 4.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Zariadenia, ktoré bude môcť ovládať", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    selected = if (selected.size == devices.size) emptySet() else devices.map { it.id }.toSet()
                }) { Text(if (selected.size == devices.size) "Nič" else "Všetko") }
            }
            if (devices.isEmpty()) Hint("V appke zatiaľ nie sú žiadne zariadenia.")
            devices.forEach { d ->
                CheckRow(d.name, d.group.ifBlank { null }, d.id in selected) { on -> selected = if (on) selected + d.id else selected - d.id }
            }
        }
        DomovCard(padding = 12.dp, spacing = 4.dp) {
            Text("Ďalej zdieľať", style = MaterialTheme.typography.titleSmall)
            CheckRow(
                "Spoločná automatika (domácnosť)",
                "Telefóny si dajú vedieť, kto je doma – svetlá nezhasnú, kým je doma niekto iný.",
                household,
            ) { household = it }
            if (heat.configured) {
                CheckRow("Kúrenie Honeywell", "Dostane tvoj e-mail a heslo Honeywell Home (rovnaké práva ako ty).", shareHeat) { shareHeat = it }
            }
            if (cloud != null) {
                CheckRow("Tuya cloud", "Dostane tvoje prístupové kľúče Tuya – vypínanie mimo domu aj zariadení, ktoré tu nevyberieš.", shareCloud) { shareCloud = it }
            }
        }
        DomovCard(padding = 14.dp, spacing = 6.dp) {
            Text("Čo treba vedieť", style = MaterialTheme.typography.titleSmall)
            Hint("• Oba telefóny musia byť na rovnakej WiFi. QR kód platí 10 minút a zariadenia sa dajú stiahnuť len raz.")
            Hint("• Zdieľa sa local key vybraných zariadení – kto ho má, ovláda zariadenie úplne (aj bez appky). Obmedziť sa dá len tým, " +
                "že ostatné zariadenia sa nezdieľajú. Odobrať prístup sa dá len novým spárovaním zariadenia v Tuya appke.", warning = true)
            Hint("• Tuya cloud ani Honeywell sa bez zaškrtnutia nezdieľajú.")
        }
        Button(
            enabled = selected.isNotEmpty() || household || (shareHeat && heat.configured) || (shareCloud && cloud != null),
            onClick = { showQr = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Zobraziť QR kód") }
        FilledTonalButton(
            onClick = { scope.launch { runCatching { shareApkFile(context) }.onFailure { Toast.makeText(context, "Súbor sa nepodarilo pripraviť", Toast.LENGTH_SHORT).show() } } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Poslať len inštalačný súbor (Quick Share, Bluetooth…)") }
        Hint("Inštalačný súbor neobsahuje žiadne zariadenia – tie si druhý telefón potom stiahne cez QR kód.")
    }
}

@Composable
private fun ShareQrScreen(
    vm: HomeViewModel,
    chosen: List<Device>,
    household: Boolean,
    shareHeat: Boolean,
    shareCloud: Boolean,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var gen by remember { mutableIntStateOf(0) }
    var link by remember { mutableStateOf<ShareLink?>(null) }
    var offer by remember { mutableStateOf<ShareOffer?>(null) }
    var server by remember { mutableStateOf<AppShareServer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableIntStateOf(0) }
    var downloads by remember { mutableIntStateOf(0) }
    var joined by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val main = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // server žije len kým je táto obrazovka otvorená (a do vypršania kódu)
    DisposableEffect(gen) {
        onDispose { server?.stop(); server = null }
    }
    LaunchedEffect(gen) {
        error = null; link = null; joined = null; opened = 0; downloads = 0
        val wifi = wifiAddress(context)
        if (wifi == null) {
            error = "Telefón nie je pripojený na WiFi. Pripoj oba telefóny na domácu WiFi a skús znova."
            return@LaunchedEffect
        }
        val o = ShareOffer.create(System.currentTimeMillis())
        try {
            val s = withContext(Dispatchers.IO) {
                val heatSaved = if (shareHeat) EvohomeStore(context).load() else null
                val payload = SharePayload(
                    from = HouseholdStore.name(context),
                    devicesJson = exportDevicesJson(chosen, if (household) HouseholdStore.shareKey(context) else null),
                    cloud = if (shareCloud) vm.cloud.value else null,
                    heatEmail = heatSaved?.email,
                    heatPassword = heatSaved?.password,
                )
                val enc = AppShareCrypto.encrypt(payload.toJson(), o.key, o.token)
                AppShareServer(File(context.applicationInfo.sourceDir), o, enc, HouseholdStore.name(context)) { ev ->
                    main.post {
                        when (ev) {
                            ShareEvent.PageOpened -> opened++
                            ShareEvent.ApkDownloaded -> downloads++
                            is ShareEvent.Joined -> {
                                joined = ev.name
                                PresenceStore.log(context, "Zdieľanie appky: telefón „${ev.name}“ dostal ${Scenes.devicesText(chosen.size)}.")
                            }
                        }
                    }
                }.also { srv -> val port = srv.start(wifi.address); link = ShareLink(wifi.address.hostAddress ?: "", port, o.token, o.key) }
            }
            server = s
            offer = o
        } catch (e: Exception) {
            error = "Server sa nepodarilo spustiť: ${e.message ?: e.javaClass.simpleName}"
        }
    }
    LaunchedEffect(gen) {
        while (true) {
            now = System.currentTimeMillis()
            val o = offer
            if (o != null && !o.valid(now)) { server?.stop(); server = null }
            delay(1000)
        }
    }

    PageScaffold("Zdieľať appku", onBack) {
        val o = offer
        val l = link
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Button(onClick = { gen++ }) { Text("Skúsiť znova") }
            return@PageScaffold
        }
        if (o == null || l == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            return@PageScaffold
        }
        val left = ((o.expiresAt - now) / 1000).coerceAtLeast(0)
        if (left == 0L) {
            DomovCard {
                Text("QR kód vypršal", style = MaterialTheme.typography.titleMedium)
                Hint("Server je vypnutý. Vytvor nový kód, ak ho druhý telefón ešte potrebuje.")
                Button(onClick = { gen++ }) { Text("Nový QR kód") }
            }
            return@PageScaffold
        }
        Text("Na druhom telefóne:", style = MaterialTheme.typography.titleSmall)
        Hint("1. Nemá appku: otvor Fotoaparát, namier na QR kód a otvor odkaz – stiahne a nainštaluje si appku podľa návodu na stránke.")
        Hint("2. V appke Domov: Pripojiť sa k domácnosti → Naskenovať QR kód – ten istý kód.")
        val qr = remember(l) { runCatching { qrBitmap(l.url(), 720) }.getOrNull() }
        Box(
            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            qr?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "QR kód na zdieľanie appky", modifier = Modifier.size(280.dp)) }
        }
        Text("Platí ešte ${left / 60}:${"%02d".format(left % 60)} · ${l.host}:${l.port}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        DomovCard(padding = 14.dp, spacing = 4.dp) {
            Text("Stav", style = MaterialTheme.typography.titleSmall)
            Hint(if (opened > 0) "Stránka otvorená ${opened}×" else "Čakám, kým druhý telefón otvorí odkaz…")
            if (downloads > 0) Hint("Appka stiahnutá ${downloads}×")
            joined?.let {
                Text("Telefón „$it“ sa pripojil a dostal ${Scenes.devicesText(chosen.size)}.", color = Domov.colors.good)
            }
            Hint("Zdieľa sa: ${Scenes.devicesText(chosen.size)}" +
                (if (household) ", spoločná automatika" else "") +
                (if (shareHeat) ", kúrenie Honeywell" else "") +
                (if (shareCloud) ", Tuya cloud" else ""))
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(if (joined != null) "Hotovo" else "Zavrieť a vypnúť zdieľanie") }
    }
}

// ------------------------------------------------------------------ pripojenie (druhý telefón)

@Composable
internal fun JoinHouseholdScreen(vm: HomeViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(HouseholdStore.customName(context) ?: "") }
    var linkText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<List<String>?>(null) }

    fun apply(p: SharePayload): List<String> {
        val lines = ArrayList<String>()
        if (name.isNotBlank()) HouseholdStore.setName(context, name)
        val list = runCatching { parseTinytuyaJson(p.devicesJson) }.getOrDefault(emptyList())
        if (list.isNotEmpty()) vm.importDevices(list)
        lines += if (list.isEmpty()) "Žiadne zariadenia" else "Zariadenia (${list.size}): " + list.joinToString(", ") { it.name }
        val key = HouseholdCrypto.parseExport(p.devicesJson)
        if (key != null) {
            HouseholdStore.adopt(context, key)
            lines += "Tento telefón je v domácnosti – automatika vie, kto je doma."
        }
        p.cloud?.let { c ->
            if (vm.cloud.value == null) { vm.saveCloud(c); lines += "Tuya cloud nastavený." } else lines += "Tuya cloud už bol nastavený – ponechaný."
        }
        if (p.heatEmail != null && p.heatPassword != null) {
            if (!vm.heat.ui.value.configured) { vm.heat.login(p.heatEmail, p.heatPassword); lines += "Kúrenie Honeywell: prihlasujem…" }
            else lines += "Kúrenie Honeywell už bolo nastavené – ponechané."
        }
        PresenceStore.log(context, "Pripojené k domácnosti od „${p.from}“: ${Scenes.devicesText(list.size)}.")
        PresenceService.sync(context)
        return lines
    }

    fun connect(text: String) {
        val link = ShareLink.parse(text)
        if (link == null) { error = "Toto nie je QR kód zo Zdieľať appku v appke Domov."; return }
        error = null
        busy = true
        scope.launch {
            try {
                val me = name.trim().ifBlank { HouseholdStore.name(context) }
                val p = withContext(Dispatchers.IO) { AppShareClient.fetch(context, link, me) }
                result = listOf("Od telefónu „${p.from}“:") + apply(p)
            } catch (e: AppShareClient.ShareException) {
                error = e.message
            } catch (e: Exception) {
                error = "Pripojenie zlyhalo (${e.javaClass.simpleName}). Ste na rovnakej WiFi?"
            }
            busy = false
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { connect(it) } }

    PageScaffold("Pripojiť sa k domácnosti", onBack) {
        val done = result
        if (done != null) {
            DomovCard {
                Text("Hotovo", style = MaterialTheme.typography.titleMedium, color = Domov.colors.good)
                done.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Hint("Zariadenia ovládaš na domácej WiFi. Automatiku príchod/odchod zapneš v Nastavenia › Automatika.")
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Pokračovať") }
            }
            return@PageScaffold
        }
        Text(
            "Na telefóne, ktorý už appku Domov má, otvor Nastavenia → Domácnosť → Zdieľať appku a vyber, čo môžeš ovládať. Potom tu naskenuj " +
                "QR kód, ktorý sa ti tam ukáže. Oba telefóny musia byť na rovnakej WiFi.",
            style = MaterialTheme.typography.bodyMedium,
        )
        DomovTextField(
            value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Meno tohto telefónu") }, placeholder = { Text("napr. Jana") },
        )
        Button(
            enabled = !busy,
            onClick = {
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Naskenuj QR kód zo Zdieľať appku na druhom telefóne")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Naskenovať QR kód") }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.width(1.dp))
        Text("Kamera nefunguje?", style = MaterialTheme.typography.titleSmall)
        Hint("Vlož odkaz z QR kódu (začína http:// a obsahuje #k=).")
        DomovTextField(
            value = linkText, onValueChange = { linkText = it; error = null }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Odkaz") },
        )
        FilledTonalButton(enabled = !busy && linkText.isNotBlank(), onClick = { connect(linkText) }, modifier = Modifier.fillMaxWidth()) { Text("Pripojiť") }
    }
}
