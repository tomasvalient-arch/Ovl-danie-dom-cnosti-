package sk.domov.ovladanie.pc;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.SocketFactory;

/**
 * Klient protokolu Domov PC: spojenie s PC programom, overenie, šifrovanie, príjem obrazu a odosielanie vstupov.
 * Čisté JDK (bez Androidu), takže sa overuje proti skutočnému PC programu v tools/run-pc-tests.sh.
 *
 * Odosielacie metódy (moveRel, button, key...) sú neblokujúce a bezpečné z hlavného vlákna: správy čakajú vo fronte
 * a posiela ich vlastné vlákno. Správy od PC sa doručujú na vlákne čítania, preto v [Listener] nerob nič dlhé.
 */
public class PcClient {

    public static final class Monitor {
        public final int index, x, y, w, h;
        public final boolean primary;

        Monitor(int index, int x, int y, int w, int h, boolean primary) {
            this.index = index; this.x = x; this.y = y; this.w = w; this.h = h; this.primary = primary;
        }
    }

    public static final class Info {
        public int protocolVersion;
        public byte[] serverId;
        public String name;
        public List<byte[]> macs = new ArrayList<>();
        public int flags;
        public int activeMonitor;
        public List<Monitor> monitors = new ArrayList<>();

        public boolean canPower() { return (flags & PcProto.FLAG_CAN_POWER) != 0; }
        public boolean elevated() { return (flags & PcProto.FLAG_ELEVATED) != 0; }
    }

    public static final class Screen {
        public int monitor, outW, outH, divisor, srcW, srcH;
        public long epoch;
    }

    public static final class Rect {
        public final int y, h;
        public final byte[] jpeg;

        Rect(int y, int h, byte[] jpeg) { this.y = y; this.h = h; this.jpeg = jpeg; }
    }

    public abstract static class Listener {
        /** Hneď po vytvorení spojenia, ešte pred prvou správou (aby mohol poslucháč posielať potvrdenia obrazu). */
        public void onAttached(PcClient client) {}
        public void onPaired(byte[] deviceKey) {}
        public void onInfo(Info info) {}
        public void onScreen(Screen screen) {}
        public void onRects(long epoch, long seq, List<Rect> rects) {}
        public void onClip(String text) {}
        /** Poloha kurzora PC: x16, y16 v rozsahu 0..65535 cez aktívny monitor; [visible] = kurzor je na tomto monitore. */
        public void onCursor(int x16, int y16, boolean visible) {}
        public void onNotice(int code, String message) {}
        /** Spojenie sa skončilo (raz). */
        public void onClosed(String reason) {}
    }

    private static final int MAX_HANDSHAKE = 512;
    private static final int MAX_FRAME = 64 << 20;
    private static final int PING_EVERY_MS = 3000;
    private static final int IDLE_TIMEOUT_MS = 12000;
    private static final Object TOKEN = new Object();

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final byte[] sendKey, recvKey;
    private final Listener listener;
    private long sendCtr = 1; // 0 patrí prvej správe od servera; klient začína od 0 (nastavené nižšie)
    private long recvCtr;
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object moveLock = new Object();
    private int accDx, accDy;
    private boolean tokenQueued;
    private volatile int rttMs = -1;
    private final byte[] serverId;

    private PcClient(Socket socket, DataInputStream in, OutputStream out, byte[] sendKey, byte[] recvKey,
                      long recvCtr, byte[] serverId, Listener listener) {
        this.socket = socket; this.in = in; this.out = out; this.sendKey = sendKey; this.recvKey = recvKey;
        this.recvCtr = recvCtr; this.serverId = serverId; this.listener = listener;
        this.sendCtr = 0;
    }

    public byte[] serverId() { return serverId.clone(); }

    /** Posledne zmeraná odozva v ms (alebo -1). */
    public int rttMs() { return rttMs; }

    public boolean isClosed() { return closed.get(); }

    // ---------------------------------------------------------------- pripojenie

