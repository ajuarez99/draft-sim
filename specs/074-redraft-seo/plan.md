# Implementation Plan: Rename Time Machine to Redraft and make it findable in search

**Branch**: `074-redraft-seo` | **Date**: 2026-11-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/074-redraft-seo/spec.md`

Everything under "read from code" below was read from `origin/main` 81c780e in this worktree. Nothing in this plan has been run yet; each claim about how a tool behaves that was not read from this repo's code is marked **unverified** and has a task to run it.

## Summary

Five pieces, shipped together on the Time Machine build (`VITE_SITE=timemachine`) and the shared backend:

1. **Season data, public.** A new read-only, identity-free backend surface computes one summary per offered season (top finishers, busts, steals, dream roster, caveats) from the same board builder and player-week scoring the game uses, and caches it in memory.
2. **Static pages at build time.** The existing `landingHtml` Vite plugin grows from "one index.html" to "index.html plus one HTML file per indexable page", fetching the season summaries from the backend during `vite build`. A build that cannot get them fails.
3. **Real 404s.** The web container stops running `serve -s`. A per-site serve config generated at build lists which paths are the app (rewritten to `index.html`); everything else falls through to a real `404.html` with status 404.
4. **Guests and chosen seasons.** A browser-kept guest id is accepted where a Sleeper id is today. The server enforces the guest allowance (daily games; one chosen-season game). A new `chosen_season` flag on a game keeps those games out of achievements and records. Signing in moves a guest's games to the Sleeper id.
5. **Rename.** Every reader-facing "Time Machine" becomes "Redraft"; share text links to the game's own address; a finished game is readable by anyone at that address.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5, JdbcClient, virtual threads); TypeScript 5.7, React 18, Vite 6

**Primary Dependencies**: none added. `serve@14.2.6` stays as the static server; its `-s` flag goes.

**Storage**: Postgres. One migration, `V50__redraft_guest_and_chosen_season.sql` (highest on main is V49; no open branch was found holding a V50, re-check at merge).

**Testing**: JUnit 5 (unit + Postgres ITs, which skip silently when Postgres is down: check the skip count); Vitest + Testing Library

**Target Platform**: Railway, two web services built from `web/Dockerfile` with different `VITE_SITE`, one backend service

**Project Type**: web application (backend + web)

**Performance Goals**: season summary computed once per season per JVM and cached; a full build fetches about 70 summaries. First-compute cost per season is **not measured**; T-measure records it before the cache design is called done.

**Constraints**: spec 041's memory budget (Xmx1024m): the cache holds only the small summary records, never boards or stat lines. No league wording on Redraft screens. Paths through `site.ts`. `api.ts` mirrors Java records.

**Scale/Scope**: about 26 NFL seasons and the NBA range (exact counts come from `offeredSeasons()` in prod, not assumed); about 75 static pages.

## Constitution Check

| Rule | Status |
|------|--------|
| Migrations append-only | V50 is new; V43–V49 untouched. |
| `api.ts` mirrors Java records | Every new or changed record below lists its TS twin in data-model.md; same commit. |
| No retuning hand-set constants | None touched. The guest allowance (1 chosen-season game) is a product rule from Allan's answer, kept in `HistoricalProperties` and labelled as that. |
| `Map.of` with nullable values | Season summary and public game payloads are records; any map built for JSON uses `LinkedHashMap`. `attribution` is null for basketball. |
| Ask before committing | Nothing is committed without Allan's go-ahead. |
| Planning doc scope is not a spec | Each "the code already does X" below cites the file; the reviewer checks them cold. |

No violations. Re-checked after Phase 1 design: unchanged.

## What the code gives us (read from code)

- **Board order is deterministic for ranked players.** `HistoricalBoardBuilder.build` ranks from prior seasons; only the unranked tail is shuffled by the game seed (`HistoricalBoardBuilder.java:174`). So "going in" rank is stable per season for ranked players, and not defined for the tail. Season pages use a fixed seed of 0 and leave unranked players out of busts and steals, counted.
- **Season value needs no game.** `SeasonReplay.scorePlayerWeeks(scoring, games)` returns `PlayerWeeks` with `seasonValue(id)` (`SeasonReplay.java:95,127`), fed by `HistoricalSport.playedWeeks(seasonStart, ids)`. `TeamScorer.season(roster, weekCount)` scores a roster best-ball (`:181`).
- **Hindsight as the game computes it needs a draft** (`Comparisons.hindsight` takes the user's pick numbers and the bots' picks). A season page has no draft, so it shows a different thing under a different name: see research R3.
- **Identity is one header, checked in one place per controller**: `HistoricalGameController.requireIdentity` (`:185`), and ownership is `r.sleeperUserId().equals(sleeperUserId)` in `HistoricalGameService.get/lock` (`:179,493`). `HistoricalAchievementController` has its own.
- **A specific season can already be created**: the private `create(...)` takes `seasonOverride` (`HistoricalGameService.java:126`), "for tests and tooling; the HTTP API never passes one".
- **Web identity**: `user.ts` `currentUserId()` feeds `api.ts`'s header funnel. `TimeMachineShell` renders only its children when signed out, and `TimeMachineTree` shows `TimeMachineSignIn` for every path until a user exists (`App.tsx:301-330`).
- **Static HTML**: `landingHtml.ts` `transform(html, cfg)` writes head tags and a static body into `index.html`; `vite.config.ts` emits `robots.txt` and `sitemap.xml` with `emitFile`.
- **Serving**: `web/Dockerfile` ends `serve -s dist --config ../serve.json`; `serve.json` holds only headers, shared by both sites.

## Design

### A. Backend: public season summaries

New `historical/SeasonSummaryService` (pure computation + cache) and `api/RedraftPublicController` at `/api/history/public` (contracts/public-api.md). No identity header read. Covered by the existing CORS rule for the Time Machine origin; the league lock does not apply to it (the lock filter's path list is checked in T-verify, since `security/` holds no filter and it lives elsewhere).

For one season: `scoring = sport.scoringFor(season)`; `board = sport.buildBoard(season, 0L, scoring)`; `pw = replay.scorePlayerWeeks(scoring, sport.playedWeeks(seasonStart, boardIds))`. Then:

- **Top finishers**: board players by `seasonValue` descending, top 12 overall and top 5 per primary position. Ties broken by player id so builds do not flap.
- **Going-in rank**: index in the board among ranked cards only (the card already says whether it is ranked; the field name is confirmed in T-read).
- **Finish rank**: 1 + number of board players with strictly greater season value (the same "level values share a rank" rule as `Comparisons.valueRanks`).
- **Busts**: among players with going-in rank ≤ teams × 5 (the first five rounds), the 5 largest `finishRank − goingInRank`. **Steals**: among players with finish rank ≤ teams × 5, the 5 largest `goingInRank − finishRank`, where an unranked player is not eligible and is counted in `unrankedLeftOut`. Each row carries both ranks and season points. An ordering test pins the direction (bug class 1): a player ranked 2nd going in who finished 150th is a bust and never a steal.
- **Dream roster**: the roster of `rounds` players that maximises best-ball season points, found greedily by the same helper `Comparisons` uses for the hindsight roster where it can be called without a draft; if that helper cannot be separated from pick slots (T-read decides), fall back to top season value per starting slot plus best remaining for the bench. Labelled on the page "highest-scoring roster that fits the lineup, ignoring draft order. Nobody could have drafted all of it." It is **not** called hindsight-best, which the game uses for something else.
- **Caveats**: `sport.dataNotes(season)`, excluded stats, attribution: the same strings the game freezes into a board.

Cache: `ConcurrentHashMap<(sport, seasonStart), SeasonPage>`, filled on demand, cleared by the historical import service on a completed import. No TTL: the data is finished seasons.

Also public: the season list per sport (what `/seasons` returns today minus identity), yesterday's daily season per sport (from `historical_daily`, null when no row), and a finished game by id (section D).

### B. Web: static pages from one page list

New `web/src/redraftPages.ts` (pure, Node-safe like `site.ts`): given the fetched season lists and summaries, returns the **indexable page list**: `{ path, file, title, description, jsonLd, body }` for `/`, `/daily`, `/nba`, `/nfl`, and each season. Titles follow "2016-17 NBA Redraft: Fantasy Basketball Draft Do-Over | Ball Knowers" / "2007 NFL Redraft: …"; each description names that season's top finisher, so no two match (FR-009). The sitemap is built from this list and nothing else (FR-014).

Season addresses use the season label: `/nba/2016-17`, `/nfl/2007`. `/nba/2016` (the bare start year) is not a page.

`vite.config.ts` `landingHtml` plugin, timemachine site only: in `buildStart`, fetch the season data (`web/scripts/fetchSeasons.ts`: `VITE_API_BASE`, 3 tries, then throw with the URL and status). `REDRAFT_SEASON_DATA=<file>` reads a JSON fixture instead, for tests and for building without a backend; the build log says which source was used. In `generateBundle`, for each page emit `file` (e.g. `nba/2016-17.html`, `nba/index.html`, `daily.html`, `404.html`) by running the built `index.html` through the same `transform` with that page's head and static body. Each season file also embeds its summary as `<script type="application/json" id="redraft-data">` so the React page renders without a fetch. (The CSP is report-only and JSON data blocks are not script execution; **unverified** that no report fires, checked in live verification.)

React routes added to `TimeMachineTree`: `/daily`, `/nba`, `/nfl`, `/nba/:season`, `/nfl/:season`. They render for guests and signed-in players alike. Season pages read the embedded JSON, and fall back to the public endpoint on client-side navigation. Outcome sections are `<details>` elements, closed by default, under "Spoilers: how the season played out" (content is in the HTML either way, FR-011a).

The combined and leagues builds are untouched: no season pages, same sitemap rules as today.

### C. Web serving: real 404s

`serve -s` is replaced by `serve dist --config ../serve.site.json`. The Vite plugin writes `serve.site.json` next to `dist` (not inside it) from `serve.json`'s headers plus, per site:

- **timemachine**: `cleanUrls: true`, `trailingSlash: false`, and `rewrites` to `/app-shell.html` for the app-only paths: `/game/**`, and the old-path forwards `/history`, `/history/**`, `/leagues/**`, `/drafts/**`, `/mock/**`, `/managers/**`, `/admin/**`. Static pages are files. Anything else is not found, and `404.html` in `dist` is served with status 404.
- **leagues / combined**: `rewrites: [{ source: "**", destination: "/index.html" }]`, which is what `-s` does.

**Measured 2026-11-17** (research R4, amended): all three behaviours this depends on hold on `serve@14.2.6` with `trailingSlash: false`. *Amended after measuring:* a `headers` rule matches the rewritten file, not the requested URL, so `X-Robots-Tag` on `/game/**` does nothing. The app-only paths are therefore rewritten to `app-shell.html`, a byte copy of `index.html` emitted by the build, and the `noindex` header sits on that file. `404.html` carries a `noindex` meta tag because `/404` itself answers 200. The Node-server fallback is dropped.

The Dockerfile copies `serve.site.json` from the build stage. Railway's health check stays `/`.

### D. Guests, chosen seasons, shared results

**Identity.** New request header `X-Guest-Id` (a UUID the browser makes and keeps in `localStorage` under `bk.guest.v1`). One resolver, `historical/Identity.resolve(sleeperUser, guestId)`, used by both historical controllers: a Sleeper id wins; else a well-formed UUID becomes the owner key `guest:<uuid>`; else 401. A `X-Sleeper-User` value starting with `guest:` is rejected (400), so the two namespaces cannot collide. The owner key is stored in the existing `historical_game.sleeper_user_id` text column: no column rename (the name becomes slightly wrong; a comment in V50 says so).

**Allowance, enforced in `HistoricalGameService`** (FR-021d):

| Action | Guest | Signed in |
|---|---|---|
| Game of the day (`POST /daily`) | yes | yes |
| Chosen season (`POST /games` with `seasonStart`) | yes while this guest has 0 non-daily games in any status | yes |
| Random season (`POST /games` without) | 403 `GUEST_LIMIT` | yes |
| Achievements, profile | 403 `GUEST_LIMIT` | yes |

`CreateRequest` gains nullable `seasonStart`. A game created with it gets `chosen_season = true`.

**V50**: `alter table historical_game add column chosen_season boolean not null default false;`

**Chosen-season games stay out of records** (FR-011): `HistoricalAchievementService.settle` skips them; `GameSummary` and `HistoricalGameState` gain `chosenSeason`; web `landingStats` ignores them; share text says "chosen season". The daily streak already ignores them (no `daily_date`).

**Claim on sign-in**: `POST /api/history/claim` with both headers moves this guest's games to the Sleeper id in one statement, skipping any daily game whose `(user, sport, daily_date)` the Sleeper id already holds (the unique index). Returns `{ moved, leftBehind }`. The web calls it once after sign-in, then drops the guest id. Left-behind games stay under the dead guest id; the response says how many, and the screen says so if it is not zero. *(This narrows spec FR-021c: "no game lost" cannot hold when both identities played the same daily. Spec amended, see below.)*

**Shared results**: `GET /api/history/public/games/{id}` returns a `COMPLETE` game's state and result with **no owner field at all**, 404 for anything else (drafting, abandoned, missing: indistinguishable). `/game/:id` in the web: owner sees today's page; anyone else sees the read-only result with "Play today's game". `noindex` is set by a `<meta name="robots">` the page adds on mount, plus `X-Robots-Tag: noindex` on `/game/**` in `serve.site.json` (which covers crawlers that do not run scripts).

Game ids are sequential, so finished games are readable by anyone who counts, not only by people sent a link. They carry no owner. This is stated here rather than hidden; an unguessable share key is the alternative and is not built (research R6).

### E. Rename

Reader-facing strings only; file, class, table, CSS-class and route-helper names keep "historical"/"tm"/"TimeMachine" (renaming them is churn with no reader). Touched: `tmLandingCopy.ts`, `historicalCopy.ts`, `historicalShare.ts`, `siteCopy.ts`, `TimeMachineShell.tsx` aria label, `App.tsx` titles, `AccountMenu`, `NotFound`, `PageHeader`, `HistoricalRoomLayout`, `HistoricalProfileCard`, `analytics.ts` labels, and `og-image-tm.svg` re-rendered to PNG by `web/scripts/og-image/render.mjs`. A new test greps the built timemachine `dist` and the copy modules for "Time Machine" (SC-004). The front door becomes the home page for everyone: guests see the daily cards and "Pick a season"; the username field moves to a "Sign in to keep your history" panel (FR-021e).

## Spec amendments this plan forces (dated 2026-11-17, made in spec.md with notes)

1. US3 scenario 1 / FR-017: "owner shown by display name only" → no owner shown. The server holds no display name, and a guest has none.
2. FR-021c: games move on sign-in, except a daily game the signed-in identity already played that day, which is left behind and counted.
3. FR-006 "hindsight-best roster" → "dream roster", defined above, because the game's hindsight roster depends on a specific draft.

## Project Structure

### Documentation (this feature)

```text
specs/074-redraft-seo/
├── plan.md, research.md, data-model.md, quickstart.md
├── contracts/public-api.md, contracts/pages.md
├── plan-review.md        (adversarial review, next stage)
└── tasks.md              (/speckit-tasks)
```

### Source Code

```text
backend/src/main/java/com/ballknowers/draftsim/
├── historical/SeasonSummaryService.java     new
├── historical/SeasonPage.java               new record
├── historical/Identity.java                 new
├── historical/HistoricalGameService.java    seasonStart, allowance, claim, publicResult
├── historical/HistoricalGameRepository.java chosen_season, claim, guest counts
├── historical/HistoricalAchievementService.java  skip chosen
├── api/RedraftPublicController.java         new
├── api/HistoricalGameController.java        Identity, seasonStart, claim
└── api/HistoricalAchievementController.java Identity, guest 403
backend/src/main/resources/db/migration/V50__redraft_guest_and_chosen_season.sql

web/
├── vite.config.ts            page emission, serve.site.json
├── Dockerfile                serve without -s
├── scripts/fetchSeasons.ts   new
├── scripts/og-image/og-image-tm.svg
└── src/
    ├── redraftPages.ts, redraftPages.test.ts          new
    ├── seasonPageHtml.ts                              new (static bodies)
    ├── guest.ts                                       new
    ├── api.ts                                         new types, X-Guest-Id
    ├── siteFiles.ts                                   sitemap from page list
    ├── pages/SeasonPage.tsx, SportIndex.tsx, DailyPage.tsx, SharedResult.tsx   new
    ├── pages/HistoricalDraft.tsx, TimeMachineSignIn.tsx   guest home
    └── (rename touches listed in E)
```

**Structure Decision**: existing backend + web layout; no new module.

## Acceptance for the verification stage

Run against a real backend with real season data and the real container command, not `vite dev`:

1. Every path class answers the status in contracts/pages.md (curl, no scripts), for the timemachine build and the leagues build.
2. Three seasons per sport: every number on the static page equals what a game of that season reports (SC-005), checked by playing or by direct query.
3. Guest flow in a private window: daily game start to finish, one chosen-season game, the refusal after it (also by direct API call), sign-in, games present once.
4. A finished game opened in another private window shows the result with no owner; a drafting game shows not-found.
5. `grep -ri "time machine" dist/` is empty.
6. What was not run is listed in verification.md in those words.

## Complexity Tracking

None: no constitution violation to justify.

## Amended after review (2026-11-17)

The adversarial review is [plan-review.md](./plan-review.md): 29 findings, 1 critical, 8 high. The designer's proposal is [design.md](./design.md). Each finding is taken below; where a line above disagrees with this section, this section wins. Three sentences of the original plan were wrong against the code (PR-01, PR-02, PR-03) and would have passed locally and failed only in production or at build.

### Wrong in the original, now corrected

- **PR-01 (critical). The public routes would have been locked in production.** `LeagueLockInterceptor.PUBLIC_PATHS` (`api/LeagueLockInterceptor.java:44-50`) does not include `/api/redraft/public/**`, and the lock is on in production and off locally. The routes now live under `/api/history/public/**`, which the lock already lets through, and `LeagueLockMappingsIT` gains a case for each new route with the lock on.
- **PR-02. Page emission as written cannot work** (the reviewer ran a real build): a normal-order plugin's `generateBundle` does not see `index.html`, and `transform` throws on an already-filled file. Corrected mechanism: `transform(html, cfg, page)` takes the page to write; a `transformIndexHtml` hook with `order: 'post'` captures the built, still-unfilled template (hashed asset tags present, `#root` empty); pages are emitted from `closeBundle` by filling that template once per page. `index.html` itself is filled the same way, so there is one code path.
- **PR-03. `X-Guest-Id` was not in the CORS allow-list** (`config/WebConfig.java:72-73` enumerates headers). `WebConfig.java` is added to the change list and the preflight test at `HistoricalGameMvcIT:130` gains the new header.
- **PR-10 (and the serve experiment). `X-Robots-Tag` cannot be put on `/game/**`** through serve headers. App-only paths rewrite to `app-shell.html`, which carries a static `<meta name="robots" content="noindex">` in its head (written at build, so it does not depend on a header at all).
- **PR-12. The card has no ranked/unranked marker**, and it cannot be derived for football. `HistoricalBoardBuilder` returns the ranked count alongside the cards (a small result record; existing callers take `.cards()`).
- **PR-15. SC-005 could not be checked as written**: no game reports an overall finish rank, and `Comparisons.valueRanks` does not implement the shared-rank rule the plan attributed to it. The season page's rank rule is defined in `SeasonSummaryService` and pinned by its own tests; SC-005 is amended in the spec to compare **season points per player** (which a game does report, in pick finishes and lineups) and positional finish rank, not overall rank.
- **PR-22.** 82 season pages (56 NBA + 26 NFL), per the reviewer's read of the configured ranges. Still to be confirmed from production's offered list at first build.

### Design changes

- **PR-04. Build reads a backend that may be mid-deploy.** Kept (research R2), with guards: the season-list response carries `schema: 1`; `fetchSeasons` fails if the field is absent or different (this is what stops a build silently publishing from an older backend's shape), validates every field the pages read, fetches sequentially, and retries each request for up to 3 minutes before failing. DEPLOY.md gains: backend first on the first deploy; a web build that fails on this step is safe to re-run; the previous web deploy keeps serving meanwhile.
- **PR-05, PR-06, design findings 1 to 5. `SeasonPage` changes** (data-model.md amended): adds `excludedStats`, `statCoverage`, `boardPlayers`, `rankedPlayers`, `pointsNote`; rows carry `positions` (list) and `weeksPlayed`; `topByPosition` is keyed by base positions; `unrankedLeftOut` counts unranked players finishing in the top teams × 5. `pointsNote` is a per-sport sentence saying what "points" are. For basketball that is the sum of each week's best single game (`SeasonReplay.java:127-134`), which is **not** a season fantasy total, and the page says so beside the first number that uses it, not only in a fold.
- **PR-13. Honesty of going-in, busts and steals.** The page states: rookies' pre-season ranks come from their draft pick, not from stats; the board holds only players who appeared in the season, so a player who missed the whole year is not listed as a bust; each bust row shows weeks played, so an injury reads as an injury. A "came from nowhere" list for unranked players is **not built**; the unranked count line stands in for it.
- **PR-14. Dream roster.** Uses `Comparisons.greedy` with no picks taken (made package-visible); no second fallback algorithm. Copy changes from "highest-scoring" to "a greedy pick of the best-scoring roster that fits the lineup": greedy is not proven optimal and the page does not claim it. Rows are players with season points and no slot label (weekly best ball has no fixed slot); `DreamSlot.slot` is dropped. The total is the best-ball team total, labelled as in design.md.
- **PR-16. Chosen season is explicit.** `chosen_season` is a parameter of the private `create(...)`, set only by the HTTP path that received `seasonStart`; `createDaily` and test overrides pass false. The offered check for a chosen season is `sport.findSeason` **and** within `firstOffered..lastOffered`. An abandoned chosen-season game still spends the guest's one allowance (session's call: otherwise abandon-and-retry beside an answer key is unlimited; Allan can overrule).
- **PR-07. A shared result must not be today's answer key.** The game of the day is the same season and seed for everyone. The public game route returns a **reduced** view while the game's `daily_date` is today in the daily's own time zone: finish, record, draft score and pick squares only, with no picks, lineups, hindsight or callouts, and the page says the rest shows tomorrow. From the next day it returns the full result. Season pages being an answer key for a chosen season is what Allan chose in Q2 (folded spoilers, no achievements).
- **PR-08. Titles after React mounts.** `App.tsx:340-345` sets one title for every path. Each indexable page now sets its own `document.title` from the same `redraftPages` function the static head uses, and a test asserts static and mounted titles agree for a season page.
- **PR-09, design findings 6 and 7. Guests get the shell.** `TimeMachineShell` always renders the bar and `main`; a guest sees "Sign in". Page views for guests are recorded under the route, not as "Landing". `SignInForm` gains `autoFocus` (default true).
- **PR-11.** `serve.site.json` sets `"directoryListing": false`.
- **PR-17.** The award panel is not mounted for a guest or for a chosen-season game; the award route answers a chosen-season game with an explicit `notCounted: true`, which the page shows as "Seasons you pick don't count toward achievements", not as a failure.
- **PR-18. Claim.** One transaction: lock the guest's rows (`for update`), move those that do not collide, count the rest. A unique-index race is caught and answered as a normal result, never 500. The web calls claim before any refetch after sign-in. The sentence "075 reuses claim" is withdrawn: the V48 achievement tables are keyed by user and 075 will need its own move.
- **PR-19. Cache.** No `computeIfAbsent` around JDBC. A per-key lock gives single flight; the build fetches sequentially. T-measure records cold time and heap for the largest NBA season before the cache is called done; if a whole-build warm-up is unacceptable, summaries are computed on a schedule instead and the finding is reopened.
- **PR-20.** The fetch is gated on `command === 'build'` and the timemachine site; `vitest` and `vite dev` never call the backend (dev serves the fixture or nothing).
- **PR-21. Pinned tests that encode "signed out means the front door at every URL"** (at least seven, including spec 073 FR-013) are rewritten to the new rule, each named in tasks.md, not deleted. The static-versus-React parity test covers the static parts of each page; data-driven parts (daily cards, your games) are placeholders in static HTML and are excluded from the comparison by an explicit marker, not by loosening the test.
- **PR-23.** FR-019 amended in the spec: a static host cannot put a game's season in its link preview. Shared games carry the site's generic preview.
- **PR-24.** `PublicGame` is `(HistoricalGameState state)` only: the state already carries the result, and old-format results pass through as stored.
- **PR-25.** `GUEST_LIMIT` rides the existing error body; `ApiError` in `api.ts` gains `code`.
- **PR-26.** `serve.site.json` is gitignored; `web/scripts/fetchSeasons.ts` is added to a tsconfig; the embedded JSON escapes `<` as `<`.
- **PR-27.** The landing's "no cookies" line stays true but gains "we keep a random id in this browser so your games stay yours". Unclaimed guest games: no retention rule in this spec; stated as open in HANDOFF.
- **PR-28.** Analytics sends route templates, so per-season visits cannot be counted. The owed check in research R8 is corrected to "visits to the season-page route as a whole".
- **PR-29.** Unoffered season on client navigation renders the not-found body; `/daily` at midnight follows design.md; the label rule is the sport's own `label()`; a stale static site after an import or config change is fixed by redeploying web, and DEPLOY.md says so.

### Decisions left with Allan (none blocks the build; each has a default above)

1. PR-07: today's daily shared result is reduced until the next day.
2. PR-16: an abandoned chosen-season game spends the guest's allowance.
3. PR-13: no "came from nowhere" list.
4. Design: third-person voice on shared results.

## Data model, amended after review (2026-11-17)

`SeasonPage` as built (supersedes data-model.md where they differ; data-model.md carries the same note):

```text
SeasonPage(sport, seasonStart, label, slug, prevSlug?, nextSlug?, teams, rounds, rosterPositions,
           scoringNote, pointsNote, rankingLabel, dataNotes, excludedStats, statCoverage, attribution?,
           boardPlayers, rankedPlayers,
           topOverall[12], topByPosition{base position -> [5]}, busts[<=5], steals[<=5], unrankedLeftOut,
           dreamRoster[rounds], dreamRosterPoints)
Finisher(playerId, name, positions[], team?, finishRank, points, weeksPlayed, goingInRank?)
Mover(playerId, name, positions[], team?, goingInRank, finishRank, points, weeksPlayed)
DreamPlayer(playerId, name, positions[], points)
SeasonList(schema, sport, seasons[SeasonRef])          // schema = 1
PublicGame(state, reducedUntil?)                       // reducedUntil: ISO instant when the full result shows; null when full
AwardView + notCounted
```
