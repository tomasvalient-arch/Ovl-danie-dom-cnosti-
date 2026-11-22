import sk.domov.ovladanie.pc.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.imageio.ImageIO;

/**
 * Overí Java klienta (PcClient) proti skutočnému PC programu so falošnou obrazovkou:
 * hľadanie v sieti, párovanie kódom, šifrovanie, obraz (JPEG), vstupy, schránka, prepnutie monitora,
 * napájanie, opätovné pripojenie uloženým kľúčom, zlé kľúče a uvoľnenie klávesov po odpojení.
 * Spúšťa sa cez tools/run-pc-tests.sh.
 */
public class PcClientTest {
    static int failures = 0;
    static final List<String> serverLog = Collections.synchronizedList(new ArrayList<>());

    static void check(boolean ok, String what) {
        if (ok) System.out.println("  OK   " + what);
        else { System.out.println("  CHYBA " + what); failures++; }
    }

    static class Events extends PcClient.Listener {
        final BlockingQueue<byte[]> paired = new LinkedBlockingQueue<>();
        final BlockingQueue<PcClient.Info> infos = new LinkedBlockingQueue<>();
        final BlockingQueue<PcClient.Screen> screens = new LinkedBlockingQueue<>();
        final BlockingQueue<Object[]> rects = new LinkedBlockingQueue<>();
        final BlockingQueue<String> clips = new LinkedBlockingQueue<>();
        final BlockingQueue<int[]> notices = new LinkedBlockingQueue<>();
        final BlockingQueue<String> closed = new LinkedBlockingQueue<>();
        PcClient client;
        @Override public void onPaired(byte[] k) { paired.add(k); }
        @Override public void onInfo(PcClient.Info i) { infos.add(i); }
        @Override public void onScreen(PcClient.Screen s) { screens.add(s); }
        @Override public void onRects(long e, long seq, List<PcClient.Rect> r) {
            rects.add(new Object[]{e, seq, r});
            if (client != null) client.ack(seq);
        }
        @Override public void onClip(String t) { clips.add(t); }
        final BlockingQueue<int[]> cursors = new LinkedBlockingQueue<>();
        @Override public void onCursor(int x, int y, boolean v) { cursors.add(new int[]{x, y, v ? 1 : 0}); }
        @Override public void onNotice(int c, String m) { notices.add(new int[]{c}); }
        @Override public void onClosed(String r) { closed.add(r); }
    }

    static <T> T poll(BlockingQueue<T> q, int ms) throws Exception { return q.poll(ms, TimeUnit.MILLISECONDS); }

    static String http(String method, String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("X-Domov-PC", "1");
        if (method.equals("POST")) { c.setDoOutput(true); c.getOutputStream().write("{}".getBytes()); }
        try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    static boolean waitLog(String line, int ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            synchronized (serverLog) { for (String l : serverLog) if (l.equals(line)) return true; }
            Thread.sleep(20);
        }
        return false;
    }

    static List<String> inputLines() {
        List<String> out = new ArrayList<>();
        synchronized (serverLog) { for (String l : serverLog) if (l.startsWith("INPUT ")) out.add(l.substring(6)); }
        return out;
    }

