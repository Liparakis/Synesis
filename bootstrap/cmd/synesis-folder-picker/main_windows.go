//go:build windows

// Command synesis-folder-picker opens the Windows Explorer folder picker and
// writes the selected filesystem path to standard output.
package main

import (
	"errors"
	"fmt"
	"os"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

const (
	coinitApartmentThreaded = 0x2
	clsctxInprocServer      = 0x1
	fosPickFolders          = 0x20
	fosForceFileSystem      = 0x40
	fosPathMustExist        = 0x800
	sigdnFileSysPath        = 0x80058000
	hresultCancelled        = 0x800704C7
)

var (
	ole32                  = windows.NewLazySystemDLL("ole32.dll")
	procCoInitializeEx     = ole32.NewProc("CoInitializeEx")
	procCoCreateInstance   = ole32.NewProc("CoCreateInstance")
	procCoUninitialize     = ole32.NewProc("CoUninitialize")
	procCoTaskMemFree      = ole32.NewProc("CoTaskMemFree")
	clsidFileOpenDialog, _ = windows.GUIDFromString("{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}")
	iidFileOpenDialog, _   = windows.GUIDFromString("{D57C7288-D4AD-4768-BE02-9D969532D960}")
)

func main() {
	path, err := pickFolder()
	if errors.Is(err, errCancelled) {
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Fprintln(os.Stdout, path)
}

var errCancelled = errors.New("folder selection cancelled")

func pickFolder() (string, error) {
	result, _, callErr := procCoInitializeEx.Call(0, coinitApartmentThreaded)
	if int32(result) < 0 {
		return "", fmt.Errorf("initialize Windows shell picker: HRESULT 0x%08X", uint32(result))
	}
	defer procCoUninitialize.Call()

	var dialog uintptr
	result, _, callErr = procCoCreateInstance.Call(
		uintptr(unsafe.Pointer(&clsidFileOpenDialog)),
		0,
		clsctxInprocServer,
		uintptr(unsafe.Pointer(&iidFileOpenDialog)),
		uintptr(unsafe.Pointer(&dialog)),
	)
	if int32(result) < 0 || dialog == 0 {
		return "", fmt.Errorf("create Windows shell picker: HRESULT 0x%08X (%v)", uint32(result), callErr)
	}
	defer comRelease(dialog)

	var options uint32
	if result = comCall(dialog, 10, uintptr(unsafe.Pointer(&options))); int32(result) < 0 {
		return "", hresultError("read Windows shell picker options", result)
	}
	options |= fosPickFolders | fosForceFileSystem | fosPathMustExist
	if result = comCall(dialog, 9, uintptr(options)); int32(result) < 0 {
		return "", hresultError("configure Windows shell picker", result)
	}
	title, err := windows.UTF16FromString("Select Synesis project folder")
	if err != nil {
		return "", fmt.Errorf("encode Windows shell picker title: %w", err)
	}
	if result = comCall(dialog, 17, uintptr(unsafe.Pointer(&title[0]))); int32(result) < 0 {
		return "", hresultError("set Windows shell picker title", result)
	}
	approve, err := windows.UTF16FromString("Select folder")
	if err != nil {
		return "", fmt.Errorf("encode Windows shell picker action: %w", err)
	}
	if result = comCall(dialog, 18, uintptr(unsafe.Pointer(&approve[0]))); int32(result) < 0 {
		return "", hresultError("set Windows shell picker action", result)
	}

	result = comCall(dialog, 3, 0)
	if uint32(result) == hresultCancelled {
		return "", errCancelled
	}
	if int32(result) < 0 {
		return "", hresultError("show Windows shell picker", result)
	}

	var item uintptr
	if result = comCall(dialog, 20, uintptr(unsafe.Pointer(&item))); int32(result) < 0 || item == 0 {
		return "", hresultError("read Windows shell picker result", result)
	}
	defer comRelease(item)

	var displayName *uint16
	if result = comCall(item, 5, sigdnFileSysPath, uintptr(unsafe.Pointer(&displayName))); int32(result) < 0 || displayName == nil {
		return "", hresultError("read selected Windows folder", result)
	}
	defer procCoTaskMemFree.Call(uintptr(unsafe.Pointer(displayName)))
	return windows.UTF16PtrToString(displayName), nil
}

func hresultError(operation string, result uintptr) error {
	return fmt.Errorf("%s: HRESULT 0x%08X", operation, uint32(result))
}

func comCall(object uintptr, method uintptr, args ...uintptr) uintptr {
	vtable := *(*uintptr)(unsafe.Pointer(object))
	entry := *(*uintptr)(unsafe.Pointer(vtable + method*unsafe.Sizeof(uintptr(0))))
	values := make([]uintptr, 0, len(args)+1)
	values = append(values, object)
	values = append(values, args...)
	result, _, _ := syscall.SyscallN(entry, values...)
	return result
}

func comRelease(object uintptr) {
	_ = comCall(object, 2)
}
