# Site redesign: top bar, league tabs, Floodlight palette

2026-11-10. Branch `061-site-redesign`, off `main` at 8c0c457.

Source: a whole-site review on 2026-11-10 by the design-reviewer, ui-designer and
accessibility-auditor agents (`.claude/agents/design/`). Their reports are not in
the repo; the findings this doc relies on are restated here with the file and
line they came from. Counts marked *(agent count)* were made by an agent and
spot-checked, not all re-counted.

Allan's decisions, 2026-11-10 (asked as three questions, each answered with the
recommended option):

1. **Navigation**: top bar plus league tabs. This **reverses** the 2026-09-14
   decision recorded at `web/src/styles.css:226-229` ("the rail is the site's
   navigation on every route now", `claude/site-wide-shell-propagation.md`).
2. **Palette**: "Floodlight", warm charcoal with one gold accent that is both the
   brand and "you". This **reverses** crimson-means-you
   (`web/src/styles.css:35-39`).
3. **Position colors**: match the convention Sleeper users know.

> **Amended 2026-11-10 after the adversarial plan review**
> (`claude/site-redesign-plan-review.md`, 26 findings: 0 critical, 7 high). The
> section "Amendments after review" at the end **overrides** the text above it
> wherever they differ. The original text is left as written.

## Why

Allan's complaint is that the site looks "AI coded". What the review found:

- A dev-tool shell: a left rail with 20 league rows in 5 groups
  (`web/src/destinations.ts:54-60, 158-497`), a "Menu" label and a "Jump to
  Ctrl K" chip (`web/src/components/Rail.tsx:150-167`).
- 21 unicode glyphs standing in for icons (`destinations.ts`, `glyph:`).
- One header on 25 of 31 pages: tracked uppercase eyebrow, uppercase Oswald
  title, muted sentence (`web/src/components/PageHeader.tsx`,
  `styles.css:1982-1996`).
- Four non-position accents (teal, volt, crimson, `--fitted`) plus two sport hues
  and eleven position hues. `var(--teal)` is used 262 times, including as the
  fill of data bars, which the stylesheet's own rule forbids
  (`styles.css:40-41`; fills at 299, 2583, 2805, 3300, 3336, 5822 *(agent
  count)*).
- Hue collisions inside one row: `--rb` and `--up` are both hue 150; `--c` (359)
  and `--down` (350); `--qb` (45) and crimson (25) (`styles.css:142-181`).
- Navy-slate at hue 255 with `--bg` and `--panel` only 5 L apart.

## What is there now

- `AppShell.tsx` renders `Rail` (global rows, account, a context slot pages
  portal into) with `LeagueRailSection` inside it (league identity, refresh
  line, season links, league switcher, the grouped destination rows), then
  `<main class="app-main">` with a page-action slot. On phones the rail becomes
  a bar that scrolls away and a `.jumpto-fab` brings back search
  (`AppShell.tsx:254-268`, `styles.css` 860px block, `useNarrow.ts:16`).
- `destinations.ts` is the single registry of league pages. `railLeague.ts`,
  `LeagueRailSection`, `searchIndex.ts` and the switcher all read it, and
  `destinations.test.ts` fails if `App.tsx` grows a league route without a row.
  **This stays the single registry.**
- `switchTarget` (`LeagueRailSection.tsx:183`) keeps you on the same page when
  you change season or league (spec 011). **This rule stays.**
- Three pages portal into the rail's context slot via `useRailContextSlot`:
  `DraftPicker.tsx:80` (sport filter), `ManagerHistory.tsx:39`,
  `ManagerTendencies.tsx:305`.
- The rail collapses by default in a draft room or mock
  (`AppShell.tsx` `railDefaultCollapsed`) because the board needs the width.
- Fonts load through a render-blocking `@import` at `styles.css:1`.
- Tests that pin the current shell: `LeagueRailSection.test.tsx`,
  `LeagueSwitcher.test.tsx`, `RailPhoneNav.test.tsx`, `AppShellJumpTo.test.tsx`,
  `AppShell.analytics*.test.tsx`, `destinations.test.ts`, `railLeague.test.ts`,
  `searchIndex.test.ts`, `useNarrow.test.ts`, `positionColours.test.ts`,
  `DraftRoomLayout.test.tsx`, `ChangeDumbbell.test.tsx`.

## Design

Three phases on one branch, each a separate commit that builds and passes on its
own, so any one can be reverted.

### Phase 1: palette and type

**Tokens.** The `:root` block is replaced. Old names are removed, not aliased, so
a missed usage fails loudly (an undefined `var()` is visible in review, and a
guard test below catches it).

| New token | Value (oklch) | Role | Replaces |
|---|---|---|---|
| `--bg` | 12% 0.006 75 | page | `--bg` |
| `--panel` | 17% 0.009 75 | the one raised surface | `--panel` |
| `--raised` | 21.5% 0.011 75 | sticky headers, active tab, row hover | `--row-hover` |
| `--line` | 28% 0.010 75 | hairlines | `--line` |
| `--text` | 96% 0.008 85 | body | `--text` |
| `--muted` | 72% 0.012 80 | secondary text | `--muted` (was 63%) |
| `--brand` | 84% 0.15 85 | the primary action, active tab or chip, focus ring, links on hover. Text on it is `--bg`. | `--teal` as interaction, `--volt` |
| `--you` | 84% 0.15 85 | your row, pick, seat: rings and marks | `--crimson` |
| `--you-text` | 84% 0.15 85 | "you" as text | `--crimson-text` |
| `--you-fill` | 36% 0.08 85 | "you" behind `--text` | `--crimson-fill` |
| `--data` | 70% 0.02 80 | bar, meter and progress fills that are not yours | `--teal` as data |
| `--up` / `--down` | 78% 0.16 150 / 68% 0.19 25 | better / worse | same names |
| `--danger` | 68% 0.19 25 | errors, stale, offline | the crimson error exception |
| `--fitted` | 80% 0.04 200 | earned / provenance | `--fitted` (was gold, now collides with brand) |
| `--sport-nfl` / `--sport-nba` | 76% 0.07 140 / 76% 0.09 50 | sport label tint; the label text carries the meaning | same names |
| NFL | QB 72% 0.16 350 · RB 78% 0.13 185 · WR 74% 0.13 240 · TE 72% 0.14 300 · K 72% 0.02 260 · DEF 66% 0.06 55 | position only | same names |
| NBA | PG 74% 0.13 240 · SG 78% 0.11 200 · SF 72% 0.14 295 · PF 72% 0.15 330 · C 80% 0.14 120 | position only | same names |

`--brand` and `--you` are the same color on purpose (Allan's choice) and stay two
tokens so the roles can be split again without a second sweep.

Contrast, computed by the ui-designer agent with an oklch-to-sRGB script, **not
measured in a browser**: text 18.1:1 and muted 8.2:1 on `--bg`; `--bg` text on
brand 12.3:1; up 10.8:1, down 6.5:1; position pills 6.4 to 11.2:1 with `--bg`
text. `--text` on `--you-fill` was **not** computed and is a verification item.

Mapping rules for the sweep:

- `--teal` → `--brand`, except a `background` on a bar, meter, fill or progress
  element → `--data`.
- `--volt` → `--brand`. Where volt was a tag or tint that is not an action
  (5653, 6425, 6443, 6800, 6826 *(agent count)*) → `--fitted` or `--up` by
  meaning.
- `--crimson*` → `--you*`, except the documented error uses (`.error`,
  `.track-note.failed`, `.live-fresh.stale`, the offline banner; 749, 1654,
  1661) → `--danger`. `.rvd-playoff` (6828) and `.lc-out` (5234) are neither
  "you" nor an error: `--down`.
- oklch literals outside `:root` whose hue is 240 to 260 at chroma ≤ 0.04 are
  the old navy surfaces → the matching neutral token. Other literals stay.
- The three red hexes (`#e07a7a`, `#6b2b2b`, `#3a1d1d`) → `--danger` and mixes
  of it.
- Colors named in TypeScript (the files `grep -l "teal\|crimson\|volt"` lists
  under `web/src`, e.g. `managerColor.ts`, `OwnershipMark.tsx`,
  `ChangeDumbbell.tsx`) follow the same rules.
- The house-style comment at the top of `styles.css` is rewritten to the new
  roles in the same commit.

**Type.** Google Fonts, loaded by `<link rel="preconnect">` and `<link
rel="stylesheet">` in `web/index.html`; the `@import` at `styles.css:1` goes.
`web/serve.json`'s CSP already allows `fonts.googleapis.com` and
`fonts.gstatic.com`.

- Body: Barlow 400/500/600/700.
- Numbers in tables (`.mono`): Barlow Semi Condensed 600 with `tabular-nums`.
- Display (`.cond`): Big Shoulders Display 700/800, for numerals that matter
  (scores, ranks, records, pick numbers). Still uppercase.
- Page titles stop using `.cond`: Barlow 700, sentence case, 26px (Phase 3).

Not in this phase: collapsing the ~30 font sizes and 19 radii to a scale. That
touches most of a 7,000-line stylesheet and cannot be checked page by page in
one pass. It is the first follow-up.

### Phase 2: navigation

Structure:

- **Top bar** (`TopBar.tsx`, replaces `Rail.tsx`), sticky, about 52px: wordmark;
  global links **Leagues** (`/`), **Mock drafts**, **Managers** (`/managers`),
  **Time Machine** (`/history`); a search button that opens the existing
  `JumpTo` (Ctrl+K still works; the kbd chip goes); the account menu.
- **League header** (`LeagueHeader.tsx`, replaces `LeagueRailSection.tsx`),
  shown when the path is inside a league: crest, league name, sport, managers
  count; the season as a dropdown; the league switcher; the refresh line; then
  **tabs** and, under the active tab, its **sub-tabs**.
- Labels are text only. No glyphs. The `glyph` field leaves `destinations.ts`.
  The live-draft pulsing dot stays, since it carries state.

Tabs. `destinations.ts` swaps `group` for a required `tab`, and the order of
rows within a tab is the sub-tab order. Every URL is unchanged.

| Tab | Rows, in order (key → current label) |
|---|---|
| Home | `home` |
| Matchups | `weeklyReport` "This week" · `schedule` (NBA) · `weeklyPot` |
| Standings | `power` · `forecast` "Playoff odds" · `analysis` "Team strength" · `expectedWins` "Luck" · `rosterManagement` "Bench points" · `superlatives` "Awards" |
| Players (NBA) | `stats` · `trends` · `compare` and `players` (not listed; they light the tab) |
| Draft | `board` · `live` (pre-draft and drafting only) · `adp` (NBA) · `mock` (action, a button at the end of the row) |
| History | `history` "Standings" · `rivalries` |

A tab links to the first row of its group that the league's sport and draft
status offer. A tab with no offered rows is not rendered (football has no
Players tab). `sports`, `requiresStatus`, `isAction`, `inRail`, `formerLabel`,
`match` and `idKind` keep their meaning; `inRail` is renamed `inNav`.

Rules that carry over unchanged: `switchTarget` for season and league changes;
the remount keys in `App.tsx`; analytics calls in `AppShell`; the JumpTo index.

The context slot: `useRailContextSlot` keeps its name-compatible hook but
portals into a strip under the top bar (under the league header when there is
one). On the picker page that is the sport filter as a horizontal segmented
row.

Draft room and mocks (`railDefaultCollapsed` paths): the league header renders
in a compact single row (league name, season, tabs; no refresh line, no
sub-tabs) so the board keeps its height. The collapsed/expanded override and
its `bk-rail` localStorage key are removed.

Phone (`useNarrow`, 860px): the top bar keeps wordmark, search and account.
Inside a league the tabs become a **bottom tab bar**, fixed, at z-index 45 in
the existing ladder (it takes `.jumpto-fab`'s slot and the FAB is removed):
Home · Matchups · Standings · Draft · More, where More opens a sheet with the
remaining tabs and the global links. Sub-tabs are a horizontally scrolling
strip under the league header. The pick card pinned at z-index 44
(`styles.css` ≤700px) sits above the bottom bar's height, not under it.

Accessibility in this phase, from the audit: a skip link to `<main>` as the
first focusable element; tabs are `<nav>` with links and `aria-current="page"`;
the season dropdown, switcher, account menu and More sheet move focus in,
close on Escape and return focus to their trigger.

### Phase 3: page header

`PageHeader` keeps its props so the 25 callers compile, and changes what it
renders: no eyebrow (the league header already says league and season), the
title in Barlow 700 sentence case with no `.cond`, the `sub` only when it is
60 characters or fewer; a longer `sub` is not rendered by `PageHeader` and the
caller's text moves into that page's existing `HowThisWorks` panel, or is cut
if it only restates the page. Each of the 24 `sub` strings is listed with its
disposition in the build report so nothing is dropped silently. The six pages
that bypass `PageHeader` are left alone.

## Not building, and why

- **Font-size, radius and spacing scale.** See Phase 1. First follow-up.
- **Scoreboard matchup card, week ticker, table restyle, player hero.** New
  components that deserve their own design and a look at real data.
- **Sleeper league avatars.** Needs a backend field on `DraftSummary` and an
  ingest change. The hashed crest stays.
- **Modal focus trapping across the seven existing modals, live-region and grid
  semantics on the draft board.** Real findings from the audit, unrelated to
  the shell; a separate accessibility pass.
- **Lazy-loaded routes, a shared fetch hook, splitting `styles.css`.**
  Efficiency work with no visual effect.
- **Merging pages.** Tabs group the existing pages; no page is merged or
  removed, so no URL or content decision is made here.
- **Renaming routes to match labels.** Addresses never change.

## Risks

- Gold for "you" may read as less urgent than crimson on the live board
  (`.cell.mine`, `.col-head.mine`, `.pick-prompt-take`). Checked in
  verification; the fallback is to point `--you*` back at a red without
  touching anything else, which is why they are separate tokens.
- Brand and "you" being one color makes "selected" and "yours" look alike where
  both appear (a selected chip beside your row).
- The draft room loses rail width it never used and gains about 52px of top
  bar plus the compact league row. `DraftRoomLayout.test.tsx` pins part of
  this; the rest is a browser check at 1440x900 and 1280x720.
- Token removal: a `var(--teal)` left behind renders as nothing. Guard test
  below.
- Tests that assert rail markup will need rewriting, not deleting. Each
  behavior they pin (same-page season switch, sport gating, status gating, the
  phone return path, analytics) keeps a test.

## Acceptance criteria

Automated:

1. `cd web && npx tsc -b && npm test && npm run build` pass.
2. A guard test reads `styles.css` from disk (`node:fs`, asserts length > 1000
   first, lesson 27) and fails on any `var(--teal`, `var(--volt`,
   `var(--crimson`, `var(--row-hover`, on any `var(--x)` whose `--x` is not
   defined in `:root`, and on `@import`.
3. `positionColours.test.ts` still proves every position of both sports has a
   token and rules.
4. `destinations.test.ts`: every row has a `tab`; every league route in
   `App.tsx` has a row; a tab's href is its first offered row; football offers
   no Players tab; `live` only for `pre_draft`/`drafting`.
5. Season and league switching keep the current page (the cases
   `LeagueSwitcher.test.tsx` and `LeagueRailSection.test.tsx` cover today).
6. No glyph characters from the old set remain in `destinations.ts`,
   `TopBar.tsx` or `LeagueHeader.tsx`.
7. Backend untouched: `git diff --stat main -- backend` is empty.

Live, in the browser pane against a real backend, NFL and NBA:

8. Every tab and sub-tab of one NFL and one NBA league loads its page with no
   console error, and the active tab and sub-tab are marked.
9. Season dropdown and league switcher land on the same page for the other
   season or league.
10. The picker, managers, Time Machine and a mock draft work from the top bar.
11. Draft room at 1440x900 and 1280x720: board, panels and pick prompt fit
    without a page scrollbar; your column and your pick are identifiable at a
    glance in gold. If not, record it and apply the red fallback.
12. Phone width (375x812): bottom bar, More sheet, sub-tab strip, and the pick
    card not covered by the bar.
13. Contrast measured in the browser for: muted on bg and panel, `--bg` on
    brand, `--text` on `--you-fill`, each position pill, up and down as text.
14. Keyboard: skip link, tab order through top bar and tabs, Escape and focus
    return on the dropdowns.
15. A league in an odd state (pre-draft, and pre-season at 0-0) renders the
    header and tabs sensibly.

Not verified by this plan: real phones, Safari, and a real live draft.

## Amendments after review (2026-11-10)

Each item names the finding it answers. Where the review said a finding changes
what the feature *is*, the amendment takes the option that changes least and
leaves the larger choice to Allan as a follow-up.

**Palette (Phase 1)**

- **PR-04, PR-09: "you" tints vanished and solid "you" controls looked
  disabled.** `--you-fill` was 36% L; mixed 8 to 16% into the panel it computed
  to 1.03 to 1.06:1, and as a solid control 1.75:1. Now `--you`, `--you-text`
  and `--you-fill` are all `oklch(84% 0.15 85)`. Tints mix `--you`. The three
  rules that put `color: var(--text)` on a solid `--you-fill` (1327, 2326, 6082)
  switch to `color: var(--bg)` (12.3:1, computed).
- **PR-05: teal is not one role.** The sweep is by role, not by name:
  hover and selected-row tints → `--raised` (neutral, no mix);
  focus rings, the active chip or segment, links, the primary button →
  `--brand`; bar, meter and progress fills → `--data`; the STATED provenance
  dot → `--stated`. Only "yours" is ever a gold tint.
- **PR-10:** `--data` was 2.2 delta-E from `--muted`. Now `oklch(82% 0.03 85)`.
  `ContributionBars` and `.sc-db-line` are checked in the browser.
- **PR-11:** `--fitted` sat on `--sg`'s hue. Now `oklch(84% 0.04 165)`, and a new
  `--stated: oklch(72% 0.012 80)`. Both always appear with their text label.
- **PR-06: the guard test would fail a correct stylesheet** (22 custom
  properties are legitimately set outside `:root`). The undefined-variable rule
  is dropped. The guard forbids the removed names (`--teal`, `--volt`,
  `--crimson`, `--crimson-fill`, `--crimson-text`, `--row-hover`) and `@import`,
  and scans `styles.css` **and** every `.ts`/`.tsx` under `web/src`.
- **PR-12:** the TypeScript sweep is by the literal strings `var(--teal`,
  `var(--volt`, `var(--crimson`, plus `FOCUS_SLOTS`, `THEME_COLOR`,
  `web/public/favicon.svg` and the og image if it carries the old colors, and
  the two tests that assert `'var(--crimson)'`.
- **PR-14, PR-15: fonts.** `.cond` is on about 42 elements, most of them words.
  It keeps its meaning (display face, uppercase) and only the face changes, to
  Big Shoulders Display. Three custom properties, `--font-body`,
  `--font-display`, `--font-num`, replace every rule that names a font family.
  `.mono` sets family and `tabular-nums` only and keeps the element's weight;
  Barlow Semi Condensed loads 500/600/700. Whether these fonts ship tabular
  figures is **unverified** and is a browser check.

**Navigation (Phase 2)**

- **PR-03: the public landing page reads `DESTINATION_GROUPS` and `group`.**
  `group` and `DESTINATION_GROUPS` stay, used only by the landing page, so
  public content does not change. `tab` is added beside them. Whether the
  landing page should follow the tabs is Allan's call, later.
- **PR-21: tab names.** No row is renamed; `weeklyReport` stays "Matchups &
  awards". The tab that holds power, playoff odds, team strength, luck, bench
  points and awards is called **Season**, not "Standings" (it has no standings;
  the History tab's "Standings" row does).
- **PR-01: no global navigation on a phone outside a league.** The top bar gets
  a menu button at phone width that opens a sheet with Leagues, Mock drafts,
  Managers, Time Machine and sign out. The league bottom bar's "More" opens the
  same sheet with the remaining league tabs above the global links.
- **PR-02: the compact draft-room header hid Follow live, ADP, Mock it and the
  switcher.** In a draft room or mock the top bar itself carries the league
  switcher and the tabs (global links move into the menu button), and the Draft
  tab's sub-tabs stay visible as a 32px strip. Nothing reachable today becomes
  unreachable.
- **PR-22: "sticky" in a 100vh shell.** The shell stays `height: 100vh`. The top
  bar and league header are non-scrolling flex children; `.app-main` scrolls.
  Budget: top bar 52px, league header 84px with sub-tabs (tabs 44 + sub-tabs
  32 + 8), draft room 52 + 32. On a phone the league header scrolls with the
  page and only the 48px top bar and 56px bottom bar are pinned, 104px of 812.
  That reverses the earlier "nothing pinned" call, which was made against a
  measured 252px.
- **PR-16:** `useLeagueRefresh` moves to its own module and runs wherever the
  league header mounts, compact or not; the line is hidden only in compact.
- **PR-17:** the Managers link keeps passing `railLeagueId` in router state.
- **PR-18:** the context slot holds a filter on the picker but an identity
  block on `ManagerHistory` and `ManagerTendencies`. It becomes a strip under
  the header, and the three callers' markup is restyled for a horizontal strip
  in the same commit.
- **PR-19:** the pinned pick card has three rules, not one; its `bottom` becomes
  the bar height plus 12px in each.
- **PR-20:** `body.bk-modal-fullscreen` hides the new top bar, league header and
  bottom bar by their classes.

**Page header (Phase 3)**

- **PR-07, PR-08: the 60-character rule dropped 15 of 17 static subtitles,
  could not measure the `ReactNode` ones, and hid data-driven takeaways.** The
  rule is withdrawn. Phase 3 is now: the title in the body face, sentence case,
  not `.cond`; the eyebrow not rendered when the page is inside a league (the
  league header says league and season); every `sub` renders as it does today.
  Pruning subtitles is a content pass for Allan, page by page, and is a
  follow-up.

Acceptance criterion 2 changes as PR-06 says. Criterion 4 adds: `group` is still
present and `landingCopy.test.ts` passes unchanged. The other medium and low
findings (PR-13, PR-23 to PR-26) are implementation notes the builders read in
the review file directly.
