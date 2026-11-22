package main

import (
	"errors"
	"strconv"
	"strings"
)

// Malý generátor QR kódov (režim bajtov, korekcia M, verzie 1–10 = až 213 bajtov), aby stránka správy na PC
// ukázala QR kód bez cudzích knižníc. Postup podľa ISO/IEC 18004.

type qrBlockSpec struct{ ec, n1, d1, n2, d2 int }

// ECC úroveň M
var qrSpecs = [11]qrBlockSpec{
	{}, {10, 1, 16, 0, 0}, {16, 1, 28, 0, 0}, {26, 1, 44, 0, 0}, {18, 2, 32, 0, 0}, {24, 2, 43, 0, 0},
	{16, 4, 27, 0, 0}, {18, 4, 31, 0, 0}, {22, 2, 38, 2, 39}, {22, 3, 36, 2, 37}, {26, 4, 43, 1, 44},
}

var qrAlign = [11][]int{
	nil, nil, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34}, {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50},
}

var gfExp [512]byte
var gfLog [256]int

func init() {
	x := 1
	for i := 0; i < 255; i++ {
		gfExp[i] = byte(x)
		gfLog[x] = i
		x <<= 1
		if x&0x100 != 0 {
			x ^= 0x11D
		}
	}
	for i := 255; i < 512; i++ {
		gfExp[i] = gfExp[i-255]
	}
}

func gfMul(a, b byte) byte {
	if a == 0 || b == 0 {
		return 0
	}
	return gfExp[gfLog[a]+gfLog[b]]
}

func rsGenerator(n int) []byte {
	g := []byte{1}
	for i := 0; i < n; i++ {
		next := make([]byte, len(g)+1)
		for j, c := range g {
			next[j] ^= c
			next[j+1] ^= gfMul(c, gfExp[i])
		}
		g = next
	}
	return g // g[0] = 1 (vedúci člen)
}

func rsRemainder(data []byte, n int) []byte {
	gen := rsGenerator(n)
	rem := make([]byte, n)
	for _, b := range data {
		f := b ^ rem[0]
		copy(rem, rem[1:])
		rem[n-1] = 0
		if f != 0 {
			for i := 0; i < n; i++ {
				rem[i] ^= gfMul(gen[i+1], f)
			}
		}
	}
	return rem
}

type qrBits struct {
	b []byte
	n int
}

func (q *qrBits) put(v uint32, count int) {
	for i := count - 1; i >= 0; i-- {
		if q.n%8 == 0 {
			q.b = append(q.b, 0)
		}
		if v>>uint(i)&1 == 1 {
			q.b[q.n/8] |= 0x80 >> uint(q.n%8)
		}
		q.n++
	}
}

func dataCapacity(v int) int {
	s := qrSpecs[v]
	return s.n1*s.d1 + s.n2*s.d2
}

