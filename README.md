# Domov – ovládanie Tuya zariadení bez Tuya appky

Android appka, ktorá ovláda tvoje Tuya zariadenia **priamo cez domácu WiFi** (lokálny protokol, TCP port 6668). Nepotrebuje Tuya appku a Tuya cloud je nepovinný (stiahnutie local keys, vypnutie zariadení pri odchode z domu). Mimo domácej WiFi nefunguje a appka to zobrazí upozornením.

Domovská obrazovka ukazuje každé zariadenie ako dlaždicu s vypínačom a stavom. Ťuknutím na dlaždicu sa otvorí detail:

- **Svetlá** (LED pás, žiarovka) sa spoznajú automaticky a majú ovládanie ako v Tuya appke: veľký vypínač, prepínač **Biela / Farba**, jas, teplota bielej a výber farby.
- **Ostatné zariadenia** (napr. fritéza) ukážu zoznam všetkých hodnôt: vypínač, alebo hodnotu s tlačidlom „Zmeniť“. Hodnoty si môžeš premenovať, skryť alebo jednu nastaviť ako hlavný vypínač (cez ⋮ pri hodnote).
- **Bežné typy zariadení** (zásuvka s meraním spotreby, viacnásobná zásuvka, termostat, ohrievač, ventilátor, zvlhčovač, odvlhčovač, žalúzie/rolety) majú vlastné ovládanie a hodnoty sa volajú po slovensky, ak naimportuješ `devices.json` s popisom hodnôt (pozri časť 3).
- **Widgety na plochu**: jedno ťuknutie zapne alebo vypne zariadenie (pozri nižšie).
- **Hľadanie zariadení v sieti**: appka sama nájde IP adresu a verziu protokolu zariadení (pozri nižšie).
- **Príchod a odchod** (od verzie 1.5): po pripojení na domácu WiFi zapne vybrané svetlá, po odchode z domu ich vypne (pozri časť 6). Od verzie 1.6 aj pre **viac telefónov v domácnosti** – odchod jedného nezhasne, keď je druhý doma (časť 7).
- **Nový vzhľad** (od verzie 1.7): tmavá jantárová téma, dlaždice v skupinách, rýchle akcie Všetko vypnúť / Príchod / Noc, nový detail svetla a zásuvky (časť 3a).
- **Kúrenie Honeywell evohome** (od verzie 1.8, nepovinné): teplota a nastavenie zón, režim systému a teplá voda cez cloud Honeywell Home (časť 8).
- **Svetlá s viacerými zdrojmi** (od verzie 1.9): stropné svetlo s hlavným svetlom a farebným podhľadom (napr. BlitzWolf BW-CLT1) – každý zdroj sa zapína samostatne, aj oba naraz. Plus **Technické info / Poslať popis zariadenia** (časť 9).

Podporuje protokoly **3.3, 3.4 a 3.5**.

## 1. Ako získať APK

Hotové `Domov.apk` ti viem zostaviť aj priamo ja, vtedy GitHub nepotrebuješ. Ak si ho chceš zostavovať sám:

1. Vytvor na GitHube nový repozitár (napr. `domov`) a nahraj do neho obsah tohto projektu (vrátane skrytého priečinka `.github`).
2. Po nahratí sa na záložke **Actions** sám spustí build „Zostaviť APK“ (trvá cca 5 minút).
3. Hotové APK nájdeš v časti **Releases** (súbor `Domov.apk`). Stiahni ho do mobilu a nainštaluj (Android si vypýta povolenie inštalovať z neznámych zdrojov).

Ak build zlyhá, otvor v Actions neúspešný beh a pošli mi text chyby – opravím to.

## 2. Ako získať Device ID a local key (jednorazovo)

Zariadenia sa lokálne ovládajú pomocou *local key*, ktorý pozná len Tuya cloud. Sú tri možnosti a všetky fungujú nezávisle:

- **A. Ručne** – tlačidlo **+** v appke (IP adresa, Device ID, local key, verzia). Funguje ako doteraz.
- **B. Import `devices.json`** z tinytuya wizardu (postup nižšie). Funguje ako doteraz.
- **C. Tuya cloud priamo v appke** (nepovinné, od verzie 1.4) – pozri časť **2a**. Appka si local keys stiahne sama a nič iné cloud nepotrebuje.

Postup pre B (tinytuya na PC):

1. Zaregistruj sa na <https://iot.tuya.com> (zadarmo) a vytvor *Cloud project* (Smart Home, dátové centrum podľa regiónu tvojho účtu, v Európe „Central Europe“).
2. V projekte pridaj služby *IoT Core* a *Authorization Token Management* a na záložke *Devices → Link Tuya App Account* prepoj svoj účet z Tuya appky (QR kódom).
3. Na PC spusti:
   ```
   pip install tinytuya
   python -m tinytuya wizard
   ```
   Wizard sa opýta na *Access ID* a *Access Secret* projektu a vytvorí súbor `devices.json` (aj s IP adresami, ak ich nájde v sieti).
   Na otázku **„Download DP Name mappings? (Y/n)“** odpovedz **Y** (alebo len Enter). Do `devices.json` sa tak uloží aj popis hodnôt (*mapping*: názvy, jednotky, rozsahy, možnosti) a appka ho použije (pozri časť 3).
4. V appke ťukni na ⋮ → **Import zariadení** a vlož obsah `devices.json`. Alebo pridaj zariadenie ručne tlačidlom **+**.

## 2a. Tuya cloud v appke (nepovinné)

Ak nechceš spúšťať tinytuya na počítači, appka vie stiahnuť local keys, názvy a popis hodnôt priamo z Tuya cloudu. **Cloud sa používa len pri pridávaní a synchronizácii zariadení**, ovládanie ide stále len lokálne cez WiFi. Bez nastaveného cloudu funguje všetko ako predtým.

1. V appke ťukni na ⋮ → **Tuya cloud (nepovinné)** → **Ako získať Access ID a Secret**. Sprievodca ťa prevedie krok za krokom (s tlačidlami na otvorenie stránok):
   - registrácia na <https://iot.tuya.com>,
   - **Cloud → Development → Create Cloud Project** (Smart Home, dátové centrum podľa regiónu účtu – na Slovensku *Central Europe*),
   - služby **IoT Core** a **Authorization Token Management**,
   - **Devices → Link App Account → Add App Account** a naskenovanie QR kódu v Tuya / Smart Life appke (**Ja → skener** vpravo hore),
   - skopírovanie **Access ID** a **Access Secret** z **Overview**.
2. Vlož Access ID a Secret (tlačidlá **Vložiť zo schránky**), vyber dátové centrum (Stredná Európa, Západná Európa, Amerika, Čína, India; predvolená je Stredná Európa) a ťukni na **Otestovať pripojenie**. Pri chybe appka napíše, čo je zle: zlý Secret, neznáme Access ID / iné dátové centrum, neprepojený účet appky, chýbajúca služba IoT Core / Authorization, vypršaná skúšobná služba alebo zlý čas v telefóne.
3. **Uložiť.** Údaje sú v telefóne zašifrované kľúčom v Android Keystore a **nikdy nie sú v exporte zariadení**.

Potom:
- **Hľadanie v sieti → Pridať** pri novom zariadení stiahne z cloudu local key, názov aj popis hodnôt a zariadenie hneď pridá (jedno ťuknutie). Ak cloud nie je nastavený, appka ponúkne sprievodcu alebo ručné zadanie; ak cloud zlyhá, otvorí ručné zadanie s predvyplnenou IP, Device ID a verziou.
- ⋮ → **Synchronizovať s Tuya cloudom** aktualizuje existujúcim zariadeniam (podľa Device ID) local key (napr. po opätovnom spárovaní), popis hodnôt a kategóriu. Názov, IP, verzia, vlastné názvy hodnôt, skryté hodnoty a hlavný vypínač zostanú. Zároveň ponúkne pridať zariadenia z účtu, ktoré v appke chýbajú – s IP adresou, ak ich súčasne nájde v sieti (inak IP doplní neskôr sama). Zariadenia cez Zigbee/Bluetooth bránu nepridáva, lokálne sa ovládať nedajú.

**Pozor – IoT Core vyprší.** Služba IoT Core je skúšobná: prvý mesiac zadarmo, potom ju treba zadarmo predĺžiť o 6 mesiacov (a znova každých 6 mesiacov): na iot.tuya.com **Cloud → Cloud Services → IoT Core → View Details → Extend Trial Period** a vyplniť krátky formulár (schválenie trvá od pár minút po jeden pracovný deň). Ak už vypršala, v projekte **Upgrade IoT Core Plan → Trial edition** a potom predĺženie. Keď vyprší, appka ovláda zariadenia ďalej normálne, nefunguje len sťahovanie z cloudu (stav z novembra 2026).

### Záloha a obnova (export / import)

V appke ťukni na ⋮ → **Export zariadení**. Exportuje sa JSON so všetkými zariadeniami (aj s názvami hodnôt, skrytými hodnotami, hlavným vypínačom, vypínačmi sekcií svetla a popisom hodnôt z Tuya cloudu).

- **Uložiť do súboru .json** – vyberieš miesto (Stiahnuté, Disk Google a pod.) a appka uloží súbor `domov-zariadenia.json`.
- **Kopírovať** alebo **Zdieľať** – to isté ako text (napr. do Poznámok alebo na Telegram).

Po preinštalovaní appky ťukni na ⋮ → **Import zariadení** a buď **Vybrať súbor .json**, alebo **Vložiť zo schránky**. Appka vie načítať export z tejto appky aj `devices.json` z tinytuya wizardu. Export obsahuje local keys, preto ho nikomu neposielaj.

## 3. Použitie