    /** Dodá kľúč podľa ID PC (to príde až v odpovedi servera, preto sa kľúč z kódu počíta až vtedy). */
    public interface KeyProvider {
        byte[] keyFor(byte[] serverId) throws PcException;
    }

    /**
     * Pripojí sa k PC a dokončí overenie. [pairing]=true: [key] je kľúč z párovacieho kódu (PcCrypto.pairKey),
     * po úspechu príde Listener.onPaired s trvalým kľúčom. Inak je [key] kľúč spárovaného telefónu.
     * Blokuje (sieť), preto nevolaj z hlavného vlákna.
     */
    public static PcClient connect(String host, int port, byte[] deviceId, byte[] key, boolean pairing,
                                   String deviceName, SocketFactory factory, int connectTimeoutMs,
                                   Listener listener) throws PcException {
        return connect(host, port, deviceId, sid -> key, null, pairing, deviceName, factory, connectTimeoutMs, listener);
    }

    /**
     * Párovanie kódom bez toho, aby telefón poznal ID PC vopred (ručné zadanie IP adresy): kľúč z kódu sa
     * odvodí, až keď PC pošle svoje ID. [expectedServerId] (z QR kódu) môže byť null; ak je zadané a PC má
     * iné ID, párovanie sa zastaví (na adrese je iný PC, než ukazoval QR kód).
     */
    public static PcClient connectWithCode(String host, int port, byte[] deviceId, String code, byte[] expectedServerId,
                                           String deviceName, SocketFactory factory, int connectTimeoutMs,
                                           Listener listener) throws PcException {
        return connect(host, port, deviceId, sid -> PcCrypto.pairKey(code, sid), expectedServerId, true, deviceName,
                factory, connectTimeoutMs, listener);
    }

    /** Spojenie spárovaného telefónu; [expectedServerId] chráni pred pripojením k inému PC na tej istej adrese. */
    public static PcClient connectPaired(String host, int port, byte[] deviceId, byte[] key, byte[] expectedServerId,
                                         String deviceName, SocketFactory factory, int connectTimeoutMs,
                                         Listener listener) throws PcException {
        return connect(host, port, deviceId, sid -> key, expectedServerId, false, deviceName, factory, connectTimeoutMs, listener);
    }

