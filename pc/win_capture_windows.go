//go:build windows

package main

import (
	"errors"
	"sort"
	"sync"
	"unsafe"
)

const (
	srccopy    = 0x00CC0020
	captureblt = 0x40000000
	diNormal   = 0x0003
)

type winScreen struct {
	mu     sync.Mutex
	hdcMem uintptr
	hbmp   uintptr
	old    uintptr
	bits   unsafe.Pointer
	w, h   int
}

func newWinScreen() *winScreen { return &winScreen{} }

var (
	enumMu     sync.Mutex
	enumResult []Monitor
	enumCB     = syscallCallback(func(hMon, hdc, lprc, lparam uintptr) uintptr {
		var mi monitorInfo
		mi.Size = uint32(unsafe.Sizeof(mi))
		if r, _, _ := procGetMonitorInfoW.Call(hMon, uintptr(unsafe.Pointer(&mi))); r != 0 {
			enumResult = append(enumResult, Monitor{
				X: int(mi.Monitor.Left), Y: int(mi.Monitor.Top),
				W: int(mi.Monitor.Right - mi.Monitor.Left), H: int(mi.Monitor.Bottom - mi.Monitor.Top),
				Primary: mi.Flags&1 != 0,
			})
		}
		return 1
	})
)

func (s *winScreen) Monitors() []Monitor {
	enumMu.Lock()
	defer enumMu.Unlock()
	enumResult = nil
	procEnumDisplayMonitors.Call(0, 0, enumCB, 0)
	out := append([]Monitor(nil), enumResult...)
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].X != out[j].X {
			return out[i].X < out[j].X
		}
		return out[i].Y < out[j].Y
	})
	for i := range out {
		out[i].Index = i
	}
	return out
}

func (s *winScreen) release() {
	if s.hdcMem != 0 {
		if s.old != 0 {
			procSelectObject.Call(s.hdcMem, s.old)
		}
		procDeleteDC.Call(s.hdcMem)
	}
	if s.hbmp != 0 {
		procDeleteObject.Call(s.hbmp)
	}
	s.hdcMem, s.hbmp, s.old, s.bits, s.w, s.h = 0, 0, 0, nil, 0, 0
}

func (s *winScreen) setup(w, h int) error {
	s.release()
	hdc, _, _ := procCreateCompatibleDC.Call(0)
	if hdc == 0 {
		return errors.New("CreateCompatibleDC zlyhalo")
	}
	var bi bitmapInfo
	bi.Header.Size = uint32(unsafe.Sizeof(bi.Header))
	bi.Header.Width = int32(w)
	bi.Header.Height = int32(-h) // zhora nadol
	bi.Header.Planes = 1
	bi.Header.BitCount = 32
	var bits unsafe.Pointer
	hbmp, _, _ := procCreateDIBSection.Call(hdc, uintptr(unsafe.Pointer(&bi)), 0, uintptr(unsafe.Pointer(&bits)), 0, 0)
	if hbmp == 0 || bits == nil {
		procDeleteDC.Call(hdc)
		return errors.New("CreateDIBSection zlyhalo")
	}
	old, _, _ := procSelectObject.Call(hdc, hbmp)
	s.hdcMem, s.hbmp, s.old, s.bits, s.w, s.h = hdc, hbmp, old, bits, w, h
	return nil
}

func (s *winScreen) Grab(mon Monitor, dst []byte) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if mon.W <= 0 || mon.H <= 0 {
		return dst, errors.New("neplatný monitor")
	}
	if s.hdcMem == 0 || s.w != mon.W || s.h != mon.H {
		if err := s.setup(mon.W, mon.H); err != nil {
			return dst, err
		}
	}
	// obrazovkový DC sa berie vždy nanovo: po zmene pracovnej plochy (zamknutie, UAC) by starý prestal platiť
	hdcScreen, _, _ := procGetDC.Call(0)
	if hdcScreen == 0 {
		return dst, errors.New("GetDC zlyhalo")
	}
	defer procReleaseDC.Call(0, hdcScreen)
	r, _, _ := procBitBlt.Call(s.hdcMem, 0, 0, uintptr(mon.W), uintptr(mon.H), hdcScreen,
		uintptr(int32(mon.X)), uintptr(int32(mon.Y)), srccopy|captureblt)
	if r == 0 {
		return dst, errors.New("BitBlt zlyhalo")
	}
	drawCursor(s.hdcMem, mon)
	procGdiFlush.Call()
	n := mon.W * mon.H * 4
	if cap(dst) < n {
		dst = make([]byte, n)
	}
	dst = dst[:n]
	copy(dst, unsafe.Slice((*byte)(s.bits), n))
	return dst, nil
}

// drawCursor dokreslí kurzor myši (BitBlt ho nezachytáva).
func drawCursor(hdc uintptr, mon Monitor) {
	var ci cursorInfo
	ci.Size = uint32(unsafe.Sizeof(ci))
	if r, _, _ := procGetCursorInfo.Call(uintptr(unsafe.Pointer(&ci))); r == 0 || ci.Flags&1 == 0 || ci.Cursor == 0 {
		return
	}
	var ii iconInfo
	hx, hy := int32(0), int32(0)
	if r, _, _ := procGetIconInfo.Call(ci.Cursor, uintptr(unsafe.Pointer(&ii))); r != 0 {
		hx, hy = int32(ii.HotspotX), int32(ii.HotspotY)
		if ii.Mask != 0 {
			procDeleteObject.Call(ii.Mask)
		}
		if ii.Color != 0 {
			procDeleteObject.Call(ii.Color)
		}
	}
	x := ci.ScreenX - int32(mon.X) - hx
	y := ci.ScreenY - int32(mon.Y) - hy
	procDrawIconEx.Call(hdc, uintptr(x), uintptr(y), ci.Cursor, 0, 0, 0, 0, diNormal)
}

func (s *winScreen) Close() {
	s.mu.Lock()
	s.release()
	s.mu.Unlock()
}