- Každé zariadenie má IP adresu, Device ID, local key a verziu protokolu. Keď zariadenie neodpovedá, skús inú verziu (3.3, 3.4, 3.5).
- Pri zariadeniach, ktoré appka nepozná ako svetlo, sa hodnoty zobrazia ako „Hodnota 20“, „Hodnota 22“ a pod. Číslo je interné označenie (DP). Skús ich meniť a uvidíš, čo ktorá robí. Potom ich cez ⋮ pri hodnote **premenuješ**, alebo **skryješ**. (Dá sa to aj hromadne v nastaveniach zariadenia, ozubené koliesko hore.)
- **Svetlo s dvoma zdrojmi** (napr. stropné svetlo BW-CLT1: hlavné biele a podhľadové farebné): appka ho pozná podľa hodnôt 22 (jas), 23 (teplota) a 24 (farba) bez hodnoty 21 (režim). V detaile je sekcia **Podhľadové svetlo (farba)** s výberom farby a jasom a sekcia **Hlavné svetlo (biela)** s jasom a teplotou. Samostatné vypínače sekcií appka sama nenájde, ale priradíš ich jedným ťuknutím: v detaile sa hneď rozbalí zoznam všetkých hodnôt. V Tuya appke prepni Colour alebo White a pozri sa, ktorý vypínač v zozname sa zmenil. Ťukni na jeho ⋮ a vyber **Vypínač hlavného svetla (biela)** alebo **Vypínač podhľadového svetla (farba)**. Číslo sa dá zadať aj ručne v nastaveniach zariadenia (ozubené koliesko).
- **Fritéza** (BH-AF2 a podobné): appka ju pozná podľa hodnôt 2 (štart/stop), 3 (režim), 5 (stav), 6 (teplota), 9 (čas). V detaile je stav, čas (1–60 min), teplota (80–200 °C po 5) a tlačidlo **Štart/Stop**. Štart sa pýta na potvrdenie, lebo spúšťa ohrev. Veľké tlačidlo hore, dlaždica aj widget ovládajú len hlavný vypínač (hodnota 1), takže fritézu nikdy nespustia. Režim fritézy (Chips, Cake…) appka od 1.34.3 nezobrazuje ani nemení – ostáva taký, aký fritéza má. **Recepty** (od 1.34): karta pod posuvníkmi. **Nový recept** otvorí editor (názov, čas, teplota; čas a teplota sú predvyplnené z fritézy), takže recepty sa dajú vytvárať a upravovať **aj pri vypnutej fritéze**; nastaviť na fritéze sa dajú, keď je zapnutá. Recept sa ukladá pod tvojím názvom (najviac 20 na fritézu, názov do 30 znakov; rovnaký názov recept prepíše). Ťuknutím na recept sa nastaví čas a teplota po jednom zápise (len to, čo sa líši); **recept fritézu nikdy nespustí**, Štart ostáva zvlášť s potvrdením. Ak fritéza práve varí, appka sa pred zmenou opýta. Aktuálne nastavený recept má fajku. Cez ⋮: Upraviť, Prepísať aktuálnym nastavením, Zmazať (s potvrdením). Recepty sú v telefóne (`domov_recipes`, kľúč `recipes_<id zariadenia>`), idú do zálohy; logika `FryerRecipes` v `Fryer.kt`, úložisko `RecipeStore`, okná `FryerRecipesUi.kt`.
- **Zmeny hodnôt**: úplne dole v detaile je tlačidlo, ktoré ukáže, ktoré hodnoty sa zmenili, kým je detail otvorený. Prepni niečo v Tuya appke (napr. Colour alebo White) a po pár sekundách uvidíš, ktoré číslo sa tým mení. Hodí sa na zistenie, čo ktorá hodnota robí.
- Otvorený detail zariadenia sa sám obnovuje každé 4 sekundy, takže uvidíš aj zmeny spravené v Tuya appke alebo tlačidlom na zariadení.
- **Hlavný vypínač** je hodnota, ktorú ovláda veľké tlačidlo, prepínač na dlaždici a widget. Pri svetle sa nájde sám, pri ostatných zariadeniach je to prvá hodnota typu zapnuté/vypnuté. Ak to nie je tá správna, nastav ju cez ⋮ → „Nastaviť ako hlavný vypínač“.
- IP adresu najľahšie zistíš cez ⋮ → **Hľadať zariadenia v sieti** (pozri časť 4). Inak ju nájdeš v routeri v zozname pripojených zariadení, alebo v Tuya appke v nastaveniach zariadenia (informácie o zariadení). V routeri ju nastav ako **pevnú (DHCP rezervácia)**, inak sa môže zmeniť.
- Local key sa zmení, ak zariadenie znova spáruješ. Vtedy ho treba vytiahnuť nanovo.
- Appka posiela spojenia priamo cez WiFi, takže funguje aj keď má WiFi sieť bez internetu.

### Názvy hodnôt z Tuya popisu

Lokálne zariadenie posiela len čísla hodnôt (DP). Tuya cloud však ku každému zariadeniu pozná popis: kód (napr. `cur_power`), typ, jednotku, rozsah a možnosti. Tinytuya wizard ho uloží do `devices.json` ako `mapping` (ak si na otázku o *DP Name mappings* odpovedal áno).

Keď naimportuješ takýto `devices.json`, v detaile zariadenia:
- hodnoty majú **slovenské názvy** (napr. *Príkon*, *Napätie*, *Nastavená teplota*, *Detská poistka*, *Vypínač 2*). Neznámy kód sa ukáže čitateľne (napr. „Light mode“). Pod názvom je malým písmom číslo hodnoty a kód,
- čísla sa ukazujú so **správnou jednotkou a desatinnou čiarkou** (zariadenie posiela napr. 2304, appka ukáže *230,4 V*),
- číslo sa mení **posuvníkom alebo tlačidlami − / +** v rozsahu a s krokom podľa popisu, výber z možností cez **zoznam** (bežné hodnoty sú preložené, napr. *auto* → *Automaticky*), zapnuté/vypnuté **vypínačom**,
- merania a stavy (príkon, aktuálna teplota, spotreba, porucha…) sú **len na čítanie**.

**Vlastný názov má vždy prednosť** – hodnotu môžeš ďalej premenovať cez ⋮. Zariadenia bez popisu fungujú ako doteraz („Hodnota 20“).

**Ako to získať pre už pridané zariadenia:** spusti znova `python -m tinytuya wizard` (odpovedz **Y** pri *DP Name mappings*) a nový `devices.json` naimportuj cez ⋮ → **Import zariadení**. Appka existujúce zariadenia spáruje podľa Device ID a doplní im popis a kategóriu. Názov zariadenia, tvoje názvy hodnôt, skryté hodnoty a hlavný vypínač zostanú; local key sa prevezme z `devices.json`, IP a verzia len ak ich obsahuje. Ak už máš `devices.json`, v ktorom pri zariadeniach je `"mapping"` (novší tinytuya ho ukladá), wizard netreba spúšťať, stačí súbor znova naimportovať.

### Šablóny pre bežné zariadenia

Appka rozpozná typ zariadenia podľa popisu hodnôt a kategórie z `devices.json`. Bez popisu skúsi typ odhadnúť podľa čísel hodnôt (zásuvka s meraním, viacnásobný vypínač, rolety). Termostat, ohrievač, ventilátor a zvlhčovač sa rozpoznajú len s popisom. Odhadnutý typ appka označí poznámkou.

- **Zásuvka s meraním** (bežne hodnoty 1 vypínač, 9 odpočet, 17 spotreba, 18 prúd v mA, 19 príkon vo W/10, 20 napätie vo V/10): veľký údaj o príkone, napätie, prúd, spotreba v kWh a **malý graf príkonu**, kým je detail otvorený (meria sa pri každom obnovení, asi každé 4 s, ukazuje posledných ~6 minút; po zatvorení detailu sa vymaže). **Odpočet**: po zvolenom čase (15 min – 4 h) zásuvka prepne stav (zapnutá sa vypne).
- **Viacnásobná zásuvka / vypínač** (`switch_1`, `switch_2`, … aj USB): zoznam vypínačov s názvami a tlačidlá *Zapnúť všetky* / *Vypnúť všetky*.
- **Termostat / ohrievač**: aktuálna a nastavená teplota, posuvník teploty v povolenom rozsahu a kroku, režim, stupeň, detská poistka.
- **Ventilátor**: rýchlosť (posuvník alebo stupne), režim, otáčanie.
- **Zvlhčovač / odvlhčovač**: aktuálna a nastavená vlhkosť, režim, rýchlosť.
- **Žalúzie / rolety**: tlačidlá *Otvoriť*, *Zastaviť*, *Zatvoriť* a posuvník polohy v %.

**Bezpečnosť:** zapnutie **ohrievača** sa vždy najprv potvrdzuje (veľké tlačidlo, vypínač v zozname hodnôt aj prepínač na dlaždici). Fritéza sa ovláda ako doteraz (štart s potvrdením). Dlaždica a widget ovládajú len hlavný vypínač. **Widget potvrdenie zobraziť nevie**, preto naň ohrievač nedávaj.

### Pomoc v appke

V menu ⋮ → **Pomoc** je krátky návod (pridávanie zariadení, hodnoty a ich premenovanie, widgety, verzie protokolu, čo robiť, keď zariadenie neodpovedá). Pri prázdnom zozname appka ponúkne všetky spôsoby pridania zariadení vrátane sprievodcu Tuya cloudom.

## 3a. Hlavná obrazovka (od verzie 1.7)

- **Nový vzhľad**: tmavá téma s jantárovým akcentom (svetlá varianta podľa nastavenia telefónu), písmo Inter, ikony Material Symbols. Dlaždice v dvoch stĺpcoch: zapnuté = jantárové, vypnuté = tlmené, offline = červená ikona.
- Hore je **Doma: …** (kto je doma podľa automatiky a domácnosti) a dnešný **východ a západ slnka**. Pod kartou počasia je úzky pás s **dnešným dňom, dátumom a meninami** (druhý riadok: zajtrajšie meniny s dátumom), pod ním úzky pás s **fázou mesiaca** (názov, osvetlenie a dátum najbližšieho splnu, štvrte alebo novu). Pás vieš skryť v Nastavenia → Zobraziť fázu mesiaca. Pod tým karta stavu automatiky Príchod a odchod (ťukni = nastavenia).
- **Rýchle akcie**:
  - **Všetko vypnúť** – po potvrdení vypne rovnaký výber ako pri odchode (predvolene svetlá a zásuvky; spotrebiče len ak ich vyberieš v Príchod a odchod → Pri odchode vypnúť). Na WiFi lokálne, mimo WiFi cez Tuya cloud (ak je nastavený).
  - **Príchod** – hneď zapne zariadenia vybrané pre príchod (aj keď ešte nie je tma).
  - **Noc** – nová scéna: vybrané svetlá ostanú stlmené na x % teplej bielej (napr. lampa pri posteli), ostatné sa vypnú. Predvolene vypne všetky svetlá a zásuvky okrem stlmených. Nastavíš cez Noc → Upraviť alebo ⋮ → Scéna Noc. Funguje na domácej WiFi.
- **Skupiny**: zariadenia sa samy zoradia do skupín Svetlá, Zásuvky, Spotrebiče a Ostatné. **Podrž dlaždicu** a môžeš ju posunúť dopredu/dozadu alebo presunúť do vlastnej skupiny (napr. miestnosť „Obývačka“). Skupina sa uloží aj do exportu (pole `group`).
- **Detail svetla**: veľký svietiaci kruh s jasom (ťuknutie = zapnúť/vypnúť), prepínač Biela/Farba, široké posuvníky jasu a teploty, farebné koliesko a 8 rýchlych farieb, časovač vypnutia (ak ho svetlo má). Svetlo s dvoma zdrojmi má dve sekcie (Hlavné svetlo, Podhľadové svetlo).
- **Detail zásuvky**: veľké číslo príkonu s grafom za posledné minúty, karty Napätie / Prúd / Spotreba, odpočet s rýchlymi voľbami (15 min, 1 h, 2 h, vlastný) a ďalšie nastavenia (stav po výpadku prúdu, kontrolka, detská poistka).

