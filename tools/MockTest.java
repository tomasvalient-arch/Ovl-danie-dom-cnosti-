import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.CRC32;
import javax.crypto.*;
import javax.crypto.spec.*;
import javax.net.SocketFactory;
import sk.domov.ovladanie.tuya.TuyaDevice;

/** Simulované Tuya zariadenie (strana zariadenia napísaná samostatne od klienta). */
public class MockTest {
    static final byte[] LOCAL_KEY = "0123456789abcdef".getBytes();
    static volatile boolean state = false;

    static byte[] hmac(byte[] k, byte[] d) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(k, "HmacSHA256")); return m.doFinal(d);
    }
    static byte[] ecbEnc(byte[] k, byte[] d) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding"); c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES")); return c.doFinal(d);
    }
    static byte[] ecbDec(byte[] k, byte[] d) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding"); c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES")); return c.doFinal(d);
    }
    static byte[] ecbRaw(byte[] k, byte[] d) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding"); c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES")); return c.doFinal(d);
    }
    static byte[] cat(byte[]... a) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); for (byte[] x : a) o.write(x); return o.toByteArray();
    }
    static byte[] xor(byte[] a, byte[] b) { byte[] r = new byte[16]; for (int i = 0; i < 16; i++) r[i] = (byte) (a[i] ^ b[i]); return r; }

    static void serve(ServerSocket ss, String ver) throws Exception {
        try (Socket s = ss.accept()) {
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            byte[] key = LOCAL_KEY;
            byte[] remoteNonce = new byte[16];
            new SecureRandom().nextBytes(remoteNonce);
            byte[] localNonce = null;
            while (true) {
                int prefix;
                try { prefix = in.readInt(); } catch (EOFException e) { return; }
                int seq, cmd; byte[] plain; // plain = dešifrovaný payload po retcode
                if (prefix == 0x000055AA) {
                    seq = in.readInt(); cmd = in.readInt(); int len = in.readInt();
                    byte[] body = new byte[len]; in.readFully(body);
                    int tail = ver.equals("3.3") ? 4 : 32;
                    byte[] payload = Arrays.copyOfRange(body, 0, len - tail - 4);
                    byte[] head = ByteBuffer.allocate(16 + payload.length).putInt(prefix).putInt(seq).putInt(cmd).putInt(len).put(payload).array();
                    if (tail == 4) {
                        CRC32 c = new CRC32(); c.update(head);
                        if ((int) c.getValue() != ByteBuffer.wrap(body, len - 8, 4).getInt()) throw new RuntimeException("CRC");
                        if (payload.length > 15 && payload[0] == '3') payload = Arrays.copyOfRange(payload, 15, payload.length);
                        plain = ecbDec(key, payload);
                    } else {
                        if (!MessageDigest.isEqual(hmac(key, head), Arrays.copyOfRange(body, len - 36, len - 4))) throw new RuntimeException("HMAC");
                        plain = ecbDec(key, payload);
                        if (plain.length > 15 && plain[0] == '3' && plain[1] == '.') plain = Arrays.copyOfRange(plain, 15, plain.length);
                    }
                } else if (prefix == 0x00006699) {
                    byte[] aad = new byte[14]; in.readFully(aad);
                    ByteBuffer ab = ByteBuffer.wrap(aad); ab.getShort(); seq = ab.getInt(); cmd = ab.getInt(); int len = ab.getInt();
                    byte[] body = new byte[len + 4]; in.readFully(body);
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(body, 0, 12)));
                    c.updateAAD(aad);
                    plain = c.doFinal(body, 12, len - 12);
                    if (plain.length > 15 && plain[0] == '3' && plain[1] == '.') plain = Arrays.copyOfRange(plain, 15, plain.length);
                } else throw new RuntimeException("prefix " + Integer.toHexString(prefix));

                byte[] reply; int rcmd = cmd; // reply = nešifrovaný obsah (bez retcode)
                if (cmd == 3) { // negotiation start
                    localNonce = plain;
                    reply = cat(remoteNonce, hmac(LOCAL_KEY, localNonce)); rcmd = 4;
                    send(out, ver, seq, rcmd, reply, key, true);
                    continue;
                } else if (cmd == 5) { // finish
                    if (!MessageDigest.isEqual(plain, hmac(LOCAL_KEY, remoteNonce))) throw new RuntimeException("finish hmac");
                    if (ver.equals("3.4")) key = ecbRaw(LOCAL_KEY, xor(localNonce, remoteNonce));
                    else {
                        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(LOCAL_KEY, "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(localNonce, 0, 12)));
                        key = Arrays.copyOf(c.doFinal(xor(localNonce, remoteNonce)), 16);
                    }
                    continue;
                }
                String json = new String(plain, StandardCharsets.UTF_8);
                if (cmd == 0x0A || cmd == 0x10) {
                    if (!json.contains("\"gwId\":\"dev123\"")) throw new RuntimeException("query json " + json);
                    String dps = "{\"1\":" + state + ",\"2\":100}";
                    reply = (ver.equals("3.3") ? "{\"devId\":\"dev123\",\"dps\":" + dps + "}"
                            : "{\"protocol\":4,\"t\":1,\"data\":{\"dps\":" + dps + "}}").getBytes();
                    send(out, ver, seq, cmd, reply, key, false);
                } else if (cmd == 0x07 || cmd == 0x0D) {
                    Matcher m = Pattern.compile("\"dps\":\\{\"1\":(true|false)\\}").matcher(json);
                    if (!m.find()) throw new RuntimeException("control json " + json);
                    state = Boolean.parseBoolean(m.group(1));
                    send(out, ver, seq, cmd, new byte[0], key, false);
                } else throw new RuntimeException("cmd " + cmd);
            }
        }
    }

    static void send(OutputStream out, String ver, int seq, int cmd, byte[] content, byte[] key, boolean neg) throws Exception {
        byte[] retcode = new byte[4];
        if (ver.equals("3.5")) {
            byte[] pt = cat(retcode, content);
            byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
            int length = 12 + pt.length + 16;
            ByteBuffer ab = ByteBuffer.allocate(14); ab.putShort((short) 0).putInt(seq).putInt(cmd).putInt(length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(ab.array());
            out.write(cat(ByteBuffer.allocate(4).putInt(0x6699).array(), ab.array(), iv, c.doFinal(pt), ByteBuffer.allocate(4).putInt(0x9966).array()));
        } else {
            byte[] enc = content.length == 0 ? new byte[0] : ecbEnc(key, content);
            byte[] payload = cat(retcode, enc);
            int tail = ver.equals("3.3") ? 4 : 32;
            ByteBuffer b = ByteBuffer.allocate(16 + payload.length + tail + 4);
            b.putInt(0x55AA).putInt(seq).putInt(cmd).putInt(payload.length + tail + 4).put(payload);
            byte[] head = Arrays.copyOf(b.array(), b.position());
            if (tail == 4) { CRC32 c = new CRC32(); c.update(head); b.putInt((int) c.getValue()); } else b.put(hmac(key, head));
            b.putInt(0xAA55);
            out.write(b.array());
        }
        out.flush();
    }

    public static void main(String[] args) throws Exception {
        int failures = 0;
        for (String ver : new String[]{"3.3", "3.4", "3.5"}) {
            try (ServerSocket ss = new ServerSocket(0)) {
                final int port = ss.getLocalPort();
                Thread t = new Thread(() -> {
                    for (int i = 0; i < 4; i++) { // 4 spojenia: query, set, query, set
                        try { serve(ss, ver); } catch (Exception e) { e.printStackTrace(); return; }
                    }
                });
                t.setDaemon(true); t.start();
                state = false;
                TuyaDevice d = new TuyaDevice("127.0.0.1", port, "dev123", "0123456789abcdef", ver, SocketFactory.getDefault());
                String q1 = d.query();
                d.setDps("{\"1\":true}");
                String q2 = d.query();
                d.setDps("{\"1\":false}");
                boolean ok = q1.contains("\"1\":false") && q2.contains("\"1\":true") && !state;
                System.out.println(ver + ": " + (ok ? "OK" : "CHYBA") + "  q1=" + q1 + "  q2=" + q2);
                if (!ok) failures++;
            } catch (Exception e) {
                e.printStackTrace(); failures++;
                System.out.println(ver + ": CHYBA " + e);
            }
        }
        // zlý kľúč sa musí odhaliť
        try (ServerSocket ss = new ServerSocket(0)) {
            Thread t = new Thread(() -> { try { serve(ss, "3.4"); } catch (Exception ignored) { } });
            t.setDaemon(true); t.start();
            try {
                new TuyaDevice("127.0.0.1", ss.getLocalPort(), "dev123", "ZZZZZZZZZZZZZZZZ", "3.4", null).query();
                System.out.println("zlý kľúč: CHYBA (nezlyhalo)"); failures++;
            } catch (IOException e) {
                System.out.println("zlý kľúč: OK (" + e.getMessage() + ")");
            }
        }
        System.exit(failures == 0 ? 0 : 1);
    }
}
