//go:build windows

package wizard

import (
	"fmt"
	"strings"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"synesis/bootstrap/internal/install"
	"synesis/bootstrap/internal/install/installfs"
	"synesis/bootstrap/internal/install/layout"
	"synesis/bootstrap/internal/protocol"
	"synesis/bootstrap/internal/wizard/draw"
	"synesis/bootstrap/internal/wizard/win32"
)

func installerOperationSteps(action installerAction, metadata bool) []installerStep {
	if action == installerActionUninstall {
		metadataTitle := "Preserve metadata"
		if metadata {
			metadataTitle = "Remove metadata"
		}
		return []installerStep{
			{id: "shell", title: "Remove shell integration", status: "pending"},
			{id: "runtime", title: "Remove application files", status: "pending"},
			{id: "metadata", title: metadataTitle, status: "pending"},
			{id: "cleanup", title: "Clean obsolete installer files", status: "pending"},
		}
	}
	return []installerStep{
		{id: "verify", title: "Verify local bundle", status: "pending"},
		{id: "runtime", title: "Activate runtime files", status: "pending"},
		{id: "shell", title: "Update shell integration", status: "pending"},
		{id: "protocol", title: "Register synesis:// link handler", status: "pending"},
	}
}

func (w *installerWizard) startOperation() {
	if w.busy {
		return
	}
	w.busy = true
	w.mu.Lock()
	w.steps = installerOperationSteps(w.action, w.metadata)
	w.logEntries = nil
	w.operationStatus = "Preparing the operation..."
	w.result = installerOperationResult{}
	w.mu.Unlock()
	w.logRendered = 0
	win32.SendMessage.Call(w.controls[controlProgressLog], 0x0184, 0, 0) // LB_RESETCONTENT
	w.showPage(installerPageProgress)
	root := w.installRoot
	action := w.action
	metadata := w.metadata
	bundle := w.bundle
	execute := w.executeOperation
	if execute == nil {
		execute = executeInstallerOperation
	}
	go func() {
		result := execute(root, bundle, action, metadata, w.reportProgress)
		if result.err != nil {
			w.reportProgress("", "failed", result.err.Error())
		}
		w.mu.Lock()
		w.result = result
		w.mu.Unlock()
		win32.PostMessage.Call(w.hwnd, installerMessageDone, 0, 0)
	}()
}

func executeInstallerOperation(root, bundle string, action installerAction, metadata bool, observer install.OperationProgress) installerOperationResult {
	result := installerOperationResult{}
	paths, err := layout.Resolve(root)
	if err != nil {
		result.err = err
		return result
	}
	if action == installerActionUninstall {
		args := []string{"--install-dir", paths.Root}
		if metadata {
			args = append(args, "--remove-metadata")
		}
		result.err = install.RunUninstall(args, observer)
		return result
	}
	args := []string{"--bundle", bundle, "--install-dir", paths.Root}
	if installfs.FileExists(paths.Current) {
		result.err = install.RunRepair(args, observer)
	} else {
		result.err = install.RunInstall("install", args, observer)
	}
	if result.err == nil {
		install.ReportOperation([]install.OperationProgress{observer}, "protocol", "working", "Registering the owned synesis:// link handler")
		if registrationErr := protocol.Register(paths); registrationErr != nil {
			result.warning = "Windows could not register the synesis:// link handler: " + registrationErr.Error()
			install.ReportOperation([]install.OperationProgress{observer}, "protocol", "warning", result.warning)
		} else {
			install.ReportOperation([]install.OperationProgress{observer}, "protocol", "done", "The synesis:// link handler is registered")
		}
	}
	return result
}

// reportProgress runs on the worker; the posted message is the only UI boundary.
func (w *installerWizard) reportProgress(stage, status, detail string) {
	// Bound retained diagnostics, including untrusted filesystem/error text.
	detail = strings.Map(func(r rune) rune {
		if r < 32 {
			return ' '
		}
		return r
	}, detail)
	if len([]rune(detail)) > 512 {
		detail = string([]rune(detail)[:509]) + "..."
	}
	w.mu.Lock()
	for i := range w.steps {
		if w.steps[i].id == stage || (stage == "" && status == "failed" && w.steps[i].status == "working") {
			w.steps[i].status, w.steps[i].detail = status, detail
		}
	}
	w.operationStatus = detail
	w.logEntries = append(w.logEntries, installerLogEntry{status: status, detail: detail})
	if len(w.logEntries) > 200 {
		w.logEntries = append([]installerLogEntry(nil), w.logEntries[len(w.logEntries)-200:]...)
	}
	w.mu.Unlock()
	win32.PostMessage.Call(w.hwnd, installerMessageProgress, 0, 0)
}