## 4. Hľadanie zariadení v sieti

Tuya zariadenia samy asi každých 5 sekúnd ohlasujú v domácej sieti svoje Device ID, IP adresu a verziu protokolu. Appka tieto správy vie zachytiť, takže IP adresu nemusíš hľadať v routeri.

1. Ťukni na ⋮ → **Hľadať zariadenia v sieti**. Appka asi 12 sekúnd počúva a priebežne ukazuje, čo našla.
2. **Známe zariadenie** (zhoduje sa Device ID): ak má inú IP adresu, appka ju hneď sama opraví. Ak zariadenie hlási inú verziu protokolu, ako máš nastavenú, ukáže tlačidlo **Použiť verziu …**.
3. **Nové zariadenie**: ťukni na **Pridať**. S nastaveným Tuya cloudom (časť 2a) sa zariadenie pridá hneď aj s local key, názvom a popisom hodnôt. Bez cloudu sa Device ID, IP adresa a verzia predvyplnia a doplníš len názov a **local key** (ten zariadenie do siete neposiela, zistíš ho podľa časti 2). Alebo naimportuj `devices.json` cez **Import zariadení** a IP adresy doplní ďalšie hľadanie.

**Automatická oprava IP adresy:** keď zariadenie prestane odpovedať (napr. router mu po reštarte pridelil inú IP adresu), appka na pozadí asi 8 sekúnd počúva v sieti. Ak zariadenie nájde na novej adrese, IP adresu opraví a znova sa pripojí. Robí to najviac raz za minútu a len keď je otvorená.

Ak hľadanie nič nenájde:
- telefón musí byť v tej istej WiFi sieti ako zariadenia (nie v sieti pre hostí),
- router nesmie mať zapnutú izoláciu klientov (*AP isolation*, *client isolation*),
- zariadenia s verziou 3.1 a staršou appka nájde, ale ovládať ich nevie.

Technicky: appka počúva UDP porty 6666 (3.1, nešifrované), 6667 (3.3/3.4 šifrované AES-ECB, 3.5 AES-GCM v rámci 6699) a 7000, a každých 5 sekúnd pošle na port 7000 požiadavku na info, na ktorú odpovedajú niektoré zariadenia 3.5 (rovnako ako Tuya appka a tinytuya). Počas hľadania drží *MulticastLock*, inak by niektoré telefóny broadcasty zahadzovali.

## 5. Widgety na plochu

1. Podrž prst na prázdnom mieste plochy, vyber **Widgety** a nájdi **Domov**. Pridaj widget **Domov – zariadenie** (je veľký 1×1, dá sa aj zväčšiť).
2. Appka sa opýta, ktoré zariadenie widget ovláda. Vyber ho.
3. Ťuknutím na widget sa zariadenie zapne alebo vypne. Žltá dlaždica = zapnuté, tmavá = vypnuté. Na každé zariadenie môžeš dať aj viac widgetov.

Widget si stav obnovuje pri ťuknutí a približne každých 30 minút. Ak zariadenie prepneš fyzickým tlačidlom, widget to môže ukázať s oneskorením. Ťuknutie ale vždy najprv zistí skutočný stav a prepne na opačný. Mimo domácej WiFi ukáže „Bez WiFi“.

Pozor pri spotrebičoch (fritéza): widget prepína hlavný vypínač. Zapnutie nemusí spustiť varenie, ale skontroluj si na zariadení, čo sa po zapnutí deje.

## 6. Príchod a odchod (automatika podľa domácej WiFi)

⋮ → **Príchod a odchod**.

1. Doma na WiFi ťukni na **Toto je moja domáca WiFi**. Appka si zapamätá adresu routera a rozsah adries siete a hneď overí, či jej odpovedajú tvoje zariadenia.
2. **Pri príchode zapnúť** – vyber svetlá / zásuvky. Zapína sa hlavný vypínač (svetlo s posledným jasom a farbou). **Fritézy, ohrievače a termostaty sa tu vôbec neponúkajú** a nikdy sa automaticky nezapnú; neponúkajú sa ani zariadenia, o ktorých appka ešte nevie, čo sú. Voliteľne **Len po zotmení** – podľa západu a východu slnka (počíta sa priamo v telefóne zo zadanej polohy; poloha sa dá zistiť jedným ťuknutím alebo zadať ručne) alebo podľa ručného času od – do (napr. 17:00 – 6:30).
3. **Pri odchode vypnúť** – predvolene všetky svetlá a zásuvky. Čas, po ktorom sa odchod vyhodnotí (predvolene **3 min**).
4. Zapni **Zapnúť príchod a odchod** a povoľ notifikácie. Odporúčam **vypnúť optimalizáciu batérie** (tlačidlo v nastaveniach).

Ako to funguje:

- Appka beží na pozadí ako služba s tichou trvalou notifikáciou (Android 14+: typ `connectedDevice`, kanál s najnižšou dôležitosťou) a sleduje WiFi (`ConnectivityManager.NetworkCallback`). Po reštarte telefónu a po aktualizácii appky sa spustí sama. Od 1.27 sa notifikácia prekreslí len pri zmene stavu (napr. „Si doma“ → „Mimo domu“); ak ju na Androide 14+ odsunieš, už sa sama nevracia. Úplne ju skryť vieš v nastaveniach notifikácií telefónu (kanál „Príchod a odchod (beží na pozadí)“). Služba pritom beží ďalej.
- **Príchod**: telefón sa pripojí na WiFi, ktorá má rovnaký router a rozsah adries ako domáca, a aspoň jedno tvoje zariadenie odpovie so svojím local key (tým je isté, že si doma, nie v inej sieti s rovnakou adresou routera). Ak bol predtým stav „mimo domu“, zapnú sa vybrané zariadenia (lokálne cez WiFi).
- **Odchod**: telefón sa odpojí od domácej WiFi. Ak sa do nastaveného času nevráti, vybrané zariadenia sa vypnú **cez Tuya cloud** (príkaz `POST /v1.0/iot-03/devices/{id}/commands`, ako `tinytuya Cloud.sendcommand`; kód vypínača podľa popisu hodnôt, napr. `switch_led`, `switch_1`). Telefón je už mimo WiFi, takže to ide cez mobilné dáta; bez internetu to appka ešte dvakrát zopakuje.
- **Krátky výpadok** WiFi (návrat pred uplynutím času) nerobí nič – ani nevypne, ani pri návrate znova nezapne.
- Bez Tuya cloudu (alebo keď cloud zlyhá) príde notifikácia **„Nepodarilo sa zhasnúť: …“**. Po úspechu voliteľne „Odišiel si – zhaslo 4 svetlá“.
- **Vyskúšať príchod / Vyskúšať odchod** spustí akciu hneď (bez čakania a bez ohľadu na „len po zotmení“); stav doma / mimo domu sa tým nemení. Dole je história posledných udalostí.

**Poistka bez cloudu** (nepovinná, predvolene vypnutá): pre zariadenia s odpočtom (`countdown`, `countdown_1`, pri svetlách zvyčajne hodnota 26, pri zásuvkách 9). Kým si doma, appka zapnutým zariadeniam každých ~5 min nastaví odpočet napr. 15 min. Keď odídeš, odpočet už nikto neobnoví a zariadenie sa po ňom samo vypne – bez cloudu aj bez internetu. Vypnutým zariadeniam appka svoj odpočet zruší (Tuya odpočet zariadenie *prepne*, vypnuté by sa inak zaplo). Po návrate a pri vypnutí poistky / automatiky sa odpočty zrušia. **Pozor:** ak telefón doma zaspí, vybije sa alebo sa odpojí od WiFi, svetlá po odpočte zhasnú, aj keď si doma.

**Povolenia**, ktoré appka pýta:

| Povolenie | Prečo |
|---|---|
| Notifikácie (Android 13+) | výsledky („Odišiel si…“, „Nepodarilo sa zhasnúť…“) a tichá trvalá notifikácia služby |
| Výnimka z optimalizácie batérie | aby Android službu neuspal a nevypol jej internet |
| Približná poloha – **len jednorazovo a len ak chceš** „po západe slnka“ s automatickým zistením polohy | výpočet západu slnka; súradnice sa dajú zadať aj ručne |
| (bez pýtania) služba v popredí, spustenie po reštarte, stav siete | beh na pozadí |

Poloha na pozadí (`ACCESS_BACKGROUND_LOCATION`) appka **nepotrebuje**: Android síce bez polohy neprezradí názov WiFi (SSID), ale domácu sieť appka spozná podľa routera a odpovedajúcich zariadení.

Obmedzenia: ak telefón doma vypína WiFi pri zamknutí obrazovky (niektoré úsporné režimy), automatika si bude myslieť, že si odišiel. Keď telefón dlho leží (Doze), Android môže kontrolu odchodu o pár minút oneskoriť.

## 7. Viac telefónov v domácnosti (od verzie 1.6)

Každý člen domácnosti má na svojom telefóne vlastnú inštaláciu Domov. Telefóny na domácej WiFi si posielajú UDP správu „som doma“ (broadcast na porte 41766, každých 45 s, po zapnutí obrazovky hneď a pri spánku telefónu budíkom približne každých 5–10 min). Správy sú podpísané **kľúčom domácnosti** (HMAC-SHA256, s časom proti zopakovaniu), takže ich nikto iný v sieti nepodvrhne. Žiadny server ani cloud.

