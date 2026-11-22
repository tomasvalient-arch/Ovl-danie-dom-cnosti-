package main

import (
	"encoding/binary"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"
	"unicode/utf8"
)

// Jedna prihlásená relácia s telefónom: čítanie príkazov (myš, klávesnica...) a posielanie obrazu.

const (
	defaultFPS     = 15
	defaultQuality = 70
	maxInflight    = 2
	idleTimeout    = 15 * time.Second
)

type sessionCfg struct {
	fps, quality, divisor, monitor int
}

type session struct {
	srv  *Server
	sc   *secureConn
	id   []byte
	name string
	ip   net.IP

	stop     chan struct{}
	stopOnce sync.Once
	done     chan struct{}
	wake     chan struct{}

	cfgMu  sync.Mutex
	cfg    sessionCfg
	cfgVer int

	curMu  sync.Mutex
	curMon Monitor
	curDiv int

	refresh   atomic.Bool
	pending   atomic.Int32
	qAdj      atomic.Int32
	sendMu    sync.Mutex
	sendTimes map[uint32]time.Time
	latMu     sync.Mutex
	latency   float64

	heldKeys map[uint16]bool
	heldBtns map[int]bool
}

func newSession(srv *Server, sc *secureConn, id []byte, name string, ip net.IP) *session {
	return &session{
		srv: srv, sc: sc, id: id, name: name, ip: ip,
		stop: make(chan struct{}), done: make(chan struct{}), wake: make(chan struct{}, 1),
		cfg:       sessionCfg{fps: defaultFPS, quality: defaultQuality, divisor: 0, monitor: 255},
		sendTimes: map[uint32]time.Time{},
		heldKeys:  map[uint16]bool{}, heldBtns: map[int]bool{},
	}
}

func (s *session) close() {
	s.stopOnce.Do(func() {
		close(s.stop)
		s.sc.conn.Close()
	})
}

// replaced: tento telefón vytlačil iný – dá mu vedieť a zavrie sa.
func (s *session) replaced() {
	_ = s.sc.WriteMsg(mNotice, []byte{noticeReplaced}, []byte("Pripojil sa iný telefón"))
	s.close()
}

func (s *session) run() {
	defer close(s.done)
	s.srv.log.Printf("pripojený telefón „%s“ (%s)", s.name, s.ip)
	defer s.srv.log.Printf("odpojený telefón „%s“ (%s)", s.name, s.ip)
	capDone := make(chan struct{})
	go func() {
		defer close(capDone)
		s.captureLoop()
	}()
	curDone := make(chan struct{})
	go func() {
		defer close(curDone)
		s.cursorLoop()
	}()
	s.readLoop()
	s.close()
	<-capDone
	<-curDone
	s.releaseAll()
}

// cursorLoop posiela telefónu polohu kurzora (najviac 25× za sekundu, len keď sa zmenila).
func (s *session) cursorLoop() {
	cr, ok := s.srv.be.Input.(CursorReader)
	if !ok {
		return
	}
	var last [6]int
	haveLast := false
	tick := time.NewTicker(40 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-s.stop:
			return
		case <-tick.C:
		}
		mon, _ := s.current()
		if mon.W <= 0 || mon.H <= 0 || !s.srv.be.System.DesktopAvailable() {
			continue
		}
		px, py := cr.CursorPos()
		x16, y16, vis := cursorFraction(mon, px, py)
		cur := [6]int{x16, y16, vis, mon.Index, mon.X + mon.Y*100000, mon.W + mon.H*100000}
		if haveLast && cur == last {
			continue
		}
		haveLast, last = true, cur
		b := make([]byte, 0, 5)
		b = putU16(b, uint16(x16))
		b = putU16(b, uint16(y16))
		b = append(b, byte(vis))
		if err := s.sc.WriteMsg(mCursor, b); err != nil {
			s.close()
			return
		}
	}
}

