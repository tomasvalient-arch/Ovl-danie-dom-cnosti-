# Domov – ovládanie Tuya zariadení bez Tuya appky

Android appka, ktorá ovláda tvoje Tuya zariadenia **priamo cez domácu WiFi** (lokálny protokol, TCP port 6668). Nepotrebuje Tuya cloud ani Tuya appku. Mimo domácej WiFi nefunguje a appka to zobrazí upozornením.

Domovská obrazovka ukazuje každé zariadenie ako dlaždicu s vypínačom a stavom. Ťuknutím na dlaždicu sa otvorí detail:

- **Svetlá** (LED pás, žiarovka) sa spoznajú automaticky a majú ovládanie ako v Tuya appke: veľký vypínač, prepínač **Biela / Farba**, jas, teplota bielej a výber farby.
- **Ostatné zariadenia** (napr. fritéza) ukážu zoznam všetkých hodnôt: vypínač, alebo hodnotu s tlačidlom „Zmeniť“. Hodnoty si môžeš premenovať, skryť alebo jednu nastaviť ako hlavný vypínač (cez ⋮ pri hodnote).
- **Widgety na plochu**: jedno ťuknutie zapne alebo vypne zariadenie (pozri nižšie).

Podporuje protokoly **3.3, 3.4 a 3.5**.

## 1. Ako získať APK

1. Vytvor na GitHube nový repozitár (napr. `domov`) a nahraj do neho obsah tohto projektu (vrátane skrytého priečinka `.github`).
2. Po nahratí sa na záložke **Actions** sám spustí build „Zostaviť APK“ (trvá cca 5 minút).
3. Hotové APK nájdeš v časti **Releases** (súbor `Domov.apk`). Stiahni ho do mobilu a nainštaluj (Android si vypýta povolenie inštalovať z neznámych zdrojov).

Ak build zlyhá, otvor v Actions neúspešný beh a pošli mi text chyby – opravím to.

## 2. Ako získať Device ID a local key (jednorazovo)

Zariadenia sa lokálne ovládajú pomocou *local key*, ktorý pozná len Tuya cloud. Dá sa vytiahnuť raz:

1. Zaregistruj sa na <https://iot.tuya.com> (zadarmo) a vytvor *Cloud project* (Smart Home, dátové centrum podľa regiónu tvojho účtu, v Európe „Central Europe“).
2. V projekte pridaj služby *IoT Core* a *Authorization Token Management* a na záložke *Devices → Link Tuya App Account* prepoj svoj účet z Tuya appky (QR kódom).
3. Na PC spusti:
   ```
   pip install tinytuya
   python -m tinytuya wizard
   ```
   Wizard sa opýta na *Access ID* a *Access Secret* projektu a vytvorí súbor `devices.json` (aj s IP adresami, ak ich nájde v sieti).
4. V appke ťukni na ⋮ → **Import zariadení** a vlož obsah `devices.json`. Alebo pridaj zariadenie ručne tlačidlom **+**.

### Záloha a obnova (export / import)

V appke ťukni na ⋮ → **Export zariadení**. Exportuje sa JSON so všetkými zariadeniami (aj s názvami hodnôt, skrytými hodnotami, hlavným vypínačom a vypínačmi sekcií svetla).

- **Uložiť do súboru .json** – vyberieš miesto (Stiahnuté, Disk Google a pod.) a appka uloží súbor `domov-zariadenia.json`.
- **Kopírovať** alebo **Zdieľať** – to isté ako text (napr. do Poznámok alebo na Telegram).

Po preinštalovaní appky ťukni na ⋮ → **Import zariadení** a buď **Vybrať súbor .json**, alebo **Vložiť zo schránky**. Appka vie načítať export z tejto appky aj `devices.json` z tinytuya wizardu. Export obsahuje local keys, preto ho nikomu neposielaj.

## 3. Použitie