- **Odchod**: po uplynutí času odchodu telefón pozrie, či sa iný člen ozval z domu za posledných **N minút pred odchodom** (predvolene 10, nastaviteľné 3–30). Ak áno, nezhasína a do histórie zapíše „Doma je ešte Priateľka – nezhasínam.“ Rozhoduje posledná známa informácia – pri odchode už telefón nič neprijme ani nepošle.
- Telefón, ktorý je doma, si odchod druhého len zapíše do zoznamu (nič nevypína). Keď neskôr odíde aj on, zhasne (ten prvý sa už dávno neozval).
- **Príchod**: telefón po pripojení pošle otázku „kto je doma“ a ostatné hneď odpovedia. Ak je niekto doma, nič nezapína (nastavenie **Zapnúť aj keď je už niekto doma**, predvolene vypnuté).
- **Poistka s odpočtom**: odpočet obnovuje každý telefón, ktorý je doma; ruší ho len vypnutým zariadeniam (a len svoj), zapnuté nikdy nevypína. Zapni ju na všetkých telefónoch rovnako.
- **Obaja odídete naraz** (do N minút od seba): každý telefón si myslí, že druhý je ešte doma, a svetlá ostanú svietiť. Príde notifikácia s tlačidlom **Aj tak zhasnúť** (vypne cez Tuya cloud). Doplnkovú „poistku“, ktorá by po zhasnutí cudzím telefónom svetlá znova zapínala, som zámerne nerobil – v noci alebo pri skutočnom odchode by mohla svetlá zapnúť zbytočne.
- Ak telefón leží doma a spí, ozýva sa zriedka – preto okno 10 minút. Kratšie okno = rýchlejšie zhasnutie, ale väčšie riziko zhasnutia niekomu, kto je doma.

### Ako pridať druhý telefón (napr. priateľkin)

1. Na druhý telefón nainštaluj rovnaké `Domov.apk`.
2. Na prvom telefóne: ⋮ → **Export zariadení** → Uložiť / Zdieľať a súbor pošli na druhý telefón. Export obsahuje zariadenia (aj local keys) **aj kľúč domácnosti**. Ak druhý telefón zariadenia už má, stačí **Príchod a odchod → Pozvať do domácnosti** (QR kód alebo text `DOMOV-POZVANKA:…` na skopírovanie / zdieľanie).
3. Na druhom telefóne: ⋮ → **Import zariadení** → vyber súbor. Alebo **Príchod a odchod → Pridať sa do domácnosti** a vlož pozvánku (QR kód naskenuj fotoaparátom a text skopíruj).
4. Na oboch telefónoch nastav **Meno tohto telefónu** (napr. „Tomáš“, „Priateľka“).
5. Na druhom telefóne doma na WiFi: **Toto je moja domáca WiFi**, vyber zariadenia, **zapni automatiku**, povoľ notifikácie a vypni optimalizáciu batérie. Automatika musí bežať na oboch telefónoch (inak sa telefón doma neozýva).
6. V časti **Kto je doma** sa do minúty objaví druhý telefón.

Tuya cloud stačí nastaviť na tom telefóne, ktorý má pri odchode zhasínať (najlepšie na oboch).

## 8. Kúrenie Honeywell evohome (od verzie 1.8, nepovinné)

Ak máš kúrenie **Honeywell evohome** s internetovou bránou **RFG100** a ovládaš ho appkou **Honeywell Home** (Total Connect Comfort International), Domov ho vie zobraziť a nastaviť cez ten istý cloud.

1. ⋮ → **Kúrenie Honeywell (nepovinné)**.
2. Zadaj e-mail a heslo z appky Honeywell Home a ťukni na **Prihlásiť**. Údaje sa uložia zašifrované (Android Keystore) len v telefóne, v samostatnom súbore – **nikdy nie sú v exporte**. Odhlásením (ikona ozubeného kolieska v Kúrení) sa zmažú.
3. Na hlavnej obrazovke pribudne pod zariadeniami skupina **Kúrenie**: každá zóna má dlaždicu s aktuálnou teplotou, cieľovou teplotou a režimom (program / natrvalo / do 17:00). Jantárová dlaždica = zóna práve kúri. Ak systém má teplú vodu, má vlastnú dlaždicu.
4. Ťuknutím na zónu sa otvorí detail:
   - **− / +** mení cieľovú teplotu po 0,5 °C (v rozsahu, ktorý povolí systém),
   - **Ako dlho**: Natrvalo, 1 h, 2 h alebo Do zmeny v programe (appka si prečíta program zóny a nastaví teplotu do najbližšej zmeny),
   - **Nastaviť** pošle zmenu, **Podľa programu** zruší ručné nastavenie,
   - **Režim systému**: Auto, Eco, Preč, Vypnuté (vypnutie sa potvrdzuje),
   - **Teplá voda**: Zapnúť / Vypnúť / Program.

Dôležité:
- Kúrenie ide cez internet, takže funguje aj mimo domu (na rozdiel od Tuya zariadení).
- Honeywell obmedzuje počet požiadaviek. Appka obnovuje stav **najviac raz za 3 minúty** a len kým je obrazovka otvorená; prihlasuje sa heslom len raz a potom používa token (obnovuje ho refresh tokenom). Po príkaze si stav overí asi o 15 sekúnd. Keď server požiadavky dočasne zablokuje (HTTP 429), appka 15 minút nič neposiela a napíše to.
- Kúrenie sa **nikdy nemení automaticky**: príchod a odchod, Všetko vypnúť ani Noc naň nesiahajú.
- Chyby (zlé heslo, bez internetu, obmedzenie servera, výpadok servera) sa zobrazia po slovensky a appka kvôli nim nikdy nespadne.

## 9. Svetlá s viacerými zdrojmi a technické info (od verzie 1.9)

Niektoré stropné svetlá majú **hlavné biele svetlo a menšie farebné podsvietenie (podhľad)**. Ak ich appka ovládala ako jedno svetlo, prepnutie Biela/Farba vyplo hlavné svetlo a zaplo podhľad (prepínač „work_mode“ vyberá, ktorý zdroj svieti). Od verzie 1.9 má také svetlo v detaile **samostatnú kartu pre každý zdroj** (Hlavné svetlo, Podhľad) s vlastným vypínačom, jasom a teplotou alebo farbou.

Ako to appka spozná (z popisu hodnôt z Tuya cloudu alebo z `devices.json`):
- **Zmiešaná hodnota `mix_rgbcw`** (zvyčajne DP 51, ako v Tuya appke): 12 bajtov – príznaky, ktoré zdroje svietia (0x01 biele/hlavné, 0x02 farebné/podhľad), farba (odtieň, sýtosť, jas), jas a teplota bielej. DP 20 je vypínač celého svetla. Automaticky sa takto berie **stropné/panelové svetlo** (kategória `xdd` a pod. alebo názov/model ako „CLT“, „strop“, „ceiling“). Bežná RGBCW žiarovka s `mix_rgbcw` ostáva jedno svetlo; v detaile sa len ponúkne „Ovládať samostatne“.
- **Samostatné vypínače**: `switch_led` + `switch_backlight` / `switch_night_light` / `switch_atmosphere`…, alebo `switch_led_1`, `switch_led_2` (stmievače s viacerými kanálmi).
- Obyčajné svetlá s jedným zdrojom sa ovládajú presne ako doteraz.

Dlaždica na hlavnej obrazovke svieti, keď svieti hociktorý zdroj; vypínač na nej vypne všetko alebo zapne hlavné svetlo. **Všetko vypnúť** a odchod vypnú všetky zdroje, **príchod** zapne len hlavné svetlo (podhľad len ak to zapneš v Kanáloch svetla), **Noc** nechá len hlavné svetlo v teplej bielej. Widget prepína vypínač celého svetla.

**Skrytá hodnota DP 51 (od verzie 1.9.1).** Niektoré stropné svetlá (napr. BW-CLT1, Annaror 5LRGB) majú `mix_rgbcw` na DP 51, ale Tuya cloud ho v bežnom popise hodnôt (`/v1.1/devices/{id}/specifications`, štandardné funkcie kategórie) neuvádza a svetlo ho neposiela ani v bežnom stave. Appka preto:
- pri stropnom svetle si DP 51 pri každom čítaní stavu vyžiada zvlášť (lokálny príkaz UPDATEDPS, ako tinytuya `updatedps`); ak ho svetlo pošle, prepne sa samo na dva zdroje. Ak ho 3× nepošle, ďalej sa nepýta.
- pri synchronizácii s Tuya cloudom doplní do popisu hodnôt aj DP z úplného modelu zariadenia (`/v2.0/cloud/thing/{id}/model`).
- ak DP 51 nevidno, ostáva svetlo jedno (ako doteraz) a v detaile ponúkne **Ovládať samostatne** (dá sa vrátiť v Kanáloch svetla → Jedno svetlo).

V nastaveniach zariadenia (ikona posuvníkov vpravo hore):
- **Technické info / Poslať popis zariadenia** – kategória, product_id, model, popis všetkých hodnôt (DP, kód, typ, rozsah) a ich aktuálne hodnoty. Od 1.9.1 aj **všetky DP, ktoré zariadenie pošle** (vyžiada DP 1–255, označí tie mimo popisu z cloudu), a ak je nastavený Tuya cloud, **úplný model zariadenia a posledné hodnoty z cloudu** (`/v2.0/cloud/thing/{id}/model` a `/shadow/properties`). Tlačidlo **Poslať** otvorí zdieľanie Androidu (e-mail, WhatsApp…), **Kopírovať** skopíruje text. **Neobsahuje local key ani IP adresu, Device ID je skrátené** (napr. `bf12…efgh`).
- **Sledovať zmeny (live, 2 min)** v technickom info – appka drží spojenie so zariadením a zapisuje každú zmenu s časom. Medzitým prepínaj v Tuya appke Farba / Biela; záznam sa pridá do textu na poslanie.
- **Kanály svetla (ručne)** – ak appka svetlo nespoznala správne: Automaticky / Jedno svetlo / Zmiešané (DP 51) / Samostatné vypínače, čísla hodnôt pre vypínače a jas/farbu podhľadu a voľba „Pri príchode zapnúť aj podhľad“. Pod formulárom vidíš, čo appka s týmto nastavením rozpozná. Nastavenie sa ukladá k zariadeniu a je aj v exporte `devices.json` (`lightRoles`).

## 10. Plynulé posuvníky (od verzie 1.9.2)

Všetky posuvníky v detaile zariadenia – jas, teplota bielej, farebné koliesko, jas podsvietenia (DP 51), čas a teplota fritézy, cieľová teplota, rýchlosť, poloha žalúzií – ovládajú zariadenie **už počas ťahania**, nie až po pustení prsta:

- počas ťahania appka posiela najnovšiu hodnotu najviac raz za ~130 ms (asi 7 príkazov za sekundu); keď zariadenie ešte spracúva predošlý príkaz, čaká len posledná hodnota, nič sa nehromadí,
- po pustení vždy odošle presnú konečnú hodnotu,
- posuvník ide hneď za prstom; staršie hlásenia zo zariadenia počas ťahania a ~1,5 s po pustení appka ignoruje (okrem tých, ktoré už zodpovedajú novej hodnote), takže posuvník neodskakuje späť,
- pri 3.4/3.5 sa používa jedno trvalé spojenie (zavrie sa po 3 s nečinnosti),
- mimo domácej WiFi (cez Tuya cloud), pri evohome, príkazoch s potvrdením (ohrievač) a v nastaveniach scén sa posiela ako doteraz až po pustení.

