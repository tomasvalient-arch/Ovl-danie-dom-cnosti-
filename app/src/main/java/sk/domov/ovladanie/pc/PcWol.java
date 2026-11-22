package sk.domov.ovladanie.pc;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;

/** Wake-on-LAN: "magický paket" = 6 × 0xFF a potom 16 × MAC adresa, posiela sa ako UDP broadcast. */
public final class PcWol {
    private PcWol() {}

    public static byte[] packet(byte[] mac) {
        if (mac == null || mac.length != 6) throw new IllegalArgumentException("MAC adresa musí mať 6 bajtov");
        byte[] p = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) p[i] = (byte) 0xFF;
        for (int i = 0; i < 16; i++) System.arraycopy(mac, 0, p, 6 + i * 6, 6);
        return p;
    }

    /** Pošle paket na všetky adresy a porty 9 a 7. Vráti počet úspešne odoslaných paketov. */
    public static int send(byte[] mac, List<InetAddress> targets, PcDiscovery.Binder binder) throws IOException {
        byte[] p = packet(mac);
        int sent = 0;
        DatagramSocket s = new DatagramSocket();
        try {
            s.setBroadcast(true);
            if (binder != null) binder.bind(s);
            for (InetAddress t : targets) {
                for (int port : new int[]{9, 7}) {
                    try {
                        s.send(new DatagramPacket(p, p.length, t, port));
                        sent++;
                    } catch (IOException ignored) {
                        // skúsiť ostatné adresy
                    }
                }
            }
        } finally {
            s.close();
        }
        return sent;
    }

    public static byte[] parseMac(String text) {
        String[] parts = text.trim().split("[:\\-]");
        if (parts.length != 6) return null;
        byte[] mac = new byte[6];
        try {
            for (int i = 0; i < 6; i++) mac[i] = (byte) Integer.parseInt(parts[i], 16);
        } catch (NumberFormatException e) {
            return null;
        }
        return mac;
    }

    public static String formatMac(byte[] mac) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", mac[i] & 0xff));
        }
        return sb.toString();
    }
}
