package sk.domov.ovladanie.pc;

import java.util.ArrayList;
import java.util.List;

/** Obsah QR kódu z PC: domovpc:1:&lt;kód&gt;:&lt;ID PC hex&gt;:&lt;IP,IP&gt;:&lt;port&gt; */
public final class PcLink {
    public final String code;
    public final String serverId;
    public final List<String> hosts;
    public final int port;

    private PcLink(String code, String serverId, List<String> hosts, int port) {
        this.code = code; this.serverId = serverId; this.hosts = hosts; this.port = port;
    }

    public static PcLink parse(String text) {
        if (text == null) return null;
        String[] p = text.trim().split(":", -1);
        if (p.length != 6 || !p[0].equals("domovpc") || !p[1].equals("1")) return null;
        String code = PcCrypto.normalizeCode(p[2]);
        if (code.isEmpty() || !p[3].matches("[0-9a-fA-F]{32}")) return null;
        List<String> hosts = new ArrayList<>();
        for (String h : p[4].split(",")) {
            if (h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) hosts.add(h);
        }
        int port;
        try {
            port = Integer.parseInt(p[5]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (port < 1 || port > 65535) return null;
        return new PcLink(code, p[3].toLowerCase(java.util.Locale.ROOT), hosts, port);
    }
}
