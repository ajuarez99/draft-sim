# Code review: spec 050 (bug hunt, not style)

**Date**: 2026-10-26 · **Tree**: worktree `050-fantasy-landing-page`, uncommitted diff on `76e9740`.
**Scope**: `web/src/landingCopy.ts`, `landingHtml.ts`, both new test files, `SignIn.tsx`,
`SignIn.test.tsx`, `App.test.tsx`, `styles.css`, `index.html`, `vite.config.ts`, `public/{boot.js,robots.txt,sitemap.xml}`,
`scripts/og-image/*`, and the existing `dist/index.html` from T017's build.

**What I ran (MEASURED)**:
- `npx vitest run src/landingHtml.test.tsx src/landingCopy.test.ts src/pages/SignIn.test.tsx src/App.test.tsx`
  gave 4 files and 40 tests, all passing.
- Title length is 63 and description length is 151 (node).
- `THEME_COLOR #040913` is the correct sRGB conversion of `--bg: oklch(14% 0.025 255)`. I recomputed it with the OKLab matrices in node.
- I read the `dist/index.html` head from T017's build. It has one `<title>`, all the og and twitter tags with absolute URLs, the JSON-LD, then `boot.js`, then Vite's module script and stylesheet. Nothing is inserted twice.

I started no server. Everything else below was found by reading code, and each finding says so.

---

## Findings

### 1. HIGH: "Managers" (and, more mildly, "Draft room") promise a reach read that basketball can't have

`landingCopy.ts:81`: `Managers — 'Who reaches, who plays it safe, and which positions each manager loves.'`.
The `'managers'` destination gets `['nfl','nba']` from `featureSports` (`landingCopy.ts:110`), so the tile has no tag.

- **Evidence.** `ManagerTendencies.tsx:124-133`: without scoreable picks, `effectiveReachBias` is "the league mean with this manager's name on it -- for every basketball manager, permanently". So the page shows no reach number at all for NBA managers.
- **Background.** Memory "NBA mock drafts" says NBA reach bias can't be fitted (no `adp_at_time`), but positional tilt is.
- **Failure scenario.** A basketball fan, the product's real target per memory, reads "Who reaches" on an untagged tile, signs in, and finds no reach for anyone in their league.
- **Why HIGH.** This is the class AGENTS.md names first: copy that makes the tool look more measured than it is.
- **Same issue, smaller.** "Draft room" (`:77`, "based on how your league-mates really draft") and "Mock drafts" (`:79`, "your actual league-mates") are only partly true. NBA seats get positional tilt only. A manager with no draft history is a NEUTRAL seat, which is the league mean, not that person.

**Fix.**
- Reword Managers to what both sports get, e.g. "Which positions each manager loves and how they draft, read from their own draft history." If "reaches" stays, it must be marked football-only.
- Soften the draft lines, e.g. "based on your league-mates' past drafts" and "seated with your league-mates' draft habits where they have history".

### 2. MEDIUM: "Matchups & awards" describes another page's award

`landingCopy.ts:58`: `'This week’s head-to-heads, plus who left the most points on the bench.'`

- **Evidence.** `WeeklyReportService.java:330-339`: the weekly awards are GOT_AWAY_WITH_IT, DESERVED_BETTER, ONE_PLAYER_CARRY and SELF_INFLICTED_WOUND.
  - The nearest one, GOT_AWAY_WITH_IT (`:611-631`), is the *winner* with the lowest share of their optimal lineup. That is a ratio among winners, not the most bench points.
  - "Who left the most points on their bench" is word for word the **Bench points** page's subtitle (`RosterManagement.tsx:115`), which is a different tile on the same landing page.
- **Failure scenario.** A fan opens Matchups & awards looking for a bench-points leader and doesn't find one.
- **Fix.** Something like "This week's head-to-heads, top performers, and awards like who won with a bad lineup or lost despite a big score." The page subtitle (`WeeklyReport.tsx:113`) and the four award kinds back that wording. (INFERRED)

