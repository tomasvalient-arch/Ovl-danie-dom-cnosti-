package sk.domov.ovladanie.pc;

/** Chyba pripojenia alebo protokolu. [code] je kód chyby z PC programu (PcProto.ERR_*), 0 pri sieťovej chybe. */
public class PcException extends Exception {
    public final int code;

    public PcException(int code, String message) {
        super(message);
        this.code = code;
    }

    public PcException(String message, Throwable cause) {
        super(message, cause);
        this.code = 0;
    }
}
