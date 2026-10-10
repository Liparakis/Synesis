//go:build windows

package main

import (
	"bytes"
	"embed"
	"errors"
	"fmt"
	"image/png"
	"unsafe"
)

// installerLogoPNG is the product mark already used by the Synesis browser
// surface. Embedding it keeps the native installer self-contained.
//
//go:embed assets/synesis-logo.png
var installerLogoPNG []byte

//go:embed assets/setup/*.png
var installerArtwork embed.FS

type installerArt struct {
	bitmap        uintptr
	width, height int32
}

func createInstallerLogo(hwnd uintptr) (bitmap uintptr, width, height int32, err error) {
	return createInstallerBitmap(hwnd, installerLogoPNG)
}

func createInstallerBitmap(hwnd uintptr, dataPNG []byte) (bitmap uintptr, width, height int32, err error) {
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
	info := installerBitmapInfo{header: installerBitmapInfoHeader{
		size:        40,
		width:       width,
		height:      -height,
		planes:      1,
		bitCount:    32,
		compression: 0,
	}}
	hdc, _, _ := installerGetDC.Call(hwnd)
	if hdc == 0 {
		return 0, 0, 0, errors.New("unable to create a logo device context")
	}
	defer installerReleaseDC.Call(hwnd, hdc)
	var pixels unsafe.Pointer
	bitmap, _, callErr := installerCreateDIBSection.Call(
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

func drawInstallerLogo(device, bitmap uintptr, sourceWidth, sourceHeight, x, y, width, height int32) {
	memory, _, _ := installerCreateCompatibleDC.Call(device)
	if memory == 0 {
		return
	}
	defer installerDeleteDC.Call(memory)
	oldBitmap, _, _ := installerSelectObject.Call(memory, bitmap)
	// AlphaBlend takes BLENDFUNCTION by value, not by pointer. The earlier
	// pointer form silently turned the alpha fields into address bits and made
	// the otherwise valid logo bitmap disappear.
	blend := installerBlendValue()
	installerAlphaBlend.Call(
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
	installerSelectObject.Call(memory, oldBitmap)
}

func installerBlendValue() uintptr {
	return uintptr(255)<<16 | uintptr(1)<<24
}
