# Bug-hunting code review: 061 Site redesign (top bar, league tabs, Floodlight palette)

**Date**: 2026-11-10 · **Reviewer**: bug-hunting code review pass (not a style review)

**Read**: `AGENTS.md`; `claude/site-redesign.md` (the "Amendments after review" section treated as
binding); the uncommitted diff on `061-site-redesign` (`git diff HEAD` plus the untracked files);
`destinations.ts`, `switchTarget.ts`, `railLeague.ts`, `appSlots.tsx`, `AppShell.tsx`,
`LeagueHeader.tsx`, `TopBar.tsx`, `BottomBar.tsx`, `NavSheet.tsx`, `popupFocus.ts`,
`leagueRefresh.ts`, `PageHeader.tsx`, `useNarrow.ts`, `railLane.ts`, the shell and phone blocks of
`styles.css`; the deleted `Rail.tsx` and `LeagueRailSection.tsx` (`git show HEAD:`) line by line
against their replacements; the deleted and new test files by test title.

**What I ran** (nothing in the project was changed except this file):

- `cd web && npx tsc -b` exit 0; `npm test`: 167 files, 2866 passed, 0 failed, 0 skipped.
  `npm run build` not re-run (reported passing). Backend suite not run (frontend-only change, per
  instructions); `git status` shows nothing under `backend/`.
- Scripts over `styles.css` and `web/src` (scratchpad): every CSS class vs every TS/TSX file, old
  stylesheet vs new, every `var(--x)` vs every definition, every `color-mix` colour space.
- A scratch copy of `web/src` (scratchpad, junction to `node_modules`, removed afterwards) to break
  the guards on purpose and to run three scratch Vitest cases against the real shell.
- No browser. Anything about pixels is REASONED and says so.

**VERIFIED** = reproduced by running something. **REASONED** = from reading the code only.

## Summary

| ID | Sev | Area | One line |
|---|---|---|---|
| CR-01 | HIGH | CSS / palette sweep | `--teal` → `--brand` also rewrote "s**teal**": five `steal` selectors are now `sbrand` and match nothing |
| CR-02 | MEDIUM | Season dropdown | On Home, History and Power rankings every season is the same URL: picking a year does nothing and the menu stays open |
| CR-03 | MEDIUM | Nav sheet / popups | A sheet or menu only closes when the pathname changes; tapping the link for the page you are on leaves it open |
| CR-04 | MEDIUM | Palette, stat calculator | `ContributionBars` series 1 (`--data`) and series 4 (`--fitted`) are 5.0 apart in OKLab; "other" (`--muted`) is 10.2 from series 1 |
| CR-05 | MEDIUM | Compact top bar | Draft room between 861px and roughly 1060px: the tabs cannot shrink or scroll, so the bar overflows |
| CR-06 | LOW | Tests | Six "eyebrow names the season shown" suites now assert text production never renders; the shell's `InsideLeagueContext` wiring has no test |
| CR-07 | LOW | PageHeader | "Inside a league" is decided by the path alone, so a league that does not resolve gets no header and no eyebrow |
| CR-08 | LOW | Guard tests | The `--you-fill`/`--text` guard only sees one-line rules; no guard ties a CSS class to a TSX class (which is how CR-01 passed 2866 tests) |

