package main

import (
	"fmt"
	"os"
	"sync"
)

// Náhradné (falošné) rozhrania na testy a na spustenie mimo Windows. Na Windows ostrú verziu dodávajú win_*.go.

type stubScreen struct {
	mu    sync.Mutex
	mons  []Monitor
	tick  int
	fail  bool
	grabs int
}

func newStubScreen() *stubScreen {
	return &stubScreen{mons: []Monitor{
		{Index: 0, X: 0, Y: 0, W: 640, H: 360, Primary: true},
		{Index: 1, X: 640, Y: 0, W: 320, H: 240},
	}}
}

func (s *stubScreen) SetTick(n int)  { s.mu.Lock(); s.tick = n; s.mu.Unlock() }
func (s *stubScreen) SetFail(f bool) { s.mu.Lock(); s.fail = f; s.mu.Unlock() }
func (s *stubScreen) SetMonitors(m []Monitor) {
	s.mu.Lock()
	s.mons = m
	s.mu.Unlock()
}
func (s *stubScreen) Grabs() int { s.mu.Lock(); defer s.mu.Unlock(); return s.grabs }

func (s *stubScreen) Monitors() []Monitor {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]Monitor(nil), s.mons...)
}

func (s *stubScreen) Grab(mon Monitor, dst []byte) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.fail {
		return dst, fmt.Errorf("zachytenie zlyhalo (test)")
	}
	s.grabs++
	n := mon.W * mon.H * 4
	if cap(dst) < n {
		dst = make([]byte, n)
	}
	dst = dst[:n]
	// pozadie: farebný prechod; štvorec 40x40, ktorého poloha závisí od tick
	sq := (s.tick * 37) % (mon.W - 40)
	for y := 0; y < mon.H; y++ {
		for x := 0; x < mon.W; x++ {
			i := (y*mon.W + x) * 4
			b, g, r := byte(x*255/mon.W), byte(y*255/mon.H), byte(128)
			if x >= sq && x < sq+40 && y >= 100 && y < 140 {
				b, g, r = 255, 255, 255
			}
			dst[i], dst[i+1], dst[i+2], dst[i+3] = b, g, r, 0
		}
	}
	return dst, nil
}

func (s *stubScreen) Close() {}

// traceInput: pri -trace-input stub vypisuje prijaté vstupy na stderr (testy klienta v Jave podľa nich overujú správy).
var traceInput bool

type stubInput struct {
	mu     sync.Mutex
	events []string
	cx, cy int // poloha kurzora vo virtuálnej ploche
}

func (i *stubInput) add(format string, a ...any) {
	ev := fmt.Sprintf(format, a...)
	i.mu.Lock()
	i.events = append(i.events, ev)
	i.mu.Unlock()
	if traceInput {
		fmt.Fprintln(os.Stderr, "INPUT "+ev)
	}
}
func (i *stubInput) Events() []string {
	i.mu.Lock()
	defer i.mu.Unlock()
	return append([]string(nil), i.events...)
}
func (i *stubInput) MoveRel(mon Monitor, dx, dy int) {
	i.mu.Lock()
	i.cx += dx
	i.cy += dy
	i.mu.Unlock()
	i.add("rel:%d,%d", dx, dy)
}
func (i *stubInput) MoveAbs(mon Monitor, fx, fy float64) {
	i.mu.Lock()
	i.cx = mon.X + int(fx*float64(mon.W-1)+0.5)
	i.cy = mon.Y + int(fy*float64(mon.H-1)+0.5)
	i.mu.Unlock()
	i.add("abs:%.2f,%.2f@%d", fx, fy, mon.Index)
}
func (i *stubInput) CursorPos() (int, int) {
	i.mu.Lock()
	defer i.mu.Unlock()
	return i.cx, i.cy
}
func (i *stubInput) Button(b int, down bool)  { i.add("btn:%d:%v", b, down) }
func (i *stubInput) Wheel(dx, dy int)         { i.add("wheel:%d,%d", dx, dy) }
func (i *stubInput) Key(vk uint16, down bool) { i.add("key:%d:%v", vk, down) }
func (i *stubInput) Text(s string)            { i.add("text:%s", s) }

type stubSystem struct {
	mu        sync.Mutex
	locked    bool
	clip      string
	powerLog  []int
	canPower  bool
	clipError bool
}

func (s *stubSystem) SetLocked(l bool) { s.mu.Lock(); s.locked = l; s.mu.Unlock() }
func (s *stubSystem) DesktopAvailable() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return !s.locked
}
func (s *stubSystem) ClipboardGet() (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.clipError {
		return "", fmt.Errorf("schránka nedostupná")
	}
	return s.clip, nil
}
func (s *stubSystem) ClipboardSet(v string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.clipError {
		return fmt.Errorf("schránka nedostupná")
	}
	s.clip = v
	return nil
}
func (s *stubSystem) Power(a int) error {
	s.mu.Lock()
	s.powerLog = append(s.powerLog, a)
	s.mu.Unlock()
	return nil
}
func (s *stubSystem) PowerLog() []int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]int(nil), s.powerLog...)
}
func (s *stubSystem) CanPower() bool { return true }
func (s *stubSystem) Elevated() bool { return false }

func newStubBackend() (Backend, *stubScreen, *stubInput, *stubSystem) {
	sc, in, sy := newStubScreen(), &stubInput{}, &stubSystem{}
	return Backend{Screen: sc, Input: in, System: sy}, sc, in, sy
}
