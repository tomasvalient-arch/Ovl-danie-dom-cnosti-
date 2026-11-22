package main

import (
	"encoding/binary"
	"strings"
	"unicode/utf8"
)

// Protokol Domov PC – pozri PROTOCOL.md. Čísla správ sú spoločné s klientom v Jave (PcProtocol.java).

const protoVersion = 1

const (
	defaultPort    = 47810 // TCP: ovládanie, UDP: hľadanie PC v sieti
	defaultWebPort = 47811 // len 127.0.0.1: stránka správy na PC
	appVersion     = "1.0.0"
)

// handshake (nešifrované rámce)
const (
	hClientHello = 0x01
	hServerHello = 0x02
	hClientProof = 0x03
	hError       = 0x7F
)

// kódy chýb v hError
const (
	errUnknownDevice = 1
	errLockedOut     = 2
	errNoPairing     = 3
	errBadProof      = 4
	errProtocol      = 5
	errBusy          = 6
)

// server → klient (šifrované)
const (
	mSessionInfo = 0x10
	mPaired      = 0x11
	mScreen      = 0x12
	mRects       = 0x13
	mClip        = 0x15
	mNotice      = 0x16
	mPong        = 0x17
	mCursor      = 0x18 // poloha kurzora: x u16, y u16 (0..65535 cez aktívny monitor), príznaky u8 (1 = na tomto monitore)
)

// klient → server (šifrované)
const (
	mMoveRel    = 0x20
	mMoveAbs    = 0x21
	mButton     = 0x22
	mWheel      = 0x23
	mKey        = 0x24
	mText       = 0x25
	mAck        = 0x26
	mConfig     = 0x27
	mRefresh    = 0x28
	mClipSet    = 0x29
	mClipGet    = 0x2A
	mPower      = 0x2B
	mPing       = 0x2C
	mReleaseAll = 0x2D
)

// kódy upozornení (mNotice)
const (
	noticeDesktopLocked  = 1
	noticeDesktopOK      = 2
	noticeCaptureError   = 3
	noticeInfo           = 4
	noticePowerAccepted  = 5
	noticeClipboardError = 6
	noticeReplaced       = 7 // tento telefón vytlačil iný: appka sa nemá sama pripájať späť
)

// akcie napájania (mPower)
const (
	powerLock     = 1
	powerSleep    = 2
	powerRestart  = 3
	powerShutdown = 4
)

// príznaky v mSessionInfo
const (
	flagCanPower = 1 << 0
	flagElevated = 1 << 1
)

// obmedzenia správ od klienta
const (
	maxClientMsg   = 1 << 20 // 1 MiB
	maxClipboard   = 256 << 10
	maxTextPerMsg  = 4096
	maxHandshake   = 512
	maxDeviceName  = 48
	handshakeLimit = 10 // sekúnd na dokončenie handshaku
)

func putU32(b []byte, v uint32) []byte { return binary.BigEndian.AppendUint32(b, v) }
func putU16(b []byte, v uint16) []byte { return binary.BigEndian.AppendUint16(b, v) }

// cleanName: orezanie, bez riadiacich znakov, najviac maxDeviceName bajtov (platné UTF-8).
func cleanName(s string) string {
	s = strings.Map(func(r rune) rune {
		if r < 32 || r == 127 {
			return -1
		}
		return r
	}, strings.ToValidUTF8(strings.TrimSpace(s), ""))
	for len(s) > maxDeviceName {
		_, size := utf8.DecodeLastRuneInString(s)
		s = s[:len(s)-size]
	}
	return s
}