## 11. Bezpečné hromadné vypínanie (od verzie 1.9.3)

**Oprava chyby:** v 1.9.2 a skôr mohlo **Všetko vypnúť** (aj odchod a Noc) zapnúť fritézu BH-AF2. Appka jej poslala „vypnúť“ `{"1": false, "2": false}` aj keď už bola vypnutá – fritézu brala ako dvojitý vypínač (hodnoty 1 aj 2 sú zapnuté/vypnuté), takže zapisovala aj štart/stop. Fritéza takýto zápis berie ako stlačenie tlačidla a zapne sa so spusteným programom (Varí · 20 min).

Pravidlá teraz (Všetko vypnúť, odchod, test odchodu, Noc):

- hromadná akcia **nikdy nepošle nič, čo by mohlo fritézu, ohrievač alebo neznáme zariadenie zapnúť**; nič sa neprepína,
- **fritéza** (podľa hodnôt alebo názvu/modelu, napr. „fritéza“, „BH-AF2“): pred akciou sa čerstvo prečíta jej stav. Ak varí, pošle sa len **Stop** (štart/stop = vypnuté). Ak nevarí, nepošle sa nič. Ak sa stav nedá zistiť alebo príkaz na zastavenie nie je istý, je v správe **preskočené**,
- **ohrievač, termostat, neznáme zariadenie**: len istý hlavný vypínač (ručne nastavený alebo kód `switch…` z Tuya cloudu), len keď je čerstvo zistené, že je zapnutý; inak preskočené. Pri odchode cez cloud sa stav číta z cloudu a neskúšajú sa odhadnuté kódy,
- po akcii sa stav rizikových zariadení znova overí; ak by sa niečo zaplo, appka hneď pošle Stop / vypnutie a zobrazí **POZOR** (pri odchode aj notifikáciu),
- svetlá a zásuvky sa vypínajú ako doteraz; príchod zapína len svetlá, zásuvky a zariadenia s istým vypínačom (nikdy fritézu ani ohrievač),
- widget: ak fritéza varí, ťuknutie pošle Stop (nie hlavný vypínač).

## 12. Jas v percentách (od verzie 1.9.4)

**Oprava chyby:** pri svetlách, ktoré majú hodnoty na číslach 1–5 (napr. LED pas BW-LT11), ale jas 10–1000, appka počítala jas ako pri starých svetlách (25–255). Kruh ukazoval nesprávne percentá (10 namiesto 1 %, 4 %…) a 1 % na posuvníku poslalo 25 namiesto 10.

Teraz je jeden spoločný prepočet (`Bright` v `Light.kt`) pre veľký kruh, posuvník, dlaždicu, svetlá s viacerými zdrojmi (DP 51), widget aj Noc, podľa rozsahu zo schémy z Tuya cloudu (bez schémy podľa formátu: 10–1000 alebo 25–255):

- najnižšia hodnota = 1 %, najvyššia = 100 % (ako Tuya appka: 10 z 10–1000 = 1 %); pri 10–1000 je to presne hodnota / 10, pri rozsahu od 0 (0–1000) hodnota / max,
- prepočet tam a späť je presný (1 % → najnižšia hodnota → 1 %),
- ak zariadenie po pustení posuvníka hodnotu neprijme, posuvník sa po chvíli vráti na skutočný stav.

## 13. Upozornenia fritézy (od verzie 1.10)

Pre fritézu BH-AF2 a každú rozpoznanú fritézu (aj kategória Tuya `kqzg`). Nastavenie je v detaile fritézy, karta **Upozornenia**: Upozornenia (hlavný vypínač, predvolene zapnuté), Minútu pred koncom, Keď dovarí, Bezpečnostné upozornenie.

- **Kým varí:** tichá stála notifikácia „… varí“ s odpočtom (beží v telefóne, priebežne sa zosúladí so zariadením) a tlačidlom **Stop**.
- **Minútu pred koncom** a **Fritéza dovarila:** upozornenie so zvukom a vibráciou. Ak fritéza hlási pripomienku pretrasenia/otočenia jedla, príde aj tá (BH-AF2 ju nehlási).
- **Bezpečnosť:** naliehavé upozornenie so Stop, keď fritéza varí a nikto z domácnosti nie je doma, a pri poruche (E1 prerušená / E2 skratovaná teplotná sonda).
- **Stop** z notifikácie pošle len príkaz Stop (DP 2 = vypnuté) a len ak čerstvé čítanie ukáže, že fritéza naozaj varí (rovnaké pravidlá ako Všetko vypnúť, časť 11), potom overí výsledok. Doma ide lokálne, mimo domu cez Tuya cloud (ak je nastavený).
- **Ako často sa pýta:** doma lokálne každých 20 s počas varenia (pred koncom 10 s), inak raz za 90 s; mimo domu cez Tuya cloud raz za minútu počas varenia, inak raz za 10 minút.
- O povolenie zobrazovať notifikácie (Android 13+) sa appka spýta raz, s vysvetlením.

**Obmedzenia:**
- Na pozadí sleduje fritézu služba **Príchod a odchod** – ak nie je zapnutá, upozornenia chodia len kým je appka otvorená.
- Mimo domu treba Tuya cloud (bez neho sa stav fritézy mimo domácej WiFi nedá zistiť ani ju zastaviť).
- Zariadenie hlási zostávajúci čas len v celých minútach, preto upozornenie „minútu pred koncom“ môže prísť o pár desiatok sekúnd skôr. Začiatok varenia (keď fritézu zapneš na nej samotnej) appka zistí s oneskorením do 1,5 min doma, do 10 min mimo domu.
- **Xiaomi / HyperOS** (a podobne Samsung, Huawei) môže appku na pozadí uspať alebo zastaviť. Povoľ: *Nastavenia → Aplikácie → Spravovať aplikácie → Domov → **Automatické spúšťanie** zapnúť* a *Šetrič batérie → **Bez obmedzení***. Pomôže aj zamknúť Domov v zozname nedávnych aplikácií (podržať → zámok). Tlačidlá na tieto nastavenia sú priamo v karte Upozornenia.

## 14. Záloha a obnovenie (od verzie 1.11)

Menu ⋮ → **Záloha a obnovenie**. Jeden súbor `domov-zaloha-RRRR-MM-DD.domov` so všetkým: zariadenia (aj local keys, popis hodnôt, ručné nastavenia – zdroje svetla, názvy a skryté hodnoty, skupiny a poradie), scéna Noc, príchod a odchod (výber zariadení, západ slnka, oneskorenie, poistka), domácnosť a jej kľúč, upozornenia fritézy, zapamätané režimy, nastavenia widgetov, zobrazenie fázy mesiaca, verzia appky.

- **Šifrovanie:** heslo, ktoré si zvolíš (aspoň 8 znakov). PBKDF2-HMAC-SHA256 s 310 000 iteráciami a náhodnou soľou, AES-256-GCM s náhodným nonce, hlavička s verziou formátu (overená spolu s obsahom). Heslo sa nikam neukladá – bez neho sa záloha obnoviť nedá.
- **Prihlasovacie údaje Tuya cloud a Honeywell** sú v zálohe len keď zaškrtneš „Pridať aj prihlasovacie údaje“ (predvolene vypnuté, s upozornením). Po obnovení sa znova zašifrujú kľúčom v Keystore nového telefónu.
- **Uložiť do súboru** (vyberieš miesto – Stiahnuté, Google Disk…) alebo **Zdieľať**.
- **Obnoviť:** vyber súbor, zadaj heslo, appka ukáže zhrnutie (počet zariadení, skupín, automatiky…) a dá na výber:
  - **Nahradiť všetko** – nastavenia z telefónu nahradí zálohou (časti, ktoré záloha nemá, ostanú; prihlasovacie údaje sa zmenia len ak sú v zálohe),
  - **Zlúčiť** – zariadenia podľa ID (alebo Device ID): nové sa pridajú na koniec, rovnaké ostanú; ak sa líšia (IP, local key, názov…), appka ich ukáže a ty zaškrtneš tie, pri ktorých chceš údaje zo zálohy (inak ostane existujúce). Ostatné nastavenia ostanú, chýbajúce sa doplnia; nové zariadenia sa doplnia do Príchodu a Odchodu.
- **Vrátiť späť:** pred každým obnovením si appka sama uloží terajší stav (zašifrovaný kľúčom v Keystore) a tlačidlom Vrátiť späť ho vráti.
- Obnovenie je **naraz**: najprv sa všetko skontroluje (inak sa nezapíše nič), potom sa zapíše s denníkom – ak by appka alebo telefón uprostred spadli, pri ďalšom spustení sa obnova dokončí. Potom sa appka sama reštartuje a znova spustí automatiku a widgety.
- Nesprávne heslo, poškodený či upravený súbor alebo záloha z novšej verzie appky → zrozumiteľná správa, nič sa nezmení.
- **Automatická záloha** (predvolene vypnutá): raz týždenne do vybraného priečinka, ponechá posledné 4 (jedna za deň). Heslo sa na to uloží v telefóne zašifrované kľúčom v Android Keystore.
- Import a export zariadení (devices.json) fungujú ako doteraz.

**Obmedzenia:** pri „Nahradiť všetko“ sa obnoví aj ID telefónu v domácnosti (správne pri obnove toho istého telefónu; na druhý telefón domácnosti použi radšej Zlúčiť alebo pozvánku). Stav automatiky, história a dočasná cache sa nezálohujú. Appka zatiaľ nemá vlastnú voľbu témy (riadi sa systémom), takže sa nezálohuje. Automatickú zálohu môže Android/HyperOS odložiť (beží, keď batéria nie je slabá); po vymazaní dát appky treba priečinok a heslo nastaviť znova. Do koreňového priečinka Stiahnuté Android nedovolí zapisovať automaticky – vyber v ňom podpriečinok.

## 15. Počasie (od verzie 1.12)

Hore na hlavnej obrazovke je malá karta s počasím: aktuálna teplota, ikona a popis po slovensky, dnešné max/min, pocitová teplota, vietor a pravdepodobnosť zrážok a predpoveď na 5 dní. **Ťuknutím** na kartu (alebo na „po hodinách“) sa ukáže najbližších 12 hodín, **podržaním** sa otvorí nastavenie. Nastavenie je aj v menu ⋮ → **Počasie**.

