//go:build windows

package main

import (
	"errors"
	"os"
	"os/exec"
	"strings"
	"syscall"
	"time"
	"unicode/utf16"
	"unsafe"
)

const (
	cfUnicodeText = 13
	gmemMoveable  = 0x0002
)

func syscallCallback(f func(a, b, c, d uintptr) uintptr) uintptr { return syscall.NewCallback(f) }

type winSystem struct{}

func hiddenCmd(name string, args ...string) *exec.Cmd {
	c := exec.Command(name, args...)
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	return c
}

// DesktopAvailable: false, keď je zamknutá obrazovka, prihlasovacia obrazovka, UAC alebo šetrič (vstupná plocha nie je "Default").
func (winSystem) DesktopAvailable() bool {
	h, _, _ := procOpenInputDesktop.Call(0, 0, 0x0001) // DESKTOP_READOBJECTS
	if h == 0 {
		return false
	}
	defer procCloseDesktop.Call(h)
	buf := make([]uint16, 64)
	var need uint32
	r, _, _ := procGetUserObjectInfoW.Call(h, 2, uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)*2), uintptr(unsafe.Pointer(&need)))
	if r == 0 {
		return true // názov sa nepodarilo zistiť, ale plocha sa otvorila: skúsime zachytávať
	}
	return strings.EqualFold(syscall.UTF16ToString(buf), "Default")
}

func openClipboardRetry() bool {
	for i := 0; i < 10; i++ {
		if r, _, _ := procOpenClipboard.Call(0); r != 0 {
			return true
		}
		time.Sleep(20 * time.Millisecond)
	}
	return false
}

func (winSystem) ClipboardGet() (string, error) {
	if !openClipboardRetry() {
		return "", errors.New("schránka je obsadená")
	}
	defer procCloseClipboard.Call()
	h, _, _ := procGetClipboardData.Call(cfUnicodeText)
	if h == 0 {
		return "", nil // prázdna alebo bez textu
	}
	p, _, _ := procGlobalLock.Call(h)
	if p == 0 {
		return "", errors.New("GlobalLock zlyhalo")
	}
	defer procGlobalUnlock.Call(h)
	var units []uint16
	for i := 0; i < maxClipboard; i++ {
		u := *(*uint16)(ptr(p + uintptr(i)*2))
		if u == 0 {
			break
		}
		units = append(units, u)
	}
	return strings.ReplaceAll(string(utf16.Decode(units)), "\r\n", "\n"), nil
}

func (winSystem) ClipboardSet(s string) error {
	s = strings.ReplaceAll(strings.ReplaceAll(s, "\r\n", "\n"), "\n", "\r\n")
	units := utf16.Encode([]rune(s))
	units = append(units, 0)
	size := uintptr(len(units) * 2)
	h, _, _ := procGlobalAlloc.Call(gmemMoveable, size)
	if h == 0 {
		return errors.New("GlobalAlloc zlyhalo")
	}
	p, _, _ := procGlobalLock.Call(h)
	if p == 0 {
		procGlobalFree.Call(h)
		return errors.New("GlobalLock zlyhalo")
	}
	copy(unsafe.Slice((*uint16)(ptr(p)), len(units)), units)
	procGlobalUnlock.Call(h)
	if !openClipboardRetry() {
		procGlobalFree.Call(h)
		return errors.New("schránka je obsadená")
	}
	defer procCloseClipboard.Call()
	procEmptyClipboard.Call()
	if r, _, _ := procSetClipboardData.Call(cfUnicodeText, h); r == 0 {
		procGlobalFree.Call(h)
		return errors.New("SetClipboardData zlyhalo")
	}
	return nil // vlastníkom pamäte je teraz systém
}

func (winSystem) CanPower() bool { return true }

func (winSystem) Elevated() bool {
	r, _, _ := procIsUserAnAdmin.Call()
	return r != 0
}

func (winSystem) Power(action int) error {
	switch action {
	case powerLock:
		if r, _, e := procLockWorkStation.Call(); r == 0 {
			return e
		}
		return nil
	case powerSleep:
		// SetSuspendState(hibernate=FALSE, force=FALSE, disableWakeEvents=FALSE)
		if r, _, e := procSetSuspendState.Call(0, 0, 0); r == 0 {
			return e
		}
		return nil
	case powerRestart:
		return hiddenCmd("shutdown", "/r", "/t", "3").Run()
	case powerShutdown:
		return hiddenCmd("shutdown", "/s", "/t", "3").Run()
	}
	return errors.New("neznáma akcia")
}

func newBackend() Backend {
	initDPI()
	return Backend{Screen: newWinScreen(), Input: newWinInput(), System: winSystem{}}
}

// ---- spúšťanie pri štarte Windows (bez práv správcu: kľúč Run v HKCU) ----

const runKey = `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`

func autostartEnabled() bool {
	return hiddenCmd("reg", "query", runKey, "/v", "DomovPC").Run() == nil
}

func setAutostart(enable bool) error {
	if !enable {
		_ = hiddenCmd("reg", "delete", runKey, "/v", "DomovPC", "/f").Run()
		return nil
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	return hiddenCmd("reg", "add", runKey, "/v", "DomovPC", "/t", "REG_SZ", "/d", `"`+exe+`" -background`, "/f").Run()
}

func openBrowser(url string) {
	_ = hiddenCmd("rundll32", "url.dll,FileProtocolHandler", url).Start()
}

func canAutostart() bool { return true }
