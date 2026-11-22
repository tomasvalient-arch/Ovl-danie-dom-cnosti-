package sk.domov.ovladanie.pc;

/**
 * Gestá prstov na obraze PC (čistý automat bez Androidu, preto sa dá otestovať syntetickými dotykmi).
 *
 * Touchpad: ťahanie jedným prstom hýbe kurzorom (s citlivosťou a zrýchlením), ťuk = ľavý klik, ťuk dvomi prstami
 * alebo dlhý ťuk = pravý klik, „ťuk a hneď podržať a ťahať“ = ťahanie s ľavým tlačidlom, dva prsty = rolovanie.
 * Priamy dotyk: ťuk = klik presne tam, kde je prst; jeden prst posúva priblížený obraz.
 * V oboch režimoch: štipnutie dvomi prstami = priblíženie.
 * Ďalší (tretí) prst gesto ukončí.
 */
public final class PcGestures {
    public static final int LEFT = 1, RIGHT = 2;

    public interface Out {
        /** Relatívny pohyb kurzora v pixeloch PC. */
        void moveRel(int dx, int dy);

        /** Priamy režim: presunie kurzor na bod okna; false, ak je bod mimo obrazu (nič sa nestalo). */
        boolean pointAt(float vx, float vy);

        void button(int button, boolean down);

        /** Koliesko v jednotkách Windows (120 = jeden zárez); kladné dy = hore, kladné dx = doprava. */
        void scroll(int dx, int dy);

        /** Posun obrazu o pixely okna. */
        void pan(float dvx, float dvy);

        /** Priblíženie o [factor] okolo bodu okna. */
        void zoom(float factor, float fvx, float fvy);

        void haptic();
    }

    private enum S { IDLE, ONE, DRAG_PENDING, MOVE, PAN, DRAG, LONG, TWO, SCROLL, PINCH, DEAD }

    // časy v ms
    static final long TAP_MAX = 350, DOUBLE_GAP = 300, LONG_PRESS = 450;
    // pohyb kurzora: pixelov PC na dp prsta pri pomalom pohybe, a rolovanie: jednotiek kolieska na dp
    static final float BASE_GAIN = 2.5f, SCROLL_UNITS_PER_DP = 5f;

    private final Out out;
    private final float density;
    private final float slop;

    /** true = priamy dotyk, false = touchpad. */
    public boolean direct;
    /** Násobok citlivosti myši (1 = základ). */
    public float sensitivity = 1f;
    /** Prirodzené rolovanie: obsah ide za prstami. */
    public boolean natural = true;

    private S s = S.IDLE;
    private long downTime;
    private float downX, downY;
    private float lastX, lastY;
    private long lastT;
    private long lastTapUpT = -1;
    private float lastTapX, lastTapY;
    private float remX, remY;
    private float speed;
    // dva prsty
    private long twoDownT;
    private float startSpan, lastSpan, startCx, startCy, lastCx, lastCy;
    private float totX, totY, pendX, pendY;
    private int axis; // 0 nerozhodnuté, 1 zvislo, 2 vodorovne

    public PcGestures(Out out, float density, float slopPx) {
        this.out = out;
        this.density = density > 0 ? density : 1f;
        this.slop = Math.max(slopPx, 4f);
    }

    private static float dist(float x0, float y0, float x1, float y1) {
        return (float) Math.hypot(x1 - x0, y1 - y0);
    }

    /** Prvý prst sa dotkol. */
    public void down(long t, float x, float y) {
        if (s != S.IDLE) cancel();
        downTime = t;
        downX = lastX = x;
        downY = lastY = y;
        lastT = t;
        remX = remY = 0;
        speed = 0;
        boolean again = lastTapUpT >= 0 && t - lastTapUpT <= DOUBLE_GAP
                && (!direct || dist(x, y, lastTapX, lastTapY) <= 48f * density);
        s = again ? S.DRAG_PENDING : S.ONE;
    }

