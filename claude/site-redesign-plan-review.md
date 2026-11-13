# Adversarial plan review: 061 Site redesign (top bar, league tabs, Floodlight palette)

**Date**: 2026-11-10 · **Reviewer**: adversarial plan-review pass (read cold, no 061 code exists)
**Inputs read**: `claude/site-redesign.md`; `AGENTS.md`; `web/src/destinations.ts`, `railLeague.ts`, `searchIndex.ts`, `appSlots.tsx`, `landingCopy.ts`, `landingHtml.ts`, `managerColor.ts`, `hue.ts`, `useNarrow.ts`, `App.tsx`; `components/AppShell.tsx`, `Rail.tsx`, `LeagueRailSection.tsx`, `SportFilterRail.tsx`, `PageHeader.tsx`, `HowThisWorks.tsx`; every `<PageHeader` call site (25); the context-slot call sites in `DraftPicker.tsx`, `ManagerHistory.tsx`, `ManagerTendencies.tsx`; `styles.css` (the header comment, `:root`, the shell, the 860/700/1279px blocks, the pick card, every `--teal`/`--volt`/`--crimson*` line by grep); the tests `destinations.test.ts`, `landingCopy.test.ts`, `managerColor.test.ts`, `positionColours.test.ts`, `useNarrow.test.ts`, `DraftRoomLayout.test.tsx`, and the `it(` lists of `LeagueRailSection.test.tsx`, `LeagueSwitcher.test.tsx`, `RailPhoneNav.test.tsx`, `AppShellJumpTo.test.tsx`; `web/index.html`, `vite.config.ts`, `serve.json`, `public/favicon.svg`, `scripts/og-image/og-image.svg`.
**Checks I ran** (read-only, scratchpad only): grep counts over `styles.css` and `web/src`; a Node oklch→sRGB script for contrast and OKLab delta-E on the plan's token values (it reproduces the plan's own figures: 18.07, 8.18, 12.32, 10.78, 6.46, so it is calibrated to the same math); `curl` of the Google Fonts `css2` endpoint for the three families. **Not run**: `tsc`, `npm test`, any server, any browser. Nothing below is a browser measurement.
**VERIFIED** = I ran or read something that shows it. **REASONED** = argument only.

The three owner decisions (top bar + tabs, charcoal + gold with gold = "you", Sleeper-style position colors) are taken as given. Findings are about how the plan as written fails when built.

## Summary

| ID | Sev | Area | One line |
|---|---|---|---|
| PR-01 | HIGH | Nav, phone | Outside a league a phone has no route to Managers, Time Machine or Mock drafts: the More sheet exists only on the league bottom bar, and Jump-to does not index them |
| PR-02 | HIGH | Nav, draft room | The compact draft-room header has no sub-tabs and no switcher, so "Follow live", "ADP & prices", "Mock it" and the league switcher become unreachable from the board |
| PR-03 | HIGH | Registry | `landingCopy.ts` / `landingCopy.test.ts` consume `DESTINATION_GROUPS` and `group`; the plan does not name them. Removing `group` breaks the build and forces a decision about the public landing page's sections |
| PR-04 | HIGH | Tokens | "Your row" tints become invisible: `--you-fill` at 36% L mixed 8-16% into `--panel` is 1.03-1.06:1 against the panel, weaker than every hover tint (which the mapping turns gold) |
| PR-05 | HIGH | Tokens | `--teal → --brand` paints ~60 hover/selected tints and the STATED provenance dot in the "you" color; the plan defines `--raised` for hover and then never routes anything to it |
| PR-06 | HIGH | Acceptance | Guard test as written fails on day one: 22 custom-property names (about 118 uses) are legitimately not in `:root` |
| PR-07 | HIGH | Page header | "`sub` only when ≤ 60 characters" drops 15 of 17 static subs, cannot be evaluated for `ReactNode` subs, and flips on live data; 12 of 25 callers have no `HowThisWorks` to move text into |
| PR-08 | MEDIUM | Page header | The eyebrow is not only "league and season": 8 callers are outside any league, and on two it is the only place the sport is stated |
| PR-09 | MEDIUM | Tokens | `--you-fill` solid controls (`.pick-prompt-take`, `.pause-banner .chip.on`, `.mock-seat.mine`) are 1.75:1 against the panel (crimson was 3.31:1); likely to read as disabled |
| PR-10 | MEDIUM | Tokens | `--data` is indistinguishable from `--muted` (delta-E 2.2); the sweep rule collapses `ContributionBars`, the `.sc-db-line` rise/base pair, and turns heat ramps gray |
| PR-11 | MEDIUM | Tokens | `--fitted` moves to hue 200, exactly `--sg` and 15° from `--rb` (delta-E 7.3 from SG): the position/provenance collision `styles.css:150-160` exists to prevent |
| PR-12 | MEDIUM | Tokens, TS | The TS grep rule matches "steal" (36 files, ~6 real) and misses hard-coded hexes: `FOCUS_SLOTS` (validated against crimson and the old panel), `THEME_COLOR`, `favicon.svg`, the og image |
| PR-13 | MEDIUM | Tokens | Crimson uses the mapping leaves undecided or gets wrong: `.rvd-po` (6840), `.target-error` (6156), the sign-out item (2221-2223), `.onbrand-verdict.off` (5008) |
| PR-14 | MEDIUM | Fonts | `.cond` is on 42 elements in 23 files and about 34 are words, not numerals; 5 rules name `'Oswald'` and 3 name `'Plus Jakarta Sans'` directly and the guard does not look for them |
| PR-15 | MEDIUM | Fonts | `.mono` is on 180 elements; loading Barlow Semi Condensed at 600 only flattens every weight on every number, including numbers inline in body text |
| PR-16 | MEDIUM | Nav | `useLeagueRefresh` (the refresh POST and the data-version bump) lives in the component being replaced; the plan carries the refresh *line* and never says where the *hook* runs, and the compact header drops the line |
| PR-17 | MEDIUM | Nav | The Managers link's `state={{ railLeagueId }}` (Rail.tsx:176) is not mentioned; `ManagerTendencies` reads it |
| PR-18 | MEDIUM | Nav | Context strip: `ManagerHistory` portals an identity block built from rail classes, not a filter; `collapsed` is in the context value and in `SportFilterRail`'s props |
| PR-19 | MEDIUM | Phone layout | Bottom bar vs pick card: the plan cites one rule at ≤700px; there are three, the card sits at `bottom: 12px` full width there, and the 701-860px band and page bottom padding are unspecified |
| PR-20 | MEDIUM | Phone layout | `body.bk-modal-fullscreen .app-rail { display: none }` (styles.css:3009) has no counterpart for the top bar, league header and bottom bar |
| PR-21 | MEDIUM | Tabs | Tab naming: the plan's table gives `weeklyReport` the label "This week" (it is "Matchups & awards"); a "Standings" tab that holds no standings while the row labelled "Standings" sits under History |
| PR-22 | MEDIUM | Layout | "Sticky" is undefined in a 100vh shell whose scroller is `.app-main`; the permanent chrome height on league pages is not budgeted, and it reverses a measured phone decision |
| PR-23 | LOW | Plan facts | Line references that are off; "only 5 L apart" is still true of the new values; "DraftRoomLayout.test pins part of this" is not so |
| PR-24 | LOW | Fonts | Weights exist (fetched). Tabular numerals not verified for any of the three families. "Big Shoulders Display" is served but the catalog entry is now "Big Shoulders" |
| PR-25 | LOW | Phasing | Phase 1 must also touch two tests the plan does not list; Phase 3's title change overlaps Phase 1's `.cond` change |
| PR-26 | LOW | Tabs | Single-row tabs, the sub-tab "current" rule on an auction ADP page, and the More sheet's z-index are unspecified |

