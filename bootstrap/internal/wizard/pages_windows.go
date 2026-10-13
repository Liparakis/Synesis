//go:build windows

package wizard

import (
	"strings"
	"time"

	"synesis/bootstrap/internal/wizard/draw"
	"synesis/bootstrap/internal/wizard/win32"
)

// placement is where one control sits on a page, in layout pixels.
type placement struct {
	id                  int
	x, y, width, height int32
}

const (
	installerLogoSize       = 42
	installerFieldHeight    = 40
	installerStepRowHeight  = 34
	installerLogRowHeight   = 17
	installerHeaderTop      = 32
	installerBodyTop        = 128
	installerResultHeaderIn = 60 // the outcome badge sits left of the result header
)

// pageLayout lists the controls a page shows and where. A control that is not
// listed is hidden, so a page cannot inherit a stray control from another.
func (w *installerWizard) pageLayout(page installerPage) []placement {
	const left, width = installerContentLeft, installerContentWidth
	right := int32(left + width)
	button := func(id int, slot, buttonWidth int32) placement {
		return placement{id, right - slot, installerButtonTop, buttonWidth, installerButtonHeight}
	}
	header := func(indent int32) []placement {
		return []placement{
			{controlTitle, left + indent, installerHeaderTop, width - indent, 36},
			{controlDescription, left + indent, 72, width - indent, 40},
		}
	}
	back, next, cancel := button(controlBack, 296, 88), button(controlNext, 200, 104), button(controlCancel, 88, 88)
	switch page {
	case installerPageAction:
		controls := append(header(0),
			placement{controlActionInstall, left, 132, width, 72},
			placement{controlActionUninstall, left, 216, width, 72},
			placement{controlInstallHint, left, 306, width, 36},
			next, cancel)
		if w.installed {
			controls = append(controls, placement{controlActionPath, left, installerButtonTop, 272, installerButtonHeight})
		}
		return controls
	case installerPageInstall:
		return append(header(0),
			placement{controlInstallGroup, left, installerBodyTop, width, 20},
			placement{controlInstallEdit, left + 12, 162, 356, 20},
			placement{controlBrowse, right - 96, 152, 96, installerFieldHeight},
			placement{controlInstallHint, left, 202, width, 36},
			placement{controlCapabilities, left, 258, width, 20},
			placement{controlCapabilityInstall, left + 24, 284, width - 24, 20},
			placement{controlCapabilityData, left + 24, 308, width - 24, 20},
			placement{controlCapabilityLink, left + 24, 332, width - 24, 20},
			back, next, cancel)
	case installerPageUninstall:
		return append(header(0),
			placement{controlUninstallPathLabel, left, installerBodyTop, width, 20},
			placement{controlUninstallPath, left + 12, 162, width - 24, 20},
			placement{controlMetadata, left, 212, width, 28},
			placement{controlUninstallWarning, left + 28, 242, width - 28, 40},
			placement{controlUninstallImpact, left + 44, 304, width - 60, 44},
			back, next, cancel)
	case installerPageProgress:
		return append(header(0),
			placement{controlProgressBar, left, 132, width, 6},
			placement{controlProgressStatus, left, 146, width - 100, 20},
			placement{controlProgressCount, right - 100, 146, 100, 20},
			placement{controlProgressSteps, left, 184, width, 5 * installerStepRowHeight},
			placement{controlProgressLog, left + 8, 372, width - 16, installerLogRows * installerLogRowHeight},
			cancel)
	case installerPageResult:
		controls := append(header(installerResultHeaderIn),
			placement{controlResult, left + 16, 142, width - 32, 40},
			placement{controlUninstallPathLabel, left + 16, 190, width - 32, 18},
			placement{controlUninstallPath, left + 16, 212, width - 32, 20},
			cancel)
		if w.showsNextSteps() {
			controls = append(controls,
				placement{controlResultNextTitle, left, 264, width, 20},
				placement{controlResultNext, left, 290, width, 66})
		}
		if w.resultDetailsOpen {
			controls = append(controls, placement{controlResultDetails, left + 10, 272, width - 20, 160})
		}
		if win32.GetText(w.controls[controlResultDetails]) != "" {
			controls = append(controls, placement{controlResultDetailsToggle, left, installerButtonTop, 120, installerButtonHeight})
		}
		return controls
	}
	return nil
}

