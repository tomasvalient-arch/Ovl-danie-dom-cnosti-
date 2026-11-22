package sk.domov.ovladanie.pc;

/**
 * Priblíženie a posun obrazu PC v okne telefónu (čistá matematika bez Androidu, preto sa dá otestovať).
 * Základ je „celý obraz vo vnútri okna“ (zoom 1); priblížiť sa dá až na [MAX_ZOOM]-násobok.
 * Súradnice okna sú v pixeloch okna, súradnice obrazu v pixeloch obrazu (ako ich pošle PC).
 */
public final class PcViewport {
    public static final float MAX_ZOOM = 8f;

    private float vw, vh, iw, ih;
    private float zoom = 1f;
    private float cx, cy; // bod obrazu, ktorý je v strede okna

    public void setView(float w, float h) {
        vw = w;
        vh = h;
        clampPan();
    }

    /** Pri zmene veľkosti obrazu (iný monitor, iné rozlíšenie) sa priblíženie zruší. */
    public void setImage(float w, float h) {
        boolean changed = w != iw || h != ih;
        iw = w;
        ih = h;
        if (changed) resetZoom();
        else clampPan();
    }

    public void resetZoom() {
        zoom = 1f;
        cx = iw / 2f;
        cy = ih / 2f;
    }

    public boolean ready() { return vw > 0 && vh > 0 && iw > 0 && ih > 0; }

    public float imageWidth() { return iw; }

    public float imageHeight() { return ih; }

    public float fit() { return ready() ? Math.min(vw / iw, vh / ih) : 1f; }

    public float scale() { return fit() * zoom; }

    public float zoom() { return zoom; }

    public boolean zoomed() { return zoom > 1.02f; }

    /** Ľavý okraj obrazu v súradniciach okna. */
    public float left() { return vw / 2f - cx * scale(); }

    public float top() { return vh / 2f - cy * scale(); }

    public float toImageX(float vx) { return (vx - left()) / scale(); }

    public float toImageY(float vy) { return (vy - top()) / scale(); }

    public float toViewX(float ix) { return left() + ix * scale(); }

    public float toViewY(float iy) { return top() + iy * scale(); }

    /** Je bod okna nad obrazom? */
    public boolean inside(float vx, float vy) {
        if (!ready()) return false;
        float x = toImageX(vx), y = toImageY(vy);
        return x >= 0 && y >= 0 && x < iw && y < ih;
    }

    /** Priblíži o [factor] tak, aby bod obrazu pod [fvx],[fvy] zostal na mieste. */
    public void zoomAt(float factor, float fvx, float fvy) {
        if (!ready() || !(factor > 0)) return;
        float s0 = scale();
        float ix = (fvx - left()) / s0, iy = (fvy - top()) / s0;
        zoom = Math.max(1f, Math.min(MAX_ZOOM, zoom * factor));
        float s1 = scale();
        float l = fvx - ix * s1, t = fvy - iy * s1;
        cx = (vw / 2f - l) / s1;
        cy = (vh / 2f - t) / s1;
        clampPan();
    }

    /** Posunie obraz o [dvx],[dvy] pixelov okna (obraz ide za prstom). */
    public void panBy(float dvx, float dvy) {
        if (!ready()) return;
        float s = scale();
        cx -= dvx / s;
        cy -= dvy / s;
        clampPan();
    }

    /** Posunie obraz len o toľko, aby bol bod obrazu vo vnútri okna s okrajom [marginPx]. */
    public void ensureVisible(float ix, float iy, float marginPx) {
        if (!ready() || !zoomed()) return;
        float m = Math.max(0f, Math.min(marginPx, Math.min(vw, vh) / 4f));
        float vx = toViewX(ix), vy = toViewY(iy);
        float dx = 0, dy = 0;
        if (vx < m) dx = m - vx;
        else if (vx > vw - m) dx = (vw - m) - vx;
        if (vy < m) dy = m - vy;
        else if (vy > vh - m) dy = (vh - m) - vy;
        if (dx != 0 || dy != 0) panBy(dx, dy);
    }

    private void clampPan() {
        if (!ready()) return;
        zoom = Math.max(1f, Math.min(MAX_ZOOM, zoom));
        float s = scale();
        float halfW = vw / (2f * s), halfH = vh / (2f * s);
        cx = iw * s <= vw ? iw / 2f : Math.max(halfW, Math.min(iw - halfW, cx));
        cy = ih * s <= vh ? ih / 2f : Math.max(halfH, Math.min(ih - halfH, cy));
    }
}
