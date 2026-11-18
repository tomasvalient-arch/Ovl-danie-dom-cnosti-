import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Falošná fritéza BH-AF2 – zápis DP 1 berie ako tlačidlo Power (z vypnutej: zapne a spustí Chips 20 min). Pôvodne: Falošné BW-CLT1 (protokol 3.3) pre emulátor: DP 51 sa v stave neposiela, len na UPDATEDPS; každých 25 s
 *  „Tuya appka“ prepne farebný zdroj (bit 0x02) a pošle hlásenie všetkým pripojeným. */
public class MockFryer {
    static final byte[] KEY = "0123456789abcdef".getBytes();
    static final Map<String, Object> dps = new LinkedHashMap<>();
    static volatile String mix = "AAcBGQJpA+gAeAPo";
    static final List<OutputStream> clients = Collections.synchronizedList(new ArrayList<>());

    static byte[] ecb(int mode, byte[] d) throws Exception { Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding"); c.init(mode, new SecretKeySpec(KEY, "AES")); return c.doFinal(d); }

    static synchronized void send(OutputStream out, int seq, int cmd, String json, boolean header) throws Exception {
        byte[] enc = json.isEmpty() ? new byte[0] : ecb(Cipher.ENCRYPT_MODE, json.getBytes("UTF-8"));
        if (header && enc.length > 0) { byte[] h = new byte[15]; System.arraycopy("3.3".getBytes(), 0, h, 0, 3); byte[] n = new byte[h.length + enc.length]; System.arraycopy(h, 0, n, 0, 15); System.arraycopy(enc, 0, n, 15, enc.length); enc = n; }
        byte[] payload = new byte[4 + enc.length]; System.arraycopy(enc, 0, payload, 4, enc.length);
        ByteBuffer b = ByteBuffer.allocate(16 + payload.length + 8);
        b.putInt(0x55AA).putInt(seq).putInt(cmd).putInt(payload.length + 8).put(payload);
        CRC32 c = new CRC32(); c.update(b.array(), 0, b.position()); b.putInt((int) c.getValue()).putInt(0xAA55);
        out.write(b.array()); out.flush();
    }

    static void broadcast(JSONObject changed) {
        String json = new JSONObject().put("devId", "bfaf2fryer000001").put("dps", changed).put("t", System.currentTimeMillis() / 1000).toString();
        synchronized (clients) { for (OutputStream o : new ArrayList<>(clients)) try { send(o, 0, 8, json, true); } catch (Exception e) { clients.remove(o); } }
    }

    static void control(JSONObject d) {
        JSONObject changed = new JSONObject();
        synchronized (dps) {
            if (d.has("1")) {
                // ako tlačidlo: z vypnutej/pohotovosti zapne a spustí program Chips
                if (!Boolean.TRUE.equals(dps.get("2"))) { dps.put("1", true); dps.put("2", true); dps.put("5", "cooking"); dps.put("9", 20); dps.put("10", 20); System.out.println("!!! FRITEZA SA SPUSTILA (zapis DP1=" + d.get("1") + ")"); }
                else { dps.put("1", false); dps.put("2", false); dps.put("5", "standby"); }
            }
            if (d.has("2")) { boolean run = d.getBoolean("2"); dps.put("2", run); dps.put("5", run ? "cooking" : "standby"); if (run) System.out.println("!!! FRITEZA SA SPUSTILA (DP2=true)"); else System.out.println("STOP prijaty"); }
            for (String k : new String[]{"1", "2", "5", "9", "10"}) changed.put(k, dps.get(k));
        }
        broadcast(changed);
    }

    static void handle(Socket s) {
        try (s) {
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            clients.add(out);
            while (true) {
                int prefix = in.readInt(); int seq = in.readInt(); int cmd = in.readInt(); int len = in.readInt();
                byte[] body = new byte[len]; in.readFully(body);
                byte[] p = Arrays.copyOfRange(body, 0, len - 8);
                if (p.length > 15 && p[0] == '3') p = Arrays.copyOfRange(p, 15, p.length);
                String json = p.length == 0 ? "" : new String(ecb(Cipher.DECRYPT_MODE, p), "UTF-8");
                if (cmd == 0x0A) {
                    JSONObject d = new JSONObject(); synchronized (dps) { dps.forEach(d::put); }
                    send(out, seq, cmd, new JSONObject().put("devId", "bfaf2fryer000001").put("dps", d).toString(), false);
                } else if (cmd == 0x12 || cmd == 0x09) {
                    send(out, seq, cmd, "", false);
                } else if (cmd == 0x07) {
                    JSONObject d = new JSONObject(json).getJSONObject("dps");
                    send(out, seq, cmd, "", false);
                    System.out.println(System.currentTimeMillis() + " CONTROL " + d);
                    control(d);
                }
            }
        } catch (Exception e) { /* koniec spojenia */ }
    }

    public static void main(String[] a) throws Exception {
        boolean running = a.length > 0 && a[0].equals("running");
        boolean standby = a.length > 0 && a[0].equals("standby");
        dps.put("1", running || standby); dps.put("2", running); dps.put("3", "Chips"); dps.put("5", running ? "cooking" : "standby");
        dps.put("6", 200); dps.put("9", running ? 15 : 0); dps.put("10", running ? Integer.getInteger("rem", 12) : 1); dps.put("12", Integer.getInteger("fault", 0));
        // zrýchlený odpočet: -Dmin=5000 = jedna „minúta“ za 5 s; na konci DP2=false, stav standby
        final long min = Long.getLong("min", 0L);
        if (min > 0) new Thread(() -> {
            while (true) {
                try { Thread.sleep(min); } catch (InterruptedException e) { return; }
                synchronized (dps) {
                    if (!Boolean.TRUE.equals(dps.get("2"))) continue;
                    int r = ((Number) dps.get("10")).intValue() - 1;
                    JSONObject ch = new JSONObject();
                    if (r <= 0) { dps.put("2", false); dps.put("5", "standby"); dps.put("10", 0); System.out.println("DOVARILA"); }
                    else { dps.put("10", r); System.out.println("zostava " + r + " min"); }
                    for (String k : new String[]{"2", "5", "10"}) ch.put(k, dps.get(k));
                    broadcast(ch);
                }
            }
        }).start();
        System.out.println("START " + dps);
        try (ServerSocket ss = new ServerSocket(6668)) {
            while (true) { Socket s = ss.accept(); new Thread(() -> handle(s)).start(); }
        }
    }
}
