@file:OptIn(ExperimentalMaterial3Api::class)

package sk.domov.ovladanie

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

private const val URL_IOT = "https://iot.tuya.com"
private const val URL_CLOUD = "https://iot.tuya.com/cloud/"
private const val URL_SERVICES = "https://iot.tuya.com/cloud/products"

/** Otvorí stránku v prehliadači. */
internal fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "Nenašiel sa prehliadač. Adresa: $url", Toast.LENGTH_LONG).show()
    }
}

private fun pasteText(context: Context): String {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
    return if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString().trim() else ""
}

/** Obrazovka s horným panelom a šípkou späť (pre nastavenia, návod a pomoc). */
@Composable
internal fun PageScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Sym.arrowBack, contentDescription = "Späť")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

// ------------------------------------------------------------ nastavenia Tuya cloudu

@Composable
fun CloudSettingsScreen(vm: HomeViewModel, onBack: () -> Unit, onGuide: () -> Unit) {
    val context = LocalContext.current
    val saved by vm.cloud.collectAsState()
    val test by vm.cloudTest.collectAsState()
    var accessId by remember { mutableStateOf(saved?.accessId ?: "") }
    var secret by remember { mutableStateOf(saved?.accessSecret ?: "") }
    var dc by remember { mutableStateOf(saved?.dataCenter ?: DataCenter.EU) }
    var showSecret by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val current = CloudConfig(accessId.trim(), secret.trim(), dc)

    DisposableEffect(Unit) { onDispose { vm.clearCloudTest() } }

    PageScaffold("Tuya cloud", onBack) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Nepovinné. Tuya cloud appka použije len pri pridávaní a synchronizácii zariadení: stiahne " +
                    "local keys, názvy a popis hodnôt. Zariadenia sa ďalej ovládajú len lokálne cez WiFi. Ručné pridanie " +
                    "(tlačidlo +) a import devices.json fungujú aj bez cloudu.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedButton(onClick = onGuide, modifier = Modifier.fillMaxWidth()) { Text("Ako získať Access ID a Secret") }

        OutlinedTextField(
            value = accessId,
            onValueChange = { accessId = it; vm.clearCloudTest() },
            label = { Text("Access ID (Client ID)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { accessId = pasteText(context); vm.clearCloudTest() }) { Text("Vložiť Access ID zo schránky") }
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it; vm.clearCloudTest() },
            label = { Text("Access Secret (Client Secret)") },
            singleLine = true,
            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { secret = pasteText(context); vm.clearCloudTest() }) { Text("Vložiť Secret zo schránky") }
            TextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "Skryť" else "Zobraziť") }
        }

        Text("Dátové centrum", style = MaterialTheme.typography.titleMedium)
        Text(
            "Musí byť rovnaké ako pri Cloud projekte a ako región účtu v Tuya / Smart Life appke. Na Slovensku a v Česku je to väčšinou Stredná Európa.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCenter.entries.forEach { d ->
                FilterChip(selected = dc == d, onClick = { dc = d; vm.clearCloudTest() }, label = { Text(d.title) })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.testCloud(current) }, enabled = current.isComplete && test !is HomeViewModel.CloudTest.Running) {
                Text("Otestovať pripojenie")
            }
            Button(
                onClick = {
                    vm.saveCloud(current)
                    Toast.makeText(context, "Uložené", Toast.LENGTH_SHORT).show()
                    onBack()
                },
                enabled = current.isComplete,
            ) { Text("Uložiť") }
        }
        when (val t = test) {
            is HomeViewModel.CloudTest.Running -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            is HomeViewModel.CloudTest.Done -> Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (t.ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(t.message, modifier = Modifier.padding(16.dp))
            }
            else -> {}
        }

        Text(
            "Údaje sú v telefóne zašifrované kľúčom v Android Keystore. Nie sú súčasťou exportu zariadení a appka ich " +
                "posiela len na server Tuya vo vybranom dátovom centre.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (saved != null) {
            TextButton(onClick = { confirmClear = true }) { Text("Odstrániť údaje z telefónu") }
        }
    }

    if (confirmClear) {
        DomovAlert(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Sym.delete, null) },
            danger = true,
            title = { Text("Odstrániť údaje Tuya cloudu?") },
            text = { Text("Zariadenia v appke zostanú a budú fungovať ďalej. Len sa už nebudú dať sťahovať z cloudu.") },
            confirmButton = {
                DialogPrimaryButton(onClick = {
                    confirmClear = false
                    vm.clearCloud()
                    accessId = ""
                    secret = ""
                }) { Text("Odstrániť") }
            },
            dismissButton = { DialogTextButton(onClick = { confirmClear = false }) { Text("Zrušiť") } },
        )
    }
}

