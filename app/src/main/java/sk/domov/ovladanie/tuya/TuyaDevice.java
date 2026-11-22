package sk.domov.ovladanie.tuya;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.SocketFactory;

/**
 * Lokálna komunikácia s Tuya zariadením cez TCP port 6668 (bez cloudu).
 * Podporuje protokoly 3.3, 3.4 a 3.5. Každé volanie otvorí spojenie, vykoná príkaz a spojenie zavrie.
 *
 * Metódy sú blokujúce, volaj ich mimo hlavného vlákna.
 */
public final class TuyaDevice {
    public static final int DEFAULT_PORT = 6668;

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 4000;

    private static final int PREFIX_55AA = 0x000055AA;
    private static final int SUFFIX_55AA = 0x0000AA55;
    private static final int PREFIX_6699 = 0x00006699;
    private static final int SUFFIX_6699 = 0x00009966;

    private static final int CMD_NEG_START = 0x03;
    private static final int CMD_NEG_RESP = 0x04;
    private static final int CMD_NEG_FINISH = 0x05;
    private static final int CMD_CONTROL = 0x07;
    private static final int CMD_STATUS = 0x08;
    private static final int CMD_QUERY = 0x0A;
    private static final int CMD_CONTROL_NEW = 0x0D;
    private static final int CMD_QUERY_NEW = 0x10;
    private static final int CMD_HEART_BEAT = 0x09;
    /** FRM_LAN_QUERY_DP – požiadavka, aby zariadenie poslalo aktuálne hodnoty vybraných DP (tinytuya updatedps). */
    private static final int CMD_UPDATEDPS = 0x12;
    /** LAN_EXT_STREAM – dotaz brány na zoznam jej podzariadení a ich stav (tinytuya subdev_query). */
    private static final int CMD_LAN_EXT_STREAM = 0x40;

    /** ID podzariadenia (node_id) je len písmená, čísla a pár znakov; do JSON sa vkladá bez escapovania. */
    private static final Pattern CID_OK = Pattern.compile("[A-Za-z0-9_.:-]*");
    private static final Pattern CID_IN_TEXT = Pattern.compile("\"cid\"\\s*:\\s*\"([^\"]*)\"");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String host;
    private final int port;
    private final String devId;
    private final byte[] localKey;
    /** Prázdne = zariadenie samo (WiFi). Inak ID podzariadenia (node_id) za touto bránou (Zigbee, Bluetooth). */
    private final String cid;
    private final int proto; // 33, 34 alebo 35
    private final byte[] versionHeader; // "3.x" + 12 núl
    private final SocketFactory socketFactory;

    public TuyaDevice(String host, String devId, String localKey, String version, SocketFactory socketFactory) {
        this(host, DEFAULT_PORT, devId, localKey, version, socketFactory);
    }

    public TuyaDevice(String host, int port, String devId, String localKey, String version, SocketFactory socketFactory) {
        this(host, port, devId, localKey, version, socketFactory, "");
    }

    /**
     * Podzariadenie za bránou: [devId] a [localKey] sú údaje brány, [cid] je ID podzariadenia (node_id).
     * S prázdnym [cid] sa komunikuje priamo so zariadením, presne ako doteraz.
     */
    public TuyaDevice(String host, int port, String devId, String localKey, String version, SocketFactory socketFactory, String cid) {
        this.host = host;
        this.port = port;
        this.devId = devId;
        this.cid = cid == null ? "" : cid.trim();
        if (!CID_OK.matcher(this.cid).matches()) {
            throw new IllegalArgumentException("Neplatné ID podzariadenia (cid)");
        }
        this.localKey = localKey.getBytes(StandardCharsets.UTF_8);
        if (this.localKey.length != 16) {
            throw new IllegalArgumentException("Local key musí mať presne 16 znakov");
        }
        switch (version) {
            case "3.3": proto = 33; break;
            case "3.4": proto = 34; break;
            case "3.5": proto = 35; break;
            default: throw new IllegalArgumentException("Nepodporovaná verzia protokolu: " + version);
        }
        this.versionHeader = new byte[15];
        byte[] v = version.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(v, 0, versionHeader, 0, 3);
        this.socketFactory = socketFactory != null ? socketFactory : SocketFactory.getDefault();
    }