Checked and **OK** (no finding):

- The tab table accounts for all 20 keys in `LEAGUE_DESTINATIONS` (`destinations.ts:158-497`), and every `/leagues/*` and `/drafts/*` route in `App.tsx:136-203` has a row.
- "First offered row" is well defined for every sport and status: Home → `home`; Matchups → `weeklyReport` (both sports); Standings → `power`; Players → `stats` (NBA only, so football has no tab); Draft → `board`, which `destinationsFor` always offers, including an auction (where `mock`, `live`, `adp` are dropped and `board` goes to the ADP page, `destinations.ts:546-547`) and a complete draft; History → `history`.
- `railLeague.ts` and `searchIndex.ts` read only `match`, `idKind`, `isAction`, `inRail`, `formerLabel`, `label`, `href`. Renaming `inRail` → `inNav` is mechanical there (`searchIndex.ts:98`). `JumpTo.tsx` reads no `glyph` or `group`.
- `switchTarget` is a pure function of `destinationsFor`; nothing in it depends on the rail's markup. It can move as is. Tests import it and `REFRESH_POLL_MS` from `./LeagueRailSection` (two test files), so the export location is part of the rewrite.
- `serve.json` CSP (report-only) already allows both font hosts. `landingHtml.transform` only needs `</head>` and the empty root to survive, so font `<link>`s in `index.html` are safe.
- The plan's cited style lines 299, 749, 1654, 1661, 2583, 2805, 3300, 3336, 5234, 5653, 5822, 6425, 6443, 6800, 6826, 6828 all hold what the plan says. `var(--teal)` is used 262 times. The three red hexes are at `styles.css:301` and are the only non-black hexes in the file.
- Computed contrast for the new tokens matches the plan: text/bg 18.07, muted/bg 8.18, muted/panel 7.71, bg-on-brand 12.32, up 10.78, down 6.46 (6.09 on panel), position pills 6.42-11.18 with `--bg` text. `--text` on `--you-fill` computes to 9.72:1, so that open item passes on paper.
- `useNarrow.test.ts` survives the rail's removal: three other 860px blocks remain (3666 is the FAB's, but 4665 and 5172 stay unless deleted).
- `positionColours.test.ts` checks only `.analysis-seg` / `.analysis-cell` rules; unaffected.
- Backend: nothing in the plan touches it.

## Findings

### PR-01 (HIGH, VERIFIED) - On a phone outside a league, the global links have no home

