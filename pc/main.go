package main

import (
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"syscall"
	"time"
)

func setupLogging() (*log.Logger, string) {
	dir := dataDir()
	_ = os.MkdirAll(dir, 0o700)
	path := filepath.Join(dir, "domov-pc.log")
	if st, err := os.Stat(path); err == nil && st.Size() > 1<<20 {
		_ = os.Remove(path + ".old")
		_ = os.Rename(path, path+".old")
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	var w io.Writer = os.Stderr
	if err == nil {
		w = io.MultiWriter(f, os.Stderr)
	}
	return log.New(w, "", log.Ldate|log.Ltime), path
}

func main() {
	port := flag.Int("port", defaultPort, "port pre telefón (TCP ovládanie, UDP hľadanie)")
	webPort := flag.Int("web-port", defaultWebPort, "port stránky správy (len 127.0.0.1)")
	background := flag.Bool("background", false, "spustené po prihlásení do Windows: neotváraj prehliadač")
	selftest := flag.Bool("selftest", false, "otestuj zachytenie obrazovky, myš a schránku a skonči")
	e2e := flag.Bool("e2e", false, "otestuj celú cestu (párovanie, obraz, vstupy) a skonči")
	flag.BoolVar(&traceInput, "trace-input", false, "vývoj: falošné rozhranie vypisuje prijaté vstupy (len mimo Windows)")
	flag.Parse()

	if *selftest {
		be := newBackend()
		if runSelfTest(be, os.Stdout, true) {
			fmt.Println("SAMOTEST: OK")
			return
		}
		fmt.Println("SAMOTEST: ZLYHAL")
		os.Exit(1)
	}
	if *e2e {
		if err := runE2E(os.Stdout); err != nil {
			fmt.Println("E2E: ZLYHAL:", err)
			os.Exit(1)
		}
		fmt.Println("E2E: OK")
		return
	}

	lg, logPath := setupLogging()
	a, err := startApp(appOptions{dir: dataDir(), port: *port, webPort: *webPort, be: newBackend(), log: lg, logPath: logPath})
	if err != nil {
		// už beží iná kópia? potom len ukáž jej stránku
		url := "http://127.0.0.1:" + strconv.Itoa(*webPort) + "/"
		if resp, e := (&http.Client{Timeout: 2 * time.Second}).Get(url + "api/state"); e == nil {
			resp.Body.Close()
			lg.Printf("program už beží, otváram stránku správy")
			if !*background {
				openBrowser(url)
			}
			return
		}
		lg.Printf("štart zlyhal: %v", err)
		os.Exit(1)
	}
	if !*background {
		openBrowser("http://" + a.web.Addr() + "/")
	}
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	select {
	case <-sig:
	case <-a.quit:
	}
	lg.Printf("ukončujem")
	a.Close()
}
