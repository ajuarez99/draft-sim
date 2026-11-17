# 074: fixes from the security, design, accessibility and copy reviews (2026-11-17)

Four read-only reviews of the built tree, condensed by the session into the fixes to make. None of the four ran a browser; items marked **browser** are for the live pass, not for the fix builder. The bug hunt is separate: `code-review.md`. Paths are under `web/src/` unless they start with `backend/` or `web/`.

Status column is filled by the fix pass: `fixed`, `not fixed (why)`.

## A. Blockers

| # | From | Where | Problem | Fix |
|---|---|---|---|---|
| A1 | design | `pages/SeasonPage.tsx:300` | `limited` never clears and ignores `user`: a guest who hits the limit and signs in through the panel's own form stays stuck with the panel and a disabled button until a reload. | `atLimit = !user && (limited \|\| usedAllowance)`; reset `limited` when `user` appears; after sign-in show the claim line where the state line was, and move focus to it (see C5). Test it. |
| A2 | design | `styles.css:7270-7271`, `components/HistoricalResults.tsx:239` | `.hist-callout-steal` uses `--you` in both voices, so a stranger sees the drafter's steal in the viewer-only colour. | Scope the `--you` rule to the owner voice; in shared voice use `--raised` (or `--brand` 12%) with `--text` on the heading. |

## B. Security

| # | Where | Problem | Fix |
|---|---|---|---|
| B1 | `web/scripts/fetchSeasons.ts` (validators), `redraftPages.ts` `seasonPageEntry`, `web/vite.config.ts` `closeBundle`, `landingHtml.ts` `pageHeadTags`, `siteFiles.ts` `sitemapXml` | A season `slug`/`label` from the backend or a fixture goes unvalidated into a file path, the canonical `href` (written raw) and the sitemap `<loc>`. Reachable only at build time. | Require `/^\d{4}(-\d{2})?$/` for slug and label in both validators; in `closeBundle` refuse a target outside `outDir`; escape the canonical; XML-escape sitemap locs. Tests: `../x`, `a"b`, `a&b` each throw; a path outside `outDir` is refused. |
| B2 | `backend/.../HistoricalGameService.java` `reduced` | The best-roster total can still be back-calculated from `userPointsFor` and the score. Accepted (a number, not a roster; season pages are the open answer key by Allan's Q2 choice). | A code comment saying exactly that. No behaviour change. |
| B3 | `web/scripts/fetchSeasons.ts`, DEPLOY.md | The build fetch sends no bearer. Production's `API_TOKEN` gate is off today (DEPLOY.md:168), so it works; it would 401 if the gate were turned on. | Do **not** add a `VITE_` token. Note in DEPLOY.md "If `API_TOKEN` is ever switched on": exempt `/api/history/public/**` in `ApiTokenFilter` first, or the Redraft build fails. (Doc task T045, not the fix builder's.) |

## C. Accessibility (should-fix; no blockers found)