### 3. MEDIUM: "Rivalries" promises "this week's grudge match", which the page doesn't have

`landingCopy.ts:89`: `'Head-to-head records between every pair of managers, and this week’s grudge match.'`

- **Evidence.**
  - `Rivalries.tsx:71`'s subtitle is "Who has beaten whom, who you play when, and which matchups would make a good rivalry week."
  - Its sections are most played, closest, most one-sided, playoff meetings and a *suggested rivalry week* (`:27`, `:35-37`).
  - The suggestion picks pairings for a hypothetical rivalry week. Nothing is "this week's" matchup.
- **Fix.** "Head-to-head records between every pair of managers, and the pairings that would make a good rivalry week." (INFERRED)

### 4. MEDIUM: Nothing tests that a feature sits in its destination's rail group (FR-003)

`landingCopy.test.ts:10-16` checks only that each `destination` *exists*.

- **Gap.** The group a feature sits in is typed by hand in `groups[]` (`landingCopy.ts:52-92`). Nothing compares it with `LEAGUE_DESTINATIONS[*].group`.
- **Today.** Every feature matches. I checked all 21 by hand against `destinations.ts`. Managers is the documented exception (A7): the rail renders it under "Menu", not under Draft.
- **Failure scenario.** Someone later moves `stats` into "This week" on the landing page, or a destination's group changes in `destinations.ts`. The landing page then contradicts the rail and stays green. This is the "two implementations of one rule" class from the multi-sport landmines.
- **Fix.** Inside the existing loop, for every feature except `'managers'`, assert `LEAGUE_DESTINATIONS.find(d => d.key === f.destination)!.group === g.key`.
- **Optional completeness check.** Assert that every non-`home` key in `LEAGUE_DESTINATIONS` is referenced by at least one feature. A page added to the rail would then fail until the landing page mentions it or deliberately skips it. Today all 20 non-home keys are covered.

### 5. LOW: Overstated or format-dependent lines (FR-004 spirit)

- **Trends** (`:60`): "...before the waiver wire catches on". This is an unmeasured claim of an edge. The page's own subtitle is "Who is getting more or fewer minutes, and who is worth streaming." (`PlayerTrends.tsx:189`). Use that instead.
- **Schedule grid** (`:59`): "...so you know who has a heavy week".
  - The page counts games per **NBA team** (`ScheduleGrid.tsx:140`). "Every team's games" reads as fantasy teams.
  - Memory "NBA one game per week is correct" measured that Allan's own NBA league credits about one game per player per week, which "contradicts the schedule grid's '4 games ≈ 2×' premise". So "heavy week" implies a payoff that depends on league format.
  - Suggest: "Games each NBA team plays in every week of your league."
- **Standings** (`:88`): "back to the beginning". History walks Sleeper's `previous_league_id` chain (`SleeperClient.java:61-71`), so it reaches the league's first *Sleeper* season. A league that moved from ESPN or Yahoo doesn't go back to its beginning. Suggest "every season on Sleeper".
- **Playoff odds** (`:67`): the page refuses leagues with divisions or non-default seeding (`SeasonForecast.tsx:199`). The tile line doesn't need that caveat. Noted only so verification doesn't call it a bug if a test league shows no odds.

Checked and accurate, so no finding:
- Power rankings, Team strength, Luck (all-play record: `ExpectedWins.tsx:16-24`), Bench points, Awards, Weekly pot, Player stats.
- Draft grades → `board`: grades render on `CompletedDraftBoard.tsx:110-205` ("played" mode, complete drafts), which is the `board` destination for a finished draft, so the mapping is correct.
- Follow live (worded as a draft-day tool, as SC-008 asks), ADP & prices, Compare players, Player pages (the line understates it: free agents have pages too).
- Sport tags: all six NBA-only rows are tagged through `featureSports`.

### 6. LOW: SignIn test dropped the product-name assertion, and the comment explaining why is false

