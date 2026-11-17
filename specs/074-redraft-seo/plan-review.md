# Adversarial plan review: 074 Redraft SEO

**Date**: 2026-11-17 · **Reviewer**: adversarial plan-review pass (read cold, no 074 code exists)

**Inputs read**: `AGENTS.md`; `specs/074-redraft-seo/` `spec.md`, `plan.md`, `research.md`, `data-model.md`, `contracts/public-api.md`, `contracts/pages.md`, `quickstart.md`, `checklists/requirements.md`; branch `074-redraft-seo` @ `81c780e` (= `origin/main`). Backend: `api/HistoricalGameController`, `api/HistoricalAchievementController`, `api/LeagueLockInterceptor`, `api/ApiTokenFilter`, `api/ErrorHandler`, `config/WebConfig`, `config/HistoricalProperties`, `application.yml`, and in `historical/`: `HistoricalGameService`, `HistoricalGameRepository`, `HistoricalAchievementService`, `HistoricalAchievementRepository`, `HistoricalDailyService`, `HistoricalBoardBuilder`, `HistoricalPlayerCard`, `HistoricalGameState`, `HistoricalResult`, `HistoricalSport`, `NbaHistoricalSport`, `NflHistoricalSport`, `HistoricalRookies`, `HistoricalDataRepository`, `NflHistoricalRepository`, `SeasonReplay`, `Comparisons`; migrations V43, V46, V48, V49. Web: `vite.config.ts`, `Dockerfile`, `serve.json`, `index.html`, `public/boot.js`, `src/landingHtml.ts`, `siteFiles.ts`, `site.ts`, `App.tsx`, `main.tsx`, `user.ts`, `api.ts` (header funnel), `analytics.ts`, `leagueWords.ts`, `historicalShare.ts`, `tmLandingCopy.ts`, `components/TimeMachineShell.tsx`, `components/HistoricalAwardPanel.tsx`, `pages/TimeMachineSignIn.tsx`, `pages/HistoricalDraft.tsx`, and the test outlines of `App.site.test`, `analytics.test`, `landingHtml*.test`, `tmSite.wording.test`, `TimeMachineShell.test`, `siteFiles.test`, `boot.test`. `DEPLOY.md` (lock section).

**Checks I ran** (read-only, scratchpad only; no server was started, nothing in the worktree was written except this file):

- Copied `web/` to the scratchpad, `npm ci`, and ran a real `vite build` (Vite 6.4.3) with probe plugins to see which hooks can see the built `index.html`, and whether `transform` accepts it.
- Ran a probe plugin under `vitest run` to see whether `buildStart` fires there.
- Ran the existing web suite in the scratchpad copy as a baseline: 201 files, 3295 tests, all pass.
- Installed `serve@14.2.6` in the scratchpad (it ships `serve-handler` 6.1.7), read its source, and called the handler function directly with mock request/response objects (no listening socket) against a fake `dist` with the planned config.
- `git ls-tree` over every local and remote branch, plus a filename glob over sibling worktrees, for any `V50+` migration.

**Not run**: any SQL (local Postgres on 5433 is not running, and I did not start it), the backend suite (its Postgres ITs would skip), any Sleeper call (the feature makes none), anything against production.

**VERIFIED** = I ran or read something that shows it. **REASONED** = argument only.

Severity uses the project scale. **CRITICAL** is what the request called BLOCKER.

## Summary

| ID | Sev | Area | One line |
|---|---|---|---|
| PR-01 | CRITICAL | Backend access | `/api/redraft/public/**` is not on the league-lock allowlist: 401 in production, 200 locally |
| PR-02 | HIGH | Vite plugin | The page-emission mechanism cannot work as written: wrong hook, and `transform` throws on the built `index.html` |
| PR-03 | HIGH | CORS | `X-Guest-Id` is not an allowed header: every guest request fails preflight in production |
| PR-04 | HIGH | Deploy | The web build reads the live backend: first deploy, every joint deploy, and a silent old-shape case |
| PR-05 | HIGH | Honesty, NBA | `SeasonPage` has no excluded-stats or coverage field; basketball's caveats live only there |
| PR-06 | HIGH | Honesty, NBA | Basketball "points" are best-single-game-per-week sums, not season fantasy points; the page does not say so |
| PR-07 | HIGH | Product | A shared result of today's daily is the answer key, and its button says "Play today's game" |
| PR-08 | HIGH | SEO | `App.tsx` overwrites `document.title` on every path: all season pages share one rendered title |
| PR-09 | HIGH | Web, guests | A guest has no shell: the draft room's layout, the sign-in entry and the page-view label all assume a user |
| PR-10 | MEDIUM | Serving | `X-Robots-Tag` on `/game/**` cannot be set through `serve` headers (matched on the file, not the URL) |
| PR-11 | MEDIUM | Serving | Dropping `-s` turns on directory listings (`/assets` answers 200 with a file list) |
| PR-12 | MEDIUM | Board | No ranked/unranked marker exists on the card; it cannot be derived for football rookies |
| PR-13 | MEDIUM | Honesty | What "going in", "bust" and "steal" leave out: hand-placed rookies, players who never played, late-round rookies |
| PR-14 | MEDIUM | Dream roster | Greedy is reusable but private; "highest-scoring" is not what a greedy proves; `DreamSlot.slot` has no meaning |
| PR-15 | MEDIUM | Spec vs code | SC-005 / FR-008 cannot be checked as written: the game never reports an overall finish rank |
| PR-16 | MEDIUM | Rule twice | `chosen_season` and "is this season offered" each already have a second implementation in `create(...)` |
| PR-17 | MEDIUM | Achievements | The award route for a chosen-season game answers `settled: false`, which the page shows as a failure |
| PR-18 | MEDIUM | Claim | Concurrent claims can 500; web ordering is unspecified; "075 reuses claim" is not true of the award tables |
| PR-19 | MEDIUM | Performance | Cache fill under `computeIfAbsent` pins carriers; no single-flight; parallel build fetches; no measurement method |
| PR-20 | MEDIUM | Vite plugin | `buildStart` also runs under `vitest` and `vite dev` |
| PR-21 | MEDIUM | Tests | At least seven pinned tests encode "signed out means the front door at every URL"; landing parity has no plan |
| PR-22 | LOW | Doc drift | 82 season pages, not "about 70/75" |
| PR-23 | LOW | Spec vs plan | FR-019's preview "name, season, sport" is not possible from a static host; `/game/**` carries the root canonical |
| PR-24 | LOW | Data model | `PublicGame(state, result)` carries the result twice, and the typed copy breaks old-format games |
| PR-25 | LOW | Contract | Two error shapes exist; `ApiError` keeps no code |
| PR-26 | LOW | Hygiene | `serve.site.json` not ignored; `scripts/` outside `tsconfig`; JSON block needs `<` escaped; soft-200 duplicates |
| PR-27 | LOW | Honesty | A stored guest id next to copy that says "no cookies"; unclaimed guest rows have no retention rule |
| PR-28 | LOW | Analytics | Route templates only: the owed "visits per season page" check cannot be made |
| PR-29 | LOW | Unspecified states | Unoffered season in the browser, midnight on `/daily`, `API_TOKEN`, guest header on other sites, stale pages |