- Každé zariadenie má IP adresu, Device ID, local key a verziu protokolu. Keď zariadenie neodpovedá, skús inú verziu (3.3, 3.4, 3.5).
- Pri zariadeniach, ktoré appka nepozná ako svetlo, sa hodnoty zobrazia ako „Hodnota 20“, „Hodnota 22“ a pod. Číslo je interné označenie (DP). Skús ich meniť a uvidíš, čo ktorá robí. Potom ich cez ⋮ pri hodnote **premenuješ**, alebo **skryješ**. (Dá sa to aj hromadne v nastaveniach zariadenia, ozubené koliesko hore.)
- **Svetlo s dvoma zdrojmi** (napr. stropné svetlo BW-CLT1: hlavné biele a podhľadové farebné): appka ho pozná podľa hodnôt 22 (jas), 23 (teplota) a 24 (farba) bez hodnoty 21 (režim). V detaile je sekcia **Podhľadové svetlo (farba)** s výberom farby a jasom a sekcia **Hlavné svetlo (biela)** s jasom a teplotou. Samostatné vypínače sekcií appka sama nenájde, ale priradíš ich jedným ťuknutím: v detaile sa hneď rozbalí zoznam všetkých hodnôt. V Tuya appke prepni Colour alebo White a pozri sa, ktorý vypínač v zozname sa zmenil. Ťukni na jeho ⋮ a vyber **Vypínač hlavného svetla (biela)** alebo **Vypínač podhľadového svetla (farba)**. Číslo sa dá zadať aj ručne v nastaveniach zariadenia (ozubené koliesko).
- **Fritéza** (BH-AF2 a podobné): appka ju pozná podľa hodnôt 2 (štart/stop), 3 (režim), 5 (stav), 6 (teplota), 9 (čas). V detaile je stav, výber režimu, čas (1–60 min), teplota (80–200 °C po 5) a tlačidlo **Štart/Stop**. Štart sa pýta na potvrdenie, lebo spúšťa ohrev. Veľké tlačidlo hore, dlaždica aj widget ovládajú len hlavný vypínač (hodnota 1), takže fritézu nikdy nespustia. Zoznam režimov (Chips, Cake…) zariadenie neposiela, preto si ich appka zapamätá, keď sa raz objavia (napr. po výbere v Tuya appke). Iný režim zadáš cez **Iný…**.
- Otvorený detail zariadenia sa sám obnovuje každé 4 sekundy, takže uvidíš aj zmeny spravené v Tuya appke alebo tlačidlom na zariadení.
- **Hlavný vypínač** je hodnota, ktorú ovláda veľké tlačidlo, prepínač na dlaždici a widget. Pri svetle sa nájde sám, pri ostatných zariadeniach je to prvá hodnota typu zapnuté/vypnuté. Ak to nie je tá správna, nastav ju cez ⋮ → „Nastaviť ako hlavný vypínač“.
- IP adresu nájdeš v routeri v zozname pripojených zariadení, alebo v Tuya appke v nastaveniach zariadenia (informácie o zariadení). V routeri ju nastav ako **pevnú (DHCP rezervácia)**, inak sa môže zmeniť.
- Local key sa zmení, ak zariadenie znova spáruješ. Vtedy ho treba vytiahnuť nanovo.
- Appka posiela spojenia priamo cez WiFi, takže funguje aj keď má WiFi sieť bez internetu.

## 4. Widgety na plochu

1. Podrž prst na prázdnom mieste plochy, vyber **Widgety** a nájdi **Domov**. Pridaj widget **Domov – zariadenie** (je veľký 1×1, dá sa aj zväčšiť).
2. Appka sa opýta, ktoré zariadenie widget ovláda. Vyber ho.
3. Ťuknutím na widget sa zariadenie zapne alebo vypne. Žltá dlaždica = zapnuté, tmavá = vypnuté. Na každé zariadenie môžeš dať aj viac widgetov.

Widget si stav obnovuje pri ťuknutí a približne každých 30 minút. Ak zariadenie prepneš fyzickým tlačidlom, widget to môže ukázať s oneskorením. Ťuknutie ale vždy najprv zistí skutočný stav a prepne na opačný. Mimo domácej WiFi ukáže „Bez WiFi“.

Pozor pri spotrebičoch (fritéza): widget prepína hlavný vypínač. Zapnutie nemusí spustiť varenie, ale skontroluj si na zariadení, čo sa po zapnutí deje.

## Limity

- Zariadenia pripojené cez Zigbee/Bluetooth bránu nie sú podporované (komunikujú cez bránu iným spôsobom).
- Svetlá: biela (jas, teplota) a farba fungujú. Hotové **scény a hudobný režim** appka zatiaľ nenastavuje (kód scén je dlhý a výrobca od výrobcu iný). Zobrazia sa len v „Ďalšie hodnoty“ na čítanie.
- Názvy hodnôt (napr. „Jas“) appka nepozná, lebo lokálny protokol posiela len čísla. Pomenuješ si ich sám.
- Local keys sú uložené v súkromnom úložisku appky (bez zálohovania do cloudu).
- Chceš byť úplne nezávislý od cloudu? V routeri zablokuj zariadeniam prístup na internet. Lokálne ovládanie by malo ďalej fungovať.

## Pre vývojárov

- `app/src/main/java/sk/domov/ovladanie/tuya/TuyaDevice.java` – protokol (3.3 AES-ECB + CRC, 3.4 HMAC + session key, 3.5 AES-GCM).
- `app/src/main/java/sk/domov/ovladanie/Light.kt` – logika svetiel (rozpoznanie, jas, teplota, farba). Test: `tools/LightTest.kt` (spúšťa sa Kotlin kompilátorom, bez Androidu).
- `DomovWidgetProvider.kt` + `WidgetConfigActivity.kt` – widgety na plochu.
- `tools/MockTest.java` – test proti simulovanému zariadeniu, spustíš ho bez Androidu:
  ```
  javac -d out app/src/main/java/sk/domov/ovladanie/tuya/TuyaDevice.java tools/MockTest.java
  java -cp out MockTest
  ```
- Build lokálne: `./gradlew :app:assembleDebug` (potrebuje Android SDK).
