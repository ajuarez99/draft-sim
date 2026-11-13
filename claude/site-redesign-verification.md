# Live verification: site redesign (branch 061-site-redesign)

**Date**: 2026-11-10 · **Run by**: Claude (built-in browser pane) ·
**API**: launch entry `061-api`, port 8161, `bootRun` from this worktree (backend
is unchanged from `main` 8c0c457) · **Web**: launch entry `061-web`, Vite on
5261, restarted after the Phase 2 build (the first load after the build served a
stale module graph and an unstyled page; a server restart cleared it) ·
**Database**: local `draftsim-pg` on 5433, at V47 · **Signed in as**: popsharky ·
**Leagues used**: "Ball Knowers" (NBA 2026, draft complete), "(Foot) Ball
Knowers" (NFL 2026, draft complete), "test" (NFL 2026, pre-draft).

Automated at verification time: web vitest 2,866 passed / 0 failed / 0 skipped
in 167 files · `tsc -b` clean · `npm run build` passes. Backend suite not run
(no backend change: `git diff --stat HEAD -- backend` is empty).

## Results

| # | Criterion | Result | Evidence |
|---|---|---|---|
| 8 | Tabs and sub-tabs load, active marked | PASS (partial) | NBA: Home, Season → Playoff odds, Players → Player stats. NFL: Matchups → Matchups & awards. Tabs read from the DOM: NFL has Home, Matchups, Season, Draft, History and no Players. Not every sub-tab was opened. |
| 9 | Season and league switch keep the page | PASS | On NBA `/forecast`, the season menu's three items and the switcher's five leagues all link to `/leagues/<other>/forecast`. |
| 10 | Picker, managers, a mock from the top bar | PASS (partial) | Picker and Managers render with the sport filter as a strip under the top bar. Time Machine and starting a mock were not opened. |
| 11 | Draft room fits; "you" identifiable | PASS | Pre-draft room at 1440x900, 1280x720 and 1024x768: no document scrollbar, top bar not overflowing (scrollWidth = clientWidth at 1024). Your seat and column carry a gold ring and tint on both the room and the completed board. Red fallback not needed. |
| 12 | Phone 375x812 | PASS (partial) | Bottom bar Home/Matchups/Season/Draft/More; More sheet lists Players, History, then Leagues, Mock drafts, Managers, Time Machine, Sign out; no horizontal scroll. The pinned pick card against the bar was not exercised (needs a running draft). |
| 13 | Contrast, measured in the browser | PASS | Canvas-resolved sRGB, WCAG ratio: text on bg 17.98; muted on bg 8.18, on panel 7.71; `--bg` on brand 12.3; brand on panel 11.61; up on bg 10.72; down on bg 6.43, on panel 6.07; `--bg` on position pills 6.42 (DEF) to 11.18 (C). |
| 14 | Keyboard | PASS (partial) | Skip link and `main#main` present. More sheet: focus moves in, Escape closes and returns focus to More. Tab order and the other three popups were not walked. |
| 15 | Odd states | PASS (partial) | Pre-draft league: compact header with Draft room, Follow live (with the live dot), Mock it. A pre-season 0-0 league was not checked. |

## Defects found during verification

- **V-01 (MEDIUM) - every position tint on the draft board was the same olive.**
  `color-mix(in oklch, <position> 22%, var(--panel))` interpolates hue, and with
  the new near-neutral panel (chroma 0.009, hue 75) a 22% mix lands near hue 99
  whatever the position. Fixed by mixing `in oklab` everywhere (172 uses in
  `styles.css`, 1 in TSX). Re-checked on the completed NFL board: RB teal, WR
  blue, QB pink, TE violet.

Defects from the code review (`claude/site-redesign-code-review.md`), fixed
before this run finished: CR-01 (the token rename rewrote "s**teal**" to
"sbrand" in five selectors, so steals lost their styling on a finished board),
CR-02 (season dropdown whose every item was the current page is now a label),
CR-03 (menus and the sheet now close on click, not only on a path change),
CR-04 (`ContributionBars` series colors separated), CR-05 (compact bar tabs can
scroll; no overflow measured at 1024). CR-06 to CR-08 are test-coverage gaps,
not fixed: nothing tests the shell's inside-a-league wiring for the eyebrow, and
no guard ties CSS class names to the ones TSX writes.

## A capture artifact, not a defect

Screenshots of the completed board taken while the browser pane was hidden
showed the grid dimmed. Computed styles showed opacity 1 and no filter on the
whole ancestor chain, and a second screenshot of the same page was correct. The
200ms `cell-reveal` animation's intermediate frame is what a hidden pane hands
back. Take two captures before believing a dim one.

## Assumed, not verified

- The steals-and-reaches view after the CR-01 fix (the fix is five class names
  restored to what the TSX writes; the view was not opened afterwards).
- `ContributionBars` after the color change.
- Tabular digit alignment in the number font.
- Panel against page is 1.06:1 by design; surfaces separate by their border and
  radius. Whether that reads well enough is a judgment for Allan.
- 701 to 860px, where the league header is pinned rather than scrolling.
- Real phones, Safari, and a live draft.
- The social preview image still carries the old navy and teal.
