//go:build !windows

package main

import "sync"

// Mimo Windows (vývoj, testy) beží server s falošným obrazom: `domov-pc -stub`.

func newBackend() Backend {
	be, _, _, _ := newStubBackend()
	return be
}

var autostartMu sync.Mutex
var autostartFlag bool

func autostartEnabled() bool { autostartMu.Lock(); defer autostartMu.Unlock(); return autostartFlag }
func setAutostart(enable bool) error {
	autostartMu.Lock()
	autostartFlag = enable
	autostartMu.Unlock()
	return nil
}
func openBrowser(url string) {}
func canAutostart() bool     { return false }