Checked and **OK** (no finding):

- **Migration number.** Highest on this branch and `main` is `V49__time_machine_daily.sql`. No branch (local or remote) and no sibling worktree directory holds a `V50` or higher. VERIFIED. Re-check at merge as the plan says.
- **`HistoricalGameState` carries no owner.** Fields are id, sport, status, season view, shape, slot, picks, available, result, reversal round, daily date (`HistoricalGameState.java:23-27`). `HistoricalResult` and all its nested records carry slots and players only. `state(row)` empties `available` once the game is not DRAFTING (`HistoricalGameService.java:453-456`). VERIFIED.
- **Seed dependence.** The seed reaches the board only at `Collections.shuffle(unranked, new Random(seed))` (`HistoricalBoardBuilder.java:174`). `HistoricalRookies.mark` is unseeded. Bots use the game's seed in `engine.advanceUntilUserOrEnd`, which never touches the board. `scoringFor(season)` is deterministic per season. So a seed-0 board's ranked part equals any game's ranked part, given the same data and config. VERIFIED by reading.
- **"Season value needs no game" is true, and no dearer than one game finish.** `finishDraft` already calls `sport.playedWeeks(season, byId.keySet())` for the WHOLE board (`HistoricalGameService.java:378`), then `scorePlayerWeeks`. A season summary does the same read once. VERIFIED by reading. Row volume is REASONED, not measured (Postgres down): basketball about 1,230 games × about 21 players ≈ 26k `GameLine` rows for a modern season, a few MB live plus about 25 MB of short-lived `statMap` garbage; football about 5 to 6k week rows. Neither threatens `-Xmx1024m` on its own. See PR-19 for what does.
- **Guests cannot have award rows.** `settle` is reached only from `profile()` and `award()` (`HistoricalAchievementService.java:133,156`), both of which the plan answers 403 for a guest. So a claim only has to move `historical_game` rows. VERIFIED by reading.
- **Daily streak ignores chosen-season games.** `completedDailyDates` filters `daily_date is not null` (`HistoricalGameRepository.java:231-234`). VERIFIED.
- **`serve@14.2.6` claims (a), (b), (c) from plan §C.** Ran the handler: `/xyz` → 404 with `404.html`'s body; `/nba` → 200 `nba/index.html`; `/nba/2016-17` → 200 `nba/2016-17.html`, no redirect; `/nba/` → 301 `/nba`; `/nba/2016-17.html` → 301 `/nba/2016-17`; `/nba/2016` → 404; `/game/123`, `/history`, `/history/5` → 200 `index.html`; a single `**` rewrite behaves as `-s` does. `--config ../serve.site.json` resolves against the served directory (`serve/build/main.js` `loadConfiguration`). VERIFIED. The Node fallback server is not needed for these three. Two things the plan did not ask are in PR-10 and PR-11.
- **`Map.of` on the new paths.** The plan uses records and `LinkedHashMap`. `ErrorHandler` builds its bodies with `Map.of` but only from non-null strings. No finding.
- **League words.** The real basketball settings line ("App standard 12-team NBA roster · Legacy Ball Knowers scoring") does not match `LEAGUE_WORDS`. VERIFIED by reading `leagueWords.ts:3`.
- **CSP and the JSON data block.** `script-src` does not govern a non-script `type`; the existing JSON-LD block is the precedent (`landingHtml.ts:74-76`). REASONED.

## Findings

### PR-01 (CRITICAL, VERIFIED) - the public routes are locked in production

**What.** Plan §A: "the league lock does not apply to it (the lock filter's path list is checked in T-verify, since `security/` holds no filter and it lives elsewhere)". It does apply.