| # | Where | Fix |
|---|---|---|
| C1 | `pages/SeasonPage.tsx:63`, `seasonPageHtml.ts:80` | Tie marker: `aria-label` on a plain span is ignored. Keep "T5" visible and add visually hidden "tied for 5th" inside it (the existing sr-only idiom near `styles.css:6940`). |
| C2 | `styles.css:7539`, `SeasonPage.tsx:345` | Limit panel heading takes focus but has `outline: none`. Remove it; add a visible `:focus` outline. Make the heading an `h2` (it sits under the h1, before "The draft"). |
| C3 | `SeasonPage.tsx:328`, `pages/SharedResult.tsx:72`, `components/DailyCards.tsx:171` | A focused button that becomes `disabled` while starting drops focus. Use `aria-disabled` + a guard in the handler. |
| C4 | `pages/HistoricalDraft.tsx:103-108` | `/#sign-in`: after scrolling, focus the panel's h2 (`tabIndex=-1`, `preventScroll`), not the input. Depend on `location.key` so a second tap on "Sign in" works. |
| C5 | `HistoricalDraft.tsx:323`, `SeasonPage.tsx` | Sign-in unmounts the focused form. Afterwards move focus to the claim line (a `tabIndex=-1` container). Keep an always-mounted empty `role="status"` node and fill it with the claim text so it is announced. |
| C6 | `components/TimeMachineShell.tsx`, the page components | On pathname change (no hash): scroll `main` and the window to the top and focus the page's h1 (`tabIndex=-1`). This also fixes "Next season opens at the bottom of the page" (design). |
| C7 | error containers in `SeasonPage`, `SharedResult`, `DailyPage`, `DailyCards`, `SportIndex`, `SignInForm` | `role="alert"` on errors; `role="status"` on progress text ("Starting…", "Checking…", loading lines); the daily rollover sentence in its own `role="status"` node (the countdown stays silent). `SignInForm`: `aria-describedby` + `aria-invalid` on the input when an error shows. |
| C8 | `pages/SharedResult.tsx:63` | No h1 on the shared result. Make the kicker the h1 (keep its look). |
| C9 | `SeasonPage.tsx:370`, `seasonPageHtml.ts:199` | Prev/next nav is labelled "All NBA seasons". Label it "Other {NBA\|NFL} seasons". |
| C10 | `pages/SportIndex.tsx:61-63`, `seasonPageHtml.ts:225` | Year pills read only "2016-17": add visually hidden " NBA Redraft" / " NFL Redraft". Yesterday rows: link text "How {label} played out". |
| C11 | `seasonPageHtml.ts:187,202,255,309-312` | Static pages: the Draft/Play buttons and sign-in form are disabled with no reason. Add a `<noscript>` line "Drafting needs JavaScript turned on." beside them. |
| C12 | `styles.css` | `:focus-visible` outline (2px `--text`, 3px offset) for `.tm-foot a`, `.tm-sport-tile`, `.tm-newest`, `.tm-fold-link`. Touch height ≥ 44px at phone width for `.tm-btn`, `.tm-sport`, `.topbar-signin`, `.tm-pill-link`; `.tm-foot a` gets `padding: 11px 4px`; the nested fold's summary gets padding. |
| C13 | `components/RedraftFoot.tsx`, `HistoricalResults.tsx:597`, tables | Wrap the footer nav in `<footer>`; drop `aria-current` from the drafter's standings row (the text suffix marks it; add the suffix there if it is missing); `scope="col"` on the result tables' headers. |
| C14 | `styles.css:7256` | `.tm-kicker` negative bottom margin can overlap when it wraps. Use normal flow spacing. |

## D. Design (should-fix)

