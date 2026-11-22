package main

// Rozhrania k systému. Na Windows ich implementuje win_*.go, v testoch a na iných systémoch stub.go.

type Monitor struct {
	Index   int
	X, Y    int // pozícia vo virtuálnej ploche (fyzické pixely)
	W, H    int
	Primary bool
}

// Screen zachytáva obraz monitora.
type Screen interface {
	// Monitors vráti aktuálne monitory (primárny prvý? nie, poradie podľa Index; Index 0.. súvislé).
	Monitors() []Monitor
	// Grab zachytí monitor do dst vo formáte BGRA (4 B na pixel, riadky zhora nadol, bez medzier).
	// dst sa vráti (prípadne zväčšený), takže volajúci ho môže používať opakovane.
	Grab(mon Monitor, dst []byte) ([]byte, error)
	Close()
}

// Input vkladá myš a klávesnicu.
type Input interface {
	MoveRel(mon Monitor, dx, dy int)
	// MoveAbs: fx, fy v rozsahu 0..1 cez celý monitor.
	MoveAbs(mon Monitor, fx, fy float64)
	Button(button int, down bool) // 1 ľavé, 2 pravé, 3 stredné, 4 a 5 bočné
	Wheel(dx, dy int)             // 120 = jedna "zárez"; kladné dy = hore
	Key(vk uint16, down bool)
	Text(s string)
}

// CursorReader je voliteľná schopnosť Input: poloha kurzora vo virtuálnej ploche (fyzické pixely).
// Telefón podľa nej sleduje kurzor pri priblíženom obraze.
type CursorReader interface {
	CursorPos() (x, y int)
}

// System sú ostatné služby PC.
type System interface {
	DesktopAvailable() bool // false = zamknutá obrazovka / UAC / prihlasovacia obrazovka
	ClipboardGet() (string, error)
	ClipboardSet(s string) error
	Power(action int) error
	CanPower() bool
	Elevated() bool
}

type Backend struct {
	Screen Screen
	Input  Input
	System System
}
