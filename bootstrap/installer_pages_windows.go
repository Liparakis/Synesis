//go:build windows

package main

import (
	"strings"
	"time"
)

func (w *installerWizard) showPage(page installerPage) {
	w.page = page
	w.resultDetailsOpen = false
	if page != installerPageProgress {
		installerUser32.NewProc("KillTimer").Call(w.hwnd, 1)
		w.progressTimer = 0
		w.progressComplete = time.Time{}
	}
	w.setPageIcon(page)
	for _, control := range w.controls {
		setInstallerVisible(control, false)
	}
	w.layoutPage(page)
	w.rebuildBackdrop()
	setInstallerVisible(w.controls[controlTitle], true)
	setInstallerVisible(w.controls[controlDescription], true)

	title := "Synesis Setup"
	description := "Choose an action to continue."
	switch page {
	case installerPageAction:
		title = "Manage installation"
		description = "Choose what you want to do with the current installation."
		setInstallerVisible(w.controls[controlCapabilities], true)
		setInstallerVisible(w.controls[controlCapabilityInstall], true)
		setInstallerVisible(w.controls[controlCapabilityLink], true)
		setInstallerVisible(w.controls[controlCapabilityData], true)
		setInstallerVisible(w.controls[controlActionGroup], true)
		setInstallerVisible(w.controls[controlActionInstall], true)
		setInstallerVisible(w.controls[controlActionUninstall], true)
		setInstallerVisible(w.controls[controlActionPath], true)
		setInstallerText(w.controls[controlProductName], "")
		setInstallerText(w.controls[controlProductDetails], "")
		setInstallerText(w.controls[controlCapabilities], "Setup includes")
		setInstallerText(w.controls[controlCapabilityInstall], "Local runtime")
		setInstallerText(w.controls[controlCapabilityLink], "synesis:// link handler")
		setInstallerText(w.controls[controlCapabilityData], "Existing project and workspace data is kept")
		setInstallerText(w.controls[controlActionPath], "Installed at "+w.installRoot)
	case installerPageInstall:
		title = "Choose install location"
		description = "Choose the folder where Synesis will be installed.\r\nThis is where the application files will be placed."
		setInstallerVisible(w.controls[controlInstallGroup], true)
		setInstallerVisible(w.controls[controlInstallEdit], true)
		setInstallerVisible(w.controls[controlBrowse], true)
		setInstallerVisible(w.controls[controlInstallHint], true)
		// Say what will change on this machine before the user commits to it.
		setInstallerVisible(w.controls[controlCapabilities], true)
		setInstallerVisible(w.controls[controlCapabilityInstall], true)
		setInstallerVisible(w.controls[controlCapabilityLink], true)
		setInstallerVisible(w.controls[controlCapabilityData], true)
		runtimeLine := "Install the Synesis runtime in this folder"
		if w.bundleVersion != "" {
			runtimeLine = "Install Synesis " + w.bundleVersion + " in this folder"
		}
		setInstallerText(w.controls[controlCapabilities], "Setup will")
		setInstallerText(w.controls[controlCapabilityInstall], runtimeLine)
		setInstallerText(w.controls[controlCapabilityLink], "Register the synesis:// link handler")
		setInstallerText(w.controls[controlCapabilityData], "Add the Synesis bin folder to your user PATH")
		setInstallerText(w.controls[controlInstallGroup], "Destination folder")
		if w.installDraft == "" {
			w.installDraft = w.installRoot
		}
		setInstallerText(w.controls[controlInstallEdit], w.installDraft)
		setInstallerText(w.controls[controlInstallHint], "Projects, repositories, workspaces, and provider configuration are not installed or removed here.")
	case installerPageUninstall:
		title = "Uninstall"
		description = "Remove the application from this computer.\r\nYour projects, repositories, and workspace data will be preserved."
		setInstallerVisible(w.controls[controlUninstallGroup], true)
		setInstallerVisible(w.controls[controlUninstallPathLabel], true)
		setInstallerVisible(w.controls[controlUninstallPath], true)
		setInstallerVisible(w.controls[controlMetadata], true)
		setInstallerVisible(w.controls[controlUninstallWarning], true)
		setInstallerVisible(w.controls[controlUninstallImpact], true)
		setInstallerText(w.controls[controlUninstallPath], w.installRoot)
		setInstallerText(w.controls[controlMetadata], "Remove metadata")
		setInstallerText(w.controls[controlUninstallWarning], "Removes local identity and installer state.\r\nProject, repository, and workspace data stay intact.")
		setInstallerText(w.controls[controlUninstallImpact], "Application files and the synesis:// handler will be removed.")
	case installerPageProgress:
		w.progressEpoch = time.Now()
		w.progressMotion = installerProgressMotion{}
		w.progressComplete = time.Time{}
		if w.action == installerActionUninstall {
			title = "Uninstalling"
		} else {
			title = "Installing"
		}
		description = "Setting up the local runtime and shell integration. This may take a minute."
		if w.action == installerActionUninstall {
			description = "Removing the application and shell integration. Your project data is preserved."
		}
		setInstallerVisible(w.controls[controlProgressStatus], true)
		setInstallerVisible(w.controls[controlProgressBar], true)
		setInstallerVisible(w.controls[controlProgressCount], true)
		setInstallerVisible(w.controls[controlProgressSteps], true)
		setInstallerVisible(w.controls[controlProgressLogTitle], true)
		setInstallerVisible(w.controls[controlProgressLog], true)
		if w.action == installerActionUninstall {
			setInstallerText(w.controls[controlProgressLogTitle], "Uninstallation log")
		} else {
			setInstallerText(w.controls[controlProgressLogTitle], "Installation log")
		}
		setInstallerText(w.controls[controlProgressStatus], "Preparing the operation...")
		w.refreshProgress()
		w.progressTimer, _, _ = installerUser32.NewProc("SetTimer").Call(w.hwnd, 1, 16, 0)
	case installerPageResult:
		title, description = "Installed", "Synesis is ready to use."
		if w.bundleVersion != "" {
			description = "Synesis " + w.bundleVersion + " is ready to use."
		}
		if w.action == installerActionUninstall {
			title, description = "Uninstalled", "The application has been removed."
		}
		if w.result.err != nil {
			title = "Installation incomplete"
			if w.action == installerActionUninstall {
				title = "Uninstall incomplete"
			}
			description = w.resultFailureDescription()
		} else if w.result.warning != "" {
			description = "Application installed. Link registration needs attention."
		}
		setInstallerVisible(w.controls[controlResult], true)
		setInstallerText(w.controls[controlResult], w.resultText())
		setInstallerVisible(w.controls[controlUninstallPathLabel], true)
		setInstallerVisible(w.controls[controlUninstallPath], true)
		setInstallerText(w.controls[controlUninstallPath], w.installRoot)
		details := w.resultDetailsText()
		setInstallerText(w.controls[controlResultDetails], details)
		setInstallerText(w.controls[controlResultDetailsToggle], "Show details")
		setInstallerVisible(w.controls[controlResultDetailsToggle], details != "")
		setInstallerText(w.controls[controlResultNext], "1.  Open a new terminal so the updated PATH is picked up.\r\n"+
			"2.  Run  synesis --version  to confirm the installation.\r\n"+
			"3.  In a project folder, run  synesis init  to get started.")
		setInstallerVisible(w.controls[controlResultNextTitle], w.showsNextSteps())
		setInstallerVisible(w.controls[controlResultNext], w.showsNextSteps())
	}
	setInstallerText(w.controls[controlTitle], title)
	setInstallerText(w.controls[controlDescription], description)

	showBack := page == installerPageInstall || page == installerPageUninstall || page == installerPageProgress
	showNext := page == installerPageAction || page == installerPageInstall || page == installerPageUninstall || page == installerPageProgress
	setInstallerVisible(w.controls[controlBack], showBack)
	setInstallerVisible(w.controls[controlNext], showNext)
	setInstallerVisible(w.controls[controlCancel], true)
	setInstallerEnabled(w.controls[controlBack], !w.busy)
	setInstallerEnabled(w.controls[controlNext], !w.busy)
	setInstallerEnabled(w.controls[controlCancel], !w.busy)
	if page == installerPageResult {
		setInstallerText(w.controls[controlCancel], "Close")
	} else {
		setInstallerText(w.controls[controlCancel], "Cancel")
	}
	if showNext {
		w.updateActionLabel()
	}
	if page == installerPageAction {
		installerSetFocus.Call(w.controls[controlActionInstall])
	} else if page == installerPageInstall {
		installerSetFocus.Call(w.controls[controlInstallEdit])
	}
	// Paint the new parent surface before refreshing its child controls. The
	// posted second pass runs after the current navigation message has unwound;
	// this keeps the branded chrome and unchanged footer controls from retaining
	// stale pixels across page transitions.
	w.refreshPage()
	installerPostMessage.Call(w.hwnd, installerMessageRefresh, 0, 0)
}