Checked and found correct (so nobody re-checks): tab lighting on the player page, compare,
`/stats/calculator`, an auction league's ADP page, `/power/verify`; no tab on manager history or a
hinted `/mock/:id`; `/history` (Time Machine) never matches a league row; football gets no Players
tab and an NFL league on a Players URL lights nothing; actions never become links (tabs, bottom bar,
sheet, palette); no conditional hooks; `useLeagueRefresh` moved verbatim and `LeagueHeader` keeps its
tree position across compact/phone, so one POST per league; all listeners removed; Managers link
state; sign-out from both places; mock-modal seed; `switchTarget` moved verbatim. Every removed rail
class is gone from TSX and every class the new components use has a rule. No undefined `var()` (the
six hits are inline-set or have fallbacks). `--bar-clear` reaches all three pick-card rules.
`body.bk-modal-fullscreen` hides the four new chrome classes. z-index values match the ladder
comment. All 244 `color-mix(in oklch` became `in oklab`; the two `in oklch` left are in a comment;
no gradient used `in oklch`; no mix pairs two chromatic colours, so none relied on hue
interpolation. Old rail behaviour dropped on purpose only (collapse toggle, group collapse, "All
pages" chip, FAB, always-visible sign out, identity link to the board).

## Findings

### CR-01 (HIGH, VERIFIED) - the teal sweep renamed five `steal` selectors to `sbrand`

**Where**: `web/src/styles.css:5250`, `:5258`, `:5265`, `:5596`, `:5599` (and comments at 997, 5246).

**What happens**: the token rename was applied to the substring `teal`, so
`.value-steal` → `.value-sbrand`, `.cell.pos-multi.value-steal` → `...value-sbrand`,
`.value-delta.steal` → `.value-delta.sbrand`, `.steals-busts` → `.sbrands-busts`,
`.sb-steals` → `.sb-sbrands`. The TSX still writes the real names
(`DraftBoard.tsx:265` `value-${value.kind}`, `CompletedDraftBoard.tsx:183` `value-delta steal`,
`:274` `steals-busts`, `:278` `sb-steals`). On a finished draft board:

- the value view tints reaches red and steals not at all, so the board shows half the story;
- the "+N" steal delta and the legend's "+" lose their green;
- the Steals column loses its accent edge and the section loses its margin and `flex-shrink: 0`.

**Reproduce**: `grep -n sbrand web/src/styles.css` (7 lines); `grep -rn "sbrand" web/src --include=*.tsx`
(none). The old stylesheet (`git show HEAD:web/src/styles.css | grep -n steal`) has the five
selectors under their real names. These are the only words in the old file that contained a token
name as a substring (checked `teal`, `volt`, `crimson`, `row-hover`).

**Fix**: rename the five selectors back to `steal`/`steals` (and the two comments). While there,
`.sb-steals` now edges the Steals column in `--brand` gold; it was the "good" side against
`.sb-busts`' `--down`, so by the new role table it should be `--up`.

**Regression test**: see CR-08, the class-existence guard.

### CR-02 (MEDIUM, VERIFIED) - the season dropdown is a no-op on Home, History and Power rankings

**Where**: `web/src/components/LeagueHeader.tsx:91-101` with `destinations.ts` rows `home`, `history`,
`power` (hrefs built from `lineage.current`).

**What happens**: `switchTarget` keeps the page, and those three pages are "whole chain", so every
item in the menu is the URL you are already on. Scratch test at `/leagues/L1/history` with seasons
2026 and 2025: both menu items have `href="/leagues/L1/history"`; clicking 2025 leaves the trigger
reading 2026 and the menu open (it closes on a pathname change, and there is none). The rail had the
same hrefs (its test (c) pinned them), but as a row of year links. As a dropdown labelled with the
season, on the league's landing page, it is a control that visibly does nothing.

**Fix** (smallest): in `SeasonMenu`, when every `switchTarget(...)` equals the current
pathname, render `league-season-static` instead of the dropdown, as it already does for a
one-season league. Alternative, if a year should always go somewhere: send a whole-chain page's
other years to that season's board (`switchTarget(null, …)`), which is what a hinted room does.
That second option is a product call.

**Regression test**: at `/leagues/L1`, `/leagues/L1/history`, `/leagues/L1/power` with two seasons,
no `button[aria-haspopup=menu]` named "Season: …" (or, for the alternative, the 2025 item's href
differs from the pathname).

### CR-03 (MEDIUM, VERIFIED) - sheet and menus stay open when the tapped link is the current page

**Where**: `web/src/components/AppShell.tsx:153-155` (`setSheet(null)` keyed on
`location.pathname`); same pattern at `LeagueHeader.tsx:70`, `:123` and `TopBar.tsx:86-88`.

**What happens**: on a phone at `/managers`, Menu → "Managers": the sheet stays up over the page.
Same for "Leagues" on `/`, "Time Machine" on `/history`, and More → "History" on a league's History
page (the item is even marked current). Both reproduced in a scratch test (`SHEET_STILL_OPEN true`,
`MORE_STILL_OPEN true`). The router does navigate (new location key), the pathname just does not
change. To the user the tap did nothing and the page is still covered.

**Fix**: close on the click, not on the consequence. In `NavSheet`, give every `Link` an
`onClick={() => onClose()}` variant that does not return focus (`closeSheet(false)`), or key the
effect in `AppShell` on `location.key`. Same one-liner for the items in `SeasonMenu` and
`LeagueSwitcher` (`onClick={() => setOpen(false)}`).

**Regression test**: extend `PhoneNav.test.tsx` "closes the sheet on a navigation" with the
same-path case: render at `/managers`, open the sheet, click Managers, expect no dialog.

### CR-04 (MEDIUM, REASONED: computed, not looked at) - three of six contribution-bar colours are near-identical

**Where**: `web/src/components/ContributionBars.tsx:20-21`; tokens at `styles.css:188`, `:197`, `:181`.

**What happens**: `PART_COLOURS[0]` became `var(--data)` = `oklch(82% 0.03 85)`, `PART_COLOURS[3]`
is `var(--fitted)` = `oklch(84% 0.04 165)`, and "other" is `var(--muted)` = `oklch(72% 0.012 80)`.
OKLab distances (same formula `managerColor.ts` quotes, floor 15 there): data–fitted **5.0**,
data–muted **10.2**. Before the change the first series was saturated teal. In a stacked bar of a
formula with four or more terms, the largest term, the fourth term and "other" are three pale
warm greys; the swatch legend cannot tell them apart either. Plan review PR-10 moved `--data` away
from `--muted` (2.2 → 10.2) but nobody measured it against `--fitted`, which shares this palette.

**Fix**: `--data` is right for a single-series fill, wrong as one member of a categorical set. Give
`PART_COLOURS[0]` a position hue not already in the list (`var(--rb)`, hue 185) or drop `--fitted`
from the list for one (`var(--c)`, hue 120). Either keeps "existing tokens only". Then look at it:
this is a browser check the spec already lists (PR-10) and I did not do.

**Regression test**: a unit test that resolves `PART_COLOURS` + `OTHER` against the `:root` values
read with `node:fs` and asserts pairwise OKLab distance ≥ 15.

### CR-05 (MEDIUM, REASONED: arithmetic, no browser) - the compact top bar overflows below about 1060px

**Where**: `styles.css:1870` (`.topbar-league`), `:1962-1971` (`.league-tabs`, `.league-tab`),
`:1996-2003` (`.league-compact`); `AppShell.tsx:138` (`compact` is on for every non-phone width).

**What happens**: in a draft room the bar holds wordmark, identity, season, "Switch league", five
or six tabs, search, menu, account. Only the league name can shrink (ellipsis). `.league-tab` is
`white-space: nowrap` with default `min-width: auto`, and `.league-tabs` neither wraps nor scrolls.
Summing fixed widths with the name fully collapsed: ~110 wordmark + ~40 crest + ~75 season +
~125 switcher + ~430 six tabs + ~132 actions + ~96 gaps + 48 padding ≈ **1056px**. Compact applies
from 861px up, so between 861 and roughly 1060 (1024x768, a half-width laptop window) the tabs run
under the right-hand buttons or push the page sideways. From ~1060 to ~1250 it fits only by
ellipsising the league name to nothing. The spec's size checks are 1440 and 1280; nothing covers
this band. The non-compact header is safe (`flex-wrap: wrap`).

**Fix**: give `.league-compact .league-tabs` `overflow-x: auto; scrollbar-width: none` (the sub-tab
strip's own treatment) and `flex: 1 1 0`, and hide the "Switch league" label below ~1100px (caret
button with `aria-label`), or raise the compact threshold: `compact` only when a
`(min-width: 1100px)` query matches, falling back to the full header below it.

**Verify**: draft room, NBA league (six tabs), at 1024x768 and 900x700.

### CR-06 (LOW, VERIFIED by reading the suites) - eyebrow tests pass against a state users never see

**Where**: `pages/ExpectedWins.test.tsx:139`, `LeagueAnalysis.test.tsx:696`,
`RosterManagement.test.tsx:396`, `SeasonForecast.test.tsx:279`, `Superlatives.test.tsx:1580`,
`WeeklyReport.test.tsx:593` ("… eyebrow names the season shown"); `AppShell.tsx:209`, `:277`.

**What happens**: those suites render the page with no shell, so `InsideLeagueContext` is its
default `false` and `League · 2025` renders. Every one of those pages is a `/leagues/:id/…` route,
where production now renders no eyebrow at all. The tests are green and describe the old site.
The opposite half is untested too: `PageHeader.test.tsx` sets the context by hand, and nothing
renders the real shell at a league path and at `/managers/:id/history` to prove `AppShell` feeds it
correctly. Swap `inLeaguePath` for `true` or `false` in `AppShell.tsx:277` and all 2866 tests pass.

On the question asked: **no page loses a fallback-season signal.** The pages that can show a season
other than the one in the URL all say so in the body, not only in the eyebrow: Luck, Bench points,
Playoff odds, Matchups & awards, Awards (`SeasonFallbackNote`), Player page, Player stats, Stat
calculator, Compare (`fallbackNote`), Trends (`fallbackLabel`), League home (its per-block notes).
The pages whose eyebrow was the only place the season was printed are Team strength, Schedule grid,
Weekly pot and (partly) Rivalries, and none of their endpoints falls back (checked the backend:
`LeagueAnalysisService`, `ScheduleGridService`, `RivalryService`, `WeeklyPotController` do not use
`LeagueSeasonResolver`), so the league header's season is the right one there.

**Fix**: delete or re-point the six suites (assert the in-body note instead, which is what now
carries the signal), and add two shell tests: a `PageHeader` with an eyebrow rendered as the `page`
of `renderAtPath('/leagues/L1/superlatives')` shows no eyebrow; the same at
`/managers/7/history` with `railLeagueId` state does.

### CR-07 (LOW, REASONED) - eyebrow hidden even when there is no league header to replace it

**Where**: `AppShell.tsx:209` (`inLeaguePath = leagueRefFromPath(pathname) != null`), `:252`.

**What happens**: the header renders only when `railLeague` resolved; the eyebrow is hidden whenever
the path merely looks like a league path. If `/api/drafts` fails, or the id is not in the signed-in
user's list, the page has neither. It is also true for the moment between navigation and resolve.
The page then names no league and no season anywhere.

**Fix**: `value={inLeaguePath && railLeague != null}`. The cost is an eyebrow that shows for a
frame on a cold deep link and then goes; if that flicker is worse than the gap, keep the path rule
and accept this.

### CR-08 (LOW, VERIFIED) - guard gaps

**Where**: `web/src/paletteTokens.test.ts:90-94`; no test at all for the second point.

- Broke the guards in a scratch copy: a leftover `var(--teal)` in CSS, a `var(--crimson-fill)` in a
  TS file, an `@import`, and a glyph appended to `NavSheet.tsx` each fail the right test. Those
  four guards are real and read from disk.
- The fifth, "a solid `--you-fill` never carries `--text`", matches line by line. A rule written
  over several lines with both declarations **passed** in the scratch copy. Today no such rule
  exists (checked every rule block: the three `--you-fill` backgrounds all set `color: var(--bg)`),
  so this is a hole, not a live bug. Fix: split on `}` and test each rule block.
- Nothing checks that a class the stylesheet styles exists in the source, or the reverse, which is
  the whole reason CR-01 is green. A cheap guard: for the selectors this branch touched
  (`value-steal`, `steal`, `steals-busts`, `sb-steals`, and the new shell prefixes), assert that
  each class named in the TSX appears as `.<name>` in `styles.css` read with `node:fs`. A general
  version exists in my scratch script; 144 CSS classes are unreferenced as literal strings, most
  of them built by template (`pos-${p}`, `lc-${state}`), so a blanket rule would need an allowlist.

## For the parent session

Counts: 1 HIGH, 4 MEDIUM, 3 LOW. Fix first: CR-01 (a five-line rename, user-visible on every
finished board), CR-03 (one `onClick` per menu item), CR-02 (the season control on the landing
page). CR-04 and CR-05 need a look in the browser before and after.

Lesson candidate for `claude/lessons.md`: **a token rename done by substring rewrites words that
contain the token** (`steal` → `sbrand`). Rename on the full `var(--name)` / `--name:` form, then
diff the set of class selectors before and after; the set must not change. No test in the suite
would have caught it, and a browser pass only catches it on the one view that uses the class.
