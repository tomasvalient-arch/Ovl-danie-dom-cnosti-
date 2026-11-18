import sk.domov.ovladanie.*

// Zdieľanie appky cez QR: odkaz, token, šifrovanie a obsah (zariadenia + kľúč domácnosti).

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

fun main() {
    val now = 1_800_000_000_000L
    val offer = ShareOffer.create(now)
    check("token 16 B a kľúč 32 B (base64url)", AppShareCrypto.validId(offer.token, 16) && AppShareCrypto.validId(offer.key, 32))
    check("dva tokeny sa líšia", ShareOffer.create(now).token != offer.token)
    check("platí 10 min", offer.valid(now) && offer.valid(now + 9 * 60_000) && !offer.valid(now + 10 * 60_000) && !offer.valid(now - 1))
    check("porovnanie tokenu", offer.tokenMatches(offer.token) && !offer.tokenMatches(null) && !offer.tokenMatches(offer.token + "x") && !offer.tokenMatches(""))

    val link = ShareLink("192.168.1.23", 8765, offer.token, offer.key)
    val url = link.url()
    check("odkaz: kľúč až za # (prehliadač ho neposiela)", url.startsWith("http://192.168.1.23:8765/d?t=${offer.token}#k=") && url.substringBefore('#').contains(offer.key).not())
    check("odkaz sa dá prečítať späť", ShareLink.parse(url) == link)
    check("medzery okolo odkazu nevadia", ShareLink.parse("  $url\n") == link)
    check("cudzí odkaz / bez kľúča / zlý port → null",
        ShareLink.parse("https://example.com") == null && ShareLink.parse(url.substringBefore('#')) == null &&
            ShareLink.parse(url.replace(":8765", ":99999")) == null && ShareLink.parse("DOMOV-POZVANKA:abc") == null)
    check("IPv6 adresa", ShareLink.parse(ShareLink("fe80::1", 8766, offer.token, offer.key).url())?.host == "fe80::1")

    val devices = listOf(
        Device(name = "LED pas kuchyňa", ip = "192.168.1.50", devId = "bf123", localKey = "0123456789abcdef"),
        Device(name = "Zásuvka práčka", ip = "192.168.1.51", devId = "bf456", localKey = "fedcba9876543210"),
    )
    val houseKey = HouseholdCrypto.newKey()
    val payload = SharePayload(from = "Tomáš", devicesJson = exportDevicesJson(devices.take(1), houseKey))
    val enc = AppShareCrypto.encrypt(payload.toJson(), offer.key, offer.token)
    check("šifrovaný obsah neobsahuje local key ani názov", !enc.contains("0123456789abcdef") && !enc.contains("LED"))
    val back = SharePayload.fromJson(AppShareCrypto.decrypt(enc, offer.key, offer.token))
    val imported = parseTinytuyaJson(back.devicesJson)
    check("druhý telefón dostane len vybrané zariadenie aj s local key", imported.size == 1 && imported[0].devId == "bf123" && imported[0].localKey == "0123456789abcdef")
    check("aj kľúč domácnosti", HouseholdCrypto.parseExport(back.devicesJson) == houseKey && back.from == "Tomáš")
    check("cloud a kúrenie sa bez výberu neposielajú", back.cloud == null && back.heatEmail == null && !payload.toJson().contains("cloud") && !payload.toJson().contains("heat"))
    val other = ShareOffer.create(now)
    check("zlý kľúč → chyba", runCatching { AppShareCrypto.decrypt(enc, other.key, offer.token) }.isFailure)
    check("iný token (AAD) → chyba", runCatching { AppShareCrypto.decrypt(enc, offer.key, other.token) }.isFailure)
    check("poškodené dáta → chyba", runCatching { AppShareCrypto.decrypt(enc.dropLast(4) + "AAAA", offer.key, offer.token) }.isFailure)

    val full = SharePayload("Tomáš", "[]", CloudConfig("id1", "sec1", DataCenter.EU), "a@b.sk", "heslo")
    val fullBack = SharePayload.fromJson(full.toJson())
    check("zvolený cloud a kúrenie prejdú", fullBack.cloud == full.cloud && fullBack.heatEmail == "a@b.sk" && fullBack.heatPassword == "heslo")
    check("neznáma verzia → chyba", runCatching { SharePayload.fromJson("{\"v\":2}") }.isFailure)
    println("ShareTest: všetko OK")
}
