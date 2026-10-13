// Package assets embeds the artwork of the native setup wizard.
package assets

import _ "embed"

// LogoPNG is the product mark already used by the Synesis browser surface.
// Embedding it keeps the native installer self-contained.
//
//go:embed synesis-logo.png
var LogoPNG []byte