// applyLayout hides every control, then places and shows the page's own.
func (w *installerWizard) applyLayout() {
	for _, control := range w.controls {
		win32.SetVisible(control, false)
	}
	for _, place := range w.pageLayout(w.page) {
		win32.MoveWindow.Call(w.controls[place.id], uintptr(w.scaled(place.x)), uintptr(w.scaled(place.y)),
			uintptr(w.scaled(place.width)), uintptr(w.scaled(place.height)), 0)
		win32.SetVisible(w.controls[place.id], true)
	}
}

func (w *installerWizard) showPage(page installerPage) {
	w.page = page
	w.resultDetailsOpen = false
	if page != installerPageProgress {
		win32.KillTimer.Call(w.hwnd, 1)
		w.progressTimer = 0
		w.progressComplete = time.Time{}
	}
	set := func(id int, text string) { win32.SetText(w.controls[id], text) }
	var title, description string
	switch page {
	case installerPageAction:
		title = "Install Synesis"
		description = "Set up the Synesis runtime, its command-line tools and the synesis:// link handler for your user account."
		if w.installed {
			title = "Manage Synesis"
			description = "Synesis is installed for your user account. Bring it up to date, or remove it."
		}
		set(controlActionInstall, w.installActionTitle())
		set(controlInstallHint, "Setup only changes application files. Your projects, repositories and workspace data are never touched.")
		set(controlActionPath, "Installed at "+w.installRoot)
	case installerPageInstall:
		title = "Choose a location"
		description = "Synesis installs for your user account only, so it needs no administrator rights."
		runtimeLine := "Install the Synesis runtime in this folder"
		if w.bundleVersion != "" {
			runtimeLine = "Install Synesis " + w.bundleVersion + " in this folder"
		}
		// Say what will change on this machine before the user commits to it.
		set(controlCapabilities, "Setup will")
		set(controlCapabilityInstall, runtimeLine)
		set(controlCapabilityData, "Add the Synesis bin folder to your user PATH")
		set(controlCapabilityLink, "Register the synesis:// link handler")
		if w.installDraft == "" {
			w.installDraft = w.installRoot
		}
		set(controlInstallEdit, w.installDraft)
		set(controlInstallHint, "Projects, repositories, workspaces, and provider configuration are not installed or removed here.")
	case installerPageUninstall:
		title = "Uninstall Synesis"
		description = "Remove the application from this computer.\r\nYour projects, repositories, and workspace data will be preserved."
		set(controlUninstallPath, w.installRoot)
		set(controlUninstallWarning, "Removes local identity and installer state.\r\nProject, repository, and workspace data stay intact.")
		set(controlUninstallImpact, "Application files and the synesis:// handler will be removed.")
	case installerPageProgress:
		w.progressEpoch = time.Now()
		w.progressMotion = draw.ProgressMotion{}
		w.progressComplete = time.Time{}
		title = "Installing Synesis"
		description = "Setting up the local runtime and shell integration. This may take a minute."
		if w.action == installerActionUninstall {
			title = "Removing Synesis"
			description = "Removing the application and shell integration. Your project data is preserved."
		}
		set(controlProgressStatus, "Preparing the operation...")
		w.refreshProgress()
		w.progressTimer, _, _ = win32.SetTimer.Call(w.hwnd, 1, 16, 0)
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
		set(controlResult, w.resultText())
		set(controlUninstallPath, w.installRoot)
		set(controlResultDetails, w.resultDetailsText())
		set(controlResultDetailsToggle, "Show details")
		set(controlResultNext, "1.  Open a new terminal so the updated PATH is picked up.\r\n"+
			"2.  Run  synesis --version  to confirm the installation.\r\n"+
			"3.  In a project folder, run  synesis init  to get started.")
	}
	set(controlTitle, title)
	set(controlDescription, description)
	set(controlCancel, "Cancel")
	if page == installerPageResult {
		set(controlCancel, "Close")
	}
	w.updateActionLabel()
	w.applyLayout()
	w.rebuildBackdrop()
	for _, id := range []int{controlBack, controlNext, controlCancel} {
		win32.SetEnabled(w.controls[id], !w.busy)
	}
	switch page {
	case installerPageAction:
		win32.SetFocus.Call(w.controls[controlActionInstall])
	case installerPageInstall:
		win32.SetFocus.Call(w.controls[controlInstallEdit])
	case installerPageUninstall:
		// Next keeps the focus across the page change and is now the destructive
		// button: move the focus so no stray key press confirms the uninstall.
		win32.SetFocus.Call(w.controls[controlBack])
	case installerPageResult:
		win32.SetFocus.Call(w.controls[controlCancel])
	}
	// Paint the new parent surface before refreshing its child controls. The
	// posted second pass runs after the current navigation message has unwound;
	// this keeps controls that stay in place from retaining stale pixels across
	// page transitions.
	w.refreshPage()
	win32.PostMessage.Call(w.hwnd, installerMessageRefresh, 0, 0)
}

