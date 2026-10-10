// Package assets embeds the artwork of the native setup wizard.
package assets

import "embed"

// LogoPNG is the product mark already used by the Synesis browser surface.
// Embedding it keeps the native installer self-contained.
//
//go:embed synesis-logo.png
var LogoPNG []byte

// Setup holds the wizard's card, button and ribbon artwork under setup/.
//
//go:embed setup/*.png
var Setup embed.FS
