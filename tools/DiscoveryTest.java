import java.util.Arrays;
import sk.domov.ovladanie.tuya.TuyaDiscovery;

/**
 * Test dekódovania UDP broadcastov Tuya zariadení (bez Androidu).
 * Vzorové správy boli vytvorené knižnicou tinytuya 1.21 (pack_message + udpkey), teda nezávisle od appky.
 *
 *   javac -d out app/src/main/java/sk/domov/ovladanie/tuya/TuyaDiscovery.java tools/DiscoveryTest.java
 *   java -cp out DiscoveryTest
 */
public class DiscoveryTest {
    static void check(String name, boolean cond) {
        System.out.println((cond ? "OK   " : "FAIL ") + name);
        if (!cond) throw new RuntimeException("FAIL: " + name);
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    static TuyaDiscovery.Found parse(byte[] p) {
        // ako z DatagramPacket: buffer je väčší ako správa
        byte[] buf = Arrays.copyOf(p, 4096);
        return TuyaDiscovery.parse(buf, p.length, "10.0.0.99");
    }

    public static void main(String[] args) throws Exception {
        // tinytuya: 3.3 zariadenie, port 6667, rámec 55AA + AES-ECB
        TuyaDiscovery.Found f = parse(hex("000055aa00000000000000130000009c00000000d09766676f3369eb10b5e9f132fd802a70172896956a9e330910344d11ac363f482611d8caa5248ac6cea65bdad007dfa41f010dca2abb30c50459a9f88024ffcb5924bb8792fe5eae7c9040e5921a98017f2170860421e6fc66f0a1ade138915fde850cd157270c5f3d1ab5ae90b300eabc77d2635fdf6c1afeaf031be1c97f6eda8eea40e93b1e3fc14a2570e18279ff7f40540000aa55"));
        check("3.3 (6667) dekódované", f != null);
        check("3.3 devId", f.devId.equals("bf0123456789abcdefgh"));
        check("3.3 ip", f.ip.equals("192.168.1.50"));
        check("3.3 verzia", f.version.equals("3.3"));
        check("3.3 productKey", f.productKey.equals("keyabc123"));

        // tinytuya: 3.1 zariadenie, port 6666, nešifrované
        f = parse(hex("000055aa000000000000001200000097000000007b226970223a223139322e3136382e312e3532222c2267774964223a223031323030313535363066326632613162326333222c22616374697665223a322c226162696c697479223a302c226d6f6465223a302c22656e6372797074223a66616c73652c2270726f647563744b6579223a226b6579706c61696e222c2276657273696f6e223a22332e31227dd47b2abb0000aa55"));
        check("3.1 (6666) dekódované", f != null && f.devId.equals("0120015560f2f2a1b2c3") && f.version.equals("3.1"));

        // tinytuya: 3.5 zariadenie, rámec 6699 + AES-GCM, s retcode
        f = parse(hex("0000669900000000000000000013000000c6b648383bf165be70a8b09b42b27a931860eecdce8157ae64bb78f1e6295609681eac8f935b339f34ba0074c3214f8c277c0f155cfed58302e658cd81dd4a39594786a399512659a92e6eac612af317dccf6f2ec63100b7041e448604c26c53a030ea1632d4fc3662442b57b1b5bec7f598f6eda0ca0c17794f37bdcd1d34b8064af7ad1d7ab70b6632fa871501492e51c4db01d706f44d3683b1372d11af9a933c81b41e90d077974fc3bd77ff97d69e14cb0e485cc78b3ab7ce8fa875e059b135fe56d621d600009966"));
        check("3.5 (6699) dekódované", f != null);
        check("3.5 devId/ip/verzia", f.devId.equals("bf9876543210zyxwvuts") && f.ip.equals("192.168.1.51") && f.version.equals("3.5"));

        // tinytuya: 3.5 bez retcode a s nulami na konci
        f = parse(hex("0000669900000000000000000013000000c4d5445e6c74e2f91083acbf7d416ddc945ffd482a0f0fd32f0d4571c2e225d91cca9516664066b519d0c1b92549cddeae93fbf421b6da207713592d58226e7fa8f0b0317f791cc43247d4638aeabee337b9288b3b3f8f7a4da54ec776bf7d06821a19dca3d4b29ac84a325b37f4dc8b0bf2d2d9d696fa9af75d6d2361dd7bb1ca0ce12ba99472ef64db439a3c5a99b9adf0ee50b804f504be290fc5832d100dc9aee09537387ec263c3a7e14b95e7bb361cb6867ceef4d64804c518d0a2837110b054890e00009966"));
        check("3.5 bez retcode", f != null && f.devId.equals("bf9876543210zyxwvuts") && f.productKey.equals("keyxyz789"));

        // poškodená 3.5 správa (zmenený bajt) -> GCM tag nesedí -> null
        byte[] bad = hex("0000669900000000000000000013000000c6b648383bf165be70a8b09b42b27a931860eecdce8157ae64bb78f1e6295609681eac8f935b339f34ba0074c3214f8c277c0f155cfed58302e658cd81dd4a39594786a399512659a92e6eac612af317dccf6f2ec63100b7041e448604c26c53a030ea1632d4fc3662442b57b1b5bec7f598f6eda0ca0c17794f37bdcd1d34b8064af7ad1d7ab70b6632fa871501492e51c4db01d706f44d3683b1372d11af9a933c81b41e90d077974fc3bd77ff97d69e14cb0e485cc78b3ab7ce8fa875e059b135fe56d621d600009966");
        bad[40] ^= 1;
        check("poškodená 3.5 = null", parse(bad) == null);

        // vlastné kódovanie (spätne)
        String js = "{\"ip\":\"\",\"gwId\":\"abc123\",\"version\":\"3.4\"}";
        f = parse(TuyaDiscovery.encode55AA(js, true));
        check("vlastné 55AA šifrované, IP z odosielateľa", f != null && f.devId.equals("abc123") && f.ip.equals("10.0.0.99") && f.version.equals("3.4"));
        f = parse(TuyaDiscovery.encode55AA(js, false));
        check("vlastné 55AA nešifrované", f != null && f.devId.equals("abc123"));
        f = parse(TuyaDiscovery.encode6699(js, new byte[12]));
        check("vlastné 6699", f != null && f.devId.equals("abc123"));

        // naša požiadavka na port 7000 sa nesmie ukázať ako zariadenie
        check("požiadavka appky ignorovaná", parse(TuyaDiscovery.discoveryRequest("192.168.1.10")) == null);
        check("požiadavka appky sa dá dešifrovať",
                TuyaDiscovery.decode(TuyaDiscovery.discoveryRequest("192.168.1.10")).equals("{\"from\":\"app\",\"ip\":\"192.168.1.10\"}"));

        // nezmysly
        check("prázdne = null", TuyaDiscovery.parse(new byte[0], 0, null) == null);
        check("náhodné bajty = null", parse(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}) == null);
        check("JSON bez gwId = null", parse(TuyaDiscovery.encode55AA("{\"ip\":\"1.2.3.4\"}", true)) == null);

        if (args.length > 0 && args[0].equals("--request")) {
            StringBuilder sb = new StringBuilder();
            for (byte b : TuyaDiscovery.discoveryRequest("192.168.1.10")) sb.append(String.format("%02x", b));
            System.out.println("REQ=" + sb);
        }
        System.out.println("Všetky testy prešli.");
    }
}