    private static PcClient connect(String host, int port, byte[] deviceId, KeyProvider keys, byte[] expectedServerId,
                                    boolean pairing, String deviceName, SocketFactory factory, int connectTimeoutMs,
                                    Listener listener) throws PcException {
        Socket s = null;
        try {
            s = factory != null ? factory.createSocket() : new Socket();
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(8000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
            OutputStream out = s.getOutputStream();
            return handshake(s, in, out, deviceId, keys, expectedServerId, pairing, deviceName, listener);
        } catch (PcException e) {
            closeQuietly(s);
            throw e;
        } catch (SocketTimeoutException e) {
            closeQuietly(s);
            throw new PcException("PC neodpovedá (" + host + ":" + port + ")", e);
        } catch (IOException e) {
            closeQuietly(s);
            throw new PcException("Nepodarilo sa pripojiť k PC (" + host + ":" + port + ")", e);
        } catch (GeneralSecurityException e) {
            closeQuietly(s);
            throw new PcException("Chyba šifrovania", e);
        }
    }

    private static PcClient handshake(Socket s, DataInputStream in, OutputStream out, byte[] deviceId, KeyProvider keys,
                                      byte[] expectedServerId, boolean pairing, String deviceName, Listener listener)
            throws IOException, PcException, GeneralSecurityException {
        int mode = pairing ? 1 : 0;
        byte[] name = truncateUtf8(deviceName == null ? "" : deviceName, PcProto.MAX_NAME);
        byte[] cNonce = PcCrypto.random(32);
        byte[] hello = new byte[1 + 1 + 1 + 16 + 32 + 1 + name.length];
        int o = 0;
        hello[o++] = PcProto.H_CLIENT_HELLO;
        hello[o++] = PcProto.VERSION;
        hello[o++] = (byte) mode;
        System.arraycopy(deviceId, 0, hello, o, 16); o += 16;
        System.arraycopy(cNonce, 0, hello, o, 32); o += 32;
        hello[o++] = (byte) name.length;
        System.arraycopy(name, 0, hello, o, name.length);
        writeFrame(out, hello);

        byte[] resp = readFrame(in, MAX_HANDSHAKE);
        if (resp.length >= 2 && (resp[0] & 0xff) == PcProto.H_ERROR) {
            int code = resp[1] & 0xff;
            throw new PcException(code, PcProto.errorText(code));
        }
        if (resp.length != 82 || (resp[0] & 0xff) != PcProto.H_SERVER_HELLO) {
            throw new PcException(PcProto.ERR_PROTOCOL, "Neplatná odpoveď PC programu.");
        }
        byte[] serverId = Arrays.copyOfRange(resp, 2, 18);
        byte[] sNonce = Arrays.copyOfRange(resp, 18, 50);
        byte[] sProof = Arrays.copyOfRange(resp, 50, 82);
        if (expectedServerId != null && !Arrays.equals(expectedServerId, serverId)) {
            throw new PcException(PcProto.ERR_WRONG_PC, "Na tejto adrese je iný PC, než ktorý sa čakal.");
        }
        byte[] key = keys.keyFor(serverId);
        byte[] want = PcCrypto.proof(key, "server", mode, deviceId, cNonce, sNonce, serverId, name);
        if (!PcCrypto.equal(sProof, want)) {
            throw new PcException(PcProto.ERR_BAD_PROOF, pairing
                    ? "Nesprávny párovací kód."
                    : "PC sa nevie preukázať uloženým kľúčom. Je to naozaj ten istý PC? Skús ho spárovať znova.");
        }
        byte[] cProof = PcCrypto.proof(key, "client", mode, deviceId, sNonce, cNonce, serverId, name);
        byte[] pf = new byte[33];
        pf[0] = PcProto.H_CLIENT_PROOF;
        System.arraycopy(cProof, 0, pf, 1, 32);
        writeFrame(out, pf);

        byte[][] sk = PcCrypto.sessionKeys(key, cNonce, sNonce);
        byte[] firstSealed = readFrame(in, MAX_FRAME);
        byte[] first;
        try {
            first = PcCrypto.open(sk[1], 1, 0, firstSealed);
        } catch (GeneralSecurityException e) {
            if (firstSealed.length >= 2 && (firstSealed[0] & 0xff) == PcProto.H_ERROR) {
                int code = firstSealed[1] & 0xff;
                throw new PcException(code, PcProto.errorText(code));
            }
            throw new PcException(PcProto.ERR_BAD_PROOF, "Spojenie sa nepodarilo overiť.");
        }
        s.setSoTimeout(IDLE_TIMEOUT_MS);
        PcClient c = new PcClient(s, in, out, sk[0], sk[1], 1, serverId, listener);
        listener.onAttached(c);
        c.dispatch(first);
        c.startThreads();
        return c;
    }

    private void startThreads() {
        Thread reader = new Thread(this::readLoop, "pc-read");
        reader.setDaemon(true);
        reader.start();
        Thread writer = new Thread(this::writeLoop, "pc-write");
        writer.setDaemon(true);
        writer.start();
        Thread pinger = new Thread(() -> {
            while (!closed.get()) {
                try {
                    Thread.sleep(PING_EVERY_MS);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.nanoTime() / 1_000_000L;
                send(PcProto.M_PING, ByteBuffer.allocate(8).putLong(now).array());
            }
        }, "pc-ping");
        pinger.setDaemon(true);
        pinger.start();
    }

    // ---------------------------------------------------------------- rámce

    private static void writeFrame(OutputStream out, byte[] body) throws IOException {
        byte[] buf = new byte[4 + body.length];
        buf[0] = (byte) (body.length >>> 24);
        buf[1] = (byte) (body.length >>> 16);
        buf[2] = (byte) (body.length >>> 8);
        buf[3] = (byte) body.length;
        System.arraycopy(body, 0, buf, 4, body.length);
        out.write(buf);
        out.flush();
    }

    private static byte[] readFrame(DataInputStream in, int max) throws IOException, PcException {
        int len = in.readInt();
        if (len < 0 || len > max) throw new PcException(PcProto.ERR_PROTOCOL, "Neplatná správa od PC.");
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
    }

    private void readLoop() {
        String reason = "Spojenie sa skončilo.";
        try {
            while (!closed.get()) {
                byte[] sealed = readFrame(in, MAX_FRAME);
                byte[] plain = PcCrypto.open(recvKey, 1, recvCtr++, sealed);
                dispatch(plain);
            }
        } catch (SocketTimeoutException e) {
            reason = "Spojenie s PC sa stratilo (žiadna odpoveď).";
        } catch (GeneralSecurityException e) {
            reason = "Správu od PC sa nepodarilo overiť.";
        } catch (PcException e) {
            reason = e.getMessage();
        } catch (IOException e) {
            reason = "Spojenie s PC sa prerušilo.";
        } catch (RuntimeException e) {
            reason = "Chyba spracovania: " + e.getClass().getSimpleName();
        }
        close(reason);
    }

    private void dispatch(byte[] plain) throws PcException {
        if (plain.length == 0) return;
        int type = plain[0] & 0xff;
        byte[] p = Arrays.copyOfRange(plain, 1, plain.length);
        try {
            switch (type) {
                case PcProto.M_PAIRED:
                    if (p.length == 32) listener.onPaired(p);
                    break;
                case PcProto.M_SESSION_INFO:
                    listener.onInfo(parseInfo(p));
                    break;
                case PcProto.M_SCREEN:
                    listener.onScreen(parseScreen(p));
                    break;
                case PcProto.M_RECTS:
                    parseRects(p);
                    break;
                case PcProto.M_CLIP:
                    listener.onClip(new String(p, StandardCharsets.UTF_8));
                    break;
                case PcProto.M_CURSOR:
                    if (p.length >= 5) {
                        listener.onCursor(((p[0] & 0xff) << 8) | (p[1] & 0xff), ((p[2] & 0xff) << 8) | (p[3] & 0xff), (p[4] & 1) != 0);
                    }
                    break;
                case PcProto.M_NOTICE:
                    if (p.length >= 1) {
                        listener.onNotice(p[0] & 0xff, new String(p, 1, p.length - 1, StandardCharsets.UTF_8));
                    }
                    break;
                case PcProto.M_PONG:
                    if (p.length >= 8) {
                        long sent = ByteBuffer.wrap(p).getLong();
                        rttMs = (int) Math.max(0, System.nanoTime() / 1_000_000L - sent);
                    }
                    break;
                default:
                    break; // neznáma správa z novšej verzie: ignorovať
            }
        } catch (java.nio.BufferUnderflowException | IndexOutOfBoundsException e) {
            throw new PcException(PcProto.ERR_PROTOCOL, "Poškodená správa od PC.");
        }
    }

    static Info parseInfo(byte[] p) {
        ByteBuffer b = ByteBuffer.wrap(p);
        Info i = new Info();
        i.protocolVersion = b.get() & 0xff;
        i.serverId = new byte[16];
        b.get(i.serverId);
        byte[] name = new byte[b.get() & 0xff];
        b.get(name);
        i.name = new String(name, StandardCharsets.UTF_8);
        int macs = b.get() & 0xff;
        for (int k = 0; k < macs; k++) {
            byte[] m = new byte[6];
            b.get(m);
            i.macs.add(m);
        }
        i.flags = b.getShort() & 0xffff;
        i.activeMonitor = b.get() & 0xff;
        int mons = b.get() & 0xff;
        for (int k = 0; k < mons; k++) {
            int x = b.getInt(), y = b.getInt(), w = b.getInt(), h = b.getInt();
            boolean primary = (b.get() & 1) != 0;
            i.monitors.add(new Monitor(k, x, y, w, h, primary));
        }
        return i;
    }

    static Screen parseScreen(byte[] p) {
        ByteBuffer b = ByteBuffer.wrap(p);
        Screen s = new Screen();
        s.monitor = b.get() & 0xff;
        s.outW = b.getInt();
        s.outH = b.getInt();
        s.divisor = b.get() & 0xff;
        s.srcW = b.getInt();
        s.srcH = b.getInt();
        s.epoch = b.getInt() & 0xffffffffL;
        return s;
    }

    private void parseRects(byte[] p) {
        ByteBuffer b = ByteBuffer.wrap(p);
        long epoch = b.getInt() & 0xffffffffL;
        long seq = b.getInt() & 0xffffffffL;
        int n = b.getShort() & 0xffff;
        List<Rect> rects = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            int y = b.getShort() & 0xffff;
            int h = b.getShort() & 0xffff;
            int len = b.getInt();
            if (len < 0 || len > b.remaining()) throw new IndexOutOfBoundsException();
            byte[] jpeg = new byte[len];
            b.get(jpeg);
            rects.add(new Rect(y, h, jpeg));
        }
        listener.onRects(epoch, seq, rects);
    }

    // ---------------------------------------------------------------- odosielanie

    private void writeLoop() {
        try {
            while (!closed.get()) {
                Object o = queue.take();
                if (o == TOKEN) {
                    int dx, dy;
                    synchronized (moveLock) {
                        tokenQueued = false;
                        dx = accDx; dy = accDy;
                        accDx = 0; accDy = 0;
                    }
                    sendMoves(dx, dy);
                } else {
                    sendPlain((byte[]) o);
                }
            }
        } catch (InterruptedException e) {
            // koniec
        } catch (IOException e) {
            close("Nepodarilo sa odoslať dáta do PC.");
        } catch (GeneralSecurityException e) {
            close("Chyba šifrovania.");
        }
    }

    private void sendMoves(int dx, int dy) throws IOException, GeneralSecurityException {
        while (dx != 0 || dy != 0) {
            int sx = Math.max(-32768, Math.min(32767, dx));
            int sy = Math.max(-32768, Math.min(32767, dy));
            sendPlain(build(PcProto.M_MOVE_REL, ByteBuffer.allocate(4).putShort((short) sx).putShort((short) sy).array()));
            dx -= sx;
            dy -= sy;
        }
    }

    private void sendPlain(byte[] plain) throws IOException, GeneralSecurityException {
        byte[] sealed = PcCrypto.seal(sendKey, 0, sendCtr++, plain);
        writeFrame(out, sealed);
    }

    private static byte[] build(int type, byte[] payload) {
        byte[] m = new byte[1 + payload.length];
        m[0] = (byte) type;
        System.arraycopy(payload, 0, m, 1, payload.length);
        return m;
    }

    /** Zaradí správu do fronty; predtým zaradí nahromadený pohyb myši, aby sa zachovalo poradie. */
    private void send(int type, byte[] payload) {
        if (closed.get()) return;
        synchronized (moveLock) {
            if (accDx != 0 || accDy != 0) {
                int dx = accDx, dy = accDy;
                accDx = 0; accDy = 0;
                while (dx != 0 || dy != 0) {
                    int sx = Math.max(-32768, Math.min(32767, dx));
                    int sy = Math.max(-32768, Math.min(32767, dy));
                    queue.add(build(PcProto.M_MOVE_REL, ByteBuffer.allocate(4).putShort((short) sx).putShort((short) sy).array()));
                    dx -= sx;
                    dy -= sy;
                }
            }
            queue.add(build(type, payload));
        }
    }

    /** Relatívny pohyb myši v bodoch obrazu PC (zlučuje sa, kým sa neodošle). */
    public void moveRel(int dx, int dy) {
        if (closed.get() || (dx == 0 && dy == 0)) return;
        synchronized (moveLock) {
            accDx += dx;
            accDy += dy;
            if (!tokenQueued) {
                tokenQueued = true;
                queue.add(TOKEN);
            }
        }
    }

    /** Absolútna poloha: x, y v rozsahu 0..65535 cez celý obraz. */
    public void moveAbs(int x16, int y16) {
        send(PcProto.M_MOVE_ABS, ByteBuffer.allocate(4).putShort((short) clamp16(x16)).putShort((short) clamp16(y16)).array());
    }

    /** Tlačidlo myši: 1 ľavé, 2 pravé, 3 stredné. */
    public void button(int button, boolean down) {
        send(PcProto.M_BUTTON, new byte[]{(byte) button, (byte) (down ? 1 : 0)});
    }

    /** Koliesko: 120 = jeden zárez; kladné dy = hore (od používateľa), kladné dx = doprava. */
    public void wheel(int dx, int dy) {
        send(PcProto.M_WHEEL, ByteBuffer.allocate(4).putShort((short) Math.max(-32768, Math.min(32767, dx)))
                .putShort((short) Math.max(-32768, Math.min(32767, dy))).array());
    }

    /** Klávesa podľa virtuálneho kódu Windows (VK_*). */
    public void key(int vk, boolean down) {
        send(PcProto.M_KEY, new byte[]{(byte) (vk >> 8), (byte) vk, (byte) (down ? 1 : 0)});
    }

    /** Napíše text (Unicode, nezávisle od rozloženia klávesnice na PC). */
    public void text(String s) {
        if (s == null || s.isEmpty()) return;
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        int off = 0;
        while (off < b.length) { // PC berie najviac 4096 B naraz; rezať na hranici znaku
            int end = Math.min(b.length, off + 3000);
            while (end < b.length && (b[end] & 0xC0) == 0x80) end--;
            send(PcProto.M_TEXT, Arrays.copyOfRange(b, off, end));
            off = end;
        }
    }

    public void ack(long seq) {
        send(PcProto.M_ACK, ByteBuffer.allocate(4).putInt((int) seq).array());
    }

    /** fps 1–30, kvalita 20–95, delič 0 (auto) až 3, monitor 255 = hlavný. */
    public void config(int fps, int quality, int divisor, int monitor) {
        send(PcProto.M_CONFIG, new byte[]{(byte) fps, (byte) quality, (byte) divisor, (byte) monitor});
    }

    public void refresh() { send(PcProto.M_REFRESH, new byte[0]); }

    public void clipSet(String text) { send(PcProto.M_CLIP_SET, text.getBytes(StandardCharsets.UTF_8)); }

    public void clipGet() { send(PcProto.M_CLIP_GET, new byte[0]); }

    public void power(int action) { send(PcProto.M_POWER, new byte[]{(byte) action}); }

    public void releaseAll() { send(PcProto.M_RELEASE_ALL, new byte[0]); }

    private static int clamp16(int v) { return Math.max(0, Math.min(65535, v)); }

    // ---------------------------------------------------------------- zatvorenie

    public void close() { close("Odpojené."); }

    private void close(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        closeQuietly(socket);
        queue.clear();
        queue.add(TOKEN); // prebudiť odosielacie vlákno
        try {
            listener.onClosed(reason);
        } catch (RuntimeException ignored) {
            // chyba v obsluhe nesmie zastaviť upratovanie
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    /** Skráti text na najviac [max] bajtov UTF-8 na hranici znaku. */
    static byte[] truncateUtf8(String s, int max) {
        byte[] b = s.trim().getBytes(StandardCharsets.UTF_8);
        if (b.length <= max) return b;
        int end = max;
        while (end > 0 && (b[end] & 0xC0) == 0x80) end--;
        return Arrays.copyOf(b, end);
    }
}
