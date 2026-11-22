package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strings"
	"sync"
	"time"
)

// Kryptografia protokolu Domov PC (verzia 1). Všetko sú štandardné stavebné kamene bez cudzích knižníc:
// HMAC-SHA256, HKDF-SHA256, PBKDF2-HMAC-SHA256 a AES-256-GCM. Rovnaké kroky robí aj klient v Jave (PcCrypto.java).

const (
	pairIterations = 200000
	pairAlphabet   = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	pairCodeLen    = 10
)

func hmacSHA256(key []byte, parts ...[]byte) []byte {
	m := hmac.New(sha256.New, key)
	for _, p := range parts {
		m.Write(p)
	}
	return m.Sum(nil)
}

// pbkdf2SHA256 je PBKDF2-HMAC-SHA256 podľa RFC 8018.
func pbkdf2SHA256(password, salt []byte, iter, keyLen int) []byte {
	prf := hmac.New(sha256.New, password)
	hLen := prf.Size()
	blocks := (keyLen + hLen - 1) / hLen
	var cnt [4]byte
	dk := make([]byte, 0, blocks*hLen)
	u := make([]byte, hLen)
	for block := 1; block <= blocks; block++ {
		prf.Reset()
		prf.Write(salt)
		binary.BigEndian.PutUint32(cnt[:], uint32(block))
		prf.Write(cnt[:])
		dk = prf.Sum(dk)
		t := dk[len(dk)-hLen:]
		copy(u, t)
		for n := 2; n <= iter; n++ {
			prf.Reset()
			prf.Write(u)
			u = prf.Sum(u[:0])
			for x := range u {
				t[x] ^= u[x]
			}
		}
	}
	return dk[:keyLen]
}

// normalizeCode: veľké písmená, bez pomlčiek a medzier; vráti "" ak obsahuje cudzí znak alebo nemá správnu dĺžku.
func normalizeCode(code string) string {
	var b strings.Builder
	for _, r := range strings.ToUpper(code) {
		if r == '-' || r == ' ' {
			continue
		}
		if !strings.ContainsRune(pairAlphabet, r) {
			return ""
		}
		b.WriteRune(r)
	}
	if b.Len() != pairCodeLen {
		return ""
	}
	return b.String()
}

// newPairCode vytvorí náhodný párovací kód (10 znakov z 32 = 50 bitov).
func newPairCode() string {
	var raw [pairCodeLen]byte
	if _, err := io.ReadFull(rand.Reader, raw[:]); err != nil {
		panic(err)
	}
	out := make([]byte, pairCodeLen)
	for i, v := range raw {
		out[i] = pairAlphabet[int(v)%32] // 256 je deliteľné 32, bez skreslenia
	}
	return string(out)
}

// formatCode: ABCDE-FGHJK
func formatCode(code string) string {
	if len(code) != pairCodeLen {
		return code
	}
	return code[:5] + "-" + code[5:]
}

// pairKeyFromCode odvodí kľúč z párovacieho kódu. Pomalé (PBKDF2) kvôli ochrane pred hádaním z odchyteného spojenia.
func pairKeyFromCode(code string, serverID []byte) []byte {
	salt := append([]byte("domov-pc/1/pair"), serverID...)
	return pbkdf2SHA256([]byte(normalizeCode(code)), salt, pairIterations, 32)
}

func randBytes(n int) []byte {
	b := make([]byte, n)
	if _, err := io.ReadFull(rand.Reader, b); err != nil {
		panic(err)
	}
	return b
}

// handshakeTranscript je to, čo sa podpisuje v dôkaze (rola = "server" alebo "client").
func handshakeProof(key []byte, role string, mode byte, deviceID, first, second, serverID []byte, name string) []byte {
	return hmacSHA256(key,
		[]byte("domov-pc/1/"+role), []byte{mode}, deviceID, first, second, serverID,
		[]byte{byte(len(name))}, []byte(name))
}

func proofEqual(a, b []byte) bool { return subtle.ConstantTimeCompare(a, b) == 1 }

// sessionKeys odvodí dva kľúče (klient→server, server→klient) pomocou HKDF-SHA256.
func sessionKeys(key, clientNonce, serverNonce []byte) (c2s, s2c []byte) {
	prk := hmacSHA256(append(append([]byte{}, clientNonce...), serverNonce...), key)
	c2s = hmacSHA256(prk, []byte("domov-pc/1/c2s"), []byte{1})
	s2c = hmacSHA256(prk, []byte("domov-pc/1/s2c"), []byte{1})
	return
}

