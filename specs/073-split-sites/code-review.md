# Bug-hunting code review: 073 Split the website into a Time Machine site and a league site

**Date**: 2026-11-15 · **Reviewer**: bug-hunting code review pass (not a style review)

**Read**: `AGENTS.md`, spec.md, plan.md (amended section and Allan's decisions), contracts/site-routes.md, tasks.md, build-notes.md, the full `git diff` and every untracked file under `web/src`, plus the code around the diff: `AppShell.tsx`, `user.ts`, `statCalcSaved.ts`, `analytics.ts`, `DraftBoard.tsx`, `HistoricalRoom.tsx`, `HistoricalDraft.tsx`, `historicalCopy.ts`, `index.html`, `public/boot.js`, `serve.json`, and the backend strings the Time Machine prints (`NbaHistoricalSport`, `NflHistoricalSport`, `EraScoring`).

**What I ran** (nothing in the project was changed except this file):

- `npx tsc -b` in `web/`: exit 0, no output.
- `npx vitest run` in `web/`: **194 files passed, 3158 tests passed, 0 failed, 0 skipped**. Matches the builder's numbers.
- A throwaway `web/src/zzReview073.test.tsx` (12 cases, real `App`, real `CrossSiteRedirect`, real `siteCarry` and `statCalcSaved`; only `goTo` and four pages stubbed). It produced every VERIFIED result below. **Deleted afterwards**; `git status` shows no trace.
- Not run: the backend suite (no backend file changed), `npm run build` in the three modes (the builder's table is taken as reported), any browser or dev server.

**VERIFIED** = reproduced by running something. **REASONED** = from reading the code only.

## Summary

| ID | Sev | Area | One line |
|---|---|---|---|
| CR-01 | MEDIUM | siteCarry | With 20 or more saved formulas nothing is carried at all; the cap holds 19 ordinary ones, not 50 |
| CR-02 | MEDIUM | site.ts / CrossSiteRedirect | Origin variables are not validated; a scheme-less or differently-cased value defeats the same-origin guard and can loop |
| CR-03 | LOW | siteCarry | Formulas deleted on the league site come back on the next forward |
| CR-04 | LOW | wording | "not tracked by the league that season" is reachable in the Time Machine room and results; the builder's two flagged items are both not real as described |
| CR-05 | LOW | tests | The wording test renders an empty home and no room or results, so it cannot see CR-04 |
| CR-06 | LOW | tests | No test signs in through the Time Machine form; removing `onSignedIn={markSignedInHere}` leaves the suite green |
| CR-07 | LOW | tests | The route-coverage test only sees `<Route>`s between the markers; one added outside them is unchecked |
| CR-08 | LOW | analytics | A visitor forwarded between the sites loses their outside referrer on the first counted view |
| CR-09 | LOW | docs | DEPLOY.md and the plan overstate what the carry does |
| CR-10 | LOW | LeaguesMovedNotice | Opening the notice link in a new tab does not dismiss it; dev server links to production |

No CRITICAL or HIGH finding. Combined mode (what merging ships) held up under every check I ran.

## Findings

### CR-01 (MEDIUM, VERIFIED) - 20 or more saved formulas are silently not carried

**Where**: `web/src/siteCarry.ts:13` (`CARRY_MAX_CHARS = 6000`), `:41` (`if (data.length > CARRY_MAX_CHARS) return url`).

**What happens**: the plan says 50 formulas keep the fragment "in the low kilobytes". Measured with formulas made by the real `saveFormula` (names like "My formula number 7", expressions like `pts + 1.2*reb + 1.5*ast + 3*stl + 3*blk - tov`): 10 formulas encode to about 3,170 characters, roughly 320 each. The largest count that is attached is **19**. At 20, 30 and 50 `withCarry` returns the URL unchanged: all-or-nothing, with no message. After the flip the main address has no stat calculator, so those formulas are unreachable to the person who saved them. FR-020 says they MUST arrive.

**Reproduce**: save 20 formulas, call `withCarry('https://leagues.ballknowers.co/', localStorage)`; the result has no `bk-carry`.

**Fix**: raise the cap to cover `SAVED_MAX` (50 formulas is about 16,000 characters; a URL fragment of that size is fine in current browsers, and it never reaches a server), and when it is still over, carry the newest formulas that fit rather than none. `acceptCarry`'s own length check at `:71` must move with it.

**Regression test**: 50 formulas saved through `saveFormula` round-trip through `withCarry` then `acceptCarry`.

### CR-02 (MEDIUM, guard miss VERIFIED, loop REASONED) - origin variables are accepted unvalidated

**Where**: `web/src/site.ts:25-28` (`origin()` only trims and strips trailing slashes), `web/src/components/CrossSiteRedirect.tsx:40` (`targetOrigin === window.location.origin`, a string compare), `web/src/landingHtml.ts` (`<link rel="canonical" href="${origin}/" />`, not escaped).

**What happens**: the plan makes a `VITE_SITE` typo fail the build, but `VITE_TM_ORIGIN` / `VITE_LEAGUES_ORIGIN` take anything.

- `VITE_LEAGUES_ORIGIN=leagues.ballknowers.co` (no scheme, an easy slip in a Railway variable): `location.replace('leagues.ballknowers.co/leagues/9')` is a relative URL, so the browser stays on the main address at `/leagues/9/leagues.ballknowers.co/leagues/9`, which matches `/leagues/*` again and redirects again with a longer path each time. Every old league link loops. Not run in a browser.
- A value that differs from the real origin only in case or an explicit default port: with the page on `http://localhost:3000` and the variable `http://LOCALHOST:3000`, the guard did not fire and `goTo('http://LOCALHOST:3000/leagues/9')` was called (run). On a misconfigured build that is the self-redirect the guard exists to stop.
- The same raw value is written into the canonical tag, robots.txt and sitemap.xml.

**Fix**: in `makeConfig`, parse each origin with `new URL(v)`, require `http:` or `https:`, store `url.origin`, and throw with the variable's name otherwise (same message style as `parseSite`). That normalises case, port and stray paths, so the string compare in `CrossSiteRedirect` becomes sound and the HTML gets a clean value.

**Regression test**: `makeConfig({ VITE_LEAGUES_ORIGIN: 'leagues.ballknowers.co' })` throws; `'HTTPS://Leagues.BallKnowers.co/'` yields `https://leagues.ballknowers.co`.

Smaller, same place: when the guard does fire, `NotFound` is rendered outside the `.app` wrapper and the shell (`CrossSiteRedirect.tsx:48`), so it is laid out differently from every other not-found. Cosmetic.

### CR-03 (LOW, VERIFIED) - deleted formulas return on the next forward

**Where**: `web/src/siteCarry.ts:74-80`: "has none of its own" is `parseStore(...).length === 0`.

**What happens**: a league-mate is forwarded once (two formulas arrive), deletes both on the league site (the store is then `{"v":1,"formulas":[]}`), and later opens another old main-address bookmark. The second forward writes both back. Run: count after the second forward was 2.

**Fix**: write only when `storage.getItem(SAVED_KEY) == null` (the key was never written on this site). Keep the garbage-reads-as-empty branch only for a value that fails to parse.

**Regression test**: destination store `{"v":1,"formulas":[]}` plus a valid carry leaves the store unchanged.

### CR-04 (LOW, READ) - league wording that is reachable on the Time Machine site, and the two flagged items

The builder's two open items, settled by reading:

- **(a) `settingsSource`**: reachable in two places, the home's "How this works" (`pages/HistoricalDraft.tsx:198`) and the room and results disclosure (`pages/HistoricalRoom.tsx:102`). But the text the builder quoted ("Settings: the Legacy Ball Knowers league.") is not in the code. The real strings are `"App standard 12-team NBA roster · Legacy Ball Knowers scoring"` (`NbaHistoricalSport.java:32`) and `"Hand-set 12-team, N-round roster: ... · full PPR"` (`NflHistoricalSport.java:149-158`). Neither contains league, mock or manager. The NBA one does name Allan's league by its name, which a stranger on the Time Machine site cannot place; a copy decision, not a rule break.
- **(b) `DraftBoard.tsx` strings**: **not reachable**. "Sleeper hasn't mapped a manager to it yet" and "Make it your seat" need a slot with no seat and an `onClaim` prop; `HistoricalRoom.tsx:268-283` passes a seat for every slot (`historicalSeats`) and no `onClaim`. "drafts like the league average" sits behind `!hideProvenanceDots`, and the room passes `hideProvenanceDots`. The `seat.manager` values printed are "You" and "Team N".

What is reachable and does break the wording rule:

- `EraScoring.java:64`: `"not tracked by the league that season"`, printed under "Not counted" in the room and results disclosure (`HistoricalRoom.tsx:82-90`) for NBA seasons from before a stat was recorded (steals and blocks before 1973-74, for example). It means the NBA. The value is stored per season at import and frozen into each game's snapshot, so a backend copy change alone does not reach existing rows.
- `HistoricalBoardBuilder.java:228`: "a position the league has no dedicated slot for cannot be ranked over replacement". An exception message; it would reach the screen only if starting a game fails that way. Unlikely.

Checked and clean: every `Historical*.tsx`, `HowThisWorks`, `Skeleton`, `RoomControls`, `historicalCopy.ts`, `historicalShare.ts`, `historicalDaily.ts`, `AccountMenu`, `TimeMachineShell`, the achievement and badge strings under `backend/.../historical/`, and both history controllers. No remaining `/history` link literal in any Time Machine file.

**Fix**: smallest is on the web side, where the reason is printed (`HistoricalRoom.tsx:87`): replace "by the league" with "by the NBA" for display, and change `EraScoring.java:64` for future imports. Decide separately whether "Legacy Ball Knowers scoring" should read differently on the public game.

### CR-05 (LOW, READ) - the wording test cannot see the populated screens

**Where**: `web/src/tmSite.wording.test.tsx:18-24`: `listHistoricalGames`, `historicalSeasons` and `getHistoricalDaily` return `[]`, `getHistoricalProfile` returns `null`.

**What happens**: the "home" case renders the hero and an empty list. The game-of-the-day cards, the games list, the profile card and the `settingsSource` line never render, and the room and results are not rendered at all. The test would pass with league wording in any of them; CR-04's string is in exactly that blind spot.

**Fix**: give the home case one season, one daily, one in-progress and one complete game and a profile; add a room case and a results case built from the fixtures in `HistoricalRoom.test.tsx`, with an `excludedStats` entry and a `settingsSource`, and run `expectNoLeagueWords` on each.

### CR-06 (LOW, READ) - the "never shown to someone who signs in here" half of FR-019 is untested

**Where**: `web/src/pages/TimeMachineSignIn.tsx:33`, `web/src/components/SignInForm.tsx:54-55`. The only tests that involve the marker set it by hand (`LeaguesMovedNotice.test.tsx:39`, `tmSite.wording.test.tsx:62`).

**What happens**: delete `onSignedIn={markSignedInHere}`, or move the call after `setUser`, and all 3158 tests still pass; every new Time Machine player would then be told their leagues moved.

The ordering itself reads correct: `onSignedIn` runs only after Sleeper recognised the name, `markSignedInHere` and `setUser` both swallow storage errors, and nothing between them can fail, so I found no path where the marker is written and sign-in then fails, or the reverse.

**Fix**: one test in `App.site.test.tsx` without the `HistoricalDraft` stub: `timemachine`, signed out, type a name, submit with `getSleeperUser` resolved, assert the marker key is set and the notice is absent; and a second with a user pre-seeded in storage and no marker, asserting the notice is present.

### CR-07 (LOW, READ) - the route-coverage test is blind outside the markers

**Where**: `web/src/analytics.test.ts` `region()` and the three `it.each` cases.

**What happens**: a missing or reordered marker throws, which is loud and good. But the old test counted every `<Route` in `App.tsx`; the new one counts only those between `@routes` markers. A route added in a new helper outside a region is never compared with a table and would be reported as `/(not-found)` without a failing test.

**Fix**: add one assertion that the number of `<Route\b` matches in the whole file equals the sum over the four regions.

### CR-08 (LOW, REASONED) - a forwarded visitor's outside referrer is dropped

**Where**: `web/src/analytics.ts` `outsideReferrer()`.

**What happens**: after the flip, someone who taps an old league link in a group chat lands on the main address, is forwarded with `location.replace`, and the league site's first page view has `document.referrer` = the main address, which `outsideReferrer()` treats as inside. The redirect leg sends no view by design, so the visit is counted with no source. Same for league site to Time Machine. Whether that matters is Allan's call; it is a change from today.

**Fix, if wanted**: none cheap. Passing the original referrer in the fragment is possible but adds another carried value. Otherwise say so in DEPLOY.md's analytics paragraph.

### CR-09 (LOW, READ) - docs say more than the code does

- `DEPLOY.md`, "What carries across": "their old ones ... are carried the first time a forward happens". False when the league site already has even one formula of its own (by design) and, until CR-01 is fixed, false for anyone with 20 or more.
- `plan.md`, bounds paragraph: "the fragment stays in the low kilobytes" for 50 formulas. Measured about 16,000 characters.

Everything else I checked in DEPLOY.md agrees with the code: the three modes, trimming, the build failure on a typo, the rollout order (league service and CORS before the flip; the league site forwarding to `/history`, which both states understand), rollback, the opt-out not being carried. `Dockerfile` ARG/ENV pairs give `""` when unset, which `parseSite` and `origin()` both treat as unset. The three `launch.json` entries use the `value&& ` form correctly; `draft-sim-api-split` shares port 8080 with `draft-sim-api`, so only one of them can run.

### CR-10 (LOW, READ) - notice link details

**Where**: `web/src/components/LeaguesMovedNotice.tsx:35`.

- Middle-click or Ctrl-click on the link opens the league site in a new tab without firing `onClick`, so the notice is not dismissed and shows again. Add `onAuxClick={dismissMovedNotice}` if "followed" should include that.
- The notice has no dev guard: a local Time Machine dev server with `VITE_LEAGUES_ORIGIN` unset links to production, unlike `CrossSiteRedirect`. Harmless.

## Checked and found sound

Run, in the throwaway test:

- **Combined mode, nested `<Routes>`**: the `header.topbar` DOM node is the same object across `/` → `/managers` → `/history` → `/` (with `state: { openMock: true }`, which reached the page) → `/history/5` → `/history/6`. AppShell does not remount. `/managers/`, `/history/`, `/HISTORY/5` match their pages; `/historyX`, `/history/5/x`, `/game` are not-found. `/game/5?x=1#y` lands on `/history/5?x=1#y` and sends exactly one page view, for `/history/5`.
- **Time Machine page views**: front door at `/game/7`, sign in, sign out gives exactly `(/game/7, out)`, `(/game/7, in)`, `(/, out)`. `/history` signed in sends one view, for `/`. Redirect paths send none.
- **Time Machine path matching**: `/managers`, `/leagues`, `/admin`, `/mock` (no trailing segment) and `/LEAGUES/9` forward once each; `/leaguesX`, `/game`, `/game/7/x`, `/drafts-old` are not-found with no forward.
- **Carry format**: non-ASCII and emoji names round-trip; original fragments `#y`, `#a;b;c`, `#bk-carry=zzz` and `#x=1;bk-carry=q` are restored exactly; `history.state` is passed through; empty, non-base64, 7,000-character, `{}` and `null` payloads are dropped, never throw, and are always stripped.
- **Hostile carry**: an entry with `id: "__proto__"`, an HTML name, `settings.__proto__` and extra keys is stored with only the five known fields and cleaned settings; `Object.prototype` is not polluted. The saved list is rendered through React text and attributes only (`StatCalculator.tsx:1079-1112`), and the app has no `dangerouslySetInnerHTML`. A crafted link can plant up to the cap's worth of formulas, only on a browser with none; `expr` and `id` length are bounded only by the cap.

Read:

- No redirect loop between correctly configured sites in any state of the rollout: leagues → `TM/history[/id]` is served by `combined` and redirected in-app by `timemachine`; `timemachine` → league prefixes; `leagues` never forwards those prefixes. Every redirect uses replace, so Back is not trapped.
- No open redirect: the target is always the configured origin plus a pathname that starts with `/`.
- `otherOriginUnsetInDev` returns false whenever `import.meta.env.DEV` is false, which is every `vite build`; it cannot suppress a production redirect.
- React StrictMode runs `CrossSiteRedirect`'s effect twice in dev only: two identical `location.replace` calls. `trackPageView` already dedupes on `signedIn|pathname`.
- `acceptCarry` runs before `BrowserRouter` exists and passes `history.state` through, so the router's first location is the cleaned URL; the path and query are untouched, so the sign-in gate still lands on the page the link named.
- Relative links and `navigate` calls resolve the same under the extra `path="*"` level (the splat's base is `/`).
- `TimeMachineShell` matches AppShell on the tracking effect's position (above the signed-out return), sign-out, skip link and boundary placement. No Time Machine file reads an AppShell context, and every context in `appSlots.tsx` and `leagueDataVersion.tsx` has a non-throwing default.
- Existing analytics events (`sign-in`, `league-added`, `mock-started`) are unchanged; `/` is titled per site; the league host is in `OWN_HOSTS` and `data-domains`.
- JSON-LD escapes `<`; every Time Machine head and body string goes through `esc()`. The dev middleware answers only the exact paths `/robots.txt` and `/sitemap.xml`.
- `App.site.test.tsx` mocks `goTo`, not the component under test, so its redirect cases are real.

## What only a browser can settle

1. Combined build on `origin/main` data: scroll position and focus across navigations, the new-mock modal opened from the top bar, a draft-to-draft switch.
2. Time Machine shell layout: the room's height and scroll inside `.app-main` with no `.context-strip` or `.app-main-head`; the front door scrolling on a phone width; the pre-rendered landing swapping to the React one without a jump.
3. Two real origins (5184 and 5185): a forward with formulas, the address bar after the carry is stripped, the Back button after a forward, and the `#bk-carry` fragment surviving Safari and an in-app browser.
4. The notice for a browser signed in before the switch: seed `bk.user.v1` by hand on 5184, load, dismiss, reload.
5. The predicted CORS 403 on 5184 and 5185 without `draft-sim-api-split`, and what the sign-in screen says when it happens.
6. A signed-out old `/history/<id>` link on the league site: the league landing is in the delivered HTML and shows until React replaces it with the "Taking you to" line. How long that flash is.
7. Production only: Umami accepting the league hostname, link-preview bots rendering `og-image-tm.png`, Railway building each mode from the Docker ARGs.