func (w *installerWizard) refreshPage() {
	// Controls move between page layouts. Repaint their former bounds as well as
	// their new bounds, including the entire right-hand footer and brand area.
	installerInvalidateRect.Call(w.hwnd, 0, 1)
	installerUpdateWindow.Call(w.hwnd)
	for _, control := range w.controls {
		installerRedrawWindow.Call(control, 0, 0, rdwInvalidate|rdwErase|rdwUpdateNow)
	}
}

func (w *installerWizard) refreshEntireWindow() {
	installerRedrawWindow.Call(w.hwnd, 0, 0, rdwInvalidate|rdwErase|rdwAllChildren|rdwUpdateNow|rdwFrame)
	for _, control := range w.controls {
		installerRedrawWindow.Call(control, 0, 0, rdwInvalidate|rdwErase|rdwUpdateNow)
	}
}

// layoutPage confines shared controls to the space actually available on each
// page while keeping the compact maintenance layout aligned to the footer.
func (w *installerWizard) layoutPage(page installerPage) {
	move := func(id int, x, y, width, height int32) {
		installerMoveWindow.Call(w.controls[id], uintptr(w.scaled(x)), uintptr(w.scaled(y)),
			uintptr(w.scaled(width)), uintptr(w.scaled(height)), 0)
	}
	headerWidth := int32(1040)
	move(controlTitle, 60, 48, headerWidth, 84)
	descriptionHeight := int32(60)
	move(controlDescription, 60, 132, headerWidth, descriptionHeight)
	move(controlNext, 980, 894, 175, 64)
	move(controlCancel, 1175, 894, 165, 64)
	move(controlBack, 785, 894, 175, 64)
	if page == installerPageInstall {
		move(controlDescription, 60, 132, headerWidth, 96)
		move(controlInstallGroup, 60, 330, 1280, 42)
		move(controlInstallEdit, 74, 405, 1008, 54)
		move(controlBrowse, 1120, 390, 220, 82)
		move(controlInstallHint, 60, 496, 1260, 44)
		move(controlCapabilities, 60, 566, 880, 38)
		move(controlCapabilityInstall, 120, 610, 1100, 42)
		move(controlCapabilityLink, 120, 652, 1100, 42)
		move(controlCapabilityData, 120, 694, 1100, 42)
		move(controlBack, 785, 825, 175, 72)
		move(controlNext, 980, 825, 175, 72)
		move(controlCancel, 1175, 825, 165, 72)
	}
	if page == installerPageProgress || page == installerPageResult {
		move(controlDescription, 60, 132, headerWidth, 90)
		move(controlProgressStatus, 60, 280, 1280, 40)
		move(controlProgressBar, 60, 328, 1050, 36)
		move(controlProgressCount, 1140, 324, 200, 44)
		move(controlProgressSteps, 60, 384, 1280, 272)
		move(controlProgressLogTitle, 60, 660, 1280, 50)
		move(controlProgressLog, 60, 718, 1280, 145)
		move(controlResult, 60, 300, 1250, 310)
	}
	if page == installerPageResult {
		move(controlResult, 88, 320, 1224, 96)
		move(controlUninstallPathLabel, 88, 432, 1224, 38)
		move(controlUninstallPath, 102, 494, 1196, 40)
		// The status badge sits in the header's left margin, so the header copy
		// moves right to stay one aligned block.
		move(controlTitle, 148, 48, headerWidth-88, 84)
		move(controlDescription, 148, 132, headerWidth-88, 90)
		toggleY := int32(596)
		if w.showsNextSteps() {
			move(controlResultNextTitle, 60, 598, 1280, 40)
			move(controlResultNext, 60, 644, 1280, 126)
			toggleY = 790
		}
		move(controlResultDetailsToggle, 60, toggleY, 300, 52)
		move(controlResultDetails, 74, 668, 1252, 182)
	}
	if page == installerPageAction {
		move(controlCapabilities, 60, 220, 880, 38)
		move(controlCapabilityInstall, 120, 268, 950, 42)
		move(controlCapabilityLink, 120, 312, 950, 42)
		move(controlCapabilityData, 120, 356, 950, 42)
		move(controlActionGroup, 60, 430, 1280, 40)
		move(controlActionInstall, 60, 490, 1280, 120)
		move(controlActionUninstall, 60, 626, 1280, 120)
		move(controlActionPath, 60, 840, 900, 42)
		move(controlNext, 980, 825, 175, 72)
		move(controlCancel, 1175, 825, 165, 72)
	}
	if page == installerPageUninstall {
		move(controlDescription, 60, 132, headerWidth, 96)
		move(controlUninstallGroup, 88, 328, 1190, 44)
		move(controlUninstallPathLabel, 88, 384, 1190, 40)
		move(controlUninstallPath, 104, 454, 1192, 42)
		move(controlMetadata, 88, 528, 1220, 48)
		move(controlUninstallWarning, 136, 584, 1172, 80)
		move(controlUninstallImpact, 164, 721, 1144, 42)
		move(controlBack, 785, 825, 175, 72)
		move(controlNext, 980, 825, 175, 72)
		move(controlCancel, 1175, 825, 165, 72)
	}
}

