package main

import (
	"bytes"
	"context"
	"errors"
	"log"
	"net"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Server prijíma pripojenia z telefónov, overuje ich a spúšťa relácie.

const (
	pairWindow     = 3 * time.Minute
	pairMaxFails   = 5
	banAfterFails  = 8
	banDuration    = 10 * time.Minute
	failWindow     = 10 * time.Minute
	maxHandshakers = 16
)

type pairingState struct {
	active  bool
	code    string
	key     []byte
	expires time.Time
	fails   int
}

type banEntry struct {
	fails int
	first time.Time
	until time.Time
}

type Server struct {
	store *Store
	be    Backend
	log   *log.Logger
	port  int

	mu          sync.Mutex
	ln          net.Listener
	pairing     pairingState
	bans        map[string]*banEntry
	active      *session
	lastPairOK  time.Time
	handshaking atomic.Int32
	started     time.Time
}

func NewServer(store *Store, be Backend, port int, lg *log.Logger) *Server {
	if lg == nil {
		lg = log.New(discard{}, "", 0)
	}
	return &Server{store: store, be: be, log: lg, port: port, bans: map[string]*banEntry{}, started: time.Now()}
}

type discard struct{}

func (discard) Write(p []byte) (int, error) { return len(p), nil }

// isLANAddr: povolené sú len adresy z domácej siete (súkromné, link-local, loopback). Verejná adresa = zahodiť.
func isLANAddr(ip net.IP) bool {
	if ip == nil {
		return false
	}
	if v4 := ip.To4(); v4 != nil {
		ip = v4
	}
	return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast()
}

// ---- párovanie ----

// StartPairing otvorí okno párovania a vráti kód. Trvá chvíľu (PBKDF2).
func (s *Server) StartPairing() (code string, expires time.Time) {
	code = newPairCode()
	key := pairKeyFromCode(code, s.store.ServerID())
	s.mu.Lock()
	defer s.mu.Unlock()
	s.pairing = pairingState{active: true, code: code, key: key, expires: time.Now().Add(pairWindow)}
	return code, s.pairing.expires
}

func (s *Server) CancelPairing() {
	s.mu.Lock()
	s.pairing = pairingState{}
	s.mu.Unlock()
}

// PairingInfo vráti stav okna párovania (po vypršaní ho zatvorí).
func (s *Server) PairingInfo() (active bool, code string, remaining time.Duration) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.pairing.active && time.Now().After(s.pairing.expires) {
		s.pairing = pairingState{}
	}
	if !s.pairing.active {
		return false, "", 0
	}
	return true, s.pairing.code, time.Until(s.pairing.expires)
}

func (s *Server) pairKey() ([]byte, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.pairing.active && time.Now().After(s.pairing.expires) {
		s.pairing = pairingState{}
	}
	if !s.pairing.active {
		return nil, false
	}
	return s.pairing.key, true
}

func (s *Server) pairFailed() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.pairing.active {
		s.pairing.fails++
		if s.pairing.fails >= pairMaxFails {
			s.pairing = pairingState{} // príliš veľa pokusov: okno sa zatvorí, treba ho otvoriť nanovo
			s.log.Printf("párovanie zatvorené: príliš veľa nesprávnych pokusov")
		}
	}
}

// ---- blokovanie po neúspešných pokusoch ----

func (s *Server) banned(ip net.IP) bool {
	if ip.IsLoopback() {
		return false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	b := s.bans[ip.String()]
	return b != nil && time.Now().Before(b.until)
}

func (s *Server) noteFail(ip net.IP) {
	if ip.IsLoopback() {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	k := ip.String()
	b := s.bans[k]
	now := time.Now()
	if b == nil || now.Sub(b.first) > failWindow {
		b = &banEntry{first: now}
		s.bans[k] = b
	}
	b.fails++
	if b.fails >= banAfterFails {
		b.until = now.Add(banDuration)
		s.log.Printf("adresa %s zablokovaná na %v (neúspešné pokusy)", k, banDuration)
	}
}

func (s *Server) noteOK(ip net.IP) {
	s.mu.Lock()
	delete(s.bans, ip.String())
	s.mu.Unlock()
}

// ---- príjem spojení ----

func (s *Server) Listen() error {
	ln, err := net.Listen("tcp", net.JoinHostPort("", strconv.Itoa(s.port)))
	if err != nil {
		return err
	}
	s.mu.Lock()
	s.ln = ln
	s.mu.Unlock()
	return nil
}

func (s *Server) Addr() net.Addr {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.ln == nil {
		return nil
	}
	return s.ln.Addr()
}

func (s *Server) Serve(ctx context.Context) {
	s.mu.Lock()
	ln := s.ln
	s.mu.Unlock()
	go func() {
		<-ctx.Done()
		ln.Close()
		s.mu.Lock()
		a := s.active
		s.mu.Unlock()
		if a != nil {
			a.close()
		}
	}()
	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				continue
			}
			s.log.Printf("accept: %v", err)
			time.Sleep(200 * time.Millisecond)
			continue
		}
		go s.handleConn(conn)
	}
}

