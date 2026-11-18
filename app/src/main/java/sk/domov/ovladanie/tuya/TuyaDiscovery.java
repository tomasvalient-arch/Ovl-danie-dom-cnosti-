package sk.domov.ovladanie.tuya;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Dekódovanie UDP správ, ktoré Tuya zariadenia vysielajú do siete (broadcast) asi každých 5 sekúnd.
 *
 * <ul>
 *   <li>port 6666 – protokol 3.1, rámec 55AA s nešifrovaným JSON,</li>
 *   <li>port 6667 – protokol 3.3/3.4: rámec 55AA, JSON šifrovaný AES-ECB kľúčom md5("yGAdlopoPVldABfn");
 *       protokol 3.5: rámec 6699, AES-GCM s tým istým kľúčom.</li>
 * </ul>
 *
 * Správa obsahuje napr. {"ip":"192.168.1.50","gwId":"bf12…","version":"3.3","productKey":"…"}.
 * Postup je rovnaký ako v tinytuya (scanner.py, decrypt_udp). Trieda nepoužíva Android, dá sa testovať v JVM.
 */
public final class TuyaDiscovery {
    public static final int PORT_PLAIN = 6666;
    public static final int PORT_ENCRYPTED = 6667;
    /** Port, na ktorý Tuya appka posiela požiadavku na info (a kde počúvajú zariadenia 3.5). */
    public static final int PORT_APP = 7000;
    private static final int REQ_DEVINFO = 0x25;

    private static final int PREFIX_55AA = 0x000055AA;
    private static final int PREFIX_6699 = 0x00006699;

    /** md5("yGAdlopoPVldABfn") – spoločný kľúč pre broadcast všetkých Tuya zariadení. */
    public static final byte[] UDP_KEY = md5("yGAdlopoPVldABfn");

    private TuyaDiscovery() {
    }

    /** Jedno zariadenie nájdené v sieti. */
    public static final class Found {
        public final String devId;
        public final String ip;
        public final String version;
        public final String productKey;
        public final String json;

        public Found(String devId, String ip, String version, String productKey, String json) {
            this.devId = devId;
            this.ip = ip;
            this.version = version;
            this.productKey = productKey;
            this.json = json;
        }

        @Override
        public String toString() {
            return "Found{devId=" + devId + ", ip=" + ip + ", version=" + version + ", productKey=" + productKey + "}";
        }
    }

    /**
     * Dekóduje jednu UDP správu. Vráti null, ak to nie je Tuya broadcast alebo sa nedá prečítať.
     * [fallbackIp] = adresa odosielateľa, ak správa IP neobsahuje.
     */
    public static Found parse(byte[] packet, int length, String fallbackIp) {
        String json = decode(Arrays.copyOf(packet, length));
        if (json == null) {
            return null;
        }
        if ("app".equals(field(json, "from"))) {
            return null; // požiadavka od appky (aj naša vlastná), nie zariadenie
        }
        String devId = field(json, "gwId");
        if (devId == null || devId.isEmpty()) {
            devId = field(json, "devId");
        }
        if (devId == null || devId.isEmpty()) {
            return null;
        }
        String ip = field(json, "ip");
        if (ip == null || ip.isEmpty()) {
            ip = fallbackIp == null ? "" : fallbackIp;
        }
        String version = field(json, "version");
        String productKey = field(json, "productKey");
        return new Found(devId, ip, version == null ? "" : version, productKey == null ? "" : productKey, json);
    }

