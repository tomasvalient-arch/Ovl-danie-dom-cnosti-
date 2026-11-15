# Domov – ovládanie Tuya zariadení bez Tuya appky

Android appka, ktorá ovláda tvoje Tuya zariadenia **priamo cez domácu WiFi** (lokálny protokol, TCP port 6668). Nepotrebuje Tuya cloud ani Tuya appku. Mimo domácej WiFi nefunguje a appka to zobrazí upozornením.

Pri každom zariadení si appka sama načíta **všetky jeho hodnoty** a ku každej ukáže vhodný ovládací prvok: vypínač (zapnuté/vypnuté), alebo hodnotu s tlačidlom „Zmeniť“ (jas, teplota, čas, režim). Podporuje protokoly **3.3, 3.4 a 3.5**.

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
4. V appke ťukni na **Import** a vlož obsah `devices.json`. Alebo pridaj zariadenie ručne tlačidlom **+**.

## 3. Použitie

- Každé zariadenie má IP adresu, Device ID, local key a verziu protokolu. Keď zariadenie neodpovedá, skús inú verziu (3.3, 3.4, 3.5).
- Hodnoty sa zobrazia ako „Hodnota 20“, „Hodnota 22“ a pod. Číslo je interné označenie (DP). Skús ich meniť a uvidíš, čo ktorá robí. Potom ich môžeš pomenovať v **Upraviť → Názvy hodnôt**, napr. `20=Svetlo, 22=Jas`.
- Hodnoty, ktoré nepotrebuješ (časovače, dlhé kódy scén), skryješ v **Upraviť → Skryť hodnoty**, napr. `25, 26`.
- IP adresu nájdeš v routeri v zozname pripojených zariadení, alebo v Tuya appke v nastaveniach zariadenia (informácie o zariadení). V routeri ju nastav ako **pevnú (DHCP rezervácia)**, inak sa môže zmeniť.
- Local key sa zmení, ak zariadenie znova spáruješ. Vtedy ho treba vytiahnuť nanovo.
- Appka posiela spojenia priamo cez WiFi, takže funguje aj keď má WiFi sieť bez internetu.

## Limity

- Zariadenia pripojené cez Zigbee/Bluetooth bránu nie sú podporované (komunikujú cez bránu iným spôsobom).
- Farby a scény sa ukladajú ako dlhý kód, ktorý appka nevie pohodlne upraviť (zobrazí sa len na čítanie). Zapnutie, jas, teplota, časy a režimy fungujú.
- Názvy hodnôt (napr. „Jas“) appka nepozná, lebo lokálny protokol posiela len čísla. Pomenuješ si ich sám.
- Local keys sú uložené v súkromnom úložisku appky (bez zálohovania do cloudu).
- Chceš byť úplne nezávislý od cloudu? V routeri zablokuj zariadeniam prístup na internet. Lokálne ovládanie by malo ďalej fungovať.

## Pre vývojárov

- `app/src/main/java/sk/domov/ovladanie/tuya/TuyaDevice.java` – protokol (3.3 AES-ECB + CRC, 3.4 HMAC + session key, 3.5 AES-GCM).
- `tools/MockTest.java` – test proti simulovanému zariadeniu, spustíš ho bez Androidu:
  ```
  javac -d out app/src/main/java/sk/domov/ovladanie/tuya/TuyaDevice.java tools/MockTest.java
  java -cp out MockTest
  ```
- Build lokálne: `./gradlew :app:assembleDebug` (potrebuje Android SDK).
