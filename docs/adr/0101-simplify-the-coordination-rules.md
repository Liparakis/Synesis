# ADR-0101 — Simplify the coordination rules

## Status

**Deferred on 2026-10-26 by the repository owner**, for future work: not to be built now. The candidates below, and the
leftovers other ADRs send here, stay recorded for when it is taken up.

Open, not decided (2026-10-20). Reserved on 2026-10-17 for the simplification the owner planned after ADR-0098,
ADR-0099 and ADR-0100 ("we are building a collaborative system, not a prison"). On 2026-10-20 the owner chose to leave
it open for change rather than build it now, so as not to risk a state that had only just become stable in real runs.

## Candidates (from the real runs of 2026-10-18 to 2026-10-20)

Rules that cost real runs, each a candidate, none decided:

1. **One lane per session.** An agent replaces its session to take new work; reviews and notices were lost across the
   replacement (ADR-0098 step 5 items 27 and 30, step 7 item 19; s6-run1, s5-run16, s7-run5).
2. **A review needs a reviewer lane with a claimed report file** (ADR-0102 item 10; s6-run1).
3. **A finish refused when unrelated entries arrived** (`SNAPSHOT_COMPLETION_EVIDENCE_STALE`, s5-run15).
4. **A moved `main` makes the workspace stale and needs a new session** (ADR-0098 step 7 item 12).
5. **A finished session is closed for good** (CLOSE; ADR-0099).
6. **The run controller's many wait reasons and wake rules** (ADR-0099 fixes 9 to 16).
7. **Synesis tracks lanes, not the goal**, so agents may stop with parts of the README undone (s6-run2, s7-run2).

## Constraints recorded with the owner

- **One identity per agent across sessions cannot be inferred.** Codex does not give enough session metadata to tell,
  deterministically, that a new session is the same agent as an earlier one (USER-STATED, 2026-10-20). A rule may rely
  only on what Synesis itself starts and records (for example, the run controller recording the sessions of an agent it
  launched), never on guessing from a provider's session.
- Any change goes through the scripted scenarios (ADR-0102 item 1) and one real run before it lands, and starts from a
  tagged known-good commit.