**Where.** `config/WebConfig.java:52-54` registers `LeagueLockInterceptor` on `/api/**` minus `PUBLIC_PATHS`. `api/LeagueLockInterceptor.java:44-50` lists `/api/health`, `/api/refresh/daily`, `/api/history/**`, `/api/sleeper/user/**`, `/api/admin/**` and the operator prefix. `/api/redraft/public/**` is not there, so with `LEAGUE_LOCK=true` every call answers `401 {"code":"league_session_required"}`. `DEPLOY.md:381` makes `LEAGUE_LOCK=true` the production state after the split.

**Why it fails.** The lock defaults to off (`application.yml:48`), so every local test, the quickstart and a local build all pass. Production then refuses the season list, every season summary, yesterday's daily and every shared result. The web build fails (PR-04) and no public page works.

**Change.** Either mount the public routes at `/api/history/public/**` (already allowed, and the feature is the same game), or add `/api/redraft/public/**` to `PUBLIC_PATHS` with its one-line reason. Either way add the new mappings to `LeagueLockMappingsIT` (it pins the public list at `:62-71`) and add an MVC test that calls each new route with the lock ON and no session. Correct the sentence in plan §A.

### PR-02 (HIGH, VERIFIED) - the page-emission mechanism cannot work as written

**What.** Plan §B: "In `generateBundle`, for each page emit `file` ... by running the built `index.html` through the same `transform`".

**Evidence (ran, Vite 6.4.3, scratchpad copy of `web/`).**

```
[normal] generateBundle: index.html in bundle = false
[post-html] transformIndexHtml post: hashed script = true
[enforce-post] generateBundle: index.html in bundle = true, has hashed script = true, root empty anchor = false
[enforce-post] second transform THREW: landingHtml: index.html has no empty <div id="root"></div>
```

Two separate failures:

1. A plugin with no `enforce` runs its `generateBundle` before Vite's own HTML plugin emits `index.html`. The existing `landing-html` plugin is such a plugin (`vite.config.ts:23-30`). The file is simply not in the bundle yet.
2. `transform` anchors on the exact string `<div id="root"></div>` and appends before `</head>` (`landingHtml.ts:185-203`). The built `index.html` has already been through it once (`order: 'pre'`), so the root is filled and the head already holds the home page's title, description, canonical, og tags and JSON-LD. A second pass throws; if the anchor check were loosened it would produce two titles and two canonicals.

**Change.** Keep the `pre` hook for what must go through Vite (the `boot.js` tag). Move head and body filling to a post stage that starts from an unfilled template:

- add `transformIndexHtml: { order: 'post', handler }` that stores the incoming HTML (hashed asset tags present, anchors still empty) and returns the home page's fill;
- emit the other pages from that stored template in a `generateBundle` on a plugin with `enforce: 'post'` (verified above to run after the HTML plugin), or write them in `closeBundle` with `fs`;
- give `transform` a page argument (`{ head, body, data }`) so one function fills the home page and every other page from the same template, and keep its throw-on-missing-anchor behaviour.

`serve.site.json` lives outside `outDir`, so it cannot be an `emitFile`; write it in `closeBundle`.

### PR-03 (HIGH, VERIFIED) - `X-Guest-Id` is not in the CORS allow-list

**Where.** `config/WebConfig.java:72-73`: `.allowedHeaders("Authorization", "Content-Type", "X-Sleeper-User", "X-Admin-Token", "X-League-Session")`. The header list is enumerated. `WebConfig.java` is not in the plan's file list, and plan §A says the new surface is "covered by the existing CORS rule".

**Why it fails.** Production is cross-origin (`www.ballknowers.co` → `api.ballknowers.co`). A request carrying `X-Guest-Id` is preflighted, the preflight is refused, and no guest request reaches a controller. `vite dev` proxies `/api` same-origin, so it never shows locally. This is bug class 6 exactly; the comment above that line records the same failure for `X-Sleeper-User`.

**Change.** Add `X-Guest-Id` to `allowedHeaders`. Extend `HistoricalGameMvcIT.aBrowserPreflightPassesForEveryGameRoute` (`:130`) to preflight with `Access-Control-Request-Headers: x-guest-id` for every history route and for `POST /api/history/claim` (which sends both identity headers). In `api.ts`, attach the guest header only on `/api/history/**` requests, so the league site never sends it.

### PR-04 (HIGH, REASONED) - the web build depends on a backend that is usually mid-deploy

**What.** Research R2 states the cost as "first deploy order is backend, then web". That understates it.

**Why it fails.**

1. **First deploy.** Both Railway services build from the same push. The web build starts while the old backend is still serving. It gets 404 (no such route), or 401 if PR-01 is not fixed. The build fails loudly, which is the designed behaviour, and the web deploy is lost until someone redeploys by hand.
2. **Every later push that touches both.** The backend restarts (JVM start plus Flyway) while the web image builds. "3 tries, then throw" with no stated wait is a coin toss. A web-only fix pushed together with any backend change may not ship.
3. **The silent case.** If a push changes the `SeasonPage` shape and the web that renders it, the build can fetch from the OLD backend, which is healthy and answers 200. TypeScript does not check runtime JSON. The site publishes about 82 pages rendered from the old shape, with blanks where new fields should be. This is the "stale type fails silently" rule in `AGENTS.md`, moved to build time.
4. **Cold cache.** The cache is in memory, so each web build after a backend restart recomputes every season on the production JVM (see PR-19 for doing that safely).