// qrEncode vráti maticu modulov (true = tmavý).
func qrEncode(data []byte) ([][]bool, error) {
	ver := 0
	for v := 1; v <= 10; v++ {
		countBits := 8
		if v >= 10 {
			countBits = 16
		}
		if (dataCapacity(v)*8 - 4 - countBits) >= len(data)*8 {
			ver = v
			break
		}
	}
	if ver == 0 {
		return nil, errors.New("príliš dlhé dáta pre QR kód")
	}
	spec := qrSpecs[ver]
	capBytes := dataCapacity(ver)
	var bits qrBits
	bits.put(0x4, 4)
	if ver >= 10 {
		bits.put(uint32(len(data)), 16)
	} else {
		bits.put(uint32(len(data)), 8)
	}
	for _, c := range data {
		bits.put(uint32(c), 8)
	}
	for t := 0; t < 4 && bits.n < capBytes*8; t++ {
		bits.put(0, 1)
	}
	for bits.n%8 != 0 {
		bits.put(0, 1)
	}
	for pad := byte(0xEC); len(bits.b) < capBytes; {
		bits.b = append(bits.b, pad)
		if pad == 0xEC {
			pad = 0x11
		} else {
			pad = 0xEC
		}
	}

	// bloky + ECC + prekladanie
	type block struct{ data, ecc []byte }
	var blocks []block
	off := 0
	for i := 0; i < spec.n1+spec.n2; i++ {
		l := spec.d1
		if i >= spec.n1 {
			l = spec.d2
		}
		d := bits.b[off : off+l]
		off += l
		blocks = append(blocks, block{d, rsRemainder(d, spec.ec)})
	}
	var final []byte
	maxD := spec.d1
	if spec.d2 > maxD {
		maxD = spec.d2
	}
	for i := 0; i < maxD; i++ {
		for _, b := range blocks {
			if i < len(b.data) {
				final = append(final, b.data[i])
			}
		}
	}
	for i := 0; i < spec.ec; i++ {
		for _, b := range blocks {
			final = append(final, b.ecc[i])
		}
	}

	size := 17 + 4*ver
	mod := make([][]bool, size)
	fn := make([][]bool, size)
	for i := range mod {
		mod[i] = make([]bool, size)
		fn[i] = make([]bool, size)
	}
	set := func(x, y int, dark bool) {
		if x >= 0 && x < size && y >= 0 && y < size {
			mod[y][x] = dark
			fn[y][x] = true
		}
	}
	abs := func(a int) int {
		if a < 0 {
			return -a
		}
		return a
	}
	mx := func(a, b int) int {
		if a > b {
			return a
		}
		return b
	}
	for i := 0; i < size; i++ {
		set(6, i, i%2 == 0)
		set(i, 6, i%2 == 0)
	}
	finder := func(cx, cy int) {
		for dy := -4; dy <= 4; dy++ {
			for dx := -4; dx <= 4; dx++ {
				d := mx(abs(dx), abs(dy))
				set(cx+dx, cy+dy, d != 2 && d != 4)
			}
		}
	}
	finder(3, 3)
	finder(size-4, 3)
	finder(3, size-4)
	pos := qrAlign[ver]
	for i, py := range pos {
		for j, px := range pos {
			if (i == 0 && j == 0) || (i == 0 && j == len(pos)-1) || (i == len(pos)-1 && j == 0) {
				continue
			}
			for dy := -2; dy <= 2; dy++ {
				for dx := -2; dx <= 2; dx++ {
					set(px+dx, py+dy, mx(abs(dx), abs(dy)) != 1)
				}
			}
		}
	}
	drawFormat := func(mask int) {
		data := 0<<3 | mask // úroveň M = 0
		rem := data
		for i := 0; i < 10; i++ {
			rem = rem<<1 ^ (rem>>9)*0x537
		}
		bits := (data<<10 | rem) ^ 0x5412
		bit := func(i int) bool { return bits>>uint(i)&1 == 1 }
		for i := 0; i <= 5; i++ {
			set(8, i, bit(i))
		}
		set(8, 7, bit(6))
		set(8, 8, bit(7))
		set(7, 8, bit(8))
		for i := 9; i < 15; i++ {
			set(14-i, 8, bit(i))
		}
		for i := 0; i < 8; i++ {
			set(size-1-i, 8, bit(i))
		}
		for i := 8; i < 15; i++ {
			set(8, size-15+i, bit(i))
		}
		set(8, size-8, true)
	}
	drawFormat(0)
	if ver >= 7 {
		rem := ver
		for i := 0; i < 12; i++ {
			rem = rem<<1 ^ (rem>>11)*0x1F25
		}
		bits := ver<<12 | rem
		for i := 0; i < 18; i++ {
			bit := bits>>uint(i)&1 == 1
			a := size - 11 + i%3
			b := i / 3
			set(a, b, bit)
			set(b, a, bit)
		}
	}

	// dáta do matice
	idx := 0
	for right := size - 1; right >= 1; right -= 2 {
		if right == 6 {
			right = 5
		}
		for vert := 0; vert < size; vert++ {
			for j := 0; j < 2; j++ {
				x := right - j
				upward := (right+1)&2 == 0
				y := vert
				if upward {
					y = size - 1 - vert
				}
				if !fn[y][x] && idx < len(final)*8 {
					mod[y][x] = final[idx>>3]>>uint(7-idx&7)&1 == 1
					idx++
				}
			}
		}
	}

	// výber masky
	bestPen := -1
	var bestMat [][]bool
	for mask := 0; mask < 8; mask++ {
		m := make([][]bool, size)
		for y := range m {
			m[y] = append([]bool(nil), mod[y]...)
		}
		for y := 0; y < size; y++ {
			for x := 0; x < size; x++ {
				if fn[y][x] {
					continue
				}
				var inv bool
				switch mask {
				case 0:
					inv = (x+y)%2 == 0
				case 1:
					inv = y%2 == 0
				case 2:
					inv = x%3 == 0
				case 3:
					inv = (x+y)%3 == 0
				case 4:
					inv = (x/3+y/2)%2 == 0
				case 5:
					inv = x*y%2+x*y%3 == 0
				case 6:
					inv = (x*y%2+x*y%3)%2 == 0
				case 7:
					inv = ((x+y)%2+x*y%3)%2 == 0
				}
				if inv {
					m[y][x] = !m[y][x]
				}
			}
		}
		// formát s touto maskou prepíše rezervované miesta
		saveMod := mod
		mod = m
		drawFormat(mask)
		mod = saveMod
		pen := qrPenalty(m)
		if bestPen < 0 || pen < bestPen {
			bestPen, bestMat = pen, m
		}
	}
	return bestMat, nil
}