`SignIn.test.tsx:102-104` removed `getByText('Ball Knowers')`. The new comment says the name "appears in the page's feature copy".

- **Evidence.** It doesn't. SignIn renders `headline`, `subheadline`, `sports`, `platformsNote`, the groups and `footerLine`, and none of them contains "Ball Knowers". The only strings that do (`title`, `imageAlt`) aren't rendered by SignIn.
- **Effect.** The old assertion would still pass, but FR-002 ("first screen MUST contain the product name") has lost its only test. SC-007 said to update the headline assertions "and nothing else".
- **Fix.** Restore `expect(screen.getByText('Ball Knowers')).toBeInTheDocument()` (or assert it on `.landing-kicker`) and delete the false comment.
- INFERRED from reading. Not run as a separate test, but every rendered string was checked by grep.

### 7. LOW: The FR-018 equality test skips the strings that are still typed twice

- **What's duplicated.** `landingHtml.ts:97-107` hard-codes the kicker, the label text, the placeholder, the button text and the privacy note. `SignIn.tsx:62-96` hard-codes the same strings.
- **What's covered.** The test (`landingHtml.test.tsx:62`) compares `.signin-note`, so the privacy note is guarded.
- **What isn't.** `.landing-kicker`, `.signin-label` (its text node), the input `placeholder` and the button text are not compared. If one side is edited, the delivered page and the app show different words, against FR-013/FR-018.
- **Fix.** Either add `.landing-kicker` and `.signin-label` to the selector list, plus a direct `placeholder` comparison, or move those strings into `landingCopy.ts` like the rest. The second option is better because it also fixes the duplication.
- **Related drift.** The headline is also hand-typed into `scripts/og-image/og-image.svg` ("Your fantasy league, / all season long."). It's a static asset, so that's acceptable, but add a one-line comment in `landingCopy.ts` next to `headline` saying the PNG must be re-rendered when it changes.

### 8. LOW: The `boot.js` storage key is a second literal with no test tying it to `user.ts`

- `public/boot.js:7` reads `'bk.user.v1'`, and `user.ts:8` declares `STORAGE_KEY = 'bk.user.v1'`. The logic matches `readStored` exactly:
  - null → `JSON.parse(null)` gives null, so not signed in
  - `""` → throws, so not signed in
  - a non-string `sleeperUserId` → not signed in
- **Failure scenario.** `user.ts`'s own comment plans for a versioned key bump. If it becomes `bk.user.v2` and `boot.js` isn't updated, signed-in visitors get the landing page flash back. The opposite case is worse: a stale v1 entry still hides the prerender for someone the app now treats as signed out, until the bundle runs. Either way, nothing fails.
- **Fix.** Export `STORAGE_KEY` from `user.ts`, and add a test that reads `public/boot.js` with `?raw` and asserts it contains `` `'${STORAGE_KEY}'` ``.

### 9. LOW: Every signed-in tab now carries the 63-character SEO title

Nothing in `web/src` sets `document.title` (I grepped it). The static `<title>` is therefore the tab title, bookmark name and history entry on every page of the signed-in app. It used to be "Ball Knowers"; it is now "Ball Knowers: Fantasy Football & Basketball for Sleeper Leagues".
- **Fix (optional).** Set `document.title = 'Ball Knowers'` once a user exists (App or AppShell). Bots don't run scripts, so the delivered title is unaffected.
- This is a UX regression, not a correctness bug. (INFERRED)

### 10. LOW: Scroll position resets when React replaces the prerender

The prerender's scroll container (`.landing-prerender > .content.landing`) is thrown away when `createRoot().render()` mounts. A visitor who scrolled during a slow first load jumps back to the top. This can't be avoided without hydration and is acceptable; it's recorded so the live check doesn't mistake it for a bug. (INFERRED)

---

## Checked, no defect found

