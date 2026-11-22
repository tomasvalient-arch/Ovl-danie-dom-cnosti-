package sk.domov.ovladanie.pc;

/** Čísla správ a kódy protokolu Domov PC (pozri pc/PROTOCOL.md). */
public final class PcProto {
    private PcProto() {}

    public static final int VERSION = 1;
    public static final int DEFAULT_PORT = 47810;

    // handshake
    public static final int H_CLIENT_HELLO = 0x01;
    public static final int H_SERVER_HELLO = 0x02;
    public static final int H_CLIENT_PROOF = 0x03;
    public static final int H_ERROR = 0x7F;

    // kódy chýb
    public static final int ERR_UNKNOWN_DEVICE = 1;
    public static final int ERR_LOCKED_OUT = 2;
    public static final int ERR_NO_PAIRING = 3;
    public static final int ERR_BAD_PROOF = 4;
    public static final int ERR_PROTOCOL = 5;
    public static final int ERR_BUSY = 6;
    /** Len v klientovi: na adrese odpovedal iný PC, než sa čakalo. */
    public static final int ERR_WRONG_PC = 100;

    // server → klient
    public static final int M_SESSION_INFO = 0x10;
    public static final int M_PAIRED = 0x11;
    public static final int M_SCREEN = 0x12;
    public static final int M_RECTS = 0x13;
    public static final int M_CLIP = 0x15;
    public static final int M_NOTICE = 0x16;
    public static final int M_PONG = 0x17;
    public static final int M_CURSOR = 0x18;

    // klient → server
    public static final int M_MOVE_REL = 0x20;
    public static final int M_MOVE_ABS = 0x21;
    public static final int M_BUTTON = 0x22;
    public static final int M_WHEEL = 0x23;
    public static final int M_KEY = 0x24;
    public static final int M_TEXT = 0x25;
    public static final int M_ACK = 0x26;
    public static final int M_CONFIG = 0x27;
    public static final int M_REFRESH = 0x28;
    public static final int M_CLIP_SET = 0x29;
    public static final int M_CLIP_GET = 0x2A;
    public static final int M_POWER = 0x2B;
    public static final int M_PING = 0x2C;
    public static final int M_RELEASE_ALL = 0x2D;

    // upozornenia
    public static final int NOTICE_DESKTOP_LOCKED = 1;
    public static final int NOTICE_DESKTOP_OK = 2;
    public static final int NOTICE_CAPTURE_ERROR = 3;
    public static final int NOTICE_INFO = 4;
    public static final int NOTICE_POWER_ACCEPTED = 5;
    public static final int NOTICE_CLIPBOARD_ERROR = 6;
    /** Tento telefón vytlačil iný: appka sa nesmie sama pripájať späť (inak by sa telefóny donekonečna vytláčali). */
    public static final int NOTICE_REPLACED = 7;

    // napájanie
    public static final int POWER_LOCK = 1;
    public static final int POWER_SLEEP = 2;
    public static final int POWER_RESTART = 3;
    public static final int POWER_SHUTDOWN = 4;

    // príznaky v SESSION_INFO
    public static final int FLAG_CAN_POWER = 1;
    public static final int FLAG_ELEVATED = 2;

    public static final int MAX_NAME = 48;

    /** Zrozumiteľný text chyby z handshaku. */
    public static String errorText(int code) {
        switch (code) {
            case ERR_UNKNOWN_DEVICE:
                return "PC tento telefón nepozná. Spáruj ho znova (PC ho mohol odstrániť).";
            case ERR_LOCKED_OUT:
                return "PC dočasne blokuje pripojenia pre príliš veľa nesprávnych pokusov. Skús o pár minút.";
            case ERR_NO_PAIRING:
                return "Párovanie nie je na PC zapnuté. Na PC klikni „Spárovať nový telefón“.";
            case ERR_BAD_PROOF:
                return "Nesprávny kód alebo kľúč.";
            case ERR_PROTOCOL:
                return "PC program má inú verziu protokolu. Aktualizuj appku aj PC program.";
            case ERR_BUSY:
                return "PC je obsadený.";
            default:
                return "PC odmietol pripojenie (" + code + ").";
        }
    }
}
