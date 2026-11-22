package main

import (
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"image/jpeg"
	"net"
	"time"
)

// Referenčný klient protokolu. Používajú ho testy aj samotestovanie na Windows (-e2e). Telefón má vlastnú
// implementáciu v Jave (PcClient.java) – obe sa overujú proti tomuto serveru.

type tcMsg struct {
	typ byte
	p   []byte
}

type hsError struct {
	code byte
	msg  string
}

func (e hsError) Error() string { return fmt.Sprintf("server odmietol (%d): %s", e.code, e.msg) }

type testClient struct {
	conn      net.Conn
	sc        *secureConn
	inbox     chan tcMsg
	serverID  []byte
	DeviceKey []byte // vyplnené po spárovaní
}

func tcConnect(addr string, mode byte, deviceID, key []byte, name string) (*testClient, error) {
	return tcConnectOpt(addr, mode, deviceID, key, name, true)
}

// tcConnectOpt s verifyServer=false simuluje útočníka, ktorý dôkaz servera neoveruje a rovno hádá (na testy blokovania).
func tcConnectOpt(addr string, mode byte, deviceID, key []byte, name string, verifyServer bool) (*testClient, error) {
	conn, err := net.DialTimeout("tcp", addr, 5*time.Second)
	if err != nil {
		return nil, err
	}
	fail := func(e error) (*testClient, error) { conn.Close(); return nil, e }
	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
	cNonce := randBytes(32)
	hello := []byte{hClientHello, protoVersion, mode}
	hello = append(hello, deviceID...)
	hello = append(hello, cNonce...)
	hello = append(hello, byte(len(name)))
	hello = append(hello, name...)
	if err := writeFrame(conn, hello); err != nil {
		return fail(err)
	}
	resp, err := readFrame(conn, maxHandshake)
	if err != nil {
		return fail(err)
	}
	if len(resp) >= 2 && resp[0] == hError {
		return fail(hsError{resp[1], string(resp[2:])})
	}
	if len(resp) != 82 || resp[0] != hServerHello {
		return fail(errors.New("neplatná odpoveď servera"))
	}
	serverID := resp[2:18]
	sNonce := resp[18:50]
	if verifyServer && !proofEqual(resp[50:], handshakeProof(key, "server", mode, deviceID, cNonce, sNonce, serverID, name)) {
		return fail(errors.New("server sa nevie preukázať (zlý kľúč alebo kód)"))
	}
	proof := append([]byte{hClientProof}, handshakeProof(key, "client", mode, deviceID, sNonce, cNonce, serverID, name)...)
	if err := writeFrame(conn, proof); err != nil {
		return fail(err)
	}
	c2s, s2c := sessionKeys(key, cNonce, sNonce)
	sc := newSecureConn(conn, c2s, s2c, false)
	sc.maxRecv = 64 << 20
	first, p, err := sc.ReadMsg()
	if err != nil {
		if len(sc.lastRaw) >= 2 && sc.lastRaw[0] == hError {
			return fail(hsError{sc.lastRaw[1], string(sc.lastRaw[2:])})
		}
		return fail(err)
	}
	_ = conn.SetDeadline(time.Time{})
	c := &testClient{conn: conn, sc: sc, inbox: make(chan tcMsg, 256), serverID: append([]byte(nil), serverID...)}
	handle := func(t byte, pl []byte) {
		if t == mPaired {
			c.DeviceKey = append([]byte(nil), pl...)
		}
		c.inbox <- tcMsg{t, pl}
	}
	handle(first, p)
	go func() {
		for {
			t, pl, err := sc.ReadMsg()
			if err != nil {
				close(c.inbox)
				return
			}
			handle(t, pl)
		}
	}()
	return c, nil
}

func (c *testClient) send(typ byte, parts ...[]byte) error { return c.sc.WriteMsg(typ, parts...) }
func (c *testClient) close()                               { c.conn.Close() }

// wait vráti prvú správu daného typu (iné zahodí) alebo chybu po vypršaní času / zatvorení spojenia.
func (c *testClient) wait(typ byte, d time.Duration) ([]byte, error) {
	deadline := time.After(d)
	for {
		select {
		case m, ok := <-c.inbox:
			if !ok {
				return nil, errors.New("spojenie zatvorené")
			}
			if m.typ == typ {
				return m.p, nil
			}
		case <-deadline:
			return nil, fmt.Errorf("vypršal čas čakania na správu 0x%02x", typ)
		}
	}
}

// decodeRects overí hlavičku mRects a každý pás rozkóduje; vráti epoch, seq a zoznam (y, výška, šírka obrazu).
type decodedRect struct{ y, h, w int }

func decodeRects(p []byte) (epoch, seq uint32, out []decodedRect, err error) {
	if len(p) < 10 {
		return 0, 0, nil, errors.New("krátka správa")
	}
	epoch = binary.BigEndian.Uint32(p)
	seq = binary.BigEndian.Uint32(p[4:])
	n := int(binary.BigEndian.Uint16(p[8:]))
	off := 10
	for i := 0; i < n; i++ {
		if off+8 > len(p) {
			return 0, 0, nil, errors.New("skrátený pás")
		}
		y := int(binary.BigEndian.Uint16(p[off:]))
		h := int(binary.BigEndian.Uint16(p[off+2:]))
		l := int(binary.BigEndian.Uint32(p[off+4:]))
		off += 8
		if off+l > len(p) {
			return 0, 0, nil, errors.New("skrátené dáta pásu")
		}
		img, err := jpeg.Decode(bytes.NewReader(p[off : off+l]))
		if err != nil {
			return 0, 0, nil, err
		}
		b := img.Bounds()
		if b.Dy() != h {
			return 0, 0, nil, fmt.Errorf("výška pásu %d != %d", b.Dy(), h)
		}
		out = append(out, decodedRect{y, h, b.Dx()})
		off += l
	}
	return epoch, seq, out, nil
}
