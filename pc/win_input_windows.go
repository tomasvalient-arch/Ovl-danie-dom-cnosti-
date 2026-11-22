//go:build windows

package main

import (
	"unicode/utf16"
	"unsafe"
)

const (
	inputMouseType    = 0
	inputKeyboardType = 1

	mouseMove       = 0x0001
	mouseLeftDown   = 0x0002
	mouseLeftUp     = 0x0004
	mouseRightDown  = 0x0008
	mouseRightUp    = 0x0010
	mouseMiddleDown = 0x0020
	mouseMiddleUp   = 0x0040
	mouseXDown      = 0x0080
	mouseXUp        = 0x0100
	mouseWheel      = 0x0800
	mouseHWheel     = 0x1000

	keyExtended = 0x0001
	keyUp       = 0x0002
	keyUnicode  = 0x0004
)

// Rozloženie INPUT na 64-bitovom Windows: 40 bajtov (typ + 4 B odsadenie + 32 B zjednotenie).
type inputMouse struct {
	Type      uint32
	_         uint32
	DX, DY    int32
	MouseData uint32
	Flags     uint32
	Time      uint32
	_         uint32
	Extra     uintptr
}

type inputKey struct {
	Type  uint32
	_     uint32
	VK    uint16
	Scan  uint16
	Flags uint32
	Time  uint32
	_     uint32
	Extra uintptr
	_     [8]byte
}

type winInput struct{}

func newWinInput() *winInput { return &winInput{} }

func sendMouse(flags uint32, data int32) {
	in := inputMouse{Type: inputMouseType, Flags: flags, MouseData: uint32(data)}
	procSendInput.Call(1, uintptr(unsafe.Pointer(&in)), unsafe.Sizeof(in))
}

func (w *winInput) CursorPos() (int, int) {
	var p winPoint
	procGetCursorPos.Call(uintptr(unsafe.Pointer(&p)))
	return int(p.X), int(p.Y)
}

func clampI(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func (w *winInput) setPos(x, y int) {
	procSetCursorPos.Call(uintptr(int32(x)), uintptr(int32(y)))
}

func (w *winInput) MoveRel(mon Monitor, dx, dy int) {
	x, y := w.CursorPos()
	x = clampI(x+dx, mon.X, mon.X+mon.W-1)
	y = clampI(y+dy, mon.Y, mon.Y+mon.H-1)
	w.setPos(x, y)
}

func (w *winInput) MoveAbs(mon Monitor, fx, fy float64) {
	x := mon.X + int(fx*float64(mon.W-1)+0.5)
	y := mon.Y + int(fy*float64(mon.H-1)+0.5)
	w.setPos(clampI(x, mon.X, mon.X+mon.W-1), clampI(y, mon.Y, mon.Y+mon.H-1))
}

func (w *winInput) Button(button int, down bool) {
	var f uint32
	var data int32
	switch button {
	case 1:
		f = mouseLeftUp
		if down {
			f = mouseLeftDown
		}
	case 2:
		f = mouseRightUp
		if down {
			f = mouseRightDown
		}
	case 3:
		f = mouseMiddleUp
		if down {
			f = mouseMiddleDown
		}
	case 4, 5:
		data = int32(button - 3) // XBUTTON1 = 1, XBUTTON2 = 2
		f = mouseXUp
		if down {
			f = mouseXDown
		}
	default:
		return
	}
	sendMouse(f, data)
}

func (w *winInput) Wheel(dx, dy int) {
	if dy != 0 {
		sendMouse(mouseWheel, int32(dy))
	}
	if dx != 0 {
		sendMouse(mouseHWheel, int32(dx))
	}
}

var extendedKeys = map[uint16]bool{
	0xA3: true, 0xA5: true, // pravý Ctrl, pravý Alt
	0x5B: true, 0x5C: true, 0x5D: true, // Win, Win, Menu
	0x2D: true, 0x2E: true, 0x24: true, 0x23: true, 0x21: true, 0x22: true, // Insert, Delete, Home, End, PgUp, PgDn
	0x25: true, 0x26: true, 0x27: true, 0x28: true, // šípky
	0x90: true, 0x6F: true, 0x2C: true, // NumLock, Num /, PrintScreen
}

func (w *winInput) Key(vk uint16, down bool) {
	scan, _, _ := procMapVirtualKeyW.Call(uintptr(vk), 0)
	in := inputKey{Type: inputKeyboardType, VK: vk, Scan: uint16(scan)}
	if extendedKeys[vk] {
		in.Flags |= keyExtended
	}
	if !down {
		in.Flags |= keyUp
	}
	procSendInput.Call(1, uintptr(unsafe.Pointer(&in)), unsafe.Sizeof(in))
}

// Text napíše znaky ako Unicode (nezávisle od rozloženia klávesnice na PC, takže diakritika funguje vždy).
func (w *winInput) Text(s string) {
	var evs []inputKey
	add := func(vk uint16) {
		scan, _, _ := procMapVirtualKeyW.Call(uintptr(vk), 0)
		evs = append(evs,
			inputKey{Type: inputKeyboardType, VK: vk, Scan: uint16(scan)},
			inputKey{Type: inputKeyboardType, VK: vk, Scan: uint16(scan), Flags: keyUp})
	}
	for _, r := range s {
		switch r {
		case '\r':
			continue
		case '\n':
			add(0x0D)
		case '\t':
			add(0x09)
		default:
			for _, u := range utf16.Encode([]rune{r}) {
				evs = append(evs,
					inputKey{Type: inputKeyboardType, Scan: u, Flags: keyUnicode},
					inputKey{Type: inputKeyboardType, Scan: u, Flags: keyUnicode | keyUp})
			}
		}
	}
	for len(evs) > 0 {
		n := len(evs)
		if n > 200 {
			n = 200
		}
		procSendInput.Call(uintptr(n), uintptr(unsafe.Pointer(&evs[0])), unsafe.Sizeof(evs[0]))
		evs = evs[n:]
	}
}
