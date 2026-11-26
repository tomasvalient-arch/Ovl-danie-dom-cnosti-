import sk.domov.ovladanie.Chat
import sk.domov.ovladanie.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

fun main() {
    fun check(name: String, cond: Boolean) {
        if (!cond) throw IllegalStateException("FAIL: $name")
        println("ok  $name")
    }

    val key = Chat.aesKey("test-household-key-xxxx")
    check("kľúč má 32 B", key.size == 32)
    check("téma je neuhádnuteľná a stabilná", Chat.topic("abc") == Chat.topic("abc") && Chat.topic("abc").startsWith("domovc") && Chat.topic("abc") != Chat.topic("abd"))
    check("iný kľúč → iná téma aj AES", !Chat.aesKey("a").contentEquals(Chat.aesKey("b")))

    val token = Chat.encryptText("""{"v":1,"t":"chat","f":"me","m":{"id":"1","tx":"Bryndza je v chladničke"}}""", key)
    check("token nie je otvorený text", !Chat.looksLikePlaintextLeak(token) && !token.contains("Bryndza"))
    val back = Chat.decryptText(token, key)
    check("dešifrovanie vráti pôvodný text", back != null && "Bryndza" in back!!)
    check("zlý kľúč vráti null", Chat.decryptText(token, Chat.aesKey("iny")) == null)
    check("poškodený token vráti null", Chat.decryptText("!!!!", key) == null)

    val blob = "ahoj fotka".toByteArray()
    val enc = Chat.encryptBytes(blob, key)
    check("príloha má hlavičku DMVC1", String(enc, 0, 5, Charsets.US_ASCII) == "DMVC1")
    check("príloha sa dešifruje", Chat.decryptBytes(enc, key)?.toString(Charsets.UTF_8) == "ahoj fotka")
    check("príloha so zlým kľúčom je null", Chat.decryptBytes(enc, Chat.aesKey("x")) == null)

    check("video podľa mime", Chat.isVideo("video/mp4", "x.bin"))
    check("video podľa prípony", Chat.isVideo("application/octet-stream", "film.MOV"))
    check("pdf nie je video", !Chat.isVideo("application/pdf", "ucet.pdf"))
    check("fotka nie je video", !Chat.isVideo("image/jpeg", "img.jpg"))
    check("limit 15 MB", Chat.tooBig(15_000_001) && !Chat.tooBig(15_000_000))
    check("dôvod video", Chat.rejectReason("video/mp4", "a.mp4", 10) == "Videá v chate nie sú podporované.")
    check("dôvod veľkosť", "15 MB" in Chat.rejectReason("application/pdf", "a.pdf", 20_000_000).orEmpty())
    check("ok súbor", Chat.rejectReason("application/pdf", "a.pdf", 240_000) == null)

    check("náhľad textu", Chat.previewBody("text", "Cestou kúpim smotanu", "") == "Cestou kúpim smotanu")
    check("náhľad fotky", Chat.previewBody("photo", "", "x.jpg") == "Fotka")
    check("náhľad súboru", Chat.previewBody("file", "", "Účet za elektrinu.pdf") == "Účet za elektrinu.pdf")

    check("1:1 je usporiadané", Chat.dmThread("b", "a") == Chat.dmThread("a", "b") && Chat.dmThread("a", "b").startsWith("dm:"))
    check("pri 1 ďalšom členovi rovno spoločný", Chat.openSharedDirectly(1) && !Chat.openSharedDirectly(2))

    val msg = Chat.WireMsg(
        type = Chat.TYPE_CHAT, fromId = "p1", fromName = "Tomáš", id = "id-1",
        thread = Chat.THREAD_SHARED, kind = Chat.KIND_TEXT, text = "Ahoj",
        fileName = "", fileSize = 0, mime = "", at = 1L,
    )
    val parsed = Chat.parse(Chat.pack(msg))
    check("obal tam a späť", parsed != null && parsed.id == "id-1" && parsed.fromName == "Tomáš" && parsed.text == "Ahoj")
    val receipt = Chat.WireMsg(
        type = Chat.TYPE_RECEIPT, fromId = "p2", fromName = "Jana", id = "r1",
        thread = Chat.THREAD_SHARED, kind = Chat.KIND_TEXT, text = "",
        fileName = "", fileSize = 0, mime = "", at = 2L, receiptFor = "id-1",
    )
    check("potvrdenie prečítania", Chat.parse(Chat.pack(receipt))?.receiptFor == "id-1")
    check("odmietne cudzí typ", Chat.parse("""{"v":1,"t":"items","f":"p","m":{"id":"1"}}""") == null)

    check("neodoslané nemá check", Chat.tick(true, Chat.STATUS_PENDING, emptyList(), listOf("p2")) == Chat.Tick.NONE)
    check("odoslané = jeden check", Chat.tick(true, Chat.STATUS_SENT, emptyList(), listOf("p2")) == Chat.Tick.SENT)
    check("prečítané všetkými = dva checky", Chat.tick(true, Chat.STATUS_SENT, listOf("p2"), listOf("p2")) == Chat.Tick.READ)
    check("pri troch členoch čaká na všetkých", Chat.tick(true, Chat.STATUS_SENT, listOf("p2"), listOf("p2", "p3")) == Chat.Tick.SENT)
    check("prichádzajúca nemá check", Chat.tick(false, Chat.STATUS_SENT, listOf("p1"), listOf("p2")) == Chat.Tick.NONE)

    val stored = ChatMessage("i", Chat.THREAD_SHARED, "p1", "Tomáš", Chat.KIND_FILE, "", "Účet.pdf", 2400, "application/pdf", "files/i", 10, Chat.STATUS_SENT, true, listOf("p2"), false)
    val round = ChatMessage.fromJson(stored.toJson())
    check("JSON správy", round != null && round.fileName == "Účet.pdf" && round.readers == listOf("p2"))
    val arr = ChatMessage.listToJson(listOf(stored))
    check("zoznam JSON", ChatMessage.listFromJson(arr).size == 1)

    val files = listOf("a" to ByteArray(100), "b" to ByteArray(100), "c" to ByteArray(8_000_000))
    val (pick, skip) = Chat.pickBackupFiles(files, budget = 1000)
    check("záloha príloh dodrží rozpočet", pick.size == 2 && skip == listOf("c"))
    val encF = Chat.encodeBackupFiles(mapOf("a" to "hi".toByteArray()))
    check("záloha príloh base64", Chat.decodeBackupFiles(encF)["a"]?.toString(Charsets.UTF_8) == "hi")

    check("detekcia úniku", Chat.looksLikePlaintextLeak("""{"v":1,"t":"chat","m":{"tx":"Ahoj"}}"""))

    // 1.65.1: chat sa otvorí hneď po pridaní do domácnosti, aj keď druhý telefón nepoznáme zo správ „som doma“
    check("chat pripravený: nikoho nepoznáme, ale telefón je v domácnosti", Chat.isReady(0, true))
    check("chat pripravený: poznáme člena", Chat.isReady(1, false))
    check("chat nepripravený: bez domácnosti a bez členov", !Chat.isReady(0, false))
    check("jeden člen = rovno spoločný rozhovor", Chat.openSharedDirectly(0) && Chat.openSharedDirectly(1) && !Chat.openSharedDirectly(2))
    fun msg(id: String, from: String, name: String, at: Long, mine: Boolean = false) =
        ChatMessage(id, Chat.THREAD_SHARED, from, name, Chat.KIND_TEXT, "x", "", 0, "", "", at, Chat.STATUS_SENT, mine, emptyList(), false)
    check("bez správ nie sú mená", Chat.peerNames(emptyList()).isEmpty())
    check("vlastné správy sa nepočítajú", Chat.peerNames(listOf(msg("1", "p1", "Tomáš", 1, mine = true))).isEmpty())
    val names = Chat.peerNames(listOf(msg("1", "p2", "Jana", 1), msg("2", "p1", "Tomáš", 2, mine = true), msg("3", "p2", "Janka", 3)))
    check("jedno meno na odosielateľa, podľa najnovšej správy", names == listOf("Janka"))
    check("viac odosielateľov, najčerstvejší prvý", Chat.peerNames(listOf(msg("1", "p2", "Jana", 5), msg("2", "p3", "Eva", 9))) == listOf("Eva", "Jana"))
    check("prázdne meno sa nahradí", Chat.peerNames(listOf(msg("1", "p2", "", 1))) == listOf("Telefón"))
    check("správa bez ID odosielateľa sa preskočí", Chat.peerNames(listOf(msg("1", "", "Duch", 1))).isEmpty())
    check("potvrdenie prečítania bez zoznamu členov stačí od jedného", Chat.tick(true, Chat.STATUS_SENT, listOf("p2"), emptyList()) == Chat.Tick.READ)
    println("ChatTest ok")
}