func (w *installerWizard) refreshProgress() {
	w.mu.Lock()
	status := w.operationStatus
	entries := append([]installerLogEntry(nil), w.logEntries...)
	steps := append([]installerStep(nil), w.steps...)
	w.mu.Unlock()
	win32.SetText(w.controls[controlProgressStatus], status)
	count := fmt.Sprintf("%d / %d steps", completedInstallerSteps(steps), len(steps))
	if len(steps) > 0 {
		w.progressMotion.SetTarget(float64(completedInstallerSteps(steps))/float64(len(steps)), time.Now())
	}
	win32.SetText(w.controls[controlProgressCount], count)
	win32.SetText(w.controls[controlProgressBar], count+" complete")
	win32.InvalidateRect.Call(w.controls[controlProgressBar], 0, 0)
	var accessible []string
	for _, step := range steps {
		accessible = append(accessible, step.title+": "+step.status)
	}
	win32.SetText(w.controls[controlProgressSteps], strings.Join(accessible, "; "))
	list := w.controls[controlProgressLog]
	dc, _, _ := win32.GetDC.Call(list)
	previous, _, _ := win32.SelectObject.Call(dc, w.logFont)
	var extent int32
	for _, entry := range entries {
		value, _ := windows.UTF16FromString("[" + entry.status + "] " + entry.detail)
		var size win32.Point
		win32.GDI32.NewProc("GetTextExtentPoint32W").Call(dc, uintptr(unsafe.Pointer(&value[0])), uintptr(len(value)-1), uintptr(unsafe.Pointer(&size)))
		if size.X+w.scaled(28) > extent {
			extent = size.X + w.scaled(28)
		}
	}
	win32.SelectObject.Call(dc, previous)
	win32.ReleaseDC.Call(list, dc)
	win32.SendMessage.Call(list, 0x0194, uintptr(extent), 0) // horizontal scroll only if needed
	top, _, _ := win32.SendMessage.Call(list, 0x018e, 0, 0)  // LB_GETTOPINDEX
	followTail := int(top)+4 >= w.logRendered
	win32.SendMessage.Call(list, 0x0184, 0, 0)
	for _, entry := range entries {
		text := "[" + entry.status + "] " + entry.detail
		win32.SendMessage.Call(list, 0x0180, 0, uintptr(unsafe.Pointer(win32.MustUTF16(text)))) // LB_ADDSTRING
	}
	w.logRendered = len(entries)
	if followTail && len(entries) > 4 {
		top = uintptr(len(entries) - 4)
	}
	win32.SendMessage.Call(list, 0x0197, top, 0) // LB_SETTOPINDEX
	win32.InvalidateRect.Call(w.controls[controlProgressSteps], 0, 1)
	win32.InvalidateRect.Call(list, 0, 1)
}

func (w *installerWizard) handleDone() {
	w.mu.Lock()
	w.mu.Unlock() // acquire the worker's final result before presenting it
	w.refreshProgress()
	if w.result.err == nil && w.progressMotion.Target == 1 && w.progressTimer != 0 {
		// Let the final verified step reach the end before showing the result.
		w.progressComplete = time.Now().Add(draw.ProgressEase + 80*time.Millisecond)
		return
	}
	w.busy = false
	w.showPage(installerPageResult)
}

func (w *installerWizard) closeOrWarn() {
	if w.busy {
		win32.ShowMessage(w.hwnd, "Synesis Setup", "The current operation is still running. Please wait for it to finish.", win32.MbIconInformation)
		return
	}
	win32.DestroyWindow.Call(w.hwnd)
}

// Count only completed stages; no estimate of bytes, elapsed time or partial
// work is implied by the bar. Warnings and failed steps are not successes.
func completedInstallerSteps(steps []installerStep) int {
	completed := 0
	for _, step := range steps {
		if step.status == "done" {
			completed++
		}
	}
	return completed
}