    /** Vráti JSON text zo správy, alebo null. */
    public static String decode(byte[] msg) {
        try {
            if (msg.length >= 4 && getInt(msg, 0) == PREFIX_55AA) {
                return decode55AA(msg);
            }
            if (msg.length >= 4 && getInt(msg, 0) == PREFIX_6699) {
                return decode6699(msg);
            }
            // niektoré zariadenia posielajú len zašifrované dáta bez rámca
            if (msg.length > 0 && msg.length % 16 == 0) {
                return asJson(unpad(ecb(Cipher.DECRYPT_MODE, msg)));
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static String decode55AA(byte[] msg) throws GeneralSecurityException {
        // prefix(4) seq(4) cmd(4) dĺžka(4) | [retcode(4)] dáta | crc(4) suffix(4)
        if (msg.length < 24) {
            return null;
        }
        int len = getInt(msg, 12);
        int end = Math.min(msg.length, 16 + len) - 8;
        if (end <= 16) {
            return null;
        }
        byte[] region = Arrays.copyOfRange(msg, 16, end);
        // najprv s retcode, potom bez neho
        for (int skip : new int[] {4, 0}) {
            if (region.length < skip) {
                continue;
            }
            byte[] data = Arrays.copyOfRange(region, skip, region.length);
            String plain = asJson(data);
            if (plain != null) {
                return plain;
            }
            if (data.length > 0 && data.length % 16 == 0) {
                String dec = asJson(unpad(ecb(Cipher.DECRYPT_MODE, data)));
                if (dec != null) {
                    return dec;
                }
            }
        }
        return null;
    }

    private static String decode6699(byte[] msg) throws GeneralSecurityException {
        // prefix(4) | rezerva(2) seq(4) cmd(4) dĺžka(4) = AAD | iv(12) šifrované dáta tag(16) | suffix(4)
        if (msg.length < 18 + 28 + 4) {
            return null;
        }
        byte[] aad = Arrays.copyOfRange(msg, 4, 18);
        int len = getInt(msg, 14);
        if (len < 28 || 18 + len > msg.length) {
            return null;
        }
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(UDP_KEY, "AES"),
                new GCMParameterSpec(128, Arrays.copyOfRange(msg, 18, 30)));
        c.updateAAD(aad);
        byte[] data = c.doFinal(msg, 30, len - 12);
        if (data.length >= 4 && data[0] != '{' && (getInt(data, 0) & 0xFFFFFF00) == 0) {
            data = Arrays.copyOfRange(data, 4, data.length);
        }
        return asJson(data);
    }

    /** Text, ak dáta vyzerajú ako JSON objekt (koncové nuly a medzery sa odstránia). */
    private static String asJson(byte[] data) {
        int end = data.length;
        while (end > 0 && (data[end - 1] == 0 || data[end - 1] == ' ' || data[end - 1] == '\n' || data[end - 1] == '\r')) {
            end--;
        }
        if (end < 2 || data[0] != '{' || data[end - 1] != '}') {
            return null;
        }
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }

    private static final Pattern FIELD = Pattern.compile("\"([A-Za-z_]+)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|[-0-9.]+|true|false|null)");

    /** Hodnota jedného poľa z plochého JSON (text alebo číslo), alebo null. */
    static String field(String json, String name) {
        Matcher m = FIELD.matcher(json);
        while (m.find()) {
            if (m.group(1).equals(name)) {
                return m.group(3) != null ? m.group(3) : m.group(2);
            }
        }
        return null;
    }

    // ------------------------------------------------------------ pomocné (aj pre testy)

    /** Zašifruje JSON ako broadcast zariadenia na porte 6667 (3.3/3.4). Používa test. */
    public static byte[] encode55AA(String json, boolean encrypted) throws GeneralSecurityException {
        byte[] plain = json.getBytes(StandardCharsets.UTF_8);
        byte[] payload = encrypted ? ecb(Cipher.ENCRYPT_MODE, pad(plain)) : plain;
        int len = 4 + payload.length + 8;
        ByteBuffer b = ByteBuffer.allocate(16 + len);
        b.putInt(PREFIX_55AA).putInt(0).putInt(encrypted ? 0x13 : 0x12).putInt(len).putInt(0).put(payload);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(b.array(), 0, 16 + 4 + payload.length);
        b.putInt((int) crc.getValue()).putInt(0x0000AA55);
        return b.array();
    }

    /** Zašifruje JSON ako broadcast zariadenia 3.5 (rámec 6699, AES-GCM, s retcode). Používa test. */
    public static byte[] encode6699(String json, byte[] iv) throws GeneralSecurityException {
        return frame6699(0x13, concat(new byte[4], json.getBytes(StandardCharsets.UTF_8)), iv);
    }

    /**
     * Požiadavka „pošlite mi info“ na port 7000 (ako to robí Tuya appka a tinytuya). Niektoré zariadenia 3.5
     * sa bez nej neozvú. [myIp] = IP adresa telefónu vo WiFi.
     */
    public static byte[] discoveryRequest(String myIp) throws GeneralSecurityException {
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        String json = "{\"from\":\"app\",\"ip\":\"" + myIp + "\"}";
        return frame6699(REQ_DEVINFO, json.getBytes(StandardCharsets.UTF_8), iv);
    }

    private static byte[] frame6699(int cmd, byte[] plain, byte[] iv) throws GeneralSecurityException {
        int length = 12 + plain.length + 16;
        ByteBuffer ab = ByteBuffer.allocate(14);
        ab.putShort((short) 0).putInt(0).putInt(cmd).putInt(length);
        byte[] aad = ab.array();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(UDP_KEY, "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD(aad);
        byte[] ctTag = c.doFinal(plain);
        ByteBuffer b = ByteBuffer.allocate(4 + 14 + 12 + ctTag.length + 4);
        b.putInt(PREFIX_6699).put(aad).put(iv).put(ctTag).putInt(0x00009966);
        return b.array();
    }

    private static byte[] ecb(int mode, byte[] data) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(mode, new SecretKeySpec(UDP_KEY, "AES"));
        return c.doFinal(data);
    }

    private static byte[] pad(byte[] data) {
        int p = 16 - data.length % 16;
        byte[] out = Arrays.copyOf(data, data.length + p);
        Arrays.fill(out, data.length, out.length, (byte) p);
        return out;
    }

    private static byte[] unpad(byte[] data) {
        if (data.length == 0) {
            return data;
        }
        int p = data[data.length - 1] & 0xFF;
        if (p < 1 || p > 16 || p > data.length) {
            return data;
        }
        for (int i = data.length - p; i < data.length; i++) {
            if ((data[i] & 0xFF) != p) {
                return data;
            }
        }
        return Arrays.copyOf(data, data.length - p);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static int getInt(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 4).getInt();
    }

    private static byte[] md5(String s) {
        try {
            return MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