    /** K prvému prstu pristal druhý. */
    public void secondDown(long t, float x0, float y0, float x1, float y1) {
        switch (s) {
            case ONE: case MOVE: case PAN: case DRAG_PENDING:
                s = S.TWO;
                twoDownT = t;
                startSpan = lastSpan = dist(x0, y0, x1, y1);
                startCx = lastCx = (x0 + x1) / 2f;
                startCy = lastCy = (y0 + y1) / 2f;
                totX = totY = pendX = pendY = 0;
                axis = 0;
                break;
            case DRAG:
                out.button(LEFT, false);
                s = S.DEAD;
                break;
            default:
                break; // LONG, TWO, SCROLL, PINCH, DEAD: nič
        }
    }

    /** Tretí a ďalší prst: gesto sa ukončí. */
    public void extraDown() {
        if (s == S.DRAG) out.button(LEFT, false);
        if (s != S.IDLE) s = S.DEAD;
    }

    /** Pohyb; [n] je počet prstov (1 alebo 2), x1/y1 sa pri jednom prste nepoužijú. */
    public void move(long t, int n, float x0, float y0, float x1, float y1) {
        if (n >= 2) {
            if (s == S.TWO || s == S.SCROLL || s == S.PINCH) twoMove(x0, y0, x1, y1);
            return;
        }
        switch (s) {
            case ONE:
                if (dist(x0, y0, downX, downY) <= slop) return;
                s = direct ? S.PAN : S.MOVE;
                lastX = downX;
                lastY = downY;
                lastT = downTime;
                oneMove(t, x0, y0);
                break;
            case DRAG_PENDING:
                if (dist(x0, y0, downX, downY) <= slop) return;
                startDrag(t, x0, y0);
                break;
            case MOVE: case PAN:
                oneMove(t, x0, y0);
                break;
            case DRAG:
                if (direct) out.pointAt(x0, y0);
                else oneMove(t, x0, y0);
                break;
            default:
                break;
        }
    }

    private void startDrag(long t, float x, float y) {
        if (direct && !out.pointAt(downX, downY)) {
            s = S.DEAD;
            return;
        }
        out.button(LEFT, true);
        s = S.DRAG;
        lastX = downX;
        lastY = downY;
        lastT = downTime;
        if (direct) out.pointAt(x, y);
        else oneMove(t, x, y);
    }

    private void oneMove(long t, float x, float y) {
        float dx = x - lastX, dy = y - lastY;
        long dt = Math.max(1, t - lastT);
        lastX = x;
        lastY = y;
        lastT = t;
        if (direct) {
            out.pan(dx, dy);
            return;
        }
        float dp = dist(0, 0, dx, dy) / density;
        float v = dp / Math.max(dt, 4);
        speed = 0.5f * speed + 0.5f * v;
        float mult = 1f + Math.max(0f, Math.min(3f, (speed - 0.15f) * 3f));
        float k = BASE_GAIN * sensitivity * mult / density;
        remX += dx * k;
        remY += dy * k;
        int ix = (int) remX, iy = (int) remY; // zaokrúhlenie k nule, zvyšok sa prenáša
        remX -= ix;
        remY -= iy;
        if (ix != 0 || iy != 0) out.moveRel(ix, iy);
    }

    private void twoMove(float x0, float y0, float x1, float y1) {
        float span = dist(x0, y0, x1, y1);
        float cx = (x0 + x1) / 2f, cy = (y0 + y1) / 2f;
        if (s == S.TWO) {
            float ds = Math.abs(span - startSpan), dc = dist(cx, cy, startCx, startCy);
            float th = slop * 1.5f;
            if (ds <= th && dc <= th) return;
            s = ds > dc ? S.PINCH : S.SCROLL;
            // začíname od miesta, kde sa gesto začalo, aby sa nestratila vzdialenosť potrebná na rozpoznanie
            lastSpan = startSpan;
            lastCx = startCx;
            lastCy = startCy;
        }
        if (s == S.PINCH) {
            if (lastSpan > 1f && span > 1f) out.zoom(span / lastSpan, cx, cy);
            out.pan(cx - lastCx, cy - lastCy);
        } else {
            scrollBy(cx - lastCx, cy - lastCy);
        }
        lastSpan = span;
        lastCx = cx;
        lastCy = cy;
    }

