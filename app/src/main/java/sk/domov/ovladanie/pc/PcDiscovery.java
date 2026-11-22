package sk.domov.ovladanie.pc;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hľadanie PC v sieti: UDP "DOMOVPC?" na port 47810 (broadcast alebo priamo na známu IP); PC odpovie
 * "DOMOVPC!" + verzia + ID PC (16 B) + port (u16) + dĺžka názvu + názov.
 */
public final class PcDiscovery {
    private PcDiscovery() {}

    public static final class Found {
        public final String serverId; // hex
        public final String host;
        public final int port;
        public final String name;

        Found(String serverId, String host, int port, String name) {
            this.serverId = serverId; this.host = host; this.port = port; this.name = name;
        }
    }

    /** Pomocník na viazanie soketu na WiFi sieť (na Androide Network.bindSocket). */
    public interface Binder {
        void bind(DatagramSocket socket) throws IOException;
    }

    private static final byte[] REQUEST = ("DOMOVPC?").getBytes(StandardCharsets.US_ASCII);
    private static final byte[] REPLY = ("DOMOVPC!").getBytes(StandardCharsets.US_ASCII);

    static Found parseReply(byte[] data, int len, String fromHost) {
        if (len < REPLY.length + 1 + 16 + 2 + 1) return null;
        for (int i = 0; i < REPLY.length; i++) if (data[i] != REPLY[i]) return null;
        ByteBuffer b = ByteBuffer.wrap(data, REPLY.length, len - REPLY.length);
        int ver = b.get() & 0xff;
        if (ver != PcProto.VERSION) return null;
        byte[] id = new byte[16];
        b.get(id);
        int port = b.getShort() & 0xffff;
        int nl = b.get() & 0xff;
        if (nl > b.remaining()) return null;
        byte[] name = new byte[nl];
        b.get(name);
        return new Found(PcCrypto.hex(id), fromHost, port, new String(name, StandardCharsets.UTF_8));
    }

    /**
     * Pošle žiadosť na všetky [targets] (adresy broadcastu alebo známe IP) a [durationMs] zbiera odpovede.
     * Výsledok je zoznam jedinečných PC podľa ID. Blokuje.
     */
    public static List<Found> discover(List<InetAddress> targets, int port, int durationMs, Binder binder) throws IOException {
        Map<String, Found> found = new LinkedHashMap<>();
        DatagramSocket s = new DatagramSocket();
        try {
            s.setBroadcast(true);
            if (binder != null) binder.bind(s);
            s.setSoTimeout(150);
            long end = System.currentTimeMillis() + durationMs;
            long nextSend = 0;
            byte[] buf = new byte[256];
            while (System.currentTimeMillis() < end) {
                long now = System.currentTimeMillis();
                if (now >= nextSend) {
                    for (InetAddress t : targets) {
                        try {
                            byte[] req = new byte[REQUEST.length + 1];
                            System.arraycopy(REQUEST, 0, req, 0, REQUEST.length);
                            req[REQUEST.length] = PcProto.VERSION;
                            s.send(new DatagramPacket(req, req.length, t, port));
                        } catch (IOException ignored) {
                            // niektoré adresy (napr. broadcast bez WiFi) môžu zlyhať; ostatné stačia
                        }
                    }
                    nextSend = now + 500;
                }
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    Found f = parseReply(p.getData(), p.getLength(), p.getAddress().getHostAddress());
                    if (f != null) found.put(f.serverId, f);
                } catch (SocketTimeoutException ignored) {
                    // ďalšie kolo
                }
            }
        } finally {
            s.close();
        }
        return new ArrayList<>(found.values());
    }

    /** Overí, či na [host] beží PC program (priama žiadosť); vráti ho, alebo null. */
    public static Found probe(String host, int port, int timeoutMs, Binder binder) {
        try {
            List<Found> r = discover(java.util.Collections.singletonList(InetAddress.getByName(host)), port, timeoutMs, binder);
            return r.isEmpty() ? null : r.get(0);
        } catch (IOException e) {
            return null;
        }
    }
}
