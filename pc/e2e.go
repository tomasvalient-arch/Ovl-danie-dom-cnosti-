package main

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

// Samotestovanie celej cesty (-e2e): spustí program s ostrým rozhraním k systému, spáruje referenčného klienta cez
// stránku správy, prijme obraz, pošle vstupy, prejde schránku a pripojí sa znova uloženým kľúčom.
// Na Windows ho spúšťa CI, aby sa overilo, že zachytávanie a vstupy fungujú aj v skutočnosti.

func runE2E(w io.Writer) error {
	p := func(format string, a ...any) { fmt.Fprintf(w, format+"\n", a...) }
	dir, err := os.MkdirTemp("", "domovpc-e2e")
	if err != nil {
		return err
	}
	defer os.RemoveAll(dir)
	lg := log.New(w, "[server] ", log.Ltime)
	be := newBackend()
	a, err := startApp(appOptions{dir: dir, port: 0, webPort: 0, be: be, log: lg, logPath: ""})
	if err != nil {
		return fmt.Errorf("štart programu: %w", err)
	}
	defer a.Close()
	addr := net.JoinHostPort("127.0.0.1", strconv.Itoa(a.srv.Port()))
	web := "http://" + a.web.Addr()
	p("server na %s, správa na %s", addr, web)

	// 1. párovanie cez stránku správy (rovnako ako človek)
	var st stateJSON
	if err := e2ePost(web+"/api/pair/start", nil, &st); err != nil {
		return fmt.Errorf("štart párovania: %w", err)
	}
	if !st.Pairing.Active || normalizeCode(st.Pairing.Code) == "" || st.Pairing.QR == "" {
		return fmt.Errorf("stránka nevrátila kód/QR: %+v", st.Pairing)
	}
	p("párovací kód %s, QR %d B, obsah %q", st.Pairing.Code, len(st.Pairing.QR), st.Pairing.Payload)
	if !strings.HasPrefix(st.Pairing.Payload, "domovpc:1:"+normalizeCode(st.Pairing.Code)+":") {
		return errors.New("obsah QR nesedí s kódom")
	}
	id := randBytes(16)
	c, err := tcConnect(addr, 1, id, pairKeyFromCode(st.Pairing.Code, a.store.ServerID()), "E2E telefón")
	if err != nil {
		return fmt.Errorf("párovanie: %w", err)
	}
	if _, err := c.wait(mPaired, 5*time.Second); err != nil {
		return err
	}
	key := append([]byte(nil), c.DeviceKey...)
	p("spárované, kľúč %d B", len(key))

	// 2. relácia: info, obraz, vstupy, schránka
	if err := e2eStream(c, p, be); err != nil {
		c.close()
		return err
	}
	c.close()

	// 3. nové pripojenie uloženým kľúčom
	time.Sleep(300 * time.Millisecond)
	c2, err := tcConnect(addr, 0, id, key, "E2E telefón")
	if err != nil {
		return fmt.Errorf("opätovné pripojenie: %w", err)
	}
	defer c2.close()
	if _, err := c2.wait(mSessionInfo, 5*time.Second); err != nil {
		return err
	}
	p("opätovné pripojenie uloženým kľúčom: OK")
	if err := e2ePost(web+"/api/state", nil, &st); err == nil {
		p("stránka správy: spárovaných telefónov %d", len(st.Devices))
	}
	return nil
}

func e2eStream(c *testClient, p func(string, ...any), be Backend) error {
	info, err := c.wait(mSessionInfo, 5*time.Second)
	if err != nil {
		return err
	}
	if len(info) < 20 {
		return errors.New("krátke SESSION_INFO")
	}
	scr, err := c.wait(mScreen, 5*time.Second)
	if err != nil {
		// obraz sa nepošle, kým je plocha zamknutá: nahlásiť, ale nepovažovať za chybu protokolu
		return fmt.Errorf("SCREEN: %w", err)
	}
	outW := int(binary.BigEndian.Uint32(scr[1:]))
	outH := int(binary.BigEndian.Uint32(scr[5:]))
	epoch := binary.BigEndian.Uint32(scr[18:])
	p("obraz %dx%d (delič %d)", outW, outH, scr[9])
	pl, err := c.wait(mRects, 10*time.Second)
	if err != nil {
		return fmt.Errorf("prvý obraz: %w", err)
	}
	ep, seq, rects, err := decodeRects(pl)
	if err != nil {
		return fmt.Errorf("prvý obraz: %w", err)
	}
	if ep != epoch || len(rects) == 0 || rects[0].w != outW {
		return fmt.Errorf("obraz nesedí so SCREEN: %v", rects)
	}
	p("prvý obraz: %d pásov, %d kB", len(rects), len(pl)/1024)
	_ = c.send(mAck, binary.BigEndian.AppendUint32(nil, seq))

	// pohyb myšou a klávesy bez následkov (Shift hore/dole)
	_ = c.send(mMoveRel, []byte{0, 1, 0, 1})
	_ = c.send(mKey, []byte{0, 0x10, 1})
	_ = c.send(mKey, []byte{0, 0x10, 0})
	_ = c.send(mPing, []byte{9, 9, 9, 9, 9, 9, 9, 9})
	if _, err := c.wait(mPong, 5*time.Second); err != nil {
		return fmt.Errorf("PONG: %w", err)
	}
	p("ping/pong: OK")

	// schránka PC cez protokol
	msg := "e2e ľščťžýáíé 😀"
	_ = c.send(mClipSet, []byte(msg))
	_ = c.send(mClipGet)
	got, err := c.wait(mClip, 5*time.Second)
	if err != nil || string(got) != msg {
		return fmt.Errorf("schránka: %q %v", got, err)
	}
	p("schránka cez protokol: OK")
	return nil
}

func e2ePost(url string, in any, out any) error {
	var body io.Reader = bytes.NewReader([]byte("{}"))
	if in != nil {
		b, _ := json.Marshal(in)
		body = bytes.NewReader(b)
	}
	method := http.MethodPost
	if strings.HasSuffix(url, "/api/state") {
		method = http.MethodGet
	}
	req, _ := http.NewRequest(method, url, body)
	req.Header.Set("X-Domov-PC", "1")
	resp, err := (&http.Client{Timeout: 10 * time.Second}).Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("HTTP %d: %s", resp.StatusCode, b)
	}
	return json.NewDecoder(resp.Body).Decode(out)
}