// cursorFraction prepočíta polohu kurzora (px, py vo virtuálnej ploche) na 0..65535 cez monitor.
// vis je 1, ak je kurzor na tomto monitore; inak sa poloha pritlačí na okraj.
func cursorFraction(mon Monitor, px, py int) (x16, y16, vis int) {
	if px >= mon.X && px < mon.X+mon.W && py >= mon.Y && py < mon.Y+mon.H {
		vis = 1
	}
	fx := float64(clampInt(px-mon.X, 0, mon.W-1)) / float64(maxInt(mon.W-1, 1))
	fy := float64(clampInt(py-mon.Y, 0, mon.H-1)) / float64(maxInt(mon.H-1, 1))
	return int(fx*65535 + 0.5), int(fy*65535 + 0.5), vis
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func (s *session) readLoop() {
	for {
		_ = s.sc.conn.SetReadDeadline(time.Now().Add(idleTimeout))
		typ, p, err := s.sc.ReadMsg()
		if err != nil {
			return
		}
		s.handle(typ, p)
	}
}

func (s *session) notice(code byte, msg string) {
	if err := s.sc.WriteMsg(mNotice, []byte{code}, []byte(msg)); err != nil {
		s.close()
	}
}

func (s *session) signal() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

func (s *session) getCfg() (sessionCfg, int) {
	s.cfgMu.Lock()
	defer s.cfgMu.Unlock()
	return s.cfg, s.cfgVer
}

func (s *session) setCfg(c sessionCfg) {
	s.cfgMu.Lock()
	if c != s.cfg {
		s.cfg = c
		s.cfgVer++
	}
	s.cfgMu.Unlock()
	s.signal()
}

func (s *session) current() (Monitor, int) {
	s.curMu.Lock()
	defer s.curMu.Unlock()
	return s.curMon, s.curDiv
}

func (s *session) setCurrent(m Monitor, k int) {
	s.curMu.Lock()
	s.curMon, s.curDiv = m, k
	s.curMu.Unlock()
}

func clampInt(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

// ---- príkazy od telefónu ----

func (s *session) handle(typ byte, p []byte) {
	in := s.srv.be.Input
	sys := s.srv.be.System
	switch typ {
	case mMoveRel:
		if len(p) >= 4 {
			dx := int(int16(binary.BigEndian.Uint16(p)))
			dy := int(int16(binary.BigEndian.Uint16(p[2:])))
			mon, k := s.current()
			if k < 1 {
				k = 1
			}
			in.MoveRel(mon, dx*k, dy*k)
		}
	case mMoveAbs:
		if len(p) >= 4 {
			fx := float64(binary.BigEndian.Uint16(p)) / 65535
			fy := float64(binary.BigEndian.Uint16(p[2:])) / 65535
			mon, _ := s.current()
			in.MoveAbs(mon, fx, fy)
		}
	case mButton:
		if len(p) >= 2 && p[0] >= 1 && p[0] <= 5 {
			down := p[1] != 0
			in.Button(int(p[0]), down)
			if down {
				s.heldBtns[int(p[0])] = true
			} else {
				delete(s.heldBtns, int(p[0]))
			}
		}
	case mWheel:
		if len(p) >= 4 {
			dx := int(int16(binary.BigEndian.Uint16(p)))
			dy := int(int16(binary.BigEndian.Uint16(p[2:])))
			in.Wheel(dx, dy)
		}
	case mKey:
		if len(p) >= 3 {
			vk := binary.BigEndian.Uint16(p)
			down := p[2]&1 != 0
			if vk == 0 || vk > 0xFE {
				return
			}
			in.Key(vk, down)
			if down {
				s.heldKeys[vk] = true
			} else {
				delete(s.heldKeys, vk)
			}
		}
	case mText:
		if len(p) > 0 && len(p) <= maxTextPerMsg && utf8.Valid(p) {
			in.Text(string(p))
		}
	case mAck:
		if len(p) >= 4 {
			s.onAck(binary.BigEndian.Uint32(p))
		}
	case mConfig:
		if len(p) >= 4 {
			fps, q, div, mon := int(p[0]), int(p[1]), int(p[2]), int(p[3])
			if fps == 0 {
				fps = defaultFPS
			}
			if q == 0 {
				q = defaultQuality
			}
			s.setCfg(sessionCfg{fps: clampInt(fps, 1, 30), quality: clampInt(q, 20, 95), divisor: clampInt(div, 0, 3), monitor: mon})
		}
	case mRefresh:
		s.refresh.Store(true)
		s.signal()
	case mClipSet:
		if len(p) <= maxClipboard && utf8.Valid(p) {
			if err := sys.ClipboardSet(string(p)); err != nil {
				s.notice(noticeClipboardError, "Schránku PC sa nepodarilo nastaviť")
			}
		}
	case mClipGet:
		txt, err := sys.ClipboardGet()
		if err != nil {
			s.notice(noticeClipboardError, "Schránku PC sa nepodarilo prečítať")
			return
		}
		if len(txt) > maxClipboard {
			txt = txt[:maxClipboard]
			for !utf8.ValidString(txt) {
				txt = txt[:len(txt)-1]
			}
		}
		if err := s.sc.WriteMsg(mClip, []byte(txt)); err != nil {
			s.close()
		}
	case mPower:
		if len(p) >= 1 {
			s.handlePower(int(p[0]))
		}
	case mPing:
		if len(p) > 16 {
			p = p[:16]
		}
		if err := s.sc.WriteMsg(mPong, p); err != nil {
			s.close()
		}
	case mReleaseAll:
		s.releaseAll()
	}
}

func (s *session) handlePower(action int) {
	if action < powerLock || action > powerShutdown {
		return
	}
	sys := s.srv.be.System
	if !sys.CanPower() {
		s.notice(noticeInfo, "Ovládanie napájania nie je na tomto PC dostupné")
		return
	}
	s.srv.log.Printf("príkaz napájania %d od „%s“", action, s.name)
	s.notice(noticePowerAccepted, "")
	go func() {
		time.Sleep(700 * time.Millisecond) // nech telefón stihne dostať potvrdenie
		if err := sys.Power(action); err != nil {
			s.srv.log.Printf("napájanie %d zlyhalo: %v", action, err)
		}
	}()
}

// releaseAll uvoľní všetky podržané klávesy a tlačidlá myši (po odpojení telefónu nesmie nič ostať "zaseknuté").
func (s *session) releaseAll() {
	in := s.srv.be.Input
	for vk := range s.heldKeys {
		in.Key(vk, false)
	}
	for b := range s.heldBtns {
		in.Button(b, false)
	}
	s.heldKeys = map[uint16]bool{}
	s.heldBtns = map[int]bool{}
}

// ---- posielanie obrazu ----

func (s *session) onAck(seq uint32) {
	s.sendMu.Lock()
	t, ok := s.sendTimes[seq]
	delete(s.sendTimes, seq)
	s.sendMu.Unlock()
	if !ok {
		return
	}
	if s.pending.Add(-1) < 0 {
		s.pending.Store(0)
	}
	lat := float64(time.Since(t).Milliseconds())
	s.latMu.Lock()
	if s.latency == 0 {
		s.latency = lat
	} else {
		s.latency = 0.7*s.latency + 0.3*lat
	}
	l := s.latency
	s.latMu.Unlock()
	q := s.qAdj.Load()
	if l > 350 && q > -40 {
		s.qAdj.Store(q - 10)
	} else if l < 120 && q < 0 {
		s.qAdj.Store(q + 5)
	}
	s.signal()
}

func (s *session) sleep(d time.Duration) {
	if d <= 0 {
		return
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-s.stop:
	case <-t.C:
	}
}

func (s *session) waitWake(d time.Duration) {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-s.stop:
	case <-s.wake:
	case <-t.C:
	}
}

func pickMonitor(mons []Monitor, idx int) Monitor {
	for _, m := range mons {
		if m.Index == idx {
			return m
		}
	}
	for _, m := range mons {
		if m.Primary {
			return m
		}
	}
	return mons[0]
}

func monitorsEqual(a, b []Monitor) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func (s *session) sendScreen(mon Monitor, k int, outW, outH int, epoch uint32) error {
	var b []byte
	b = append(b, byte(mon.Index))
	b = putU32(b, uint32(outW))
	b = putU32(b, uint32(outH))
	b = append(b, byte(k))
	b = putU32(b, uint32(mon.W))
	b = putU32(b, uint32(mon.H))
	b = putU32(b, epoch)
	return s.sc.WriteMsg(mScreen, b)
}

func (s *session) sendRects(epoch, seq uint32, rects []rect) error {
	hdr := make([]byte, 0, 10)
	hdr = putU32(hdr, epoch)
	hdr = putU32(hdr, seq)
	hdr = putU16(hdr, uint16(len(rects)))
	parts := make([][]byte, 0, 1+2*len(rects))
	parts = append(parts, hdr)
	for _, r := range rects {
		rh := make([]byte, 0, 8)
		rh = putU16(rh, uint16(r.y))
		rh = putU16(rh, uint16(r.h))
		rh = putU32(rh, uint32(len(r.data)))
		parts = append(parts, rh, r.data)
	}
	return s.sc.WriteMsg(mRects, parts...)
}

func (s *session) captureLoop() {
	var (
		buf         []byte
		enc         *encoder
		seq         uint32
		epoch       uint32
		lastGeom    string
		lastMons    []Monitor
		lastCheck   time.Time
		lastSend    = time.Now()
		unavailable bool
		errCount    int
		errNotified bool
		appliedVer  = -1
		force       = true
	)
	be := s.srv.be
	for {
		select {
		case <-s.stop:
			return
		default:
		}
		iter := time.Now()
		cfg, ver := s.getCfg()
		if ver != appliedVer {
			appliedVer = ver
			force = true
			lastCheck = time.Time{}
		}
		if s.refresh.Swap(false) {
			force = true
		}

		// zamknutá obrazovka / UAC: nie je čo zachytávať
		if !be.System.DesktopAvailable() {
			if !unavailable {
				unavailable = true
				s.notice(noticeDesktopLocked, "PC je zamknutý alebo ukazuje prihlasovaciu obrazovku. Odomkni ho na PC.")
			}
			s.waitWake(300 * time.Millisecond)
			continue
		}
		if unavailable {
			unavailable = false
			s.notice(noticeDesktopOK, "")
			force = true
		}

		// monitory a ich geometria (kontrola raz za sekundu a po zmene nastavení)
		if time.Since(lastCheck) > time.Second {
			lastCheck = time.Now()
			mons := be.Screen.Monitors()
			if len(mons) == 0 {
				s.sleep(time.Second)
				continue
			}
			mon := pickMonitor(mons, cfg.monitor)
			k := cfg.divisor
			if k == 0 {
				k = chooseDivisor(mon.W)
			}
			if !monitorsEqual(mons, lastMons) {
				lastMons = mons
				if err := s.sc.WriteMsg(mSessionInfo, s.srv.infoPayload(s.ip, mons, mon.Index)); err != nil {
					s.close()
					return
				}
			}
			geom := fmt.Sprintf("%d:%d,%d,%d,%d:%d", mon.Index, mon.X, mon.Y, mon.W, mon.H, k)
			if geom != lastGeom {
				lastGeom = geom
				epoch++
				enc = newEncoder(mon.W, mon.H, k)
				s.setCurrent(mon, k)
				if err := s.sendScreen(mon, k, enc.outW, enc.outH, epoch); err != nil {
					s.close()
					return
				}
				force = true
			}
		}
		if enc == nil {
			s.sleep(100 * time.Millisecond)
			continue
		}

		// okno prenosu: nepošleme ďalší obraz, kým telefón nepotvrdí predošlé
		if s.pending.Load() >= maxInflight {
			if time.Since(lastSend) > 5*time.Second {
				s.pending.Store(0) // telefón nepotvrdzuje: radšej pokračovať, než zaseknúť obraz
				s.sendMu.Lock()
				s.sendTimes = map[uint32]time.Time{}
				s.sendMu.Unlock()
			} else {
				s.waitWake(50 * time.Millisecond)
			}
			continue
		}

		mon, _ := s.current()
		var err error
		buf, err = be.Screen.Grab(mon, buf)
		if err != nil || len(buf) != mon.W*mon.H*4 {
			errCount++
			lastCheck = time.Time{}
			if errCount >= 3 && !errNotified {
				errNotified = true
				s.notice(noticeCaptureError, "Obraz PC sa nepodarilo zachytiť")
			}
			s.sleep(400 * time.Millisecond)
			continue
		}
		errCount = 0
		if errNotified {
			errNotified = false
			s.notice(noticeDesktopOK, "")
		}

		q := clampInt(cfg.quality+int(s.qAdj.Load()), 25, 95)
		rects := enc.encode(buf, q, force)
		force = false
		if len(rects) > 0 {
			seq++
			s.sendMu.Lock()
			s.sendTimes[seq] = time.Now()
			s.sendMu.Unlock()
			s.pending.Add(1)
			lastSend = time.Now()
			if err := s.sendRects(epoch, seq, rects); err != nil {
				s.close()
				return
			}
		}
		s.sleep(time.Until(iter.Add(time.Second / time.Duration(cfg.fps))))
	}
}
