package main

import (
	"bytes"
	"image"
	"image/jpeg"
	"runtime"
	"sync"
)

// Obraz sa posiela ako pásy po bandRows riadkov (po zmenšení). Pošlú sa len pásy, ktoré sa zmenili od posledného
// odoslania, každý ako samostatné JPEG. Pri statickej ploche (písanie textu, pohyb kurzora) je to pár kilobajtov.

const bandRows = 64

type rect struct {
	y, h int
	data []byte
}

type encoder struct {
	srcW, srcH int
	k          int // delič: 1 = bez zmenšenia, 2 = polovica...
	outW, outH int
	prev       []byte // naposledy odoslaný zdroj (BGRA)
	bufPool    sync.Pool
}

func newEncoder(srcW, srcH, k int) *encoder {
	if k < 1 {
		k = 1
	}
	e := &encoder{srcW: srcW, srcH: srcH, k: k, outW: srcW / k, outH: srcH / k}
	if e.outW < 1 {
		e.outW = 1
	}
	if e.outH < 1 {
		e.outH = 1
	}
	return e
}

func (e *encoder) bands() int { return (e.outH + bandRows - 1) / bandRows }

// chooseDivisor: automatický výber zmenšenia podľa šírky monitora.
func chooseDivisor(width int) int {
	switch {
	case width > 5000:
		return 3
	case width > 2600:
		return 2
	default:
		return 1
	}
}

// encode vráti pásy, ktoré sa zmenili (alebo všetky pri force). src musí mať srcW*srcH*4 bajtov (BGRA).
func (e *encoder) encode(src []byte, quality int, force bool) []rect {
	if len(src) < e.srcW*e.srcH*4 {
		return nil
	}
	if quality < 20 {
		quality = 20
	}
	if quality > 95 {
		quality = 95
	}
	stride := e.srcW * 4
	nb := e.bands()
	var dirty []int
	if force || e.prev == nil {
		for b := 0; b < nb; b++ {
			dirty = append(dirty, b)
		}
	} else {
		for b := 0; b < nb; b++ {
			r0, r1 := e.bandSrcRows(b)
			if !bytes.Equal(src[r0*stride:r1*stride], e.prev[r0*stride:r1*stride]) {
				dirty = append(dirty, b)
			}
		}
	}
	if len(dirty) == 0 {
		return nil
	}
	out := make([]rect, len(dirty))
	workers := runtime.NumCPU()
	if workers > len(dirty) {
		workers = len(dirty)
	}
	if workers < 1 {
		workers = 1
	}
	var wg sync.WaitGroup
	next := make(chan int)
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range next {
				b := dirty[i]
				y0 := b * bandRows
				y1 := y0 + bandRows
				if y1 > e.outH {
					y1 = e.outH
				}
				out[i] = rect{y: y0, h: y1 - y0, data: e.encodeBand(src, y0, y1, quality)}
			}
		}()
	}
	for i := range dirty {
		next <- i
	}
	close(next)
	wg.Wait()
	// zapamätať si odoslané pásy
	if e.prev == nil {
		e.prev = make([]byte, len(src))
	}
	for _, b := range dirty {
		r0, r1 := e.bandSrcRows(b)
		copy(e.prev[r0*stride:r1*stride], src[r0*stride:r1*stride])
	}
	return out
}

func (e *encoder) bandSrcRows(b int) (int, int) {
	y0 := b * bandRows
	y1 := y0 + bandRows
	if y1 > e.outH {
		y1 = e.outH
	}
	return y0 * e.k, y1 * e.k
}

func (e *encoder) encodeBand(src []byte, y0, y1, quality int) []byte {
	w, h := e.outW, y1-y0
	need := w * h * 4
	var pix []byte
	if v := e.bufPool.Get(); v != nil {
		pix = v.([]byte)
	}
	if cap(pix) < need {
		pix = make([]byte, need)
	}
	pix = pix[:need]
	img := &image.RGBA{Pix: pix, Stride: w * 4, Rect: image.Rect(0, 0, w, h)}
	stride := e.srcW * 4
	k := e.k
	if k == 1 {
		for y := 0; y < h; y++ {
			s := src[(y0+y)*stride : (y0+y)*stride+w*4]
			d := pix[y*w*4 : (y+1)*w*4]
			for x := 0; x < w*4; x += 4 {
				d[x], d[x+1], d[x+2], d[x+3] = s[x+2], s[x+1], s[x], 255
			}
		}
	} else {
		area := k * k
		for y := 0; y < h; y++ {
			d := pix[y*w*4 : (y+1)*w*4]
			for x := 0; x < w; x++ {
				var sb, sg, sr int
				for dy := 0; dy < k; dy++ {
					row := (y0+y)*k + dy
					base := row*stride + x*k*4
					for dx := 0; dx < k; dx++ {
						p := base + dx*4
						sb += int(src[p])
						sg += int(src[p+1])
						sr += int(src[p+2])
					}
				}
				d[x*4], d[x*4+1], d[x*4+2], d[x*4+3] = byte(sr/area), byte(sg/area), byte(sb/area), 255
			}
		}
	}
	var buf bytes.Buffer
	buf.Grow(w * h / 4)
	_ = jpeg.Encode(&buf, img, &jpeg.Options{Quality: quality})
	e.bufPool.Put(pix)
	return buf.Bytes()
}