- **transformIndexHtml.**
  - In dev, `order: 'pre'` runs once per HTML request on the raw file. In build it runs before Vite's HTML pass, which then leaves `/boot.js` alone (it's a public file) and appends its own module script and CSS link after it, as `dist/index.html` shows. Nothing is inserted twice.
  - `transform` is not idempotent: a second run throws because the root is no longer empty. No code path runs it twice.
  - Vitest loads `vite.config.ts` and its import chain cleanly. The hook never fires in tests, and the 40 tests pass.
- **Escaping.** `esc()` covers `& < > "`, and every attribute is double-quoted, so not escaping `'` is safe. JSON-LD escapes `<` as `<`. `data-group` interpolates unescaped literals only.
- **CSS.**
  - The removed classes (`.signin-welcome`, `.signin-kicker`, `.signin-pitch`) are used nowhere else. `.signin-content` never had a rule.
  - `.signin-hero` and `.signin-controls` (`styles.css:2255-2256`) are dead CSS that predates this change and is untouched by it.
  - `.landing` gets `overflow-y: auto` inside the `100vh` `.app` with `min-height: 0` from `.content`, so its top is not clipped.
  - `.landing-prerender > .content` padding equals `.app > .content` (16px 20px). The `≥1800px` `.app` padding override applies to both trees equally.
  - Tiles use `minmax(260px, 1fr)` and drop to one column at ≤560px, so there is no fixed width over 260 and no long unbreakable strings. Horizontal overflow at 390px is not expected. The live check will measure it.
  - `.landing` is the only use of that class name.
- **Sign-out in the same tab.** React's tree never has `.landing-prerender` (tested at `SignIn.test.tsx:144-147`), so the sticky `html[data-bk-user]` can't hide React's landing page.
- **`robots.txt` and `sitemap.xml`.**
  - robots.txt is valid and has no Disallow lines (A3).
  - sitemap.xml is a valid `urlset` with the 0.9 namespace and a single absolute `<loc>`.
- **Preview tags.** og and twitter image URLs are absolute, width and height are declared, and the card is `summary_large_image`.
- **Preview image.** All of the og-image's text sits at x≈358–842, y≈150–500, so it survives both a square crop and a 16:9 crop.
- **Changed tests.**
  - The `App.test.tsx` assertions use an exact `name: headline` with `level: 1`, so they're not vacuous.
  - The new `SignIn` tests check order, tags and the ESPN restriction against real rendered DOM.

---

## Fixes applied (2026-10-26)

- **F1 (HIGH)**: Managers now reads "Each manager's draft habits: the positions they load up on and, in football, who reaches." Draft room and Mock drafts now say "each league-mate's draft history" and "seats modelled on your league-mates' past drafts, not generic bots". The review's alternative wording was not used verbatim.
- **F2**: Matchups & awards now names the weekly awards as the page shows them (`WeeklyReport.tsx:517-520`): "one-player carry" and "self-inflicted wound". The other two are "Got away with it" and "Deserved better".
- **F3**: Rivalries now says "suggested pairings for a rivalry week".
- **F4**: `landingCopy.test.ts` has a new test that every non-`managers` feature's destination group equals its landing group key. The optional every-destination-referenced check was not added.
- **F5**: Trends, Schedule grid (now "every NBA team", no "heavy week") and Standings ("on Sleeper") reworded.
- **F6**: `SignIn.test.tsx` asserts `getByText('Ball Knowers')` again; the false comment is replaced.
- **F8**: `STORAGE_KEY` is exported from `user.ts`. New `src/boot.test.ts` reads `public/boot.js` via `?raw` and asserts it holds that key literal and `sleeperUserId`.
- **F9**: `App.tsx` sets `document.title` to 'Ball Knowers' when signed in and to the landing title when signed out. `App.test.tsx` asserts both states.
- **F7, accepted**: the four strings are UI chrome that FR-018 does not cover, and the og SVG is a static image. The SVG's text was checked and none of it is false, so it is unchanged.
- **F10, accepted**: the scroll reset on mount is expected.
