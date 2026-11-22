package main

import (
	"context"
	"log"
	"sync"
)

// app spája súčasti programu: server pre telefón, hľadanie v sieti a stránku správy.

type appOptions struct {
	dir     string
	port    int
	webPort int
	be      Backend
	log     *log.Logger
	logPath string
}

type app struct {
	store   *Store
	srv     *Server
	web     *webServer
	be      Backend
	log     *log.Logger
	logPath string
	cancel  context.CancelFunc

	quit     chan struct{}
	quitOnce sync.Once
}

func startApp(o appOptions) (*app, error) {
	store, err := OpenStore(o.dir)
	if err != nil {
		return nil, err
	}
	srv := NewServer(store, o.be, o.port, o.log)
	if err := srv.Listen(); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	a := &app{store: store, srv: srv, be: o.be, log: o.log, logPath: o.logPath, cancel: cancel, quit: make(chan struct{})}
	web, err := newWebServer(a, o.webPort)
	if err != nil {
		cancel()
		if a.srv.ln != nil {
			a.srv.ln.Close()
		}
		return nil, err
	}
	a.web = web
	go srv.Serve(ctx)
	go func() {
		if err := srv.ServeDiscovery(ctx); err != nil {
			o.log.Printf("hľadanie v sieti (UDP) nebeží: %v", err)
		}
	}()
	go web.Serve()
	o.log.Printf("Domov PC %s beží: telefón port %d, správa http://%s/", appVersion, srv.Port(), web.Addr())
	return a, nil
}

func (a *app) requestQuit() { a.quitOnce.Do(func() { close(a.quit) }) }

func (a *app) Close() {
	a.cancel()
	if a.web != nil {
		a.web.Close()
	}
	a.be.Screen.Close()
}
