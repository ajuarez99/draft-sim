# Baseline (T001)

Recorded before any code in this feature was written, so a later regression is
provable rather than argued about (`claude/lessons.md`, standing rule: separate
verified from assumed).

## Frontend suite

```bash
cd web && npm test
```

**2026-09-16, before any change on `001-readable-bump-chart`:**

```
Test Files  27 passed (27)
     Tests  244 passed (244)
  Duration  33.20s
```

**0 skipped.** Every file and every test passed.

Note: the run prints React stack traces mentioning
`ErrorBoundary.test.tsx:13` and `Cannot read properties of undefined (reading
'season')`. These are **expected** — that test deliberately throws to prove the
error boundary catches it. They are not failures, and they were present in the
baseline.

## Before screenshot (T002)

The "before" state is the screenshot supplied with the original request: the
*Projected week by week* panel showing fourteen rosters whose legend swatches are
all either orange or green. That image is the artifact this feature was opened
against, so it is the before-state of record rather than a re-capture.

The measured cause is recorded in `research.md` R1 — `hueFor` puts manager ids
1–9 on hues 49–57 and ids 10–19 on hues 127–136.
