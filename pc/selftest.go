package main

import (
	"fmt"
	"io"
	"time"
)

// runSelfTest overí, že PC časť vie zachytiť obraz (a pri full aj hýbať myšou a používať schránku).
// Vráti true, ak nič nezlyhalo. Používa ho stránka správy (bez zásahu do myši a schránky) a CI na Windows (full).
func runSelfTest(be Backend, w io.Writer, full bool) bool {
	ok := true
	p := func(format string, a ...any) { fmt.Fprintf(w, format+"\n", a...) }
	mons := be.Screen.Monitors()
	p("Monitory: %d", len(mons))
	for _, m := range mons {
		pr := ""
		if m.Primary {
			pr = " (hlavný)"
		}
		p("  #%d %dx%d na (%d,%d)%s", m.Index, m.W, m.H, m.X, m.Y, pr)
	}
	if len(mons) == 0 {
		p("CHYBA: nenašiel sa žiadny monitor")
		return false
	}
	if be.System.DesktopAvailable() {
		p("Plocha: odomknutá")
	} else {
		p("Plocha: nedostupná (zamknutá obrazovka alebo UAC); obraz sa teraz neprenáša")
	}
	mon := pickMonitor(mons, 255)
	var buf []byte
	var err error
	t0 := time.Now()
	const rounds = 5
	for i := 0; i < rounds; i++ {
		buf, err = be.Screen.Grab(mon, buf)
		if err != nil {
			p("CHYBA: zachytenie zlyhalo: %v", err)
			return false
		}
	}
	grabMs := time.Since(t0).Milliseconds() / rounds
	if len(buf) != mon.W*mon.H*4 {
		p("CHYBA: zachytený obraz má %d bajtov, čakal som %d", len(buf), mon.W*mon.H*4)
		return false
	}
	var sum, nonzero int
	for i := 0; i < len(buf); i += 4 * 97 {
		sum += int(buf[i]) + int(buf[i+1]) + int(buf[i+2])
		if buf[i]|buf[i+1]|buf[i+2] != 0 {
			nonzero++
		}
	}
	p("Zachytenie %dx%d: %d ms na snímku, neprázdnych vzoriek: %d", mon.W, mon.H, grabMs, nonzero)
	if nonzero == 0 {
		p("VAROVANIE: obraz je celý čierny (zamknutá plocha, chránený obsah alebo prázdna obrazovka)")
	}
	k := chooseDivisor(mon.W)
	enc := newEncoder(mon.W, mon.H, k)
	t1 := time.Now()
	rects := enc.encode(buf, defaultQuality, true)
	total := 0
	for _, r := range rects {
		total += len(r.data)
	}
	if len(rects) == 0 || total == 0 {
		p("CHYBA: kódovanie obrazu nevrátilo nič")
		return false
	}
	p("Kódovanie (delič %d): %d pásov, %d kB, %d ms", k, len(rects), total/1024, time.Since(t1).Milliseconds())
	est := grabMs + time.Since(t1).Milliseconds()
	if est < 1 {
		est = 1
	}
	p("Odhad rýchlosti pri plnej zmene obrazu: asi %d snímok/s", 1000/est)

	if !full {
		return ok
	}
	if cp, isCP := be.Input.(interface{ CursorPos() (int, int) }); isCP {
		x0, y0 := cp.CursorPos()
		dx, dy := 7, 5
		if x0+dx >= mon.X+mon.W-1 || y0+dy >= mon.Y+mon.H-1 {
			dx, dy = -7, -5
		}
		be.Input.MoveRel(mon, dx, dy)
		x1, y1 := cp.CursorPos()
		be.Input.MoveRel(mon, -dx, -dy)
		if x1-x0 == dx && y1-y0 == dy {
			p("Myš: pohyb funguje")
		} else {
			p("CHYBA: myš sa nepohla (pred %d,%d po %d,%d)", x0, y0, x1, y1)
			ok = false
		}
	}
	msg := "Domov PC test ľščťžýáíé 😀\nďalší riadok"
	if err := be.System.ClipboardSet(msg); err != nil {
		p("CHYBA: schránka (zápis): %v", err)
		ok = false
	} else if got, err := be.System.ClipboardGet(); err != nil || got != msg {
		p("CHYBA: schránka (čítanie): %q %v", got, err)
		ok = false
	} else {
		p("Schránka: zápis a čítanie funguje (aj diakritika a emoji)")
	}
	return ok
}
