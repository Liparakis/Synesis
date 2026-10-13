#!/usr/bin/env bash
# Runs Synesis Gradle tasks inside the Linux gate container, from a clone on the container's own
# filesystem. Started by run.ps1; not meant to be run on the host.
#   /out/synesis.bundle   the branch, as one file
#   /out/wip.patch        uncommitted and untracked work, possibly empty
# Arguments are passed to Gradle; with none, the whole gate runs.
set -u
branch="${SYNESIS_BRANCH:?SYNESIS_BRANCH is required}"
rm -rf /work && mkdir -p /work
git clone -q -b "$branch" /out/synesis.bundle /work/synesis || { echo "CLONE_FAILED"; exit 1; }
cd /work/synesis
if [ -s /out/wip.patch ]; then
  git apply --whitespace=nowarn /out/wip.patch || { echo "WIP_PATCH_FAILED"; exit 1; }
  echo "WIP_PATCH_APPLIED"
fi
echo "HEAD=$(git log --oneline -1)"
chmod +x gradlew
if [ "$#" -eq 0 ]; then set -- check --continue; fi
started=$(date +%s)
./gradlew "$@" --console=plain > /out/linux-gate.log 2>&1
status=$?
echo "GATE_EXIT=$status GATE_WALL_SECONDS=$(( $(date +%s) - started ))"
grep -E "BUILD|tests completed|FAILED|What went wrong" /out/linux-gate.log | head -40
rm -rf /out/linux-results && mkdir -p /out/linux-results
for module in link coordination project-record relay mcp-contract workspace mcp cli; do
  tests=0; failures=0; errors=0; skipped=0
  for result in "$module"/build/test-results/test/*.xml; do
    [ -f "$result" ] || continue
    line=$(grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$result" | head -1)
    tests=$(( tests + $(echo "$line" | sed 's/.*tests="\([0-9]*\)".*/\1/') ))
    skipped=$(( skipped + $(echo "$line" | sed 's/.*skipped="\([0-9]*\)".*/\1/') ))
    failures=$(( failures + $(echo "$line" | sed 's/.*failures="\([0-9]*\)".*/\1/') ))
    errors=$(( errors + $(echo "$line" | sed 's/.*errors="\([0-9]*\)".*/\1/') ))
  done
  echo "$module tests=$tests failures=$failures errors=$errors skipped=$skipped"
  [ -d "$module/build/test-results/test" ] && cp -r "$module/build/test-results/test" "/out/linux-results/$module"
done
exit $status
