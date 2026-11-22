package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// Stránka správy na PC: beží len na 127.0.0.1, takže ju vidí len ten, kto sedí pri PC. Slúži na zobrazenie
// párovacieho kódu / QR, správu spárovaných telefónov a základné nastavenia.

type webServer struct {
	app *app
	ln  net.Listener
	srv *http.Server
}

func newWebServer(a *app, port int) (*webServer, error) {
	ln, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		return nil, err
	}
	w := &webServer{app: a, ln: ln}
	mux := http.NewServeMux()
	mux.HandleFunc("/", w.page)
	mux.HandleFunc("/api/state", w.state)
	mux.HandleFunc("/api/pair/start", w.post(w.pairStart))
	mux.HandleFunc("/api/pair/cancel", w.post(w.pairCancel))
	mux.HandleFunc("/api/device/remove", w.post(w.deviceRemove))
	mux.HandleFunc("/api/rename", w.post(w.rename))
	mux.HandleFunc("/api/autostart", w.post(w.autostart))
	mux.HandleFunc("/api/selftest", w.post(w.selftest))
	mux.HandleFunc("/api/quit", w.post(w.quit))
	w.srv = &http.Server{Handler: w.guard(mux), ReadHeaderTimeout: 5 * time.Second}
	return w, nil
}

func (w *webServer) Addr() string { return w.ln.Addr().String() }
func (w *webServer) Serve()       { _ = w.srv.Serve(w.ln) }
func (w *webServer) Close() {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_ = w.srv.Shutdown(ctx)
}

// guard: len požiadavky pre 127.0.0.1 / localhost (ochrana pred DNS rebindingom z webových stránok).
func (w *webServer) guard(next http.Handler) http.Handler {
	return http.HandlerFunc(func(rw http.ResponseWriter, r *http.Request) {
		host, _, err := net.SplitHostPort(r.Host)
		if err != nil {
			host = r.Host
		}
		if host != "127.0.0.1" && host != "localhost" {
			http.Error(rw, "zakázané", http.StatusForbidden)
			return
		}
		rw.Header().Set("Cache-Control", "no-store")
		rw.Header().Set("X-Content-Type-Options", "nosniff")
		rw.Header().Set("Content-Security-Policy", "default-src 'self' 'unsafe-inline'; img-src 'self' data:")
		next.ServeHTTP(rw, r)
	})
}

// post: len POST s hlavičkou X-Domov-PC (jednoduché formuláre z cudzích stránok ju poslať nevedia).
func (w *webServer) post(h func(*http.Request) (any, error)) http.HandlerFunc {
	return func(rw http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.Header.Get("X-Domov-PC") != "1" {
			http.Error(rw, "zakázané", http.StatusForbidden)
			return
		}
		r.Body = http.MaxBytesReader(rw, r.Body, 4096)
		v, err := h(r)
		rw.Header().Set("Content-Type", "application/json; charset=utf-8")
		if err != nil {
			rw.WriteHeader(http.StatusBadRequest)
			_ = json.NewEncoder(rw).Encode(map[string]string{"error": err.Error()})
			return
		}
		_ = json.NewEncoder(rw).Encode(v)
	}
}

func (w *webServer) page(rw http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(rw, r)
		return
	}
	rw.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = io.WriteString(rw, pageHTML)
}

type pairingJSON struct {
	Active    bool   `json:"active"`
	Code      string `json:"code,omitempty"`
	Remaining int    `json:"remaining,omitempty"`
	QR        string `json:"qr,omitempty"`
	Payload   string `json:"payload,omitempty"`
}

type deviceJSON struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	LastSeen int64  `json:"lastSeen"`
	LastIP   string `json:"lastIp"`
}

