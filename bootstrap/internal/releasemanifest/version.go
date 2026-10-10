package releasemanifest

import (
	"strconv"
	"strings"
)

// CompareVersions orders dotted numeric versions (with an optional
// "-prerelease" suffix), returning -1/0/1 like strings.Compare.
func CompareVersions(left, right string) int {
	leftParts, leftPre := splitVersion(left)
	rightParts, rightPre := splitVersion(right)
	for i := 0; i < len(leftParts) || i < len(rightParts); i++ {
		lv, rv := 0, 0
		if i < len(leftParts) {
			lv, _ = strconv.Atoi(leftParts[i])
		}
		if i < len(rightParts) {
			rv, _ = strconv.Atoi(rightParts[i])
		}
		if lv != rv {
			if lv < rv {
				return -1
			}
			return 1
		}
	}
	return comparePrerelease(leftPre, rightPre)
}

// comparePrerelease follows semver precedence: a version with no
// prerelease suffix outranks one with a suffix (e.g. 1.0.0 > 1.0.0-alpha).
func comparePrerelease(left, right string) int {
	if left == right {
		return 0
	}
	if left == "" {
		return 1
	}
	if right == "" {
		return -1
	}
	leftParts, rightParts := strings.Split(left, "."), strings.Split(right, ".")
	for i := 0; i < len(leftParts) && i < len(rightParts); i++ {
		leftNumber, leftErr := strconv.Atoi(leftParts[i])
		rightNumber, rightErr := strconv.Atoi(rightParts[i])
		if leftErr == nil && rightErr == nil && leftNumber != rightNumber {
			if leftNumber < rightNumber {
				return -1
			}
			return 1
		}
		if leftParts[i] != rightParts[i] {
			return strings.Compare(leftParts[i], rightParts[i])
		}
	}
	if len(leftParts) < len(rightParts) {
		return -1
	}
	return 1
}

// splitVersion strips a leading "v", any build metadata ("+..."), and
// splits the remainder into numeric release parts and a prerelease string.
func splitVersion(value string) ([]string, string) {
	value = strings.TrimPrefix(strings.TrimSpace(value), "v")
	value = strings.Split(value, "+")[0]
	parts := strings.SplitN(value, "-", 2)
	return strings.Split(parts[0], "."), func() string {
		if len(parts) == 2 {
			return parts[1]
		}
		return ""
	}()
}
