//go:build windows

package main

import (
	"syscall"
	"unsafe"
)

const (
	hwndBroadcast   = 0xffff
	wmSettingChange = 0x001a
	smtoAbortIfHung = 0x0002
)

var notifyEnvironmentChanged = notifyEnvironmentChangedImpl

// notifyEnvironmentChanged tells already-running Windows desktop processes
// that the user environment has changed so newly launched shells can observe
// the updated PATH without requiring Explorer to be restarted.
func notifyEnvironmentChangedImpl() {
	user32 := syscall.NewLazyDLL("user32.dll")
	sendMessageTimeout := user32.NewProc("SendMessageTimeoutW")
	environment := syscall.StringToUTF16Ptr("Environment")
	_, _, _ = sendMessageTimeout.Call(
		uintptr(hwndBroadcast),
		uintptr(wmSettingChange),
		0,
		uintptr(unsafe.Pointer(environment)),
		uintptr(smtoAbortIfHung),
		5000,
		0,
	)
}