    /** Vráti JSON so stavom (napr. {"dps":{"1":true}} alebo {"data":{"dps":{...}}}). */
    public String query() throws IOException {
        try (Session s = new Session()) {
            String json = queryJson(proto, devId, cid, now());
            s.send(proto >= 34 ? CMD_QUERY_NEW : CMD_QUERY, json.getBytes(StandardCharsets.UTF_8), false);
            Frame f = s.await(CMD_QUERY, CMD_QUERY_NEW, CMD_STATUS);
            return text(f);
        }
    }

    /**
     * Stav ako {@link #query()} a potom v tom istom spojení vyžiada ďalšie DP (UPDATEDPS, ako tinytuya updatedps).
     * Niektoré DP (napr. raw DP 51 mix_rgbcw) zariadenie v bežnom stave neposiela. Pre každú skupinu [groups]
     * pošle jednu požiadavku a [waitMs] zbiera hlásenia (pri [stopOnFirst] skončí pri prvom hlásení).
     * Vráti texty všetkých rámcov; prvý je odpoveď na stav. Chyba pri dodatočnom vyžiadaní sa ignoruje.
     */
    public List<String> queryWithExtra(int[][] groups, long waitMs, boolean stopOnFirst) throws IOException {
        try (Session s = new Session()) {
            List<String> out = new ArrayList<>();
            String json = queryJson(proto, devId, cid, now());
            s.send(proto >= 34 ? CMD_QUERY_NEW : CMD_QUERY, json.getBytes(StandardCharsets.UTF_8), false);
            out.add(text(s.await(CMD_QUERY, CMD_QUERY_NEW, CMD_STATUS)));
            if (groups == null) {
                return out;
            }
            try {
                for (int[] g : groups) {
                    if (g == null || g.length == 0) {
                        continue;
                    }
                    s.send(CMD_UPDATEDPS, updateJson(g), false);
                    long end = System.currentTimeMillis() + waitMs;
                    while (true) {
                        long left = end - System.currentTimeMillis();
                        if (left <= 0) {
                            break;
                        }
                        Frame f = s.poll((int) Math.max(50, left));
                        if (f == null) {
                            break;
                        }
                        String t = f.retcode == 0 ? text(f) : "";
                        if (!t.isEmpty() && t.contains("dps") && !forOtherCid(t)) {
                            out.add(t);
                            if (stopOnFirst) {
                                break;
                            }
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // zariadenie UPDATEDPS nepodporuje alebo zavrelo spojenie – stav z query stačí
            }
            return out;
        }
    }

    /** Prijatý rámec pri sledovaní zmien. */
    public interface MonitorListener {
        /** Rámec s dátami (stav alebo hlásenie zmeny); [text] je dešifrovaný JSON. */
        void onFrame(int cmd, int retcode, String text);

        /** Stav spojenia (pripojené, odpojené, chyba) – na zobrazenie v logu. */
        void onInfo(String message);
    }

    /** Či sa má sledovanie zastaviť. */
    public interface Cancel {
        boolean isCancelled();
    }

    /**
     * Drží spojenie otvorené a hlási každý prijatý rámec (stav aj zmeny, ktoré zariadenie samo pošle, napr. keď ho
     * niekto ovláda z Tuya appky). Raz za ~9 s pošle heartbeat. Pri výpadku sa znova pripojí. Skončí po [durationMs]
     * alebo keď [cancel] vráti true. Výnimky von nepúšťa (chyby hlási cez onInfo).
     */
    public void monitor(int[] extraDps, long durationMs, MonitorListener listener, Cancel cancel) {
        long end = System.currentTimeMillis() + durationMs;
        while (!cancel.isCancelled() && System.currentTimeMillis() < end) {
            try (Session s = new Session()) {
                listener.onInfo("pripojené");
                String q = queryJson(proto, devId, cid, now());
                s.send(proto >= 34 ? CMD_QUERY_NEW : CMD_QUERY, q.getBytes(StandardCharsets.UTF_8), false);
                if (extraDps != null && extraDps.length > 0) {
                    s.send(CMD_UPDATEDPS, updateJson(extraDps), false);
                }
                long lastHb = System.currentTimeMillis();
                byte[] hb = ("{\"gwId\":\"" + devId + "\",\"devId\":\"" + devId + "\"}").getBytes(StandardCharsets.UTF_8);
                while (!cancel.isCancelled() && System.currentTimeMillis() < end) {
                    Frame f = s.poll(1000);
                    if (System.currentTimeMillis() - lastHb > 9000) {
                        s.send(CMD_HEART_BEAT, hb, false);
                        lastHb = System.currentTimeMillis();
                    }
                    if (f == null || f.cmd == CMD_HEART_BEAT) {
                        continue;
                    }
                    String t = text(f);
                    if (forOtherCid(t)) {
                        continue; // hlásenie iného podzariadenia na tej istej bráne
                    }
                    if (!t.isEmpty() || f.retcode != 0) {
                        listener.onFrame(f.cmd, f.retcode, t);
                    }
                }
            } catch (IOException | RuntimeException e) {
                listener.onInfo("spojenie prerušené: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                long wait = System.currentTimeMillis() + 2000;
                while (!cancel.isCancelled() && System.currentTimeMillis() < wait && System.currentTimeMillis() < end) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private byte[] updateJson(int[] dps) {
        return updateJson(dps, cid).getBytes(StandardCharsets.UTF_8);
    }

    /** Požiadavka na vybrané DP (UPDATEDPS). Pri podzariadení pribudne cid (tinytuya ho pridáva ku každému príkazu). */
    static String updateJson(int[] dps, String cid) {
        StringBuilder b = new StringBuilder("{\"dpId\":[");
        for (int i = 0; i < dps.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(dps[i]);
        }
        b.append(']');
        if (!cid.isEmpty()) {
            b.append(",\"cid\":\"").append(cid).append('"');
        }
        return b.append('}').toString();
    }

    /**
     * Dotaz na stav. Samotné zariadenie: ako doteraz. Podzariadenie za bránou (tinytuya, šablóna zigbee):
     * 3.3 = {"t":int,"cid":…}, 3.4 a 3.5 = {"cid":…}.
     */
    static String queryJson(int proto, String devId, String cid, long nowSec) {
        if (cid.isEmpty()) {
            return "{\"gwId\":\"" + devId + "\",\"devId\":\"" + devId + "\",\"uid\":\"" + devId
                    + "\",\"t\":\"" + nowSec + "\"}";
        }
        if (proto >= 34) {
            return "{\"cid\":\"" + cid + "\"}";
        }
        return "{\"t\":" + nowSec + ",\"cid\":\"" + cid + "\"}";
    }

    /**
     * Príkaz na nastavenie hodnôt. Podzariadenie: 3.3 = {"t":int,"cid":…,"dps":…}, 3.4 a 3.5 = cid aj ctype 0
     * v časti data (tinytuya, šablóny zigbee_v3.4 a zigbee_v3.5).
     */
    static String controlJson(int proto, String devId, String cid, String dpsJson, long nowSec) {
        if (cid.isEmpty()) {
            if (proto >= 34) {
                return "{\"data\":{\"ctype\":0,\"devId\":\"" + devId + "\",\"gwId\":\"" + devId
                        + "\",\"uid\":\"\",\"dps\":" + dpsJson + "},\"protocol\":5,\"t\":" + nowSec + "}";
            }
            return "{\"devId\":\"" + devId + "\",\"uid\":\"" + devId + "\",\"t\":\"" + nowSec
                    + "\",\"dps\":" + dpsJson + "}";
        }
        if (proto >= 34) {
            return "{\"protocol\":5,\"t\":" + nowSec + ",\"data\":{\"cid\":\"" + cid + "\",\"ctype\":0,\"dps\":"
                    + dpsJson + "},\"cid\":\"" + cid + "\"}";
        }
        return "{\"t\":" + nowSec + ",\"cid\":\"" + cid + "\",\"dps\":" + dpsJson + "}";
    }

    /** Hlásenie patrí inému podzariadeniu tej istej brány (obsahuje cid, ktorý nie je náš). */
    private boolean forOtherCid(String text) {
        if (cid.isEmpty() || text == null || text.isEmpty()) {
            return false;
        }
        Matcher m = CID_IN_TEXT.matcher(text);
        return m.find() && !m.group(1).equals(cid);
    }

    /**
     * Opýta sa brány na zoznam podzariadení a ich stav online (tinytuya subdev_query). Vráti texty všetkých
     * odpovedí; formát závisí od brány, preto sa používa len ako diagnostika. Chyby posiela von.
     */
    public List<String> subdevQuery(long waitMs) throws IOException {
        try (Session s = new Session()) {
            s.send(CMD_LAN_EXT_STREAM, "{\"data\":{\"cids\":[]},\"reqType\":\"subdev_online_stat_query\"}"
                    .getBytes(StandardCharsets.UTF_8), false);
            List<String> out = new ArrayList<>();
            long end = System.currentTimeMillis() + waitMs;
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                Frame f = s.poll((int) Math.max(50, left));
                if (f == null) {
                    break;
                }
                if (f.cmd == CMD_HEART_BEAT) {
                    continue;
                }
                String t = text(f);
                if (f.retcode != 0) {
                    t = "(kód " + f.retcode + ") " + t;
                }
                if (!t.trim().isEmpty()) {
                    out.add(t);
                }
            }
            return out;
        }
    }

    /** Nastaví datapointy, napr. dpsJson = {"1":true}. Vráti odpoveď zariadenia (často prázdnu). */
    public String setDps(String dpsJson) throws IOException {
        try (Session s = new Session()) {
            s.send(controlCmd(), controlJson(dpsJson), true);
            Frame f = s.await(CMD_CONTROL, CMD_CONTROL_NEW, CMD_STATUS);
            return text(f);
        }
    }

    private int controlCmd() {
        return proto >= 34 ? CMD_CONTROL_NEW : CMD_CONTROL;
    }

    private byte[] controlJson(String dpsJson) {
        return controlJson(proto, devId, cid, dpsJson, now()).getBytes(StandardCharsets.UTF_8);
    }

    /** Otvorí trvalé spojenie na rýchle posielanie príkazov počas ťahania posuvníka (3.4/3.5 bez nového vyjednávania). */
    public Live openLive() throws IOException {
        return new Live();
    }

    /** Trvalé spojenie: {@link #set} pošle príkaz a krátko počká na potvrdenie. Nie je vláknovo bezpečné. */
    public final class Live implements Closeable {
        private final Session s = new Session();

        Live() throws IOException {
        }

        /** Pošle dps. Staré hlásenia v spojení zahodí; na potvrdenie čaká najviac 400 ms (bez neho to nie je chyba). */
        public void set(String dpsJson) throws IOException {
            while (s.poll(1) != null) {
                // zahodiť nahromadené hlásenia
            }
            s.send(controlCmd(), controlJson(dpsJson), true);
            long end = System.currentTimeMillis() + 400;
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    return;
                }
                Frame f = s.poll((int) left);
                if (f == null) {
                    return;
                }
                if (f.retcode != 0) {
                    throw new DeviceException(f.retcode);
                }
                if (f.cmd == CMD_CONTROL || f.cmd == CMD_CONTROL_NEW) {
                    return;
                }
            }
        }

        @Override
        public void close() {
            s.close();
        }
    }

    // ---------------------------------------------------------------- spojenie

    /** Zariadenie odpovedalo s nenulovým návratovým kódom (napr. 1 = príkaz odmietnuté alebo zaneprázdnené). */
    public static final class DeviceException extends IOException {
        public final int code;

        DeviceException(int code) {
            super("Zariadenie vrátilo chybu (kód " + code + ")");
            this.code = code;
        }
    }

    private static final class Frame {
        int seq;
        int cmd;
        int retcode;
        byte[] payload;
    }

    private final class Session implements Closeable {
        private final Socket socket;
        private InputStream in;
        private OutputStream out;
        private byte[] key = localKey;
        private int seq = 1;

        Session() throws IOException {
            socket = socketFactory.createSocket();
            try {
                socket.setSoTimeout(READ_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                in = new BufferedInputStream(socket.getInputStream());
                out = socket.getOutputStream();
                if (proto >= 34) {
                    negotiate();
                }
            } catch (IOException | RuntimeException e) {
                closeQuietly();
                throw e;
            }
        }

        @Override
        public void close() {
            closeQuietly();
        }

        private void closeQuietly() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nič
            }
        }

        void send(int cmd, byte[] plain, boolean withHeader) throws IOException {
            byte[] frame = buildFrame(seq++, cmd, plain, withHeader, key);
            out.write(frame);
            out.flush();
        }

        /**
         * Počká najviac [timeoutMs] na začiatok ďalšieho rámca; ak nepríde, vráti null. Zvyšok rámca sa číta
         * s bežným timeoutom, aby sa rámec nikdy neprečítal len napoly.
         */
        Frame poll(int timeoutMs) throws IOException {
            socket.setSoTimeout(timeoutMs);
            try {
                in.mark(1);
                int b = in.read();
                if (b < 0) {
                    throw new EOFException("Zariadenie zavrelo spojenie");
                }
                in.reset();
            } catch (SocketTimeoutException e) {
                return null;
            } finally {
                socket.setSoTimeout(READ_TIMEOUT_MS);
            }
            return readFrame(key, false);
        }

        /** Čaká na rámec s jedným z očakávaných príkazov, ostatné (heartbeat a pod.) preskočí. */
        Frame await(int... expected) throws IOException {
            // brána môže medzi odpoveďami posielať hlásenia iných podzariadení; tie preskočíme, preto viac pokusov
            int max = cid.isEmpty() ? 8 : 24;
            for (int i = 0; i < max; i++) {
                Frame f = readFrame(key, false);
                if (f.retcode != 0) {
                    throw new DeviceException(f.retcode);
                }
                for (int e : expected) {
                    if (f.cmd == e) {
                        if (forOtherCid(text(f))) {
                            break;
                        }
                        return f;
                    }
                }
            }
            throw new IOException("Zariadenie neodpovedalo na príkaz");
        }

        private void negotiate() throws IOException {
            byte[] localNonce = new byte[16];
            RANDOM.nextBytes(localNonce);
            send(CMD_NEG_START, localNonce, false);

            Frame r = readFrame(localKey, true);
            if (r.cmd != CMD_NEG_RESP) {
                throw new IOException("Neočakávaná odpoveď pri nadväzovaní spojenia (cmd " + r.cmd + ")");
            }
            if (r.payload.length < 48) {
                throw new IOException("Príliš krátka odpoveď pri nadväzovaní spojenia – skontroluj local key");
            }
            byte[] remoteNonce = Arrays.copyOfRange(r.payload, 0, 16);
            byte[] mac = Arrays.copyOfRange(r.payload, 16, 48);
            if (!MessageDigest.isEqual(mac, hmac(localKey, localNonce))) {
                throw new IOException("Overenie zlyhalo – skontroluj local key");
            }
            send(CMD_NEG_FINISH, hmac(localKey, remoteNonce), false);

            byte[] xor = new byte[16];
            for (int i = 0; i < 16; i++) {
                xor[i] = (byte) (localNonce[i] ^ remoteNonce[i]);
            }
            try {
                if (proto == 34) {
                    key = ecb(Cipher.ENCRYPT_MODE, localKey, xor);
                } else {
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(localKey, "AES"),
                            new GCMParameterSpec(128, Arrays.copyOfRange(localNonce, 0, 12)));
                    key = Arrays.copyOfRange(c.doFinal(xor), 0, 16);
                }
            } catch (GeneralSecurityException e) {
                throw new IOException("Odvodenie session kľúča zlyhalo", e);
            }
        }

        // ------------------------------------------------------------ rámce

        private byte[] buildFrame(int seqNo, int cmd, byte[] plain, boolean withHeader, byte[] k) throws IOException {
            try {
                if (proto == 33) {
                    byte[] enc = ecb(Cipher.ENCRYPT_MODE, k, pad(plain));
                    byte[] payload = withHeader ? concat(versionHeader, enc) : enc;
                    return frame55AA(seqNo, cmd, payload, null);
                }
                byte[] p = withHeader ? concat(versionHeader, plain) : plain;
                if (proto == 34) {
                    return frame55AA(seqNo, cmd, ecb(Cipher.ENCRYPT_MODE, k, pad(p)), k);
                }
                return frame6699(seqNo, cmd, p, k);
            } catch (GeneralSecurityException e) {
                throw new IOException("Šifrovanie zlyhalo", e);
            }
        }

        private Frame readFrame(byte[] k, boolean negotiation) throws IOException {
            int prefix = readInt();
            Frame f = new Frame();
            try {
                if (prefix == PREFIX_55AA) {
                    f.seq = readInt();
                    f.cmd = readInt();
                    int len = readInt();
                    int tail = proto == 33 ? 4 : 32;
                    if (len < tail + 4 || len > 65536) {
                        throw new IOException("Neplatná dĺžka rámca");
                    }
                    byte[] body = readFully(len);
                    if (getInt(body, len - 4) != SUFFIX_55AA) {
                        throw new IOException("Neplatný koniec rámca");
                    }
                    byte[] region = Arrays.copyOfRange(body, 0, len - tail - 4);
                    ByteBuffer hb = ByteBuffer.allocate(16 + region.length);
                    hb.putInt(prefix).putInt(f.seq).putInt(f.cmd).putInt(len).put(region);
                    byte[] expected;
                    if (proto == 33) {
                        CRC32 crc = new CRC32();
                        crc.update(hb.array());
                        expected = ByteBuffer.allocate(4).putInt((int) crc.getValue()).array();
                    } else {
                        expected = hmac(k, hb.array());
                    }
                    if (!MessageDigest.isEqual(expected, Arrays.copyOfRange(body, len - tail - 4, len - 4))) {
                        throw new IOException("Neplatný kontrolný súčet rámca – skontroluj local key");
                    }
                    byte[] data = region;
                    if (hasRetcode(data)) {
                        f.retcode = getInt(data, 0);
                        data = Arrays.copyOfRange(data, 4, data.length);
                    }
                    f.payload = decode55AA(data, k, negotiation);
                } else if (prefix == PREFIX_6699) {
                    byte[] aad = readFully(14);
                    ByteBuffer ab = ByteBuffer.wrap(aad);
                    ab.getShort();
                    f.seq = ab.getInt();
                    f.cmd = ab.getInt();
                    int len = ab.getInt();
                    if (len < 28 || len > 65536) {
                        throw new IOException("Neplatná dĺžka rámca");
                    }
                    byte[] body = readFully(len + 4);
                    if (getInt(body, len) != SUFFIX_6699) {
                        throw new IOException("Neplatný koniec rámca");
                    }
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"),
                            new GCMParameterSpec(128, Arrays.copyOfRange(body, 0, 12)));
                    c.updateAAD(aad);
                    byte[] data = c.doFinal(body, 12, len - 12);
                    if (hasRetcode(data)) {
                        f.retcode = getInt(data, 0);
                        data = Arrays.copyOfRange(data, 4, data.length);
                    }
                    f.payload = stripHeader(data);
                } else {
                    throw new IOException("Neznámy formát rámca (0x" + Integer.toHexString(prefix) + ")");
                }
            } catch (GeneralSecurityException e) {
                throw new IOException("Dešifrovanie zlyhalo – skontroluj local key a verziu protokolu", e);
            }
            return f;
        }

        private byte[] decode55AA(byte[] data, byte[] k, boolean negotiation) throws GeneralSecurityException, IOException {
            if (proto == 33) {
                data = stripHeader(data);
                return data.length == 0 ? data : unpad(ecb(Cipher.DECRYPT_MODE, k, requireBlocks(data)));
            }
            if (data.length == 0) {
                return data;
            }
            byte[] plain = ecb(Cipher.DECRYPT_MODE, k, requireBlocks(data));
            // pri vyjednávaní kľúča má odpoveď presne 48 bajtov, padding neriešime
            return negotiation ? plain : stripHeader(unpad(plain));
        }

        private int readInt() throws IOException {
            return getInt(readFully(4), 0);
        }

        private byte[] readFully(int n) throws IOException {
            byte[] buf = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(buf, off, n - off);
                if (r < 0) {
                    throw new EOFException("Zariadenie zavrelo spojenie");
                }
                off += r;
            }
            return buf;
        }
    }

