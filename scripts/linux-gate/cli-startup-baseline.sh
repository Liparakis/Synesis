#!/usr/bin/env bash
# Records how long the Synesis CLI takes to start, on the machine it runs on (ADR-0103, "Other measures": CLI
# responsiveness). It is a baseline to set a budget from, not a pass/fail check, so it is not part of `check`; run it
# through Gradle on the Linux gate:  scripts/linux-gate/run.ps1 :cli:cliStartupBaseline
#
#   cli-startup-baseline.sh <launcher> [runs]
#
# Each command is run once first (reported on its own as first_ms, before the timed runs) and then <runs>
# times (default 20); the minimum, median, 95th percentile (nearest rank) and maximum of those are printed as
# STARTUP_BASELINE lines, after one CONDITIONS line that records where the numbers come from. Time covers the whole
# process, launcher script and JVM start included, as a person waits for it. The home directory and Link profile are
# temporary, so nothing of the user's is read or written.
set -u
launcher="${1:?usage: cli-startup-baseline.sh <launcher> [runs]}"
runs="${2:-20}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
export HOME="$work/home" SYNESIS_LINK_PROFILE="$work/profile" SYNESIS_STUN_SERVER=off
mkdir -p "$HOME" "$work/project"
git -C "$work/project" init -q || { echo "STARTUP_BASELINE_FAILED=git init"; exit 1; }
"$launcher" init --project "$work/project" > "$work/init.out" 2>&1 || { echo "STARTUP_BASELINE_FAILED=init"; cat "$work/init.out"; exit 1; }

now_ms() { echo $(( $(date +%s%N) / 1000000 )); }

# measure <label> <arguments...>: one first run, then <runs> timed runs.
measure() {
  local label="$1"; shift
  local start end first i
  local -a times=()
  start=$(now_ms); "$launcher" "$@" > "$work/out" 2>&1; local status=$?; end=$(now_ms)
  first=$(( end - start ))
  if [ "$status" -ne 0 ]; then echo "STARTUP_BASELINE_FAILED=$label exit=$status"; cat "$work/out"; exit 1; fi
  for ((i = 0; i < runs; i++)); do
    start=$(now_ms); "$launcher" "$@" > /dev/null 2>&1; end=$(now_ms)
    times+=($(( end - start )))
  done
  local sorted
  sorted=($(printf '%s\n' "${times[@]}" | sort -n))
  local p95_index=$(( (95 * runs + 99) / 100 ))
  echo "STARTUP_BASELINE command=\"$label\" first_ms=$first runs=$runs min_ms=${sorted[0]} median_ms=${sorted[$(( runs / 2 ))]} p95_ms=${sorted[$(( p95_index - 1 ))]} max_ms=${sorted[$(( runs - 1 ))]}"
}

echo "CONDITIONS os=\"$(uname -sr)\" cpus=$(nproc 2>/dev/null || echo unknown) java=\"$(java -version 2>&1 | head -1)\" launcher=$launcher at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
measure "--help" --help
measure "version" version
measure "coordination status" coordination status --project "$work/project"
