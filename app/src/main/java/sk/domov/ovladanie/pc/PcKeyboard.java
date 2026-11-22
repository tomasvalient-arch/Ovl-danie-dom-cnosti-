package sk.domov.ovladanie.pc;

/**
 * Klávesnica: lepivé modifikátory (Shift, Ctrl, Alt, Win), písanie textu a prevod kláves Androidu na kódy Windows.
 * Čistá logika bez Androidu; skutočné odosielanie robí [Sink].
 *
 * Lepivý modifikátor: 1. ťuk = platí pre najbližšiu klávesu (potom sa sám pustí), 2. ťuk = zamknutý, 3. ťuk = vypnutý.
 */
public final class PcKeyboard {
    public interface Sink {
        void key(int vk, boolean down);

        void text(String s);
    }

    public interface Listener {
        void modifiersChanged();
    }

    public static final int VK_BACK = 0x08, VK_TAB = 0x09, VK_RETURN = 0x0D, VK_SHIFT = 0x10, VK_CONTROL = 0x11,
            VK_MENU = 0x12, VK_ESCAPE = 0x1B, VK_SPACE = 0x20, VK_PRIOR = 0x21, VK_NEXT = 0x22, VK_END = 0x23,
            VK_HOME = 0x24, VK_LEFT = 0x25, VK_UP = 0x26, VK_RIGHT = 0x27, VK_DOWN = 0x28, VK_INSERT = 0x2D,
            VK_DELETE = 0x2E, VK_LWIN = 0x5B, VK_F1 = 0x70, VK_C = 0x43, VK_V = 0x56, VK_D = 0x44;

    public static final int OFF = 0, ONCE = 1, LOCKED = 2;

    private static final int[] MODS = {VK_SHIFT, VK_CONTROL, VK_MENU, VK_LWIN};

    private final Sink sink;
    private final int[] state = new int[MODS.length];
    private Listener listener;

    public PcKeyboard(Sink sink) { this.sink = sink; }

    public void setListener(Listener l) { listener = l; }

    private static int idx(int vk) {
        for (int i = 0; i < MODS.length; i++) if (MODS[i] == vk) return i;
        return -1;
    }

    public static boolean isModifier(int vk) { return idx(vk) >= 0; }

    /** OFF, ONCE alebo LOCKED pre modifikátor [vk] (VK_SHIFT, VK_CONTROL, VK_MENU, VK_LWIN). */
    public synchronized int state(int vk) {
        int i = idx(vk);
        return i < 0 ? OFF : state[i];
    }

    public void toggle(int vk) {
        int i = idx(vk);
        if (i < 0) return;
        synchronized (this) {
            if (state[i] == OFF) {
                state[i] = ONCE;
                sink.key(vk, true);
            } else if (state[i] == ONCE) {
                state[i] = LOCKED;
            } else {
                state[i] = OFF;
                sink.key(vk, false);
            }
        }
        changed();
    }

    private void changed() {
        Listener l = listener;
        if (l != null) l.modifiersChanged();
    }

    private synchronized boolean commandActive() {
        return state[1] != OFF || state[2] != OFF || state[3] != OFF;
    }

    private void releaseOneShot() {
        boolean any = false;
        synchronized (this) {
            for (int i = state.length - 1; i >= 0; i--) {
                if (state[i] == ONCE) {
                    state[i] = OFF;
                    sink.key(MODS[i], false);
                    any = true;
                }
            }
        }
        if (any) changed();
    }

    /** Stlačí a pustí klávesu; jednorazové modifikátory sa potom pustia. */
    public void press(int vk) {
        if (vk == 0) return;
        sink.key(vk, true);
        sink.key(vk, false);
        releaseOneShot();
    }

    /** Skratka: podrží modifikátory [mods], stlačí [vk] a modifikátory pustí (nezávisle od lepivých). */
    public void chord(int[] mods, int vk) {
        for (int m : mods) sink.key(m, true);
        sink.key(vk, true);
        sink.key(vk, false);
        for (int i = mods.length - 1; i >= 0; i--) sink.key(mods[i], false);
    }

    /**
     * Napíše text. Ak je aktívny Ctrl/Alt/Win a text je jeden znak (písmeno, číslica, medzera), pošle sa ako
     * klávesa (aby fungovali skratky ako Ctrl+C); inak sa pošle ako Unicode text. Shift zväčší písmená.
     */
    public void typeText(String s) {
        if (s == null || s.isEmpty()) return;
        boolean shift;
        synchronized (this) { shift = state[0] != OFF; }
        if (commandActive() && s.codePointCount(0, s.length()) == 1) {
            char c = s.charAt(0);
            int vk = vkForChar(c);
            if (vk != 0) {
                boolean needShift = Character.isUpperCase(c) && !shift;
                if (needShift) sink.key(VK_SHIFT, true);
                press(vk);
                if (needShift) sink.key(VK_SHIFT, false);
                return;
            }
        }
        sink.text(shift ? s.toUpperCase(java.util.Locale.ROOT) : s);
        releaseOneShot();
    }

