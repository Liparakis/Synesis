//go:build windows

package linkfirewall

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"

	"synesis/bootstrap/internal/childproc"
)

func hide(command *exec.Cmd) { childproc.Hide(command) }

// Allow replaces the rule with one for program. Windows asks the user to
// approve; declining is reported as an error and changes nothing.
func Allow(program string) error {
	return elevate("link-firewall", "allow", "--program", program)
}

// Remove deletes the rule when one exists, asking the user to approve.
func Remove() error {
	if !Exists() {
		return nil
	}
	return elevate("link-firewall", "remove")
}

// shellExecuteInfo is SHELLEXECUTEINFOW.
type shellExecuteInfo struct {
	size          uint32
	mask          uint32
	window        uintptr
	verb          *uint16
	file          *uint16
	parameters    *uint16
	directory     *uint16
	show          int32
	instance      uintptr
	idList        uintptr
	class         *uint16
	classKey      uintptr
	hotKey        uint32
	iconOrMonitor uintptr
	process       windows.Handle
}

const seeMaskNoCloseProcess = 0x40

// elevate runs this installer again with args under the "runas" verb, which
// makes Windows show its approval prompt, and waits for it to finish.
func elevate(args ...string) error {
	self, err := os.Executable()
	if err != nil {
		return err
	}
	verb, _ := windows.UTF16PtrFromString("runas")
	file, err := windows.UTF16PtrFromString(self)
	if err != nil {
		return err
	}
	parameters, err := windows.UTF16PtrFromString(windows.ComposeCommandLine(args))
	if err != nil {
		return err
	}
	info := shellExecuteInfo{mask: seeMaskNoCloseProcess, verb: verb, file: file, parameters: parameters, show: windows.SW_HIDE}
	info.size = uint32(unsafe.Sizeof(info))
	result, _, callErr := windows.NewLazySystemDLL("shell32.dll").NewProc("ShellExecuteExW").Call(uintptr(unsafe.Pointer(&info)))
	if result == 0 {
		if errors.Is(callErr, syscall.Errno(windows.ERROR_CANCELLED)) {
			return errors.New("administrator approval was declined")
		}
		return fmt.Errorf("could not start the elevated installer: %w", callErr)
	}
	defer windows.CloseHandle(info.process)
	if _, err := windows.WaitForSingleObject(info.process, windows.INFINITE); err != nil {
		return err
	}
	var code uint32
	if err := windows.GetExitCodeProcess(info.process, &code); err != nil {
		return err
	}
	if code != 0 {
		return fmt.Errorf("the elevated installer exited with %d", code)
	}
	return nil
}