**What.** The plan's phone rule: "the top bar keeps wordmark, search and account. Inside a league the tabs become a bottom tab bar ... More opens a sheet with the remaining tabs and the global links." So Leagues, Mock drafts, Managers and Time Machine exist on a phone only inside the More sheet, and the More sheet exists only inside a league.

**Where.** On `/`, `/managers`, `/history`, `/mock/*` there is no league, so no bottom bar. Search does not cover it: `searchIndex.ts:22` indexes `'league-page' | 'season-board' | 'manager'` only. `/managers`, `/history` and the mock modal are not in the index. Today the phone bar keeps those four rows as labelled pills (`styles.css:2188-2197`).

**Why it fails.** A phone user on the picker cannot reach Managers or Time Machine at all, and reaches a mock only if the picker page happens to offer its own button.

**Change.** Say where the global links live on a phone outside a league (a menu button in the top bar, or the bottom bar on every route with league tabs swapped in when inside one), and add a test for it.

### PR-02 (HIGH, VERIFIED) - The compact draft-room header strands the Draft sub-pages

**What.** "Draft room and mocks: the league header renders in a compact single row (league name, season, tabs; no refresh line, no sub-tabs)." `live`, `adp` and `mock` are sub-tabs of Draft. The Draft tab links to its first offered row, `board`, which is the page you are already on.

**Where.** `destinations.ts:404-465`. The only other links to `/drafts/:id/live` in the app are on the picker card and League home (`DraftPicker.tsx:404`, `LeagueHome.tsx:364`); `DraftView` has none. Today the collapsed rail keeps the pulsing live row, the ADP row and "Mock it" as icons.

**Why it fails.** From the pre-draft room, "Follow live" needs three steps through Home. That is the route used on draft night. The league switcher is also not in the compact list, and `LeagueRailSection.tsx:471-478` records that draft rooms "are exactly where comparing seasons is wanted", with a test for it (`LeagueSwitcher.test.tsx:140`).

**Change.** Either keep the Draft tab's sub-tabs in the compact row (they are at most four items), or state that `live`/`adp`/`mock` render inline beside the tabs there. Name the switcher's fate in compact mode explicitly.

### PR-03 (HIGH, VERIFIED) - `landingCopy` is an unnamed consumer of `group`

**What.** The plan lists `railLeague.ts`, `LeagueRailSection`, `searchIndex.ts` and the switcher as readers of the registry. There is a fifth.

**Where.** `landingCopy.ts:2-7, 53-56, 105-107` imports `DESTINATION_GROUPS` and `DestinationGroup`, types its groups as `Exclude<DestinationGroup, 'home'>`, and reads headings through `headingOf`. `landingCopy.test.ts:17-31` asserts every landing feature sits in the same group as its destination and that the groups are "the four non-home rail groups, in rail order". `landingHtml.ts` writes those headings into the pre-rendered HTML crawlers receive (spec 050), and `SignIn.tsx:120` renders them.

**Why it fails.** "`destinations.ts` swaps `group` for a required `tab`" stops `tsc -b` in a file the plan never mentions. The fix is not mechanical: following the tabs gives the public page five sections, one of them ("Players") entirely NBA-only, and moves Power rankings, Weekly pot, Trends and Player stats between sections. Keeping the old four sections means keeping a second grouping the plan says is gone.

**Change.** Decide and write down: the landing page keeps its own four-section grouping (declared in `landingCopy.ts`, test rewritten to stop comparing with the nav), or it follows the tabs. **This one changes public content and needs Allan.**

### PR-04 (HIGH, VERIFIED by computation, not in a browser) - "Mine" tints disappear

**What.** `--crimson-fill → --you-fill`, and `--you-fill` is `36% 0.08 85`. Most "this is yours" surfaces are not the solid fill; they are a small mix of it into the panel.

**Where.** `styles.css:906-907` (`.board .col-head.mine`, 16% / 22%), `5197`, `5213` (14%), `5270` (18%), `5747`, `5873` (8%), `6038` (7%).

**Why it fails.** Computed with the plan's values: `--you-fill` 16% over `--panel` lands at L 20.0, contrast 1.06:1 against the panel; at 8% it is 1.03:1. `--raised`, the hover surface, is L 21.5. The current crimson mix at 16% is 1.13:1 and also shifts hue from navy to red; the new one stays on the panel's own warm hue. Rows that mix `--crimson` rather than `--crimson-fill` (`.pr-row.mine` 2754, `.record-row.mine` 3466, `.wp-me` 6934) fare better because `--you` is 84% L (12% → 1.20:1), so the same "yours" meaning will render at two visibly different strengths depending on which token a rule happened to use. Your column header on the draft board is in the weak group and is left with only its 1.5px ring.

**Change.** Add a mapping rule: a `color-mix` of `--crimson-fill` becomes a mix of `--you` (not `--you-fill`), and name a target strength. Keep `--you-fill` for solid fills behind text only. Add the board column header to acceptance item 11 by name.

### PR-05 (HIGH, VERIFIED) - The sweep paints hover, selection and provenance in the "you" color

**What.** The rule is "`--teal → --brand`, except a `background` on a bar, meter, fill or progress element". `--brand` and `--you` are the same color.

