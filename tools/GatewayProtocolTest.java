package sk.domov.ovladanie.tuya;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Overenie komunikácie s Tuya bránou (Zigbee podzariadenia cez cid). Brána je simulovaná samostatne od klienta
 * (verzia 3.3: AES-ECB + CRC32), kontrolujú sa presné tvary požiadaviek podľa tinytuya a filtrovanie hlásení
 * iných podzariadení. Tvary pre 3.4 a 3.5 sa overujú na úrovni zostavovania JSON.
 * Je v balíku tuya, aby videla balíkové pomocné funkcie (queryJson, controlJson, updateJson).
 */
public class GatewayProtocolTest {
    static final byte[] KEY = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "OK     " : "CHYBA  ") + name + (ok ? "" : "  -> " + detail));
        if (!ok) failures++;
    }

    static byte[] ecb(int mode, byte[] d) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(mode, new SecretKeySpec(KEY, "AES"));
        return c.doFinal(d);
    }

    /** Zaznamenané požiadavky brány. */
    static final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    interface Handler {
        /** Vráti rámce odpovede ako páry {cmd, json}. */
        List<Object[]> on(int cmd, String json);
    }

    static void sendFrame(OutputStream out, int seq, int cmd, String json) throws Exception {
        byte[] enc = json.isEmpty() ? new byte[0] : ecb(Cipher.ENCRYPT_MODE, json.getBytes(StandardCharsets.UTF_8));
        byte[] payload = new byte[4 + enc.length];
        System.arraycopy(enc, 0, payload, 4, enc.length); // retcode 0
        ByteBuffer b = ByteBuffer.allocate(16 + payload.length + 8);
        b.putInt(0x55AA).putInt(seq).putInt(cmd).putInt(payload.length + 8).put(payload);
        CRC32 c = new CRC32();
        c.update(b.array(), 0, b.position());
        b.putInt((int) c.getValue()).putInt(0xAA55);
        out.write(b.array());
        out.flush();
    }

    /** Brána 3.3: prijme [connections] spojení, na každý rámec zavolá [h]. */
    static Thread serve(ServerSocket ss, int connections, Handler h) {
        Thread t = new Thread(() -> {
            for (int n = 0; n < connections; n++) {
                try (Socket s = ss.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    OutputStream out = s.getOutputStream();
                    while (true) {
                        int prefix;
                        try {
                            prefix = in.readInt();
                        } catch (EOFException e) {
                            break;
                        }
                        if (prefix != 0x55AA) throw new RuntimeException("prefix");
                        int seq = in.readInt(), cmd = in.readInt(), len = in.readInt();
                        byte[] body = new byte[len];
                        in.readFully(body);
                        byte[] payload = Arrays.copyOfRange(body, 0, len - 8);
                        if (payload.length > 15 && payload[0] == '3') payload = Arrays.copyOfRange(payload, 15, payload.length);
                        String json = payload.length == 0 ? "" : new String(ecb(Cipher.DECRYPT_MODE, payload), StandardCharsets.UTF_8);
                        requests.add(cmd + ":" + json);
                        for (Object[] r : h.on(cmd, json)) {
                            sendFrame(out, (cmd == 0x08 || (Integer) r[0] == 0x08) ? 0 : seq, (Integer) r[0], (String) r[1]);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    static List<Object[]> one(int cmd, String json) {
        List<Object[]> l = new ArrayList<>();
        l.add(new Object[]{cmd, json});
        return l;
    }

    public static void main(String[] args) throws Exception {
        // ---- 1) tvary požiadaviek (tinytuya, šablóny zigbee)
        check("dotaz 3.3 s cid", TuyaDevice.queryJson(33, "gw", "abc123", 1700000000L).equals("{\"t\":1700000000,\"cid\":\"abc123\"}"),
                TuyaDevice.queryJson(33, "gw", "abc123", 1700000000L));
        check("dotaz 3.4 s cid", TuyaDevice.queryJson(34, "gw", "abc123", 1).equals("{\"cid\":\"abc123\"}"), TuyaDevice.queryJson(34, "gw", "abc123", 1));
        check("dotaz 3.5 s cid", TuyaDevice.queryJson(35, "gw", "abc123", 1).equals("{\"cid\":\"abc123\"}"), "");
        check("dotaz bez cid ostáva ako doteraz",
                TuyaDevice.queryJson(33, "dev1", "", 5L).equals("{\"gwId\":\"dev1\",\"devId\":\"dev1\",\"uid\":\"dev1\",\"t\":\"5\"}"),
                TuyaDevice.queryJson(33, "dev1", "", 5L));
        check("príkaz 3.3 s cid", TuyaDevice.controlJson(33, "gw", "abc123", "{\"1\":true}", 7L)
                .equals("{\"t\":7,\"cid\":\"abc123\",\"dps\":{\"1\":true}}"), TuyaDevice.controlJson(33, "gw", "abc123", "{\"1\":true}", 7L));
        String c34 = TuyaDevice.controlJson(34, "gw", "abc123", "{\"1\":true}", 7L);
        check("príkaz 3.4 s cid (data.cid, ctype 0, protocol 5)",
                c34.equals("{\"protocol\":5,\"t\":7,\"data\":{\"cid\":\"abc123\",\"ctype\":0,\"dps\":{\"1\":true}},\"cid\":\"abc123\"}"), c34);
        check("príkaz 3.5 má rovnaký tvar ako 3.4", TuyaDevice.controlJson(35, "gw", "abc123", "{\"1\":true}", 7L).equals(c34), "");
        check("príkaz bez cid 3.3 ako doteraz", TuyaDevice.controlJson(33, "d", "", "{\"1\":true}", 9L)
                .equals("{\"devId\":\"d\",\"uid\":\"d\",\"t\":\"9\",\"dps\":{\"1\":true}}"), TuyaDevice.controlJson(33, "d", "", "{\"1\":true}", 9L));
        check("príkaz bez cid 3.4 ako doteraz", TuyaDevice.controlJson(34, "d", "", "{\"1\":true}", 9L)
                .equals("{\"data\":{\"ctype\":0,\"devId\":\"d\",\"gwId\":\"d\",\"uid\":\"\",\"dps\":{\"1\":true}},\"protocol\":5,\"t\":9}"), TuyaDevice.controlJson(34, "d", "", "{\"1\":true}", 9L));
        check("updatedps s cid", TuyaDevice.updateJson(new int[]{1, 2}, "abc").equals("{\"dpId\":[1,2],\"cid\":\"abc\"}"), TuyaDevice.updateJson(new int[]{1, 2}, "abc"));
        check("updatedps bez cid", TuyaDevice.updateJson(new int[]{51}, "").equals("{\"dpId\":[51]}"), "");

        // ---- 2) neplatné cid sa odmietne (nedá sa vložiť do JSON)
        boolean rejected = false;
        try {
            new TuyaDevice("127.0.0.1", 6668, "gw", "0123456789abcdef", "3.3", null, "a\"b");
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        check("cid s úvodzovkou sa odmietne", rejected, "");

        // ---- 3) falošná brána 3.3: odpovedá len svojmu cid, ostatné hlásenia sú medzi tým
        try (ServerSocket ss = new ServerSocket(0)) {
            serve(ss, 3, (cmd, json) -> {
                List<Object[]> r = new ArrayList<>();
                if (cmd == 0x0A) { // dotaz na stav
                    // najprv hlásenie iného podzariadenia, potom naše
                    r.add(new Object[]{0x08, "{\"dps\":{\"1\":false},\"cid\":\"other999\"}"});
                    String cid = json.contains("\"cid\":\"door01\"") ? "door01" : "?";
                    r.add(new Object[]{0x0A, "{\"dps\":{\"1\":true,\"2\":\"normal\",\"3\":87},\"cid\":\"" + cid + "\"}"});
                } else if (cmd == 0x07) { // príkaz
                    r.add(new Object[]{0x07, ""});
                } else if (cmd == 0x12) {
                    r.add(new Object[]{0x12, ""});
                } else if (cmd == 0x40) {
                    r.add(new Object[]{0x40, "{\"data\":{\"online\":[\"door01\"],\"offline\":[\"temp02\"],\"nearby\":[]},\"reqType\":\"subdev_online_stat_query\"}"});
                }
                return r;
            });
            int port = ss.getLocalPort();
            TuyaDevice d = new TuyaDevice("127.0.0.1", port, "gw1", "0123456789abcdef", "3.3", null, "door01");
            String q = d.query();
            check("dotaz podzariadenia preskočí cudzie hlásenie a vráti naše",
                    q.contains("\"cid\":\"door01\"") && q.contains("\"3\":87") && !q.contains("other999"), q);
            check("brána dostala cid v dotaze", requests.stream().anyMatch(s -> s.startsWith("10:") && s.contains("\"cid\":\"door01\"") && s.contains("\"t\":")), requests.toString());
            d.setDps("{\"1\":true}");
            check("príkaz poslal cid aj dps", requests.stream().anyMatch(s -> s.startsWith("7:") && s.contains("\"cid\":\"door01\"") && s.contains("\"dps\":{\"1\":true}")), requests.toString());

            List<String> sub = d.subdevQuery(600);
            check("subdevQuery vráti odpoveď brány", sub.size() == 1 && sub.get(0).contains("subdev_online_stat_query") && sub.get(0).contains("temp02"), sub.toString());
            check("subdevQuery poslal cmd 0x40 so správnym telom", requests.stream().anyMatch(s -> s.startsWith("64:") && s.contains("\"reqType\":\"subdev_online_stat_query\"") && s.contains("\"cids\":[]")), requests.toString());

            // sledovanie: cudzie hlásenia sa nesmú dostať k poslucháčovi
            serveMonitor(port, ss);
        }

        // ---- 4) bez cid sa nič nefiltruje (regresia pre WiFi zariadenia)
        try (ServerSocket ss = new ServerSocket(0)) {
            serve(ss, 1, (cmd, json) -> {
                List<Object[]> r = new ArrayList<>();
                if (cmd == 0x0A) {
                    r.add(new Object[]{0x0A, "{\"devId\":\"dev1\",\"dps\":{\"1\":true},\"cid\":\"whatever\"}"});
                }
                return r;
            });
            TuyaDevice d = new TuyaDevice("127.0.0.1", ss.getLocalPort(), "dev1", "0123456789abcdef", "3.3", null);
            String q = d.query();
            check("WiFi zariadenie (bez cid) odpoveď nefiltruje", q.contains("\"1\":true"), q);
            check("WiFi zariadenie posiela pôvodný tvar dotazu", requests.stream().anyMatch(s -> s.startsWith("10:") && s.contains("\"gwId\":\"dev1\"")), requests.toString());
        }

        System.out.println(failures == 0 ? "\nVšetko v poriadku." : "\nChýb: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    static void serveMonitor(int port, ServerSocket ss) throws Exception {
        // nový server na tom istom porte nejde; použijeme nové spojenie na ten istý ss (serve s 4 spojeniami, 3 využité)
        List<String> got = Collections.synchronizedList(new ArrayList<>());
        // brána z predošlého bloku už nemá spojenie navyše pre monitor s hlásením – spustíme samostatnú
        try (ServerSocket ms = new ServerSocket(0)) {
            serve(ms, 1, (cmd, json) -> {
                List<Object[]> r = new ArrayList<>();
                if (cmd == 0x0A) {
                    r.add(new Object[]{0x0A, "{\"dps\":{\"1\":false},\"cid\":\"door01\"}"});
                    r.add(new Object[]{0x08, "{\"dps\":{\"1\":true},\"cid\":\"other999\"}"});
                    r.add(new Object[]{0x08, "{\"dps\":{\"3\":55},\"cid\":\"door01\"}"});
                }
                return r;
            });
            TuyaDevice d = new TuyaDevice("127.0.0.1", ms.getLocalPort(), "gw1", "0123456789abcdef", "3.3", null, "door01");
            d.monitor(null, 2500, new TuyaDevice.MonitorListener() {
                public void onFrame(int cmd, int retcode, String text) {
                    got.add(text);
                }

                public void onInfo(String message) {
                }
            }, () -> got.size() >= 2);
        }
        check("sledovanie podzariadenia dostáva len svoje hlásenia",
                got.size() == 2 && got.get(0).contains("\"1\":false") && got.get(1).contains("\"3\":55")
                        && got.stream().noneMatch(s -> s.contains("other999")), got.toString());
    }
}
