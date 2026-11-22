# Protokol Domov PC (verzia 1)

Protokol medzi programom Domov PC (Windows, adresár `pc/`) a appkou Domov (balík `sk.domov.ovladanie.pc`).
Bez cudzích knižníc: TCP + AES-256-GCM, kľúče z HMAC-SHA256 / HKDF-SHA256 / PBKDF2-HMAC-SHA256.
Čísla sú vždy big-endian. Program prijíma spojenia len z domácej siete (súkromné adresy, link-local, loopback).

## Porty

| Port | Čo | Kto sa môže pripojiť |
|------|----|----------------------|
| 47810 TCP | ovládanie (šifrované) | telefóny z domácej siete |
| 47810 UDP | hľadanie PC v sieti | telefóny z domácej siete |
| 47811 TCP | stránka správy | len `127.0.0.1` (prehliadač na tom istom PC) |

## Hľadanie PC (UDP)

Telefón pošle `DOMOVPC?` (broadcast siete alebo priamo na známu adresu), PC odpovie
`DOMOVPC!` + verzia (u8) + ID PC (16 B) + port TCP (u16) + názov (zvyšok). Odpovedá len na žiadosti z domácej siete.

## Rámce na TCP

Každý rámec: `u32 dĺžka` + telo. Handshake je nešifrovaný (najviac 512 B), potom je každé telo jedna šifrovaná správa:
`AES-256-GCM(typ u8 || dáta)`, nonce = `smer u8 || 0 0 0 || počítadlo u64` (počítadlo rastie po jednej na smer,
takže nič sa nedá zopakovať ani vynechať). Kľúče: `c2s` (telefón → PC) a `s2c` (PC → telefón).
Najväčšia správa od telefónu je 1 MiB.

## Handshake (vzájomné preukázanie kľúča)