**Where.** 121 lines set a `background` from `--teal`. By my read about 12 are data fills; around 60 are low-percentage hover or selected tints on rows and cells: `.board .cell:hover` (993), `.board .col-head:hover` (895), `.draft-row:hover` (743), `.mgr-row:hover` (1257), `.picker-row:hover` (1609), `.pr-row:hover` and `.pr-row.on` (2752-2753), `.record-row:hover` (3465), `.lockin-row:hover` (6478), and so on. Also `.board .col-head-dot.stated` (924) and `.prov-dot.stated` (1172), the STATED provenance dots.

**Why it fails.** (a) A hovered row gets a gold tint at 6-12% of an 84% L color, which is stronger than the "mine" tint in PR-04. On the board, hovering any cell looks more "yours" than your own column. `.pr-row.on` and `.pr-row.mine` (2753-2754) become the same rule twice. The plan lists "selected and yours look alike" as a risk for chips; it is the default for every row. (b) The plan creates `--raised` as "sticky headers, active tab, row hover" and maps only `--row-hover` (16 uses) to it; the teal hover tints never get there. (c) A STATED seat's dot turns gold, the same color as the ring on your seat. Provenance reading as identity is the kind of quiet overstatement this project's rules are written against.

**Change.** Split the teal rule three ways: interactive foreground/outline/solid control → `--brand`; hover or selected *tint* on a row, cell or card → `--raised` (or a neutral mix); data fill → `--data`. Give STATED its own answer explicitly. Without this the builder has to make about 120 judgment calls with a rule that covers a dozen of them.

### PR-06 (HIGH, VERIFIED) - The guard test false-positives immediately

**What.** Acceptance item 2: fail "on any `var(--x)` whose `--x` is not defined in `:root`".

**Where.** 49 distinct names are referenced with `var()` in `styles.css`; 27 are in `:root`. The other 22 (about 118 uses) are component-scoped: `--sl-hue` (23), `--tint` (18), `--pos-b` (9), `--vt` (8), `--lead-hue` (7), `--scar-hue`, `--pos-a/c/d`, `--pos-s1..t2`, `--pos-split`, `--hue`, `--pin`, `--rvd-tint`, `--mrv-tint`, `--adp-red`, `--fg`, `--amber`. Only 6 of those names are declared anywhere in CSS (14 declarations outside `:root`); the rest are set from inline styles in TSX (`DraftBoard.tsx:391`, `ScarcityMeter.tsx:57`, `positions.ts:78,111`, `SuperlativeStandings.tsx:223`, `TrophyCase.tsx:148`, `Superlatives.tsx:243`, `SeasonForecast.tsx:312`, `LeagueAnalysis.tsx:672,725`) or exist only as a `var(--x, fallback)`.

**Why it fails.** The test cannot pass against a correct stylesheet, so it will be weakened on the spot, probably to just the four banned names, and then it no longer catches a typo like `var(--brnd)`.

**Change.** Specify it as: every `var(--x)` must be defined in `:root`, or declared somewhere in `styles.css`, or be on an explicit allowlist of names set from TSX, or carry a fallback. Also extend the banned list to `--crimson-fill`/`--crimson-text` fallbacks (`var(--crimson-fill, var(--crimson))` appears 5 times; the `var(--crimson` prefix does catch them) and to the font names in PR-14. TSX is outside this test entirely: `Avatar.tsx:48`, `managerColor.ts:82`, `ContributionBars.tsx:20`, `AvailabilityPanel.tsx:728` hold `var(--crimson)` / `var(--teal)` strings that a stylesheet-only guard never sees. Add a grep over `web/src/**/*.ts(x)` for the removed names.

### PR-07 (HIGH, VERIFIED) - The 60-character `sub` rule

**What.** "`sub` only when it is 60 characters or fewer; a longer `sub` is not rendered by `PageHeader` and the caller's text moves into that page's existing `HowThisWorks` panel, or is cut."

**Where and why it fails.**

- `sub` is `ReactNode` (`PageHeader.tsx:14`). `DraftPicker.tsx:301-309` and `MockSetup.tsx:160-165` pass JSX with `<strong>`; there is no length to measure. The component can only apply the rule to strings, so those two are exempt by accident.
- Of the 17 static string subs, 2 are ≤ 60 (ScheduleGrid 55, PlayerPage 60). The other 15 run 62-214. The rule removes the subtitle from nearly every page; it should be stated as that.
- It flips on data. `ExpectedWins.tsx:42-49`: the neutral line is 50 characters and renders; the line with an actual finding ("<team> has been the luckiest, 1.52 wins above what their scoring earned.") is over 60 and silently does not. The page shows its takeaway only when it has none. `WeeklyPot.tsx:62-66` and `LeagueHome.tsx:241-249` are also built from data.
- 12 of 25 callers have no `HowThisWorks` on the page: AdminDigest, HistoricalRoom, LeagueHome, ManagerComparison, PlayerCompare, PlayerPage, PlayerTrends, Rivalries, ScheduleGrid, StatCalculator, StatLeaderboard, WeeklyPot. "Existing panel" does not exist for them.
- The house rule at `styles.css:58-64` says the subtitle is "one sentence, the takeaway" and `HowThisWorks` is methodology that "keeps its original text". Moving takeaways into the methodology fold contradicts it; the plan rewrites the color section of that comment and not the WORDS section.
- 25 callers, not 24 `sub` strings: all 25 pass a `sub`.

