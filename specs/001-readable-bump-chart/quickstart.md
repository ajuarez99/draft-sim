# Phase 1 — Quickstart / validation

How to prove this feature works. Every check below is observable; none of them is
"looks better".

## Prerequisites

```bash
cd web && npm install
```

Backend + Postgres running for live data. Note from prior sessions: the backend
suite reports `BUILD SUCCESSFUL` with ~52 tests **skipped** when Postgres is
down — check the skip count, don't trust the green.

## 1. Unit tests

```bash
cd web && npm test
```

Expected — `hue.test.ts` is new; this shared rule has never had a test:

- `hueForIndex` spreads: for n=14, the minimum gap between any two hues is
  `>= 25` degrees. **This test fails against today's `hueFor`** (gap = 1), which
  is the point of writing it first.
- `hueForIndex(i, n)` is stable across calls and unaffected by roster-array
  reordering that preserves the `managerId` ordering.
- `hueForIndex(0, 0)` throws rather than returning 0.
- `hueForName` is byte-for-byte unchanged from the old `hueFor`.
- `managerHues` ties: a null `managerId` falls back to `rosterId` deterministically.
- Selection to slot: deselecting the *first* of three selections leaves the other
  two on their original slots (FR-003).

`PowerRankings.chart.test.ts` must still pass **unmodified** — `segmentsOf` is
untouched, and if that test needs editing, something drifted that shouldn't have.

## 2. Palette gate

Re-run the validator whenever `FOCUS_SLOTS` changes. This is the check that keeps
a well-meaning fourth colour from silently breaking the chart:

```bash
node scripts/validate_palette.js "#3987e5,#d95926,#199e70" --mode dark --surface "#09121c" --pairs all
```

Expected: `ALL CHECKS PASS` — CVD ΔE 9.4, normal-vision ΔE 20.9, contrast ≥ 3:1.

## 3. Browser verification

Do not ship this on tests alone — the failure modes here are label collision and
visual density, which no unit test sees.

```bash
cd web && npm run dev
```

Open League analysis for a 14-team league and confirm:

- [ ] **Default state** — the chart reads as recessive texture plus one crimson
      line (yours). It is not fourteen competing coloured lines.
- [ ] **US1** — you can find your own roster without clicking anything.
- [ ] **US3** — every line's owner is identifiable from its end label, with no
      reference to colour. Check for label collisions at week 14 specifically.
- [ ] **US2** — select two rivals; three lines are coloured, eleven recede.
- [ ] **FR-007** — a fourth selection is refused with a visible reason, or
      releases the oldest. No fourth colour is ever generated.
- [ ] **FR-003** — deselect the *first* of three; the other two keep their
      original colours. This is the regression most likely to slip through.
- [ ] **Grayscale** — apply `filter: grayscale(1)` in devtools. The chart must
      still be readable. If it isn't, identity is still leaning on colour.
- [ ] **FR-011** — the scored-week chart lower on the page, and Power rankings,
      both got the same treatment.

## 4. What "done" means

`SC-001` through `SC-004` in [spec.md](spec.md). SC-003 is the one that matters
most and the one only a human can sign off: *a reader can name the owner of any
line without using colour.*