Kľúč `K` je buď **dlhodobý kľúč telefónu** (režim 0, po spárovaní), alebo **kľúč z párovacieho kódu** (režim 1):
`K = PBKDF2-HMAC-SHA256(kód, soľ = "domov-pc/1/pair" || ID PC, 200 000 iterácií, 32 B)`. Kód má 10 znakov
z abecedy bez zameniteľných znakov (`ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, 50 bitov), zobrazuje sa ako `ABCDE-FGHJK`.

1. **Telefón → PC** `C_HELLO`: `0x01, verzia, režim, ID telefónu (16 B), nonce telefónu (32 B), dĺžka názvu, názov (UTF-8, ≤ 48 B)`
2. **PC → telefón** `S_HELLO`: `0x02, verzia, ID PC (16 B), nonce PC (32 B), dôkaz PC (32 B)`
3. **Telefón → PC** `C_PROOF`: `0x03, dôkaz telefónu (32 B)`

Dôkaz = `HMAC-SHA256(K, "domov-pc/1/" || rola || režim || ID telefónu || nonce1 || nonce2 || ID PC || dĺžka názvu || názov)`
(rola `server`/`client`; `nonce1`/`nonce2` sú nonce protistrany a vlastný). Telefón overí aj ID PC, ak ho pozná z QR kódu.
Chyba: rámec `0x7F, kód, text` – kódy: 1 neznámy telefón, 2 adresa zablokovaná po viacerých omyloch, 3 párovanie nie je zapnuté,
4 zlý kľúč alebo kód, 5 chyba protokolu, 6 obsadené. Kód 2 znamená zablokovanú adresu.

Kľúče relácie: `prk = HMAC(nonce telefónu || nonce PC, K)`, `c2s = HMAC(prk, "domov-pc/1/c2s" || 0x01)`,
`s2c = HMAC(prk, "domov-pc/1/s2c" || 0x01)`.

**Párovanie (režim 1)**: po úspešnom handshaku pošle PC správu `PAIRED` s novým náhodným dlhodobým kľúčom telefónu (32 B)
a okno párovania sa zatvorí (jedno spárovanie na jeden kód; kód platí 3 minúty). Po 5 zlých kódoch sa okno párovania zatvorí (treba ho otvoriť znova) a po 8 neúspešných pokusoch z jednej adresy ju PC na 10 minút zablokuje.

**Nový telefón vytlačí starý**: PC pošle starému telefónu `NOTICE` kód 7 (nemá sa sám pripájať späť) a zavrie ho.

## Správy PC → telefón

| Kód | Názov | Dáta |
|-----|-------|------|
| 0x10 | SESSION_INFO | verzia u8, ID PC (16 B), dĺžka názvu u8, názov, počet MAC u8, MAC × 6 B, príznaky u16 (1 = vie vypnúť/uspať, 2 = beží ako správca), aktívny monitor u8, počet monitorov u8, pre každý: x i32, y i32, šírka u32, výška u32, príznaky u8 (1 = hlavný). Posiela sa pri pripojení a pri zmene zostavy monitorov. |
| 0x11 | PAIRED | dlhodobý kľúč telefónu (32 B) |
| 0x12 | SCREEN | monitor u8, výstupná šírka u32, výška u32, delič u8, zdrojová šírka u32, výška u32, epocha u32. Nový obraz (po zmene monitora, rozlíšenia, deliča); telefón vytvorí čierny obraz tejto veľkosti. |
| 0x13 | RECTS | epocha u32, poradie u32, počet u16, potom pre každý pás: y u16, výška u16, dĺžka u32, JPEG. Pásy majú plnú šírku a výšku najviac 64 riadkov; posielajú sa len zmenené. |
| 0x15 | CLIP | text schránky (UTF-8) |
| 0x16 | NOTICE | kód u8, text. 1 plocha zamknutá, 2 plocha znova dostupná, 3 chyba zachytenia, 4 info, 5 príkaz napájania prijatý, 6 chyba schránky, 7 vytlačený iným telefónom |
| 0x17 | PONG | to, čo prišlo v PING |
| 0x18 | CURSOR | x u16, y u16 (0–65535 cez aktívny monitor), príznak u8 (1 = kurzor je na tomto monitore). Najviac 25× za sekundu a len po zmene. |

Tok obrazu je riadený potvrdeniami: PC neposiela ďalší obraz, kým telefón nepotvrdí (`ACK`) všetky okrem dvoch posledných.
Telefón musí potvrdiť **každý** RECTS (aj zastaraný z predošlej epochy).

## Správy telefón → PC

| Kód | Názov | Dáta |
|-----|-------|------|
| 0x20 | MOVE_REL | dx i16, dy i16 (v pixeloch obrazu telefónu; PC ich vynásobí deličom) |
| 0x21 | MOVE_ABS | x u16, y u16 (0–65535 cez aktívny monitor) |
| 0x22 | BUTTON | tlačidlo u8 (1 ľavé, 2 pravé, 3 stredné, 4 a 5 bočné), stav u8 (1 stlačené) |
| 0x23 | WHEEL | dx i16, dy i16 (120 = jeden zárez, kladné dy = hore) |
| 0x24 | KEY | virtuálny kláves Windows u16, príznaky u8 (bit 0 = stlačené) |
| 0x25 | TEXT | text UTF-8 (≤ 4096 B), PC ho napíše ako Unicode znaky |
| 0x26 | ACK | poradie u32 z RECTS |
| 0x27 | CONFIG | snímky/s u8, kvalita JPEG u8, delič u8 (0 = automaticky), monitor u8 (255 = hlavný) |
| 0x28 | REFRESH | (prázdne) pošli celý obraz znova |
| 0x29 | CLIP_SET | text schránky (≤ 256 KB) |
| 0x2A | CLIP_GET | (prázdne) odpoveď je CLIP |
| 0x2B | POWER | 1 zamknúť, 2 uspať, 3 reštartovať, 4 vypnúť |
| 0x2C | PING | až 16 B, vrátia sa v PONG |
| 0x2D | RELEASE_ALL | pusti všetky držané klávesy a tlačidlá |

Keď sa telefón odpojí (aj nečakane), PC pustí všetky klávesy a tlačidlá, ktoré telefón držal.
Telefón posiela PING každé 3 s a spojenie zahodí, ak 12 s nič neprišlo.

## Čo sa nedá

- Zamknutá obrazovka, prihlasovanie a okná UAC (tzv. zabezpečená plocha): Windows ich neukáže ani neovláda iným programom. PC to
  oznámi (`NOTICE` 1) a telefón ukáže, že treba odomknúť na PC.
- Okná spustené ako správca pri programe bežiacom bez práv správcu (UIPI) nepríjmu vstup; `SESSION_INFO` to ohlási príznakom.
- Ctrl+Alt+Del posielať nejde (Windows ho rezervuje pre seba).
- Obsah chránený DRM (niektoré prehrávače) sa zachytí ako čierna plocha.