- **Miesto:** buď **Poloha telefónu** (appka si raz zistí približnú polohu; o povolenie polohy žiada až po ťuknutí na toto tlačidlo a polohu na pozadí nesleduje), alebo **Vybrať mesto** – napíš názov mesta či obce a vyber z výsledkov. Kým miesto nie je vybraté, karta ponúka výber jedným ťuknutím (ak máš v Príchode a odchode nastavenú polohu domova, použije sa tá s menom „Domov“).
- **Obnovovanie:** pri otvorení appky a návrate do nej, najviac raz za 30 minút. Ručne tlačidlom ↻ hore alebo potiahnutím obrazovky nadol. Pod kartou je „aktualizované pred X min“.
- **Bez internetu** karta ukazuje posledné uložené počasie s poznámkou; staršie ako 6 hodín je označené „staré údaje“.
- Teplota je vždy v °C, vietor v km/h. Kartu sa dá **skryť** (prepínač v nastavení alebo „Skryť počasie“).
- Vybraté miesto a viditeľnosť karty sú súčasťou zálohy `.domov`; stiahnuté počasie nie. Staršie zálohy (bez počasia) sa obnovia normálne.
- Počasie neovplyvňuje zariadenia, automatiky, widgety ani hromadné vypínanie.

Údaje o počasí: [Open-Meteo.com](https://open-meteo.com/) (licencia CC BY 4.0), bez registrácie a bez kľúča. Hľadanie miest: Open-Meteo Geocoding.

## 16. Fáza mesiaca (od verzie 1.26)

Pod kartou počasia na Domove je úzky pás s aktuálnou fázou mesiaca: názov po slovensky (napr. Prvá štvrť, Spln), percento osvetlenia a dátum najbližšej hlavnej fázy. Výpočet je podľa Meeusa (Astronomical Algorithms, vzorec 48.2). Ikona ukazuje osvetlenú časť disku — na severnej pologuli pri priberaní svetlo sprava. Pás ostane aj keď je počasie skryté. Vypnúť ho vieš v **Nastavenia → Zobraziť fázu mesiaca** (predvolene zapnuté). Nastavenie sa ukladá do zálohy.

## 17. Tichá notifikácia automatiky (od verzie 1.27)

Kým beží Príchod a odchod, Android vyžaduje službu v popredí a k nej notifikáciu („Príchod a odchod — Si doma · poistka zapnutá“ a podobne). Je tichá, s najnižšou dôležitosťou. Appka ju od 1.27 neprekresľuje pri každej kontrole WiFi, poistky či obnovení služby, len keď sa zmení jej text (iný stav alebo zapnutie/vypnutie poistky). Na Androide 14+ ju môžeš odsunúť; bez zmeny stavu sa nevráti. Služba, detekcia prítomnosti, poistka, upozornenia fritézy a správy domácnosti bežia ďalej. Ak chceš notifikáciu nevidieť vôbec, v nastaveniach notifikácií telefónu vypni kanál **Príchod a odchod (beží na pozadí)** – nie kanál udalostí (Vitaj doma / Odišiel si) ani upozornenia fritézy.

## 18. Dátum a meniny (od verzie 1.28)

Medzi počasím a fázou mesiaca je úzky pás s dnešným dňom, dátumom a meninami (napr. „Streda 18. novembra · Meniny: Eugen“) a pod tým zajtrajšie meniny („Zajtra 19. novembra · Alžbeta, Liliana“). Použité je Oficiálne kalendárium Kalendárovej komisie pri Ministerstve kultúry SR (475 mien na 366 dní vrátane 29. 2. – Radomír). V dňoch bez mena (1. 1., 1. 5., 2. 11., 25. 12.) pás ukáže názov sviatku. Dátum sa prepíše o polnoci aj po návrate do appky. Bez internetu.

## 23. Zigbee zariadenia cez Tuya bránu (od verzie 1.35)

Appka vie zobraziť a ovládať aj Zigbee zariadenia pripojené k Tuya bráne (napr. Zigbee brána s ethernetom WG016 / THP10-Z, aplikácia Smart Life). Podzariadenie nemá vlastnú IP adresu ani local key: appka sa lokálne pripojí k **bráne** (port 6668, jej IP, Device ID a local key) a v príkaze uvedie ID podzariadenia (`cid`, v Tuya cloude `node_id`). Funguje to len v domácej sieti, rovnako ako ostatné zariadenia.

**Ako pridať.** Nastav Tuya cloud (časť 2a), potom Nastavenia → Synchronizovať s Tuya cloudom. V okne sa objaví zoznam „Zigbee zariadenia (cez bránu)“; ťuknutím na Pridať (alebo Pridať všetky Zigbee) sa pridajú aj s bránou, ak ju appka ešte nemá. Appka sama zistí, cez ktorú bránu zariadenie ide (dotaz na podzariadenia brány v cloude; ak je v účte len jedna brána, patria jej).

**Čo sa zobrazuje a ovláda**
- Senzory dverí a okien (otvorené / zatvorené), pohybu, teploty a vlhkosti, vody, dymu a plynu: dlaždica ukazuje hodnotu a stav batérie; pri poplachu (otvorené dvere, pohyb, únik) svieti jantárovo. Senzory sa len zobrazujú, nič sa im neposiela, a nikdy sa nezapnú ani nevypnú automatikou, scénou ani Všetko vypnúť.
- Zásuvky, vypínače a žiarovky sa správajú ako WiFi zariadenia (vypínač, jas, teplota a farba, widgety, príchod a odchod, scény).
- Tlačidlá a diaľkové ovládače sa zobrazia (stav batérie), ale stlačenia zatiaľ appka nezachytáva.
- Brána má v zozname vlastnú dlaždicu a v detaile tlačidlo „Zistiť podzariadenia brány“ (surová odpoveď brány, na ladenie).
- Nové skupina **Senzory** na Domove.

**Čo ešte nie je (ďalšia verzia):** upozornenia pri zmene senzora, automatizácie podľa senzorov a udalosti tlačidiel – potrebujú stále otvorené spojenie s bránou. Batériové senzory sa nedajú „spýtať“ priamo; brána vráti ich poslednú známu hodnotu.

**Technicky.** `TuyaDevice` dostal voliteľné `cid` (tvary požiadaviek podľa tinytuya: 3.3 `{"t":…,"cid":…}`, 3.4 a 3.5 `{"cid":…}`, príkaz s `data.cid` a `ctype 0`), odpovede iných podzariadení na tom istom spojení sa preskakujú. Pripojenie rieši `Gateways.route` (Zigbee.kt); všetky operácie na jednej bráne idú po jednom. Druh senzora určuje `Sensors` podľa kódov hodnôt z Tuya popisu (`doorcontact_state`, `pir`, `va_temperature`…) a kategórie. Testy: `tools/GatewayProtocolTest.java` (falošná brána), `tools/ZigbeeTest.kt`.

**Overenie.** Verzia 1.35.0 bola napísaná, kým brána ešte nedorazila, takže komunikácia je overená len proti simulovanej bráne podľa dokumentácie tinytuya. Po dorazení brány treba ešte ladenie (najmä tvary odpovedí konkrétnej brány) – pošli „Technické info“ brány a podzariadenia.

## Limity

- Zariadenia pripojené cez Zigbee/Bluetooth bránu nie sú podporované (komunikujú cez bránu iným spôsobom).
- Svetlá: biela (jas, teplota) a farba fungujú. Hotové **scény a hudobný režim** appka zatiaľ nenastavuje (kód scén je dlhý a výrobca od výrobcu iný). Zobrazia sa len v „Ďalšie hodnoty“ na čítanie.
- Názvy hodnôt (napr. „Jas“) lokálny protokol neposiela. Appka ich pozná len z popisu v `devices.json` (časť 3), inak si ich pomenuješ sám.
- Z popisu z Tuya cloudu sa nedá zistiť, ktoré hodnoty sú len na čítanie. Appka to určuje podľa kódu (merania, stavy, poruchy). Ak sa niektorá hodnota nedá zmeniť, zariadenie zmenu jednoducho ignoruje.
- Local keys sú uložené v súkromnom úložisku appky (bez zálohovania do cloudu).
- Kúrenie Honeywell: podporovaný je prvý systém evohome v účte (bežná inštalácia s jednou bránou). Ovládanie programov (rozvrhu) appka nemení – len ho číta pre voľbu „Do zmeny v programe“. Funguje len s cloudom Honeywell (bez internetu kúrenie z appky neovládneš).
- Svetlá s viacerými zdrojmi: rozloženie `mix_rgbcw` je podľa konfigurácií svetiel v projekte tuya-local (Ledvance, Cloudybay a ďalšie); konkrétne zariadenie sa môže líšiť. Ak niečo nesedí, pošli technické info (časť 9).
- Chceš byť úplne nezávislý od cloudu? V routeri zablokuj zariadeniam prístup na internet. Lokálne ovládanie by malo ďalej fungovať.

## Pre vývojárov

- `app/src/main/java/sk/domov/ovladanie/tuya/TuyaDevice.java` – protokol (3.3 AES-ECB + CRC, 3.4 HMAC + session key, 3.5 AES-GCM).
- `app/src/main/java/sk/domov/ovladanie/Light.kt` – logika svetiel (rozpoznanie, jas, teplota, farba). Test: `tools/LightTest.kt` (spúšťa sa Kotlin kompilátorom, bez Androidu).
- `DomovWidgetProvider.kt` + `WidgetConfigActivity.kt` – widgety na plochu.
- `app/src/main/java/sk/domov/ovladanie/tuya/TuyaDiscovery.java` – dekódovanie UDP broadcastov (bez Androidu), `Scanner.kt` – počúvanie v sieti. Test so správami vytvorenými knižnicou tinytuya:
  ```
  javac -d out app/src/main/java/sk/domov/ovladanie/tuya/TuyaDiscovery.java tools/DiscoveryTest.java
  java -cp out DiscoveryTest
  ```
- `Schema.kt` – popis hodnôt (mapping z tinytuya): načítanie, slovenské názvy, jednotky, skala, read-only. `Templates.kt` – rozpoznanie šablón (zásuvka, termostat…), `TemplateControls.kt` – ich ovládanie. Test: `tools/SchemaTest.kt` s `tools/testdata/devices.json` (vytvorený funkciou `Cloud._build_mapping` z tinytuya 1.21).
- `TuyaCloud.kt` – Tuya OpenAPI (podpis HMAC-SHA256, token, zoznam zariadení, popis hodnôt) ako tinytuya 1.21, bez Androidu. `CloudStore.kt` – šifrované uloženie údajov (Android Keystore), `CloudUi.kt` – nastavenia, sprievodca, pomoc, synchronizácia. Test: `tools/CloudTest.kt` – podpisy sa porovnávajú s hodnotami, ktoré pre rovnaké vstupy vypočítal tinytuya, a popis hodnôt s výstupom `Cloud._build_mapping`.
- `Household.kt` – domácnosť bez Androidu: správy „som doma“ (HMAC-SHA256, ochrana proti podvrhu a zopakovaniu), pozvánka, kľúč v exporte, rozhodovanie pri odchode a príchode. `HouseholdNet.kt` – UDP broadcast a príjem, uloženie ID / mena / kľúča / členov. Test: `tools/HouseholdTest.kt` (referenčná správa z Pythonu `hmac`).
- `Presence.kt` – automatika príchod/odchod bez Androidu: stavový automat (doma / čakanie / mimo domu, krátky výpadok), domáca sieť, výber zariadení, cloud príkazy na vypnutie, poistka. `Sun.kt` – východ/západ slnka (NOAA). `PresenceService.kt` – služba v popredí, NetworkCallback, akcie, notifikácie (od 1.27 trvalú notifikáciu služby prekreslí len pri zmene textu), spustenie po reštarte. `PresenceUi.kt` – nastavenia. Test: `tools/PresenceTest.kt` – automat, slnko porovnané s knižnicou astral, podpis príkazu porovnaný s `tinytuya Cloud.sendcommand`.
- `Scenes.kt` – skupiny na hlavnej obrazovke, Všetko vypnúť a scéna Noc (bez Androidu), `Home.kt` – hlavná obrazovka, `Theme.kt` / `Components.kt` / `Symbols.kt` – téma, spoločné prvky a ikony (Material Symbols, Apache 2.0; písmo Inter, OFL). Test: `tools/ScenesTest.kt`.
- `Evohome.kt` – Honeywell evohome, TCC v2 API ako knižnice evohome-client / evohome-async a integrácia evohome v Home Assistant (server `tccna.resideo.com`, OAuth `/Auth/OAuth/Token` s grantom password / refresh_token, `/WebAPI/emea/api/v1/…`: userAccount, installationInfo, location status, heatSetpoint, mode, domesticHotWater state, schedule), bez Androidu. `EvohomeStore.kt` – šifrované uloženie a `HeatController` (obnovovanie, príkazy, limity), `HeatUi.kt` – dlaždice, detail a prihlásenie. Test: `tools/EvohomeTest.kt` – lokálny HTTP server prehráva odpovede z testovacích dát evohome-async (`tools/testdata/evohome`, Apache 2.0): prihlásenie, obnova tokenu, 401, 429, chyby, stav, príkazy a výpočet ďalšej zmeny v programe.
- Záloha (1.11): `Backup.kt` (bez Androidu: formát a šifrovanie `BackupCrypto`, obsah `BackupPayload`/`BackupSpec`, zhrnutie, zlúčenie a konflikty `Backup.merge/conflicts`, atomické obnovenie s poistnou zálohou a denníkom `BackupApplier`), `BackupAndroid.kt` (SharedPreferences s commit, Keystore, SAF, zdieľanie cez FileProvider, týždenná úloha JobScheduler, `RestartActivity` v samostatnom procese), `BackupUi.kt`. Test: `tools/BackupTest.kt`. Formát: `DOMOVZAL` | verzia 1 | KDF 1 | iterácie (4 B) | soľ 16 B | nonce 12 B | kontrola hesla 16 B | AES-GCM(gzip(JSON)), hlavička je AAD; z hesla 64 B (kľúč AES + kľúč kontroly, v hlavičke len SHA-256 z neho).
- Počasie (1.12): `Weather.kt` (bez Androidu: kódy WMO → slovenský text a ikona `Wmo`, URL a spracovanie odpovedí Open-Meteo `WeatherApi`, nastavenia `WeatherSettings`, cache a pravidlá obnovovania `WeatherPolicy` – najviac raz za 30 min, ručne najskôr po 60 s, po chybe pauza 2 min), `WeatherAndroid.kt` (`WeatherController`: SharedPreferences `domov_weather`, HttpURLConnection, jednorazová približná poloha cez LocationManager), `WeatherUi.kt` (karta a nastavenie). Test: `tools/WeatherTest.kt` s ukážkovými odpoveďami v `tools/testdata/weather/`.
- Meniny (1.28): `Meniny.kt` (bez Androidu: kalendár 366 dní z Oficiálneho kalendária, formátovanie dátumu po slovensky), `MeninyUi.kt` (úzky pás `MeninyStrip` na Domove, prepočet o polnoci). Test: `tools/MeninyTest.kt`.
- Fáza mesiaca (1.26): `Moon.kt` (bez Androidu: Meeus AA 2ed vzorec 48.2, slovenské názvy, percento osvetlenia, najbližšia hlavná fáza), `MoonAndroid.kt` (`MoonStore` v SharedPreferences `domov_moon`, `MoonPhaseIcon`, úzky pás `MoonStrip` na Domove, prepínač v Nastaveniach). Test: `tools/MoonTest.kt` porovnáva percentá s PyEphem (odchýlka do 0,31 % na vzorke 2026).
- Upozornenia fritézy (1.10): `FryerWatch.kt` (bez Androidu: stav, odhad konca, udalosti Started / OneMinute / Done / Stopped / Shake / Fault / AwayRunning, intervaly), `FryerAlerts.kt` (`FryerStore`, kanály a notifikácie `FryerNotify`, `FryerMonitor.onValues/tick/stop`, `FryerReceiver` pre Stop), `FryerAlertsUi.kt` (karta nastavení). Volá sa z `HomeViewModel.apply` a slučky v `PresenceService`. Test: `tools/FryerWatchTest.kt`; simulovaná fritéza `tools/MockFryer.java` (`-Dmin=60000 -Drem=2 running` = odpočet 2 min, `-Dfault=1` porucha).
- Jas (1.9.4): `Bright.percent/raw/range` v `Light.kt`, `Light.detect(values, schema)` (rozsah jasu a teploty zo schémy). Test: `tools/BrightTest.kt`.
- Bezpečné hromadné vypínanie (1.9.3): `SafeOff.kt` (`offStep`, `cloudOff`, `verify`), `Scenes.allOffSteps/nightSteps`, `HomeViewModel.runPlan`, `PresenceActions.depart/turnOff/verifyCloud`. Test: `tools/SafeOffTest.kt` (fritéza BH-AF2 vypnutá / v pohotovosti / varí / neznámy stav pre Všetko vypnúť, Noc aj odchod; ohrievač; neznáme zariadenia; overenie po akcii).
- Plynulé posuvníky (1.9.2): `Live.kt` – `LiveThrottle` (najviac 1 príkaz za 130 ms, zlučovanie bez frontu, konečná hodnota) a `DragGuard` (ignorovanie starých hlásení 1,5 s); `TuyaDevice.openLive()` (trvalé spojenie), `DeviceClient.liveSend`, `HomeViewModel.liveValues`, v UI `LocalLiveCommand` a `liveOf`. Test: `tools/LiveTest.kt` (v `tools/run-tests.sh`).
- `TuyaDevice.java` od 1.9.1: `queryWithExtra` (stav + UPDATEDPS 0x12 `{"dpId":[…]}` v tom istom spojení) a `monitor` (dlhé spojenie, heartbeat 0x09, opätovné pripojenie). Test: `tools/MockTest.java` (3.3/3.4/3.5). `LocalDps` a `TechExtra` v `LightChannels.kt`, `CloudParse.thingProps/shadow/addThingProps` v `TuyaCloud.kt`.
- `LightChannels.kt` – svetlá s viacerými zdrojmi bez Androidu: kodek `mix_rgbcw` (base64 aj hex, 6 × 16 bitov: príznaky, H, S, V, jas bielej, teplota), rozpoznanie (MIX / samostatné vypínače, ručné `Device.lightRoles`), príkazy na kanál, Všetko vypnúť / príchod / Noc a `TechInfo` (popis bez kľúča a IP). UI: `MultiLightControls` v `LightControls.kt`, dialógy v `DeviceExtras.kt`. Test: `tools/LightChannelsTest.kt` (schéma ako BW-CLT1 s DP 51 a bežná RGBCW žiarovka).
- Snímky obrazovky s ukážkovými dátami (len v pamäti, nič sa neuloží): `adb shell am start -n sk.domov.ovladanie/.MainActivity --ez demo true`.
- Všetky testy bez Androidu naraz: `tools/run-tests.sh` (potrebuje JDK 17 a `kotlinc`; org.json si stiahne sám).
- `tools/MockTest.java` – test proti simulovanému zariadeniu, spustíš ho bez Androidu:
  ```
  javac -d out app/src/main/java/sk/domov/ovladanie/tuya/TuyaDevice.java tools/MockTest.java
  java -cp out MockTest
  ```
- Build lokálne: `./gradlew :app:assembleDebug` (potrebuje Android SDK).

## 19. Nový vzhľad (od verzie 1.29)
Princíp „svieti len to, čo je zapnuté“: tmavý neutrálny základ, teplá jantárová len pre zapnuté zariadenia a aktívne prvky.
- Farby sú v `Theme.kt` (`DarkScheme`/`LightScheme`, `DomovColors`) – zmena tokenov prefarbí celú aplikáciu.
- Domov: pozdrav podľa času, tlačidlo „+“ v hlavičke (namiesto plávajúceho „Pridať“), scény ako pilulky, podrobné počasie, meniny a fáza mesiaca ostali.
- Dlaždice: jantárové keď svietia, pásik jasu pri svetlách, pri svetle s podhľadom dve tlačidlá (Hlavné / Podhľad).
- Dolná lišta je plávajúca pilulka (`HomeBottomBar` v `Tabs.kt`).
- Detail svetla: kruh s oblúkom jasu a tlačidlom Zapnúť / Vypnúť (`LightOrb`).
- Widgety používajú rovnakú paletu (svetlý aj tmavý režim).

## 20. Denník príkazov (od verzie 1.29.1)
V **Technickom info** zariadenia pribudla sekcia „Príkazy z appky, automatiky a widgetu“: každý príkaz, ktorý sa zariadeniu poslal (s časom na milisekundy a zdrojom: appka, posuvník, widget, automatika, poistka, cloud), chyby pripojenia a zmeny zapnuté/vypnuté, ktoré appka videla. Slúži na zistenie, kto svetlu poslal príkaz, keď sa po zapnutí samo vypne. Denník je len v pamäti (`CommandLog.kt`).

### Oprava 1.29.2
- Farebné koliesko (aj rýchle farby cez ťuknutie na koliesko) používalo staré údaje o svetle z čias, keď sa zobrazilo: po vypnutí hlavného svetla a zmene farby podhľadu poslalo „hlavné svieti“ a rozsvietilo ho. Teraz berie vždy aktuálny stav.
- Po príkaze sa stav v appke 1,5 s nenechá prepísať starým hlásením pomalého svetla.

## 21. Nákupný zoznam (od verzie 1.30)
Na Domove je pod scénami tenký riadok **Nákup** (koľko zostáva kúpiť). Ťuknutím sa otvorí jedna obrazovka:
- Hore pole „Čo treba kúpiť?“ a tlačidlo +. Množstvo sa píše rovno do názvu („Mlieko 2 l“). Po potvrdení ostane klávesnica, takže sa dá pridať viac vecí za sebou.
- Pod ním plochý zoznam, najnovšie hore. Ťuknutím na riadok sa položka odškrtne: preškrtne sa, zosivie a presunie dole. Ťuknutím sa vráti späť.
- „Vymazať kúpené“ hore vpravo (je vidno len keď je niečo odškrtnuté). Preklepnutú položku odstrániš tak, že ju odškrtneš a vymažeš kúpené.
- Keď napíšeš položku, ktorá už v zozname je, nezduplikuje sa; ak je kúpená, vráti sa medzi nekúpené.
- Zoznam sa ukladá len v tomto telefóne (`ShoppingAndroid.kt`, súbor `domov_shopping`) a je súčasťou zálohy. Zdieľanie s ďalším telefónom zatiaľ nie je.
- Kód: `Shopping.kt` (logika bez Androidu), `ShoppingAndroid.kt` (uloženie), `ShoppingUi.kt` (riadok na Domove a obrazovka). Test: `tools/ShoppingTest.kt`.

## 22. Udalosti a narodeniny (od verzie 1.31)
Na Domove je pod Nákupom riadok **Udalosti**. Kým sa nič nechystá, je tenký („Nič sa nechystá“); keď sa nejaká udalosť dostane do okna upozornení, riadok sa predĺži a ukáže **všetky** čakajúce pod sebou (odpočet, názov, pri narodeninách vek). Ťuknutím sa otvorí zoznam, hore je + na pridanie.
- Druh: **Udalosť** (jednorazová alebo „opakovať každý rok“) alebo **Narodeniny** (každý rok; ak zadáš rok narodenia, upozornenie napíše vek).
- **Upozorniť vopred**: čipy V ten deň, 1 deň, 3 dni, 1 týždeň, 2 týždne, 30 dní (dá sa vybrať viac naraz) a políčko pre vlastný počet dní (0 – 365).
- Čas upozornení je jeden pre všetky udalosti (predvolene 8:00, nastavíš dole v zozname). Na jednu udalosť je jedna notifikácia: „v ten deň“ nahradí „týždeň vopred“.
- Budík (`AlarmManager.setAndAllowWhileIdle`, bez práva na presné budíky) sa spúšťa raz denne. Kontrola beží aj po štarte telefónu, po aktualizácii appky a pri každom otvorení appky, takže zmeškané upozornenie (vypnutý telefón, šetrič batérie) príde najneskôr vtedy. Ak je zmeškaných viac upozornení jednej udalosti, príde jedno – najbližšie k udalosti.
- Po uložení udalosti sa upozornenia, ktorých čas už prešiel, považujú za vybavené (narodeniny o 3 dni s „týždeň vopred“ nespustia hneď staré upozornenie).
- Bez povolených upozornení v telefóne sa nič nevybaví: hore je upozornenie s tlačidlom do nastavení a po zapnutí prídu zmeškané pri najbližšej kontrole. O povolenie sa appka pýta po uložení prvej udalosti (Android 13+).
- Ukladá sa len v telefóne (`domov_events`), udalosti a čas upozornení sú v zálohe. Kód: `Events.kt` (logika bez Androidu), `EventsAndroid.kt` (uloženie, budík, notifikácie, `EventsReceiver`), `EventsUi.kt` (riadok, zoznam, dialóg). Test: `tools/EventsTest.kt`.

### Doladenie 1.31.1
- Narodeniny: po zaškrtnutí „Poznám rok narodenia“ sa hneď otvorí výber dátumu, aby sa zvolil rok (najneskôr dnes). Kým sa rok nevyberie, uložiť sa nedá – appka rok nikdy nevymyslí. Pod dátumom je vidno riadok, ako ho zobrazí zoznam („streda 25. novembra · každý rok · bude mať 30 rokov“).
- Jednorazová udalosť nemôže byť v minulosti (výber dátumu ju nepustí; staršiu pri úprave appka označí a nedá uložiť).
- Pod voľbami „Upozorniť vopred“ dialóg ukáže, kedy upozornenia naozaj prídu („Príde o 8:00: 18. novembra (1 týždeň vopred), 25. novembra (v ten deň)“) a ktoré už prešli a nepríde („Už prešlo, nepríde: 1 týždeň vopred“). Logika je v `Events.plan` / `Events.planLines` (test `tools/EventsTest.kt`).

### Verzia 1.32: okno upozornení, meniny, bubny
- **Okno upozornení**: udalosť sa na Domove ukáže až od dňa, keď sa začína jej prvé upozornenie (najväčšia voľba „vopred“, napr. 7 dní). Narodeniny o 300 dní sa tam neukážu. Riadkov je toľko, koľko je udalostí v okne – nič sa nezbaľuje. Po dni udalosti riadok sám zmizne. Udalosť bez upozornenia sa na Domove nezobrazí (je len v zozname). Logika: `Events.window` / `Events.groups`.
- **Zoznam** má skupiny **Čoskoro** (v okne, s odpočtom), **Neskôr** (len dátum, bez „o 300 dní“) a **Prešlo** (jednorazové, ktoré už prešli, aby sa dali zmazať).
- **Meniny**: pri narodeninách je „Pridať aj meniny“. Meniny sú druhý dátum (deň a mesiac) tej istej osoby s rovnakými upozorneniami; uložené sú v jednom zázname (`meninyMonth`, `meninyDay`) a pre upozornenia a zoznam sa rozložia na dve položky (`Events.parts`, id meníň = id + `~n`). Ak je meno v kalendári mien (`Meniny.dniPreMeno`, prvé slovo, bez diakritiky) na jednom dni, dátum sa predvyplní, inak sa otvoria bubny. Úprava aj zmazanie menín ide cez záznam osoby; odoslané upozornenia sa evidujú osobitne (`id~n|…`) a pri uložení či zmazaní sa čistia spolu (`Events.dropKeys`).
- **Výber dátumu na bubnoch** (`WheelPicker.kt`, čistá logika `DatePick` v `Events.kt`): deň, mesiac a rok sa točia ako bubny. Deň a mesiac sa zalamujú, rok nie. Po zastavení sa dátum opraví: deň sa skráti na dĺžku mesiaca (31. → 28. 2.) a dátum sa dá do rozsahu – jednorazová udalosť nie je v minulosti, narodený nie je v budúcnosti. Hodnoty, ktoré sa nedajú vybrať, sú zašedené. Bez roku (meniny, každoročné udalosti) sú len dva bubny a v priestupnom referenčnom roku sa dá vybrať aj 29. 2.
- 1.32.1: tlačidlá „Zrušiť“ a „Hotovo“ sú v paneli s bubnami hore vedľa názvu (spodné prekrývala systémová navigácia) a panel má odstup od spodku podľa systémovej navigácie.
- 1.32.2: výber dátumu na bubnoch je vyskakovacie okno uprostred obrazovky (nie spodný panel); Zrušiť a Hotovo sú naspodku okna. Na nízkej obrazovke (naležato) majú bubny tri riadky a obsah okna sa dá posúvať.
- 1.33.0: **jednotný vzhľad vyskakovacích okien** (`DomovDialog.kt`): zaoblená karta uprostred obrazovky ako pri výbere dátumu, jantárový krúžok s ikonou navrchu (pri mazaní a odhlásení červený a s červeným hlavným tlačidlom), pevná päta s tlačidlami (obsah sa medzi nimi posúva) a plynulé zobrazenie. Všetky `AlertDialog` sú nahradené `DomovAlert`; polia sú `DomovTextField`, výber jednej možnosti `DialogOption` (karta s fajkou namiesto bodkového políčka), `CheckRow` je v oknách prepínač, menu ⋮ je `DomovMenu` s ikonami. Okno na číslo má veľkú hodnotu, tlačidlá −/+ a jantárový posuvník. Čas upozornení sa vyberá na bubnoch (`WheelTimeDialog`) namiesto systémového okna. Okná s výberom dátumu sa nezmenili.
- 1.33.1: vyskakovacie okná sa kreslia na celú obrazovku a okraje (stavový riadok, navigačná lišta, klávesnica) si ošetrujú samy (`decorFitsSystemWindows = false` + `WindowInsets.safeDrawing`); výška okna je navyše obmedzená podľa výšky displeja. Dlhé okná (napr. Nové zariadenie) mali pätu s tlačidlom Uložiť pod displejom.
- 1.34.0: **vlastné recepty fritézy** (názov + režim + čas + teplota, jedným ťuknutím sa nastavia; Štart ostáva zvlášť). Pridané do zálohy.
- 1.34.1: recepty fritézy sa dajú vytvárať a upravovať aj pri vypnutej fritéze (karta Recepty už nie je stlmená, editor s posuvníkmi času a teploty); zamknutá ostáva len priama obsluha a nastavenie receptu na fritéze.
- 1.34.2: režim v recepte je nepovinný a v novom recepte prázdny (recept bez režimu nastaví len čas a teplotu, režim fritézy nemení); Prepísať aktuálnym nastavením nechá recept bez režimu bez režimu.
- 1.34.3: **režim fritézy je zrušený** – zmizol z obrazovky (tlačidlá režimov, Iný…) aj z receptov. Recept = názov + čas + teplota a režim fritézy nemení. Staré recepty s režimom sa načítajú bez neho.
- 1.34.4: **oprava ikony mesiaca** – ikona ukazovala opačné osvetlenie (pri priberajúcom mesiaci pribúdala tmavá časť namiesto svetlej, pri nove svietil celý disk). Oblúk osvetlenej strany (`moonLitPath` v `MoonAndroid.kt`) mal obrátený smer; teraz sedí osvetlenie aj strana pre všetky fázy (na severnej pologuli pri priberaní svetlo sprava).
- 1.34.5: **na Domove zmizlo veľké červené upozornenie „Nie si na domácej WiFi“**; mimo domácej WiFi ostáva len malý chip „Bez WiFi“ v hlavičke. Upozornenie ostáva na obrazovke detailu zariadenia (`WifiBanner` v `DeviceScreen.kt`), kde chip nie je.
- 1.35.0: **Zigbee zariadenia cez Tuya bránu** (senzory dverí/okien, pohybu, teploty a vlhkosti, vody, dymu; zásuvky, vypínače, žiarovky; tlačidlá len ako zobrazenie). Pridanie zo Synchronizácie s Tuya cloudom, skupina Senzory, brána s diagnostikou. Nové: `Zigbee.kt`, `cid` v `TuyaDevice`. Otestované len proti simulovanej bráne (pozri časť 23).