**Not checked.** Whether Railway's builder has outbound network to the public API URL (it normally does; private `*.railway.internal` names are not reachable at build time), and whether the web service rebuilds on backend-only pushes.

**Change.**

- Add a small public `GET .../version` (or put a `schemaVersion` integer in the season-list response) and make `fetchSeasons` refuse a backend whose version is not the one this web build expects, with a message that names both numbers.
- Retry with a real budget: poll that version route for several minutes (state the number, label it hand-set), then fetch.
- Validate each summary's required fields at fetch time and fail naming the season and the field (FR-015 asks for this wording).
- Fetch sequentially (PR-19).
- Write the two-step first deploy and "redeploy web after any backend deploy, import or config change that alters boards" into `DEPLOY.md`.
- Consider the alternative R2 rejected, in a safer form: a script that writes the JSON into the repo plus a CI check that the committed file matches the backend. That removes the build-time coupling. This is a choice for Allan only if the mitigations above are judged too fragile.

### PR-05 (HIGH, VERIFIED) - basketball's data caveats never reach the page

**What.** Plan §A lists "Caveats: `sport.dataNotes(season)`, excluded stats, attribution". `data-model.md`'s `SeasonPage` has `dataNotes` and `attribution` and nothing else.

**Where.** `NbaHistoricalSport.java:160`: `dataNotes` returns `List.of()` for every basketball season. Basketball's caveats (steals, blocks, turnovers and threes not tracked in early seasons; partial coverage) are in `SeasonRow.excludedStatsJson` and `statCoverageJson`, which the game shows through `SeasonView.countedStats / excludedStats / statCoverage` (`HistoricalGameService.java:467-474`). Football's `dataNotes` are real (`NflHistoricalSport.java:326-340`), so football pages would look right and basketball pages would carry no caveat at all.

**Why it matters.** A 1972-73 page would rank finishers on a scoring map with several stats silently missing. That is spec US1 scenario 5 and FR-006 unmet, for one sport only. It is also two documents describing one rule differently.

**Change.** Add `List<String> countedStats`, `Map<String,String> excludedStats` and `Map<String,Double> statCoverage` to `SeasonPage` (and its `api.ts` twin), filled the way `state()` fills `SeasonView`, in `StatOrder` order. Require the static page to print them. Add a test on an early basketball season that fails if the page has no excluded-stat line.

### PR-06 (HIGH, VERIFIED) - basketball "points" are not season fantasy points

**Where.** `SeasonReplay.java:127-134`: a player-week score is the MAX over that week's games. `PlayerWeeks.seasonValue` (`:95-100`) sums those. For football that is the season total (one game a week). For basketball it is the sum of each week's best single game, roughly 25 games out of up to 82.

**Why it matters.** The plan ranks "top finishers" and prints `points` from `seasonValue`, and sets `scoringNote = sport.settingsSource()`, which for basketball reads "App standard 12-team NBA roster · Legacy Ball Knowers scoring". Nothing on the page says lock-in. A reader who lands from search on "2016-17 NBA Redraft" assumes a season fantasy total and a season ranking, and will find both "wrong" against any other source: a player with a few huge games outranks a steadier one. This is bug class 5 (a correct statistic that is the wrong thing to display unlabelled) and FR-008 ("the page MUST state the scoring ... they assume").

**Change.** Add a per-sport `formatNote` to `SeasonPage` and print it beside every points column and in the description: basketball "best game of each week, added up (lock-in best ball)", football "weekly full-PPR totals". The game's own sentence is in `pages/HistoricalDraft.tsx:41-52`; move both sentences to one copy module so the page and the game cannot drift. Name the points column for what it is on basketball pages.

### PR-07 (HIGH, REASONED) - a shared daily result gives away the daily, and invites you to play it

**What.** Plan §D makes any `COMPLETE` game readable by anyone, and the shared page's button is "Play today's game". Spec US3 wants a friend to tap a shared result and then play.

**Why it fails.** The game of the day is one season and one seed for everyone (`HistoricalDailyService.java:86-95`). A finished daily's state carries every pick, the standings, and the hindsight roster. Today the share text is deliberately spoiler-free: a score and coloured squares (`historicalShare.ts:45-58`). The plan turns the link under those squares into the answer key for the same puzzle the friend is then invited to play. Daily results count toward streaks, achievements and best-score records. Ids are sequential (research R6), so today's finished dailies can also be found by counting.

The season pages have the same property in a weaker form: `/daily` in a browser names today's season, and that season's page lists the dream roster under the game's exact scoring.

**Change (needs Allan).** Options, cheapest first:

1. For a game whose `daily_date` is today (US Eastern), the public route returns the summary only (season label, finish, draft score, pick squares) and withholds picks, standings and hindsight until the day rolls over. The page says "full result unlocks tomorrow". The owner still sees everything.
2. As 1, but the full result is shown to a caller whose own daily for that sport and day is `COMPLETE`.
3. Accept it and say so on the page.

Whichever is chosen, state in the plan that season pages are an open answer key and that this is accepted (or that daily records are labelled accordingly).

### PR-08 (HIGH, VERIFIED) - one rendered title for every page

**Where.** `App.tsx:340-345`:

```ts
if (site === 'timemachine') document.title = user ? 'Time Machine' : tmLandingTitle
```