    /** Pustí všetky držané modifikátory (pri odpojení a pri zatvorení ovládania). */
    public void releaseAll() {
        boolean any = false;
        synchronized (this) {
            for (int i = 0; i < state.length; i++) {
                if (state[i] != OFF) {
                    state[i] = OFF;
                    sink.key(MODS[i], false);
                    any = true;
                }
            }
        }
        if (any) changed();
    }

    /** Po novom spojení PC o držaných klávesoch nevie: lepivé stavy sa len vynulujú, bez posielania. */
    public void resetWithoutSending() {
        boolean any = false;
        synchronized (this) {
            for (int i = 0; i < state.length; i++) {
                if (state[i] != OFF) {
                    state[i] = OFF;
                    any = true;
                }
            }
        }
        if (any) changed();
    }

    // ------------------------------------------------------------------ prevody

    /** Kód Windows pre písmeno/číslicu/medzeru, inak 0. */
    public static int vkForChar(char c) {
        if (c >= 'a' && c <= 'z') return 0x41 + (c - 'a');
        if (c >= 'A' && c <= 'Z') return 0x41 + (c - 'A');
        if (c >= '0' && c <= '9') return 0x30 + (c - '0');
        if (c == ' ') return VK_SPACE;
        return 0;
    }

    /**
     * Kód Windows pre kód klávesy Androidu (KeyEvent.KEYCODE_*), alebo 0, ak ju nepoznáme.
     * Čísla sú stabilné časti API Androidu; sú tu ako čísla, aby sa logika dala skúšať aj bez Androidu.
     */
    public static int vkForAndroid(int k) {
        if (k >= 29 && k <= 54) return 0x41 + (k - 29);   // A–Z
        if (k >= 7 && k <= 16) return 0x30 + (k - 7);     // 0–9
        if (k >= 131 && k <= 142) return VK_F1 + (k - 131); // F1–F12
        switch (k) {
            case 67: return VK_BACK;          // DEL (backspace)
            case 112: return VK_DELETE;       // FORWARD_DEL
            case 66: case 160: return VK_RETURN;
            case 61: return VK_TAB;
            case 111: return VK_ESCAPE;
            case 62: return VK_SPACE;
            case 19: return VK_UP;
            case 20: return VK_DOWN;
            case 21: return VK_LEFT;
            case 22: return VK_RIGHT;
            case 122: return VK_HOME;
            case 123: return VK_END;
            case 92: return VK_PRIOR;
            case 93: return VK_NEXT;
            case 124: return VK_INSERT;
            case 59: case 60: return VK_SHIFT;
            case 113: case 114: return VK_CONTROL;
            case 57: case 58: return VK_MENU;
            case 117: case 118: return VK_LWIN;
            case 115: return 0x14;            // CAPS_LOCK
            case 120: return 0x2C;            // SYSRQ = Print Screen
            case 55: return 0xBC;             // ,
            case 56: return 0xBE;             // .
            case 69: return 0xBD;             // -
            case 70: return 0xBB;             // =
            case 68: return 0xC0;             // `
            case 71: return 0xDB;             // [
            case 72: return 0xDD;             // ]
            case 73: return 0xDC;             // \
            case 74: return 0xBA;             // ;
            case 75: return 0xDE;             // '
            case 76: return 0xBF;             // /
            default: return 0;
        }
    }

    /** Klávesy, ktoré sa posielajú ako klávesy (nie ako text) aj bez modifikátorov. */
    public static boolean isFunctional(int vk) {
        switch (vk) {
            case VK_BACK: case VK_DELETE: case VK_RETURN: case VK_TAB: case VK_ESCAPE:
            case VK_UP: case VK_DOWN: case VK_LEFT: case VK_RIGHT: case VK_HOME: case VK_END:
            case VK_PRIOR: case VK_NEXT: case VK_INSERT: case 0x14: case 0x2C:
                return true;
            default:
                return vk >= VK_F1 && vk < VK_F1 + 12;
        }
    }

    // ------------------------------------------------------------------ text počas písania (IME)

    /** Čo treba spraviť, aby sa text [from] na PC zmenil na [to]: zmazať [deleteCodePoints] znakov a napísať [insert]. */
    public static final class Edit {
        public final int deleteCodePoints;
        public final String insert;

        Edit(int d, String i) { deleteCodePoints = d; insert = i; }
    }

    public static Edit diff(String from, String to) {
        int a = 0;
        int max = Math.min(from.length(), to.length());
        while (a < max && from.charAt(a) == to.charAt(a)) a++;
        // nerozdeľovať pár náhrad (emoji) uprostred
        if (a > 0 && a < from.length() && Character.isHighSurrogate(from.charAt(a - 1))) a--;
        if (a > 0 && a < to.length() && Character.isHighSurrogate(to.charAt(a - 1))) a--;
        String removed = from.substring(a);
        return new Edit(removed.codePointCount(0, removed.length()), to.substring(a));
    }
}