func qrPenalty(m [][]bool) int {
	n := len(m)
	pen := 0
	// pravidlo 1: súvislé rady rovnakej farby; pravidlo 3: vzor 1011101 s bielym okolím
	line := func(get func(i int) bool) {
		run := 1
		for i := 1; i < n; i++ {
			if get(i) == get(i-1) {
				run++
			} else {
				if run >= 5 {
					pen += 3 + run - 5
				}
				run = 1
			}
		}
		if run >= 5 {
			pen += 3 + run - 5
		}
		pat := []bool{true, false, true, true, true, false, true}
		for i := 0; i+7 <= n; i++ {
			ok := true
			for k := 0; k < 7; k++ {
				if get(i+k) != pat[k] {
					ok = false
					break
				}
			}
			if !ok {
				continue
			}
			white := func(from, to int) bool {
				for k := from; k < to; k++ {
					if k >= 0 && k < n && get(k) {
						return false
					}
				}
				return true
			}
			if white(i-4, i) || white(i+7, i+11) {
				pen += 40
			}
		}
	}
	for y := 0; y < n; y++ {
		y := y
		line(func(i int) bool { return m[y][i] })
	}
	for x := 0; x < n; x++ {
		x := x
		line(func(i int) bool { return m[i][x] })
	}
	// pravidlo 2: bloky 2x2
	for y := 0; y+1 < n; y++ {
		for x := 0; x+1 < n; x++ {
			c := m[y][x]
			if m[y][x+1] == c && m[y+1][x] == c && m[y+1][x+1] == c {
				pen += 3
			}
		}
	}
	// pravidlo 4: pomer tmavých modulov
	dark := 0
	for y := 0; y < n; y++ {
		for x := 0; x < n; x++ {
			if m[y][x] {
				dark++
			}
		}
	}
	total := n * n
	d := dark*20 - total*10
	if d < 0 {
		d = -d
	}
	pen += ((d+total-1)/total - 1) * 10
	return pen
}

// qrSVG: matica ako SVG s bielym okrajom (quiet zone 4 moduly).
func qrSVG(m [][]bool) string {
	n := len(m)
	const q = 4
	var b strings.Builder
	b.WriteString(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ` + strconv.Itoa(n+2*q) + ` ` + strconv.Itoa(n+2*q) + `" shape-rendering="crispEdges">`)
	b.WriteString(`<rect width="100%" height="100%" fill="#fff"/><path fill="#000" d="`)
	for y := 0; y < n; y++ {
		for x := 0; x < n; x++ {
			if m[y][x] {
				b.WriteString("M" + strconv.Itoa(x+q) + " " + strconv.Itoa(y+q) + "h1v1h-1z")
			}
		}
	}
	b.WriteString(`"/></svg>`)
	return b.String()
}
