//go:build windows

package draw

import (
	"bytes"
	"errors"
	"fmt"
	"image/png"
	"unsafe"

	"synesis/bootstrap/assets"
	"synesis/bootstrap/internal/wizard/win32"
)

// Art is a decoded wizard image ready to be blended.
type Art struct {
	Bitmap        uintptr
	Width, Height int32
}

// CreateLogo decodes the embedded product mark into a bitmap.
func CreateLogo(hwnd uintptr) (bitmap uintptr, width, height int32, err error) {
	return CreateBitmap(hwnd, assets.LogoPNG)
}

// CreateBitmap decodes PNG data into a 32-bit device independent bitmap.
func CreateBitmap(hwnd uintptr, dataPNG []byte) (bitmap uintptr, width, height int32, err error) {
	image, decodeErr := png.Decode(bytes.NewReader(dataPNG))
	if decodeErr != nil {
		return 0, 0, 0, fmt.Errorf("decode embedded Synesis logo: %w", decodeErr)
	}
	bounds := image.Bounds()
	width = int32(bounds.Dx())
	height = int32(bounds.Dy())
	if width == 0 || height == 0 {
		return 0, 0, 0, errors.New("embedded Synesis logo is empty")
	}
	info := win32.BitmapInfo{Header: win32.BitmapInfoHeader{
		Size:        40,
		Width:       width,
		Height:      -height,
		Planes:      1,
		BitCount:    32,
		Compression: 0,
	}}
	hdc, _, _ := win32.GetDC.Call(hwnd)
	if hdc == 0 {
		return 0, 0, 0, errors.New("unable to create a logo device context")
	}
	defer win32.ReleaseDC.Call(hwnd, hdc)
	var pixels unsafe.Pointer
	bitmap, _, callErr := win32.CreateDIBSection.Call(
		hdc,
		uintptr(unsafe.Pointer(&info)),
		0,
		uintptr(unsafe.Pointer(&pixels)),
		0,
		0,
	)
	if bitmap == 0 || pixels == nil {
		if callErr != nil {
			return 0, 0, 0, fmt.Errorf("create logo bitmap: %w", callErr)
		}
		return 0, 0, 0, errors.New("create logo bitmap failed")
	}
	data := unsafe.Slice((*byte)(pixels), int(width*height*4))
	for y := 0; y < int(height); y++ {
		for x := 0; x < int(width); x++ {
			red, green, blue, alpha := image.At(bounds.Min.X+x, bounds.Min.Y+y).RGBA()
			index := (y*int(width) + x) * 4
			data[index] = byte(blue >> 8)
			data[index+1] = byte(green >> 8)
			data[index+2] = byte(red >> 8)
			data[index+3] = byte(alpha >> 8)
		}
	}
	return bitmap, width, height, nil
}

// DrawLogo alpha-blends a bitmap onto device, scaled to the destination size.
func DrawLogo(device, bitmap uintptr, sourceWidth, sourceHeight, x, y, width, height int32) {
	memory, _, _ := win32.CreateCompatibleDC.Call(device)
	if memory == 0 {
		return
	}
	defer win32.DeleteDC.Call(memory)
	oldBitmap, _, _ := win32.SelectObject.Call(memory, bitmap)
	// AlphaBlend takes BLENDFUNCTION by value, not by pointer. The earlier
	// pointer form silently turned the alpha fields into address bits and made
	// the otherwise valid logo bitmap disappear.
	blend := blendValue()
	win32.AlphaBlend.Call(
		device,
		uintptr(x),
		uintptr(y),
		uintptr(width),
		uintptr(height),
		memory,
		0,
		0,
		uintptr(sourceWidth),
		uintptr(sourceHeight),
		blend,
	)
	win32.SelectObject.Call(memory, oldBitmap)
}

func blendValue() uintptr {
	return uintptr(255)<<16 | uintptr(1)<<24
}