func (w *installerWizard) refreshPage() {
	win32.RedrawWindow.Call(w.hwnd, 0, 0, win32.RdwInvalidate|win32.RdwErase|win32.RdwAllChildren|win32.RdwUpdateNow)
}

// installerRailSteps names the four stops the rail shows for an action.
func installerRailSteps(action installerAction) [4]string {
	if action == installerActionUninstall {
		return [4]string{"Welcome", "Options", "Remove", "Finish"}
	}
	return [4]string{"Welcome", "Location", "Install", "Finish"}
}

// railStep is the stop of the rail a page belongs to.
func (page installerPage) railStep() int {
	switch page {
	case installerPageAction:
		return 0
	case installerPageInstall, installerPageUninstall:
		return 1
	case installerPageProgress:
		return 2
	default:
		return 3
	}
}

// installActionTitle names the primary action for the version already on this
// machine, when both versions are known.
func (w *installerWizard) installActionTitle() string {
	switch {
	case !w.installed:
		return "Install"
	case w.bundleVersion == "" || w.installedVersion == "":
		return "Install or update"
	case w.installedVersion == w.bundleVersion:
		return "Repair"
	default:
		return "Update"
	}
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

func (w *installerWizard) uninstallActionDetail() string {
	if !w.installed {
		return "Synesis is not installed for this account."
	}
	return "Remove the application; metadata stays unless you choose otherwise."
}

// showsNextSteps reports whether the result page has room for, and reason to
// show, what to do after a successful install. Open details take that space.
func (w *installerWizard) showsNextSteps() bool {
	return w.page == installerPageResult && w.result.err == nil && w.action != installerActionUninstall && !w.resultDetailsOpen
}

// resultBadge selects the status mark painted beside the result title.
func (w *installerWizard) resultBadge() (kind string, color draw.PaintColor) {
	switch {
	case w.result.err != nil:
		return "badge-failed", draw.StatusColor("failed")
	case w.result.warning != "":
		return "badge-warning", draw.StatusColor("warning")
	default:
		return "badge-done", draw.StatusColor("done")
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
	case "unpack":
		return "The installer could not be unpacked."
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
	win32.SetText(w.controls[controlResultDetailsToggle], label)
	w.applyLayout()
	if w.resultDetailsOpen {
		// Start with the error summary, even after earlier diagnostic scrolling.
		win32.SendMessage.Call(w.controls[controlResultDetails], 0x00b1, 0, 0) // EM_SETSEL
		win32.SendMessage.Call(w.controls[controlResultDetails], 0x00b7, 0, 0) // EM_SCROLLCARET
	}
	w.rebuildBackdrop()
	w.refreshPage()
}

// selectAction switches the chosen action. The rail names the steps of that
// action, so it is repainted with the cards.
func (w *installerWizard) selectAction(action installerAction) {
	w.action = action
	w.rebuildBackdrop()
	w.refreshPage()
}

func (w *installerWizard) updateActionLabel() {
	label := "Next"
	switch w.page {
	case installerPageInstall:
		label = "Install"
	case installerPageUninstall:
		label = "Uninstall"
	}
	win32.SetText(w.controls[controlNext], label)
}
