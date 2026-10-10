//go:build windows

package wizard

const (
	installerWindowClass = "SynesisInstallerWizard"
	// The reference composition is authored at this generous desktop size. The
	// wizard scales the client layout down only when the current work area is
	// smaller, so the presentation remains usable on compact laptops.
	installerWindowWidth  = 1400
	installerWindowHeight = 980
	// The source layout is authored at 1400x980. A compact desktop default
	// leaves the wizard closer to the supplied reference while the existing
	// work-area calculation still protects smaller displays.
	installerDesktopScale = 0.52

	installerActionInstall installerAction = iota
	installerActionUninstall

	installerPageAction installerPage = iota
	installerPageInstall
	installerPageUninstall
	installerPageProgress
	installerPageResult

	installerMessageDone     = 0x8001
	installerMessageShow     = 0x8002
	installerMessageRefresh  = 0x8003
	installerMessageProgress = 0x8004
)

// The control identifiers keep the values they had when this block was shared
// with the Win32 constants: Windows exposes them as automation identifiers.
const (
	controlTitle = 1001 + 71 + iota
	controlDescription
	controlProductName
	controlProductDetails
	controlCapabilities
	controlCapabilityInstall
	controlCapabilityLink
	controlCapabilityData
	controlActionGroup
	controlActionInstall
	controlActionUninstall
	controlActionPath
	controlInstallGroup
	controlInstallEdit
	controlBrowse
	controlInstallHint
	controlUninstallGroup
	controlUninstallPathLabel
	controlUninstallPath
	controlMetadata
	controlUninstallWarning
	controlUninstallImpact
	controlProgressStatus
	controlProgressBar
	controlProgressCount
	controlProgressSteps
	controlProgressLogTitle
	controlProgressLog
	controlResult
	controlResultDetailsToggle
	controlResultDetails
	controlResultNextTitle
	controlResultNext
	controlBack
	controlNext
	controlCancel
)
