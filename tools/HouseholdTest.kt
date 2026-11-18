import sk.domov.ovladanie.*

// Test domácnosti s viacerými telefónmi bez Androidu: HMAC správ (referenčná správa vytvorená v Pythone cez hmac/hashlib),
// ochrana proti podvrhnutiu a zopakovaniu, rozhodovanie pri odchode a príchode, pozvánka a export kľúča.

fun check(name: String, cond: Boolean) {
    println((if (cond) "OK   " else "FAIL ") + name)
    if (!cond) throw RuntimeException("FAIL: $name")
}

const val KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
const val REF = "DOMOV1 f4401b29 a1b2c3d4e5f6 1794830400000 B UHJpYXRlxL5rYQ 33d193d75a97d124d671f41d7ba307fd24f38142eebda85b97d26b2329f8a57a"
const val T0 = 1794830400000L
const val MIN = 60_000L

fun main() {
    // ---------------------------------------------------------------- správy
    check("kľúč je platný (32 bajtov)", HouseholdCrypto.isValidKey(KEY) && !HouseholdCrypto.isValidKey("abc"))
    check("ID domácnosti = Python", HouseholdCrypto.householdId(KEY) == "f4401b29")
    check("správa = Python (HMAC-SHA256)", HouseholdCrypto.encode(KEY, "a1b2c3d4e5f6", "Priateľka", T0, MsgType.BEAT) == REF)
    val msg = HouseholdCrypto.decode(KEY, REF, T0 + 2000)
    check("dekódovanie: meno s diakritikou, typ, čas", msg == HouseMsg("f4401b29", "a1b2c3d4e5f6", "Priateľka", T0, MsgType.BEAT))
    check("zmenené meno -> podpis nesedí", HouseholdCrypto.decode(KEY, REF.replace("UHJpYXRlxL5rYQ", "VG9tw6HFoQ"), T0) == null)
    check("zmenený čas -> podpis nesedí", HouseholdCrypto.decode(KEY, REF.replace("1794830400000", "1794830460000"), T0) == null)
    check("cudzí kľúč -> zahodí", HouseholdCrypto.decode(HouseholdCrypto.newKey(), REF, T0) == null)
    val other = HouseholdCrypto.newKey()
    val forged = HouseholdCrypto.encode(other, "a1b2c3d4e5f6", "Priateľka", T0, MsgType.BEAT).split(' ').toMutableList()
    forged[1] = "f4401b29"
    check("podvrh so správnym ID domácnosti, ale iným kľúčom -> zahodí", HouseholdCrypto.decode(KEY, forged.joinToString(" "), T0) == null)
    check("stará správa (11 min) -> zahodí", HouseholdCrypto.decode(KEY, REF, T0 + 11 * MIN) == null)
    check("správa z budúcnosti (11 min) -> zahodí", HouseholdCrypto.decode(KEY, REF, T0 - 11 * MIN) == null)
    check("zopakovaná správa (nie novšia než posledná) -> zahodí", HouseholdCrypto.decode(KEY, REF, T0, lastSentAt = { T0 }) == null)
    check("novšia správa prejde", HouseholdCrypto.decode(KEY, HouseholdCrypto.encode(KEY, "a1b2c3d4e5f6", "P", T0 + 1, MsgType.QUERY), T0, lastSentAt = { T0 })?.type == MsgType.QUERY)
    check("nezmysel -> null", HouseholdCrypto.decode(KEY, "ahoj", T0) == null && HouseholdCrypto.decode(KEY, "", T0) == null)
    check("nové ID telefónu a kľúč sú náhodné", HouseholdCrypto.newPhoneId() != HouseholdCrypto.newPhoneId() && HouseholdCrypto.newKey() != HouseholdCrypto.newKey() &&
        HouseholdCrypto.newPhoneId().length == 12)

    // ---------------------------------------------------------------- pozvánka a export
    val inv = HouseholdCrypto.invite(KEY)
    check("pozvánka -> kľúč", HouseholdCrypto.parseInvite(inv) == KEY)
    check("pozvánka vložená v texte správy", HouseholdCrypto.parseInvite("Ahoj, tu je: $inv\nPá") == KEY)
    check("poškodená pozvánka -> null", HouseholdCrypto.parseInvite(inv.dropLast(5)) == null)
    val dev = Device(name = "Lampa", ip = "192.168.1.5", devId = "bf0011", localKey = "0123456789abcdef")
    val exported = exportDevicesJson(listOf(dev), KEY)
    check("export obsahuje kľúč domácnosti", HouseholdCrypto.parseExport(exported) == KEY)
    check("import zariadení prvok domácnosti preskočí", parseTinytuyaJson(exported).map { it.devId } == listOf("bf0011"))
    check("export bez kľúča", HouseholdCrypto.parseExport(exportDevicesJson(listOf(dev))) == null)
    check("parseExport berie aj pozvánku", HouseholdCrypto.parseExport(inv) == KEY)

    // ---------------------------------------------------------------- členovia
    val self = "aaaaaaaaaaaa"
    var members = emptyList<Member>()
    fun beat(id: String, name: String, at: Long) {
        val m = HouseholdCrypto.decode(KEY, HouseholdCrypto.encode(KEY, id, name, at, MsgType.BEAT), at)!!
        members = HouseDecision.receive(members, self, m, at)
    }
    beat("bbbbbbbbbbbb", "Priateľka", T0)
    beat(self, "Ja", T0)
    check("vlastné správy sa ignorujú", members.map { it.id } == listOf("bbbbbbbbbbbb"))
    beat("bbbbbbbbbbbb", "Priateľka", T0 + 45_000)
    check("člen sa aktualizuje, nezdvojí", members.size == 1 && members[0].lastSeen == T0 + 45_000)
    check("členovia sa uložia a načítajú", HouseDecision.membersFromJson(HouseDecision.membersToJson(members)) == members)

    // ---------------------------------------------------------------- odchod
    val w = 10 * MIN
    // ja odchádzam o 1 min po poslednej správe priateľky -> je doma, nezhasínať
    var stay = HouseDecision.stayingHome(members, self, T0 + 45_000 + MIN, w)
    check("odchod: priateľka sa ozvala pred 1 min -> nezhasínať", stay.map { it.name } == listOf("Priateľka"))
    check("text do histórie", HouseDecision.stayText(stay) + " – nezhasínam." == "Doma je ešte Priateľka – nezhasínam.")
    // telefón priateľky spí, posledná správa pred 9 min -> stále doma
    check("odchod: posledná správa pred 9 min (spánok) -> nezhasínať",
        HouseDecision.stayingHome(members, self, T0 + 45_000 + 9 * MIN, w).isNotEmpty())
    // priateľka odišla pred pol hodinou -> zhasnúť
    check("odchod: posledná správa pred 30 min -> zhasnúť", HouseDecision.stayingHome(members, self, T0 + 30 * MIN, w).isEmpty())
    check("odchod: kratšie okno 3 min -> pred 5 min už nie je doma", HouseDecision.stayingHome(members, self, T0 + 45_000 + 5 * MIN, 3 * MIN).isEmpty())
    check("odchod: bez iných členov -> zhasnúť", HouseDecision.stayingHome(emptyList(), self, T0, w).isEmpty())
    beat("cccccccccccc", "Jano", T0 + 50_000)
    stay = HouseDecision.stayingHome(members, self, T0 + 2 * MIN, w)
    check("odchod: dvaja doma -> text s oboma", HouseDecision.stayText(stay) == "Doma sú ešte Jano, Priateľka")

    // ---------------------------------------------------------------- príchod
    val leftAt = T0 + 2 * MIN // stav „mimo domu“ od tohto času
    check("príchod: staré správy spred odchodu sa nepočítajú", HouseDecision.alreadyHome(members, self, leftAt).isEmpty())
    beat("bbbbbbbbbbbb", "Priateľka", T0 + 60 * MIN) // odpoveď na otázku po návrate
    check("príchod: priateľka odpovedala po návrate -> už je doma", HouseDecision.alreadyHome(members, self, leftAt).map { it.name } == listOf("Priateľka"))
    check("zoznam Kto je doma", HouseDecision.isHome(members.first { it.name == "Priateľka" }, T0 + 61 * MIN, w) &&
        !HouseDecision.isHome(members.first { it.name == "Jano" }, T0 + 61 * MIN, w))

    // ---------------------------------------------------------------- scenár: dva telefóny
    // Tomáš odíde 18:00, Priateľka je doma (telefón posiela každých 45 s) -> Tomášov telefón nezhasne.
    // Priateľka odíde 19:00 -> jej telefón videl Tomáša naposledy 18:00 (60 min) -> zhasne.
    val tomas = "111111111111"; val pria = "222222222222"
    var tMembers = emptyList<Member>(); var pMembers = emptyList<Member>()
    var t = T0
    while (t < T0 + 60 * MIN) {
        if (t <= T0) pMembers = HouseDecision.receive(pMembers, pria, HouseMsg("", tomas, "Tomáš", t, MsgType.BEAT), t)
        if (t <= T0) tMembers = HouseDecision.receive(tMembers, tomas, HouseMsg("", pria, "Priateľka", t, MsgType.BEAT), t)
        t += 45_000
    }
    check("scenár: Tomáš odchádza, Priateľka doma -> Tomáš nezhasne", HouseDecision.stayingHome(tMembers, tomas, T0 + 10_000, w).isNotEmpty())
    check("scenár: Priateľka odchádza o hodinu -> zhasne", HouseDecision.stayingHome(pMembers, pria, T0 + 60 * MIN, w).isEmpty())

    // ---------------------------------------------------------------- pomocné
    check("broadcast adresa", HomeNetwork.broadcastOf("192.168.1.0/24") == "192.168.1.255" && HomeNetwork.broadcastOf("10.0.0.0/16") == "10.0.255.255" &&
        HomeNetwork.broadcastOf("x") == "255.255.255.255")
    check("nastavenia domácnosti: predvolené 10 min, pri príchode nezapínať keď je niekto doma",
        PresenceConfig.fromJson(null).let { it.memberWindowMin == 10 && !it.arriveIfSomeoneHome } &&
            PresenceConfig.fromJson(PresenceConfig(memberWindowMin = 5, arriveIfSomeoneHome = true).toJson().toString()).let { it.memberWindowMin == 5 && it.arriveIfSomeoneHome })

    println("HouseholdTest: všetko OK")
}
