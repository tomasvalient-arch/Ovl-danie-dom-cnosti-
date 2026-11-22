package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/hex"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"
)

type testEnv struct {
	t      *testing.T
	srv    *Server
	store  *Store
	screen *stubScreen
	input  *stubInput
	sys    *stubSystem
	addr   string
	cancel context.CancelFunc
}

func newTestEnv(t *testing.T) *testEnv {
	t.Helper()
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	be, sc, in, sy := newStubBackend()
	srv := NewServer(store, be, 0, nil)
	if err := srv.Listen(); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	go srv.Serve(ctx)
	t.Cleanup(cancel)
	return &testEnv{t: t, srv: srv, store: store, screen: sc, input: in, sys: sy,
		addr: net.JoinHostPort("127.0.0.1", itoaPort(srv.Port())), cancel: cancel}
}

func itoaPort(p int) string { return strconv.Itoa(p) }

// pairNew spáruje nový telefón a vráti jeho ID a kľúč.
func (e *testEnv) pairNew(name string) (id, key []byte, c *testClient) {
	e.t.Helper()
	code, _ := e.srv.StartPairing()
	id = randBytes(16)
	pk := pairKeyFromCode(code, e.store.ServerID())
	c, err := tcConnect(e.addr, 1, id, pk, name)
	if err != nil {
		e.t.Fatalf("párovanie: %v", err)
	}
	if _, err := c.wait(mPaired, 3*time.Second); err != nil {
		e.t.Fatalf("PAIRED: %v", err)
	}
	if len(c.DeviceKey) != 32 {
		e.t.Fatalf("kľúč zariadenia má %d bajtov", len(c.DeviceKey))
	}
	return id, c.DeviceKey, c
}

func TestPBKDF2Vector(t *testing.T) {
	// RFC 7914, oddiel 11: PBKDF2-HMAC-SHA-256, P="passwd", S="salt", c=1, dkLen=64
	got := hex.EncodeToString(pbkdf2SHA256([]byte("passwd"), []byte("salt"), 1, 64))
	want := "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"
	if got != want {
		t.Fatalf("PBKDF2 nesedí:\n%s\n%s", got, want)
	}
}

func TestCodeNormalize(t *testing.T) {
	if normalizeCode("abcde-fghjk") != "ABCDEFGHJK" {
		t.Fatal("normalizácia")
	}
	if normalizeCode("ABCDE-FGHJ") != "" || normalizeCode("ABCDE-FGHJ0") != "" || normalizeCode("ABCDE-FGHIK") != "" {
		t.Fatal("neplatné kódy musia prejsť na prázdny reťazec")
	}
	c := newPairCode()
	if normalizeCode(c) != c {
		t.Fatalf("vygenerovaný kód %q nie je platný", c)
	}
}

func TestLANAddr(t *testing.T) {
	yes := []string{"192.168.1.5", "10.0.0.1", "172.16.5.5", "172.31.255.1", "169.254.1.1", "127.0.0.1", "::1", "fe80::1", "fd12:3456::1"}
	no := []string{"8.8.8.8", "1.1.1.1", "100.64.0.1", "172.32.0.1", "2001:4860:4860::8888", "193.0.0.1"}
	for _, s := range yes {
		if !isLANAddr(net.ParseIP(s)) {
			t.Errorf("%s má byť domáca sieť", s)
		}
	}
	for _, s := range no {
		if isLANAddr(net.ParseIP(s)) {
			t.Errorf("%s nemá byť domáca sieť", s)
		}
	}
}

func TestSessionKeysDirectional(t *testing.T) {
	k := randBytes(32)
	a, b := sessionKeys(k, randBytes(32), randBytes(32))
	if bytes.Equal(a, b) {
		t.Fatal("kľúče smerov sa nesmú rovnať")
	}
}

func TestPairingRequiresWindow(t *testing.T) {
	e := newTestEnv(t)
	_, err := tcConnect(e.addr, 1, randBytes(16), randBytes(32), "X")
	if he, ok := err.(hsError); !ok || he.code != errNoPairing {
		t.Fatalf("čakal som errNoPairing, je %v", err)
	}
}

func TestUnknownDevice(t *testing.T) {
	e := newTestEnv(t)
	_, err := tcConnect(e.addr, 0, randBytes(16), randBytes(32), "X")
	if he, ok := err.(hsError); !ok || he.code != errUnknownDevice {
		t.Fatalf("čakal som errUnknownDevice, je %v", err)
	}
}

