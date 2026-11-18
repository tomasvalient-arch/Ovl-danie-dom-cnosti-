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
public class MockLt {
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
        String json = new JSONObject().put("devId", "bflt11strip00001").put("dps", changed).put("t", System.currentTimeMillis() / 1000).toString();
        synchronized (clients) { for (OutputStream o : new ArrayList<>(clients)) try { send(o, 0, 8, json, true); } catch (Exception e) { clients.remove(o); } }
    }

    static void control(JSONObject d) {
        synchronized (dps) { for (String k : d.keySet()) dps.put(k, d.get(k)); }
        broadcast(d);
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
                    send(out, seq, cmd, new JSONObject().put("devId", "bflt11strip00001").put("dps", d).toString(), false);
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
        dps.put("1", true); dps.put("2", "white"); dps.put("3", 10); dps.put("4", 0);
        System.out.println("START " + dps);
        try (ServerSocket ss = new ServerSocket(6668)) {
            while (true) { Socket s = ss.accept(); new Thread(() -> handle(s)).start(); }
        }
    }
}