// ------------------------------------------------------------ sprievodca

@Composable
private fun Step(number: Int, title: String, text: String, buttons: @Composable () -> Unit = {}) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$number. $title", style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            buttons()
        }
    }
}

/** Návod krok za krokom: ako získať Access ID a Access Secret na iot.tuya.com. */
@Composable
fun CloudGuideScreen(onBack: () -> Unit, onEnterKeys: () -> Unit) {
    val context = LocalContext.current
    PageScaffold("Ako získať Access ID a Secret", onBack) {
        Text(
            "Jednorazovo, asi 10 minút, najlepšie na počítači. Tuya cloud je nepovinný: bez neho môžeš zariadenia pridať " +
                "ručne alebo importom devices.json.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Step(
            1, "Registrácia",
            "Otvor iot.tuya.com a zaregistruj sa (zadarmo). Pri type účtu vyber súkromnú osobu / Individual developer. " +
                "Môžeš použiť iný e-mail ako v Tuya appke.",
        ) { OutlinedButton(onClick = { openUrl(context, URL_IOT) }) { Text("Otvoriť iot.tuya.com") } }
        Step(
            2, "Cloud projekt",
            "Vľavo vyber Cloud → Development → Create Cloud Project. Názov ľubovoľný (napr. Domov), Industry: Smart Home, " +
                "Development Method: Smart Home. Data Center vyber podľa regiónu účtu v Tuya / Smart Life appke – na " +
                "Slovensku a v Česku Central Europe. Rovnaké dátové centrum potom zvolíš aj v appke.",
        ) { OutlinedButton(onClick = { openUrl(context, URL_CLOUD) }) { Text("Otvoriť Cloud projekty") } }
        Step(
            3, "Služby IoT Core a Authorization",
            "Pri vytváraní projektu (alebo neskôr v projekte na záložke Service API → Go to Authorize) pridaj služby " +
                "IoT Core a Authorization Token Management. Bez nich cloud vráti chybu „not subscribed“.",
        )
        Step(
            4, "Prepojenie účtu appky",
            "V projekte otvor záložku Devices → Link App Account (Link Tuya App Account) → Add App Account. Zobrazí sa QR kód. " +
                "V mobile otvor Tuya alebo Smart Life appku, ťukni na Ja (Me) a vpravo hore na ikonu skenera, naskenuj QR kód " +
                "a potvrď prihlásenie. Ak sa pýta na spôsob, zvoľ automatické prepojenie (Automatic Link). Po chvíli uvidíš " +
                "svoje zariadenia v zozname Devices.",
        )
        Step(
            5, "Access ID a Access Secret",
            "Na záložke Overview projektu nájdeš Authorization Key: Access ID / Client ID a Access Secret / Client Secret. " +
                "Skopíruj ich (napr. si ich pošli do mobilu) a vlož do appky. Potom ťukni na Otestovať pripojenie.",
        ) { Button(onClick = onEnterKeys) { Text("Zadať Access ID a Secret") } }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pozor: služba IoT Core vyprší", style = MaterialTheme.typography.titleMedium)
                Text(
                    "IoT Core je skúšobná služba (Trial) – prvý mesiac zadarmo, potom sa dá zadarmo predĺžiť o 6 mesiacov a znova " +
                        "každých 6 mesiacov: Cloud → Cloud Services → IoT Core → View Details → Extend Trial Period " +
                        "(vyplníš krátky formulár, schválenie trvá od pár minút po jeden pracovný deň). Ak už vypršala, " +
                        "v projekte zvoľ Upgrade IoT Core Plan → Trial edition a potom Extend Trial Period.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Keď služba vyprší, appka ovláda zariadenia ďalej normálne (lokálne). Nefunguje len sťahovanie z cloudu.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { openUrl(context, URL_SERVICES) }) { Text("Otvoriť Cloud Services") }
            }
        }
    }
}