func TestWrongCodeAndLockout(t *testing.T) {
	e := newTestEnv(t)
	code, _ := e.srv.StartPairing()
	wrong := "AAAAABBBBB"
	if wrong == code {
		wrong = "CCCCCDDDDD"
	}
	for i := 0; i < pairMaxFails; i++ {
		_, err := tcConnectOpt(e.addr, 1, randBytes(16), pairKeyFromCode(wrong, e.store.ServerID()), "X", false)
		if he, ok := err.(hsError); !ok || he.code != errBadProof {
			t.Fatalf("čakal som errBadProof, je %v", err)
		}
	}
	// okno sa zatvorilo, ani správny kód už neprejde
	if active, _, _ := e.srv.PairingInfo(); active {
		t.Fatal("okno párovania malo byť zatvorené po príliš veľa pokusoch")
	}
	_, err := tcConnect(e.addr, 1, randBytes(16), pairKeyFromCode(code, e.store.ServerID()), "X")
	if he, ok := err.(hsError); !ok || he.code != errNoPairing {
		t.Fatalf("čakal som errNoPairing, je %v", err)
	}
}

func TestClientRejectsServerWithWrongKey(t *testing.T) {
	e := newTestEnv(t)
	e.srv.StartPairing()
	_, err := tcConnect(e.addr, 1, randBytes(16), randBytes(32), "X")
	if err == nil || !strings.Contains(err.Error(), "nevie preukázať") {
		t.Fatalf("klient mal odmietnuť server s iným kľúčom: %v", err)
	}
}

