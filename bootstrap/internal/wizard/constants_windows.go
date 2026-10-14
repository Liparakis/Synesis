//go:build windows

package wizard

const (
	installerWindowClass = "SynesisInstallerWizard"
	// The layout is authored in 96-DPI pixels. The window scales it to the
	// display, and down again when the work area is smaller.
	installerWindowWidth  = 760
	installerWindowHeight = 520

	// A branded rail on the left carries the steps; the page sits to its right
	// above one row of buttons.
	installerSidebarWidth = 208
	installerContentLeft  = 240
	installerContentWidth = 488
	installerFooterTop    = 456
	installerButtonTop    = 472
	installerButtonHeight = 32

	installerMessageDone     = 0x8001
	installerMessageShow     = 0x8002
	installerMessageRefresh  = 0x8003
	installerMessageProgress = 0x8004
)

const (
	installerActionInstall installerAction = iota
	installerActionUninstall
)

const (
	installerPageAction installerPage = iota
	installerPageInstall
	installerPageUninstall
	installerPageProgress
	installerPageResult
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
	controlShortcut
	controlAllowLink
)