func sendError(conn net.Conn, code byte, msg string) {
	_ = conn.SetWriteDeadline(time.Now().Add(2 * time.Second))
	_ = writeFrame(conn, append([]byte{hError, code}, msg...))
}

func (s *Server) handleConn(conn net.Conn) {
	defer conn.Close()
	ta, ok := conn.RemoteAddr().(*net.TCPAddr)
	if !ok || !isLANAddr(ta.IP) {
		s.log.Printf("odmietnuté spojenie z mimo domácej siete: %v", conn.RemoteAddr())
		return
	}
	if s.banned(ta.IP) {
		sendError(conn, errLockedOut, "príliš veľa pokusov")
		return
	}
	if s.handshaking.Add(1) > maxHandshakers {
		s.handshaking.Add(-1)
		return
	}
	released := false
	release := func() {
		if !released {
			released = true
			s.handshaking.Add(-1)
		}
	}
	defer release()
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
		_ = tc.SetKeepAlive(true)
		_ = tc.SetKeepAlivePeriod(15 * time.Second)
	}
	_ = conn.SetDeadline(time.Now().Add(handshakeLimit * time.Second))

	// 1. C_HELLO
	raw, err := readFrame(conn, maxHandshake)
	if err != nil {
		return
	}
	if len(raw) < 1+1+1+16+32+1 || raw[0] != hClientHello {
		sendError(conn, errProtocol, "neplatný úvod")
		s.noteFail(ta.IP)
		return
	}
	ver, mode := raw[1], raw[2]
	deviceID := raw[3:19]
	clientNonce := raw[19:51]
	nameLen := int(raw[51])
	if ver != protoVersion || len(raw) != 52+nameLen || nameLen > maxDeviceName || mode > 1 {
		sendError(conn, errProtocol, "nepodporovaná verzia")
		return
	}
	name := cleanName(string(raw[52:]))
	rawName := string(raw[52:]) // do dôkazu ide presne to, čo klient poslal

	var key []byte
	serverID := s.store.ServerID()
	if mode == 0 {
		_, k, found := s.store.Device(deviceID)
		if !found {
			sendError(conn, errUnknownDevice, "telefón nie je spárovaný")
			s.noteFail(ta.IP)
			return
		}
		key = k
	} else {
		k, active := s.pairKey()
		if !active {
			sendError(conn, errNoPairing, "párovanie nie je zapnuté")
			s.noteFail(ta.IP)
			return
		}
		key = k
	}

	// 2. S_HELLO
	serverNonce := randBytes(32)
	sp := handshakeProof(key, "server", mode, deviceID, clientNonce, serverNonce, serverID, rawName)
	hello := make([]byte, 0, 1+1+16+32+32)
	hello = append(hello, hServerHello, protoVersion)
	hello = append(hello, serverID...)
	hello = append(hello, serverNonce...)
	hello = append(hello, sp...)
	if err := writeFrame(conn, hello); err != nil {
		return
	}

	// 3. C_PROOF
	pf, err := readFrame(conn, maxHandshake)
	if err != nil || len(pf) != 33 || pf[0] != hClientProof {
		return
	}
	want := handshakeProof(key, "client", mode, deviceID, serverNonce, clientNonce, serverID, rawName)
	if !proofEqual(pf[1:], want) {
		sendError(conn, errBadProof, "nesprávny kľúč alebo kód")
		s.noteFail(ta.IP)
		if mode == 1 {
			s.pairFailed()
		}
		time.Sleep(300 * time.Millisecond) // spomalenie hádania
		return
	}
	s.noteOK(ta.IP)
	_ = conn.SetDeadline(time.Time{})

	c2s, s2c := sessionKeys(key, clientNonce, serverNonce)
	sc := newSecureConn(conn, c2s, s2c, true)
	sc.maxRecv = maxClientMsg + 64

	sess := newSession(s, sc, append([]byte(nil), deviceID...), name, ta.IP)
	if mode == 1 {
		// nové spárovanie: telefón dostane vlastný dlhodobý kľúč a okno párovania sa zatvorí
		devKey := randBytes(32)
		if err := s.store.AddDevice(deviceID, name, devKey); err != nil {
			s.log.Printf("uloženie telefónu zlyhalo: %v", err)
			return
		}
		s.CancelPairing()
		if err := sc.WriteMsg(mPaired, devKey); err != nil {
			return
		}
		s.log.Printf("spárovaný telefón „%s“ (%s)", name, ta.IP)
	} else {
		s.store.Touch(deviceID, ta.IP.String())
	}

	// nový telefón nahradí starú reláciu (stará mohla ostať "visieť" po strate WiFi)
	s.mu.Lock()
	old := s.active
	s.active = sess
	s.mu.Unlock()
	if old != nil {
		old.replaced()
		select {
		case <-old.done:
		case <-time.After(3 * time.Second):
		}
	}
	release()
	sess.run()
	s.mu.Lock()
	if s.active == sess {
		s.active = nil
	}
	s.mu.Unlock()
}

