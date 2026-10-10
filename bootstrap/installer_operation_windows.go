//go:build windows

package main

import (
	"fmt"
	"strings"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
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
	installerSendMessage.Call(w.controls[controlProgressLog], 0x0184, 0, 0) // LB_RESETCONTENT
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
		installerPostMessage.Call(w.hwnd, installerMessageDone, 0, 0)
	}()
}

func executeInstallerOperation(root, bundle string, action installerAction, metadata bool, observer operationProgress) installerOperationResult {
	result := installerOperationResult{}
	paths, err := installationPaths(root)
	if err != nil {
		result.err = err
		return result
	}
	if action == installerActionUninstall {
		args := []string{"--install-dir", paths.root}
		if metadata {
			args = append(args, "--remove-metadata")
		}
		result.err = runUninstall(args, observer)
		return result
	}
	args := []string{"--bundle", bundle, "--install-dir", paths.root}
	if fileExists(paths.current) {
		result.err = runRepair(args, observer)
	} else {
		result.err = runInstall("install", args, observer)
	}
	if result.err == nil {
		reportOperation([]operationProgress{observer}, "protocol", "working", "Registering the owned synesis:// link handler")
		if registrationErr := registerProtocol(paths); registrationErr != nil {
			result.warning = "Windows could not register the synesis:// link handler: " + registrationErr.Error()
			reportOperation([]operationProgress{observer}, "protocol", "warning", result.warning)
		} else {
			reportOperation([]operationProgress{observer}, "protocol", "done", "The synesis:// link handler is registered")
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
	installerPostMessage.Call(w.hwnd, installerMessageProgress, 0, 0)
}

func (w *installerWizard) refreshProgress() {
	w.mu.Lock()
	status := w.operationStatus
	entries := append([]installerLogEntry(nil), w.logEntries...)
	steps := append([]installerStep(nil), w.steps...)
	w.mu.Unlock()
	setInstallerText(w.controls[controlProgressStatus], status)
	count := fmt.Sprintf("%d / %d steps", completedInstallerSteps(steps), len(steps))
	if len(steps) > 0 {
		w.progressMotion.setTarget(float64(completedInstallerSteps(steps))/float64(len(steps)), time.Now())
	}
	setInstallerText(w.controls[controlProgressCount], count)
	setInstallerText(w.controls[controlProgressBar], count+" complete")
	installerInvalidateRect.Call(w.controls[controlProgressBar], 0, 0)
	var accessible []string
	for _, step := range steps {
		accessible = append(accessible, step.title+": "+step.status)
	}
	setInstallerText(w.controls[controlProgressSteps], strings.Join(accessible, "; "))
	list := w.controls[controlProgressLog]
	dc, _, _ := installerGetDC.Call(list)
	previous, _, _ := installerSelectObject.Call(dc, w.logFont)
	var extent int32
	for _, entry := range entries {
		value, _ := windows.UTF16FromString("[" + entry.status + "] " + entry.detail)
		var size installerPoint
		installerGDI32.NewProc("GetTextExtentPoint32W").Call(dc, uintptr(unsafe.Pointer(&value[0])), uintptr(len(value)-1), uintptr(unsafe.Pointer(&size)))
		if size.x+w.scaled(28) > extent {
			extent = size.x + w.scaled(28)
		}
	}
	installerSelectObject.Call(dc, previous)
	installerReleaseDC.Call(list, dc)
	installerSendMessage.Call(list, 0x0194, uintptr(extent), 0) // horizontal scroll only if needed
	top, _, _ := installerSendMessage.Call(list, 0x018e, 0, 0)  // LB_GETTOPINDEX
	followTail := int(top)+4 >= w.logRendered
	installerSendMessage.Call(list, 0x0184, 0, 0)
	for _, entry := range entries {
		text := "[" + entry.status + "] " + entry.detail
		installerSendMessage.Call(list, 0x0180, 0, uintptr(unsafe.Pointer(mustInstallerUTF16(text)))) // LB_ADDSTRING
	}
	w.logRendered = len(entries)
	if followTail && len(entries) > 4 {
		top = uintptr(len(entries) - 4)
	}
	installerSendMessage.Call(list, 0x0197, top, 0) // LB_SETTOPINDEX
	installerInvalidateRect.Call(w.controls[controlProgressSteps], 0, 1)
	installerInvalidateRect.Call(list, 0, 1)
}

func (w *installerWizard) handleDone() {
	w.mu.Lock()
	w.mu.Unlock() // acquire the worker's final result before presenting it
	w.refreshProgress()
	if w.result.err == nil && w.progressMotion.target == 1 && w.progressTimer != 0 {
		// Let the final verified step reach the end before showing the result.
		w.progressComplete = time.Now().Add(installerProgressEase + 80*time.Millisecond)
		return
	}
	w.busy = false
	w.showPage(installerPageResult)
}

func (w *installerWizard) closeOrWarn() {
	if w.busy {
		showInstallerMessage(w.hwnd, "Synesis Setup", "The current operation is still running. Please wait for it to finish.", mbIconInformation)
		return
	}
	installerDestroyWindow.Call(w.hwnd)
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
