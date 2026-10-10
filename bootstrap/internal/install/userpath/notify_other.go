//go:build !windows

package userpath

var notifyEnvironmentChanged = func() {}