| # | Where | Fix |
|---|---|---|
| D1 | `SeasonPage.tsx:322-361` | A guest mid-draft on this season sees "You've used your free season draft" beside three Resume links. Show the limit panel only when there is no resumable game for this season; one Resume control (the top one); hide the guest state line while at the limit. In the panel, "Play today's game" is `.tm-btn.quiet` (sign-in is the panel's one filled control). |
| D2 | rank rows in `SeasonPage.tsx`, `seasonPageHtml.ts` | Points are bare numbers. Render "{n} pts" (muted unit) on finisher, mover, position and dream-roster rows. |
| D3 | `SeasonPage.tsx:90`, `redraftCopy.ts:69-70` | "Going in 4th" has no denominator while "Finished 131st of 412" has one, and the pools differ. Use `rankedPlayers` from the payload: "Going in 4th of {rankedPlayers} ranked". |
| D4 | `styles.css:7510-7531` | Lead-row styling leaks into every by-position list. Scope it to the top-finishers list, and give the first bust and first steal the same lead treatment. |
| D5 | `SeasonPage.tsx:276-286`, `HistoricalDraft.tsx:202-217`, `redraftData.ts:146` | Dead ends: season loading/error states get the h1 (label from the slug), the sport-index link and the footer. Home: show loading under "Pick a season", and an error with Try again when the list fetch fails (do not swallow it). |
| D6 | `DailyCards.tsx`, `styles.css:7417` | Daily cards: tint by sport (the `.nba`/`.nfl` classes exist and nothing styles them), as the sport tiles do. React loading state says "Loading…", not the static "shows when the page loads" strings. |
| D7 | `styles.css:7471,7559-7561` | Sign-in panel: notes left-aligned inside `.tm-signin`; the input gets `--bg` so it is visible on the panel. |
| D8 | `styles.css:7571` vs `:7277-7282` | `.tm-drafter` loses to `.tm-page .hist-champ` / `.hist-bk-match` / `.hist-bk-byes li`. Raise its specificity (`.tm-page .tm-drafter`). **browser** to confirm. |
| D9 | `SeasonPage.tsx:149-153` | "The draft" block stacks about eight muted sentences. Keep `pointsNote` and the going-in definition there; move the rookie and missed-season sentences to "About this season's data"; `howRanksWork` no longer repeats the going-in sentence. (FR-006b still holds: all three statements stay on the page, outside the spoiler fold.) |
| D10 | `seasonPageHtml.ts:310-312` | "Your Sleeper username", the placeholder and "Continue" are inlined: move to the copy module so the guards cover them. Mover rows: remove the empty rank gutter or align the indents. |

## E. Copy

Guest rule, one wording everywhere (page state line, static line, limit panel, server messages):

- `stateLineGuest` and `stateLineStatic`: `Free, no sign-up. The game of the day is free every day. Guests also get one season draft of their choice; an abandoned one counts.`
- `notCountedNote` (and `stateLineSignedIn` = the same constant): `Seasons you pick don't count toward achievements, your daily streak, or your best and average draft score.`
- `limitBody`: `Guests get one season of their choice, and an abandoned one counts. Sign in with your Sleeper username to draft any season.` `limitNote`: `No password. Games from this browser move into your history when you sign in.`
- Server (`backend/.../HistoricalGameService.java:148-149,162-163`): `You've used your free season draft. Sign in with your Sleeper username to draft any season, or play today's game.` and `Sign in to draft a random season. You can play the game of the day, or one season you choose.` (update the ITs that assert the text, if any).
- `keepResultBody`: `Sign in with your Sleeper username to save this result beyond this browser.` `keepBody`: `Sign in with your Sleeper username to keep your history, play any season, and earn achievements from the game of the day.`

Other strings:

| Where | Change |
|---|---|
| `tmLandingCopy.ts:29-30` `analyticsLine` | Re-export `redraftCopy`'s, so the mounted sign-in form also says the random-id sentence. New text for both: `We count page visits and a few actions, like signing in. We never record your username. No cookies. We keep a random id in this browser so your games stay yours.` (the id is in localStorage, `guest.ts`; "No cookies" is true). |
| `tmLandingCopy.ts:24-25` `signInNote` | `Enter your Sleeper username or profile link to keep your games. No password. This site never asks Sleeper for anything private.` (builder confirms the form accepts a profile link; if not, drop "or profile link"). Also the "--" in `SignInForm.tsx:105`. |
| `backend/.../NbaHistoricalSport.java:155-157` `pointsNote` | `Each week only a player's best single game counts, and the weeks add up. That is not the season total you would see on a fantasy site.` (drop "lock-in"). |
| `backend/.../NflHistoricalSport.java:163-164` `pointsNote` | end with "over the regular season." unless weeks really are missing (builder checks, says which). |
| `redraftCopy.ts:68` `topSub`, `HistoricalResults.tsx:275` | "this game's scoring" reads as one NBA game. Say "Redraft's scoring". |
| `goingInNote` | `"Going in" is our own pre-season ranking ({rankingLabel}), not where people really drafted players that year.` |
| `rookieNote` | Builder checks how football rookies are ranked (`NflHistoricalSport`, rookie config) and writes a true sentence per sport, or shows it only where true. |
| `unrankedLine` | "no stats the season before" is wrong for ranked rookies. Say `with no pre-season rank`, without a reason. |
| `bustsSub` | append: `A low weeks-played count can mean an injury.` |
| `dreamSub` | `Built by taking the top scorer each lineup spot allows, ignoring draft order. It may not be the single best possible roster, and nobody could have drafted all of it.` |
| `dreamTotal` | `Team total: {pts} points, counting the best lineup each week.` (drop the second sentence). |
| `redraftVoice.ts:83,128` `hindsightRosterNote` (both voices) | drop "scores the most" and "full-season total": `The best roster we found among players still on the board at your picks. Points are each player's season total as scored here.` (shared voice: "at this team's picks"). |
| `redraftVoice.ts:76,121` `bustNone` | "at or above where you took him" / "where this team took him". |
| `historicalShare.ts` | `Draft score 87%`; sport word `NBA` / `NFL`; chosen line `Chosen season, not the game of the day`. Update its tests. |
| `siteCopy.ts:22` | not-found body = `redraftCopy.notFoundBody` on the Redraft site. `footerLine` / `footNote` single source. |
| `historicalCopy.ts:328-329` `howToPlaySteps` | same step-3 text as `redraftCopy` (`Your finish, the picks you nailed and the ones you missed.`), one source. |
| error strings | `…didn't load. Try again in a minute.` where there is no Try again button. `No NBA seasons are available yet.` `sharedNoneBody`: `This game isn't finished, so there is no result yet.` |

Search head tags (lengths matter; the naming test's rules still apply: "Redraft" + a year or "past"):

- season title: `{label} Fantasy {Basketball|Football} Redraft | Ball Knowers`
- season description: **no names** (the page folds the outcome as a spoiler; the snippet should not give it away): `Redraft the {label} {NBA|NFL} season using only what players did the year before, then see who finished on top. Free, no sign-up for your first season.` Descriptions stay unique because the label is in each. *(Changes contracts/pages.md, which said the description names the top finisher and biggest steal. Session's call on the copy reviewer's advice; Allan can overrule.)*
- sport index title: `{NBA|NFL} Redraft: Past Seasons {first} to {last} | Ball Knowers`; description: `Redraft any of {n} past {NBA|NFL} seasons, {first} to {last}, using only what players did the year before. Free, no sign-up for your first season.` (singular when n is 1).
- daily title: `Daily Redraft: A Past NBA or NFL Season | Ball Knowers`; description: `Every day, everyone gets the same past NBA season and the same past NFL season to redraft. New games at midnight Eastern. Free every day, no sign-up.`

## F. For the live pass, not the fix builder (**browser**)

Heading inside `<summary>` as read by a screen reader; where focus lands after sign-in, `/#sign-in`, route change; measured touch heights and contrast (`.tm-pick.behind` glyph is about 4.7:1 by estimate); 320px and 200% zoom reflow; a fold opened before React mounts; guest markup flashing for a signed-in visitor on `/`; the wordmark's font and width on a phone; no-script reading of a season page; security headers on the 301 responses (serve sends none there; measured).

## Fix pass (2026-11-17)

Done in the working tree, nothing committed. `fixed` = changed and covered by a test or a build check named in the pass report; "browser" = needs the live pass (section F) to confirm what a jsdom test cannot see.

| Id | Status | Note |
|---|---|---|
| A1 | fixed | `atLimit = !user && (limited \|\| usedAllowance) && !resume`; `limited` resets when a user appears; the claim line (always-mounted `role="status"`) sits where the state line was and takes focus. Test: `redraftPages.app.test.tsx`, "signing in from the limit panel lifts the limit at once". |
| A2 | fixed | `.tm-page .hist-callout-steal.shared` uses `--raised` and `--text`; `HistoricalResults` adds `shared` in the shared voice. CSS guard test. Look in the browser. |
| B1 | fixed | `/^\d{4}(-\d{2})?$/` on slug and label in both validators; `isSafePageFile` plus a relative-path check in `closeBundle`; the canonical in `pageHeadTags` and the sitemap `<loc>` are escaped. Tests for `../x`, `a"b`, `a&b`, the sitemap, and the file check. |
| B2 | fixed | Comment at `HistoricalGameService.reduced`, no behaviour change. |
| B3 | not fixed (not mine) | Doc note for DEPLOY.md (T045). |
| C1 | fixed | "T5" stays visible, "tied for 5th" is `.hist-sr-only` text inside it, in the page and in the static body. |
| C2 | fixed | The limit heading is an `h2`, `outline: none` is gone, `:focus` draws a 2px `--text` outline. Browser: how it reads. |
| C3 | fixed | `aria-disabled` plus a guard in the handler on the season-page button, the shared-result button and the daily Play button. The busy/limited look is a CSS rule. |
| C4 | fixed | `/#sign-in` focuses the panel's `h2` with `preventScroll`; the effect depends on `location.key`. Test in `HistoricalDraft.test.tsx`. Browser: scroll and focus together. |
| C5 | fixed | `ClaimLine` (always mounted, `role="status"`, `tabIndex=-1`) on the home, the season page and the owner's results page; focus moves to it when a guest signs in on that page. The static pages carry an empty copy marked `data-dynamic="claim"`. |
| C6 | fixed | `TimeMachineShell` scrolls `main` and the document to the top and focuses the page's h1 (or `main`) on a pathname change with no hash; not on the first render. Two tests. |
| C7 | fixed | `role="alert"` on the errors in `SeasonPage`, `SharedResult`, `DailyPage`, `DailyCards`, `SportIndex`, the home and `SignInForm`; `role="status"` on loading lines and on the rollover sentence (its own node; the countdown stays silent); `SignInForm` input has `aria-invalid` and `aria-describedby`; "Starting" and "Checking" have hidden status nodes. The "Checking" status text is "Checking that username with Sleeper." so it does not duplicate the button. |
| C8 | fixed | The shared result's kicker is the `h1` (look unchanged). |
| C9 | fixed | The prev/next group is "Other NBA seasons" / "Other NFL seasons"; the pill inside still says "All NBA seasons". |
| C10 | fixed | Year pills carry hidden " NBA Redraft" / " NFL Redraft"; yesterday rows read "How {label} played out". |
| C11 | fixed | A `<noscript>` line "Drafting needs JavaScript turned on." in the static season page, the daily cards and the static home's sign-in panel (named `noscript` in the parity test's exclusions). The static daily cards (on the home and on /daily) carry it too. |
| C12 | fixed | Focus rings on `.tm-foot a`, `.tm-sport-tile`, `.tm-newest`, `.tm-fold-link`; 44px min height for `.tm-btn`, `.tm-sport`, `.topbar-signin`, `.tm-pill-link` at 640px and below; `.tm-foot a` padding `11px 4px`; the nested fold's summary has padding. Browser: measured heights. |
| C13 | fixed | The footer nav is inside `<footer>`; `aria-current` is gone from the drafter's standings row (the row already says "You" or "· this team"); `scope="col"` on every result-table header. |
| C14 | fixed | `.tm-kicker` margin is `16px 0 8px`. |
| D1 | fixed | The limit panel shows only with no resumable game for this season; one Resume control (the top); the guest state line is hidden while the panel is up; "Play today's game" is `.tm-btn.quiet`; the panel has no Resume link. |
| D2 | fixed | "{n} pts" with a muted unit on finisher, mover, position and dream-roster rows, page and static body. |
| D3 | fixed | "Going in 4th of {rankedPlayers} ranked" on the finisher rows, "4th of {n} ranked" on the movers' going-in number. |
| D4 | fixed | The lead-row rules are scoped to `ol.tm-rank-list.tm-top`; the first bust and first steal get the same bigger name. |
| D5 | fixed | The season loading and error states keep the h1 (label from the slug), a link to the sport index and the footer. The home shows a loading line and an error with Try again under "Pick a season" (see CR-09). |
| D6 | fixed | `.tm-daily-card.nba/.nfl` tinted before `.done`; the cards get the sport class; the mounted loading state says "Loading…". |
| D7 | fixed | Notes left-aligned in `.tm-signin`; the input gets `--bg`. |
| D8 | fixed (browser to confirm) | `.tm-page .tm-drafter`, after the three rules it must beat; CSS guard test checks the order. |
| D9 | fixed | "The draft" holds the scoring note, the points note and the going-in sentence; the rookie and missed-season sentences are in "About this season's data"; `howRanksWork` no longer repeats the going-in sentence. All three statements are still outside the spoiler fold (test). |
| D10 | fixed | "Your Sleeper username", the placeholder and "Continue" are in `redraftCopy` and read by both the static home and `SignInForm`; mover rows have no empty rank gutter (a `.tm-mover-head` grid). |
| E | fixed | Every line of the section, verbatim, with these notes. `rookieNote` is per sport and was checked against `HistoricalBoardBuilder.buildRanked`: basketball places any first-season player whose NBA pick is known; football only rookies drafted in the first `rookie-max-round` rounds (2, hand-set), so the football sentence names no round number. The NBA/NFL `pointsNote` follow the text: NFL ends "over the regular season" because the import keeps regular-season rows only, and a season missing games says so in its own data notes. The sign-in form does accept a profile link (`SignInForm.usernameFrom`), so "or profile link" stays. `analyticsLine` in `tmLandingCopy` now re-exports the Redraft one. "Error strings ... Try again in a minute": every Redraft error with no Try again button was already covered by the new Try again buttons, so none needed the longer wording. The season description no longer names players (this overrules `contracts/pages.md`, as the session decided). A season index with one season reads "Redraft the only past NBA season, 2016-17, using ...". |
| CR-01 | not fixed (not mine) | Needs the og-image render, which needs a dependency install the owner has to approve. |
| CR-02 | accepted (note added) | Comment on `HistoricalGameService.claim` stating the property and pointing at `code-review.md`. |
| CR-03 | fixed (independent part) | A reduced public row is `{pickNo, mark}`. `api.ts`, `reduced(...)`, `RedraftPublicMvcIT`, `HistoricalResults` (square, label, detail line), `historicalShare.markOf` and the share text change together. The daily still names its season: that part is the owner's decision. |
| CR-04 | fixed | `tmDaily`, `tmSportIndex`, `tmSeason` in `site.ts` answer null off the Redraft site; every component that linked to those routes uses them and renders no link on null; `HistoricalRoom` asks the public route only on the Redraft site (a stranger at `/history/:id` gets not-found again); `usePageTitle` does nothing off the Redraft site. Tests: `site.test.ts`, `App.combined.redraft.test.tsx`. |
| CR-05 | fixed | Same change as A1. |
| CR-06 | fixed | `peekGuestId` never caches "none"; the id is re-read before generating and again after the write; a `storage` event updates the tab. Three tests in `guest.test.ts`. |
| CR-07 | fixed | `buildEnd(error)` marks the build failed; `closeBundle` returns at once for a failed build or a Redraft build that never got its data. Checked by hand (see the pass report); there is no unit test, the plugin is not exported. |
| CR-08 | fixed | `REDRAFT_ALLOW_EMPTY_SPORT=1` (build time): one empty sport builds with a loud warning, no page, sitemap entry, tile, footer, 404 or shared-result link for it. Without it the error names the variable. Both empty always fails. Tests for both modes. |
| CR-09 | fixed | `useHomeLists` returns `{lists, status, retry}`; the home shows "The season list didn't load." with Try again, and a signed-in player keeps "Draft a random year" when the list failed. `HistoricalOldBackend.test.tsx` pins it again. |
| CR-10 | fixed | `/daily` asks for yesterday again when today's date changes; a reduced shared result asks the public route again once `reducedUntil` passes (`useAt`). Two tests. |
| CR-11 | fixed | The claim log line prints `Identity.shortHash(key)`; the nil UUID is not an identity; `guest.ts` says the id is a bearer credential and builds the fallback from `crypto.getRandomValues` (it refuses rather than use `Math.random`). Tests: `IdentityTest`, `guest.test.ts`. |
| CR-12 | fixed | Stale comment removed; slug check (B1); the award panel shows "stayed in this browser" for a 404 instead of an error; the concurrent-create IT is `sixParallelChosenSeasonCreatesFromOneGuestMakeExactlyOneGame`. |

## Fix pass 2 (2026-11-17)

Source: `verification.md`, "Defects found". V-01 (the og PNG) is not part of this pass.

| Item | Status | What changed |
|---|---|---|
| V-02 | fixed | `TimeMachineShell`'s navigation effect keeps focusing the h1 it finds, then watches `<main>` (a `MutationObserver`, at most 5 s, ended by any keydown or pointerdown) and, when the swap from the loading h1 to the loaded h1 leaves focus on `<body>`, focuses the new h1. If focus is anywhere else (the visitor moved it) it does nothing; the first page load never focuses. Tests in `TimeMachineShell.test.tsx`: loading to loaded h1 after a client-side move, and focus left alone after the visitor moved it. Not run in a real browser. |
| V-03 | fixed | The static daily cards (`/` and `/daily`) are made from the sports the build offers, so an empty sport gets no card. Head and h1 copy unchanged. Test in `redraftEmptySport.test.ts`. The mounted `/daily` line "No football game was dealt yesterday." is not changed (not in the ask). |
| V-04 | fixed (unobserved) | `styles.css` hides `.topbar-signin` and the home's `.tm-signin` panel under `html[data-bk-user]`; `main.tsx` takes the mark off in a layout effect of the commit that replaces the static markup, so a visitor who signs out later sees the app's own controls. `boot.js` is unchanged except its comment. Tests: `redraft.css.test.ts` (rule) and `boot.test.ts` (main.tsx removes the mark). The flash itself needs a throttled browser run. |
| V-05 | fixed | `.tm-foot a` and `.tm-fold-link` are `inline-flex`, centred, `min-height: 44px`. Test in `redraft.css.test.ts`. Not measured in a browser. |
| V-06 | fixed | `.tm-pick.behind` glyph colour `--down` (rgb 254,98,112) to `color-mix(in oklab, var(--down) 85%, var(--text))` (rgb 255,123,132) on the unchanged tint (rgb 69,40,57): 4.45:1 to 5.21:1, computed from the oklch tokens (the script reproduces the measured 4.45). The tint, `--down` and every model constant are unchanged. |
| V-07 | fixed (cause established from the code) | `aDraftingStateHoldsNothingFromTheDraftedSeason` searched the whole state JSON for the substrings 7777, 8888, 9999. The state carries player, pick and game ids from database sequences, which pass through those digit runs; the failure had nothing leaked. The test now walks the JSON and flags numbers equal to the drafted line (7777, 8888, 9999, age 99, 91 games) only in fields whose name is not an id or `pickNo`, and keeps the "ZZZ" text check. It pins the same leak with no false match on ids. The whole run of the class passed 15/15. |
| V-08a | fixed | In the reduced shared result the "comparisons below" sentence is left out; the unplayed-weeks fact stays. |
| V-08b | fixed | `sharedNoneHeading` "No finished game here", `sharedNoneBody` "There is no finished game at this address." |
| V-08c | fixed by dropping the team | `HistoricalPlayerCard.team` is the PRIOR season's team (its own javadoc says so; `HistoricalBoardBuilder` takes it from the line the card shows). On a page about the season itself it misleads, and "{team} the year before" on every row would be noise, so the season page rows (static and React) no longer print it. The API field is unchanged (the draft room still uses it for the prior-season card). Test in `seasonPageHtml.test.ts`. |
| V-08d | fixed | `rookieNote` no longer gives a reason for a missing rank ("no stats the season before" is gone from both sports' sentences; nothing else in the copy modules or `seasonPageHtml.ts` carried it). It says only that other first-year players (NBA) or later-round and undrafted rookies (NFL) have no pre-season rank. Why a veteran such as Wilt Chamberlain on 1970-71 is unranked was not investigated; the sentence makes no claim about it. |
| V-08e | fixed | `howRanksWork`: "Finished is a player's rank in season points among all {n} players on our board." |
| V-08f | fixed | The backend freezes one reason for both double stats ("double-doubles need ..."). `notCountedReason(reason, stat)` now says "triple-doubles need" for `td` (the stat is a required argument; the three callers pass it). Test in `historicalCopy.notCounted.test.ts`. The stored snapshots still hold the old words; the fix is at display. |
| V-08g | fixed | `useNoindex` keeps exactly one `meta[name=robots][content=noindex]` while any user is mounted (reference counted, replaces the delivered shell's one), removes it with the last. `HistoricalRoom` now uses it too, so a game page reached by a client-side link has it. Tests in `SharedResult.room.test.tsx`. |
| V-08h | fixed | `HistoricalDraft` renders "Pick a season" only when some sport index page exists on this site (not on the combined build). Test in `HistoricalDraft.test.tsx`. |
| V-08i | fixed | The season page sets its title from sport and slug at once (`seasonTitle(label ?? slug, sport)`). Test in `redraftPages.app.test.tsx`. |
| V-08j | fixed | A viewer with no user and no stored guest id goes straight to the public route; with either, the owner route is still first. `SharedResult.room.test.tsx` now stores a guest id by default and has the identity-less and with-identity cases. Not run in a browser. |

Run after this pass: web `tsc -b` clean, vitest 215 files / 3555 tests, all pass (baseline 3540; one unrelated `LeagueHome` test timed out once under load and passed on re-run and in the next full run). Builds: Redraft (fixture data), leagues and unset all succeed; `grep -ril "time machine" dist` after the Redraft build finds nothing. Backend full `./gradlew test` on `draftsim_074`: 3020 tests, 12 failed, 48 skipped; the 12 are the baseline names (AccessControlMvcIT 1, AdminDigestControllerIT 1, TargetControllerIT 8, LeagueRepositorySuccessorIT 2), and `HistoricalGameMvcIT` passes 15/15.
