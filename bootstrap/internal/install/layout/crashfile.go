package layout

import (
	"os"
	"path/filepath"
	"runtime"
)

// CrashFileOption returns the JVM option that sends a crash report (hs_err_pid<N>.log)
// to Synesis's own state folder, the same folder the Java side uses
// (AdministrativeStateLocator.applicationStateRoot). Without it a JVM writes the report into
// its working directory, which is the person's project, and Synesis counts the file as a
// change it did not make. It returns "" when the folder cannot be resolved or created, so a
// launcher then starts the JVM as before.
func CrashFileOption() string {
	home, err := os.UserHomeDir()
	if err != nil {
		home = ""
	}
	var base string
	switch runtime.GOOS {
	case "windows":
		base = os.Getenv("LOCALAPPDATA")
		if base == "" && home != "" {
			base = filepath.Join(home, "AppData", "Local")
		}
	case "darwin":
		if home != "" {
			base = filepath.Join(home, "Library", "Application Support")
		}
	default:
		base = os.Getenv("XDG_STATE_HOME")
		if base == "" && home != "" {
			base = filepath.Join(home, ".local", "state")
		}
	}
	if base == "" {
		return ""
	}
	directory := filepath.Join(base, "Synesis", "crash")
	if os.MkdirAll(directory, 0o700) != nil {
		return ""
	}
	return "-XX:ErrorFile=" + filepath.Join(directory, "hs_err_pid%p.log")
}