// ------------------------------------------------------------ pomoc

@Composable
private fun HelpSection(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun HelpScreen(onBack: () -> Unit, onGuide: () -> Unit) {
    PageScaffold("Pomoc", onBack) {
        HelpSection(
            "Ako appka funguje",
            "Domov ovláda Tuya zariadenia priamo cez domácu WiFi, bez Tuya appky a bez cloudu. Na každé zariadenie potrebuje " +
                "IP adresu, Device ID, local key a verziu protokolu. Mimo domácej WiFi ovládanie nefunguje.",
        )
        HelpSection(
            "Pridanie zariadení",
            "• Pridať (alebo Nastavenia → Hľadať zariadenia v sieti): appka nájde IP adresu, Device ID a verziu. Ak máš nastavený Tuya cloud, " +
                "ťuknutím na Pridať dotiahne aj local key, názov a popis hodnôt.\n" +
                "• Tlačidlo +: všetko zadáš ručne.\n" +
                "• Nastavenia → Import zariadení: devices.json z tinytuya wizardu alebo záloha z tejto appky.\n" +
                "• Nastavenia → Synchronizovať s Tuya cloudom: aktualizuje local keys a popisy a ponúkne zariadenia, ktoré v appke chýbajú.",
        )
        OutlinedButton(onClick = onGuide) { Text("Ako nastaviť Tuya cloud") }
        HelpSection(
            "Hodnoty a ich názvy",
            "V detaile zariadenia je zoznam hodnôt. Ak appka pozná popis z Tuya cloudu, hodnoty majú slovenské názvy a " +
                "jednotky. Inak sa volajú „Hodnota 20“ a pod. Cez ⋮ pri hodnote ju premenuješ, skryješ alebo nastavíš ako " +
                "hlavný vypínač. Vlastný názov má vždy prednosť. Úplne dole tlačidlo Zmeny hodnôt ukáže, ktoré hodnoty sa " +
                "menia – prepni niečo na zariadení a uvidíš, čo ktorá robí.",
        )
        HelpSection(
            "Hlavná obrazovka a scény",
            "• Všetko vypnúť: po potvrdení vypne to isté, čo sa vypína pri odchode (predvolene svetlá a zásuvky, spotrebiče len " +
                "ak ich tam vyberieš). Mimo WiFi to skúsi cez Tuya cloud.\n" +
                "• Príchod: hneď zapne zariadenia vybrané pre príchod.\n" +
                "• Noc: vybrané svetlá nechá stlmené na teplú bielu, ostatné vypne. Nastavíš cez Noc → Upraviť.\n" +
                "• Skupiny: podrž dlaždicu – posunieš ju v poradí alebo ju presunieš do vlastnej skupiny (napr. miestnosti).",
        )
        HelpSection(
            "Widgety na plochu",
            "Podrž prst na ploche → Widgety → Domov. Widget zapína a vypína hlavný vypínač jedného zariadenia. Nedávaj naň " +
                "ohrievač – widget nevie zobraziť potvrdenie.",
        )
        HelpSection(
            "Príchod a odchod",
            "Karta Automatika → Príchod a odchod. Doma na WiFi ťukni na „Toto je moja domáca WiFi“, vyber, čo sa má pri príchode zapnúť a pri " +
                "odchode vypnúť, a zapni automatiku. Keď sa telefón pripojí na domácu WiFi (a predtým bol mimo domu), zapnú sa " +
                "vybrané zariadenia – voliteľne len po zotmení. Keď sa odpojí a do nastaveného času (predvolene 3 min) sa " +
                "nevráti, vybrané zariadenia sa vypnú cez Tuya cloud. Krátky výpadok WiFi nerobí nič.\n" +
                "• Bez Tuya cloudu sa mimo domu zariadenia vypnúť nedajú – appka ťa len upozorní. Vtedy pomôže Poistka: kým si " +
                "doma, appka zariadeniam priebežne nastavuje odpočet, a keď odídeš, samy sa vypnú. Ak telefón doma zaspí alebo " +
                "sa odpojí od WiFi, môžu zhasnúť aj keď si doma.\n" +
                "• Appka beží na pozadí s tichou notifikáciou a spustí sa aj po reštarte telefónu. Vypni jej optimalizáciu " +
                "batérie, inak ju Android môže uspať.\n" +
                "• Povolenia: notifikácie (výsledky a trvalá notifikácia), výnimka z optimalizácie batérie, a len ak chceš " +
                "„po západe slnka“, jednorazovo približná poloha. Poloha na pozadí sa nepoužíva – domácu sieť appka pozná podľa " +
                "routera a podľa toho, že jej odpovedajú tvoje zariadenia.\n" +
                "• Fritézy, ohrievače a termostaty sa pri príchode nikdy nezapínajú.\n" +
                "• Ak telefón doma vypína WiFi pri zamknutí obrazovky (niektoré úsporné režimy), automatika si bude myslieť, že si odišiel.",
        )
        HelpSection(
            "Druhý telefón v domácnosti (napr. priateľka)",
            "Aby odchod jedného nezhasol svetlá tomu, kto zostal doma:\n" +
                "1. Na druhý telefón nainštaluj rovnaké Domov.apk.\n" +
                "2. Na prvom telefóne: Nastavenia → Export zariadení → Zdieľať / Uložiť a pošli súbor na druhý telefón. Export obsahuje " +
                "zariadenia aj kľúč domácnosti. (Ak už druhý telefón zariadenia má, stačí Príchod a odchod → Pozvať do domácnosti.)\n" +
                "3. Na druhom telefóne: Nastavenia → Import zariadení → vyber súbor (alebo vlož pozvánku v Príchod a odchod → Pridať sa do domácnosti).\n" +
                "4. Na oboch telefónoch nastav v Príchod a odchod Meno tohto telefónu (napr. Tomáš, Priateľka).\n" +
                "5. Na druhom telefóne doma na WiFi ťukni Toto je moja domáca WiFi, vyber zariadenia a zapni automatiku. Povoľ " +
                "notifikácie a vypni optimalizáciu batérie. Automatika musí byť zapnutá na oboch telefónoch – inak sa telefón doma neozýva.\n" +
                "6. Do minúty sa v časti Kto je doma objaví druhý telefón.\n" +
                "Ak odídete obaja naraz, svetlá ostanú svietiť a príde notifikácia s tlačidlom Aj tak zhasnúť (potrebuje Tuya cloud).",
        )
        HelpSection(
            "Verzia protokolu",
            "Zariadenia používajú verziu 3.3, 3.4 alebo 3.5. Hľadanie v sieti ju zistí samo. Ak zariadenie neodpovedá, skús " +
                "v nastaveniach zariadenia (ozubené koliesko) inú verziu.",
        )
        HelpSection(
            "Keď zariadenie neodpovedá",
            "Skontroluj, či je telefón na domácej WiFi a zariadenie zapnuté. Appka sama skúsi nájsť novú IP adresu. Ak " +
                "nepomôže, skontroluj local key (zmení sa po opätovnom spárovaní – stačí Synchronizovať s Tuya cloudom) a " +
                "verziu protokolu. V routeri je dobré nastaviť zariadeniam pevnú IP adresu.",
        )
        HelpSection(
            "Súkromie",
            "Zariadenia a local keys sú len v telefóne. Export zariadení obsahuje local keys, nikomu ho neposielaj. Údaje Tuya " +
                "cloudu sú zašifrované a do exportu sa nedávajú.",
        )
    }
}

// ------------------------------------------------------------ synchronizácia

@Composable
fun SyncDialog(vm: HomeViewModel, onDismiss: () -> Unit) {
    val sync by vm.sync.collectAsState()
    var zigbeeWarning by remember { mutableStateOf<String?>(null) }
    DomovAlert(
        onDismissRequest = onDismiss,
        icon = { Icon(Sym.sync, null) },
        title = { Text("Synchronizácia s Tuya cloudom") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sync.running) {
                    Text("Sťahujem zariadenia z Tuya cloudu a hľadám ich v sieti…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                sync.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (sync.done && sync.error == null) {
                    Text(
                        if (sync.updated.isEmpty()) "Zariadenia v appke sú aktuálne."
                        else "Aktualizované (local key alebo popis hodnôt): " + sync.updated.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (sync.missing.isNotEmpty()) {
                        Text("V appke chýbajú:", style = MaterialTheme.typography.titleSmall)
                        sync.missing.forEach { c ->
                            DialogCard {
                                    Text(c.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                    val ip = vm.syncIp(c.id)
                                    Text(
                                        (c.productName.ifBlank { c.category }.let { if (it.isBlank()) "" else "$it · " }) +
                                            (ip?.let { "IP $it" } ?: "v sieti sa nenašlo (IP doplní neskôr)"),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Button(onClick = { vm.addFromSync(c) }) { Text("Pridať") }
                            }
                        }
                        if (sync.missing.size > 1) {
                            OutlinedButton(onClick = { sync.missing.forEach { vm.addFromSync(it) } }) { Text("Pridať všetky") }
                        }
                    } else {
                        Text("Všetky zariadenia z účtu už v appke sú.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (sync.zigbee.isNotEmpty()) {
                        Text("Zigbee zariadenia (cez bránu):", style = MaterialTheme.typography.titleSmall)
                        sync.zigbee.forEach { c ->
                            DialogCard {
                                Text(c.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    c.productName.ifBlank { c.category }.ifBlank { "Zigbee zariadenie" },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(onClick = { zigbeeWarning = vm.addZigbeeFromSync(c) }) { Text("Pridať") }
                            }
                        }
                        if (sync.zigbee.size > 1) {
                            OutlinedButton(onClick = { zigbeeWarning = sync.zigbee.mapNotNull { vm.addZigbeeFromSync(it) }.firstOrNull() }) {
                                Text("Pridať všetky Zigbee")
                            }
                        }
                        zigbeeWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Text(
                            "Zigbee zariadenia sa ovládajú lokálne cez bránu, ktorá musí byť v domácej sieti. Senzory (dvere, pohyb, " +
                                "teplota) appka zobrazuje; upozornenia pri zmene prídu v ďalšej verzii.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (sync.subCount > 0) {
                        Text(
                            "${sync.subCount} zariadení cez bránu appka nevie pridať (nepodarilo sa zistiť bránu alebo ID zariadenia v cloude).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { DialogPrimaryButton(onClick = onDismiss) { Text("Zavrieť") } },
    )
}

/** Pridanie nájdeného zariadenia bez nastaveného cloudu: návod alebo ručné zadanie local key. */
@Composable
fun NoCloudDialog(onGuide: () -> Unit, onManual: () -> Unit, onDismiss: () -> Unit) {
    DomovAlert(
        onDismissRequest = onDismiss,
        icon = { Icon(Sym.lock, null) },
        title = { Text("Chýba local key") },
        text = {
            Text(
                "Zariadenie do siete neposiela local key. Môžeš ho zadať ručne (alebo naimportovať devices.json), alebo si " +
                    "nastaviť Tuya cloud – potom ho appka dotiahne sama jedným ťuknutím."
            )
        },
        confirmButton = { DialogPrimaryButton(onClick = onManual) { Text("Zadať ručne") } },
        dismissButton = { DialogTextButton(onClick = onGuide) { Text("Nastaviť Tuya cloud") } },
    )
}