// headerDividerY is the design-space row of the rule under the page header.
func (w *installerWizard) headerDividerY() int32 {
	if w.page == installerPageAction {
		return 192
	}
	return 250
}

// installActionDetail describes what the primary action will do to the version
// already on this machine, when both versions are known.
func (w *installerWizard) installActionDetail() string {
	const kept = "Your identity, projects, and workspace data are kept."
	switch {
	case w.bundleVersion == "":
		return "Keep your identity, projects, and workspace data."
	case !w.installed:
		return "Install Synesis " + w.bundleVersion + "."
	case w.installedVersion == "":
		return "Install Synesis " + w.bundleVersion + ". " + kept
	case w.installedVersion == w.bundleVersion:
		return "Reinstall " + w.bundleVersion + ". " + kept
	default:
		return "Update " + w.installedVersion + " to " + w.bundleVersion + "."
	}
}

// showsNextSteps reports whether the result page has room for, and reason to
// show, what to do after a successful install. Open details take that space.
func (w *installerWizard) showsNextSteps() bool {
	return w.page == installerPageResult && w.result.err == nil && w.action != installerActionUninstall && !w.resultDetailsOpen
}

// resultBadge selects the status mark painted beside the result title.
func (w *installerWizard) resultBadge() (kind string, color uintptr) {
	switch {
	case w.result.err != nil:
		return "badge-failed", installerStatusColor("failed")
	case w.result.warning != "":
		return "badge-warning", installerStatusColor("warning")
	default:
		return "badge-done", installerStatusColor("done")
	}
}

