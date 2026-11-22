package main

import (
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"sync"
	"time"
)

// Trvalý stav PC programu: identita PC a spárované telefóny (s ich kľúčmi). Súbor je v profile používateľa.

type deviceRec struct {
	ID       string `json:"id"`   // hex, 16 B
	Name     string `json:"name"` // názov telefónu
	Key      string `json:"key"`  // hex, 32 B
	Created  int64  `json:"created"`
	LastSeen int64  `json:"lastSeen"`
	LastIP   string `json:"lastIp"`
}

type stateFile struct {
	ServerID string      `json:"serverId"`
	Name     string      `json:"name"`
	Devices  []deviceRec `json:"devices"`
}

type Store struct {
	mu   sync.Mutex
	path string
	st   stateFile
}

// dataDir: %LOCALAPPDATA%\DomovPC (Windows) alebo ~/.config/DomovPC.
func dataDir() string {
	if runtime.GOOS == "windows" {
		if d := os.Getenv("LOCALAPPDATA"); d != "" {
			return filepath.Join(d, "DomovPC")
		}
	}
	if d, err := os.UserConfigDir(); err == nil {
		return filepath.Join(d, "DomovPC")
	}
	return filepath.Join(os.TempDir(), "DomovPC")
}

func OpenStore(dir string) (*Store, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	s := &Store{path: filepath.Join(dir, "state.json")}
	if raw, err := os.ReadFile(s.path); err == nil {
		_ = json.Unmarshal(raw, &s.st) // poškodený súbor = začať nanovo, ale ID PC sa vygeneruje nové
	}
	changed := false
	if id, err := hex.DecodeString(s.st.ServerID); err != nil || len(id) != 16 {
		s.st.ServerID = hex.EncodeToString(randBytes(16))
		changed = true
	}
	if s.st.Name == "" {
		h, _ := os.Hostname()
		if h == "" {
			h = "PC"
		}
		s.st.Name = cleanName(h)
		changed = true
	}
	if changed {
		if err := s.saveLocked(); err != nil {
			return nil, err
		}
	}
	return s, nil
}

func (s *Store) saveLocked() error {
	raw, err := json.MarshalIndent(s.st, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func (s *Store) ServerID() []byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, _ := hex.DecodeString(s.st.ServerID)
	return b
}

func (s *Store) Name() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.st.Name
}

func (s *Store) SetName(name string) error {
	name = cleanName(name)
	if name == "" {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.st.Name = name
	return s.saveLocked()
}

// Device vráti kľúč spárovaného telefónu podľa jeho ID.
func (s *Store) Device(id []byte) (deviceRec, []byte, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	want := hex.EncodeToString(id)
	for _, d := range s.st.Devices {
		if d.ID == want {
			k, err := hex.DecodeString(d.Key)
			if err != nil || len(k) != 32 {
				return d, nil, false
			}
			return d, k, true
		}
	}
	return deviceRec{}, nil, false
}

// AddDevice pridá (alebo prepíše) spárovaný telefón.
func (s *Store) AddDevice(id []byte, name string, key []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	rec := deviceRec{ID: hex.EncodeToString(id), Name: cleanName(name), Key: hex.EncodeToString(key), Created: time.Now().Unix()}
	if rec.Name == "" {
		rec.Name = "Telefón"
	}
	for i, d := range s.st.Devices {
		if d.ID == rec.ID {
			s.st.Devices[i] = rec
			return s.saveLocked()
		}
	}
	s.st.Devices = append(s.st.Devices, rec)
	return s.saveLocked()
}

func (s *Store) RemoveDevice(idHex string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := s.st.Devices[:0]
	for _, d := range s.st.Devices {
		if d.ID != idHex {
			out = append(out, d)
		}
	}
	s.st.Devices = out
	return s.saveLocked()
}

func (s *Store) Touch(id []byte, ip string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	want := hex.EncodeToString(id)
	for i, d := range s.st.Devices {
		if d.ID == want {
			s.st.Devices[i].LastSeen = time.Now().Unix()
			s.st.Devices[i].LastIP = ip
			_ = s.saveLocked()
			return
		}
	}
}

// Devices vráti kópiu zoznamu bez kľúčov, od naposledy videných.
func (s *Store) Devices() []deviceRec {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]deviceRec, len(s.st.Devices))
	for i, d := range s.st.Devices {
		d.Key = ""
		out[i] = d
	}
	sort.Slice(out, func(i, j int) bool { return out[i].LastSeen > out[j].LastSeen })
	return out
}