It runs on every path. After the app mounts on `/nba/2016-17`, the title becomes the home page's title.

**Why it matters.** Google indexes the rendered page. All 82 season pages, both indexes and `/daily` would render with the same title, which is FR-009 failing for exactly the crawler the feature is for. The plan lists "`App.tsx` titles" under the rename only. `App.site.test.tsx:263-273` pins the current behaviour.

**Change.** Titles per route, from the same `redraftPages.ts` entry that wrote the static `<title>`, so the delivered and rendered titles are equal by construction. Add a test that mounts each indexable route and compares `document.title` with the page list. The `noindex` meta that `/game/:id` adds on mount must be removed on unmount.

### PR-09 (HIGH, VERIFIED) - a guest has no shell

**Where.**

- `components/TimeMachineShell.tsx:32`: `if (!user) return <>{children}</>`. Its own comment (`:12-14`) says the draft room's height (`calc(100vh - 290px)`) and scroll containment depend on `.app-shell > header.topbar + main.app-main#main`. A guest in the room gets none of it.
- `:58`: `AccountMenu` needs `username`, `displayName`, `avatarId`. A guest has no bar, so no way home from the room and no place to sign in from.
- `:28-30` with `analytics.ts:119-125`: a signed-out view is recorded as `/(landing)...` "Landing (from ...)". Every guest draft would be counted as a landing visit.
- `App.tsx:314-322`: `user ? <Routes> : <TimeMachineSignIn />`. The plan addresses this branch but not the shell around it.
- `public/boot.js` hides `.landing-prerender` only for `bk.user.v1`. A guest with a game in progress sees the static landing until React replaces it. Minor, but decide it.