func gcmNonce(dir byte, ctr uint64) []byte {
	n := make([]byte, 12)
	n[0] = dir
	binary.BigEndian.PutUint64(n[4:], ctr)
	return n
}

func newGCM(key []byte) cipher.AEAD {
	blk, err := aes.NewCipher(key)
	if err != nil {
		panic(err)
	}
	g, err := cipher.NewGCM(blk)
	if err != nil {
		panic(err)
	}
	return g
}

// ---- rámcovanie na TCP: u32 dĺžka (big-endian) + telo ----

var errFrameTooBig = errors.New("príliš veľký rámec")

func writeFrame(w io.Writer, body []byte) error {
	buf := make([]byte, 4+len(body))
	binary.BigEndian.PutUint32(buf, uint32(len(body)))
	copy(buf[4:], body)
	_, err := w.Write(buf)
	return err
}

func readFrame(r io.Reader, max int) ([]byte, error) {
	var hdr [4]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return nil, err
	}
	n := int(binary.BigEndian.Uint32(hdr[:]))
	if n > max || n < 0 {
		return nil, errFrameTooBig
	}
	body := make([]byte, n)
	if _, err := io.ReadFull(r, body); err != nil {
		return nil, err
	}
	return body, nil
}

// secureConn je šifrované spojenie: každá správa = [typ][dáta] zapečatená AES-GCM s počítadlom ako nonce.
type secureConn struct {
	conn     net.Conn
	send     cipher.AEAD
	recv     cipher.AEAD
	sendDir  byte
	recvDir  byte
	sendCtr  uint64
	recvCtr  uint64
	wmu      sync.Mutex
	maxRecv  int
	writeTTL time.Duration
	lastRaw  []byte // posledné telo, ktoré sa nepodarilo overiť (klient z neho vie prečítať nešifrovanú chybu)
}

// newSecureConn: server=true používa kľúč s2c na odosielanie a c2s na príjem.
func newSecureConn(conn net.Conn, c2s, s2c []byte, server bool) *secureConn {
	sc := &secureConn{conn: conn, maxRecv: 2 << 20, writeTTL: 8 * time.Second}
	if server {
		sc.send, sc.recv, sc.sendDir, sc.recvDir = newGCM(s2c), newGCM(c2s), 1, 0
	} else {
		sc.send, sc.recv, sc.sendDir, sc.recvDir = newGCM(c2s), newGCM(s2c), 0, 1
	}
	return sc
}

// WriteMsg pošle jednu správu (typ + zreťazené časti). Bezpečné volať z viacerých vlákien.
func (s *secureConn) WriteMsg(typ byte, parts ...[]byte) error {
	n := 1
	for _, p := range parts {
		n += len(p)
	}
	plain := make([]byte, 0, n)
	plain = append(plain, typ)
	for _, p := range parts {
		plain = append(plain, p...)
	}
	s.wmu.Lock()
	defer s.wmu.Unlock()
	sealed := s.send.Seal(make([]byte, 4, 4+n+16), gcmNonce(s.sendDir, s.sendCtr), plain, nil)
	s.sendCtr++
	binary.BigEndian.PutUint32(sealed, uint32(len(sealed)-4))
	_ = s.conn.SetWriteDeadline(time.Now().Add(s.writeTTL))
	_, err := s.conn.Write(sealed)
	return err
}

// ReadMsg prečíta a overí jednu správu. Chyba overenia = útok alebo porucha, spojenie treba zavrieť.
func (s *secureConn) ReadMsg() (typ byte, payload []byte, err error) {
	body, err := readFrame(s.conn, s.maxRecv)
	if err != nil {
		return 0, nil, err
	}
	plain, err := s.recv.Open(nil, gcmNonce(s.recvDir, s.recvCtr), body, nil)
	if err != nil {
		s.lastRaw = body
		return 0, nil, errors.New("správu sa nepodarilo overiť")
	}
	s.recvCtr++
	if len(plain) == 0 {
		return 0, nil, errors.New("prázdna správa")
	}
	return plain[0], plain[1:], nil
}
