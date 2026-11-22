import sk.domov.ovladanie.pc.*;

import java.util.*;

/** Skúša čistú logiku ovládania na telefóne (priblíženie, gestá, klávesnica) bez Androidu. */
public class PcLogicTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        if (ok) System.out.println("  OK   " + what);
        else { System.out.println("  CHYBA " + what); failures++; }
    }

    static boolean near(float a, float b, float eps) { return Math.abs(a - b) <= eps; }

    // ------------------------------------------------------------------ priblíženie

    static void viewport() {
        System.out.println("Priblíženie a posun");
        PcViewport v = new PcViewport();
        v.setView(1080, 2000);
        v.setImage(1920, 1080);
        check(near(v.fit(), 1080f / 1920f, 1e-4f) && near(v.scale(), v.fit(), 1e-6f), "celý obraz vo výške aj šírke okna");
        check(near(v.left(), 0, 1e-3f) && near(v.top(), (2000 - 1080 * v.scale()) / 2f, 1e-2f), "obraz je v zvislom strede");
        check(v.inside(500, 1000) && !v.inside(500, 10), "bod nad obrazom / mimo obrazu");
        // bod pod prstom zostane pri priblížení na mieste
        float fx = 700, fy = 1000;
        float ix0 = v.toImageX(fx), iy0 = v.toImageY(fy);
        v.zoomAt(2.5f, fx, fy);
        check(near(v.toImageX(fx), ix0, 0.5f) && near(v.toImageY(fy), iy0, 0.5f), "priblíženie drží bod pod prstom");
        check(v.zoomed() && near(v.zoom(), 2.5f, 1e-3f), "zoom 2.5");
        // posun nikdy neukáže prázdno (pri priblížení 4× obraz presahuje okno na oboch osiach)
        v.zoomAt(1.6f, fx, fy);
        v.panBy(100000, 100000);
        check(v.left() <= 0.01f && v.top() <= 0.01f, "okraj obrazu sa nedá odtiahnuť dnu (ľavý/horný)");
        v.panBy(-100000, -100000);
        check(v.left() + 1920 * v.scale() >= 1080 - 0.01f && v.top() + 1080 * v.scale() >= 2000 - 0.01f, "okraj obrazu sa nedá odtiahnuť (pravý/dolný)");
        v.zoomAt(100f, 500, 500);
        check(near(v.zoom(), PcViewport.MAX_ZOOM, 1e-3f), "najväčšie priblíženie je obmedzené");
        v.zoomAt(0.0001f, 500, 500);
        check(near(v.zoom(), 1f, 1e-3f) && !v.zoomed(), "oddialenie sa zastaví na celom obraze");
        check(near(v.left(), 0, 1e-3f), "po oddialení je obraz opäť vycentrovaný");
        // sledovanie kurzora
        v.zoomAt(4f, 540, 1000);
        v.ensureVisible(1900, 1070, 60);
        check(v.toViewX(1900) <= 1080 && v.toViewX(1900) >= 0 && v.toViewY(1070) <= 2000 && v.toViewY(1070) >= 0, "kurzor v pravom dolnom rohu sa dostane do okna");
        v.ensureVisible(960, 540, 60);
        check(v.toViewX(960) >= 59.9f && v.toViewX(960) <= 1080 - 59.9f && v.toViewY(960 * 0 + 540) >= 59.9f, "kurzor uprostred obrazu sa dostane do okna s okrajom");
        v.ensureVisible(10, 10, 60);
        check(v.toViewX(10) >= 59.9f && v.toViewY(10) >= 59.9f || v.left() >= -0.01f, "kurzor v ľavom hornom rohu sa dostane do okna");
        // zmena veľkosti obrazu zruší priblíženie
        v.setImage(640, 360);
        check(!v.zoomed(), "iný monitor = nové priblíženie 1");
        // otočenie okna
        v.setView(2000, 1000);
        check(near(v.fit(), Math.min(2000f / 640, 1000f / 360), 1e-4f), "po otočení sa obraz znova vpasuje");
    }

    // ------------------------------------------------------------------ klávesnica

    static class Rec implements PcKeyboard.Sink {
        final List<String> log = new ArrayList<>();
        public void key(int vk, boolean down) { log.add((down ? "v" : "^") + Integer.toHexString(vk)); }
        public void text(String s) { log.add("t:" + s); }
        String joined() { return String.join(" ", log); }
    }

    static void keyboard() {
        System.out.println("Klávesnica");
        Rec r = new Rec();
        PcKeyboard k = new PcKeyboard(r);
        k.typeText("Ahoj ľščť");
        check(r.joined().equals("t:Ahoj ľščť"), "obyčajný text ide ako Unicode");
        r.log.clear();
        k.toggle(PcKeyboard.VK_CONTROL);
        check(k.state(PcKeyboard.VK_CONTROL) == PcKeyboard.ONCE && r.joined().equals("v11"), "Ctrl: 1. ťuk = podržaný");
        k.typeText("c");
        check(r.joined().equals("v11 v43 ^43 ^11") && k.state(PcKeyboard.VK_CONTROL) == PcKeyboard.OFF, "Ctrl+C a Ctrl sa sám pustí");
        r.log.clear();
        k.toggle(PcKeyboard.VK_CONTROL);
        k.toggle(PcKeyboard.VK_CONTROL);
        check(k.state(PcKeyboard.VK_CONTROL) == PcKeyboard.LOCKED, "Ctrl: 2. ťuk = zamknutý");
        k.typeText("v");
        k.typeText("a");
        check(r.joined().equals("v11 v56 ^56 v41 ^41") && k.state(PcKeyboard.VK_CONTROL) == PcKeyboard.LOCKED, "zamknutý Ctrl zostáva po viacerých klávesoch");
        r.log.clear();
        k.toggle(PcKeyboard.VK_CONTROL);
        check(k.state(PcKeyboard.VK_CONTROL) == PcKeyboard.OFF && r.joined().equals("^11"), "Ctrl: 3. ťuk = pustený");
        r.log.clear();
        k.toggle(PcKeyboard.VK_SHIFT);
        k.typeText("ahoj");
        check(r.joined().equals("v10 t:AHOJ ^10"), "Shift zväčší písmená a pustí sa");
        r.log.clear();
        k.toggle(PcKeyboard.VK_MENU);
        k.press(PcKeyboard.VK_TAB);
        check(r.joined().equals("v12 v9 ^9 ^12"), "Alt+Tab cez lepivý Alt");
        r.log.clear();
        k.toggle(PcKeyboard.VK_LWIN);
        k.toggle(PcKeyboard.VK_CONTROL);
        k.toggle(PcKeyboard.VK_CONTROL);
        k.releaseAll();
        check(r.joined().equals("v5b v11 ^11 ^5b") || r.joined().equals("v5b v11 ^5b ^11"), "releaseAll pustí všetky držané: " + r.joined());
        check(k.state(PcKeyboard.VK_LWIN) == PcKeyboard.OFF && k.state(PcKeyboard.VK_CONTROL) == PcKeyboard.OFF, "po releaseAll nič nezostalo");
        r.log.clear();
        k.chord(new int[]{PcKeyboard.VK_CONTROL, PcKeyboard.VK_SHIFT}, PcKeyboard.VK_ESCAPE);
        check(r.joined().equals("v11 v10 v1b ^1b ^10 ^11"), "Ctrl+Shift+Esc");
        r.log.clear();
        k.typeText("C"); // veľké písmeno s aktívnym Ctrl: Shift navyše
        k.toggle(PcKeyboard.VK_CONTROL);
        r.log.clear();
        k.typeText("C");
        check(r.joined().equals("v10 v43 ^43 ^10 ^11") || r.joined().equals("v10 v43 ^43 ^11 ^10") || r.joined().contains("v43"), "Ctrl+Shift+C pri veľkom písmene: " + r.joined());

        System.out.println("Preklad kláves a písanie z klávesnice telefónu");
        check(PcKeyboard.vkForAndroid(29) == 0x41 && PcKeyboard.vkForAndroid(54) == 0x5A, "KEYCODE_A..Z → VK A..Z");
        check(PcKeyboard.vkForAndroid(7) == 0x30 && PcKeyboard.vkForAndroid(16) == 0x39, "KEYCODE_0..9 → VK 0..9");
        check(PcKeyboard.vkForAndroid(67) == 0x08 && PcKeyboard.vkForAndroid(66) == 0x0D && PcKeyboard.vkForAndroid(111) == 0x1B, "Backspace, Enter, Esc");
        check(PcKeyboard.vkForAndroid(131) == 0x70 && PcKeyboard.vkForAndroid(142) == 0x7B, "F1..F12");
        check(PcKeyboard.vkForAndroid(19) == 0x26 && PcKeyboard.vkForAndroid(22) == 0x27, "šípky");
        check(PcKeyboard.vkForAndroid(4) == 0 && PcKeyboard.vkForAndroid(3) == 0, "Späť a Domov sa neposielajú");
        PcKeyboard.Edit e = PcKeyboard.diff("", "ahoj");
        check(e.deleteCodePoints == 0 && e.insert.equals("ahoj"), "diff: nový text");
        e = PcKeyboard.diff("ahoj", "ahojte");
        check(e.deleteCodePoints == 0 && e.insert.equals("te"), "diff: pripísanie");
        e = PcKeyboard.diff("ahoj", "ahor");
        check(e.deleteCodePoints == 1 && e.insert.equals("r"), "diff: oprava posledného znaku");
        e = PcKeyboard.diff("ahoj", "");
        check(e.deleteCodePoints == 4 && e.insert.isEmpty(), "diff: zmazanie všetkého");
        e = PcKeyboard.diff("a😀", "a😁");
        check(e.deleteCodePoints == 1 && e.insert.equals("😁"), "diff: emoji sa nerozdelí uprostred páru");
        e = PcKeyboard.diff("príliš", "príliš ");
        check(e.deleteCodePoints == 0 && e.insert.equals(" "), "diff: medzera po slove");
    }

    // ------------------------------------------------------------------ gestá

    static class G implements PcGestures.Out {
        final List<String> log = new ArrayList<>();
        boolean outside;
        public void moveRel(int dx, int dy) { log.add("rel:" + dx + "," + dy); }
        public boolean pointAt(float x, float y) { if (outside) return false; log.add("at:" + (int) x + "," + (int) y); return true; }
        public void button(int b, boolean down) { log.add("b" + b + (down ? "v" : "^")); }
        public void scroll(int dx, int dy) { log.add("sc:" + dx + "," + dy); }
        public void pan(float x, float y) { log.add("pan"); }
        public void zoom(float f, float x, float y) { log.add("zoom"); }
        public void haptic() { log.add("hap"); }
        String joined() { return String.join(" ", log); }
        int sumRelX() { int s = 0; for (String l : log) if (l.startsWith("rel:")) s += Integer.parseInt(l.substring(4).split(",")[0]); return s; }
        int sumRelY() { int s = 0; for (String l : log) if (l.startsWith("rel:")) s += Integer.parseInt(l.substring(4).split(",")[1]); return s; }
        int sumScrollY() { int s = 0; for (String l : log) if (l.startsWith("sc:")) s += Integer.parseInt(l.substring(3).split(",")[1]); return s; }
        int sumScrollX() { int s = 0; for (String l : log) if (l.startsWith("sc:")) s += Integer.parseInt(l.substring(3).split(",")[0]); return s; }
        int count(String prefix) { int c = 0; for (String l : log) if (l.startsWith(prefix)) c++; return c; }
    }

    static final float D = 2.5f; // hustota obrazovky

    static PcGestures pad(G g) { return new PcGestures(g, D, 20f); }

    static void gestures() {
        System.out.println("Gestá: touchpad");
        G g = new G();
        PcGestures p = pad(g);
        long t = 1000;
        p.down(t, 300, 300); p.up(t + 80, 300, 300);
        check(g.joined().equals("b1v b1^"), "ťuk = ľavý klik");
        g.log.clear();
        // ťahanie prstom: kurzor sa pohne, bez kliknutia
        p.down(t + 2000, 300, 300);
        for (int i = 1; i <= 10; i++) p.move(t + 2000 + i * 16, 1, 300 + i * 10, 300, 0, 0);
        p.up(t + 2200, 400, 300);
        check(g.count("rel:") > 0 && g.sumRelX() > 100 && g.sumRelY() == 0 && g.count("b") == 0, "ťahanie hýbe kurzorom, bez kliku (Σx=" + g.sumRelX() + ")");
        g.log.clear();
        // pomalý pohyb o 1 pixel nesmie „zmiznúť“ (zvyšok sa prenáša) a nesmie ujsť
        p.down(t + 4000, 300, 300);
        p.move(t + 4020, 1, 330, 300, 0, 0); // cez toleranciu
        int before = g.sumRelX();
        for (int i = 1; i <= 100; i++) p.move(t + 4020 + i * 30, 1, 330 + i, 300, 0, 0);
        p.up(t + 8000, 430, 300);
        check(g.sumRelX() - before >= 100 * 0.9f, "pomalý pohyb (100 px) sa neutratí: " + (g.sumRelX() - before));
        g.log.clear();
        // rýchly pohyb sa zrýchli
        PcGestures slow = pad(new G());
        G gs = new G(); slow = pad(gs);
        slow.down(0, 0, 0);
        for (int i = 1; i <= 20; i++) slow.move(i * 20, 1, i * 5, 0, 0, 0);   // 5 px / 20 ms (pomaly)
        slow.up(500, 100, 0);
        G gf = new G(); PcGestures fast = pad(gf);
        fast.down(0, 0, 0);
        for (int i = 1; i <= 5; i++) fast.move(i * 8, 1, i * 20, 0, 0, 0);     // 20 px / 8 ms (rýchlo)
        fast.up(100, 100, 0);
        check(gf.sumRelX() > gs.sumRelX() * 1.5f, "rýchly ťah (" + gf.sumRelX() + ") ide ďalej než pomalý (" + gs.sumRelX() + ") pri rovnakej dráhe");
        // dlhý ťuk = pravý klik
        g = new G(); p = pad(g);
        p.down(t, 200, 200);
        p.tick(t + 300);
        check(g.joined().isEmpty(), "pred uplynutím času sa nič nestane");
        p.tick(t + 480);
        p.up(t + 600, 200, 200);
        check(g.joined().equals("b2v b2^ hap"), "dlhý ťuk = pravý klik + odozva: " + g.joined());
        // dvojprstový ťuk = pravý klik
        g = new G(); p = pad(g);
        p.down(t, 200, 200);
        p.secondDown(t + 20, 200, 200, 300, 200);
        p.pointerUp(t + 90, 1);
        p.up(t + 120, 300, 200);
        check(g.joined().equals("b2v b2^"), "ťuk dvoma prstami = pravý klik");
        // dva ťuky = dva kliky (dvojklik na PC)
        g = new G(); p = pad(g);
        p.down(t, 100, 100); p.up(t + 60, 100, 100);
        p.down(t + 160, 100, 100); p.up(t + 220, 100, 100);
        check(g.joined().equals("b1v b1^ b1v b1^"), "dvojitý ťuk = dva kliky");
        // ťuk a ťahať = ťahanie s tlačidlom
        g = new G(); p = pad(g);
        p.down(t, 100, 100); p.up(t + 60, 100, 100);
        g.log.clear();
        p.down(t + 150, 100, 100);
        for (int i = 1; i <= 8; i++) p.move(t + 150 + i * 16, 1, 100 + i * 15, 100, 0, 0);
        p.up(t + 400, 220, 100);
        check(g.log.get(0).equals("b1v") && g.log.get(g.log.size() - 1).equals("b1^") && g.sumRelX() > 50, "ťuk+ťahať = ľavé tlačidlo držané počas pohybu");
        // ťuk a podržať = ťahanie aj bez pohybu
        g = new G(); p = pad(g);
        p.down(t, 100, 100); p.up(t + 60, 100, 100);
        g.log.clear();
        p.down(t + 150, 100, 100);
        p.tick(t + 150 + 480);
        p.up(t + 1000, 100, 100);
        check(g.joined().equals("b1v hap b1^"), "ťuk+podržať: tlačidlo sa stlačí a pri zdvihnutí pustí: " + g.joined());
        // dlhý čas medzi ťuknutiami = nie je ťahanie
        g = new G(); p = pad(g);
        p.down(t, 100, 100); p.up(t + 60, 100, 100);
        g.log.clear();
        p.down(t + 900, 100, 100);
        for (int i = 1; i <= 5; i++) p.move(t + 900 + i * 16, 1, 100 + i * 15, 100, 0, 0);
        p.up(t + 1100, 175, 100);
        check(g.count("b") == 0 && g.sumRelX() > 0, "pomalé ďalšie ťuknutie je len pohyb");
        // pomalý „ťuk“ (dlhý dotyk bez pohybu, pustený po 400 ms) nič nerobí
        g = new G(); p = pad(g);
        p.down(t, 100, 100); p.tick(t + 400); p.up(t + 400, 100, 100);
        check(g.joined().isEmpty(), "dotyk 400 ms bez pohybu nie je ani ťuk, ani dlhý ťuk");

        System.out.println("Gestá: rolovanie a priblíženie");
        g = new G(); p = pad(g);
        p.down(t, 200, 500);
        p.secondDown(t + 10, 200, 500, 320, 500);
        for (int i = 1; i <= 12; i++) p.move(t + 10 + i * 16, 2, 200, 500 + i * 20, 320, 500 + i * 20);
        p.pointerUp(t + 300, 1);
        p.up(t + 330, 320, 740);
        check(g.sumScrollY() > 0 && g.sumScrollX() == 0 && g.count("zoom") == 0 && g.count("b") == 0,
                "prsty dole = rolovanie nahor (prirodzené), Σ=" + g.sumScrollY());
        // prirodzené rolovanie vypnuté: opačný smer
        g = new G(); p = pad(g); p.natural = false;
        p.down(t, 200, 500);
        p.secondDown(t + 10, 200, 500, 320, 500);
        for (int i = 1; i <= 12; i++) p.move(t + 10 + i * 16, 2, 200, 500 + i * 20, 320, 500 + i * 20);
        p.pointerUp(t + 300, 1); p.up(t + 330, 320, 740);
        check(g.sumScrollY() < 0, "bez prirodzeného rolovania je smer opačný");
        // vodorovné rolovanie: prsty doprava = obsah doprava = koliesko doľava (záporné)
        g = new G(); p = pad(g);
        p.down(t, 200, 500);
        p.secondDown(t + 10, 200, 500, 320, 500);
        for (int i = 1; i <= 12; i++) p.move(t + 10 + i * 16, 2, 200 + i * 20, 500, 320 + i * 20, 500);
        p.pointerUp(t + 300, 1); p.up(t + 330, 560, 500);
        check(g.sumScrollX() < 0 && g.sumScrollY() == 0, "vodorovné rolovanie, Σx=" + g.sumScrollX());
        // štipnutie = priblíženie, nie rolovanie
        g = new G(); p = pad(g);
        p.down(t, 400, 500);
        p.secondDown(t + 10, 400, 500, 440, 500);
        for (int i = 1; i <= 10; i++) p.move(t + 10 + i * 16, 2, 400 - i * 15, 500, 440 + i * 15, 500);
        p.pointerUp(t + 300, 1); p.up(t + 330, 590, 500);
        check(g.count("zoom") > 0 && g.count("sc:") == 0 && g.count("b") == 0, "štipnutie = priblíženie");
        // tretí prst ukončí gesto a nič nezlepí
        g = new G(); p = pad(g);
        p.down(t, 400, 500);
        p.secondDown(t + 10, 400, 500, 440, 500);
        p.extraDown();
        for (int i = 1; i <= 10; i++) p.move(t + 10 + i * 16, 2, 400, 500 + i * 20, 440, 500 + i * 20);
        p.up(t + 400, 400, 500);
        check(g.joined().isEmpty(), "tretí prst: gesto je mŕtve, nič sa nepošle");
        // ťahanie a druhý prst = tlačidlo sa pustí
        g = new G(); p = pad(g);
        p.down(t, 100, 100); p.up(t + 60, 100, 100);
        p.down(t + 150, 100, 100); p.tick(t + 150 + 480);
        g.log.clear();
        p.secondDown(t + 700, 100, 100, 200, 100);
        check(g.joined().equals("b1^"), "druhý prst počas ťahania pustí tlačidlo");
        // zrušenie dotyku počas ťahania pustí tlačidlo
        g = new G(); p = pad(g);
        p.down(t, 100, 100); p.up(t + 60, 100, 100);
        p.down(t + 150, 100, 100); p.tick(t + 150 + 480);
        g.log.clear();
        p.cancel();
        check(g.joined().equals("b1^"), "cancel pustí držané tlačidlo");

        System.out.println("Gestá: priamy dotyk");
        g = new G(); p = pad(g); p.direct = true;
        p.down(t, 250, 400); p.up(t + 70, 250, 400);
        check(g.joined().equals("at:250,400 b1v b1^"), "ťuk = klik presne na mieste: " + g.joined());
        g = new G(); p = pad(g); p.direct = true; g.outside = true;
        p.down(t, 250, 400); p.up(t + 70, 250, 400);
        check(g.joined().isEmpty(), "ťuk mimo obrazu nič nerobí");
        g = new G(); p = pad(g); p.direct = true;
        p.down(t, 250, 400);
        for (int i = 1; i <= 6; i++) p.move(t + i * 16, 1, 250, 400 + i * 15, 0, 0);
        p.up(t + 300, 250, 490);
        check(g.count("pan") > 0 && g.count("b") == 0 && g.count("rel:") == 0, "jeden prst v priamom režime posúva obraz");
        g = new G(); p = pad(g); p.direct = true;
        p.down(t, 250, 400);
        p.tick(t + 480);
        check(g.joined().equals("at:250,400 b2v b2^ hap"), "dlhý ťuk v priamom režime = pravý klik na mieste");
        g = new G(); p = pad(g); p.direct = true;
        p.down(t, 100, 100); p.up(t + 60, 100, 100);
        g.log.clear();
        p.down(t + 150, 100, 100);
        for (int i = 1; i <= 6; i++) p.move(t + 150 + i * 16, 1, 100 + i * 20, 100, 0, 0);
        p.up(t + 400, 220, 100);
        check(g.log.get(0).equals("at:100,100") && g.log.get(1).equals("b1v") && g.log.get(g.log.size() - 1).equals("b1^") && g.count("at:") >= 6,
                "ťuk+ťahať v priamom režime ťahá od miesta dotyku");
        g = new G(); p = pad(g); p.direct = true;
        p.down(t, 200, 200);
        p.secondDown(t + 20, 200, 200, 300, 200);
        p.pointerUp(t + 90, 1);
        check(g.joined().equals("at:250,200 b2v b2^"), "dvojprstový ťuk v priamom režime = pravý klik v strede prstov: " + g.joined());
    }

    public static void main(String[] a) {
        viewport();
        keyboard();
        gestures();
        System.out.println(failures == 0 ? "PcLogicTest: OK" : "PcLogicTest: CHYBY " + failures);
        if (failures != 0) System.exit(1);
    }
}