func (w *installerWizard) resultText() string {
	if w.result.err != nil {
		return "Some changes may already have been applied."
	}
	if w.action == installerActionUninstall {
		metadata := "Installer-owned metadata was preserved."
		if w.metadata {
			metadata = "Installer-owned metadata was removed."
		}
		return metadata +
			"\r\nYour projects, repositories, and workspace data were left untouched."
	}
	protocol := "The synesis:// link handler is registered."
	if w.result.warning != "" {
		protocol = "The synesis:// link handler could not be registered."
	}
	return "Runtime and shell integration are ready.\r\n" + protocol
}

func (w *installerWizard) resultFailureDescription() string {
	w.mu.Lock()
	defer w.mu.Unlock()
	stage := ""
	for _, step := range w.steps {
		if step.status == "failed" {
			stage = step.id
			break
		}
	}
	if w.action == installerActionUninstall {
		switch stage {
		case "runtime":
			return "Application files could not be removed."
		case "shell":
			return "Shell integration could not be fully removed."
		case "metadata":
			return "Installer metadata could not be removed."
		case "cleanup":
			return "Some installer files could not be cleaned up."
		}
		return "Synesis could not be fully removed."
	}
	switch stage {
	case "verify":
		return "The local installation bundle could not be verified."
	case "runtime":
		return "Application files could not be installed."
	case "shell":
		return "Shell integration could not be updated."
	}
	return "Setup could not finish the installation."
}