    public static void main(String[] args) throws Exception {
        String bin = args[0];
        Path home = Files.createTempDirectory("pctest");
        int port = 47820, web = 47821;
        ProcessBuilder pb = new ProcessBuilder(bin, "-port", "" + port, "-web-port", "" + web, "-trace-input", "-background");
        pb.environment().put("XDG_CONFIG_HOME", home.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) serverLog.add(l);
            } catch (IOException ignored) { }
        });
        t.setDaemon(true);
        t.start();
        try {
            run(port, web);
        } finally {
            proc.destroy();
        }
        System.out.println(failures == 0 ? "PcClientTest: OK" : "PcClientTest: CHYBY " + failures);
        if (failures != 0) {
            synchronized (serverLog) { for (String l : serverLog) System.out.println("  [server] " + l); }
            System.exit(1);
        }
    }

    static void run(int port, int web) throws Exception {
        String base = "http://127.0.0.1:" + web;
        for (int i = 0; i < 100; i++) {
            try { http("GET", base + "/api/state"); break; } catch (IOException e) { Thread.sleep(100); }
        }

        System.out.println("Pomocné funkcie");
        check(PcCrypto.normalizeCode("abcde-fghjk").equals("ABCDEFGHJK"), "normalizácia kódu");
        check(PcCrypto.normalizeCode("ABCDE-FGHIK").isEmpty(), "znak I nie je v abecede");
        byte[] mac = PcWol.parseMac("aa:bb:cc:01:02:03");
        byte[] pkt = PcWol.packet(mac);
        check(pkt.length == 102 && (pkt[0] & 0xff) == 0xff && pkt[5] == (byte) 0xff && pkt[6] == mac[0] && pkt[101] == mac[5], "magický paket Wake-on-LAN (102 B)");
        check(PcWol.formatMac(mac).equals("AA:BB:CC:01:02:03"), "formát MAC");

        System.out.println("Párovanie");
        String st = http("POST", base + "/api/pair/start");
        Matcher mc = Pattern.compile("\"code\":\"([A-Z0-9-]+)\"").matcher(st);
        Matcher mp = Pattern.compile("\"payload\":\"([^\"]+)\"").matcher(st);
        check(mc.find() && mp.find(), "stránka správy vrátila kód a obsah QR");
        String code = mc.group(1);
        PcLink link = PcLink.parse(mp.group(1));
        check(link != null && link.code.equals(PcCrypto.normalizeCode(code)) && link.port == port, "obsah QR sa dá načítať");
        byte[] serverId = PcCrypto.unhex(link.serverId);

        PcDiscovery.Found f = PcDiscovery.probe("127.0.0.1", port, 2000, null);
        check(f != null && f.serverId.equals(link.serverId) && f.port == port, "hľadanie v sieti nájde PC");

        byte[] deviceId = PcCrypto.random(16);
        Events ev = new Events();
        PcClient c = PcClient.connect("127.0.0.1", port, deviceId, PcCrypto.pairKey(code, serverId), true, "Pixel Ľščť", null, 3000, ev);
        ev.client = c;
        byte[] devKey = poll(ev.paired, 3000);
        check(devKey != null && devKey.length == 32, "telefón dostal trvalý kľúč");
        PcClient.Info info = poll(ev.infos, 3000);
        check(info != null && Arrays.equals(info.serverId, serverId) && info.monitors.size() == 2 && info.monitors.get(0).w == 640 && info.canPower(), "SESSION_INFO (monitory, príznaky)");
        PcClient.Screen scr = poll(ev.screens, 3000);
        check(scr != null && scr.outW == 640 && scr.outH == 360 && scr.divisor == 1, "SCREEN 640x360");
        Object[] fr = poll(ev.rects, 5000);
        @SuppressWarnings("unchecked") List<PcClient.Rect> rl = fr == null ? null : (List<PcClient.Rect>) fr[2];
        boolean decoded = rl != null && !rl.isEmpty();
        if (decoded) {
            for (PcClient.Rect r : rl) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(r.jpeg));
                if (img == null || img.getWidth() != 640 || img.getHeight() != r.h) decoded = false;
            }
        }
        check(decoded && rl.size() == 6, "prvý obraz: 6 pásov, všetky JPEG sa rozkódujú");

        System.out.println("Vstupy");
        c.moveRel(3, -4);
        c.button(1, true);
        c.button(1, false);
        c.wheel(0, 120);
        c.key(0x41, true);
        c.key(0x41, false);
        c.text("Ľščť 😀");
        c.moveAbs(32768, 0);
        c.key(0x11, true); // Ctrl zostane podržaný: po odpojení sa musí uvoľniť
        check(waitLog("INPUT abs:0.50,0.00@0", 3000), "všetky vstupy dorazili");
        List<String> got = inputLines();
        List<String> want = Arrays.asList("rel:3,-4", "btn:1:true", "btn:1:false", "wheel:0,120", "key:65:true", "key:65:false",
                "text:Ľščť 😀", "abs:0.50,0.00@0", "key:17:true");
        check(got.equals(want), "poradie a obsah vstupov " + got);

        // zlúčenie pohybov: veľa malých pohybov = súčet, poradie voči kliknutiu zostane
        serverLog.clear();
        for (int i = 0; i < 50; i++) c.moveRel(1, 2);
        c.button(3, true);
        c.button(3, false);
        check(waitLog("INPUT btn:3:false", 3000), "pohyby a stredné tlačidlo dorazili");
        int sx = 0, sy = 0; boolean order = true; boolean seenBtn = false;
        for (String l : inputLines()) {
            if (l.startsWith("rel:")) { String[] p = l.substring(4).split(","); sx += Integer.parseInt(p[0]); sy += Integer.parseInt(p[1]); if (seenBtn) order = false; }
            if (l.startsWith("btn:")) seenBtn = true;
        }
        check(sx == 50 && sy == 100 && order, "50 pohybov = súčet (50,100) pred kliknutím");

        System.out.println("Poloha kurzora");
        c.moveAbs(32768, 16384);
        boolean cur = false;
        for (int i = 0; i < 40 && !cur; i++) {
            int[] cp = poll(ev.cursors, 200);
            if (cp != null && Math.abs(cp[0] - 32768) < 200 && Math.abs(cp[1] - 16384) < 400 && cp[2] == 1) cur = true;
        }
        check(cur, "PC posiela polohu kurzora (stred obrazu)");

        System.out.println("Schránka, monitory, napájanie");
        c.clipSet("Ahoj ľščťžýáíé 😀\nriadok 2");
        c.clipGet();
        String clip = poll(ev.clips, 3000);
        check("Ahoj ľščťžýáíé 😀\nriadok 2".equals(clip), "schránka: zápis a čítanie");
        c.config(15, 70, 0, 1);
        PcClient.Screen s2 = poll(ev.screens, 3000);
        check(s2 != null && s2.monitor == 1 && s2.outW == 320 && s2.outH == 240 && s2.epoch > scr.epoch, "prepnutie na druhý monitor");
        c.config(15, 70, 2, 0);
        PcClient.Screen s3 = poll(ev.screens, 3000);
        check(s3 != null && s3.divisor == 2 && s3.outW == 320 && s3.outH == 180, "zmenšenie na polovicu");
        c.power(PcProto.POWER_LOCK);
        boolean powerNotice = false;
        for (int i = 0; i < 20 && !powerNotice; i++) { int[] n = poll(ev.notices, 500); if (n != null && n[0] == PcProto.NOTICE_POWER_ACCEPTED) powerNotice = true; }
        check(powerNotice, "napájanie: PC potvrdilo príkaz");
        Thread.sleep(3500);
        check(c.rttMs() >= 0 && c.rttMs() < 1000, "ping/pong: odozva " + c.rttMs() + " ms");

        System.out.println("Odpojenie a opätovné pripojenie");
        c.close();
        check(poll(ev.closed, 2000) != null, "onClosed po zatvorení");
        check(waitLog("INPUT key:17:false", 3000), "podržaný Ctrl sa po odpojení uvoľnil");

        Events ev2 = new Events();
        PcClient c2 = PcClient.connect("127.0.0.1", port, deviceId, devKey, false, "Pixel", null, 3000, ev2);
        ev2.client = c2;
        check(poll(ev2.infos, 3000) != null && poll(ev2.rects, 5000) != null, "pripojenie uloženým kľúčom (obraz prišiel)");

        // druhý telefón vytlačí prvý; vytlačený dostane upozornenie, aby sa nepripájal späť
        Events ev3 = new Events();
        PcClient c3 = PcClient.connect("127.0.0.1", port, deviceId, devKey, false, "Pixel", null, 3000, ev3);
        ev3.client = c3;
        check(poll(ev3.rects, 5000) != null, "nová relácia dostala obraz");
        boolean replaced = false;
        for (int i = 0; i < 20 && !replaced; i++) { int[] n = poll(ev2.notices, 300); if (n != null && n[0] == PcProto.NOTICE_REPLACED) replaced = true; }
        check(replaced, "vytlačená relácia dostala upozornenie NOTICE_REPLACED");
        check(poll(ev2.closed, 5000) != null, "stará relácia sa zatvorila");
        c3.close();

        try {
            PcClient.connectPaired("127.0.0.1", port, deviceId, devKey, PcCrypto.random(16), "x", null, 3000, new Events());
            check(false, "iný PC na tej istej adrese sa nesmie prijať");
        } catch (PcException e) { check(e.code == PcProto.ERR_WRONG_PC, "iný PC na adrese: " + e.getMessage()); }
        PcClient okPaired = PcClient.connectPaired("127.0.0.1", port, deviceId, devKey, serverId, "Pixel", null, 3000, new Events());
        check(okPaired != null, "connectPaired s tým istým ID PC prejde");
        okPaired.close();
        try {
            PcClient.connect("127.0.0.1", port, deviceId, PcCrypto.random(32), false, "x", null, 3000, new Events());
            check(false, "zlý kľúč sa nesmie dať použiť");
        } catch (PcException e) { check(e.code == PcProto.ERR_BAD_PROOF, "zlý kľúč: " + e.getMessage()); }
        try {
            PcClient.connect("127.0.0.1", port, PcCrypto.random(16), devKey, false, "x", null, 3000, new Events());
            check(false, "neznámy telefón sa nesmie dať pripojiť");
        } catch (PcException e) { check(e.code == PcProto.ERR_UNKNOWN_DEVICE, "neznámy telefón: " + e.getMessage()); }
        try {
            PcClient.connect("127.0.0.1", port, PcCrypto.random(16), PcCrypto.pairKey("AAAAA-BBBBB", serverId), true, "x", null, 3000, new Events());
            check(false, "párovanie bez zapnutého okna sa nesmie podariť");
        } catch (PcException e) { check(e.code == PcProto.ERR_NO_PAIRING, "párovanie je po úspešnom spárovaní zatvorené: " + e.getMessage()); }
        System.out.println("Párovanie len kódom (ručné zadanie IP)");
        String st2 = http("POST", base + "/api/pair/start");
        Matcher mc2 = Pattern.compile("\"code\":\"([A-Z0-9-]+)\"").matcher(st2);
        check(mc2.find(), "nové párovacie okno");
        String code2 = mc2.group(1);
        try {
            PcClient.connectWithCode("127.0.0.1", port, PcCrypto.random(16), "AAAAA-BBBBB", null, "x", null, 5000, new Events());
            check(false, "zlý kód sa nesmie prijať");
        } catch (PcException e) { check(e.code == PcProto.ERR_BAD_PROOF, "zlý kód: " + e.getMessage()); }
        try {
            PcClient.connectWithCode("127.0.0.1", port, PcCrypto.random(16), code2, PcCrypto.random(16), "x", null, 5000, new Events());
            check(false, "iné ID PC než v QR sa nesmie prijať");
        } catch (PcException e) { check(e.code == PcProto.ERR_WRONG_PC, "ID PC z QR nesedí: " + e.getMessage()); }
        Events ev4 = new Events();
        PcClient c4 = PcClient.connectWithCode("127.0.0.1", port, PcCrypto.random(16), code2.toLowerCase(), serverId, "Druhý", null, 8000, ev4);
        check(poll(ev4.paired, 3000) != null && poll(ev4.rects, 5000) != null, "párovanie len kódom prebehlo, obraz prišiel");
        c4.close();

        try {
            PcClient.connect("127.0.0.1", 1, deviceId, devKey, false, "x", null, 1000, new Events());
            check(false, "nedostupný port");
        } catch (PcException e) { check(e.code == 0, "nedostupný PC: " + e.getMessage()); }
    }
}