// ActiveInfo: kto je práve pripojený (pre stránku správy).
func (s *Server) ActiveInfo() (name, ip string, ok bool) {
	s.mu.Lock()
	a := s.active
	s.mu.Unlock()
	if a == nil {
		return "", "", false
	}
	return a.name, a.ip.String(), true
}

// ---- adresy PC ----

var virtualNames = []string{"vethernet", "virtualbox", "vmware", "vmnet", "docker", "veth", "br-", "hyper-v", "loopback", "bluetooth", "tailscale", "zerotier", "wsl", "isatap", "teredo", "npcap", "tap-"}
var virtualMACPrefixes = [][]byte{{0x00, 0x50, 0x56}, {0x00, 0x0C, 0x29}, {0x08, 0x00, 0x27}, {0x00, 0x15, 0x5D}, {0x0A, 0x00, 0x27}}

type lanIface struct {
	name string
	mac  net.HardwareAddr
	nets []*net.IPNet
	ips  []net.IP
}

func lanInterfaces() []lanIface {
	var out []lanIface
	ifs, _ := net.Interfaces()
	for _, ifc := range ifs {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		lname := strings.ToLower(ifc.Name)
		skip := false
		for _, v := range virtualNames {
			if strings.Contains(lname, v) {
				skip = true
			}
		}
		if skip {
			continue
		}
		addrs, _ := ifc.Addrs()
		li := lanIface{name: ifc.Name, mac: ifc.HardwareAddr}
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok {
				if v4 := ipn.IP.To4(); v4 != nil && !v4.IsLinkLocalUnicast() && v4.IsPrivate() {
					li.ips = append(li.ips, v4)
					li.nets = append(li.nets, &net.IPNet{IP: v4.Mask(ipn.Mask), Mask: ipn.Mask})
				}
			}
		}
		if len(li.ips) > 0 {
			out = append(out, li)
		}
	}
	return out
}

// lanIPs: súkromné IPv4 adresy PC (pre QR kód a stránku správy).
func lanIPs() []string {
	var out []string
	for _, li := range lanInterfaces() {
		for _, ip := range li.ips {
			out = append(out, ip.String())
		}
	}
	sort.Strings(out)
	return out
}

// localMACs: MAC adresy sieťových kariet PC; prvá je tá, ktorá je v rovnakej sieti ako [peer].
func localMACs(peer net.IP) [][]byte {
	var first, rest [][]byte
outer:
	for _, li := range lanInterfaces() {
		if len(li.mac) != 6 {
			continue
		}
		for _, p := range virtualMACPrefixes {
			if bytes.HasPrefix(li.mac, p) {
				continue outer
			}
		}
		same := false
		for _, n := range li.nets {
			if peer != nil && n.Contains(peer) {
				same = true
			}
		}
		if same {
			first = append(first, append([]byte(nil), li.mac...))
		} else {
			rest = append(rest, append([]byte(nil), li.mac...))
		}
	}
	return append(first, rest...)
}

// infoPayload zostaví správu mSessionInfo.
func (s *Server) infoPayload(peer net.IP, mons []Monitor, active int) []byte {
	var b []byte
	b = append(b, protoVersion)
	b = append(b, s.store.ServerID()...)
	name := s.store.Name()
	b = append(b, byte(len(name)))
	b = append(b, name...)
	macs := localMACs(peer)
	if len(macs) > 4 {
		macs = macs[:4]
	}
	b = append(b, byte(len(macs)))
	for _, m := range macs {
		b = append(b, m...)
	}
	var flags uint16
	if s.be.System.CanPower() {
		flags |= flagCanPower
	}
	if s.be.System.Elevated() {
		flags |= flagElevated
	}
	b = putU16(b, flags)
	b = append(b, byte(active))
	b = append(b, byte(len(mons)))
	for _, m := range mons {
		b = putU32(b, uint32(int32(m.X)))
		b = putU32(b, uint32(int32(m.Y)))
		b = putU32(b, uint32(m.W))
		b = putU32(b, uint32(m.H))
		var f byte
		if m.Primary {
			f = 1
		}
		b = append(b, f)
	}
	return b
}
