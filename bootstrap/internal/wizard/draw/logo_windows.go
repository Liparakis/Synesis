//go:build windows

package draw

import (
	"bytes"
	"errors"
	"fmt"
	"image"
	"image/draw"
	"image/png"

	"synesis/bootstrap/assets"
)

// NewLogo renders the embedded product mark into a size by size raster. The
// mark is averaged down from its source pixels and stored premultiplied, so it
// stays smooth at every display scale and blends onto any background.
func NewLogo(size int32) (*Raster, error) {
	decoded, err := png.Decode(bytes.NewReader(assets.LogoPNG))
	if err != nil {
		return nil, fmt.Errorf("decode embedded Synesis logo: %w", err)
	}
	source, ok := decoded.(*image.NRGBA)
	if !ok {
		source = image.NewNRGBA(decoded.Bounds())
		draw.Draw(source, source.Bounds(), decoded, decoded.Bounds().Min, draw.Src)
	}
	width, height := source.Bounds().Dx(), source.Bounds().Dy()
	raster := NewRaster(size, size)
	if width == 0 || height == 0 || raster == nil {
		raster.Dispose()
		return nil, errors.New("embedded Synesis logo is empty")
	}
	for y := 0; y < int(size); y++ {
		top, bottom := y*height/int(size), max((y+1)*height/int(size), y*height/int(size)+1)
		for x := 0; x < int(size); x++ {
			left, right := x*width/int(size), max((x+1)*width/int(size), x*width/int(size)+1)
			var red, green, blue, alpha, count int
			for sy := top; sy < bottom && sy < height; sy++ {
				row := source.Pix[sy*source.Stride:]
				for sx := left; sx < right && sx < width; sx++ {
					a := int(row[sx*4+3])
					red, green, blue, alpha = red+int(row[sx*4])*a, green+int(row[sx*4+1])*a, blue+int(row[sx*4+2])*a, alpha+a
					count++
				}
			}
			i := (y*int(size) + x) * 4
			raster.Pixels[i], raster.Pixels[i+1], raster.Pixels[i+2], raster.Pixels[i+3] =
				byte(blue/(count*255)), byte(green/(count*255)), byte(red/(count*255)), byte(alpha/count)
		}
	}
	return raster, nil
}
