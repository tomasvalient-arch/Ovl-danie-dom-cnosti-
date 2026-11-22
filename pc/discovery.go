package main

import (
	"context"
	"net"
	"strconv"
	"time"
)

// Hľadanie PC v sieti: telefón pošle UDP "DOMOVPC?" (broadcast alebo priamo na známu IP) na port 47810 a PC odpovie
// "DOMOVPC!" + verzia + ID PC + port + názov. Odpovedá len na žiadosti z domácej siete.

var (
	discoveryRequest = []byte("DOMOVPC?")
	discoveryReply   = []byte("DOMOVPC!")
)

// Port vráti skutočný port TCP (po Listen aj pri porte 0).
func (s *Server) Port() int {
	if a, ok := s.Addr().(*net.TCPAddr); ok {
		return a.Port
	}
	return s.port
}

func (s *Server) buildDiscoveryReply() []byte {
	b := append([]byte{}, discoveryReply...)
	b = append(b, protoVersion)
	b = append(b, s.store.ServerID()...)
	b = putU16(b, uint16(s.Port()))
	name := s.store.Name()
	b = append(b, byte(len(name)))
	b = append(b, name...)
	return b
}

// ServeDiscovery beží, kým sa nezruší ctx. Chyba pri otvorení portu nie je fatálna (PC sa dá pripojiť aj ručne).
func (s *Server) ServeDiscovery(ctx context.Context) error {
	pc, err := net.ListenPacket("udp4", ":"+strconv.Itoa(s.Port()))
	if err != nil {
		return err
	}
	go func() {
		<-ctx.Done()
		pc.Close()
	}()
	buf := make([]byte, 64)
	window := time.Now()
	count := 0
	for {
		n, addr, err := pc.ReadFrom(buf)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		ua, ok := addr.(*net.UDPAddr)
		if !ok || !isLANAddr(ua.IP) || n < len(discoveryRequest) || string(buf[:len(discoveryRequest)]) != string(discoveryRequest) {
			continue
		}
		// ochrana pred zahltením: najviac 50 odpovedí za sekundu
		if time.Since(window) > time.Second {
			window, count = time.Now(), 0
		}
		if count++; count > 50 {
			continue
		}
		_, _ = pc.WriteTo(s.buildDiscoveryReply(), addr)
	}
}