type stateJSON struct {
	Version      string       `json:"version"`
	Name         string       `json:"name"`
	Port         int          `json:"port"`
	IPs          []string     `json:"ips"`
	Pairing      pairingJSON  `json:"pairing"`
	Devices      []deviceJSON `json:"devices"`
	SessionName  string       `json:"sessionName,omitempty"`
	SessionIP    string       `json:"sessionIp,omitempty"`
	Autostart    bool         `json:"autostart"`
	CanAutostart bool         `json:"canAutostart"`
	Elevated     bool         `json:"elevated"`
	Desktop      bool         `json:"desktop"`
	LogPath      string       `json:"logPath"`
	Uptime       int          `json:"uptime"`
}

// pairPayload: obsah QR kódu: domovpc:1:<kód>:<ID PC hex>:<IP,IP>:<port>
func (a *app) pairPayload(code string) string {
	ips := lanIPs()
	if len(ips) > 3 {
		ips = ips[:3]
	}
	return fmt.Sprintf("domovpc:1:%s:%x:%s:%d", code, a.store.ServerID(), strings.Join(ips, ","), a.srv.Port())
}

func (w *webServer) buildState() stateJSON {
	a := w.app
	st := stateJSON{
		Version: appVersion, Name: a.store.Name(), Port: a.srv.Port(), IPs: lanIPs(),
		Autostart: autostartEnabled(), CanAutostart: canAutostart(),
		Elevated: a.be.System.Elevated(), Desktop: a.be.System.DesktopAvailable(),
		LogPath: a.logPath, Uptime: int(time.Since(a.srv.started).Seconds()),
		Devices: []deviceJSON{},
	}
	if active, code, rem := a.srv.PairingInfo(); active {
		p := a.pairPayload(code)
		st.Pairing = pairingJSON{Active: true, Code: formatCode(code), Remaining: int(rem.Seconds()), Payload: p}
		if m, err := qrEncode([]byte(p)); err == nil {
			st.Pairing.QR = qrSVG(m)
		}
	}
	for _, d := range a.store.Devices() {
		st.Devices = append(st.Devices, deviceJSON{ID: d.ID, Name: d.Name, LastSeen: d.LastSeen, LastIP: d.LastIP})
	}
	if n, ip, ok := a.srv.ActiveInfo(); ok {
		st.SessionName, st.SessionIP = n, ip
	}
	return st
}

func (w *webServer) state(rw http.ResponseWriter, r *http.Request) {
	rw.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(rw).Encode(w.buildState())
}

func (w *webServer) pairStart(r *http.Request) (any, error) {
	w.app.srv.StartPairing()
	return w.buildState(), nil
}

func (w *webServer) pairCancel(r *http.Request) (any, error) {
	w.app.srv.CancelPairing()
	return w.buildState(), nil
}

func readJSON(r *http.Request, v any) error {
	return json.NewDecoder(r.Body).Decode(v)
}

func (w *webServer) deviceRemove(r *http.Request) (any, error) {
	var in struct{ ID string }
	if err := readJSON(r, &in); err != nil || in.ID == "" {
		return nil, errors.New("chýba id")
	}
	if err := w.app.store.RemoveDevice(in.ID); err != nil {
		return nil, err
	}
	return w.buildState(), nil
}

func (w *webServer) rename(r *http.Request) (any, error) {
	var in struct{ Name string }
	if err := readJSON(r, &in); err != nil || cleanName(in.Name) == "" {
		return nil, errors.New("neplatný názov")
	}
	if err := w.app.store.SetName(in.Name); err != nil {
		return nil, err
	}
	return w.buildState(), nil
}

func (w *webServer) autostart(r *http.Request) (any, error) {
	var in struct{ Enable bool }
	if err := readJSON(r, &in); err != nil {
		return nil, err
	}
	if err := setAutostart(in.Enable); err != nil {
		return nil, err
	}
	return w.buildState(), nil
}

func (w *webServer) selftest(r *http.Request) (any, error) {
	var sb strings.Builder
	ok := runSelfTest(w.app.be, &sb, false)
	return map[string]any{"ok": ok, "text": sb.String()}, nil
}

func (w *webServer) quit(r *http.Request) (any, error) {
	go func() {
		time.Sleep(200 * time.Millisecond)
		w.app.requestQuit()
	}()
	return map[string]bool{"ok": true}, nil
}