**Change.** Drop the runtime length check. Decide per caller at build time (the plan already promises a disposition list): keep, shorten, or delete, with `PageHeader` rendering whatever it is given. Keep data-driven subs. Update the WORDS rule in the same commit.

### PR-08 (MEDIUM, VERIFIED) - The eyebrow carries more than league and season

**What.** "No eyebrow (the league header already says league and season)."

**Where.** Eight callers render where no league header exists: AdminDigest ("Admin"), DraftPicker (`sportTitle(sportFilter)`, placed there by spec 013 US4 so the active sport filter "is still said out loud", `DraftPicker.tsx:297-299`), HistoricalDraft and HistoricalRoom ("Time Machine"), ManagerComparison, ManagerHistory, ManagerTendencies, MockSetup (`New NFL mock` / `New NBA mock`, `MockSetup.tsx:158`, the only statement of the mock's sport in the header).

**Why it fails.** On the picker the filter moves to a strip (fine, it is then visible there); on MockSetup the sport disappears from the header. REASONED addition: on season pages the eyebrow prints `data.season` from the payload, while the league header will print the route's season; five pages already carry `SeasonFallbackNote` for the case where those differ, but LeagueAnalysis, Rivalries, ScheduleGrid, PlayerTrends, WeeklyPot and the stats pages do not. I did not check whether their payload season can differ from the route's.

**Change.** Keep `eyebrow` rendering for non-league pages, or list each of the eight with where its text goes. Check the six pages named above for a payload/route season mismatch before removing the year from them.

### PR-09 (MEDIUM, VERIFIED by computation) - Solid "you" controls lose their edge

**Where.** `styles.css:6082` `.pick-prompt-take`, `1327` `.pause-banner .chip.on`, `2326` `.mock-seat.mine .mock-seat-face`: `background: var(--crimson-fill); color: var(--text)`.

**Why.** `--text` on `--you-fill` is 9.72:1, fine. The fill against its surroundings is 1.75:1 on `--panel` and 1.86:1 on `--bg` (crimson-fill on the old panel: 3.31:1). A dark brown button on a charcoal panel, next to 84% L gold primary buttons, is below the 3:1 non-text threshold and will read as the disabled one. `.pick-prompt-take` is the control that takes your pick. `--you-fill` as specified is also slightly outside sRGB and clips (to about `#503900`).

**Change.** For controls, use `--you` with `--bg` text (12.3:1), and keep `--you-fill` only where a quiet surface is wanted. Add `.pick-prompt-take` and the pause-banner chip to acceptance item 11.

### PR-10 (MEDIUM, VERIFIED) - `--data` collides with `--muted`

**What.** `--data` is `70% 0.02 80`; `--muted` is `72% 0.012 80`. OKLab delta-E 2.2, contrast between them 1.08:1.

**Where.** `ContributionBars.tsx:20-21`: `PART_COLOURS = ['var(--teal)', ...]` with `OTHER = 'var(--muted)'`. By the rule the first part becomes `--data` and is the same gray as "other"; by the alternative it becomes gold, which means "you". `styles.css:6349-6351`: `.sc-db-line` base is `--muted`, `.rise` is `--teal` (deliberately neutral per the comment there), `.fall` is `--wr`; rise becomes indistinguishable from the base line. Heat ramps are not "a bar, meter, fill or progress element" and are undecided: `.sg-c1..c6` (5678-5683, the schedule grid, with `color: var(--bg)` at the top two steps) and the availability cells in `AvailabilityPanel.tsx:728` (`color-mix(var(--teal) N%, var(--panel))` in TSX). As `--data` the low steps vanish; as `--brand` the whole grid is "you".

**Change.** Give `--data` a hue of its own or more chroma, and name a ramp token for heat cells. List the categorical and ramp cases in the mapping.

### PR-11 (MEDIUM, VERIFIED) - `--fitted` lands on a position hue

**Where.** Plan table: `--fitted: 80% 0.04 200`; `--sg: 78% 0.11 200`; `--rb: 78% 0.13 185`. `styles.css:150-160` explains that `--fitted` was split off `--rb` precisely because a provenance color sharing a position hue "gets worse, not better, with basketball's five new position tokens".

**Why.** Delta-E fitted vs SG is 7.3. At chroma 0.04 it is also close to the neutrals (9.3 from `--muted`). On an NBA board the FITTED dot and an SG cell share a hue. The plan also remaps some volt tags to `--fitted` "by meaning", widening its use.

**Change.** Pick a hue no position in either sport uses (the new table leaves roughly 0-40 and 140-170 to `--down`/`--up`, so this needs an actual choice), and state it.

### PR-12 (MEDIUM, VERIFIED) - The TypeScript color rule finds the wrong files

**What.** "the files `grep -l "teal\|crimson\|volt"` lists under `web/src`, e.g. `managerColor.ts`, `OwnershipMark.tsx`, `ChangeDumbbell.tsx`".

**Where.** That grep returns 36 files; most match "steal" or "volt" in copy. `OwnershipMark.tsx` and `ChangeDumbbell.tsx` contain the words only in comments. The real token strings are four: `Avatar.tsx:48`, `managerColor.ts:82`, `ContributionBars.tsx:20`, `AvailabilityPanel.tsx:728`. Two tests assert the string `'var(--crimson)'`: `BumpChart.test.tsx:50`, `managerColor.test.ts:169`; neither is in the plan's list of pinned tests.

**What the grep cannot find.**
- `managerColor.ts:48` `FOCUS_SLOTS = ['#3987e5', '#199e70', '#c98500']`, validated as a set of four with crimson `#d33a3c` on surface `#09121c` (comment at 26-46, test at `managerColor.test.ts:54-86` with crimson hard-coded). With a gold "me" line (about `#f7c243`) the third slot `#c98500` is the nearest neighbor: delta-E 17.3 under normal vision, above the floor of 15 mostly on lightness. The color-blind figure, which was already in the "legal only with labels" band, is not recomputed, and the file refers to `scripts/validate_palette.js`, which is not in this repo (`web/scripts` holds only `og-image/`). The existing test will keep passing against a color that is no longer on screen.
- `landingHtml.ts:16` `THEME_COLOR = '#040913'` ("mirrors `--bg`"); new `--bg` is about `#070604`.
- `public/favicon.svg`: navy tile, teal "BK", with a comment tying it to the tokens.
- `scripts/og-image/og-image.svg` and the rendered `public/og-image.png`: navy and teal throughout.
- Crest and avatar literals (`oklch(30% 0.05 h)` in five files) are hue-hashed and can stay.

**Change.** Replace the grep with the explicit list above. Re-validate `FOCUS_SLOTS` against the new "me" color and surface and update the test's reference color. Say whether favicon, theme-color and og image are in or out.

### PR-13 (MEDIUM, VERIFIED) - Crimson uses the mapping does not settle

- `.rvd-playoff` (6828) → `--down` per the plan, but its companion `.rvd-po` (6840, `color: var(--crimson-text)`) is not listed and would become `--you-text`: the same "playoff" mark in two colors, one of them yours.
- `.target-error` (6156, wavy underline) is an error and is not in the `--danger` list; it becomes gold.
- `.app-account-menu-item` (2221-2223), the Sign out item, uses crimson as "destructive"; it becomes gold, the primary-action color.
- `.onbrand-verdict.off` (5008) is neither you nor an error.
- `.board .col-head.assumed` (6034-6038) is a "you, assumed" state drawn as a low-alpha dashed crimson ring so "the two cannot be mistaken"; it inherits PR-04's problem.
- `--down` and `--danger` are the same value, so "worse" and "broken" are one color, and `--down` now sits on the hue the old stylesheet moved it off (`styles.css:36-37`). Not a blocker; say it is intended.
- `var(--volt, var(--teal))` at 6050-6055: read the selector and decide; a blind replace gives `var(--brand, var(--brand))`.

### PR-14 (MEDIUM, VERIFIED) - `.cond` is mostly words

**What.** "Display (`.cond`): Big Shoulders Display 700/800, for numerals that matter."

**Where.** `className` with `cond`: 42 occurrences in 23 TSX files, plus 2 in `landingHtml.ts`. About 8 are numerals (`league-card-season`, the on-clock countdown, `pr-number-one-rank`, two `lh-hero-num`, `rnd`, a season label). The rest are words: headings ("Ready when you are", "No board yet", "Start a mock draft", "Choose stats"), small labels ("Your team", "On the clock", "Targets", "You", "vs", "auto", the pick-feed run and fit tags, the scarcity run sentence), loading text, the wordmark, and the landing page's kicker and section titles.

**Why it fails.** Changing the class's font changes all of them. Several are 10-12px labels; a tight display face at that size is a legibility risk (REASONED, needs a look). Separately, five rules name `'Oswald'` directly (`styles.css:416, 420, 1760, 1986` and the `.cond` rule at 203) and three name `'Plus Jakarta Sans'` (206, 310, 2831) besides `body` at 200. Any left behind falls back silently to Arial Narrow or the system font once the `@import` is gone; nothing in the acceptance list looks for them.

**Change.** Add a numeral class for the display face and decide what word-`.cond` becomes (Barlow 700 uppercase is the obvious answer). Add `Oswald` and `Plus Jakarta` to the guard test's banned strings.

### PR-15 (MEDIUM, REASONED) - `.mono` at one weight

**What.** "Numbers in tables (`.mono`): Barlow Semi Condensed 600." `.mono` is currently `font-family: inherit` (`styles.css:204`) and appears on 180 elements, many inline in sentences and many with their own weight (400 through 800).

**Why.** With only the 600 face loaded, every weight request resolves to it: regular numbers get heavier, bold ones no longer stand out from regular ones, and a number inside a Barlow sentence switches to a condensed semi-bold mid-line. The plan describes this as a table treatment; the class is not limited to tables.

**Change.** Either load 500/600/700 of the condensed face, or leave `.mono` as tabular Barlow and introduce the condensed face on table cells only.

### PR-16 (MEDIUM, VERIFIED where, REASONED consequence) - The refresh hook

**Where.** `useLeagueRefresh` is defined and called inside `LeagueRailSection.tsx:61-110, 246-249`. It fires the `POST` once per league, polls while RUNNING, and bumps every season's data version so pages refetch (spec 009). Six tests cover it (`LeagueRailSection.test.tsx:231-400`). Today it runs in draft rooms too, because the collapsed rail still mounts the section.

**Why.** The plan carries "the refresh line" into `LeagueHeader` and says the compact variant has "no refresh line". If the hook travels with the line, leagues stop refreshing from draft rooms and mocks. If the phone layout mounts both a header and a bottom bar from the same component, it can fire twice.

**Change.** State that the hook runs exactly once per league in every header variant, independent of whether the line is shown, and keep the six tests.

Also in this file and not in the plan: the per-group open/closed state in `localStorage` `bk.rail.groups.v1` (lines 142-163, tests 598-646) goes away with groups. Fine, but say so and leave the stale key alone or clear it; the plan mentions only `bk-rail`.

### PR-17 (MEDIUM, VERIFIED) - Managers link state

`Rail.tsx:174-185` links to `/managers` with `state={{ railLeagueId }}` when a league is showing; `ManagerTendencies.tsx:311-340` reads it to list that league's managers first. The plan describes the top-bar link as "**Managers** (`/managers`)". A plain link drops the behavior with no test failing unless one exists for it; I did not find one in the files I read.

### PR-18 (MEDIUM, VERIFIED) - What the context slot actually holds

- `DraftPicker.tsx:284-292` and `ManagerTendencies.tsx:403-411`: `SportFilterRail`, three rows with a dot, label and count, built from `app-rail-section`, `app-rail-row`, `app-rail-dot`, `app-rail-count`. The plan covers the picker; the Scouting report has the same filter and is not mentioned.
- `ManagerHistory.tsx:108-148`: not a filter. A "Manager" label, the avatar, the name, a per-sport counted-seasons line (with a long comment on why that number must match the page, FR-007), and an "All managers" link with a glyph. As "a strip under the top bar" this duplicates the page header directly beneath it, and the glyph contradicts "no glyphs".
- All three are styled entirely by rail classes that Phase 2 deletes.
- `RailContext` is `{ node, collapsed }` (`appSlots.tsx:40`); both filter call sites pass `rail.collapsed` into a prop that changes the `aria-label`. "Name-compatible hook" needs to say what `collapsed` is now (always false, or removed with the callers updated).
- On a hinted route (`/managers/:id/history` from a league) both the league header and the strip render, with no tab current.

**Change.** Name each of the three portals and its new form; for ManagerHistory, consider dropping the portal and keeping only the "All managers" link.

### PR-19 (MEDIUM, VERIFIED) - Bottom bar and the pinned pick card

**Where.** Three rules, not one: `styles.css:1516-1521` (≤700px: `bottom: 12px; left: 12px; right: 12px`), `1526-1531` (≤1279px: `bottom: 76px; left: 12px`), `1532-1534` (≤700px again, re-asserting `bottom: 12px`). The STACKING comment (108-114) says 44 was chosen "just below the jump-to FAB so that is never covered".

**Why.** The bottom bar appears at ≤860px (`useNarrow`) and takes z-index 45. At ≤700px the card is full width at 12px from the bottom, directly under the bar. The plan's sentence "sits above the bottom bar's height" is the intent; the build needs the three rules, a bar height (with `env(safe-area-inset-bottom)`), and the 701-860px band, where `.app` is still a fixed 100vh with an internal scroller (`styles.css:2243` applies only ≤700px) so a `position: fixed` bar overlays the bottom of `.app-main` unless it is a flex child. Page content also needs bottom padding to clear the bar (`.app-main` has 32px at ≤700px, line 2232). `AppShellJumpTo.test.tsx:60-92` ("the phone return affordance", "outside the scrolling pane") pins the FAB the plan removes; its replacement behavior should be named. The draft room on a phone already stacks status, controls, prompt, a Board|Players toggle and the card; add the bar and the sub-tab strip to that budget before building.

### PR-20 (MEDIUM, VERIFIED) - The full-screen ballot hides `.app-rail` by class

`styles.css:3004-3009` and `PowerRankings.tsx:441-442`: on a phone the ballot adds `bk-modal-fullscreen` to `body` and CSS removes `.app-rail`, because "a full-viewport backdrop was not enough on a real phone". With the rail gone the selector matches nothing and the top bar, league header and bottom bar stay. Carry the rule to the new chrome.

### PR-21 (MEDIUM, VERIFIED) - Tab and sub-tab names

- The table is headed "key → current label" and gives `weeklyReport` "This week". Its label is "Matchups & awards" (`destinations.ts:183`); "This week" is the old group heading. If this is a rename, `formerLabel` is a single string already holding "Weekly report" (`destinations.ts:178`), so "Matchups & awards" would stop matching in Jump-to, and the landing page names the feature "Matchups & awards" (`landingCopy.ts:65`). If it is not a rename, the table is wrong. The plan also says "Renaming routes to match labels" is out of scope but is silent on labels.
- The "Standings" tab holds Power rankings, Playoff odds, Team strength, Luck, Bench points and Awards. None is a standings table; that lives on League home, and the row labelled "Standings" (`history`) is under the History tab. A reader who taps Standings gets Power rankings. This is bug class 5 in `AGENTS.md`.
- Top bar "Time Machine" is `/history`; the league tab "History" is `/leagues/:id/history`. Two adjacent navs, one word.

**Change.** Fix the table, state whether any label changes, and reconsider the "Standings" tab name ("Season" or "Rankings").

### PR-22 (MEDIUM, VERIFIED layout, REASONED consequence) - "Sticky" and the height budget

`.app` is `height: 100vh` and `.app-main` is the only scroller (`styles.css:231-236, 1958-1961`); the page itself does not scroll above 700px. A top bar in that shell is a fixed band by construction, not sticky, and so is the league header: about 52px plus identity row, tabs and sub-tabs, perhaps 150-180px on every league page at every desktop height, where the rail cost none. At ≤700px the document scrolls and the current chrome scrolls away on purpose, with a measurement behind it ("252px of 812 ... a third of the viewport", `styles.css:2234-2242`, repeated at 3669-3673). The plan does not say whether the top bar and league header pin or scroll there; pinning them reverses that decision without mentioning it, as the plan did for the two reversals it does call out.

**Change.** Give a target height for the full league header and say what pins at each of: >860, 701-860, ≤700.

### PR-23 (LOW, VERIFIED) - Plan facts to correct

- `styles.css:226-229` → the "rail is the site's navigation" comment is at 246-249. `35-39` (crimson means you) → 28-31. `40-41` (teal never data) → 32-33.
- "Navy-slate ... with `--bg` and `--panel` only 5 L apart": the new values are 12% and 17%, also 5 apart (computed 1.06:1). Either it was not the problem or the new palette keeps it.
- "`DraftRoomLayout.test.tsx` pins part of this": it pins region order, the stacked toggle and the split arithmetic inside `.room`. Nothing in it sees the shell, so the draft-room fit rests entirely on the browser check in item 11.
- "20 league rows in 5 groups": 20 rows, 18 of them in the rail.
- `Rail.tsx` has its own 700px `matchMedia` copy (line 6) for the account menu; the plan speaks only of 860px. `TopBar` should not grow a third.

### PR-24 (LOW, VERIFIED weights, NOT VERIFIED numerals) - Fonts

Fetched `fonts.googleapis.com/css2` (generic request): Barlow 400/500/600/700, Barlow Semi Condensed 600 and Big Shoulders Display 700/800 all return faces. The same endpoint also serves a family named "Big Shoulders", and the catalog metadata URL for "Big Shoulders Display" returned 404 while "Big Shoulders" resolved, which suggests the Display name is now the legacy one. I did not download font files, so whether each family's digits are tabular under `tabular-nums` is unverified. Add a browser check: equal rendered width of `1111` and `0000` in `.mono` and in the display numerals. Nothing in tests, the landing page or the og image depends on Oswald or Plus Jakarta by name (the og image uses system Bahnschrift).

### PR-25 (LOW, REASONED) - Phasing

- Phase 1 alone must update `BumpChart.test.tsx:50` and `managerColor.test.ts:54-86, 169`, or it does not pass. `MockSetup.test.tsx:209` mentions the tokens in a comment only.
- Phase 1 restyles rail CSS that Phase 2 deletes. That is the cost of separate commits; accept it, but do not spend review time on rail colors.
- Phase 1 changes what `.cond` looks like; `PageHeader` and `PowerRankings.tsx:810` keep `.cond` on titles until Phase 3. A Phase-1-only revert point therefore shows every page title in the display face. If Phase 1 must stand alone visually, move the title's `.cond` removal into it.
- The guard test (item 2) belongs to Phase 1, the `destinations.test.ts` changes to Phase 2. Neither depends on the other. Phase 2 cannot pass without resolving PR-03.
- `git diff --stat main -- backend` is checkable. Item 6 ("no glyph characters from the old set") needs the set written down: the 20 in `destinations.ts` plus `◆ ● ↺ ▶ ⌕ ⏻ « » ▾ ▸` from `Rail.tsx` and `LeagueRailSection.tsx`, and it should also cover `ManagerHistory.tsx:141` and `AppShell.tsx:266`.
- Item 15 ("renders ... sensibly") and item 11 ("identifiable at a glance") are not checkable as written. Replace with named elements and a contrast or screenshot criterion.
- Item 13 lists contrast pairs but none of the tint pairs from PR-04/PR-09, which are the ones at risk.

### PR-26 (LOW, REASONED) - Small unspecified states

- Home, and History with two rows, and football's Matchups with two: does a one-item sub-tab strip render?
- On an auction league's ADP page the current row is `board` only through `railKeyFor` (`destinations.ts:561-566`); the rail today marks rows by `dest.match.test(pathname)` (`LeagueRailSection.tsx:413`), which does not light `board` there. The new sub-tab should use `railKeyFor`, and a test should say so.
- The More sheet, season dropdown and switcher need places in the page-level z-index list (`styles.css:99-115`); the switcher menu is at 40 today (3722) and is not in that list.
- The `mock` action "at the end of the row" is off-screen in a horizontally scrolling phone strip.
- The league identity link currently goes to the board (`LeagueRailSection.tsx:389, 442`); with a Home tab beside it, say where the crest and name link.