    private void scrollBy(float dx, float dy) {
        float sign = natural ? 1f : -1f;
        totX += dx;
        totY += dy;
        pendY += dy / density * SCROLL_UNITS_PER_DP * sign;
        pendX += -dx / density * SCROLL_UNITS_PER_DP * sign;
        if (axis == 0) {
            if (Math.max(Math.abs(totX), Math.abs(totY)) < slop) return;
            axis = Math.abs(totY) >= Math.abs(totX) ? 1 : 2;
        }
        if (axis == 1) {
            pendX = 0;
            int w = (int) pendY;
            if (Math.abs(w) >= 10) {
                out.scroll(0, w);
                pendY -= w;
            }
        } else {
            pendY = 0;
            int w = (int) pendX;
            if (Math.abs(w) >= 10) {
                out.scroll(w, 0);
                pendX -= w;
            }
        }
    }

    /** Zdvihol sa jeden z dvoch prstov ([remaining] prstov ešte drží). */
    public void pointerUp(long t, int remaining) {
        if (s == S.TWO) {
            // dvojprstový ťuk = pravý klik (tam, kde bol stred prstov)
            if (t - twoDownT <= TAP_MAX) rightClick(startCx, startCy);
            s = S.DEAD;
        } else if (s == S.SCROLL || s == S.PINCH) {
            s = S.DEAD;
        }
    }

    private void rightClick(float vx, float vy) {
        if (direct && !out.pointAt(vx, vy)) return;
        out.button(RIGHT, true);
        out.button(RIGHT, false);
    }

    /** Posledný prst sa zdvihol. */
    public void up(long t, float x, float y) {
        switch (s) {
            case ONE: case DRAG_PENDING:
                if (t - downTime <= TAP_MAX) tap(t, x, y);
                break;
            case DRAG:
                out.button(LEFT, false);
                lastTapUpT = -1;
                break;
            default:
                break;
        }
        s = S.IDLE;
        remX = remY = 0;
    }

    private void tap(long t, float x, float y) {
        lastTapUpT = t;
        lastTapX = x;
        lastTapY = y;
        if (direct && !out.pointAt(x, y)) return;
        out.button(LEFT, true);
        out.button(LEFT, false);
    }

    /** Dotyk sa prerušil (systém ho zrušil). */
    public void cancel() {
        if (s == S.DRAG) out.button(LEFT, false);
        s = S.IDLE;
        lastTapUpT = -1;
        remX = remY = 0;
    }

    /** Volá sa po [LONG_PRESS] ms od dotyku: dlhé podržanie bez pohybu. */
    public void tick(long t) {
        if (s == S.ONE && t - downTime >= LONG_PRESS) {
            s = S.LONG;
            if (direct && !out.pointAt(downX, downY)) return;
            out.button(RIGHT, true);
            out.button(RIGHT, false);
            out.haptic();
        } else if (s == S.DRAG_PENDING && t - downTime >= LONG_PRESS) {
            // „ťuk a podržať“: ťahanie sa začne hneď, aj bez pohybu (napr. chytiť okno za záhlavie)
            if (direct && !out.pointAt(downX, downY)) {
                s = S.DEAD;
                return;
            }
            out.button(LEFT, true);
            s = S.DRAG;
            out.haptic();
        }
    }

    /** Čas, po ktorom treba zavolať [tick]. */
    public static long tickDelayMs() { return LONG_PRESS + 20; }
}
