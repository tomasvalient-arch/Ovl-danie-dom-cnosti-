#!/bin/sh
# Spustí všetky testy bez Androidu. Potrebuje javac/java (JDK 17) a kotlinc v PATH.
# org.json (na Androide je vstavaný) sa stiahne do tools/libs pri prvom spustení.
set -e
cd "$(dirname "$0")/.."
S=app/src/main/java/sk/domov/ovladanie
OUT=out/tests
JSON=tools/libs/json-20240303.jar
mkdir -p "$OUT" tools/libs
[ -f "$JSON" ] || curl -sSL -o "$JSON" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar

javac -d "$OUT/mock" $S/tuya/TuyaDevice.java tools/MockTest.java && java -cp "$OUT/mock" MockTest
# brána (Zigbee podzariadenia cez cid): tvary požiadaviek, filtrovanie cudzích hlásení, regresia bez cid
javac -d "$OUT/gateway" $S/tuya/TuyaDevice.java tools/GatewayProtocolTest.java && java -cp "$OUT/gateway" sk.domov.ovladanie.tuya.GatewayProtocolTest
javac -d "$OUT/disc" $S/tuya/TuyaDiscovery.java tools/DiscoveryTest.java && java -cp "$OUT/disc" DiscoveryTest
kotlinc $S/Light.kt tools/LightTest.kt -include-runtime -d "$OUT/light.jar" 2>/dev/null && java -jar "$OUT/light.jar"
kotlinc $S/Fryer.kt tools/FryerTest.kt -include-runtime -d "$OUT/fryer.jar" 2>/dev/null && java -jar "$OUT/fryer.jar"
javac -d "$OUT/stubs" tools/stubs/android/content/*.java
PURE="$S/Schema.kt $S/Templates.kt $S/Device.kt $S/Light.kt $S/Fryer.kt $S/Found.kt $S/TuyaCloud.kt $S/Presence.kt $S/Sun.kt $S/Household.kt $S/Scenes.kt $S/DeviceList.kt $S/LightChannels.kt $S/Live.kt $S/SafeOff.kt $S/FryerWatch.kt $S/Backup.kt $S/Weather.kt $S/AppShare.kt $S/Http.kt $S/Zigbee.kt $S/Masked.kt $S/CommandLog.kt"
kotlinc -cp "$OUT/stubs:$JSON" $PURE tools/SchemaTest.kt -include-runtime -d "$OUT/schema.jar" 2>/dev/null
java -cp "$OUT/schema.jar:$OUT/stubs:$JSON" SchemaTestKt
kotlinc -cp "$OUT/stubs:$JSON" $PURE tools/CloudTest.kt -include-runtime -d "$OUT/cloud.jar" 2>/dev/null
java -cp "$OUT/cloud.jar:$OUT/stubs:$JSON" CloudTestKt
kotlinc -cp "$OUT/stubs:$JSON" $PURE tools/PresenceTest.kt -include-runtime -d "$OUT/presence.jar" 2>/dev/null
java -cp "$OUT/presence.jar:$OUT/stubs:$JSON" PresenceTestKt
kotlinc -cp "$OUT/stubs:$JSON" $PURE tools/HouseholdTest.kt -include-runtime -d "$OUT/household.jar" 2>/dev/null
java -cp "$OUT/household.jar:$OUT/stubs:$JSON" HouseholdTestKt
kotlinc -cp "$OUT/stubs:$JSON" $PURE tools/ScenesTest.kt -include-runtime -d "$OUT/scenes.jar" 2>/dev/null
java -cp "$OUT/scenes.jar:$OUT/stubs:$JSON" ScenesTestKt
kotlinc -cp "$OUT/stubs:$JSON" $PURE tools/DeviceListTest.kt -include-runtime -d "$OUT/devicelist.jar" 2>/dev/null
java -cp "$OUT/devicelist.jar:$OUT/stubs:$JSON" DeviceListTestKt
kotlinc -cp "$OUT/stubs:$JSON" $PURE tools/LightChannelsTest.kt -include-runtime -d "$OUT/lightchannels.jar" 2>/dev/null
java -cp "$OUT/lightchannels.jar:$OUT/stubs:$JSON" LightChannelsTestKt
# upozornenia fritézy
kotlinc -cp "$OUT/stubs:$JSON" $PURE tools/FryerWatchTest.kt -include-runtime -d "$OUT/fryerwatch.jar" 2>/dev/null
java -cp "$OUT/fryerwatch.jar:$OUT/stubs:$JSON" FryerWatchTestKt
# záloha a obnovenie
kotlinc -cp "$OUT/stubs:$JSON" $PURE tools/BackupTest.kt -include-runtime -d "$OUT/backup.jar" 2>/dev/null
java -cp "$OUT/backup.jar:$OUT/stubs:$JSON" BackupTestKt
# počasie
kotlinc -cp "$OUT/stubs:$JSON" $PURE tools/WeatherTest.kt -include-runtime -d "$OUT/weather.jar" 2>/dev/null
java -cp "$OUT/weather.jar:$OUT/stubs:$JSON" WeatherTestKt
# jeden prepočet jasu (hodnota <-> %)
kotlinc -cp "$OUT/stubs:$JSON" $PURE tools/BrightTest.kt -include-runtime -d "$OUT/bright.jar" 2>/dev/null
java -cp "$OUT/bright.jar:$OUT/stubs:$JSON" BrightTestKt
# bezpečné hromadné vypínanie (fritéza, ohrievač, neznáme zariadenia)
kotlinc -cp "$OUT/stubs:$JSON" $PURE tools/SafeOffTest.kt -include-runtime -d "$OUT/safeoff.jar" 2>/dev/null
java -cp "$OUT/safeoff.jar:$OUT/stubs:$JSON" SafeOffTestKt
# plynulé posuvníky: obmedzovač a ochrana pred starým hlásením
kotlinc $S/Live.kt tools/LiveTest.kt -include-runtime -d "$OUT/live.jar" 2>/dev/null
java -jar "$OUT/live.jar"
# evohome (Honeywell): klient proti lokálnemu falošnému serveru s odpoveďami z evohome-async
kotlinc -cp "$JSON" $S/Evohome.kt $S/Http.kt $S/Masked.kt tools/EvohomeTest.kt -include-runtime -d "$OUT/evohome.jar" 2>/dev/null
java -cp "$OUT/evohome.jar:$JSON" EvohomeTestKt
# zdieľanie appky cez QR: odkaz, token, šifrovanie, výber zariadení
kotlinc -cp "$OUT/stubs:$JSON" $PURE tools/ShareTest.kt -include-runtime -d "$OUT/share.jar" 2>/dev/null
java -cp "$OUT/share.jar:$OUT/stubs:$JSON" ShareTestKt
# východ a západ slnka: referencie timeanddate.com / sunrise-sunset.org pre Bánovce nad Bebravou, výber polohy
kotlinc -cp "$OUT/stubs:$JSON" $PURE tools/SunTest.kt -include-runtime -d "$OUT/sun.jar" 2>/dev/null
java -cp "$OUT/sun.jar:$OUT/stubs:$JSON" SunTestKt
# fáza mesiaca: Meeus 48.2, názvy, ďalšia hlavná fáza, porovnanie s PyEphem
kotlinc $S/Moon.kt tools/MoonTest.kt -include-runtime -d "$OUT/moon.jar" 2>/dev/null
java -jar "$OUT/moon.jar"
# meniny: kalendár 366 dní, formát dátumu
kotlinc $S/Meniny.kt tools/MeninyTest.kt -include-runtime -d "$OUT/meniny.jar" 2>/dev/null
java -jar "$OUT/meniny.jar"
# nákupný zoznam
kotlinc -cp "$JSON" $S/Shopping.kt tools/ShoppingTest.kt -include-runtime -d "$OUT/shopping.jar" 2>/dev/null
java -cp "$OUT/shopping.jar:$JSON" ShoppingTestKt
# ranný prehľad: skladanie textu notifikácie, výpadky zdrojov, zapnuté zariadenia, plánovanie
kotlinc -cp "$OUT/stubs:$JSON" $PURE $S/Events.kt $S/Meniny.kt $S/Shopping.kt $S/MorningBrief.kt tools/MorningBriefTest.kt -include-runtime -d "$OUT/morningbrief.jar" 2>/dev/null
java -cp "$OUT/morningbrief.jar:$OUT/stubs:$JSON" MorningBriefTestKt
# udalosti a narodeniny
kotlinc -cp "$JSON" $S/Events.kt $S/Meniny.kt $S/Household.kt $S/Calendar.kt tools/EventsTest.kt -include-runtime -d "$OUT/events.jar" 2>/dev/null
java -cp "$OUT/events.jar:$JSON" EventsTestKt
# kalendár (mriežka, viditeľnosť, časované pripomienky)
kotlinc -cp "$JSON" $S/Events.kt $S/Meniny.kt $S/Household.kt $S/Calendar.kt tools/CalendarTest.kt -include-runtime -d "$OUT/calendar.jar" 2>/dev/null
java -cp "$OUT/calendar.jar:$JSON" CalendarTestKt
# synchronizácia: AES-GCM, last-writer-wins, tombstone, balenie, starý JSON
kotlinc -cp "$JSON" $S/Events.kt $S/Meniny.kt $S/Shopping.kt $S/HouseSync.kt tools/HouseSyncTest.kt -include-runtime -d "$OUT/housesync.jar" 2>/dev/null
java -cp "$OUT/housesync.jar:$JSON" HouseSyncTestKt
# súkromný chat: AES-GCM, typ správy, video/limit, náhľad, potvrdenia, záloha príloh
kotlinc -cp "$JSON" $S/HouseSync.kt $S/Chat.kt tools/ChatTest.kt -include-runtime -d "$OUT/chat.jar" 2>/dev/null
java -cp "$OUT/chat.jar:$JSON" ChatTestKt
# jedálniček: zhoda surovín, ks/g, plán nákupu, zmeny plánu, Uvarené?, import receptu, návrh týždňa
kotlinc -cp "$JSON" $S/Events.kt $S/Meniny.kt $S/Shopping.kt $S/HouseSync.kt $S/Meals.kt $S/MealShop.kt $S/RecipeImport.kt $S/MealPlanner.kt tools/MealsTest.kt -include-runtime -d "$OUT/meals.jar" 2>/dev/null
java -cp "$OUT/meals.jar:$JSON" MealsTestKt
kotlinc -cp "$JSON" $S/Shopping.kt $S/Meals.kt $S/MealShop.kt $S/RecipeImport.kt $S/MealPlanner.kt tools/MealAutoTest.kt -include-runtime -d "$OUT/mealauto.jar" 2>/dev/null
java -cp "$OUT/mealauto.jar:$JSON" MealAutoTestKt