    // ---------------------------------------------------------------- pomocné

    private byte[] frame55AA(int seq, int cmd, byte[] payload, byte[] hmacKey) {
        int tail = hmacKey == null ? 4 : 32;
        ByteBuffer b = ByteBuffer.allocate(16 + payload.length + tail + 4);
        b.putInt(PREFIX_55AA).putInt(seq).putInt(cmd).putInt(payload.length + tail + 4).put(payload);
        byte[] head = Arrays.copyOf(b.array(), b.position());
        if (hmacKey == null) {
            CRC32 crc = new CRC32();
            crc.update(head);
            b.putInt((int) crc.getValue());
        } else {
            b.put(hmac(hmacKey, head));
        }
        b.putInt(SUFFIX_55AA);
        return b.array();
    }

    private byte[] frame6699(int seq, int cmd, byte[] plain, byte[] key) throws GeneralSecurityException {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        int length = 12 + plain.length + 16;
        ByteBuffer ab = ByteBuffer.allocate(14);
        ab.putShort((short) 0).putInt(seq).putInt(cmd).putInt(length);
        byte[] aad = ab.array();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD(aad);
        byte[] ctTag = c.doFinal(plain);
        ByteBuffer b = ByteBuffer.allocate(4 + 14 + 12 + ctTag.length + 4);
        b.putInt(PREFIX_6699).put(aad).put(iv).put(ctTag).putInt(SUFFIX_6699);
        return b.array();
    }

    private static boolean hasRetcode(byte[] data) {
        return data.length >= 4 && (getInt(data, 0) & 0xFFFFFF00) == 0;
    }

    /** Odstráni hlavičku "3.x" + 12 núl, ak je na začiatku. */
    private static byte[] stripHeader(byte[] data) {
        if (data.length >= 15 && data[0] == '3' && data[1] == '.') {
            return Arrays.copyOfRange(data, 15, data.length);
        }
        return data;
    }

    private static byte[] requireBlocks(byte[] data) throws IOException {
        if (data.length % 16 != 0) {
            throw new IOException("Neplatná odpoveď zariadenia: "
                    + new String(data, StandardCharsets.UTF_8).trim());
        }
        return data;
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

    private static byte[] ecb(int mode, byte[] key, byte[] data) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"));
        return c.doFinal(data);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static int getInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static String text(Frame f) {
        return new String(f.payload, StandardCharsets.UTF_8).trim();
    }
}