func TestPairThenReconnectAndStream(t *testing.T) {
	e := newTestEnv(t)
	id, key, c := e.pairNew("Môj telefón ľščťžýáíé")

	// SESSION_INFO
	info, err := c.wait(mSessionInfo, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if info[0] != protoVersion || !bytes.Equal(info[1:17], e.store.ServerID()) {
		t.Fatal("SESSION_INFO: zlé ID PC")
	}
	// SCREEN
	scr, err := c.wait(mScreen, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if scr[0] != 0 || binary.BigEndian.Uint32(scr[1:]) != 640 || binary.BigEndian.Uint32(scr[5:]) != 360 || scr[9] != 1 {
		t.Fatalf("SCREEN: %v", scr)
	}
	epoch := binary.BigEndian.Uint32(scr[18:])
	// prvý obraz = všetky pásy (360 / 64 = 6 pásov)
	p, err := c.wait(mRects, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	ep, seq, rects, err := decodeRects(p)
	if err != nil {
		t.Fatal(err)
	}
	if ep != epoch || len(rects) != 6 || rects[0].w != 640 || rects[5].y != 320 || rects[5].h != 40 {
		t.Fatalf("prvý obraz: epoch=%d rects=%v", ep, rects)
	}
	// bez ACK a bez zmeny sa nič nepošle; po zmene obsahu prídu len zmenené pásy (štvorec je v riadkoch 100–140 = pásy 1 a 2)
	_ = c.send(mAck, binary.BigEndian.AppendUint32(nil, seq))
	e.screen.SetTick(3)
	p, err = c.wait(mRects, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	_, seq2, rects, _ := decodeRects(p)
	if len(rects) != 2 || rects[0].y != 64 || rects[1].y != 128 {
		t.Fatalf("zmenené pásy: %v", rects)
	}
	_ = c.send(mAck, binary.BigEndian.AppendUint32(nil, seq2))

	// vstupy
	_ = c.send(mMoveRel, []byte{0, 3, 0xFF, 0xFC}) // +3, -4
	_ = c.send(mButton, []byte{1, 1})
	_ = c.send(mButton, []byte{1, 0})
	_ = c.send(mWheel, []byte{0, 0, 0, 120})
	_ = c.send(mKey, []byte{0, 0x11, 1}) // Ctrl dole – po odpojení sa musí uvoľniť
	_ = c.send(mText, []byte("Ľščť"))
	_ = c.send(mMoveAbs, []byte{0x80, 0x00, 0x00, 0x00})
	_ = c.send(mPing, []byte{1, 2, 3, 4, 5, 6, 7, 8})
	if pong, err := c.wait(mPong, 3*time.Second); err != nil || !bytes.Equal(pong, []byte{1, 2, 3, 4, 5, 6, 7, 8}) {
		t.Fatalf("PONG: %v %v", pong, err)
	}
	want := []string{"rel:3,-4", "btn:1:true", "btn:1:false", "wheel:0,120", "key:17:true", "text:Ľščť", "abs:0.50,0.00@0"}
	got := e.input.Events()
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("vstupy:\n got %v\nwant %v", got, want)
	}

	// schránka
	_ = c.send(mClipSet, []byte("Ahoj ľščťžýáíé 😀"))
	_ = c.send(mClipGet)
	clip, err := c.wait(mClip, 3*time.Second)
	if err != nil || string(clip) != "Ahoj ľščťžýáíé 😀" {
		t.Fatalf("schránka: %q %v", clip, err)
	}

	// odpojenie uvoľní podržané klávesy
	c.close()
	deadline := time.Now().Add(3 * time.Second)
	for {
		ev := e.input.Events()
		if len(ev) > 0 && ev[len(ev)-1] == "key:17:false" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("Ctrl sa po odpojení neuvoľnil: %v", ev)
		}
		time.Sleep(20 * time.Millisecond)
	}

	// opätovné pripojenie uloženým kľúčom
	c2, err := tcConnect(e.addr, 0, id, key, "Môj telefón")
	if err != nil {
		t.Fatalf("pripojenie spárovaným telefónom: %v", err)
	}
	if _, err := c2.wait(mRects, 3*time.Second); err != nil {
		t.Fatal(err)
	}
	c2.close()
	// zlý kľúč
	if _, err := tcConnect(e.addr, 0, id, randBytes(32), "x"); err == nil {
		t.Fatal("zlý kľúč nesmie prejsť")
	}
	// odstránenie telefónu
	_ = e.store.RemoveDevice(hex.EncodeToString(id))
	if _, err := tcConnect(e.addr, 0, id, key, "x"); err == nil {
		t.Fatal("odstránený telefón nesmie prejsť")
	}
}

func TestMonitorSwitchAndDivisor(t *testing.T) {
	e := newTestEnv(t)
	_, _, c := e.pairNew("T")
	if _, err := c.wait(mRects, 3*time.Second); err != nil {
		t.Fatal(err)
	}
	// druhý monitor 320x240
	_ = c.send(mConfig, []byte{15, 70, 0, 1})
	scr, err := c.wait(mScreen, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if scr[0] != 1 || binary.BigEndian.Uint32(scr[1:]) != 320 || binary.BigEndian.Uint32(scr[5:]) != 240 {
		t.Fatalf("SCREEN po prepnutí: %v", scr)
	}
	p, err := c.wait(mRects, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, rects, err := decodeRects(p); err != nil || rects[0].w != 320 {
		t.Fatalf("obraz druhého monitora: %v %v", rects, err)
	}
	// zmenšenie na polovicu: 640x360 -> 320x180; relatívny pohyb sa násobí deličom
	_ = c.send(mConfig, []byte{15, 70, 2, 0})
	scr, err = c.wait(mScreen, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if binary.BigEndian.Uint32(scr[1:]) != 320 || binary.BigEndian.Uint32(scr[5:]) != 180 || scr[9] != 2 {
		t.Fatalf("SCREEN po zmenšení: %v", scr)
	}
	_ = c.send(mMoveRel, []byte{0, 5, 0, 1})
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		ev := e.input.Events()
		if len(ev) > 0 {
			if ev[len(ev)-1] != "rel:10,2" {
				t.Fatalf("pohyb má byť vynásobený deličom: %v", ev)
			}
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("pohyb nepríšiel")
}

func TestLockedDesktopNotice(t *testing.T) {
	e := newTestEnv(t)
	_, _, c := e.pairNew("T")
	p, err := c.wait(mRects, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	_, seq, _, _ := decodeRects(p)
	_ = c.send(mAck, binary.BigEndian.AppendUint32(nil, seq))
	e.sys.SetLocked(true)
	n, err := c.wait(mNotice, 3*time.Second)
	if err != nil || n[0] != noticeDesktopLocked {
		t.Fatalf("upozornenie na zamknutý PC: %v %v", n, err)
	}
	e.sys.SetLocked(false)
	n, err = c.wait(mNotice, 3*time.Second)
	if err != nil || n[0] != noticeDesktopOK {
		t.Fatalf("upozornenie na odomknutie: %v %v", n, err)
	}
	// po odomknutí príde celý obraz nanovo
	p, err = c.wait(mRects, 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, rects, _ := decodeRects(p); len(rects) != 6 {
		t.Fatalf("po odomknutí má prísť celý obraz, prišlo %d pásov", len(rects))
	}
}

func TestCaptureErrorRecovers(t *testing.T) {
	e := newTestEnv(t)
	_, _, c := e.pairNew("T")
	p, _ := c.wait(mRects, 3*time.Second)
	_, seq, _, _ := decodeRects(p)
	_ = c.send(mAck, binary.BigEndian.AppendUint32(nil, seq))
	e.screen.SetFail(true)
	n, err := c.wait(mNotice, 5*time.Second)
	if err != nil || n[0] != noticeCaptureError {
		t.Fatalf("upozornenie na chybu zachytenia: %v %v", n, err)
	}
	e.screen.SetFail(false)
	n, err = c.wait(mNotice, 5*time.Second)
	if err != nil || n[0] != noticeDesktopOK {
		t.Fatalf("upozornenie na obnovu: %v %v", n, err)
	}
}

func TestTakeover(t *testing.T) {
	e := newTestEnv(t)
	id, key, c1 := e.pairNew("A")
	if _, err := c1.wait(mRects, 3*time.Second); err != nil {
		t.Fatal(err)
	}
	c2, err := tcConnect(e.addr, 0, id, key, "A")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := c2.wait(mRects, 3*time.Second); err != nil {
		t.Fatalf("nová relácia nedostala obraz: %v", err)
	}
	// stará relácia dostane upozornenie a zatvorí sa
	n, err := c1.wait(mNotice, 3*time.Second)
	if err != nil || n[0] != noticeReplaced {
		t.Fatalf("stará relácia mala dostať upozornenie: %v %v", n, err)
	}
	if _, err := c1.wait(mRects, time.Second); err == nil || !strings.Contains(err.Error(), "zatvorené") {
		t.Fatalf("stará relácia sa mala zatvoriť: %v", err)
	}
}

func TestPowerCommand(t *testing.T) {
	e := newTestEnv(t)
	_, _, c := e.pairNew("T")
	_ = c.send(mPower, []byte{powerLock})
	n, err := c.wait(mNotice, 3*time.Second)
	if err != nil || n[0] != noticePowerAccepted {
		t.Fatalf("potvrdenie napájania: %v %v", n, err)
	}
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if l := e.sys.PowerLog(); len(l) == 1 && l[0] == powerLock {
			// neplatná akcia sa ignoruje
			_ = c.send(mPower, []byte{99})
			time.Sleep(100 * time.Millisecond)
			if len(e.sys.PowerLog()) != 1 {
				t.Fatal("neplatná akcia napájania sa nesmie vykonať")
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("príkaz napájania sa nevykonal")
}

func TestBanAfterFailures(t *testing.T) {
	e := newTestEnv(t)
	ip := net.ParseIP("192.168.1.77")
	for i := 0; i < banAfterFails; i++ {
		if e.srv.banned(ip) {
			t.Fatalf("blokované už po %d pokusoch", i)
		}
		e.srv.noteFail(ip)
	}
	if !e.srv.banned(ip) {
		t.Fatal("po viacerých pokusoch mala byť adresa blokovaná")
	}
	e.srv.noteOK(ip)
	if e.srv.banned(ip) {
		t.Fatal("úspešné prihlásenie má blokovanie zrušiť")
	}
	if e.srv.banned(net.ParseIP("127.0.0.1")) {
		t.Fatal("loopback sa neblokuje")
	}
}

func TestDiscovery(t *testing.T) {
	pc, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Skip(err)
	}
	port := pc.LocalAddr().(*net.UDPAddr).Port
	pc.Close()
	store, _ := OpenStore(t.TempDir())
	be, _, _, _ := newStubBackend()
	srv := NewServer(store, be, port, nil)
	if err := srv.Listen(); err != nil {
		t.Skip(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go srv.Serve(ctx)
	go srv.ServeDiscovery(ctx)
	time.Sleep(100 * time.Millisecond)
	conn, err := net.Dial("udp4", net.JoinHostPort("127.0.0.1", itoaPort(port)))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
	_, _ = conn.Write(append([]byte("DOMOVPC?"), 1))
	buf := make([]byte, 128)
	n, err := conn.Read(buf)
	if err != nil {
		t.Fatal(err)
	}
	r := buf[:n]
	name := store.Name()
	if !bytes.HasPrefix(r, []byte("DOMOVPC!")) || r[8] != protoVersion || !bytes.Equal(r[9:25], store.ServerID()) ||
		int(binary.BigEndian.Uint16(r[25:])) != port || int(r[27]) != len(name) || string(r[28:]) != name {
		t.Fatalf("odpoveď hľadania: %q", r)
	}
}

func TestStorePersists(t *testing.T) {
	dir := t.TempDir()
	s1, _ := OpenStore(dir)
	id, key := randBytes(16), randBytes(32)
	if err := s1.AddDevice(id, "  Telefón\x00\n", key); err != nil {
		t.Fatal(err)
	}
	s2, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(s1.ServerID(), s2.ServerID()) {
		t.Fatal("ID PC sa musí zachovať")
	}
	rec, k, ok := s2.Device(id)
	if !ok || !bytes.Equal(k, key) || rec.Name != "Telefón" {
		t.Fatalf("zariadenie: %+v ok=%v", rec, ok)
	}
	for _, d := range s2.Devices() {
		if d.Key != "" {
			t.Fatal("zoznam zariadení nesmie obsahovať kľúče")
		}
	}
}

func TestCleanName(t *testing.T) {
	long := strings.Repeat("ľ", 100)
	if n := cleanName(long); len(n) > maxDeviceName || !strings.HasPrefix(long, n) {
		t.Fatalf("dlhý názov: %d bajtov", len(n))
	}
	if cleanName("a\x00b\x1f") != "ab" {
		t.Fatal("riadiace znaky")
	}
}

func TestEncoderDividerAndDiff(t *testing.T) {
	w, h := 300, 200
	src := make([]byte, w*h*4)
	for i := range src {
		src[i] = byte(i)
	}
	e := newEncoder(w, h, 2)
	if e.outW != 150 || e.outH != 100 {
		t.Fatalf("výstup %dx%d", e.outW, e.outH)
	}
	r := e.encode(src, 70, false)
	if len(r) != 2 { // 100 riadkov = pás 64 + pás 36
		t.Fatalf("pásov %d", len(r))
	}
	if again := e.encode(src, 70, false); len(again) != 0 {
		t.Fatal("nezmenený obraz sa nesmie posielať")
	}
	src[(150*w+10)*4] ^= 0xFF // zmena v zdrojovom riadku 150 = výstupný riadok 75 = pás 1
	r = e.encode(src, 70, false)
	if len(r) != 1 || r[0].y != 64 || r[0].h != 36 {
		t.Fatalf("po zmene: %+v", r)
	}
	if len(e.encode(src, 70, true)) != 2 {
		t.Fatal("force má poslať všetko")
	}
}

func TestCursorReports(t *testing.T) {
	e := newTestEnv(t)
	_, _, c := e.pairNew("K")
	// čakáme na správu s polohou, kým nezodpovedá požadovanej (prvé môžu byť staršie)
	waitCursor := func(wantX, wantY, wantVis int) {
		t.Helper()
		deadline := time.Now().Add(3 * time.Second)
		var last []byte
		for time.Now().Before(deadline) {
			p, err := c.wait(mCursor, time.Second)
			if err != nil {
				break
			}
			last = p
			x, y, v := int(p[0])<<8|int(p[1]), int(p[2])<<8|int(p[3]), int(p[4])
			if abs(x-wantX) <= 110 && abs(y-wantY) <= 190 && v == wantVis { // jeden pixel falošného monitora 640x360 je ~100–180 jednotiek
				return
			}
		}
		t.Fatalf("poloha kurzora nedorazila (chcem %d,%d,%d, posledná %v)", wantX, wantY, wantVis, last)
	}
	_ = c.send(mMoveAbs, []byte{0x80, 0x00, 0x40, 0x00}) // 32768, 16384
	waitCursor(32768, 16384, 1)
	_ = c.send(mMoveAbs, []byte{0xFF, 0xFF, 0xFF, 0xFF})
	waitCursor(65535, 65535, 1)
	// kurzor mimo aktívneho monitora: poloha sa pritlačí na okraj a príznak je 0
	e.input.mu.Lock()
	e.input.cx, e.input.cy = 5000, -300
	e.input.mu.Unlock()
	waitCursor(65535, 0, 0)
	// druhý monitor: rovnaký bod virtuálnej plochy je tam inde
	mon, vis := cursorFractionTest()
	if mon != 0 || vis != 1 {
		t.Fatalf("cursorFraction: %d %d", mon, vis)
	}
}

func abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

func cursorFractionTest() (int, int) {
	m := Monitor{X: -1920, Y: 0, W: 1920, H: 1080}
	x, y, vis := cursorFraction(m, -1920, 0)
	if x != 0 || y != 0 {
		return 1, vis
	}
	x, y, vis = cursorFraction(m, -1, 1079)
	if x != 65535 || y != 65535 {
		return 2, vis
	}
	_, _, vis2 := cursorFraction(m, 0, 0)
	if vis2 != 0 {
		return 3, vis
	}
	return 0, vis
}