func (w *installerWizard) resultDetailsText() string {
	w.mu.Lock()
	entries := append([]installerLogEntry(nil), w.logEntries...)
	w.mu.Unlock()
	var sections []string
	if w.result.err != nil {
		message := []rune(strings.Join(strings.Fields(w.result.err.Error()), " "))
		if len(message) > 4096 {
			message = append(message[:4093], '.', '.', '.')
		}
		sections = append(sections, "Error: "+string(message))
	} else if w.result.warning != "" {
		sections = append(sections, w.result.warning)
	}
	var history []string
	for _, entry := range entries {
		history = append(history, "["+entry.status+"] "+entry.detail)
	}
	if len(history) > 0 {
		sections = append(sections, strings.Join(history, "\r\n"))
	}
	return strings.Join(sections, "\r\n\r\n")
}

func (w *installerWizard) toggleResultDetails() {
	if w.page != installerPageResult || w.busy {
		return
	}
	w.resultDetailsOpen = !w.resultDetailsOpen
	label := "Show details"
	if w.resultDetailsOpen {
		label = "Hide details"
	}
	setInstallerText(w.controls[controlResultDetailsToggle], label)
	setInstallerVisible(w.controls[controlResultDetails], w.resultDetailsOpen)
	w.layoutPage(w.page)
	setInstallerVisible(w.controls[controlResultNextTitle], w.showsNextSteps())
	setInstallerVisible(w.controls[controlResultNext], w.showsNextSteps())
	if w.resultDetailsOpen {
		// Start with the error summary, even after earlier diagnostic scrolling.
		installerSendMessage.Call(w.controls[controlResultDetails], 0x00b1, 0, 0) // EM_SETSEL
		installerSendMessage.Call(w.controls[controlResultDetails], 0x00b7, 0, 0) // EM_SCROLLCARET
	}
	w.rebuildBackdrop()
	w.refreshPage()
}

func (w *installerWizard) invalidateActionCards() {
	w.updateActionLabel()
	for _, controlID := range []int{controlActionInstall, controlActionUninstall} {
		if control := w.controls[controlID]; control != 0 {
			installerInvalidateRect.Call(control, 0, 1)
		}
	}
}

func (w *installerWizard) updateActionLabel() {
	label := "Install"
	if w.page == installerPageUninstall || (w.page == installerPageAction && w.action == installerActionUninstall) {
		label = "Uninstall"
	}
	if w.page == installerPageProgress {
		label = "Next"
	}
	setInstallerText(w.controls[controlNext], label)
}