**Change.** The plan lists `TimeMachineShell.tsx` for an aria label only. Specify: the shell always renders the bar and `main`; the right side is `AccountMenu` for a user and a "Sign in" control for a guest; `trackPageView` takes a three-way state (signed in, guest, none) or the route table gains guest labels (and `specs/052`'s data model is amended to say so). Add a room test that renders as a guest and asserts `.app-shell > main.app-main#main`.

### PR-10 (MEDIUM, VERIFIED) - `X-Robots-Tag` cannot be set per URL with `serve`

**What.** Plan §D and `contracts/pages.md` put `X-Robots-Tag: noindex` on `/game/**` and on the forwarded old paths "in `serve.site.json`".

**Evidence.** `serve-handler/src/index.js` `getHeaders` matches `source` against `path.relative(current, absolutePath)`, the file that is served, after rewriting. Ran it with a `/game/**` header rule:

```
{"url":"/game/123","status":200,"sec":"yes","body":"home"}     <- no X-Robots-Tag
```

The served file is `index.html`, which is also the home page, so no header rule can tell them apart.

**Change.** Emit a second shell, `app.html`: the same built template with a static `<meta name="robots" content="noindex">`, a generic title, no canonical and an empty root. Rewrite `/game/**` and the old forwarded paths to `/app.html` instead of `/index.html`. Then a header rule on `/app.html` works too, and crawlers that do not run scripts get the request. Update `contracts/pages.md`.

### PR-11 (MEDIUM, VERIFIED) - directory listings switch on

**Evidence.** With the planned config and no `-s`:

```
{"url":"/assets","status":200,"body":"<!DOCTYPE html><html lang=\"en\"> <head> <"}   <- serve's file listing
```

With `-s` the `**` rewrite hid this. With `"directoryListing": false` added, the same request answers 404 with `404.html`. Also seen: `/index.html` → 301 `/index`, and `/404` → 200 with the not-found body (`/nba/index` will behave the same).

**Change.** Put `"directoryListing": false` in the timemachine config. Add `/assets` (expect 404) to the path table in `contracts/pages.md` and to T-serve. The soft-200 duplicates are covered by canonicals; note them rather than fix them.

### PR-12 (MEDIUM, VERIFIED) - there is no ranked/unranked marker to read

**What.** Plan §A: "the card already says whether it is ranked; the field name is confirmed in T-read". It does not.

**Where.** `HistoricalPlayerCard.java:25-26` has `rank`, `positionalRank`, `prior`, `rookiePick`, `rookie`. `rank` runs 1..N over ranked and unranked alike (`HistoricalBoardBuilder.java:176-195`). A derivation almost works: ranked veterans have `prior.bestOfTwo != null`. It fails for rookies. `rookiePickOf` (`:281-285`) puts the pick on any no-prior-line card whose pick is known, but a football rookie is only *placed* when `rp.round() <= rookieMaxRound` (`:156-157`; `rookie-max-round: 2` in `application.yml:209`). A third-round rookie carries `rookiePick` and sits in the shuffled tail. The card holds the overall pick, not the round.

**Change.** Do not derive. Have the builder report it: a `build` variant returning `(cards, rankedCount)` (the ranked rows are exactly the first `ranked.size()` after `:168`), surfaced through a new `HistoricalSport` method used only by `SeasonSummaryService`. That leaves the frozen card record and old snapshots alone. Then `goingInRank = card.rank()` for the first `rankedCount` cards. Test with a football season that has a round-3 rookie.

### PR-13 (MEDIUM, VERIFIED by reading; the product weight is judgment) - what the three lists leave out

1. **Rookies' going-in ranks are hand-set.** A placed basketball rookie's rank comes from `first-rank: 60, rank-step: 12` (`application.yml:174-178`, labelled arbitrary there); a football rookie's from `rookie-positional-offset`. Research R3's page wording, "our pre-season ranking, built from the season before", is false for them, and also loose for veterans (the newest qualifying line of the last three seasons, `rank-lookback-seasons: 3`). A rookie "steal" is measured against a guess. The page must mark rookie rows ("placed by draft pick") and use `sport.rankingLabel()` verbatim.
2. **The board holds only players who played.** Candidates are players with at least one game that season (`HistoricalDataRepository.java:374-384`, `NflHistoricalRepository.java:166-176`). A star who missed the whole season is not on the page, so the biggest real busts are absent, and "finished 480th" means 480th of those who played. Say so on the page.
3. **The bust list will be an injury list.** The five largest `finishRank − goingInRank` among the top 60 are the players who played a game or two. That is honest only if each row shows weeks played. Add `weeksPlayed` to `Mover` (`PlayerWeeks.weeksPlayed` exists, `SeasonReplay.java:103-106`).
4. **The best-known steals are excluded.** A football rookie drafted in round 3 or later is unranked, so he can never be a steal; he is only a number in `unrankedLeftOut`. Add a short "came from nowhere" list: unranked players who finished in the top `teams × 5`, with finish rank only and no gap.

### PR-14 (MEDIUM, VERIFIED by reading) - the dream roster

- **Reuse.** `Comparisons.greedy` (`:230-266`) works with no draft if called with an empty `takenAt` and the order `1..rounds`: every board player is then a candidate at every step. It is `private` and an instance method, and `SeasonReplay` builds its own `Comparisons` (`SeasonReplay.java:58`). So T-read's answer is: reusable after a visibility change, no fallback needed. Delete the fallback from the plan; it would print a different roster and a different total, chosen silently by an implementation detail.
- **Cost.** Same order as one game's hindsight pass, which every finished game already runs twice. In late rounds the branch-and-bound prunes little (a bench player's marginal gain is small, most of the board's `positiveValue` exceeds it), so expect a few hundred `team.season` trials per late pick. REASONED; measure it in the T-measure task.
- **The label over-claims.** A greedy does not prove "the roster ... that maximises best-ball season points" (plan §A) or "highest-scoring roster that fits the lineup". The game is careful here: `HINDSIGHT_LABEL = "greedy hindsight, bots unchanged"` (`Comparisons.java:33`). Word it as "the best roster a greedy search found" or prove optimality.
- **`DreamSlot.slot` has no meaning.** Lineups are re-seated every week (`TeamScorer.week`). No player has a slot for the season. Drop `slot`, or define it (for example "weeks started") and name it that.

### PR-15 (MEDIUM, VERIFIED) - SC-005 and FR-008 cannot be checked as written

SC-005: "every number on the season page matches the game's own result for that season". The page's main new number is an overall finish rank among all board players. No game reports that:

- `Bust.seasonValueRank` is a rank among the DRAFTED players only (`Comparisons.java:55-57,122`).
- `PickFinish.finishPositionRank` is a rank within a position (`:68-74`).

So the same player can read "finished 150th" on the page and "finished 120th" in a game's bust line. Both are right; a reader sees a contradiction.

Also: plan §A says finish rank follows "the same 'level values share a rank' rule as `Comparisons.valueRanks`". `valueRanks` breaks ties by player id and never shares a rank (`:108-115`). The shared-rank rule is in `pickFinishes` (`:95-97`).

**Change.** Restate SC-005 as what can be compared: per-player `points` against `PickFinish.seasonValue` and `HindsightPick.seasonValue` from a game created after this deploy (an older game's board is a snapshot and may predate the 072 rookie placement), and positional finish rank against `finishPositionRank`. Add `positionFinishRank` to `Finisher`/`Mover` so there is a number that matches. Label the overall rank "among every player who played that season". Fix the citation.

### PR-16 (MEDIUM, VERIFIED by reading) - two rules already have a second implementation

1. **`chosen_season`.** Plan: "A game created with [`seasonStart`] gets `chosen_season = true`". The private `create(...)` already receives a season from two other callers: `createDaily` passes the day's season (`HistoricalGameService.java:123`), and tests and tooling pass `seasonOverride` (`:109-112`). An implementation that infers `chosen = seasonOverride != null` marks every daily game as chosen and removes it from achievements. Make it an explicit required boolean on the private `create` and on `HistoricalGameRepository.create`, with no default, and add to V50: `check (not (chosen_season and daily_date is not null))`.
2. **"Offered".** The override path checks `sport.offeredSeasons(s, s)` (`:139`), documented as looser on purpose "so tooling and tests can use a season outside the real offered range". Football's real range is `props.firstOffered()..lastOffered()` (`NflHistoricalSport.java:99-102`); basketball's is the constants at `HistoricalDataRepository.java:309`. The HTTP route would therefore accept any imported season with prior lines, including one outside the configured range (a part-imported current season is the realistic case). The contract says "400 if not offered". The route must test membership in `sport.offeredSeasons()`, the same list the public season list returns, and a test must post a season that is imported but outside the range.

Related: the guest allowance counts "non-daily games in any status". A guest who abandons the one chosen-season game has spent the allowance. That may be intended; say it in the spec and on the refusal screen.

### PR-17 (MEDIUM, VERIFIED by reading) - the award route for a game that will never settle

Plan: "`settle` skips them". For a chosen-season game `award()` then finds no award row and returns `AwardView(gameId, false, ...)` (`HistoricalAchievementService.java:157-160`). `settled: false` is amendment A3's "could not settle, will retry" state. `HistoricalAwardPanel` is mounted on every results page (`HistoricalRoom.tsx:323`) and also requests the profile. For a guest both calls answer 403 and the panel shows its failure line.

**Change.** Decide the wire answer: add a `reason` to `AwardView` (`CHOSEN_SEASON`), or have the room not mount the panel when `state.chosenSeason` or the viewer is a guest or not the owner, and show one sentence instead ("Chosen-season games do not earn achievements" / "Sign in to earn achievements"). Put the skip in `repo.unsettled` (`where not g.chosen_season`) so `Totals.games` and XP exclude them by construction. Add a test that a chosen-season champion adds nothing to `ProfileView.totals`.

### PR-18 (MEDIUM, REASONED; SQL not run, Postgres was down) - the claim

- **Concurrency.** A single `UPDATE ... WHERE NOT EXISTS (same sport and daily_date under the Sleeper id)` evaluates `NOT EXISTS` on its own snapshot. Two guests claiming into one Sleeper id at once, each holding today's daily, both pass the check; the second hits `historical_game_daily_once` and the route answers 500. The same happens if the Sleeper id starts today's daily in another tab mid-claim. Take the per-user advisory lock first (`HistoricalAchievementRepository.lockUser`, `:45-47`, exists) and catch `DuplicateKeyException` with one retry. Run the statement against a scratch database before building on it (bug class 2).
- **Web ordering.** After sign-in `apiFetch` sends `X-Sleeper-User`, which wins. Any refetch of a guest's open game before the claim lands answers 404. Specify: claim first, await it, then publish the signed-in state; drop the guest id only on a 200. `data-model.md` says "after a successful claim"; plan §D says "calls it once after sign-in, then drops". Make them agree.
- **Left-behind games.** They stay under an id the browser has discarded. A finished one is still publicly readable; a drafting one is unreachable. Say which on the screen.
- **Forward fit.** Research R5: spec 075 "reuses `claim`". Not as built. A guest has no award rows, but a Sleeper identity does: `historical_game_award.sleeper_user_id`, `historical_achievement (sleeper_user_id, code)` as primary key, `historical_profile.sleeper_user_id` (V48). Moving a Sleeper identity's games to an email identity needs a merge with conflict rules. Reword R5 so 075 does not inherit a promise.
- **After a claim.** Claimed finished dailies settle on the next profile read and can raise the streak and unlock badges at once. That reads as intended; state it, and test that a claimed chosen-season game still earns nothing.

### PR-19 (MEDIUM, REASONED) - filling the cache

- `ConcurrentHashMap.computeIfAbsent` runs the function under the bin's monitor. On Java 21 a virtual thread that blocks on JDBC inside a monitor pins its carrier. A handful of concurrent cold season requests can pin every carrier and stall unrelated requests. Without `computeIfAbsent` there is no single-flight, and N callers compute the same season together.
- The plan says "a full build fetches about 70 summaries" and does not say sequentially. Fired in parallel that is 82 season computations (PR-22) against a pool of 10 connections (`application.yml:16`) on the JVM that serves live drafts.
- The route is public and uncached after every restart, so anyone can trigger the same burst.

**Change.** Single-flight with a per-key `CompletableFuture` stored via `putIfAbsent`, computed outside any monitor, on a small bounded executor (one or two threads) so season computations can never crowd out requests. `fetchSeasons` fetches one at a time. Do not cache failures or unknown seasons. Give T-measure a method: the existing log line "historical game {} replayed season {} in {} ms" (`HistoricalGameService.java:398`) is the per-season baseline; record cold time for the earliest, a middle and the latest season of each sport, and heap before and after a full 82-season fill.

### PR-20 (MEDIUM, VERIFIED) - `buildStart` is not only the build

Ran a probe plugin under `vitest run`: `[probe] buildStart CALLED under vitest`. Vite's dev server calls it at start too. A fetch in `buildStart` gated only on `cfg.site === 'timemachine'` makes `vite dev` and any test run with `VITE_SITE=timemachine` need a live backend or throw. Gate on the build command (`apply: 'build'`, or capture `command` in `config`) as well as the site.

### PR-21 (MEDIUM, VERIFIED by reading test names) - tests that pin the behaviour being removed

These encode "signed out means the front door at every URL" or the current names, and are not in the plan:

- `App.site.test.tsx:80,85,96` (the last is spec 073 FR-013: "signed out, a game link shows the front door at the SAME url"), `:105`, `:264`.
- `components/TimeMachineShell.test.tsx:80,95` ("signed out renders only its children").
- `analytics.test.ts:267` (`routesFor('timemachine')` equals exactly `['/', '/game/:gameId', '*']`), `:280-285`, and the `@routes:timemachine` region check at `:200-236`.
- `siteFiles.test.ts:9` ("timemachine name[s] the main address only").
- `landingHtml.sites.test.tsx:51,58` (the static body has a disabled sign-in form; the static body equals the React front door).
- `tmSite.wording.test.tsx:162-221` must grow to the new pages (FR-004).

The parity test is the hard one. The plan makes `/` a data-driven guest home (daily cards, counts). The static body cannot contain today's season, and must not. Define which elements are static and compared (heading, tagline, steps, links to `/daily`, `/nba`, `/nfl`) and which are filled in after mount, or the parity test is either deleted or permanently red. Spec 073's FR-013 is being superseded; say so in the spec with a dated note.

### PR-22 (LOW, VERIFIED) - page counts

Basketball offers 1970 to 2025 (`HistoricalDataRepository.java:309`), 56 seasons; `application.yml:159` says the same. Football offers 2000 to 2025 (`application.yml:210-211`), 26. That is 82 season pages and 87 indexable pages, not "about 70 summaries" and "about 75 static pages". The plan rightly says the real count comes from production; correct the estimates so T-measure budgets for 82.

### PR-23 (LOW, VERIFIED by reading) - what `/game/<id>` can say without a server

FR-019 wants a result page's link preview to give "name, season, sport". A static host serves one file for every game, so the preview cannot name a season. `contracts/pages.md` already says "generic preview tags"; amend FR-019 to match, with a note. Today that one file is `index.html`, whose canonical and `og:url` point at the home page; PR-10's `app.html` fixes that as a side effect.

### PR-24 (LOW, VERIFIED) - `PublicGame`

`HistoricalGameState.result` is already the stored result, passed through as a `JsonNode` so a game finished under an older format returns exactly what was frozen (`HistoricalGameState.java:17-19`). `PublicGame(HistoricalGameState state, HistoricalResult result)` carries it twice, and parsing into the typed record can alter or reject an old-format result (the spec's own edge case). Return the state alone.

### PR-25 (LOW, VERIFIED) - the error shape

Two conventions exist: `{"error": <code>, "message": ...}` (`ErrorHandler.java:35,46`) and `{"code": ..., "message": ...}` (the lock's refusal, `LeagueLockInterceptor.java:88`). The contract picks `code`; record that in the contract rather than leaving it to the build stage. `apiError.ts`'s `ApiError` keeps status and message only, so the web cannot tell `GUEST_LIMIT` from any other 403 until it is extended. Add that to the plan's file list.

### PR-26 (LOW) - hygiene

- `web/serve.site.json` is a generated file beside `dist`; `.gitignore` lists `dist/` only. Ignore it. VERIFIED.
- `web/tsconfig.json` includes `src` only, so `web/scripts/fetchSeasons.ts` is not type-checked by `tsc -b`. Put the pure parts in `src/` (as `site.ts` is) or widen the include. VERIFIED.
- The embedded `redraft-data` block needs the same `<` → `<` escape `jsonLd` uses (`landingHtml.ts:89-90`). REASONED.

### PR-27 (LOW, REASONED) - the guest id

Research R5 rejects a cookie because the site is "cookie-free by stated policy", then stores a persistent id in `localStorage` and sends it on every request. Functionally that is the same thing. The site's own line says "no cookies" (`tmLandingCopy.ts:29-30`). Add one plain sentence where a guest starts playing ("this browser keeps a random id so your games are still here tomorrow"). Also state what happens to `guest:` rows that are never claimed (each holds a full board snapshot); "kept, no cleanup" is an acceptable answer if written down.

### PR-28 (LOW, VERIFIED) - analytics can count templates, not seasons

`analytics.ts:10-15` sends a route template, never the pathname. Research R8's check after two to four weeks, "visits to `/nba/*` and `/nfl/*`", will give one number per sport index and one per `/nba/:season` template, not per season. Either accept that and reword R8, or amend spec 052's data model to allow the season segment (it is not personal data). The new routes need entries in `TIME_MACHINE_ROUTES`.

### PR-29 (LOW) - states the plan does not specify

- **An unoffered season in the browser.** `/nba/1950` matches the React route `/nba/:season`; `404.html` has no embedded data; the fallback fetch answers 404. The page must render not-found, not an empty season page.
- **Midnight on `/daily`.** The spec's edge case. `HistoricalDraft.tsx` has the rollover logic (`:94-104`); the new `DailyPage` needs the same, from one shared hook.
- **The label rule.** Spec: the rule for which year names a basketball season is "stated on the sport index". Not in the plan.
- **`API_TOKEN`.** If it is ever set, `ApiTokenFilter` refuses the build's fetch. `web/Dockerfile` has no `VITE_API_TOKEN` argument, so production presumably runs without it. One line in `DEPLOY.md`.
- **Stale pages.** Static pages change only on a web rebuild. A re-import or a change to any board constant (`historical-rookies`, `rank-*`) changes boards immediately in the game. The spec's assumption covers re-imports; add config changes.

---

## For the parent session

**Counts**: CRITICAL 1 · HIGH 8 · MEDIUM 12 · LOW 8.

**Change what the feature is (Allan decides)**:

- **PR-07**: whether a shared result of today's daily shows its picks, and whether season pages being an answer key for the daily is accepted.
- **PR-13 item 4**: whether a "came from nowhere" list is added, since the plan's steal rule excludes late-round rookies by design.
- **PR-04**: only if the mitigations are not enough, the choice between a build that reads the live backend and committed season data.
- **PR-16, last paragraph**: whether an abandoned chosen-season game spends the guest's one allowance.

Everything else is how it is built and can be folded into the plan and spec with a dated "amended after review" note. PR-01, PR-02 and PR-03 are each a one-line claim in the plan that the code contradicts; fix those three sentences first.

**Could not check**: row counts and timings for a whole-board season read (Postgres on 5433 was not running); the claim statement against a real database; whether Railway's builder reaches the public API; production's actual `LEAGUE_LOCK` and `API_TOKEN` values (read from `DEPLOY.md`, not from the service); how search crawlers treat the pages.
