package main

import (
	"fmt"
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Ak je nastavená premenná QR_OUT, testy zapíšu PNG-čká do toho priečinka (na kontrolu čítačkou QR kódov).
func TestQRSizes(t *testing.T) {
	out := os.Getenv("QR_OUT")
	for _, n := range []int{1, 14, 26, 42, 62, 71, 84, 106, 122, 152, 180, 213} {
		payload := strings.Repeat("domovpc:1:ABCDEFGHJK:", 20)[:n]
		m, err := qrEncode([]byte(payload))
		if err != nil {
			t.Fatalf("n=%d: %v", n, err)
		}
		if out != "" {
			savePNG(t, filepath.Join(out, fmt.Sprintf("qr_%03d.png", n)), m)
			_ = os.WriteFile(filepath.Join(out, fmt.Sprintf("qr_%03d.txt", n)), []byte(payload), 0o644)
		}
	}
	if _, err := qrEncode(make([]byte, 214)); err == nil {
		t.Fatal("214 bajtov sa už nemá zmestiť")
	}
}

func savePNG(t *testing.T, path string, m [][]bool) {
	const scale, quiet = 8, 4
	n := len(m)
	img := image.NewGray(image.Rect(0, 0, (n+2*quiet)*scale, (n+2*quiet)*scale))
	for i := range img.Pix {
		img.Pix[i] = 255
	}
	for y := 0; y < n; y++ {
		for x := 0; x < n; x++ {
			if m[y][x] {
				for dy := 0; dy < scale; dy++ {
					for dx := 0; dx < scale; dx++ {
						img.SetGray((x+quiet)*scale+dx, (y+quiet)*scale+dy, color.Gray{0})
					}
				}
			}
		}
	}
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	_ = png.Encode(f, img)
}
